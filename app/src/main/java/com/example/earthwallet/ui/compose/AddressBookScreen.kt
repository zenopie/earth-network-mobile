package network.erth.wallet.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import network.erth.wallet.ui.theme.EarthTheme
import network.erth.wallet.ui.vendor.component.EarthButton
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography

data class Contact(val name: String, val address: String)

/**
 * Address book.
 *
 * Adapted from their AddressBookView: an initial in a circular avatar, name
 * above a truncated address, and the add action pinned at the bottom rather
 * than hidden behind a top-bar icon — it is the only thing to do on an empty
 * screen, and the empty screen is the common case at first.
 */
@Composable
fun AddressBookScreen(
    contacts: List<Contact>,
    onAdd: () -> Unit,
    onSelect: (Contact) -> Unit,
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
) {
    val dimens = EarthTheme.dimens
    Column(
        modifier
            .fillMaxSize()
            .background(EarthColors.Surfaces.bgPrimary)
            .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier),
    ) {
        Text(
            text = "Address book",
            style = EarthTypography.header5.copy(color = EarthColors.Text.textPrimary),
            modifier = Modifier.padding(dimens.gutter),
        )

        if (contacts.isEmpty()) {
            Spacer(Modifier.height(dimens.space24))
            Text(
                text = "No saved addresses yet. Add one so you don't have to paste it next time.",
                style = EarthTypography.textSm.copy(color = EarthColors.Text.textTertiary),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = dimens.space32),
            )
            Spacer(Modifier.height(dimens.space24))
        }

        contacts.forEach { c ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(c) }
                    .padding(horizontal = dimens.gutter, vertical = dimens.space12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(dimens.space32)
                        .background(EarthColors.Surfaces.bgSecondary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = c.name.take(1).uppercase(),
                        style = EarthTypography.textSm.copy(color = EarthColors.Text.textPrimary),
                    )
                }
                Column(Modifier.weight(1f).padding(start = dimens.space12)) {
                    Text(
                        text = c.name,
                        style = EarthTypography.textMd.copy(color = EarthColors.Text.textPrimary),
                    )
                    Text(
                        text = c.address,
                        style = EarthTypography.textSm.copy(color = EarthColors.Text.textTertiary),
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                }
            }
        }

        Spacer(Modifier.height(dimens.space24))
        EarthButton(
            text = "Add address",
            onClick = onAdd,
            modifier = Modifier.fillMaxWidth().padding(horizontal = dimens.gutter),
            colors = brandButtonColors(),
        )
        Spacer(Modifier.height(dimens.space24))
    }
}
