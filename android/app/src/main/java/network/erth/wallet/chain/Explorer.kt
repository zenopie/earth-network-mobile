package network.erth.wallet.chain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
     * Transactions matching a CometBFT query string, newest first. The LCD
     * returns two parallel arrays — `txs` (decoded bodies) and `tx_responses`
     * (execution results) — which are zipped here.
     */
    private fun searchTxs(query: String, limit: Int = 20): List<Tx> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val json = getJson(
            "/cosmos/tx/v1beta1/txs?query=$encoded&order_by=ORDER_BY_DESC&limit=$limit"
        ) ?: return emptyList()
        val responses = json.optJSONArray("tx_responses") ?: return emptyList()
        val bodies = json.optJSONArray("txs")
        val out = ArrayList<Tx>(responses.length())
        for (i in 0 until responses.length()) {
            out.add(toTx(responses.getJSONObject(i), bodies?.optJSONObject(i)))
        }
        return out
    }

    /**
     * Transactions involving an address — both those it signed and those that
     * paid it. A `message.sender` query alone misses incoming transfers, since
     * those are indexed under the sender, so both are queried and merged.
     */
    fun txsForAddress(address: String, limit: Int = 20): List<Tx> {
        val sent = searchTxs("message.sender='$address'", limit)
        val received = searchTxs("transfer.recipient='$address'", limit)
        val byHash = LinkedHashMap<String, Tx>()
        for (tx in sent + received) byHash[tx.hash] = tx
        return byHash.values.sortedByDescending { it.height }.take(limit)
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
