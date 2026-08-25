package network.erth.wallet.ui.compose

import androidx.compose.foundation.background
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
import network.erth.wallet.ui.theme.EarthTheme
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography

/**
 * Chain proposals.
 *
 * Its own screen rather than a section under the streams, because it is the
 * other kind of governance rather than more of the same: bonded stake decides
 * these, they run for a fixed period, and they change the chain itself instead
 * of steering an emission.
 */
@Composable
fun ProposalsScreen(
    proposals: List<Gov.Proposal>?,
    modifier: Modifier = Modifier,
    onOpen: ((Gov.Proposal) -> Unit)? = null,
) {
    val dimens = EarthTheme.dimens

    Column(
        modifier
            .fillMaxSize()
            .background(EarthColors.Surfaces.bgPrimary)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = dimens.gutter),
    ) {
        Spacer(Modifier.height(dimens.space8))
        Text(
            text = "Parameters, upgrades and spending from the community pool. " +
                "Voting power is bonded ERTH, so staking is what gives you a say " +
                "here — unlike the emission streams, where being a verified human " +
                "counts on its own.",
            style = EarthTypography.textSm,
            color = EarthColors.Text.textSecondary,
        )
        Spacer(Modifier.height(dimens.space16))
        // Voting lives on the proposal itself. Whether this wallet may vote is
        // answered there too, next to the buttons it governs, rather than as a
        // warning up here about buttons that are two taps away.
        ProposalList(proposals = proposals, onOpen = onOpen)
        Spacer(Modifier.height(dimens.space32))
    }
}
