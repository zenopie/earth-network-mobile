package network.erth.wallet.wallet.constants

/**
 * Token registry for the earth chain. Tokens are native bank denoms (no SNIP-20
 * contracts, no viewing keys) — each [TokenInfo] carries its base `denom`.
 */
object Tokens {

    class TokenInfo @JvmOverloads constructor(
        @JvmField val denom: String,
        @JvmField val decimals: Int,
        @JvmField val symbol: String,
        @JvmField val logo: String,
        @JvmField val coingeckoId: String? = null,
    )

    @JvmField
    val ERTH = TokenInfo(denom = "uerth", decimals = 6, symbol = "ERTH", logo = "coin/ERTH.png")

    @JvmField
    val ANML = TokenInfo(denom = "uanml", decimals = 6, symbol = "ANML", logo = "coin/ANML.png")

    @JvmField
    val USDC = TokenInfo(denom = "uusdc", decimals = 6, symbol = "USDC", logo = "coin/USDC.png")

    @JvmField
    val ATOM = TokenInfo(denom = "uatom", decimals = 6, symbol = "ATOM", logo = "coin/ATOM.png")

    private val tokenRegistry = mapOf(
        "erth" to ERTH,
        "anml" to ANML,
        "usdc" to USDC,
        "atom" to ATOM,
    )

    /** Look up a token by symbol or by base denom. */
    @JvmStatic
    fun getTokenInfo(identifier: String): TokenInfo? =
        tokenRegistry[identifier.lowercase()] ?: tokenRegistry.values.find { it.denom == identifier }

    @JvmStatic
    fun getToken(identifier: String): TokenInfo? = getTokenInfo(identifier)

    @JvmStatic
    fun getAllTokens(): Map<String, TokenInfo> = tokenRegistry.mapKeys { (_, v) -> v.symbol }

    @JvmField
    val ALL_TOKENS: Map<String, TokenInfo> =
        mapOf("ERTH" to ERTH, "ANML" to ANML, "USDC" to USDC, "ATOM" to ATOM)

    @JvmStatic
    fun isTokenSupported(identifier: String): Boolean = getTokenInfo(identifier) != null

    @JvmStatic
    fun getSupportedTokenSymbols(): List<String> = tokenRegistry.values.map { it.symbol }

    /** Format a base-unit amount string using the token's decimals. */
    @JvmStatic
    fun formatTokenAmount(rawAmount: String, token: TokenInfo): String {
        return try {
            val amount = rawAmount.toLong()
            val divisor = Math.pow(10.0, token.decimals.toDouble())
            val formatted = amount / divisor
            when {
                formatted == formatted.toLong().toDouble() -> String.format("%.0f", formatted)
                formatted >= 1 -> String.format("%.2f", formatted)
                else -> String.format("%.6f", formatted).trimEnd('0').trimEnd('.')
            }
        } catch (e: Exception) {
            rawAmount
        }
    }

    @JvmStatic
    fun formatTokenAmount(amount: Long, tokenIdentifier: String): String {
        val tokenInfo = getTokenInfo(tokenIdentifier) ?: return amount.toString()
        val divisor = Math.pow(10.0, tokenInfo.decimals.toDouble()).toLong()
        val whole = amount / divisor
        val frac = Math.abs(amount % divisor)
        if (frac == 0L) return whole.toString()
        val fracStr = String.format("%0${tokenInfo.decimals}d", frac).trimEnd('0')
        return if (fracStr.isEmpty()) whole.toString() else "$whole.$fracStr"
    }

    /** Parse a decimal amount string into base units for the given token. */
    @JvmStatic
    fun parseTokenAmount(amountStr: String, tokenIdentifier: String): Long? {
        return try {
            val tokenInfo = getTokenInfo(tokenIdentifier) ?: return null
            val parts = amountStr.split(".")
            val wholePart = parts[0].toLongOrNull() ?: 0L
            val fracPart = if (parts.size > 1) {
                parts[1].padEnd(tokenInfo.decimals, '0').take(tokenInfo.decimals).toLongOrNull() ?: 0L
            } else 0L
            val multiplier = Math.pow(10.0, tokenInfo.decimals.toDouble()).toLong()
            wholePart * multiplier + fracPart
        } catch (e: Exception) {
            null
        }
    }
}
