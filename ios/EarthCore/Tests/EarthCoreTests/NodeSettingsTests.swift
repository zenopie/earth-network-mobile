@testable import EarthCore
import XCTest

/// The custom-node rules: https anywhere, plain http only to this phone or a
/// private network. The same cases as Android's NodeConfigTest.
final class NodeSettingsTests: XCTestCase {
    func testLocalAddresses() {
        for h in ["localhost", "127.0.0.1", "10.0.2.2", "192.168.1.20", "172.16.0.5", "169.254.1.1", "::1", "fd00::1", "mynode.local"] {
            XCTAssertTrue(NodeSettings.isLocal(h), h)
        }
        for h in ["8.8.8.8", "172.32.0.1", "lcd.erth.network", "2001:4860::8888", "cafe.bad", ""] {
            XCTAssertFalse(NodeSettings.isLocal(h), h)
        }
    }

    /// R2-MC-03: the rules Android NodeConfig.isLocal applies, case for case.
    func testLocalRulesMatchAndroid() {
        for h in ["::ffff:192.168.1.5", "[::ffff:10.0.0.1]", "fec0::1", "fe80::1", "fc00::1"] { XCTAssertTrue(NodeSettings.isLocal(h), h) }
        for h in ["::ffff:8.8.8.8", "300.1.1.1", "192.168.1.256", "2001:db8::1", "::2"] { XCTAssertFalse(NodeSettings.isLocal(h), h) }
    }

    /// The genesis hash is over the decoded chunks in order, whatever their split (Android's genesisHashOverChunks).
    func testGenesisHashOverChunks() async throws {
        let genesis = Data(#"{"genesis_time":"2026-10-02T12:00:00Z","chain_id":"earth-1"}"#.utf8)
        let want = Hashes.sha256(genesis).hexString
        func served(_ parts: [Data]) -> (Int) async throws -> JSON {
            { i in JSON(["jsonrpc": "2.0", "id": -1, "result": ["chunk": "\(i)", "total": "\(parts.count)", "data": parts[i].base64EncodedString()]]) }
        }
        let got1 = try await NodeSettings.genesisSHA256(served([genesis]))
        XCTAssertEqual(got1, want)
        let got3 = try await NodeSettings.genesisSHA256(served([genesis.prefix(10), genesis.dropFirst(10).prefix(20), genesis.dropFirst(30)].map { Data($0) }))
        XCTAssertEqual(got3, want)
        // A chunk out of order, a changing total, too many chunks, an error: refused.
        let bad: [(Int) async throws -> JSON] = [
            { _ in JSON(["result": ["chunk": "1", "total": "2", "data": "YQ=="]]) },
            { i in JSON(["result": ["chunk": "\(i)", "total": "\(i + 2)", "data": "YQ=="]]) },
            { i in JSON(["result": ["chunk": "\(i)", "total": "99", "data": "YQ=="]]) },
            { _ in JSON(["jsonrpc": "2.0", "error": ["code": -32603]]) },
        ]
        for f in bad {
            do { _ = try await NodeSettings.genesisSHA256(f); XCTFail("accepted") } catch {
                XCTAssertTrue(error is NodeSettings.ProbeError)
            }
        }
    }

    /// The pin is 64 lowercase hex digits (set at the ceremony; Android holds the same).
    func testGenesisPinIsASHA256() {
        XCTAssertEqual(Constants.genesisSHA256.count, 64)
        XCTAssertTrue(Constants.genesisSHA256.allSatisfy { "0123456789abcdef".contains($0) })
    }

    /// A node without an RPC is refused before anything is asked of it: only the RPC serves the genesis.
    func testRPCIsRequired() async {
        do {
            _ = try await NodeSettings.probe(NodeSettings.Node(lcd: URL(string: "https://node.example.com")!, rpc: nil))
            XCTFail("accepted")
        } catch {
            XCTAssertTrue(error.localizedDescription.contains("RPC"))
        }
    }
}
