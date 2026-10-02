package network.erth.wallet.privacy.sync

import network.erth.wallet.privacy.note.NotePlaintext
import network.erth.wallet.privacy.note.OwnedNote
import network.erth.wallet.privacy.note.OwnedStakeNote
import network.erth.wallet.privacy.zk.FileNodeStore
import network.erth.wallet.privacy.zk.Fr
import network.erth.wallet.privacy.zk.MemNodeStore
import network.erth.wallet.privacy.zk.MerkleTree
import network.erth.wallet.privacy.zk.NodeStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * This wallet's registration as the identity tree holds it. Everything here
 * is needed to prove membership; nothing is sent anywhere. The leaf is
 * H(TAG_LEAF, idc, dsc_key, country, activated_at).
 */
data class IdentityRecord(
    val leafIndex: Long,
    val dscKey: Fr,
    val country: Fr,
    val activatedAt: Long,
    /** The passport nullifier: public in the registration, the switch/expiry key. */
    val passportNullifier: String,
)

/**
 * What the wallet keeps between syncs: cursors into each indexer stream, its
 * own notes, its registration, and the automations' bookkeeping. Small; the
 * trees live beside it in per-level files.
 */
class PrivacyState {
    var chainId: String? = null
    var notesNext: Long = 0
    var notesHeight: Long = 0
    var nullifiersNext: Long = 0
    var identityNext: Long = 0
    var zeroedNext: Long = 0
    val notes: MutableList<OwnedNote> = ArrayList()
    var identity: IdentityRecord? = null
    /** UTC days a claim was broadcast for (so the automation does not repeat one). */
    val claimedDays: MutableSet<Long> = sortedSetOf()
    /** When the caretaker split was last cast (unix seconds), and the split (option -> percent). */
    var caretakerCastAt: Long = 0
    var caretakerSplit: Map<Long, Long> = emptyMap()
    /** The transparent address bound as this person's referrer ("" for none), and when (unix seconds). */
    var referrerAddress: String = ""
    var referrerBoundAt: Long = 0
    /** Unbond denoms whose claim the chain refused as not yet matured, to when the automation next tries. */
    val unbondRetryAt: MutableMap<String, Long> = sortedMapOf()
    /** Next unused Groundworks owner-tag counter (PrivacyKeys.otagSalt). */
    var nextOtagCounter: Int = 0
    /** Next unused self-mint counter (PrivacyKeys.mintSecrets). */
    var nextMintCounter: Int = 0
    /** Next unused stake self-mint counter (PrivacyKeys.stakeMintSecrets). */
    var nextStakeMintCounter: Int = 0
    /** The stake tree's stream cursors and this wallet's stake notes. */
    var stakeNext: Long = 0
    var stakeHeight: Long = 0
    var stakeNullifiersNext: Long = 0
    val stakeNotes: MutableList<OwnedStakeNote> = ArrayList()
    /** Every denom seen in a public amount: resolves the asset ids ciphertexts carry. */
    val denoms: MutableSet<String> = sortedSetOf()

    fun toJson(): JSONObject = JSONObject().apply {
        put("chain_id", chainId)
        put("notes_next", notesNext); put("notes_height", notesHeight)
        put("nullifiers_next", nullifiersNext); put("identity_next", identityNext); put("zeroed_next", zeroedNext)
        put("notes", JSONArray().apply { notes.forEach { put(noteJson(it)) } })
        identity?.let { id ->
            put("identity", JSONObject().put("leaf_index", id.leafIndex).put("dsc_key", id.dscKey.toHex())
                .put("country", id.country.toHex()).put("activated_at", id.activatedAt).put("passport_nullifier", id.passportNullifier))
        }
        put("claimed_days", JSONArray(claimedDays.toList()))
        put("caretaker_cast_at", caretakerCastAt)
        put("caretaker_split", JSONObject().apply { caretakerSplit.forEach { (k, v) -> put(k.toString(), v) } })
        put("referrer_address", referrerAddress); put("referrer_bound_at", referrerBoundAt)
        put("unbond_retry_at", JSONObject().apply { unbondRetryAt.forEach { (k, v) -> put(k, v) } })
        put("next_otag_counter", nextOtagCounter)
        put("next_mint_counter", nextMintCounter)
        put("next_stake_mint_counter", nextStakeMintCounter)
        put("stake_next", stakeNext); put("stake_height", stakeHeight); put("stake_nullifiers_next", stakeNullifiersNext)
        put("stake_notes", JSONArray().apply { stakeNotes.forEach { put(stakeJson(it)) } })
        put("denoms", JSONArray(denoms.toList()))
    }

    companion object {
        fun fromJson(j: JSONObject): PrivacyState = PrivacyState().apply {
            chainId = j.optString("chain_id").ifEmpty { null }
            notesNext = j.optLong("notes_next"); notesHeight = j.optLong("notes_height")
            nullifiersNext = j.optLong("nullifiers_next"); identityNext = j.optLong("identity_next"); zeroedNext = j.optLong("zeroed_next")
            j.optJSONArray("notes")?.let { a -> for (i in 0 until a.length()) notes.add(noteFromJson(a.getJSONObject(i))) }
            j.optJSONObject("identity")?.let {
                identity = IdentityRecord(it.getLong("leaf_index"), Fr.fromHex(it.getString("dsc_key")), Fr.fromHex(it.getString("country")),
                    it.getLong("activated_at"), it.optString("passport_nullifier"))
            }
            j.optJSONArray("claimed_days")?.let { a -> for (i in 0 until a.length()) claimedDays.add(a.getLong(i)) }
            caretakerCastAt = j.optLong("caretaker_cast_at")
            caretakerSplit = j.optJSONObject("caretaker_split")?.let { o -> o.keys().asSequence().associate { it.toLong() to o.getLong(it) } } ?: emptyMap()
            referrerAddress = j.optString("referrer_address"); referrerBoundAt = j.optLong("referrer_bound_at")
            j.optJSONObject("unbond_retry_at")?.let { o -> o.keys().forEach { unbondRetryAt[it] = o.getLong(it) } }
            nextOtagCounter = j.optInt("next_otag_counter")
            nextMintCounter = j.optInt("next_mint_counter")
            nextStakeMintCounter = j.optInt("next_stake_mint_counter")
            stakeNext = j.optLong("stake_next"); stakeHeight = j.optLong("stake_height"); stakeNullifiersNext = j.optLong("stake_nullifiers_next")
            j.optJSONArray("stake_notes")?.let { a -> for (i in 0 until a.length()) stakeNotes.add(stakeFromJson(a.getJSONObject(i))) }
            j.optJSONArray("denoms")?.let { a -> for (i in 0 until a.length()) denoms.add(a.getString(i)) }
        }

        private fun stakeJson(n: OwnedStakeNote) = JSONObject()
            .put("position", n.position).put("height", n.height).put("denom", n.denom).put("amount", n.amount)
            .put("rho", n.rho.toHex()).put("rcm", n.rcm.toHex()).put("cm", n.cm.toHex()).put("nf", n.nf.toHex())
            .put("spent_height", n.spentHeight ?: JSONObject.NULL)
            .put("pending_at", n.pendingAt ?: JSONObject.NULL)

        private fun stakeFromJson(o: JSONObject) = OwnedStakeNote(
            position = o.getLong("position"), height = o.getLong("height"), denom = o.getString("denom"), amount = o.getLong("amount"),
            rho = Fr.fromHex(o.getString("rho")), rcm = Fr.fromHex(o.getString("rcm")),
            cm = Fr.fromHex(o.getString("cm")), nf = Fr.fromHex(o.getString("nf")),
            spentHeight = if (o.isNull("spent_height")) null else o.getLong("spent_height"),
            pendingAt = if (o.isNull("pending_at")) null else o.getLong("pending_at"),
        )

        private fun noteJson(n: OwnedNote) = JSONObject()
            .put("position", n.position).put("height", n.height).put("cm", n.cm.toHex()).put("nf", n.nf.toHex())
            .put("denom", n.note.denom).put("value", n.note.value).put("rho", n.note.rho.toHex()).put("rcm", n.note.rcm.toHex())
            .put("memo", n.note.memo.joinToString("") { "%02x".format(it.toInt() and 0xff) })
            .put("spent_height", n.spentHeight ?: JSONObject.NULL)
            .put("pending_at", n.pendingAt ?: JSONObject.NULL)

        private fun noteFromJson(o: JSONObject): OwnedNote {
            val memo = o.optString("memo")
            return OwnedNote(
                position = o.getLong("position"), height = o.getLong("height"),
                note = NotePlaintext(o.getString("denom"), o.getLong("value"), Fr.fromHex(o.getString("rho")), Fr.fromHex(o.getString("rcm")),
                    ByteArray(memo.length / 2) { memo.substring(2 * it, 2 * it + 2).toInt(16).toByte() }),
                cm = Fr.fromHex(o.getString("cm")), nf = Fr.fromHex(o.getString("nf")),
                spentHeight = if (o.isNull("spent_height")) null else o.getLong("spent_height"),
                pendingAt = if (o.isNull("pending_at")) null else o.getLong("pending_at"),
            )
        }
    }
}

/**
 * The wallet's privacy data on disk (or in memory, for tests): [state] and the
 * trees. One directory per wallet, named by a hash of its owner key so
 * wallets in the same app never share notes. Three trees: the pool's notes,
 * the identity leaves and the stake notes.
 */
class PrivacyStore private constructor(private val dir: File?) {
    private val noteNodes: NodeStore = dir?.let { FileNodeStore(File(it, "notes")) } ?: MemNodeStore()
    private val identityNodes: NodeStore = dir?.let { FileNodeStore(File(it, "identity")) } ?: MemNodeStore()
    private val stakeNodes: NodeStore = dir?.let { FileNodeStore(File(it, "stake")) } ?: MemNodeStore()

    var state: PrivacyState = dir?.let { File(it, STATE).takeIf(File::exists) }
        ?.let { runCatching { PrivacyState.fromJson(JSONObject(it.readText())) }.getOrNull() } ?: PrivacyState()
        private set

    val noteTree = MerkleTree(noteNodes, state.notesNext)
    val identityTree = MerkleTree(identityNodes, state.identityNext)
    val stakeTree = MerkleTree(stakeNodes, state.stakeNext)

    /** Persists state after the trees, so a crash between the two leaves state behind (and resyncs) rather than ahead. */
    @Synchronized
    fun save() {
        noteTree.flush(); identityTree.flush(); stakeTree.flush()
        val d = dir ?: return
        val tmp = File(d, "$STATE.tmp")
        tmp.writeText(state.toJson().toString())
        tmp.renameTo(File(d, STATE))
    }

    /**
     * Forgets the synced data, keeping the key counters, the registration's
     * leaf and what the wallet itself cast (claims, caretaker split, referrer): a fresh chain, or an
     * inconsistent sync.
     */
    @Synchronized
    fun reset(chainId: String?) {
        noteTree.clear()
        identityTree.clear()
        stakeTree.clear()
        val old = state
        state = PrivacyState().apply {
            this.chainId = chainId
            nextOtagCounter = old.nextOtagCounter
            nextMintCounter = old.nextMintCounter
            nextStakeMintCounter = old.nextStakeMintCounter
            if (old.chainId == chainId) {
                // The leaf index cannot be found again without the registration tx.
                identity = old.identity
                claimedDays.addAll(old.claimedDays)
                caretakerCastAt = old.caretakerCastAt; caretakerSplit = old.caretakerSplit
                referrerAddress = old.referrerAddress; referrerBoundAt = old.referrerBoundAt
            }
        }
        save()
    }

    companion object {
        private const val STATE = "state.json"

        fun memory(): PrivacyStore = PrivacyStore(null)

        fun open(root: File, walletId: String): PrivacyStore = PrivacyStore(File(root, "privacy/$walletId").apply { mkdirs() })
    }
}
