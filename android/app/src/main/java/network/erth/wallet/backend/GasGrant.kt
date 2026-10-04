package network.erth.wallet.backend

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import network.erth.earth.proto.personhood.MsgRegister
import network.erth.wallet.Constants
import network.erth.wallet.chain.EarthRest
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Free gas for a first registration.
 *
 * A new human has no ERTH, and a registration is an unsigned private tx that
 * pays its fee from a shielded note, so the backend shields a little ERTH to a
 * note the app names (pc_gas). What keeps that from being drained is the
 * chain's own personhood: the backend is shown the MsgRegister about to be
 * broadcast and runs the chain's checks on it (the proof, its binding to the
 * notes, the DSC chain, the rate caps), and pays only if the chain would
 * accept it, at most once per passport per month. The backend learns that a
 * passport, public in the registration anyway, got a gas note; the note's
 * spend is unlinkable to it. Every later fee comes from the registration
 * reward, so there is no other grant.
 *
 * This returns when the backend has *sent* the grant, not when it has landed:
 * callers sync until the note appears.
 *
 * The request may need a proof of work ([GasPow]): GET /gas/pow says how
 * many bits admit a request now; a stamp at that difficulty goes with the
 * request; a 428 names the bits that request needs and a fresh stamp is made
 * at them. A stamp is kept for the person's next try only after a 503 or a
 * 429 (the server gave it back); a 403 or anything else drops it.
 */
object GasGrant {
    private const val TAG = "GasGrant"

    sealed interface Result {
        /** The grant was broadcast. Sync for the note. */
        data object Sent : Result

        /** Broadcast but unconfirmed: handled exactly like [Sent]. */
        data object Pending : Result

        /** Nothing was sent; [message] is for the person, as-is. */
        data class Refused(val message: String) : Result
    }

    /**
     * Gas for [msg], the registration about to be broadcast (without its fee
     * transfer), to the note [pcGas] with [ciphertextGas], its required
     * 177-byte v2 ciphertext (MsgShield's; the app finds the note by it). A
     * refusal carries the chain's own reason (an expired passport, say).
     */
    suspend fun forRegistration(
        msg: MsgRegister,
        pcGas: ByteArray,
        ciphertextGas: ByteArray,
        /** The proof of work's progress, 0..1, while one is being made. */
        onProgress: (Float) -> Unit = {},
    ): Result {
        val body = registerBody(msg, pcGas, ciphertextGas)
        val binding = msg.publicSignalsList.getOrNull(1) ?: return Result.Refused(UNAVAILABLE)
        val nullifier = msg.publicSignalsList.getOrNull(2) ?: return Result.Refused(UNAVAILABLE)
        return withPow(body, binding, nullifier, onProgress)
    }

    /** What talks to the backend; tests swap in a fake server. */
    internal interface Transport {
        fun get(path: String): Pair<Int, String>
        fun post(path: String, json: String): Pair<Int, String>
    }

    internal var transport: Transport = object : Transport {
        override fun get(path: String) = EarthRest.getFrom(Constants.EARTH_API_URL, path)
        override fun post(path: String, json: String) = EarthRest.postJson(path, json, base = Constants.EARTH_API_URL)
    }

    /** Unix seconds; tests pin it. */
    internal var clock: () -> Long = { System.currentTimeMillis() / 1000 }

    /** A stamp the server gave back (503, 429), for the next try of the same registration. */
    private var kept: Pair<String, GasPow.Stamp>? = null

    private const val MAX_POW_ROUNDS = 4

    /** How long a kept stamp is reused (the server takes ts within 600 s). */
    private const val STAMP_REUSE_S = 300L

    internal suspend fun withPow(body: JSONObject, binding: String, nullifier: String, onProgress: (Float) -> Unit): Result {
        val key = "$binding:$nullifier"
        suspend fun stamp(bits: Int): GasPow.Stamp? = if (bits <= 0) null else withContext(Dispatchers.Default) {
            GasPow.solve(clock(), binding, nullifier, bits, onProgress)
        }
        try {
            var stamp: GasPow.Stamp? = kept?.takeIf { it.first == key && clock() - it.second.ts < STAMP_REUSE_S }?.second
            kept = null
            if (stamp == null) stamp = stamp(withContext(Dispatchers.IO) { powBits() })
            repeat(MAX_POW_ROUNDS) {
                stamp?.let { body.put("pow", JSONObject().put("ts", it.ts).put("nonce", it.nonce)) } ?: body.remove("pow")
                val (code, response) = withContext(Dispatchers.IO) { transport.post("/gas/register", body.toString()) }
                val json = parse(response)
                when {
                    code == 200 && json?.optString("status") == "success" -> return Result.Sent
                    code == 202 -> return Result.Pending
                    code == 428 -> {
                        // A stamp is needed, or this one was stale or used: a fresh one at the bits asked.
                        val bits = json?.optJSONObject("pow")?.optInt("bits", -1) ?: -1
                        if (bits !in 1..GasPow.MAX_BITS) return refused(code, json, response)
                        stamp = stamp(bits)
                    }
                    else -> {
                        // 503, 429: the server gave the stamp back; the next try may use it.
                        if ((code == 503 || code == 429) && stamp != null) kept = key to stamp!!
                        return refused(code, json, response)
                    }
                }
            }
            return Result.Refused(UNAVAILABLE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "gas service unreachable: ${e.javaClass.simpleName}")
            return Result.Refused(UNREACHABLE)
        } catch (e: Exception) {
            Log.w(TAG, "gas grant failed: ${e.javaClass.simpleName}")
            return Result.Refused(UNAVAILABLE)
        }
    }

    /** GET /gas/pow's bits: what admits a request now (0 when it cannot say: the POST's 428 will). */
    private fun powBits(): Int = runCatching {
        val (code, body) = transport.get("/gas/pow")
        if (code !in 200..299) 0 else JSONObject(body).let { j ->
            if (j.optString("version") != GasPow.VERSION) 0 else j.optInt("bits", 0).takeIf { it in 0..GasPow.MAX_BITS } ?: 0
        }
    }.getOrDefault(0)

    private fun refused(code: Int, json: JSONObject?, response: String): Result {
        Log.w(TAG, "/gas/register refused: $code")
        val message = json?.optString("message")?.takeIf { it.isNotBlank() }
        if (json == null) Log.w(TAG, "not JSON: ${response.take(80)}")
        // The chain's own reason, in plain words where the app has them (1127, 1113).
        return Result.Refused(message?.let { network.erth.wallet.chain.ChainErrors.explain(it) ?: it } ?: UNAVAILABLE)
    }

    /**
     * MsgRegister's fields as the backend takes them: bytes as standard base64
     * (not URL-safe, no line breaks), public signals passed through untouched.
     * The backend rebuilds the message from this and checks it as the chain
     * would, so anything reformatted here is a message the chain never sees.
     */
    internal fun registerBody(msg: MsgRegister, pcGas: ByteArray, ciphertextGas: ByteArray): JSONObject = JSONObject()
        .put("proof", msg.proof.toByteArray().toByteString().base64())
        .put("public_signals", JSONArray(msg.publicSignalsList))
        .put("signature_algorithm", msg.signatureAlgorithm)
        .put("dsc_der", msg.dscDer.toByteArray().toByteString().base64())
        .put("idc", msg.idc.toByteArray().toByteString().base64())
        .put("pc_anml", msg.pcAnml.toByteArray().toByteString().base64())
        .put("pc_erth", msg.pcErth.toByteArray().toByteString().base64())
        .put("ciphertext_anml", msg.ciphertextAnml.toByteArray().toByteString().base64())
        .put("ciphertext_erth", msg.ciphertextErth.toByteArray().toByteString().base64())
        // The referrer by handle alone ("" for none): MsgRegister carries no
        // affiliate_pc / affiliate_ciphertext, and the backend refuses them.
        .put("affiliate_handle", msg.affiliateHandle)
        .put("pc_gas", pcGas.toByteString().base64())
        .put("ciphertext_gas", ciphertextGas.toByteString().base64())

    // An error in front of the backend (the tunnel, a proxy) answers in HTML,
    // so a body is not trusted to be JSON.
    private fun parse(body: String): JSONObject? = runCatching { JSONObject(body) }.getOrNull()

    private const val UNREACHABLE = "Couldn't reach the gas service. Check your connection and try again."
    private const val UNAVAILABLE = "Free gas is unavailable right now. Try again later."
}
