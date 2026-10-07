import XCTest
@testable import EarthCore

final class SentTxLogTests: XCTestCase {
    private func log() -> SentTxLog {
        let name = "SentTxLogTests.\(UUID().uuidString)"
        let d = UserDefaults(suiteName: name)!
        addTeardownBlock { d.removePersistentDomain(forName: name) }
        return SentTxLog(defaults: d)
    }

    private func hash(_ i: Int) -> String { String(repeating: String(format: "%02X", i % 256), count: 32) }

    func testNewestFirstDedupedCappedPerAddress() {
        let l = log()
        for i in 0 ..< 60 { l.record(hash(i), for: "earth1a") }
        l.record(hash(10).lowercased(), for: "earth1a") // again, any case: moves to the front
        let got = l.hashes(for: "earth1a")
        XCTAssertEqual(got.count, SentTxLog.limit)
        XCTAssertEqual(got.first, hash(10))
        XCTAssertEqual(Set(got).count, got.count)
        XCTAssertEqual(got[1], hash(59))
        XCTAssertEqual(l.hashes(for: "earth1b"), [])
        l.clear("earth1a")
        XCTAssertEqual(l.hashes(for: "earth1a"), [])
    }

    func testRefusesWhatIsNotAHash() {
        let l = log()
        l.record("../../bank/v1beta1/supply", for: "earth1a")
        l.record("AB", for: "earth1a")
        l.record(hash(1), for: "")
        XCTAssertEqual(l.hashes(for: "earth1a"), [])
        XCTAssertFalse(SentTxLog.isHash(String(repeating: "G", count: 64)))
    }

    /// The by-hash lookup's answer becomes a row's data.
    func testTxFromLookup() throws {
        let body = """
        {"tx":{"body":{"messages":[{"@type":"/cosmos.bank.v1beta1.MsgSend","from_address":"earth1a","to_address":"earth1b","amount":[{"denom":"uerth","amount":"5"}]}]}},
         "tx_response":{"txhash":"\(hash(3))","height":"42","code":0,"timestamp":"2026-10-06T00:00:00Z"}}
        """
        let tx = try XCTUnwrap(Explorer.tx(lookup: JSON(JSONSerialization.jsonObject(with: Data(body.utf8)))))
        XCTAssertEqual(tx.hash, hash(3))
        XCTAssertEqual(tx.height, 42)
        XCTAssertTrue(tx.success)
        XCTAssertEqual(tx.types, ["MsgSend"])
        XCTAssertEqual(tx.first["to_address"] as? String, "earth1b")
        XCTAssertNil(Explorer.tx(lookup: JSON(["code": 5])))
    }
}
