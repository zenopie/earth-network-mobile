package network.erth.wallet.ui.wallet

import network.erth.wallet.chain.Explorer
import network.erth.wallet.privacy.ActivityCoin
import network.erth.wallet.privacy.PrivateActivity
import network.erth.wallet.privacy.PrivateActivityKind
import network.erth.wallet.privacy.PrivateActivityRow
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** The activity list's rows and the detail sheet's lines, from both sources. As iOS's ActivityEntryTests. */
class ActivityEntryTest {
    private val me = "earth1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqme0000"
    private val utc = TimeZone.getTimeZone("UTC")
    private val hash = "AB12CD34EF56AB12CD34EF56AB12CD34EF56AB12CD34EF56AB12CD34EF56AB12"

    private fun row(
        kind: PrivateActivityKind, coins: List<ActivityCoin>, party: String = "", fee: Long? = 2_500, hash: String? = this.hash,
        status: PrivateActivityRow.Status = PrivateActivityRow.Status.CONFIRMED, failure: String? = null, time: Long? = 1_791_400_000,
        exact: Boolean = true, grant: Boolean = false,
    ) = ActivityEntry.of(PrivateActivityRow(kind, coins, party, fee, hash, 120_345, time, exact, status, failure, grant))

    @Test
    fun privateSwapShowsWhatCameInAndBothLegsInDetail() {
        val e = row(PrivateActivityKind.SWAP, listOf(ActivityCoin("uerth", -12_500_000), ActivityCoin("uanml", 3_250_000)))
        assertEquals("Swapped", e.title)
        assertEquals(ActivityCoin("uanml", 3_250_000), e.primary)
        assertTrue(e.showsCoin)
        val d = e.details(zone = utc)
        assertEquals(listOf("Type", "Date", "You paid", "You got", "Network fee", "Fee paid by", "Block", "Privacy", "Transaction"), d.map { it.label })
        assertEquals("Swap", d[0].value)
        assertEquals("−12.5 ERTH", d[2].value)
        assertEquals("+3.25 ANML", d[3].value)
        assertEquals("0.0025 ERTH", d[4].value)
        assertEquals("You (private ERTH)", d[5].value)
        assertEquals("120,345", d[6].value)
        assertEquals("Private", d[7].value)
        assertEquals("AB12CD…56AB12", d[8].value)
        assertEquals(hash, d[8].copy)
    }

    @Test
    fun sendNamesTheHandleAndStakeTheValidator() {
        val send = row(PrivateActivityKind.SEND, listOf(ActivityCoin("uanml", -700_000)), party = "@bob")
        assertEquals("Sent", send.title)
        assertEquals(ActivityCoin("uanml", -700_000), send.primary)
        assertEquals("@bob", send.listParty { it })
        assertEquals(ActivityEntry.Detail("To", "@bob"), send.details().first { it.label == "To" })

        val stake = row(PrivateActivityKind.STAKE, listOf(ActivityCoin("uerth", -5_000_000), ActivityCoin("derth/earthvaloper1abc", 4_900_000)),
            party = "earthvaloper1abc")
        assertEquals(ActivityCoin("uerth", -5_000_000), stake.primary)
        assertFalse(stake.showsCoin)
        assertEquals("Moss", stake.listParty { "Moss" })
        assertEquals(ActivityEntry.Detail("Validator", "Moss", "earthvaloper1abc"), stake.details({ "Moss" }).first { it.label == "Validator" })

        val vote = row(PrivateActivityKind.VOTE, emptyList(), party = "proposal 4")
        assertNull(vote.primary)
        assertEquals("Proposal 4", vote.listParty { it })
    }

    @Test
    fun receivedNotesAndRegistration() {
        val gas = row(PrivateActivityKind.GAS_GRANT, listOf(ActivityCoin("uerth", 100_000)), fee = null, hash = null, exact = false)
        assertEquals("Gas from Earth", gas.title)
        assertNull(gas.listParty { it })
        val d = gas.details(zone = utc)
        assertFalse(d.any { it.label in setOf("Transaction", "Network fee", "Fee paid by") })
        assertTrue(ActivityEntry.Detail("From", "Earth") in d)
        assertTrue(d.first { it.label == "Date" }.value.startsWith("About "))

        val reg = row(PrivateActivityKind.REGISTER, listOf(ActivityCoin("uanml", 1_000_000), ActivityCoin("uerth", 10_000)), grant = true)
        assertEquals("Registered", reg.title)
        assertEquals(ActivityCoin("uanml", 1_000_000), reg.primary)
        assertFalse(reg.showsCoin)
        assertEquals("Earth (gas grant)", reg.feePayer)
    }

    @Test
    fun pendingAndFailed() {
        val p = row(PrivateActivityKind.SEND, listOf(ActivityCoin("uerth", -1)), status = PrivateActivityRow.Status.PENDING)
        assertEquals(ActivityEntry.Status.PENDING, p.status)
        assertNull(p.shortFailure)
        val f = row(PrivateActivityKind.SEND, listOf(ActivityCoin("uerth", -1)), status = PrivateActivityRow.Status.FAILED,
            failure = "tx failed (code 11): out of gas in location: ReadFlat")
        assertEquals("Out of gas", f.shortFailure)
        assertTrue(f.details().any { it.label == "Error" })
        assertEquals("Expired before a block", ActivityEntry.shortReason(PrivateActivity.NEVER_LANDED))
        assertEquals("Refused by the chain", ActivityEntry.shortReason(PrivateActivity.FAILED_IN_BLOCK))
    }

    @Test
    fun publicTxCarriesFeeGasAndExplorerFacts() {
        val msg = JSONObject().put("from_address", me).put("to_address", "earth1zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz9xy")
            .put("amount", JSONArray().put(JSONObject().put("denom", "uerth").put("amount", "1500000")))
        val tx = Explorer.Tx("A".repeat(64), 9_001, true, "2026-10-07T16:41:00.123Z", listOf("MsgSend"), listOf(msg),
            fee = listOf(ActivityCoin("uerth", 5_000)), gasUsed = 84_123, gasWanted = 200_000, memo = "rent")
        val e = ActivityEntry.of(tx, me)
        assertEquals("Sent", e.title)
        assertFalse(e.isPrivate)
        assertEquals(ActivityCoin("uerth", -1_500_000), e.primary)
        assertEquals("earth1zzzz…z9xy", e.listParty { it })
        val d = e.details(zone = utc)
        assertEquals(listOf("Type", "Date", "Sent", "To", "Network fee", "Fee paid by", "Gas used", "Block", "Privacy", "Memo", "Transaction"), d.map { it.label })
        assertEquals("Oct 7, 2026 at 4:41 PM", d[1].value)
        assertEquals("You", d[5].value)
        assertEquals("84,123 of 200,000", d[6].value)
        assertEquals("Public", d[8].value)
        assertEquals("earth1zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz9xy", d[3].copy)
        assertEquals("Fee grant from earth1gran…ant7", ActivityEntry.of(tx.copy(feeGranter = "earth1grantergrantergrantergrantergrant7"), me).feePayer)

        val failed = ActivityEntry.of(tx.copy(success = false, code = 5, rawLog = "insufficient funds"), me)
        assertEquals(ActivityEntry.Status.FAILED, failed.status)
        assertEquals("Not enough funds", failed.shortFailure)
    }

    @Test
    fun daysAndClock() {
        val now = 1_791_400_000L // 2026-10-07 19:06:40 UTC
        assertEquals("Today", ActivityEntry.dayTitle(now - 3_600, now, utc))
        assertEquals("Yesterday", ActivityEntry.dayTitle(now - 86_400, now, utc))
        assertEquals("Oct 5", ActivityEntry.dayTitle(now - 2 * 86_400, now, utc))
        assertEquals("Oct 7, 2025", ActivityEntry.dayTitle(now - 365 * 86_400, now, utc))
        assertEquals("Earlier", ActivityEntry.dayTitle(null, now, utc))
        val a = row(PrivateActivityKind.SEND, emptyList(), time = now - 60)
        val b = row(PrivateActivityKind.GAS_GRANT, emptyList(), hash = null, time = now - 86_400)
        val c = row(PrivateActivityKind.SWAP, emptyList(), hash = "C", time = now - 120)
        val days = ActivityEntry.days(listOf(a, c, b), now, utc)
        assertEquals(listOf("Today", "Yesterday"), days.map { it.first })
        assertEquals(2, days[0].second.size)
        assertEquals("~7:05 PM", ActivityEntry.clock(now - 60, false, utc))
    }

    @Test
    fun figures() {
        assertEquals("1,250.5", ActivityEntry.figure(1_250_500_000))
        assertEquals("0.000001", ActivityEntry.figure(-1))
        assertEquals("12", ActivityEntry.figure(12_000_000))
        assertEquals("+2 ANML", ActivityEntry.coinText(ActivityCoin("uanml", 2_000_000)))
        assertEquals("−4.9 dERTH", ActivityEntry.coinText(ActivityCoin("derth/earthvaloper1abc", -4_900_000)))
    }

    @Test
    fun publicShieldIsWhatLeftTheAccount() {
        val e = ActivityEntry.of(Explorer.Tx("B".repeat(64), 1, true, "2026-10-07T10:00:00Z", listOf("MsgShield"),
            listOf(JSONObject().put("sender", me).put("amount", JSONObject().put("denom", "uerth").put("amount", "10")))), me)
        assertEquals("Shielded", e.title)
        assertEquals(ActivityCoin("uerth", -10), e.primary)
    }
}
