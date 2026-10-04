import XCTest
import ProverGateCore
@testable import ProverGate

/// XCTest front end for the passport gate on every variant, for running it
/// inside Xcode. Named to run before PrivacyProverTests: barretenberg sizes
/// its SRS once per process, and the gate starts with the largest circuit. The
/// checks themselves live in `Gate` so they can also run as `swift run progate`
/// without a full Xcode install — see Sources/progate/main.swift.
final class LeanPoaGateTests: XCTestCase {

    func testPassportGateOnEveryVariant() throws {
        let root = try RepoLayout.root(from: #filePath)
        let reports = try Gate.runAll(paths: RepoLayout.Paths(root: root))
        XCTAssertEqual(reports.count, 33)
        for (id, report) in reports {
            for check in report.checks where check.outcome == .failed {
                XCTFail("\(id) \(check.name): \(check.detail)")
            }
            for check in report.checks where check.outcome != .failed {
                print("[\(check.outcome)] \(id) \(check.name): \(check.detail)")
            }
            XCTAssertTrue(report.passed, id)
        }
    }
}
