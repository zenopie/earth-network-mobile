package network.erth.wallet.privacy

import cosmos.gov.v1.VoteOption as GovVoteOption
import cosmos.gov.v1.WeightedVoteOption
import network.erth.wallet.chain.math.SwapMath
import network.erth.wallet.privacy.keys.PrivacyKeys
import network.erth.wallet.privacy.sync.PrivacyStore
import network.erth.wallet.privacy.sync.WalletSync
import network.erth.wallet.privacy.tx.NoteSelection
import network.erth.wallet.privacy.tx.ShieldMove
import network.erth.wallet.privacy.zk.Fr
import network.erth.wallet.privacy.zk.Privacy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigInteger

/**
 * Two wallets driven end to end against [FakeChain] on bundles and the stake
 * tree: gas grant, registration, claim, assembly vote, private sends of any
 * number of notes and assets, unshields, swaps, private LP add and remove,
 * delegate / restake / undelegate / claim, stake votes, Groundworks votes,
 * the personhood paths, and wallets restored from their mnemonics finding
 * everything again (stake notes and Groundworks votes included).
 */
class WalletFlowTest {
    private val alice = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private val bob = "legal winner thank year wave sausage worth useful legal winner thank yellow"
    private val validator = "earthvaloper1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"
    private val receiver = "earth1qqqsyqcyq5rqwzqfpg9scrgwpugpzysncc2uls"
    private val derth = PrivacyWallet.derthDenom(validator)

    private fun reads(chain: FakeChain) = object : PrivacyChainReads {
        override fun personhoodParams() = PrivacyChainReads.PersonhoodParams(30L * 86_400, 3_600)
        override fun leaseBounds() = chain.leaseBounds()
        override fun ballotInputs(proposalId: Long, optionId: Long) = if (proposalId != 0L) {
            PrivacyChainReads.BallotInputs(Privacy.proposalScope(proposalId, 0), Fr.ZERO, Fr.ZERO, Privacy.NO_BOUND, 0, 0, chain.ballotMaxPredecessor())
        } else {
            val id = chain.removalBallots.getValue(optionId)
            PrivacyChainReads.BallotInputs(Privacy.removalScope(id), Fr.ZERO, Fr.ZERO, Privacy.NO_BOUND, 0, id, chain.ballotMaxPredecessor())
        }
        override fun epochNumber() = chain.epoch
        override fun snapshot(proposalId: Long) = chain.snapshotRead(proposalId)
        override fun stakeNullifierTree(start: Long, limit: Int) = chain.nfTreeRead(start, limit)
        override fun groundworksVotes() = chain.gwVoteReads()
        override fun minGroundworksVote(): Long = if (chain.failMinVoteRead) throw java.io.IOException("node unreachable") else chain.minGroundworksVote
        override fun groundworksOptions() = chain.gwOptions
        override fun debtTree(start: Long, limit: Int) = chain.debtTreeRead(start, limit)
        override fun validators() = chain.validatorsRead()
        override fun minDelegation() = chain.minDelegation
    }

    /** Pauses the wallet asked for (stake votes), in milliseconds. */

    private fun wallet(
        chain: FakeChain,
        words: String,
        reads: PrivacyChainReads = reads(chain),
        indexer: network.erth.wallet.privacy.sync.PrivacyIndexer = chain,
        store: PrivacyStore = PrivacyStore.memory(),
    ) = PrivacyWallet(
        PrivacyKeys.fromMnemonic(words), store, indexer, chain, reads, chain.prover, chain.chainId, chain,
        now = { chain.now },
    )

    private fun bal(w: PrivacyWallet, d: String) = w.balances()[d] ?: 0L

    private val yes = listOf(WeightedVoteOption.newBuilder().setOption(GovVoteOption.VOTE_OPTION_YES).setWeight("1").build())

    private fun shieldTo(chain: FakeChain, w: PrivacyWallet, denom: String, value: Long) {
        val o = w.shieldOutput(denom, value)
        chain.shield(denom, value, o.pc, o.ciphertext)
    }

    @Test
    fun endToEnd() {
        val chain = FakeChain()
        val a = wallet(chain, alice)
        a.sync()
        assertEquals(WalletSync.IdentityStatus.NONE, a.identityStatus())

        // Registration: prepare, the backend shields gas to pc_gas, register.
        val prep = a.prepareRegistration(null)
        chain.shield("uerth", 100_000, prep.gas.pc, prep.gas.ciphertext)
        a.sync()
        assertEquals(100_000L, bal(a, "uerth"))
        val signals = listOf("261001", prep.binding.toBigInteger().toString(), "123456789", Fr.of(77).toBigInteger().toString(), prep.idc.toBigInteger().toString())
        a.register(prep, ByteArray(14_656), signals, "lean_poa", ByteArray(10))
        a.sync()
        assertEquals(WalletSync.IdentityStatus.LIVE, a.identityStatus())
        assertEquals(1_000_000L, bal(a, "uanml"))
        // The gas note paid the fee bundle; the reward is found by its v2 ciphertext and public amount.
        assertTrue(bal(a, "uerth") > 5_000_000)
        assertEquals(Privacy.countryField("DE"), a.store.state.identity!!.country)
        // Every bundle is padded to at least two actions.
        assertTrue(chain.actionCounts.all { it >= 2 })

        // Too soon to claim: activated today.
        assertEquals(chain.now / 86_400 * 86_400 + 2 * 86_400, a.claimOpensAt())
        chain.now += 2 * 86_400
        a.sync()
        assertEquals(0L, a.claimOpensAt())
        a.claimAnml()
        a.sync()
        assertEquals(2_000_000L, bal(a, "uanml"))
        assertTrue(a.claimedToday())

        // An assembly vote.
        chain.now += 7200
        a.voteProposal(5, yes = true)
        assertEquals(5L to 1, chain.votes.last())

        // Private send to bob, who finds it; ANML and its ERTH fee in one bundle.
        val b = wallet(chain, bob)
        a.sync()
        a.send(b.address, "uanml", 700_000, "hi".toByteArray())
        a.sync(); b.sync()
        assertEquals(1_300_000L, bal(a, "uanml"))
        assertEquals(700_000L, bal(b, "uanml"))
        assertEquals("hi", String(b.notes.first { it.note.denom == "uanml" }.note.memo))

        // Unshield ERTH to an address.
        val before = bal(a, "uerth")
        a.unshield(receiver, "uerth", 1_000_000)
        a.sync()
        assertEquals(1_000_000L, chain.unshieldedTo(receiver))
        assertTrue(bal(a, "uerth") < before - 1_000_000)

        // Stake: the quoted derth (9/10 at the fake's rate, less the drift margin) in a note of ours, its input padded.
        a.delegate(validator, 2_000_000)
        a.sync()
        val q1 = (chain.lastMsg as network.erth.earth.proto.shieldedstaking.MsgDelegate).derth
        assertEquals(1_800_000L - (1_800_000L * PrivacyWallet.CREDIT_MARGIN_PPM + 999_999) / 1_000_000, q1)
        assertEquals(q1, bal(a, derth))
        assertEquals(1, a.stakeNotes.size)
        // Owner-locked: stake cannot be sent or unshielded.
        assertThrows(IllegalArgumentException::class.java) { a.send(b.address, derth, 1) }
        assertThrows(IllegalArgumentException::class.java) { a.unshield(receiver, derth, 1) }

        // A stake vote against a snapshot taken right after: the note proves
        // itself unspent at the snapshot and is not spent (ORCHARD_DESIGN 8.5).
        chain.openProposal(9)
        val fresh = wallet(chain, alice)
        fresh.sync()
        // A top-up after the snapshot merges into the note (spending it).
        fresh.delegate(validator, 100_000)
        fresh.sync()
        val q2 = (chain.lastMsg as network.erth.earth.proto.shieldedstaking.MsgDelegate).derth
        fresh.castStakeVote(9, fresh.stakeVoteItems(9).single(), yes)
        fresh.sync()
        // The old note votes the value it held at the snapshot; the merged one is not in it.
        assertEquals(Triple(9L, validator, PrivacyWallet.voteWeight(q1)), chain.stakeVotes.single())
        assertEquals(q1 + q2, bal(fresh, derth))
        assertEquals(1, fresh.stakeNotes.count { it.unspent })
        assertTrue(fresh.stakeVoteItems(9).isEmpty())

        // A wallet restored from the mnemonic alone sees the same balances,
        // the self-mints (gas, reward, derth) included.
        val restored = wallet(chain, alice)
        restored.sync()
        assertEquals(fresh.balances(), restored.balances())
        assertTrue(chain.simulated > 0)

        dump(chain, "endToEnd")
    }

    /** A registered wallet holding ANML from its registration and a claim, and its reward ERTH. */
    private fun registered(chain: FakeChain, words: String): PrivacyWallet {
        val a = wallet(chain, words)
        a.sync()
        val prep = a.prepareRegistration(null)
        chain.shield("uerth", 100_000, prep.gas.pc, prep.gas.ciphertext)
        a.sync()
        val signals = listOf("261001", prep.binding.toBigInteger().toString(), "123456789", Fr.of(77).toBigInteger().toString(), prep.idc.toBigInteger().toString())
        a.register(prep, ByteArray(14_656), signals, "lean_poa", ByteArray(10))
        chain.now += 2 * 86_400
        a.sync()
        a.claimAnml()
        a.sync()
        return a
    }

    /**
     * The dex, LP, staking, Groundworks and personhood paths with two wallets.
     */
    @Test
    fun dexStakingPositionsAndPersonhood() {
        val chain = FakeChain()
        val a = registered(chain, alice)
        val b = wallet(chain, bob)
        b.sync()
        assertEquals(2_000_000L, bal(a, "uanml"))
        val pool = { mapOf("uanml" to SwapMath.Reserves(chain.poolErth, chain.poolAnml)) }

        // ANML -> ERTH: the quote is the chain's, the fee comes from an ERTH note (one fee rule).
        val erth0 = bal(a, "uerth")
        val q = SwapMath.route(pool(), "uerth", "uanml", BigInteger.valueOf(600_000), "uerth", chain.swapFee)!!
        a.noteSwap("uanml", 600_000, "uerth", SwapMath.withSlippage(q.amountOut, 100).toLong())
        a.sync()
        assertEquals(1_400_000L, bal(a, "uanml"))
        val fee = q.amountOut.toLong() - (bal(a, "uerth") - erth0)
        assertTrue("fee from the bundle: $fee", fee in 1000..10_000)

        // ERTH -> ANML by bob from the one note he was sent: amount and fee
        // from the same note, the change back in the same bundle.
        a.send(b.address, "uerth", 2_000_000)
        a.sync(); b.sync()
        assertEquals(1, b.notes.count { it.note.denom == "uerth" && it.unspent })
        val bq = SwapMath.route(pool(), "uerth", "uerth", BigInteger.valueOf(1_000_000), "uanml", chain.swapFee)!!
        b.noteSwap("uerth", 1_000_000, "uanml", SwapMath.withSlippage(bq.amountOut, 50).toLong())
        b.sync()
        assertEquals(bq.amountOut.toLong(), bal(b, "uanml"))
        assertTrue(bal(b, "uerth") in 900_000 until 1_000_000)

        // A swap paid to someone else: bob opens its value-blind (v2) ciphertext.
        val bErth = bal(b, "uerth")
        val gq = SwapMath.route(pool(), "uerth", "uanml", BigInteger.valueOf(100_000), "uerth", chain.swapFee)!!
        a.noteSwap("uanml", 100_000, "uerth", SwapMath.withSlippage(gq.amountOut, 100).toLong(), to = b.address)
        a.sync(); b.sync()
        val paid = chain.notes.last()
        assertEquals(177, paid.ciphertext.size)
        assertEquals("${bal(b, "uerth") - bErth}uerth", paid.amount)
        assertEquals(1_300_000L, bal(a, "uanml"))

        // A bound the pool cannot meet fails before anything is spent.
        val before = a.balances()
        assertThrows(Exception::class.java) { a.noteSwap("uanml", 100_000, "uerth", 10_000_000) }
        a.sync()
        assertEquals(before, a.balances())

        // Private LP: one bundle (ANML + ERTH legs), shares as a note.
        val erthBefore = bal(a, "uerth")
        a.addLiquidityShielded(1, "uanml", 400_000, 2_000_000, "1")
        a.sync()
        val shares = bal(a, "dexlp/1")
        assertTrue(shares > 0)
        assertEquals(mapOf(1L to shares), a.lpShares())
        val anmlAfterLp = bal(a, "uanml")
        assertTrue(anmlAfterLp in 900_000L..900_010L)
        val spent = erthBefore - bal(a, "uerth")
        assertTrue("the unused ERTH is refunded: spent $spent", spent in 800_000..810_000)
        // Shares cannot be unshielded, only withdrawn.
        assertThrows(IllegalArgumentException::class.java) { a.unshield(receiver, "dexlp/1", shares) }
        // Withdraw half; both legs are notes at maturity.
        val e0 = bal(a, "uerth"); val t0 = bal(a, "uanml")
        a.removeLiquidityShielded(1, "uanml", shares / 2)
        a.sync()
        assertEquals(shares - shares / 2, bal(a, "dexlp/1"))
        chain.matureWithdrawals()
        a.sync()
        assertTrue(bal(a, "uanml") > t0)
        assertTrue(bal(a, "uerth") > e0 - 10_000)

        // Staking: delegate twice without a sync between (as two devices
        // would): each pads its input, two notes; the user merges them.
        a.delegate(validator, 1_000_000)
        a.delegate(validator, 1_000_000)
        a.sync()
        val held = bal(a, derth)
        assertEquals(2 * (900_000L - 9), held)
        assertEquals(2, a.stakeNotes.count { it.spendable })
        assertEquals(mapOf(derth to 2), a.stakeMergeable())
        a.mergeStake(derth)
        a.sync()
        assertEquals(held, bal(a, derth))
        assertEquals(1, a.stakeNotes.count { it.spendable })
        assertTrue(a.stakeMergeable().isEmpty())
        // One note per validator: the next delegation merges into it.
        a.delegate(validator, 100_000); a.sync()
        val total = bal(a, derth)
        assertEquals(held + 90_000 - 1, total)
        assertEquals(1, a.stakeNotes.count { it.spendable })

        // Groundworks: vote the stake with a split, change it, stop.
        chain.minGroundworksVote = 100_000
        a.castGroundworks(mapOf(2L to 60L, 5L to 40L))
        var votes = a.groundworksVotes()
        assertEquals(listOf(mapOf(2L to 60L, 5L to 40L)), votes.map { it.split })
        assertEquals(total, votes[0].derth)
        a.castGroundworks(mapOf(2L to 100L))
        votes = a.groundworksVotes()
        assertEquals(listOf(mapOf(2L to 100L)), votes.map { it.split })
        assertEquals(1, chain.gwVotes.size)
        // b sees none of a's.
        assertTrue(b.groundworksVotes().isEmpty())
        a.castGroundworks(emptyMap())
        assertTrue(chain.gwVotes.isEmpty())
        assertEquals(total, bal(a, derth))
        assertEquals(1, a.stakeNotes.count { it.spendable })

        // Stake votes: the derth note from before the snapshot, one vote per validator, its rounded amount.
        a.sync()
        chain.openProposal(11)
        val notes = a.stakeNotes.filter { it.spendable && it.denom == derth }
        assertEquals(1, notes.size)
        val items = a.stakeVoteItems(11)
        val preview = a.stakeVotePreview(11, items.single())!!
        assertEquals(notes.size, preview.notes)
        assertEquals(liveValue(chain, PrivacyWallet.parseDerth(derth), PrivacyWallet.voteWeight(notes.sumOf { it.amount })), preview.uerth)
        val voted = items.mapNotNull { a.castStakeVote(11, it, yes) }
        assertEquals(1, voted.size)
        a.sync()
        assertEquals(total, bal(a, derth))
        assertEquals(PrivacyWallet.voteWeight(notes.sumOf { it.amount }), chain.stakeVotes.single().third)
        assertEquals(listOf(notes.size), chain.stakeVoteSlots)

        // A handle: claimed by a fresh registrant at once, naming this wallet's shielded address.
        chain.now += 31 * 86_400
        a.sync()
        a.bindHandle("alice")
        assertEquals(a.address.encode(), chain.handles.getValue("alice").address)
        assertEquals("alice", a.store.state.handle)
        a.setCaretaker(mapOf(1L to 100L))
        assertEquals(mapOf(1L to 100L), chain.caretakerVotes.values.single())

        // Removal ballot: open one, vote in it.
        a.proposeRemoval(3)
        a.voteRemoval(3, yes = true)
        assertEquals(3L, chain.removalVotes.single().first)

        // Unstake (the note, change back as a created stake note): the msg
        // names a pool note of ours; at maturity the chain pays it there by
        // itself, and the wallet sends nothing more.
        a.sync()
        a.undelegate(validator, 1_000_000)
        a.sync()
        assertEquals(total - 1_000_000, bal(a, derth))
        val u = a.pendingUnbonds.single()
        assertEquals(listOf(1_111_111L, 4L, 1L), listOf(u.value, u.epoch, u.payoutId))
        val erthPre = bal(a, "uerth")
        val txsBefore = chain.txs.size
        chain.payUnbonds()
        a.sync()
        assertEquals(txsBefore, chain.txs.size)
        assertEquals(1_111_111L, bal(a, "uerth") - erthPre)
        assertTrue(a.pendingUnbonds.isEmpty())

        // Both wallets, restored from their mnemonics, find everything again:
        // pool self-mints, stake mints (by spc), created stake notes (by
        // their stake ciphertext) and share notes.
        for ((w, words) in listOf(a to alice, b to bob)) {
            val restored = wallet(chain, words)
            restored.sync()
            assertEquals(w.balances(), restored.balances())
        }
        dump(chain, "dex")
    }

    /**
     * Any number of notes: a payment spending 15 small notes in one bundle
     * (no per-bundle note limit below max_actions), a multi-asset bundle, Max
     * unshielding every note a bundle carries with the fee from the amount,
     * and merge only past max_actions_per_bundle.
     */
    @Test
    fun manyNotesAndMax() {
        val chain = FakeChain()
        val c = wallet(chain, alice)
        val b = wallet(chain, bob)
        c.sync(); b.sync()
        repeat(20) { shieldTo(chain, c, "uerth", 100_000) }
        repeat(3) { shieldTo(chain, c, "uanml", 50_000) }
        c.sync()

        // 1.495 ERTH and its fee need 16 notes: one bundle of 16 actions.
        c.send(b.address, "uerth", 1_495_000)
        assertEquals(16, chain.actionCounts.last())
        c.sync(); b.sync()
        assertEquals(1_495_000L, bal(b, "uerth"))

        // ANML from three notes and ERTH, both to bob, the fee in the same bundle.
        c.send(b.address, "uanml", 120_000)
        c.sync(); b.sync()
        assertEquals(120_000L, bal(b, "uanml"))
        assertEquals(30_000L, bal(c, "uanml"))

        // Max unshield: every spendable note one bundle carries, the fee out of it.
        val max = ShieldMove.maxUnshield(c.notes, chain.maxActions)
        assertEquals(bal(c, "uerth"), max)
        c.unshield(receiver, "uerth", max, feeFromAmount = true)
        c.sync()
        assertEquals(0L, bal(c, "uerth"))
        assertTrue(chain.unshieldedTo(receiver) in max - 10_000 until max)

        // More notes than a bundle carries: the payment refuses, a merge fixes it.
        chain.maxActions = 4
        repeat(6) { shieldTo(chain, c, "uerth", 10_000) }
        c.sync()
        assertThrows(NoteSelection.Insufficient::class.java) { c.send(b.address, "uerth", 50_000) }
        c.merge("uerth")
        c.sync()
        assertEquals(3, c.notes.count { it.note.denom == "uerth" && it.unspent })
        c.send(b.address, "uerth", 50_000)
        c.sync()

        // ANML with no ERTH at all: its fee cannot come out of ANML.
        val d = wallet(chain, "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong")
        d.sync()
        shieldTo(chain, d, "uanml", 1_000_000)
        d.sync()
        assertThrows(NoteSelection.Insufficient::class.java) { d.send(b.address, "uanml", 100_000) }
        dump(chain, "manyNotes")
    }

    /**
     * With PRIVACY_TOML_OUT set, writes every witness the wallet proved as a
     * nargo Prover.toml, for `nargo execute` against the real circuits.
     */
    private fun dump(chain: FakeChain, test: String) {
        dumpWitnesses(chain, test)
    }
}

/** derth is valued at rate_v, floored as the chain floors it. */
class StakeValueTest {
    @Test
    fun derthValueFloorsAtTheRate() {
        assertEquals(1_050_000L, PrivacyWallet.derthValue(1_000_000, java.math.BigDecimal("1.05")))
        assertEquals(1L, PrivacyWallet.derthValue(3, java.math.BigDecimal("0.5")))
        assertEquals(999_999L, PrivacyWallet.derthValue(999_999, java.math.BigDecimal("1.000001000001000001")))
    }
}
