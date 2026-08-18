package network.erth.wallet.ui.compose

import network.erth.wallet.ui.vendor.component.EarthButton
import network.erth.wallet.ui.vendor.component.EarthButtonDefaults
import network.erth.wallet.ui.vendor.component.EarthCard
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.erth.wallet.Constants
import network.erth.wallet.chain.Bank
import network.erth.wallet.chain.Explorer
import network.erth.wallet.chain.Personhood
import network.erth.wallet.chain.Staking
import network.erth.wallet.wallet.services.SecureWalletManager

/**
 * Wallet state, loaded from the chain.
 *
 * Every read is wrapped: a wallet that cannot reach its node should show zeroes
 * and stay usable, not fall over. The old fragments each decided this for
 * themselves and disagreed — some showed "Error", some showed nothing, one
 * showed a balance of zero that was indistinguishable from a real zero. Here
 * [reachable] carries that distinction explicitly.
 */
class WalletViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<WalletUiState?>(null)
    val state: StateFlow<WalletUiState?> = _state.asStateFlow()

    private val _reachable = MutableStateFlow(true)
    val reachable: StateFlow<Boolean> = _reachable.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /**
     * Transaction history, loaded separately from the balances.
     *
     * A separate flow because it is a separate failure: the history comes from
     * the transaction index, which a pruned or freshly-synced node may not
     * have, and a wallet that refuses to show a balance because it could not
     * list transactions is answering the wrong question.
     */
    private val _activity = MutableStateFlow<List<ActivityRow>?>(null)
    val activity: StateFlow<List<ActivityRow>?> = _activity.asStateFlow()

    /**
     * True when there is no unlocked wallet session.
     *
     * SecureWalletManager.getWalletAddress throws rather than returning null in
     * that case — the mnemonic lives behind a PIN session, and a cold start has
     * none. Treating it as an error would be wrong: locked is a normal state,
     * not a failure, and the difference matters because one wants a PIN prompt
     * and the other wants a retry.
     */
    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            try {
                val ctx = getApplication<Application>()
                val address = withContext(Dispatchers.IO) {
                    runCatching { SecureWalletManager.getWalletAddress(ctx) }.getOrNull()
                }
                if (address.isNullOrBlank()) {
                    _locked.value = true
                    _state.value = WalletUiState.EMPTY
                    return@launch
                }
                _locked.value = false

                val loaded = withContext(Dispatchers.IO) {
                    // One balances call for every denom, rather than one call
                    // per denom the app happens to know the name of. The extra
                    // tokens were always in the response; nothing was reading
                    // past the two it asked for.
                    val balances = runCatching { Bank.balances(address) }
                        .getOrElse { _reachable.value = false; emptyMap() }
                    val holdings = Tokens.holdings(balances)

                    val erth = balances[Constants.UERTH_DENOM]?.toLongOrNull() ?: 0L
                    val anml = balances["uanml"]?.toLongOrNull() ?: 0L

                    val staked = runCatching {
                        Staking.delegations(address).sumOf { it.amount.toLongOrNull() ?: 0L }
                    }.getOrDefault(0L)

                    // registrationStatus rather than isRegistered: the same
                    // request also carries the last claim time, and the claim
                    // button needs both.
                    val status = runCatching {
                        Personhood.registrationStatus(address)
                    }.getOrNull()

                    val rewards = runCatching {
                        Staking.totalRewards(address).toLongOrNull() ?: 0L
                    }.getOrDefault(0L)

                    WalletUiState(
                        name = runCatching {
                            SecureWalletManager.getCurrentWalletName(ctx)
                        }.getOrDefault(""),
                        address = address,
                        balanceUerth = erth,
                        anmlBalance = if (anml > 0) formatSix(anml) else null,
                        stakedUerth = staked,
                        rewardsUerth = rewards,
                        holdings = holdings,
                        registered = status?.registered == true,
                        // Null when there is nothing to claim against at all.
                        // Folding "not registered" into "claimable now" made
                        // the button fire a claim the chain rejects.
                        anmlClaimableAt = when {
                            status == null || !status.registered -> null
                            Personhood.isAnmlClaimable(status) -> 0L
                            // Next UTC midnight, not 24 hours from the claim.
                            // Deriving it from the last claim time was only
                            // ever right because the chain stored a truncated
                            // midnight there.
                            else -> Personhood.nextClaimOpensAt()
                        },
                    )
                }
                _state.value = loaded

                _activity.value = withContext(Dispatchers.IO) {
                    runCatching {
                        Explorer.txsForAddress(address).map { it.toActivityRow(address) }
                    }.getOrDefault(emptyList())
                }
            } finally {
                _loading.value = false
            }
        }
    }

    private companion object {

        fun formatSix(micro: Long): String {
            val whole = micro / 1_000_000
            val frac = (micro % 1_000_000).toString().padStart(6, '0').trimEnd('0')
            return if (frac.isEmpty()) "$whole" else "$whole.$frac"
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
