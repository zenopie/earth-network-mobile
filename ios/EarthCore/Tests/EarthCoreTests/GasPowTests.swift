import CryptoKit
import Foundation
import XCTest
@testable import EarthCore

/// The /gas/register proof of work (backend services/pow.py) against a fake
/// server that checks stamps as the backend does. Ports GasPowTest.kt.
final class GasPowTests: XCTestCase {
    let binding = "123456789012345678901234567890"
    let nullifier = "987654321"

    /// A fake /gas: GET /gas/pow answers `advertised`; POST checks the stamp like services/pow.
    final class Server: @unchecked Sendable {
        let lock = NSLock()
        var advertised: Int
        var replies: [([String: Any]) -> (Int, String)] = []
        var posts: [[String: Any]] = []
        var used = Set<Data>()
        var now: Int64 = 1_759_363_200
        let binding: String, nullifier: String
        init(_ bits: Int, binding: String, nullifier: String) { advertised = bits; self.binding = binding; self.nullifier = nullifier }

        var transport: GasGrant.Transport {
            GasGrant.Transport(
                get: { [self] path in
                    XCTAssertEqual("/gas/pow", path)
                    return (200, Data("{\"version\":\"earth-gas-pow/v1\",\"bits\":\(advertised),\"max_age_seconds\":600}".utf8))
                },
                post: { [self] path, body in
                    XCTAssertEqual("/gas/register", path)
                    let (s, b) = take(body)
                    return (s, Data(b.utf8))
                })
        }

        func take(_ body: [String: Any]) -> (Int, String) {
            lock.lock(); posts.append(body); let r = replies.isEmpty ? nil : replies.removeFirst(); lock.unlock()
            return r?(body) ?? (200, "{\"status\":\"success\"}")
        }

        /// The stamp's leading zero bits, or -1 when it would be refused (stale, reused, malformed).
        func bits(_ body: [String: Any]) -> Int {
            guard let p = body["pow"] as? [String: Any], let ts = p["ts"] as? Int64, let nonce = p["nonce"] as? String,
                  nonce.range(of: "^[0-9A-Za-z]{1,64}$", options: .regularExpression) != nil, abs(now - ts) <= 600 else { return -1 }
            let d = Data(SHA256.hash(data: GasPow.input(ts: ts, binding: binding, nullifier: nullifier, nonce: nonce)))
            guard !used.contains(d) else { return -1 }
            used.insert(d)
            return GasPow.leadingZeroBits(d)
        }
    }

    func msg() -> MsgRegisterPrivate {
        MsgRegisterPrivate(fee: nil, proof: Data(), publicSignals: ["261001", binding, nullifier, "0"], signatureAlgorithm: "", dscDer: Data(),
                           idc: Data(), pcAnml: Data(), ciphertextAnml: Data(), pcErth: Data(), ciphertextErth: Data())
    }

    func request(_ s: Server, progress: @escaping @Sendable (Double) -> Void = { _ in }) async throws -> GasGrant.Outcome {
        try await GasGrant.request(.register(msg(), pcGas: Data(count: 32), ciphertextGas: Data(count: 177)), transport: s.transport,
                                   clock: { [s] in s.now }, progress: progress)
    }

    func testStampFormatMatchesTheBackend() throws {
        XCTAssertEqual("earth-gas-pow/v1:1759363200:123456789012345678901234567890:987654321:0",
                       String(decoding: GasPow.input(ts: 1_759_363_200, binding: binding, nullifier: nullifier, nonce: "0"), as: UTF8.self))
        XCTAssertEqual(8, GasPow.leadingZeroBits([0, 0xff]))
        XCTAssertEqual(12, GasPow.leadingZeroBits([0, 0x0f, 0]))
        let s = try GasPow.solve(ts: 1_759_363_200, binding: binding, nullifier: nullifier, bits: 12)
        XCTAssertGreaterThanOrEqual(GasPow.leadingZeroBits(SHA256.hash(data: GasPow.input(ts: s.ts, binding: binding, nullifier: nullifier, nonce: s.nonce))), 12)
    }

    func testStampsAtTheAdvertisedBitsAndRestampsOn428() async throws {
        let s = Server(10, binding: binding, nullifier: nullifier)
        s.replies = [
            { body in XCTAssertGreaterThanOrEqual(s.bits(body), 10); return (428, "{\"status\":\"error\",\"message\":\"shedding\",\"pow\":{\"version\":\"earth-gas-pow/v1\",\"bits\":14}}") },
            { body in XCTAssertGreaterThanOrEqual(s.bits(body), 14); return (200, "{\"status\":\"success\"}") },
        ]
        let last = Progress()
        let r = try await request(s) { last.set($0) }
        XCTAssertEqual(.sent(txHash: nil), r)
        XCTAssertEqual(2, s.posts.count)
        XCTAssertEqual(1, last.value)
    }

    func testNoWorkWhenNoneIsAsked() async throws {
        let s = Server(0, binding: binding, nullifier: nullifier)
        _ = try await request(s)
        XCTAssertNil(s.posts.first?["pow"])
    }

    func testStampIsKeptOnlyAfter503Or429() async throws {
        let s = Server(8, binding: binding, nullifier: nullifier)
        s.replies = [{ _ in (503, "{\"status\":\"error\",\"message\":\"busy\"}") }]
        do { _ = try await request(s); XCTFail("expected a refusal") } catch is GasGrant.Refused {}
        _ = try await request(s)
        XCTAssertEqual(s.posts[0]["pow"] as? [String: AnyHashable], s.posts[1]["pow"] as? [String: AnyHashable])
        s.replies = [{ _ in (403, "{\"status\":\"error\",\"message\":\"refused\"}") }]
        do { _ = try await request(s); XCTFail("expected a refusal") } catch is GasGrant.Refused {}
        s.now += 1
        _ = try await request(s)
        XCTAssertNotEqual(s.posts[2]["pow"] as? [String: AnyHashable], s.posts[3]["pow"] as? [String: AnyHashable])
    }

    func testWorkCanBeCancelled() async throws {
        let t = Task.detached { [binding, nullifier] in try GasPow.solve(ts: 0, binding: binding, nullifier: nullifier, bits: GasPow.maxBits) }
        try await Task.sleep(nanoseconds: 100_000_000)
        t.cancel()
        do { _ = try await t.value; XCTFail("expected cancellation") } catch is CancellationError {}
    }

    final class Progress: @unchecked Sendable {
        private let l = NSLock()
        private var v = 0.0
        func set(_ x: Double) { l.lock(); v = x; l.unlock() }
        var value: Double { l.lock(); defer { l.unlock() }; return v }
    }

    /// Cancelling the request stops the proof of work.
    func testCancellingTheRequestStopsTheWork() async throws {
        let g = self
        let s = GasPowTests.Server(GasPow.maxBits, binding: g.binding, nullifier: g.nullifier)
        let p = GasPowTests.Progress()
        let outer = Task { try await g.request(s) { p.set($0) } }
        try await Task.sleep(nanoseconds: 200_000_000)
        outer.cancel()
        try await Task.sleep(nanoseconds: 300_000_000)
        let a = p.value
        try await Task.sleep(nanoseconds: 1_000_000_000)
        XCTAssertEqual(a, p.value, "the hashcash stopped with the request")
        XCTAssertEqual(24, GasPow.maxBits)
    }
}
