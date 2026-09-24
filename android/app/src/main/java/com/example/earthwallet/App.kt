package network.erth.wallet

import android.app.Application
import network.erth.wallet.chain.Fees
import network.erth.wallet.wallet.services.AutoLock
import network.erth.wallet.wallet.services.SessionManager
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import kotlin.concurrent.thread

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

        AutoLock.install(this)

        // Before anything else can read it: earlier builds left the unlock
        // secret's bare SHA-256 on disk, which for a PIN is the PIN.
        runCatching { SessionManager.purgeLegacyPinHash(this) }

        // Learn the node's minimum gas price before any screen needs to quote a
        // fee. Fees.forGas must never block — it is read from composables — so
        // it answers from cache or a fallback, and this is what fills the cache.
        // On a background thread and best-effort: a node that cannot be reached
        // at launch must not stop the app from starting, and the fallback is
        // the right answer on this chain anyway.
        thread(isDaemon = true, name = "fees-prime") {
            runCatching { Fees.prime() }
        }
    }
}
