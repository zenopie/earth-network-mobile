import Foundation

/// Casts a stake vote on a proposal the one way the app does (K5): every
/// eligible derth note pair and every position that may vote, in a shuffled
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
/// already voted are not voted twice, notes already voted are spent.
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

    /// `onProgress` hears every change (on no particular thread); `pause`
    /// waits between casts (milliseconds; tests pass a recorder).
    public init(wallet: @escaping @Sendable () throws -> PrivacyWallet,
                pause: @escaping @Sendable (UInt64) async throws -> Void = { try await Task.sleep(nanoseconds: $0 * 1_000_000) },
                onProgress: @escaping @Sendable (Progress?) -> Void = { _ in }) {
        self.wallet = wallet; self.pause = pause; self.onProgress = onProgress
    }

    public var progress: Progress? { lock.lock(); defer { lock.unlock() }; return progressValue }

    public var isRunning: Bool { lock.lock(); defer { lock.unlock() }; return task != nil && progressValue?.running == true }

    private func set(_ p: Progress?) {
        lock.lock(); progressValue = p; lock.unlock()
        onProgress(p)
    }

    /// Plans and starts a run on `proposalID`, returning once the first cast
    /// is broadcast (its hash); the rest goes on in the background.
    public func startAndAwaitFirst(proposalID: UInt64, options: [WeightedVoteOption]) async throws -> String {
        guard !isRunning else { throw PrivacyError("a stake vote is already running") }
        let w = try wallet()
        let total = try await w.stakeVoteItems(proposalID: proposalID).count
        guard total > 0 else { throw PrivacyError("no stake from before this proposal's voting opened") }
        await w.setStakeVoteRun(StakeVoteRun(proposalID: proposalID, options: options.map { .init(option: $0.option, weight: $0.weight) },
                                             votedPositions: [], total: total))
        return try await withCheckedThrowingContinuation { (c: CheckedContinuation<String, Error>) in
            launch(first: c, resumed: false)
        }
    }

    /// Picks up a persisted run the app lost (call on unlock).
    public func resume() {
        guard !isRunning, let w = try? wallet(), let run = w.store.state.stakeVoteRun else { return }
        set(Progress(proposalID: run.proposalID, done: run.done, total: run.total))
        launch(first: nil, resumed: true)
    }

    /// Stops the run now (between casts, or before the next one starts).
    public func cancel() {
        lock.lock(); let t = task; lock.unlock()
        t?.cancel()
    }

    /// Waits for the current run to end (tests).
    public func wait() async {
        lock.lock(); let t = task; lock.unlock()
        await t?.value
    }

    private func launch(first: CheckedContinuation<String, Error>?, resumed: Bool) {
        let t = Task { [self] in
            var pendingFirst = first
            func fail(_ e: Error) { pendingFirst?.resume(throwing: e); pendingFirst = nil }
            guard let w = try? wallet(), var run = w.store.state.stakeVoteRun else {
                fail(PrivacyError("no stake vote to run")); return
            }
            do {
                let options = run.options.map { WeightedVoteOption(option: $0.option, weight: $0.weight) }
                let items = try await w.stakeVoteItems(proposalID: run.proposalID).filter {
                    if case let .position(id, _) = $0 { return !run.votedPositions.contains(id) }
                    return true
                }.shuffled()
                set(Progress(proposalID: run.proposalID, done: run.done, total: run.total))
                for (i, item) in items.enumerated() {
                    if i > 0 || resumed {
                        let ms = UInt64.random(in: Self.votePauseMinMs ... Self.votePauseMaxMs)
                        set(Progress(proposalID: run.proposalID, done: run.done, total: run.total, nextAt: Date().addingTimeInterval(Double(ms) / 1000)))
                        try await pause(ms)
                        try Task.checkCancellation()
                        try await w.sync()
                    }
                    try Task.checkCancellation()
                    // As the last sync left it: a note voted (or pending) or a position gone is skipped.
                    guard let r = try await w.castStakeVote(proposalID: run.proposalID, item: item, options: options) else { continue }
                    run.done += 1
                    if case let .position(id, _) = item { run.votedPositions.insert(id) }
                    await w.setStakeVoteRun(run)
                    set(Progress(proposalID: run.proposalID, done: run.done, total: run.total))
                    pendingFirst?.resume(returning: r.hash); pendingFirst = nil
                }
                fail(PrivacyError("nothing left to vote with"))
                set(Progress(proposalID: run.proposalID, done: run.done, total: run.total, finished: true))
            } catch is CancellationError {
                set(Progress(proposalID: run.proposalID, done: run.done, total: run.total, cancelled: true))
                fail(CancellationError())
            } catch {
                set(Progress(proposalID: run.proposalID, done: run.done, total: run.total, error: error.localizedDescription))
                fail(error)
            }
            // Finished, cancelled or failed: nothing to resume.
            await w.clearStakeVoteRun(proposalID: run.proposalID)
            lock.lock(); task = nil; lock.unlock()
        }
        lock.lock(); task = t; lock.unlock()
    }
}

extension PrivacyWallet {
    /// Persists the stake vote being cast (K5).
    func setStakeVoteRun(_ run: StakeVoteRun) async {
        await lockedNoThrow { store.mutate { $0.stakeVoteRun = run }; store.save() }
    }

    func clearStakeVoteRun(proposalID: UInt64) async {
        await lockedNoThrow {
            if store.state.stakeVoteRun?.proposalID == proposalID { store.mutate { $0.stakeVoteRun = nil }; store.save() }
        }
    }
}
