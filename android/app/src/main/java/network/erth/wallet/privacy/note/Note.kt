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
    /** That tx's timeout_height: the note is released only once the chain is past it (null: a pre-timeout mark). */
    val pendingUntil: Long? = null,
    /** That tx's hash: released only once the chain says it is missing or failed (audit 4; null: a mark from before). */
    val pendingTx: String? = null,
) {
    val unspent: Boolean get() = spentHeight == null
}

/**
 * Asset id -> denom, for the ids note ciphertexts carry. Seeded with the fee
 * and personhood denoms. Audit 6 (M2, M3): it learns only denoms the wallet
 * has reason to trust (a note of its own whose cm the denom reproduces, or
 * the chain's asset list, each entry checked against its id), never a public
 * amount an indexer merely serves; only well-formed denoms ([Denoms.valid]),
 * and at most [Denoms.MAX] of them. Built once per sync and grown as it goes:
 * every learn costs one hash, every resolve none.
 */
class AssetDenoms(known: Collection<String> = emptyList()) {
    private val byId = HashMap<Fr, String>()

    init { (listOf("uerth", "uanml") + known).forEach(::learn) }

    /** Whether [denom] is new here (false: invalid, known already, or the set is full). */
    fun learn(denom: String): Boolean {
        if (!Denoms.valid(denom) || byId.size >= Denoms.MAX) return false
        return byId.put(Privacy.assetId(denom), denom) == null
    }

    /** Learn [denom] under an id the chain stated for it: only if the id is the denom's own. */
    fun learn(denom: String, id: Fr): Boolean {
        if (!Denoms.valid(denom) || byId.size >= Denoms.MAX || byId[id] != null) return false
        if (Privacy.assetId(denom) != id) return false
        byId[id] = denom
        return true
    }

    fun denoms(): Set<String> = byId.values.toSet()

    fun resolve(asset: Fr): String = byId[asset] ?: (NotePlaintext.UNRESOLVED_PREFIX + asset.toHex())
}

/**
 * Which denoms the wallet accepts from outside (audit 6, M2, M3): the SDK's
 * own denom rule (`[a-zA-Z][a-zA-Z0-9/:._-]{2,127}`), and never the wallet's
 * internal "asset/<hex>" name for an id it cannot resolve: an indexer that
 * served one would relabel a note so no planner picks it.
 */
object Denoms {
    /** The most denoms a wallet keeps (learned and persisted). */
    const val MAX = 4096

    private val SDK = Regex("^[a-zA-Z][a-zA-Z0-9/:._-]{2,127}$")

    fun valid(denom: String): Boolean = SDK.matches(denom) && !denom.startsWith(NotePlaintext.UNRESOLVED_PREFIX)
}

/**
 * A stake note the wallet owns (x/shieldedstaking's stake tree): delegated
 * stake (derth/<valoper>).
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
    /** The spending tx's timeout_height (see OwnedNote.pendingUntil). */
    val pendingUntil: Long? = null,
    /** The spending tx's hash (see OwnedNote.pendingTx). */
    val pendingTx: String? = null,
) {
    val unspent: Boolean get() = spentHeight == null
    val spendable: Boolean get() = unspent && pendingAt == null && amount > 0
}
