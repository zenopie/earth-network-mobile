package network.erth.wallet

object Constants {
    // Contract Registry - the source of truth for all contract addresses
    const val REGISTRY_CONTRACT = "secret1ql943kl7fd7pyv9njf7rmngxhzljncgx6eyw5j"
    const val REGISTRY_HASH = "a14ffee004b6be0bbd9d52574bc809b4071e85847a30a4c2981ef10c4d3c6e1b"

    // Contract details - populated from registry on startup (no defaults, registry is required)
    @JvmField var REGISTRATION_CONTRACT: String = ""
    @JvmField var REGISTRATION_HASH: String = ""
    @JvmField var EXCHANGE_CONTRACT: String = ""
    @JvmField var EXCHANGE_HASH: String = ""
    @JvmField var STAKING_CONTRACT: String = ""
    @JvmField var STAKING_HASH: String = ""
    @JvmField var AIRDROP_CONTRACT: String = ""
    @JvmField var AIRDROP_HASH: String = ""

    // Backend base URL
    const val BACKEND_BASE_URL = "https://api.erth.network"

    // Network constants
    const val DEFAULT_LCD_URL = "https://lcd.erth.network"
}