package network.erth.wallet.wallet.services

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Service to fetch and cache ERTH token price from the API
 */
object ErthPriceService {
    private const val TAG = "ErthPriceService"
    private const val ERTH_API_URL = "https://api.erth.network/erth-price"
    private const val CACHE_TTL_MS = 60_000L // 1 minute cache

    private var cachedPrice: Double? = null
    private var lastFetchTime: Long = 0

    /**
     * Fetch the current ERTH price in USD
     * @return The ERTH price in USD, or null if fetch fails
     */
    suspend fun fetchErthPrice(): Double? = withContext(Dispatchers.IO) {
        // Return cached price if still valid
        val now = System.currentTimeMillis()
        if (cachedPrice != null && (now - lastFetchTime) < CACHE_TTL_MS) {
            return@withContext cachedPrice
        }

        try {
            val url = URL(ERTH_API_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/json")

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)

                val price = json.optDouble("price", -1.0)
                if (price > 0) {
                    cachedPrice = price
                    lastFetchTime = now
                    return@withContext price
                }
            }

            Log.w(TAG, "Failed to fetch ERTH price: HTTP $responseCode")
            return@withContext cachedPrice // Return stale cache if available
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching ERTH price", e)
            return@withContext cachedPrice // Return stale cache if available
        }
    }

    /**
     * Get the cached price without making a network request
     */
    fun getCachedPrice(): Double? = cachedPrice

    /**
     * Format a USD value for display
     */
    fun formatUSD(value: Double): String {
        return if (value < 0.01 && value > 0) {
            String.format("$%.4f", value)
        } else {
            String.format("$%,.2f", value)
        }
    }

    /**
     * Clear the cached price
     */
    fun clearCache() {
        cachedPrice = null
        lastFetchTime = 0
    }
}
