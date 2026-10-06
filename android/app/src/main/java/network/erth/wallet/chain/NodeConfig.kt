package network.erth.wallet.chain

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import network.erth.wallet.Constants
import network.erth.wallet.privacy.chain.RestPrivateChain
import okio.ByteString.Companion.decodeBase64
import org.json.JSONObject
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * Which node the wallet talks to: every chain query and every broadcast goes
 * to [current]'s LCD (and its RPC, for the explorer's block ranges). The
 * default is Earth's own node; Settings → Network can point the install at
 * the user's own, so no third party sees which account asks what or when.
 * The backend (privacy indexer, handle directory, gas grant, fetched
 * circuits) is not a node and stays at [Constants.EARTH_API_URL].
 *
 * Per install, in plain preferences: a URL is not a secret.
 *
 * HTTPS is required, except to the user's own node at a loopback or
 * private-network address, which they choose with a warning (a node on the
 * phone, or on their home network, often has no certificate). The platform's
 * cleartext rule cannot name a LAN address range, so this is where it is
 * enforced: [EarthRest] refuses any http:// base that is not that custom node.
 */
object NodeConfig {

    data class Node(val lcd: String, val rpc: String) {
        val isDefault: Boolean get() = this == DEFAULT
    }

    val DEFAULT = Node(Constants.EARTH_LCD_URL, Constants.EARTH_RPC_URL)

    private const val PREFS = "node"
    private const val KEY_LCD = "lcd"
    private const val KEY_RPC = "rpc"

    private val _node = MutableStateFlow(DEFAULT)
    val node: StateFlow<Node> = _node.asStateFlow()

    val current: Node get() = _node.value

    /** Read once at launch, before anything queries the chain. */
    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lcd = p.getString(KEY_LCD, null)?.let(::normalize) ?: return
        // A node saved before the RPC was required is dropped: its genesis was never checked.
        val rpc = p.getString(KEY_RPC, null)?.let(::normalize) ?: return
        if (problem(lcd) == null && problem(rpc) == null) _node.value = Node(lcd, rpc)
    }

    fun save(context: Context, node: Node) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LCD, node.lcd).putString(KEY_RPC, node.rpc).commit()
        _node.value = node
    }

    fun reset(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        _node.value = DEFAULT
    }

    /**
     * A base URL as typed, trimmed of whitespace and trailing slashes; null
     * when it is not an http(s) URL with a host and nothing past its path.
     */
    fun normalize(input: String): String? {
        val s = input.trim().trimEnd('/')
        val u = runCatching { URI(s) }.getOrNull() ?: return null
        val scheme = u.scheme?.lowercase() ?: return null
        if (scheme != "https" && scheme != "http") return null
        if (u.host.isNullOrEmpty() || u.rawUserInfo != null || u.rawQuery != null || u.rawFragment != null) return null
        return s
    }

    /** Why [url] (normalized) cannot be a node, or null when it can. */
    fun problem(url: String): String? {
        val u = runCatching { URI(url) }.getOrNull() ?: return "That is not a URL."
        return when {
            u.scheme.equals("https", ignoreCase = true) -> null
            u.scheme.equals("http", ignoreCase = true) && isLocal(u.host) -> null
            else -> "Use https://. Plain http:// is allowed only to this phone or your own local network."
        }
    }

    /** Plain http:// to a local address: allowed, with a warning. */
    fun isCleartext(url: String): Boolean = url.startsWith("http://", ignoreCase = true)

    /**
     * The phone itself or a private network: localhost, a loopback,
     * link-local or RFC 1918 / unique-local / site-local (fec0::/10) address,
     * or a .local name. An IPv4-mapped IPv6 address (::ffff:a.b.c.d) is
     * judged by its IPv4 address, where the socket goes. Literal addresses
     * only (no DNS lookup): a public name that resolves to a private
     * address is still a public name. Same rules as iOS NodeSettings.isLocal.
     */
    fun isLocal(host: String?): Boolean {
        val h = host?.lowercase()?.trim('[', ']') ?: return false
        if (h == "localhost" || h.endsWith(".local")) return true
        // Literals only, checked before getByName so it never resolves a
        // name: four decimal octets each at most 255, or an IPv6 literal (a
        // string with a colon is parsed as one and never looked up).
        val v4 = Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(h) && h.split('.').all { it.toInt() <= 255 }
        val v6 = h.contains(':') && h.all { it.isDigit() || it == ':' || it == '.' || it in 'a'..'f' }
        if (!v4 && !v6) return false
        val a = runCatching { InetAddress.getByName(h) }.getOrNull() ?: return false
        return localBytes(a.address)
    }

    /** [isLocal] on an address's bytes (4 or 16). */
    internal fun localBytes(b: ByteArray): Boolean {
        fun u(i: Int) = b[i].toInt() and 0xff
        if (b.size == 4) return when {
            u(0) == 127 || u(0) == 10 -> true
            u(0) == 192 && u(1) == 168 -> true
            u(0) == 169 && u(1) == 254 -> true
            u(0) == 172 && u(1) in 16..31 -> true
            else -> false
        }
        if (b.size != 16) return false
        if ((0 until 10).all { u(it) == 0 } && u(10) == 0xff && u(11) == 0xff) return localBytes(b.copyOfRange(12, 16)) // ::ffff:a.b.c.d
        if ((0 until 15).all { u(it) == 0 } && u(15) == 1) return true // ::1
        if (u(0) and 0xfe == 0xfc) return true // fc00::/7
        if (u(0) == 0xfe && (u(1) and 0xc0 == 0x80 || u(1) and 0xc0 == 0xc0)) return true // fe80::/10, fec0::/10
        return false
    }

    /** Whether [EarthRest] may send to [base]: https, or the custom node's own local http. */
    internal fun allowed(base: String): Boolean {
        if (base.startsWith("https://", ignoreCase = true)) return true
        val node = current
        if (node.isDefault || (base != node.lcd && base != node.rpc)) return false
        return problem(base) == null
    }

    /** What a node said about itself: its chain id and latest height. */
    data class Probe(val chainId: String, val height: Long)

    /** How far behind the wall clock a node's latest block may be (it is syncing or stalled past that). */
    const val MAX_LAG_SECONDS = 10L * 60

    /** The most /genesis_chunked chunks read (CometBFT's are 16 MiB each). */
    const val MAX_GENESIS_CHUNKS = 8

    /** One chunk's response bound: 16 MiB as base64, and the JSON around it. */
    private const val GENESIS_CHUNK_BYTES = 24 * 1024 * 1024

    /**
     * Asks [node] which chain it follows and how far it is. Refused: a node
     * that is not [Constants.EARTH_CHAIN_ID]; one whose genesis is not the
     * live chain's (an earlier earth-1 genesis, or another chain under the
     * same id): the sha256 of what its RPC's /genesis_chunked serves must be
     * [Constants.EARTH_GENESIS_SHA256]; an LCD that does not hold the RPC's
     * block at their common height (so both are one node, or nodes of one
     * chain); and either one whose latest block is more than
     * [MAX_LAG_SECONDS] old. The RPC is required: only it serves the genesis,
     * and every node keeps that, state-synced or pruned.
     * On the caller's (IO) thread; throws with a message to show.
     */
    fun probe(node: Node): Probe {
        problem(node.lcd)?.let { throw IllegalArgumentException("LCD: $it") }
        if (node.rpc.isEmpty()) throw IllegalArgumentException(RPC_REQUIRED)
        problem(node.rpc)?.let { throw IllegalArgumentException("RPC: $it") }
        val body = lcdGet(node.lcd, "/cosmos/base/tendermint/v1beta1/blocks/latest", "the LCD")
        val header = runCatching {
            val j = JSONObject(body)
            (j.optJSONObject("sdk_block") ?: j.getJSONObject("block")).getJSONObject("header")
        }.getOrElse { throw IllegalStateException("That does not look like a Cosmos LCD.") }
        val chainId = header.optString("chain_id")
        if (chainId != Constants.EARTH_CHAIN_ID) throw IllegalStateException("That node follows \"$chainId\", not ${Constants.EARTH_CHAIN_ID}.")
        val height = header.optString("height").toLongOrNull() ?: throw IllegalStateException("The LCD did not say its latest height.")
        checkLag("The LCD", RestPrivateChain.parseTime(header.optString("time")))

        val status = runCatching { JSONObject(lcdGet(node.rpc, "/status", "the RPC")).let { it.optJSONObject("result") ?: it } }
            .getOrElse { throw if (it is IllegalStateException) it else IllegalStateException(NOT_RPC) }
        val net = status.optJSONObject("node_info")?.optString("network") ?: throw IllegalStateException(NOT_RPC)
        if (net != Constants.EARTH_CHAIN_ID) throw IllegalStateException("The RPC follows \"$net\", not ${Constants.EARTH_CHAIN_ID}.")
        val sync = status.optJSONObject("sync_info")
        checkLag("The RPC", RestPrivateChain.parseTime(sync?.optString("latest_block_time").orEmpty()))
        val rpcHeight = sync?.optString("latest_block_height")?.toLongOrNull() ?: throw IllegalStateException("The RPC did not say its latest height.")

        val genesis = genesisSha256 { chunk -> lcdGet(node.rpc, "/genesis_chunked?chunk=$chunk", "the RPC", GENESIS_CHUNK_BYTES) }
        if (genesis != Constants.EARTH_GENESIS_SHA256.lowercase()) throw IllegalStateException(OTHER_GENESIS)

        // The genesis is the RPC's; the LCD answers every query. Both must
        // hold the same block at a height both have.
        val common = minOf(height, rpcHeight)
        val lcdHash = runCatching {
            base64Hex(JSONObject(lcdGet(node.lcd, "/cosmos/base/tendermint/v1beta1/blocks/$common", "the LCD")).getJSONObject("block_id").getString("hash"))
        }.getOrNull()
        val rpcHash = runCatching {
            JSONObject(lcdGet(node.rpc, "/block?height=$common", "the RPC")).let { it.optJSONObject("result") ?: it }
                .getJSONObject("block_id").getString("hash").lowercase()
        }.getOrNull()
        if (lcdHash == null || rpcHash == null) throw IllegalStateException("Could not read block $common from both the LCD and the RPC to compare them; try again.")
        if (lcdHash != rpcHash) throw IllegalStateException(SPLIT_NODES)
        return Probe(chainId, height)
    }

    /**
     * sha256 of the genesis a CometBFT RPC serves: /genesis_chunked's
     * base64 chunks, decoded, in order. [chunk] fetches one response body.
     * 64 lowercase hex digits; throws with a message to show.
     */
    internal fun genesisSha256(chunk: (Int) -> String): String {
        val sha = java.security.MessageDigest.getInstance("SHA-256")
        var total = 1
        var i = 0
        while (i < total) {
            val r = runCatching { JSONObject(chunk(i)).let { it.optJSONObject("result") ?: it } }
                .getOrElse { throw if (it is IllegalStateException) it else IllegalStateException(NO_GENESIS) }
            val n = r.optString("total").toIntOrNull() ?: throw IllegalStateException(NO_GENESIS)
            if (i == 0) {
                if (n !in 1..MAX_GENESIS_CHUNKS) throw IllegalStateException(NO_GENESIS)
                total = n
            }
            if (n != total || r.optString("chunk").toIntOrNull() != i) throw IllegalStateException(NO_GENESIS)
            val data = r.optString("data").decodeBase64()?.toByteArray()?.takeIf { it.isNotEmpty() } ?: throw IllegalStateException(NO_GENESIS)
            sha.update(data)
            i++
        }
        return sha.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** Settings -> Network says this under the RPC field (docs quote it). */
    const val RPC_WHY = "Required. The wallet checks your node against the live chain's genesis, and only " +
        "the RPC serves it (/genesis_chunked). Every node keeps its genesis, state-synced or not. " +
        "The explorer also reads block ranges from it."

    private const val RPC_REQUIRED = "RPC: required. The wallet checks the node's genesis, which only the RPC serves."
    private const val NOT_RPC = "That does not look like a CometBFT RPC."
    private const val NO_GENESIS = "The RPC did not serve its genesis (/genesis_chunked), so the wallet cannot tell " +
        "which earth-1 it follows. Check that the URL is the node's CometBFT RPC (port 26657)."
    private const val OTHER_GENESIS = "That node follows another earth-1: its genesis is not the live chain's " +
        "(an earlier launch, or another network under the same name). Point it at the live chain."
    private const val SPLIT_NODES = "The LCD and the RPC disagree about a recent block: they are not following " +
        "the same chain. Use the LCD and RPC of one node."

    /** GET [path] from [base] (local http allowed for the probe); the body of a 2xx, else throws with a message. */
    private fun lcdGet(base: String, path: String, what: String, maxBytes: Int = EarthRest.MAX_BODY_BYTES): String {
        val (code, body) = runCatching { EarthRest.getFrom(base, path, allowLocal = true, maxBytes = maxBytes) }
            .getOrElse { throw IllegalStateException("Could not reach $what: ${it.message ?: it.javaClass.simpleName}") }
        if (code !in 200..299) throw IllegalStateException("${what.replaceFirstChar { it.uppercase() }} answered $code for $path.")
        return body
    }

    private fun checkLag(what: String, time: Long) {
        val now = System.currentTimeMillis() / 1000
        if (time <= 0) throw IllegalStateException("$what did not say when its latest block was made.")
        if (now - time > MAX_LAG_SECONDS) {
            throw IllegalStateException("$what's latest block is ${(now - time) / 60} minutes old: it is still syncing or has stopped. Try again once it has caught up.")
        }
    }

    /** A 32-byte hash as base64 (the LCD's encoding), as 64 lowercase hex digits; null otherwise. */
    private fun base64Hex(b64: String): String? =
        b64.decodeBase64()?.toByteArray()
            ?.takeIf { it.size == 32 }?.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
