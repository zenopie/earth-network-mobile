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

    private val _state = MutableStateFlow(EMPTY)
    val state: StateFlow<WalletUiState> = _state.asStateFlow()

    private val _reachable = MutableStateFlow(true)
    val reachable: StateFlow<Boolean> = _reachable.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

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
                    _state.value = EMPTY
                    return@launch
                }
                _locked.value = false

                val loaded = withContext(Dispatchers.IO) {
                    val erth = runCatching {
                        Bank.balance(address, Constants.UERTH_DENOM).toLong()
                    }.getOrElse { _reachable.value = false; 0L }

                    val anml = runCatching {
                        Bank.balance(address, "uanml").toLong()
                    }.getOrDefault(0L)

                    val staked = runCatching {
                        Staking.delegations(address).sumOf { it.amount.toLongOrNull() ?: 0L }
                    }.getOrDefault(0L)

                    val registered = runCatching {
                        Personhood.isRegistered(address)
                    }.getOrDefault(false)

                    WalletUiState(
                        address = address,
                        balanceUerth = erth,
                        anmlBalance = if (anml > 0) formatSix(anml) else null,
                        stakedUerth = staked,
                        rewardsUerth = 0L,
                        registered = registered,
                    )
                }
                _state.value = loaded
            } finally {
                _loading.value = false
            }
        }
    }

    private companion object {
        val EMPTY = WalletUiState(
            address = "",
            balanceUerth = 0,
            anmlBalance = null,
            stakedUerth = 0,
            rewardsUerth = 0,
            registered = false,
        )

        fun formatSix(micro: Long): String {
            val whole = micro / 1_000_000
            val frac = (micro % 1_000_000).toString().padStart(6, '0').trimEnd('0')
            return if (frac.isEmpty()) "$whole" else "$whole.$frac"
        }
    }
}
