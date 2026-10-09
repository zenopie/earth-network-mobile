import EarthCore
import Foundation
import Observation

/// The staking timing the Stake screen shows: the unbonding period. Every
/// read is chain-wide and the same for every wallet.
@MainActor @Observable
final class StakeRoundModel {
    private(set) var unbondingSeconds: Int64?

    func load(rest: EarthRest) async {
        if unbondingSeconds == nil, let t = try? await PrivacyQueries(rest: rest).stakingTiming() { unbondingSeconds = t.unbondingSeconds }
    }

    /// A payout or window date: "Oct 28, 6:00 AM".
    static func day(_ unix: Int64) -> String {
        Date(timeIntervalSince1970: TimeInterval(unix)).formatted(.dateTime.month(.abbreviated).day().hour().minute())
    }
}
