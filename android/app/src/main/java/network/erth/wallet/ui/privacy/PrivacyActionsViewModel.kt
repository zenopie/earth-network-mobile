package network.erth.wallet.ui.privacy

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
import network.erth.earth.proto.allocation.StreamId
import network.erth.wallet.chain.Allocation
import network.erth.wallet.privacy.PrivacyChainReads
import network.erth.wallet.privacy.PrivacySession
import network.erth.wallet.privacy.chain.PrivacyQueries

/** One of this wallet's Groundworks positions and the key index that signs for it. */
/** A position of ours and its owner-tag counter (PrivacyKeys.otagSalt). */
data class PositionRow(val position: PrivacyChainReads.Position, val keyIndex: Int)

data class PrivacyActionsState(
    val positions: List<PositionRow>,
    val groundworksOptions: List<Allocation.OptionInfo>,
    val ballots: List<PrivacyQueries.RemovalBallot>,
    /** Spendable note counts per denom where a merge would help. */
    val mergeable: Map<String, Int>,
    /** The address bound as this person's referrer ("" for none) and when. */
    val referrerAddress: String,
    val referrerBoundAt: Long,
    val referrerLapseSeconds: Long,
)

/**
 * What the private Groundworks, removal-ballot, referrer and note screens
 * show. Every read is public and whole: all positions (ours found by key),
 * all open ballots, all options; nothing asked names this wallet.
 */
class PrivacyActionsViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow<PrivacyActionsState?>(null)
    val state: StateFlow<PrivacyActionsState?> = _state.asStateFlow()

    fun refresh(): Job = viewModelScope.launch {
        val ctx = getApplication<Application>()
        _state.value = withContext(Dispatchers.IO) {
            val w = runCatching { PrivacySession.wallet(ctx) }.getOrNull()
            PrivacyActionsState(
                positions = w?.let { runCatching { it.positions() }.getOrNull() }.orEmpty().map { (p, k) -> PositionRow(p, k) },
                groundworksOptions = runCatching { Allocation.stream(StreamId.STREAM_ID_GROUNDWORKS).options }.getOrDefault(emptyList()),
                ballots = runCatching { PrivacyQueries.removalBallots() }.getOrDefault(emptyList()),
                mergeable = w?.let { it.mergeable() + it.stakeMergeable() }.orEmpty(),
                referrerAddress = w?.store?.state?.referrerAddress.orEmpty(),
                referrerBoundAt = w?.store?.state?.referrerBoundAt ?: 0L,
                referrerLapseSeconds = runCatching { PrivacyQueries.personhoodParams().caretakerVoteSeconds }.getOrDefault(30L * 86_400),
            )
        }
    }

    fun clear() {
        _state.value = null
    }
}
