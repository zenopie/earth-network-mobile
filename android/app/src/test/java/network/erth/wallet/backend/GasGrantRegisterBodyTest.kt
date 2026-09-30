package network.erth.wallet.backend

import network.erth.wallet.chain.Personhood
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The backend rebuilds MsgRegister from this body and checks it as the chain
 * would, so the body must carry the message exactly. Expected base64 computed
 * independently with Python's base64.b64encode.
 */
class GasGrantRegisterBodyTest {

    // 100 bytes, 255 down to 156: long enough that an encoder wrapping at 76
    // characters would insert a newline, and full of '/' and '+'.
    private val proof = ByteArray(100) { (255 - it).toByte() }
    private val dscDer = byteArrayOf(0xfb.toByte(), 0xff.toByte(), 0xbf.toByte(), 0xfe.toByte(), 0x00)

    // Leading zeros and a value past Long: passed through, never parsed.
    private val signals = listOf("260929", "0012", "21888242871839275222246405745257275088548364400416034343698204186575808495617", "0")

    private val body = GasGrant.registerBody(
        Personhood.registerMsg("earth1creator", proof, signals, "rsa_2048_sha256", "earth1referrer", dscDer),
    )

    @Test
    fun bytesAreStandardBase64WithoutWrapping() {
        assertEquals(
            "//79/Pv6+fj39vX08/Lx8O/u7ezr6uno5+bl5OPi4eDf3t3c29rZ2NfW1dTT0tHQz87NzMvKycjHxsXEw8LBwL++" +
                "vby7urm4t7a1tLOysbCvrq2sq6qpqKempaSjoqGgn56dnA==",
            body.getString("proof"),
        )
        assertEquals("+/+//gA=", body.getString("dsc_der"))
    }

    @Test
    fun publicSignalsPassThroughUnchanged() {
        val out = body.getJSONArray("public_signals")
        assertEquals(signals, List(out.length()) { out.getString(it) })
    }

    @Test
    fun remainingFields() {
        assertEquals("earth1creator", body.getString("address"))
        assertEquals("rsa_2048_sha256", body.getString("signature_algorithm"))
        assertEquals("earth1referrer", body.getString("affiliate"))
    }

    @Test
    fun noReferrerIsEmptyString() {
        val unreferred = GasGrant.registerBody(
            Personhood.registerMsg("earth1creator", proof, signals, "rsa_2048_sha256", null, dscDer),
        )
        assertEquals("", unreferred.getString("affiliate"))
    }
}
