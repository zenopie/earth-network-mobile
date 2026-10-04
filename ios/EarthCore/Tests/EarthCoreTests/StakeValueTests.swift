import BigInt
import Foundation
import XCTest
@testable import EarthCore

/// derth is valued at rate_v (ERTH per derth), floored as the chain floors
/// it; a stake vote weighs only what the snapshot admits; chain-supplied
/// durations and epochs never trap or wrap.
final class StakeValueTests: PrivacyTestCase {
    func testDerthValueFloorsAtTheRate() {
        XCTAssertEqual(1_050_000, PrivacyWallet.derthValue(1_000_000, rate: Decimal(string: "1.05")!))
        XCTAssertEqual(1, PrivacyWallet.derthValue(3, rate: Decimal(string: "0.5")!))
        XCTAssertEqual(1_234_567, PrivacyWallet.derthValue(1_234_567, rate: 1))
        // 999,999.999999999999999999: floored, not rounded up by the 18-place rate.
        XCTAssertEqual(999_999, PrivacyWallet.derthValue(999_999, rate: Decimal(string: "1.000001000001000001")!))
    }

    func testPositionsFromBeforeTheSnapshotVote() {
        func pos(_ id: UInt64, _ h: UInt64) -> PrivacyReads.Position {
            .init(id: id, validator: "v", derth: 1, ownerTag: .zero, createdHeight: h)
        }
        let snap = PrivacyReads.Snapshot(root: .zero, treeSize: 0, height: 100)
        XCTAssertEqual([1], PrivacyWallet.votingPositions([pos(1, 99), pos(2, 100), pos(3, 101)], snapshot: snap).map(\.id))
        // A snapshot without a height (unknown) admits every position.
        XCTAssertEqual(2, PrivacyWallet.votingPositions([pos(1, 99), pos(2, 100)], snapshot: .init(root: .zero, treeSize: 0)).count)
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
        let positions = [PrivacyReads.Position(id: 1, validator: validator, derth: 1, ownerTag: .zero, createdHeight: UInt64.max)]
        XCTAssertTrue(PrivacyWallet.votingPositions(positions, snapshot: .init(root: .zero, treeSize: 0, height: 10)).isEmpty)
    }
}
