package network.erth.wallet.ui.gas

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import network.erth.wallet.Constants
import network.erth.wallet.chain.EarthRest
import okio.Buffer
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.ProviderException
import java.security.spec.ECGenParameterSpec
import java.util.UUID

/**
 * Free gas for an account that cannot pay its own fee.
 *
 * A new human has no ERTH, and an address the chain has never seen cannot sign
 * anything at all, so the backend sends a little from its gas wallet. What
 * stops a script from draining that wallet is Android Key Attestation: the
 * phone's secure hardware generates a fresh key and certifies it, and that
 * certificate carries the backend's challenge along with this app's package
 * name and signing-certificate digest. The backend checks the chain up to
 * Google's public hardware-attestation roots, so only this app, signed by us,
 * on real hardware, can produce one. No Google Play services and no Cloud
 * project are involved, and a sideloaded copy of our signed APK qualifies.
 *
 * The attestation is bound to the address. The challenge the key is attested
 * over is SHA-256 of the backend's single-use challenge and the address, so a
 * chain made for one address cannot be spent on another, and a challenge
 * cannot be spent twice.
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

    suspend fun request(address: String): Result = withContext(Dispatchers.IO) {
        try {
            val challenge = when (val issued = challenge(address)) {
                is Issued.Challenge -> issued.value
                is Issued.Refusal -> return@withContext issued.refused
            }
            val chain = attest(bind(challenge, address))
                ?: return@withContext Result.Refused(UNATTESTED)
            submit(address, challenge, chain)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "gas service unreachable", e)
            Result.Refused(UNREACHABLE)
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "key attestation failed", e)
            Result.Refused(UNATTESTED)
        } catch (e: ProviderException) {
            // What the keystore throws when the hardware itself refuses, for
            // instance a device that cannot attest at all.
            Log.w(TAG, "key attestation failed", e)
            Result.Refused(UNATTESTED)
        } catch (e: Exception) {
            Log.e(TAG, "gas grant failed", e)
            Result.Refused(UNAVAILABLE)
        }
    }

    /**
     * `SHA-256(base64url_decode(challenge) || utf8(address))`, raw 32 bytes.
     *
     * Must match the backend byte for byte, or every chain is refused as
     * unbound. okio rather than android.util.Base64 so this runs in a plain
     * JVM test, and rather than java.util.Base64 because that needs API 26.
     */
    internal fun bind(challenge: String, address: String): ByteArray {
        val raw = requireNotNull(challenge.decodeBase64()) { "challenge is not base64" }
        return Buffer().write(raw).writeUtf8(address).sha256().toByteArray()
    }

    /**
     * Generates a throwaway key attested over [bound] and returns its
     * certificate chain, leaf first, as DER.
     *
     * The key is never used to sign anything; it exists only for its
     * certificate, so it is deleted as soon as the chain is read. A fresh alias
     * each time keeps two requests from ever sharing one. No StrongBox: most
     * phones lack one, and a TEE-backed key attests the same facts.
     *
     * Null when the keystore hands back no chain, or only the key's own
     * self-signed certificate — the device generated a key but cannot attest
     * it, and the backend would refuse that anyway.
     */
    private fun attest(bound: ByteArray): List<ByteArray>? {
        val alias = "earth-gas-" + UUID.randomUUID()
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        try {
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setAttestationChallenge(bound)
                .build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).run {
                initialize(spec)
                generateKeyPair()
            }
            val chain = keyStore.getCertificateChain(alias)
            if (chain == null || chain.size < 2) return null
            return chain.map { it.encoded }
        } finally {
            runCatching { keyStore.deleteEntry(alias) }
        }
    }

    private sealed interface Issued {
        data class Challenge(val value: String) : Issued
        data class Refusal(val refused: Result.Refused) : Issued
    }

    /**
     * Asked for before attesting, not alongside it: the challenge goes into
     * the certificate, and a refusal here (the address already had its grant,
     * say) spares generating a key that would only be thrown away.
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

    /**
     * [chain] goes up as standard base64 (not URL-safe) of each certificate's
     * DER, leaf first, in the order the keystore returned it.
     */
    private fun submit(address: String, challenge: String, chain: List<ByteArray>): Result {
        val (code, body) = EarthRest.postJson(
            "/gas/android",
            JSONObject()
                .put("address", address)
                .put("challenge", challenge)
                .put("chain", JSONArray(chain.map { it.toByteString().base64() }))
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

    private const val UNREACHABLE = "Couldn't reach the gas service. Check your connection and try again."
    private const val UNAVAILABLE = "Free gas is unavailable right now. Try again later."
    private const val UNATTESTED = "This phone couldn't verify the app, so free gas isn't available here."

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
}
