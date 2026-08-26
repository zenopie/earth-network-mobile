package network.erth.wallet.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import network.erth.wallet.ui.theme.EarthTheme
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import network.erth.wallet.ui.theme.EarthAccent

/**
 * The handful of pieces the vendored library does not carry.
 *
 * Everything with a general-purpose equivalent — buttons, cards, sheets, list
 * items, badges, dividers — comes from the vendored components instead. What is
 * left here is Earth-shaped: an uppercase eyebrow, a label/value row, and the
 * monospace block a chain error is printed into.
 */

/** Uppercase eyebrow: "TOTAL BALANCE", "NETWORK FEE". */
@Composable
fun EarthLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = EarthTypography.textSm.copy(color = EarthColors.Text.textTertiary),
        modifier = modifier,
    )
}

/** A label/value line — fee, amount, balance-after. */
@Composable
fun EarthDetailRow(label: String, value: String, modifier: Modifier = Modifier) {
    val dimens = EarthTheme.dimens
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = dimens.space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = EarthTypography.textMd.copy(color = EarthColors.Text.textTertiary),
            modifier = Modifier.weight(1f),
        )
        Text(text = value, style = EarthTypography.textMd.copy(color = EarthColors.Text.textPrimary))
    }
}

/**
 * A chain error or transaction hash.
 *
 * Selectable, because the whole point of showing it is that it can be taken
 * somewhere else. This is the successor to the toast that used to truncate
 * "out of gas in location: ReadFlat; gasWanted: 400000, gasUsed: 400324".
 */
@Composable
fun EarthCodeBlock(text: String, modifier: Modifier = Modifier) {
    val dimens = EarthTheme.dimens
    SelectionContainer {
        Text(
            text = text,
            style = EarthTypography.textSm.copy(color = EarthColors.Text.textSecondary),
            modifier = modifier
                .fillMaxWidth()
                .background(EarthColors.Surfaces.bgSecondary, RoundedCornerShape(dimens.radiusSm))
                .border(
                    dimens.strokeWidth,
                    EarthColors.Surfaces.strokeSecondary,
                    RoundedCornerShape(dimens.radiusSm),
                )
                .padding(dimens.space12),
        )
    }
}

/** Transaction and registration state, as colour *and* word. */
enum class EarthStatus { Success, Pending, Failed, Neutral }

@Composable
fun EarthStatusPill(status: EarthStatus, text: String, modifier: Modifier = Modifier) {
    val dimens = EarthTheme.dimens
    val (bg, fg) = when (status) {
        EarthStatus.Success ->
            EarthColors.Utility.SuccessGreen.utilitySuccess50 to
                EarthColors.Utility.SuccessGreen.utilitySuccess700
        EarthStatus.Pending ->
            EarthColors.Utility.WarningYellow.utilityOrange50 to
                EarthColors.Utility.WarningYellow.utilityOrange700
        EarthStatus.Failed ->
            EarthColors.Utility.ErrorRed.utilityError50 to
                EarthColors.Utility.ErrorRed.utilityError700
        EarthStatus.Neutral ->
            EarthColors.Utility.Gray.utilityGray100 to EarthColors.Utility.Gray.utilityGray700
    }
    Box(
        modifier
            .background(bg, RoundedCornerShape(dimens.radiusPill))
            .padding(horizontal = dimens.space12, vertical = dimens.space4),
    ) {
        Text(text = text, style = EarthTypography.textSm.copy(color = fg))
    }
}

/**
 * A titled, scrolling screen.
 *
 * Their EarthSmallTopAppBar expects their navigation and scaffold plumbing, so
 * this is the same idea over the vendored tokens: a title, and a column that
 * scrolls under it.
 */
@Composable
fun EarthScaffold(
    title: String,
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dimens = EarthTheme.dimens
    Column(
        modifier
            .fillMaxSize()
            .background(EarthColors.Surfaces.bgPrimary),
    ) {
        Text(
            text = title,
            style = EarthTypography.header5.copy(color = EarthColors.Text.textPrimary),
            modifier = Modifier.padding(horizontal = dimens.gutter, vertical = dimens.space16),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .then(
                    if (scrollable) {
                        Modifier.verticalScroll(rememberScrollState())
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = dimens.gutter)
                .padding(bottom = dimens.space32),
        ) { content() }
    }
}

/** An asset or holding row: name, subtitle, value. */
@Composable
fun EarthListRow(
    initial: String,
    name: String,
    subtitle: String?,
    value: String?,
    modifier: Modifier = Modifier,
    iconBg: androidx.compose.ui.graphics.Color? = null,
    iconFg: androidx.compose.ui.graphics.Color? = null,
    onClick: (() -> Unit)? = null,
) {
    val dimens = EarthTheme.dimens
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(vertical = dimens.space12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(dimens.space32)
                .background(
                    iconBg ?: EarthColors.Surfaces.bgSecondary,
                    RoundedCornerShape(dimens.radiusSm),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initial,
                style = EarthTypography.textSm.copy(
                    color = iconFg ?: EarthColors.Text.textPrimary,
                ),
            )
        }
        Column(Modifier.weight(1f).padding(start = dimens.space12)) {
            Text(text = name, style = EarthTypography.textMd.copy(color = EarthColors.Text.textPrimary))
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = EarthTypography.textSm.copy(color = EarthColors.Text.textTertiary),
                )
            }
        }
        if (value != null) {
            Text(text = value, style = EarthTypography.textMd.copy(color = EarthColors.Text.textPrimary))
        }
    }
}

/**
 * A two-way selector.
 *
 * Earth has one place that needs it — Earn, choosing between staking and pools
 * — so this is two pills rather than Material's SegmentedButton, which brings a
 * border treatment and a check icon that fit nothing else on these screens.
 */
@Composable
fun EarthSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = EarthTheme.dimens
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(dimens.space12))
            .background(EarthColors.Surfaces.bgSecondary)
            .padding(dimens.space4),
        horizontalArrangement = Arrangement.spacedBy(dimens.space4),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(dimens.space8))
                    .background(if (selected) EarthAccent.tint else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(vertical = dimens.space8),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = EarthTypography.textSm,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) {
                        EarthAccent.ink
                    } else {
                        EarthColors.Text.textSecondary
                    },
                )
            }
        }
    }
}

/**
 * A bottom sheet.
 *
 * Their EarthModalBottomSheet is bound to their navigation and state plumbing,
 * so this wraps Material's directly over the same tokens.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun EarthSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val dimens = EarthTheme.dimens
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = EarthColors.Surfaces.bgPrimary,
        shape = RoundedCornerShape(topStart = dimens.radiusSheet, topEnd = dimens.radiusSheet),
    ) {
        Column(
            modifier
                .fillMaxWidth()
                .padding(horizontal = dimens.gutter)
                .padding(bottom = dimens.space32),
        ) { content() }
    }
}

/**
 * The accent-coloured button.
 *
 * Their `primaryColors()` is a black button and their `Btns.Brand` carries the
 * accent — a naming difference, not a design one, and the reason the first
 * build after vendoring produced a black Send button. In this app the primary
 * action is the green one, so screens ask for this.
 */
/**
 * The colours for a button that undoes or refuses.
 *
 * Zashi's set has no destructive rank, so this is Earth's: the secondary
 * button's shape — a tint with darker type, no outline — in the palette's
 * error red rather than the brand green. Sitting beside a green Confirm it
 * reads as the other choice at a glance, without the weight of a filled red
 * button, which would give declining more emphasis than agreeing.
 */
@Composable
fun destructiveButtonColors() =
    network.erth.wallet.ui.vendor.component.EarthButtonDefaults.secondaryColors(
        containerColor = EarthColors.Utility.ErrorRed.utilityError50,
        contentColor = EarthColors.Utility.ErrorRed.utilityError700,
        borderColor = androidx.compose.ui.graphics.Color.Unspecified,
    )

@Composable
fun brandButtonColors() =
    network.erth.wallet.ui.vendor.component.EarthButtonDefaults.primaryColors(
        containerColor = EarthColors.Btns.Brand.btnBrandBg,
        contentColor = EarthColors.Btns.Brand.btnBrandFg,
        disabledContainerColor = EarthColors.Btns.Brand.btnBrandBgDisabled,
        disabledContentColor = EarthColors.Btns.Brand.btnBrandFgDisabled,
    )

/**
 * A keyboard that ends the field rather than extending it.
 *
 * Every input in this app is one line, so the return key should dismiss rather
 * than insert. Pairs with [dismissKeyboardOnTap] on the screen behind it: a
 * Done key covers the deliberate exit, tapping away covers the rest, and
 * without both the keyboard can only be closed with the system back gesture.
 */
@Composable
fun doneKeyboard(
    keyboardType: KeyboardType = KeyboardType.Text,
    autoCorrect: Boolean = true,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Sentences,
): Pair<KeyboardOptions, KeyboardActions> {
    val focus = LocalFocusManager.current
    return KeyboardOptions(
        keyboardType = keyboardType,
        imeAction = ImeAction.Done,
        autoCorrectEnabled = autoCorrect,
        capitalization = capitalization,
    ) to KeyboardActions(onDone = { focus.clearFocus() })
}

/**
 * Tapping anywhere on this surface lets go of whatever field has focus.
 *
 * No ripple and no accessibility node — this is not a control, it is the
 * absence of one. A field that keeps its cursor and its keyboard after you have
 * tapped somewhere else reads as stuck, and on a sheet the keyboard can end up
 * covering the button you were reaching for.
 */
@Composable
fun Modifier.dismissKeyboardOnTap(): Modifier {
    val focus = LocalFocusManager.current
    return pointerInput(Unit) {
        detectTapGestures(onTap = { focus.clearFocus() })
    }
}
