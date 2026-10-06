package network.erth.wallet.chain

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import network.erth.wallet.Constants
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
        val rpc = p.getString(KEY_RPC, null)?.let { if (it.isEmpty()) "" else normalize(it) } ?: ""
        if (problem(lcd) == null && (rpc.isEmpty() || problem(rpc) == null)) _node.value = Node(lcd, rpc)
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
     * link-local or RFC 1918 / unique-local address, or a .local name.
     * Literal addresses only (no DNS lookup): a public name that resolves
     * to a private address is still a public name.
     */
    fun isLocal(host: String?): Boolean {
        val h = host?.lowercase()?.trim('[', ']') ?: return false
        if (h == "localhost" || h.endsWith(".local")) return true
        // Literals only, so getByName never resolves a name.
        val v4 = Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(h)
        val v6 = h.contains(':') && h.all { it.isDigit() || it == ':' || it == '.' || it in 'a'..'f' }
        if (!v4 && !v6) return false
        val a = runCatching { InetAddress.getByName(h) }.getOrNull() ?: return false
        return when (a) {
            is Inet4Address, is Inet6Address ->
                a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress ||
                    (a is Inet6Address && (a.address[0].toInt() and 0xfe) == 0xfc)
            else -> false
        }
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

    /**
     * Asks [node] which chain it follows and how far it is, and refuses
     * one that is not [Constants.EARTH_CHAIN_ID]. The RPC, when given, is
     * checked the same way. On the caller's (IO) thread; throws with a
     * message to show.
     */
    fun probe(node: Node): Probe {
        problem(node.lcd)?.let { throw IllegalArgumentException("LCD: $it") }
        if (node.rpc.isNotEmpty()) problem(node.rpc)?.let { throw IllegalArgumentException("RPC: $it") }
        val (code, body) = runCatching { EarthRest.getFrom(node.lcd, "/cosmos/base/tendermint/v1beta1/blocks/latest", allowLocal = true) }
            .getOrElse { throw IllegalStateException("Could not reach the LCD: ${it.message ?: it.javaClass.simpleName}") }
        if (code !in 200..299) throw IllegalStateException("The LCD answered $code.")
        val header = runCatching {
            val j = JSONObject(body)
            (j.optJSONObject("sdk_block") ?: j.getJSONObject("block")).getJSONObject("header")
        }.getOrElse { throw IllegalStateException("That does not look like a Cosmos LCD.") }
        val chainId = header.optString("chain_id")
        if (chainId != Constants.EARTH_CHAIN_ID) throw IllegalStateException("That node follows \"$chainId\", not ${Constants.EARTH_CHAIN_ID}.")
        val height = header.optString("height").toLongOrNull() ?: throw IllegalStateException("The LCD did not say its latest height.")
        if (node.rpc.isNotEmpty()) {
            val (rc, rb) = runCatching { EarthRest.getFrom(node.rpc, "/status", allowLocal = true) }
                .getOrElse { throw IllegalStateException("Could not reach the RPC: ${it.message ?: it.javaClass.simpleName}") }
            if (rc !in 200..299) throw IllegalStateException("The RPC answered $rc.")
            val network = runCatching {
                val j = JSONObject(rb)
                (j.optJSONObject("result") ?: j).getJSONObject("node_info").getString("network")
            }.getOrElse { throw IllegalStateException("That does not look like a CometBFT RPC.") }
            if (network != Constants.EARTH_CHAIN_ID) throw IllegalStateException("The RPC follows \"$network\", not ${Constants.EARTH_CHAIN_ID}.")
        }
        return Probe(chainId, height)
    }
}
