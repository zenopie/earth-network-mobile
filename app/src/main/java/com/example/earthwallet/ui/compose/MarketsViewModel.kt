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
import network.erth.earth.proto.allocation.StreamId
import network.erth.wallet.chain.Allocation
import network.erth.wallet.chain.Dex

data class MarketsUiState(
    val pools: List<Dex.Pool>,
    val swapFeePercent: String,
    /**
     * The LP-rewards option's share of the capital stream, 0..1.
     *
     * Zero when voters have given it nothing, which is a real state — the
     * emission half of the APR is then genuinely zero rather than unknown.
     */
    val lpOptionShare: Double,
)

/** The pools, the fee the swap quote needs anyway, and what the LP option earns. */
class MarketsViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<MarketsUiState?>(null)
    val state: StateFlow<MarketsUiState?> = _state.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _state.value = withContext(Dispatchers.IO) {
                MarketsUiState(
                    pools = runCatching { Dex.pools() }.getOrDefault(emptyList()),
                    swapFeePercent = runCatching { Dex.swapFeePercent() }.getOrDefault("0"),
                    lpOptionShare = runCatching { lpOptionShare() }.getOrDefault(0.0),
                )
            }
        }
    }
}

/**
 * How much of the capital stream the LP-rewards option is voted.
 *
 * Matched on the handler rather than the description: the description is
 * governance-editable text, and matching it would detach the APR from its
 * source the first time someone renames the option.
 */
private fun lpOptionShare(): Double {
    val stream = Allocation.stream(StreamId.STREAM_ID_CAPITAL)
    val total = stream.totalWeight.toDoubleOrNull() ?: return 0.0
    if (total <= 0) return 0.0
    val lp = stream.options
        .filter { it.handler == "lp_rewards" }
        .sumOf { it.amountAllocated.toDoubleOrNull() ?: 0.0 }
    return (lp / total).coerceIn(0.0, 1.0)
}
