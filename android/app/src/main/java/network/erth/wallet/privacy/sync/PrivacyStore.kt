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
 * H(TAG_LEAF, idc, dsc_key, country, activated_at, predecessor_at).
 */
data class IdentityRecord(
    val leafIndex: Long,
    val dscKey: Fr,
    val country: Fr,
    val activatedAt: Long,
    /** The passport nullifier: public in the registration, the switch/expiry key. */
    val passportNullifier: String,
    /**
     * Matched against an identity tree the chain verified in the same sync
     * (audit 4, M5), or resolved from the registration's own committed tx.
     * A reset keeps only a verified identity; one from before is not.
     */
    val verified: Boolean = false,
    /**
     * The leaf's predecessor_at: the switch or re-entry that made it (then
     * equal to [activatedAt]), 0 for a passport never registered before.
     * Found by matching the leaf with either value.
     */
    val predecessorAt: Long = 0,
)

/**
 * A registration the node accepted whose identity leaf the wallet has not
 * resolved yet (C2, K7): everything needed to rebuild the identity record,
 * persisted the moment the broadcast is accepted (before the wait for its
 * block), so neither a lagging indexer, a wait that times out nor a killed
 * app can lose it. [leafIndex] and [activatedAt] come from the committed tx
 * (its register event, its block time): null until it is found by
 * [txHash]. Each sync retries until the local identity tree holds the leaf.
 */
data class PendingRegistration(
    val txHash: String,
    val leafIndex: Long?,
    val dscKey: Fr,
    val passportNullifier: String,
    val publicSignals: List<String>,
    /** The registration block's time: the leaf's activated_at (null until the tx is found). */
    val activatedAt: Long?,
    /** ISO alpha-2 guess at the verifying CSCA's country ("" for none). */
    val countryHint: String,
    /** Why the last attempt to resolve it failed, for the UI (null: waiting for the indexer). */
    val failure: String? = null,
)

/**
 * A registration record note found by sync (PRIVACY_FORMATS.md 3a), its tag
 * checked: what a wallet restored from the mnemonic finds its identity leaf
 * by. [height] is the registration's block. The search for its leaf is
 * persisted (K1): the leaves appended at [height] as the stream passed
 * them, whether it matched or was given up, and how far the bounded
 * fallback search got, so a killed app or a later sync resumes it and never
 * repeats it.
 */
data class RegRecord(
    val height: Long,
    val position: Long,
    val dscKey: Fr,
    val country: String,
    val builtAt: Long,
    /** (index, leaf) of every identity leaf appended at [height]. */
    val leaves: List<Pair<Long, Fr>> = emptyList(),
    val status: RecordStatus = RecordStatus.OPEN,
    /** Fallback search steps done (one activated_at offset each). */
    val cursor: Long = 0,
    /** Leaf hashes spent on this record so far (capped). */
    val work: Long = 0,
    /** [height]'s block time as the indexer's identity rows carry it (null: not served). */
    val time: Long? = null,
    /** Exact activated_at candidates already tried (each at most 677 hashes a leaf): a new one is tried even after EXHAUSTED. */
    val tried: List<Long> = emptyList(),
    /** The cover set of heights whose block times were asked of the LCD with [height]'s (chosen once, reused). */
    val cover: List<Long> = emptyList(),
    /** LCD cover-set fetches made (bounded). */
    val coverTries: Int = 0,
    /** [height]'s block time as the LCD answered it (null: not asked, or it could not say). */
    val chainTime: Long? = null,
    /** How many leaves [tried] was tried against: more leaves later reopen the record. */
    val leavesTried: Int = 0,
)

/**
 * OPEN: still searching; MATCHED: the identity; EXHAUSTED: the bounded
 * fallback search is spent. EXHAUSTED never blocks a restore for good: an
 * exact time not tried before (the indexer's, the LCD's) is still tried, and
 * a store reset finds the record afresh (K13).
 */
enum class RecordStatus { OPEN, MATCHED, EXHAUSTED }

/**
 * A stake vote being cast (K5), persisted so a run the process lost resumes
 * on the next unlock: the options as (VoteOption number, weight), the
 * positions already voted, casts done of [total].
 */
data class StakeVoteRun(
    val proposalId: Long,
    val options: List<Pair<Int, String>>,
    val votedPositions: Set<Long>,
    val total: Int,
    val done: Int = 0,
)

/**
 * A stake vote this wallet cast (ORCHARD_DESIGN 15): its proposal and vote
 * nullifier, recorded the moment the node accepted the tx ([confirmed]
 * false, with its hash and timeout_height) and confirmed once committed or
 * refused as already voted. One note votes once per proposal.
 */
data class StakeVoteRecord(val proposalId: Long, val vnf: Fr, val txHash: String?, val until: Long?, val confirmed: Boolean)

/**
 * A move of a handle or caretaker split (audit 5, M2), recorded before its
 * broadcast in both wallets: the mover's ([incoming] false: it still holds
 * what it is moving until the tx is confirmed) and the new identity's
 * ([incoming] true: it holds it already, rolled back only if the tx is
 * refused, failed in its block, or missing past its timeout_height).
 * [target] is the new wallet's store id (the mover's copy), [recorded]
 * whether writing it there succeeded (retryable while false).
 */
data class PendingMove(
    /** "handle" or "caretaker". */
    val kind: String,
    val txHash: String,
    val timeoutHeight: Long,
    val incoming: Boolean,
    val handle: String = "",
    val split: Map<Long, Long> = emptyMap(),
    val splitUnknown: Boolean = false,
    val expiresAt: Long = 0,
    val target: String = "",
    val recorded: Boolean = false,
    val confirmed: Boolean = false,
) {
    companion object {
        const val HANDLE = "handle"
        const val CARETAKER = "caretaker"
    }
}

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
    var rootsError: String? = null
    /**
     * Sync generations (audit 3): [syncGeneration] is bumped, with
     * [rootsVerified] cleared and persisted, before a sync's first request;
     * [verifiedGeneration] is set to it only when every stream and the root
     * checks of that same sync succeeded. Txs need the two equal.
     */
    var syncGeneration: Long = 0
    var verifiedGeneration: Long = -1
    /**
     * The height the last verified sync reached (the indexer's, checked
     * against the chain's tree and tip). Audit 6 (M4): a tx's tip is bounded
     * by it, and a pending mark whose timeout is far past it is resolved by
     * the tx's status alone. Kept across resets (heights only grow).
     */
    var verifiedHeight: Long = 0
    /** UTC days a claim was broadcast for (so a claim is not offered twice). */
    val claimedDays: MutableSet<Long> = sortedSetOf()
    /** When the caretaker split was last cast (unix seconds), and the split (option -> percent). */
    var caretakerCastAt: Long = 0
    var caretakerSplit: Map<Long, Long> = emptyMap()
    /** When the split lapses (the chain's expires_at; 0: unknown, castAt + R). */
    var caretakerExpiresAt: Long = 0
    /** This identity moved its split away (MsgMoveCaretaker): it may never cast one again. */
    var caretakerMovedOut: Boolean = false
    /** This identity's handle ("" for none), as last claimed, renewed or moved in. */
    var handle: String = ""
    /** This identity moved its handle away (MsgMoveHandle): it may never claim one again. */
    var handleMovedOut: Boolean = false
    /** When [handle] last changed here (wallet clock): a directory read before it says nothing about it. */
    var handleSetAt: Long = 0
    /**
     * When the handle [handleExpiresFor] stops being live (the chain's
     * expires_at, from its bind or the chain's directory). Counts only while
     * it is [handle]; otherwise unknown. Past it, a renewal or change is
     * bounded like a claim and a move is refused (chain 203d3b2).
     */
    var handleExpiresAt: Long = 0
    var handleExpiresFor: String = ""
    /** The caretaker split is held but its record did not carry it (restored from a state record). */
    var caretakerSplitUnknown: Boolean = false
    /** The newest handle / caretaker state record applied (note position; -1: none). */
    var handleRecordPos: Long = -1
    var caretakerRecordPos: Long = -1
    /** Heights of this wallet's txs that failed in their block: their state records are void. */
    val voidRecordHeights: MutableSet<Long> = sortedSetOf()
    /** Moves in flight, either way (audit 5, M2). */
    val pendingMoves: MutableList<PendingMove> = ArrayList()
    /** The store id of the wallet a switch moves to, fixed by its first move (audit 5, L8). */
    var switchTarget: String = ""
    /** Unbond denoms whose claim the chain refused as not yet matured, to when the automation next tries. */
    val unbondRetryAt: MutableMap<String, Long> = sortedMapOf()
    /** A stake vote being cast (K5), or null. */
    var stakeVoteRun: StakeVoteRun? = null
    /** Every stake vote cast: (proposal, vote nullifier). */
    val stakeVotes: MutableList<StakeVoteRecord> = ArrayList()
    /** A uniform sample of identity row heights (registration blocks): a record's LCD cover set is drawn from it (audit 4). */
    val identityHeights: MutableList<Long> = ArrayList()
    var identityRowsSeen: Long = 0
    /** Next unused Groundworks owner-tag counter (PrivacyKeys.otagSalt). */
    var nextOtagCounter: Int = 0
    /** The highest owner-tag counter of a position this wallet closed, from its unlock memos (-1: none; K11). */
    var closedOtagMax: Int = -1
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
            put("pending_registration", JSONObject().put("tx_hash", p.txHash).put("leaf_index", p.leafIndex ?: JSONObject.NULL).put("dsc_key", p.dscKey.toHex())
                .put("passport_nullifier", p.passportNullifier).put("public_signals", JSONArray(p.publicSignals))
                .put("activated_at", p.activatedAt ?: JSONObject.NULL).put("country_hint", p.countryHint).put("failure", p.failure ?: JSONObject.NULL))
        }
        put("reg_records", JSONArray().apply {
            regRecords.forEach {
                put(JSONObject().put("height", it.height).put("position", it.position).put("dsc_key", it.dscKey.toHex())
                    .put("country", it.country).put("built_at", it.builtAt)
                    .put("leaves", JSONArray().apply { it.leaves.forEach { (i, l) -> put(JSONArray().put(i).put(l.toHex())) } })
                    .put("status", it.status.name).put("cursor", it.cursor).put("work", it.work)
                    .put("time", it.time ?: JSONObject.NULL).put("tried", JSONArray(it.tried)).put("cover", JSONArray(it.cover))
                    .put("cover_tries", it.coverTries).put("chain_time", it.chainTime ?: JSONObject.NULL).put("leaves_tried", it.leavesTried))
            }
        })
        put("roots_verified", rootsVerified); put("roots_error", rootsError ?: JSONObject.NULL)
        put("sync_generation", syncGeneration); put("verified_generation", verifiedGeneration); put("verified_height", verifiedHeight)
        put("notes_next", notesNext); put("notes_height", notesHeight)
        put("nullifiers_next", nullifiersNext); put("identity_next", identityNext); put("zeroed_next", zeroedNext)
        put("notes", JSONArray().apply { notes.forEach { put(noteJson(it)) } })
        identity?.let { id ->
            put("identity", JSONObject().put("leaf_index", id.leafIndex).put("dsc_key", id.dscKey.toHex())
                .put("country", id.country.toHex()).put("activated_at", id.activatedAt).put("passport_nullifier", id.passportNullifier)
                .put("verified", id.verified).put("predecessor_at", id.predecessorAt))
        }
        put("claimed_days", JSONArray(claimedDays.toList()))
        put("caretaker_cast_at", caretakerCastAt)
        put("caretaker_split", JSONObject().apply { caretakerSplit.forEach { (k, v) -> put(k.toString(), v) } })
        put("caretaker_expires_at", caretakerExpiresAt); put("caretaker_moved_out", caretakerMovedOut)
        put("handle", handle); put("handle_moved_out", handleMovedOut); put("handle_set_at", handleSetAt); put("handle_expires_at", handleExpiresAt); put("handle_expires_for", handleExpiresFor)
        put("caretaker_split_unknown", caretakerSplitUnknown)
        put("handle_record_pos", handleRecordPos); put("caretaker_record_pos", caretakerRecordPos)
        put("void_record_heights", JSONArray(voidRecordHeights.toList()))
        put("pending_moves", JSONArray().apply { pendingMoves.forEach { put(moveJson(it)) } })
        put("switch_target", switchTarget)
        put("unbond_retry_at", JSONObject().apply { unbondRetryAt.forEach { (k, v) -> put(k, v) } })
        stakeVoteRun?.let { r ->
            put("stake_vote_run", JSONObject().put("proposal_id", r.proposalId)
                .put("options", JSONArray().apply { r.options.forEach { (o, w) -> put(JSONArray().put(o).put(w)) } })
                .put("voted_positions", JSONArray(r.votedPositions.toList())).put("total", r.total).put("done", r.done))
        }
        put("stake_votes", JSONArray().apply {
            stakeVotes.forEach { v ->
                put(JSONObject().put("proposal_id", v.proposalId).put("vnf", v.vnf.toHex()).put("tx_hash", v.txHash ?: JSONObject.NULL)
                    .put("until", v.until ?: JSONObject.NULL).put("confirmed", v.confirmed))
            }
        })
        put("identity_heights", JSONArray(identityHeights)); put("identity_rows_seen", identityRowsSeen)
        put("next_otag_counter", nextOtagCounter); put("closed_otag_max", closedOtagMax)
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
                    p.getString("tx_hash"), if (p.isNull("leaf_index")) null else p.getLong("leaf_index"), Fr.fromHex(p.getString("dsc_key")), p.optString("passport_nullifier"),
                    (0 until (sigs?.length() ?: 0)).map { sigs!!.getString(it) }, if (p.isNull("activated_at")) null else p.getLong("activated_at"), p.optString("country_hint"),
                    if (p.isNull("failure")) null else p.optString("failure"),
                )
            }
            j.optJSONArray("reg_records")?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).let {
                    val ls = it.optJSONArray("leaves")
                    regRecords.add(
                        RegRecord(
                            it.getLong("height"), it.getLong("position"), Fr.fromHex(it.getString("dsc_key")), it.optString("country"), it.getLong("built_at"),
                            (0 until (ls?.length() ?: 0)).map { k -> ls!!.getJSONArray(k).let { l -> l.getLong(0) to Fr.fromHex(l.getString(1)) } },
                            runCatching { RecordStatus.valueOf(it.optString("status")) }.getOrDefault(RecordStatus.OPEN),
                            it.optLong("cursor"), it.optLong("work"),
                            opt(it, "time"),
                            longs(it.optJSONArray("tried")), longs(it.optJSONArray("cover")), it.optInt("cover_tries"),
                            opt(it, "chain_time"), it.optInt("leaves_tried"),
                        ),
                    )
                }
            }
            rootsVerified = j.optBoolean("roots_verified")
            rootsError = if (j.isNull("roots_error")) null else j.optString("roots_error").ifEmpty { null }
            syncGeneration = j.optLong("sync_generation"); verifiedGeneration = j.optLong("verified_generation", -1); verifiedHeight = j.optLong("verified_height", 0).coerceAtLeast(0)
            notesNext = j.optLong("notes_next"); notesHeight = j.optLong("notes_height")
            nullifiersNext = j.optLong("nullifiers_next"); identityNext = j.optLong("identity_next"); zeroedNext = j.optLong("zeroed_next")
            j.optJSONArray("notes")?.let { a -> for (i in 0 until a.length()) notes.add(noteFromJson(a.getJSONObject(i))) }
            j.optJSONObject("identity")?.let {
                identity = IdentityRecord(it.getLong("leaf_index"), Fr.fromHex(it.getString("dsc_key")), Fr.fromHex(it.getString("country")),
                    it.getLong("activated_at"), it.optString("passport_nullifier"), it.optBoolean("verified", false), it.optLong("predecessor_at", 0))
            }
            j.optJSONArray("claimed_days")?.let { a -> for (i in 0 until a.length()) claimedDays.add(a.getLong(i)) }
            caretakerCastAt = j.optLong("caretaker_cast_at")
            caretakerSplit = j.optJSONObject("caretaker_split")?.let { o -> o.keys().asSequence().associate { it.toLong() to o.getLong(it) } } ?: emptyMap()
            caretakerExpiresAt = j.optLong("caretaker_expires_at"); caretakerMovedOut = j.optBoolean("caretaker_moved_out")
            handle = j.optString("handle"); handleMovedOut = j.optBoolean("handle_moved_out"); handleSetAt = j.optLong("handle_set_at"); handleExpiresAt = j.optLong("handle_expires_at"); handleExpiresFor = j.optString("handle_expires_for")
            caretakerSplitUnknown = j.optBoolean("caretaker_split_unknown")
            handleRecordPos = j.optLong("handle_record_pos", -1); caretakerRecordPos = j.optLong("caretaker_record_pos", -1)
            voidRecordHeights.addAll(longs(j.optJSONArray("void_record_heights")))
            j.optJSONArray("pending_moves")?.let { a -> for (i in 0 until a.length()) pendingMoves.add(moveFromJson(a.getJSONObject(i))) }
            switchTarget = j.optString("switch_target")
            j.optJSONObject("unbond_retry_at")?.let { o -> o.keys().forEach { unbondRetryAt[it] = o.getLong(it) } }
            j.optJSONObject("stake_vote_run")?.let { r ->
                val o = r.optJSONArray("options"); val v = r.optJSONArray("voted_positions")
                stakeVoteRun = StakeVoteRun(
                    r.getLong("proposal_id"),
                    (0 until (o?.length() ?: 0)).map { o!!.getJSONArray(it).let { p -> p.getInt(0) to p.getString(1) } },
                    (0 until (v?.length() ?: 0)).map { v!!.getLong(it) }.toSet(),
                    r.optInt("total"), r.optInt("done"),
                )
            }
            j.optJSONArray("stake_votes")?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).let {
                    stakeVotes.add(StakeVoteRecord(it.getLong("proposal_id"), Fr.fromHex(it.getString("vnf")),
                        if (it.isNull("tx_hash")) null else it.getString("tx_hash"), opt(it, "until"), it.optBoolean("confirmed")))
                }
            }
            identityHeights.addAll(longs(j.optJSONArray("identity_heights"))); identityRowsSeen = j.optLong("identity_rows_seen")
            nextOtagCounter = j.optInt("next_otag_counter"); closedOtagMax = j.optInt("closed_otag_max", -1)
            stakeNext = j.optLong("stake_next"); stakeHeight = j.optLong("stake_height"); stakeNullifiersNext = j.optLong("stake_nullifiers_next")
            j.optJSONArray("stake_notes")?.let { a -> for (i in 0 until a.length()) stakeNotes.add(stakeFromJson(a.getJSONObject(i))) }
            j.optJSONArray("denoms")?.let { a -> for (i in 0 until a.length()) denoms.add(a.getString(i)) }
        }

        private fun splitJson(m: Map<Long, Long>) = JSONObject().apply { m.forEach { (k, v) -> put(k.toString(), v) } }

        private fun splitFromJson(o: JSONObject?): Map<Long, Long> = o?.let { s -> s.keys().asSequence().associate { it.toLong() to s.getLong(it) } } ?: emptyMap()

        private fun moveJson(m: PendingMove) = JSONObject()
            .put("kind", m.kind).put("tx_hash", m.txHash).put("timeout_height", m.timeoutHeight).put("incoming", m.incoming)
            .put("handle", m.handle).put("split", splitJson(m.split)).put("split_unknown", m.splitUnknown).put("expires_at", m.expiresAt)
            .put("target", m.target).put("recorded", m.recorded).put("confirmed", m.confirmed)

        private fun moveFromJson(o: JSONObject) = PendingMove(
            o.getString("kind"), o.getString("tx_hash"), o.optLong("timeout_height"), o.optBoolean("incoming"),
            o.optString("handle"), splitFromJson(o.optJSONObject("split")), o.optBoolean("split_unknown"), o.optLong("expires_at"),
            o.optString("target"), o.optBoolean("recorded"), o.optBoolean("confirmed"),
        )

        private fun longs(a: JSONArray?): List<Long> = (0 until (a?.length() ?: 0)).map { a!!.getLong(it) }

        private fun opt(o: JSONObject, k: String): Long? = if (!o.has(k) || o.isNull(k)) null else o.getLong(k)

        private fun stakeJson(n: OwnedStakeNote) = JSONObject()
            .put("position", n.position).put("height", n.height).put("denom", n.denom).put("amount", n.amount)
            .put("rho", n.rho.toHex()).put("rcm", n.rcm.toHex()).put("cm", n.cm.toHex()).put("nf", n.nf.toHex())
            .put("spent_height", n.spentHeight ?: JSONObject.NULL)
            .put("pending_at", n.pendingAt ?: JSONObject.NULL)
            .put("pending_until", n.pendingUntil ?: JSONObject.NULL)
            .put("pending_tx", n.pendingTx ?: JSONObject.NULL)

        private fun stakeFromJson(o: JSONObject) = OwnedStakeNote(
            position = o.getLong("position"), height = o.getLong("height"), denom = o.getString("denom"), amount = o.getLong("amount"),
            rho = Fr.fromHex(o.getString("rho")), rcm = Fr.fromHex(o.getString("rcm")),
            cm = Fr.fromHex(o.getString("cm")), nf = Fr.fromHex(o.getString("nf")),
            spentHeight = if (o.isNull("spent_height")) null else o.getLong("spent_height"),
            pendingAt = if (o.isNull("pending_at")) null else o.getLong("pending_at"),
            pendingUntil = opt(o, "pending_until"),
            pendingTx = optString(o, "pending_tx"),
        )

        private fun optString(o: JSONObject, k: String): String? = if (!o.has(k) || o.isNull(k)) null else o.getString(k)

        private fun noteJson(n: OwnedNote) = JSONObject()
            .put("position", n.position).put("height", n.height).put("cm", n.cm.toHex()).put("nf", n.nf.toHex())
            .put("denom", n.note.denom).put("value", n.note.value).put("rho", n.note.rho.toHex()).put("rcm", n.note.rcm.toHex())
            .put("memo", n.note.memo.joinToString("") { "%02x".format(it.toInt() and 0xff) })
            .put("spent_height", n.spentHeight ?: JSONObject.NULL)
            .put("pending_at", n.pendingAt ?: JSONObject.NULL)
            .put("pending_until", n.pendingUntil ?: JSONObject.NULL)
            .put("pending_tx", n.pendingTx ?: JSONObject.NULL)

        private fun noteFromJson(o: JSONObject): OwnedNote {
            val memo = o.optString("memo")
            return OwnedNote(
                position = o.getLong("position"), height = o.getLong("height"),
                note = NotePlaintext(o.getString("denom"), o.getLong("value"), Fr.fromHex(o.getString("rho")), Fr.fromHex(o.getString("rcm")),
                    ByteArray(memo.length / 2) { memo.substring(2 * it, 2 * it + 2).toInt(16).toByte() }),
                cm = Fr.fromHex(o.getString("cm")), nf = Fr.fromHex(o.getString("nf")),
                spentHeight = if (o.isNull("spent_height")) null else o.getLong("spent_height"),
                pendingAt = if (o.isNull("pending_at")) null else o.getLong("pending_at"),
                pendingUntil = opt(o, "pending_until"),
                pendingTx = optString(o, "pending_tx"),
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

    /** state.json exists but does not parse: shown as an error, never silently replaced by an empty state (audit 3). */
    class CorruptState(message: String, cause: Throwable?) : java.io.IOException(message, cause)

    var state: PrivacyState = dir?.let { File(it, STATE).takeIf(File::exists) }?.let { f ->
        try {
            PrivacyState.fromJson(JSONObject(f.readText()))
        } catch (e: Exception) {
            throw CorruptState("this wallet's private data (${f.name}) is unreadable: ${e.message}", e)
        }
    } ?: PrivacyState()
        private set

    val noteTree = MerkleTree(noteNodes, state.notesNext)
    val identityTree = MerkleTree(identityNodes, state.identityNext)
    val stakeTree = MerkleTree(stakeNodes, state.stakeNext)

    /**
     * Persists state after the trees, so a crash between the two leaves state
     * behind (and resyncs) rather than ahead. The state is written to a temp
     * file, fsynced, then renamed over state.json (atomic on one filesystem)
     * and any failure throws (audit 3: never silent).
     */
    @Synchronized
    fun save() {
        noteTree.flush(); identityTree.flush(); stakeTree.flush()
        val d = dir ?: return
        val tmp = File(d, "$STATE.tmp")
        java.io.FileOutputStream(tmp).use { out ->
            out.write(state.toJson().toString().toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        // rename(2): atomic within one filesystem (java.nio.file needs API 26; minSdk is 24).
        if (!tmp.renameTo(File(d, STATE))) throw java.io.IOException("could not save this wallet's private data")
    }

    /**
     * Forgets the synced data. On the same chain (an inconsistent sync, a
     * root mismatch) it keeps the owner-tag counter, the registration (its
     * leaf only when it was matched against a verified tree, audit 4 M5; or
     * the one pending) and what the wallet itself cast (claims, caretaker
     * split, handle, the moves, its stake votes, audit 4 L1); a different chain or genesis (a relaunch under the same chain
     * id) keeps only the owner-tag counter.
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
            closedOtagMax = old.closedOtagMax
            if (old.chainId == chainId && old.genesis == genesis) {
                identity = old.identity?.takeIf { it.verified }
                pendingRegistration = old.pendingRegistration
                claimedDays.addAll(old.claimedDays)
                caretakerCastAt = old.caretakerCastAt; caretakerSplit = old.caretakerSplit
                caretakerExpiresAt = old.caretakerExpiresAt; caretakerMovedOut = old.caretakerMovedOut
                handle = old.handle; handleMovedOut = old.handleMovedOut
                keepHandleState(old, this)
                stakeVoteRun = old.stakeVoteRun
                stakeVotes.addAll(old.stakeVotes)
            } else if (old.chainId == null) {
                // Never synced: what a switch moved to this identity was
                // recorded for the chain the app follows (PrivacySession.recorderFor).
                caretakerCastAt = old.caretakerCastAt; caretakerSplit = old.caretakerSplit
                caretakerExpiresAt = old.caretakerExpiresAt
                handle = old.handle
                keepHandleState(old, this)
            }
        }
        save()
    }

    /**
     * What a reset keeps of the moves and state records: the records already
     * applied are not applied again over what the wallet did since (a resync
     * reads them from the start), and moves in flight stay in flight.
     */
    private fun keepHandleState(old: PrivacyState, s: PrivacyState) {
        s.handleSetAt = old.handleSetAt
        s.handleExpiresAt = old.handleExpiresAt; s.handleExpiresFor = old.handleExpiresFor
        s.caretakerSplitUnknown = old.caretakerSplitUnknown
        s.handleRecordPos = old.handleRecordPos; s.caretakerRecordPos = old.caretakerRecordPos
        s.voidRecordHeights.addAll(old.voidRecordHeights)
        s.pendingMoves.addAll(old.pendingMoves)
        s.switchTarget = old.switchTarget
        s.verifiedHeight = old.verifiedHeight
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
            closedOtagMax = old.closedOtagMax
            identity = old.identity
            pendingRegistration = old.pendingRegistration?.copy(failure = null)
        }
        save()
    }

    companion object {
        private const val STATE = "state.json"

        fun memory(): PrivacyStore = PrivacyStore(null)

        fun open(root: File, walletId: String): PrivacyStore = PrivacyStore(File(root, "privacy/$walletId").apply { mkdirs() })

        private val sharedStores = HashMap<String, PrivacyStore>()

        /**
         * The process's one store for a wallet's directory (audit 6, M8):
         * two instances on one directory each save their whole state over
         * the other's. Every app path opens stores through this.
         */
        fun shared(root: File, walletId: String): PrivacyStore = synchronized(sharedStores) {
            val dir = File(root, "privacy/$walletId").canonicalPath
            sharedStores.getOrPut(dir) { open(root, walletId) }
        }

        /**
         * Deletes a wallet's private data (notes, identity, records, trees)
         * when the wallet is forgotten (audit 3): every file is overwritten
         * with zeros and synced before it is unlinked (best effort on flash,
         * where the FTL may keep old blocks; the app's sandbox is the real
         * boundary). [walletId] null: every wallet's.
         */
        fun delete(root: File, walletId: String? = null) {
            val target = if (walletId == null) File(root, "privacy") else File(root, "privacy/$walletId")
            synchronized(sharedStores) {
                val prefix = target.canonicalPath
                sharedStores.keys.removeAll { it == prefix || it.startsWith(prefix + File.separator) }
            }
            if (!target.exists()) return
            target.walkBottomUp().forEach { f ->
                if (f.isFile) runCatching {
                    java.io.RandomAccessFile(f, "rw").use { r ->
                        val zeros = ByteArray(64 * 1024)
                        var left = r.length()
                        r.seek(0)
                        while (left > 0) { val n = minOf(left, zeros.size.toLong()).toInt(); r.write(zeros, 0, n); left -= n }
                        r.fd.sync()
                    }
                }
                if (!f.delete() && f.exists()) throw java.io.IOException("could not delete ${f.name}")
            }
        }
    }
}
