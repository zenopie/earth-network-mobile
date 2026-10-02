import Foundation

/// What the wallet does on its own while it is unlocked (it needs the keys,
/// so never in the background). Ports `privacy/PrivacyAutomation.kt`:
///
///  - claims the day's ANML, at a random time of day chosen afresh each day,
///    so a claim's timing says nothing about who made it;
///  - refreshes the caretaker split before it lapses (it counts for R after
///    each cast);
///  - refreshes the referrer binding the same way;
///  - claims unbonding notes once their epoch's undelegation has matured.
///
/// Maturity is worked out from chain-wide timing alone (the current epoch,
/// epoch length, x/staking's unbonding time), never by asking the node about
/// a (validator, epoch) record: a query for the records this wallet holds,
/// repeated every pass, would tell the node which unbond notes are whose long
/// before any claim. If the record is not matured after all (its epoch's
/// undelegation was deferred), the claim fails before anything is spent and
/// waits `retrySeconds`.
///
/// `decide` is the pure part, unit-tested; `runOnce` runs it.
public enum PrivacyAutomation {
    /// Claims land within this many seconds after UTC midnight plus the day's draw.
    public static let claimWindow: Int64 = 12 * 3600
    /// Slack past the computed completion for the block that completes it.
    public static let maturityMargin: Int64 = 15 * 60
    /// How long a claim the chain refused waits before trying again.
    public static let retrySeconds: Int64 = 6 * 3600
    /// How often the app runs a pass while unlocked.
    public static let interval: Duration = .seconds(10 * 60)

    public enum Action: Equatable {
        case claimAnml(day: UInt64)
        case refreshCaretaker
        case refreshReferrer
        case claimUnbonding(OwnedNote)
    }

    public struct Inputs {
        public var now: Int64
        public var identityLive: Bool
        public var claimOpensAt: Int64?
        public var claimedToday: Bool
        /// Seconds after UTC midnight today's claim waits for.
        public var claimOffset: Int64
        public var caretakerDue: Bool
        public var referrerDue: Bool = false
        public var hasFeeErth: Bool
        public var maturedUnbonds: [OwnedNote]

        public init(now: Int64, identityLive: Bool, claimOpensAt: Int64?, claimedToday: Bool, claimOffset: Int64,
                    caretakerDue: Bool, referrerDue: Bool = false, hasFeeErth: Bool, maturedUnbonds: [OwnedNote]) {
            self.now = now; self.identityLive = identityLive; self.claimOpensAt = claimOpensAt; self.claimedToday = claimedToday
            self.claimOffset = claimOffset; self.caretakerDue = caretakerDue; self.referrerDue = referrerDue
            self.hasFeeErth = hasFeeErth; self.maturedUnbonds = maturedUnbonds
        }
    }

    public static func decide(_ i: Inputs) -> [Action] {
        var out: [Action] = []
        let day = i.now / PrivacyWallet.secondsPerDay
        if i.identityLive, i.hasFeeErth, !i.claimedToday, i.claimOpensAt == 0,
           i.now - day * PrivacyWallet.secondsPerDay >= i.claimOffset {
            out.append(.claimAnml(day: UInt64(day)))
        }
        if i.identityLive, i.hasFeeErth, i.caretakerDue { out.append(.refreshCaretaker) }
        if i.identityLive, i.hasFeeErth, i.referrerDue { out.append(.refreshReferrer) }
        // Fee from output: needs no fee note.
        out += i.maturedUnbonds.map { .claimUnbonding($0) }
        return out
    }

    /// The latest moment epoch `e`'s undelegation can complete. Epoch e ends
    /// in the block that starts e+1, and each epoch lasts at least
    /// `epochSeconds`, so end(e) <= start(current) - (current - 1 - e) x
    /// epochSeconds; the SDK entry completes unbondingSeconds after that. Nil
    /// while e has not ended.
    public static func maturesBy(_ e: UInt64, current: UInt64, currentStart: Int64, epochSeconds: Int64, unbondingSeconds: Int64) -> Int64? {
        guard e < current else { return nil }
        return currentStart - Int64(current - 1 - e) * epochSeconds + unbondingSeconds + maturityMargin
    }

    /// The unbond notes to claim now: matured by `maturesBy`, and not waiting out a refused claim.
    public static func matured(_ notes: [OwnedNote], now: Int64, current: UInt64, currentStart: Int64, epochSeconds: Int64,
                               unbondingSeconds: Int64, retryAt: [String: Int64]) -> [OwnedNote] {
        notes.filter { n in
            guard n.unspent, n.pendingAt == nil, n.note.denom.hasPrefix("unbond/"),
                  let (_, e) = try? PrivacyWallet.parseUnbond(n.note.denom),
                  let by = maturesBy(e, current: current, currentStart: currentStart, epochSeconds: epochSeconds, unbondingSeconds: unbondingSeconds)
            else { return false }
            return now >= by && now >= (retryAt[n.note.denom] ?? 0)
        }
    }

    private static let lock = NSLock()
    nonisolated(unsafe) private static var offsetDay: Int64 = -1
    nonisolated(unsafe) private static var offset: Int64 = 0

    /// Today's random claim offset, drawn once per UTC day.
    public static func claimOffset(now: Int64) -> Int64 {
        lock.lock(); defer { lock.unlock() }
        let day = now / PrivacyWallet.secondsPerDay
        if day != offsetDay {
            offsetDay = day
            offset = Int64.random(in: 0 ..< claimWindow)
        }
        return offset
    }

    /// One pass: sync, then whatever `decide` says. Failures are logged by the
    /// caller's `onFailure`; a refused unbonding claim waits `retrySeconds`.
    public static func runOnce(
        wallet: PrivacyWallet,
        queries: PrivacyQueries,
        now: Int64 = Int64(Date().timeIntervalSince1970),
        onFailure: (Action, Swift.Error) -> Void = { _, _ in }
    ) async throws {
        try await wallet.sync()
        // Global reads only: the same for every wallet.
        let epoch = try await queries.epoch()
        let timing = try await queries.stakingTiming()
        let snap = wallet.snapshot
        let mature = matured(snap.notes, now: now, current: epoch.number, currentStart: epoch.startTime,
                             epochSeconds: timing.epochSeconds, unbondingSeconds: timing.unbondingSeconds, retryAt: snap.unbondRetryAt)
        let inputs = Inputs(
            now: now,
            identityLive: snap.identityStatus == .live,
            claimOpensAt: wallet.claimOpensAt(),
            claimedToday: wallet.claimedToday(),
            claimOffset: claimOffset(now: now),
            caretakerDue: (try? await wallet.caretakerDue()) ?? false,
            referrerDue: (try? await wallet.referrerDue()) ?? false,
            hasFeeErth: (snap.balances["uerth"] ?? 0) > 0,
            maturedUnbonds: mature
        )
        for a in decide(inputs) {
            do {
                switch a {
                case let .claimAnml(day): _ = try await wallet.claimAnml(day: day)
                case .refreshCaretaker: _ = try await wallet.setCaretaker(split: wallet.snapshot.caretakerSplit)
                case .refreshReferrer: _ = try await wallet.bindReferrer(address: wallet.snapshot.referrerAddress)
                case let .claimUnbonding(n): _ = try await wallet.claimUnbonding(note: n)
                }
            } catch {
                onFailure(a, error)
                if case let .claimUnbonding(n) = a { await wallet.deferUnbondClaim(denom: n.note.denom, until: now + retrySeconds) }
            }
        }
    }
}
