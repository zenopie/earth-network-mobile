import BigInt
import Foundation
import XCTest
@testable import EarthCore

/// derth is valued at rate_v (ERTH per derth), floored as the chain floors
/// it; chain-supplied
/// durations and epochs never trap or wrap.
final class StakeValueTests: PrivacyTestCase {
    func testDerthValueFloorsAtTheRate() {
        XCTAssertEqual(1_050_000, PrivacyWallet.derthValue(1_000_000, rate: Decimal(string: "1.05")!))
        XCTAssertEqual(1, PrivacyWallet.derthValue(3, rate: Decimal(string: "0.5")!))
        XCTAssertEqual(1_234_567, PrivacyWallet.derthValue(1_234_567, rate: 1))
        // 999,999.999999999999999999: floored, not rounded up by the 18-place rate.
        XCTAssertEqual(999_999, PrivacyWallet.derthValue(999_999, rate: Decimal(string: "1.000001000001000001")!))
    }

    func testChainNumbersNeverTrapOrWrap() {
        XCTAssertNil(PrivacyQueries.durationSeconds("1e30s"))
        XCTAssertNil(PrivacyQueries.durationSeconds("-5s"))
        XCTAssertNil(PrivacyQueries.durationSeconds("99999999999999999999999s"))
        XCTAssertNil(PrivacyQueries.durationSeconds("nan"))
        XCTAssertEqual(1_814_400, PrivacyQueries.durationSeconds("1814400s"))
        XCTAssertEqual(0, PrivacyQueries.durationSeconds("0.5s"))
        XCTAssertNil(PrivacyWallet.unbondDueBy(epoch: 0, current: UInt64.max, currentStart: 0, currentEnd: 0, epochSeconds: Int64.max, unbondingSeconds: 0))
        XCTAssertNil(PrivacyWallet.unbondDueBy(epoch: 1, current: 3, currentStart: Int64.max, currentEnd: 0, epochSeconds: 1, unbondingSeconds: Int64.max))
    }
}
