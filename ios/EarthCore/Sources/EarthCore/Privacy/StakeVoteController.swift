import Foundation

/// Casts a stake vote on a proposal the one way the app does (K5): every
/// eligible derth note (one vote proof each, nothing spent: ORCHARD_DESIGN
/// 15) and every position that may vote, in a shuffled
/// order, one at a time, with a full sync and a random 20-120 s pause between
/// casts, so a vote's fee never spends the previous vote's change unseen and
/// the casts are not one burst that times them together. Ports
/// `privacy/StakeVoteController.kt`.
///
/// Runs in its own task, off any screen's lifetime and never holding the
/// wallet lock while it waits; `progress` says how far it got and when the
/// next cast is due, `cancel` stops it at once. The plan is persisted
/// (PrivacyState.stakeVoteRun) so a run the app lost (killed while in the
/// background) is picked up by `resume` on the next unlock; positions
/// already voted are not voted twice, notes already voted are known by their
/// recorded vote nullifiers (PrivacyState.stakeVotes) and skipped. A note
/// votes once per proposal and on every open proposal.
public final class StakeVoteController: @unchecked Sendable {
    public struct Progress: Sendable, Equatable {
        public let proposalID: UInt64
        public let done: Int
        public let total: Int
        /// When the next cast is due, while waiting.
        public var nextAt: Date?
        public var finished = false
        public var cancelled = false
        public var error: String?
        public var running: Bool { !finished && !cancelled && error == nil }
    }

    /// The random pause between casts, milliseconds.
    public static let votePauseMinMs: UInt64 = 20_000
    public static let votePauseMaxMs: UInt64 = 120_000

    private let wallet: @Sendable () throws -> PrivacyWallet
    private let pause: @Sendable (UInt64) async throws -> Void
    private let onProgress: @Sendable (Progress?) -> Void
    private let lock = NSLock()
    private var task: Task<Void, Never>?
    private var progressValue: Progress?
    private var keepRun = false
    /// Set from the moment a start is accepted until its task exists (audit 3: no double start).
    private var starting = false
    /// Bumped by every `suspend` (audit 4, M4): a start or
    /// resume in flight, and every step of a running task, checks under the
    /// lock that its session is still this one; a lock, wallet switch or
    /// forget that lands at any moment stops it, and it reports nothing more.
    private var session: UInt64 = 0
    /// The last suspended task: its cast may still be finishing. A resumed or
    /// new run waits for it before casting (audit 4, L5).
    private var previous: Task<Void, Never>?

    /// `onProgress` hears every change (on no particular thread); `pause`
    /// waits between casts (milliseconds; tests pass a recorder).
    public init(wallet: @escaping @Sendable () throws -> PrivacyWallet,
                pause: @escaping @Sendable (UInt64) async throws -> Void = { try await Task.sleep(nanoseconds: $0 * 1_000_000) },
                onProgress: @escaping @Sendable (Progress?) -> Void = { _ in }) {
        self.wallet = wallet; self.pause = pause; self.onProgress = onProgress
    }

    public var progress: Progress? { lock.lock(); defer { lock.unlock() }; return progressValue }

    public var isRunning: Bool { lock.lock(); defer { lock.unlock() }; return task != nil && progressValue?.running == true }

    /// Shows `p`, only while `id`'s session is current: a suspended run says
    /// nothing more on screen (it may be another wallet's by now).
    private func set(_ p: Progress?, session id: UInt64) {
        lock.lock()
        guard session == id else { lock.unlock(); return }
        progressValue = p
        lock.unlock()
        onProgress(p)
    }

    private func current(_ id: UInt64) -> Bool { lock.lock(); defer { lock.unlock() }; return session == id }

    /// Throws when `id`'s session ended (a suspend or cancel landed).
    private func checkSession(_ id: UInt64) throws { if !current(id) { throw CancellationError() } }

    /// Claims the right to start a run: its session, nil while one runs or is starting.
    private func claimStart() -> UInt64? {
        lock.lock(); defer { lock.unlock() }
        if starting || task != nil { return nil }
        starting = true
        return session
    }

    private func releaseStart() { lock.lock(); starting = false; lock.unlock() }

    /// Plans and starts a run on `proposalID`, returning once the first cast
    /// is broadcast (its hash); the rest goes on in the background.
    public func startAndAwaitFirst(proposalID: UInt64, options: [WeightedVoteOption]) async throws -> String {
        guard let id = claimStart() else { throw PrivacyError("a stake vote is already running") }
        let w: PrivacyWallet
        do {
            w = try wallet()
            let total = try await w.stakeVoteItems(proposalID: proposalID).count
            guard total > 0 else { throw PrivacyError("no stake from before this proposal's voting opened") }
            try checkSession(id)
            guard await w.startStakeVoteRun(StakeVoteRun(proposalID: proposalID, options: options.map { .init(option: $0.option, weight: $0.weight) },
                                                         votedPositions: [], total: total)) else {
                throw PrivacyError("a stake vote is already running")
            }
        } catch {
            releaseStart()
            throw error
        }
        // The first cast is what the confirm sheet showed a fee for.
        let shown = PrivacyWallet.shownFee
        return try await withCheckedThrowingContinuation { (c: CheckedContinuation<String, Error>) in
            launch(w, session: id, first: c, resumed: false, shownFee: shown)
        }
    }

    /// Picks up a persisted run the app lost (call on unlock): the selected
    /// wallet's own, read from its store under its lock.
    public func resume() async {
        guard let id = claimStart() else { return }
        guard let w = try? wallet(), let run = await w.currentStakeVoteRun(), current(id) else { releaseStart(); return }
        set(Progress(proposalID: run.proposalID, done: run.done, total: run.total), session: id)
        launch(w, session: id, first: nil, resumed: true)
    }

    /// Stops the run now (between casts, or before the next one starts).
    public func cancel() {
        lock.lock(); keepRun = false; let t = task; lock.unlock()
        t?.cancel()
    }

    /// Stops the run but keeps it persisted, for `resume` on the next unlock
    /// (the wallet locks: the keys go, the vote is not abandoned). Takes
    /// effect at once, a start or resume in flight included (audit 4, M4):
    /// nothing more is cast or shown by the run it stops.
    public func suspend() {
        lock.lock()
        keepRun = true
        session &+= 1
        let t = task
        if let t { previous = t }
        progressValue = nil
        lock.unlock()
        t?.cancel()
        onProgress(nil)
    }

    /// Waits for the current run to end (tests).
    public func wait() async {
        await currentTask()?.value
    }

    private func currentTask() -> Task<Void, Never>? { lock.lock(); defer { lock.unlock() }; return task ?? previous }

    private func takeKeepRun(cancelled: Bool) -> Bool {
        lock.lock(); defer { lock.unlock() }
        let keep = keepRun && cancelled
        keepRun = false
        return keep
    }

    /// The run whose task is (or is about to be) `task`.
    private var runID: UUID?

    private func clearTask(_ id: UUID) {
        lock.lock(); defer { lock.unlock() }
        guard runID == id else { return }
        task = nil; runID = nil; starting = false
    }

    /// Runs the persisted run of `w` (the wallet the caller resolved: never re-resolved inside the task) in session `sid`.
    private func launch(_ w: PrivacyWallet, session sid: UInt64, first: CheckedContinuation<String, Error>?, resumed: Bool, shownFee: UInt64? = nil) {
        let id = UUID()
        lock.lock()
        // A suspend that landed while this start or resume was in flight: nothing launches (the run stays persisted).
        guard session == sid else {
            starting = false
            lock.unlock()
            first?.resume(throwing: CancellationError())
            return
        }
        runID = id
        let before = previous
        lock.unlock()
        // Detached from the caller's task locals: only the first cast is bound by the sheet's fee.
        let t = Task.detached { [self] in
            var pendingFirst = first
            func fail(_ e: Error) { pendingFirst?.resume(throwing: e); pendingFirst = nil }
            // The last suspended run's cast may still be finishing: never cast the same item twice at once.
            await before?.value
            guard current(sid), var run = await w.currentStakeVoteRun() else {
                fail(current(sid) ? PrivacyError("no stake vote to run") : CancellationError()); clearTask(id); return
            }
            do {
                let options = run.options.map { WeightedVoteOption(option: $0.option, weight: $0.weight) }
                let items = try await w.stakeVoteItems(proposalID: run.proposalID).filter {
                    if case let .position(id, _) = $0 { return !run.votedPositions.contains(id) }
                    return true
                }.shuffled()
                set(Progress(proposalID: run.proposalID, done: run.done, total: run.total), session: sid)
                for (i, item) in items.enumerated() {
                    try checkSession(sid)
                    if i > 0 || resumed {
                        let ms = UInt64.random(in: Self.votePauseMinMs ... Self.votePauseMaxMs)
                        set(Progress(proposalID: run.proposalID, done: run.done, total: run.total, nextAt: Date().addingTimeInterval(Double(ms) / 1000)),
                            session: sid)
                        try await pause(ms)
                        try Task.checkCancellation()
                        try checkSession(sid)
                        try await w.sync()
                    }
                    try Task.checkCancellation()
                    try checkSession(sid)
                    // The run is still this one (a chain switch drops it: audit 4, L2).
                    guard let stored = await w.currentStakeVoteRun(), stored.proposalID == run.proposalID else { throw CancellationError() }
                    // Audit 6 (M8): the positions voted as the store says now,
                    // not as this task read them: another task (a suspended
                    // cast that finished) may have voted one since.
                    if case let .position(pid, _) = item, stored.votedPositions.contains(pid) { continue }
                    // A position's vote is persisted the moment the node takes it
                    // (audit 4, L5), before the wait for its block: a run resumed
                    // after a suspend or a lost app never votes it again.
                    let accepted: (String) -> Void = { _ in
                        if case let .position(pid, _) = item { w.markRunPositionVotedLocked(proposalID: run.proposalID, positionID: pid) }
                    }
                    // As the last sync left it: a note voted (or pending) or a position gone is skipped.
                    let cast: TxResult?
                    if pendingFirst != nil, let shownFee {
                        cast = try await PrivacyWallet.$shownFee.withValue(shownFee) {
                            try await w.castStakeVote(proposalID: run.proposalID, item: item, options: options, accepted: accepted)
                        }
                    } else {
                        cast = try await w.castStakeVote(proposalID: run.proposalID, item: item, options: options, accepted: accepted)
                    }
                    guard let r = cast else { continue }
                    // Merged into the run as stored (audit 6, M8), never a stale copy written over it.
                    var voted: UInt64?
                    if case let .position(pid, _) = item { voted = pid }
                    run = await w.advanceStakeVoteRun(run, positionID: voted)
                    set(Progress(proposalID: run.proposalID, done: run.done, total: run.total), session: sid)
                    pendingFirst?.resume(returning: r.hash); pendingFirst = nil
                }
                fail(PrivacyError("nothing left to vote with"))
                set(Progress(proposalID: run.proposalID, done: run.done, total: run.total, finished: true), session: sid)
            } catch is CancellationError {
                set(Progress(proposalID: run.proposalID, done: run.done, total: run.total, cancelled: true), session: sid)
                fail(CancellationError())
            } catch {
                set(Progress(proposalID: run.proposalID, done: run.done, total: run.total, error: error.localizedDescription), session: sid)
                fail(error)
            }
            // Finished, cancelled or failed: nothing to resume (a suspended run is kept).
            let keep = takeKeepRun(cancelled: Task.isCancelled || !current(sid))
            if !keep { await w.clearStakeVoteRun(proposalID: run.proposalID) }
            clearTask(id)
        }
        // A run that already ended (cleared its id) is not recorded as running.
        lock.lock(); if runID == id { task = t; starting = false }; lock.unlock()
    }
}

extension PrivacyWallet {
    /// Persists the stake vote being cast (K5).
    func setStakeVoteRun(_ run: StakeVoteRun) async {
        await lockedNoThrow { store.mutate { $0.stakeVoteRun = run }; persistNoThrow() }
    }

    /// Persists a new run unless one is already persisted (audit 3: no double start).
    func startStakeVoteRun(_ run: StakeVoteRun) async -> Bool {
        var ok = false
        await lockedNoThrow {
            guard store.state.stakeVoteRun == nil else { return }
            store.mutate { $0.stakeVoteRun = run }
            persistNoThrow()
            ok = true
        }
        return ok
    }

    /// The persisted run, read under the wallet's lock.
    func currentStakeVoteRun() async -> StakeVoteRun? {
        var r: StakeVoteRun?
        await lockedNoThrow { r = store.state.stakeVoteRun }
        return r
    }

    /// One more cast of `run` (and `positionID` voted), merged into the run as
    /// stored under the wallet's lock (audit 6, M8); returns the run now.
    func advanceStakeVoteRun(_ run: StakeVoteRun, positionID: UInt64?) async -> StakeVoteRun {
        var out = run
        await lockedNoThrow {
            var cur = store.state.stakeVoteRun.flatMap { $0.proposalID == run.proposalID ? $0 : nil } ?? run
            cur.done += 1
            if let positionID { cur.votedPositions.insert(positionID) }
            store.mutate { $0.stakeVoteRun = cur }
            persistNoThrow()
            out = cur
        }
        return out
    }

    /// Records a position voted in the persisted run, called from inside the
    /// cast (the wallet's lock already held) the moment the node takes it.
    func markRunPositionVotedLocked(proposalID: UInt64, positionID: UInt64) {
        guard store.state.stakeVoteRun?.proposalID == proposalID else { return }
        store.mutate { _ = $0.stakeVoteRun?.votedPositions.insert(positionID) }
        persistNoThrow()
    }

    func clearStakeVoteRun(proposalID: UInt64) async {
        await lockedNoThrow {
            if store.state.stakeVoteRun?.proposalID == proposalID { store.mutate { $0.stakeVoteRun = nil }; persistNoThrow() }
        }
    }
}
