package network.erth.wallet.ui.govern

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import network.erth.earth.proto.allocation.StreamId
import network.erth.wallet.chain.Allocation
import network.erth.wallet.privacy.PrivacySession
import network.erth.wallet.chain.Assembly
import network.erth.wallet.chain.Gov
import network.erth.wallet.wallet.SecureWalletManager
import kotlin.math.floor

/** One stream's options and this wallet's split across them. */
data class StreamUiState(
    val options: List<Allocation.OptionInfo>,
    /** optionId to percent, as the chain holds it. */
    val mine: Map<Long, Long>,
    /** The stream's reward index, for what an option is owed (OptionInfo.owed). */
    val rewardIndex: String = "0",
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
        get() = actualShares.entries
            .filter { it.value > 0 }
            .mapNotNull { (id, pct) -> options.firstOrNull { it.id == id }?.let { AllocationSlice(it.name, pct, id, it.source) } }
            .sortedByDescending { it.percent }

    /** Each option's whole-percent share of the stream, by id. */
    val actualShares: Map<Long, Int>
        get() {
            val weights = options.map { it.amountAllocated.toDoubleOrNull() ?: 0.0 }
            val total = weights.sum()
            if (total <= 0) return options.associate { it.id to 0 }
            return options.map { it.id }.zip(apportion(weights, total)).toMap()
        }

    /** Live options the tally gives nothing: listed after the charted ones, so a new option can be found. */
    val unfundedSlices: List<AllocationSlice>
        get() {
            val shares = actualShares
            return options.filter { !it.removed && (shares[it.id] ?: 0) == 0 }.map { AllocationSlice(it.name, 0, it.id, it.source) }
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
            .mapNotNull { o -> mine[o.id]?.takeIf { it > 0 }?.let { AllocationSlice(o.name, it.toInt(), o.id, o.source) } }
            .sortedByDescending { it.percent }
}

data class AllocationUiState(
    val human: StreamUiState,
    val capital: StreamUiState,
    /** Chain proposals — the SDK's governance, not the streams. */
    val proposals: List<Gov.Proposal>,
    /**
     * The human house's tally on each proposal still open, by proposal id.
     *
     * Only the open ones: the chain purges a proposal's assembly votes when it
     * resolves, so a closed proposal would answer 0/0 and read as "nobody
     * voted" rather than "this is no longer recorded". What ended a closed
     * proposal is in its [Gov.Proposal.failedReason] instead.
     *
     * Missing means the tally could not be read.
     */
    val assemblyTallies: Map<Long, Assembly.Tally> = emptyMap(),
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

    /**
     * Re-read from the chain, and hand back the read so a caller can wait on
     * it.
     *
     * The [Job] is what pull-to-refresh needs: its spinner has to stay down
     * until the read it started has finished, and the read itself belongs to
     * [viewModelScope] so that leaving the screen mid-read does not cancel it.
     */
    fun refresh(): Job = loader.request()

    private val loader = network.erth.wallet.ui.Loader(viewModelScope) {
        val ctx = getApplication<Application>()
        withContext(Dispatchers.IO) {
            runCatching { SecureWalletManager.getWalletAddress(ctx) }.getOrNull()
        } ?: return@Loader

        val fresh = withContext(Dispatchers.IO) {
            val proposals = runCatching { Gov.proposals() }.getOrDefault(emptyList())
            AllocationUiState(
                // The caretaker split is cast anonymously, so the chain
                // cannot say which one is ours: the wallet remembers it.
                human = runCatching { Allocation.stream(StreamId.STREAM_ID_CARETAKER) }.getOrNull().let { s ->
                    StreamUiState(
                        options = s?.options.orEmpty(),
                        mine = runCatching { PrivacySession.wallet(ctx).store.state.caretakerSplit }.getOrDefault(emptyMap()),
                        rewardIndex = s?.rewardIndex ?: "0",
                    )
                },
                // Groundworks: the split this wallet's stake votes with.
                capital = runCatching { Allocation.stream(StreamId.STREAM_ID_GROUNDWORKS) }.getOrNull().let { s ->
                    StreamUiState(
                        options = s?.options.orEmpty(),
                        mine = runCatching { PrivacySession.wallet(ctx).groundworksSplit }.getOrDefault(emptyMap()),
                        rewardIndex = s?.rewardIndex ?: "0",
                    )
                },
                proposals = proposals,
                assemblyTallies = assemblyTallies(proposals),
            )
        }
        _state.value = keepIfEmptied(_state.value, fresh)
    }

    /**
     * [fresh], except where it came back empty over data already shown: a
     * stream always has its built-in options and the proposal list never
     * shrinks to nothing, so an empty answer is a read that failed (an edge
     * turning reads away after a tx), not the chain's state.
     */
    private fun keepIfEmptied(prev: AllocationUiState?, fresh: AllocationUiState): AllocationUiState {
        if (prev == null) return fresh
        fun stream(old: StreamUiState, new: StreamUiState) =
            if (new.options.isEmpty() && old.options.isNotEmpty()) old.copy(mine = new.mine) else new
        val keepProposals = fresh.proposals.isEmpty() && prev.proposals.isNotEmpty()
        return fresh.copy(
            human = stream(prev.human, fresh.human),
            capital = stream(prev.capital, fresh.capital),
            proposals = if (keepProposals) prev.proposals else fresh.proposals,
            assemblyTallies = if (keepProposals) prev.assemblyTallies else fresh.assemblyTallies,
        )
    }

    /**
     * The human tally for each open proposal — one request apiece.
     *
     * Restricted to the open ones because that is the only place the answer
     * exists, and because it bounds the cost: a refresh reads twenty proposals
     * and usually none of them are open, so this is normally zero extra
     * requests and never more than a handful.
     */
    private fun assemblyTallies(proposals: List<Gov.Proposal>): Map<Long, Assembly.Tally> =
        proposals.asSequence()
            .filter { it.isVoting }
            .mapNotNull { p -> runCatching { Assembly.tally(p.id) }.getOrNull()?.let { p.id to it } }
            .toMap()

    /**
     * Drop everything this holds about the current wallet.
     *
     * Called when the selected wallet changes. Without it the old wallet's
     * figures stay on screen until the new query returns — and a balance that
     * belongs to a different address is a worse answer than no balance at all,
     * because nothing about it looks wrong.
     */
    fun clear() {
        loader.cancel()
        _state.value = null
    }

}
