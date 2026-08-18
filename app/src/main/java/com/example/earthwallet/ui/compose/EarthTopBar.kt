package network.erth.wallet.ui.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import network.erth.wallet.R
import network.erth.wallet.ui.vendor.component.EarthIconButton
import network.erth.wallet.ui.vendor.component.EarthSmallTopAppBar
import network.erth.wallet.ui.vendor.component.EarthTopAppBarBackNavigation
import network.erth.wallet.ui.vendor.component.IconButtonState
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography
import network.erth.wallet.ui.vendor.util.stringRes

/**
 * The main bar: which wallet you are in, and the way out to settings.
 *
 * Adapted from their ZashiTopAppBarWithAccountSelection. Theirs switches
 * between a Zashi and a Keystone account; Earth has one wallet, so the left
 * side identifies rather than switches — same 32dp mark, same 40dp touch
 * targets, no chevron implying a menu that does not exist.
 *
 * Hiding balances stays: shoulder-surfing is the reason it exists, and that
 * does not depend on which chain the balance is denominated in.
 */
@Composable
fun EarthMainTopBar(
    walletName: String,
    balancesVisible: Boolean,
    onToggleBalances: () -> Unit,
    onSettings: () -> Unit,
    /**
     * Whether this tab shows any of your money.
     *
     * The mark and the hide-balances toggle belong to the wallet, not to the
     * app: on Markets or Explore there is nothing of yours on screen, so an eye
     * that hides nothing is a control that teaches you it does nothing.
     */
    showsBalances: Boolean = true,
    /**
     * An action belonging to this tab, left of settings.
     *
     * Tabs are destinations, not toolbars, so most have none. Swap has one
     * because providing liquidity is adjacent to swapping without being part
     * of it — same market, different thing to do with it.
     */
    tabAction: TabAction? = null,
) {
    EarthSmallTopAppBar(
        windowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Top),
        navigationAction = {
            Row(
                modifier = Modifier
                    .defaultMinSize(40.dp, 40.dp)
                    .padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showsBalances) {
                    Image(
                        modifier = Modifier.size(32.dp),
                        painter = painterResource(R.drawable.logo),
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = walletName,
                    style = EarthTypography.header6,
                    fontWeight = FontWeight.SemiBold,
                    color = EarthColors.Text.textPrimary,
                )
            }
        },
        hamburgerMenuActions = {
            if (tabAction != null) {
                EarthIconButton(
                    state = IconButtonState(
                        icon = tabAction.icon,
                        contentDescription = stringRes(tabAction.label),
                        onClick = tabAction.onClick,
                    ),
                    modifier = Modifier.size(40.dp),
                )
                Spacer(Modifier.width(4.dp))
            }
            if (showsBalances) {
                EarthIconButton(
                    state = IconButtonState(
                        icon = if (balancesVisible) {
                            R.drawable.ic_app_bar_balances_show
                        } else {
                            R.drawable.ic_app_bar_balances_hide
                        },
                        contentDescription = stringRes(
                            if (balancesVisible) "Hide balances" else "Show balances",
                        ),
                        onClick = onToggleBalances,
                    ),
                    modifier = Modifier.size(40.dp),
                )
                Spacer(Modifier.width(4.dp))
            }
            EarthIconButton(
                state = IconButtonState(
                    icon = R.drawable.ic_app_bar_settings,
                    contentDescription = stringRes("Settings"),
                    onClick = onSettings,
                ),
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.width(20.dp))
        },
    )
}

/** The bar for anything pushed on top of a tab: a title and the way back. */
@Composable
fun EarthDetailTopBar(title: String, onBack: () -> Unit) {
    EarthSmallTopAppBar(
        windowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Top),
        title = title,
        navigationAction = { EarthTopAppBarBackNavigation(onBack = onBack) },
    )
}

/** An icon action a tab puts in the top bar. */
data class TabAction(
    val icon: Int,
    val label: String,
    val onClick: () -> Unit,
)
