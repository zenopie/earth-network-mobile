package network.erth.wallet.backend

import kotlinx.coroutines.ensureActive
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * The hashcash stamp /gas/register may ask for (backend README "Proof of
 * work", services/pow.py):
 *
 *     input = "earth-gas-pow/v1:" + ts + ":" + binding + ":" + nullifier + ":" + nonce   (ASCII)
 *     valid = SHA-256(input) has at least `bits` leading zero bits
 *
 * ts is unix seconds (the server takes ±600 s), binding and nullifier are
 * public_signals[1] and [2] exactly as the request sends them, the nonce a
 * lowercase hex counter (1-64 of [0-9A-Za-z]). 22 bits, the server's cap,
 * is about four million hashes: a few seconds on a phone.
 */
object GasPow {
    const val VERSION = "earth-gas-pow/v1"

    /** The most bits the wallet will work for (the server's POW_MAX_BITS is 22). */
    const val MAX_BITS = 28

    data class Stamp(val ts: Long, val nonce: String, val bits: Int)

    fun input(ts: Long, binding: String, nullifier: String, nonce: String): ByteArray =
        "$VERSION:$ts:$binding:$nullifier:$nonce".toByteArray(Charsets.US_ASCII)

    fun leadingZeroBits(d: ByteArray): Int {
        var n = 0
        for (b in d) {
            val v = b.toInt() and 0xff
            if (v == 0) { n += 8; continue }
            return n + Integer.numberOfLeadingZeros(v) - 24
        }
        return n
    }

    /**
     * A stamp at [bits] for this registration, made at [ts]. Suspends between
     * batches so it can be cancelled; [progress] gets the expected fraction
     * done (tries over 2^bits, capped below 1).
     */
    suspend fun solve(ts: Long, binding: String, nullifier: String, bits: Int, progress: (Float) -> Unit = {}): Stamp {
        require(bits in 0..MAX_BITS) { "the gas service asks $bits bits of work" }
        val md = MessageDigest.getInstance("SHA-256")
        val prefix = "$VERSION:$ts:$binding:$nullifier:".toByteArray(Charsets.US_ASCII)
        val expected = 1L shl bits
        var i = 0L
        while (true) {
            val nonce = java.lang.Long.toHexString(i)
            md.update(prefix)
            md.update(nonce.toByteArray(Charsets.US_ASCII))
            if (leadingZeroBits(md.digest()) >= bits) {
                progress(1f)
                return Stamp(ts, nonce, bits)
            }
            i++
            if (i and 0xfff == 0L) {
                coroutineContext.ensureActive()
                progress(minOf(0.99f, i.toFloat() / expected))
            }
        }
    }
}
