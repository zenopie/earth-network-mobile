package network.erth.wallet.privacy.sync

import network.erth.wallet.privacy.note.NotePlaintext
import network.erth.wallet.privacy.note.OwnedNote
import network.erth.wallet.privacy.note.OwnedStakeNote
import network.erth.wallet.privacy.note.StakeLabel
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
     * Matched against an identity tree the chain verified in the same sync,
     * or resolved from the registration's own committed tx. A reset keeps
     * only a verified identity; one from an older version is not.
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
 * resolved yet: everything needed to rebuild the identity record,
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
    /** The identity generation it registers (PrivacyKeys): its leaf is matched with that generation's idc. */
    val generation: Int = 0,
)

/**
 * A registration record note found by sync (PRIVACY_FORMATS.md §6), its tag
 * checked: what a wallet restored from the mnemonic finds its identity leaf
 * by. [height] is the registration's block. The search for its leaf is
 * persisted: the leaves appended at [height] as the stream passed
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
    /** The identity generation the registration was for, from the record's tag (PRIVACY_FORMATS.md §6). */
    val generation: Int = 0,
)

/**
 * OPEN: still searching; MATCHED: the identity; EXHAUSTED: the bounded
 * fallback search is spent. EXHAUSTED never blocks a restore for good: an
 * exact time not tried before (the indexer's, the LCD's) is still tried, and
 * a store reset finds the record afresh.
 */
enum class RecordStatus { OPEN, MATCHED, EXHAUSTED }

/**
 * An undelegation of this wallet whose payout has not arrived
 * (ORCHARD_DESIGN 8.4): recorded when the node takes the tx
 * ([until] its timeout_height), confirmed with the committed event's
 * [epoch], [value] (uerth) and [payoutId], dropped once a note to [pc] is
 * synced (paid) or the tx failed. Local only: what the wallet shows while it
 * waits, never a query key.
 */
data class PendingUnbond(
    val txHash: String,
    val validator: String,
    val derth: Long,
    val pc: Fr,
    val startedAt: Long,
    val until: Long?,
    val confirmed: Boolean = false,
    val epoch: Long? = null,
    val value: Long? = null,
    val payoutId: Long? = null,
    /** About when the chain pays it (PrivacyWallet.unbondDueBy at confirmation; null: unknown). */
    val dueBy: Long? = null,
)

/**
 * A stake vote this wallet cast (ORCHARD_DESIGN 8.5): its proposal and vote
 * nullifier, recorded the moment the node accepted the tx ([confirmed]
 * false, with its hash and timeout_height) and confirmed once committed or
 * refused as already voted. One note votes once per proposal.
 */
data class StakeVoteRecord(val proposalId: Long, val vnf: Fr, val txHash: String?, val until: Long?, val confirmed: Boolean)

/**
 * A move of a handle or caretaker split, recorded before its
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
 * A spend mark carried across a store reset, keyed by the note's nullifier:
 * a tx still in the mempool keeps its notes unspendable while the resync
 * finds them again (WalletSync applies it to the note it re-finds).
 */
data class CarriedMark(val at: Long, val until: Long?, val tx: String?)

/**
 * The last Groundworks split this wallet saw on one of its own positions and
 * when that split's lease ends. The chain clears a lapsed split (splits
 * empty, split_expires_at 0), so only this says when it lapsed and what it
 * was, for the reminder and a re-cast of the same split.
 */
data class PositionLease(val expiresAt: Long, val split: Map<Long, Long>)

/**
 * What one identity generation of the wallet holds (PrivacyKeys: one phrase,
 * an identity secret per generation): its registration, handle, caretaker
 * split and moves. The wallet acts as [PrivacyState.generation]; an earlier
 * one keeps what it holds until moved on or lapsed.
 */
class IdentitySlot {
    var identity: IdentityRecord? = null
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
     * bounded like a claim and a move is refused.
     */
    var handleExpiresAt: Long = 0
    var handleExpiresFor: String = ""
    /** The caretaker split is held but its record did not carry it (restored from a state record). */
    var caretakerSplitUnknown: Boolean = false
    /** The newest handle / caretaker state record applied (note position; -1: none). */
    var handleRecordPos: Long = -1
    var caretakerRecordPos: Long = -1
    /** Moves in flight, either way. */
    val pendingMoves: MutableList<PendingMove> = ArrayList()
    /** The store id of the wallet a switch moves to, fixed by its first move. */
    var switchTarget: String = ""
    /**
     * After a switch to this identity: when the wallet suggests bringing the
     * predecessor's handle and split over (a random delay after the switch,
     * so a move does not link them by timing; 0: none drawn), and the leaf
     * of the registration it was drawn for. A suggestion only: nothing moves
     * unasked.
     */
    var moveSuggestedAt: Long = 0
    var moveSuggestedLeaf: Long = -1

    /** Whether it holds or did nothing (such a slot is not written). */
    val empty: Boolean get() = identity == null && caretakerCastAt == 0L && caretakerSplit.isEmpty() && caretakerExpiresAt == 0L &&
        !caretakerMovedOut && handle.isEmpty() && !handleMovedOut && handleSetAt == 0L && handleExpiresAt == 0L && handleExpiresFor.isEmpty() &&
        !caretakerSplitUnknown && handleRecordPos == -1L && caretakerRecordPos == -1L && pendingMoves.isEmpty() && switchTarget.isEmpty() &&
        moveSuggestedAt == 0L && moveSuggestedLeaf == -1L

    /** A copy; [keepIdentity] filters the registration kept. */
    fun copy(keepIdentity: (IdentityRecord) -> Boolean = { true }): IdentitySlot = IdentitySlot().also { c ->
        c.identity = identity?.takeIf(keepIdentity)
        c.caretakerCastAt = caretakerCastAt; c.caretakerSplit = caretakerSplit; c.caretakerExpiresAt = caretakerExpiresAt
        c.caretakerMovedOut = caretakerMovedOut; c.handle = handle; c.handleMovedOut = handleMovedOut; c.handleSetAt = handleSetAt
        c.handleExpiresAt = handleExpiresAt; c.handleExpiresFor = handleExpiresFor; c.caretakerSplitUnknown = caretakerSplitUnknown
        c.handleRecordPos = handleRecordPos; c.caretakerRecordPos = caretakerRecordPos; c.pendingMoves.addAll(pendingMoves)
        c.switchTarget = switchTarget; c.moveSuggestedAt = moveSuggestedAt; c.moveSuggestedLeaf = moveSuggestedLeaf
    }

    fun toJson(generation: Int): JSONObject = JSONObject().apply {
        put("generation", generation)
        identity?.let { id ->
            put("identity", JSONObject().put("leaf_index", id.leafIndex).put("dsc_key", id.dscKey.toHex())
                .put("country", id.country.toHex()).put("activated_at", id.activatedAt).put("passport_nullifier", id.passportNullifier)
                .put("verified", id.verified).put("predecessor_at", id.predecessorAt))
        }
        put("caretaker_cast_at", caretakerCastAt)
        put("caretaker_split", JSONObject().apply { caretakerSplit.forEach { (k, v) -> put(k.toString(), v) } })
        put("caretaker_expires_at", caretakerExpiresAt); put("caretaker_moved_out", caretakerMovedOut)
        put("handle", handle); put("handle_moved_out", handleMovedOut); put("handle_set_at", handleSetAt); put("handle_expires_at", handleExpiresAt); put("handle_expires_for", handleExpiresFor)
        put("caretaker_split_unknown", caretakerSplitUnknown)
        put("handle_record_pos", handleRecordPos); put("caretaker_record_pos", caretakerRecordPos)
        put("pending_moves", JSONArray().apply { pendingMoves.forEach { put(PrivacyState.moveJson(it)) } })
        put("switch_target", switchTarget)
        put("move_suggested_at", moveSuggestedAt); put("move_suggested_leaf", moveSuggestedLeaf)
    }

    companion object {
        /** From a slot object, or (a store from before generations) the state's own top-level fields. */
        fun fromJson(j: JSONObject): IdentitySlot = IdentitySlot().apply {
            j.optJSONObject("identity")?.let {
                identity = IdentityRecord(it.getLong("leaf_index"), Fr.fromHex(it.getString("dsc_key")), Fr.fromHex(it.getString("country")),
                    it.getLong("activated_at"), it.optString("passport_nullifier"), it.optBoolean("verified", false), it.optLong("predecessor_at", 0))
            }
            caretakerCastAt = j.optLong("caretaker_cast_at")
            caretakerSplit = j.optJSONObject("caretaker_split")?.let { o -> o.keys().asSequence().associate { it.toLong() to o.getLong(it) } } ?: emptyMap()
            caretakerExpiresAt = j.optLong("caretaker_expires_at"); caretakerMovedOut = j.optBoolean("caretaker_moved_out")
            handle = j.optString("handle"); handleMovedOut = j.optBoolean("handle_moved_out"); handleSetAt = j.optLong("handle_set_at"); handleExpiresAt = j.optLong("handle_expires_at"); handleExpiresFor = j.optString("handle_expires_for")
            caretakerSplitUnknown = j.optBoolean("caretaker_split_unknown")
            handleRecordPos = j.optLong("handle_record_pos", -1); caretakerRecordPos = j.optLong("caretaker_record_pos", -1)
            j.optJSONArray("pending_moves")?.let { a -> for (i in 0 until a.length()) pendingMoves.add(PrivacyState.moveFromJson(a.getJSONObject(i))) }
            switchTarget = j.optString("switch_target")
            moveSuggestedAt = j.optLong("move_suggested_at"); moveSuggestedLeaf = j.optLong("move_suggested_leaf", -1)
        }
    }
}

/**
 * What the wallet keeps between syncs: cursors into each indexer stream, its
 * own notes, its registration, and its own txs' bookkeeping. Small; the
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
    /**
     * The identity generation the wallet acts as (PrivacyKeys): the one its
     * registration, handle and split below are. It moves up when a
     * registration of a later generation lands, or a restore finds one.
     */
    var generation: Int = 0
    /** The lowest generation a registration may use: raised past one the chain refused as used (1130). */
    var generationFloor: Int = 0
    /** Every generation's slot that holds anything (by generation). */
    val slots: java.util.TreeMap<Int, IdentitySlot> = java.util.TreeMap()
    fun slot(generation: Int): IdentitySlot = slots.getOrPut(generation) { IdentitySlot() }
    /** The slot of [generation]: what the identity the wallet acts as holds. */
    val current: IdentitySlot get() = slot(generation)
    var identity: IdentityRecord?
        get() = current.identity
        set(v) { current.identity = v }

    /**
     * The highest generation whose idc may have been registered, -1 for
     * none: one with a registration record (a registration of it reached a
     * block, whether or not it succeeded: the record lands with the fee),
     * a registration matched or committed, and every one below the floor.
     * Skipping a generation whose registration failed costs nothing.
     */
    fun usedThrough(): Int {
        var m = generationFloor - 1
        regRecords.forEach { m = maxOf(m, it.generation) }
        slots.forEach { (g, slot) -> if (slot.identity != null) m = maxOf(m, g) }
        pendingRegistration?.let { if (it.leafIndex != null) m = maxOf(m, it.generation) }
        return m
    }

    /**
     * The generation the next registration (a first one, a re-entry, a
     * fresh identity) uses: the lowest above every one that may have been
     * registered. A sent registration that has not landed keeps its own:
     * retrying it is the same identity.
     */
    fun nextGeneration(): Int {
        val pending = pendingRegistration?.takeIf { it.leafIndex == null && it.failure == null }?.generation ?: -1
        return maxOf(usedThrough() + 1, generationFloor, pending, 0)
    }

    /** A registration of [g] landed or was found: the wallet acts as it from now on (never back to an earlier one). */
    fun actAs(g: Int) {
        // Its slot made here, under the writer's lock, not by a reader's getOrPut.
        slot(g)
        if (g > generation) generation = g
    }
    /** A committed registration not yet matched to its leaf. */
    var pendingRegistration: PendingRegistration? = null
    /**
     * Until when (unix seconds) a registration this wallet broadcast can
     * still land: its proof's current_date plus the chain's 48 h skew. A
     * registration that failed or was refused is public and may be replayed
     * until then, so this wallet's identity (its recovery phrase) must be
     * kept and counted as possibly registered. 0: none.
     */
    var registrationKeepUntil: Long = 0
    /** Registration record notes found (restore). */
    val regRecords: MutableList<RegRecord> = ArrayList()
    /** Whether the last sync's roots matched the chain's own, and why not. */
    var rootsVerified: Boolean = false
    var rootsError: String? = null
    /**
     * Sync generations: [syncGeneration] is bumped, with
     * [rootsVerified] cleared and persisted, before a sync's first request;
     * [verifiedGeneration] is set to it only when every stream and the root
     * checks of that same sync succeeded. Txs need the two equal.
     */
    var syncGeneration: Long = 0
    var verifiedGeneration: Long = -1
    /**
     * The height the last verified sync reached (the indexer's, checked
     * against the chain's tree and tip). A tx's tip is bounded
     * by it, and a pending mark whose timeout is far past it is resolved by
     * the tx's status alone. Kept across resets (heights only grow).
     */
    var verifiedHeight: Long = 0
    /** UTC days a claim was broadcast for (so a claim is not offered twice). */
    val claimedDays: MutableSet<Long> = sortedSetOf()
    // What the identity the wallet acts as holds: its slot's (IdentitySlot).
    var caretakerCastAt: Long get() = current.caretakerCastAt; set(v) { current.caretakerCastAt = v }
    var caretakerSplit: Map<Long, Long> get() = current.caretakerSplit; set(v) { current.caretakerSplit = v }
    var caretakerExpiresAt: Long get() = current.caretakerExpiresAt; set(v) { current.caretakerExpiresAt = v }
    var caretakerMovedOut: Boolean get() = current.caretakerMovedOut; set(v) { current.caretakerMovedOut = v }
    var handle: String get() = current.handle; set(v) { current.handle = v }
    var handleMovedOut: Boolean get() = current.handleMovedOut; set(v) { current.handleMovedOut = v }
    var handleSetAt: Long get() = current.handleSetAt; set(v) { current.handleSetAt = v }
    var handleExpiresAt: Long get() = current.handleExpiresAt; set(v) { current.handleExpiresAt = v }
    var handleExpiresFor: String get() = current.handleExpiresFor; set(v) { current.handleExpiresFor = v }
    var caretakerSplitUnknown: Boolean get() = current.caretakerSplitUnknown; set(v) { current.caretakerSplitUnknown = v }
    var handleRecordPos: Long get() = current.handleRecordPos; set(v) { current.handleRecordPos = v }
    var caretakerRecordPos: Long get() = current.caretakerRecordPos; set(v) { current.caretakerRecordPos = v }
    val pendingMoves: MutableList<PendingMove> get() = current.pendingMoves
    var switchTarget: String get() = current.switchTarget; set(v) { current.switchTarget = v }
    var moveSuggestedAt: Long get() = current.moveSuggestedAt; set(v) { current.moveSuggestedAt = v }
    var moveSuggestedLeaf: Long get() = current.moveSuggestedLeaf; set(v) { current.moveSuggestedLeaf = v }
    /** Heights of this wallet's txs that failed in their block: their state records are void. */
    val voidRecordHeights: MutableSet<Long> = sortedSetOf()
    /** Undelegations whose payout has not arrived yet. */
    val pendingUnbonds: MutableList<PendingUnbond> = ArrayList()
    /** Every stake vote cast: (proposal, vote nullifier). */
    val stakeVotes: MutableList<StakeVoteRecord> = ArrayList()
    /** A uniform sample of identity row heights (registration blocks): a record's LCD cover set is drawn from it. */
    val identityHeights: MutableList<Long> = ArrayList()
    var identityRowsSeen: Long = 0
    /** Next unused Groundworks owner-tag counter (PrivacyKeys.otagSalt). */
    var nextOtagCounter: Int = 0
    /** The highest owner-tag counter of a position this wallet closed, from its unlock memos (-1: none). */
    var closedOtagMax: Int = -1
    /** Per position id of ours: its split's lease as last seen (PrivacyWallet.positions keeps it). */
    val positionLeases: MutableMap<Long, PositionLease> = java.util.TreeMap()
    /** The stake tree's stream cursors and this wallet's stake notes. */
    var stakeNext: Long = 0
    var stakeHeight: Long = 0
    var stakeNullifiersNext: Long = 0
    /**
     * The chain's slash label window (Query/DebtTree window_seconds) as last
     * read: when a moved stake's exposure may leave its note, for display
     * between reads (0: never read).
     */
    var labelWindowSeconds: Long = 0
    val stakeNotes: MutableList<OwnedStakeNote> = ArrayList()
    /** Pending marks a reset carried, by nullifier hex, until the resync re-finds their notes. */
    val carriedMarks: MutableMap<String, CarriedMark> = HashMap()
    /** Every denom seen in a public amount: resolves the asset ids ciphertexts carry. */
    val denoms: MutableSet<String> = sortedSetOf()

    fun toJson(): JSONObject = JSONObject().apply {
        put("chain_id", chainId)
        put("genesis", genesis)
        pendingRegistration?.let { p ->
            put("pending_registration", JSONObject().put("tx_hash", p.txHash).put("leaf_index", p.leafIndex ?: JSONObject.NULL).put("dsc_key", p.dscKey.toHex())
                .put("passport_nullifier", p.passportNullifier).put("public_signals", JSONArray(p.publicSignals))
                .put("activated_at", p.activatedAt ?: JSONObject.NULL).put("country_hint", p.countryHint).put("failure", p.failure ?: JSONObject.NULL)
                .put("generation", p.generation))
        }
        if (registrationKeepUntil != 0L) put("registration_keep_until", registrationKeepUntil)
        put("reg_records", JSONArray().apply {
            regRecords.forEach {
                put(JSONObject().put("height", it.height).put("position", it.position).put("dsc_key", it.dscKey.toHex())
                    .put("country", it.country).put("built_at", it.builtAt)
                    .put("leaves", JSONArray().apply { it.leaves.forEach { (i, l) -> put(JSONArray().put(i).put(l.toHex())) } })
                    .put("status", it.status.name).put("cursor", it.cursor).put("work", it.work)
                    .put("time", it.time ?: JSONObject.NULL).put("tried", JSONArray(it.tried)).put("cover", JSONArray(it.cover))
                    .put("cover_tries", it.coverTries).put("chain_time", it.chainTime ?: JSONObject.NULL).put("leaves_tried", it.leavesTried)
                    .put("generation", it.generation))
            }
        })
        put("roots_verified", rootsVerified); put("roots_error", rootsError ?: JSONObject.NULL)
        put("sync_generation", syncGeneration); put("verified_generation", verifiedGeneration); put("verified_height", verifiedHeight)
        put("notes_next", notesNext); put("notes_height", notesHeight)
        put("nullifiers_next", nullifiersNext); put("identity_next", identityNext); put("zeroed_next", zeroedNext)
        put("notes", JSONArray().apply { notes.forEach { put(noteJson(it)) } })
        put("generation", generation); put("generation_floor", generationFloor)
        put("identities", JSONArray().apply { slots.forEach { (g, slot) -> if (g == generation || !slot.empty) put(slot.toJson(g)) } })
        put("claimed_days", JSONArray(claimedDays.toList()))
        put("void_record_heights", JSONArray(voidRecordHeights.toList()))
        put("pending_unbonds", JSONArray().apply {
            pendingUnbonds.forEach { u ->
                put(JSONObject().put("tx_hash", u.txHash).put("validator", u.validator).put("derth", u.derth).put("pc", u.pc.toHex())
                    .put("started_at", u.startedAt).put("until", u.until ?: JSONObject.NULL).put("confirmed", u.confirmed)
                    .put("epoch", u.epoch ?: JSONObject.NULL).put("value", u.value ?: JSONObject.NULL).put("payout_id", u.payoutId ?: JSONObject.NULL).put("due_by", u.dueBy ?: JSONObject.NULL))
            }
        })
        put("stake_votes", JSONArray().apply {
            stakeVotes.forEach { v ->
                put(JSONObject().put("proposal_id", v.proposalId).put("vnf", v.vnf.toHex()).put("tx_hash", v.txHash ?: JSONObject.NULL)
                    .put("until", v.until ?: JSONObject.NULL).put("confirmed", v.confirmed))
            }
        })
        put("identity_heights", JSONArray(identityHeights)); put("identity_rows_seen", identityRowsSeen)
        put("next_otag_counter", nextOtagCounter); put("closed_otag_max", closedOtagMax)
        put("position_leases", JSONArray().apply {
            positionLeases.forEach { (id, l) -> put(JSONObject().put("id", id).put("expires_at", l.expiresAt).put("split", splitJson(l.split))) }
        })
        put("stake_next", stakeNext); put("stake_height", stakeHeight); put("stake_nullifiers_next", stakeNullifiersNext)
        put("label_window_seconds", labelWindowSeconds)
        put("stake_notes", JSONArray().apply { stakeNotes.forEach { put(stakeJson(it)) } })
        put("denoms", JSONArray(denoms.toList()))
        put("carried_marks", JSONArray().apply {
            carriedMarks.forEach { (nf, m) ->
                put(JSONObject().put("nf", nf).put("at", m.at).put("until", m.until ?: JSONObject.NULL).put("tx", m.tx ?: JSONObject.NULL))
            }
        })
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
                    if (p.isNull("failure")) null else p.optString("failure"), gen(p),
                )
            }
            registrationKeepUntil = j.optLong("registration_keep_until", 0L)
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
                            opt(it, "chain_time"), it.optInt("leaves_tried"), gen(it),
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
            generation = gen(j); generationFloor = gen(j, "generation_floor")
            val ids = j.optJSONArray("identities")
            if (ids == null) {
                // A store from before generations: its one identity is generation 0.
                slots[0] = IdentitySlot.fromJson(j)
            } else for (i in 0 until ids.length()) ids.getJSONObject(i).let { slots[gen(it)] = IdentitySlot.fromJson(it) }
            slot(generation)
            j.optJSONArray("claimed_days")?.let { a -> for (i in 0 until a.length()) claimedDays.add(a.getLong(i)) }
            voidRecordHeights.addAll(longs(j.optJSONArray("void_record_heights")))
            j.optJSONArray("pending_unbonds")?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).let {
                    pendingUnbonds.add(PendingUnbond(it.getString("tx_hash"), it.getString("validator"), it.getLong("derth"), Fr.fromHex(it.getString("pc")),
                        it.optLong("started_at"), opt(it, "until"), it.optBoolean("confirmed"), opt(it, "epoch"), opt(it, "value"), opt(it, "payout_id"), opt(it, "due_by")))
                }
            }
            j.optJSONArray("stake_votes")?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).let {
                    stakeVotes.add(StakeVoteRecord(it.getLong("proposal_id"), Fr.fromHex(it.getString("vnf")),
                        if (it.isNull("tx_hash")) null else it.getString("tx_hash"), opt(it, "until"), it.optBoolean("confirmed")))
                }
            }
            identityHeights.addAll(longs(j.optJSONArray("identity_heights"))); identityRowsSeen = j.optLong("identity_rows_seen")
            nextOtagCounter = j.optInt("next_otag_counter"); closedOtagMax = j.optInt("closed_otag_max", -1)
            j.optJSONArray("position_leases")?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).let {
                    positionLeases[it.getLong("id")] = PositionLease(it.optLong("expires_at"), splitFromJson(it.optJSONObject("split")))
                }
            }
            stakeNext = j.optLong("stake_next"); stakeHeight = j.optLong("stake_height"); stakeNullifiersNext = j.optLong("stake_nullifiers_next")
            labelWindowSeconds = j.optLong("label_window_seconds")
            j.optJSONArray("stake_notes")?.let { a -> for (i in 0 until a.length()) stakeNotes.add(stakeFromJson(a.getJSONObject(i))) }
            j.optJSONArray("denoms")?.let { a -> for (i in 0 until a.length()) denoms.add(a.getString(i)) }
            j.optJSONArray("carried_marks")?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).let {
                    carriedMarks[it.getString("nf")] = CarriedMark(it.getLong("at"), opt(it, "until"), optString(it, "tx"))
                }
            }
        }

        private fun splitJson(m: Map<Long, Long>) = JSONObject().apply { m.forEach { (k, v) -> put(k.toString(), v) } }

        private fun splitFromJson(o: JSONObject?): Map<Long, Long> = o?.let { s -> s.keys().asSequence().associate { it.toLong() to s.getLong(it) } } ?: emptyMap()

        private fun gen(o: JSONObject, k: String = "generation"): Int =
            o.optInt(k, 0).coerceIn(0, network.erth.wallet.privacy.keys.PrivacyKeys.MAX_GENERATION)

        internal fun moveJson(m: PendingMove) = JSONObject()
            .put("kind", m.kind).put("tx_hash", m.txHash).put("timeout_height", m.timeoutHeight).put("incoming", m.incoming)
            .put("handle", m.handle).put("split", splitJson(m.split)).put("split_unknown", m.splitUnknown).put("expires_at", m.expiresAt)
            .put("target", m.target).put("recorded", m.recorded).put("confirmed", m.confirmed)

        internal fun moveFromJson(o: JSONObject) = PendingMove(
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
            .apply {
                n.label?.let { l -> put("move_key", l.moveKey.toHex()).put("move_time", l.moveTime).put("exposed", l.exposed) }
            }

        private fun stakeFromJson(o: JSONObject) = OwnedStakeNote(
            position = o.getLong("position"), height = o.getLong("height"), denom = o.getString("denom"), amount = o.getLong("amount"),
            rho = Fr.fromHex(o.getString("rho")), rcm = Fr.fromHex(o.getString("rcm")),
            cm = Fr.fromHex(o.getString("cm")), nf = Fr.fromHex(o.getString("nf")),
            spentHeight = if (o.isNull("spent_height")) null else o.getLong("spent_height"),
            pendingAt = if (o.isNull("pending_at")) null else o.getLong("pending_at"),
            pendingUntil = opt(o, "pending_until"),
            pendingTx = optString(o, "pending_tx"),
            label = optString(o, "move_key")?.let { StakeLabel(Fr.fromHex(it), o.getLong("move_time"), o.getLong("exposed")) },
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
class PrivacyStore private constructor(
    private val dir: File?,
    /** The install's data key ([StateSeal]); null only for a store with no file. */
    private val key: ByteArray? = null,
    private val walletId: String = "",
) {
    private val noteNodes: NodeStore = dir?.let { FileNodeStore(File(it, "notes")) } ?: MemNodeStore()
    private val identityNodes: NodeStore = dir?.let { FileNodeStore(File(it, "identity")) } ?: MemNodeStore()
    private val stakeNodes: NodeStore = dir?.let { FileNodeStore(File(it, "stake")) } ?: MemNodeStore()

    /** state.json exists but does not parse: shown as an error, never silently replaced by an empty state. */
    class CorruptState(message: String, cause: Throwable?) : java.io.IOException(message, cause)

    /** What the file held: plaintext from before sealing (sealed on load), or another install's sealed data (dropped). */
    private var loadedAs: StateSeal.Contents? = null

    var state: PrivacyState = dir?.let { File(it, STATE).takeIf(File::exists) }?.let { f ->
        val contents = try {
            StateSeal.open(f.readBytes(), key, walletId)
        } catch (e: Exception) {
            throw CorruptState("this wallet's private data (${f.name}) is unreadable: ${e.javaClass.simpleName}", e)
        }
        loadedAs = contents
        when (contents) {
            is StateSeal.Contents.OtherKey -> null
            is StateSeal.Contents.Legacy -> parse(f, contents.json)
            is StateSeal.Contents.Opened -> parse(f, contents.json)
        }
    } ?: PrivacyState()
        private set

    private fun parse(f: File, json: JSONObject): PrivacyState = try {
        PrivacyState.fromJson(json)
    } catch (e: Exception) {
        throw CorruptState("this wallet's private data (${f.name}) is unreadable: ${e.message}", e)
    }

    val noteTree = MerkleTree(noteNodes, state.notesNext)
    val identityTree = MerkleTree(identityNodes, state.identityNext)
    val stakeTree = MerkleTree(stakeNodes, state.stakeNext)

    init {
        when (loadedAs) {
            // Sealed under an earlier install's key (the wallet storage, and
            // with it the key, was made anew): unreadable, and everything in
            // it is found again by a sync from the mnemonic. Its trees go too.
            is StateSeal.Contents.OtherKey -> { noteTree.clear(); identityTree.clear(); stakeTree.clear(); save() }
            // Plaintext from before sealing: sealed now, not at some later save,
            // and the plaintext's blocks overwritten.
            is StateSeal.Contents.Legacy -> if (key != null) save(scrubOld = true)
            else -> {}
        }
        loadedAs = null
    }

    /**
     * Persists state after the trees, so a crash between the two leaves state
     * behind (and resyncs) rather than ahead. The state is written to a temp
     * file, fsynced, then renamed over state.json (atomic on one filesystem)
     * and any failure throws (never silent).
     */
    @Synchronized
    fun save() = save(scrubOld = false)

    @Synchronized
    private fun save(scrubOld: Boolean) {
        noteTree.flush(); identityTree.flush(); stakeTree.flush()
        val d = dir ?: return
        val k = key ?: throw IllegalStateException("a stored wallet's private data needs the data key to be saved")
        writeState(d, StateSeal.seal(state.toJson().toString().toByteArray(Charsets.UTF_8), k, walletId), scrubOld)
    }

    /**
     * Forgets the synced data. On the same chain (an inconsistent sync, a
     * root mismatch) it keeps the owner-tag counter, the registration (its
     * leaf only when it was matched against a verified tree, or the one
     * pending) and what the wallet itself cast (claims, caretaker split,
     * handle, the moves, its stake votes); a different chain or genesis (a
     * relaunch under the same chain id) keeps only the owner-tag counter.
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
                // Every generation's slot: registrations (verified ones), handles, splits, moves.
                keepSlots(old, this) { it.verified }
                pendingRegistration = old.pendingRegistration
                registrationKeepUntil = old.registrationKeepUntil
                claimedDays.addAll(old.claimedDays)
                keepHandleState(old, this)
                pendingUnbonds.addAll(old.pendingUnbonds)
                stakeVotes.addAll(old.stakeVotes)
                positionLeases.putAll(old.positionLeases)
                labelWindowSeconds = old.labelWindowSeconds
                // Notes a tx in flight spends stay unspendable through the resync.
                carriedMarks.putAll(old.carriedMarks)
                for (n in old.notes) if (n.unspent && n.pendingAt != null) carriedMarks[n.nf.toHex()] = CarriedMark(n.pendingAt, n.pendingUntil, n.pendingTx)
                for (n in old.stakeNotes) if (n.unspent && n.pendingAt != null) carriedMarks[n.nf.toHex()] = CarriedMark(n.pendingAt, n.pendingUntil, n.pendingTx)
            } else if (old.chainId == null) {
                // Never synced: what a switch moved to this identity was
                // recorded for the chain the app follows (PrivacySession.recorderFor).
                keepSlots(old, this) { false }
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
        s.voidRecordHeights.addAll(old.voidRecordHeights)
        s.verifiedHeight = old.verifiedHeight
    }

    /**
     * Every generation's slot (what each identity holds and its moves, the
     * records applied: see [keepHandleState]), the generation the wallet
     * acts as and its floor; a registration only where [keepIdentity].
     */
    private fun keepSlots(old: PrivacyState, s: PrivacyState, keepIdentity: (IdentityRecord) -> Boolean) {
        s.generation = old.generation
        s.generationFloor = old.generationFloor
        old.slots.forEach { (g, slot) -> s.slots[g] = slot.copy(keepIdentity) }
    }

    /**
     * A relaunch of the same chain id under a new genesis, confirmed by the
     * LCD: the synced data goes, but the registration stays (the
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
            // The registration stays: every generation's, and which the wallet acts as.
            generation = old.generation
            generationFloor = old.generationFloor
            old.slots.forEach { (g, slot) -> slot.identity?.let { id -> slot(g).identity = id } }
            pendingRegistration = old.pendingRegistration?.copy(failure = null)
            registrationKeepUntil = old.registrationKeepUntil
        }
        save()
    }

    companion object {
        private const val STATE = "state.json"

        fun memory(): PrivacyStore = PrivacyStore(null)

        /** [key]: the install's data key (SessionManager.dataKey), which seals state.json. */
        fun open(root: File, walletId: String, key: ByteArray): PrivacyStore =
            PrivacyStore(File(root, "privacy/$walletId").apply { mkdirs() }, key.copyOf(), walletId)

        /**
         * [bytes] (sealed) as dir/state.json: a temp file, fsynced, then
         * renamed over it (rename(2) is atomic within one filesystem;
         * java.nio.file needs API 26, minSdk is 24); any failure throws.
         * [scrubOld]: the file replaced is plaintext from before sealing, so
         * it is held open across the rename and its blocks overwritten with
         * zeros through that descriptor before it is let go. A crash before
         * the rename leaves the plaintext to migrate again, never a zeroed
         * state. Best effort: on flash the FTL may write the zeros elsewhere
         * and keep the old blocks until they are erased, and file-based
         * encryption is what really covers them.
         */
        private fun writeState(d: File, bytes: ByteArray, scrubOld: Boolean) {
            val tmp = File(d, "$STATE.tmp")
            java.io.FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            val target = File(d, STATE)
            val old = if (scrubOld && target.exists()) runCatching { java.io.RandomAccessFile(target, "rw") }.getOrNull() else null
            try {
                if (!tmp.renameTo(target)) throw java.io.IOException("could not save this wallet's private data")
                old?.let { r -> runCatching { zeroFill(r) } }
            } finally {
                old?.let { runCatching { it.close() } }
            }
        }

        private fun zeroFill(r: java.io.RandomAccessFile) {
            val zeros = ByteArray(64 * 1024)
            var left = r.length()
            r.seek(0)
            while (left > 0) { val n = minOf(left, zeros.size.toLong()).toInt(); r.write(zeros, 0, n); left -= n }
            r.fd.sync()
        }

        /**
         * Open stores by directory. [sharedStores] holds them while a
         * session is open; [lockAll] lets go of it, and only [liveStores]
         * (weak) still finds one that a tx finishing after the lock
         * holds, so the next unlock reopens that same instance and never a
         * second one beside it. One nothing holds is gone, key copy and
         * decrypted state with it.
         */
        private val sharedStores = HashMap<String, PrivacyStore>()
        private val liveStores = HashMap<String, java.lang.ref.WeakReference<PrivacyStore>>()

        /**
         * The process's one store for a wallet's directory:
         * two instances on one directory each save their whole state over
         * the other's. Every app path opens stores through this.
         */
        fun shared(root: File, walletId: String, key: ByteArray): PrivacyStore = synchronized(sharedStores) {
            val dir = File(root, "privacy/$walletId").canonicalPath
            sharedStores[dir]?.let { return it }
            val s = liveStores[dir]?.get() ?: open(root, walletId, key)
            sharedStores[dir] = s
            liveStores[dir] = java.lang.ref.WeakReference(s)
            s
        }

        /** At lock: the process stops holding any store (see [sharedStores]). */
        fun lockAll() = synchronized(sharedStores) {
            sharedStores.clear()
            liveStores.values.removeAll { it.get() == null }
        }

        /**
         * Seals every wallet's plaintext state.json from before sealing
         * under [key], not just the stores the user opens again: a wallet
         * never selected after the upgrade would otherwise keep its notes,
         * registration and handle in plaintext. The store id (the directory
         * name) is all the seal's AAD needs. A store open in this process is
         * skipped (opening one seals it); the lock is held per directory,
         * so none opens mid-seal. Returns how many were sealed; one that
         * fails is left for the next unlock.
         */
        fun sealLegacy(root: File, key: ByteArray): Int {
            val dirs = File(root, "privacy").listFiles { f -> f.isDirectory } ?: return 0
            var sealed = 0
            for (d in dirs) {
                val f = File(d, STATE)
                if (!f.isFile) continue
                synchronized(sharedStores) {
                    val path = d.canonicalPath
                    if (sharedStores.containsKey(path) || liveStores[path]?.get() != null) return@synchronized
                    runCatching {
                        val plain = f.readBytes()
                        val c = StateSeal.open(plain, key, d.name)
                        if (c is StateSeal.Contents.Legacy) {
                            writeState(d, StateSeal.seal(plain, key, d.name), scrubOld = true)
                            plain.fill(0)
                            sealed++
                        }
                    }
                }
            }
            return sealed
        }

        /**
         * Deletes a wallet's private data (notes, identity, records, trees)
         * when the wallet is forgotten: every file is overwritten
         * with zeros and synced before it is unlinked (best effort on flash,
         * where the FTL may keep old blocks; the app's sandbox is the real
         * boundary). [walletId] null: every wallet's.
         */
        fun delete(root: File, walletId: String? = null) {
            val target = if (walletId == null) File(root, "privacy") else File(root, "privacy/$walletId")
            synchronized(sharedStores) {
                val prefix = target.canonicalPath
                sharedStores.keys.removeAll { it == prefix || it.startsWith(prefix + File.separator) }
                liveStores.keys.removeAll { it == prefix || it.startsWith(prefix + File.separator) }
            }
            if (!target.exists()) return
            target.walkBottomUp().forEach { f ->
                if (f.isFile) runCatching { java.io.RandomAccessFile(f, "rw").use { zeroFill(it) } }
                if (!f.delete() && f.exists()) throw java.io.IOException("could not delete ${f.name}")
            }
        }
    }
}
