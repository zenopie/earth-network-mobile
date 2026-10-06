package network.erth.wallet.ui.tx

import network.erth.wallet.chain.Bank
import network.erth.wallet.chain.Dex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** MK-15: a signed tx's sheet says what its messages say, and only this wallet's messages get one. */
class SignedSummaryTest {
    private val me = "earth1me"
    private val asked = TxConfirmDetails(action = "Send ERTH", msgTypeUrl = "?", balanceUerth = 0, amountValue = "9 ERTH", recipient = "earth1typed")

    @Test
    fun theSheetIsReadFromTheMessages() {
        val d = SignedSummary.describe(listOf(Bank.msgSend(me, "earth1signed", "uerth", "1500000")), asked, me)
        assertEquals("/cosmos.bank.v1beta1.MsgSend", d.msgTypeUrl)
        assertEquals("1.5 ERTH", d.amountValue)
        assertEquals("earth1signed", d.recipient)
        assertEquals("Send ERTH", d.action)
        val lp = SignedSummary.describe(listOf(Dex.msgAddLiquidity(me, 2, "uerth", "1000000", "uanml", "2000000", "1")), asked, me)
        assertEquals("1 ERTH + 2 ANML", lp.amountValue)
    }

    @Test
    fun anotherWalletsMessageGetsNoSheet() {
        assertThrows(SignedSummary.Mismatch::class.java) {
            SignedSummary.describe(listOf(Bank.msgSend("earth1other", "earth1x", "uerth", "1")), asked, me)
        }
        assertThrows(SignedSummary.Mismatch::class.java) { SignedSummary.describe(emptyList(), asked, me) }
    }
}
