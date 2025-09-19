package com.example.earthwallet.wallet.constants

/**
 * Token constants for SNIP-20 tokens on Secret Network
 * Contains contract addresses, code hashes, and metadata for supported tokens
 */
object Tokens {

    /**
     * Token information class with public fields for Java compatibility
     */
    class TokenInfo @JvmOverloads constructor(
        @JvmField val contract: String,
        @JvmField val hash: String,
        @JvmField val decimals: Int,
        @JvmField val symbol: String,
        @JvmField val logo: String,
        @JvmField val coingeckoId: String? = null,
        @JvmField val lp: LpInfo? = null
    )

    /**
     * Liquidity pool information
     */
    class LpInfo constructor(
        @JvmField val contract: String,
        @JvmField val hash: String,
        @JvmField val decimals: Int,
        @JvmField val asset0: String? = null,
        @JvmField val asset1: String? = null
    )

    // Static token constants for Java compatibility
    @JvmField
    val ERTH = TokenInfo(
        contract = "secret16snu3lt8k9u0xr54j2hqyhvwnx9my7kq7ay8lp",
        hash = "638a3e1d50175fbcb8373cf801565283e3eb23d88a9b7b7f99fcc5eb1e6b561e",
        decimals = 6,
        symbol = "ERTH",
        logo = "coin/ERTH.png"
    )

    @JvmField
    val ANML = TokenInfo(
        contract = "secret14p6dhjznntlzw0yysl7p6z069nk0skv5e9qjut",
        hash = "638a3e1d50175fbcb8373cf801565283e3eb23d88a9b7b7f99fcc5eb1e6b561e",
        decimals = 6,
        symbol = "ANML",
        logo = "coin/ANML.png",
        coingeckoId = null,
        lp = LpInfo(
            contract = "secret1cqxxq586zl6g5zly3536rc7crwfcmeluuwehvx",
            hash = "638a3e1d50175fbcb8373cf801565283e3eb23d88a9b7b7f99fcc5eb1e6b561e",
            decimals = 6
        )
    )

    @JvmField
    val SSCRT = TokenInfo(
        contract = "secret1k0jntykt7e4g3y88ltc60czgjuqdy4c9e8fzek",
        hash = "af74387e276be8874f07bec3a87023ee49b0e7ebe08178c49d0a49c3c98ed60e",
        decimals = 6,
        symbol = "sSCRT",
        logo = "coin/SSCRT.png",
        coingeckoId = "secret",
        lp = LpInfo(
            contract = "secret1lqmvkxrhqfjj64ge8cr9v8pwnz4enhw7f6hdys",
            hash = "638a3e1d50175fbcb8373cf801565283e3eb23d88a9b7b7f99fcc5eb1e6b561e",
            decimals = 6
        )
    )

    // SNIP-20 Token Registry
    private val tokenRegistry = mapOf(
        "erth" to ERTH,
        "anml" to ANML,
        "sscrt" to SSCRT,
        // SCRT (native token)
        "uscrt" to TokenInfo(
            contract = "",
            hash = "",
            decimals = 6,
            symbol = "SCRT",
            logo = "scrt",
            coingeckoId = "secret"
        ),

        // stkd-SCRT
        "stkd-scrt" to TokenInfo(
            contract = "secret1k6u0cy4feepm6pehnz804zmwakuwdapm69tuc4",
            hash = "f6be719b3c6feb498d3554ca0398eb6b7e7db262acb33f84a8f12106da6bbb09",
            decimals = 6,
            symbol = "stkd-SCRT",
            logo = "stkd-scrt",
            coingeckoId = "stkd-scrt"
        ),

        // BUTT
        "butt" to TokenInfo(
            contract = "secret1yxcexylwyxlq58umhgsjgstgcg2a0ytfy4d9lt",
            hash = "f7b6982bb50e11ff036dd9a4f03d7e86a0c35e4df0e1b5af8e8f8b3e76e1c3e",
            decimals = 6,
            symbol = "BUTT",
            logo = "butt",
            coingeckoId = "buttcoin-2"
        ),

        // SHD
        "shd" to TokenInfo(
            contract = "secret1qfql357amn448duf5gvp9gr48sxx9tsnhupu3d",
            hash = "be2d98a5b4c68ad6b4b4c60c1b68b9f4a4e3b8b0c9e7b88b2b3d9e1c3e6b4f4a",
            decimals = 8,
            symbol = "SHD",
            logo = "shd",
            coingeckoId = "shade-protocol"
        ),

        // SILK
        "silk" to TokenInfo(
            contract = "secret1fl449muk5yq8dlad7a22nje4p5d2pnsgymhjfd",
            hash = "ad91060456344fc8d8e93c0600a3957b8158605c044b3bef7048510b3157b807",
            decimals = 6,
            symbol = "SILK",
            logo = "silk",
            coingeckoId = "silk-bcec1136-561c-4706-a42c-8b67d0d7f7d2"
        )
    )

    // Liquidity Pairs Registry
    private val liquidityPairRegistry = mapOf(
        "scrt-stkd-scrt" to TokenInfo(
            contract = "secret1w8d0ntrhrys4yzcfxnwprts7gfg5gfw86ccdpf",
            hash = "c7fe5b89b3f4e8c5d9e7b8b0a3f5c9e1b3d7f4a8c2e6b9f0d4e8a1c5e9b3d7f4",
            decimals = 6,
            symbol = "SCRT-stkd-SCRT",
            logo = "scrt-stkd-scrt",
            lp = LpInfo(
                contract = "secret1w8d0ntrhrys4yzcfxnwprts7gfg5gfw86ccdpf",
                hash = "c7fe5b89b3f4e8c5d9e7b8b0a3f5c9e1b3d7f4a8c2e6b9f0d4e8a1c5e9b3d7f4",
                decimals = 6,
                asset0 = "uscrt",
                asset1 = "stkd-scrt"
            )
        )
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
     * Get all liquidity pairs
     */
    @JvmStatic
    fun getAllLiquidityPairs(): Map<String, TokenInfo> = liquidityPairRegistry

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