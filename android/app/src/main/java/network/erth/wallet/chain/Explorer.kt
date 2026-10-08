package network.erth.wallet.chain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Chain explorer reads.
 *
 * Everything here is public LCD data — blocks and transactions — so nothing
 * in this file needs a wallet or a signature.
 *
 * The LCD returns each block twice: `block` is the raw CometBFT form and
 * `sdk_block` re-encodes the header; either header serves.
 */
object Explorer {

    data class Status(val chainId: String, val height: Long, val time: String)

    data class Block(
        val height: Long,
        val time: String,
        val txCount: Int,
    )

    data class Tx(
        val hash: String,
        val height: Long,
        /** A non-zero code means the transaction was included but failed. */
        val success: Boolean,
        val timestamp: String,
        /** Short message names, e.g. "MsgSwap" — enough for a list row. */
        val types: List<String>,
        /** Raw message objects, for the detail view. */
        val messages: List<JSONObject>,
        /**
         * What the same lookup also says, for the detail sheet: the fee and
         * who paid it (a fee granter, when one did), the chain's log when it
         * failed, the memo and the gas.
         */
        val fee: List<network.erth.wallet.privacy.ActivityCoin> = emptyList(),
        val feeGranter: String = "",
        val feePayer: String = "",
        val code: Int = 0,
        val rawLog: String = "",
        val memo: String = "",
        val gasUsed: Long = 0,
        val gasWanted: Long = 0,
    )

    // --- status and blocks ---

    fun status(): Status? {
        val json = getJson("/cosmos/base/tendermint/v1beta1/blocks/latest") ?: return null
        val h = header(json) ?: return null
        return Status(
            chainId = h.optString("chain_id", ""),
            height = h.optString("height", "0").toLongOrNull() ?: 0L,
            time = h.optString("time", ""),
        )
    }

    fun latestBlock(): Block? =
        getJson("/cosmos/base/tendermint/v1beta1/blocks/latest")?.let { toBlock(it) }

    /** A block by height, or null if it does not exist. */
    fun block(height: Long): Block? =
        getJson("/cosmos/base/tendermint/v1beta1/blocks/$height")?.let { toBlock(it) }

    /**
     * The [count] most recent blocks, newest first.
     *
     * Served by CometBFT's `/blockchain?minHeight=&maxHeight=` range query: one
     * request for the whole page instead of one per block. The LCD has no
     * equivalent, which is the only reason the explorer knows about the RPC port
     * at all.
     *
     * If the RPC port is unreachable — a deployment may expose only REST — this
     * falls back to fetching each height from the LCD concurrently. Slower and
     * chattier, but the tab still fills.
     */
    suspend fun recentBlocks(count: Int = 15): List<Block> {
        val capped = count.coerceAtMost(BLOCKCHAIN_RANGE_LIMIT)
        return withContext(Dispatchers.IO) { blockRange(capped) }
            ?: recentBlocksViaLcd(capped)
    }

    /**
     * CometBFT refuses more than 20 block metas per `/blockchain` call and
     * silently clamps the range, so asking for more would quietly return fewer
     * than requested rather than erroring.
     */
    private const val BLOCKCHAIN_RANGE_LIMIT = 20

    /**
     * One range request, newest first. Returns null (rather than an empty list)
     * when the RPC port cannot serve it, so the caller can tell "no blocks" from
     * "no RPC" and fall back.
     */
    private fun blockRange(count: Int): List<Block>? {
        val tip = status()?.height ?: return null
        val min = maxOf(1L, tip - count + 1)
        val (code, body) = try {
            EarthRest.getRpc("/blockchain?minHeight=$min&maxHeight=$tip")
        } catch (e: Exception) {
            return null
        }
        if (code !in 200..299) return null
        val metas = try {
            JSONObject(body).optJSONObject("result")?.optJSONArray("block_metas")
        } catch (e: Exception) {
            null
        } ?: return null

        val out = ArrayList<Block>(metas.length())
        for (i in 0 until metas.length()) {
            val m = metas.optJSONObject(i) ?: continue
            val h = m.optJSONObject("header") ?: continue
            out.add(
                Block(
                    height = h.optString("height", "0").toLongOrNull() ?: 0L,
                    time = h.optString("time", ""),
                    txCount = m.optString("num_txs", "0").toIntOrNull() ?: 0,
                )
            )
        }
        return out.sortedByDescending { it.height }
    }

    private suspend fun recentBlocksViaLcd(count: Int): List<Block> = coroutineScope {
        val tip = withContext(Dispatchers.IO) { latestBlock() } ?: return@coroutineScope emptyList()
        val heights = ((tip.height - 1) downTo maxOf(1, tip.height - count + 1)).toList()
        val rest = heights
            .map { h -> async(Dispatchers.IO) { block(h) } }
            .awaitAll()
            .filterNotNull()
        listOf(tip) + rest
    }

    private fun header(json: JSONObject): JSONObject? =
        json.optJSONObject("sdk_block")?.optJSONObject("header")
            ?: json.optJSONObject("block")?.optJSONObject("header")

    private fun toBlock(json: JSONObject): Block? {
        val h = header(json) ?: return null
        val txs = json.optJSONObject("block")?.optJSONObject("data")?.optJSONArray("txs")
        return Block(
            height = h.optString("height", "0").toLongOrNull() ?: 0L,
            time = h.optString("time", ""),
            txCount = txs?.length() ?: 0,
        )
    }

    // --- transactions ---

    /**
     * These transactions, newest first: each looked up by hash (a point read
     * in the node's index). A hash the node does not know (not yet indexed,
     * or dropped from the mempool) is left out.
     *
     * Not a search by address: Earth's public node refuses one, because
     * CometBFT loads every match of a search before it pages and cannot
     * cancel it, so an address search is a scan of that address's whole
     * history on the only validator (round-5 R5-E-1), and the node indexes no
     * address events. The wallet's activity is the txs it sent ([SentTxLog]);
     * a transfer someone else sent shows in the balance, not as a row.
     *
     * Gentle on the node (round-6 R6-E-7): a tx found in a block never
     * changes, so it is kept in memory and not asked for again; at most
     * [LOOKUP_CONCURRENCY] lookups are in flight; and a lookup the node could
     * not answer (busy, rate-limited, unreachable) is retried with a backoff
     * rather than taken for "no such tx".
     */
    suspend fun txsByHash(hashes: List<String>): List<Tx> =
        lookupAll(hashes, fetch = { h -> withContext(Dispatchers.IO) { lookup(h) } }, sleep = { delay(it) })

    /** What one lookup said. */
    internal sealed interface Lookup {
        data class Found(val tx: Tx) : Lookup
        /** The node answered: it has no such tx (yet). */
        data object NotFound : Lookup
        /** The node could not be asked, or did not answer: ask again. */
        data object Unavailable : Lookup
    }

    internal const val LOOKUP_CONCURRENCY = 4
    internal const val LOOKUP_ATTEMPTS = 3
    private const val LOOKUP_BACKOFF_MS = 1000L
    private const val FOUND_CACHE = 512

    /** Txs found in a block, by upper-case hash: they never change. */
    private val found = object : LinkedHashMap<String, Tx>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Tx>?) = size > FOUND_CACHE
    }

    /** Drops the remembered lookups (the wallet's data was forgotten). */
    fun forgetLookups() = synchronized(found) { found.clear() }

    internal suspend fun lookupAll(
        hashes: List<String>,
        fetch: suspend (String) -> Lookup,
        sleep: suspend (Long) -> Unit,
    ): List<Tx> = coroutineScope {
        val gate = Semaphore(LOOKUP_CONCURRENCY)
        hashes.filter(SentTxLog::isHash).map { it.uppercase() }.distinct()
            .map { h ->
                async<Tx?> {
                    synchronized(found) { found[h] }?.let { return@async it }
                    for (attempt in 1..LOOKUP_ATTEMPTS) {
                        when (val r = gate.withPermit { fetch(h) }) {
                            is Lookup.Found -> {
                                if (r.tx.height > 0) synchronized(found) { found[h] = r.tx }
                                return@async r.tx
                            }
                            Lookup.NotFound -> return@async null
                            Lookup.Unavailable ->
                                if (attempt < LOOKUP_ATTEMPTS) sleep(LOOKUP_BACKOFF_MS shl (attempt - 1))
                        }
                    }
                    null
                }
            }
            .awaitAll()
            .filterNotNull()
            .sortedByDescending { it.height }
    }

    /** One `GET /cosmos/tx/v1beta1/txs/{hash}`. */
    private fun lookup(hash: String): Lookup {
        val (code, body) = try {
            EarthRest.get("/cosmos/tx/v1beta1/txs/$hash")
        } catch (e: Exception) {
            return Lookup.Unavailable
        }
        return classify(code, body)
    }

    /**
     * An answer as a lookup result. 404 is the node's "no such tx" (the
     * gateway's NotFound); 400 a hash it will never know. Everything else
     * that is not a tx (a busy edge's 503, Cloudflare's 429, a timeout, a
     * 5xx, no connection) says nothing about the tx.
     */
    internal fun classify(code: Int, body: String): Lookup = when (code) {
        in 200..299 -> {
            val json = try {
                JSONObject(body)
            } catch (e: Exception) {
                null
            }
            // Not JSON (a portal, a proxy's page): not the node's answer.
            if (json == null) Lookup.Unavailable else fromLookup(json)?.let { Lookup.Found(it) } ?: Lookup.NotFound
        }
        400, 404 -> Lookup.NotFound
        else -> Lookup.Unavailable
    }

    /** A `GET /cosmos/tx/v1beta1/txs/{hash}` answer, or null when it names no tx. */
    internal fun fromLookup(json: JSONObject): Tx? {
        val res = json.optJSONObject("tx_response") ?: return null
        if (res.optString("txhash", "").isEmpty()) return null
        return toTx(res, json.optJSONObject("tx"))
    }

    private fun toTx(res: JSONObject, body: JSONObject?): Tx {
        val bodyObj = body?.optJSONObject("body")
        val messages = bodyObj?.optJSONArray("messages").toObjectList()
        return Tx(
            hash = res.optString("txhash", ""),
            height = res.optString("height", "0").toLongOrNull() ?: 0L,
            success = res.optInt("code", 0) == 0,
            timestamp = res.optString("timestamp", ""),
            // "/earth.dex.v1.MsgSwap" -> "MsgSwap"
            types = messages.map { it.optString("@type", "").substringAfterLast('.') }
                .filter { it.isNotEmpty() },
            messages = messages,
            fee = body?.optJSONObject("auth_info")?.optJSONObject("fee")?.optJSONArray("amount").toObjectList().mapNotNull { c ->
                val a = c.optString("amount").toLongOrNull() ?: return@mapNotNull null
                network.erth.wallet.privacy.ActivityCoin(c.optString("denom"), a)
            },
            feeGranter = body?.optJSONObject("auth_info")?.optJSONObject("fee")?.optString("granter", "").orEmpty(),
            feePayer = body?.optJSONObject("auth_info")?.optJSONObject("fee")?.optString("payer", "").orEmpty(),
            code = res.optInt("code", 0),
            rawLog = res.optString("raw_log", ""),
            memo = bodyObj?.optString("memo", "").orEmpty(),
            gasUsed = res.optString("gas_used", "0").toLongOrNull() ?: 0L,
            gasWanted = res.optString("gas_wanted", "0").toLongOrNull() ?: 0L,
        )
    }

    // --- helpers ---

    private fun getJson(path: String): JSONObject? {
        val (code, body) = EarthRest.get(path)
        if (code !in 200..299) return null
        return try {
            JSONObject(body)
        } catch (e: Exception) {
            null
        }
    }

    private fun JSONArray?.toObjectList(): List<JSONObject> {
        if (this == null) return emptyList()
        val out = ArrayList<JSONObject>(length())
        for (i in 0 until length()) optJSONObject(i)?.let { out.add(it) }
        return out
    }
}
