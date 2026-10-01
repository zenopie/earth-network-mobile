package network.erth.wallet.privacy.note

import network.erth.wallet.privacy.zk.Fr
import network.erth.wallet.privacy.zk.Privacy
import java.security.SecureRandom

/**
 * A note's opening: what its owner needs to prove it, and what its
 * ciphertext carries. denom is carried as a string (the circuit needs only
 * AssetID(denom), but the wallet needs the denom to show it and to name it
 * when value leaves the pool).
 *
 *     pc = H(TAG_PC, owner_pk, rho, rcm)     cm = H(TAG_CM, AssetID(denom), value, pc)
 */
data class NotePlaintext(
    val denom: String,
    val value: Long,
    val rho: Fr,
    val rcm: Fr,
    val memo: ByteArray = ByteArray(0),
) {
    val asset: Fr get() = Privacy.assetId(denom)

    fun pc(ownerPk: Fr): Fr = Privacy.pc(ownerPk, rho, rcm)

    fun cm(ownerPk: Fr): Fr = Privacy.cm(asset, value, pc(ownerPk))

    override fun equals(other: Any?): Boolean = other is NotePlaintext && denom == other.denom &&
        value == other.value && rho == other.rho && rcm == other.rcm && memo.contentEquals(other.memo)

    override fun hashCode(): Int = rho.hashCode()

    companion object {
        private val rng = SecureRandom()

        fun randomField(): Fr = ByteArray(64).also { rng.nextBytes(it) }.let(Fr::fromWideBytes)

        /** A fresh note of [value] [denom]: random rho and rcm. */
        fun fresh(denom: String, value: Long, memo: ByteArray = ByteArray(0)): NotePlaintext =
            NotePlaintext(denom, value, randomField(), randomField(), memo)
    }
}

/** A note the wallet owns: its opening, where it sits in the tree, and its nullifier. */
data class OwnedNote(
    val position: Long,
    val height: Long,
    val note: NotePlaintext,
    val cm: Fr,
    val nf: Fr,
    val spentHeight: Long? = null,
) {
    val unspent: Boolean get() = spentHeight == null
}
