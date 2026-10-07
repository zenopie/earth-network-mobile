package network.erth.wallet.ui.tx

import java.math.BigDecimal
import network.erth.wallet.privacy.PrivacyWallet
import network.erth.wallet.privacy.prove.PrivacyProver
import network.erth.wallet.privacy.tx.PrivateTxEngine as E
import network.erth.wallet.privacy.tx.UnsignedTx
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The confirm sheet's single-flight rule (iOS build 21: a registration's sheet
 * came back over "Sending" and was confirmed twice), the duplicate
 * registration refusals, and the registration estimate that made the sheet
 * come back. As iOS's TxGateTests.
 */
class TxGateTest {
    @Test
    fun aConfirmedRequestsSheetNeverComesBack() {
        val g = TxGate()
        val a = Any()
        assertTrue(g.present(a))
        assertTrue(g.confirm(a))
        assertFalse(g.confirm(a))
        assertFalse(g.present(a))
        g.finish(a)
        assertFalse(g.present(a))
        assertFalse(g.confirm(a))
    }

    @Test
    fun noSheetDrawsOverASend() {
        val g = TxGate()
        val a = Any()
        val b = Any()
        assertTrue(g.present(a))
        assertTrue(g.confirm(a))
        assertFalse(g.present(b))
        assertFalse(g.confirm(b))
        g.cancel()
        assertTrue(g.sending)
        g.finish(a)
        assertTrue(g.present(b))
        assertTrue(g.confirm(b))
    }

    @Test
    fun aReaskShowsTheSameRequestOnceMore() {
        val g = TxGate()
        val a = Any()
        assertTrue(g.present(a))
        assertTrue(g.confirm(a))
        assertTrue(g.reask(a))
        assertTrue(g.showing(a))
        assertTrue(g.confirm(a))
        g.finish(a)
        assertFalse(g.reask(a))
        assertFalse(g.present(a))
    }

    @Test
    fun aGasWaitStopsOnceItsSheetIsGone() {
        val g = TxGate()
        val a = Any()
        assertTrue(g.present(a))
        assertTrue(g.showing(a))
        assertTrue(g.confirm(a))
        assertFalse(g.showing(a))
        g.finish(a)
        val b = Any()
        assertTrue(g.present(b))
        g.cancel()
        assertFalse(g.showing(b))
        val c = Any()
        val d = Any()
        assertTrue(g.present(c))
        assertTrue(g.present(d))
        assertFalse(g.showing(c))
    }

    @Test
    fun duplicateRegistrationRefusals() {
        for (code in listOf(1123, 1124, 1130)) {
            assertTrue(TxGate.duplicateRegistration(UnsignedTx.TxRejected(code, "", "personhood")))
        }
        assertTrue(TxGate.duplicateRegistration(PrivacyWallet.IdentityUsed()))
        assertTrue(TxGate.duplicateRegistration(RuntimeException("wrapped", UnsignedTx.TxRejected(1124, "", "personhood"))))
        assertFalse(TxGate.duplicateRegistration(UnsignedTx.TxRejected(1102, "", "personhood")))
        assertFalse(TxGate.duplicateRegistration(UnsignedTx.TxRejected(1130, "", "sdk")))
    }

    /**
     * The sheet's registration fee covers the chain's default schedule with
     * the quote's headroom (else every registration is re-asked), and fits
     * inside the backend's 100,000 uerth gas grant at the validator's price.
     */
    @Test
    fun theRegistrationEstimateCoversItsQuote() {
        // Three proofs (the passport's, two actions'), the DSC and ciphertexts.
        val bytes = 3L * PrivacyProver.PROOF_BYTES + 4_096
        val gas = E.BASE_GAS + E.TX_BYTE_GAS * bytes + E.BUNDLE_GAS + 2 * E.ACTION_GAS + E.REGISTER_GAS
        val quoted = gas + maxOf(gas / 10, E.MIN_HEADROOM)
        assertTrue(quoted <= PrivacyWallet.REGISTER_GAS_ESTIMATE)
        assertTrue(E.feeFor(BigDecimal("0.005"), PrivacyWallet.REGISTER_GAS_ESTIMATE) <= 100_000)
    }

    @Test
    fun theReaskNoteSaysNothingWasSent() {
        val n = TxGate.reaskNote(52_000, 35_000)
        assertTrue(n.startsWith("Nothing was sent."))
        assertTrue(n.contains("0.052 ERTH"))
        assertTrue(n.contains("0.035 ERTH"))
    }
}
