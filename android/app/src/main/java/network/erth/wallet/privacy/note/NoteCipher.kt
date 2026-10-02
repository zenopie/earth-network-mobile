package network.erth.wallet.privacy.note

import network.erth.wallet.privacy.keys.PrivacyKeys
import network.erth.wallet.privacy.keys.ShieldedAddress
import network.erth.wallet.privacy.zk.Fr
import network.erth.wallet.privacy.zk.Privacy
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
 * Note ciphertexts, in the canonical format shared with chain zk/privacy and
 * the web app ("earth note v1"). The chain itself treats them as opaque bytes
 * (at most 1024, bound into the signal so a relay cannot swap them).
 *
 *     ct    = epk (32) || ChaCha20-Poly1305(key, nonce = 0^12, aad = empty, pt)      217 bytes
 *     key   = HKDF-SHA256(ikm = X25519(esk, ek_pub), salt = "earth.note.v1", info = epk || cm)
 *     pt    = 0x01 || asset_id (32) || value (u64 BE) || rho (32) || rcm (32) || memo (64, zero padded)
 *
 * Every key is single-use (a fresh esk per note), so the zero nonce never
 * repeats under one key. cm in the info binds the ciphertext to its note's
 * commitment, and the recipient recomputes cm from the decrypted fields under
 * its own owner key before accepting. A dummy output (value 0) is encrypted
 * to a throwaway key: same length, openable by no one.
 *
 * "earth note v2" (value-blind), for a note whose asset and value the chain
 * decides when the msg runs (a swap's output, an LP withdrawal priced at
 * maturity) and publishes with the note (the mint event's amount):
 *
 *     ct    = epk (32) || ChaCha20-Poly1305(key, nonce = 0^12, aad = empty, pt)      177 bytes
 *     key   = HKDF-SHA256(ikm = X25519(esk, ek_pub), salt = "earth.note.v2", info = epk)
 *     pt    = 0x02 || rho (32) || rcm (32) || memo (64, zero padded)
 *
 * The length alone tells the two apart. info cannot bind cm (the sender does
 * not know it), so the binding is the recipient's check that
 * CM(asset, value, PC(owner_pk, rho, rcm)) is the note's cm, with the asset
 * and value the chain published for that position.
 *
 * "earth stake note v1", for a stake note a stake proof creates (a restake's
 * outputs, an undelegation's or a lock's change). Stake notes are
 * owner-locked, so it is always encrypted to the wallet's own address, for
 * its other devices and for recovery from the mnemonic:
 *
 *     ct    = epk (32) || ChaCha20-Poly1305(key, nonce = 0^12, aad = empty, pt)      153 bytes
 *     key   = HKDF-SHA256(ikm = X25519(esk, ek_pub), salt = "earth.stake.v1", info = epk || cm)
 *     pt    = 0x03 || asset_id (32) || amount (u64 BE) || rho (32) || rcm (32)
 *
 * accepted only if StakeCM(asset, amount, StakePC(owner_pk, rho, rcm)) is
 * the note's cm.
 */
object NoteCipher {
    const val VERSION: Byte = 1
    const val MEMO_BYTES = 64
    const val PLAINTEXT_BYTES = 1 + 32 + 8 + 32 + 32 + MEMO_BYTES
    const val CIPHERTEXT_BYTES = 32 + PLAINTEXT_BYTES + 16
    private val SALT = "earth.note.v1".toByteArray()
    const val BLIND_VERSION: Byte = 2
    const val BLIND_PLAINTEXT_BYTES = 1 + 32 + 32 + MEMO_BYTES
    const val BLIND_CIPHERTEXT_BYTES = 32 + BLIND_PLAINTEXT_BYTES + 16
    private val BLIND_SALT = "earth.note.v2".toByteArray()
    const val STAKE_VERSION: Byte = 3
    const val STAKE_PLAINTEXT_BYTES = 1 + 32 + 8 + 32 + 32
    const val STAKE_CIPHERTEXT_BYTES = 32 + STAKE_PLAINTEXT_BYTES + 16
    private val STAKE_SALT = "earth.stake.v1".toByteArray()
    private val rng = SecureRandom()

    /** Encrypts [note] to [to]; cm is the note's commitment under to's owner key. */
    fun encrypt(note: NotePlaintext, to: ShieldedAddress): ByteArray =
        encrypt(note, to.ekPub, note.cm(to.ownerPk))

    fun encrypt(note: NotePlaintext, ekPub: ByteArray, cm: Fr): ByteArray =
        seal(X25519PrivateKeyParameters(rng), ekPub, cm, plaintext(note))

    /** Deterministic encryption under a given ephemeral secret: for golden vectors only. */
    internal fun encryptWith(esk: ByteArray, note: NotePlaintext, to: ShieldedAddress): ByteArray =
        seal(X25519PrivateKeyParameters(esk, 0), to.ekPub, note.cm(to.ownerPk), plaintext(note))

    private fun seal(esk: X25519PrivateKeyParameters, ekPub: ByteArray, cm: Fr, pt: ByteArray): ByteArray {
        val epk = esk.generatePublicKey().encoded
        val shared = ByteArray(32)
        X25519Agreement().apply { init(esk) }.calculateAgreement(X25519PublicKeyParameters(ekPub, 0), shared, 0)
        return epk + aead(true, kdf(shared, epk, cm), pt)
    }

    /** A ciphertext for a dummy output: well formed, and openable by no one. */
    fun dummy(): ByteArray {
        val throwaway = X25519PrivateKeyParameters(rng).generatePublicKey().encoded
        return encrypt(NotePlaintext.fresh("uerth", 0), throwaway, NotePlaintext.randomField())
    }

    /**
     * The note if [ct] opens with our ek for commitment [cm] and its fields
     * recompute [cm] under our owner key; else null. Never throws.
     */
    fun tryDecrypt(ct: ByteArray, cm: Fr, keys: PrivacyKeys, denoms: AssetDenoms = AssetDenoms()): NotePlaintext? {
        if (ct.size != CIPHERTEXT_BYTES) return null
        return try {
            val epk = ct.copyOf(32)
            val shared = ByteArray(32)
            X25519Agreement().apply { init(keys.ek()) }.calculateAgreement(X25519PublicKeyParameters(epk, 0), shared, 0)
            val pt = aead(false, kdf(shared, epk, cm), ct.copyOfRange(32, ct.size))
            val (asset, note) = parse(pt, denoms) ?: return null
            if (Privacy.cm(asset, note.value, note.pc(keys.ownerPk)) != cm) return null
            note
        } catch (e: Exception) {
            // Not ours (the tag fails), a low-order epk, or a malformed body.
            null
        }
    }

    private fun kdf(shared: ByteArray, epk: ByteArray, cm: Fr): ByteArray = hkdf(shared, SALT, epk + cm.toBytes())

    private fun hkdf(shared: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
        require(!shared.all { it.toInt() == 0 }) { "low-order point" }
        val out = ByteArray(32)
        HKDFBytesGenerator(SHA256Digest()).apply { init(HKDFParameters(shared, salt, info)) }.generateBytes(out, 0, 32)
        return out
    }

    // ---- v2 (value-blind) ----

    /** Encrypts [note]'s secrets (rho, rcm, memo; not its asset or value) to [to]. */
    fun encryptBlind(note: NotePlaintext, to: ShieldedAddress): ByteArray =
        sealBlind(X25519PrivateKeyParameters(rng), to.ekPub, blindPlaintext(note))

    /** Deterministic v2 encryption: for golden vectors only. */
    internal fun encryptBlindWith(esk: ByteArray, note: NotePlaintext, ekPub: ByteArray): ByteArray =
        sealBlind(X25519PrivateKeyParameters(esk, 0), ekPub, blindPlaintext(note))

    private fun sealBlind(esk: X25519PrivateKeyParameters, ekPub: ByteArray, pt: ByteArray): ByteArray {
        val epk = esk.generatePublicKey().encoded
        val shared = ByteArray(32)
        X25519Agreement().apply { init(esk) }.calculateAgreement(X25519PublicKeyParameters(ekPub, 0), shared, 0)
        return epk + aead(true, hkdf(shared, BLIND_SALT, epk), pt)
    }

    private fun blindPlaintext(n: NotePlaintext): ByteArray {
        require(n.memo.size <= MEMO_BYTES) { "memo exceeds $MEMO_BYTES bytes" }
        return ByteBuffer.allocate(BLIND_PLAINTEXT_BYTES)
            .put(BLIND_VERSION).put(n.rho.toBytes()).put(n.rcm.toBytes()).put(n.memo.copyOf(MEMO_BYTES)).array()
    }

    /**
     * A v2 note: opens [ct] with our ek, and accepts it only if the secrets
     * with the chain-published [denom] and [value] recompute [cm] under our
     * owner key. Null otherwise; never throws.
     */
    fun tryDecryptBlind(ct: ByteArray, cm: Fr, denom: String, value: Long, keys: PrivacyKeys): NotePlaintext? =
        tryDecryptBlind(ct, cm, denom, value, keys.ek(), keys.ownerPk)

    internal fun tryDecryptBlind(ct: ByteArray, cm: Fr, denom: String, value: Long, ek: X25519PrivateKeyParameters, ownerPk: Fr): NotePlaintext? {
        if (ct.size != BLIND_CIPHERTEXT_BYTES) return null
        return try {
            val epk = ct.copyOf(32)
            val shared = ByteArray(32)
            X25519Agreement().apply { init(ek) }.calculateAgreement(X25519PublicKeyParameters(epk, 0), shared, 0)
            val pt = aead(false, hkdf(shared, BLIND_SALT, epk), ct.copyOfRange(32, ct.size))
            if (pt.size != BLIND_PLAINTEXT_BYTES || pt[0] != BLIND_VERSION) return null
            val rho = Fr.fromBytes(pt.copyOfRange(1, 33))
            val rcm = Fr.fromBytes(pt.copyOfRange(33, 65))
            val memo = pt.copyOfRange(65, pt.size)
            val n = NotePlaintext(denom, value, rho, rcm, memo.copyOf(memo.indexOfLast { it.toInt() != 0 } + 1))
            if (n.cm(ownerPk) != cm) null else n
        } catch (e: Exception) {
            null
        }
    }

    // ---- stake notes ----

    /** A stake note's opening, as its stake ciphertext carries it. */
    data class StakeOpening(val asset: Fr, val amount: Long, val rho: Fr, val rcm: Fr)

    /** Encrypts a stake note of [ownerPk] to [ekPub] (the wallet's own). */
    fun encryptStake(o: StakeOpening, ekPub: ByteArray, cm: Fr): ByteArray =
        sealStake(X25519PrivateKeyParameters(rng), ekPub, cm, stakePlaintext(o))

    /** Deterministic stake encryption: for golden vectors only. */
    internal fun encryptStakeWith(esk: ByteArray, o: StakeOpening, ekPub: ByteArray, cm: Fr): ByteArray =
        sealStake(X25519PrivateKeyParameters(esk, 0), ekPub, cm, stakePlaintext(o))

    private fun sealStake(esk: X25519PrivateKeyParameters, ekPub: ByteArray, cm: Fr, pt: ByteArray): ByteArray {
        val epk = esk.generatePublicKey().encoded
        val shared = ByteArray(32)
        X25519Agreement().apply { init(esk) }.calculateAgreement(X25519PublicKeyParameters(ekPub, 0), shared, 0)
        return epk + aead(true, hkdf(shared, STAKE_SALT, epk + cm.toBytes()), pt)
    }

    private fun stakePlaintext(o: StakeOpening): ByteArray = ByteBuffer.allocate(STAKE_PLAINTEXT_BYTES)
        .put(STAKE_VERSION).put(o.asset.toBytes()).putLong(o.amount).put(o.rho.toBytes()).put(o.rcm.toBytes()).array()

    /** The stake note if [ct] opens with our ek for [cm] and recomputes it under our owner key; else null. */
    fun tryDecryptStake(ct: ByteArray, cm: Fr, keys: PrivacyKeys): StakeOpening? {
        if (ct.size != STAKE_CIPHERTEXT_BYTES) return null
        return try {
            val epk = ct.copyOf(32)
            val shared = ByteArray(32)
            X25519Agreement().apply { init(keys.ek()) }.calculateAgreement(X25519PublicKeyParameters(epk, 0), shared, 0)
            val pt = aead(false, hkdf(shared, STAKE_SALT, epk + cm.toBytes()), ct.copyOfRange(32, ct.size))
            if (pt.size != STAKE_PLAINTEXT_BYTES || pt[0] != STAKE_VERSION) return null
            val b = ByteBuffer.wrap(pt, 1, STAKE_PLAINTEXT_BYTES - 1)
            val asset = Fr.fromBytes(ByteArray(32).also { b.get(it) })
            val amount = b.long
            val rho = Fr.fromBytes(ByteArray(32).also { b.get(it) })
            val rcm = Fr.fromBytes(ByteArray(32).also { b.get(it) })
            if (Privacy.stakeCm(asset, amount, Privacy.stakePc(keys.ownerPk, rho, rcm)) != cm) null
            else StakeOpening(asset, amount, rho, rcm)
        } catch (e: Exception) {
            null
        }
    }

    private fun aead(encrypt: Boolean, key: ByteArray, input: ByteArray): ByteArray {
        val c = ChaCha20Poly1305()
        c.init(encrypt, AEADParameters(KeyParameter(key), 128, ByteArray(12), ByteArray(0)))
        val out = ByteArray(c.getOutputSize(input.size))
        val n = c.processBytes(input, 0, input.size, out, 0)
        c.doFinal(out, n)
        return out
    }

    internal fun plaintext(n: NotePlaintext): ByteArray {
        require(n.memo.size <= MEMO_BYTES) { "memo exceeds $MEMO_BYTES bytes" }
        return ByteBuffer.allocate(PLAINTEXT_BYTES)
            .put(VERSION).put(n.asset.toBytes()).putLong(n.value).put(n.rho.toBytes()).put(n.rcm.toBytes())
            .put(n.memo.copyOf(MEMO_BYTES))
            .array()
    }

    /** (asset id, note). The memo's trailing zero padding is dropped. */
    internal fun parse(pt: ByteArray, denoms: AssetDenoms): Pair<Fr, NotePlaintext>? {
        if (pt.size != PLAINTEXT_BYTES || pt[0] != VERSION) return null
        val b = ByteBuffer.wrap(pt, 1, PLAINTEXT_BYTES - 1)
        val asset = Fr.fromBytes(ByteArray(32).also { b.get(it) })
        val value = b.long
        val rho = Fr.fromBytes(ByteArray(32).also { b.get(it) })
        val rcm = Fr.fromBytes(ByteArray(32).also { b.get(it) })
        val memo = ByteArray(MEMO_BYTES).also { b.get(it) }
        val end = memo.indexOfLast { it.toInt() != 0 } + 1
        return asset to NotePlaintext(denoms.resolve(asset), value, rho, rcm, memo.copyOf(end))
    }
}
