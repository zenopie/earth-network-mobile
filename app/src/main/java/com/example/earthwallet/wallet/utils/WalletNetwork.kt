package com.example.earthwallet.wallet.utils

import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * WalletNetwork
 *
 * Network utility functions for Secret Network operations.
 * Handles LCD API calls, balance queries, and response formatting.
 * All methods are stateless network operations.
 */
object WalletNetwork {

    private const val TAG = "WalletNetwork"
    const val DEFAULT_LCD_URL = "https://lcd.erth.network"

    /**
     * Fetch SCRT balance from LCD endpoint
     */
    @JvmStatic
    @Throws(Exception::class)
    fun fetchUscrtBalanceMicro(lcdBaseUrl: String?, address: String): Long {
        val base = if (lcdBaseUrl.isNullOrBlank()) DEFAULT_LCD_URL else lcdBaseUrl.trim()
        val cleanBase = if (base.endsWith("/")) base.dropLast(1) else base
        val url = "$cleanBase/cosmos/bank/v1beta1/balances/$address"

        val body = httpGet(url)
        if (body.isNullOrEmpty()) return 0L

        val root = JSONObject(body)
        val balances = root.optJSONArray("balances") ?: return 0L

        var total = 0L
        for (i in 0 until balances.length()) {
            val balance = balances.optJSONObject(i) ?: continue
            val denom = balance.optString("denom", "")
            val amount = balance.optString("amount", "0")
            if (denom == "uscrt") {
                try {
                    total += amount.toLong()
                } catch (e: NumberFormatException) {
                    // Ignore invalid amounts
                }
            }
        }
        return total
    }

    /**
     * Format SCRT amount from microSCRT to display format
     */
    @JvmStatic
    fun formatScrt(micro: Long): String {
        // 6 decimals
        val whole = micro / 1_000_000L
        val frac = kotlin.math.abs(micro % 1_000_000L)
        // trim trailing zeros
        val fracStr = String.format("%06d", frac).replace(Regex("0+$"), "")
        return if (fracStr.isEmpty()) whole.toString() else "$whole.$fracStr"
    }

    /**
     * Perform HTTP GET request
     */
    @JvmStatic
    @Throws(Exception::class)
    fun httpGet(urlStr: String): String {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.requestMethod = "GET"
            conn.connect()

            val inputStream = if (conn.responseCode >= 400) {
                conn.errorStream
            } else {
                conn.inputStream
            }

            inputStream?.use { stream ->
                readAllBytes(stream).toString(Charsets.UTF_8)
            } ?: ""
        } finally {
            conn?.disconnect()
        }
    }

    @Throws(Exception::class)
    private fun readAllBytes(inputStream: InputStream): ByteArray {
        return inputStream.use { stream ->
            val baos = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            var n: Int
            while (stream.read(buf).also { n = it } != -1) {
                baos.write(buf, 0, n)
            }
            baos.toByteArray()
        }
    }
}