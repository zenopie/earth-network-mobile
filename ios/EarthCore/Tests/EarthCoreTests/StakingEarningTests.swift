import BigInt
import XCTest
@testable import EarthCore

/// What stake earns a day is paid on its book's x/staking delegation, not on
/// its value: earth-2's first stake (39 ERTH of a 139 ERTH bonded pool) was
/// worth 5,420 ERTH after six hours of unbonded rewards, and the value times
/// the network rate showed +3 million ERTH a day, more than the 86,400 ERTH
/// the whole pool is paid.
final class StakingEarningTests: XCTestCase {
    private let bonded: Int64 = 139_000_000
    private let supply: BigUInt = 39_000_000
    private let delegation: BigUInt = 39_000_000

    func testEarnsOnTheDelegationNotTheValue() throws {
        let part = StakingApr.earningPart(derth: 39_000_000, supply: supply, delegation: delegation)
        XCTAssertEqual(part, 39_000_000)
        let rate = try XCTUnwrap(StakingApr.forValidator(bondedUerth: bonded, commission: 0.1))
        let dailyErth = part * rate / 365 / 1_000_000
        // 39/139 of 86,400 ERTH, less 10%.
        XCTAssertEqual(dailyErth, 86_400 * 39 / 139 * 0.9, accuracy: 0.01)
    }

    func testNeverMoreThanTheWholeEmission() throws {
        // Every derth of the book, at any backing: at most the book's share of the pool.
        let part = StakingApr.earningPart(derth: 39_000_000, supply: supply, delegation: delegation)
        let rate = try XCTUnwrap(StakingApr.base(bondedUerth: bonded))
        XCTAssertLessThanOrEqual(part * rate / 365, 86_400 * 1_000_000)
    }

    func testAShareOfTheBook() {
        XCTAssertEqual(StakingApr.earningPart(derth: 10, supply: 40, delegation: 100), 25)
    }

    func testFaceValueWithoutABook() {
        XCTAssertEqual(StakingApr.earningPart(derth: 7, supply: nil, delegation: nil), 7)
        XCTAssertEqual(StakingApr.earningPart(derth: 7, supply: 0, delegation: 5), 7)
    }
}
