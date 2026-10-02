package network.erth.wallet.privacy.note

import network.erth.wallet.privacy.zk.Fr
import network.erth.wallet.privacy.zk.Privacy
import java.security.SecureRandom

/**
 * A note's opening: what its owner needs to prove it. The ciphertext
 * carries the asset id, not the denom (NoteCipher); the wallet resolves it to
 * a denom from the denoms it has seen ([AssetDenoms]), and an id it cannot
 * resolve is kept as "asset/<hex>", spendable inside the pool all the same.
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
    val asset: Fr get() = assetOf(denom)

    fun pc(ownerPk: Fr): Fr = Privacy.pc(ownerPk, rho, rcm)

    fun cm(ownerPk: Fr): Fr = Privacy.cm(asset, value, pc(ownerPk))

    override fun equals(other: Any?): Boolean = other is NotePlaintext && denom == other.denom &&
        value == other.value && rho == other.rho && rcm == other.rcm && memo.contentEquals(other.memo)

    override fun hashCode(): Int = rho.hashCode()

    companion object {
        private val rng = SecureRandom()

        const val UNRESOLVED_PREFIX = "asset/"

        fun assetOf(denom: String): Fr =
            if (denom.startsWith(UNRESOLVED_PREFIX)) Fr.fromHex(denom.removePrefix(UNRESOLVED_PREFIX)) else Privacy.assetId(denom)

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
    /** When a tx spending this note was broadcast (unix seconds), until sync sees its nullifier. */
    val pendingAt: Long? = null,
) {
    val unspent: Boolean get() = spentHeight == null
}

/**
 * Asset id -> denom, for the ids note ciphertexts carry. Seeded with the fee
 * and personhood denoms; every public amount the indexer serves (shields and
 * mints, which name their denom) teaches it more, and the first note of any
 * derth/ or unbond/ denom is always a public mint, so a wallet learns a denom
 * before it can be sent one privately.
 */
class AssetDenoms(known: Collection<String> = emptyList()) {
    private val byId = HashMap<Fr, String>()

    init { (listOf("uerth", "uanml") + known).forEach(::learn) }

    fun learn(denom: String) {
        if (denom.isNotEmpty() && !denom.startsWith(NotePlaintext.UNRESOLVED_PREFIX)) byId[Privacy.assetId(denom)] = denom
    }

    fun denoms(): Set<String> = byId.values.toSet()

    fun resolve(asset: Fr): String = byId[asset] ?: (NotePlaintext.UNRESOLVED_PREFIX + asset.toHex())
}

/**
 * A stake note the wallet owns (x/shieldedstaking's stake tree): delegated
 * stake (derth/<valoper>) or an unbonding claim (unbond/<valoper>/<epoch>).
 * Owner-locked: it can be merged, split, undelegated, voted or locked by its
 * owner, never sent.
 *
 *     spc = H(TAG_SPC, owner_pk, rho, rcm)    cm = H(TAG_STAKE, AssetID(denom), amount, spc)
 *     nf  = H(TAG_SNF, nk, rho, position)
 */
data class OwnedStakeNote(
    val position: Long,
    val height: Long,
    val denom: String,
    val amount: Long,
    val rho: Fr,
    val rcm: Fr,
    val cm: Fr,
    val nf: Fr,
    val spentHeight: Long? = null,
    val pendingAt: Long? = null,
) {
    val unspent: Boolean get() = spentHeight == null
    val spendable: Boolean get() = unspent && pendingAt == null && amount > 0
}
