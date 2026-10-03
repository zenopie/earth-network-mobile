package network.erth.wallet.privacy

import cosmos.gov.v1.VoteOption as GovVoteOption
import cosmos.gov.v1.WeightedVoteOption
import network.erth.wallet.privacy.keys.PrivacyKeys
import network.erth.wallet.privacy.note.OwnedStakeNote
import network.erth.wallet.privacy.sync.PrivacyIndexer
import network.erth.wallet.privacy.sync.PrivacyStore
import network.erth.wallet.privacy.sync.StakeNfLeavesPage
import network.erth.wallet.privacy.zk.Fr
import network.erth.wallet.privacy.zk.Privacy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stake votes without spending (ORCHARD_DESIGN 15), the chain's
 * TestStakeVoteConcurrentProposals as wallet flows against [FakeChain]: one
 * note on two concurrently open proposals, unlinkable vote nullifiers, a
 * second vote refused (locally, and by the chain for a restored wallet), a
 * note spent before the snapshot refused locally, one restaked after it
 * still voting while its outputs cannot, the note spendable throughout; the
 * indexer's nullifier stream and the LCD fallback; the rounded weight.
 */
class StakeVoteFlowTest {
    private val alice = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private val vB = "earthvaloper1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"
    private val derth = PrivacyWallet.derthDenom(vB)

    private fun opt(o: GovVoteOption) = listOf(WeightedVoteOption.newBuilder().setOption(o).setWeight("1").build())
    private val yes = opt(GovVoteOption.VOTE_OPTION_YES)
    private val no = opt(GovVoteOption.VOTE_OPTION_NO)
    private val abstain = opt(GovVoteOption.VOTE_OPTION_ABSTAIN)

    private fun reads(chain: FakeChain) = object : PrivacyChainReads {
        override fun personhoodParams() = PrivacyChainReads.PersonhoodParams(30L * 86_400, 3_600)
        override fun ballotInputs(proposalId: Long, optionId: Long) =
            PrivacyChainReads.BallotInputs(Privacy.proposalScope(proposalId, 0), Fr.ZERO, Fr.ZERO, chain.now - 3600, 0, 0)
        override fun epochNumber() = chain.epoch
        override fun snapshot(proposalId: Long) = chain.snapshotRead(proposalId)
        override fun stakeNullifierTree(start: Long, limit: Int) = chain.nfTreeRead(start, limit)
        override fun positions() = chain.positionReads()
    }

    private fun wallet(chain: FakeChain, indexer: PrivacyIndexer = chain, store: PrivacyStore = PrivacyStore.memory()) =
        PrivacyWallet(PrivacyKeys.fromMnemonic(alice), store, indexer, chain, reads(chain), chain.prover, chain.chainId, chain, now = { chain.now })

    private fun funded(chain: FakeChain, w: PrivacyWallet, amount: Long) {
        val o = w.shieldOutput("uerth", 0)
        chain.shield("uerth", amount, o.pc, o.ciphertext)
    }

    private fun PrivacyWallet.newest(): OwnedStakeNote = stakeNotes.maxByOrNull { it.position }!!

    private fun PrivacyWallet.note(position: Long): OwnedStakeNote = stakeNotes.first { it.position == position }

    private fun delegate(a: PrivacyWallet, amount: Long): OwnedStakeNote {
        a.delegate(vB, amount); a.sync()
        return a.newest()
    }

    /** Restakes [n] into two notes; returns them. */
    private fun restakeAll(a: PrivacyWallet, n: OwnedStakeNote): List<OwnedStakeNote> {
        val before = a.stakeNotes.map { it.position }.toSet()
        a.restake(vB, listOf(n), listOf(n.amount / 2, n.amount - n.amount / 2)); a.sync()
        return a.stakeNotes.filter { it.position !in before }
    }

    private class Scenario(val chain: FakeChain, val a: PrivacyWallet, val n: OwnedStakeNote, val k: OwnedStakeNote, val m: OwnedStakeNote, val m1: List<OwnedStakeNote>)

    /** n, k, m delegated; m restaked (spent) before proposals 1 and 2 open together. */
    private fun scenario(chain: FakeChain = FakeChain()): Scenario {
        val a = wallet(chain)
        repeat(12) { funded(chain, a, 2_000_000) }
        a.sync()
        val n = delegate(a, 1_111_111)
        val k = delegate(a, 666_666)
        val m = delegate(a, 333_333)
        val m1 = restakeAll(a, m)
        val s1 = chain.openProposal(1)
        val s2 = chain.openProposal(2)
        assertEquals(2L, s1.nfSize) // the sentinel and m's nullifier
        assertEquals(s1.nfRoot, s2.nfRoot)
        a.sync()
        return Scenario(chain, a, n, k, a.note(m.position), m1)
    }

    @Test
    fun oneNoteVotesOnTwoConcurrentProposals() {
        val sc = scenario()
        val (chain, a, n) = Triple(sc.chain, sc.a, sc.n)
        val proofsBefore = chain.prover.allStakes.size
        a.stakeVote(1, n, yes)
        a.stakeVote(2, n, no)
        // No stake proof, nothing minted, nothing spent.
        assertEquals(proofsBefore, chain.prover.allStakes.size)
        assertFalse(n.nf in chain.stakeNullifiers)
        a.sync()
        assertTrue(a.note(n.position).spendable)
        // Unlinkable: one vote nullifier per proposal, neither the spend nullifier.
        val vnfs = chain.voteNullifiers.map { it.second }
        assertEquals(2, vnfs.toSet().size)
        assertTrue(n.nf !in vnfs)
        assertNotEquals(Privacy.voteNf(a.keys.nk, n.rho, n.position, 1), Privacy.voteNf(a.keys.nk, n.rho, n.position, 2))
        // Weighted at its rounded amount (3 significant figures).
        val w = PrivacyWallet.voteWeight(n.amount)
        assertEquals(listOf(Triple(1L, vB, w), Triple(2L, vB, w)), chain.stakeVotes)
        assertEquals(999_000L, w) // 1,111,111 x 9/10 = 999,999 uderth

        // Again on 1: refused here, nothing broadcast.
        val sims = chain.simulated
        assertThrows(PrivacyWallet.AlreadyVoted::class.java) { a.stakeVote(1, n, no) }
        assertEquals(sims, chain.simulated)
        assertTrue(a.stakeVoteItems(1).none { it == PrivacyWallet.StakeVoteItem.Note(n.position) })
        assertTrue(a.stakeVoteItems(2).none { it == PrivacyWallet.StakeVoteItem.Note(n.position) })

        // n is still an ordinary note: it undelegates.
        a.undelegate(vB, 10_000); a.sync()
        dumpWitnesses(chain, "stakeVoteConcurrent")
    }

    @Test
    fun spentBeforeTheSnapshotIsRefusedLocally() {
        val sc = scenario()
        val (chain, a) = sc.chain to sc.a
        // m's nullifier is under nf_root: no low leaf proves it absent, nothing is broadcast.
        assertTrue(a.stakeVoteItems(1).none { it == PrivacyWallet.StakeVoteItem.Note(sc.m.position) })
        val sims = chain.simulated
        assertThrows(PrivacyWallet.SpentBeforeSnapshot::class.java) { a.stakeVote(1, sc.m, yes) }
        assertNull(a.castStakeVote(1, PrivacyWallet.StakeVoteItem.Note(sc.m.position), yes))
        assertEquals(sims, chain.simulated)
        // Its outputs came before the snapshot: they vote.
        for (o in sc.m1) a.stakeVote(1, o, no)
        assertEquals(sc.m1.map { PrivacyWallet.voteWeight(it.amount) }, chain.stakeVotes.map { it.third })
        dumpWitnesses(chain, "stakeVoteSpentBefore")
    }

    @Test
    fun restakedAfterTheSnapshotStillVotesItsOutputsCannot() {
        val sc = scenario()
        val (chain, a) = sc.chain to sc.a
        val k1 = restakeAll(a, sc.k)
        assertTrue(a.note(sc.k.position).spentHeight != null)
        // Its outputs are not under the note root.
        for (o in k1) {
            assertThrows(IllegalArgumentException::class.java) { a.stakeVote(1, o, yes) }
            assertTrue(a.stakeVoteItems(1).none { it == PrivacyWallet.StakeVoteItem.Note(o.position) })
        }
        // k itself still votes: its nullifier went in after nf_root.
        assertTrue(a.stakeVoteItems(1).contains(PrivacyWallet.StakeVoteItem.Note(sc.k.position)))
        a.stakeVote(1, a.note(sc.k.position), abstain)
        assertEquals(Triple(1L, vB, PrivacyWallet.voteWeight(sc.k.amount)), chain.stakeVotes.single())
        dumpWitnesses(chain, "stakeVoteRestakedAfter")
    }

    /** Every eligible note on both proposals through castStakeVote: the tallies count each once. */
    @Test
    fun everyNoteOnBothProposals() {
        val sc = scenario()
        val (chain, a) = sc.chain to sc.a
        for (p in listOf(1L, 2L)) {
            val items = a.stakeVoteItems(p)
            assertEquals(setOf(sc.n.position, sc.k.position) + sc.m1.map { it.position },
                items.map { (it as PrivacyWallet.StakeVoteItem.Note).position }.toSet())
            items.forEach { assertTrue(a.castStakeVote(p, it, yes) != null) }
            a.sync()
            assertTrue(a.stakeVoteItems(p).isEmpty())
        }
        assertEquals(8, chain.stakeVotes.size)
        assertEquals(8, chain.voteNullifiers.size)
    }

    /** A wallet restored from the mnemonic does not know its votes: the chain's refusal (at simulate) records it, nothing is paid. */
    @Test
    fun restoredWalletLearnsItAlreadyVoted() {
        val sc = scenario()
        sc.a.stakeVote(1, sc.n, yes)
        val restored = wallet(sc.chain)
        restored.sync()
        val before = sc.chain.height
        assertNull(restored.castStakeVote(1, PrivacyWallet.StakeVoteItem.Note(sc.n.position), no))
        assertEquals(before, sc.chain.height)
        assertTrue(restored.store.state.stakeVotes.single().confirmed)
        assertTrue(restored.stakeVoteItems(1).none { it == PrivacyWallet.StakeVoteItem.Note(sc.n.position) })
    }

    /** A vote whose tx failed in its block is forgotten: the note votes again. */
    @Test
    fun aFailedVoteIsRetried() {
        val sc = scenario()
        sc.chain.failInBlockNext = 1
        assertThrows(java.io.IOException::class.java) { sc.a.stakeVote(1, sc.n, yes) }
        assertFalse(sc.a.store.state.stakeVotes.single().confirmed)
        sc.a.sync()
        assertTrue(sc.a.stakeVoteItems(1).contains(PrivacyWallet.StakeVoteItem.Note(sc.n.position)))
        sc.a.stakeVote(1, sc.a.note(sc.n.position), yes)
        assertEquals(1, sc.chain.stakeVotes.size)
    }

    /** An indexer without the streams: the snapshot and the nullifiers come from the LCD. */
    @Test
    fun lcdFallback() {
        val chain = FakeChain()
        chain.indexerNfTree = false
        chain.indexerSnapshots = false
        val sc = scenario(chain)
        sc.a.stakeVote(1, sc.n, yes)
        assertEquals(listOf(0L), chain.nfTreeAsks)
        assertEquals(1, chain.stakeVotes.size)
    }

    /** An indexer whose nullifier stream does not rebuild nf_root: the wallet rebuilds from the chain instead. */
    @Test
    fun forgedNullifierStreamFallsBackToTheChain() {
        val chain = FakeChain()
        val forged = object : PrivacyIndexer by chain {
            override fun stakeNullifierLeaves(fromIndex: Long, limit: Int?): StakeNfLeavesPage {
                val p = chain.stakeNullifierLeaves(fromIndex, limit)
                // Leaves out nothing, but swaps the value: the root no longer matches.
                return p.copy(leaves = p.leaves.map { (i, _) -> i to Fr.of(424242) })
            }
        }
        val a0 = scenario(chain)
        val a = wallet(chain, indexer = forged)
        a.sync()
        a.stakeVote(1, a.note(a0.n.position), yes)
        assertEquals(listOf(0L), chain.nfTreeAsks)
        assertEquals(1, chain.stakeVotes.size)
    }

    @Test
    fun weightRounding() {
        assertEquals(1L, PrivacyWallet.voteWeight(1))
        assertEquals(999L, PrivacyWallet.voteWeight(999))
        assertEquals(1_000L, PrivacyWallet.voteWeight(1_000))
        assertEquals(1_000L, PrivacyWallet.voteWeight(1_009))
        assertEquals(1_230_000L, PrivacyWallet.voteWeight(1_234_567))
        assertEquals(999_000L, PrivacyWallet.voteWeight(999_999))
        assertEquals(123_000_000L, PrivacyWallet.voteWeight(123_456_789))
        assertEquals(9_220_000_000_000_000_000L, PrivacyWallet.voteWeight(Long.MAX_VALUE))
        assertThrows(IllegalArgumentException::class.java) { PrivacyWallet.voteWeight(0) }
    }
}
