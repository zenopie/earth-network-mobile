package network.erth.wallet.privacy.sync

import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A wallet's private state at rest: AES-256-GCM under the install's data key
 * (SessionManager.dataKey, kept inside the sealed wallet storage, so it opens
 * exactly when the wallet does, whatever the unlock method). The notes, the
 * registration record, the handle and the moves in flight are what tie this
 * phone's wallet to its shielded history and to a passport registration; on
 * disk they are this envelope, never plaintext. The trees beside it hold the
 * chain's public leaves and are not sealed.
 *
 *     {"sealed": 1, "alg": "AES-256-GCM", "kid": <hex>, "nonce": <hex>, "ct": <hex>}
 *
 * `kid` names the key (a hash of it, not the key) so a store sealed under an
 * earlier install's key is told apart from a damaged one. The wallet id is
 * the AAD: one wallet's state cannot be passed off as another's. Mirrored by
 * iOS StateSeal.swift; the format is the same.
 */
internal object StateSeal {
    const val FORMAT = 1
    private const val ALG = "AES-256-GCM"
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128

    /** The key id: the first 8 bytes of SHA-256("earth/privacy-store/kid" || key), hex. */
    fun kid(key: ByteArray): String =
        MessageDigest.getInstance("SHA-256").run {
            update("earth/privacy-store/kid".toByteArray(Charsets.UTF_8))
            digest(key)
        }.copyOf(8).let(::hex)

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun unhex(h: String): ByteArray {
        require(h.length % 2 == 0 && h.all { it in '0'..'9' || it in 'a'..'f' }) { "not lowercase hex" }
        return ByteArray(h.length / 2) { h.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }

    private fun aad(walletId: String) = "earth/privacy-state/v1|$walletId".toByteArray(Charsets.UTF_8)

    fun seal(plain: ByteArray, key: ByteArray, walletId: String): ByteArray {
        require(key.size == 32) { "the data key is 32 bytes" }
        val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        c.updateAAD(aad(walletId))
        val ct = c.doFinal(plain)
        return JSONObject()
            .put("sealed", FORMAT).put("alg", ALG).put("kid", kid(key))
            .put("nonce", hex(nonce))
            .put("ct", hex(ct))
            .toString().toByteArray(Charsets.UTF_8)
    }

    /** What a state file holds. */
    sealed class Contents {
        /** Written before sealing existed: read once, sealed on the next save. */
        data class Legacy(val json: JSONObject) : Contents()
        data class Opened(val json: JSONObject) : Contents()
        /** Sealed under another key: an earlier install's data, which this one cannot read. */
        data object OtherKey : Contents()
    }

    /** Throws when the file is damaged (bad JSON, unknown format, a failed tag under this key). */
    fun open(file: ByteArray, key: ByteArray?, walletId: String): Contents {
        val j = JSONObject(String(file, Charsets.UTF_8))
        if (!j.has("sealed")) return Contents.Legacy(j)
        val format = j.getInt("sealed")
        require(format == FORMAT && j.getString("alg") == ALG) { "sealed state format $format is not one this app reads" }
        requireNotNull(key) { "this wallet's private data is sealed and no key is open" }
        if (j.getString("kid") != kid(key)) return Contents.OtherKey
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, unhex(j.getString("nonce"))))
        c.updateAAD(aad(walletId))
        val plain = c.doFinal(unhex(j.getString("ct")))
        return Contents.Opened(JSONObject(String(plain, Charsets.UTF_8)))
    }
}
