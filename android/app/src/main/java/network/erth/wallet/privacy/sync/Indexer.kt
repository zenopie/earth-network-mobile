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
}

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

data class NoteRow(val position: Long, val height: Long, val cm: Fr, val ciphertext: ByteArray, val amount: String?)

data class NotesPage(val rows: List<NoteRow>, val nextPos: Long, val complete: Boolean, val syncedHeight: Long)

data class HeightPage<T>(val blocks: List<Pair<Long, List<T>>>, val nextHeight: Long, val complete: Boolean, val syncedHeight: Long)

data class IdentityRow(val index: Long, val height: Long, val leaf: Fr, val zeroedHeight: Long?)

data class IdentityPage(val rows: List<IdentityRow>, val nextIndex: Long, val size: Long, val syncedHeight: Long)

data class RootRecord(val root: Fr, val treeSize: Long, val height: Long, val time: Long)

data class LatestRoots(val note: RootRecord?, val identity: RootRecord?, val syncedHeight: Long, val stake: RootRecord? = null)

/**
 * A stake tree leaf. A note the chain minted carries its public [denom],
 * [amount] and stake pc [spc] and no ciphertext; a note a stake proof created
 * carries a ciphertext and none of the three.
 */
data class StakeNoteRow(
    val position: Long,
    val height: Long,
    val cm: Fr,
    val ciphertext: ByteArray,
    val denom: String?,
    val amount: Long?,
    val spc: Fr?,
)

data class StakeNotesPage(val rows: List<StakeNoteRow>, val nextPos: Long, val complete: Boolean, val syncedHeight: Long)

data class RateRow(val validator: String, val rate: String, val supply: String, val epoch: Long?, val height: Long)

/**
 * [PrivacyIndexer] over HTTP. Blocking; call from an IO thread. Only ever
 * talks to [host]: the status's `base` is accepted only as exactly
 * `/privacy/<chain_id>/<genesis>` for [chainId] (K10), so a hostile status
 * cannot point the stream requests anywhere else.
 */
class HttpPrivacyIndexer(private val host: String, private val chainId: String = network.erth.wallet.Constants.EARTH_CHAIN_ID) : PrivacyIndexer {
    @Volatile private var base: String? = null
    private val hostUrl = URL(host.trimEnd('/'))

    private fun get(path: String): JSONObject {
        require(path.startsWith("/") && !path.startsWith("//")) { "indexer path $path" }
        val url = URL(hostUrl.toString() + path)
        if (url.protocol != hostUrl.protocol || url.host != hostUrl.host || url.port != hostUrl.port || url.userInfo != null) {
            throw IOException("indexer path $path leaves ${hostUrl.host}")
        }
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        c.setRequestProperty("Accept-Encoding", "gzip")
        try {
            val code = c.responseCode
            val raw = if (code in 200..299) c.inputStream else c.errorStream
            val stream = if (c.contentEncoding == "gzip") GZIPInputStream(raw) else raw
            val body = stream?.let { readBounded(it, MAX_BODY_BYTES) }.orEmpty()
            if (code == 404) throw IndexerBaseMoved("indexer $path: 404 ${body.take(200)}")
            if (code !in 200..299) throw IOException("indexer $path: $code ${body.take(200)}")
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
        parseNotes(stream("/notes?from_pos=$fromPos${q("limit", limit)}"))

    override fun nullifiers(fromHeight: Long, limit: Int?): HeightPage<Fr> =
        parseHeights(stream("/nullifiers?from_height=$fromHeight${q("limit", limit)}")) { Fr.fromHex(it as String) }

    override fun identity(fromIndex: Long, limit: Int?): IdentityPage =
        parseIdentity(stream("/identity?from_index=$fromIndex${q("limit", limit)}"))

    override fun identityZeroed(fromHeight: Long, limit: Int?): HeightPage<Long> =
        parseHeights(stream("/identity/zeroed?from_height=$fromHeight${q("limit", limit)}")) { (it as Number).toLong() }

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
        parseStakeNotes(stream("/stake/notes?from_pos=$fromPos${q("limit", limit)}"))

    override fun stakeNullifiers(fromHeight: Long, limit: Int?): HeightPage<Fr> =
        parseHeights(stream("/stake/nullifiers?from_height=$fromHeight${q("limit", limit)}")) { Fr.fromHex(it as String) }

    companion object {
        private val CHAIN_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
        private val GENESIS = Regex("[0-9a-f]{16}")

        /**
         * K10: [base] is exactly `/privacy/<chain_id>/<genesis>` for the
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
                    denom = if (r.isNull(4)) null else r.getString(4),
                    amount = if (r.isNull(5)) null else network.erth.wallet.privacy.Amounts.parseU64(r.get(5).toString()),
                    spc = if (r.isNull(6)) null else Fr.fromHex(r.getString(6)),
                )
            }
            return StakeNotesPage(rows, j.getLong("next_pos"), j.getBoolean("complete"), j.getLong("synced_height"))
        }

        fun parseNotes(j: JSONObject): NotesPage {
            val a = j.getJSONArray("notes")
            val rows = (0 until a.length()).map { i ->
                val r = a.getJSONArray(i)
                NoteRow(
                    position = r.getLong(0),
                    height = r.getLong(1),
                    cm = Fr.fromHex(r.getString(2)),
                    ciphertext = r.getString(3).decodeBase64()?.toByteArray() ?: ByteArray(0),
                    amount = if (r.isNull(4)) null else r.getString(4),
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
                IdentityRow(r.getLong(0), r.getLong(1), Fr.fromHex(r.getString(2)), if (r.isNull(3)) null else r.getLong(3))
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
