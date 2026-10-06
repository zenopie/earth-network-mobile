package network.erth.wallet.privacy

import network.erth.wallet.chain.ChainErrors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Chain errors the wallet explains in plain words. */
class ChainErrorsTest : WalletTest() {
    @Test
    fun newChainErrorsAreExplained() {
        assertNotNull(ChainErrors.explain(1101, "dex", "invalid amount: the uanml leg (1) is above 2, the most one withdrawal pays as notes; withdraw in smaller parts"))
        // Another 1101 is not mislabelled.
        assertNull(ChainErrors.explain(1101, "dex", "invalid amount: zero"))
        assertNotNull(ChainErrors.explain(5, "bank", "uanml: send transactions are disabled"))
        assertNotNull(ChainErrors.explain("failed to execute message; message index: 0: \"alice\" is not live (renewal): renew it before moving it: invalid private msg"))
        assertNotNull(ChainErrors.explain(1103, "shielded", "01AB expires at 5, within 120s of the last block: pick a newer anchor: root is not a recent note-tree root"))
        assertNull(ChainErrors.explain(1103, "shielded", "root is not a recent note-tree root"))
    }

    @Test
    fun switchSignerAndDailyCapErrorsArePlain() {
        val mismatch = ChainErrors.explain(1127, "personhood")!!
        assertTrue(mismatch, "same passport" in mismatch)
        // A simulate (or the gas service) answers with the registered text.
        assertEquals(mismatch, ChainErrors.explain("identity switch must be proven under the live registration's document signer"))
        val cap = ChainErrors.explain(1113, "personhood")!!
        assertTrue(cap, "Try again tomorrow" in cap)
        assertEquals(cap, ChainErrors.explain("rpc error: daily registration limit reached for this document signer or country"))
        // A second switch the same UTC day.
        val stale = ChainErrors.explain(1128, "personhood")!!
        assertTrue(stale, "once per day" in stale)
        assertEquals(stale, ChainErrors.explain("rpc error: identity switch must be proven on a later date than the live registration: proof dated 1, live registration proven 1"))
        // The codes mean nothing in another module.
        assertNull(ChainErrors.explain(1127, "dex"))
    }

    /** 1130: an identity registers once; the wallet says to switch to a new one. */
    @Test
    fun usedIdentityIsPlain() {
        val used = ChainErrors.explain(1130, "personhood")!!
        assertTrue(used, "Switch to a new wallet" in used)
        assertEquals(used, ChainErrors.explain("rpc error: identity commitment has been registered before; register a fresh identity"))
        assertNull(ChainErrors.explain(1130, "dex"))
    }
}
