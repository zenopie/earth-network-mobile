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
import network.erth.wallet.chain.Dex

data class MarketsUiState(
    val pools: List<Dex.Pool>,
    val swapFeePercent: String,
)

/** The pools and the fee, which the swap quote needs anyway. */
class MarketsViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<MarketsUiState?>(null)
    val state: StateFlow<MarketsUiState?> = _state.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _state.value = withContext(Dispatchers.IO) {
                MarketsUiState(
                    pools = runCatching { Dex.pools() }.getOrDefault(emptyList()),
                    swapFeePercent = runCatching { Dex.swapFeePercent() }.getOrDefault("0"),
                )
            }
        }
    }
}
