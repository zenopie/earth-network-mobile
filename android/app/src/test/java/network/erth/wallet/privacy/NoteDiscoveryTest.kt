package network.erth.wallet.privacy

import network.erth.wallet.privacy.zk.Fr
import network.erth.wallet.privacy.zk.Privacy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Notes the chain mints without a ciphertext of ours: open notes, and split payouts sharing one ciphertext. */
class NoteDiscoveryTest : WalletTest() {
    @Test
    fun openNoteIsOursOnlyByOwnerPkAndCm() {
        val chain = FakeChain()
        val a = wallet(chain, alice)
        val b = wallet(chain, bob)
        val (rho, rcm) = Privacy.referralOpening(Fr.of(4242), 3)
        val pos = chain.mintOpen("uerth", 5_000_000, a.keys.ownerPk, rho, rcm)
        chain.emptyBlock()
        // A row naming alice's owner_pk but a cm of another amount is not a note of hers.
        val cmOther = Privacy.cm(Privacy.assetId("uerth"), 6_000_000, Privacy.pc(a.keys.ownerPk, rho, rcm))
        val lie = chain.noteTree.append(cmOther)
        chain.notes.add(network.erth.wallet.privacy.sync.NoteRow(lie, chain.height, cmOther, ByteArray(0), "5000000uerth", a.keys.ownerPk, rho, rcm))
        chain.emptyBlock()
        a.sync(); b.sync()
        assertEquals(listOf(pos), a.notes.map { it.position })
        assertEquals(5_000_000L, bal(a))
        assertTrue(b.notes.isEmpty())
        // Spendable: alice sends from it.
        a.send(b.address, "uerth", 1_000_000)
        b.sync()
        assertEquals(1_000_000L, bal(b))
        dumpWitnesses(chain, "fix6OpenNote")
    }

    @Test
    fun splitPayoutOneCiphertextAtSeveralPositions() {
        val chain = FakeChain()
        val a = wallet(chain, alice)
        // MintNoteSplit: one pc and one ciphertext, several notes, each its own amount and position
        // (the test's chunks are ones this wallet holds; the chain cuts at 2^64-1).
        val out = a.withdrawalNote()
        val values = listOf(3_000_000L, 3_000_000L, 1_234L)
        val ps = chain.mintSplit("uanml", values, out.pc, out.ciphertext)
        chain.emptyBlock()
        a.sync()
        val got = a.notes.filter { it.position in ps }.sortedBy { it.position }
        assertEquals(values, got.map { it.note.value })
        // One rho and rcm, a nullifier per position: every chunk spends on its own.
        assertEquals(1, got.map { it.note.rho to it.note.rcm }.toSet().size)
        assertEquals(3, got.map { it.nf }.toSet().size)
        got.forEach { assertEquals(Privacy.nf(a.keys.nk, it.note.rho, it.position), it.nf) }
        assertEquals(6_001_234L, bal(a, "uanml"))
        // Two chunks of the same value are still two notes.
        assertEquals(got[0].note, got[1].note)
        assertTrue(got[0] != got[1])
    }
}
