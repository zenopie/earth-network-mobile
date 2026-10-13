package network.erth.wallet.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.erth.wallet.privacy.PrivacySession
import network.erth.wallet.privacy.chain.PrivacyQueries
import network.erth.wallet.privacy.chain.RestPrivateChain
import network.erth.wallet.ui.earn.EarnViewModel
import network.erth.wallet.ui.govern.AllocationViewModel
import network.erth.wallet.ui.navigation.EarthRoute
import network.erth.wallet.ui.privacy.PrivacyActionsViewModel
import network.erth.wallet.ui.swap.MarketsViewModel
import network.erth.wallet.ui.wallet.WalletViewModel

/**
 * What re-reads what, in one place.
 *
 * Screens used to refresh from their own tx callbacks, each naming the
 * view models it remembered to, right after the tx committed. That is
 * before the indexer serves the tx's notes, so the one sync they got saw the
 * spent notes gone and the change, the swap's output or the new stake not
 * yet back. After a tx this waits for the wallet to see the tx's block,
 * then re-reads everything already on screen.
 *
 * [walletEpoch] is the app's wallet-switch counter: a re-read that began for
 * one wallet is not finished for another (the switch reloads by itself).
 */
class AppRefresh(
    private val context: Context,
    private val scope: CoroutineScope,
    private val walletEpoch: () -> Int,
    private val wallet: WalletViewModel,
    private val earn: EarnViewModel,
    private val allocation: AllocationViewModel,
    private val markets: MarketsViewModel,
    private val privacy: PrivacyActionsViewModel,
) {
    /**
     * Bumped after each post-tx re-read, for reads a screen keeps itself
     * (an identity's move offer) to key off.
     */
    var version by mutableIntStateOf(0)
        private set

    /**
     * What [tab] shows. The wallet's read is in every one: each tab shows
     * a balance or stake from it, and it carries the privacy sync.
     */
    fun tab(tab: EarthRoute.Tab): List<Job> = buildList {
        add(wallet.refresh())
        when (tab) {
            // The portfolio values private stake at the live rates and lists
            // unbonding, both from the earn read.
            EarthRoute.Wallet -> { add(earn.refresh()); add(privacy.refreshPersonal()) }
            // Earn shows pools now, so it needs the market data too.
            EarthRoute.Earn -> { add(earn.refresh()); add(markets.refresh()); add(privacy.refresh()) }
            EarthRoute.Swap -> add(markets.refresh())
            EarthRoute.Govern -> { add(allocation.refresh()); add(privacy.refresh()) }
        }
    }

    /**
     * Everything already loaded, again. What has not been shown yet loads
     * when it is: four tabs' queries for screens nobody opened is the slow
     * launch the per-tab loading avoids.
     */
    fun loaded(): List<Job> = buildList {
        add(wallet.refresh())
        if (earn.state.value != null) add(earn.refresh())
        if (markets.state.value != null) add(markets.refresh())
        if (allocation.state.value != null) add(allocation.refresh())
        when {
            privacy.state.value != null -> add(privacy.refresh())
            privacy.personal.value != null -> add(privacy.refreshPersonal())
        }
    }

    /**
     * After a send ([TxController.settled]): find its block (waiting for one
     * still unconfirmed), sync until the wallet has seen it, then re-read.
     * The handle directory is dropped too: a tx may have bound, renewed or
     * moved a handle, and its cache would show the old entry for minutes.
     */
    fun afterTx(hash: String?) {
        val epoch = walletEpoch()
        scope.launch {
            withContext(Dispatchers.IO) {
                PrivacyQueries.handles.invalidate()
                val height = hash?.let(::committedHeight)
                if (height != null && epoch == walletEpoch()) {
                    runCatching { PrivacySession.wallet(context).catchUp(height) }
                }
            }
            if (epoch != walletEpoch()) return@launch
            loaded().joinAll()
            version++
        }
    }

    /** [hash]'s block height, polling while it is unconfirmed; null if it never shows. */
    private fun committedHeight(hash: String): Long? {
        repeat(COMMIT_POLLS) { i ->
            runCatching { RestPrivateChain.tx(hash) }.getOrNull()?.let { return it.height }
            if (i < COMMIT_POLLS - 1) Thread.sleep(COMMIT_POLL_MS)
        }
        return null
    }

    private companion object {
        // Three minutes: past it the tx is either gone from the mempool or
        // something a pull will pick up.
        const val COMMIT_POLLS = 60
        const val COMMIT_POLL_MS = 3_000L
    }
}
