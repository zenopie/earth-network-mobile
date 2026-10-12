package network.erth.wallet.ui.govern

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import network.erth.wallet.chain.Gov
import network.erth.wallet.ui.designsystem.theme.colors.EarthColors
import network.erth.wallet.ui.designsystem.theme.typography.EarthTypography
import network.erth.wallet.ui.theme.EarthTheme

/**
 * Chain proposals, with any open removal ballots above them (rare).
 *
 * Its own screen rather than a section under the streams, because it is the
 * other kind of governance rather than more of the same: bonded stake and the
 * human chamber decide these together, they run for a fixed period, and they
 * change the chain itself instead of steering an emission.
 */
@Composable
fun ProposalsScreen(
    proposals: List<Gov.Proposal>?,
    modifier: Modifier = Modifier,
    onOpen: ((Gov.Proposal) -> Unit)? = null,
    /** Open removal ballots: rare, so their section shows only when there is one. */
    ballots: List<OpenBallot> = emptyList(),
    /** Votes in an open ballot; null when this wallet may not (not a registered person). */
    onVoteBallot: ((optionId: Long, yes: Boolean) -> Unit)? = null,
    /** Opens the list of options a ballot can be proposed against; null when this wallet may not. */
    onProposeRemoval: (() -> Unit)? = null,
) {
    val dimens = EarthTheme.dimens

    Column(
        modifier
            .fillMaxSize()
            .background(EarthColors.Surfaces.bgPrimary)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = dimens.gutter),
    ) {
        Spacer(Modifier.height(dimens.space16))
        ballots.forEach { b ->
            BallotRow(b, onVoteBallot?.let { vote -> { yes: Boolean -> vote(b.optionId, yes) } })
            Spacer(Modifier.height(dimens.space8))
        }
        if (ballots.isNotEmpty()) Spacer(Modifier.height(dimens.space8))
        // Voting lives on the proposal itself. Whether this wallet may vote is
        // answered there too, next to the buttons it governs, rather than as a
        // warning up here about buttons that are two taps away.
        ProposalList(proposals = proposals, onOpen = onOpen)
        if (onProposeRemoval != null) {
            Spacer(Modifier.height(dimens.space16))
            Text(
                "Propose removing a Groundworks option",
                style = EarthTypography.textSm,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                color = network.erth.wallet.ui.theme.EarthAccent.ink,
                modifier = Modifier.clickable(onClick = onProposeRemoval).padding(vertical = dimens.space8),
            )
        }
        Spacer(Modifier.height(dimens.space32))
    }
}
