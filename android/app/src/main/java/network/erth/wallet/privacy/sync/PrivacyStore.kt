package network.erth.wallet.privacy.sync

import network.erth.wallet.privacy.note.NotePlaintext
import network.erth.wallet.privacy.note.OwnedNote
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
    /** Next unused position-key index. */
    var nextPositionKey: Int = 0
    /** Next unused self-mint counter (PrivacyKeys.mintSecrets). */
    var nextMintCounter: Int = 0
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
        put("next_position_key", nextPositionKey)
        put("next_mint_counter", nextMintCounter)
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
            nextPositionKey = j.optInt("next_position_key")
            nextMintCounter = j.optInt("next_mint_counter")
            j.optJSONArray("denoms")?.let { a -> for (i in 0 until a.length()) denoms.add(a.getString(i)) }
        }

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
 * two trees. One directory per wallet, named by a hash of its owner key so
 * wallets in the same app never share notes.
 */
class PrivacyStore private constructor(private val dir: File?) {
    private val noteNodes: NodeStore = dir?.let { FileNodeStore(File(it, "notes")) } ?: MemNodeStore()
    private val identityNodes: NodeStore = dir?.let { FileNodeStore(File(it, "identity")) } ?: MemNodeStore()

    var state: PrivacyState = dir?.let { File(it, STATE).takeIf(File::exists) }
        ?.let { runCatching { PrivacyState.fromJson(JSONObject(it.readText())) }.getOrNull() } ?: PrivacyState()
        private set

    val noteTree = MerkleTree(noteNodes, state.notesNext)
    val identityTree = MerkleTree(identityNodes, state.identityNext)

    /** Persists state after the trees, so a crash between the two leaves state behind (and resyncs) rather than ahead. */
    @Synchronized
    fun save() {
        noteTree.flush(); identityTree.flush()
        val d = dir ?: return
        val tmp = File(d, "$STATE.tmp")
        tmp.writeText(state.toJson().toString())
        tmp.renameTo(File(d, STATE))
    }

    /** Forgets everything but the position-key counter: a fresh chain, or an inconsistent sync. */
    @Synchronized
    fun reset(chainId: String?) {
        noteTree.clear()
        identityTree.clear()
        val old = state
        state = PrivacyState().apply {
            this.chainId = chainId
            nextPositionKey = old.nextPositionKey
            nextMintCounter = old.nextMintCounter
        }
        save()
    }

    companion object {
        private const val STATE = "state.json"

        fun memory(): PrivacyStore = PrivacyStore(null)

        fun open(root: File, walletId: String): PrivacyStore = PrivacyStore(File(root, "privacy/$walletId").apply { mkdirs() })
    }
}
