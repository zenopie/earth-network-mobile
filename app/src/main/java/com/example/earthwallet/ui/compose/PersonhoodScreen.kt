package network.erth.wallet.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import network.erth.wallet.ui.theme.EarthTheme
import network.erth.wallet.ui.vendor.component.EarthButton
import network.erth.wallet.ui.vendor.component.EarthButtonDefaults
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.dimensions.EarthDimensions
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography

/**
 * Identity: whether this wallet is a verified human, and the ANML it earns.
 *
 * Built on their ReceiveView composition — a large-radius panel with a circular
 * badge, a title and a subtitle — because this screen answers the same kind of
 * question: one piece of state, stated plainly, with one action under it.
 *
 * The claim of what the passport scan does and does not send is on the screen
 * rather than in a help page. It is the objection someone has at the moment
 * they are asked to hold their passport to their phone, and answering it a tap
 * away is answering it too late.
 */
@Composable
fun PersonhoodScreen(
    registered: Boolean,
    anmlBalance: String?,
    onRegister: () -> Unit,
    onClaim: () -> Unit,
    modifier: Modifier = Modifier,
    claiming: Boolean = false,
) {
    val dimens = EarthTheme.dimens
    val shape = RoundedCornerShape(EarthDimensions.Radius.radius3xl)

    Column(
        modifier
            .fillMaxSize()
            .background(EarthColors.Surfaces.bgPrimary)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = dimens.gutter),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(dimens.space24))

        Column(
            Modifier
                .fillMaxWidth()
                .background(
                    if (registered) EarthTheme.domain.anmlBg else EarthColors.Surfaces.bgSecondary,
                    shape,
                )
                .padding(dimens.space24),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(dimens.space48)
                    .background(EarthColors.Surfaces.bgPrimary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (registered) "✓" else "?",
                    style = EarthTypography.header5,
                    color = if (registered) {
                        EarthTheme.domain.anmlFg
                    } else {
                        EarthColors.Text.textTertiary
                    },
                )
            }
            Spacer(Modifier.height(dimens.space12))
            Text(
                text = if (registered) "Verified human" else "Not registered",
                style = EarthTypography.header5,
                color = EarthColors.Text.textPrimary,
            )
            Spacer(Modifier.height(dimens.space4))
            Text(
                text = if (registered) {
                    "This wallet counts as one person in the human allocation stream, " +
                        "and earns ANML."
                } else {
                    "Register to count as one person in the human allocation stream."
                },
                style = EarthTypography.textSm,
                color = EarthColors.Text.textSecondary,
                textAlign = TextAlign.Center,
            )
        }

        if (registered) {
            Spacer(Modifier.height(dimens.space16))
            EarthDetailRow("ANML balance", anmlBalance ?: "0")
            Spacer(Modifier.height(dimens.space16))
            EarthButton(
                text = "Claim ANML",
                onClick = onClaim,
                isLoading = claiming,
                modifier = Modifier.fillMaxWidth(),
                colors = brandButtonColors(),
            )
        } else {
            Spacer(Modifier.height(dimens.space24))
            EarthButton(
                text = "Scan passport",
                onClick = onRegister,
                modifier = Modifier.fillMaxWidth(),
                colors = brandButtonColors(),
            )
            Spacer(Modifier.height(dimens.space16))
            Text(
                text = "Your passport is read over NFC and proved on this device. " +
                    "The chip data never leaves your phone — only the proof is " +
                    "broadcast, and it does not identify you.",
                style = EarthTypography.textSm,
                color = EarthColors.Text.textTertiary,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(dimens.space32))
    }
}
