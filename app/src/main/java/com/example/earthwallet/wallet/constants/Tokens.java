package com.example.earthwallet.wallet.constants;

import java.util.HashMap;
import java.util.Map;

/**
 * Token constants for SNIP-20 tokens on Secret Network
 * Contains contract addresses, code hashes, and metadata for supported tokens
 */
public class Tokens {
    
    /**
     * Token information class
     */
    public static class TokenInfo {
        public final String contract;
        public final String hash;
        public final int decimals;
        public final String symbol;
        public final String logo;
        public final String coingeckoId;
        public final LpInfo lp;
        
        public TokenInfo(String contract, String hash, int decimals, String symbol, String logo) {
            this(contract, hash, decimals, symbol, logo, null, null);
        }
        
        public TokenInfo(String contract, String hash, int decimals, String symbol, String logo, String coingeckoId) {
            this(contract, hash, decimals, symbol, logo, coingeckoId, null);
        }
        
        public TokenInfo(String contract, String hash, int decimals, String symbol, String logo, String coingeckoId, LpInfo lp) {
            this.contract = contract;
            this.hash = hash;
            this.decimals = decimals;
            this.symbol = symbol;
            this.logo = logo;
            this.coingeckoId = coingeckoId;
            this.lp = lp;
        }
    }
    
    /**
     * Liquidity pool information
     */
    public static class LpInfo {
        public final String contract;
        public final String hash;
        public final int decimals;
        
        public LpInfo(String contract, String hash, int decimals) {
            this.contract = contract;
            this.hash = hash;
            this.decimals = decimals;
        }
    }
    
    // Token definitions
    public static final TokenInfo ERTH = new TokenInfo(
        "secret16snu3lt8k9u0xr54j2hqyhvwnx9my7kq7ay8lp",
        "638a3e1d50175fbcb8373cf801565283e3eb23d88a9b7b7f99fcc5eb1e6b561e",
        6,
        "ERTH",
        "/images/coin/ERTH.png"
    );
    
    public static final TokenInfo ANML = new TokenInfo(
        "secret14p6dhjznntlzw0yysl7p6z069nk0skv5e9qjut",
        "638a3e1d50175fbcb8373cf801565283e3eb23d88a9b7b7f99fcc5eb1e6b561e",
        6,
        "ANML",
        "/images/coin/ANML.png",
        null,
        new LpInfo(
            "secret1cqxxq586zl6g5zly3536rc7crwfcmeluuwehvx",
            "638a3e1d50175fbcb8373cf801565283e3eb23d88a9b7b7f99fcc5eb1e6b561e",
            6
        )
    );
    
    public static final TokenInfo SSCRT = new TokenInfo(
        "secret1k0jntykt7e4g3y88ltc60czgjuqdy4c9e8fzek",
        "af74387e276be8874f07bec3a87023ee49b0e7ebe08178c49d0a49c3c98ed60e",
        6,
        "sSCRT",
        "/images/coin/SSCRT.png",
        "secret",
        new LpInfo(
            "secret1lqmvkxrhqfjj64ge8cr9v8pwnz4enhw7f6hdys",
            "638a3e1d50175fbcb8373cf801565283e3eb23d88a9b7b7f99fcc5eb1e6b561e",
            6
        )
    );
    
    // All supported tokens
    public static final Map<String, TokenInfo> ALL_TOKENS = new HashMap<String, TokenInfo>() {{
        put("ERTH", ERTH);
        put("ANML", ANML);
        put("sSCRT", SSCRT);
    }};
    
    /**
     * Get token info by symbol
     */
    public static TokenInfo getToken(String symbol) {
        return ALL_TOKENS.get(symbol);
    }
    
    /**
     * Get token info by contract address
     */
    public static TokenInfo getTokenByContract(String contractAddress) {
        for (TokenInfo token : ALL_TOKENS.values()) {
            if (token.contract.equals(contractAddress)) {
                return token;
            }
        }
        return null;
    }
    
    /**
     * Format token amount with correct decimals
     */
    public static String formatTokenAmount(String rawAmount, TokenInfo token) {
        try {
            long amount = Long.parseLong(rawAmount);
            double divisor = Math.pow(10, token.decimals);
            double formatted = amount / divisor;
            
            // Format to reasonable decimal places
            if (formatted == (long) formatted) {
                return String.format("%.0f", formatted);
            } else if (formatted >= 1) {
                return String.format("%.2f", formatted);
            } else {
                return String.format("%.6f", formatted);
            }
        } catch (NumberFormatException e) {
            return "0";
        }
    }
}