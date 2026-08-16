package network.erth.wallet

object Constants {
    // Backend base URL (passport proof verification + app update metadata)
    const val BACKEND_BASE_URL = "https://api.erth.network"

    // --- earth chain (native Cosmos SDK chain; proof-of-personhood registration) ---
    // LCD/REST base. The deployed node fronts this on 443 via traefik, so it is
    // plain HTTPS with no port.
    //
    // For a locally-served chain instead: "http://127.0.0.1:1317" with
    // `adb reverse tcp:1317 tcp:1317` on a USB device, or "http://10.0.2.2:1317"
    // on the emulator. Both are allowed by network_security_config.xml.
    const val EARTH_LCD_URL = "https://aqua-ant.vm.scrtlabs.com"

    // CometBFT RPC base. Only the explorer uses it, and only for the one thing
    // the LCD cannot do: fetch a *range* of blocks in a single request
    // (`/blockchain?minHeight=&maxHeight=`). Everything else goes through the
    // LCD, and the explorer falls back to the LCD if this is unreachable — a
    // deployment that exposes only the REST port stays fully functional.
    // Left empty against the deployed node: traefik only fronts 443, so the RPC
    // is reachable over cleartext HTTP alone, which this app forbids
    // (network_security_config.xml). The explorer already falls back to the LCD
    // when the RPC is unreachable, so the only cost is slower block-range reads.
    //
    // For a locally-served chain: "http://127.0.0.1:26657" with
    // `adb reverse tcp:26657 tcp:26657`, or "http://10.0.2.2:26657".
    const val EARTH_RPC_URL = ""

    const val EARTH_CHAIN_ID = "earth"
    const val EARTH_PREFIX = "earth"
    const val EARTH_COIN_TYPE = 118
    const val UERTH_DENOM = "uerth"
    const val MSG_REGISTER_TYPE_URL = "/earth.personhood.v1.MsgRegister"
}