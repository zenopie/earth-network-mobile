package network.erth.wallet.ui.compose

import android.app.Application
import android.content.Context
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
import network.erth.wallet.chain.Gov
import network.erth.wallet.wallet.services.SecureWalletManager
import kotlin.math.floor

/** One stream's options and this wallet's split across them. */
data class StreamUiState(
    val options: List<Allocation.OptionInfo>,
    /** optionId to percent, as the chain holds it. */
    val mine: Map<Long, Long>,
) {
    /**
     * Where the stream actually goes, across every voter.
     *
     * Each option's amount_allocated is its share of the stream's total weight,
     * so this is the tally rather than anyone's preference. Percentages are
     * computed against the total rather than read off, because the chain stores
     * weights and not shares.
     */
    val actualSlices: List<AllocationSlice>
        get() {
            val weights = options.map { it.amountAllocated.toDoubleOrNull() ?: 0.0 }
            val total = weights.sum()
            if (total <= 0) return emptyList()
            val percents = apportion(weights, total)
            return options.asSequence()
                .zip(percents.asSequence())
                .mapNotNull { (o, pct) -> if (pct > 0) AllocationSlice(o.description, pct) else null }
                .sortedByDescending { it.percent }
                .toList()
        }

    /**
     * Whole percentages that sum to exactly 100.
     *
     * Truncating each share independently loses up to one point per option, and
     * did: a 95/5 split against a stake weight that divides as 95.000000000680
     * and 4.999999999320 truncated to 95 and 4, and the screen reported 99%.
     * The chain was paying 95.0000/5.0000 the whole time — the shortfall was
     * only ever in the arithmetic used to describe it.
     *
     * Largest remainder: floor everything, then hand the leftover points to the
     * shares with the biggest fractional parts. Rounding each share on its own
     * would fix this case and produce 101% in others.
     */
    private fun apportion(weights: List<Double>, total: Double): List<Int> {
        val exact = weights.map { it / total * 100.0 }
        val out = exact.map { floor(it).toInt() }.toMutableList()
        var leftover = 100 - out.sum()
        if (leftover <= 0) return out
        // Biggest fractional part first; ties go to the larger share.
        val order = exact.indices.sortedWith(
            compareByDescending<Int> { exact[it] - floor(exact[it]) }.thenByDescending { exact[it] },
        )
        for (i in order) {
            if (leftover == 0) break
            out[i]++
            leftover--
        }
        return out
    }

    val slices: List<AllocationSlice>
        get() = options
            .mapNotNull { o -> mine[o.id]?.takeIf { it > 0 }?.let { AllocationSlice(o.description, it.toInt()) } }
            .sortedByDescending { it.percent }
}

data class AllocationUiState(
    val human: StreamUiState,
    val capital: StreamUiState,
    /** Chain proposals — the SDK's governance, not the streams. */
    val proposals: List<Gov.Proposal>,
)

/**
 * The two allocation streams.
 *
 * Both are loaded together even though eligibility differs, because the screen
 * shows both either way — a stream you cannot vote in still tells you what is
 * being funded, and hiding it would make the second half of the chain's
 * tokenomics invisible to anyone who has not yet registered or staked.
 */
class AllocationViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<AllocationUiState?>(null)
    val state: StateFlow<AllocationUiState?> = _state.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val address = withContext(Dispatchers.IO) {
                runCatching { SecureWalletManager.getWalletAddress(ctx) }.getOrNull()
            } ?: return@launch

            _state.value = withContext(Dispatchers.IO) {
                AllocationUiState(
                    human = load(StreamId.STREAM_ID_CARETAKER, address),
                    capital = load(StreamId.STREAM_ID_GROUNDWORKS, address),
                    proposals = runCatching { Gov.proposals() }.getOrDefault(emptyList()),
                )
            }
        }
    }

    private fun load(stream: StreamId, address: String) = StreamUiState(
        options = runCatching { Allocation.stream(stream).options }.getOrDefault(emptyList()),
        mine = runCatching { Allocation.voterAllocations(stream, address) }
            .getOrDefault(emptyList())
            .toMap(),
    )

    /**
     * Replace a stream's split.
     *
     * The chain takes the whole split rather than a delta, so this sends every
     * weight including the ones that did not change. Zero-percent entries are
     * dropped: the chain treats an absent option as zero, and sending it
     * explicitly only makes the message bigger.
     */
    fun setAllocations(stream: StreamId, weights: Map<Long, Long>) = { ctx: Context ->
        val creator = SecureWalletManager.getWalletAddress(ctx).orEmpty()
        listOf(
            Allocation.msgSetAllocations(
                creator,
                stream,
                weights.filterValues { it > 0 }.map { (id, pct) -> id to pct },
            ),
        )
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
    }

}
