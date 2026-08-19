import Foundation

/// A tiny assertion harness.
///
/// Not XCTest: a Command Line Tools toolchain ships no XCTest platform, so
/// `swift test` cannot run at all on this machine. An executable of
/// known-answer checks is what works everywhere, and it is what
/// ProverGateCore already does.
enum Check {
    private(set) static var failures = 0
    private(set) static var total = 0
    private static var section = ""

    static func group(_ name: String) {
        section = name
        print("\n\u{001B}[1m\(name)\u{001B}[0m")
    }

    static func that(_ label: String, _ condition: Bool, detail: @autoclosure () -> String = "") {
        total += 1
        if condition {
            print("  ok    \(label)")
        } else {
            failures += 1
            let d = detail()
            print("  FAIL  \(label)\(d.isEmpty ? "" : "\n        \(d)")")
        }
    }

    static func equal<T: Equatable>(_ label: String, _ actual: T, _ expected: T) {
        that(label, actual == expected, detail: "expected \(expected)\n        actual   \(actual)")
    }

    static func throwsError(_ label: String, _ body: () throws -> Void) {
        do {
            try body()
            that(label, false, detail: "expected a throw, got none")
        } catch {
            that(label, true)
        }
    }

    static func finish() -> Never {
        print("\n\(total - failures)/\(total) checks passed")
        if failures > 0 {
            print("\u{001B}[31m\(failures) FAILED\u{001B}[0m")
            exit(1)
        }
        print("\u{001B}[32mall good\u{001B}[0m")
        exit(0)
    }
}
