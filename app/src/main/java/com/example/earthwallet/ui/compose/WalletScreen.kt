package network.erth.wallet.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import network.erth.wallet.ui.theme.EarthTheme
import network.erth.wallet.ui.vendor.component.EarthButton
import network.erth.wallet.ui.vendor.component.EarthButtonDefaults
import network.erth.wallet.ui.vendor.component.EarthHorizontalDivider
import androidx.compose.material3.Text
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography

/** What the wallet screen shows. Held by the caller; this composable is pure. */
data class WalletUiState(
    val address: String,
    val balanceUerth: Long,
    val anmlBalance: String?,
    val stakedUerth: Long,
    val rewardsUerth: Long,
    val registered: Boolean,
)

/**
 * The balance screen, built on the vendored components.
 *
 * One number, two actions, then holdings. A wallet is opened to answer "how
 * much do I have" far more often than anything else, so that is answered before
 * anything competes with it.
 */
@Composable
fun WalletScreen(
    state: WalletUiState,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onStakingClick: () -> Unit = {},
    onAnmlClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
) {
    val dimens = EarthTheme.dimens

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(EarthColors.Surfaces.bgPrimary)
            .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = dimens.gutter)
            .padding(top = dimens.space24, bottom = dimens.space32),
    ) {
        Text(
            text = "TOTAL BALANCE",
            style = EarthTypography.textSm.copy(color = EarthColors.Text.textTertiary),
        )
        Text(
            text = formatAmount(state.balanceUerth),
            style = EarthTypography.header1.copy(color = EarthColors.Text.textPrimary),
        )
        Text(
            text = state.address,
            style = EarthTypography.textSm.copy(color = EarthColors.Text.textTertiary),
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
        )

        Spacer(Modifier.height(dimens.space24))
        EarthButton(text = "Send", onClick = onSend, modifier = Modifier.fillMaxWidth(),
            colors = brandButtonColors(),
        )
        Spacer(Modifier.height(dimens.space8))
        EarthButton(
            text = "Receive",
            onClick = onReceive,
            modifier = Modifier.fillMaxWidth(),
            colors = EarthButtonDefaults.secondaryColors(),
        )

        Spacer(Modifier.height(dimens.space24))
        Text(
            text = "HOLDINGS",
            style = EarthTypography.textSm.copy(color = EarthColors.Text.textTertiary),
        )
        Spacer(Modifier.height(dimens.space8))

        if (state.anmlBalance != null) {
            HoldingRow("ANML", if (state.registered) "Proof of personhood" else "Not registered",
                state.anmlBalance, EarthTheme.domain.anmlBg, EarthTheme.domain.anmlFg, onAnmlClick)
            EarthHorizontalDivider()
        }
        if (state.stakedUerth > 0) {
            HoldingRow("Staked", "Delegated", formatAmount(state.stakedUerth),
                EarthTheme.domain.stakingBg, EarthTheme.domain.stakingFg, onStakingClick)
            EarthHorizontalDivider()
        }
        if (state.rewardsUerth > 0) {
            HoldingRow("Rewards", "Claimable", formatAmount(state.rewardsUerth),
                EarthTheme.domain.stakingBg, EarthTheme.domain.stakingFg, onStakingClick)
        }
    }
}

@Composable
private fun HoldingRow(
    name: String,
    subtitle: String,
    value: String,
    bg: androidx.compose.ui.graphics.Color,
    fg: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    val dimens = EarthTheme.dimens
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = dimens.space12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = name, style = EarthTypography.textMd.copy(color = EarthColors.Text.textPrimary))
            Text(text = subtitle, style = EarthTypography.textSm.copy(color = EarthColors.Text.textTertiary))
        }
        Text(text = value, style = EarthTypography.textMd.copy(color = EarthColors.Text.textPrimary))
    }
}

/** Whole ERTH with thousands separators; fractions live in the detail rows. */
private fun formatAmount(uerth: Long): String = "%,d".format(uerth / 1_000_000)
