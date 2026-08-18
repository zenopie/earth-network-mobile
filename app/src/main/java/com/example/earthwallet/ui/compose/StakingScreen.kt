package network.erth.wallet.ui.compose

import androidx.compose.material3.CardDefaults
import network.erth.wallet.ui.vendor.component.EarthButton
import network.erth.wallet.ui.vendor.component.EarthButtonDefaults
import network.erth.wallet.ui.vendor.component.EarthCard
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import network.erth.wallet.ui.theme.EarthAccent
import network.erth.wallet.ui.theme.EarthTheme

/** One delegation, resolved for display. */
data class DelegationRow(
    val validatorOperator: String,
    val moniker: String,
    val amountUerth: Long,
    val commission: Double,
)

data class UnbondingRow(
    val moniker: String,
    val amountUerth: Long,
    val completesIn: String,
)

data class StakingUiState(
    val stakedUerth: Long,
    val rewardsUerth: Long,
    val delegations: List<DelegationRow>,
    val unbonding: List<UnbondingRow>,
)

/**
 * Staking.
 *
 * The two figures that matter are staked and claimable, so they lead; the
 * validator breakdown is below because the common question is "how much" rather
 * than "with whom".
 *
 * Claim is disabled at zero rather than hidden. A button that appears and
 * disappears as rewards accrue is harder to find than one that is always in the
 * same place, and its disabled state answers "is there anything to claim"
 * without the user pressing it.
 */
@Composable
fun StakingScreen(
    state: StakingUiState,
    onStake: () -> Unit,
    onUnstake: () -> Unit,
    onClaim: () -> Unit,
    modifier: Modifier = Modifier,
    claiming: Boolean = false,
) {
    val colors = EarthTheme.colors
    val dimens = EarthTheme.dimens

    EarthScaffold(title = "Earn", modifier = modifier) {
        EarthCard(colors = CardDefaults.cardColors(containerColor = EarthAccent.tint)) {
            Column {
                EarthLabel("Staked")
                Text(
                    text = formatErth(state.stakedUerth),
                    style = EarthTypography.header5,
                    color = EarthColors.Text.textPrimary,
                )
                Spacer(Modifier.height(dimens.space12))
                EarthLabel("Claimable rewards")
                Text(
                    text = formatErth(state.rewardsUerth),
                    style = EarthTypography.header5,
                    color = EarthAccent.ink,
                )
            }
        }

        Spacer(Modifier.height(dimens.space8))
        EarthButton(
            text = "Claim rewards",
            onClick = onClaim,
            enabled = state.rewardsUerth > 0,
            isLoading = claiming,
            colors = brandButtonColors(),
        )
        Row(Modifier.fillMaxWidth().padding(top = dimens.space8)) {
            Column(Modifier.weight(1f)) {
                EarthButton("Stake", onStake, colors = EarthButtonDefaults.secondaryColors())
            }
            Spacer(Modifier.width(dimens.space12))
            Column(Modifier.weight(1f)) {
                EarthButton(
                    text = "Unstake",
                    onClick = onUnstake,
                    colors = EarthButtonDefaults.secondaryColors(),
                    enabled = state.stakedUerth > 0,
                )
            }
        }

        if (state.delegations.isNotEmpty()) {
            Spacer(Modifier.height(dimens.space16))
            EarthLabel("Validators")
            state.delegations.forEach { d ->
                EarthListRow(
                    initial = d.moniker.take(1).uppercase(),
                    name = d.moniker,
                    subtitle = "${"%.0f".format(d.commission * 100)}% commission",
                    value = formatErth(d.amountUerth),
                    iconBg = EarthAccent.tint,
                    iconFg = EarthAccent.ink,
                )
            }
        }

        if (state.unbonding.isNotEmpty()) {
            Spacer(Modifier.height(dimens.space16))
            EarthLabel("Unbonding")
            state.unbonding.forEach { u ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = dimens.space8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = u.moniker,
                            style = EarthTypography.textMd,
                            color = EarthColors.Text.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        EarthStatusPill(EarthStatus.Neutral, u.completesIn)
                    }
                    Text(
                        text = formatErth(u.amountUerth),
                        style = EarthTypography.textMd,
                        color = EarthColors.Text.textSecondary,
                    )
                }
            }
        }

        if (state.stakedUerth == 0L && state.delegations.isEmpty()) {
            Spacer(Modifier.height(dimens.space16))
            Text(
                text = "Staking ERTH secures the chain and earns a share of the " +
                    "investor pillar — one ERTH per second, split by voting power.",
                style = EarthTypography.textMd,
                color = EarthColors.Text.textSecondary,
            )
        }
    }
}
