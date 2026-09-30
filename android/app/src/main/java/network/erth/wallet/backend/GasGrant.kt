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
 * Free gas for an account that cannot pay its own fee.
 *
 * A new human has no ERTH, and an address the chain has never seen cannot sign
 * anything at all, so the backend sends a little from its gas wallet. What
 * keeps that wallet from being drained is the chain's own personhood, not
 * anything about the device:
 *
 * - Registering: the backend is shown the MsgRegister about to be broadcast
 *   and runs the chain's checks on it — the proof, its binding to the address,
 *   the DSC chain, the rate caps. It pays only if the chain would accept the
 *   message, and at most once per passport per month.
 * - Everything else: the address must already be a registered human, and is
 *   paid at most once a day.
 *
 * This returns when the backend has *sent* the grant, not when it has landed:
 * the send still has to make it into a block, so callers poll the balance.
 */
object GasGrant {
    private const val TAG = "GasGrant"

    sealed interface Result {
        /** The grant was broadcast. Poll for it. */
        data object Sent : Result

        /**
         * The backend broadcast but could not confirm the outcome. Polled
         * exactly like [Sent]: if the send landed, the balance shows it, and
         * if it did not, the poll gives up and the button comes back.
         */
        data object Pending : Result

        /** Nothing was sent; [message] is for the person, as-is. */
        data class Refused(val message: String) : Result
    }

    /**
     * Gas for [msg], the registration about to be broadcast.
     *
     * The same message is broadcast once the gas lands — no second proof. A
     * refusal carries the chain's own reason (an expired passport, say), which
     * is worth more to the person than anything written here.
     */
    suspend fun forRegistration(msg: MsgRegister): Result =
        post("/gas/register", registerBody(msg), onRefused = { it })

    /** Gas for any other transaction, for an address that is a registered human. */
    suspend fun forHuman(address: String): Result =
        post(
            "/gas/human",
            JSONObject().put("address", address),
            // The backend's wording is for someone who knows what a
            // registered human is; this says what to do about it.
            onRefused = { message ->
                if (message.contains("not a registered human", ignoreCase = true)) {
                    "Register to get free gas."
                } else {
                    message
                }
            },
        )

    /**
     * MsgRegister as the backend takes it: field for field, bytes as standard
     * base64 (not URL-safe, no line breaks), public signals passed through
     * untouched. The backend rebuilds the message from this and checks it as
     * the chain would, so anything reformatted here — a signal normalised, a
     * byte re-encoded — is a message the chain never sees.
     */
    internal fun registerBody(msg: MsgRegister): JSONObject = JSONObject()
        .put("address", msg.creator)
        .put("proof", msg.proof.toByteArray().toByteString().base64())
        .put("public_signals", JSONArray(msg.publicSignalsList))
        .put("signature_algorithm", msg.signatureAlgorithm)
        .put("dsc_der", msg.dscDer.toByteArray().toByteString().base64())
        .put("affiliate", msg.affiliate)

    private suspend fun post(
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
