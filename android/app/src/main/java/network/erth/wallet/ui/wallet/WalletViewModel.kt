package network.erth.wallet.ui.wallet

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.erth.wallet.Constants
import network.erth.wallet.chain.Bank
import network.erth.wallet.chain.Explorer
import network.erth.wallet.chain.SentTxLog
import network.erth.wallet.privacy.PrivacySession
import network.erth.wallet.privacy.sync.WalletSync
import network.erth.wallet.privacy.tx.ShieldMove
import network.erth.wallet.chain.Staking
import network.erth.wallet.ui.components.formatUerth
import network.erth.wallet.wallet.SecureWalletManager

/**
 * Wallet state, loaded from the chain.
 *
 * Every read is wrapped: a wallet that cannot reach its node should show zeroes
 * and stay usable, not fall over.
 */
class WalletViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<WalletUiState?>(null)
    val state: StateFlow<WalletUiState?> = _state.asStateFlow()

    /**
     * Transaction history, loaded separately from the balances.
     *
     * A separate flow because it is a separate failure: the history comes from
     * the transaction index, which a pruned or freshly-synced node may not
     * have, and a wallet that refuses to show a balance because it could not
     * list transactions is answering the wrong question.
     */
    private val _activity = MutableStateFlow<List<ActivityEntry>?>(null)
    val activity: StateFlow<List<ActivityEntry>?> = _activity.asStateFlow()

    /**
     * Re-read from the chain, and hand back the read so a caller can wait on
     * it.
     *
     * The [Job] is what pull-to-refresh needs: its spinner has to stay down
     * until the read it started has finished, and the read itself belongs to
     * [viewModelScope] so that leaving the screen mid-read does not cancel it.
     */
    fun refresh(): Job {
        return viewModelScope.launch {
            val ctx = getApplication<Application>()
            val address = withContext(Dispatchers.IO) {
                runCatching { SecureWalletManager.getWalletAddress(ctx) }.getOrNull()
            }
            // No unlocked session (a cold start): locked is a normal
            // state, not a failure.
            if (address.isNullOrBlank()) {
                _state.value = WalletUiState.EMPTY
                return@launch
            }

            val loaded = withContext(Dispatchers.IO) {
                // One balances call for every denom, rather than one call
                // per denom the app knows the name of: every token held
                // is in the response.
                val balances = runCatching { Bank.balances(address) }
                    .getOrDefault(emptyMap())
                val holdings = Tokens.holdings(balances)

                val erth = balances[Constants.UERTH_DENOM]?.toLongOrNull() ?: 0L
                val anml = balances["uanml"]?.toLongOrNull() ?: 0L

                val staked = runCatching {
                    Staking.delegations(address).sumOf { it.amount.toLongOrNull() ?: 0L }
                }.getOrDefault(0L)

                // The private side: notes and registration, from a full
                // sync of the indexer's streams (nothing asked about us).
                val privacy = runCatching { PrivacySession.wallet(ctx) }.getOrNull()
                // A sync error, else roots the chain has not vouched for
                // (no private tx is built on them), else a registration
                // whose leaf did not match: each is shown, none is hidden.
                val syncError = privacy?.let { w ->
                    runCatching { w.sync() }.exceptionOrNull()?.message
                        ?: w.store.state.rootsError?.takeIf { !w.store.state.rootsVerified }
                        ?: w.pendingRegistration?.failure
                }
                val shielded = privacy?.balances().orEmpty()

                val rewards = runCatching {
                    Staking.totalRewards(address).toLongOrNull() ?: 0L
                }.getOrDefault(0L)

                WalletUiState(
                    name = runCatching {
                        SecureWalletManager.getCurrentWalletName()
                    }.getOrDefault(""),
                    address = address,
                    balanceUerth = erth,
                    anmlBalance = (anml + (shielded["uanml"] ?: 0L)).takeIf { it > 0 }?.let(::formatUerth),
                    stakedUerth = staked,
                    rewardsUerth = rewards,
                    holdings = holdings,
                    registered = privacy?.let { runCatching { it.identityStatus() }.getOrNull() } == WalletSync.IdentityStatus.LIVE,
                    registeredBefore = privacy?.let { it.registeredBefore() && runCatching { it.identityStatus() }.getOrNull() != WalletSync.IdentityStatus.LIVE } == true,
                    // Null without a live registration; otherwise now, or
                    // the next UTC midnight the chain will take a claim.
                    anmlClaimableAt = privacy?.let { runCatching { it.claimOpensAt() }.getOrNull() },
                    shieldedErthUerth = shielded["uerth"] ?: 0L,
                    shielded = shielded,
                    unstaking = privacy?.pendingUnbonds.orEmpty(),
                    shieldedAddress = privacy?.address?.encode().orEmpty(),
                    privacySyncError = syncError,
                    unshieldableErthUerth = privacy?.let {
                        ShieldMove.maxUnshield(it.notes, runCatching { it.maxActions() }.getOrDefault(DEFAULT_MAX_ACTIONS))
                    } ?: 0L,
                )
            }
            _state.value = loaded

            _activity.value = withContext(Dispatchers.IO) {
                val ctx = getApplication<Application>()
                val public = runCatching {
                    Explorer.txsByHash(SentTxLog.hashes(ctx, address)).map { ActivityEntry.of(it, address) }
                }.getOrDefault(emptyList())
                // Private txs are never looked up (that would tell the node
                // which are ours): their rows come from the sealed store and
                // the notes the sync above found.
                val private = runCatching {
                    PrivacySession.wallet(ctx).activity().map { ActivityEntry.of(it) }
                }.getOrDefault(emptyList())
                mergeActivity(public, private)
            }
        }
    }

    /**
     * Drop everything this holds about the current wallet.
     *
     * Called when the selected wallet changes. Without it the old wallet's
     * figures stay on screen until the new query returns — and a balance that
     * belongs to a different address is a worse answer than no balance at all,
     * because nothing about it looks wrong.
     */
    fun clear() {
        _state.value = null
        _activity.value = null
    }

}

/** x/shielded's default max_actions_per_bundle, while the param cannot be read. */
private const val DEFAULT_MAX_ACTIONS = 16
