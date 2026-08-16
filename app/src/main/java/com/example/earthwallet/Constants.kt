package network.erth.wallet

object Constants {
    // Backend base URL (passport proof verification + app update metadata)
    const val BACKEND_BASE_URL = "https://api.erth.network"

    // --- earth chain (native Cosmos SDK chain; proof-of-personhood registration) ---
    // LCD/REST base. For a USB device run `adb reverse tcp:1317 tcp:1317` so the
    // phone's localhost reaches the Mac running `ignite chain serve` (LCD on :1317).
    // For the Android emulator use "http://10.0.2.2:1317" instead.
    const val EARTH_LCD_URL = "http://127.0.0.1:1317"

    // CometBFT RPC base. Only the explorer uses it, and only for the one thing
    // the LCD cannot do: fetch a *range* of blocks in a single request
    // (`/blockchain?minHeight=&maxHeight=`). Everything else goes through the
    // LCD, and the explorer falls back to the LCD if this is unreachable — a
    // deployment that exposes only the REST port stays fully functional.
    // Same host notes as above: :26657 for `adb reverse`, 10.0.2.2 on the emulator.
    const val EARTH_RPC_URL = "http://127.0.0.1:26657"

    const val EARTH_CHAIN_ID = "earth"
    const val EARTH_PREFIX = "earth"
    const val EARTH_COIN_TYPE = 118
    const val UERTH_DENOM = "uerth"
    const val MSG_REGISTER_TYPE_URL = "/earth.personhood.v1.MsgRegister"
}