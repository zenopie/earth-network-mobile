package network.erth.wallet.privacy

import network.erth.earth.proto.shieldedstaking.MsgCheckIn
import network.erth.earth.proto.shieldedstaking.MsgDelegate
import network.erth.earth.proto.shieldedstaking.MsgRestake
import network.erth.earth.proto.shieldedstaking.MsgStakeVote
import network.erth.earth.proto.shieldedstaking.MsgUndelegate
import network.erth.wallet.privacy.note.OwnedStakeNote
import network.erth.wallet.privacy.zk.Fr
import network.erth.wallet.privacy.zk.Privacy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stake vintages (chain v1.3.0) end to end against [FakeChain]: one note per
 * (validator, vintage), every stake msg naming its vintage, a delegation
 * landing in the current month, check-ins (an open label's exposure kept),
 * retired stake withdraw-only, and a restored wallet naming vintaged notes.
 */
class VintageFlowTest : WalletTest() {
    private val vA = "earthvaloper1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"
    private val vB: String = Vectors.json.getString("validator2")

    private fun f(b: com.google.protobuf.ByteString) = Fr.fromBytes(b.toByteArray())

    private fun PrivacyWallet.stakeOf(denom: String): List<OwnedStakeNote> = stakeNotes.filter { it.spendable && it.denom == denom }

    /** [chain]'s clock at [day] of month [m] (UTC noon). */
    private fun at(chain: FakeChain, m: Int, day: Int = 2) {
        chain.now = Vintages.start(m) + (day - 1) * 86_400L + 12 * 3_600
    }

    private fun funded(chain: FakeChain, n: Int = 8): PrivacyWallet {
        val a = wallet(chain)
        repeat(n) { funded(chain, a, 3_000_000) }
        a.sync()
        return a
    }

    @Test
    fun delegateLandsInTheCurrentVintage() {
        val chain = FakeChain()
        val base = chain.baseVintage
        val a = funded(chain)
        a.delegate(vA, 1_000_000); a.sync()
        // The base month: the unsuffixed (legacy) denom, the month named.
        var m = chain.lastMsg as MsgDelegate
        assertEquals(base, m.vintage)
        assertEquals(PrivacyWallet.derthDenom(vA), a.stakeNotes.single { it.spendable }.denom)

        // Two months on: a new note of this month's vintage, the old one untouched.
        val next = Vintages.addMonths(base, 2)
        at(chain, next)
        a.sync()
        val q = a.quoteDelegate(vA, 1_000_000)
        assertEquals("derth/$vA/$next", q.denom)
        a.delegate(q); a.sync()
        m = chain.lastMsg as MsgDelegate
        assertEquals(next, m.vintage)
        assertEquals(1, a.stakeOf("derth/$vA").size)
        assertEquals(1, a.stakeOf("derth/$vA/$next").size)
        assertEquals(m.derth, a.stakeOf("derth/$vA/$next").single().amount)
        // The chain's supply per vintage.
        assertEquals(m.derth, chain.supplyOf(vA, next))
        // One row per vintage, each with its retirement.
        val rows = a.stakeHoldings().associateBy { it.denom }
        assertEquals(setOf("derth/$vA", "derth/$vA/$next"), rows.keys)
        assertEquals(Vintages.retiresAt(base), rows.getValue("derth/$vA").retiresAt)
        assertEquals(next, rows.getValue("derth/$vA/$next").vintage)
        assertFalse(rows.values.any { it.retired })

        // A quote from last month is shown again, not sent into the wrong vintage.
        val stale = a.quoteDelegate(vA, 1_000_000)
        at(chain, Vintages.addMonths(next, 1))
        val sent = chain.txs.size
        assertThrows(PrivacyWallet.QuoteChanged::class.java) { a.delegate(stale) }
        assertEquals(sent, chain.txs.size)

        // A merge stays within its vintage and names it.
        chain.plantStake(a.keys, "derth/$vA", 70_000); a.sync()
        assertEquals(mapOf("derth/$vA" to 2), a.stakeMergeable())
        a.mergeStake("derth/$vA"); a.sync()
        val r = chain.lastMsg as MsgRestake
        assertEquals(base, r.vintage)
        assertEquals(1, a.stakeOf("derth/$vA").size)
        assertEquals(1, a.stakeOf("derth/$vA/$next").size)
        dumpWitnesses(chain, "vintageDelegate")
    }

    @Test
    fun undelegateAndMoveNameTheSourceVintage() {
        val chain = FakeChain()
        val base = chain.baseVintage
        val a = funded(chain)
        a.delegate(vA, 2_000_000); a.sync()
        val next = Vintages.addMonths(base, 1)
        at(chain, next, 10)
        a.sync()
        // An undelegation of the old vintage names it.
        a.undelegate("derth/$vA", 300_000); a.sync()
        assertEquals(base, (chain.lastMsg as MsgUndelegate).vintage)
        // A move: the source's vintage named, the credit in dst's current one.
        a.redelegate(a.quoteMove("derth/$vA", vB, 300_000)); a.sync()
        val mv = chain.lastMsg as network.erth.earth.proto.shieldedstaking.MsgRedelegate
        assertEquals(base, mv.vintage)
        assertEquals(1, a.stakeOf("derth/$vB/$next").size)
        assertEquals(mv.dstDerth, chain.supplyOf(vB, next))
    }

    /**
     * Check-in: within a month of the old vintage's retirement the row is
     * due and the reminder fires; one tap checks in every older note (a tx
     * each): lane A spends the old note (a zero note back), the credit lane
     * merges into this month's note, unlabelled. Nothing is sent by itself.
     */
    @Test
    fun checkInMovesOlderVintagesIntoThisMonth() {
        val chain = FakeChain().apply { minGroundworksVote = 100_000 }
        val base = chain.baseVintage
        val a = funded(chain)
        a.delegate(vA, 2_000_000); a.sync()
        val old = a.stakeOf("derth/$vA").single()

        // Eleven months on: a delegation of this month (the credit will merge into it).
        val late = Vintages.addMonths(base, 12)
        at(chain, late, 5)
        a.sync()
        a.delegate(vA, 1_000_000); a.sync()
        val current = a.stakeOf("derth/$vA/$late").single()
        // Due: within 30 days of the base vintage's retirement, the reminder, not yet sent.
        val retires = Vintages.retiresAt(base)
        assertTrue(chain.now >= retires - Vintages.CHECK_IN_LEAD_SECONDS)
        assertEquals(retires, a.checkInRetiresAt())
        assertEquals(retires - Vintages.CHECK_IN_LEAD_SECONDS, a.checkInDueAt())
        assertTrue(a.stakeHoldings().single { it.denom == "derth/$vA" }.checkInDue(chain.now))
        assertFalse(a.stakeHoldings().single { it.denom == "derth/$vA/$late" }.checkInDue(chain.now))
        assertTrue(Reminders.due(Reminders.Inputs(chain.now, false, null, true, 0, "", null, stakeRetiresAt = a.checkInRetiresAt()!!))
            .contains(Reminders.Reminder.CheckInDue(retires)))
        val items = a.checkIns()
        assertEquals(1, items.size)
        assertEquals(old.amount, items[0].amount)
        assertEquals(base, items[0].fromVintage)
        assertEquals(0L, items[0].keptExposed)

        val txs = chain.txs.size
        val r = a.checkInAll()
        assertEquals(1, r.size)
        assertEquals(txs + 1, chain.txs.size)
        val m = chain.lastMsg as MsgCheckIn
        assertEquals(vA, m.validator)
        assertEquals(base, m.fromVintage)
        assertEquals(old.amount, m.amount)
        // Lane A spends the old note; the credit lane merges into this month's note, unlabelled.
        val w = chain.prover.allStakes.last()
        assertEquals(Privacy.assetId("derth/$vA"), w.asset)
        assertEquals(Privacy.assetId("derth/$vA/$late"), w.crAsset)
        assertEquals(old.amount, w.vOut)
        assertEquals(old.amount, w.crVIn)
        assertEquals(0L, w.crMoveTime)
        assertEquals(current.nf, f(m.stake.creditNullifier))
        assertEquals(0L, w.outAmount)
        // Everything now in this month's vintage, one note; the chain moved the supply.
        assertTrue(a.stakeOf("derth/$vA").isEmpty())
        assertEquals(old.amount + current.amount, a.stakeOf("derth/$vA/$late").single().amount)
        assertNull(a.stakeOf("derth/$vA/$late").single().label)
        assertEquals(0L, chain.supplyOf(vA, base))
        assertEquals(old.amount + current.amount, chain.supplyOf(vA, late))
        assertTrue(a.checkIns().isEmpty())
        assertEquals(Vintages.retiresAt(late), a.checkInRetiresAt())
        assertTrue(a.activity().any { it.kind == PrivateActivityKind.CHECK_IN })
        dumpWitnesses(chain, "vintageCheckIn")
    }

    /**
     * A note holding moved-in stake whose window is still open checks in
     * only its unexposed part: the exposure stays in its vintage, labelled,
     * and the sheet says until when.
     */
    @Test
    fun checkInKeepsAnOpenLabelsExposure() {
        val chain = FakeChain()
        val base = chain.baseVintage
        val a = funded(chain)
        // This month: a note at B, then a move into it (labelled, window open for 21 days).
        at(chain, base, 27)
        a.sync()
        a.delegate(vB, 1_000_000); a.sync()
        a.delegate(vA, 2_000_000); a.sync()
        a.redelegate(a.quoteMove(vA, vB, 500_000)); a.sync()
        val n = a.stakeOf("derth/$vB").single()
        val l = n.label!!
        // Next month, the window still open.
        at(chain, Vintages.addMonths(base, 1), 3)
        a.sync()
        val c = a.checkIns().single { it.validator == vB }
        assertEquals(n.amount - l.exposed, c.amount)
        assertEquals(l.exposed, c.keptExposed)
        assertEquals(a.movableAfter(l), c.keptUntil)
        a.checkIn(c); a.sync()
        val m = chain.lastMsg as MsgCheckIn
        assertEquals(n.amount - l.exposed, m.amount)
        // The exposure stays behind, still labelled; the rest is this month's.
        val left = a.stakeOf("derth/$vB").single()
        assertEquals(l, left.label)
        assertEquals(l.exposed, left.amount)
        val next = Vintages.addMonths(base, 1)
        assertEquals(n.amount - l.exposed, a.stakeOf("derth/$vB/$next").single().amount)
        assertNull(a.stakeOf("derth/$vB/$next").single().label)
    }

    /**
     * Retired stake: not earning, withdraw-only. It is left out of merges,
     * check-ins, Groundworks and gov votes, and every other stake tx refuses
     * it; the withdrawal is MsgUndelegate naming the retired month, no split,
     * no vote, paying its share of what the vintage was worth when it retired.
     */
    @Test
    fun retiredStakeIsWithdrawOnly() {
        val chain = FakeChain().apply { minGroundworksVote = 100_000 }
        val base = chain.baseVintage
        val a = funded(chain)
        a.delegate(vA, 2_000_000); a.sync()
        chain.plantStake(a.keys, "derth/$vA", 70_000); a.sync()
        a.castGroundworks(mapOf(2L to 100L)); a.sync()
        val held = a.stakeOf("derth/$vA")
        assertEquals(1, held.size)
        // A vote opens while it is live; it retires before the stake votes.
        chain.openProposal(9)

        // Past its retirement, before the epoch end books it: retiring.
        chain.now = Vintages.retiresAt(base) + 3_600
        chain.epochEndPending = true
        chain.epochEndsAt = Vintages.retiresAt(base) + 86_400
        a.sync()
        val row = a.stakeHoldings().single()
        assertTrue(row.retired)
        assertTrue(row.retiring)
        assertEquals(chain.epochEndsAt, row.withdrawFrom)
        assertFalse(row.checkInDue(chain.now))
        assertTrue(a.stakeMergeable().isEmpty())
        assertTrue(a.checkIns().isEmpty())
        assertNull(a.checkInRetiresAt())
        assertTrue(a.stakeVoteItems(9).isEmpty())
        val txs = chain.txs.size
        assertThrows(PrivacyWallet.StakeRetired::class.java) { a.undelegate("derth/$vA", 1_000) }
        assertThrows(PrivacyWallet.StakeRetired::class.java) { a.quoteMove("derth/$vA", vB, 1_000) }
        assertThrows(PrivacyWallet.StakeRetired::class.java) { a.restake("derth/$vA") }
        assertThrows(PrivacyWallet.StakeRetired::class.java) { a.revote("derth/$vA") }
        assertThrows(PrivacyWallet.StakeRetired::class.java) { a.stakeVote(9, "derth/$vA", yes) }
        // Its Groundworks vote is gone; a re-cast leaves it alone.
        assertEquals(txs, chain.txs.size)
        // Not booked yet: the withdrawal waits, saying from when.
        val shut = assertThrows(PrivacyWallet.WithdrawalNotOpen::class.java) { a.quoteWithdraw("derth/$vA") }
        assertEquals(chain.epochEndsAt, shut.from)
        assertEquals(txs, chain.txs.size)
        // The epoch end books it: retired, withdrawable.
        chain.now = chain.epochEndsAt
        chain.epochEndPending = false
        chain.retireDue()
        assertTrue(chain.gwVotes.isEmpty())
        val booked = a.stakeHoldings().single()
        assertTrue(booked.retired)
        assertFalse(booked.retiring)

        val q = a.quoteWithdraw("derth/$vA")
        val note = held.single()
        assertEquals(note.amount, q.amount)
        val r = chain.retiredVintages.getValue(vA to base)
        assertEquals(note.amount, r.remainingSupply)
        // The whole remaining supply takes the whole remaining value.
        assertEquals(r.remainingValue, q.value)
        assertFalse(q.paysNow)
        assertEquals(1, q.parts)
        // The chain's expected completion, with the payout margin.
        assertEquals(r.completion + PrivacyWallet.PAYOUT_MARGIN_S, q.paysAt)
        a.withdraw(q); a.sync()
        val m = chain.lastMsg as MsgUndelegate
        assertEquals(base, m.vintage)
        assertEquals(note.amount, m.amount)
        assertEquals(0, m.groundworksSplitCount)
        assertTrue(f(m.stake.voteTag).isZero)
        assertEquals(0L, m.stake.voteWeight)
        // A zero note back: nothing left in the retired vintage.
        assertTrue(a.stakeOf("derth/$vA").isEmpty())
        val u = a.pendingUnbonds.single()
        assertTrue(u.confirmed)
        assertEquals(q.value, u.value)
        assertEquals(q.paysAt, u.dueBy)
        chain.payUnbonds(); a.sync()
        assertTrue(a.pendingUnbonds.isEmpty())
        assertEquals(0L, chain.retiredVintages.getValue(vA to base).remainingSupply)
    }

    /** A gov stake vote names its notes' vintage: one vote per (validator, vintage), each its own asset. */
    @Test
    fun stakeVotesNameTheirVintage() {
        val chain = FakeChain()
        val base = chain.baseVintage
        val a = funded(chain)
        a.delegate(vA, 1_000_000); a.sync()
        val next = Vintages.addMonths(base, 1)
        at(chain, next)
        a.sync()
        a.delegate(vA, 1_000_000); a.sync()
        chain.openProposal(3)
        a.sync()
        val items = a.stakeVoteItems(3).map { it as PrivacyWallet.StakeVoteItem.Validator }
        assertEquals(setOf("derth/$vA", "derth/$vA/$next"), items.map { it.denom }.toSet())
        val named = ArrayList<Int>()
        for (i in items) {
            assertNotNull(a.castStakeVote(3, i, yes))
            named.add((chain.lastMsg as MsgStakeVote).vintage)
        }
        assertEquals(2, chain.stakeVotes.size)
        assertEquals(setOf(base, next), named.toSet())
        // Each vote proof's asset is its vintage's denom (the fake chain checked it against the msg).
        assertEquals(setOf(Privacy.assetId("derth/$vA"), Privacy.assetId("derth/$vA/$next")), chain.prover.allVotes.map { it.asset }.toSet())
    }

    /** A wallet restored from its mnemonic names a vintaged stake note by trying each validator's months. */
    @Test
    fun restoreNamesVintagedNotes() {
        val chain = FakeChain()
        val base = chain.baseVintage
        val a = funded(chain, 2)
        val next = Vintages.addMonths(base, 3)
        at(chain, next)
        chain.plantStake(a.keys, "derth/$vA/$next", 123_456)
        val restored = wallet(chain)
        restored.sync()
        assertEquals(123_456L, restored.stakeOf("derth/$vA/$next").single().amount)
    }

    /**
     * The month-boundary grace: a delegation quoted, and a move and a
     * check-in proven, for last month still land there during the first hour
     * of the new one, naming it (credit_vintage); past the hour the quote is
     * shown again, and the chain refuses a credit to last month.
     */
    @Test
    fun creditsNameTheirMonthThroughTheGrace() {
        val chain = FakeChain()
        val base = chain.baseVintage
        val a = funded(chain)
        a.delegate(vA, 2_000_000); a.sync()
        val next = Vintages.addMonths(base, 1)
        // A quote at the end of the month, sent ten minutes into the next.
        chain.now = Vintages.start(next) - 60
        a.sync()
        val q = a.quoteDelegate(vB, 1_000_000)
        assertEquals("derth/$vB", q.denom)
        val mq = a.quoteMove("derth/$vA", vB, 300_000)
        assertEquals("derth/$vB", mq.dstDenom)
        chain.now = Vintages.start(next) + 600
        assertEquals(base, a.vintageContext()!!.grace)
        a.delegate(q); a.sync()
        assertEquals(base, (chain.lastMsg as MsgDelegate).vintage)
        a.redelegate(mq); a.sync()
        val mv = chain.lastMsg as network.erth.earth.proto.shieldedstaking.MsgRedelegate
        assertEquals(base, mv.vintage)
        assertEquals(base, mv.creditVintage)
        assertEquals(Privacy.assetId("derth/$vB"), chain.prover.allStakes.last().crAsset)
        assertEquals(q.derth + mv.dstDerth, chain.supplyOf(vB, base))

        // A check-in names the month its credit lane proves (the current one).
        a.checkIn(a.checkIns().single { it.validator == vA }); a.sync()
        val ci = chain.lastMsg as MsgCheckIn
        assertEquals(next, ci.creditVintage)
        assertEquals(Privacy.assetId("derth/$vA/$next"), chain.prover.allStakes.last().crAsset)

        // Past the hour: a stale quote is shown again, nothing sent.
        val stale = a.quoteDelegate(vB, 1_000_000)
        chain.now = Vintages.start(Vintages.addMonths(next, 1)) + Vintages.GRACE_SECONDS
        assertEquals(0, a.vintageContext()!!.grace)
        val sent = chain.txs.size
        assertThrows(PrivacyWallet.QuoteChanged::class.java) { a.delegate(stale) }
        assertEquals(sent, chain.txs.size)
    }

    /** The vintage reads name no validator: Query/VintageInfo for the clock, Query/VintageBooks read whole for a retired vintage. */
    @Test
    fun vintageReadsNameNoValidator() {
        val chain = FakeChain()
        val base = chain.baseVintage
        val a = funded(chain)
        a.delegate(vA, 1_000_000); a.sync()
        assertEquals(0, chain.booksReads)
        // A live row reads no books.
        a.stakeHoldings()
        assertEquals(0, chain.booksReads)
        chain.now = Vintages.retiresAt(base) + 2 * 86_400
        a.sync()
        a.quoteWithdraw("derth/$vA")
        assertEquals(1, chain.booksReads)
    }

    /**
     * A withdrawal pays one note's worth at most (2^63 - 1 uerth): a larger
     * share leaves in parts, each the largest d with floor(d x V / S) under
     * the limit, against the vintage as the last part left it.
     */
    @Test
    fun withdrawalPartsFitOneNote() {
        val max = java.math.BigInteger.valueOf(Long.MAX_VALUE)
        val s = java.math.BigInteger.valueOf(1_000_000_000_000L)
        val v = max.multiply(java.math.BigInteger.valueOf(3)) // 3 notes' worth
        var rv = PrivacyChainReads.RetiredVintage(vA, 202601, "derth/$vA/202601", s, v, s, v, 1, 0)
        var left = s.toLong()
        var parts = 0
        var paid = java.math.BigInteger.ZERO
        while (left > 0) {
            val d = rv.partDerth(left)
            assertTrue(d in 1..left)
            val w = rv.payout(d)
            assertTrue(w <= max)
            // The largest such d: one more derth would not fit.
            if (d < left) assertTrue(rv.payout(d + 1) > max)
            paid += w
            rv = rv.after(d)
            left -= d
            parts++
        }
        assertEquals(4, parts)
        assertEquals(v, paid)
        // A share that fits is one part.
        val small = PrivacyChainReads.RetiredVintage(vA, 202601, "", s, s, s, s, 1, 0)
        assertEquals(s.toLong(), small.partDerth(s.toLong()))
    }
}
