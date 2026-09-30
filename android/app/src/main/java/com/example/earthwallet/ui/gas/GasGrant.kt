package network.erth.wallet.ui.gas

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityServiceException
import com.google.android.play.core.integrity.IntegrityTokenRequest
import com.google.android.play.core.integrity.model.IntegrityErrorCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import network.erth.wallet.Constants
import network.erth.wallet.chain.EarthRest
import okio.Buffer
import okio.ByteString.Companion.decodeBase64
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Free gas for an account that cannot pay its own fee.
 *
 * A new human has no ERTH, and an address the chain has never seen cannot sign
 * anything at all, so the backend sends a little from its gas wallet. What
 * stops a script from draining that wallet is Play Integrity: the backend pays
 * only a request carrying a verdict that it came from this app, installed from
 * Play, on a real device.
 *
 * The verdict is bound to the address. The nonce is SHA-256 over the backend's
 * single-use challenge and the address, so a token minted for one address
 * cannot be spent on another, and a challenge cannot be spent twice.
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

    suspend fun request(context: Context, address: String): Result = withContext(Dispatchers.IO) {
        try {
            val challenge = when (val issued = challenge(address)) {
                is Issued.Challenge -> issued.value
                is Issued.Refusal -> return@withContext issued.refused
            }
            val token = IntegrityManagerFactory.create(context.applicationContext)
                .requestIntegrityToken(
                    // No cloud project number: the app is distributed through
                    // Play and linked in Play Console, which is how Play knows
                    // which project's verdict to issue.
                    IntegrityTokenRequest.builder()
                        .setNonce(nonceFor(challenge, address))
                        .build(),
                )
                .await()
                .token()
            submit(address, challenge, token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IntegrityServiceException) {
            Log.w(TAG, "integrity request failed: code=${e.errorCode}", e)
            Result.Refused(integrityMessage(e.errorCode))
        } catch (e: IOException) {
            Log.w(TAG, "gas service unreachable", e)
            Result.Refused(UNREACHABLE)
        } catch (e: Exception) {
            Log.e(TAG, "gas grant failed", e)
            Result.Refused(UNAVAILABLE)
        }
    }

    /**
     * `base64url(SHA-256(base64url_decode(challenge) || utf8(address)))`,
     * unpadded — 43 characters, inside Play Integrity's 16–500 byte bound.
     *
     * Must match the backend byte for byte, or every verdict is refused as
     * unbound. okio rather than android.util.Base64 so this runs in a plain
     * JVM test, and rather than java.util.Base64 because that needs API 26.
     */
    internal fun nonceFor(challenge: String, address: String): String {
        val raw = requireNotNull(challenge.decodeBase64()) { "challenge is not base64" }
        return Buffer().write(raw).writeUtf8(address).sha256().base64Url().trimEnd('=')
    }

    private sealed interface Issued {
        data class Challenge(val value: String) : Issued
        data class Refusal(val refused: Result.Refused) : Issued
    }

    /**
     * Asked for before the token, not alongside it: the challenge is the nonce
     * input, and a refusal here (the address already had its grant, say)
     * spares a Play Integrity call that would only be thrown away.
     */
    private fun challenge(address: String): Issued {
        val (code, body) = EarthRest.postJson(
            "/gas/challenge",
            JSONObject().put("address", address).toString(),
            base = Constants.EARTH_API_URL,
        )
        val json = parse(body)
        val challenge = json?.optString("challenge").orEmpty()
        if (code == 200 && challenge.isNotEmpty()) return Issued.Challenge(challenge)
        Log.w(TAG, "challenge refused: $code $body")
        return Issued.Refusal(Result.Refused(messageOf(json)))
    }

    private fun submit(address: String, challenge: String, token: String): Result {
        val (code, body) = EarthRest.postJson(
            "/gas/android",
            JSONObject()
                .put("address", address)
                .put("challenge", challenge)
                .put("token", token)
                .toString(),
            base = Constants.EARTH_API_URL,
        )
        val json = parse(body)
        val status = json?.optString("status").orEmpty()
        return when {
            code == 200 && status == "success" -> Result.Sent
            code == 202 -> Result.Pending
            else -> {
                Log.w(TAG, "grant refused: $code $body")
                Result.Refused(messageOf(json))
            }
        }
    }

    // An error in front of the backend (the tunnel, a proxy) answers in HTML,
    // so a body is not trusted to be JSON.
    private fun parse(body: String): JSONObject? = runCatching { JSONObject(body) }.getOrNull()

    private fun messageOf(json: JSONObject?): String =
        json?.optString("message")?.takeIf { it.isNotBlank() } ?: UNAVAILABLE

    private fun integrityMessage(code: Int): String = when (code) {
        IntegrityErrorCode.PLAY_STORE_NOT_FOUND,
        IntegrityErrorCode.PLAY_STORE_VERSION_OUTDATED,
        -> "Free gas needs an up-to-date Google Play Store."
        IntegrityErrorCode.PLAY_SERVICES_NOT_FOUND,
        IntegrityErrorCode.PLAY_SERVICES_VERSION_OUTDATED,
        -> "Free gas needs up-to-date Google Play services."
        IntegrityErrorCode.PLAY_STORE_ACCOUNT_NOT_FOUND ->
            "Sign in to the Google Play Store to get free gas."
        IntegrityErrorCode.NETWORK_ERROR -> UNREACHABLE
        IntegrityErrorCode.TOO_MANY_REQUESTS -> "Too many requests. Try again in a few minutes."
        // Play has not heard of this install, which is a sideloaded or
        // modified copy: the backend would refuse its verdict anyway.
        IntegrityErrorCode.APP_NOT_INSTALLED,
        IntegrityErrorCode.APP_UID_MISMATCH,
        -> "Free gas is only available in the app installed from Google Play."
        else -> "Couldn't verify this device with Google Play. Try again in a moment."
    }

    private const val UNREACHABLE = "Couldn't reach the gas service. Check your connection and try again."
    private const val UNAVAILABLE = "Free gas is unavailable right now. Try again later."

    /**
     * Awaits a Play services Task without pulling in
     * kotlinx-coroutines-play-services for one call.
     */
    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnCompleteListener { task ->
            val error = task.exception
            when {
                error != null -> cont.resumeWithException(error)
                task.isCanceled -> cont.cancel()
                else -> cont.resume(task.result)
            }
        }
    }
}
