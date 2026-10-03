package network.erth.wallet.privacy.keys

import network.erth.wallet.privacy.zk.Fr
import network.erth.wallet.privacy.zk.Privacy
import org.bitcoinj.crypto.ChildNumber
import org.bitcoinj.crypto.DeterministicKey
import org.bitcoinj.crypto.HDKeyDerivation
import org.bitcoinj.crypto.MnemonicCode
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The wallet's privacy keys, all derived from the BIP-39 mnemonic so the
 * mnemonic stays the only backup. The chain does not define these (it sees
 * only their hashes); the derivation is the wallet's, documented in
 * PRIVACY_FORMATS.md, and the iOS port must reproduce it exactly.
 *
 * BIP-32 from the BIP-39 seed (empty passphrase) on a purpose of its own, so
 * no Cosmos account key is ever reused:
 *
 *     m/2026'/118'/0'/0'   id_secret   (identity: idc = H(TAG_ID, id_secret))
 *     m/2026'/118'/0'/1'   nk          (spending: owner_pk = H(TAG_OWNER, nk))
 *     m/2026'/118'/0'/2'   ek          (x25519 note-encryption key)
 *
 * A child's 32-byte private key k becomes a secret by
 * HMAC-SHA512(key = "earth.privacy.v1", label || k): reduced mod p for the
 * field secrets (64 bytes, so uniform), its first 32 bytes for ek.
 */
class PrivacyKeys private constructor(
    val idSecret: Fr,
    val nk: Fr,
    private val ekSecret: ByteArray,
) {
    val idc: Fr = Privacy.idc(idSecret)
    val ownerPk: Fr = Privacy.ownerPk(nk)
    val ekPub: ByteArray = X25519PrivateKeyParameters(ekSecret, 0).generatePublicKey().encoded
    val address: ShieldedAddress = ShieldedAddress(ownerPk, ekPub)

    /** The x25519 secret, for trial decryption only. */
    internal fun ek(): X25519PrivateKeyParameters = X25519PrivateKeyParameters(ekSecret, 0)

    private fun counted(label: String, nk: Fr, counter: Int): Fr {
        val c = java.nio.ByteBuffer.allocate(4).putInt(counter).array()
        return Fr.fromWideBytes(hmac(label.toByteArray() + nk.toBytes() + c))
    }

    // No self-mint counters (removed for chain fced976): every note the
    // chain mints to this wallet carries a blind ciphertext of fresh secrets
    // (PRIVACY_FORMATS.md section 1), found by trial decryption.

    /**
     * Groundworks position [counter]'s owner-tag salt: a position stores
     * H(TAG_OTAG, owner_pk, salt) and its owner proves it again to update,
     * unlock or vote it. Found again from the mnemonic by recomputing the
     * tags of counters 0 ... last+gap against the public positions.
     *
     *     salt = HMAC-SHA512("earth.privacy.v1", "otag-salt" || nk (32) || counter u32 BE) mod p
     */
    fun otagSalt(counter: Int): Fr = counted("otag-salt", nk, counter)

    fun ownerTag(counter: Int): Fr = Privacy.ownerTag(ownerPk, otagSalt(counter))

    companion object {
        const val PURPOSE = 2026
        private const val COIN = 118
        private val HMAC_KEY = "earth.privacy.v1".toByteArray()

        fun fromMnemonic(words: List<String>): PrivacyKeys =
            fromSeed(MnemonicCode.toSeed(words, ""))

        fun fromMnemonic(mnemonic: String): PrivacyKeys =
            fromMnemonic(mnemonic.trim().split(Regex("\\s+")))

        fun fromSeed(seed: ByteArray): PrivacyKeys {
            val master = HDKeyDerivation.createMasterPrivateKey(seed)
            fun hard(k: DeterministicKey, i: Int) = HDKeyDerivation.deriveChildKey(k, ChildNumber(i, true))
            val coin = hard(hard(master, PURPOSE), COIN)
            val keys = hard(coin, 0)
            fun secret(i: Int, label: String): ByteArray =
                hmac(label.toByteArray() + hard(keys, i).privKeyBytes)
            val ek = secret(2, "ek").copyOf(32)
            return PrivacyKeys(
                idSecret = Fr.fromWideBytes(secret(0, "id_secret")),
                nk = Fr.fromWideBytes(secret(1, "nk")),
                ekSecret = ek,
            )
        }

        internal fun hmac(data: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA512")
            mac.init(SecretKeySpec(HMAC_KEY, "HmacSHA512"))
            return mac.doFinal(data)
        }
    }
}
