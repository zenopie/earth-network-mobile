import BigInt
import XCTest
@testable import EarthCore

/// A delegation's credit is priced at the backing its validator will have
/// once `creditHorizonSeconds` of rewards accrue: on launch week the rate rose
/// ~3.6 ppm a second, and a fixed 10 ppm margin was outrun by every stake.
final class CreditHorizonTests: XCTestCase {
    /// earth-1's only validator at height 26885 (2026-10-09).
    private let book = PrivacyReads.ValidatorQuote(validator: "v", backing: 144_094_238_622, supply: 117_607_253_770,
                                                   delegation: 117_607_253_770, tokens: 204_110_671_460)
    private let bonded: BigUInt = 204_110_671_460

    /// The credit the chain allows `seconds` after the read, at the emission's pro-rata share.
    private func allowed(_ value: BigUInt, after seconds: UInt64) -> BigUInt {
        let w = BigUInt(StakingApr.emissionUerthPerSecond) * BigUInt(seconds) * book.delegation / bonded
        return value * book.supply / (book.backing + w)
    }

    func testHoldsUntilTheHorizon() throws {
        let value: BigUInt = 100_000_000
        let credit = BigUInt(try PrivacyWallet.creditFor(value, book, bonded: bonded))
        XCTAssertLessThanOrEqual(credit, allowed(value, after: PrivacyWallet.creditHorizonSeconds))
        // The old fixed margin alone was outrun within seconds.
        let fixed = value * book.supply / book.backing
        XCTAssertGreaterThan(fixed - fixed * 10 / 1_000_000, allowed(value, after: 5))
        // What the horizon costs: under half a percent of the stake today.
        XCTAssertGreaterThan(credit * 1_000, fixed * 995)
    }

    func testNothingBondedNoAccrual() throws {
        let credit = try PrivacyWallet.creditFor(2_000_000, book, bonded: 0)
        let buys: UInt64 = 2_000_000 * 117_607_253_770 / 144_094_238_622
        XCTAssertEqual(buys - (buys * PrivacyWallet.creditMarginPPM + 999_999) / 1_000_000, credit)
    }
}
