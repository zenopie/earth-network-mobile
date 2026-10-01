package network.erth.wallet.privacy.note

import network.erth.wallet.privacy.keys.PrivacyKeys
import network.erth.wallet.privacy.keys.ShieldedAddress
import network.erth.wallet.privacy.zk.Fr
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.nio.ByteBuffer
import java.security.SecureRandom

/**
 * Note ciphertexts. The chain treats them as opaque bytes (at most 1024, bound
 * into the signal so a relay cannot swap them), so the format is the
 * wallet's, documented in PRIVACY_FORMATS.md ("earth note v1"):
 *
 *     ct  = 0x01 || epk (32) || ChaCha20-Poly1305(key, nonce = 0^12, aad = 0x01 || epk, pt)
 *     key = HKDF-SHA256(ikm = X25519(esk, ek_pub), salt = epk || ek_pub, info = "earth.note.v1")
 *     pt  = 0x01 || value u64 BE || rho 32 || rcm 32 || denom_len u8 || denom (128, zero padded)
 *           || memo_len u16 BE || memo (52, zero padded)                         (256 bytes)
 *
 * 305 bytes whatever the denom and memo, so a ciphertext's length says nothing
 * about its asset. Every key is ephemeral, so the fixed nonce is never reused
 * under one key. A dummy output (value 0) is encrypted to a throwaway key: it
 * looks like every other ciphertext and nobody can open it.
 */
object NoteCipher {
    const val VERSION: Byte = 1
    const val PLAINTEXT_BYTES = 256
    const val CIPHERTEXT_BYTES = 1 + 32 + PLAINTEXT_BYTES + 16
    const val MAX_DENOM = 128
    const val MAX_MEMO = 52
    private val INFO = "earth.note.v1".toByteArray()
    private val rng = SecureRandom()

    fun encrypt(note: NotePlaintext, to: ShieldedAddress): ByteArray = encrypt(note, to.ekPub)

    fun encrypt(note: NotePlaintext, ekPub: ByteArray): ByteArray {
        val esk = X25519PrivateKeyParameters(rng)
        val epk = esk.generatePublicKey().encoded
        val shared = ByteArray(32)
        X25519Agreement().apply { init(esk) }.calculateAgreement(X25519PublicKeyParameters(ekPub, 0), shared, 0)
        val key = kdf(shared, epk, ekPub)
        val aad = byteArrayOf(VERSION) + epk
        val body = aead(true, key, aad, plaintext(note))
        return aad + body
    }

    /** A ciphertext for a dummy output: well-formed, and openable by no one. */
    fun dummy(): ByteArray {
        val throwaway = X25519PrivateKeyParameters(rng).generatePublicKey().encoded
        return encrypt(NotePlaintext.fresh("uerth", 0), throwaway)
    }

    /** The note if [ct] is ours (and well formed), else null. Never throws. */
    fun tryDecrypt(ct: ByteArray, keys: PrivacyKeys): NotePlaintext? {
        if (ct.size != CIPHERTEXT_BYTES || ct[0] != VERSION) return null
        return try {
            val epk = ct.copyOfRange(1, 33)
            val shared = ByteArray(32)
            X25519Agreement().apply { init(keys.ek()) }.calculateAgreement(X25519PublicKeyParameters(epk, 0), shared, 0)
            val key = kdf(shared, epk, keys.ekPub)
            parse(aead(false, key, ct.copyOf(33), ct.copyOfRange(33, ct.size)))
        } catch (e: Exception) {
            // Not ours (the tag fails), a low-order epk, or a malformed body.
            null
        }
    }

    private fun kdf(shared: ByteArray, epk: ByteArray, ekPub: ByteArray): ByteArray {
        require(!shared.all { it.toInt() == 0 }) { "low-order point" }
        val out = ByteArray(32)
        HKDFBytesGenerator(SHA256Digest()).apply { init(HKDFParameters(shared, epk + ekPub, INFO)) }.generateBytes(out, 0, 32)
        return out
    }

    private fun aead(encrypt: Boolean, key: ByteArray, aad: ByteArray, input: ByteArray): ByteArray {
        val c = ChaCha20Poly1305()
        c.init(encrypt, AEADParameters(KeyParameter(key), 128, ByteArray(12), aad))
        val out = ByteArray(c.getOutputSize(input.size))
        val n = c.processBytes(input, 0, input.size, out, 0)
        c.doFinal(out, n)
        return out
    }

    internal fun plaintext(n: NotePlaintext): ByteArray {
        val denom = n.denom.toByteArray(Charsets.UTF_8)
        require(denom.size <= MAX_DENOM) { "denom too long for a note" }
        require(n.memo.size <= MAX_MEMO) { "memo exceeds $MAX_MEMO bytes" }
        val b = ByteBuffer.allocate(PLAINTEXT_BYTES)
        b.put(VERSION).putLong(n.value).put(n.rho.toBytes()).put(n.rcm.toBytes())
        b.put(denom.size.toByte()).put(denom.copyOf(MAX_DENOM))
        b.putShort(n.memo.size.toShort()).put(n.memo.copyOf(MAX_MEMO))
        return b.array()
    }

    internal fun parse(pt: ByteArray): NotePlaintext? {
        if (pt.size != PLAINTEXT_BYTES || pt[0] != VERSION) return null
        val b = ByteBuffer.wrap(pt, 1, PLAINTEXT_BYTES - 1)
        val value = b.long
        val rho = ByteArray(32).also { b.get(it) }
        val rcm = ByteArray(32).also { b.get(it) }
        val dl = b.get().toInt() and 0xff
        val denom = ByteArray(MAX_DENOM).also { b.get(it) }
        val ml = b.short.toInt() and 0xffff
        val memo = ByteArray(MAX_MEMO).also { b.get(it) }
        if (dl > MAX_DENOM || ml > MAX_MEMO) return null
        return NotePlaintext(
            denom = String(denom, 0, dl, Charsets.UTF_8),
            value = value,
            rho = runCatching { Fr.fromBytes(rho) }.getOrNull() ?: return null,
            rcm = runCatching { Fr.fromBytes(rcm) }.getOrNull() ?: return null,
            memo = memo.copyOf(ml),
        )
    }
}
