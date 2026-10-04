import BigInt
import Foundation
import XCTest
@testable import EarthCore

/// The HTTP side of the indexer and LCD: base paths taken only as
/// /privacy/<chain_id>/<genesis>, no redirects, a busy indexer backed off,
/// bodies and JSON nesting bounded, and every row format parsed strictly.
final class IndexerClientTests: PrivacyTestCase {
    func indexer() -> HTTPPrivacyIndexer {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [ScriptedProtocol.self]
        return HTTPPrivacyIndexer(host: URL(string: "https://indexer.test")!, chainID: "earth-1", configuration: config) { ms in ScriptedProtocol.slept(ms) }
    }

    func notes(_ s: String) throws -> NotesPage { try HTTPPrivacyIndexer.parseNotes(JSON(try JSONSerialization.jsonObject(with: Data(s.utf8)))) }

    let fields = #"["position","height","cm","ciphertext","amount","owner_pk","rho","rcm"]"#
    let hex0 = String(repeating: "00", count: 32)
    let hex1 = String(repeating: "00", count: 31) + "01"

    /// The status's base is taken only as exactly /privacy/<chain_id>/<genesis>, never as a host; no path traps.
    func testHostileIndexerBasesAreRefused() async throws {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StatusProtocol.self]
        func indexer() -> HTTPPrivacyIndexer { HTTPPrivacyIndexer(host: URL(string: "https://indexer.test")!, chainID: "earth-1", configuration: config) }
        StatusProtocol.base = "/privacy/earth-1/0123456789abcdef"
        StatusProtocol.paths = []
        _ = try await indexer().notes(fromPos: 0, limit: nil)
        XCTAssertEqual(["/privacy/status", "/privacy/earth-1/0123456789abcdef/notes?from_pos=0"], StatusProtocol.paths)
        for b in ["@evil.example/privacy/earth-1/0123456789abcdef", "//evil.example/privacy/earth-1/0123456789abcdef",
                  "https://evil.example/privacy/earth-1/0123456789abcdef", "/privacy/earth-1/0123456789abcdef/../../x",
                  "/privacy/earth-1/0123456789abcdef?x=", "/privacy/earth-2/0123456789abcdef", "/privacy/earth-1/0123456789ABCDEF",
                  "privacy/earth-1/0123456789abcdef", "/privacy/earth-1/0123456789abcdef/ x"] {
            StatusProtocol.base = b
            StatusProtocol.paths = []
            await assertThrowsAsync({ try await indexer().notes(fromPos: 0, limit: nil) })
            XCTAssertEqual(["/privacy/status"], StatusProtocol.paths, b)
        }
        XCTAssertFalse(HTTPPrivacyIndexer.validBase("/privacy/null/0123456789abcdef", expected: "earth-1", chainID: nil, genesis: "0123456789abcdef"))
        XCTAssertFalse(HTTPPrivacyIndexer.validBase("/privacy/earth 1/0123456789abcdef", expected: "earth 1", chainID: "earth 1", genesis: "0123456789abcdef"))
        XCTAssertTrue(HTTPPrivacyIndexer.validBase("/privacy/earth-1/0123456789abcdef", expected: "earth-1", chainID: "earth-1", genesis: "0123456789abcdef"))
    }

    /// A redirect is never followed; the other origin is never asked.
    func testRedirectsAreNotFollowed() async throws {
        ScriptedProtocol.reset()
        ScriptedProtocol.redirect = true
        await assertThrowsAsync({ try await self.indexer().notes(fromPos: 0, limit: 1000) })
        XCTAssertFalse(ScriptedProtocol.hosts.contains("elsewhere.test"))
    }

    /// A busy indexer (503 with Retry-After, 429) is waited out, then given up on; a page size it does not serve is never asked.
    func testABusyIndexerIsBackedOff() async throws {
        ScriptedProtocol.reset()
        ScriptedProtocol.busy = 2
        let idx = indexer()
        let page = try await idx.notes(fromPos: 0, limit: 1000)
        XCTAssertEqual(0, page.rows.count)
        XCTAssertEqual([3000, 3000], ScriptedProtocol.sleeps)
        ScriptedProtocol.reset()
        ScriptedProtocol.busy = 100
        ScriptedProtocol.busyCode = 429
        await assertThrowsAsync({ try await self.indexer().notes(fromPos: 0, limit: nil) }) {
            if case HTTPPrivacyIndexer.Error.busy = $0 { return true }
            return false
        }
        XCTAssertEqual([1000, 2000, 4000, 8000], ScriptedProtocol.sleeps)
        await assertThrowsAsync({ try await self.indexer().notes(fromPos: 0, limit: 5000) })
    }

    /// A response past the 8 MiB cap is refused while it streams in, never held whole.
    func testResponseBodiesAreBounded() async throws {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [BigBodyProtocol.self]
        let session = URLSession(configuration: config)
        BigBodyProtocol.size = EarthRest.maxBodyBytes + 1
        await assertThrowsAsync({ try await EarthRest.boundedData(session, URLRequest(url: URL(string: "https://indexer.test/privacy/status")!)) }) {
            if case EarthRest.Error.tooLarge = $0 { return true }
            return false
        }
        BigBodyProtocol.size = 1024
        let (data, status) = try await EarthRest.boundedData(session, URLRequest(url: URL(string: "https://indexer.test/privacy/status")!))
        XCTAssertEqual(1024, data.count)
        XCTAssertEqual(200, status)
    }

    /// Deep JSON is refused before any parser sees it; brackets inside strings do not count.
    func testDeepJsonIsRefusedBeforeParsing() throws {
        let deep = Data(("{\"notes\":" + String(repeating: "[", count: 500_000)).utf8)
        XCTAssertThrowsError(try EarthRest.checkJSONDepth(deep))
        XCTAssertNoThrow(try EarthRest.checkJSONDepth(Data(#"{"a":"[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[\"]","b":[[1]]}"#.utf8)))
    }

    func testNotesRowsParseByNameWithOpenRows() throws {
        let page = try notes(#"{"format":2,"fields":["rcm","rho","owner_pk","amount","ciphertext","cm","height","position"],"notes":[[null,null,null,null,"AAAA",""# + hex1 + #"",5,0],[null,null,null,"7uerth","AAAA",""# + hex1 + #"",5,1],[""# + hex1 + #"",""# + hex0 + #"",""# + hex1 + #"","5000000uerth",null,""# + hex1 + #"",6,2]],"next_pos":3,"complete":false,"synced_height":6}"#)
        XCTAssertEqual([0, 1, 2], page.rows.map(\.position))
        XCTAssertNil(page.rows[0].amount); XCTAssertNil(page.rows[0].ownerPK)
        XCTAssertEqual("7uerth", page.rows[1].amount); XCTAssertNil(page.rows[1].rho)
        let open = page.rows[2]
        XCTAssertEqual(0, open.ciphertext.count)
        XCTAssertEqual(Fr(UInt64(1)), open.ownerPK); XCTAssertEqual(Fr.zero, open.rho); XCTAssertEqual(Fr(UInt64(1)), open.rcm)
        // An old backend (format 1, five columns) is refused, not misread.
        XCTAssertThrowsError(try notes(#"{"notes":[[0,1,""# + hex1 + #"","AAAA",null]],"next_pos":1,"complete":false,"synced_height":1}"#))
        // Part of an opening, an opening beside a ciphertext, an open row with no amount: refused.
        for row in [
            #"[0,1,""# + hex1 + #"",null,"5uerth",""# + hex1 + #"",null,""# + hex1 + #""]"#,
            #"[0,1,""# + hex1 + #"","AAAA","5uerth",""# + hex1 + #"",""# + hex1 + #"",""# + hex1 + #""]"#,
            #"[0,1,""# + hex1 + #"",null,null,""# + hex1 + #"",""# + hex1 + #"",""# + hex1 + #""]"#,
            #"[0,1,""# + hex1 + #"",null,"5uerth",null,null,null]"#,
        ] {
            XCTAssertThrowsError(try notes(#"{"format":2,"fields":"# + fields + #","notes":["# + row + #"],"next_pos":1,"complete":false,"synced_height":1}"#), row)
        }
    }

    /// Amounts past 2^63-1 are ignored on rows, never wrapped; derth values saturate.
    func testRowAmountsPast2To63AreIgnoredAndStakeRowsParse() throws {
        XCTAssertNil(WalletSync.publicAmount("9223372036854775808uerth"))
        XCTAssertEqual(UInt64(Int64.max), WalletSync.publicAmount("9223372036854775807uerth")?.value)
        XCTAssertNil(WalletSync.publicAmount("-1uerth"))
        // A stake row is [position, height, cm, ciphertext] (format 2); an older row's extra columns are ignored, never trusted.
        let page = try HTTPPrivacyIndexer.parseStakeNotes(JSON(try JSONSerialization.jsonObject(with: Data(
            #"{"format":2,"notes":[[0,1,"\#(String(repeating: "00", count: 32))","AAAA"],[1,1,"\#(String(repeating: "00", count: 32))",null,"derth/x","18446744073709551615",null]],"next_pos":2,"complete":true,"synced_height":1}"#.utf8))))
        XCTAssertEqual([3, 0], page.rows.map(\.ciphertext.count))
        XCTAssertEqual(UInt64(Int64.max), PrivacyWallet.derthValue(UInt64(Int64.max), rate: 2.5))
        XCTAssertEqual(0, PrivacyWallet.derthValue(5, rate: -1))
    }
}
