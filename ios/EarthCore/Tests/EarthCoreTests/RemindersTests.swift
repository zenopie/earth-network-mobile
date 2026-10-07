import BigInt
import Foundation
import XCTest
@testable import EarthCore

/// The wallet sends nothing on its own: the day's claim, the caretaker vote
/// and the handle are reminders the user acts on, and a sync (payouts,
/// pending votes and undelegations included) never broadcasts or simulates.
final class RemindersTests: PrivacyTestCase {
    let day: Int64 = 20_000
    var now: Int64 { day * 86_400 + 5 * 3600 }

    /// The wallet sends nothing on its own. The day's claim, the caretaker
    /// vote and the handle are reminders; an undelegation pays out by itself
    /// (nothing to claim).
    func testRecurringActionsAreRemindersNotTxs() {
        let base = Reminders.Inputs(now: now, identityLive: true, claimOpensAt: 0, claimedToday: false, caretakerExpiresAt: 0, handle: "", handleEntry: nil)
        XCTAssertEqual([.anmlReady], Reminders.due(base))
        var i = base; i.claimedToday = true; XCTAssertTrue(Reminders.due(i).isEmpty)
        i = base; i.claimOpensAt = now + 100; XCTAssertTrue(Reminders.due(i).isEmpty)
        i = base; i.identityLive = false; XCTAssertTrue(Reminders.due(i).isEmpty)
        // The caretaker vote: from 30 days before it lapses, and for 30 days after.
        var q = base; q.claimedToday = true
        i = q; i.caretakerExpiresAt = now + Reminders.leadSeconds + 1; XCTAssertTrue(Reminders.due(i).isEmpty)
        i = q; i.caretakerExpiresAt = now + 86_400; XCTAssertEqual([.caretakerExpiring(expiresAt: now + 86_400, lapsed: false)], Reminders.due(i))
        i = q; i.caretakerExpiresAt = now - 86_400; XCTAssertEqual([.caretakerExpiring(expiresAt: now - 86_400, lapsed: true)], Reminders.due(i))
        i = q; i.caretakerExpiresAt = now - Reminders.lapsedSeconds - 1; XCTAssertTrue(Reminders.due(i).isEmpty)
        // The handle: from 30 days before expiry, through the renewal period.
        let e = HandleEntry(handle: "alice", address: "erthz1x", status: "live", expiresAt: now + 10 * 86_400, renewalUntil: now + 40 * 86_400)
        q.handle = "alice"
        i = q; i.handleEntry = e
        XCTAssertEqual([.handleExpiring(handle: "alice", expiresAt: e.expiresAt, renewalUntil: e.renewalUntil, inRenewal: false)], Reminders.due(i))
        let r = HandleEntry(handle: "alice", address: "erthz1x", status: "renewal", expiresAt: now - 86_400, renewalUntil: now + 29 * 86_400)
        i = q; i.handleEntry = r
        XCTAssertEqual([.handleExpiring(handle: "alice", expiresAt: r.expiresAt, renewalUntil: r.renewalUntil, inRenewal: true)], Reminders.due(i))
        i = q; i.handleEntry = HandleEntry(handle: "alice", address: "x", status: "live", expiresAt: now + 200 * 86_400, renewalUntil: now + 230 * 86_400)
        XCTAssertTrue(Reminders.due(i).isEmpty)
        i = q; i.handleEntry = HandleEntry(handle: "alice", address: "x", status: "free", expiresAt: now - 40 * 86_400, renewalUntil: now - 1)
        XCTAssertTrue(Reminders.due(i).isEmpty)
        // A served "live" whose expiry passed by our clock is in its renewal period.
        i = q; i.handleEntry = HandleEntry(handle: "alice", address: "x", status: "live", expiresAt: now - 5, renewalUntil: now + 86_400)
        if case let .handleExpiring(_, _, _, inRenewal) = Reminders.due(i).first { XCTAssertTrue(inRenewal) } else { XCTFail("no reminder") }
        XCTAssertTrue(Reminders.text(.anmlReady, now: now).contains("ANML"))
    }

    /// Sync, payouts, pending votes and undelegations: none of it ever broadcasts.
    func testASyncNeverSendsAnything() async throws {
        let chain = FakeChain()
        let a = try await delegatedWallet(chain)
        _ = try await a.undelegate(validator: validator, amount: 100_000)
        chain.openProposal(10)
        let broadcasts = chain.txs.count
        let sims = chain.simulated
        for _ in 0 ..< 3 { try await a.sync() }
        chain.payUnbonds()
        chain.now += 40 * 86_400
        for _ in 0 ..< 3 { try await a.sync() }
        XCTAssertEqual(broadcasts, chain.txs.count)
        XCTAssertEqual(sims, chain.simulated)
    }
    /// The move reminder grows urgent as the deadline nears. As Android.
    func testTheMoveReminderEscalates() {
        let now: Int64 = 20_000 * 86_400
        XCTAssertTrue(Reminders.text(.moveSuggested(at: now - 1, deadline: now + 20 * 86_400), now: now).contains("within 20 days"))
        XCTAssertTrue(Reminders.text(.moveSuggested(at: now - 1, deadline: now + 2 * 86_400), now: now)
            .hasPrefix("Move your handle and caretaker vote from your previous identity now"))
        XCTAssertTrue(Reminders.text(.moveSuggested(at: now - 1), now: now).contains("suggested time"))
    }

    /// Before the registration's year ends: a handle or caretaker vote whose
    /// lease ends within renewFirstWindowSeconds after it is to be renewed
    /// first (the live identity can; after the lapse it cannot). As Android.
    func testRenewBeforeTheRegistrationLapses() {
        let now: Int64 = 20_000 * 86_400
        let end = now + 10 * 86_400
        var base = Reminders.Inputs(now: now, identityLive: true, claimOpensAt: nil, claimedToday: true, caretakerExpiresAt: 0,
                                    handle: "alice", handleEntry: nil, registrationEndsAt: end, handleExpiresAt: end + 5 * 86_400)
        let r = Reminders.due(base).first { if case .renewBeforeLapse = $0 { return true } else { return false } }
        XCTAssertEqual(r, .renewBeforeLapse(registrationEndsAt: end, handle: "alice", handleExpiresAt: end + 5 * 86_400, voteExpiresAt: 0))
        XCTAssertTrue(Reminders.text(r!, now: now).hasPrefix("Your registration ends in 10 days. Renew @alice before then"))
        func none(_ i: Reminders.Inputs) -> Bool { !Reminders.due(i).contains { if case .renewBeforeLapse = $0 { return true } else { return false } } }
        var i = base; i.handleExpiresAt = end + PrivacyWallet.renewFirstWindowSeconds
        XCTAssertTrue(none(i))
        i = base; i.registrationEndsAt = now + Reminders.leadSeconds + 1
        XCTAssertTrue(none(i))
        base.handle = ""; base.caretakerExpiresAt = end + 86_400
        let v = Reminders.due(base).first { if case .renewBeforeLapse = $0 { return true } else { return false } }
        XCTAssertTrue(Reminders.text(v!, now: now).contains("Renew your caretaker vote before then"))
    }
}
