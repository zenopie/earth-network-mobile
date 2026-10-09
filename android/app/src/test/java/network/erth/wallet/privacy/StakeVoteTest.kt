package network.erth.wallet.privacy

import network.erth.wallet.privacy.keys.PrivacyKeys
import network.erth.wallet.privacy.prove.VoteWitness
import network.erth.wallet.privacy.sync.StakeNfLeavesPage
import network.erth.wallet.privacy.sync.StakeSnapshotRow
import network.erth.wallet.privacy.sync.StakeSnapshotsPage
import network.erth.wallet.privacy.tx.PrivateMsgs
import network.erth.wallet.privacy.tx.UnsignedTx
import network.erth.wallet.privacy.zk.Fr
import network.erth.wallet.privacy.zk.IndexedTree
import network.erth.wallet.privacy.zk.Privacy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stake votes: one tx per validator, each the user's own;
 * two slots and the chain's rounded weight; the snapshot and its nullifier
 * tree taken from the chain; what has voted survives a reset.
 */
class StakeVoteTest : WalletTest() {
    /** Two validators' derth, before proposal 12's snapshot. */
    private fun stakedAtTwoValidators(chain: FakeChain): PrivacyWallet {
        val v1 = "earthvaloper1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"
        val v2 = "earthvaloper1qyqszqgpqyqszqgpqyqszqgpqyqszqgpjnp7du"
        val a = wallet(chain)
        repeat(4) { funded(chain, a, 2_000_000) }
        a.sync()
        a.delegate(v1, 1_000_000); a.sync()
        a.delegate(v2, 1_000_000); a.sync()
        chain.openProposal(12)
        return a
    }

    private fun staked(chain: FakeChain, due: (Long) -> Long? = { null }): PrivacyWallet {
        val a = wallet(chain, r = reads(chain, due = due))
        repeat(4) { funded(chain, a, 2_000_000) }
        a.sync()
        a.delegate(vB, 1_000_000); a.sync()
        return a
    }

    private val vB = "earthvaloper1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"

    private val v1 = "earthvaloper1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"

    /**
     * k delegated before proposal 1's snapshot, beside a second note of ours
     * (another device's), and merged by a top-up (spent) after it: on chain it
     * still votes the value it held at the snapshot.
     */
    private fun voting(): Voting {
        val chain = FakeChain()
        val a = wallet(chain)
        repeat(6) { funded(chain, a, 2_000_000) }
        a.sync()
        a.delegate(vB, 666_666); a.sync()
        val k = a.stakeNotes.single { it.unspent }
        chain.plantStake(a.keys, PrivacyWallet.derthDenom(vB), 300_000); a.sync()
        chain.openProposal(1)
        a.delegate(vB, 333_333); a.sync()
        return Voting(chain, a, a.stakeNotes.first { it.position == k.position })
    }

    private class Voting(val chain: FakeChain, val a: PrivacyWallet, val k: network.erth.wallet.privacy.note.OwnedStakeNote)

    /**
     * One vote per validator, each its own tx the user confirms; nothing is
     * cast in the background. A validator voted once is not voted again.
     */
    @Test
    fun stakeVotesAreOneTxPerValidator() {
        val chain = FakeChain()
        val a = stakedAtTwoValidators(chain)
        a.sync()
        val items = a.stakeVoteItems(12)
        assertEquals(2, items.size)
        val before = chain.height
        for (item in items) {
            org.junit.Assert.assertNotNull(a.castStakeVote(12, item, yes))
            a.sync()
        }
        assertEquals(2, chain.stakeVotes.size)
        assertEquals(before + 2, chain.height)
        // Final: the validators' notes have voted.
        assertEquals(null, a.castStakeVote(12, items.first(), yes))
        assertEquals(2, chain.stakeVotes.size)
    }

    @Test
    fun roundVoteWeightMatchesTheChain() {
        val v = Vectors.json.getJSONObject("round_vote_weight")
        for (k in v.keys()) {
            val w = java.math.BigInteger(k)
            if (w.bitLength() > 63) continue // past what a wallet holds
            assertEquals(k, v.getString(k).toLong(), PrivacyWallet.voteWeight(w.toLong()))
        }
    }

    @Test
    fun aVoteHasTwoSlotsAndNinePublicInputs() {
        val chain = FakeChain()
        val a = staked(chain)
        // A top-up merges into the one note.
        a.delegate(vB, 500_000); a.sync()
        val note = a.stakeNotes.single { it.unspent }
        chain.openProposal(7)
        a.sync()
        a.stakeVote(7, vB, yes)
        val m = chain.lastMsg as network.erth.earth.proto.shieldedstaking.MsgStakeVote
        assertEquals(2, m.voteNullifiersCount)
        val vs = m.voteNullifiersList.map { Fr.fromBytes(it.toByteArray()) }
        // One note, two non-zero vote nullifiers: the note's and a padding one.
        assertEquals(listOf(false, false), vs.map { it.isZero })
        assertEquals(2, vs.toSet().size)
        assertEquals(chain.debtRoot(), Fr.fromBytes(m.debtRoot.toByteArray()))
        val w = chain.prover.allVotes.last()
        assertEquals(9, w.publicInputs().size)
        assertEquals(vs, w.vnfs)
        val padAt = w.layout.order.indexOf(null)
        assertEquals(Privacy.votePadNf(w.nk, w.layout.padR(padAt), 7), vs[padAt])
        assertEquals(Privacy.voteNf(w.nk, note.rho, note.position, 7), vs[1 - padAt])
        val inputs = w.noirInputs()
        for (k in listOf("amount", "rho", "rcm", "pos", "path", "move_key", "move_time", "exposed", "low_value", "low_next_value",
            "low_next_index", "low_index", "low_path", "debt_low_key", "debt_low_next_key", "debt_low_next_index", "debt_low_retained",
            "debt_low_index", "debt_low_path", "vnf")) {
            assertEquals(k, 2, (inputs.getValue(k) as List<*>).size)
        }
        assertEquals("0x0", (inputs.getValue("amount") as List<*>)[padAt])
        assertEquals(w.layout.padR(padAt).toNoir(), (inputs.getValue("rho") as List<*>)[padAt])
        assertEquals(32, ((inputs.getValue("path") as List<*>)[padAt] as List<*>).size)
        assertEquals(m.weight, PrivacyWallet.voteWeight(note.amount))
        dumpWitnesses(chain, "fix7VoteSlots")
        // The msg's own checks: two slots, used first, distinct.
        assertThrows(IllegalArgumentException::class.java) {
            PrivateMsgs.withVote(m, vs.take(1), ByteArray(0))
        }
    }

    @Test
    fun aVoteWitnessRefusesWhatTheCircuitWould() {
        val chain = FakeChain()
        val a = staked(chain)
        chain.openProposal(8)
        a.sync()
        a.stakeVote(8, vB, yes)
        val w = chain.prover.allVotes.last()
        // The same note twice; more weight than the notes.
        assertThrows(IllegalArgumentException::class.java) { w.copy(slots = w.slots + w.slots).check() }
        assertThrows(IllegalArgumentException::class.java) { w.copy(weight = w.slots.sumOf { it.amount } + 1).check() }
        assertThrows(IllegalArgumentException::class.java) { w.copy(slots = List(3) { w.slots[0] }) }
        assertTrue(VoteWitness.MAX_NOTES == PrivateMsgs.MAX_VOTE_NOTES)
    }

    /** A 1119 in a block names one note: only that one is final, the vote's others may vote again. */
    @Test
    fun aRefusalInABlockSettlesOnlyTheNamedNote() {
        val vnf = Fr.of(77)
        assertTrue(PrivacyWallet.namesVoteNullifier("this stake note already voted on this proposal: proposal 3, vote nullifier ${vnf.toHex().uppercase()}", vnf))
        assertFalse(PrivacyWallet.namesVoteNullifier("this stake note already voted on this proposal: proposal 3, vote nullifier 00", vnf))
        assertEquals(vnf, PrivacyWallet.usedVoteNullifier(IllegalStateException("x", IllegalArgumentException("vote nullifier ${vnf.toHex().uppercase()}")), listOf(Fr.of(1), vnf)))
    }

    /** A stake snapshot larger than the local stake tree is "sync first", not a crash. */
    @Test
    fun snapshotAheadOfLocalTreeAsksForASync() {
        val chain = FakeChain()
        val a = wallet(chain)
        repeat(2) { funded(chain, a, 2_000_000) }
        a.sync()
        a.delegate(v1, 1_000_000)
        a.sync()
        // Someone else's stake lands after this wallet's sync.
        chain.plantStake(PrivacyKeys.fromMnemonic("legal winner thank year wave sausage worth useful legal winner thank yellow"), PrivacyWallet.derthDenom(v1), 7)
        chain.openProposal(1)
        val e = assertThrows(PrivacyWallet.SyncFirst::class.java) { a.stakeVote(1, v1, yes) }
        assertTrue(e.message!!.contains("sync first"))
        a.sync()
        a.stakeVote(1, v1, yes)
    }

    /**
     * A hostile indexer forges the snapshot's nf_root (adding k's
     * post-snapshot nullifier), which would classify k as spent before the
     * snapshot and silently skip the vote. The snapshot comes from the LCD:
     * k votes.
     */
    @Test
    fun voteForgedIndexerSnapshotDoesNotSuppressTheVote() {
        val v = voting()
        val chain = v.chain
        val forged = object : Wrapped(chain) {
            override fun stakeNullifierLeaves(fromIndex: Long, limit: Int?): StakeNfLeavesPage {
                val p = inner.stakeNullifierLeaves(fromIndex, limit)
                return p.copy(leaves = p.leaves + ((p.leaves.lastOrNull()?.first ?: 0L) + 1 to v.k.nf), size = p.size + 1)
            }
            override fun stakeSnapshots(fromHeight: Long, limit: Int?): StakeSnapshotsPage = inner.stakeSnapshots(fromHeight, limit).let { p ->
                p.copy(rows = p.rows.map { r ->
                    val values = chain.stakeNfValues.take((r.nfSize - 1).toInt()) + v.k.nf
                    StakeSnapshotRow(r.height, r.proposalId, r.root, r.treeSize, IndexedTree.build(values).root(), r.nfSize + 1)
                })
            }
        }
        val a = wallet(chain, indexer = forged)
        a.sync()
        assertTrue(a.stakeVoteItems(1).contains(PrivacyWallet.StakeVoteItem.Validator(vB, 2)))
        assertNotNull(a.castStakeVote(1, PrivacyWallet.StakeVoteItem.Validator(vB, 2), yes))
        assertEquals(1, chain.stakeVotes.size)
    }

    /**
     * When the snapshot's nullifier tree holds the note's nullifier but sync
     * saw no spend before the snapshot, the two disagree: an error, never a
     * vote silently skipped.
     */
    @Test
    fun spentBeforeSnapshotDisagreeingWithSyncIsAnError() {
        val v = voting()
        val chain = v.chain
        val real = chain.snapshotRead(1)
        val values = chain.stakeNfValues.take((real.nfSize - 1).toInt()) + v.k.nf
        val forgedSnap = real.copy(nfRoot = IndexedTree.build(values).root(), nfSize = real.nfSize + 1)
        val a = wallet(chain, r = reads(chain, snapshot = { forgedSnap }, nfTree = { start, limit ->
            PrivacyChainReads.NfTreePage(values.drop(start.toInt()).take(limit), values.size + 1L)
        }))
        a.sync()
        chain.indexerNfTree = false
        val sims = chain.simulated
        val e = assertThrows(IllegalStateException::class.java) { a.castStakeVote(1, PrivacyWallet.StakeVoteItem.Validator(vB, 2), yes) }
        assertTrue(e.message!!, "sync saw no spend" in e.message!!)
        assertEquals(sims, chain.simulated)
    }

    /** A note spent in the snapshot's own block is spent before it: not eligible. */
    @Test
    fun noteSpentInTheSnapshotBlockIsNotEligible() {
        val v = voting()
        val a = v.a
        val snap = a.snapshot(1)
        val i = a.store.state.stakeNotes.indexOfFirst { it.position == v.k.position }
        a.store.state.stakeNotes[i] = a.store.state.stakeNotes[i].copy(spentHeight = snap.height)
        assertEquals(listOf(PrivacyWallet.StakeVoteItem.Validator(vB, 1)), a.stakeVoteItems(1))
        a.store.state.stakeNotes[i] = a.store.state.stakeNotes[i].copy(spentHeight = snap.height + 1)
        assertEquals(listOf(PrivacyWallet.StakeVoteItem.Validator(vB, 2)), a.stakeVoteItems(1))
    }

    /** The snapshot's nf_size is the LCD's: an indexer's larger count is never fetched. */
    @Test
    fun nullifierFetchIsBoundedByTheLcdSize() {
        val v = voting()
        val chain = v.chain
        val asked = ArrayList<Pair<Long, Int?>>()
        val idx = object : Wrapped(chain) {
            override fun stakeNullifierLeaves(fromIndex: Long, limit: Int?): StakeNfLeavesPage { asked.add(fromIndex to limit); return inner.stakeNullifierLeaves(fromIndex, limit) }
            override fun stakeSnapshots(fromHeight: Long, limit: Int?) = inner.stakeSnapshots(fromHeight, limit).let { p ->
                p.copy(rows = p.rows.map { it.copy(nfSize = 1_000_000_000L) })
            }
        }
        val a = wallet(chain, indexer = idx)
        a.sync()
        assertNotNull(a.castStakeVote(1, PrivacyWallet.StakeVoteItem.Validator(vB, 2), yes))
        // The LCD's nf_size (a handful): one aligned page, nothing past it.
        assertEquals(listOf(0L to PrivacyWallet.NF_PAGE), asked)
    }

    /** The stake votes cast survive a same-chain reset (an inconsistent sync). */
    @Test
    fun stakeVotesSurviveAReset() {
        val v = voting()
        val a = v.a
        a.castStakeVote(1, PrivacyWallet.StakeVoteItem.Validator(vB, 2), yes)
        val votes = a.store.state.stakeVotes.toList()
        // One vote, two notes: each note's vote nullifier is remembered.
        assertEquals(2, votes.size)
        a.store.reset(v.chain.chainId)
        assertEquals(votes, a.store.state.stakeVotes)
        a.sync()
        assertNull(a.castStakeVote(1, PrivacyWallet.StakeVoteItem.Validator(vB, 2), yes))
        assertEquals(1, v.chain.stakeVotes.size)
    }

    /** The stake-vote record a refusal leaves behind is forgotten: the note may vote again. */
    @Test
    fun refusedVoteIsForgotten() {
        val v = voting()
        v.chain.rejectNext = 1
        assertThrows(UnsignedTx.TxRejected::class.java) { v.a.castStakeVote(1, PrivacyWallet.StakeVoteItem.Validator(vB, 2), yes) }
        assertTrue(v.a.store.state.stakeVotes.isEmpty())
        assertNotNull(v.a.castStakeVote(1, PrivacyWallet.StakeVoteItem.Validator(vB, 2), yes))
        assertEquals(2, v.a.store.state.stakeVotes.size)
    }

    /** 1119 counts as "already voted" only in x/shieldedstaking's codespace. */
    @Test
    fun alreadyVotedNeedsTheCodespace() {
        assertTrue(PrivacyWallet.alreadyVotedError(UnsignedTx.TxRejected(1119, "x", "shieldedstaking")))
        assertFalse(PrivacyWallet.alreadyVotedError(UnsignedTx.TxRejected(1119, "x", "wasm")))
        assertFalse(PrivacyWallet.alreadyVotedError(java.io.IOException("tx rejected (code 1119): something else")))
        assertTrue(PrivacyWallet.alreadyVotedError(java.io.IOException("simulate failed (400): this stake note already voted on this proposal")))
    }
}
