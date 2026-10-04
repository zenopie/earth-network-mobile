package network.erth.wallet.privacy

import network.erth.wallet.privacy.chain.ValidatorPages
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.math.BigInteger

/** Query/Validators: one entry's fields, and every page read at one height. */
class ValidatorPagesTest {
    private val page = """
        {"validators":[
          {"validator":"earthvaloper1a","staking":{"operator_address":"earthvaloper1a","jailed":true,"status":"BOND_STATUS_UNBONDING",
            "tokens":"5000","description":{"moniker":"Alpha"},"commission":{"commission_rates":{"rate":"0.100000000000000000"}}},
           "tombstoned":true,"delegatable":false,"refusal":"earthvaloper1a is jailed",
           "book":{"validator":"earthvaloper1a","pending_delegation":"7","pending_undelegation":"3","epoch_rate":"1.0","derth_supply":"90","slash_debt":"0"},
           "backing":"100","supply":"90","rate":"1.111111111111111111","delegation":"95","rewards":"1",
           "redelegations":[{"dst_validator":"earthvaloper1b","entries":4,"counted_entries":3}]},
          {"validator":"earthvaloper1gone","staking":{"operator_address":"","status":"BOND_STATUS_UNSPECIFIED","tokens":"0"},
           "tombstoned":false,"delegatable":false,"refusal":"unknown validator","book":{"pending_delegation":"7"},
           "backing":"7","supply":"7","rate":"1.000000000000000000","delegation":"0","rewards":"0","redelegations":[]}
        ],"pagination":{"next_key":null,"total":"0"},"height":"42"}
    """.trimIndent()

    @Test
    fun anEntryCarriesEveryQuoteInput() {
        val p = ValidatorPages.parse(JSONObject(page))
        assertEquals(42L, p.height)
        assertEquals("", p.nextKey)
        val a = p.validators[0]
        assertEquals(BigInteger.valueOf(100), a.backing)
        assertEquals(BigInteger.valueOf(90), a.supply)
        assertEquals(BigInteger.valueOf(7), a.pendingDelegation)
        assertEquals(BigInteger.valueOf(3), a.pendingUndelegation)
        assertEquals(BigInteger.valueOf(95), a.delegation)
        assertEquals(BigInteger.ONE, a.rewards)
        assertEquals(BigInteger.valueOf(8), a.queue)
        assertEquals("BOND_STATUS_UNBONDING", a.status)
        assertTrue(a.jailed && a.tombstoned && !a.delegatable)
        assertEquals("earthvaloper1a is jailed", a.refusal)
        assertEquals("Alpha", a.moniker)
        assertEquals(0.1, a.commission, 1e-12)
        assertEquals(PrivacyChainReads.RedelegationLoad(4, 3), a.redelegations["earthvaloper1b"])
        assertFalse(a.queueFirst)
        // A book whose validator x/staking removed: no status, the queue first.
        val g = p.validators[1]
        assertTrue(g.removed && g.queueFirst && !g.bonded)
        assertFalse(g.jailed)
    }

    @Test
    fun aMalformedEntryIsRefused() {
        for (bad in listOf(
            """{"validator":"v","backing":"-1","supply":"1"}""",
            """{"validator":"v","backing":"1","supply":"1","rate":"x"}""",
            """{"validator":"","backing":"1","supply":"1"}""",
            """{"validator":"v","staking":{"operator_address":"w"},"backing":"1","supply":"1","rate":"1"}""",
        )) assertThrows(bad, IOException::class.java) { ValidatorPages.quote(JSONObject(bad)) }
    }

    private fun q(v: String) = PrivacyChainReads.ValidatorQuote(v, BigInteger.TEN, BigInteger.TEN)

    @Test
    fun everyPageIsReadAtTheFirstPagesHeight() {
        val asked = ArrayList<Pair<String, Long?>>()
        val list = ValidatorPages.readAll { key, h ->
            asked += key to h
            when (key) {
                "" -> ValidatorPages.Page(listOf(q("a")), "k1", 10)
                "k1" -> ValidatorPages.Page(listOf(q("b")), "k2", 10)
                else -> ValidatorPages.Page(listOf(q("c")), "", 10)
            }
        }
        assertEquals(10L, list.height)
        assertEquals(listOf("a", "b", "c"), list.validators.map { it.validator })
        assertEquals(listOf("" to null, "k1" to 10L, "k2" to 10L), asked)
    }

    @Test
    fun aHeightChangeMidReadStartsOver() {
        var reads = 0
        val list = ValidatorPages.readAll { key, _ ->
            if (key == "") reads++
            when {
                key == "" -> ValidatorPages.Page(listOf(q("a")), "k1", 10L + reads)
                // The first read's second page comes from another height; the
                // second read's is not served at its height at all.
                reads == 1 -> ValidatorPages.Page(listOf(q("b")), "", 99)
                reads == 2 -> null
                else -> ValidatorPages.Page(listOf(q("b")), "", 10L + reads)
            }
        }
        assertEquals(3, reads)
        assertEquals(13L, list.height)
        assertEquals(listOf("a", "b"), list.validators.map { it.validator })
        // A list that never holds still is an error, not a mix.
        assertThrows(IOException::class.java) {
            var h = 0L
            ValidatorPages.readAll { key, _ -> if (key == "") ValidatorPages.Page(listOf(q("a")), "k", ++h) else ValidatorPages.Page(emptyList(), "", -1) }
        }
    }

    @Test
    fun loopsAndDuplicatesAreRefused() {
        assertThrows(IOException::class.java) { ValidatorPages.readAll { _, _ -> ValidatorPages.Page(listOf(), "k", 5) } }
        assertThrows(IOException::class.java) {
            ValidatorPages.readAll { key, _ -> ValidatorPages.Page(listOf(q("a")), if (key == "") "k" else "", 5) }
        }
        assertThrows(IOException::class.java) { ValidatorPages.readAll { _, _ -> ValidatorPages.Page(listOf(), "", 0) } }
    }
}
