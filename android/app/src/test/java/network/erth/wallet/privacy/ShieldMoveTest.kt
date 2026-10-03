package network.erth.wallet.privacy

import network.erth.wallet.privacy.note.NotePlaintext
import network.erth.wallet.privacy.note.OwnedNote
import network.erth.wallet.privacy.tx.NoteSelection
import network.erth.wallet.privacy.tx.ShieldMove
import network.erth.wallet.privacy.zk.Fr
import org.junit.Assert.assertEquals
import org.junit.Test

/** The wallet home's Shield / Unshield limits. */
class ShieldMoveTest {
    private fun note(value: Long, pos: Long, denom: String = "uerth", spent: Boolean = false, pending: Boolean = false) =
        OwnedNote(pos, 1, NotePlaintext(denom, value, Fr.ONE, Fr.ONE), Fr.ONE, Fr.ONE,
            spentHeight = if (spent) 2 else null, pendingAt = if (pending) 1 else null)

    @Test
    fun maxSpendableTakesTheLargestSpendableNotes() {
        val notes = listOf(note(5, 1), note(40, 2), note(30, 3), note(20, 4), note(99, 5, spent = true),
            note(98, 6, pending = true), note(97, 7, denom = "uanml"))
        assertEquals(90L, NoteSelection.maxSpendable(notes, "uerth", 3))
        assertEquals(95L, NoteSelection.maxSpendable(notes, "uerth", 16))
        assertEquals(70L, NoteSelection.maxSpendable(notes, "uerth", 2))
        assertEquals(97L, NoteSelection.maxSpendable(notes, "uanml", 16))
        assertEquals(0L, NoteSelection.maxSpendable(emptyList(), "uerth", 16))
    }

    /** Max is every note one bundle carries; at Max the fee comes out of the amount. */
    @Test
    fun maxUnshieldIsEveryNoteABundleCarries() {
        val ns = (1..20).map { note(10, it.toLong()) }
        assertEquals(160L, ShieldMove.maxUnshield(ns, 16))
        assertEquals(200L, ShieldMove.maxUnshield(ns, 32))
        assertEquals(0L, ShieldMove.maxUnshield(emptyList(), 16))
        assertEquals(true, ShieldMove.feeFromAmount(160, 160, 3))
        assertEquals(false, ShieldMove.feeFromAmount(150, 160, 3))
    }

    @Test
    fun maxShieldLeavesTheFee() {
        assertEquals(90L, ShieldMove.maxShield(100, 10))
        assertEquals(0L, ShieldMove.maxShield(5, 10))
    }
}
