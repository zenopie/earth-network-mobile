package network.erth.wallet

import android.app.Application
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

/**
 * Application entry point.
 *
 * Registers the full BouncyCastle JCE provider at the top of the provider list.
 * Android ships a stripped-down "BC" provider that lacks algorithms jMRTD needs
 * for the passport BAC/PACE handshake (3DES + ISO-9797 retail MAC), so we replace
 * it with the bundled `bcprov` before any crypto runs. (This was previously done
 * transitively by a since-removed dependency; passport reading needs it.)
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        } catch (e: Throwable) {
            // Best-effort: fall back to the platform providers if replacement fails.
        }
    }
}
