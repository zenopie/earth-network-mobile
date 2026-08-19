package network.erth.wallet.wallet.services

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import network.erth.wallet.chain.Dex
import network.erth.wallet.wallet.constants.Tokens

/**
 * Derives the ERTH price in USD entirely on-chain from the ERTH/USDC dex pool
 * (USDC is treated as $1). No external price API is needed — every other USD
 * figure in the app is this price times an on-chain pool spot rate.
 */
object ErthPriceService {
    private const val TAG = "ErthPriceService"
    private const val CACHE_TTL_MS = 60_000L // 1 minute cache

    private var cachedPrice: Double? = null
    private var lastFetchTime: Long = 0

    /**
     * ERTH price in USD, derived from the ERTH/USDC pool spot rate, or null if
     * there is no USDC pool yet (falls back to the last cached value if any).
     */
    suspend fun fetchErthPrice(): Double? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (cachedPrice != null && (now - lastFetchTime) < CACHE_TTL_MS) {
            return@withContext cachedPrice
        }

        try {
            val pool = Dex.poolForToken(Tokens.USDC.denom) ?: return@withContext cachedPrice
            val erthReserve = pool.erthReserve.toDoubleOrNull() ?: return@withContext cachedPrice
            val usdcReserve = pool.tokenReserve.toDoubleOrNull() ?: return@withContext cachedPrice
            if (erthReserve <= 0) return@withContext cachedPrice
            // ERTH and USDC share 6 decimals, so usdc/erth is USD per ERTH directly.
            val price = usdcReserve / erthReserve
            if (price > 0) {
                cachedPrice = price
                lastFetchTime = now
                return@withContext price
            }
            return@withContext cachedPrice
        } catch (e: Exception) {
            Log.e(TAG, "Error deriving ERTH price from USDC pool", e)
            return@withContext cachedPrice
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
