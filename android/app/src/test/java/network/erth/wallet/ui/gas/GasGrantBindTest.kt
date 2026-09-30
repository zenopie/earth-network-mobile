package network.erth.wallet.ui.gas

import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The attestation challenge must match the backend byte for byte, or every
 * chain comes back unbound and no grant is ever paid. Expected values computed
 * independently:
 *
 *     sha256(base64.urlsafe_b64decode(challenge + "=") + address).hexdigest()
 */
class GasGrantBindTest {

    @Test
    fun zeroChallenge() {
        assertEquals(
            "314fb6ad6cfe942e9a386b3507f7887d23935af58b6702fa4673e337d604d294",
            GasGrant.bind("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "earth1test").toByteString().hex(),
        )
    }

    // Bytes 200..231, chosen so the challenge carries both '-' and '_': a
    // decoder that only knows the standard alphabet fails here, not above.
    @Test
    fun urlSafeAlphabet() {
        assertEquals(
            "39ab8caafd80060681573ee356d9fe5180fdccd3e2bc5ccebb6cb93060d3f293",
            GasGrant.bind("yMnKy8zNzs_Q0dLT1NXW19jZ2tvc3d7f4OHi4-Tl5uc", "earth1abc").toByteString().hex(),
        )
    }
}
