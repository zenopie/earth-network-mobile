import XCTest
@testable import EarthCore

final class StakeRoundTests: XCTestCase {
    private func note(_ pos: UInt64, _ height: UInt64, _ amount: UInt64, spent: UInt64? = nil, label: StakeLabel? = nil, denom: String = "derth/v") -> OwnedStakeNote {
        OwnedStakeNote(position: pos, height: height, denom: denom, amount: amount, rho: Fr(pos), rcm: .one, cm: Fr(pos), nf: Fr(pos + 1000),
                       spentHeight: spent, label: label)
    }

    func testJoiningCountsDelegationsAfterTheRoundOnly() {
        // 100 before the round ended at 50; +40 at 60; +10 at 70.
        let ns = [note(1, 40, 100, spent: 60), note(2, 60, 140, spent: 70), note(3, 70, 150)]
        XCTAssertEqual(StakeRound.joining(ns, denom: "derth/v", after: 50), 50)
        XCTAssertEqual(StakeRound.joining(ns, denom: "derth/v", after: 60), 10)
        XCTAssertEqual(StakeRound.joining(ns, denom: "derth/v", after: 70), 0)
        XCTAssertEqual(StakeRound.joining(ns, denom: "derth/w", after: 0), 0)
        // A first delegation: everything joins.
        XCTAssertEqual(StakeRound.joining([note(1, 60, 30)], denom: "derth/v", after: 50), 30)
    }

    func testUnstakeMergeAndMoveInAreNotJoining() {
        // Unstake 30 after the round: nothing joins.
        XCTAssertEqual(StakeRound.joining([note(1, 40, 100, spent: 60), note(2, 60, 70)], denom: "derth/v", after: 50), 0)
        // A merge of two notes.
        XCTAssertEqual(StakeRound.joining([note(1, 40, 10, spent: 60), note(2, 45, 20, spent: 60), note(3, 60, 30)], denom: "derth/v", after: 50), 0)
        // A move arriving: a new label.
        let l = StakeLabel(moveKey: Fr(UInt64(77)), moveTime: 1_000, exposed: 25)
        XCTAssertEqual(StakeRound.joining([note(1, 40, 10, spent: 60), note(2, 60, 35, label: l)], denom: "derth/v", after: 50), 0)
        // A delegation on top of a labelled note keeps the label: it joins.
        XCTAssertEqual(StakeRound.joining([note(1, 40, 35, spent: 60, label: l), note(2, 60, 45, label: l)], denom: "derth/v", after: 50), 10)
        // Staked then unstaked more than that: at most what is held.
        XCTAssertEqual(StakeRound.joining([note(1, 60, 50, spent: 70), note(2, 70, 5)], denom: "derth/v", after: 50), 5)
    }

    /// A synthetic chain: block h at 1_000 + 6h, with jitter.
    private func time(_ h: UInt64) -> Int64 { 1_000 + Int64(h) * 6 + Int64(h % 3) }

    func testFirstHeightFindsTheRoundsBlock() async {
        let tip: UInt64 = 20_000
        for target: UInt64 in [1, 2, 777, 14_400, 19_999, 20_000] {
            let t = time(target)
            var asked = 0
            let h = await StakeRound.firstHeight(atOrAfter: t, tip: tip, tipTime: time(tip)) { asked += 1; return self.time($0) }
            XCTAssertEqual(h, target, "target \(target)")
            XCTAssertLessThanOrEqual(asked, 40)
        }
        // A time between blocks: the next block.
        let h = await StakeRound.firstHeight(atOrAfter: time(500) + 1, tip: tip, tipTime: time(tip)) { self.time($0) }
        XCTAssertEqual(h, 501)
        // Unanswered probes, or a time past the tip.
        let none = await StakeRound.firstHeight(atOrAfter: time(500), tip: tip, tipTime: time(tip)) { _ in nil }
        XCTAssertNil(none)
        let future = await StakeRound.firstHeight(atOrAfter: time(tip) + 1, tip: tip, tipTime: time(tip)) { self.time($0) }
        XCTAssertNil(future)
    }

    func testCountdown() {
        XCTAssertEqual(StakeRound.countdown(0), "now")
        XCTAssertEqual(StakeRound.countdown(30), "under a minute")
        XCTAssertEqual(StakeRound.countdown(45 * 60), "45m")
        XCTAssertEqual(StakeRound.countdown(20 * 3600 + 15 * 60), "20h 15m")
        XCTAssertEqual(StakeRound.countdown(3 * 3600), "3h")
        XCTAssertEqual(StakeRound.countdown(5 * 86_400), "5 days")
    }

    func testStanding() {
        func q(_ status: String, jailed: Bool = false, tomb: Bool = false, ok: Bool = true) -> PrivacyReads.ValidatorQuote {
            PrivacyReads.ValidatorQuote(validator: "v", backing: 0, supply: 0, status: status, jailed: jailed, tombstoned: tomb, delegatable: ok)
        }
        XCTAssertEqual(StakeRound.Standing(q(PrivacyReads.bondStatusBonded)), .active)
        XCTAssertEqual(StakeRound.Standing(q(PrivacyReads.bondStatusBonded, ok: false)), .closed(""))
        XCTAssertEqual(StakeRound.Standing(q("BOND_STATUS_UNBONDED")), .inactive)
        XCTAssertEqual(StakeRound.Standing(q("BOND_STATUS_UNBONDED", jailed: true, ok: false)), .jailed)
        XCTAssertEqual(StakeRound.Standing(q("BOND_STATUS_UNBONDED", jailed: true, tomb: true, ok: false)), .tombstoned)
        XCTAssertEqual(StakeRound.Standing(q("")), .removed)
        XCTAssertNil(StakeRound.Standing.active.reason)
    }
}
