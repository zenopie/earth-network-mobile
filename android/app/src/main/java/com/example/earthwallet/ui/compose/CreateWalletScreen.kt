package network.erth.wallet.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import network.erth.wallet.ui.theme.EarthAccent
import network.erth.wallet.ui.theme.EarthTheme
import network.erth.wallet.ui.vendor.component.EarthButton
import network.erth.wallet.ui.vendor.component.EarthButtonDefaults
import network.erth.wallet.ui.vendor.component.EarthCheckbox
import network.erth.wallet.ui.vendor.component.EarthTextField
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.dimensions.EarthDimensions
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography
import network.erth.wallet.ui.vendor.util.stringRes

/**
 * A new wallet: the phrase, then a name.
 *
 * The phrase is shown numbered in a grid rather than as a sentence. Order is
 * the part people get wrong when writing twelve words down, and a numbered grid
 * makes the order impossible to mistake while copying.
 *
 * There is no "copy to clipboard". A seed on the clipboard is readable by every
 * app on the device and survives in clipboard history; the whole point of this
 * screen is that the phrase leaves it only in the user's handwriting.
 *
 * The acknowledgement is a checkbox rather than a re-entry quiz. A quiz proves
 * the phrase was transcribed a minute ago, not that it was kept — and it
 * teaches people to screenshot the grid to get past it, which is the exact
 * behaviour the screen is trying to prevent.
 */
@Composable
fun CreateWalletScreen(
    mnemonic: String?,
    onConfirm: (name: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The phrase is on screen here. Keep it out of screenshots and out of the
    // recents snapshot the system takes when the user switches away to write
    // it down — which is exactly what this screen asks them to do.
    SecureScreen()
    val dimens = EarthTheme.dimens
    var name by remember { mutableStateOf("") }
    var acknowledged by remember { mutableStateOf(false) }

    Column(
        modifier
            .fillMaxSize()
            .background(EarthColors.Surfaces.bgPrimary)
            .dismissKeyboardOnTap()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = dimens.gutter),
    ) {
        Spacer(Modifier.height(dimens.space16))
        Text(
            text = "Write these words down, in this order. They are the only " +
                "way to recover this wallet — nobody can reset them for you, " +
                "and anyone who has them has the wallet.",
            style = EarthTypography.textSm,
            color = EarthColors.Text.textSecondary,
        )

        Spacer(Modifier.height(dimens.space16))

        if (mnemonic == null) {
            Text(
                text = "Generating…",
                style = EarthTypography.textSm,
                color = EarthColors.Text.textTertiary,
            )
            return@Column
        }

        SeedGrid(mnemonic.split(" "))

        Spacer(Modifier.height(dimens.space24))
        EarthLabel("Name")
        Spacer(Modifier.height(dimens.space8))
        EarthTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Wallet") },
        )

        Spacer(Modifier.height(dimens.space16))
        EarthCheckbox(
            text = stringRes("I have written the phrase down somewhere safe."),
            isChecked = acknowledged,
            onClick = { acknowledged = !acknowledged },
        )

        Spacer(Modifier.height(dimens.space16))
        EarthButton(
            text = "Create wallet",
            onClick = { onConfirm(name) },
            enabled = acknowledged,
            modifier = Modifier.fillMaxWidth(),
            colors = brandButtonColors(),
        )
        Spacer(Modifier.height(dimens.space32))
    }
}

/**
 * The phrase, numbered, two to a row.
 *
 * Two columns rather than three: twelve words in three columns fits, but the
 * eye has to track across a row and back, and a transposed pair between
 * columns is the single most common way a written-down phrase goes wrong.
 */
@Composable
private fun SeedGrid(words: List<String>) {
    val dimens = EarthTheme.dimens
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                EarthColors.Surfaces.bgSecondary,
                RoundedCornerShape(EarthDimensions.Radius.radius3xl),
            )
            .padding(dimens.space16),
        verticalArrangement = Arrangement.spacedBy(dimens.space12),
    ) {
        words.chunked(2).forEachIndexed { row, pair ->
            Row(Modifier.fillMaxWidth()) {
                pair.forEachIndexed { col, word ->
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${row * 2 + col + 1}",
                            style = EarthTypography.textSm,
                            color = EarthColors.Text.textTertiary,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(dimens.space24),
                        )
                        Spacer(Modifier.width(dimens.space8))
                        Text(
                            text = word,
                            style = EarthTypography.textMd,
                            fontWeight = FontWeight.Medium,
                            color = EarthColors.Text.textPrimary,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Restore from a phrase.
 *
 * One field rather than twelve. Twelve boxes look careful but fight paste,
 * fight a phrase of a different length, and turn one wrong word into a hunt
 * through a grid; the validation below catches a bad phrase either way, and it
 * catches it before anything is stored.
 */
@Composable
fun ImportWalletScreen(
    error: String?,
    onImport: (name: String, phrase: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // A phrase being typed in is as sensitive as one being shown.
    SecureScreen()
    val dimens = EarthTheme.dimens
    var name by remember { mutableStateOf("") }
    var phrase by remember { mutableStateOf("") }

    Column(
        modifier
            .fillMaxSize()
            .background(EarthColors.Surfaces.bgPrimary)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = dimens.gutter),
    ) {
        Spacer(Modifier.height(dimens.space16))
        Text(
            text = "Enter the recovery phrase for the wallet you want back. " +
                "Spacing and capitalisation do not matter.",
            style = EarthTypography.textSm,
            color = EarthColors.Text.textSecondary,
        )

        Spacer(Modifier.height(dimens.space16))
        EarthLabel("Recovery phrase")
        Spacer(Modifier.height(dimens.space8))
        EarthTextField(
            value = phrase,
            onValueChange = { phrase = it },
            modifier = Modifier.fillMaxWidth(),
            error = error,
            placeholder = { Text("word word word…") },
        )

        Spacer(Modifier.height(dimens.space16))
        EarthLabel("Name")
        Spacer(Modifier.height(dimens.space8))
        EarthTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Wallet") },
        )

        Spacer(Modifier.height(dimens.space24))
        EarthButton(
            text = "Import wallet",
            onClick = { onImport(name, phrase) },
            enabled = phrase.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            colors = brandButtonColors(),
        )
        Spacer(Modifier.height(dimens.space32))
    }
}
