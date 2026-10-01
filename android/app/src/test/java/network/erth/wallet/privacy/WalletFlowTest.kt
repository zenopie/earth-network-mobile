package network.erth.wallet.privacy

import cosmos.gov.v1.VoteOption as GovVoteOption
import cosmos.gov.v1.WeightedVoteOption
import network.erth.wallet.privacy.keys.PrivacyKeys
import network.erth.wallet.privacy.sync.PrivacyStore
import network.erth.wallet.privacy.sync.WalletSync
import network.erth.wallet.privacy.zk.Fr
import network.erth.wallet.privacy.zk.Privacy
import network.erth.wallet.privacy.tx.NoteSelection
import network.erth.wallet.chain.math.SwapMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigInteger

/**
 * A wallet driven end to end against [FakeChain]: gas grant, registration,
 * claim, assembly vote, private send and receive, unshield, delegation and a
 * stake vote against a snapshot root, then a second wallet restored from the
 * same mnemonic finding everything again.
 */
class WalletFlowTest {
    private val alice = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private val bob = "legal winner thank year wave sausage worth useful legal winner thank yellow"
    private val validator = "earthvaloper1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"

    /** A proposal's snapshot, fixed once taken (null: the tree as it stands). */
    private var snapshot: Long? = null

    private fun reads(chain: FakeChain, snapshotSize: () -> Long = { snapshot ?: chain.noteTree.size }) = object : PrivacyChainReads {
        override fun personhoodParams() = PrivacyChainReads.PersonhoodParams(30L * 86_400, 3_600)
        override fun ballotInputs(proposalId: Long, optionId: Long) = if (proposalId != 0L) {
            PrivacyChainReads.BallotInputs(Privacy.proposalScope(proposalId, 0), Fr.ZERO, Fr.ZERO, chain.now - 3600, 0, 0)
        } else {
            val id = chain.removalBallots.getValue(optionId)
            PrivacyChainReads.BallotInputs(Privacy.removalScope(id), Fr.ZERO, Fr.ZERO, chain.now - 3600, 0, id)
        }
        override fun epochNumber() = 4L
        override fun snapshot(proposalId: Long) = snapshotSize().let { PrivacyChainReads.Snapshot(chain.noteTree.rootAt(it), it) }
        override fun positions() = chain.positionReads()
    }

    private fun wallet(chain: FakeChain, words: String, reads: PrivacyChainReads = reads(chain)) = PrivacyWallet(
        PrivacyKeys.fromMnemonic(words), PrivacyStore.memory(), chain, chain, reads, chain.prover, chain.chainId, now = { chain.now },
    )

    @Test
    fun endToEnd() {
        val chain = FakeChain()
        val a = wallet(chain, alice)
        a.sync()
        assertEquals(WalletSync.IdentityStatus.NONE, a.identityStatus())

        // Registration: prepare, the backend shields gas to pc_gas, register.
        val prep = a.prepareRegistration(null)
        chain.shield("uerth", 100_000, prep.gas.pc)
        a.sync()
        assertEquals(100_000L, a.balances()["uerth"])
        val dsc = Fr.of(77)
        val signals = listOf("261001", prep.binding.toBigInteger().toString(), "123456789", dsc.toBigInteger().toString())
        a.register(prep, ByteArray(14_656), signals, "lean_poa", ByteArray(10))
        a.sync()
        assertEquals(WalletSync.IdentityStatus.LIVE, a.identityStatus())
        assertEquals(1_000_000L, a.balances()["uanml"])
        // The gas note paid the fee; the reward is a self-mint found by its public amount.
        assertTrue((a.balances()["uerth"] ?: 0) > 5_000_000)
        assertEquals(Privacy.countryField("DE"), a.store.state.identity!!.country)

        // Too soon to claim: activated today.
        assertEquals(chain.now / 86_400 * 86_400 + 2 * 86_400, a.claimOpensAt())
        chain.now += 2 * 86_400
        a.sync()
        assertEquals(0L, a.claimOpensAt())
        a.claimAnml()
        a.sync()
        assertEquals(2_000_000L, a.balances()["uanml"])
        assertTrue(a.claimedToday())

        // An assembly vote.
        chain.now += 7200
        a.voteProposal(5, yes = true)
        assertEquals(5L to 1, chain.votes.last())

        // Private send to bob, who finds it.
        val b = wallet(chain, bob)
        a.sync()
        a.send(b.address, "uanml", 700_000, "hi".toByteArray())
        a.sync(); b.sync()
        assertEquals(1_300_000L, a.balances()["uanml"])
        assertEquals(700_000L, b.balances()["uanml"])
        assertEquals("hi", String(b.notes.first { it.note.denom == "uanml" }.note.memo))

        // Unshield ERTH to an address.
        val before = a.balances()["uerth"]!!
        a.unshield("earth1qqqsyqcyq5rqwzqfpg9scrgwpugpzysncc2uls", "uerth", 1_000_000)
        a.sync()
        assertEquals(1_000_000L, chain.unshielded["earth1qqqsyqcyq5rqwzqfpg9scrgwpugpzysncc2uls"])
        assertTrue(a.balances()["uerth"]!! < before - 1_000_000)

        // Stake, then vote with the derth against a snapshot taken right after.
        a.delegate(validator, 2_000_000)
        a.sync()
        val derth = PrivacyWallet.derthDenom(validator)
        assertEquals(1_800_000L, a.balances()[derth])
        val snap = chain.noteTree.size
        val fresh = wallet(chain, alice, reads(chain) { snap })
        fresh.sync()
        // A later note moves the tree past the snapshot.
        chain.shield("uerth", 1, Fr.of(5))
        fresh.sync()
        val opts = listOf(WeightedVoteOption.newBuilder().setOption(GovVoteOption.VOTE_OPTION_YES).setWeight("1").build())
        fresh.stakeVote(9, fresh.notes.first { it.note.denom == derth && it.unspent }, opts)
        fresh.sync()
        assertEquals(1_800_000L, fresh.balances()[derth])

        // A wallet restored from the mnemonic alone sees the same balances,
        // the self-mints (gas, reward, derth) included.
        val restored = wallet(chain, alice)
        restored.sync()
        assertEquals(fresh.balances(), restored.balances())
        assertTrue(chain.simulated > 0)

        dump(chain, "endToEnd")
    }

    /** A registered wallet holding ANML from its registration and two claims, and its reward ERTH. */
    private fun registered(chain: FakeChain, words: String): PrivacyWallet {
        val a = wallet(chain, words)
        a.sync()
        val prep = a.prepareRegistration(null)
        chain.shield("uerth", 100_000, prep.gas.pc)
        a.sync()
        val signals = listOf("261001", prep.binding.toBigInteger().toString(), "123456789", Fr.of(77).toBigInteger().toString())
        a.register(prep, ByteArray(14_656), signals, "lean_poa", ByteArray(10))
        chain.now += 2 * 86_400
        a.sync()
        a.claimAnml()
        a.sync()
        return a
    }

    private fun bal(w: PrivacyWallet, d: String) = w.balances()[d] ?: 0L

    /**
     * The dex, positions and personhood paths with two wallets: note swaps
     * both ways (fee from output into ERTH), a refused slippage bound that
     * spends nothing, shielded pool-1 liquidity with its refunds, Groundworks
     * positions (lock, update, vote, unlock), stake votes, the referrer
     * binding, removal ballots, note merges, an unbonding claimed by the
     * automation's maturity rule, and both wallets restored from their
     * mnemonics finding every self-mint again.
     */
    @Test
    fun dexPositionsAndPersonhood() {
        val chain = FakeChain()
        val a = registered(chain, alice)
        val b = wallet(chain, bob)
        b.sync()
        assertEquals(2_000_000L, bal(a, "uanml"))
        val pool = { mapOf("uanml" to SwapMath.Reserves(chain.poolErth, chain.poolAnml)) }

        // ANML -> ERTH: the quote is the chain's, the fee comes out of the output.
        val erth0 = bal(a, "uerth")
        val q = SwapMath.route(pool(), "uerth", "uanml", BigInteger.valueOf(600_000), "uerth", chain.swapFee)!!
        val min = SwapMath.withSlippage(q.amountOut, 100).toLong()
        a.noteSwap("uanml", 600_000, "uerth", min)
        a.sync()
        assertEquals(1_400_000L, bal(a, "uanml"))
        val fee = q.amountOut.toLong() - (bal(a, "uerth") - erth0)
        assertTrue("fee from output: $fee", fee in 1000..10_000)

        // ERTH -> ANML by bob after he is sent ERTH as one note: an ERTH
        // transfer balances all three slots together, so that note pays the
        // swap and its fee.
        a.send(b.address, "uerth", 2_000_000)
        a.sync(); b.sync()
        assertEquals(1, b.notes.count { it.note.denom == "uerth" && it.unspent })
        val bq = SwapMath.route(pool(), "uerth", "uerth", BigInteger.valueOf(1_000_000), "uanml", chain.swapFee)!!
        b.noteSwap("uerth", 1_000_000, "uanml", SwapMath.withSlippage(bq.amountOut, 50).toLong())
        b.sync()
        assertEquals(bq.amountOut.toLong(), bal(b, "uanml"))
        assertTrue(bal(b, "uerth") in 900_000 until 1_000_000)

        // A swap paid to someone else: bob opens its value-blind (v2)
        // ciphertext against the amount the chain publishes.
        val bAnml = bal(b, "uanml")
        val bErth = bal(b, "uerth")
        val gq = SwapMath.route(pool(), "uerth", "uanml", BigInteger.valueOf(100_000), "uerth", chain.swapFee)!!
        a.noteSwap("uanml", 100_000, "uerth", SwapMath.withSlippage(gq.amountOut, 100).toLong(), to = b.address)
        a.sync(); b.sync()
        assertEquals(bAnml, bal(b, "uanml"))
        val paid = chain.notes.last()
        assertEquals(177, paid.ciphertext.size)
        assertEquals("${bal(b, "uerth") - bErth}uerth", paid.amount)
        assertTrue(bal(b, "uerth") > bErth)
        assertEquals(1_300_000L, bal(a, "uanml"))

        // A bound the pool cannot meet fails before anything is spent.
        val before = a.balances()
        assertThrows(Exception::class.java) { a.noteSwap("uanml", 100_000, "uerth", 10_000_000) }
        a.sync()
        assertEquals(before, a.balances())

        // Pool-1 liquidity from notes: more ERTH than the ratio takes comes back.
        val provider = "earth1qqqsyqcyq5rqwzqfpg9scrgwpugpzysncc2uls"
        val erthBefore = bal(a, "uerth")
        a.addLiquidityShielded(1, "uanml", 400_000, 2_000_000, provider, "1")
        a.sync()
        assertTrue(chain.lpShares.getValue(provider).signum() > 0)
        // The ANML leg's rounding comes back too.
        val anmlAfterLp = bal(a, "uanml")
        assertTrue(anmlAfterLp in 900_000L..900_010L)
        val spent = erthBefore - bal(a, "uerth")
        assertTrue("the unused ERTH is refunded: spent $spent", spent in 800_000..810_000)

        // Groundworks: stake, lock a position, re-split it, vote with it, unlock.
        val derth = PrivacyWallet.derthDenom(validator)
        a.delegate(validator, 2_000_000)
        a.sync()
        assertEquals(1_800_000L, bal(a, derth))
        a.lockPosition(validator, 1_000_000, mapOf(2L to 60L, 5L to 40L))
        a.sync()
        assertEquals(800_000L, bal(a, derth))
        val (pos, key) = a.positions().single()
        assertEquals(mapOf(2L to 60L, 5L to 40L), pos.splits)
        a.updatePosition(pos, key, mapOf(2L to 100L))
        assertEquals(mapOf(2L to 100L), chain.positions.getValue(pos.id).splits)
        val yes = listOf(WeightedVoteOption.newBuilder().setOption(GovVoteOption.VOTE_OPTION_YES).setWeight("1").build())
        a.positionVote(a.positions().single().first, key, 9, yes)
        assertEquals(9L, chain.positionVotes.single().second)
        a.unlockPosition(a.positions().single().first, key)
        a.sync()
        assertTrue(a.positions().isEmpty())
        assertEquals(1_800_000L, bal(a, derth))

        // Stake votes: every derth note from before the snapshot, each once.
        a.sync()
        snapshot = chain.noteTree.size
        val voted = a.stakeVoteAll(11, yes)
        snapshot = null
        assertEquals(2, voted.size)
        a.sync()
        assertEquals(1_800_000L, bal(a, derth))

        // Referrer binding (lapses after R; refreshed past R/2).
        chain.now += 31 * 86_400
        a.sync()
        a.bindReferrer(provider)
        assertEquals(provider, chain.referrers.values.single())
        assertTrue(!a.referrerDue())
        chain.now += 16 * 86_400
        assertTrue(a.referrerDue())

        // Removal ballot: open one, vote in it.
        a.proposeRemoval(3)
        a.voteRemoval(3, yes = true)
        assertEquals(3L, chain.removalVotes.single().first)

        // Small notes merge two at a time.
        a.sync()
        repeat(3) { val o = a.shieldOutput("uanml", 1_000L + it); chain.shield("uanml", o.value, o.pc, o.ciphertext) }
        a.sync()
        val n0 = a.mergeable()["uanml"]!!
        a.merge("uanml")
        a.sync()
        assertEquals(n0 - 1, a.mergeable()["uanml"])
        assertEquals(anmlAfterLp + 3_003L, bal(a, "uanml"))

        // Unstake; the automation claims once epoch 4 (the fake's) has matured,
        // by timing alone.
        a.undelegate(validator, 500_000)
        a.sync()
        val unbond = PrivacyWallet.unbondDenom(validator, 4)
        assertEquals(500_000L, bal(a, unbond))
        val start = chain.now
        val notYet = PrivacyAutomation.matured(a.notes, start, 5, start, 86_400, 21 * 86_400, emptyMap())
        assertTrue(notYet.isEmpty())
        val ready = PrivacyAutomation.matured(a.notes, start + 21 * 86_400 + 3600, 5, start, 86_400, 21 * 86_400, emptyMap())
        val erthPre = bal(a, "uerth")
        a.claimUnbonding(ready.single())
        a.sync()
        assertEquals(0L, bal(a, unbond))
        assertTrue(bal(a, "uerth") - erthPre in 490_000 until 500_000)

        // Both wallets, restored from their mnemonics, find everything again.
        for ((w, words) in listOf(a to alice, b to bob)) {
            val restored = wallet(chain, words)
            restored.sync()
            assertEquals(w.balances(), restored.balances())
        }
        dump(chain, "dex")
    }

    /**
     * A wallet whose shielded ERTH is one note sends, unshields, stakes and
     * merges ERTH with the fee paid from the same notes (the transfer
     * circuit's combined ERTH balance), while a non-ERTH spend still needs an
     * ERTH note for its fee.
     */
    @Test
    fun singleErthNote() {
        val chain = FakeChain()
        val c = wallet(chain, alice)
        val b = wallet(chain, bob)
        c.sync(); b.sync()
        val o = c.shieldOutput("uerth", 3_000_000)
        chain.shield("uerth", o.value, o.pc, o.ciphertext)
        c.sync()
        val erthNotes = { c.notes.filter { it.note.denom == "uerth" && it.unspent && it.note.value > 0 } }
        assertEquals(1, erthNotes().size)

        c.send(b.address, "uerth", 1_000_000)
        c.sync(); b.sync()
        assertEquals(1_000_000L, bal(b, "uerth"))
        val afterSend = bal(c, "uerth")
        val sendFee = 2_000_000 - afterSend
        assertTrue("fee $sendFee", sendFee > 0)
        assertEquals(1, erthNotes().size)

        val receiver = "earth1qqqsyqcyq5rqwzqfpg9scrgwpugpzysncc2uls"
        c.unshield(receiver, "uerth", 500_000)
        c.sync()
        assertEquals(500_000L, chain.unshielded[receiver])
        assertEquals(1, erthNotes().size)
        assertTrue(bal(c, "uerth") < afterSend - 500_000)

        c.delegate(validator, 400_000)
        c.sync()
        assertEquals(1, erthNotes().size)
        assertTrue(bal(c, PrivacyWallet.derthDenom(validator)) > 0)

        // ERTH merges three notes into one, the fee paid from them.
        repeat(2) { val n = c.shieldOutput("uerth", 10_000L + it); chain.shield("uerth", n.value, n.pc, n.ciphertext) }
        c.sync()
        assertEquals(3, erthNotes().size)
        assertEquals(3, c.mergeable()["uerth"])
        val pre = bal(c, "uerth")
        c.merge("uerth")
        c.sync()
        assertEquals(1, erthNotes().size)
        assertTrue(bal(c, "uerth") < pre)

        // ANML with no ERTH note at all: its fee cannot come out of ANML.
        val d = wallet(chain, "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong")
        d.sync()
        val an = d.shieldOutput("uanml", 1_000_000)
        chain.shield("uanml", an.value, an.pc, an.ciphertext)
        d.sync()
        assertThrows(NoteSelection.Insufficient::class.java) { d.send(b.address, "uanml", 100_000) }
        dump(chain, "singleErthNote")
    }

    /**
     * With PRIVACY_TOML_OUT set, writes every witness the wallet proved as a
     * nargo Prover.toml, for `nargo execute` against the real circuits.
     */
    private fun dump(chain: FakeChain, test: String) {
        val out = System.getenv("PRIVACY_TOML_OUT") ?: return
        chain.prover.allTransfers.forEachIndexed { i, w -> File(out, "${test}_transfer_$i.toml").apply { parentFile.mkdirs() }.writeText(w.proverToml()) }
        chain.prover.allMemberships.forEachIndexed { i, w -> File(out, "${test}_membership_$i.toml").apply { parentFile.mkdirs() }.writeText(w.proverToml()) }
    }
}
