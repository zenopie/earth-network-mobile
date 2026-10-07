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
    /** That tx's hash: released only once the chain says it is missing or failed (null: a mark from an older version). */
    val pendingTx: String? = null,
    /** How sync found it (null: synced before the wallet kept it): what a received row calls it. */
    val origin: NoteOrigin? = null,
) {
    val unspent: Boolean get() = spentHeight == null
}

/**
 * A pool note's format as found (PRIVACY_FORMATS §5): a bundle output (v1,
 * [OUTPUT]), a note the chain minted to a hidden owner (v2, [BLIND]), or an
 * open mint, the referral note ([OPEN]).
 */
enum class NoteOrigin(val tag: String) {
    OUTPUT("v1"), BLIND("v2"), OPEN("open");

    companion object {
        fun of(tag: String?): NoteOrigin? = entries.firstOrNull { it.tag == tag }
    }
}

/**
 * Asset id -> denom, for the ids note ciphertexts carry. Seeded with the fee
 * and personhood denoms. It learns only denoms the wallet
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

    fun resolve(asset: Fr): String = byId[asset] ?: (NotePlaintext.UNRESOLVED_PREFIX + asset.toHex())
}

/**
 * Which denoms the wallet accepts from outside: the SDK's
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
 * A stake note's slash label (ORCHARD_DESIGN 8.7): the note holds [exposed]
 * derth a private redelegation credited, the move [moveKey] (its credit
 * nullifier) named at [moveTime] (unix seconds). Until the move's window
 * closes (moveTime + the chain's label window) a slash of the move's source
 * may still cut it, and it cannot leave the note; after, any lane-A proof
 * clears it at what the slash debt tree says it is worth.
 */
data class StakeLabel(val moveKey: Fr, val moveTime: Long, val exposed: Long) {
    init {
        require(!moveKey.isZero && moveTime > 0 && exposed > 0) { "a label names its move, time and a positive exposure" }
    }

    val hash: Fr get() = Privacy.stakeLabel(moveKey, moveTime, exposed)

    companion object {
        /** The label field of a stake commitment: 0 for none. */
        fun hash(l: StakeLabel?): Fr = l?.hash ?: Fr.ZERO
    }
}

/**
 * A stake note the wallet owns (x/shieldedstaking's stake tree): delegated
 * stake (derth/<valoper>). One per validator as a rule (every delegation,
 * unlock and redelegation merges into it); a second appears only beside a
 * labelled note or from another device. Owner-locked: it can be merged,
 * undelegated, redelegated, voted or locked by its owner, never sent.
 *
 *     spc = H(TAG_SPC, owner_pk, rho, rcm)    cm = H(TAG_STAKE, AssetID(denom), amount, spc, label)
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
    /** The redelegation exposure it holds, null for an unlabelled note. */
    val label: StakeLabel? = null,
) {
    val unspent: Boolean get() = spentHeight == null
    val spendable: Boolean get() = unspent && pendingAt == null && amount > 0
}
