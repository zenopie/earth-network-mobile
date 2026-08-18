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
import network.erth.wallet.ui.theme.EarthTheme
import network.erth.wallet.ui.vendor.component.EarthButton
import network.erth.wallet.ui.vendor.component.EarthButtonDefaults
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.dimensions.EarthDimensions
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography

/**
 * Earn: staking and the allocation streams.
 *
 * There is no Zodl equivalent — Zcash has no staking — so this borrows the
 * shape of their address panel instead: a large-radius card carrying the
 * figures, with the actions beneath.
 *
 * Staked and claimable lead because the common question is how much rather than
 * with whom. Claim is disabled at zero rather than hidden: a button that comes
 * and goes as rewards accrue is harder to find than one that is always in the
 * same place, and its disabled state answers "is there anything to claim"
 * without being pressed.
 */
@Composable
fun EarnScreen(
    stakedErth: String,
    rewardsErth: String,
    hasRewards: Boolean,
    validators: List<DelegationRow>,
    onStake: () -> Unit,
    onUnstake: () -> Unit,
    onClaim: () -> Unit,
    modifier: Modifier = Modifier,
    claiming: Boolean = false,
    scrollable: Boolean = true,
) {
    val dimens = EarthTheme.dimens
    val shape = RoundedCornerShape(EarthDimensions.Radius.radius3xl)

    Column(
        modifier
            .fillMaxSize()
            .background(EarthColors.Surfaces.bgPrimary)
            .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            .padding(horizontal = dimens.gutter),
    ) {
        Text(
            text = "Earn",
            style = EarthTypography.header5.copy(color = EarthColors.Text.textPrimary),
            modifier = Modifier.padding(vertical = dimens.space16),
        )

        Column(
            Modifier
                .fillMaxWidth()
                .background(EarthTheme.domain.stakingBg, shape)
                .padding(dimens.space16),
        ) {
            EarthLabel("Staked")
            Text(
                text = stakedErth,
                style = EarthTypography.header5.copy(color = EarthColors.Text.textPrimary),
            )
            Spacer(Modifier.height(dimens.space12))
            EarthLabel("Claimable rewards")
            Text(
                text = rewardsErth,
                style = EarthTypography.header5.copy(color = EarthTheme.domain.stakingFg),
            )
        }

        Spacer(Modifier.height(dimens.space16))
        EarthButton(
            text = "Claim rewards",
            onClick = onClaim,
            enabled = hasRewards,
            isLoading = claiming,
            modifier = Modifier.fillMaxWidth(),
            colors = brandButtonColors(),
        )
        Spacer(Modifier.height(dimens.space8))
        Row(Modifier.fillMaxWidth()) {
            EarthButton(
                text = "Stake",
                onClick = onStake,
                modifier = Modifier.weight(1f),
                colors = EarthButtonDefaults.secondaryColors(),
            )
            Spacer(Modifier.width(dimens.space12))
            EarthButton(
                text = "Unstake",
                onClick = onUnstake,
                modifier = Modifier.weight(1f),
                colors = EarthButtonDefaults.secondaryColors(),
            )
        }

        if (validators.isNotEmpty()) {
            Spacer(Modifier.height(dimens.space24))
            EarthLabel("Validators")
            validators.forEach { v ->
                EarthListRow(
                    initial = v.moniker.take(1).uppercase(),
                    name = v.moniker,
                    subtitle = "${"%.0f".format(v.commission * 100)}% commission",
                    value = "%,d".format(v.amountUerth / 1_000_000),
                    iconBg = EarthTheme.domain.stakingBg,
                    iconFg = EarthTheme.domain.stakingFg,
                )
            }
        }
        Spacer(Modifier.height(dimens.space32))
    }
}
