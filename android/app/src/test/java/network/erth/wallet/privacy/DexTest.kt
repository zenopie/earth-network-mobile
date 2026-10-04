package network.erth.wallet.privacy

import java.math.BigInteger
import network.erth.wallet.chain.math.SwapMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dex deposits and withdrawals: a public deposit's share floor, and note legs bounded before a withdrawal starts. */
class DexTest : WalletTest() {
    @Test
    fun publicAddLiquidityCarriesMinShares() {
        // min(e*S/Re, t*S/Rt) = min(1000*500/2000, 300*500/1000) = 150, less 1%: 148.
        assertEquals("148", SwapMath.minShares(BigInteger.valueOf(1000), BigInteger.valueOf(300), BigInteger.valueOf(2000), BigInteger.valueOf(1000), BigInteger.valueOf(500), 100))
        assertEquals("", SwapMath.minShares(BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, 100))
        val any = network.erth.wallet.chain.Dex.msgAddLiquidity("earth1creator", 2, "uerth", "1000", "uusd", "300", "148")
        assertEquals("/earth.dex.v1.MsgAddLiquidity", any.typeUrl)
        // Golden shared with the iOS tests: fields 1-5.
        assertEquals(
            "0a0d65617274683163726561746f7210021a0d0a057565727468120431303030" +
                "220b0a047575736412033330302a03313438",
            any.value.toByteArray().joinToString("") { "%02x".format(it) },
        )
    }

    @Test
    fun withdrawalNoteLegsAreBoundedAtStart() {
        val total = BigInteger.valueOf(1_000)
        val big = PrivacyWallet.MAX_WITHDRAWAL_NOTE_LEG.multiply(BigInteger.valueOf(2))
        // Half the shares of a pool holding twice the cap: exactly the cap, allowed.
        PrivacyWallet.checkWithdrawalNoteLegs(BigInteger.valueOf(500), total, BigInteger.TEN, big, "uanml", erthNote = true, tokenNote = true)
        val e = assertThrows(PrivacyWallet.WithdrawalTooLarge::class.java) {
            PrivacyWallet.checkWithdrawalNoteLegs(BigInteger.valueOf(501), total, BigInteger.TEN, big, "uanml", erthNote = true, tokenNote = true)
        }
        assertTrue(e.message!!.contains("uanml") && e.message!!.contains("smaller parts"))
        // A leg paid to the account is not a note leg.
        PrivacyWallet.checkWithdrawalNoteLegs(BigInteger.valueOf(501), total, big, BigInteger.TEN, "uanml", erthNote = false, tokenNote = true)
        // The wallet's bound is below x/dex's (16 x (2^64-1)) and below one note it can hold.
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(32)), PrivacyWallet.MAX_WITHDRAWAL_NOTE_LEG)
    }
}
