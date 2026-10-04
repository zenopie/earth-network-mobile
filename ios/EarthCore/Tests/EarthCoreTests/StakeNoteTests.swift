import BigInt
import Foundation
import XCTest
@testable import EarthCore

/// Stake notes (ORCHARD_DESIGN 20): one note per validator (a delegation,
/// an unlock and a move's credit merge into it; a first delegation pads its
/// input, a full exit creates a zero note), credits quoted at the live rate
/// with a margin (a refusal costs nothing), moving stake (MsgRedelegate)
/// with its slash label, the window that keeps moved-in stake in place
/// (refused up front, explained), the label cleared at what the slash debt
/// tree says it is worth, every stake proof naming the chain's clear_before
/// and debt root, and the debt tree read whole (the indexer's stream, the
/// chain's pages), never asked about one move.
final class StakeNoteTests: PrivacyTestCase {
    let vA = "earthvaloper1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"
    var vB: String { Vectors.json["validator2"] as! String }
    let vC = "earthvaloper1zyqszqgpqyqszqgpqyqszqgpqyqszqgpqyqszq"

    func staked(_ chain: FakeChain = FakeChain(), at: String? = nil, amount: UInt64 = 2_000_000) async throws -> PrivacyWallet {
        let a = try wallet(chain)
        for _ in 0 ..< 6 { try funded(chain, a, 3_000_000) }
        try await a.sync()
        _ = try await a.delegate(validator: at ?? vA, amount: amount); try await a.sync()
        return a
    }

    func at(_ a: PrivacyWallet, _ v: String) -> [OwnedStakeNote] { a.stakeNotes.filter { $0.spendable && $0.denom == PrivacyWallet.derthDenom(v) } }

    func f(_ b: Data) -> Fr { try! Fr(bytes: b) }

    /// Every proof names the chain's clear_before and debt root.
    func assertNamesTheDebt(_ chain: FakeChain, _ p: StakeProof, line: UInt = #line) {
        XCTAssertEqual(chain.clearBefore(), p.clearBefore, line: line)
        XCTAssertEqual(chain.debtRoot(), f(p.debtRoot), line: line)
    }

    func testAFirstDelegationPadsAndATopUpMerges() async throws {
        let chain = FakeChain()
        let a = try wallet(chain)
        for _ in 0 ..< 4 { try funded(chain, a, 3_000_000) }
        try await a.sync()
        let nfs = chain.stakeNfValues.count
        _ = try await a.delegate(validator: vA, amount: 1_000_000); try await a.sync()
        let first = try XCTUnwrap(chain.lastMsg as? MsgShieldedDelegate)
        // A padding input publishes its own nullifier: a first delegation looks like a top-up.
        XCTAssertFalse(f(first.stake.nullifiers[0]).isZero)
        XCTAssertTrue(f(first.stake.nullifiers[1]).isZero)
        XCTAssertEqual(nfs + 1, chain.stakeNfValues.count)
        XCTAssertTrue(chain.prover.allStakes.last!.ins[0].pad)
        XCTAssertEqual(first.derth, at(a, vA)[0].amount)
        assertNamesTheDebt(chain, first.stake)
        // A second delegation spends the note and creates the merged one.
        let n = at(a, vA)[0]
        _ = try await a.delegate(validator: vA, amount: 500_000); try await a.sync()
        let second = try XCTUnwrap(chain.lastMsg as? MsgShieldedDelegate)
        XCTAssertEqual(n.nf, f(second.stake.nullifiers[0]))
        XCTAssertEqual(1, at(a, vA).count)
        XCTAssertEqual(n.amount + second.derth, at(a, vA)[0].amount)
        XCTAssertTrue(a.stakeMergeable().isEmpty)
        assertNamesTheDebt(chain, second.stake)
        dump(chain, "merge")
    }

    func testAFullExitCreatesAZeroNote() async throws {
        let chain = FakeChain()
        let a = try await staked(chain)
        let n = at(a, vA)[0]
        let rows = chain.stakeRows.count
        _ = try await a.undelegate(validator: vA, amount: n.amount); try await a.sync()
        let m = try XCTUnwrap(chain.lastMsg as? MsgShieldedUndelegate)
        // The change slot holds a zero note's commitment: a full exit looks like a partial one.
        XCTAssertFalse(f(m.stake.commitment).isZero)
        XCTAssertEqual(201, m.stake.ciphertext.count)
        XCTAssertEqual(0, chain.prover.allStakes.last!.outAmount)
        XCTAssertEqual(rows + 1, chain.stakeRows.count)
        XCTAssertTrue(at(a, vA).isEmpty)
        XCTAssertTrue(a.stakeBalances().isEmpty)
        dump(chain, "fullExit")
    }

    /// While the block time is below the label window (the chain says clear_before 0) a proof names 0 and a zero debt root, as the chain requires.
    func testAYoungChainNamesNoClear() async throws {
        let chain = FakeChain()
        chain.now = 1_000_000
        let a = try await staked(chain)
        let m = try XCTUnwrap(chain.lastMsg as? MsgShieldedDelegate)
        XCTAssertEqual(0, m.stake.clearBefore)
        XCTAssertTrue(f(m.stake.debtRoot).isZero)
        _ = try await a.undelegate(validator: vA, amount: 100_000)
        XCTAssertEqual(0, (chain.lastMsg as! MsgShieldedUndelegate).stake.clearBefore)
    }

    func testTheQuoteIsWhatTheTxCredits() async throws {
        let chain = FakeChain()
        let a = try await staked(chain)
        let q = try await a.quoteDelegate(validator: vA, amount: 1_000_000)
        // floor(amount x S / B) less the drift margin.
        XCTAssertEqual(900_000 - 9, q.derth)
        XCTAssertEqual(0, q.haircut)
        _ = try await a.delegate(q); try await a.sync()
        XCTAssertEqual(q.derth, (chain.lastMsg as! MsgShieldedDelegate).derth)
    }

    func testARateThatOutranTheQuoteIsRefusedAtNoCost() async throws {
        let chain = FakeChain()
        let a = try await staked(chain)
        let q = try await a.quoteDelegate(validator: vA, amount: 1_000_000)
        let txs = chain.txs.count
        let erth = a.balances()["uerth"]!
        chain.rateDriftPPM = 50
        await assertThrowsAsync({ try await a.delegate(q) }) { e in
            let t = ChainErrors.explain(e)
            return t != nil && t!.contains("try again") && t!.contains("Nothing was spent")
        }
        XCTAssertEqual(txs, chain.txs.count)
        try await a.sync()
        XCTAssertEqual(erth, a.balances()["uerth"])
        // Within the margin (the rate moved a little): it lands.
        chain.rateDriftPPM = 5
        _ = try await a.delegate(try await a.quoteDelegate(validator: vA, amount: 1_000_000)); try await a.sync()
        XCTAssertEqual(txs + 1, chain.txs.count)
    }

    func testBelowTheMinimumIsRefusedUpFront() async throws {
        let chain = FakeChain()
        chain.minDelegation = 1_000_000
        let a = try await staked(chain)
        let sims = chain.simulated
        await assertThrowsAsync({ try await a.quoteDelegate(validator: self.vA, amount: 999_999) })
        // 1 ERTH buys 0.9 derth here: below the least derth a delegation may credit.
        await assertThrowsAsync({ try await a.quoteDelegate(validator: self.vA, amount: 1_000_000) })
        XCTAssertEqual(sims, chain.simulated)
    }

    /// A move arrives whole only out of an unbonded source's queue: from a
    /// bonded one it leaves pro rata, so the quote takes u - 1001 even when
    /// the queue covers u, and lands.
    func testAMoveArrivesWholeOnlyFromAnUnbondedQueue() async throws {
        let chain = FakeChain()
        let a = try await staked(chain)
        chain.queues[vA] = 10_000_000
        let bonded = try await a.quoteMove(src: vA, dst: vB, amount: 500_000)
        chain.unbonded.insert(vA)
        let whole = try await a.quoteMove(src: vA, dst: vB, amount: 500_000)
        XCTAssertGreaterThan(whole.dstDerth, bonded.dstDerth)
        // Quoted whole from a bonded source, the chain would refuse it.
        chain.unbonded.remove(vA)
        await assertThrowsAsync { try await a.redelegate(whole) }
        try await a.sync()
        _ = try await a.redelegate(bonded); try await a.sync()
        XCTAssertEqual(bonded.dstDerth, at(a, vB).map(\.amount).reduce(0, +))
        chain.unbonded.insert(vA)
        let again = try await a.quoteMove(src: vA, dst: vB, amount: 500_000)
        _ = try await a.redelegate(again); try await a.sync()
        XCTAssertEqual(bonded.dstDerth + again.dstDerth, at(a, vB).map(\.amount).reduce(0, +))
    }

    func testMoveStakeLabelsTheCreditAndLeavesTheChange() async throws {
        let chain = FakeChain()
        let a = try await staked(chain)
        let src = at(a, vA)[0]
        let q = try await a.quoteMove(src: vA, dst: vB, amount: 1_000_000)
        XCTAssertEqual(1_000_000 * 10 / 9, q.value)
        XCTAssertFalse(q.merges)
        XCTAssertEqual(chain.labelWindow, q.windowSeconds)
        _ = try await a.redelegate(q); try await a.sync()
        let m = try XCTUnwrap(chain.lastMsg as? MsgRedelegate)
        XCTAssertEqual(UInt64(chain.now), m.moveTime)
        XCTAssertEqual(q.dstDerth, m.dstDerth)
        assertNamesTheDebt(chain, m.stake)
        // The change at vA; the credit at vB in a note of its own, labelled with this move.
        XCTAssertEqual(src.amount - 1_000_000, at(a, vA)[0].amount)
        let dst = at(a, vB)[0]
        XCTAssertEqual(q.dstDerth, dst.amount)
        let l = try XCTUnwrap(dst.label)
        XCTAssertEqual(f(m.stake.creditNullifier), l.moveKey)
        XCTAssertEqual(m.moveTime, l.moveTime)
        XCTAssertEqual(q.dstDerth, l.exposed)
        XCTAssertNotNil(chain.moves[l.moveKey])
        // The event names the move: credited, move key, move time.
        let ev = chain.txs.values.compactMap { $0.events.first { $0.type == "shieldedstaking_redelegate" } }.first!.attributes
        XCTAssertEqual([String(q.dstDerth), l.moveKey.hex, String(m.moveTime)], [ev["credited"], ev["move_key"], ev["move_time"]])
        // A restored wallet finds the label in the note's ciphertext, and names its validators by the chain's list.
        let restored = try wallet(chain)
        try await restored.sync()
        XCTAssertEqual(dst.label, at(restored, vB)[0].label)
        XCTAssertEqual(a.stakeBalances(), restored.stakeBalances())
        dump(chain, "move")
    }

    func testAMoveMergesIntoAnUnlabelledNoteAndBesideALabelledOne() async throws {
        let chain = FakeChain()
        let a = try await staked(chain)
        _ = try await a.delegate(validator: vB, amount: 1_000_000); try await a.sync()
        let old = at(a, vB)[0]
        let q = try await a.quoteMove(src: vA, dst: vB, amount: 500_000)
        XCTAssertTrue(q.merges)
        _ = try await a.redelegate(q)
        // The destination note the credit lane spent is pending until sync sees its nullifier.
        XCTAssertTrue(at(a, vB).isEmpty)
        try await a.sync()
        let m = try XCTUnwrap(chain.lastMsg as? MsgRedelegate)
        // The credit lane spent our unlabelled note: its nullifier is the move key.
        XCTAssertEqual(old.nf, f(m.stake.creditNullifier))
        let merged = at(a, vB)[0]
        XCTAssertEqual(1, at(a, vB).count)
        XCTAssertEqual(old.amount + q.dstDerth, merged.amount)
        XCTAssertEqual(q.dstDerth, merged.label?.exposed)
        // Our note at vB is labelled now: a second move pads its lane and makes a second note there.
        let q2 = try await a.quoteMove(src: vA, dst: vB, amount: 300_000)
        XCTAssertFalse(q2.merges)
        _ = try await a.redelegate(q2); try await a.sync()
        XCTAssertEqual(2, at(a, vB).count)
        XCTAssertTrue(at(a, vB).allSatisfy { $0.label != nil })
        // Two labelled notes cannot merge until one's window closes.
        XCTAssertTrue(a.stakeMergeable().isEmpty)
        let h = await a.stakeHoldings().first { $0.validator == vB }!
        XCTAssertEqual(2, h.notes)
        XCTAssertFalse(h.mergeable)
        XCTAssertEqual(merged.amount - q.dstDerth, h.free)
        dump(chain, "moveMerge")
    }

    func testARestakeMergesALabelledNoteWithAnUnlabelledOne() async throws {
        let chain = FakeChain()
        let a = try await staked(chain)
        _ = try await a.redelegate(try await a.quoteMove(src: vA, dst: vB, amount: 500_000)); try await a.sync()
        let labelled = at(a, vB)[0]
        // Another device's unlabelled note at vB.
        chain.plantStake(a.keys, PrivacyWallet.derthDenom(vB), 70_000); try await a.sync()
        XCTAssertEqual([PrivacyWallet.derthDenom(vB): 2], a.stakeMergeable())
        _ = try await a.restake(validator: vB); try await a.sync()
        XCTAssertTrue(chain.lastMsg is MsgRestake)
        XCTAssertEqual(1, at(a, vB).count)
        XCTAssertEqual(labelled.amount + 70_000, at(a, vB)[0].amount)
        XCTAssertEqual(labelled.label, at(a, vB)[0].label)
        dump(chain, "restakeLabelled")
    }

    func testMovedStakeStaysUntilItsWindowCloses() async throws {
        let chain = FakeChain()
        let a = try await staked(chain)
        _ = try await a.delegate(validator: vB, amount: 1_000_000); try await a.sync()
        _ = try await a.redelegate(try await a.quoteMove(src: vA, dst: vB, amount: 500_000)); try await a.sync()
        let n = at(a, vB)[0]
        let l = try XCTUnwrap(n.label)
        let sims = chain.simulated
        // Refused up front, with the date it may move again.
        let until = PrivacyWallet.dateText(l.moveTime + chain.labelWindow)
        func held(_ e: Error) -> Bool { ((e as? NoteSelection.Insufficient)?.message ?? "").contains("moved stake can move again after \(until)") }
        await assertThrowsAsync({ try await a.undelegate(validator: self.vB, amount: n.amount) }, held)
        await assertThrowsAsync({ try await a.quoteMove(src: self.vB, dst: self.vC, amount: n.amount) }, held)
        await assertThrowsAsync({ try await a.lockPosition(validator: self.vB, amount: n.amount, splits: [1: 100]) }, held)
        XCTAssertEqual(sims, chain.simulated)
        let h = await a.stakeHoldings().first { $0.validator == vB }!
        XCTAssertEqual(n.amount - l.exposed, h.free)
        XCTAssertEqual(l.exposed, h.locked)
        XCTAssertEqual(l.moveTime + chain.labelWindow, h.lockedUntil)
        // The rest of the note moves freely, keeping the label on the change.
        _ = try await a.undelegate(validator: vB, amount: n.amount - l.exposed); try await a.sync()
        let change = at(a, vB)[0]
        XCTAssertEqual(l, change.label)
        XCTAssertEqual(l.exposed, change.amount)
        XCTAssertFalse(chain.prover.allStakes.last!.clear)
        dump(chain, "window")
    }

    func testTheLabelClearsOnceTheWindowCloses() async throws {
        let chain = FakeChain()
        let a = try await staked(chain)
        _ = try await a.redelegate(try await a.quoteMove(src: vA, dst: vB, amount: 500_000)); try await a.sync()
        let n = at(a, vB)[0]
        chain.now = Int64(n.label!.moveTime + chain.labelWindow + 1)
        chain.emptyBlock(); try await a.sync()
        let free = await a.stakeHoldings().first { $0.validator == vB }!.free
        XCTAssertEqual(n.amount, free)
        let cut = try await a.leaveHaircut(validator: vB, amount: 100_000)
        XCTAssertEqual(0, cut)
        _ = try await a.undelegate(validator: vB, amount: 100_000); try await a.sync()
        let w = chain.prover.allStakes.last!
        XCTAssertTrue(w.clear)
        XCTAssertNil(w.outLabel)
        let left = at(a, vB)[0]
        XCTAssertNil(left.label)
        XCTAssertEqual(n.amount - 100_000, left.amount)
        dump(chain, "clear")
    }

    func testASlashedMoveClearsAtWhatItRetains() async throws {
        let chain = FakeChain()
        let a = try await staked(chain)
        _ = try await a.redelegate(try await a.quoteMove(src: vA, dst: vB, amount: 500_000)); try await a.sync()
        let n = at(a, vB)[0]
        let l = try XCTUnwrap(n.label)
        let retained = l.exposed * 7 / 10
        chain.slashMove(l.moveKey, retained: retained)
        chain.now = Int64(l.moveTime + chain.labelWindow + 1)
        chain.emptyBlock(); try await a.sync()
        // The rows come whole from the indexer's stream, checked against the chain's root.
        chain.debtAsks = []
        let cut = try await a.leaveHaircut(validator: vB, amount: 10_000)
        XCTAssertEqual(l.exposed - retained, cut)
        XCTAssertTrue(chain.debtAsks.contains("indexer:0"), "\(chain.debtAsks)")
        let free = await a.stakeHoldings().first { $0.validator == vB }!.free
        XCTAssertEqual(n.amount - cut, free)
        // A sheet that showed no cut does not send one.
        await assertThrowsAsync({ try await a.undelegate(validator: self.vB, amount: 10_000, maxHaircut: 0) }) { $0 is PrivacyWallet.QuoteChanged }
        _ = try await a.undelegate(validator: vB, amount: 10_000, maxHaircut: cut); try await a.sync()
        XCTAssertEqual(n.amount - cut - 10_000, at(a, vB)[0].amount)
        XCTAssertTrue(chain.prover.allStakes.last!.clear)
        // Every read of the debt tree is a whole page from its start: nothing names this wallet's move.
        XCTAssertTrue(chain.debtAsks.allSatisfy { $0 == "indexer:0" || $0 == "chain:0" }, "\(chain.debtAsks)")
        dump(chain, "slashed")
    }

    func testAForgedOrMissingDebtStreamFallsBackToTheChain() async throws {
        let chain = FakeChain()
        let a = try await staked(chain)
        _ = try await a.redelegate(try await a.quoteMove(src: vA, dst: vB, amount: 500_000)); try await a.sync()
        let l = try XCTUnwrap(at(a, vB)[0].label)
        chain.slashMove(l.moveKey, retained: l.exposed / 2)
        chain.now = Int64(l.moveTime + chain.labelWindow + 1)
        chain.emptyBlock(); try await a.sync()
        chain.forgeDebtRetained = l.exposed
        let cut = try await a.leaveHaircut(validator: vB, amount: 10_000)
        XCTAssertEqual(l.exposed - l.exposed / 2, cut)
        chain.forgeDebtRetained = nil
        chain.indexerDebtRows = false
        let b = try wallet(chain)
        try await b.sync()
        let cutB = try await b.leaveHaircut(validator: vB, amount: 10_000)
        XCTAssertEqual(l.exposed - l.exposed / 2, cutB)
        XCTAssertGreaterThanOrEqual(chain.debtAsks.filter { $0.hasPrefix("chain:") }.count, 2)
    }

    /// The proof's owner tag is fresh on every msg that is not a position's.
    func testOwnerTagsAreFreshOffPositions() async throws {
        let chain = FakeChain()
        let a = try await staked(chain)
        _ = try await a.delegate(validator: vA, amount: 500_000); try await a.sync()
        _ = try await a.undelegate(validator: vA, amount: 100_000); try await a.sync()
        _ = try await a.redelegate(try await a.quoteMove(src: vA, dst: vB, amount: 500_000)); try await a.sync()
        let tags = chain.prover.allStakes.map(\.otag)
        XCTAssertEqual(tags.count, Set(tags).count)
    }

    func testTheMsgShapesAreTheChains() {
        XCTAssertEqual("/earth.shieldedstaking.v1.MsgRedelegate", MsgRedelegate.typeURL)
        XCTAssertEqual(DebtTree.emptyRoot, FakeChain().debtRoot())
    }
}
