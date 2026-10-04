package network.erth.wallet.privacy.sync

import network.erth.wallet.privacy.zk.Fr
import okio.ByteString.Companion.decodeBase64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * The privacy indexer's streams (backend routers/privacy.py). Full ranges
 * only: every request is addressed by a position, index or height, never by
 * anything derived from this wallet's keys, so the server learns nothing
 * about which notes or leaf are ours.
 *
 * Streams live under a base keyed by the chain (`/privacy/<chain_id>/<genesis>`)
 * that [status] names; a stream call naming another chain fails with
 * [IndexerBaseMoved] (HTTP 404), and the caller reads [status] again.
 */
interface PrivacyIndexer {
    /** `/privacy/status`: the chain the index holds, its base, whether it halted. Also (re)selects the base. */
    fun status(): IndexerStatus
    fun notes(fromPos: Long, limit: Int? = null): NotesPage
    fun nullifiers(fromHeight: Long, limit: Int? = null): HeightPage<Fr>
    fun identity(fromIndex: Long, limit: Int? = null): IdentityPage
    fun identityZeroed(fromHeight: Long, limit: Int? = null): HeightPage<Long>
    fun rootsLatest(): LatestRoots
    fun rates(epoch: Long? = null): List<RateRow>
    /** x/shieldedstaking's stake note tree, by position. */
    fun stakeNotes(fromPos: Long, limit: Int? = null): StakeNotesPage
    /** Its spent nullifiers, by height. */
    fun stakeNullifiers(fromHeight: Long, limit: Int? = null): HeightPage<Fr>
    /**
     * The stake nullifier tree's values by leaf index, in insertion order
     * (leaf 1 is the first value; leaf 0, the sentinel, is never a row):
     * what a stake vote rebuilds the snapshot's nullifier tree from.
     */
    fun stakeNullifierLeaves(fromIndex: Long, limit: Int? = null): StakeNfLeavesPage
    /** Every proposal snapshot (what stake votes prove against), by height. */
    fun stakeSnapshots(fromHeight: Long, limit: Int? = null): StakeSnapshotsPage
    /**
     * The handle directory, whole (there is no endpoint for one handle): a
     * snapshot of the chain's Handles query at one height, paged by place
     * from [fromIndex] (aligned).
     */
    fun handles(fromIndex: Long, limit: Int): network.erth.wallet.privacy.handles.HandleDirectory.StreamPage =
        throw UnsupportedOperationException("this indexer serves no handle directory")
    /**
     * The slash debt tree, whole, by leaf index (rows from leaf 1, each with
     * its latest retained), its size and root as the indexer synced them
     * (chain dff3a9b). The wallet checks the rebuilt root against the
     * chain's own Query/DebtTree before using a row.
     */
    fun debtRows(fromIndex: Long, limit: Int): DebtRowsPage =
        throw UnsupportedOperationException("this indexer serves no debt rows")
}

/** /debt_rows: [rows] (leaf index, move key, retained), [size] the leaf count (sentinel included, 0 when empty), [root] at the synced height. */
data class DebtRowsPage(val rows: List<Triple<Long, Fr, Long>>, val nextIndex: Long, val complete: Boolean, val size: Long, val root: Fr?)

data class IndexerStatus(
    val chainId: String?,
    val syncedHeight: Long,
    val syncedTime: Long?,
    val notes: Long,
    val identityLeaves: Long,
    val halted: String?,
    /** First 16 hex digits of the chain's first block hash: with chainId, which chain this is. */
    val genesis: String? = null,
    /** `/privacy/<chain_id>/<genesis>`, null until the indexer met its chain. */
    val base: String? = null,
)

/** A stream request named a chain the indexer no longer holds (404): re-read [PrivacyIndexer.status]. */
class IndexerBaseMoved(message: String) : java.io.IOException(message)

/** The indexer refuses to serve trees it cannot vouch for (`/privacy/status` halted). */
class IndexerHalted(val reason: String) : java.io.IOException("the privacy indexer has halted: $reason")

/**
 * A note tree leaf. [amount] is set for a note whose value is public (a
 * shield, a module mint). A note the chain minted with an opening it chose
 * (MintOpenNote: the referral note a registration pays its referrer handle)
 * has no ciphertext and carries the opening from its `shielded_mint` event,
 * [ownerPk], [rho] and [rcm]; every other row has them null.
 */
data class NoteRow(
    val position: Long,
    val height: Long,
    val cm: Fr,
    val ciphertext: ByteArray,
    val amount: String?,
    val ownerPk: Fr? = null,
    val rho: Fr? = null,
    val rcm: Fr? = null,
)

data class NotesPage(val rows: List<NoteRow>, val nextPos: Long, val complete: Boolean, val syncedHeight: Long)

data class HeightPage<T>(val blocks: List<Pair<Long, List<T>>>, val nextHeight: Long, val complete: Boolean, val syncedHeight: Long)

/** An identity leaf; [time] is its block's time (unix seconds) when the indexer serves it (a fifth column), null otherwise. */
data class IdentityRow(val index: Long, val height: Long, val leaf: Fr, val zeroedHeight: Long?, val time: Long? = null)

data class IdentityPage(val rows: List<IdentityRow>, val nextIndex: Long, val size: Long, val syncedHeight: Long)

data class RootRecord(val root: Fr, val treeSize: Long, val height: Long, val time: Long)

data class LatestRoots(val note: RootRecord?, val identity: RootRecord?, val syncedHeight: Long, val stake: RootRecord? = null)

/**
 * A stake tree leaf: every one a stake proof's output with its 201-byte
 * wallet stake ciphertext (chain dff3a9b: the chain mints no stake note, so
 * no row has a public denom, amount or pc any more).
 */
data class StakeNoteRow(
    val position: Long,
    val height: Long,
    val cm: Fr,
    val ciphertext: ByteArray,
)

data class StakeNotesPage(val rows: List<StakeNoteRow>, val nextPos: Long, val complete: Boolean, val syncedHeight: Long)

/** [leaves]: (leaf index, value); [size] the tree's leaf count as the chain counts it (sentinel included, 0 when empty). */
data class StakeNfLeavesPage(val leaves: List<Pair<Long, Fr>>, val nextIndex: Long, val complete: Boolean, val size: Long, val syncedHeight: Long)

/** A proposal snapshot: the stake note tree's root and size and the stake nullifier tree's (null roots: none recorded). */
data class StakeSnapshotRow(val height: Long, val proposalId: Long, val root: Fr?, val treeSize: Long, val nfRoot: Fr?, val nfSize: Long)

data class StakeSnapshotsPage(val rows: List<StakeSnapshotRow>, val nextHeight: Long, val complete: Boolean, val syncedHeight: Long)

data class RateRow(val validator: String, val rate: String, val supply: String, val epoch: Long?, val height: Long)

/**
 * [PrivacyIndexer] over HTTP. Blocking; call from an IO thread. Only ever
 * talks to [host]: the status's `base` is accepted only as exactly
 * `/privacy/<chain_id>/<genesis>` for [chainId], so a hostile status
 * cannot point the stream requests anywhere else.
 */
class HttpPrivacyIndexer(
    private val host: String,
    private val chainId: String = network.erth.wallet.Constants.EARTH_CHAIN_ID,
    /** Waits between retries of a busy indexer (tests pass their own). */
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) : PrivacyIndexer {
    @Volatile private var base: String? = null
    private val hostUrl = URL(host.trimEnd('/'))

    /** The indexer shed the request (503, its in-flight cap) or rate-limited this client (429). */
    private class Busy(val code: Int, val retryAfter: Long?) : IOException("the privacy indexer is busy ($code)")

    /**
     * [getOnce], backing off while the indexer sheds load (/privacy
     * answers 503 with Retry-After past its in-flight cap, 429 past a
     * client's rate): Retry-After or 1, 2, 4, 8 s (at most 30), then an
     * error the sync reports like any other.
     */
    private fun get(path: String): JSONObject {
        var attempt = 0
        while (true) {
            try {
                return getOnce(path)
            } catch (e: Busy) {
                if (++attempt > MAX_BUSY_RETRIES) throw IOException("the privacy indexer is busy (${e.code}); try again later")
                sleep(backoffMs(e.retryAfter, attempt))
            }
        }
    }

    private fun getOnce(path: String): JSONObject {
        require(path.startsWith("/") && !path.startsWith("//")) { "indexer path $path" }
        val url = URL(hostUrl.toString() + path)
        if (url.protocol != hostUrl.protocol || url.host != hostUrl.host || url.port != hostUrl.port || url.userInfo != null) {
            throw IOException("indexer path $path leaves ${hostUrl.host}")
        }
        val c = url.openConnection() as HttpURLConnection
        // A redirect is never followed (it would leave the pinned host); a 3xx is an error.
        c.instanceFollowRedirects = false
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        c.setRequestProperty("Accept-Encoding", "gzip")
        try {
            val code = c.responseCode
            val raw = if (code in 200..299) c.inputStream else c.errorStream
            val stream = if (c.contentEncoding == "gzip") GZIPInputStream(raw) else raw
            val body = stream?.let { readBounded(it, MAX_BODY_BYTES) }.orEmpty()
            if (code == 404) throw IndexerBaseMoved("indexer $path: 404 ${body.take(200)}")
            if (code == 503 || code == 429) throw Busy(code, c.getHeaderField("Retry-After")?.trim()?.toLongOrNull())
            if (code !in 200..299) throw IOException("indexer $path: $code ${body.take(200)}")
            network.erth.wallet.chain.EarthRest.checkJsonDepth(body)
            return JSONObject(body)
        } finally {
            c.disconnect()
        }
    }

    /** A stream path under the current base (from [status], read first when unknown). */
    private fun stream(path: String): JSONObject {
        val b = base ?: status().base ?: throw IOException("the privacy indexer has not met its chain yet")
        return get(b + path)
    }

    private fun q(name: String, v: Any?): String = if (v == null) "" else "&$name=$v"

    /** The backend serves only its fixed page sizes; anything else is a 400. */
    private fun limit(limit: Int?): String {
        require(limit == null || limit in WalletSync.PAGE_SIZES) { "page size $limit is not one the indexer serves" }
        return q("limit", limit)
    }

    override fun status(): IndexerStatus = get("/privacy/status").let { j ->
        IndexerStatus(
            chainId = j.optString("chain_id").ifEmpty { null },
            syncedHeight = j.optLong("synced_height"),
            syncedTime = if (j.isNull("synced_time")) null else j.optLong("synced_time"),
            notes = j.optLong("notes"),
            identityLeaves = j.optLong("identity_leaves"),
            halted = if (j.isNull("halted")) null else j.optString("halted").ifEmpty { null },
            genesis = if (j.isNull("genesis")) null else j.optString("genesis").ifEmpty { null },
            base = if (j.isNull("base")) null else j.optString("base").ifEmpty { null },
        ).also { st ->
            // Refused before it is ever used: a base that is not exactly this
            // chain's (another chain, a host, a scheme, '..', '@', '?').
            st.base?.let { b -> if (!validBase(b, chainId, st.chainId, st.genesis)) throw IOException("the privacy indexer named an invalid base ${b.take(80)}") }
            base = st.base
        }
    }

    override fun notes(fromPos: Long, limit: Int?): NotesPage =
        parseNotes(stream("/notes?from_pos=$fromPos${limit(limit)}"))

    override fun nullifiers(fromHeight: Long, limit: Int?): HeightPage<Fr> =
        parseHeights(stream("/nullifiers?from_height=$fromHeight${limit(limit)}")) { Fr.fromHex(it as String) }

    override fun identity(fromIndex: Long, limit: Int?): IdentityPage =
        parseIdentity(stream("/identity?from_index=$fromIndex${limit(limit)}"))

    override fun identityZeroed(fromHeight: Long, limit: Int?): HeightPage<Long> =
        parseHeights(stream("/identity/zeroed?from_height=$fromHeight${limit(limit)}")) { (it as Number).toLong() }

    override fun rootsLatest(): LatestRoots = parseRoots(stream("/roots/latest"))

    override fun rates(epoch: Long?): List<RateRow> {
        val j = stream("/rates" + (epoch?.let { "?epoch=$it" } ?: ""))
        val rows = j.getJSONArray("rates")
        return (0 until rows.length()).map { i ->
            val r = rows.getJSONArray(i)
            RateRow(r.getString(0), r.get(1).toString(), r.get(2).toString(), if (r.isNull(3)) null else r.getLong(3), r.getLong(4))
        }
    }

    override fun stakeNotes(fromPos: Long, limit: Int?): StakeNotesPage =
        parseStakeNotes(stream("/stake/notes?from_pos=$fromPos${limit(limit)}"))

    override fun stakeNullifiers(fromHeight: Long, limit: Int?): HeightPage<Fr> =
        parseHeights(stream("/stake/nullifiers?from_height=$fromHeight${limit(limit)}")) { Fr.fromHex(it as String) }

    override fun stakeNullifierLeaves(fromIndex: Long, limit: Int?): StakeNfLeavesPage =
        parseStakeNfLeaves(stream("/stake/nullifier-tree?from_index=$fromIndex${limit(limit)}"))

    override fun stakeSnapshots(fromHeight: Long, limit: Int?): StakeSnapshotsPage =
        parseStakeSnapshots(stream("/stake/snapshots?from_height=$fromHeight${limit(limit)}"))

    override fun handles(fromIndex: Long, limit: Int): network.erth.wallet.privacy.handles.HandleDirectory.StreamPage =
        parseHandles(stream("/handles?from_index=$fromIndex${limit(limit)}"))

    override fun debtRows(fromIndex: Long, limit: Int): DebtRowsPage =
        parseDebtRows(stream("/debt_rows?from_index=$fromIndex${limit(limit)}"))

    companion object {
        private val CHAIN_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
        private val GENESIS = Regex("[0-9a-f]{16}")

        /**
         * [base] is exactly `/privacy/<chain_id>/<genesis>` for the
         * chain the wallet follows ([expected]), as the same status names it,
         * with a chain id of [A-Za-z0-9._-] (at most 64) and a genesis of 16
         * lowercase hex digits. A null chain id is refused.
         */
        fun validBase(base: String, expected: String, chainId: String?, genesis: String?): Boolean {
            if (chainId == null || genesis == null || chainId != expected) return false
            if (!CHAIN_ID.matches(chainId) || !GENESIS.matches(genesis)) return false
            return base == "/privacy/$chainId/$genesis"
        }

        /**
         * The most a response may hold, decompressed: a full 5000-row note
         * page is about 2 MB of JSON. A larger body (a hostile or broken
         * server, a gzip bomb) is refused rather than read into memory.
         */
        const val MAX_BODY_BYTES = 8L * 1024 * 1024

        /** Retries of a busy (503/429) indexer before the request fails. */
        const val MAX_BUSY_RETRIES = 4

        /** Retry-After (seconds, when sent) or 2^(attempt-1) s, whichever is longer, at most 30 s. */
        fun backoffMs(retryAfter: Long?, attempt: Int): Long =
            maxOf(retryAfter?.coerceIn(0, 30) ?: 0, 1L shl minOf(attempt - 1, 5)).coerceAtMost(30) * 1000

        /** Reads [input] as UTF-8, refusing more than [max] bytes. */
        fun readBounded(input: java.io.InputStream, max: Long): String = input.use { s ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                total += n
                if (total > max) throw IOException("indexer response exceeds $max bytes")
                out.write(buf, 0, n)
            }
            out.toString("UTF-8")
        }

        fun parseStakeNotes(j: JSONObject): StakeNotesPage {
            val a = j.getJSONArray("notes")
            val rows = (0 until a.length()).map { i ->
                val r = a.getJSONArray(i)
                StakeNoteRow(
                    position = r.getLong(0),
                    height = r.getLong(1),
                    cm = Fr.fromHex(r.getString(2)),
                    ciphertext = if (r.isNull(3)) ByteArray(0) else r.getString(3).decodeBase64()?.toByteArray() ?: ByteArray(0),
                )
            }
            return StakeNotesPage(rows, j.getLong("next_pos"), j.getBoolean("complete"), j.getLong("synced_height"))
        }

        /** /debt_rows: rows [index, key (hex), retained, height, updated_height]; size, root (hex). */
        fun parseDebtRows(j: JSONObject): DebtRowsPage {
            val a = j.getJSONArray("rows")
            val rows = (0 until a.length()).map { i ->
                val r = a.getJSONArray(i)
                val retained = network.erth.wallet.privacy.Amounts.parseU64(r.get(2).toString()) ?: throw IOException("debt row retained ${r.get(2)}")
                Triple(r.getLong(0), Fr.fromHex(r.getString(1)), retained)
            }
            val root = if (j.isNull("root")) null else j.optString("root").takeIf { it.length == 64 }?.let { Fr.fromHex(it) }
            return DebtRowsPage(rows, j.getLong("next_index"), j.getBoolean("complete"), j.optLong("size"), root)
        }

        /** /stake/nullifier-tree: rows [index, nullifier (hex), height]. */
        /**
         * /handles: rows [handle, address, status, expires_at, renewal_until, owner], the
         * snapshot's height and size. owner (64 hex, the chain's
         * HandleEntry.owner) is optional: a row without it says no owner.
         */
        fun parseHandles(j: JSONObject): network.erth.wallet.privacy.handles.HandleDirectory.StreamPage {
            val a = j.optJSONArray("handles") ?: JSONArray()
            val rows = (0 until a.length()).map { i ->
                val r = a.getJSONArray(i)
                network.erth.wallet.privacy.handles.HandleEntry(
                    r.getString(0), r.getString(1), r.getString(2), r.getLong(3), r.getLong(4),
                    if (r.length() > 5) network.erth.wallet.privacy.handles.Handles.owner(r.optString(5)) else "",
                )
            }
            return network.erth.wallet.privacy.handles.HandleDirectory.StreamPage(
                rows, if (j.isNull("height")) null else j.optLong("height"), j.optLong("size"), j.optLong("from_index"), j.optBoolean("last_page"),
            )
        }

        fun parseStakeNfLeaves(j: JSONObject): StakeNfLeavesPage {
            val a = j.getJSONArray("nullifiers")
            val leaves = (0 until a.length()).map { i -> a.getJSONArray(i).let { r -> r.getLong(0) to Fr.fromHex(r.getString(1)) } }
            return StakeNfLeavesPage(leaves, j.getLong("next_index"), j.getBoolean("complete"), j.optLong("size"), j.getLong("synced_height"))
        }

        private fun optField(hex: String): Fr? = if (hex.isEmpty()) null else Fr.fromHex(hex)

        /** /stake/snapshots: rows [height, proposal_id, root, tree_size, nf_root, nf_size] ("" for a root not recorded). */
        fun parseStakeSnapshots(j: JSONObject): StakeSnapshotsPage {
            val a = j.getJSONArray("snapshots")
            val rows = (0 until a.length()).map { i ->
                val r = a.getJSONArray(i)
                StakeSnapshotRow(r.getLong(0), r.getLong(1), optField(r.getString(2)), r.getLong(3), optField(r.getString(4)), r.getLong(5))
            }
            return StakeSnapshotsPage(rows, j.getLong("next_height"), j.getBoolean("complete"), j.getLong("synced_height"))
        }

        /** The notes stream format this wallet reads (backend README "Note stream format 2", chain 203d3b2). */
        const val NOTE_FORMAT = 2
        private val NOTE_FIELDS = listOf("position", "height", "cm", "ciphertext", "amount", "owner_pk", "rho", "rcm")

        /**
         * /notes, format 2: columns by name from `fields` (position, height,
         * cm, ciphertext, amount, owner_pk, rho, rcm). `ciphertext` is null
         * exactly for an open note, whose owner_pk, rho and rcm (hex) are
         * then all set; they are null on every other row. A page of another
         * format (an old backend) or one missing a column is refused.
         */
        fun parseNotes(j: JSONObject): NotesPage {
            if (j.optInt("format", 1) != NOTE_FORMAT) throw IOException("the privacy indexer serves notes format ${j.opt("format")}, not $NOTE_FORMAT; it needs an update")
            val fields = j.getJSONArray("fields").let { f -> (0 until f.length()).map { f.getString(it) } }
            val col = NOTE_FIELDS.associateWith { name -> fields.indexOf(name).also { if (it < 0) throw IOException("the notes page has no $name column") } }
            val a = j.getJSONArray("notes")
            val rows = (0 until a.length()).map { i ->
                val r = a.getJSONArray(i)
                fun isNull(name: String) = col.getValue(name).let { r.length() <= it || r.isNull(it) }
                fun str(name: String): String = r.getString(col.getValue(name))
                fun hex(name: String): Fr? = if (isNull(name)) null else Fr.fromHex(str(name))
                val opening = listOf(hex("owner_pk"), hex("rho"), hex("rcm"))
                val open = isNull("ciphertext")
                if (opening.any { it == null } && opening.any { it != null }) throw IOException("a note row carries part of an opening")
                if (open != (opening[0] != null)) throw IOException("a note row has ${if (open) "neither a ciphertext nor an opening" else "both a ciphertext and an opening"}")
                if (open && isNull("amount")) throw IOException("an open note row has no amount")
                NoteRow(
                    position = r.getLong(col.getValue("position")),
                    height = r.getLong(col.getValue("height")),
                    cm = Fr.fromHex(str("cm")),
                    ciphertext = if (open) ByteArray(0) else str("ciphertext").decodeBase64()?.toByteArray() ?: ByteArray(0),
                    amount = if (isNull("amount")) null else str("amount"),
                    ownerPk = opening[0],
                    rho = opening[1],
                    rcm = opening[2],
                )
            }
            return NotesPage(rows, j.getLong("next_pos"), j.getBoolean("complete"), j.getLong("synced_height"))
        }

        fun <T> parseHeights(j: JSONObject, item: (Any) -> T): HeightPage<T> {
            val a = j.getJSONArray("blocks")
            val blocks = (0 until a.length()).map { i ->
                val b = a.getJSONArray(i)
                val xs: JSONArray = b.getJSONArray(1)
                b.getLong(0) to (0 until xs.length()).map { item(xs.get(it)) }
            }
            return HeightPage(blocks, j.getLong("next_height"), j.getBoolean("complete"), j.getLong("synced_height"))
        }

        fun parseIdentity(j: JSONObject): IdentityPage {
            val a = j.getJSONArray("leaves")
            val rows = (0 until a.length()).map { i ->
                val r = a.getJSONArray(i)
                IdentityRow(r.getLong(0), r.getLong(1), Fr.fromHex(r.getString(2)), if (r.isNull(3)) null else r.getLong(3),
                    if (r.length() > 4 && !r.isNull(4)) r.getLong(4) else null)
            }
            return IdentityPage(rows, j.getLong("next_index"), j.getLong("size"), j.getLong("synced_height"))
        }

        private fun root(j: JSONObject?): RootRecord? = j?.let {
            RootRecord(Fr.fromHex(it.getString("root")), it.getLong("tree_size"), it.getLong("height"), it.getLong("time"))
        }

        fun parseRoots(j: JSONObject): LatestRoots = LatestRoots(
            root(j.optJSONObject("note")), root(j.optJSONObject("identity")), j.getLong("synced_height"), root(j.optJSONObject("stake")),
        )
    }
}
