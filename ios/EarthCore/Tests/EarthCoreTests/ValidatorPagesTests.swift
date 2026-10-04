import BigInt
import Foundation
import XCTest
@testable import EarthCore

/// Query/Validators: one entry's fields, and every page read at one height. As Android's ValidatorPagesTest.
final class ValidatorPagesTests: XCTestCase {
    let page = """
    {"validators":[
      {"validator":"earthvaloper1a","staking":{"operator_address":"earthvaloper1a","jailed":true,"status":"BOND_STATUS_UNBONDING",
        "tokens":"5000","description":{"moniker":"Alpha"},"commission":{"commission_rates":{"rate":"0.100000000000000000"}}},
       "tombstoned":true,"delegatable":false,"refusal":"earthvaloper1a is jailed",
       "book":{"validator":"earthvaloper1a","pending_delegation":"7","pending_undelegation":"3","epoch_rate":"1.0","derth_supply":"90","slash_debt":"0"},
       "backing":"100","supply":"90","rate":"1.111111111111111111","delegation":"95","rewards":"1",
       "redelegations":[{"dst_validator":"earthvaloper1b","entries":4,"counted_entries":3}]},
      {"validator":"earthvaloper1gone","staking":{"operator_address":"","status":"BOND_STATUS_UNSPECIFIED","tokens":"0"},
       "tombstoned":false,"delegatable":false,"refusal":"unknown validator","book":{"pending_delegation":"7"},
       "backing":"7","supply":"7","rate":"1.000000000000000000","delegation":"0","rewards":"0","redelegations":[]}
    ],"pagination":{"next_key":null,"total":"0"},"height":"42"}
    """

    func json(_ s: String) -> JSON { JSON(try! JSONSerialization.jsonObject(with: Data(s.utf8))) }

    func testAnEntryCarriesEveryQuoteInput() throws {
        let p = try ValidatorPages.parse(json(page))
        XCTAssertEqual(42, p.height)
        XCTAssertEqual("", p.nextKey)
        let a = p.validators[0]
        XCTAssertEqual(100, a.backing)
        XCTAssertEqual(90, a.supply)
        XCTAssertEqual(7, a.pendingDelegation)
        XCTAssertEqual(3, a.pendingUndelegation)
        XCTAssertEqual(95, a.delegation)
        XCTAssertEqual(1, a.rewards)
        XCTAssertEqual(8, a.queue)
        XCTAssertEqual("BOND_STATUS_UNBONDING", a.status)
        XCTAssertTrue(a.jailed && a.tombstoned && !a.delegatable)
        XCTAssertEqual("earthvaloper1a is jailed", a.refusal)
        XCTAssertEqual("Alpha", a.moniker)
        XCTAssertEqual(0.1, a.commission, accuracy: 1e-12)
        XCTAssertEqual(PrivacyReads.RedelegationLoad(entries: 4, countedEntries: 3), a.redelegations["earthvaloper1b"])
        XCTAssertFalse(a.queueFirst)
        // A book whose validator x/staking removed: no status, the queue first.
        let g = p.validators[1]
        XCTAssertTrue(g.removed && g.queueFirst && !g.bonded)
        XCTAssertFalse(g.jailed)
    }

    func testAMalformedEntryIsRefused() {
        for bad in [
            #"{"validator":"v","backing":"-1","supply":"1"}"#,
            #"{"validator":"v","backing":"1","supply":"1","rate":"x"}"#,
            #"{"validator":"","backing":"1","supply":"1"}"#,
            #"{"validator":"v","staking":{"operator_address":"w"},"backing":"1","supply":"1","rate":"1"}"#,
        ] {
            XCTAssertThrowsError(try ValidatorPages.quote(json(bad)), bad)
        }
    }

    func q(_ v: String) -> PrivacyReads.ValidatorQuote { PrivacyReads.ValidatorQuote(validator: v, backing: 10, supply: 10) }

    func testEveryPageIsReadAtTheFirstPagesHeight() async throws {
        var asked: [String] = []
        let list = try await ValidatorPages.readAll { key, h in
            asked.append("\(key)@\(h.map(String.init) ?? "latest")")
            switch key {
            case "": return ValidatorPages.Page(validators: [self.q("a")], nextKey: "k1", height: 10)
            case "k1": return ValidatorPages.Page(validators: [self.q("b")], nextKey: "k2", height: 10)
            default: return ValidatorPages.Page(validators: [self.q("c")], nextKey: "", height: 10)
            }
        }
        XCTAssertEqual(10, list.height)
        XCTAssertEqual(["a", "b", "c"], list.validators.map(\.validator))
        XCTAssertEqual(["@latest", "k1@10", "k2@10"], asked)
    }

    func testAHeightChangeMidReadStartsOver() async throws {
        var reads: Int64 = 0
        let list = try await ValidatorPages.readAll { key, _ in
            if key == "" { reads += 1 }
            if key == "" { return ValidatorPages.Page(validators: [self.q("a")], nextKey: "k1", height: 10 + reads) }
            // The first read's second page comes from another height; the
            // second read's is not served at its height at all.
            if reads == 1 { return ValidatorPages.Page(validators: [self.q("b")], nextKey: "", height: 99) }
            if reads == 2 { return nil }
            return ValidatorPages.Page(validators: [self.q("b")], nextKey: "", height: 10 + reads)
        }
        XCTAssertEqual(3, reads)
        XCTAssertEqual(13, list.height)
        XCTAssertEqual(["a", "b"], list.validators.map(\.validator))
        // A list that never holds still is an error, not a mix.
        var h: Int64 = 0
        do {
            _ = try await ValidatorPages.readAll { key, _ in
                if key == "" { h += 1; return ValidatorPages.Page(validators: [self.q("a")], nextKey: "k", height: h) }
                return ValidatorPages.Page(validators: [], nextKey: "", height: -1)
            }
            XCTFail("expected an error")
        } catch {}
    }

    func testLoopsAndDuplicatesAreRefused() async {
        for fetch in [
            { (_: String, _: Int64?) -> ValidatorPages.Page? in ValidatorPages.Page(validators: [], nextKey: "k", height: 5) },
            { (key: String, _: Int64?) -> ValidatorPages.Page? in ValidatorPages.Page(validators: [self.q("a")], nextKey: key == "" ? "k" : "", height: 5) },
            { (_: String, _: Int64?) -> ValidatorPages.Page? in ValidatorPages.Page(validators: [], nextKey: "", height: 0) },
        ] {
            do { _ = try await ValidatorPages.readAll(fetch); XCTFail("expected an error") } catch {}
        }
    }
}
