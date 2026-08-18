package network.erth.wallet.ui.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import network.erth.wallet.ui.vendor.component.EarthHorizontalDivider
import network.erth.wallet.ui.vendor.component.EarthVersion
import network.erth.wallet.ui.vendor.component.listitem.EarthListItem
import network.erth.wallet.ui.vendor.util.imageRes
import network.erth.wallet.ui.vendor.util.stringRes

/** One row in a settings list: what it is, and what happens when it is tapped. */
data class SettingsItem(
    val title: String,
    val icon: Int,
    val subtitle: String? = null,
    val onClick: () -> Unit,
)

/**
 * Settings.
 *
 * Their MoreView, structurally unchanged: a plain scrolling list of list items
 * separated by dividers with 4dp of horizontal inset, then the version pinned
 * to the bottom by a weighted spacer. No cards and no section headers — a
 * settings screen is scanned for one row, and grouping boxes slow that down.
 */
@Composable
fun SettingsScreen(
    items: List<SettingsItem>,
    version: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxHeight()
            .verticalScroll(rememberScrollState()),
    ) {
        items.forEachIndexed { index, item ->
            EarthListItem(
                modifier = Modifier.padding(horizontal = 4.dp),
                title = item.title,
                subtitle = item.subtitle,
                icon = imageRes(item.icon),
                onClick = item.onClick,
            )
            if (index != items.lastIndex) {
                EarthHorizontalDivider(modifier = Modifier.padding(horizontal = 4.dp))
            }
        }

        Spacer(Modifier.height(24.dp))
        Spacer(Modifier.weight(1f))

        EarthVersion(
            modifier = Modifier.fillMaxWidth(),
            version = stringRes(version),
        )
        Spacer(Modifier.height(24.dp))
    }
}
