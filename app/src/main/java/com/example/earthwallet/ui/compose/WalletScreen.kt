package network.erth.wallet.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import network.erth.wallet.ui.theme.EarthTheme

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
 * The balance screen.
 *
 * One number, two actions, then holdings. The whole argument for this layout is
 * that a wallet is opened to answer "how much do I have" far more often than
 * anything else, so that question is answered before anything competes with it.
 */
@Composable
fun WalletScreen(
    state: WalletUiState,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onStakingClick: () -> Unit = {},
    onAnmlClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    /**
     * Off when an ancestor already scrolls. Two nested vertical scrolls measure
     * with an infinite height constraint and Compose throws rather than guessing.
     */
    scrollable: Boolean = true,
) {
    val colors = EarthTheme.colors
    val dimens = EarthTheme.dimens

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surfaces.bgPrimary)
            .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            // The screen draws to the top of the display, so the status bar has
            // to be accounted for or the balance sits underneath it.
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = dimens.gutter)
            .padding(top = dimens.space24, bottom = dimens.space32),
    ) {
        EarthLabel("Total balance")
        Spacer(Modifier.height(dimens.space4))
        EarthAmount(
            amount = formatAmount(state.balanceUerth),
            denom = null,
        )
        Text(
            text = state.address,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.text.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
        )

        Spacer(Modifier.height(dimens.space24))
        EarthButton("Send", onSend)
        Spacer(Modifier.height(dimens.space8))
        EarthButton("Receive", onReceive, style = EarthButtonStyle.Secondary)

        Spacer(Modifier.height(dimens.space24))
        EarthLabel("Holdings")
        Spacer(Modifier.height(dimens.space4))

        if (state.anmlBalance != null) {
            EarthListRow(
                initial = "A",
                name = "ANML",
                subtitle = if (state.registered) "Proof of personhood" else "Not registered",
                value = state.anmlBalance,
                iconBg = colors.domain.anmlBadgeBg,
                iconFg = colors.domain.anmlBadgeFg,
                onClick = onAnmlClick,
            )
        }
        if (state.stakedUerth > 0) {
            EarthListRow(
                initial = "S",
                name = "Staked",
                subtitle = "Delegated",
                value = formatAmount(state.stakedUerth),
                iconBg = colors.domain.stakingBg,
                iconFg = colors.domain.stakingAccent,
                onClick = onStakingClick,
            )
        }
        if (state.rewardsUerth > 0) {
            EarthListRow(
                initial = "R",
                name = "Rewards",
                subtitle = "Claimable",
                value = formatAmount(state.rewardsUerth),
                iconBg = colors.domain.stakingBg,
                iconFg = colors.domain.stakingAccent,
                onClick = onStakingClick,
            )
        }

        if (state.registered) {
            Spacer(Modifier.height(dimens.space16))
            Row(Modifier.fillMaxWidth()) {
                EarthStatusPill(EarthStatus.Success, "Registered human")
                Spacer(Modifier.width(dimens.space8))
            }
        }
    }
}

/** Whole ERTH with thousands separators; the fraction lives in the detail rows. */
private fun formatAmount(uerth: Long): String {
    val whole = uerth / 1_000_000
    return "%,d".format(whole)
}
