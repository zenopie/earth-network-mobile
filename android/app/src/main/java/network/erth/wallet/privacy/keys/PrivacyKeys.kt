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
 *
 * Identity generations: the chain accepts each idc once (personhood 1130),
 * so every registration after the first (a re-entry after a lapse, a fresh
 * identity) needs a new identity secret. Generation g >= 1 appends g to the
 * identity child's HMAC input; generation 0 is the identity above, unchanged:
 *
 *     id_secret_0 = HMAC-SHA512("earth.privacy.v1", "id_secret" || k)              mod p
 *     id_secret_g = HMAC-SHA512("earth.privacy.v1", "id_secret" || k || u32 BE g)  mod p
 *
 * One phrase holds every generation; nk and ek (notes, the shielded
 * address) are the same for all of them.
 */
class PrivacyKeys private constructor(
    /** The identity child's 32-byte private key (m/2026'/118'/0'/0'), from which every generation's secret comes. */
    private val idNode: ByteArray,
    val nk: Fr,
    private val ekSecret: ByteArray,
) {
    /** Generation 0's identity secret and commitment (the first identity). */
    val idSecret: Fr = idSecret(0)
    val idc: Fr = Privacy.idc(idSecret)

    private val idcs = HashMap<Int, Fr>().apply { put(0, idc) }

    /** Generation [generation]'s identity secret (see the class comment). */
    fun idSecret(generation: Int): Fr {
        require(generation in 0..MAX_GENERATION) { "identity generation $generation is out of range" }
        val c = if (generation == 0) ByteArray(0) else java.nio.ByteBuffer.allocate(4).putInt(generation).array()
        return Fr.fromWideBytes(hmac(ID_LABEL + idNode + c))
    }

    /** Generation [generation]'s idc = H(TAG_ID, id_secret_g). */
    fun idc(generation: Int): Fr = synchronized(idcs) { idcs.getOrPut(generation) { Privacy.idc(idSecret(generation)) } }
    val ownerPk: Fr = Privacy.ownerPk(nk)
    val ekPub: ByteArray = X25519PrivateKeyParameters(ekSecret, 0).generatePublicKey().encoded
    val address: ShieldedAddress = ShieldedAddress(ownerPk, ekPub)

    /** The x25519 secret, for trial decryption only. */
    internal fun ek(): X25519PrivateKeyParameters = X25519PrivateKeyParameters(ekSecret, 0)

    private fun counted(label: String, nk: Fr, counter: Int): Fr {
        val c = java.nio.ByteBuffer.allocate(4).putInt(counter).array()
        return Fr.fromWideBytes(hmac(label.toByteArray() + nk.toBytes() + c))
    }

    // No counter is derived for a note the chain mints to this wallet: it
    // carries a v2 ciphertext of fresh secrets (PRIVACY_FORMATS.md §5), found
    // by trial decryption.

    companion object {
        const val PURPOSE = 2026
        private const val COIN = 118
        private val ID_LABEL = "id_secret".toByteArray()

        /** The highest identity generation a wallet derives: far past any lifetime of yearly registrations. */
        const val MAX_GENERATION = 10_000
        private val HMAC_KEY = "earth.privacy.v1".toByteArray()

        fun fromMnemonic(words: List<String>): PrivacyKeys {
            val seed = MnemonicCode.toSeed(words, "")
            // The seed is zeroed once the keys are derived.
            return try { fromSeed(seed) } finally { seed.fill(0) }
        }

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
                idNode = hard(keys, 0).privKeyBytes,
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
