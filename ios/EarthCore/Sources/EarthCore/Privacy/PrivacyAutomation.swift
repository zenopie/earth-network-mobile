import Foundation

/// The one thing the wallet does on its own while it is unlocked (it needs
/// the keys, so never in the background). Ports
/// `privacy/PrivacyAutomation.kt`: it completes an undelegation the user
/// started, claiming the unbonding claim (stake note) once its epoch's
/// undelegation has matured. Its fee comes out of what it claims.
///
/// Nothing else spends a fee unasked: the day's ANML claim, the caretaker
/// vote's refresh and the handle's renewal are the user's to make, and
/// `Reminders` says when each is due.
///
/// Maturity is worked out from chain-wide timing alone (the current epoch,
/// epoch length, x/staking's unbonding time), never by asking the node about
/// a (validator, epoch) record: a query for the records this wallet holds,
/// repeated every pass, would tell the node which unbond notes are whose long
/// before any claim. If the record is not matured after all (its epoch's
/// undelegation was deferred), the claim fails before anything is spent and
/// waits `retrySeconds`.
///
/// Claims are never made in one burst (audit 3): one at a time, chosen at
/// random among those due, with a random pause and a full sync between each
/// and a fresh decision after it. Logs name the kind of action only (`kind`),
/// never a denom.
///
/// `decide` is the pure part, unit-tested; `runPass` runs one pass; `runOnce` runs it on a wallet.
public enum PrivacyAutomation {
    /// Slack past the computed completion for the block that completes it.
    public static let maturityMargin: Int64 = 15 * 60
    /// How long a claim the chain refused waits before trying again.
    public static let retrySeconds: Int64 = 6 * 3600
    /// How often the app runs a pass while unlocked.
    public static let interval: Duration = .seconds(10 * 60)

    /// The only automatic action: completing an undelegation the user started.
    public enum Action: Equatable {
        case claimUnbonding(denom: String)
    }

    public struct Inputs {
        public var now: Int64
        /// unbond/<valoper>/<epoch> denoms whose claims have matured.
        public var maturedUnbonds: [String]

        public init(now: Int64, maturedUnbonds: [String]) {
            self.now = now; self.maturedUnbonds = maturedUnbonds
        }
    }

    // Fee from output: needs no fee note.
    public static func decide(_ i: Inputs) -> [Action] { i.maturedUnbonds.map { .claimUnbonding(denom: $0) } }

    /// The latest moment epoch `e`'s undelegation can complete. Epoch e ends
    /// in the block that starts e+1, and each epoch lasts at least
    /// `epochSeconds`, so end(e) <= start(current) - (current - 1 - e) x
    /// epochSeconds; the SDK entry completes unbondingSeconds after that. Nil
    /// while e has not ended.
    public static func maturesBy(_ e: UInt64, current: UInt64, currentStart: Int64, epochSeconds: Int64, unbondingSeconds: Int64) -> Int64? {
        guard e < current else { return nil }
        // Chain-supplied numbers: any overflow means no answer, never a trap (audit 3).
        guard let n = Int64(exactly: current - 1 - e) else { return nil }
        let (span, o1) = n.multipliedReportingOverflow(by: epochSeconds)
        let (a, o2) = currentStart.subtractingReportingOverflow(span)
        let (b, o3) = a.addingReportingOverflow(unbondingSeconds)
        let (c, o4) = b.addingReportingOverflow(maturityMargin)
        return o1 || o2 || o3 || o4 ? nil : c
    }

    /// The unbond denoms to claim now: matured by `maturesBy`, and not waiting out a refused claim.
    public static func matured(_ notes: [OwnedStakeNote], now: Int64, current: UInt64, currentStart: Int64, epochSeconds: Int64,
                               unbondingSeconds: Int64, retryAt: [String: Int64]) -> [String] {
        var out: [String] = []
        for n in notes {
            guard n.spendable, n.denom.hasPrefix(PrivacyWallet.unbondPrefix),
                  let (_, e) = try? PrivacyWallet.parseUnbond(n.denom),
                  let by = maturesBy(e, current: current, currentStart: currentStart, epochSeconds: epochSeconds, unbondingSeconds: unbondingSeconds),
                  now >= by, now >= (retryAt[n.denom] ?? 0), !out.contains(n.denom)
            else { continue }
            out.append(n.denom)
        }
        return out
    }

    /// The random pause between two automated actions in one pass, milliseconds.
    public static let actionPauseMinMs: UInt64 = 30_000
    public static let actionPauseMaxMs: UInt64 = 180_000

    /// The kind of `a`, for logs: never its denom (as Android's class names).
    public static func kind(_ a: Action) -> String {
        switch a {
        case .claimUnbonding: "ClaimUnbonding"
        }
    }

    /// One pass (audit 3): a `sync`, then while anything is due, one action
    /// chosen at random among those due (`inputs` read afresh each time),
    /// and before the next a random pause and a full sync. Never two actions
    /// in one burst; a pause and a sync too when another was due when the
    /// last was chosen (it may wait for the last one's change to land). Each
    /// action is tried at most once a pass. Returns the actions taken.
    @discardableResult
    public static func runPass(
        sync: () async throws -> Void,
        inputs: () async throws -> Inputs,
        act: (Action) async throws -> Void,
        pause: (UInt64) async throws -> Void,
        onFailure: (Action, Swift.Error) async -> Void = { _, _ in },
        pick: (Int) -> Int = { Int.random(in: 0 ..< $0) },
        pauseMs: () -> UInt64 = { UInt64.random(in: actionPauseMinMs ... actionPauseMaxMs) }
    ) async throws -> [Action] {
        try await sync()
        var attempted: [Action] = []
        var acted = false
        var othersDue = false
        while true {
            let due = decide(try await inputs()).filter { !attempted.contains($0) }
            if acted {
                if due.isEmpty && !othersDue { return attempted }
                try await pause(pauseMs())
                try await sync()
                acted = false
                continue
            }
            if due.isEmpty { return attempted }
            let a = due[pick(due.count)]
            attempted.append(a)
            acted = true
            othersDue = due.count > 1
            do {
                try await act(a)
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                await onFailure(a, error)
            }
        }
    }

    /// One pass on `wallet` (see `runPass`). Failures go to `onFailure` (its
    /// kind only is fit for a log); a refused unbonding claim waits
    /// `retrySeconds`.
    public static func runOnce(
        wallet: PrivacyWallet,
        queries: PrivacyQueries,
        clock: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970) },
        pause: (UInt64) async throws -> Void = { try await Task.sleep(nanoseconds: $0 * 1_000_000) },
        onFailure: (Action, Swift.Error) -> Void = { _, _ in }
    ) async throws {
        // Global reads only: the same for every wallet.
        let epoch = try await queries.epoch()
        let timing = try await queries.stakingTiming()
        try await runPass(
            sync: { try await wallet.sync() },
            inputs: {
                let now = clock()
                let snap = wallet.snapshot
                return Inputs(
                    now: now,
                    maturedUnbonds: matured(snap.stakeNotes, now: now, current: epoch.number, currentStart: epoch.startTime,
                                            epochSeconds: timing.epochSeconds, unbondingSeconds: timing.unbondingSeconds, retryAt: snap.unbondRetryAt)
                )
            },
            act: { a in
                switch a {
                case let .claimUnbonding(denom): _ = try await wallet.claimUnbonding(denom: denom)
                }
            },
            pause: pause,
            onFailure: { a, error in
                onFailure(a, error)
                if case let .claimUnbonding(denom) = a { await wallet.deferUnbondClaim(denom: denom, until: clock() + retrySeconds) }
            }
        )
    }
}
