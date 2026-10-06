package network.erth.wallet.chain

import network.erth.wallet.Constants
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * EarthRest
 *
 * Tiny REST client for the earth chain LCD (cosmos gRPC-gateway). Plain
 * HttpURLConnection to avoid extra dependencies; all chain I/O goes through here.
 * `path` is relative to the node's LCD ([NodeConfig.current]: Earth's own
 * unless the user chose theirs), e.g. "/cosmos/bank/v1beta1/...".
 */
object EarthRest {

    /** Returns (httpCode, body). Does not throw on non-2xx. */
    fun get(path: String): Pair<Int, String> = getFrom(NodeConfig.current.lcd, path)

    /**
     * [get] of the state at block [height] (the gRPC gateway's
     * `x-cosmos-block-height` request header; a pruned height answers an
     * error), with the height the node says it answered at (the same
     * response header; null when absent). A caller pinning state to a height
     * checks the two agree.
     */
    fun getAtEcho(path: String, height: Long): Triple<Int, String, Long?> {
        var echo: Long? = null
        val (code, body) = getFrom(NodeConfig.current.lcd, path, height) { conn ->
            echo = conn.getHeaderField("x-cosmos-block-height")?.trim()?.toLongOrNull()
        }
        return Triple(code, body, echo)
    }

    /**
     * The most any response is read to: a node or proxy streaming without
     * end (or a hostile one) cannot exhaust memory. The largest legitimate
     * answers (a page of positions or validators) are far below it.
     */
    const val MAX_BODY_BYTES = 8 * 1024 * 1024

    /** The deepest JSON nesting any response may have (Android's org.json recurses without a cap). */
    const val MAX_JSON_DEPTH = 64

    /**
     * Refuses [body] when its JSON arrays/objects nest deeper than [max]
     * (counted outside strings), before any parser sees it: a deeply nested
     * body would otherwise overflow org.json's recursion with a
     * StackOverflowError, which no `catch (Exception)` stops.
     */
    fun checkJsonDepth(body: String, max: Int = MAX_JSON_DEPTH) {
        var depth = 0
        var inString = false
        var escaped = false
        for (c in body) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '[', '{' -> if (++depth > max) throw java.io.IOException("response nests deeper than $max")
                ']', '}' -> depth--
            }
        }
    }

    private fun readBounded(stream: java.io.InputStream?): String {
        if (stream == null) return ""
        return stream.use { s ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_BODY_BYTES) throw java.io.IOException("response exceeds $MAX_BODY_BYTES bytes")
                out.write(buf, 0, n)
            }
            out.toString("UTF-8").also { checkJsonDepth(it) }
        }
    }

    /**
     * Same as [get] but against the CometBFT RPC port, which serves the one
     * thing the LCD cannot: a range of blocks in a single request. Callers must
     * tolerate it being unreachable — see [Constants.EARTH_RPC_URL].
     */
    fun getRpc(path: String): Pair<Int, String> = getFrom(NodeConfig.current.rpc, path)

    /**
     * Plain http:// only to the user's own node at a local address
     * (NodeConfig.allowed); [allowLocal] lets Settings probe one before it
     * is saved. Everything built in is https.
     */
    private fun checkBase(base: String, allowLocal: Boolean) {
        val ok = if (allowLocal) base.startsWith("https://", ignoreCase = true) || NodeConfig.problem(base) == null else NodeConfig.allowed(base)
        if (!ok) throw IOException("refusing a cleartext connection to $base")
    }

    internal fun getFrom(base: String, path: String, height: Long? = null, allowLocal: Boolean = false, headers: (HttpURLConnection) -> Unit = {}): Pair<Int, String> {
        // An unset base is a supported configuration, not an error: the RPC is
        // optional and is left empty when the deployment exposes only the LCD.
        // Reported as a non-2xx so callers take their existing failure path
        // instead of an exception thrown from the URL constructor, which is
        // outside the try below.
        if (base.isBlank()) return 0 to ""
        checkBase(base, allowLocal)

        val conn = URL(base + path).openConnection() as HttpURLConnection
        return try {
            // Never followed. A redirect would take the request to
            // another origin than the one configured; a 3xx is an error.
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20000
            conn.readTimeout = 30000
            conn.requestMethod = "GET"
            height?.let { conn.setRequestProperty("x-cosmos-block-height", it.toString()) }
            val code = conn.responseCode
            headers(conn)
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            code to readBounded(stream)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Returns (httpCode, body). Does not throw on non-2xx.
     *
     * [base] is the LCD unless told otherwise; the gas grant posts to the
     * backend through here too rather than growing a second HTTP client.
     */
    fun postJson(path: String, json: String, base: String = NodeConfig.current.lcd): Pair<Int, String> {
        checkBase(base, allowLocal = false)
        val conn = URL(base + path).openConnection() as HttpURLConnection
        return try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20000
            conn.readTimeout = 30000
            conn.doOutput = true
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val out = json.toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(out.size)
            conn.connect()
            conn.outputStream.use { it.write(out); it.flush() }
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            code to readBounded(stream)
        } finally {
            conn.disconnect()
        }
    }
}
