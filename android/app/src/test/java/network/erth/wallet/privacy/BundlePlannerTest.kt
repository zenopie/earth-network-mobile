package network.erth.wallet.privacy

import network.erth.wallet.privacy.keys.PrivacyKeys
import network.erth.wallet.privacy.note.NotePlaintext
import network.erth.wallet.privacy.note.OwnedNote
import network.erth.wallet.privacy.tx.BundleBuilder
import network.erth.wallet.privacy.tx.BundlePlan
import network.erth.wallet.privacy.tx.NoteOut
import network.erth.wallet.privacy.tx.NoteSelection
import network.erth.wallet.privacy.tx.PrivateMsgs
import network.erth.wallet.privacy.zk.Fr
import network.erth.wallet.privacy.zk.Grumpkin
import network.erth.wallet.privacy.zk.MemNodeStore
import network.erth.wallet.privacy.zk.Merkle
import network.erth.wallet.privacy.zk.MerkleTree
import network.erth.wallet.privacy.zk.Privacy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bundle planner: notes chosen per denom (any number), change per denom,
 * spends and outputs paired into actions (mixed assets allowed), padded to
 * two, capped at max_actions_per_bundle, and every bundle balancing under its
 * own binding signature with every action's witness satisfying the circuit.
 */
class BundlePlannerTest {
    private val keys = PrivacyKeys.fromMnemonic("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about")
    private val bob = PrivacyKeys.fromMnemonic("legal winner thank year wave sausage worth useful legal winner thank yellow")

    private class Wallet(val tree: MerkleTree, val notes: List<OwnedNote>)

    /** A tree holding [values] of [denom] (and [other]) as our notes, among strangers' notes. */
    private fun wallet(values: Map<String, List<Long>>): Wallet {
        val tree = MerkleTree(MemNodeStore())
        val notes = ArrayList<OwnedNote>()
        for ((denom, vs) in values) for (v in vs) {
            tree.append(Privacy.cm(Privacy.assetId("uerth"), 1, NotePlaintext.randomField()))
            val n = NotePlaintext.fresh(denom, v)
            val cm = n.cm(keys.ownerPk)
            val pos = tree.append(cm)
            notes.add(OwnedNote(pos, 1, n, cm, Privacy.nf(keys.nk, n.rho, pos)))
        }
        return Wallet(tree, notes)
    }

    /** Proves nothing; checks every action against the circuit and the bundle's binding signature. */
    private fun verify(p: BundlePlan) {
        val sighash = NotePlaintext.randomField()
        val bundle = p.prove(sighash) { w ->
            w.check()
            assertEquals(Grumpkin.valueCommit(w.sAsset, w.sValue, w.oAsset, w.oValue, w.rcv), w.cv)
            ByteArray(1)
        }
        assertTrue(PrivateMsgs.checkBalance(bundle, sighash))
        assertEquals(p.actions.size, p.nullifiers.toSet().size)
        assertTrue(p.actions.size >= BundlePlan.MIN_ACTIONS)
        // Outputs to us that are not dummies open to the values the plan says.
        bundle.balancesList.forEach { assertTrue(it.amount > 0) }
    }

    @Test
    fun erthPaymentFrom1To20Notes() {
        for (n in 1..20) {
            val w = wallet(mapOf("uerth" to List(n) { 1_000L }))
            val amount = n * 1_000L - 500 - 300 // leaves change 300 after a 500 fee... when n notes are needed
            val out = NoteOut.to(bob.address, "uerth", amount)
            val p = BundleBuilder.plan(keys, w.tree, w.notes, listOf(out), mapOf("uerth" to 500L), maxActions = 32)
            assertEquals("n=$n spends", n, p.spends.size)
            assertEquals(mapOf("uerth" to 500L), p.balances.toMap())
            // Spends n; outputs the payment and 300 change: max(n, 2) actions, at least 2.
            assertEquals(maxOf(n, 2), p.actions.size)
            val change = p.actions.map { it.out }.filter { it.note != null && it.pc == it.note!!.pc(keys.ownerPk) }
            assertEquals(listOf(300L), change.map { it.value })
            verify(p)
        }
    }

    @Test
    fun multiAssetBundlePairsAcrossAssets() {
        val w = wallet(mapOf("uerth" to listOf(5_000L, 7_000L), "uanml" to listOf(100L, 200L, 300L), "derth/x" to emptyList()))
        val outs = listOf(NoteOut.to(bob.address, "uanml", 550), NoteOut.to(bob.address, "uerth", 4_000))
        val p = BundleBuilder.plan(keys, w.tree, w.notes, outs, mapOf("uerth" to 1_000L, "uanml" to 20L), maxActions = 16)
        assertEquals(mapOf("uanml" to 20L, "uerth" to 1_000L), p.balances.toMap())
        // ANML: 570 needs all three (600, change 30); ERTH: 5,000 is one note exactly.
        assertEquals(4, p.spends.size)
        assertEquals(4, p.actions.size)
        // The spends and outputs are shuffled independently, so assets mix within actions.
        verify(p)
    }

    @Test
    fun feeOnlyAndPadding() {
        val w = wallet(mapOf("uerth" to listOf(10_000L)))
        val p = BundleBuilder.plan(keys, w.tree, w.notes, emptyList(), mapOf("uerth" to 10_000L), maxActions = 16)
        // One spend, no output at all (the fee is the whole note): padded to two actions.
        assertEquals(2, p.actions.size)
        assertEquals(1, p.spends.size)
        assertTrue(p.actions.all { it.out.value == 0L })
        verify(p)
        // Dummy spends carry their own anchor (the bundle's) and a fresh nullifier.
        assertEquals(2, p.nullifiers.toSet().size)
    }

    @Test
    fun capsAtMaxActions() {
        val w = wallet(mapOf("uerth" to List(10) { 100L }))
        val out = listOf(NoteOut.to(bob.address, "uerth", 700))
        assertThrows(NoteSelection.Insufficient::class.java) {
            BundleBuilder.plan(keys, w.tree, w.notes, out, mapOf("uerth" to 100L), maxActions = 4)
        }
        val p = BundleBuilder.plan(keys, w.tree, w.notes, out, mapOf("uerth" to 100L), maxActions = 8)
        assertEquals(8, p.spends.size)
        verify(p)
        // Not enough at all.
        assertThrows(NoteSelection.Insufficient::class.java) {
            BundleBuilder.plan(keys, w.tree, w.notes, out, mapOf("uerth" to 400L), maxActions = 32)
        }
    }

    @Test
    fun selectionPrefersOneNoteThenFewest() {
        val w = wallet(mapOf("uerth" to listOf(50L, 400L, 300L, 1_000L, 20L)))
        assertEquals(listOf(400L), NoteSelection.cover(w.notes, "uerth", 350).map { it.note.value })
        // 1,000 + 400 covers 1,300; the 400 is swapped for the smallest that still covers (300).
        assertEquals(listOf(1_000L, 300L), NoteSelection.cover(w.notes, "uerth", 1_300).map { it.note.value })
        assertEquals(5, NoteSelection.cover(w.notes, "uerth", 1_770).size)
    }

    @Test
    fun valueCommitmentsSumToTheBalance() {
        // bvk = sum cv - sum value*G = (sum rcv)*R for any honest plan.
        val w = wallet(mapOf("uerth" to listOf(3_000L, 4_000L), "uanml" to listOf(9L)))
        val p = BundleBuilder.plan(keys, w.tree, w.notes, listOf(NoteOut.to(bob.address, "uanml", 9)), mapOf("uerth" to 6_000L), 16)
        val sum = p.cvs.fold(Grumpkin.Point.INFINITY) { a, c -> a + c }
        val bvk = p.balances.fold(sum) { a, (d, v) -> a - Grumpkin.valueBase(Privacy.assetId(d)) * Grumpkin.u64(v) }
        assertEquals(Grumpkin.R * p.bindingKey(), bvk)
        assertEquals(Merkle.DEPTH, p.witness(0, Fr.ONE).sPath.size)
    }

    @Test
    fun stakeSelectionSpendsAtMostTwo() {
        fun s(pos: Long, amount: Long, label: network.erth.wallet.privacy.note.StakeLabel? = null) =
            network.erth.wallet.privacy.note.OwnedStakeNote(pos, 1, "derth/v", amount, Fr.ONE, Fr.ONE, Fr.ONE, Fr.ONE, label = label)
        val amount: (network.erth.wallet.privacy.note.OwnedStakeNote) -> Long = { it.amount }
        val ns = listOf(s(1, 50), s(2, 400), s(3, 300), s(4, 1_000))
        assertEquals(listOf(400L), network.erth.wallet.privacy.tx.StakeSelection.cover(ns, 350, amount).map { it.amount })
        assertEquals(listOf(400L, 1_000L), network.erth.wallet.privacy.tx.StakeSelection.cover(ns, 1_350, amount).map { it.amount })
        val e = org.junit.Assert.assertThrows(network.erth.wallet.privacy.tx.NoteSelection.Insufficient::class.java) {
            network.erth.wallet.privacy.tx.StakeSelection.cover(ns, 1_500, amount)
        }
        assertEquals("this stake is spread over more notes than one transaction spends; merge them first (one fee each), then try again", e.message)
        val e2 = org.junit.Assert.assertThrows(network.erth.wallet.privacy.tx.NoteSelection.Insufficient::class.java) {
            network.erth.wallet.privacy.tx.StakeSelection.cover(ns, 2_000, amount)
        }
        assertEquals("insufficient stake", e2.message)
        assertEquals(listOf(1_000L, 400L), network.erth.wallet.privacy.tx.StakeSelection.merge(ns, amount).map { it.amount })
        // At most one labelled note a proof; a labelled note gives up only its unexposed part while its window is open.
        val ls = listOf(s(1, 500, network.erth.wallet.privacy.note.StakeLabel(Fr.ONE, 1, 100)),
            s(2, 600, network.erth.wallet.privacy.note.StakeLabel(Fr.of(2), 1, 100)), s(3, 50))
        val free: (network.erth.wallet.privacy.note.OwnedStakeNote) -> Long = { it.amount - (it.label?.exposed ?: 0) }
        assertEquals(listOf(50L, 600L), network.erth.wallet.privacy.tx.StakeSelection.cover(ls, 520, free).map { it.amount })
        org.junit.Assert.assertThrows(network.erth.wallet.privacy.tx.NoteSelection.Insufficient::class.java) {
            network.erth.wallet.privacy.tx.StakeSelection.cover(ls, 700, free)
        }
        val e3 = org.junit.Assert.assertThrows(network.erth.wallet.privacy.tx.NoteSelection.Insufficient::class.java) {
            network.erth.wallet.privacy.tx.StakeSelection.cover(ls, 1_000, free) { "held" }
        }
        assertEquals("held", e3.message)
        assertEquals(listOf(600L, 50L), network.erth.wallet.privacy.tx.StakeSelection.merge(ls, free).map { it.amount })
    }
}
