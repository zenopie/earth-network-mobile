package network.erth.wallet.chain

import network.erth.wallet.Constants
import java.net.HttpURLConnection
import java.net.URL

/**
 * EarthRest
 *
 * Tiny REST client for the earth chain LCD (cosmos gRPC-gateway). Plain
 * HttpURLConnection to avoid extra dependencies; all chain I/O goes through here.
 * `path` is relative to [Constants.EARTH_LCD_URL], e.g. "/cosmos/bank/v1beta1/...".
 */
object EarthRest {

    /** Returns (httpCode, body). Does not throw on non-2xx. */
    fun get(path: String): Pair<Int, String> = getFrom(Constants.EARTH_LCD_URL, path)

    /**
     * Same as [get] but against the CometBFT RPC port, which serves the one
     * thing the LCD cannot: a range of blocks in a single request. Callers must
     * tolerate it being unreachable — see [Constants.EARTH_RPC_URL].
     */
    fun getRpc(path: String): Pair<Int, String> = getFrom(Constants.EARTH_RPC_URL, path)

    private fun getFrom(base: String, path: String): Pair<Int, String> {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 20000
            conn.readTimeout = 30000
            conn.requestMethod = "GET"
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            code to (stream?.readBytes()?.toString(Charsets.UTF_8) ?: "")
        } finally {
            conn.disconnect()
        }
    }

    /** Returns (httpCode, body). Does not throw on non-2xx. */
    fun postJson(path: String, json: String): Pair<Int, String> {
        val conn = URL(Constants.EARTH_LCD_URL + path).openConnection() as HttpURLConnection
        return try {
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
            code to (stream?.readBytes()?.toString(Charsets.UTF_8) ?: "")
        } finally {
            conn.disconnect()
        }
    }
}
