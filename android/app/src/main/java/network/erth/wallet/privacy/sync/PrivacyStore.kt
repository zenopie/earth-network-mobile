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
 * A registration broadcast and committed whose identity leaf the wallet has
 * not resolved yet (C2): everything needed to rebuild the identity record,
 * persisted before any sync so a lagging indexer cannot lose it. Each sync
 * retries until the local identity tree holds [leafIndex].
 */
data class PendingRegistration(
    val txHash: String,
    val leafIndex: Long,
    val dscKey: Fr,
    val passportNullifier: String,
    val publicSignals: List<String>,
    /** The registration block's time: the leaf's activated_at. */
    val activatedAt: Long,
    /** ISO alpha-2 guess at the verifying CSCA's country ("" for none). */
    val countryHint: String,
    /** Why the last attempt to resolve it failed, for the UI (null: waiting for the indexer). */
    val failure: String? = null,
)

/**
 * A registration record note found by sync (PRIVACY_FORMATS.md 3a): what a
 * wallet restored from the mnemonic finds its identity leaf by. [height] is
 * the registration's block.
 */
data class RegRecord(val height: Long, val position: Long, val dscKey: Fr, val country: String, val builtAt: Long)

/**
 * What the wallet keeps between syncs: cursors into each indexer stream, its
 * own notes, its registration, and the automations' bookkeeping. Small; the
 * trees live beside it in per-level files.
 */
class PrivacyState {
    var chainId: String? = null
    /** The indexer's genesis key (first block hash prefix) the synced data is from. */
    var genesis: String? = null
    var notesNext: Long = 0
    var notesHeight: Long = 0
    var nullifiersNext: Long = 0
    var identityNext: Long = 0
    var zeroedNext: Long = 0
    val notes: MutableList<OwnedNote> = ArrayList()
    var identity: IdentityRecord? = null
    /** A committed registration not yet matched to its leaf (C2). */
    var pendingRegistration: PendingRegistration? = null
    /** Registration record notes found (restore, L8). */
    val regRecords: MutableList<RegRecord> = ArrayList()
    /** Whether the last sync's roots matched the chain's own (C3), and why not. */
    var rootsVerified: Boolean = false
    var rootsError: String? = null    /** UTC days a claim was broadcast for (so the automation does not repeat one). */
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
    /** The stake tree's stream cursors and this wallet's stake notes. */
    var stakeNext: Long = 0
    var stakeHeight: Long = 0
    var stakeNullifiersNext: Long = 0
    val stakeNotes: MutableList<OwnedStakeNote> = ArrayList()
    /** Every denom seen in a public amount: resolves the asset ids ciphertexts carry. */
    val denoms: MutableSet<String> = sortedSetOf()

    fun toJson(): JSONObject = JSONObject().apply {
        put("chain_id", chainId)
        put("genesis", genesis)
        pendingRegistration?.let { p ->
            put("pending_registration", JSONObject().put("tx_hash", p.txHash).put("leaf_index", p.leafIndex).put("dsc_key", p.dscKey.toHex())
                .put("passport_nullifier", p.passportNullifier).put("public_signals", JSONArray(p.publicSignals))
                .put("activated_at", p.activatedAt).put("country_hint", p.countryHint).put("failure", p.failure ?: JSONObject.NULL))
        }
        put("reg_records", JSONArray().apply {
            regRecords.forEach {
                put(JSONObject().put("height", it.height).put("position", it.position).put("dsc_key", it.dscKey.toHex())
                    .put("country", it.country).put("built_at", it.builtAt))
            }
        })
        put("roots_verified", rootsVerified); put("roots_error", rootsError ?: JSONObject.NULL)
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
        put("stake_next", stakeNext); put("stake_height", stakeHeight); put("stake_nullifiers_next", stakeNullifiersNext)
        put("stake_notes", JSONArray().apply { stakeNotes.forEach { put(stakeJson(it)) } })
        put("denoms", JSONArray(denoms.toList()))
    }

    companion object {
        fun fromJson(j: JSONObject): PrivacyState = PrivacyState().apply {
            chainId = j.optString("chain_id").ifEmpty { null }
            genesis = if (j.isNull("genesis")) null else j.optString("genesis").ifEmpty { null }
            j.optJSONObject("pending_registration")?.let { p ->
                val sigs = p.optJSONArray("public_signals")
                pendingRegistration = PendingRegistration(
                    p.getString("tx_hash"), p.getLong("leaf_index"), Fr.fromHex(p.getString("dsc_key")), p.optString("passport_nullifier"),
                    (0 until (sigs?.length() ?: 0)).map { sigs!!.getString(it) }, p.getLong("activated_at"), p.optString("country_hint"),
                    if (p.isNull("failure")) null else p.optString("failure"),
                )
            }
            j.optJSONArray("reg_records")?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).let {
                    regRecords.add(RegRecord(it.getLong("height"), it.getLong("position"), Fr.fromHex(it.getString("dsc_key")), it.optString("country"), it.getLong("built_at")))
                }
            }
            rootsVerified = j.optBoolean("roots_verified")
            rootsError = if (j.isNull("roots_error")) null else j.optString("roots_error").ifEmpty { null }
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
     * Forgets the synced data. On the same chain (an inconsistent sync) it
     * keeps the owner-tag counter, the registration (its leaf, or the one
     * pending) and what the wallet itself cast (claims, caretaker split,
     * referrer); a different chain or genesis (a relaunch under the same
     * chain id) keeps only the owner-tag counter.
     */
    @Synchronized
    fun reset(chainId: String?, genesis: String? = state.genesis) {
        noteTree.clear()
        identityTree.clear()
        stakeTree.clear()
        val old = state
        state = PrivacyState().apply {
            this.chainId = chainId
            this.genesis = genesis
            nextOtagCounter = old.nextOtagCounter
            if (old.chainId == chainId && old.genesis == genesis) {
                identity = old.identity
                pendingRegistration = old.pendingRegistration
                claimedDays.addAll(old.claimedDays)
                caretakerCastAt = old.caretakerCastAt; caretakerSplit = old.caretakerSplit
                referrerAddress = old.referrerAddress; referrerBoundAt = old.referrerBoundAt
            }
        }
        save()
    }

    /**
     * A relaunch of the same chain id under a new genesis, confirmed by the
     * LCD (K6): the synced data goes, but the registration stays (the
     * identity record, its passport nullifier, a pending registration) and
     * so do the owner-tag counters; the old chain's bookkeeping does not.
     */
    @Synchronized
    fun switchGenesis(genesis: String) {
        noteTree.clear()
        identityTree.clear()
        stakeTree.clear()
        val old = state
        state = PrivacyState().apply {
            chainId = old.chainId
            this.genesis = genesis
            nextOtagCounter = old.nextOtagCounter
            identity = old.identity
            pendingRegistration = old.pendingRegistration?.copy(failure = null)
        }
        save()
    }

    companion object {
        private const val STATE = "state.json"

        fun memory(): PrivacyStore = PrivacyStore(null)

        fun open(root: File, walletId: String): PrivacyStore = PrivacyStore(File(root, "privacy/$walletId").apply { mkdirs() })
    }
}
