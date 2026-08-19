package network.erth.wallet.chain

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import network.erth.wallet.Constants
import network.erth.wallet.wallet.utils.Bech32
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Chain explorer reads.
 *
 * Everything here is public LCD data — blocks, transactions, validators — so
 * nothing in this file needs a wallet or a signature. It mirrors the web app's
 * `src/chain/explorer.js`; keep the two in step when either changes.
 *
 * The LCD returns each block twice: `block` is the raw CometBFT form (proposer
 * as a base64 consensus address) and `sdk_block` re-encodes the header with a
 * bech32 `earthvalcons…` proposer, which is what we display.
 */
object Explorer {

    data class Status(val chainId: String, val height: Long, val time: String)

    data class Block(
        val height: Long,
        val time: String,
        val chainId: String,
        val proposer: String,
        val hash: String,
        val txCount: Int,
    )

    data class Tx(
        val hash: String,
        val height: Long,
        /** A non-zero code means the transaction was included but failed. */
        val success: Boolean,
        val code: Int,
        val rawLog: String,
        val gasUsed: Long,
        val gasWanted: Long,
        val timestamp: String,
        val memo: String,
        val fee: String,
        /** Short message names, e.g. "MsgSwap" — enough for a list row. */
        val types: List<String>,
        /** Raw message objects, for the detail view. */
        val messages: List<JSONObject>,
    )

    data class SlashingParams(
        val signedBlocksWindow: Long,
        val minSignedPerWindow: Double,
        val downtimeJailDuration: String,
        val slashFractionDoubleSign: Double,
        val slashFractionDowntime: Double,
    )

    data class ValidatorInfo(
        val operator: String,
        val moniker: String,
        val details: String,
        val website: String,
        val consAddress: String,
        val tokens: String,
        val bonded: Boolean,
        val jailed: Boolean,
        val tombstoned: Boolean,
        val commission: Double,
        val maxCommission: Double,
        /** Null when uptime would be misleading — see [validators]. */
        val uptime: Double?,
        val missedBlocks: Long?,
        val jailedUntil: String?,
        val votingPower: Double,
    )

    data class ValidatorSet(
        val params: SlashingParams,
        val totalBonded: Double,
        val validators: List<ValidatorInfo>,
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
                    chainId = h.optString("chain_id", ""),
                    // The RPC gives the proposer as hex, where the LCD's
                    // sdk_block gives bech32. Converted here so both paths
                    // produce the same key for the moniker lookup.
                    proposer = valconsFromHex(h.optString("proposer_address", "")),
                    // Already hex and uppercase over RPC — no decode needed.
                    hash = m.optJSONObject("block_id")?.optString("hash", "") ?: "",
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
            chainId = h.optString("chain_id", ""),
            proposer = h.optString("proposer_address", ""),
            hash = hexOf(json.optJSONObject("block_id")?.optString("hash", "")),
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

    /** Chain-wide recent transactions. */
    fun recentTxs(limit: Int = 20): List<Tx> = searchTxs("tx.height>0", limit)

    /** Transactions included in a single block. */
    fun txsAtHeight(height: Long, limit: Int = 50): List<Tx> =
        searchTxs("tx.height=$height", limit)

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

    /** A single transaction by hash, or null if not found / not indexed. */
    fun txByHash(hash: String): Tx? {
        val json = getJson("/cosmos/tx/v1beta1/txs/${hash.uppercase()}") ?: return null
        val res = json.optJSONObject("tx_response") ?: return null
        return toTx(res, json.optJSONObject("tx"))
    }

    private fun toTx(res: JSONObject, body: JSONObject?): Tx {
        val bodyObj = body?.optJSONObject("body")
        val messages = bodyObj?.optJSONArray("messages").toObjectList()
        val fee = body?.optJSONObject("auth_info")?.optJSONObject("fee")
            ?.optJSONArray("amount").toObjectList()
            .joinToString(", ") { it.optString("amount", "0") + it.optString("denom", "") }
        return Tx(
            hash = res.optString("txhash", ""),
            height = res.optString("height", "0").toLongOrNull() ?: 0L,
            success = res.optInt("code", 0) == 0,
            code = res.optInt("code", 0),
            rawLog = res.optString("raw_log", ""),
            gasUsed = res.optString("gas_used", "0").toLongOrNull() ?: 0L,
            gasWanted = res.optString("gas_wanted", "0").toLongOrNull() ?: 0L,
            timestamp = res.optString("timestamp", ""),
            memo = bodyObj?.optString("memo", "") ?: "",
            fee = fee,
            // "/earth.dex.v1.MsgSwap" -> "MsgSwap"
            types = messages.map { it.optString("@type", "").substringAfterLast('.') }
                .filter { it.isNotEmpty() },
            messages = messages,
        )
    }

    // --- validators ---

    /** Slashing parameters: the uptime window and its penalties. */
    fun slashingParams(): SlashingParams {
        val p = getJson("/cosmos/slashing/v1beta1/params")?.optJSONObject("params")
        return SlashingParams(
            signedBlocksWindow = p?.optString("signed_blocks_window", "0")?.toLongOrNull() ?: 0L,
            minSignedPerWindow = p?.optString("min_signed_per_window", "0")?.toDoubleOrNull() ?: 0.0,
            downtimeJailDuration = p?.optString("downtime_jail_duration", "") ?: "",
            slashFractionDoubleSign =
                p?.optString("slash_fraction_double_sign", "0")?.toDoubleOrNull() ?: 0.0,
            slashFractionDowntime =
                p?.optString("slash_fraction_downtime", "0")?.toDoubleOrNull() ?: 0.0,
        )
    }

    /**
     * Every validator with its stake, commission and uptime, ranked by voting
     * power.
     *
     * Uptime is measured over the slashing window (the last
     * `signed_blocks_window` blocks) rather than the validator's whole history,
     * because that is the window that actually decides whether it gets jailed.
     */
    fun validators(): ValidatorSet {
        val params = slashingParams()
        val staking = getJson("/cosmos/staking/v1beta1/validators?pagination.limit=300")
        val signing = getJson("/cosmos/slashing/v1beta1/signing_infos?pagination.limit=300")

        val signingByCons = HashMap<String, JSONObject>()
        signing?.optJSONArray("info").toObjectList().forEach {
            signingByCons[it.optString("address", "")] = it
        }

        val raw = staking?.optJSONArray("validators").toObjectList()
        val list = raw.map { v ->
            val pubkey = v.optJSONObject("consensus_pubkey")?.optString("key", "") ?: ""
            val consAddress = if (pubkey.isNotEmpty()) consensusAddress(pubkey) else ""
            val info = signingByCons[consAddress]
            val window = params.signedBlocksWindow
            val jailed = v.optBoolean("jailed", false)
            val tombstoned = info?.optBoolean("tombstoned", false) ?: false
            val missed = info?.optString("missed_blocks_counter", "0")?.toLongOrNull()

            // x/slashing zeroes missed_blocks_counter when it jails a validator,
            // because the window restarts when it rejoins. Computing uptime from
            // that would show a validator 100% healthy at the exact moment it was
            // jailed FOR downtime — the most misleading number this screen could
            // show. Report "unknown" instead and let the status say what happened.
            val uptime = if (info != null && missed != null && window > 0 && !jailed && !tombstoned) {
                (((window - missed).toDouble() / window) * 100).coerceIn(0.0, 100.0)
            } else null

            ValidatorInfo(
                operator = v.optString("operator_address", ""),
                moniker = v.optJSONObject("description")?.optString("moniker", "") ?: "",
                details = v.optJSONObject("description")?.optString("details", "") ?: "",
                website = v.optJSONObject("description")?.optString("website", "") ?: "",
                consAddress = consAddress,
                tokens = v.optString("tokens", "0"),
                bonded = v.optString("status", "") == "BOND_STATUS_BONDED",
                jailed = jailed,
                tombstoned = tombstoned,
                commission = v.optJSONObject("commission")?.optJSONObject("commission_rates")
                    ?.optString("rate", "0")?.toDoubleOrNull() ?: 0.0,
                maxCommission = v.optJSONObject("commission")?.optJSONObject("commission_rates")
                    ?.optString("max_rate", "0")?.toDoubleOrNull() ?: 0.0,
                uptime = uptime,
                missedBlocks = missed,
                jailedUntil = info?.optString("jailed_until", null),
                votingPower = 0.0,
            )
        }

        val totalBonded = list.sumOf { if (it.bonded) it.tokens.toDoubleOrNull() ?: 0.0 else 0.0 }
        val ranked = list
            .map {
                it.copy(
                    votingPower = if (totalBonded > 0 && it.bonded) {
                        (it.tokens.toDoubleOrNull() ?: 0.0) / totalBonded * 100
                    } else 0.0
                )
            }
            .sortedByDescending { it.tokens.toDoubleOrNull() ?: 0.0 }

        return ValidatorSet(params, totalBonded, ranked)
    }

    /**
     * A validator's consensus address, derived from its ed25519 consensus
     * pubkey: bech32(prefix + "valcons", sha256(pubkey)[:20]).
     *
     * This is the only way to line staking records up with slashing records —
     * the staking module exposes pubkeys but not consensus addresses, and
     * x/slashing keys its uptime data by consensus address. Deriving it (rather
     * than joining through the validator set) also covers validators outside the
     * active set, whose uptime is exactly what you want to see when jailed.
     */
    fun consensusAddress(pubkeyBase64: String): String {
        val raw = Base64.decode(pubkeyBase64, Base64.DEFAULT)
        val digest = MessageDigest.getInstance("SHA-256").digest(raw)
        val truncated = digest.copyOfRange(0, 20)
        return Bech32.encode(
            Constants.EARTH_PREFIX + "valcons",
            Bech32.convertBits(truncated, 8, 5, true),
        )
    }

    /**
     * Bech32 valcons address from CometBFT's hex consensus address.
     *
     * The RPC identifies a proposer by the raw 20-byte address; the LCD's
     * `sdk_block` bech32-encodes the same bytes. Both have to land on the same
     * string or the proposer moniker silently comes back empty.
     */
    fun valconsFromHex(hex: String): String {
        if (hex.length != 40) return hex
        val bytes = try {
            ByteArray(20) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        } catch (e: Exception) {
            return hex
        }
        return Bech32.encode(
            Constants.EARTH_PREFIX + "valcons",
            Bech32.convertBits(bytes, 8, 5, true),
        )
    }

    // --- search ---

    enum class SearchKind { BLOCK, TX, ACCOUNT }

    data class Search(val kind: SearchKind, val value: String)

    /**
     * Classifies a search term so the UI knows where to route it: heights are
     * digits, tx hashes are 64 hex chars, everything else bech32.
     */
    fun classifySearch(term: String): Search? {
        val t = term.trim()
        return when {
            t.isEmpty() -> null
            t.all { it.isDigit() } -> Search(SearchKind.BLOCK, t)
            t.length == 64 && t.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' } ->
                Search(SearchKind.TX, t.uppercase())
            t.startsWith(Constants.EARTH_PREFIX) && t.length > 10 -> Search(SearchKind.ACCOUNT, t)
            else -> null
        }
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

    /** Base64 (the LCD's encoding for hashes) -> uppercase hex, as explorers show it. */
    private fun hexOf(b64: String?): String {
        if (b64.isNullOrEmpty()) return ""
        return try {
            Base64.decode(b64, Base64.DEFAULT).joinToString("") { "%02X".format(it) }
        } catch (e: Exception) {
            ""
        }
    }
}
