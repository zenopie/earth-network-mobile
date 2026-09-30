package network.erth.wallet.ui.gas

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The nonce must match the backend byte for byte, or every verdict comes back
 * unbound and no grant is ever paid. Expected values computed independently:
 *
 *     base64.urlsafe_b64encode(sha256(challenge_bytes + address)).rstrip(b"=")
 */
class GasGrantNonceTest {

    @Test
    fun zeroChallenge() {
        assertEquals(
            "MU-2rWz-lC6aOGs1B_eIfSOTWvWLZwL6RnPjN9YE0pQ",
            GasGrant.nonceFor("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "earth1test"),
        )
    }

    // Bytes 200..231, chosen so the challenge carries both '-' and '_': a
    // decoder that only knows the standard alphabet fails here, not above.
    @Test
    fun urlSafeAlphabet() {
        assertEquals(
            "OauMqv2ABgaBVz7jVtn-UYD9zNPivFzOu2y5MGDT8pM",
            GasGrant.nonceFor("yMnKy8zNzs_Q0dLT1NXW19jZ2tvc3d7f4OHi4-Tl5uc", "earth1abc"),
        )
    }

    @Test
    fun unpaddedAndPlayIntegritySized() {
        val nonce = GasGrant.nonceFor("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "earth1test")
        assertEquals(43, nonce.length)
    }
}
