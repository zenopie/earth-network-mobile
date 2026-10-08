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
    // MARK: - earth-1, 2026-10-07: a first stake before the first round ended

    /// Query/Validators for the validator as the chain served it then (the brief's entry, verbatim).
    static let live = """
    {"validators":[{"validator":"earthvaloper1n6amvkgfrrgy6ulhurewnm0endkgye69vpfk7m","delegatable":true,
      "book":{"pending_delegation":"117607253770","pending_undelegation":"0","epoch_rate":"1.000000000000000000",
              "derth_supply":"117607253770","checkpoint_seq":"0","supply_height":"2395","supply_at_block_start":"0","slash_debt":"0"},
      "backing":"117607253770","supply":"117607253770","rate":"1.000000000000000000","delegation":"0","rewards":"0","redelegations":[]}],
     "pagination":{"next_key":null,"total":"0"},"height":"8158"}
    """
    static let op = "earthvaloper1n6amvkgfrrgy6ulhurewnm0endkgye69vpfk7m"
    static let staked: UInt64 = 117_607_253_770

    private func liveRates() throws -> [String: Decimal] {
        let page = try ValidatorPages.parse(JSON(try JSONSerialization.jsonObject(with: Data(Self.live.utf8))))
        return Dictionary(uniqueKeysWithValues: page.validators.map { ($0.validator, $0.rate) })
    }

    /// The MsgDelegate at 2395, as a stake note; the round began at block 1 (genesis), nothing delegated yet (D = 0).
    func testAFirstStakeBeforeTheRoundEndsIsValuedAndWaiting() throws {
        let rates = try liveRates()
        XCTAssertEqual(rates[Self.op], 1)
        let ns = [note(0, 2395, Self.staked, denom: PrivacyWallet.derthDenom(Self.op))]
        let lines = StakeRound.lines(notes: ns, positions: [], rate: { rates[$0] ?? 1 }, after: 1)
        XCTAssertEqual(lines.count, 1)
        XCTAssertEqual(lines[0].validator, Self.op)
        XCTAssertEqual(lines[0].value, Self.staked)
        XCTAssertEqual(lines[0].joiningValue, Self.staked)
        // After the round that delegated it: nothing waiting.
        XCTAssertEqual(StakeRound.lines(notes: ns, positions: [], rate: { rates[$0] ?? 1 }, after: 2395)[0].joiningValue, 0)
    }

    /// What the chain holds now: the note was locked whole into Groundworks position 0 at 2541 (MsgLockPosition),
    /// leaving a zero note the wallet drops. Counting notes alone gave 0: the Stake tab's zero.
    func testStakeLockedInAPositionStillCounts() throws {
        let rates = try liveRates()
        let ns = [note(0, 2395, Self.staked, spent: 2541, denom: PrivacyWallet.derthDenom(Self.op))]
        let ps = [StakeRound.Locked(validator: Self.op, derth: Self.staked, height: 2541)]
        XCTAssertTrue(StakeRound.lines(notes: ns, positions: [], rate: { rates[$0] ?? 1 }, after: 1).isEmpty)
        let lines = StakeRound.lines(notes: ns, positions: ps, rate: { rates[$0] ?? 1 }, after: 1)
        XCTAssertEqual(lines.count, 1)
        XCTAssertEqual(lines[0].notes, 0)
        XCTAssertEqual(lines[0].locked, Self.staked)
        XCTAssertEqual(lines[0].value, Self.staked)
        XCTAssertEqual(lines[0].lockedValue, Self.staked)
        // Still waiting: the lock moved it, the round has not delegated it.
        XCTAssertEqual(lines[0].joiningValue, Self.staked)
        // A lock of stake that joined already adds nothing waiting.
        XCTAssertEqual(StakeRound.joining(ns, positions: ps, denom: PrivacyWallet.derthDenom(Self.op), after: 2400), 0)
        // Part locked: the remainder note and the position, one block.
        let part = [note(0, 2395, Self.staked, spent: 2541, denom: PrivacyWallet.derthDenom(Self.op)),
                    note(1, 2541, Self.staked - 1_000_000, denom: PrivacyWallet.derthDenom(Self.op))]
        let one = [StakeRound.Locked(validator: Self.op, derth: 1_000_000, height: 2541)]
        let l = StakeRound.lines(notes: part, positions: one, rate: { _ in Decimal(string: "1.5")! }, after: 1)[0]
        XCTAssertEqual(l.derth, Self.staked)
        XCTAssertEqual(l.value, Self.staked / 2 * 3)
        XCTAssertEqual(l.joiningValue, Self.staked / 2 * 3)
    }
}
