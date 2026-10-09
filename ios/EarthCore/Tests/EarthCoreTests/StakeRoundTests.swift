import XCTest
@testable import EarthCore

final class StakeRoundTests: XCTestCase {
    private func note(_ pos: UInt64, _ height: UInt64, _ amount: UInt64, spent: UInt64? = nil, label: StakeLabel? = nil, denom: String = "derth/v") -> OwnedStakeNote {
        OwnedStakeNote(position: pos, height: height, denom: denom, amount: amount, rho: Fr(pos), rcm: .one, cm: Fr(pos), nf: Fr(pos + 1000),
                       spentHeight: spent, label: label)
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

    /// The MsgDelegate at 2395, as a stake note: valued at the live rate.
    func testAStakeIsValuedAtTheRate() throws {
        let rates = try liveRates()
        XCTAssertEqual(rates[Self.op], 1)
        let ns = [note(0, 2395, Self.staked, denom: PrivacyWallet.derthDenom(Self.op))]
        let lines = StakeRound.lines(notes: ns, rate: { rates[$0] ?? 1 })
        XCTAssertEqual(lines.count, 1)
        XCTAssertEqual(lines[0].validator, Self.op)
        XCTAssertEqual(lines[0].value, Self.staked)
        // Spent notes do not count; several notes add up, at the rate.
        XCTAssertTrue(StakeRound.lines(notes: [note(0, 2395, Self.staked, spent: 2541, denom: PrivacyWallet.derthDenom(Self.op))],
                                       rate: { rates[$0] ?? 1 }).isEmpty)
        let two = [note(0, 2395, 1_000_000, denom: PrivacyWallet.derthDenom(Self.op)), note(1, 2400, 2_000_000, denom: PrivacyWallet.derthDenom(Self.op))]
        let l = StakeRound.lines(notes: two, rate: { _ in Decimal(string: "1.5")! })[0]
        XCTAssertEqual(l.derth, 3_000_000)
        XCTAssertEqual(l.value, 4_500_000)
    }
}
