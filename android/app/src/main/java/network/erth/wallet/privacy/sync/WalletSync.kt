package network.erth.wallet.privacy.sync

import network.erth.wallet.privacy.keys.PrivacyKeys
import network.erth.wallet.privacy.note.AssetDenoms
import network.erth.wallet.privacy.note.Denoms
import network.erth.wallet.privacy.note.NoteCipher
import network.erth.wallet.privacy.note.NotePlaintext
import network.erth.wallet.privacy.note.OwnedNote
import network.erth.wallet.privacy.note.OwnedStakeNote
import network.erth.wallet.privacy.tx.PrivateTxEngine
import network.erth.wallet.privacy.zk.Fr
import network.erth.wallet.privacy.zk.Privacy

/**
 * x/shielded Query/Root: a root the chain recorded, whether it is still an
 * anchor, its tree size, and the height of the block that produced it (null:
 * the node did not say).
 */
/** [expiresAt]: when the root stops being an anchor (0: the latest root, which does not lapse; null: the node did not say). */
data class NoteRootRecord(val valid: Boolean, val treeSize: Long, val height: Long? = null, val expiresAt: Long? = null)

/** The chain's latest block: its height and time (unix seconds; null when the node did not say). */
data class ChainTip(val height: Long, val time: Long?)

/** What the chain says of a tx by hash (audit 4): committed, failed in its block, or unknown to it. */
enum class TxStatus { COMMITTED, FAILED, MISSING }

/**
 * A tree's state as the chain reports it: size and latest recorded root
 * (null for an empty tree); [pinned] only when the node answered at exactly
 * the height asked for (its echoed `x-cosmos-block-height`), false when that
 * height was unavailable or the echo differed and the state is some other
 * height's (K9).
 */
data class TreeState(val size: Long, val root: Fr?, val pinned: Boolean = true)

/**
 * The chain's own view of the three trees (the LCD in the app, the fake
 * chain in tests), against which every tree the indexer served is checked
 * (audit C3): an indexer can omit, add or forge rows, and a wallet that
 * trusted it would show forged notes and build proofs nobody accepts.
 */
interface ChainRoots {
    /** x/shielded Query/Root for [root]: null when the chain never recorded it. */
    fun noteRoot(root: Fr): NoteRootRecord?
    /** x/personhood Query/IdentityTree at [height] (latest when null). */
    fun identityTree(height: Long?): TreeState
    /** x/shieldedstaking Query/StakeTree at [height] (latest when null). */
    fun stakeTree(height: Long?): TreeState
    /** x/shielded Query/Nullifier: whether [nf] is spent (null: the node could not say). */
    fun nullifierSpent(nf: Fr): Boolean? = null
    /** x/shieldedstaking Query/StakeNullifier likewise. */
    fun stakeNullifierSpent(nf: Fr): Boolean? = null
    /** The chain's latest block height (null: unknown). */
    fun latestHeight(): Long? = null
    /** A block's time (unix seconds): a registration's activated_at (K1). Null when the node cannot say. */
    fun blockTime(height: Long): Long? = null
    /** The LCD's chain id and genesis key (first block hash, 16 hex); null when it cannot say (K6). */
    fun chainIdentity(): ChainIdentity? = null
    /** The chain's latest block, with its time when the node says it (audit 4: every indexer height and time is bounded by it). */
    fun latestBlock(): ChainTip? = latestHeight()?.let { ChainTip(it, null) }
    /** x/shielded Query/Tree at [height]: the note tree's size then (null: the node cannot say). */
    fun noteTree(height: Long?): TreeState? = null
    /**
     * A tx by [hash] (one this wallet broadcast, so the node knows it
     * already): null when the node could not say. A pending note is released
     * only on MISSING or FAILED (audit 4).
     */
    fun txStatus(hash: String): TxStatus? = null
    /**
     * x/shielded Query/Assets: (denom, asset id) pairs, at most
     * [network.erth.wallet.privacy.note.Denoms.MAX]; null when the node
     * cannot say. Each is learned only if the id is the denom's own hash
     * (audit 6, M2).
     */
    fun assets(): List<Pair<String, Fr>>? = null
    /**
     * x/staking's validators, every status (public): a stake ciphertext
     * carries derth/<valoper>'s asset id only, so a wallet restored from the
     * mnemonic names its stake by trying each (null: the node cannot say).
     */
    fun validatorOperators(): List<String>? = null
}

/** Which chain the LCD serves: its chain id and the first 16 lowercase hex digits of its block 1 hash (null: unavailable). */
data class ChainIdentity(val chainId: String, val genesis: String?)

/**
 * Brings a wallet's [PrivacyStore] up to the indexer's tip:
 *
 *  1. `/privacy/status`: refuse a halted indexer or another chain; a new
 *     (chain id, genesis) wipes the local data;
 *  2. every note commitment, appended to the local note tree, every
 *     ciphertext trial-decrypted with this wallet's ek (v1, or v2 against
 *     the row's public amount: one note-discovery rule, no counters), and
 *     every open note (no ciphertext: the referral note) whose owner_pk is
 *     ours checked against its cm; a split payout's rows sharing one
 *     ciphertext are each a note of their own;
 *  3. every nullifier, up to the height the notes reached;
 *  4. the stake tree the same way (the wallet's own stake ciphertexts, and
 *     the blind stake ciphertexts of the notes the chain minted);
 *  5. every identity leaf and zeroing up to the same height; registration
 *     record notes matched to their leaf (restore), a pending registration
 *     resolved (C2);
 *  6. the local roots checked against the indexer's latest (repeating the
 *     pass while the indexer moves) and then against the chain's own (C3).
 *
 * Nothing is ever requested about one note or one leaf: the trees, and with
 * them this wallet's Merkle paths, are built here from the full streams.
 */
class WalletSync(
    private val indexer: PrivacyIndexer,
    private val store: PrivacyStore,
    private val keys: PrivacyKeys,
    private val chainId: String,
    private val chain: ChainRoots,
    /** The wallet's clock (unix seconds): what pending marks are stamped with. */
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    /** Leaf hashes the record fallback search may spend this sync (K1). */
    private val searchBudget: Long = SYNC_SEARCH_BUDGET,
    /** The most one sync (its retries included) may take before it gives up (audit 3). */
    private val syncTimeoutMs: Long = SYNC_TIMEOUT_MS,
    /** A monotonic clock in milliseconds (tests pass their own). */
    private val monoMs: () -> Long = { System.nanoTime() / 1_000_000 },
    /** Draws the LCD cover set of block heights (tests pass a seeded one). */
    private val rng: java.util.Random = sampleRng,
) {
    class Inconsistent(message: String) : Exception(message)

    /** The sync ran past [SYNC_TIMEOUT_MS]: an indexer that never stops serving, or one far too slow. */
    class SyncTimeout(message: String) : java.io.IOException(message)

    private var deadline = Long.MAX_VALUE

    /** The chain's tip (LCD) every indexer height and row time this sync is bounded by (audit 4). */
    private var tip: ChainTip? = null

    /** Our own tx landed but the indexer never reported its spend: what it served is not the chain's (audit 4). */
    private var ownSpendMissing = false

    /**
     * Audit 6 (M2): the asset-id lookup, built once per sync from the
     * persisted denoms and grown as notes of our own name new ones.
     */
    private var assetDenoms = AssetDenoms()

    /** Whether this sync has read the chain's asset list (at most once a sync, and only when a note of ours needs it). */
    private var chainAssetsRead = false
    private var validatorsRead = false

    private fun readTip(): ChainTip =
        (runCatching { chain.latestBlock() }.getOrNull() ?: throw java.io.IOException("the node did not say its latest height; nothing was synced"))
            .also { tip = it }

    /**
     * [h], an indexer's height (a row's, a cursor, a synced_height), at most
     * the chain's tip plus [TIP_SLACK] (audit 4, M1): past it the tip is read
     * again once (the chain moved), then the page is Inconsistent.
     */
    private fun bounded(name: String, h: Long): Long {
        if (h < 0) throw Inconsistent("the indexer's $name height $h")
        val t = tip ?: readTip()
        if (h <= t.height + TIP_SLACK) return h
        if (h <= readTip().height + TIP_SLACK) return h
        throw Inconsistent("the indexer's $name height $h is past the chain's tip ${tip?.height}")
    }

    /** A height page's heights and cursors, bounded by the tip. */
    private fun bounded(name: String, page: HeightPage<*>) {
        bounded("$name synced", page.syncedHeight)
        bounded("$name next", maxOf(0L, page.nextHeight - 1))
        page.blocks.forEach { bounded(name, it.first) }
    }

    /** The latest block time an indexer row may carry: the tip's (or, unknown, the wallet's clock) plus [TIME_SLACK]. */
    private fun maxTime(): Long = (tip?.time ?: now()).let { if (it > Long.MAX_VALUE - TIME_SLACK) Long.MAX_VALUE else it + TIME_SLACK }

    /** Whether [t] can be a block time of this chain (audit 4, H1): never 0, never before [MIN_BLOCK_TIME], never past the tip. */
    private fun timeOk(t: Long): Boolean = t in MIN_BLOCK_TIME..maxTime()

    private fun tick() {
        if (monoMs() > deadline) throw SyncTimeout("the privacy sync did not finish in ${syncTimeoutMs / 1000}s")
    }

    /** A uniform sample of up to [n] of everything offered (reservoir): nothing about which are ours. */
    private class Reservoir(private val n: Int) {
        val items = ArrayList<Fr>()
        private var seen = 0L
        fun offer(x: Fr) {
            seen++
            if (items.size < n) items.add(x)
            else (sampleRng.nextDouble() * seen).toLong().let { j -> if (j < n) items[j.toInt()] = x }
        }
    }

    private val poolSample = Reservoir(NULLIFIER_SAMPLE)
    private val stakeSample = Reservoir(NULLIFIER_SAMPLE)

    /** The chain disagrees with what the indexer served: nothing synced is trusted (C3). */
    class ChainMismatch(message: String) : Exception(message)

    /** The indexer names another genesis the LCD does not confirm: nothing is wiped, nothing synced (K6). */
    class GenesisUnverified(message: String) : Exception(message)

    data class Result(
        val syncedHeight: Long,
        val newNotes: List<OwnedNote>,
        val spent: List<OwnedNote>,
        val noteRoot: Fr,
        val identityRoot: Fr,
        val newStake: List<OwnedStakeNote> = emptyList(),
        val identityStatus: IdentityStatus,
        /** Whether every root matched the chain's (false: the indexer moved too fast to pin; sync again). */
        val verified: Boolean = true,
    )

    enum class IdentityStatus { NONE, LIVE, ZEROED }

    companion object {
        /** Pending marks made before txs carried a timeout_height are released after this long (wall clock). */
        const val PENDING_TIMEOUT_S = 15 * 60L

        /** One sync's time limit, its retries included. */
        const val SYNC_TIMEOUT_MS = 10 * 60 * 1000L

        /** What rootsError says while a sync has not finished (audit 3). */
        const val SYNC_UNFINISHED = "unverified: the last sync did not finish; sync again"

        /** Block heights asked of the LCD with a record's own when the indexer serves no block time (audit 3). */
        const val COVER_SET = 16

        /** LCD cover-set fetches a record may make. */
        const val MAX_COVER_TRIES = 3

        /**
         * The page size every stream is asked with (the backend's paging
         * rule, audit 4: `limit` is 100 or 1000, a position or index cursor a
         * multiple of it). Every wallet asks for the same URLs, so a CDN keeps
         * one copy of each page.
         */
        const val PAGE_SIZE = 1000

        /** A height page never splits a block, so one block may bring more than the limit; never more than this. */
        const val MAX_HEIGHT_PAGE_ROWS = 5000

        /** The page sizes the backend serves (PRIVACY_PAGE_SIZES). */
        val PAGE_SIZES = setOf(100, 1000)

        /** The aligned page holding [cursor]: page k is positions [k*limit, (k+1)*limit). */
        fun aligned(cursor: Long, limit: Int): Long = cursor - cursor % limit

        /** Passes over the streams while the indexer keeps moving, before giving up on pinning a height. */
        private const val MAX_PASSES = 4

        /** Nullifiers of this sync's streams (pool, stake) spot-checked against the chain (K9). */
        const val NULLIFIER_SAMPLE = 4

        /** Blocks the indexer may trail the chain by before what it served is labelled stale (K9). */
        const val STALE_BLOCKS = 30L

        /**
         * Blocks an indexer height may run past the chain's tip as the LCD
         * reports it (the indexer's node may be a block or two ahead of the
         * LCD's). Anything further is Inconsistent (audit 4): a height past
         * the tip would poison the persisted cursors for good.
         */
        const val TIP_SLACK = 10L

        /** Seconds an indexer row's block time may run past the tip's time (or the wallet's clock). */
        const val TIME_SLACK = 3_600L

        /**
         * No block time before this (2025-01-01 UTC) is one of earth-1's: an
         * indexer row time, a record's candidate activated_at below it is
         * refused (audit 4, H1; earth-1's genesis is later still).
         */
        const val MIN_BLOCK_TIME = 1_735_689_600L

        /** Identity row heights kept (a uniform sample) to draw a record's LCD cover set from (audit 4, M2). */
        const val IDENTITY_HEIGHT_SAMPLE = 256

        private val sampleRng = java.security.SecureRandom()

        /** The largest tree position the circuits take (u32). */
        private const val MAX_POSITION = 0xffffffffL

        /** Registration record memo: "ER", version 2 (PRIVACY_FORMATS.md 3a). Version 1 (untagged) is ignored. */
        val REG_MAGIC = byteArrayOf(0x45, 0x52, 0x02)

        /** Bytes of the record memo's tag. */
        const val REG_TAG_BYTES = 16

        /** The record's tag: the first 16 bytes of H(TAG_RECTAG, nk, dsc_key, U64(built_at)). Only the owner (nk) can make one. */
        fun regTag(nk: Fr, dscKey: Fr, builtAt: Long): ByteArray =
            Privacy.h(Privacy.TAG_RECTAG, nk, dscKey, Privacy.u64(builtAt)).toBytes().copyOf(REG_TAG_BYTES)

        /** The 64-byte memo of a registration record note. */
        fun regMemo(nk: Fr, dscKey: Fr, country: String, builtAt: Long): ByteArray {
            val b = java.nio.ByteBuffer.allocate(NoteCipher.MEMO_BYTES)
            b.put(REG_MAGIC)
            val c = country.uppercase().takeIf { it.length == 2 && it.all { ch -> ch in 'A'..'Z' } }
            b.put(c?.toByteArray(Charsets.US_ASCII) ?: ByteArray(2))
            b.putLong(builtAt)
            b.put(dscKey.toBytes())
            b.put(regTag(nk, dscKey, builtAt))
            return b.array()
        }

        /**
         * (dsc_key, country, built_at) if [memo] is a version-2 registration
         * record whose tag is [nk]'s (K1): anyone can send this wallet a
         * value-0 note with any memo, and an untagged record would cost a
         * leaf search per leaf at its height. The tag is checked before
         * anything else is done with it.
         */
        fun parseRegMemo(nk: Fr, memo: ByteArray): Triple<Fr, String, Long>? {
            val m = memo.copyOf(NoteCipher.MEMO_BYTES)
            if (!m.copyOf(3).contentEquals(REG_MAGIC)) return null
            val b = java.nio.ByteBuffer.wrap(m, 3, m.size - 3)
            val c = ByteArray(2).also { b.get(it) }
            val country = when {
                c.all { it.toInt() == 0 } -> ""
                c.all { it in 'A'.code.toByte()..'Z'.code.toByte() } -> String(c, Charsets.US_ASCII)
                else -> return null
            }
            val builtAt = b.long
            val dsc = runCatching { Fr.fromBytes(ByteArray(32).also { b.get(it) }) }.getOrNull() ?: return null
            val tag = ByteArray(REG_TAG_BYTES).also { b.get(it) }
            if (m.copyOfRange(3 + 2 + 8 + 32 + REG_TAG_BYTES, m.size).any { it.toInt() != 0 }) return null
            if (!java.security.MessageDigest.isEqual(tag, regTag(nk, dsc, builtAt))) return null
            return Triple(dsc, country, builtAt)
        }

        /** Unlock memo: "EU", version 1 (PRIVACY_FORMATS.md 1, K11). */
        val UNLOCK_MAGIC = byteArrayOf(0x45, 0x55, 0x01)

        private fun unlockTag(nk: Fr, counter: Int): ByteArray =
            Privacy.h(Privacy.TAG_UNLOCKTAG, nk, Privacy.u64(counter.toLong() and 0xffffffffL)).toBytes().copyOf(REG_TAG_BYTES)

        /**
         * The memo of an unlock's record note (a value-0 pool note to itself in
         * the unlock's fee bundle; the stake note it merges into carries no
         * memo since chain dff3a9b): the owner-tag counter of the position it
         * closed, so a wallet restored from the mnemonic knows the tags of
         * closed positions too and never locks under one again (K11). Tagged
         * like the record (only nk makes one): a note carrying a huge counter
         * cannot stretch the owner-tag scan.
         */
        fun unlockMemo(nk: Fr, counter: Int): ByteArray =
            java.nio.ByteBuffer.allocate(NoteCipher.MEMO_BYTES).put(UNLOCK_MAGIC).putInt(counter).put(unlockTag(nk, counter)).array()

        /** The closed counter if [memo] is this wallet's unlock memo. */
        fun parseUnlockMemo(nk: Fr, memo: ByteArray): Int? {
            val m = memo.copyOf(NoteCipher.MEMO_BYTES)
            if (!m.copyOf(3).contentEquals(UNLOCK_MAGIC)) return null
            val counter = java.nio.ByteBuffer.wrap(m, 3, 4).int
            if (counter < 0) return null
            if (m.copyOfRange(7 + REG_TAG_BYTES, m.size).any { it.toInt() != 0 }) return null
            if (!java.security.MessageDigest.isEqual(m.copyOfRange(7, 7 + REG_TAG_BYTES), unlockTag(nk, counter))) return null
            return counter
        }

        /**
         * State records (PRIVACY_FORMATS.md 3b, audit 5 M1): value-0 notes
         * whose memo says what this identity holds in a scope, so a wallet
         * restored from the mnemonic knows its handle and caretaker split
         * (or that it moved them away). "EH" handle, "EC" caretaker, version 1.
         */
        val HANDLE_MAGIC = byteArrayOf(0x45, 0x48, 0x01)
        val CARETAKER_MAGIC = byteArrayOf(0x45, 0x43, 0x01)

        /** The tag sits at [STATE_TAG_AT] and covers every byte before it. */
        const val STATE_TAG_AT = 48

        const val RECORD_HOLDS = 1
        /** Handle released / caretaker split cleared. */
        const val RECORD_NONE = 2
        const val RECORD_MOVED_OUT = 3
        /** OR'd into a caretaker HOLDS kind: the split did not fit the memo and is not recorded. */
        const val SPLIT_UNRECORDED = 0x80

        /** Entries of a recorded split: (option uvarint, percent u8)..., zero padded. */
        private const val SPLIT_AT = 8

        /** The most options a caretaker split names (x/allocation MaxVoterOptions). */
        const val MAX_SPLIT_OPTIONS = 20

        sealed interface StateRecord {
            data class Handle(val kind: Int, val handle: String) : StateRecord
            /** [split] null: held, but the split was not recorded. */
            data class Caretaker(val kind: Int, val expiresAt: Long, val split: Map<Long, Long>?) : StateRecord
        }

        private fun stateTag(nk: Fr, body: ByteArray): ByteArray =
            Privacy.h(Privacy.TAG_STATETAG, nk, Privacy.bytes(body.copyOf(STATE_TAG_AT))).toBytes().copyOf(REG_TAG_BYTES)

        private fun sealState(nk: Fr, body: ByteArray): ByteArray {
            val m = body.copyOf(NoteCipher.MEMO_BYTES)
            stateTag(nk, m).copyInto(m, STATE_TAG_AT)
            return m
        }

        /** A handle record for the identity whose nk is [nk]: HOLDS [handle], or RELEASED / MOVED_OUT (no handle). */
        fun handleMemo(nk: Fr, kind: Int, handle: String = ""): ByteArray {
            require(kind in RECORD_HOLDS..RECORD_MOVED_OUT)
            require(if (kind == RECORD_HOLDS) network.erth.wallet.privacy.handles.Handles.valid(handle) else handle.isEmpty())
            val b = ByteArray(STATE_TAG_AT)
            HANDLE_MAGIC.copyInto(b)
            b[3] = kind.toByte()
            handle.toByteArray(Charsets.US_ASCII).copyInto(b, 4)
            return sealState(nk, b)
        }

        /**
         * A caretaker record: HOLDS with the split and its expiry (u32 unix
         * seconds), or CLEARED / MOVED_OUT. A split whose entries do not fit
         * 40 bytes is marked unrecorded (its expiry still is).
         */
        fun caretakerMemo(nk: Fr, kind: Int, expiresAt: Long = 0, split: Map<Long, Long> = emptyMap()): ByteArray {
            require(kind in RECORD_HOLDS..RECORD_MOVED_OUT)
            val b = ByteArray(STATE_TAG_AT)
            CARETAKER_MAGIC.copyInto(b)
            var k = kind
            if (kind == RECORD_HOLDS) {
                java.nio.ByteBuffer.wrap(b, 4, 4).putInt(expiresAt.coerceIn(1, 0xffffffffL).toInt())
                val entries = java.io.ByteArrayOutputStream()
                var fits = split.isNotEmpty() && split.size <= MAX_SPLIT_OPTIONS
                for ((option, percent) in split.toSortedMap()) {
                    if (option < 0 || percent !in 1..100) fits = false
                    var v = option
                    while (v >= 0x80) { entries.write(((v and 0x7f) or 0x80).toInt()); v = v ushr 7 }
                    entries.write(v.toInt())
                    entries.write(percent.toInt())
                }
                val e = entries.toByteArray()
                if (!fits || e.size > STATE_TAG_AT - SPLIT_AT) k = kind or SPLIT_UNRECORDED
                else e.copyInto(b, SPLIT_AT)
            }
            b[3] = k.toByte()
            return sealState(nk, b)
        }

        /**
         * The record in [memo] if it is a well-formed state record whose tag
         * is [nk]'s (anyone can send this wallet a value-0 note with any
         * memo; only the holder of nk can tag one). Checked before use.
         */
        fun parseStateMemo(nk: Fr, memo: ByteArray): StateRecord? {
            val m = memo.copyOf(NoteCipher.MEMO_BYTES)
            val head = m.copyOf(3)
            val isHandle = head.contentEquals(HANDLE_MAGIC)
            if (!isHandle && !head.contentEquals(CARETAKER_MAGIC)) return null
            if (!java.security.MessageDigest.isEqual(m.copyOfRange(STATE_TAG_AT, STATE_TAG_AT + REG_TAG_BYTES), stateTag(nk, m))) return null
            if (m.copyOfRange(STATE_TAG_AT + REG_TAG_BYTES, m.size).any { it.toInt() != 0 }) return null
            val kind = m[3].toInt() and 0xff
            if (isHandle) {
                if (kind !in RECORD_HOLDS..RECORD_MOVED_OUT) return null
                val raw = m.copyOfRange(4, 36)
                if (m.copyOfRange(36, STATE_TAG_AT).any { it.toInt() != 0 }) return null
                val len = raw.indexOfFirst { it.toInt() == 0 }.let { if (it < 0) raw.size else it }
                if (raw.copyOfRange(len, raw.size).any { it.toInt() != 0 }) return null
                val h = String(raw, 0, len, Charsets.US_ASCII)
                if (if (kind == RECORD_HOLDS) !network.erth.wallet.privacy.handles.Handles.valid(h) else h.isNotEmpty()) return null
                return StateRecord.Handle(kind, h)
            }
            val base = kind and SPLIT_UNRECORDED.inv()
            if (base !in RECORD_HOLDS..RECORD_MOVED_OUT || (kind != base && base != RECORD_HOLDS)) return null
            val exp = java.nio.ByteBuffer.wrap(m, 4, 4).int.toLong() and 0xffffffffL
            val entries = m.copyOfRange(SPLIT_AT, STATE_TAG_AT)
            if (base != RECORD_HOLDS) {
                if (exp != 0L || entries.any { it.toInt() != 0 }) return null
                return StateRecord.Caretaker(base, 0, emptyMap())
            }
            if (exp == 0L) return null
            if (kind != base) {
                if (entries.any { it.toInt() != 0 }) return null
                return StateRecord.Caretaker(base, exp, null)
            }
            val split = LinkedHashMap<Long, Long>()
            var i = 0
            var sum = 0L
            while (i < entries.size && entries.copyOfRange(i, entries.size).any { it.toInt() != 0 }) {
                var v = 0L
                var shift = 0
                while (true) {
                    if (i >= entries.size || shift > 56) return null
                    val byte = entries[i++].toInt() and 0xff
                    v = v or ((byte and 0x7f).toLong() shl shift)
                    if (byte < 0x80) break
                    shift += 7
                }
                if (i >= entries.size || v < 0) return null
                val pct = (entries[i++].toInt() and 0xff).toLong()
                if (pct !in 1L..100L || split.containsKey(v)) return null
                split[v] = pct; sum += pct
            }
            if (split.isEmpty() || split.size > MAX_SPLIT_OPTIONS || sum != 100L) return null
            return StateRecord.Caretaker(base, exp, split)
        }

        /**
         * Applies a state record found at note [position] (audit 5, M1): the
         * newest record says what this identity holds, unless the wallet
         * already applied a newer one (or acted since: a reset keeps the
         * cursor), or the record's tx failed in its block ([height] void).
         * A held split's expiry is bounded like any other lease time.
         */
        fun applyStateRecord(s: PrivacyState, position: Long, height: Long, rec: StateRecord, now: Long) {
            if (height in s.voidRecordHeights) return
            when (rec) {
                is StateRecord.Handle -> {
                    if (position <= s.handleRecordPos) return
                    s.handleRecordPos = position
                    when (rec.kind) {
                        RECORD_HOLDS -> if (!s.handleMovedOut) s.handle = rec.handle
                        RECORD_NONE -> s.handle = ""
                        RECORD_MOVED_OUT -> { s.handle = ""; s.handleMovedOut = true }
                    }
                    s.handleSetAt = now
                    // A move's record is in the chain: the move is no longer in doubt (audit 5, M2).
                    settleMoves(s, PendingMove.HANDLE, rec.kind) { it.handle == rec.handle }
                }
                is StateRecord.Caretaker -> {
                    if (position <= s.caretakerRecordPos) return
                    s.caretakerRecordPos = position
                    when (rec.kind) {
                        RECORD_HOLDS -> if (!s.caretakerMovedOut) {
                            val exp = minOf(rec.expiresAt, network.erth.wallet.privacy.handles.Handles.satAdd(now, network.erth.wallet.privacy.handles.Handles.MAX_AHEAD_SECONDS))
                            val same = rec.split != null && rec.split == s.caretakerSplit
                            // The chain's own expiry, when the wallet has it for this split, is later than the record's estimate.
                            s.caretakerExpiresAt = if (same && s.caretakerExpiresAt > exp) s.caretakerExpiresAt else exp
                            s.caretakerSplit = rec.split ?: emptyMap()
                            s.caretakerSplitUnknown = rec.split == null
                        }
                        else -> {
                            s.caretakerSplit = emptyMap(); s.caretakerExpiresAt = 0; s.caretakerSplitUnknown = false
                            if (rec.kind == RECORD_MOVED_OUT) s.caretakerMovedOut = true
                        }
                    }
                    settleMoves(s, PendingMove.CARETAKER, rec.kind) { true }
                }
            }
        }

        /**
         * A HOLDS record settles an incoming move of [kind] ([matches] it), a
         * MOVED_OUT one an outgoing move (kept, confirmed, until recorded in
         * its target).
         */
        private fun settleMoves(s: PrivacyState, kind: String, recordKind: Int, matches: (PendingMove) -> Boolean) {
            val incoming = when (recordKind) { RECORD_HOLDS -> true; RECORD_MOVED_OUT -> false; else -> return }
            s.pendingMoves.replaceAll { if (it.kind == kind && it.incoming == incoming && matches(it)) it.copy(confirmed = true) else it }
            // Audit 6 (M5): a confirmed outgoing move fixes the switch target.
            if (!incoming && s.switchTarget.isEmpty()) {
                s.pendingMoves.firstOrNull { !it.incoming && it.confirmed && it.target.isNotEmpty() }?.let { s.switchTarget = it.target }
            }
            s.pendingMoves.removeAll { it.confirmed && (it.incoming || it.recorded) }
        }

        /** Record notes kept (newest first); only this wallet's own registrations carry a valid tag. */
        const val MAX_RECORDS = 32

        /** Identity leaves kept per record (registrations sharing its block). */
        const val MAX_RECORD_LEAVES = 64

        /**
         * Leaf hashes the fallback search may spend in one sync, across all
         * records: bounds how long a sync holds the wallet lock (a few
         * seconds on a phone at ~50-100 us a Poseidon2 hash).
         */
        const val SYNC_SEARCH_BUDGET = 50_000L

        /** Leaf hashes the fallback search may spend on one record before it is given up (two predecessor_at candidates a time). */
        const val RECORD_SEARCH_CAP = 8_000_000L

        /** predecessor_at candidates per (country, time): 0, or the time itself (a switch or re-entry). */
        const val PREDECESSORS = 2L

        /** The fallback windows around built_at (seconds before, after): hinted countries, then every other. */
        const val NARROW_BEFORE = 3_600L
        const val NARROW_AFTER = 86_400L
        const val WIDE_BEFORE = 600L
        const val WIDE_AFTER = 3_600L

        /**
         * The [i]th offset of an outward walk over [-before, after]: 0, +1,
         * -1, +2, -2, ..., then the longer side alone.
         */
        internal fun offsetAt(i: Long, before: Long, after: Long): Long {
            val m = minOf(before, after)
            return if (i <= 2 * m) {
                if (i == 0L) 0L else if (i % 2 == 1L) (i + 1) / 2 else -(i / 2)
            } else {
                val k = m + (i - 2 * m)
                if (after >= before) k else -k
            }
        }

        /** Every country the chain's leaf may commit to: unknown (0), then each A..Z pair. */
        private val ALL_COUNTRIES: List<Fr> by lazy {
            listOf(Fr.ZERO) + ('A'..'Z').flatMap { a -> ('A'..'Z').map { b -> Privacy.countryField("$a$b") } }
        }

        private fun countryOrZero(c: String): Fr = if (c.isEmpty()) Fr.ZERO else Privacy.countryField(c)
    }

    /**
     * Syncs; on an inconsistency with the indexer, starts over once from an
     * empty store. The whole of it, retries included, is bounded by
     * [syncTimeoutMs].
     */
    fun sync(pageLimit: Int = PAGE_SIZE): Result {
        deadline = monoMs().let { if (it > Long.MAX_VALUE - syncTimeoutMs) Long.MAX_VALUE else it + syncTimeoutMs }
        return try {
            syncRetryingBase(pageLimit)
        } catch (e: Inconsistent) {
            store.reset(chainId)
            syncRetryingBase(pageLimit)
        }
    }

    /**
     * Audit 3: before a sync's first request the roots are unverified, and
     * persisted so: a sync that fails part way (an indexer that serves forged
     * notes and then breaks a later stream) leaves nothing labelled verified.
     * Only [verifyRoots] at the end of this same sync sets the new generation
     * verified.
     */
    private fun markSyncing() {
        val s = store.state
        s.syncGeneration++
        s.rootsVerified = false
        s.rootsError = SYNC_UNFINISHED
        store.save()
    }

    /** A 404 means the indexer's base moved (a relaunch): read the status again, once. */
    private fun syncRetryingBase(pageLimit: Int): Result = try {
        syncOnce(pageLimit)
    } catch (e: IndexerBaseMoved) {
        syncOnce(pageLimit)
    }

    private fun syncOnce(pageLimit: Int): Result {
        markSyncing()
        tick()
        val status = indexer.status()
        status.halted?.let { throw IndexerHalted(it) }
        if (status.chainId == null) throw IllegalStateException("the privacy indexer names no chain yet")
        if (status.chainId != chainId) throw IllegalStateException("the privacy indexer follows ${status.chainId}, not $chainId")
        if (store.state.chainId != chainId || store.state.genesis != status.genesis) {
            switchChain(status.genesis)
            markSyncing()
        }
        readTip()
        ownSpendMissing = false
        val s = store.state
        beginDenoms(s)
        val newNotes = ArrayList<OwnedNote>()
        val spent = ArrayList<OwnedNote>()
        val newStake = ArrayList<OwnedStakeNote>()
        var roots: LatestRoots
        var pass = 0
        while (true) {
            newNotes += syncNotes(s, pageLimit)
            spent += syncNullifiers(s, pageLimit)
            newStake += syncStakeNotes(s, pageLimit)
            syncStakeNullifiers(s, pageLimit)
            syncIdentity(s, pageLimit)
            tick()
            roots = indexer.rootsLatest()
            boundRoots(roots)
            if (atIndexerTip(roots) || ++pass >= MAX_PASSES) break
        }
        releaseStalePending(s)
        val verified = verifyRoots(s, roots)
        // Audit 4 (M5): a registration is matched only against an identity
        // tree this same sync verified against the chain's; an unverified one
        // waits (its leaves are kept) for a sync that verifies. Once a sync,
        // after every pass: the record search is budgeted per sync (K1).
        if (verified) {
            matchRecords(s)
            resolvePending(s)
            // An identity from before (or a reset) whose leaf this verified tree holds is verified now.
            s.identity?.let { id -> if (!id.verified && identityStatus() == IdentityStatus.LIVE) s.identity = id.copy(verified = true) }
        }
        store.save()
        return Result(s.notesHeight, newNotes, spent, store.noteTree.root(), store.identityTree.root(), newStake, identityStatus(), verified)
    }

    /**
     * K6: the indexer names a (chain id, genesis) other than the store's.
     * The LCD must confirm it (its chain id, its block 1 hash) before
     * anything is wiped; an indexer's word alone never drops the identity.
     * A first sync goes ahead when the LCD cannot say (the root checks still
     * guard it) but not when it says otherwise. A confirmed relaunch keeps
     * the identity record (its leaf is re-verified against the new tree by
     * [identityStatus]), the pending registration and the passport
     * nullifier; everything else is the old chain's and goes.
     */
    private fun switchChain(genesis: String?) {
        val old = store.state
        val switching = old.chainId == chainId && old.genesis != null
        val id = runCatching { chain.chainIdentity() }.getOrNull()
        val confirmed = id != null && id.chainId == chainId && id.genesis != null && id.genesis == genesis
        val contradicted = id != null && (id.chainId != chainId || (id.genesis != null && id.genesis != genesis))
        if (genesis == null || contradicted || (switching && !confirmed)) {
            old.rootsVerified = false
            old.rootsError = "unverified: the indexer names genesis ${genesis ?: "none"}, which the chain does not confirm" +
                (id?.let { " (the LCD serves ${it.chainId}, genesis ${it.genesis ?: "unknown"})" } ?: " (the LCD could not say)")
            store.save()
            throw GenesisUnverified(old.rootsError!!)
        }
        // A store from before K6 (same chain id, no genesis recorded) is
        // treated like a switch: the synced data goes, the registration stays.
        val preK6 = old.chainId == chainId && old.genesis == null
        if (switching || preK6) store.switchGenesis(genesis) else store.reset(chainId, genesis)
    }

    /** Every height /roots/latest names, bounded by the chain's tip (audit 4). */
    private fun boundRoots(roots: LatestRoots) {
        bounded("roots synced", roots.syncedHeight)
        listOfNotNull(roots.note, roots.identity, roots.stake).forEach { bounded("root", it.height) }
    }

    /**
     * Whether every local tree is the indexer's latest; a tree of the same
     * size with another root, or a larger one, is inconsistent (the stream
     * and the roots disagree), a smaller one just means the indexer moved on.
     */
    private fun atIndexerTip(roots: LatestRoots): Boolean {
        var tip = true
        fun check(name: String, r: RootRecord?, tree: network.erth.wallet.privacy.zk.MerkleTree) {
            val size = r?.treeSize ?: 0L
            // More than the indexer now says it has: what it served before is not its tree.
            if (tree.size > size) throw Inconsistent("$name tree holds ${tree.size} leaves, the indexer's latest $size")
            if (size != tree.size) { tip = false; return }
            if (r != null && r.root != tree.root()) throw Inconsistent("$name tree root differs from the indexer's at $size")
        }
        check("note", roots.note, store.noteTree)
        check("identity", roots.identity, store.identityTree)
        check("stake", roots.stake, store.stakeTree)
        return tip
    }

    /**
     * C3: every local root against the chain's own (the LCD: PRIVACY_FORMATS
     * 4b says what that trusts). Only a positive contradiction is a mismatch,
     * which wipes the synced data and throws [ChainMismatch]: a note root the
     * chain recorded at another tree size, or an identity or stake tree that
     * differs from the chain's read at exactly the indexer's root height (the
     * node echoed that height). Everything that cannot be established leaves
     * the roots unverified, and the wallet builds nothing on them and labels
     * what it shows until a later sync verifies them (K8, K9): a note root the
     * chain no longer holds (pruned after its retention window: an indexer far
     * behind, or a forged root; the two look the same), a tree read at another
     * height, the indexer still moving, a sampled nullifier the chain does not
     * hold spent, or the indexer trailing the chain's tip.
     */
    private fun verifyRoots(s: PrivacyState, roots: LatestRoots): Boolean {
        val problems = ArrayList<String>()
        var mismatch: String? = null
        if (store.noteTree.size > 0) {
            val rec = chain.noteRoot(store.noteTree.root())
            when {
                rec == null -> problems.add("unverified: the chain no longer holds the indexer's note root (the indexer is behind, or its notes are not the chain's)")
                rec.treeSize != store.noteTree.size ->
                    mismatch = "the chain recorded the indexer's note root at ${rec.treeSize} notes, not ${store.noteTree.size}"
                !rec.valid -> problems.add("unverified: the indexer is too far behind the chain (its note root is no longer an anchor)")
            }
            // Audit 4 (M1): the indexer dates its root as the chain does.
            val h = rec?.height
            if (h != null && roots.note != null && h != roots.note.height) {
                problems.add("unverified: the indexer dates its note root at height ${roots.note.height}, the chain at $h")
            }
        }
        val tip = atIndexerTip(roots)
        if (!tip) problems.add("unverified: the indexer kept moving; sync again")
        // Audit 4 (M1): the height the indexer claims to be synced to is
        // checked, not taken: the chain's note tree at exactly that height
        // (pinned) must be the one served. A stale indexer naming the
        // current height is caught here (every spend appends notes).
        if (mismatch == null && tip && roots.syncedHeight > 0) {
            val t = runCatching { chain.noteTree(roots.syncedHeight) }.getOrNull()
            if (t != null && t.pinned && t.size != store.noteTree.size) {
                problems.add("unverified: the chain's note tree at the indexer's height ${roots.syncedHeight} holds ${t.size} notes, the indexer served ${store.noteTree.size}")
            }
        }
        fun tree(name: String, local: network.erth.wallet.privacy.zk.MerkleTree, r: RootRecord?, read: (Long?) -> TreeState) {
            // Pinned to the indexer's root height, which is only the local
            // tree's when the local tree is the indexer's latest.
            if (mismatch != null || !tip) return
            val height = r?.height?.takeIf { it > 0 } ?: roots.syncedHeight.takeIf { it > 0 }
            val t = read(height)
            val localRoot = if (local.size == 0L) null else local.root()
            when {
                t.size == local.size && (t.root == localRoot || (local.size == 0L && t.root == null)) -> {}
                t.pinned -> mismatch = "the chain's $name tree (${t.size}) at height $height differs from the indexer's (${local.size})"
                else -> problems.add("unverified: the $name tree could not be read at the indexer's height $height")
            }
        }
        tree("identity", store.identityTree, roots.identity, chain::identityTree)
        tree("stake", store.stakeTree, roots.stake, chain::stakeTree)
        mismatch?.let {
            store.reset(chainId)
            store.state.rootsVerified = false
            store.state.rootsError = it
            store.save()
            throw ChainMismatch(it)
        }
        // A sample of the spends this sync read, asked of the chain: an
        // indexer inventing spends is caught without asking about ours.
        if (poolSample.items.any { chain.nullifierSpent(it) == false } || stakeSample.items.any { chain.stakeNullifierSpent(it) == false }) {
            problems.add("unverified: the indexer reported a spend the chain does not hold")
        }
        if (ownSpendMissing) problems.add("unverified: a tx of this wallet is in a block but the indexer did not report its spend")
        // Behind the chain's tip as the LCD says it (audit 4), the indexer's
        // height having been checked against the chain's note tree above.
        val tipHeight = runCatching { readTip().height }.getOrNull()
        if (tipHeight == null) problems.add("unverified: the node did not say its latest height")
        else {
            val behind = tipHeight - roots.syncedHeight
            if (behind > STALE_BLOCKS) problems.add("unverified: the indexer is $behind blocks behind the chain")
        }
        s.rootsVerified = problems.isEmpty()
        s.rootsError = problems.firstOrNull()
        if (s.rootsVerified) {
            s.verifiedGeneration = s.syncGeneration
            s.verifiedHeight = maxOf(s.verifiedHeight, roots.syncedHeight)
        }
        return s.rootsVerified
    }

    private fun checkPage(n: Int, limit: Int) {
        if (n > limit) throw Inconsistent("the indexer sent $n rows in a page of $limit")
    }

    /**
     * Audit 3: a position page must say where it ends (next = from + rows)
     * and a page that says more follows must carry rows; otherwise the same
     * page could be asked for forever while the wallet lock is held.
     */
    private fun checkPositions(name: String, from: Long, rows: Int, next: Long, complete: Boolean) {
        if (next != from + rows) throw Inconsistent("a $name page from $from with $rows rows names next $next")
        if (complete && rows == 0) throw Inconsistent("an empty $name page from $from says more follows")
    }

    /** Audit 3: a height page never moves backwards, and one that says more follows moves forwards. */
    private fun checkHeights(name: String, from: Long, page: HeightPage<*>) {
        bounded(name, page)
        if (page.nextHeight < from || (page.complete && page.nextHeight <= from)) {
            throw Inconsistent("a $name page from height $from names next ${page.nextHeight}")
        }
        if (page.blocks.any { it.first < from }) throw Inconsistent("a $name page from height $from holds an earlier height")
    }

    /**
     * The rows of an aligned position page past [held] (the backend's paging
     * rule): the page starts at [from], so the rows before [held] are ones the
     * wallet holds already, and each must be the very leaf it holds.
     */
    private fun <R> fresh(name: String, rows: List<R>, from: Long, held: Long, position: (R) -> Long, same: (R) -> Boolean): List<R> {
        rows.forEachIndexed { i, r ->
            if (position(r) != from + i) throw Inconsistent("$name at position ${position(r)}, expected ${from + i}")
            if (position(r) > MAX_POSITION) throw Inconsistent("$name position ${position(r)} beyond the tree")
        }
        val n = (held - from).toInt()
        if (rows.size < n) throw Inconsistent("a $name page from $from ends before the $held held")
        for (r in rows.take(n)) if (!same(r)) throw Inconsistent("$name ${position(r)} differs from the one held")
        return rows.drop(n)
    }

    private fun syncNotes(s: PrivacyState, limit: Int): List<OwnedNote> {
        val found = ArrayList<OwnedNote>()
        while (true) {
            tick()
            val from = aligned(s.notesNext, limit)
            val page = indexer.notes(from, limit)
            checkPage(page.rows.size, limit)
            bounded("note synced", page.syncedHeight)
            page.rows.forEach { bounded("note", it.height) }
            checkPositions("note", from, page.rows.size, page.nextPos, page.complete)
            val rows = fresh("note", page.rows, from, s.notesNext, { it.position }) { store.noteTree.leaf(it.position) == it.cm }
            if (rows.isNotEmpty()) {
                // Audit 6 (M3): every row is opened before the tree grows, and
                // a row that cannot be opened is skipped, never thrown on: a
                // throw between the append and the cursor would add the
                // page's notes again on the retry.
                val mine = rows.mapNotNull { r -> openTotal(r) }
                store.noteTree.appendAll(rows.map { it.cm })
                for (n in mine) { found.add(n); s.notes.add(n) }
                s.notesNext += rows.size
            }
            s.notesHeight = maxOf(s.notesHeight, page.syncedHeight)
            if (!page.complete) break
        }
        return found
    }

    /**
     * A note row is ours if its ciphertext opens with our ek and the opening
     * reproduces the cm under our owner key: a v1 ciphertext (217 bytes,
     * value inside), or a value-blind v2 one (177 bytes) against the asset and
     * value the chain published on the row. Every note the chain mints carries
     * v2, so the mnemonic alone finds everything. A value-0 v1 note is kept
     * only as a registration record (its memo).
     */
    /** [open], total: a row it cannot read is not ours (audit 6, M3). */
    private fun openTotal(r: NoteRow): OwnedNote? = try {
        open(r)
    } catch (e: RuntimeException) {
        null
    }

    internal fun open(r: NoteRow): OwnedNote? {
        val s = store.state
        // Audit 6 (M2, M3): the row's amount is only checked here, never
        // learned from: a denom is learned once a note of ours reproduces
        // its cm with it.
        val amount = publicAmount(r.amount)
        val opened: NotePlaintext = when (r.ciphertext.size) {
            // An open mint (chain 203d3b2: the referral note to a handle we
            // hold): no ciphertext, the opening on the row. Ours if its
            // owner_pk is ours and the opening with the public amount
            // recomputes the row's cm (which the tree check pins to the
            // chain's root). Matched here, over the whole stream: no query
            // ever names our owner_pk.
            0 -> {
                val (v, denom) = amount ?: return null
                val owner = r.ownerPk ?: return null
                val rho = r.rho ?: return null
                val rcm = r.rcm ?: return null
                if (owner != keys.ownerPk) return null
                NotePlaintext(denom, v, rho, rcm).takeIf { it.cm(keys.ownerPk) == r.cm } ?: return null
            }
            NoteCipher.BLIND_CIPHERTEXT_BYTES -> {
                val (v, denom) = amount ?: return null
                NoteCipher.tryDecryptBlind(r.ciphertext, r.cm, denom, v, keys) ?: return null
            }
            NoteCipher.CIPHERTEXT_BYTES -> NoteCipher.tryDecrypt(r.ciphertext, r.cm, keys, assetDenoms) ?: return null
            else -> return null
        }
        val note = if (opened.value > 0L) resolved(s, opened) else opened
        // A value past 2^63-1 is not one the wallet can hold (Amounts).
        if (note.value < 0L) return null
        if (note.value == 0L) {
            parseRegMemo(keys.nk, note.memo)?.let { (dsc, country, builtAt) ->
                if (s.regRecords.none { it.position == r.position }) {
                    s.regRecords.add(RegRecord(r.height, r.position, dsc, country, builtAt))
                    if (s.regRecords.size > MAX_RECORDS) s.regRecords.remove(s.regRecords.minBy { it.height })
                }
            }
            parseStateMemo(keys.nk, note.memo)?.let { applyStateRecord(s, r.position, r.height, it, now()) }
            // An unlock's record (K11): the owner-tag counter of the position it closed.
            parseUnlockMemo(keys.nk, note.memo)?.let { c -> if (c > s.closedOtagMax) s.closedOtagMax = c }
            return null
        }
        return OwnedNote(r.position, r.height, note, r.cm, Privacy.nf(keys.nk, note.rho, r.position))
    }

    private fun publicAmount(amount: String?): Pair<Long, String>? {
        if (amount == null) return null
        val digits = amount.takeWhile { it in '0'..'9' }
        val denom = amount.substring(digits.length)
        // Audit 6 (M3): an SDK denom only; never the wallet's own "asset/" name.
        if (digits.isEmpty() || !Denoms.valid(denom)) return null
        return (network.erth.wallet.privacy.Amounts.parseU64(digits) ?: return null) to denom
    }

    /**
     * Audit 6 (M2): the persisted denoms are the ones of notes this wallet
     * holds (anything else a store learned before is dropped), at most
     * [Denoms.MAX], and the lookup is built from them once for the sync.
     * A held note named "asset/<hex>" whose id is now known is renamed
     * (same asset, same cm), which also undoes audit 6 M3's relabel.
     */
    private fun beginDenoms(s: PrivacyState) {
        chainAssetsRead = false
        validatorsRead = false
        val own = (s.notes.map { it.note.denom } + s.stakeNotes.map { it.denom }).filterTo(sortedSetOf(), Denoms::valid)
        s.denoms.clear()
        own.take(Denoms.MAX).forEach { s.denoms.add(it) }
        assetDenoms = AssetDenoms(s.denoms)
        for (i in s.notes.indices) {
            val n = s.notes[i]
            if (n.note.denom.startsWith(NotePlaintext.UNRESOLVED_PREFIX)) {
                val r = resolved(s, n.note)
                if (r.denom != n.note.denom) s.notes[i] = n.copy(note = r)
            }
        }
    }

    /**
     * A note of ours, its denom resolved: one already a denom is learned
     * (its cm, checked by the caller, binds it); an "asset/<hex>" one is
     * looked up in the chain's asset list, read at most once a sync.
     */
    private fun resolved(s: PrivacyState, n: NotePlaintext): NotePlaintext {
        if (!n.denom.startsWith(NotePlaintext.UNRESOLVED_PREFIX)) {
            if (assetDenoms.learn(n.denom) && s.denoms.size < Denoms.MAX) s.denoms.add(n.denom)
            return n
        }
        val asset = runCatching { n.asset }.getOrNull() ?: return n
        if (!chainAssetsRead) {
            chainAssetsRead = true
            runCatching { chain.assets() }.getOrNull()?.forEach { (d, id) -> assetDenoms.learn(d, id) }
        }
        val d = assetDenoms.resolve(asset)
        if (d.startsWith(NotePlaintext.UNRESOLVED_PREFIX)) return n
        if (s.denoms.size < Denoms.MAX) s.denoms.add(d)
        return n.copy(denom = d)
    }

    private fun syncNullifiers(s: PrivacyState, limit: Int): List<OwnedNote> {
        val mine = s.notes.withIndex().filter { it.value.unspent }.associate { it.value.nf to it.index }
        // The spot-check sample never holds one of ours (spent or not): asking
        // the chain about it would name our note (PRIVACY_FORMATS 4b).
        val own = s.notes.mapTo(HashSet()) { it.nf }
        val spent = ArrayList<OwnedNote>()
        // Only up to the height the note stream reached: a note found next
        // time could otherwise have been spent in a block this pass skipped.
        val ceiling = s.notesHeight
        while (s.nullifiersNext <= ceiling) {
            tick()
            val page = indexer.nullifiers(s.nullifiersNext, limit)
            checkPage(page.blocks.sumOf { it.second.size }, maxOf(limit, MAX_HEIGHT_PAGE_ROWS))
            checkHeights("nullifier", s.nullifiersNext, page)
            for ((h, nfs) in page.blocks) {
                if (h > ceiling) break
                for (nf in nfs) if (nf !in own) poolSample.offer(nf)
                for (nf in nfs) mine[nf]?.let { i ->
                    val n = s.notes[i].copy(spentHeight = h)
                    s.notes[i] = n
                    spent.add(n)
                }
            }
            s.nullifiersNext = minOf(page.nextHeight, ceiling + 1)
            if (!page.complete) break
        }
        return spent
    }

    /**
     * A note marked pending by a broadcast is released (spendable again) only
     * once its tx can no longer land (audit 3): the chain's tip (LCD) is past
     * the tx's timeout_height and this wallet has read the nullifier stream
     * through that height without seeing its nullifier. Never by the wall
     * clock, which says nothing about the chain. Marks made before txs carried
     * a timeout (no pendingUntil) keep the old 15-minute rule.
     */
    private fun releaseStalePending(s: PrivacyState) {
        val now = now()
        val tipHeight by lazy { runCatching { readTip().height }.getOrNull() }
        val status = HashMap<String, TxStatus?>()
        fun release(pendingAt: Long?, until: Long?, hash: String?, readThrough: Long): Boolean = when {
            pendingAt == null -> false
            until == null -> now - pendingAt > PENDING_TIMEOUT_S
            // Audit 6 (M4): a timeout no sane tip gives (a node inflated the
            // tip at send) is never reached: the tx's status alone settles
            // it, after the mempool's grace.
            !PrivateTxEngine.timeoutSane(until, s.verifiedHeight) -> hash != null && now - pendingAt > PENDING_TIMEOUT_S &&
                status.getOrPut(hash) { runCatching { chain.txStatus(hash) }.getOrNull() }.let { it == TxStatus.MISSING || it == TxStatus.FAILED }
            readThrough < until || tipHeight?.let { it > until } != true -> false
            // Marks from before audit 4 carry no hash: the timeout alone.
            hash == null -> true
            // Audit 4 (M1): the chain itself says the tx did not land (or failed in its block).
            else -> when (status.getOrPut(hash) { runCatching { chain.txStatus(hash) }.getOrNull() }) {
                TxStatus.MISSING, TxStatus.FAILED -> true
                TxStatus.COMMITTED -> { ownSpendMissing = true; false }
                null -> false
            }
        }
        for (i in s.notes.indices) {
            val n = s.notes[i]
            if (n.unspent && release(n.pendingAt, n.pendingUntil, n.pendingTx, s.nullifiersNext - 1)) s.notes[i] = n.copy(pendingAt = null, pendingUntil = null, pendingTx = null)
        }
        for (i in s.stakeNotes.indices) {
            val n = s.stakeNotes[i]
            if (n.unspent && release(n.pendingAt, n.pendingUntil, n.pendingTx, s.stakeNullifiersNext - 1)) s.stakeNotes[i] = n.copy(pendingAt = null, pendingUntil = null, pendingTx = null)
        }
    }

    private fun syncStakeNotes(s: PrivacyState, limit: Int): List<OwnedStakeNote> {
        val found = ArrayList<OwnedStakeNote>()
        while (true) {
            tick()
            val from = aligned(s.stakeNext, limit)
            val page = indexer.stakeNotes(from, limit)
            checkPage(page.rows.size, limit)
            bounded("stake note synced", page.syncedHeight)
            page.rows.forEach { bounded("stake note", it.height) }
            checkPositions("stake note", from, page.rows.size, page.nextPos, page.complete)
            val rows = fresh("stake note", page.rows, from, s.stakeNext, { it.position }) { store.stakeTree.leaf(it.position) == it.cm }
            if (rows.isNotEmpty()) {
                // As syncNotes (audit 6, M3): opened first, a bad row skipped.
                val mine = rows.mapNotNull { r -> try { openStake(r) } catch (e: RuntimeException) { null } }
                store.stakeTree.appendAll(rows.map { it.cm })
                for (n in mine) { found.add(n); s.stakeNotes.add(n) }
                s.stakeNext += rows.size
            }
            s.stakeHeight = maxOf(s.stakeHeight, page.syncedHeight)
            if (!page.complete) break
        }
        return found
    }

    /**
     * A stake row is ours if its ciphertext opens (the wallet stake note,
     * 201 bytes, label inside): every stake note is a stake proof's output
     * (chain dff3a9b). A zero note (a full exit's padding output) is dropped.
     */
    internal fun openStake(r: StakeNoteRow): OwnedStakeNote? {
        val s = store.state
        val o = NoteCipher.tryDecryptStake(r.ciphertext, r.cm, keys) ?: return null
        // Zero, or past 2^63-1 (negative as a Long): nothing the wallet holds (Amounts).
        if (o.amount <= 0L) return null
        val denom = stakeDenom(o.asset) ?: return null
        if (s.denoms.size < Denoms.MAX) s.denoms.add(denom)
        return OwnedStakeNote(r.position, r.height, denom, o.amount, o.rho, o.rcm, r.cm, Privacy.stakeNf(keys.nk, o.rho, r.position), label = o.label)
    }

    /**
     * derth/<valoper> for a stake note's asset id: known already, or found
     * among the chain's validators (read at most once a sync; each candidate
     * learned only as the hash of its own name). Null: no validator's (a stake
     * note of an asset the wallet cannot name is not one it can use).
     */
    private fun stakeDenom(asset: Fr): String? {
        assetDenoms.resolve(asset).takeIf { !it.startsWith(NotePlaintext.UNRESOLVED_PREFIX) }?.let { return it }
        if (!validatorsRead) {
            validatorsRead = true
            runCatching { chain.validatorOperators() }.getOrNull()?.forEach { op -> assetDenoms.learn("derth/$op") }
        }
        return assetDenoms.resolve(asset).takeIf { !it.startsWith(NotePlaintext.UNRESOLVED_PREFIX) }
    }

    private fun syncStakeNullifiers(s: PrivacyState, limit: Int) {
        val mine = s.stakeNotes.withIndex().filter { it.value.unspent }.associate { it.value.nf to it.index }
        val own = s.stakeNotes.mapTo(HashSet()) { it.nf }
        val ceiling = s.stakeHeight
        while (s.stakeNullifiersNext <= ceiling) {
            tick()
            val page = indexer.stakeNullifiers(s.stakeNullifiersNext, limit)
            checkPage(page.blocks.sumOf { it.second.size }, maxOf(limit, MAX_HEIGHT_PAGE_ROWS))
            checkHeights("stake nullifier", s.stakeNullifiersNext, page)
            for ((h, nfs) in page.blocks) {
                if (h > ceiling) break
                for (nf in nfs) if (nf !in own) stakeSample.offer(nf)
                for (nf in nfs) mine[nf]?.let { i -> s.stakeNotes[i] = s.stakeNotes[i].copy(spentHeight = h) }
            }
            s.stakeNullifiersNext = minOf(page.nextHeight, ceiling + 1)
            if (!page.complete) break
        }
    }

    /**
     * Identity leaves and zeroings, up to the height the notes reached (so a
     * registration's record note is always seen no later than its leaf).
     * Each appended leaf at the height of an unmatched record note is tried
     * against it: that is how a wallet restored from the mnemonic finds its
     * registration, with no query naming it.
     */
    private fun syncIdentity(s: PrivacyState, limit: Int) {
        val ceiling = s.notesHeight
        // Zeroings of leaves already held, first: a leaf appended below
        // carries its own zeroed_height.
        while (s.zeroedNext <= ceiling) {
            tick()
            val page = indexer.identityZeroed(s.zeroedNext, limit)
            checkPage(page.blocks.sumOf { it.second.size }, maxOf(limit, MAX_HEIGHT_PAGE_ROWS))
            checkHeights("identity zeroing", s.zeroedNext, page)
            val updates = HashMap<Long, Fr>()
            for ((h, idxs) in page.blocks) {
                if (h > ceiling) break
                for (i in idxs) if (i < store.identityTree.size) updates[i] = Fr.ZERO
            }
            store.identityTree.updateAll(updates)
            s.zeroedNext = minOf(page.nextHeight, ceiling + 1)
            if (!page.complete) break
        }
        val recordHeights = s.regRecords.map { it.height }.toSet()
        outer@ while (true) {
            tick()
            val from = aligned(s.identityNext, limit)
            val page = indexer.identity(from, limit)
            checkPage(page.rows.size, limit)
            bounded("identity synced", page.syncedHeight)
            for (r in page.rows) {
                bounded("identity", r.height)
                r.zeroedHeight?.let { bounded("identity zeroing", it) }
                // Audit 4 (H1): a row's block time is one of this chain's.
                if (r.time != null && r.time != 0L && !timeOk(r.time)) throw Inconsistent("identity leaf ${r.index} carries block time ${r.time}, outside the chain's")
            }
            // Rows already held are dropped unchecked: a leaf zeroed since reads differently.
            val rest = fresh("identity leaf", page.rows, from, s.identityNext, { it.index }) { true }
            if (rest.isEmpty()) break
            val take = rest.takeWhile { it.height <= ceiling }
            // A leaf zeroed at or below the ceiling is zero here; one zeroed
            // later is zeroed by a later pass's zeroed stream.
            store.identityTree.appendAll(take.map { if (it.zeroedHeight != null && it.zeroedHeight <= ceiling) Fr.ZERO else it.leaf })
            // Each record keeps the leaves of its block as they pass (persisted:
            // never streamed again), and the block's time if the row carries it.
            for (r in take) offerIdentityHeight(s, r.height)
            for (r in take) if (r.height in recordHeights) {
                val leaf = store.identityTree.leaf(r.index)
                for (k in s.regRecords.indices) {
                    var rec = s.regRecords[k]
                    if (rec.height != r.height) continue
                    if (rec.leaves.size < MAX_RECORD_LEAVES && rec.leaves.none { it.first == r.index }) {
                        rec = rec.copy(leaves = rec.leaves + (r.index to leaf))
                    }
                    if (r.time != null && r.time > 0 && rec.time == null) rec = rec.copy(time = r.time)
                    s.regRecords[k] = rec
                }
            }
            s.identityNext += take.size
            if (take.size < rest.size || s.identityNext >= page.size || page.rows.size < limit) break@outer
        }
    }

    /** A uniform sample of identity row heights (registration blocks), the cover set's decoys (audit 4, M2). */
    private fun offerIdentityHeight(s: PrivacyState, h: Long) {
        s.identityRowsSeen++
        if (s.identityHeights.size < IDENTITY_HEIGHT_SAMPLE) s.identityHeights.add(h)
        else (rng.nextDouble() * s.identityRowsSeen).toLong().let { j -> if (j < IDENTITY_HEIGHT_SAMPLE) s.identityHeights[j.toInt()] = h }
    }

    /**
     * Matches record notes to the leaves appended at their heights (several
     * registrations may share a block), newest record first, stopping at the
     * newest that matched (it is the identity). K1, bounded, and (audit 3)
     * never asking the LCD about this wallet's own registration block alone:
     *
     *  1. activated_at is the registration block's time. The indexer's
     *     identity rows carry it (the record keeps it as the leaves pass):
     *     every country (hint, unknown, then A..Z) at exactly that time, at
     *     most 677 hashes a leaf.
     *  2. When the rows carry no time (or it did not match), the LCD is asked
     *     for the block times of a cover set: the record's height among
     *     [COVER_SET] - 1 others drawn uniformly from the chain so far, in a
     *     shuffled order, the same set on every retry. Its time is tried the
     *     same way; known and unmatched, the record is given up.
     *  3. Only when no block time can be had, the device clock's built_at is
     *     searched outward, hinted countries over [-1h, +24h], then every
     *     other over [-10min, +1h], resumably: the cursor and the hashes
     *     spent are persisted, each sync spends at most [SYNC_SEARCH_BUDGET]
     *     hashes over all records and a record at most [RECORD_SEARCH_CAP].
     *
     * A time already tried is not tried again; a new one (or new leaves at
     * the record's height) is, even after the record was given up (K13).
     */
    private fun matchRecords(s: PrivacyState) {
        var budget = searchBudget
        for (rec0 in s.regRecords.sortedByDescending { it.height }) {
            if (rec0.status == RecordStatus.MATCHED) return
            val leaves = rec0.leaves.filter { it.second != Fr.ZERO }
            if (leaves.isEmpty()) continue
            val k = s.regRecords.indexOfFirst { it.position == rec0.position }
            var rec = rec0
            // New leaves since the last attempt: every time is worth trying again.
            if (leaves.size > rec.leavesTried) rec = rec.copy(tried = emptyList(), leavesTried = leaves.size,
                status = if (rec.status == RecordStatus.EXHAUSTED) RecordStatus.OPEN else rec.status, cursor = 0)
            var found: LeafMatch? = null
            var lcdTime: Long? = null
            // 1. The indexer's block time (bounded by the chain's tip as it streamed, audit 4).
            rec.time?.takeIf(::timeOk)?.let { t -> if (t !in rec.tried) { found = tryTime(rec, leaves, t); rec = rec.copy(tried = rec.tried + t, work = rec.work + PREDECESSORS * ALL_COUNTRIES.size.toLong() * leaves.size) } }
            // 2. The LCD's, asked with a cover set.
            if (found == null && rec.tried.containsAll(listOfNotNull(rec.time?.takeIf(::timeOk)))) {
                val (t0, r) = coverTime(rec)
                val t = t0?.takeIf(::timeOk)
                rec = r
                lcdTime = t
                if (t != null && t !in rec.tried) {
                    found = tryTime(rec, leaves, t)
                    rec = rec.copy(tried = rec.tried + t, work = rec.work + PREDECESSORS * ALL_COUNTRIES.size.toLong() * leaves.size)
                }
                if (found == null && t != null) rec = rec.copy(status = RecordStatus.EXHAUSTED)
            }
            // 3. The bounded fallback, only while no chain time is known.
            if (found == null && lcdTime == null && rec.status == RecordStatus.OPEN) {
                val before = rec.work
                val r = search(rec, leaves, budget)
                budget -= r.second.work - before
                found = r.first
                rec = r.second
            }
            if (found != null) rec = rec.copy(status = RecordStatus.MATCHED)
            s.regRecords[k] = rec
            found?.let { (index, country, at, pred) ->
                val cur = s.identity
                // At an index at least the identity's: a match there replaces
                // one made before (audit 4, M5: an identity from an unverified
                // tree, or another time, is re-matched rather than kept).
                if (cur == null || index >= cur.leafIndex) {
                    s.identity = IdentityRecord(index, rec.dscKey, country, at, cur?.takeIf { it.leafIndex == index }?.passportNullifier ?: "", verified = true, predecessorAt = pred)
                }
                return
            }
            if (budget <= 0) return
        }
    }

    /** A leaf matched: its index, country, activated_at and predecessor_at. */
    internal data class LeafMatch(val index: Long, val country: Fr, val at: Long, val pred: Long)

    /**
     * The predecessor_at with which [leaf] is ours at ([dscKey], [country],
     * activated_at [t]), or null. The chain sets it to the registration's
     * own block time for a switch or re-entry, 0 for a passport never seen
     * before, so those are the only two values to try.
     */
    private fun predecessorOf(leaf: Fr, dscKey: Fr, country: Fr, t: Long): Long? =
        (if (t == 0L) listOf(0L) else listOf(0L, t)).firstOrNull { Privacy.identityLeaf(keys.idc, dscKey, country, t, it) == leaf }

    /** The match if a leaf of [rec] is ours at activated_at = [t]. */
    private fun tryTime(rec: RegRecord, leaves: List<Pair<Long, Fr>>, t: Long): LeafMatch? {
        val countries = (listOf(countryOrZero(rec.country)) + ALL_COUNTRIES).distinct()
        return leaves.firstNotNullOfOrNull { (i, leaf) ->
            countries.firstNotNullOfOrNull { c -> predecessorOf(leaf, rec.dscKey, c, t)?.let { LeafMatch(i, c, t, it) } }
        }
    }

    /**
     * [rec]'s block time from the LCD, asked together with a cover set of
     * other heights (chosen once, uniformly over the chain so far, persisted
     * with the record so a retry asks the same set), in a shuffled order. At
     * most [MAX_COVER_TRIES] fetches; once the LCD answered, its answer is
     * kept and never asked again.
     */
    private fun coverTime(rec: RegRecord): Pair<Long?, RegRecord> {
        rec.chainTime?.let { return it to rec }
        if (rec.coverTries >= MAX_COVER_TRIES) return null to rec
        // Never past the chain's tip (audit 4, M2): a decoy the chain has not
        // reached yet is no decoy, the LCD sees which height is real.
        val tipHeight = tip?.height ?: readTip().height
        val top = minOf(maxOf(store.state.notesHeight, rec.height), tipHeight).coerceAtLeast(1)
        val cover = rec.cover.ifEmpty {
            val set = LinkedHashSet<Long>().apply { add(rec.height) }
            // Decoys are other registrations' blocks first (a block time is
            // asked for exactly those when restoring), then any height.
            store.state.identityHeights.filter { it in 1..top && it != rec.height }.distinct().shuffled(rng)
                .take(COVER_SET - 1).forEach { set.add(it) }
            val want = minOf(COVER_SET.toLong(), top).toInt()
            while (set.size < want) set.add(1 + (rng.nextDouble() * top).toLong().coerceIn(0, top - 1))
            set.toList()
        }
        var t: Long? = null
        for (h in cover.shuffled(rng)) {
            val x = runCatching { chain.blockTime(h) }.getOrNull()
            if (h == rec.height) t = x
        }
        return t to rec.copy(cover = cover, coverTries = rec.coverTries + 1, chainTime = t)
    }

    /**
     * The fallback search for [rec] from its persisted cursor, spending at
     * most [budget] hashes (and the record's cap): (index, country,
     * activated_at) if found, with the record's new state.
     */
    private fun search(rec: RegRecord, leaves: List<Pair<Long, Fr>>, budget: Long): Pair<LeafMatch?, RegRecord> {
        val hinted = listOf(countryOrZero(rec.country), Fr.ZERO).distinct()
        val others = ALL_COUNTRIES - hinted.toSet()
        val narrowSteps = NARROW_BEFORE + NARROW_AFTER + 1
        val total = narrowSteps + WIDE_BEFORE + WIDE_AFTER + 1
        var cursor = rec.cursor
        var work = rec.work
        var spent = 0L
        while (cursor < total) {
            val narrow = cursor < narrowSteps
            val countries = if (narrow) hinted else others
            val cost = PREDECESSORS * countries.size.toLong() * leaves.size
            if (spent > 0 && spent + cost > budget) break
            if (work + cost > RECORD_SEARCH_CAP) return null to rec.copy(status = RecordStatus.EXHAUSTED, cursor = cursor, work = work)
            val off = if (narrow) offsetAt(cursor, NARROW_BEFORE, NARROW_AFTER) else offsetAt(cursor - narrowSteps, WIDE_BEFORE, WIDE_AFTER)
            cursor++
            work += cost
            spent += cost
            // Checked (audit 4, H1): a candidate that wraps, or that cannot be a block time, is skipped.
            val t = runCatching { Math.addExact(rec.builtAt, off) }.getOrNull() ?: continue
            if (!timeOk(t)) continue
            for ((i, leaf) in leaves) for (c in countries) {
                predecessorOf(leaf, rec.dscKey, c, t)?.let { pred ->
                    return LeafMatch(i, c, t, pred) to rec.copy(status = RecordStatus.MATCHED, cursor = cursor, work = work)
                }
            }
        }
        return null to rec.copy(status = if (cursor >= total) RecordStatus.EXHAUSTED else RecordStatus.OPEN, cursor = cursor, work = work)
    }

    /**
     * C2: a committed registration whose leaf the wallet has not matched
     * yet. Once the local identity tree holds its index, the leaf is
     * recomputed for the hinted country, unknown, and every A..Z pair; the
     * identity record replaces the pending one. It is never dropped
     * unmatched: a failure is recorded for the UI and retried.
     */
    private fun resolvePending(s: PrivacyState) {
        val p = s.pendingRegistration ?: return
        // Not committed yet as far as the wallet knows (PrivacyWallet.sync looks it up by hash).
        val index = p.leafIndex ?: return
        val activatedAt = p.activatedAt ?: return
        if (index >= store.identityTree.size) {
            s.pendingRegistration = p.copy(failure = null)
            return
        }
        val leaf = store.identityTree.leaf(index)
        val m = countryFor(leaf, p.dscKey, activatedAt, p.countryHint)
        if (m != null) {
            s.identity = IdentityRecord(index, p.dscKey, m.first, activatedAt, p.passportNullifier, verified = true, predecessorAt = m.second)
            s.pendingRegistration = null
        } else {
            s.pendingRegistration = p.copy(
                failure = if (leaf == Fr.ZERO) "the registration's leaf $index has been zeroed"
                else "leaf $index does not match this registration",
            )
        }
    }

    /** (country, predecessor_at) with which [leaf] is ours at [activatedAt], or null. */
    internal fun countryFor(leaf: Fr, dscKey: Fr, activatedAt: Long, hint: String = ""): Pair<Fr, Long>? {
        if (leaf == Fr.ZERO) return null
        val hinted = listOf(countryOrZero(hint))
        return (hinted + ALL_COUNTRIES).firstNotNullOfOrNull { c -> predecessorOf(leaf, dscKey, c, activatedAt)?.let { c to it } }
    }

    fun identityStatus(): IdentityStatus {
        val id = store.state.identity ?: return IdentityStatus.NONE
        if (id.leafIndex >= store.identityTree.size) return IdentityStatus.NONE
        val want = Privacy.identityLeaf(keys.idc, id.dscKey, id.country, id.activatedAt, id.predecessorAt)
        return if (store.identityTree.leaf(id.leafIndex) == want) IdentityStatus.LIVE else IdentityStatus.ZEROED
    }
}
