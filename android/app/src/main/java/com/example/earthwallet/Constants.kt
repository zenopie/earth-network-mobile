package network.erth.wallet

object Constants {
    // No backend. Registration is proved on-device and verified on-chain, and
    // updates go through Play's in-app update API (UpdateCheckActivity), so the
    // app talks to the chain and to Google and to nothing else.

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
    // Now set: the tunnel fronts the RPC on 443 too, so it is reachable over
    // HTTPS and no longer trips network_security_config.xml's cleartext ban.
    // This was empty against the old node, where only 443 was fronted and the
    // RPC was cleartext-only, costing the explorer its block-range reads.
    //
    // For a locally-served chain: "http://127.0.0.1:26657" with
    // `adb reverse tcp:26657 tcp:26657`, or "http://10.0.2.2:26657".
    const val EARTH_RPC_URL = "https://rpc.erth.network"

    const val EARTH_CHAIN_ID = "earth-1"
    const val EARTH_PREFIX = "earth"
    const val EARTH_COIN_TYPE = 118
    const val UERTH_DENOM = "uerth"
    const val MSG_REGISTER_TYPE_URL = "/earth.personhood.v1.MsgRegister"
}