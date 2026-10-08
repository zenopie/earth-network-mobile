package network.erth.wallet.ui.wallet

import network.erth.wallet.chain.Explorer
import network.erth.wallet.privacy.ActivityCoin
import network.erth.wallet.privacy.PrivateActivityKind
import network.erth.wallet.privacy.PrivateActivityRow
import org.junit.Assert.assertEquals
import org.junit.Test
import org.json.JSONObject

/** Private and public rows in one list, newest first; a tx in both shown once, as its private row. */
class ActivityMergeTest {
    private val now = 1_800_000_000L

    private fun private(kind: PrivateActivityKind, time: Long, exact: Boolean = true, hash: String? = null) =
        ActivityEntry.of(PrivateActivityRow(kind, listOf(ActivityCoin("uerth", -1_500_000)), "", 10_000, hash, 42, time, exact,
            PrivateActivityRow.Status.CONFIRMED))

    private fun public(hash: String, time: String) = ActivityEntry.of(
        Explorer.Tx(hash, 7, true, time, listOf("MsgSend"), listOf(JSONObject().put("from_address", "me").put("to_address", "you"))), "me")

    @Test
    fun oneListNewestFirst() {
        val pub = listOf(public("P1", "2027-01-15T08:00:00Z"), public("P2", "2027-01-14T08:00:00Z"))
        val priv = listOf(private(PrivateActivityKind.CLAIM_ANML, 1_800_000_100), private(PrivateActivityKind.RECEIVED, 1_768_400_000, exact = false))
        val merged = mergeActivity(pub, priv)
        assertEquals(listOf("Claimed ANML", "Sent", "Sent", "Received"), merged.map { it.title })
        assertEquals(listOf(true, false, false, true), merged.map { it.isPrivate })
    }

    @Test
    fun aTxInBothIsItsPrivateRow() {
        val h = "B".repeat(64)
        val merged = mergeActivity(listOf(public(h, "2027-01-15T08:00:00Z")), listOf(private(PrivateActivityKind.SHIELD, now, hash = h.lowercase())))
        assertEquals(1, merged.size)
        assertEquals(true, merged[0].isPrivate)
    }
}
