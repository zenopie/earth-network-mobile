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
 */
interface PrivacyIndexer {
    fun status(): IndexerStatus
    fun notes(fromPos: Long, limit: Int? = null): NotesPage
    fun nullifiers(fromHeight: Long, limit: Int? = null): HeightPage<Fr>
    fun identity(fromIndex: Long, limit: Int? = null): IdentityPage
    fun identityZeroed(fromHeight: Long, limit: Int? = null): HeightPage<Long>
    fun rootsLatest(): LatestRoots
    fun rates(epoch: Long? = null): List<RateRow>
}

data class IndexerStatus(val chainId: String?, val syncedHeight: Long, val syncedTime: Long?, val notes: Long, val identityLeaves: Long, val halted: String?)

data class NoteRow(val position: Long, val height: Long, val cm: Fr, val ciphertext: ByteArray, val amount: String?)

data class NotesPage(val rows: List<NoteRow>, val nextPos: Long, val complete: Boolean, val syncedHeight: Long)

data class HeightPage<T>(val blocks: List<Pair<Long, List<T>>>, val nextHeight: Long, val complete: Boolean, val syncedHeight: Long)

data class IdentityRow(val index: Long, val height: Long, val leaf: Fr, val zeroedHeight: Long?)

data class IdentityPage(val rows: List<IdentityRow>, val nextIndex: Long, val size: Long, val syncedHeight: Long)

data class RootRecord(val root: Fr, val treeSize: Long, val height: Long, val time: Long)

data class LatestRoots(val note: RootRecord?, val identity: RootRecord?, val syncedHeight: Long)

data class RateRow(val validator: String, val rate: String, val supply: String, val epoch: Long?, val height: Long)

/** [PrivacyIndexer] over HTTP. Blocking; call from an IO thread. */
class HttpPrivacyIndexer(private val base: String) : PrivacyIndexer {

    private fun get(path: String): JSONObject {
        val c = URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        c.setRequestProperty("Accept-Encoding", "gzip")
        try {
            val code = c.responseCode
            val raw = if (code in 200..299) c.inputStream else c.errorStream
            val stream = if (c.contentEncoding == "gzip") GZIPInputStream(raw) else raw
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("indexer $path: $code $body")
            return JSONObject(body)
        } finally {
            c.disconnect()
        }
    }

    private fun q(name: String, v: Any?): String = if (v == null) "" else "&$name=$v"

    override fun status(): IndexerStatus = get("/privacy/status").let { j ->
        IndexerStatus(
            chainId = j.optString("chain_id").ifEmpty { null },
            syncedHeight = j.optLong("synced_height"),
            syncedTime = if (j.isNull("synced_time")) null else j.optLong("synced_time"),
            notes = j.optLong("notes"),
            identityLeaves = j.optLong("identity_leaves"),
            halted = if (j.isNull("halted")) null else j.optString("halted"),
        )
    }

    override fun notes(fromPos: Long, limit: Int?): NotesPage =
        parseNotes(get("/privacy/notes?from_pos=$fromPos${q("limit", limit)}"))

    override fun nullifiers(fromHeight: Long, limit: Int?): HeightPage<Fr> =
        parseHeights(get("/privacy/nullifiers?from_height=$fromHeight${q("limit", limit)}")) { Fr.fromHex(it as String) }

    override fun identity(fromIndex: Long, limit: Int?): IdentityPage =
        parseIdentity(get("/privacy/identity?from_index=$fromIndex${q("limit", limit)}"))

    override fun identityZeroed(fromHeight: Long, limit: Int?): HeightPage<Long> =
        parseHeights(get("/privacy/identity/zeroed?from_height=$fromHeight${q("limit", limit)}")) { (it as Number).toLong() }

    override fun rootsLatest(): LatestRoots = parseRoots(get("/privacy/roots/latest"))

    override fun rates(epoch: Long?): List<RateRow> {
        val j = get("/privacy/rates" + (epoch?.let { "?epoch=$it" } ?: ""))
        val rows = j.getJSONArray("rates")
        return (0 until rows.length()).map { i ->
            val r = rows.getJSONArray(i)
            RateRow(r.getString(0), r.get(1).toString(), r.get(2).toString(), if (r.isNull(3)) null else r.getLong(3), r.getLong(4))
        }
    }

    companion object {
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
            root(j.optJSONObject("note")), root(j.optJSONObject("identity")), j.getLong("synced_height"),
        )
    }
}
