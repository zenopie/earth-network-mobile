package network.erth.wallet.ui.compose

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.erth.wallet.chain.Explorer
import network.erth.wallet.chain.Personhood
import network.erth.wallet.chain.Staking

/**
 * The chain, for the explore tab.
 *
 * recentBlocks is already suspend and fans out its own requests, so it is
 * called directly rather than wrapped again.
 */
class ExploreViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<ExploreUiState?>(null)
    val state: StateFlow<ExploreUiState?> = _state.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            val status = withContext(Dispatchers.IO) {
                runCatching { Explorer.status() }.getOrNull()
            }
            val blocks = runCatching { Explorer.recentBlocks(8) }.getOrDefault(emptyList())

            _state.value = withContext(Dispatchers.IO) {
                ExploreUiState(
                    chainId = status?.chainId ?: "unknown",
                    height = status?.height ?: blocks.firstOrNull()?.height ?: 0L,
                    registrations = runCatching { Personhood.registrationCount() }.getOrDefault(0L),
                    blocks = blocks,
                    validators = runCatching { Staking.bondedValidators() }
                        .getOrDefault(emptyList())
                        .map {
                            DelegationRow(
                                validatorOperator = it.operator,
                                moniker = it.moniker,
                                amountUerth = it.tokens.toLongOrNull() ?: 0L,
                                commission = it.commission,
                            )
                        },
                )
            }
        }
    }
}
