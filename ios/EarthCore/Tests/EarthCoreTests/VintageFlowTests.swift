import BigInt
import Foundation
import XCTest
@testable import EarthCore

/// Stake vintages (chain v1.3.0) end to end against FakeChain: a delegation
/// credits the current month (one note per validator and vintage, one rate
/// per validator), a check-in moves an older vintage's stake into the
/// current one (its moved-in exposure staying behind while its window is
/// open, the Groundworks vote carried), a retired vintage only withdraws
/// (out of selection, merges, Groundworks and stake votes), and a restored
/// wallet names its suffixed notes. Every msg names its vintage explicitly.
final class VintageFlowTests: PrivacyTestCase {
    let vA = "earthvaloper1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"
    var vB: String { Vectors.json["validator2"] as! String }
    /// FakeChain's clock starts in Sep 2026: the v1.3.0 upgrade month here.
    let base: UInt32 = 202609

    func chain() -> FakeChain {
        let c = FakeChain()
        c.vintageBase = base
        XCTAssertEqual(base, Vintage.monthOf(c.now))
        return c
    }

    func funded(_ chain: FakeChain, _ words: String? = nil) async throws -> PrivacyWallet {
        let a = try wallet(chain, words)
        for _ in 0 ..< 8 { try funded(chain, a, 3_000_000) }
        try await a.sync()
        return a
    }

    /// Moves the chain's clock into `month` (a day and an hour in), ending a block there.
    func enter(_ chain: FakeChain, _ month: UInt32) {
        chain.now = Vintage.start(month) + 86_400 + 3_600
        chain.emptyBlock()
    }

    func notes(_ a: PrivacyWallet, _ denom: String) -> [OwnedStakeNote] { a.stakeNotes.filter { $0.spendable && $0.denom == denom } }
    func sum(_ ns: [OwnedStakeNote]) -> UInt64 { ns.map(\.amount).reduce(0, +) }
    func f(_ b: Data) -> Fr { try! Fr(bytes: b) }

    /// A delegation names the current month and credits its denom (the
    /// unsuffixed one in the base month); a later month's is a note of its
    /// own, never merged with the older vintage. One line per validator.
    func testDelegationCreditsTheCurrentVintage() async throws {
        let chain = chain()
        defer { dump(chain, "vDelegate") }
        let a = try await funded(chain)
        _ = try await a.delegate(validator: vA, amount: 1_000_000); try await a.sync()
        let first = try XCTUnwrap(chain.lastMsg as? MsgShieldedDelegate)
        XCTAssertEqual(base, first.vintage)
        XCTAssertEqual(first.derth, sum(notes(a, PrivacyWallet.derthDenom(vA))))
        XCTAssertEqual(first.derth, chain.vintageSupply[vA]?[base])

        enter(chain, 202611)
        _ = try await a.delegate(validator: vA, amount: 500_000); try await a.sync()
        let second = try XCTUnwrap(chain.lastMsg as? MsgShieldedDelegate)
        XCTAssertEqual(202611, second.vintage)
        // A first note in the new vintage: both inputs padded, the old note untouched.
        XCTAssertTrue(chain.prover.allStakes.last!.ins.allSatisfy(\.pad))
        XCTAssertEqual(second.derth, sum(notes(a, "derth/\(vA)/202611")))
        XCTAssertEqual(first.derth, sum(notes(a, PrivacyWallet.derthDenom(vA))))

        let hs0 = await a.stakeHoldings()
        let h = try XCTUnwrap(hs0.first)
        XCTAssertEqual(vA, h.validator)
        XCTAssertEqual([base, 202611], h.vintages.map(\.month))
        XCTAssertEqual(first.derth + second.derth, h.derth)
        XCTAssertEqual(Vintage.retiresAt(base), h.vintages[0].retiresAt)
        XCTAssertEqual(Vintage.start(202710), h.vintages[0].retiresAt)
        XCTAssertFalse(h.mergeable)
        XCTAssertEqual(Vintage.retiresAt(base), PrivacyWallet.checkInBy([h]))
        let lines = StakeRound.lines(notes: a.stakeNotes, rate: { _ in 1 }, vintages: a.localVintages)
        XCTAssertEqual(1, lines.count)
        XCTAssertEqual(first.derth + second.derth, lines[0].derth)
        // The store remembers the chain's base and current month.
        XCTAssertEqual(VintageCtx(base: base, current: 202611), a.localVintages)
    }

    /// A quote from last month: in the new month's first hour (the chain's
    /// grace) it is sent as it stands, naming last month; after the hour it
    /// is shown again rather than sent into a vintage the chain refuses.
    func testAQuoteFromLastMonthCreditsTheNewMonth() async throws {
        let chain = chain()
        let a = try await funded(chain)
        let oct = Vintage.start(202610)
        let q = try await a.quoteDelegate(validator: vA, amount: 1_000_000)
        XCTAssertEqual(base, q.vintage)
        chain.now = oct + 1_200
        chain.emptyBlock()
        _ = try await a.delegate(q); try await a.sync()
        let m = try XCTUnwrap(chain.lastMsg as? MsgShieldedDelegate)
        XCTAssertEqual(base, m.vintage)
        XCTAssertEqual(q.derth, m.derth)
        XCTAssertEqual(q.derth, chain.vintageSupply[vA]?[base])

        // Past the hour: the old quote is shown again, nothing sent; a new one credits October.
        let q2 = try await a.quoteDelegate(validator: vA, amount: 1_000_000)
        XCTAssertEqual(202610, q2.vintage)
        let late = PrivacyWallet.DelegateQuote(validator: vA, amount: 1_000_000, derth: q2.derth, haircut: 0, vintage: base)
        chain.now = oct + Vintage.graceSeconds
        chain.emptyBlock()
        let txs = chain.txs.count
        await assertThrowsAsync({ try await a.delegate(late) }) { $0 is PrivacyWallet.QuoteChanged }
        XCTAssertEqual(txs, chain.txs.count)
        _ = try await a.delegate(try await a.quoteDelegate(validator: vA, amount: 1_000_000)); try await a.sync()
        XCTAssertEqual(202610, (chain.lastMsg as? MsgShieldedDelegate)?.vintage)
    }

    /// The month-boundary grace: a proof built just before 00:00 UTC on the
    /// 1st and included in the next month's first hour lands in the vintage
    /// it was built for, each credit naming its month explicitly (a
    /// delegation's vintage, a move's and a check-in's credit_vintage); past
    /// the hour the chain refuses it.
    func testAProofBuiltBeforeTheBoundaryLandsInTheGrace() async throws {
        let chain = chain()
        defer { dump(chain, "vGrace") }
        let a = try await funded(chain)
        _ = try await a.delegate(validator: vA, amount: 2_000_000); try await a.sync()
        let dec = Vintage.start(202612)
        func lateNov() { chain.now = dec - 60; chain.emptyBlock() }

        // A move: the credit lane proves vB's Nov asset; included 5 minutes into Dec (move_time within 600 s).
        enter(chain, 202611)
        lateNov()
        chain.beforeBroadcast = { chain.now = dec + 300 }
        _ = try await a.redelegate(try await a.quoteMove(src: vA, dst: vB, amount: 300_000)); try await a.sync()
        let mv = try XCTUnwrap(chain.lastMsg as? MsgRedelegate)
        XCTAssertEqual(base, mv.vintage)
        XCTAssertEqual(202611, mv.creditVintage)
        XCTAssertEqual(PrivacyHash.u64(202611), try mv.sighashFields().last)
        XCTAssertEqual(mv.dstDerth, chain.vintageSupply[vB]?[202611])
        XCTAssertEqual(mv.dstDerth, sum(notes(a, "derth/\(vB)/202611")))

        // A delegation and a check-in, both built in Nov, included in Dec's first hour.
        lateNov()
        let q = try await a.quoteDelegate(validator: vA, amount: 500_000)
        chain.beforeBroadcast = { chain.now = dec + 3000 }
        _ = try await a.delegate(q); try await a.sync()
        XCTAssertEqual(202611, (chain.lastMsg as? MsgShieldedDelegate)?.vintage)
        XCTAssertEqual(q.derth, chain.vintageSupply[vA]?[202611])
        lateNov()
        chain.beforeBroadcast = { chain.now = dec + 3000 }
        _ = try await a.checkIn(denom: PrivacyWallet.derthDenom(vA)); try await a.sync()
        let ci = try XCTUnwrap(chain.lastMsg as? MsgCheckIn)
        XCTAssertEqual(base, ci.fromVintage)
        XCTAssertEqual(202611, ci.creditVintage)
        XCTAssertEqual(0, chain.vintageSupply[vA]?[base])

        // Past the hour: refused, nothing lands.
        lateNov()
        let q2 = try await a.quoteDelegate(validator: vB, amount: 500_000)
        let before = chain.vintageSupply[vB]?[202611]
        chain.beforeBroadcast = { chain.now = dec + Vintage.graceSeconds }
        await assertThrowsAsync({ try await a.delegate(q2) })
        XCTAssertEqual(before, chain.vintageSupply[vB]?[202611])
        XCTAssertNil(chain.vintageSupply[vB]?[202612])
    }

    /// A check-in moves an older vintage's stake into the current one: lane
    /// A spends it (v_out = amount, a zero note back), the credit lane pads or
    /// merges into our current note, unlabelled; the supply moves, nothing
    /// is unbonded; its Groundworks vote moves with it to the current vintage.
    func testCheckInMovesStakeToTheCurrentVintage() async throws {
        let chain = chain()
        defer { dump(chain, "vCheckIn") }
        chain.minGroundworksVote = 100_000
        let a = try await funded(chain)
        _ = try await a.delegate(validator: vA, amount: 2_000_000); try await a.sync()
        _ = try await a.castGroundworks(split: [2: 100]); try await a.sync()
        let held = sum(notes(a, PrivacyWallet.derthDenom(vA)))
        XCTAssertEqual(base, chain.gwVotes.values.first?.vintage)
        XCTAssertEqual(0, chain.gwVotes.values.first?.splitExpiresAt)

        enter(chain, 202612)
        _ = try await a.delegate(validator: vA, amount: 500_000); try await a.sync()
        let current = sum(notes(a, "derth/\(vA)/202612"))
        let items = try await a.checkInItems()
        XCTAssertEqual([PrivacyWallet.CheckInItem(validator: vA, month: base, denom: PrivacyWallet.derthDenom(vA), derth: held,
                                                  retiresAt: Vintage.retiresAt(base))], items)
        let payouts = chain.unbondPayouts.count
        _ = try await a.checkIn(denom: items[0].denom); try await a.sync()
        let m = try XCTUnwrap(chain.lastMsg as? MsgCheckIn)
        XCTAssertEqual(vA, m.validator)
        XCTAssertEqual(base, m.fromVintage)
        XCTAssertEqual(held, m.amount)
        XCTAssertEqual([2: 100], Dictionary(uniqueKeysWithValues: m.groundworksSplit.map { ($0.optionID, $0.percent) }))
        let w = chain.prover.allStakes.last!
        XCTAssertEqual(0, w.crMoveTime)
        XCTAssertNil(w.crLabel)
        XCTAssertEqual(held, w.crVIn)
        XCTAssertEqual(0, w.outAmount)
        // Merged into our current note: one note there, all of it.
        XCTAssertTrue(notes(a, PrivacyWallet.derthDenom(vA)).isEmpty)
        XCTAssertEqual([held + current], notes(a, "derth/\(vA)/202612").map(\.amount))
        XCTAssertNil(notes(a, "derth/\(vA)/202612")[0].label)
        XCTAssertEqual(0, chain.vintageSupply[vA]?[base])
        XCTAssertEqual(held + current, chain.vintageSupply[vA]?[202612])
        XCTAssertEqual(payouts, chain.unbondPayouts.count)
        // The vote is the current vintage's now; nothing is left to check in.
        XCTAssertEqual([202612], chain.gwVotes.values.map(\.vintage))
        XCTAssertEqual(held + current, chain.gwVotes.values.first?.derth)
        let left = try await a.checkInItems()
        XCTAssertTrue(left.isEmpty)
        let hs0 = await a.stakeHoldings()
        let h = try XCTUnwrap(hs0.first)
        XCTAssertEqual(Vintage.retiresAt(202612), PrivacyWallet.checkInBy([h]))
        // From the current month: refused up front.
        await assertThrowsAsync({ try await a.checkIn(denom: "derth/\(self.vA)/202612") })
    }

    /// "Check in" for everything: one tx per vintage, in turn, each validator's into its current vintage.
    func testCheckInAll() async throws {
        let chain = chain()
        defer { dump(chain, "vCheckInAll") }
        let a = try await funded(chain)
        _ = try await a.delegate(validator: vA, amount: 1_000_000); try await a.sync()
        enter(chain, 202610)
        _ = try await a.delegate(validator: vB, amount: 1_000_000); try await a.sync()
        enter(chain, 202702)
        let rs = try await a.checkInAll()
        XCTAssertEqual(2, rs.count)
        let none = try await a.checkInItems()
        XCTAssertTrue(none.isEmpty)
        XCTAssertEqual(Set(["derth/\(vA)/202702", "derth/\(vB)/202702"]), Set(a.stakeNotes.filter(\.spendable).map(\.denom)))
    }

    /// A note holding a move's open exposure checks in its unexposed part
    /// only: the exposure stays in its vintage, labelled, until its window
    /// closes; then it checks in too.
    func testCheckInLeavesAnOpenExposureBehind() async throws {
        let chain = chain()
        defer { dump(chain, "vCheckInExposure") }
        let a = try await funded(chain)
        _ = try await a.delegate(validator: vA, amount: 1_000_000)
        _ = try await a.delegate(validator: vB, amount: 2_000_000); try await a.sync()
        let before = sum(notes(a, PrivacyWallet.derthDenom(vB)))
        // Late in the month, a move into vB merges into our vB note there, labelled.
        chain.now = Vintage.start(202610) - 2 * 86_400
        chain.emptyBlock()
        _ = try await a.redelegate(try await a.quoteMove(src: vA, dst: vB, amount: 500_000)); try await a.sync()
        let mv = try XCTUnwrap(chain.lastMsg as? MsgRedelegate)
        XCTAssertEqual(base, mv.vintage)
        let labelled = try XCTUnwrap(notes(a, PrivacyWallet.derthDenom(vB)).first)
        let l = try XCTUnwrap(labelled.label)
        XCTAssertEqual(before + mv.dstDerth, labelled.amount)

        // Next month, the window still open: only the unexposed part moves.
        enter(chain, 202610)
        let items = try await a.checkInItems()
        XCTAssertEqual(before, items.first { $0.validator == vB }?.derth)
        _ = try await a.checkIn(denom: PrivacyWallet.derthDenom(vB)); try await a.sync()
        XCTAssertEqual(before, (chain.lastMsg as? MsgCheckIn)?.amount)
        let stays = notes(a, PrivacyWallet.derthDenom(vB))
        XCTAssertEqual([l.exposed], stays.map(\.amount))
        XCTAssertEqual(l, stays.first?.label)
        XCTAssertEqual([before], notes(a, "derth/\(vB)/202610").map(\.amount))
        let rest = try await a.checkInItems()
        XCTAssertFalse(rest.contains { $0.validator == vB })

        // The window closes: the exposure checks in, its label cleared.
        chain.now = Int64(l.moveTime) + Int64(chain.labelWindow) + 3_600
        chain.emptyBlock()
        let later = try await a.checkInItems()
        XCTAssertEqual(l.exposed, later.first { $0.validator == vB }?.derth)
        _ = try await a.checkIn(denom: PrivacyWallet.derthDenom(vB)); try await a.sync()
        XCTAssertTrue(notes(a, PrivacyWallet.derthDenom(vB)).isEmpty)
    }

    /// A retired vintage earns nothing and only withdraws: out of the stake
    /// lines, undelegations, moves, merges, Groundworks and stake votes; its
    /// withdrawal (MsgUndelegate naming the retired month) spends it whole
    /// into a zero note with no split and no vote, and is paid its pro-rata
    /// share of the held value as a pool note.
    func testRetiredVintageOnlyWithdraws() async throws {
        let chain = chain()
        defer { dump(chain, "vWithdraw") }
        chain.minGroundworksVote = 100_000
        let a = try await funded(chain)
        _ = try await a.delegate(validator: vA, amount: 2_000_000); try await a.sync()
        _ = try await a.castGroundworks(split: [2: 100]); try await a.sync()
        let held = sum(notes(a, PrivacyWallet.derthDenom(vA)))
        enter(chain, 202709)
        _ = try await a.delegate(validator: vB, amount: 1_000_000); try await a.sync()

        // Sep 2026 retires on Oct 1 2027.
        enter(chain, 202710)
        chain.retireDue()
        try await a.sync()
        XCTAssertTrue(chain.gwVotes.values.allSatisfy { $0.vintage != base })
        let hs = await a.stakeHoldings()
        let ha = try XCTUnwrap(hs.first { $0.validator == vA })
        XCTAssertEqual(0, ha.derth)
        XCTAssertTrue(ha.vintages.isEmpty)
        XCTAssertEqual([base], ha.retired.map(\.month))
        XCTAssertEqual(Vintage.start(202710), ha.retired[0].retiresAt)
        XCTAssertEqual([vB], StakeRound.lines(notes: a.stakeNotes, rate: { _ in 1 }, vintages: a.localVintages).map(\.validator))
        XCTAssertTrue(a.snapshot.stakeMergeable.isEmpty)
        let ins = try await a.checkInItems()
        XCTAssertTrue(ins.allSatisfy { $0.validator != vA })
        // Refused up front, nothing sent.
        let txs = chain.txs.count
        await assertThrowsAsync({ try await a.undelegate(validator: self.vA, amount: 100_000) })
        await assertThrowsAsync({ try await a.quoteMove(src: self.vA, dst: self.vB, amount: 100_000) })
        await assertThrowsAsync({ try await a.mergeStake(denom: PrivacyWallet.derthDenom(self.vA)) })
        await assertThrowsAsync({ try await a.checkIn(denom: PrivacyWallet.derthDenom(self.vA)) })
        XCTAssertEqual(txs, chain.txs.count)
        // Groundworks skips it; a vote on a new proposal does not carry it.
        let sent = chain.committed.count
        _ = try await a.castGroundworks(split: [2: 100]); try await a.sync()
        XCTAssertFalse(chain.committed.dropFirst(sent).contains { ($0 as? MsgRestake)?.validator == vA })
        chain.openProposal(1)
        let items = try await a.stakeVoteItems(proposalID: 1)
        XCTAssertEqual([vB], items.map(\.validator))

        // The withdrawal.
        let q = try await a.quoteWithdraw(denom: PrivacyWallet.derthDenom(vA))
        XCTAssertEqual(held, q.derth)
        XCTAssertEqual(held * 10 / 9, q.value)
        XCTAssertEqual(base, q.month)
        XCTAssertTrue(q.paidAt > 0)
        _ = try await a.withdrawRetired(q); try await a.sync()
        let m = try XCTUnwrap(chain.lastMsg as? MsgShieldedUndelegate)
        XCTAssertEqual(base, m.vintage)
        XCTAssertEqual(held, m.amount)
        XCTAssertTrue(m.groundworksSplit.isEmpty)
        XCTAssertTrue(f(m.stake.voteTag).isZero)
        XCTAssertEqual(0, chain.prover.allStakes.last!.outAmount)
        XCTAssertEqual(PrivacyHash.assetID(PrivacyWallet.derthDenom(vA)), chain.prover.allStakes.last!.asset)
        XCTAssertTrue(notes(a, PrivacyWallet.derthDenom(vA)).isEmpty)
        XCTAssertNil(chain.retired[PrivacyWallet.derthDenom(vA)])
        let u = try XCTUnwrap(a.pendingUnbonds.first)
        XCTAssertTrue(u.confirmed)
        XCTAssertEqual(held * 10 / 9, u.value)
        XCTAssertEqual(q.paidAt + PrivacyWallet.payoutMarginSeconds, u.dueBy)
        let erth = bal(a)
        chain.payUnbonds(); try await a.sync()
        XCTAssertEqual(erth + held * 10 / 9, bal(a))
        XCTAssertTrue(a.pendingUnbonds.isEmpty)
    }

    /// Between the boundary and the epoch end that books it the vintage is
    /// retiring: no msg on it, no withdrawal yet ("withdraw from" the epoch
    /// end), its value still at the validator's live rate; once booked it
    /// withdraws, paid at the chain's expected_completion.
    func testARetiringVintageWithdrawsFromTheEpochEnd() async throws {
        let chain = chain()
        let a = try await funded(chain)
        _ = try await a.delegate(validator: vA, amount: 1_000_000); try await a.sync()
        let held = sum(notes(a, PrivacyWallet.derthDenom(vA)))
        enter(chain, 202710)
        chain.epochEndTime = chain.now + 7_200
        let txs = chain.txs.count
        await assertThrowsAsync({ try await a.quoteWithdraw(denom: PrivacyWallet.derthDenom(self.vA)) }) {
            "\($0)".contains("retiring") && "\($0)".contains(PrivacyWallet.dateText(UInt64(chain.now + 7_200)))
        }
        await assertThrowsAsync({ try await a.undelegate(validator: self.vA, amount: 100_000) })
        await assertThrowsAsync({ try await a.checkIn(denom: PrivacyWallet.derthDenom(self.vA)) })
        XCTAssertEqual(txs, chain.txs.count)
        let rs = try await a.retiredStake()
        let r = try XCTUnwrap(rs?.first)
        XCTAssertTrue(r.retiring)
        XCTAssertEqual(chain.now + 7_200, r.withdrawFrom)
        XCTAssertEqual(held, r.derth)
        XCTAssertEqual(UInt64(BigUInt(held) * chain.book(vA).backing / chain.book(vA).supply), r.value)
        await assertThrowsAsync({ try await a.withdrawRetired(r) })
        XCTAssertEqual(txs, chain.txs.count)

        chain.retireDue()
        let q = try await a.quoteWithdraw(denom: PrivacyWallet.derthDenom(vA))
        XCTAssertFalse(q.retiring)
        XCTAssertEqual(1, q.parts)
        XCTAssertEqual(chain.now + 21 * 86_400, q.paidAt)
        let booked = try await a.retiredStake()
        XCTAssertEqual([q], booked)
    }

    /// A share past one note's value (2^63-1; the chain refuses it, 1103)
    /// withdraws in parts, one tx each, every part within one note.
    func testALargeWithdrawalGoesInParts() async throws {
        let chain = chain()
        let a = try await funded(chain)
        _ = try await a.delegate(validator: vA, amount: 1_000_000); try await a.sync()
        let held = sum(notes(a, PrivacyWallet.derthDenom(vA)))
        enter(chain, 202710)
        chain.retireDue()
        let denom = PrivacyWallet.derthDenom(vA)
        chain.retired[denom]!.remainingValue = BigUInt(Int64.max) * 5 / 2
        let q = try await a.quoteWithdraw(denom: denom)
        XCTAssertEqual(3, q.parts)
        let sent = chain.committed.count
        _ = try await a.withdrawRetired(q); try await a.sync()
        let parts = chain.committed.dropFirst(sent).compactMap { $0 as? MsgShieldedUndelegate }
        XCTAssertEqual(3, parts.count)
        XCTAssertEqual(held, parts.map(\.amount).reduce(0, +))
        XCTAssertTrue(parts.allSatisfy { $0.vintage == base })
        XCTAssertNil(chain.retired[denom])
        XCTAssertTrue(notes(a, denom).isEmpty)
        XCTAssertEqual(3, chain.unbondPayouts.count)
        XCTAssertTrue(chain.unbondPayouts.allSatisfy { $0.value <= UInt64(Int64.max) })
    }

    /// A stake vote names its notes' vintage: one item per (validator,
    /// vintage), the vote proof's asset that vintage's; a vintage after the
    /// snapshot's does not vote on it.
    func testStakeVotesNameTheirVintage() async throws {
        let chain = chain()
        defer { dump(chain, "vVote") }
        let a = try await funded(chain)
        _ = try await a.delegate(validator: vA, amount: 1_000_000); try await a.sync()
        enter(chain, 202611)
        _ = try await a.delegate(validator: vA, amount: 1_000_000); try await a.sync()
        chain.openProposal(1)
        enter(chain, 202612)
        _ = try await a.delegate(validator: vB, amount: 1_000_000); try await a.sync()
        let items = try await a.stakeVoteItems(proposalID: 1)
        XCTAssertEqual([PrivacyWallet.StakeVoteItem.validator(vA, notes: 1, vintage: base), .validator(vA, notes: 1, vintage: 202611)], items)
        for item in items {
            let r = try await a.castStakeVote(proposalID: 1, item: item, options: yes)
            XCTAssertNotNil(r)
            let m = try XCTUnwrap(chain.lastMsg as? MsgStakeVote)
            XCTAssertEqual(item.vintage, m.vintage)
            XCTAssertEqual(PrivacyHash.assetID(Vintage.denom(vA, item.vintage, base: base)), chain.prover.allVotes.last!.asset)
        }
        XCTAssertEqual(2, chain.stakeVotes.count)
        let after = try await a.stakeVoteItems(proposalID: 1)
        XCTAssertTrue(after.isEmpty)
    }

    /// A wallet restored from its words names every vintage's notes: the
    /// unsuffixed one by the validator list, a suffixed one by trying each
    /// month from the base on.
    func testARestoredWalletNamesItsVintages() async throws {
        let chain = chain()
        let a = try await funded(chain)
        _ = try await a.delegate(validator: vA, amount: 1_000_000); try await a.sync()
        enter(chain, 202702)
        _ = try await a.delegate(validator: vA, amount: 1_000_000); try await a.sync()
        let fresh = try wallet(chain)
        try await fresh.sync()
        XCTAssertEqual(Set(a.stakeNotes.filter(\.spendable).map(\.denom)), Set(fresh.stakeNotes.filter(\.spendable).map(\.denom)))
        XCTAssertEqual(Set([PrivacyWallet.derthDenom(vA), "derth/\(vA)/202702"]), Set(fresh.stakeNotes.filter(\.spendable).map(\.denom)))
        XCTAssertEqual(base, fresh.localVintages?.base)
    }

    /// Undelegating and moving take one vintage a msg: the oldest that covers the amount.
    func testLeavingTakesTheOldestVintageThatCovers() async throws {
        let chain = chain()
        defer { dump(chain, "vLeave") }
        let a = try await funded(chain)
        _ = try await a.delegate(validator: vA, amount: 500_000); try await a.sync()
        enter(chain, 202611)
        _ = try await a.delegate(validator: vA, amount: 2_000_000); try await a.sync()
        let old = sum(notes(a, PrivacyWallet.derthDenom(vA)))
        _ = try await a.undelegate(validator: vA, amount: old / 2); try await a.sync()
        XCTAssertEqual(base, (chain.lastMsg as? MsgShieldedUndelegate)?.vintage)
        _ = try await a.undelegate(validator: vA, amount: old + 1); try await a.sync()
        XCTAssertEqual(202611, (chain.lastMsg as? MsgShieldedUndelegate)?.vintage)
        let hs0 = await a.stakeHoldings()
        let h = try XCTUnwrap(hs0.first)
        XCTAssertEqual(h.vintages.map(\.free).max(), h.maxLeave)
        _ = try await a.redelegate(try await a.quoteMove(src: vA, dst: vB, amount: 100_000)); try await a.sync()
        let mv = try XCTUnwrap(chain.lastMsg as? MsgRedelegate)
        XCTAssertEqual(base, mv.vintage)
        // The credit lands in vB's current vintage.
        XCTAssertEqual(mv.dstDerth, sum(notes(a, "derth/\(vB)/202611")))
    }
}
