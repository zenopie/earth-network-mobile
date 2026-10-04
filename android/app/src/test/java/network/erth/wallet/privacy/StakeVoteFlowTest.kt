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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stake votes without spending (ORCHARD_DESIGN 15, 18.2), the chain's
 * TestStakeVoteConcurrentProposals and TestStakeVoteManyNotesOneWeight as
 * wallet flows against [FakeChain]: one vote per validator carrying up to
 * four notes with one weight (their rounded sum), on two concurrently open
 * proposals, unlinkable vote nullifiers, a second vote refused (locally, and
 * by the chain for a restored wallet), a note spent before the snapshot left
 * out, one restaked after it still voting while its outputs cannot, a fifth
 * note in a second vote; the indexer's nullifier stream and the LCD fallback.
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
        override fun leaseBounds() = chain.leaseBounds()
        override fun ballotInputs(proposalId: Long, optionId: Long) =
            PrivacyChainReads.BallotInputs(Privacy.proposalScope(proposalId, 0), Fr.ZERO, Fr.ZERO, Privacy.NO_BOUND, 0, 0, chain.ballotMaxPredecessor())
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

    /** The vote's notes, by position, as its witness carried them. */
    private fun FakeChain.lastVotePositions(): Set<Long> = prover.allVotes.last().slots.map { it.pos }.toSet()

    @Test
    fun oneVoteCarriesEveryNoteOnTwoConcurrentProposals() {
        val sc = scenario()
        val (chain, a, n) = Triple(sc.chain, sc.a, sc.n)
        val eligible = listOf(sc.n, sc.k) + sc.m1
        assertEquals(listOf(PrivacyWallet.StakeVoteItem.Validator(vB, 4)), a.stakeVoteItems(1))
        val proofsBefore = chain.prover.allStakes.size
        a.stakeVote(1, vB, yes)
        assertEquals(eligible.map { it.position }.toSet(), chain.lastVotePositions())
        a.stakeVote(2, vB, no)
        // No stake proof, nothing minted, nothing spent.
        assertEquals(proofsBefore, chain.prover.allStakes.size)
        assertFalse(n.nf in chain.stakeNullifiers)
        a.sync()
        assertTrue(a.note(n.position).spendable)
        // Unlinkable: a vote nullifier per note and proposal, none a spend nullifier.
        val vnfs = chain.voteNullifiers.map { it.second }
        assertEquals(8, vnfs.toSet().size)
        assertTrue(eligible.none { it.nf in vnfs })
        assertNotEquals(Privacy.voteNf(a.keys.nk, n.rho, n.position, 1), Privacy.voteNf(a.keys.nk, n.rho, n.position, 2))
        // One weight per vote: the notes' sum rounded down to 3 significant figures.
        val w = PrivacyWallet.voteWeight(eligible.sumOf { it.amount })
        assertEquals(listOf(Triple(1L, vB, w), Triple(2L, vB, w)), chain.stakeVotes)
        assertEquals(listOf(4, 4), chain.stakeVoteSlots)
        assertEquals(1_890_000L, w) // 999,999 + 599,999 + 299,999 (two halves)

        // Again on 1: refused here, nothing broadcast.
        val sims = chain.simulated
        assertThrows(PrivacyWallet.AlreadyVoted::class.java) { a.stakeVote(1, vB, no) }
        assertEquals(sims, chain.simulated)
        assertTrue(a.stakeVoteItems(1).isEmpty())
        assertTrue(a.stakeVoteItems(2).isEmpty())
        assertNull(a.castStakeVote(1, PrivacyWallet.StakeVoteItem.Validator(vB, 4), yes))

        // n is still an ordinary note: it undelegates.
        a.undelegate(vB, 10_000); a.sync()
        dumpWitnesses(chain, "stakeVoteConcurrent")
    }

    @Test
    fun spentBeforeTheSnapshotIsLeftOut() {
        val sc = scenario()
        val (chain, a) = sc.chain to sc.a
        // m's nullifier is under nf_root: it is no candidate; its outputs, from before the snapshot, are.
        a.stakeVote(1, vB, no)
        assertFalse(sc.m.position in chain.lastVotePositions())
        assertTrue(sc.m1.all { it.position in chain.lastVotePositions() })
        dumpWitnesses(chain, "stakeVoteSpentBefore")
    }

    @Test
    fun restakedAfterTheSnapshotStillVotesItsOutputsCannot() {
        val sc = scenario()
        val (chain, a) = sc.chain to sc.a
        val k1 = restakeAll(a, sc.k)
        assertTrue(a.note(sc.k.position).spentHeight != null)
        // k itself still votes (its nullifier went in after nf_root); its outputs are not under the note root.
        assertEquals(listOf(PrivacyWallet.StakeVoteItem.Validator(vB, 4)), a.stakeVoteItems(1))
        a.stakeVote(1, vB, abstain)
        val voted = chain.lastVotePositions()
        assertTrue(sc.k.position in voted)
        assertTrue(k1.none { it.position in voted })
        assertEquals(Triple(1L, vB, PrivacyWallet.voteWeight((listOf(sc.n, sc.k) + sc.m1).sumOf { it.amount })), chain.stakeVotes.single())
        dumpWitnesses(chain, "stakeVoteRestakedAfter")
    }

    /** Five notes at one validator: the largest four in one vote, the fifth in a second (the user's choice: two weights). */
    @Test
    fun aFifthNoteVotesInASecondPart() {
        val chain = FakeChain()
        val a = wallet(chain)
        repeat(12) { funded(chain, a, 2_000_000) }
        a.sync()
        val ns = listOf(1_111_111L, 666_666, 333_333, 222_222, 111_111).map { delegate(a, it) }
        chain.openProposal(1)
        a.sync()
        val item = a.stakeVoteItems(1).single() as PrivacyWallet.StakeVoteItem.Validator
        assertEquals(5, item.notes)
        assertEquals(2, item.parts)
        assertEquals(PrivacyWallet.VotePreview(4, PrivacyWallet.voteWeight(ns.take(4).sumOf { it.amount })), a.stakeVotePreview(1, item))
        assertNotNull(a.castStakeVote(1, item, yes))
        assertEquals(ns.take(4).map { it.position }.toSet(), chain.lastVotePositions())
        a.sync()
        assertEquals(PrivacyWallet.VotePreview(1, PrivacyWallet.voteWeight(ns[4].amount)), a.stakeVotePreview(1, item))
        assertNotNull(a.castStakeVote(1, item, yes))
        assertEquals(setOf(ns[4].position), chain.lastVotePositions())
        assertNull(a.castStakeVote(1, item, yes))
        assertEquals(listOf(4, 1), chain.stakeVoteSlots)
        assertEquals(listOf(PrivacyWallet.voteWeight(ns.take(4).sumOf { it.amount }), PrivacyWallet.voteWeight(ns[4].amount)),
            chain.stakeVotes.map { it.third })
        dumpWitnesses(chain, "stakeVoteFiveNotes")
    }

    /** Votes at two validators: one msg each, each its own weight. */
    @Test
    fun oneVotePerValidator() {
        val chain = FakeChain()
        val a = wallet(chain)
        repeat(6) { funded(chain, a, 2_000_000) }
        a.sync()
        val vC = "earthvaloper1qyqszqgpqyqszqgpqyqszqgpqyqszqgpjnp7du"
        a.delegate(vB, 1_000_000); a.sync()
        a.delegate(vC, 2_000_000); a.sync()
        a.delegate(vC, 500_000); a.sync()
        chain.openProposal(3)
        a.sync()
        val items = a.stakeVoteItems(3)
        assertEquals(listOf(PrivacyWallet.StakeVoteItem.Validator(vB, 1), PrivacyWallet.StakeVoteItem.Validator(vC, 2)), items)
        items.forEach { assertNotNull(a.castStakeVote(3, it, yes)); a.sync() }
        assertEquals(listOf(Triple(3L, vB, 900_000L), Triple(3L, vC, PrivacyWallet.voteWeight(1_800_000L + 450_000L))), chain.stakeVotes)
        assertEquals(listOf(1, 2), chain.stakeVoteSlots)
        dumpWitnesses(chain, "stakeVoteTwoValidators")
    }

    /**
     * A wallet restored from the mnemonic does not know its votes: the
     * chain's refusal at simulate names a vote nullifier, which is recorded,
     * and the vote is laid out again without it; nothing is paid or sent.
     */
    @Test
    fun restoredWalletLearnsItAlreadyVoted() {
        val sc = scenario()
        sc.a.stakeVote(1, vB, yes)
        val restored = wallet(sc.chain)
        restored.sync()
        val before = sc.chain.height
        assertNull(restored.castStakeVote(1, PrivacyWallet.StakeVoteItem.Validator(vB, 4), no))
        assertEquals(before, sc.chain.height)
        assertEquals(4, restored.store.state.stakeVotes.count { it.confirmed && it.proposalId == 1L })
        assertTrue(restored.stakeVoteItems(1).isEmpty())
    }

    /** A restored wallet whose first part voted: each voted note is learned from a refusal, the vote goes out with what is left. */
    @Test
    fun restoredWalletVotesWhatIsLeft() {
        val chain = FakeChain()
        val a = wallet(chain)
        repeat(12) { funded(chain, a, 2_000_000) }
        a.sync()
        val ns = listOf(1_111_111L, 666_666, 333_333, 222_222, 111_111).map { delegate(a, it) }
        chain.openProposal(1)
        a.sync()
        a.stakeVote(1, vB, yes)
        val restored = wallet(chain)
        restored.sync()
        assertEquals(listOf(PrivacyWallet.StakeVoteItem.Validator(vB, 5)), restored.stakeVoteItems(1))
        val before = chain.height
        assertNotNull(restored.castStakeVote(1, PrivacyWallet.StakeVoteItem.Validator(vB, 5), no))
        // One block: the four refusals were simulations, nothing paid.
        assertEquals(before + 1, chain.height)
        assertEquals(setOf(ns[4].position), chain.lastVotePositions())
        assertEquals(5, restored.store.state.stakeVotes.count { it.confirmed })
    }

    /** A vote whose tx failed in its block is forgotten: its notes vote again. */
    @Test
    fun aFailedVoteIsRetried() {
        val sc = scenario()
        sc.chain.failInBlockNext = 1
        assertThrows(java.io.IOException::class.java) { sc.a.stakeVote(1, vB, yes) }
        assertEquals(4, sc.a.store.state.stakeVotes.count { !it.confirmed })
        sc.a.sync()
        assertEquals(listOf(PrivacyWallet.StakeVoteItem.Validator(vB, 4)), sc.a.stakeVoteItems(1))
        sc.a.stakeVote(1, vB, yes)
        assertEquals(1, sc.chain.stakeVotes.size)
    }

    /** An indexer without the streams: the snapshot and the nullifiers come from the LCD. */
    @Test
    fun lcdFallback() {
        val chain = FakeChain()
        chain.indexerNfTree = false
        chain.indexerSnapshots = false
        val sc = scenario(chain)
        sc.a.stakeVote(1, vB, yes)
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
        a.stakeVote(1, vB, yes)
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
