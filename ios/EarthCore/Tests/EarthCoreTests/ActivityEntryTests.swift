import XCTest
@testable import EarthCore

/// The activity list's rows and the detail sheet's lines, from both sources.
/// Ports ActivityEntryTest.kt.
final class ActivityEntryTests: XCTestCase {
    let me = "earth1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqme0000"
    let utc: Calendar = { var c = Calendar(identifier: .gregorian); c.timeZone = TimeZone(identifier: "UTC")!; return c }()

    func row(_ kind: PrivateActivityKind, _ coins: [ActivityCoin], party: String = "", fee: UInt64? = 2_500, hash: String? = "AB12CD34EF56AB12CD34EF56AB12CD34EF56AB12CD34EF56AB12CD34EF56AB12",
             status: PrivateActivityRow.Status = .confirmed, failure: String? = nil, time: Int64? = 1_791_400_000, exact: Bool = true) -> PrivateActivityRow {
        PrivateActivityRow(kind: kind, coins: coins, counterparty: party, fee: fee, hash: hash, height: 120_345, time: time, timeExact: exact,
                           status: status, failure: failure)
    }

    func testPrivateSwapShowsWhatCameInAndBothLegsInDetail() {
        let e = ActivityEntry(private: row(.swap, [ActivityCoin("uerth", -12_500_000), ActivityCoin("uanml", 3_250_000)]))
        XCTAssertEqual("Swapped", e.title)
        XCTAssertEqual(ActivityCoin("uanml", 3_250_000), e.primary)
        XCTAssertTrue(e.showsCoin)
        XCTAssertTrue(e.isPrivate)
        let d = e.details(calendar: utc)
        XCTAssertEqual(["Type", "Date", "You paid", "You got", "Network fee", "Fee paid by", "Block", "Privacy", "Transaction"], d.map(\.label))
        XCTAssertEqual("−12.5 ERTH", d[2].value)
        XCTAssertEqual("+3.25 ANML", d[3].value)
        XCTAssertEqual("0.0025 ERTH", d[4].value)
        XCTAssertEqual("You (private ERTH)", d[5].value)
        XCTAssertEqual("120,345", d[6].value)
        XCTAssertEqual("Private", d[7].value)
        XCTAssertEqual(e.hash, d[8].copy)
        XCTAssertEqual("AB12CD…56AB12", d[8].value)
    }

    func testSendNamesTheHandleAndStakeTheValidator() {
        let send = ActivityEntry(private: row(.send, [ActivityCoin("uanml", -700_000)], party: "@bob"))
        XCTAssertEqual("Sent", send.title)
        XCTAssertEqual(ActivityCoin("uanml", -700_000), send.primary)
        XCTAssertEqual("@bob", send.listParty(name: { $0 }))
        XCTAssertEqual(.init("To", "@bob"), send.details().first { $0.label == "To" })

        let stake = ActivityEntry(private: row(.stake, [ActivityCoin("uerth", -5_000_000), ActivityCoin("derth/earthvaloper1abc", 4_900_000)],
                                               party: "earthvaloper1abc"))
        XCTAssertEqual(ActivityCoin("uerth", -5_000_000), stake.primary)
        XCTAssertFalse(stake.showsCoin)
        XCTAssertEqual("Moss", stake.listParty(name: { _ in "Moss" }))
        XCTAssertEqual(.init("Validator", "Moss", copy: "earthvaloper1abc"), stake.details(name: { _ in "Moss" }).first { $0.label == "Validator" })

        let vote = ActivityEntry(private: row(.vote, [], party: "proposal 4"))
        XCTAssertNil(vote.primary)
        XCTAssertEqual("Proposal 4", vote.listParty(name: { $0 }))
    }

    func testReceivedNotesAndRegistration() {
        let gas = ActivityEntry(private: row(.gasGrant, [ActivityCoin("uerth", 100_000)], fee: nil, hash: nil, exact: false))
        XCTAssertEqual("Gas from Earth", gas.title)
        XCTAssertNil(gas.listParty(name: { $0 }))
        let d = gas.details(calendar: utc)
        XCTAssertFalse(d.contains { $0.label == "Transaction" || $0.label == "Network fee" || $0.label == "Fee paid by" })
        XCTAssertTrue(d.contains(.init("From", "Earth")))
        XCTAssertTrue(d.first { $0.label == "Date" }!.value.hasPrefix("About "))

        let reg = ActivityEntry(private: row(.register, [ActivityCoin("uerth", -2_500), ActivityCoin("uanml", 1_000_000), ActivityCoin("uerth", 10_000)]))
        XCTAssertEqual("Registered", reg.title)
        XCTAssertEqual(ActivityCoin("uanml", 1_000_000), reg.primary)
        XCTAssertFalse(reg.showsCoin)
    }

    func testPendingAndFailed() {
        let p = ActivityEntry(private: row(.send, [ActivityCoin("uerth", -1)], status: .pending))
        XCTAssertEqual(.pending, p.status)
        XCTAssertNil(p.shortFailure)
        let f = ActivityEntry(private: row(.send, [ActivityCoin("uerth", -1)], status: .failed, failure: "tx failed (code 11): out of gas in location: ReadFlat"))
        XCTAssertEqual("Out of gas", f.shortFailure)
        XCTAssertTrue(f.details().contains { $0.label == "Error" })
        XCTAssertEqual("Expired before a block", ActivityEntry.shortReason(PrivateActivity.neverLanded))
        XCTAssertEqual("Refused by the chain", ActivityEntry.shortReason(PrivateActivity.failedInBlock))
    }

    func testPublicTxCarriesFeeGasAndExplorerFacts() {
        var tx = Explorer.Tx(hash: String(repeating: "A", count: 64), height: 9_001, success: true, timestamp: "2026-10-07T16:41:00.123Z",
                             types: ["MsgSend"], first: ["from_address": me, "to_address": "earth1zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz9xy",
                                                         "amount": [["denom": "uerth", "amount": "1500000"]]])
        tx.fee = [ActivityCoin("uerth", 5_000)]
        tx.gasUsed = 84_123; tx.gasWanted = 200_000; tx.memo = "rent"
        let e = try! XCTUnwrap(ActivityEntry(tx: tx, self: me))
        XCTAssertEqual("Sent", e.title)
        XCTAssertFalse(e.isPrivate)
        XCTAssertEqual(ActivityCoin("uerth", -1_500_000), e.primary)
        XCTAssertEqual("earth1zzzz…z9xy", e.listParty(name: { $0 }))
        let d = e.details(calendar: utc)
        XCTAssertEqual(["Type", "Date", "Sent", "To", "Network fee", "Fee paid by", "Gas used", "Block", "Privacy", "Memo", "Transaction"], d.map(\.label))
        XCTAssertEqual("Oct 7, 2026 at 4:41 PM", d[1].value)
        XCTAssertEqual("You", d[5].value)
        XCTAssertEqual("84,123 of 200,000", d[6].value)
        XCTAssertEqual("Public", d[8].value)
        XCTAssertEqual("earth1zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz9xy", d[3].copy)

        tx.feeGranter = "earth1grantergrantergrantergrantergrant7"
        XCTAssertEqual("Fee grant from earth1gran…ant7", ActivityEntry(tx: tx, self: me)?.feePayer)

        let failed = Explorer.Tx(hash: tx.hash, height: 9_002, success: false, timestamp: "", types: ["MsgSend"], first: tx.first,
                                 code: 5, rawLog: "insufficient funds")
        let fe = ActivityEntry(tx: failed, self: me)!
        XCTAssertEqual(.failed, fe.status)
        XCTAssertEqual("Not enough funds", fe.shortFailure)
    }

    func testDaysAndClock() {
        let now: Int64 = 1_791_400_000 // 2026-10-07 19:06:40 UTC
        XCTAssertEqual("Today", ActivityEntry.dayTitle(now - 3_600, now: now, calendar: utc))
        XCTAssertEqual("Yesterday", ActivityEntry.dayTitle(now - 86_400, now: now, calendar: utc))
        XCTAssertEqual("Oct 5", ActivityEntry.dayTitle(now - 2 * 86_400, now: now, calendar: utc))
        XCTAssertEqual("Oct 7, 2025", ActivityEntry.dayTitle(now - 365 * 86_400, now: now, calendar: utc))
        XCTAssertEqual("Earlier", ActivityEntry.dayTitle(nil, now: now, calendar: utc))
        let a = ActivityEntry(private: row(.send, [], time: now - 60))
        let b = ActivityEntry(private: row(.gasGrant, [], hash: nil, time: now - 86_400))
        let c = ActivityEntry(private: row(.swap, [], hash: "C", time: now - 120))
        let days = ActivityEntry.days([a, c, b], now: now, calendar: utc)
        XCTAssertEqual(["Today", "Yesterday"], days.map(\.title))
        XCTAssertEqual(2, days[0].entries.count)
        XCTAssertEqual("~7:05 PM", ActivityEntry.clock(now - 60, exact: false, calendar: utc))
    }

    func testFigures() {
        XCTAssertEqual("1,250.5", ActivityEntry.figure(1_250_500_000))
        XCTAssertEqual("0.000001", ActivityEntry.figure(-1))
        XCTAssertEqual("12", ActivityEntry.figure(12_000_000))
        XCTAssertEqual("+2 ANML", ActivityEntry.coinText(ActivityCoin("uanml", 2_000_000)))
        XCTAssertEqual("−4.9 dERTH", ActivityEntry.coinText(ActivityCoin("derth/earthvaloper1abc", -4_900_000)))
    }

    func testMergeKeepsThePrivateRowOfATxInBoth() {
        let h = String(repeating: "B", count: 64)
        let pub = ActivityEntry(tx: Explorer.Tx(hash: h, height: 1, success: true, timestamp: "2026-10-07T10:00:00Z", types: ["MsgShield"],
                                                first: ["sender": me, "amount": ["denom": "uerth", "amount": "10"]]), self: me)!
        XCTAssertEqual("Shielded", pub.title)
        XCTAssertEqual(ActivityCoin("uerth", -10), pub.primary)
        let priv = ActivityEntry(private: row(.shield, [], hash: h.lowercased()))
        let merged = ActivityEntry.merge(public: [pub], private: [priv])
        XCTAssertEqual(1, merged.count)
        XCTAssertTrue(merged[0].isPrivate)
    }
}
