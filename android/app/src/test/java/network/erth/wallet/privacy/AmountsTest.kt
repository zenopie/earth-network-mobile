package network.erth.wallet.privacy

import network.erth.wallet.privacy.sync.HttpPrivacyIndexer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigDecimal

/** u64 amounts parsed unsigned and bounded to 2^63-1, sums saturating (display) or checked (tx). */
class AmountsTest {
    @Test
    fun parsesUnsignedWithinLong() {
        assertEquals(0L, Amounts.parseU64("0"))
        assertEquals(Long.MAX_VALUE, Amounts.parseU64("9223372036854775807"))
        assertNull(Amounts.parseU64("9223372036854775808"))
        assertNull(Amounts.parseU64("18446744073709551615"))
        assertNull(Amounts.parseU64("18446744073709551616"))
        assertNull(Amounts.parseU64("-1"))
        assertNull(Amounts.parseU64("+1"))
        assertNull(Amounts.parseU64(" 1"))
        assertNull(Amounts.parseU64(""))
        assertNull(Amounts.parseU64(null))
    }

    @Test
    fun sumsSaturateOrThrow() {
        assertEquals(Long.MAX_VALUE, Amounts.satAdd(Long.MAX_VALUE, 1))
        assertEquals(Long.MAX_VALUE, Amounts.satSum(listOf(Long.MAX_VALUE / 2, Long.MAX_VALUE / 2, 10)))
        assertEquals(6L, Amounts.satSum(listOf(1L, 2L, 3L)))
        assertThrows(ArithmeticException::class.java) { Amounts.exactSum(listOf(Long.MAX_VALUE, 1L)) { it } }
        assertEquals(Long.MAX_VALUE, PrivacyWallet.derthValue(Long.MAX_VALUE, BigDecimal("2.5")))
        assertEquals(0L, PrivacyWallet.derthValue(5, BigDecimal("-1")))
    }

    /** A stake row is [position, height, cm, ciphertext] (format 2); an older row's extra columns are ignored, never trusted. */
    @Test
    fun stakeRowsCarryNoPublicAmount() {
        val page = JSONObject(
            """{"format":2,"notes":[[0,1,"${"00".repeat(32)}","AAAA"],[1,1,"${"00".repeat(32)}",null,"derth/x","18446744073709551615",null]],"next_pos":2,"complete":true,"synced_height":1}""",
        )
        val rows = HttpPrivacyIndexer.parseStakeNotes(page).rows
        assertEquals(listOf(3, 0), rows.map { it.ciphertext.size })
    }
}
