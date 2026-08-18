package network.erth.wallet.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import network.erth.wallet.ui.theme.EarthAccent
import network.erth.wallet.ui.theme.EarthTheme
import network.erth.wallet.ui.vendor.component.EarthButton
import network.erth.wallet.ui.vendor.component.EarthButtonDefaults
import network.erth.wallet.ui.vendor.component.ShimmerRectangle
import network.erth.wallet.ui.vendor.component.rememberEarthShimmer
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.dimensions.EarthDimensions
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography
import com.valentinilk.shimmer.shimmer

/**
 * Earn: staking and its rewards, and nothing else.
 *
 * The daily ANML claim used to sit here too. It moved to the wallet screen's
 * action row, where it belongs: claiming ANML is a one-tap action on a balance,
 * not a position to manage, and putting it beside staking made this screen
 * answer two unrelated questions.
 *
 * There is no Zodl equivalent — Zcash has no staking — so this borrows the
 * shape of their address panel instead: a large-radius card carrying the
 * figures, with the actions beneath.
 *
 * Staked and claimable lead because the common question is how much rather
 * than with whom. Claim is disabled at zero rather than hidden: a button that
 * comes and goes as rewards accrue is harder to find than one that is always in
 * the same place, and its disabled state answers "is there anything to claim"
 * without being pressed.
 */
@Composable
fun EarnScreen(
    state: EarnUiState?,
    onStake: () -> Unit,
    onUnstake: () -> Unit,
    onClaim: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = EarthTheme.dimens
    val shape = RoundedCornerShape(EarthDimensions.Radius.radius3xl)
    val shimmer = rememberEarthShimmer()

    Column(
        modifier
            .fillMaxSize()
            .background(EarthColors.Surfaces.bgPrimary)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = dimens.gutter),
    ) {
        Spacer(Modifier.height(dimens.space16))

        Column(
            Modifier
                .fillMaxWidth()
                .background(EarthAccent.tint, shape)
                .padding(dimens.space16),
        ) {
            EarthLabel("Staked")
            AmountOrShimmer(state?.stakedUerth, shimmer, EarthColors.Text.textPrimary)
            Spacer(Modifier.height(dimens.space12))
            EarthLabel("Claimable rewards")
            AmountOrShimmer(state?.rewardsUerth, shimmer, EarthAccent.ink)
        }

        Spacer(Modifier.height(dimens.space16))
        EarthButton(
            text = "Claim rewards",
            onClick = onClaim,
            enabled = (state?.rewardsUerth ?: 0) > 0,
            modifier = Modifier.fillMaxWidth(),
            colors = brandButtonColors(),
        )
        Spacer(Modifier.height(dimens.space8))
        Row(Modifier.fillMaxWidth()) {
            EarthButton(
                text = "Stake",
                onClick = onStake,
                enabled = state != null,
                modifier = Modifier.weight(1f),
                colors = EarthButtonDefaults.secondaryColors(),
            )
            Spacer(Modifier.width(dimens.space12))
            EarthButton(
                text = "Unstake",
                onClick = onUnstake,
                enabled = !state?.delegations.isNullOrEmpty(),
                modifier = Modifier.weight(1f),
                colors = EarthButtonDefaults.secondaryColors(),
            )
        }

        if (!state?.delegations.isNullOrEmpty()) {
            Spacer(Modifier.height(dimens.space24))
            EarthLabel("Your validators")
            state.delegations.forEach { v ->
                EarthListRow(
                    initial = v.moniker.take(1).uppercase(),
                    name = v.moniker,
                    subtitle = "${"%.0f".format(v.commission * 100)}% commission",
                    value = formatUerth(v.amountUerth),
                    iconBg = EarthAccent.tint,
                    iconFg = EarthAccent.ink,
                )
            }
        }

        if (!state?.unbonding.isNullOrEmpty()) {
            Spacer(Modifier.height(dimens.space24))
            EarthLabel("Unbonding")
            // Unbonding stake is neither spendable nor earning, and it returns
            // on its own — so it is listed apart from the delegations rather
            // than mixed in, with the date rather than a commission.
            state.unbonding.forEach { u ->
                EarthListRow(
                    initial = u.moniker.take(1).uppercase(),
                    name = u.moniker,
                    subtitle = "Returns ${u.completesIn.take(10)}",
                    value = formatUerth(u.amountUerth),
                    iconBg = EarthColors.Surfaces.bgSecondary,
                    iconFg = EarthColors.Text.textTertiary,
                )
            }
        }
        Spacer(Modifier.height(dimens.space32))
    }
}

@Composable
private fun AmountOrShimmer(
    uerth: Long?,
    shimmer: com.valentinilk.shimmer.Shimmer,
    color: androidx.compose.ui.graphics.Color,
) {
    if (uerth == null) {
        Column(Modifier.shimmer(shimmer)) {
            ShimmerRectangle(width = 120.dp(), height = 26.dp())
        }
    } else {
        Text(
            text = "${formatUerth(uerth)} ERTH",
            style = EarthTypography.header5,
            color = color,
        )
    }
}

private fun Int.dp() = androidx.compose.ui.unit.Dp(toFloat())
