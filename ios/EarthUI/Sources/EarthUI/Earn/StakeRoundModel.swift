import EarthCore
import Foundation
import Observation

/// The chain's daily round (x/shieldedstaking's epoch) as the Stake screen
/// shows it: when it ends, the block that ended the last one (what tells
/// stake still queued for its validator from stake already joined), and the
/// unbonding period. Every read is chain-wide and the same for every wallet.
@MainActor @Observable
final class StakeRoundModel {
    private(set) var epoch: PrivacyReads.Epoch?
    /// The block that ended the last round; nil until found (or if the node cannot say).
    private(set) var startHeight: UInt64?
    private(set) var unbondingSeconds: Int64?

    /// Per round, for the session: found once, never re-asked.
    private static var heights: [String: UInt64] = [:]

    func load(rest: EarthRest) async {
        let q = PrivacyQueries(rest: rest)
        if unbondingSeconds == nil, let t = try? await q.stakingTiming() { unbondingSeconds = t.unbondingSeconds }
        guard let e = try? await q.epoch() else { return }
        epoch = e
        let key = "\(rest.lcd.absoluteString)#\(e.number)@\(e.startTime)"
        if let h = Self.heights[key] { startHeight = h; return }
        startHeight = nil
        let roots = LCDChainRoots(rest: rest)
        guard let tip = await roots.latestBlock(), let t = tip.time else { return }
        let h = await StakeRound.firstHeight(atOrAfter: e.startTime, tip: tip.height, tipTime: Int64(clamping: t)) { h in
            await roots.blockTime(h).map { Int64(clamping: $0) }
        }
        if let h {
            Self.heights[key] = h
            startHeight = h
        }
    }

    /// When the round ends, as "6:00 AM" (today or tomorrow) or "Tue 6:00 AM".
    static func clock(_ unix: Int64) -> String {
        let d = Date(timeIntervalSince1970: TimeInterval(unix))
        let cal = Calendar.current
        let time = d.formatted(date: .omitted, time: .shortened)
        if cal.isDateInToday(d) || cal.isDateInTomorrow(d) { return time }
        return d.formatted(.dateTime.weekday(.abbreviated)) + " " + time
    }

    /// A payout or window date: "Oct 28, 6:00 AM".
    static func day(_ unix: Int64) -> String {
        Date(timeIntervalSince1970: TimeInterval(unix)).formatted(.dateTime.month(.abbreviated).day().hour().minute())
    }
}
