package network.erth.wallet.ui.compose

import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import network.erth.wallet.ui.vendor.component.EarthHorizontalDivider
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import network.erth.wallet.chain.Dex

/**
 * Earn: the two ways to put capital to work — staking, and pools.
 *
 * Pools used to be their own route, reached from a Liquidity button on the swap
 * tab, on the argument that providing liquidity is adjacent to swapping. The
 * question people actually arrive with is "where do I earn on what I hold",
 * which has one answer rather than two places to look — so both live here now,
 * one selector apart. iOS is laid out the same way.
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
    // --- the liquidity half ---
    pools: List<Dex.Pool>? = null,
    swapFeePercent: String? = null,
    lpOptionShare: Double = 0.0,
    lpUnbondings: List<Dex.Unbonding> = emptyList(),
    lpShares: Map<Long, Long> = emptyMap(),
    onAddLiquidity: (Dex.Pool) -> Unit = {},
    onRemoveLiquidity: (Dex.Pool) -> Unit = {},
) {
    val dimens = EarthTheme.dimens
    val shape = RoundedCornerShape(EarthDimensions.Radius.radius3xl)
    val shimmer = rememberEarthShimmer()
    var showPools by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier
            .fillMaxSize()
            .background(EarthColors.Surfaces.bgPrimary)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = dimens.gutter),
    ) {
        Spacer(Modifier.height(dimens.space16))

        EarthSegmented(
            options = listOf("Stake", "Liquidity"),
            selectedIndex = if (showPools) 1 else 0,
            onSelect = { showPools = it == 1 },
        )
        Spacer(Modifier.height(dimens.space16))

        if (showPools) {
            PoolList(
                pools = pools,
                swapFeePercent = swapFeePercent,
                lpOptionShare = lpOptionShare,
                unbondings = lpUnbondings,
                shares = lpShares,
                onAdd = onAddLiquidity,
                onRemove = onRemoveLiquidity,
            )
            Spacer(Modifier.height(dimens.space32))
            return@Column
        }

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

            val apr = state?.let { StakingApr.base(it.totalBondedUerth) }
            if (apr != null) {
                Spacer(Modifier.height(dimens.space12))
                EarthHorizontalDivider()
                Spacer(Modifier.height(dimens.space12))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Estimated APR",
                            style = EarthTypography.textSm,
                            color = EarthColors.Text.textSecondary,
                        )
                        Text(
                            // The single most useful thing to say about this
                            // number: it is not a policy the chain is aiming
                            // at, it is a fixed stream divided by however much
                            // stake is competing for it.
                            text = "1 ERTH/sec across " +
                                "${formatUerth(state.totalBondedUerth)} ERTH staked",
                            style = EarthTypography.textXs,
                            color = EarthColors.Text.textTertiary,
                        )
                    }
                    Text(
                        text = apr.asRate(),
                        style = EarthTypography.textMd,
                        fontWeight = FontWeight.SemiBold,
                        color = EarthAccent.ink,
                    )
                }
            }
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
                // Commission and the rate it leaves, together: the commission
                // alone is only half the comparison anyone is making between
                // validators.
                val net = StakingApr.forValidator(state.totalBondedUerth, v.commission)
                EarthListRow(
                    initial = v.moniker.take(1).uppercase(),
                    name = v.moniker,
                    subtitle = if (net != null) {
                        "${"%.0f".format(v.commission * 100)}% commission · ${net.asRate()} APR"
                    } else {
                        "${"%.0f".format(v.commission * 100)}% commission"
                    },
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

/**
 * A rate at whatever magnitude it lands.
 *
 * On a young chain with little bonded this runs to millions of percent, which
 * is arithmetically right and worth showing rather than capping — a capped
 * number invites the reader to believe the cap.
 */
private fun Double.asRate(): String {
    val pct = this * 100
    return when {
        pct == 0.0 -> "0%"
        pct < 0.01 -> "<0.01%"
        pct < 1 -> "%.2f%%".format(pct)
        pct < 1_000 -> "%.1f%%".format(pct)
        else -> "%,.0f%%".format(pct)
    }
}
