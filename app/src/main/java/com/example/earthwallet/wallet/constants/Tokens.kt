package network.erth.wallet.wallet.constants

/**
 * Token constants for SNIP-20 tokens on Secret Network
 * Contains contract addresses, code hashes, and metadata for supported tokens
 */
object Tokens {

    /**
     * Token information class with public fields for Java compatibility
     * Contract and hash are mutable to allow population from registry
     */
    class TokenInfo @JvmOverloads constructor(
        @JvmField var contract: String,
        @JvmField var hash: String,
        @JvmField val decimals: Int,
        @JvmField val symbol: String,
        @JvmField val logo: String,
        @JvmField val coingeckoId: String? = null
    )

    // Static token constants for Java compatibility
    // Contract addresses and hashes populated from registry on startup with fallback defaults
    @JvmField
    val ERTH = TokenInfo(
        contract = "secret16snu3lt8k9u0xr54j2hqyhvwnx9my7kq7ay8lp",
        hash = "72e7242ceb5e3e441243f5490fab2374df0d3e828ce33aa0f0b4aad226cfedd7",
        decimals = 6,
        symbol = "ERTH",
        logo = "coin/ERTH.png"
    )

    @JvmField
    val ANML = TokenInfo(
        contract = "secret14p6dhjznntlzw0yysl7p6z069nk0skv5e9qjut",
        hash = "72e7242ceb5e3e441243f5490fab2374df0d3e828ce33aa0f0b4aad226cfedd7",
        decimals = 6,
        symbol = "ANML",
        logo = "coin/ANML.png"
    )

    @JvmField
    val SSCRT = TokenInfo(
        contract = "secret1k0jntykt7e4g3y88ltc60czgjuqdy4c9e8fzek",
        hash = "af74387e276be8874f07bec3a87023ee49b0e7ebe08178c49d0a49c3c98ed60e",
        decimals = 6,
        symbol = "sSCRT",
        logo = "coin/SSCRT.png",
        coingeckoId = "secret"
    )

    // SNIP-20 Token Registry
    private val tokenRegistry = mapOf(
        "erth" to ERTH,
        "anml" to ANML,
        "sscrt" to SSCRT
    )

    /**
     * Get token information by symbol or contract address
     */
    @JvmStatic
    fun getTokenInfo(identifier: String): TokenInfo? {
        // Try by symbol first
        return tokenRegistry[identifier.lowercase()] ?:
               // Try by contract address
               tokenRegistry.values.find { it.contract == identifier }
    }

    /**
     * Legacy Java compatibility method
     */
    @JvmStatic
    fun getToken(identifier: String): TokenInfo? = getTokenInfo(identifier)

    /**
     * Get all registered tokens (returns map with proper symbol names as keys)
     */
    @JvmStatic
    fun getAllTokens(): Map<String, TokenInfo> = tokenRegistry.mapKeys { (_, value) -> value.symbol }

    /**
     * Legacy Java compatibility - ALL_TOKENS field
     */
    @JvmField
    val ALL_TOKENS: Map<String, TokenInfo> = mapOf(
        "ERTH" to ERTH,
        "ANML" to ANML,
        "sSCRT" to SSCRT
    )

    /**
     * Check if a token is supported
     */
    @JvmStatic
    fun isTokenSupported(identifier: String): Boolean = getTokenInfo(identifier) != null

    /**
     * Get token symbols (returns proper symbol names for display)
     */
    @JvmStatic
    fun getSupportedTokenSymbols(): List<String> = tokenRegistry.values.map { it.symbol }

    /**
     * Format token amount with proper decimals (Java compatibility version)
     */
    @JvmStatic
    fun formatTokenAmount(rawAmount: String, token: TokenInfo): String {
        return try {
            val amount = rawAmount.toLong()
            val divisor = Math.pow(10.0, token.decimals.toDouble())
            val formatted = amount / divisor

            // Format to reasonable decimal places
            if (formatted == formatted.toLong().toDouble()) {
                String.format("%.0f", formatted)
            } else if (formatted >= 1) {
                String.format("%.2f", formatted)
            } else {
                String.format("%.6f", formatted).trimEnd('0').trimEnd('.')
            }
        } catch (e: Exception) {
            rawAmount
        }
    }

    /**
     * Format token amount with proper decimals (overload for identifier lookup)
     */
    @JvmStatic
    fun formatTokenAmount(amount: Long, tokenIdentifier: String): String {
        val tokenInfo = getTokenInfo(tokenIdentifier) ?: return amount.toString()
        val divisor = Math.pow(10.0, tokenInfo.decimals.toDouble()).toLong()
        val whole = amount / divisor
        val frac = Math.abs(amount % divisor)

        if (frac == 0L) {
            return whole.toString()
        }

        val fracStr = String.format("%0${tokenInfo.decimals}d", frac)
            .trimEnd('0')

        return if (fracStr.isEmpty()) whole.toString() else "$whole.$fracStr"
    }

    /**
     * Parse token amount string to micro units
     */
    @JvmStatic
    fun parseTokenAmount(amountStr: String, tokenIdentifier: String): Long? {
        return try {
            val tokenInfo = getTokenInfo(tokenIdentifier) ?: return null
            val parts = amountStr.split(".")
            val wholePart = parts[0].toLongOrNull() ?: 0L
            val fracPart = if (parts.size > 1) {
                val fracStr = parts[1].padEnd(tokenInfo.decimals, '0')
                    .take(tokenInfo.decimals)
                fracStr.toLongOrNull() ?: 0L
            } else {
                0L
            }

            val multiplier = Math.pow(10.0, tokenInfo.decimals.toDouble()).toLong()
            wholePart * multiplier + fracPart
        } catch (e: Exception) {
            null
        }
    }
}