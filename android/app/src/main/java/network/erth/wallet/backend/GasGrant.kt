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
    suspend fun forRegistration(msg: MsgRegister, pcGas: ByteArray, ciphertextGas: ByteArray): Result =
        post("/gas/register", registerBody(msg, pcGas, ciphertextGas), onRefused = { it })

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
        .put("affiliate", msg.affiliate)
        .put("pc_gas", pcGas.toByteString().base64())
        .put("ciphertext_gas", ciphertextGas.toByteString().base64())

    internal suspend fun post(
        path: String,
        body: JSONObject,
        onRefused: (String) -> String,
    ): Result = withContext(Dispatchers.IO) {
        try {
            val (code, response) = EarthRest.postJson(path, body.toString(), base = Constants.EARTH_API_URL)
            val json = parse(response)
            val status = json?.optString("status").orEmpty()
            when {
                code == 200 && status == "success" -> Result.Sent
                code == 202 -> Result.Pending
                else -> {
                    Log.w(TAG, "$path refused: $code $response")
                    val message = json?.optString("message")?.takeIf { it.isNotBlank() }
                    Result.Refused(message?.let(onRefused) ?: UNAVAILABLE)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "gas service unreachable", e)
            Result.Refused(UNREACHABLE)
        } catch (e: Exception) {
            Log.e(TAG, "gas grant failed", e)
            Result.Refused(UNAVAILABLE)
        }
    }

    // An error in front of the backend (the tunnel, a proxy) answers in HTML,
    // so a body is not trusted to be JSON.
    private fun parse(body: String): JSONObject? = runCatching { JSONObject(body) }.getOrNull()

    private const val UNREACHABLE = "Couldn't reach the gas service. Check your connection and try again."
    private const val UNAVAILABLE = "Free gas is unavailable right now. Try again later."
}
