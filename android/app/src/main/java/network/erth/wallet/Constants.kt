package network.erth.wallet

object Constants {
    // Where the app connects: the chain (LCD, RPC); the backend
    // (api.erth.network) for the privacy indexer, the handle directory, the
    // registration gas grant and the passport circuits not bundled (each
    // pinned by sha256); Aztec's CRS host for the passport SRS (pinned by
    // sha256); and Google Play for updates and the install referrer.
    // Registration is proved on-device and verified on-chain.

    // --- earth chain (native Cosmos SDK chain; proof-of-personhood registration) ---
    // LCD/REST base. The node runs on Akash behind a Cloudflare Tunnel, which
    // terminates TLS at the edge, so this is plain HTTPS with no port. The
    // hostname is stable across redeploys — Akash reassigns external ports on
    // every new lease, which is exactly what the tunnel exists to hide.
    //
    // For a locally-served chain instead: "http://127.0.0.1:1317" with
    // `adb reverse tcp:1317 tcp:1317` on a USB device, or "http://10.0.2.2:1317"
    // on the emulator. Both are allowed by network_security_config.xml.
    const val EARTH_LCD_URL = "https://lcd.erth.network"

    // CometBFT RPC base. Only the explorer uses it, and only for the one thing
    // the LCD cannot do: fetch a *range* of blocks in a single request
    // (`/blockchain?minHeight=&maxHeight=`). Everything else goes through the
    // LCD, and the explorer falls back to the LCD if this is unreachable — a
    // deployment that exposes only the REST port stays fully functional.
    // The tunnel fronts the RPC on 443, so it is reached over HTTPS and
    // network_security_config.xml's cleartext ban holds. Empty disables it:
    // the explorer then reads blocks one at a time from the LCD.
    //
    // For a locally-served chain: "http://127.0.0.1:26657" with
    // `adb reverse tcp:26657 tcp:26657`, or "http://10.0.2.2:26657".
    const val EARTH_RPC_URL = "https://rpc.erth.network"

    // The backend: the privacy indexer and handle directory (checked against
    // the chain before use), the registration gas grant and the fetched
    // passport circuits.
    const val EARTH_API_URL = "https://api.erth.network"

    const val EARTH_CHAIN_ID = "earth-1"
    const val EARTH_PREFIX = "earth"
    const val UERTH_DENOM = "uerth"
}