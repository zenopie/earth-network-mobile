import XCTest
@testable import ProverGate

/// XCTest front end for the Phase 1 gate, for running it inside Xcode. The
/// checks themselves live in `Gate` so they can also run as `swift run progate`
/// without a full Xcode install — see Sources/progate/main.swift.
final class LeanPoaGateTests: XCTestCase {

    func testLeanPoaGate() throws {
        let root = try RepoLayout.root(from: #filePath)
        let report = try Gate.run(paths: RepoLayout.Paths(root: root))

        for check in report.checks where check.outcome == .failed {
            XCTFail("\(check.name): \(check.detail)")
        }
        for check in report.checks where check.outcome != .failed {
            print("[\(check.outcome)] \(check.name): \(check.detail)")
        }
        XCTAssertTrue(report.passed)
    }
}
