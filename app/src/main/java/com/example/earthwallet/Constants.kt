package network.erth.wallet

object Constants {
    // Contract Registry - the source of truth for all contract addresses
    const val REGISTRY_CONTRACT = "secret1ql943kl7fd7pyv9njf7rmngxhzljncgx6eyw5j"
    const val REGISTRY_HASH = "2a53df1dc1d8f37ecddd9463930c9caa4940fed94f9a8cd113d6285eef09445b"

    // Contract details - populated from registry on startup with fallback defaults
    // Registration contract details
    @JvmField var REGISTRATION_CONTRACT: String = "secret12q72eas34u8fyg68k6wnerk2nd6l5gaqppld6p"
    @JvmField var REGISTRATION_HASH: String = "e6f9a7a7a6060721b0cf511d78a423c216fb961668ceeb7289dc189a94a7b730"

    // Exchange contract details
    @JvmField var EXCHANGE_CONTRACT: String = "secret1rj2phrf6x3v7526jrz60m2dcq58slyq2269kra"
    @JvmField var EXCHANGE_HASH: String = "58c616e3736ccaecbdb7293a60ca1f8b4d64a75559a1dee941d1292a489ae0ec"

    // Staking contract details
    @JvmField var STAKING_CONTRACT: String = "secret10ea3ya578qnz02rmr7adhu2rq7g2qjg88ry2h5"
    @JvmField var STAKING_HASH: String = "62201f2b6e7d9d8c54621fff3dee39c33ad7074ae142b2ef4371dad6e0b386cb"

    // Airdrop contract details
    @JvmField var AIRDROP_CONTRACT: String = ""
    @JvmField var AIRDROP_HASH: String = ""

    // Backend base URL
    const val BACKEND_BASE_URL = "https://api.erth.network"

    // Network constants
    const val DEFAULT_LCD_URL = "https://lcd.erth.network"
}