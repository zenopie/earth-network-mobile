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
 * Stake votes without spending (ORCHARD_DESIGN 8.5) as wallet
 * flows against [FakeChain]: one vote per validator carrying up to two notes
 * with one weight (their rounded value), on two concurrently open proposals,
 * unlinkable vote nullifiers, a second vote refused (locally, and by the
 * chain for a restored wallet), a note spent before the snapshot left out, a
 * note merged by a top-up after it still voting the value it held then while
 * the merged note cannot, a third note in a second vote, a labelled note at
 * its value after a slash (the current debt root); the indexer's nullifier
 * stream and the LCD fallback.
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
        override fun debtTree(start: Long, limit: Int) = chain.debtTreeRead(start, limit)
        override fun validators() = chain.validatorsRead()
        override fun minDelegation() = chain.minDelegation
    }

    private fun wallet(chain: FakeChain, indexer: PrivacyIndexer = chain, store: PrivacyStore = PrivacyStore.memory()) =
        PrivacyWallet(PrivacyKeys.fromMnemonic(alice), store, indexer, chain, reads(chain), chain.prover, chain.chainId, chain, now = { chain.now })

    private fun funded(chain: FakeChain, w: PrivacyWallet, amount: Long) {
        val o = w.shieldOutput("uerth", 0)
        chain.shield("uerth", amount, o.pc, o.ciphertext)
    }

    private fun PrivacyWallet.note(position: Long): OwnedStakeNote = stakeNotes.first { it.position == position }

    private fun PrivacyWallet.live(): List<OwnedStakeNote> = stakeNotes.filter { it.unspent && it.denom == derth }

    private class Scenario(val chain: FakeChain, val a: PrivacyWallet, val n: OwnedStakeNote, val p: OwnedStakeNote)

    /**
     * n delegated (a first delegation pads its input), p a second note of
     * ours at the same validator (another device's), before proposals 1 and
     * 2 open together.
     */
    private fun scenario(chain: FakeChain = FakeChain()): Scenario {
        val a = wallet(chain)
        repeat(12) { funded(chain, a, 2_000_000) }
        a.sync()
        a.delegate(vB, 1_111_111); a.sync()
        val n = a.live().single()
        chain.plantStake(a.keys, derth, 300_000); a.sync()
        val p = a.live().single { it.position != n.position }
        val s1 = chain.openProposal(1)
        val s2 = chain.openProposal(2)
        assertEquals(2L, s1.nfSize) // the sentinel and the first delegation's padding nullifier
        assertEquals(s1.nfRoot, s2.nfRoot)
        a.sync()
        return Scenario(chain, a, n, p)
    }

    /** The vote's notes, by position, as its witness carried them. */
    private fun FakeChain.lastVotePositions(): Set<Long> = prover.allVotes.last().slots.map { it.pos }.toSet()

    @Test
    fun oneVoteCarriesBothNotesOnTwoConcurrentProposals() {
        val sc = scenario()
        val (chain, a, n) = Triple(sc.chain, sc.a, sc.n)
        val eligible = listOf(sc.n, sc.p)
        assertEquals(listOf(PrivacyWallet.StakeVoteItem.Validator(vB, 2)), a.stakeVoteItems(1))
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
        assertEquals(4, vnfs.toSet().size)
        assertTrue(eligible.none { it.nf in vnfs })
        assertNotEquals(Privacy.voteNf(a.keys.nk, n.rho, n.position, 1), Privacy.voteNf(a.keys.nk, n.rho, n.position, 2))
        // One weight per vote: the notes' sum rounded down to 3 significant figures, and the current debt root.
        val w = PrivacyWallet.voteWeight(eligible.sumOf { it.amount })
        assertEquals(listOf(Triple(1L, vB, w), Triple(2L, vB, w)), chain.stakeVotes)
        assertEquals(listOf(2, 2), chain.stakeVoteSlots)
        assertEquals(chain.debtRoot(), chain.prover.allVotes.last().debtRoot)

        // Again on 1: refused here, nothing broadcast.
        val sims = chain.simulated
        assertThrows(PrivacyWallet.AlreadyVoted::class.java) { a.stakeVote(1, vB, no) }
        assertEquals(sims, chain.simulated)
        assertTrue(a.stakeVoteItems(1).isEmpty())
        assertTrue(a.stakeVoteItems(2).isEmpty())
        assertNull(a.castStakeVote(1, PrivacyWallet.StakeVoteItem.Validator(vB, 2), yes))

        // n is still an ordinary note: it undelegates.
        a.undelegate(vB, 10_000); a.sync()
        dumpWitnesses(chain, "stakeVoteConcurrent")
    }

    /** A note a top-up merged before the snapshot is spent under nf_root: the merged note votes in its place. */
    @Test
    fun spentBeforeTheSnapshotIsLeftOut() {
        val chain = FakeChain()
        val a = wallet(chain)
        repeat(6) { funded(chain, a, 2_000_000) }
        a.sync()
        a.delegate(vB, 1_111_111); a.sync()
        val n = a.live().single()
        a.delegate(vB, 500_000); a.sync()
        val merged = a.live().single()
        assertEquals(n.amount + (chain.lastMsg as network.erth.earth.proto.shieldedstaking.MsgDelegate).derth, merged.amount)
        chain.openProposal(1)
        a.sync()
        assertEquals(listOf(PrivacyWallet.StakeVoteItem.Validator(vB, 1)), a.stakeVoteItems(1))
        a.stakeVote(1, vB, no)
        assertEquals(setOf(merged.position), chain.lastVotePositions())
        dumpWitnesses(chain, "stakeVoteSpentBefore")
    }

    /**
     * The snapshot rule (ORCHARD_DESIGN 8.5): a top-up after the snapshot
     * spends both notes into one; the old notes still vote the value they
     * held (their openings kept), the merged note, not under the snapshot
     * root, cannot. No unit votes twice.
     */
    @Test
    fun toppedUpAfterTheSnapshotVotesThePreexistingValue() {
        val sc = scenario()
        val (chain, a) = sc.chain to sc.a
        a.delegate(vB, 700_000); a.sync()
        val merged = a.live().single()
        assertTrue(a.note(sc.n.position).spentHeight != null && a.note(sc.p.position).spentHeight != null)
        assertEquals(listOf(PrivacyWallet.StakeVoteItem.Validator(vB, 2)), a.stakeVoteItems(1))
        a.stakeVote(1, vB, abstain)
        val voted = chain.lastVotePositions()
        assertEquals(setOf(sc.n.position, sc.p.position), voted)
        assertFalse(merged.position in voted)
        assertEquals(Triple(1L, vB, PrivacyWallet.voteWeight(sc.n.amount + sc.p.amount)), chain.stakeVotes.single())
        // A proposal opened after the top-up sees the merged note alone.
        chain.openProposal(3); a.sync()
        assertEquals(listOf(PrivacyWallet.StakeVoteItem.Validator(vB, 1)), a.stakeVoteItems(3))
        a.stakeVote(3, vB, yes)
        assertEquals(setOf(merged.position), chain.lastVotePositions())
        dumpWitnesses(chain, "stakeVoteToppedUpAfter")
    }

    /** Three notes at one validator: the largest two in one vote, the third in a second (the user's choice: two weights). */
    @Test
    fun aThirdNoteVotesInASecondPart() {
        val chain = FakeChain()
        val a = wallet(chain)
        repeat(6) { funded(chain, a, 2_000_000) }
        a.sync()
        a.delegate(vB, 1_111_111); a.sync()
        chain.plantStake(a.keys, derth, 666_666)
        chain.plantStake(a.keys, derth, 111_111)
        chain.openProposal(1)
        a.sync()
        val ns = a.live().sortedByDescending { it.amount }
        val item = a.stakeVoteItems(1).single() as PrivacyWallet.StakeVoteItem.Validator
        assertEquals(3, item.notes)
        assertEquals(2, item.parts)
        // The snapshot names no rates: the ERTH figure is at the live book's.
        assertEquals(PrivacyWallet.VotePreview(2, liveValue(chain, vB, PrivacyWallet.voteWeight(ns.take(2).sumOf { it.amount }))), a.stakeVotePreview(1, item))
        assertNotNull(a.castStakeVote(1, item, yes))
        assertEquals(ns.take(2).map { it.position }.toSet(), chain.lastVotePositions())
        a.sync()
        assertEquals(PrivacyWallet.VotePreview(1, liveValue(chain, vB, PrivacyWallet.voteWeight(ns[2].amount))), a.stakeVotePreview(1, item))
        assertNotNull(a.castStakeVote(1, item, yes))
        assertEquals(setOf(ns[2].position), chain.lastVotePositions())
        assertNull(a.castStakeVote(1, item, yes))
        assertEquals(listOf(2, 1), chain.stakeVoteSlots)
        dumpWitnesses(chain, "stakeVoteThreeNotes")
    }

    /** Votes at two validators: one msg each, each its own weight; a second delegation to one merged into its note. */
    @Test
    fun oneVotePerValidator() {
        val chain = FakeChain()
        val a = wallet(chain)
        repeat(6) { funded(chain, a, 2_000_000) }
        a.sync()
        val vC = Vectors.json.getString("validator2")
        a.delegate(vB, 1_000_000); a.sync()
        a.delegate(vC, 2_000_000); a.sync()
        a.delegate(vC, 500_000); a.sync()
        chain.openProposal(3)
        a.sync()
        val items = a.stakeVoteItems(3)
        assertEquals(listOf(PrivacyWallet.StakeVoteItem.Validator(vB, 1), PrivacyWallet.StakeVoteItem.Validator(vC, 1)), items)
        items.forEach { assertNotNull(a.castStakeVote(3, it, yes)); a.sync() }
        val atB = a.stakeNotes.single { it.denom == derth && it.unspent }.amount
        val atC = a.stakeNotes.single { it.denom == PrivacyWallet.derthDenom(vC) && it.unspent }.amount
        assertEquals(listOf(Triple(3L, vB, PrivacyWallet.voteWeight(atB)), Triple(3L, vC, PrivacyWallet.voteWeight(atC))), chain.stakeVotes)
        assertEquals(listOf(1, 1), chain.stakeVoteSlots)
        dumpWitnesses(chain, "stakeVoteTwoValidators")
    }

    /**
     * A labelled note (stake moved in) votes its amount less the slash cut
     * of its exposure under the CURRENT debt tree: a slash after the
     * snapshot counts.
     */
    @Test
    fun aLabelledNoteVotesItsValueAfterASlash() {
        val chain = FakeChain()
        val a = wallet(chain)
        repeat(6) { funded(chain, a, 2_000_000) }
        a.sync()
        val vA = Vectors.json.getString("validator2")
        a.delegate(vA, 2_000_000); a.sync()
        a.redelegate(a.quoteMove(vA, vB, 1_000_000)); a.sync()
        val l = a.live().single()
        val label = l.label!!
        assertEquals(l.amount, label.exposed)
        chain.openProposal(1)
        // The source is slashed after the snapshot: the move's exposure is worth 60% now.
        chain.slashMove(label.moveKey, label.exposed * 6 / 10)
        a.sync()
        a.stakeVote(1, vB, yes)
        val w = chain.prover.allVotes.last()
        assertEquals(chain.debtRoot(), w.debtRoot)
        assertEquals(label, w.slots.single().label)
        assertEquals(Triple(1L, vB, PrivacyWallet.voteWeight(l.amount - label.exposed + label.exposed * 6 / 10)), chain.stakeVotes.single())
        dumpWitnesses(chain, "stakeVoteLabelled")
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
        assertNull(restored.castStakeVote(1, PrivacyWallet.StakeVoteItem.Validator(vB, 2), no))
        assertEquals(before, sc.chain.height)
        assertEquals(2, restored.store.state.stakeVotes.count { it.confirmed && it.proposalId == 1L })
        assertTrue(restored.stakeVoteItems(1).isEmpty())
    }

    /** A restored wallet whose first part voted: each voted note is learned from a refusal, the vote goes out with what is left. */
    @Test
    fun restoredWalletVotesWhatIsLeft() {
        val chain = FakeChain()
        val a = wallet(chain)
        repeat(6) { funded(chain, a, 2_000_000) }
        a.sync()
        a.delegate(vB, 1_111_111); a.sync()
        chain.plantStake(a.keys, derth, 666_666)
        chain.plantStake(a.keys, derth, 111_111)
        chain.openProposal(1)
        a.sync()
        val ns = a.live().sortedByDescending { it.amount }
        a.stakeVote(1, vB, yes)
        val restored = wallet(chain)
        restored.sync()
        assertEquals(listOf(PrivacyWallet.StakeVoteItem.Validator(vB, 3)), restored.stakeVoteItems(1))
        val before = chain.height
        assertNotNull(restored.castStakeVote(1, PrivacyWallet.StakeVoteItem.Validator(vB, 3), no))
        // One block: the two refusals were simulations, nothing paid.
        assertEquals(before + 1, chain.height)
        assertEquals(setOf(ns[2].position), chain.lastVotePositions())
        assertEquals(3, restored.store.state.stakeVotes.count { it.confirmed })
    }

    /** A vote whose tx failed in its block is forgotten: its notes vote again. */
    @Test
    fun aFailedVoteIsRetried() {
        val sc = scenario()
        sc.chain.failInBlockNext = 1
        assertThrows(java.io.IOException::class.java) { sc.a.stakeVote(1, vB, yes) }
        assertEquals(2, sc.a.store.state.stakeVotes.count { !it.confirmed })
        sc.a.sync()
        assertEquals(listOf(PrivacyWallet.StakeVoteItem.Validator(vB, 2)), sc.a.stakeVoteItems(1))
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
