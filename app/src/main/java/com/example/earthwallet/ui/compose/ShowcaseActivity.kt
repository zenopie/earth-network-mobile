package network.erth.wallet.ui.compose

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import network.erth.wallet.ui.theme.EarthTheme

/**
 * Every component on one screen, so the design system can be looked at rather
 * than imagined.
 *
 * Not reachable from the app — launched directly:
 *
 *     adb shell am start -n network.erth.wallet/.ui.compose.ShowcaseActivity
 *
 * It stays in the debug build for as long as the rewrite is in progress; it is
 * the fastest way to see a token change land across everything at once.
 */
class ShowcaseActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { EarthTheme { Showcase() } }
    }
}

@Composable
private fun Showcase() {
    val colors = EarthTheme.colors
    val dimens = EarthTheme.dimens
    var sheet by remember { mutableStateOf<TxOutcome?>(null) }
    var confirming by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.Surfaces.bgSecondary)
            .verticalScroll(rememberScrollState()),
    ) {
        // The real screen, on its own ground.
        WalletScreen(
            state = WalletUiState(
                address = "earth1c9pthe4a5ngylhm5mjem6y5tt0sz65yqdngthx",
                balanceUerth = 46_000_000_000,
                anmlBalance = "1.00",
                stakedUerth = 100_000_000,
                rewardsUerth = 2_190_000,
                registered = true,
            ),
            onSend = { confirming = true },
            onReceive = { sheet = TxOutcome.Message("Receive", "Your address is shown here.") },
            scrollable = false,
        )

        Column(Modifier.padding(dimens.gutter)) {
            EarthLabel("Buttons")
            Spacer(Modifier.height(dimens.space8))
            EarthButton("Primary", {})
            Spacer(Modifier.height(dimens.space8))
            EarthButton("Secondary", {}, style = EarthButtonStyle.Secondary)
            Spacer(Modifier.height(dimens.space8))
            EarthButton("Ghost", {}, style = EarthButtonStyle.Ghost)
            Spacer(Modifier.height(dimens.space8))
            EarthButton("Destructive", {}, style = EarthButtonStyle.Destructive)
            Spacer(Modifier.height(dimens.space8))
            EarthButton("Disabled", {}, enabled = false)
            Spacer(Modifier.height(dimens.space8))
            EarthButton("Loading", {}, loading = true)

            Spacer(Modifier.height(dimens.space24))
            EarthLabel("Status")
            Spacer(Modifier.height(dimens.space8))
            EarthStatusPill(EarthStatus.Success, "Confirmed")
            Spacer(Modifier.height(dimens.space4))
            EarthStatusPill(EarthStatus.Pending, "Pending")
            Spacer(Modifier.height(dimens.space4))
            EarthStatusPill(EarthStatus.Failed, "Failed")
            Spacer(Modifier.height(dimens.space4))
            EarthStatusPill(EarthStatus.Neutral, "Unbonding")

            Spacer(Modifier.height(dimens.space24))
            EarthLabel("Detail rows")
            EarthDetailRow("Network fee", "0.002 ERTH")
            EarthDetailRow("Balance after", "45.998 ERTH")

            Spacer(Modifier.height(dimens.space24))
            EarthLabel("Chain error")
            Spacer(Modifier.height(dimens.space8))
            EarthCodeBlock(
                "out of gas in location: ReadFlat; gasWanted: 400000, " +
                    "gasUsed: 400324: out of gas",
            )

            Spacer(Modifier.height(dimens.space24))
            EarthLabel("Sheets")
            Spacer(Modifier.height(dimens.space8))
            EarthButton("Show failure", {
                sheet = TxOutcome.Failure(
                    "Stake ERTH",
                    IllegalStateException(
                        "out of gas in location: ReadFlat; gasWanted: 400000, " +
                            "gasUsed: 400324: out of gas",
                    ),
                )
            }, style = EarthButtonStyle.Secondary)
            Spacer(Modifier.height(dimens.space8))
            EarthButton("Show success", {
                sheet = TxOutcome.Success("Stake ERTH", "62DE2CF2D504B6E1A9F0…")
            }, style = EarthButtonStyle.Secondary)
            Spacer(Modifier.height(dimens.space8))
            EarthButton("Show confirm (unfunded)", { confirming = true },
                style = EarthButtonStyle.Secondary)

            Spacer(Modifier.height(dimens.space32))
            Text(
                "Sprout · ${'$'}{132} raw values, 75 semantic tokens",
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                color = colors.Text.textTertiary,
            )
        }
    }

    sheet?.let { TxResultSheet(it) { sheet = null } }

    if (confirming) {
        TxConfirmSheet(
            details = TxConfirmDetails(
                action = "Stake ERTH",
                msgTypeUrl = "/cosmos.staking.v1beta1.MsgDelegate",
                feeUerth = 2_000,
                balanceUerth = 500,
                amountLabel = "Amount",
                amountValue = "100 ERTH",
            ),
            onConfirm = { confirming = false },
            onDismiss = { confirming = false },
            onWatchAd = {},
        )
    }
}
