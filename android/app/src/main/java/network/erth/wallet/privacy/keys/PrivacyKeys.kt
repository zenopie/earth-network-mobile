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
 *     m/2026'/118'/1'/i'   position key i (one-time secp256k1, Groundworks)
 *
 * A child's 32-byte private key k becomes a secret by
 * HMAC-SHA512(key = "earth.privacy.v1", label || k): reduced mod p for the
 * field secrets (64 bytes, so uniform), its first 32 bytes for ek.
 */
class PrivacyKeys private constructor(
    val idSecret: Fr,
    val nk: Fr,
    private val ekSecret: ByteArray,
    private val positionRoot: DeterministicKey,
) {
    val idc: Fr = Privacy.idc(idSecret)
    val ownerPk: Fr = Privacy.ownerPk(nk)
    val ekPub: ByteArray = X25519PrivateKeyParameters(ekSecret, 0).generatePublicKey().encoded
    val address: ShieldedAddress = ShieldedAddress(ownerPk, ekPub)

    /** The x25519 secret, for trial decryption only. */
    internal fun ek(): X25519PrivateKeyParameters = X25519PrivateKeyParameters(ekSecret, 0)

    /**
     * rho and rcm of self-mint [counter]: a note the chain mints to us at a
     * value we cannot know when we name its pc (a registration reward, derth
     * at the live rate, an unbonding payout, a gas grant). Its ciphertext
     * cannot be written (the canonical format binds cm, which binds the value),
     * so these notes are found instead by their public mint amount and a pc
     * the wallet can rederive from the mnemonic alone (WalletSync):
     *
     *     rho = HMAC-SHA512("earth.privacy.v1", "mint-rho" || nk (32) || counter u32 BE) mod p
     *     rcm = HMAC-SHA512("earth.privacy.v1", "mint-rcm" || nk (32) || counter u32 BE) mod p
     */
    fun mintSecrets(counter: Int): Pair<Fr, Fr> {
        val c = java.nio.ByteBuffer.allocate(4).putInt(counter).array()
        fun d(label: String) = Fr.fromWideBytes(hmac(label.toByteArray() + nk.toBytes() + c))
        return d("mint-rho") to d("mint-rcm")
    }

    /** pc of self-mint [counter]. */
    fun mintPc(counter: Int): Fr = mintSecrets(counter).let { (rho, rcm) -> Privacy.pc(ownerPk, rho, rcm) }

    /** Groundworks position key [index]: a 32-byte secp256k1 private key. */
    fun positionKey(index: Int): org.bitcoinj.core.ECKey =
        org.bitcoinj.core.ECKey.fromPrivate(
            HDKeyDerivation.deriveChildKey(positionRoot, ChildNumber(index, true)).privKey,
        )

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
                positionRoot = hard(coin, 1),
            )
        }

        internal fun hmac(data: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA512")
            mac.init(SecretKeySpec(HMAC_KEY, "HmacSHA512"))
            return mac.doFinal(data)
        }
    }
}
