import Foundation
import XCTest
@testable import EarthCore

/// Groundworks votes by stake note: the split rides every stake tx onto the
/// note it makes, and a spend cancels the vote. (Since v1.3.0 a vote has no
/// lease: it ends with its vintage, VintageFlowTests.)
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

    func held(_ a: PrivacyWallet, _ v: String) -> UInt64 {
        a.stakeNotes.filter { $0.spendable && $0.denom == PrivacyWallet.derthDenom(v) }.map(\.amount).reduce(0, +)
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
        dump(chain, "gwFollows")

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
        dump(chain, "gwPending")
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
}
