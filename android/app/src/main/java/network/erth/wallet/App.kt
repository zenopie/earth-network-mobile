package network.erth.wallet

import android.app.Application
import network.erth.wallet.chain.Fees
import network.erth.wallet.wallet.AutoLock
import network.erth.wallet.wallet.SessionManager
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import kotlin.concurrent.thread

/**
 * Application entry point.
 *
 * Registers the full BouncyCastle JCE provider at the top of the provider list.
 * Android ships a stripped-down "BC" provider that lacks algorithms jMRTD needs
 * for the passport BAC/PACE handshake (3DES + ISO-9797 retail MAC), so we replace
 * it with the bundled `bcprov` before any crypto runs. Nothing else installs
 * it, and passport reading needs it.
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

        // The node the user chose (Settings -> Network), before anything queries the chain.
        network.erth.wallet.chain.NodeConfig.load(this)

        // Its full check (genesis, chain, LCD and RPC agreeing) again now, and
        // whenever the last pass is RECHECK_SECONDS old while the app runs: a
        // node re-initialised on another genesis, or one saved by a build
        // before the check, is not used on its say-so (NodeConfig.recheck).
        thread(isDaemon = true, name = "node-recheck") {
            var force = true
            while (true) {
                runCatching { network.erth.wallet.chain.NodeConfig.recheck(this, force) }
                force = false
                try { Thread.sleep(network.erth.wallet.chain.NodeConfig.RECHECK_TICK_SECONDS * 1000) } catch (e: InterruptedException) { return@thread }
            }
        }

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

        // The passport SRS, fetched once at launch rather than when a proof
        // needs it (the privacy SRS is in the APK).
        thread(isDaemon = true, name = "passport-srs") {
            runCatching { network.erth.wallet.passport.PassportSrs.prefetch(this) }
        }
    }
}
