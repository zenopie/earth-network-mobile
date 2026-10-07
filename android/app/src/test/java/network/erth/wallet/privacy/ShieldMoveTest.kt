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

    /** ERTH and ANML always and first, the rest by denom; stake is a position, not a coin. */
    @Test
    fun coinsListEveryHeldDenomOnce() {
        val coins = ShieldMove.coins(
            mapOf("uerth" to 5L, "uusdc" to 7L, "uatom" to 0L, "dexlp/2" to 3L),
            mapOf("uerth" to 9L, "uanml" to 4L, "derth/earthvaloper1x" to 8L, "uusdc" to 1L),
        )
        assertEquals(listOf("uerth", "uanml", "dexlp/2", "uusdc"), coins.map { it.denom })
        assertEquals(ShieldMove.Coin("uerth", 5, 9), coins[0])
        assertEquals(ShieldMove.Coin("uusdc", 7, 1), coins[3])
        assertEquals(listOf("uerth", "uanml"), ShieldMove.coins(emptyMap(), emptyMap()).map { it.denom })
    }

    @Test
    fun onlyErthMovesAndOnlyWhatCoversItsFee() {
        assertEquals(null, ShieldMove.shieldBlocked("uerth", 11, 10))
        assertEquals("No public ERTH to shield.", ShieldMove.shieldBlocked("uerth", 0, 10))
        assertEquals("Public ERTH doesn't cover the shield fee.", ShieldMove.shieldBlocked("uerth", 10, 10))
        assertEquals("ANML is always private.", ShieldMove.shieldBlocked("uanml", 5, 10))
        assertEquals("Only ERTH moves between public and private.", ShieldMove.shieldBlocked("uusdc", 5, 10))
        assertEquals(null, ShieldMove.unshieldBlocked("uerth", 11, 11, 10))
        assertEquals("No private ERTH to unshield.", ShieldMove.unshieldBlocked("uerth", 0, 0, 10))
        assertEquals("Private ERTH doesn't cover the unshield fee.", ShieldMove.unshieldBlocked("uerth", 10, 10, 10))
        assertEquals("ANML can't be made public.", ShieldMove.unshieldBlocked("uanml", 5, 5, 1))
        assertEquals("Only ERTH moves between public and private.", ShieldMove.unshieldBlocked("uusdc", 5, 5, 1))
    }
}
