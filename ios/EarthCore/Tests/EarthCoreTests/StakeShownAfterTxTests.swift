import Foundation
import XCTest
@testable import EarthCore

/// What the Stake screen shows between a tx landing and the sync that sees
/// it: stake a delegation merges stays counted (never zero), and stake an
/// unstake spends does not (it is shown as unbonding from the send).
final class StakeShownAfterTxTests: PrivacyTestCase {
    let vA = "earthvaloper1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"

    func shown(_ a: PrivacyWallet) -> UInt64 {
        StakeRound.lines(notes: a.snapshot.stakeNotes, rate: { _ in 1 }, vintages: a.snapshot.vintages,
                         leaving: Set(a.pendingUnbonds.map { $0.txHash.lowercased() }))
            .map(\.value).reduce(0, +)
    }

    func testStakeStaysShownUntilTheSyncThatSeesTheTx() async throws {
        let chain = FakeChain()
        chain.vintageBase = 202609
        let a = try wallet(chain, nil)
        for _ in 0 ..< 8 { try funded(chain, a, 3_000_000) }
        try await a.sync()
        let r1 = try await a.delegate(validator: vA, amount: 1_000_000)
        let caught1 = await a.catchUp(height: r1.height, timeout: .seconds(5))
        XCTAssertTrue(caught1)
        let first = shown(a)
        XCTAssertGreaterThan(first, 0)

        // A second delegation merges the first note: it stays counted until the merged note arrives.
        let r2 = try await a.delegate(validator: vA, amount: 1_000_000)
        XCTAssertEqual(first, shown(a))
        let caught2 = await a.catchUp(height: r2.height, timeout: .seconds(5))
        XCTAssertTrue(caught2)
        let both = shown(a)
        XCTAssertGreaterThan(both, first)

        // An unstake of all of it: gone from stake at once, not counted with its unbonding.
        let all = a.stakeNotes.filter(\.spendable).map(\.amount).reduce(0, +)
        _ = try await a.undelegate(validator: vA, amount: all)
        XCTAssertFalse(a.pendingUnbonds.isEmpty)
        XCTAssertEqual(0, shown(a))
    }
}
