package network.erth.wallet.ui.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import network.erth.wallet.R
import network.erth.wallet.ui.vendor.component.EarthHorizontalDivider
import network.erth.wallet.ui.vendor.component.EarthVersion
import network.erth.wallet.ui.vendor.component.listitem.EarthListItem
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography
import network.erth.wallet.ui.vendor.util.imageRes
import network.erth.wallet.ui.vendor.util.stringRes

/**
 * About.
 *
 * Their AboutView shape: a bolded lede, a paragraph, then the legal links as
 * list items, with the version pinned at the bottom. The copy is Earth's — it
 * explains what the chain issues and why, because that is the question someone
 * opens this screen with.
 */
@Composable
fun AboutScreen(
    version: String,
    onPrivacyPolicy: () -> Unit,
    onTerms: () -> Unit,
    onSource: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxHeight()
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 24.dp),
            text = "A wallet for Earth Network.",
            color = EarthColors.Text.textPrimary,
            style = EarthTypography.header6,
            fontWeight = FontWeight.SemiBold,
        )

        Spacer(Modifier.height(12.dp))

        Text(
            modifier = Modifier.padding(horizontal = 24.dp),
            text = "Earth issues exactly 4 ERTH per second, forever, split evenly across " +
                "four streams: staking rewards, the ANML buyback, and two allocation " +
                "streams — one weighted by verified personhood, one by stake. Because " +
                "the rate is fixed while the supply it adds to grows, inflation falls " +
                "on its own. There is no schedule and no halving.\n\n" +
                "Your keys are held on this device. Registering as a verified human " +
                "reads your passport over NFC and proves it on-device; the passport " +
                "itself never leaves the phone.",
            color = EarthColors.Text.textPrimary,
            style = EarthTypography.textSm,
        )

        Spacer(Modifier.height(32.dp))

        EarthListItem(
            modifier = Modifier.padding(horizontal = 4.dp),
            title = "Privacy policy",
            icon = imageRes(R.drawable.ic_info),
            onClick = onPrivacyPolicy,
        )
        EarthHorizontalDivider(modifier = Modifier.padding(horizontal = 4.dp))
        EarthListItem(
            modifier = Modifier.padding(horizontal = 4.dp),
            title = "Terms of use",
            icon = imageRes(R.drawable.ic_info),
            onClick = onTerms,
        )
        EarthHorizontalDivider(modifier = Modifier.padding(horizontal = 4.dp))
        EarthListItem(
            modifier = Modifier.padding(horizontal = 4.dp),
            title = "Source code",
            icon = imageRes(R.drawable.ic_explore),
            onClick = onSource,
        )

        Spacer(Modifier.height(24.dp))
        Spacer(Modifier.weight(1f))

        EarthVersion(
            modifier = Modifier.fillMaxWidth(),
            version = stringRes(version),
        )
        Spacer(Modifier.height(24.dp))
    }
}
