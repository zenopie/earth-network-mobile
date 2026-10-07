import XCTest
@testable import EarthCore

/// R6-E-7: the activity refresh's lookups are bounded, retried, and remembered.
final class TxLookupTests: XCTestCase {
    private func hash(_ i: Int) -> String { String(repeating: String(format: "%02X", i % 256), count: 32) }

    override func setUp() { Explorer.forgetLookups() }

    private final class Counter: @unchecked Sendable {
        private let lock = NSLock()
        private var now = 0, most = 0
        private var calls: [String: Int] = [:]
        private(set) var sleeps: [Double] = []
        func enter() { lock.lock(); now += 1; most = max(most, now); lock.unlock() }
        func leave() { lock.lock(); now -= 1; lock.unlock() }
        func call(_ h: String) -> Int { lock.lock(); defer { lock.unlock() }; calls[h, default: 0] += 1; return calls[h]! }
        func calls(_ h: String) -> Int { lock.lock(); defer { lock.unlock() }; return calls[h, default: 0] }
        func slept(_ s: Double) { lock.lock(); sleeps.append(s); lock.unlock() }
        var peak: Int { lock.lock(); defer { lock.unlock() }; return most }
    }

    func testAtMostFourInFlight() async {
        let c = Counter()
        let hashes = (0 ..< 50).map(hash)
        let got = await Explorer.lookupAll(hashes, fetch: { h in
            c.enter()
            try? await Task.sleep(nanoseconds: 3_000_000)
            c.leave()
            return .found(Explorer.Tx(hash: h, height: 10, success: true, timestamp: "", types: [], first: [:]))
        }, sleep: { _ in })
        XCTAssertEqual(got.count, 50)
        XCTAssertLessThanOrEqual(c.peak, Explorer.lookupConcurrency)
    }

    func testRefusedLookupIsRetriedNotDropped() async {
        let c = Counter()
        let (h1, h2, h3) = (hash(1), hash(2), hash(3))
        let got = await Explorer.lookupAll([h1, h2, h3], fetch: { h in
            let n = c.call(h)
            switch h {
            case h1: return n < 3 ? .unavailable : .found(Explorer.Tx(hash: h, height: 7, success: true, timestamp: "", types: [], first: [:]))
            case h2: return .notFound
            default: return .unavailable
            }
        }, sleep: { c.slept($0) })
        XCTAssertEqual(got.map(\.hash), [h1])
        XCTAssertEqual(c.calls(h1), 3)
        XCTAssertEqual(c.calls(h2), 1) // "no such tx" is an answer: not asked again
        XCTAssertEqual(c.calls(h3), Explorer.lookupAttempts)
        XCTAssertTrue(c.sleeps.allSatisfy { $0 >= 1 })
    }

    func testCommittedTxIsNotAskedAgain() async {
        let c = Counter()
        let h = hash(4)
        let fetch: @Sendable (String) async -> Explorer.Lookup = { h in
            _ = c.call("all")
            return .found(Explorer.Tx(hash: h, height: 9, success: true, timestamp: "", types: [], first: [:]))
        }
        _ = await Explorer.lookupAll([h], fetch: fetch, sleep: { _ in })
        let again = await Explorer.lookupAll([h.lowercased()], fetch: fetch, sleep: { _ in })
        XCTAssertEqual(c.calls("all"), 1)
        XCTAssertEqual(again.map(\.hash), [h])
        Explorer.forgetLookups()
        _ = await Explorer.lookupAll([h], fetch: fetch, sleep: { _ in })
        XCTAssertEqual(c.calls("all"), 2)
    }

    func testClassifiesAnswers() {
        func isNotFound(_ l: Explorer.Lookup) -> Bool { if case .notFound = l { return true }; return false }
        func isUnavailable(_ l: Explorer.Lookup) -> Bool { if case .unavailable = l { return true }; return false }
        XCTAssertTrue(isNotFound(Explorer.lookup(error: EarthRest.Error.http(status: 404, body: "tx not found"))))
        XCTAssertTrue(isNotFound(Explorer.lookup(answer: JSON([String: Any]()))))
        for status in [408, 429, 500, 502, 503, 504] {
            XCTAssertTrue(isUnavailable(Explorer.lookup(error: EarthRest.Error.http(status: status, body: "busy"))), "HTTP \(status)")
        }
        XCTAssertTrue(isUnavailable(Explorer.lookup(error: EarthRest.Error.notJSON("<html>"))))
        XCTAssertTrue(isUnavailable(Explorer.lookup(error: URLError(.timedOut))))
    }
}
