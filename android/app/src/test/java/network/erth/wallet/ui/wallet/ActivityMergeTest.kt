package network.erth.wallet.ui.wallet

import network.erth.wallet.privacy.ActivityCoin
import network.erth.wallet.privacy.PrivateActivityKind
import network.erth.wallet.privacy.PrivateActivityRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Private rows in the list's terms, and one list with the public rows, newest first. */
class ActivityMergeTest {
    private val now = 1_800_000_000L

    private fun private(kind: PrivateActivityKind, time: Long, status: PrivateActivityRow.Status = PrivateActivityRow.Status.CONFIRMED, exact: Boolean = true, hash: String? = null) =
        PrivateActivityRow(kind, listOf(ActivityCoin("uerth", -1_500_000), ActivityCoin("uanml", 2_000_000)), "", if (hash != null) 10_000 else null,
            hash, 42, time, exact, status)

    @Test
    fun privateRowsReadAsPrivate() {
        val sent = private(PrivateActivityKind.SEND, now - 120, PrivateActivityRow.Status.PENDING, hash = "AB".repeat(32)).toActivityRow(now)
        assertTrue(sent.isPrivate)
        assertTrue(sent.pending)
        assertEquals("Sent privately", sent.label())
        assertEquals("-1.5 ERTH, +2 ANML", sent.amount)
        assertEquals("pending · 2m ago", sent.timestamp)
        assertEquals("0.01 ERTH", sent.fee)
        val got = private(PrivateActivityKind.GAS_GRANT, now - 7_200, exact = false).toActivityRow(now)
        assertEquals("~2h ago", got.timestamp)
        assertEquals("block 42", got.counterparty)
        assertEquals(ActivityKind.Received, got.kind)
        assertEquals(null, got.hash)
        val failed = private(PrivateActivityKind.SWAP, now, PrivateActivityRow.Status.FAILED).toActivityRow(now)
        assertTrue(failed.failed)
    }

    @Test
    fun oneListNewestFirst() {
        val pub = listOf(
            ActivityRow("P1", ActivityKind.Sent, "", "", "", sortTime = now - 100, hash = "P1"),
            ActivityRow("P2", ActivityKind.Sent, "", "", "", sortTime = now - 10_000, hash = "P2"),
        )
        val priv = listOf(
            private(PrivateActivityKind.CLAIM_ANML, now - 50).toActivityRow(now),
            private(PrivateActivityKind.RECEIVED, now - 5_000, exact = false).toActivityRow(now),
        )
        val merged = mergeActivity(pub, priv)
        assertEquals(listOf("Claimed ANML", "Sent", "Received privately", "Sent"), merged.map { it.label() })
        assertEquals(listOf(true, false, true, false), merged.map { it.isPrivate })
        assertFalse(merged.first().failed)
    }
}
