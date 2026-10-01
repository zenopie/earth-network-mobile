package network.erth.wallet.privacy

import network.erth.wallet.privacy.zk.Fr
import org.json.JSONObject

/**
 * Golden vectors generated from the chain's own Go code by
 * tools/privacyvectors/gen.sh (src/test/resources/privacy).
 */
object Vectors {
    val json: JSONObject by lazy { JSONObject(resource("privacy/vectors.json").decodeToString()) }

    fun resource(path: String): ByteArray =
        (Vectors::class.java.classLoader!!.getResourceAsStream(path) ?: error("missing test resource $path")).use { it.readBytes() }

    fun fr(hex: String): Fr = Fr.fromHex(hex)

    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun unhex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    /** The generator's fe(i) = Poseidon2([i]). */
    fun fe(i: Long): Fr = network.erth.wallet.privacy.zk.Poseidon2.hash(Fr.of(i))
}
