package network.erth.wallet.ui.compose

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/**
 * Scan a QR code, and hand back the address inside it.
 *
 * Wraps the ZXing activity the old app already depended on rather than building
 * a CameraX preview: this is a one-shot capture with no UI of its own to speak
 * of, and the permission prompt, the torch, the orientation handling and the
 * decode loop all come with it.
 *
 * Returns a launcher; call it to open the scanner.
 */
@Composable
fun rememberAddressScanner(onScanned: (String) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val raw = result.contents ?: return@rememberLauncherForActivityResult
        onScanned(raw.asEarthAddress())
    }

    val options = remember {
        ScanOptions()
            // QR only. Leaving the other formats on means a barcode on
            // whatever is behind the phone can decode first and fill the field
            // with a product number.
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt("Scan an Earth address")
            .setBeepEnabled(false)
            .setOrientationLocked(false)
    }

    return { launcher.launch(options) }
}

/**
 * The address out of whatever the code actually held.
 *
 * Wallets disagree about what to put in an address QR: some encode the bare
 * bech32 string, others wrap it in a URI with an amount and a memo hung off it.
 * Taking the first thing that looks like an Earth address handles both, and
 * leaves anything unrecognisable to the field's own validation rather than
 * silently swallowing it.
 */
internal fun String.asEarthAddress(): String {
    val trimmed = trim()
    val match = Regex("earth1[0-9a-z]{38,}").find(trimmed)
    return match?.value ?: trimmed
}
