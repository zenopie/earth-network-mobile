package network.erth.wallet.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import network.erth.wallet.R
import network.erth.wallet.ui.components.HeroColumn
import network.erth.wallet.ui.components.HeroGap
import network.erth.wallet.ui.components.HeroLine
import network.erth.wallet.ui.components.HeroTitle
import network.erth.wallet.ui.designsystem.component.EarthHorizontalDivider
import network.erth.wallet.ui.designsystem.component.listitem.EarthListItem
import network.erth.wallet.ui.designsystem.theme.colors.EarthColors
import network.erth.wallet.ui.designsystem.util.imageRes

/** About: the logo, the name and version, and two links. */
@Composable
fun AboutScreen(
    /** "Version <name> (<code>)", from BuildConfig. */
    version: String,
    onPrivacyPolicy: () -> Unit,
    onSource: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(EarthColors.Surfaces.bgPrimary)
            .verticalScroll(rememberScrollState()),
    ) {
        HeroColumn {
            Image(painterResource(R.drawable.logo), null, Modifier.size(72.dp))
            HeroGap()
            HeroTitle("Earth Wallet")
            HeroLine(version)
        }
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
            title = "Source",
            icon = imageRes(R.drawable.ic_explore),
            onClick = onSource,
        )
        Spacer(Modifier.height(24.dp))
    }
}
