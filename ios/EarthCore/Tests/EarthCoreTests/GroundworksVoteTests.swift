import Foundation
import XCTest
@testable import EarthCore

/// Groundworks votes by stake note: the split rides every stake tx onto the
/// note it makes, a spend cancels the vote, and a vote lapses after its lease.
/// Ports `GroundworksVotesTest`.
final class GroundworksVoteTests: PrivacyTestCase {
    let vA = "earthvaloper1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"
    var vB: String { Vectors.json["validator2"] as! String }
    let day: Int64 = 20_000
    var now: Int64 { day * 86_400 + 5 * 3600 }

    func staked(_ chain: FakeChain) async throws -> PrivacyWallet {
        let a = try wallet(chain)
        for _ in 0 ..< 6 { try funded(chain, a, 3_000_000) }
        try await a.sync()
        _ = try await a.delegate(validator: vA, amount: 2_000_000); try await a.sync()
        return a
    }

    func lease(_ a: PrivacyWallet, read: Bool = true) async throws -> Reminders.GroundworksLease {
        let votes = read ? try await a.groundworksVotes() : []
        let l = await a.groundworksLease(votes)
        return try XCTUnwrap(l)
    }

    func held(_ a: PrivacyWallet, _ v: String) -> UInt64 {
        a.stakeNotes.filter { $0.spendable && $0.denom == PrivacyWallet.derthDenom(v) }.map(\.amount).reduce(0, +)
    }

    /// Reminded as the caretaker vote is (from 30 days before its lease end, for 30 days after),
    /// and never while the lease end is unknown.
    func testGroundworksLeaseReminders() {
        let q = Reminders.Inputs(now: now, identityLive: false, claimOpensAt: nil, claimedToday: true, caretakerExpiresAt: 0, handle: "", handleEntry: nil)
        func due(_ g: Reminders.GroundworksLease?) -> [Reminders.Reminder] { var i = q; i.groundworks = g; return Reminders.due(i) }
        func lease(_ e: Int64, _ held: Bool, _ split: [UInt64: UInt64] = [2: 100]) -> Reminders.GroundworksLease {
            Reminders.GroundworksLease(expiresAt: e, held: held, split: split)
        }
        XCTAssertTrue(due(nil).isEmpty)
        XCTAssertTrue(due(lease(now + Reminders.leadSeconds + 1, true)).isEmpty)
        XCTAssertEqual([.groundworksExpiring(expiresAt: now + 7 * 86_400, lapsed: false)], due(lease(now + 7 * 86_400, true)))
        XCTAssertEqual([.groundworksExpiring(expiresAt: now + Reminders.leadSeconds, lapsed: false)], due(lease(now + Reminders.leadSeconds, true)))
        // Past its end, deleted by the chain or not yet: lapsed.
        XCTAssertEqual([.groundworksExpiring(expiresAt: now, lapsed: true)], due(lease(now, true)))
        XCTAssertEqual([.groundworksExpiring(expiresAt: now - 86_400, lapsed: true)], due(lease(now - 86_400, false)))
        XCTAssertTrue(due(lease(now - Reminders.lapsedSeconds - 1, false)).isEmpty)
        // Unknown lease end: no reminder.
        XCTAssertTrue(due(lease(0, true)).isEmpty)
        XCTAssertTrue(lease(0, false).lapsed(now))
        XCTAssertFalse(lease(0, true).lapsed(now))
        // Hostile times saturate.
        var h = q; h.now = .min; h.groundworks = lease(.max, true); _ = Reminders.due(h)
        _ = Reminders.text(.groundworksExpiring(expiresAt: .max, lapsed: false), now: .min)
        XCTAssertTrue(Reminders.text(.groundworksExpiring(expiresAt: now + 7 * 86_400, lapsed: false), now: now).contains("expires in 7 days"))
        XCTAssertTrue(Reminders.text(.groundworksExpiring(expiresAt: now - 1, lapsed: true), now: now).contains("Vote again"))
    }

    /// Casting votes all our stake in one tx per validator; every later stake
    /// tx carries the split onto the note it makes, so the vote follows the
    /// stake: a top-up adds to it, an unstake shrinks it, a move leaves the
    /// change voting. Stopping cancels it.
    func testVoteFollowsTheStake() async throws {
        let chain = FakeChain()
        chain.minGroundworksVote = 100_000
        let a = try await staked(chain)
        XCTAssertTrue(chain.gwVotes.isEmpty)

        _ = try await a.castGroundworks(split: [2: 60, 3: 40])
        var mine = try await a.groundworksVotes()
        XCTAssertEqual(1, mine.count)
        XCTAssertEqual(vA, mine[0].validator)
        XCTAssertEqual(held(a, vA), mine[0].derth)
        XCTAssertEqual([2: 60, 3: 40], mine[0].split)
        XCTAssertEqual(chain.now + chain.groundworksLease, mine[0].expiresAt)

        // A top-up: the merged note votes it all, one vote.
        _ = try await a.delegate(validator: vA, amount: 1_000_000); try await a.sync()
        mine = try await a.groundworksVotes()
        XCTAssertEqual(1, chain.gwVotes.count)
        XCTAssertEqual(held(a, vA), mine[0].derth)

        // An unstake: the change votes.
        _ = try await a.undelegate(validator: vA, amount: 500_000); try await a.sync()
        mine = try await a.groundworksVotes()
        XCTAssertEqual(1, chain.gwVotes.count)
        XCTAssertEqual(held(a, vA), mine[0].derth)

        // A move: the change at A votes; the credit at B votes too, pending
        // (exposed until its move's window closes), and then counts by
        // itself: nothing to re-cast.
        _ = try await a.redelegate(try await a.quoteMove(src: vA, dst: vB, amount: 500_000)); try await a.sync()
        mine = try await a.groundworksVotes()
        XCTAssertEqual(Set([vA, vB]), Set(mine.map(\.validator)))
        XCTAssertEqual(held(a, vA), mine.first { $0.validator == vA }!.derth)
        let atB = mine.first { $0.validator == vB }!
        XCTAssertEqual(0, atB.derth)
        XCTAssertEqual(held(a, vB), atB.pending)
        XCTAssertGreaterThan(atB.maturesAt, chain.now)
        let txs = chain.txs.count
        chain.now = atB.maturesAt
        chain.matureVotes()
        let matured = try await a.groundworksVotes().first { $0.validator == vB }!
        XCTAssertEqual(held(a, vB), matured.derth)
        XCTAssertEqual(0, matured.pending)
        XCTAssertEqual(txs, chain.txs.count, "the chain counts it; the wallet sent nothing")

        // Stop: the split cleared, the vote cancelled.
        _ = try await a.castGroundworks(split: [:])
        XCTAssertTrue(chain.gwVotes.isEmpty)
        XCTAssertTrue(a.groundworksSplit.isEmpty)
        // And a later stake tx votes nothing.
        _ = try await a.delegate(validator: vA, amount: 1_000_000); try await a.sync()
        XCTAssertTrue(chain.gwVotes.isEmpty)
    }

    /// A vote changed while moved stake is still exposed keeps its pending
    /// part: the restake keeps the label and publishes it (lane A's pending).
    func testChangingTheVoteKeepsThePendingPart() async throws {
        let chain = FakeChain()
        chain.minGroundworksVote = 100_000
        let a = try await staked(chain)
        _ = try await a.castGroundworks(split: [2: 100])
        _ = try await a.redelegate(try await a.quoteMove(src: vA, dst: vB, amount: 500_000)); try await a.sync()
        _ = try await a.castGroundworks(split: [3: 100])
        let atB = try await a.groundworksVotes().first { $0.validator == self.vB }!
        XCTAssertEqual([3: 100], atB.split)
        XCTAssertEqual(held(a, vB), atB.pending)
        XCTAssertNotNil(chain.prover.allStakes.last { $0.pEx > 0 }, "lane A published the kept label's exposure")
        chain.now = atB.maturesAt
        chain.matureVotes()
        let matured = try await a.groundworksVotes().first { $0.validator == self.vB }!
        XCTAssertEqual(held(a, vB), matured.derth)
    }

    /// Notes made apart (another device; delegations without a sync between)
    /// merge as the vote is cast, until all of the stake votes as one note.
    func testCastingMergesEveryNote() async throws {
        let chain = FakeChain()
        chain.minGroundworksVote = 100_000
        let a = try wallet(chain)
        for _ in 0 ..< 6 { try funded(chain, a, 3_000_000) }
        try await a.sync()
        for _ in 0 ..< 3 { _ = try await a.delegate(validator: vA, amount: 1_000_000) }
        try await a.sync()
        XCTAssertEqual(3, a.stakeNotes.filter(\.spendable).count)
        let rs = try await a.castGroundworks(split: [2: 100])
        XCTAssertEqual(2, rs.count)
        XCTAssertEqual(1, a.stakeNotes.filter(\.spendable).count)
        let votes = try await a.groundworksVotes()
        XCTAssertEqual([held(a, vA)], votes.map(\.derth))
    }

    /// A split naming an option the fund has since removed: the chain would
    /// refuse it, so stake moves go on without the vote (and the wallet says so).
    func testARemovedOptionStopsTheVoteNotTheStake() async throws {
        let chain = FakeChain()
        chain.minGroundworksVote = 100_000
        let a = try await staked(chain)
        _ = try await a.castGroundworks(split: [2: 60, 3: 40])
        XCTAssertEqual(1, chain.gwVotes.count)
        chain.gwOptions = [2]
        let live = await a.groundworksSplitLive([2: 60, 3: 40])
        XCTAssertEqual(false, live)
        _ = try await a.delegate(validator: vA, amount: 1_000_000); try await a.sync()
        XCTAssertTrue(chain.gwVotes.isEmpty)
        // Casting (or renewing) it again is refused up front: nothing sent.
        let txs = chain.txs.count
        await assertThrowsAsync { _ = try await a.castGroundworks(split: [2: 60, 3: 40]) }
        XCTAssertEqual(txs, chain.txs.count)
        // A new split votes again.
        _ = try await a.castGroundworks(split: [2: 100])
        XCTAssertEqual(1, chain.gwVotes.count)
    }

    /// A read the vote needs failing refuses the tx: never sent without the
    /// vote (its input's tag would cancel it).
    func testAFailedReadRefusesRatherThanCancels() async throws {
        let chain = FakeChain()
        chain.minGroundworksVote = 100_000
        let a = try await staked(chain)
        _ = try await a.castGroundworks(split: [2: 100])
        let txs = chain.txs.count
        chain.failMinVoteRead = true
        await assertThrowsAsync { try await a.delegate(validator: self.vA, amount: 1_000_000) }
        XCTAssertEqual(txs, chain.txs.count)
        XCTAssertEqual(1, chain.gwVotes.count)
        chain.failMinVoteRead = false
        _ = try await a.delegate(validator: vA, amount: 1_000_000); try await a.sync()
        XCTAssertEqual(1, chain.gwVotes.count)
    }

    /// Stake below the least vote weight cannot vote (the chain refuses
    /// less): casting is refused before any fee.
    func testBelowTheMinimumDoesNotVote() async throws {
        let chain = FakeChain()
        chain.minGroundworksVote = 100_000_000
        let a = try await staked(chain)
        // Refused up front: nothing could vote, so nothing is sent.
        let txs = chain.txs.count
        await assertThrowsAsync { _ = try await a.castGroundworks(split: [2: 100]) }
        XCTAssertEqual(txs, chain.txs.count)
        XCTAssertTrue(chain.gwVotes.isEmpty)
    }

    /// The lease: renewed by casting again, still known after the chain
    /// deletes a lapsed vote, with the split to cast again. Nothing is sent
    /// unless the wallet is asked.
    func testLeaseRenewsAndLapses() async throws {
        let chain = FakeChain()
        chain.minGroundworksVote = 100_000
        chain.groundworksLease = 40 * 86_400
        let a = try await staked(chain)
        _ = try await a.castGroundworks(split: [2: 100])
        let l0 = try await lease(a)
        XCTAssertEqual(chain.now + chain.groundworksLease, l0.expiresAt)
        XCTAssertTrue(l0.held && !l0.lapsed(chain.now) && !l0.renewalDue(chain.now))

        // A week before the end: due, and nothing was sent on its own.
        chain.now = l0.expiresAt - 7 * 86_400
        let txs = chain.txs.count
        let l1 = try await lease(a)
        XCTAssertTrue(l1.renewalDue(chain.now))
        XCTAssertEqual(txs, chain.txs.count)
        // Renew: the same split, a new lease from now.
        _ = try await a.castGroundworks(split: l1.split)
        let l2 = try await lease(a)
        XCTAssertEqual(chain.now + chain.groundworksLease, l2.expiresAt)
        XCTAssertEqual([2: 100], l2.split)

        // The lapse: the chain deletes the vote; the wallet still knows when, and what it was.
        chain.now = l2.expiresAt
        chain.lapseSplits()
        let gone = try await a.groundworksVotes()
        XCTAssertTrue(gone.isEmpty)
        let l3 = try await lease(a, read: false)
        XCTAssertTrue(!l3.held && l3.lapsed(chain.now))
        XCTAssertEqual(l2.expiresAt, l3.expiresAt)
        XCTAssertEqual([2: 100], l3.split)
        XCTAssertEqual(.groundworksExpiring(expiresAt: l2.expiresAt, lapsed: true), Reminders.groundworks(l3, now: chain.now))
        // Survives an encode/decode of the store, and a re-cast counts again.
        let decoded = try JSONDecoder().decode(PrivacyState.self, from: try JSONEncoder().encode(a.store.state))
        XCTAssertEqual(l2.expiresAt, decoded.groundworksExpiresAt)
        XCTAssertEqual([2: 100], decoded.groundworksSplit)
        _ = try await a.castGroundworks(split: l3.split)
        let l4 = try await lease(a)
        XCTAssertTrue(l4.held && !l4.lapsed(chain.now))
        // Stopped: no lease.
        _ = try await a.castGroundworks(split: [:])
        let none = await a.groundworksLease([])
        XCTAssertNil(none)
    }
}
