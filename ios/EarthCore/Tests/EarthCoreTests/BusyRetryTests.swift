import XCTest
@testable import EarthCore

/// EarthRest.retryingBusy: a GET the edge turned away (503, or Cloudflare's
/// 429) is sent again, a bounded number of times; nothing else is.
final class BusyRetryTests: XCTestCase {
    private let get = URLRequest(url: URL(string: "https://lcd.example/x")!)

    private func run(_ statuses: [Int], _ request: URLRequest) async throws -> (status: Int, calls: Int) {
        var calls = 0
        let result: (Data, Int) = try await EarthRest.retryingBusy(request) {
            defer { calls += 1 }
            return (Data(), statuses[min(calls, statuses.count - 1)])
        }
        return (result.1, calls)
    }

    func testRetriesBusyUntilAnswered() async throws {
        let r = try await run([503, 429, 200], get)
        XCTAssertEqual(r.status, 200)
        XCTAssertEqual(r.calls, 3)
    }

    func testGivesUpAfterTheCap() async throws {
        let r = try await run([503], get)
        XCTAssertEqual(r.status, 503)
        XCTAssertEqual(r.calls, EarthRest.busyRetries + 1)
    }

    func testOtherFailuresAreNotRetried() async throws {
        for status in [500, 404, 400] {
            let r = try await run([status, 200], get)
            XCTAssertEqual(r.calls, 1, "\(status)")
        }
    }

    func testWritesAreNotRetried() async throws {
        var post = get
        post.httpMethod = "POST"
        let r = try await run([503, 200], post)
        XCTAssertEqual(r.status, 503)
        XCTAssertEqual(r.calls, 1)
    }
}
