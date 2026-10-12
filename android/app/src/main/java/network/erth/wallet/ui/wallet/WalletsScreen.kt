package network.erth.wallet.ui.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import network.erth.wallet.privacy.handles.Handles
import network.erth.wallet.ui.components.HeroColumn
import network.erth.wallet.ui.components.HeroGap
import network.erth.wallet.ui.components.HeroInitial
import network.erth.wallet.ui.components.HeroLine
import network.erth.wallet.ui.components.HeroTitle
import network.erth.wallet.ui.components.SecureScreen
import network.erth.wallet.ui.components.destructiveButtonColors
import network.erth.wallet.ui.designsystem.component.EarthButton
import network.erth.wallet.ui.designsystem.theme.colors.EarthColors
import network.erth.wallet.ui.designsystem.theme.typography.EarthTypography
import network.erth.wallet.ui.earn.PillButton
import network.erth.wallet.ui.onboarding.SeedGrid
import network.erth.wallet.ui.theme.EarthAccent
import network.erth.wallet.ui.theme.EarthTheme

/**
 * The wallets in this install: the current one as the hero, the others as
 * rows (a tap switches), and two ways to add another.
 *
 * Delete is for the current wallet only, behind a fresh unlock and its
 * phrase shown back ([DeleteWalletScreen]): it removes the only copy of a
 * mnemonic this phone holds.
 */
@Composable
fun WalletsScreen(
    state: WalletsUiState?,
    onSelect: (Int) -> Unit,
    onCreate: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
    onDelete: () -> Unit = {},
    onRename: (String) -> Unit = {},
) {
    val dimens = EarthTheme.dimens
    val current = state?.wallets?.getOrNull(state.selectedIndex)
    var renaming by remember { mutableStateOf(false) }
    if (renaming && current != null) {
        RenameDialog(current.name, onSave = { renaming = false; onRename(it) }, onDismiss = { renaming = false })
    }
    Column(
        modifier
            .fillMaxSize()
            .background(EarthColors.Surfaces.bgPrimary)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = dimens.gutter),
    ) {
        HeroColumn {
            HeroInitial(current?.name.orEmpty())
            HeroGap()
            // Tap the name to rename it.
            Row(
                Modifier.clickable(enabled = current != null) { renaming = true }.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    current?.name ?: "Wallet",
                    style = EarthTypography.header5.copy(fontWeight = FontWeight.Bold),
                    color = EarthColors.Text.textPrimary,
                    maxLines = 1,
                )
                if (current != null) {
                    Spacer(Modifier.width(6.dp))
                    Text("✎", style = EarthTypography.textMd, color = EarthColors.Text.textTertiary)
                }
            }
            HeroLine(current?.address?.let { Handles.truncate(it, 10, 6) }.orEmpty())
        }
        Spacer(Modifier.height(32.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Create", modifier = Modifier.weight(1f), onClick = onCreate)
            PillButton("Import", primary = false, modifier = Modifier.weight(1f), onClick = onImport)
        }
        Spacer(Modifier.height(dimens.space24))
        state?.wallets?.filter { it.index != state.selectedIndex }?.forEach { wallet ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(wallet.index) }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(44.dp).background(EarthColors.Surfaces.bgTertiary, CircleShape), contentAlignment = Alignment.Center) {
                    Text(wallet.name.take(1).uppercase(), style = EarthTypography.textMd, fontWeight = FontWeight.Bold,
                        color = EarthColors.Text.textTertiary)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(wallet.name, style = EarthTypography.textMd, fontWeight = FontWeight.SemiBold,
                        color = EarthColors.Text.textPrimary, maxLines = 1)
                    Text(Handles.truncate(wallet.address, 10, 6), style = EarthTypography.textXs,
                        color = EarthColors.Text.textTertiary, maxLines = 1)
                }
            }
        }
        if (current != null) {
            Spacer(Modifier.height(dimens.space32))
            Text(
                "Delete wallet",
                style = EarthTypography.textMd,
                fontWeight = FontWeight.SemiBold,
                color = EarthColors.Utility.ErrorRed.utilityError700,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clickable(onClick = onDelete).padding(vertical = 12.dp),
            )
        }
        Spacer(Modifier.height(dimens.space32))
    }
}

@Composable
private fun RenameDialog(name: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(name) }
    val clean = network.erth.wallet.wallet.WalletList.cleanName(text)
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename wallet") },
        text = {
            network.erth.wallet.ui.designsystem.component.EarthTextField(
                value = text,
                onValueChange = { text = it.take(network.erth.wallet.wallet.WalletList.NAME_MAX) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { clean?.let(onSave) }, enabled = clean != null && clean != name) { Text("Save") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The last look at a wallet's phrase before it is deleted. Reached only past
 * a fresh unlock; kept out of screenshots and recents while shown.
 */
@Composable
fun DeleteWalletScreen(
    name: String,
    phrase: String,
    /** The only wallet: deleting it erases the install. */
    last: Boolean,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    deleting: Boolean = false,
    error: String? = null,
) {
    SecureScreen()
    val dimens = EarthTheme.dimens
    var wrote by remember { mutableStateOf(false) }
    val red = EarthColors.Utility.ErrorRed.utilityError700
    Column(
        modifier
            .fillMaxSize()
            .background(EarthColors.Surfaces.bgPrimary)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = dimens.gutter),
    ) {
        HeroColumn {
            HeroTitle("Delete $name?")
            HeroLine(if (last) "Your only wallet · the app resets" else "Without this phrase it's gone", color = red)
        }
        Spacer(Modifier.height(dimens.space24))
        SeedGrid(phrase.trim().split(Regex("\\s+")))
        Spacer(Modifier.height(dimens.space16))
        Row(Modifier.fillMaxWidth().clickable { wrote = !wrote }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("I wrote this phrase down", style = EarthTypography.textMd, color = EarthColors.Text.textPrimary, modifier = Modifier.weight(1f))
            Switch(checked = wrote, onCheckedChange = { wrote = it }, colors = SwitchDefaults.colors(checkedTrackColor = EarthAccent.ink))
        }
        Spacer(Modifier.height(dimens.space24))
        error?.let {
            Text(it, style = EarthTypography.textSm, color = red, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(dimens.space8))
        }
        EarthButton(
            text = "Delete",
            onClick = onDelete,
            enabled = wrote && !deleting,
            isLoading = deleting,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
            colors = destructiveButtonColors(),
        )
        Spacer(Modifier.height(dimens.space32))
    }
}
