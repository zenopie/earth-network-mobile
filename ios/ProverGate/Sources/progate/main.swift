import Foundation
import ProverGate
import ProverGateCore

// Runs the Phase 1 gate and prints a report. An executable rather than only an
// XCTest case because XCTest needs full Xcode, and the gate is meant to be
// answerable before committing to any of the iOS toolchain.
//
//   cd ios/ProverGate && swift run progate
//
// Exits non-zero if any check fails, so CI can use it directly.

let start = CommandLine.arguments.count > 1
    ? CommandLine.arguments[1]
    : FileManager.default.currentDirectoryPath

do {
    let root = try RepoLayout.root(from: start)
    let paths = RepoLayout.Paths(root: root)

    FileHandle.standardError.write(Data("running the lean_poa gate (first run downloads the SRS)…\n".utf8))
    let report = try Gate.run(paths: paths)

    let width = report.checks.map { $0.name.count }.max() ?? 0
    for check in report.checks {
        let mark: String
        switch check.outcome {
        case .passed: mark = "PASS"
        case .failed: mark = "FAIL"
        case .skipped: mark = "SKIP"
        case .informational: mark = "····"
        }
        let name = check.name.padding(toLength: width, withPad: " ", startingAt: 0)
        print("[\(mark)] \(name)  \(check.detail)")
    }

    print(report.passed
          ? "\ngate PASSED — Barretenberg on this platform proves lean_poa and the proof verifies"
          : "\ngate FAILED — see the failing checks above")
    exit(report.passed ? 0 : 1)
} catch {
    FileHandle.standardError.write(Data("gate errored: \(error)\n".utf8))
    exit(2)
}
