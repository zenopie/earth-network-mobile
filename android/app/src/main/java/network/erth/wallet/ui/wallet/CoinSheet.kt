package network.erth.wallet.ui.wallet

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import network.erth.wallet.R
import network.erth.wallet.privacy.tx.ShieldMove
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import network.erth.wallet.ui.components.EarthSheet
import network.erth.wallet.ui.components.asAmountInput
import network.erth.wallet.ui.components.doneKeyboard
import network.erth.wallet.ui.components.fromBaseUnits
import network.erth.wallet.ui.components.toUerthOrNull
import network.erth.wallet.ui.components.HeroAmount
import network.erth.wallet.ui.components.HeroLine
import network.erth.wallet.ui.earn.PillButton
import network.erth.wallet.ui.components.formatUerth
import network.erth.wallet.ui.designsystem.theme.colors.EarthColors
import network.erth.wallet.ui.designsystem.theme.typography.EarthTypography
import network.erth.wallet.ui.theme.EarthAccent
import network.erth.wallet.ui.theme.EarthTheme

/** What a person calls the coin. */
val ShieldMove.Coin.symbol: String get() = Tokens.symbolOf(denom)

/** One line on what the coin is for, under its symbol. */
val ShieldMove.Coin.role: String
    get() = when {
        denom == "uerth" -> "Pays network fees"
        denom == "uanml" -> "Personhood · always private"
        denom.startsWith("dexlp/") -> "Pool share"
        else -> denom
    }

/** ERTH shows both sides even at zero (the fee comes from the private one); ANML has no public side. */
private val ShieldMove.Coin.showsPrivate get() = private > 0 || denom == "uerth" || denom == "uanml"
private val ShieldMove.Coin.showsPublic get() = public > 0 || denom == "uerth"

/**
 * A coin's own mark: its logo, or for a coin this build has none for, its
 * symbol's first letters on a neutral disc. Neutral rather than the accent:
 * the accent would make an unknown token look like one of Earth's own.
 */
@Composable
fun CoinMark(denom: String, symbol: String, size: Dp, modifier: Modifier = Modifier) {
    val logo = when (denom) {
        "uerth", "uanml" -> Tokens.iconOf(denom)
        else -> null
    }
    if (logo != null) {
        Image(painter = painterResource(logo), contentDescription = null, modifier = modifier.size(size))
    } else {
        Box(
            modifier.size(size).clip(CircleShape).background(EarthColors.Surfaces.bgTertiary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = symbol.filter { it.isLetter() }.take(2).uppercase(),
                style = EarthTypography.textMd.copy(fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.36f).sp),
                color = EarthColors.Text.textSecondary,
            )
        }
    }
}

/**
 * One coin in Portfolio: its mark, what it is, and its private and public
 * amounts on their own lines, private first (it is the default home). The
 * whole row opens the coin's sheet.
 */
@Composable
fun CoinRow(coin: ShieldMove.Coin, visible: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoinMark(coin.denom, coin.symbol, 44.dp)
        Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp)) {
            Text(
                text = coin.symbol,
                style = EarthTypography.textMd.copy(fontWeight = FontWeight.SemiBold),
                color = EarthColors.Text.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = coin.role,
                style = EarthTypography.textXs,
                color = EarthColors.Text.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (coin.showsPrivate) AmountLine(coin.private, locked = true, visible = visible)
            if (coin.showsPublic) AmountLine(coin.public, locked = false, visible = visible)
        }
        Spacer(Modifier.width(8.dp))
        Image(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun AmountLine(amount: Long, locked: Boolean, visible: Boolean) {
    val text = if (visible) formatUerth(amount) else "••••"
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics { contentDescription = (if (locked) "Private " else "Public ") + text },
    ) {
        Text(
            text = text,
            style = if (locked) EarthTypography.textMd else EarthTypography.textSm,
            color = if (locked) EarthColors.Text.textPrimary else EarthColors.Text.textSecondary,
            maxLines = 1,
        )
        Spacer(Modifier.width(5.dp))
        Image(
            painter = painterResource(if (locked) R.drawable.ic_lock else R.drawable.ic_public),
            contentDescription = null,
            modifier = Modifier.size(11.dp),
            colorFilter = ColorFilter.tint(if (locked) EarthAccent.ink else EarthColors.Text.textTertiary),
        )
    }
}

/** Which way ERTH moves between the public account and this wallet's private notes. */
enum class MoveDirection { Shield, Unshield }

/**
 * One coin, laid out like a validator's stake sheet: the private amount big,
 * the public one a line under it, Shield and Unshield. ANML has no public
 * side, so no moves.
 *
 * Shield or Unshield turns the big number into the amount, in place: the
 * move needs only an amount, and a second screen for one field was a detour.
 * Shield is MsgShield, signed by the account (the coins are its), its note to
 * this wallet's own shielded address; Unshield is a private transfer out of
 * the notes to this wallet's own account, its fee from the same notes.
 */
@Composable
fun CoinSheet(
    coin: ShieldMove.Coin,
    visible: Boolean,
    /** What one ERTH unshield can spend (ShieldMove.maxUnshield). */
    unshieldableUerth: Long,
    /** The signed MsgShield's fee. */
    shieldFee: Long,
    /** The private transfer's estimated fee, as its confirm sheet shows it. */
    unshieldFee: Long,
    /** Raises the move's confirmation; the sheet closes. */
    onMove: (MoveDirection, amountUerth: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val anml = coin.denom == "uanml"
    var moving by remember { mutableStateOf<MoveDirection?>(null) }
    var amount by remember { mutableStateOf("") }
    EarthSheet(onDismiss = onDismiss, bottomPadding = 24.dp, fitContent = true) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(8.dp))
            CoinMark(coin.denom, coin.symbol, 56.dp)
            Spacer(Modifier.height(12.dp))
            Text(coin.symbol, style = EarthTypography.textMd.copy(fontSize = 17.sp), fontWeight = FontWeight.SemiBold,
                color = EarthColors.Text.textPrimary, maxLines = 1)
            Spacer(Modifier.height(24.dp))
            val direction = moving
            if (direction != null) {
                MoveAmount(
                    direction = direction,
                    coin = coin,
                    amount = amount,
                    onAmount = { amount = it },
                    cap = if (direction == MoveDirection.Shield) ShieldMove.maxShield(coin.public, shieldFee) else unshieldableUerth,
                    unshieldable = unshieldableUerth,
                    onCancel = { moving = null; amount = "" },
                    onConfirm = { onMove(direction, it) },
                )
                return@Column
            }
            HeroAmount(formatUerth(coin.private), visible)
            HeroLine(if (anml) "Always private" else "Private", color = EarthAccent.ink, icon = R.drawable.ic_lock)
            if (anml) return@Column
            if (coin.showsPublic) {
                HeroLine(
                    "${if (visible) formatUerth(coin.public) else "••••"} public",
                    icon = R.drawable.ic_public,
                )
            }
            Spacer(Modifier.height(32.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(
                    "Shield",
                    enabled = ShieldMove.shieldBlocked(coin.denom, coin.public, shieldFee) == null,
                    modifier = Modifier.weight(1f),
                ) { moving = MoveDirection.Shield }
                PillButton(
                    "Unshield",
                    enabled = ShieldMove.unshieldBlocked(
                        coin.denom, coin.private, if (coin.denom == "uerth") unshieldableUerth else 0L, unshieldFee,
                    ) == null,
                    primary = false,
                    modifier = Modifier.weight(1f),
                ) { moving = MoveDirection.Unshield }
            }
        }
    }
}

/** The amount in the big number's place: the balance it comes out of, Max, then Cancel and the move. */
@Composable
private fun MoveAmount(
    direction: MoveDirection,
    coin: ShieldMove.Coin,
    amount: String,
    onAmount: (String) -> Unit,
    /** The most this can move after its fee. */
    cap: Long,
    unshieldable: Long,
    onCancel: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    val shielding = direction == MoveDirection.Shield
    val keys = doneKeyboard(keyboardType = KeyboardType.Decimal)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val amountUerth = amount.toUerthOrNull()
    val error = when {
        amount.isEmpty() -> null
        amountUerth == null -> "Enter an amount"
        amountUerth <= 0 -> "Enter more than zero"
        amountUerth > cap -> "Max ${formatUerth(cap)} after the fee"
        else -> null
    }
    BasicTextField(
        value = amount,
        onValueChange = { onAmount(it.asAmountInput(amount)) },
        singleLine = true,
        textStyle = EarthTypography.header2.copy(
            fontSize = if (amount.length > 9) 40.sp else 56.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            color = EarthColors.Text.textPrimary,
        ),
        cursorBrush = SolidColor(EarthAccent.ink),
        keyboardOptions = keys.first,
        keyboardActions = keys.second,
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
        decorationBox = { inner ->
            if (amount.isEmpty()) {
                Text(
                    "0",
                    style = EarthTypography.header2.copy(fontSize = 56.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center),
                    color = EarthColors.Text.textTertiary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            inner()
        },
    )
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (shielding) "Public ${formatUerth(coin.public)}" else "Private ${formatUerth(coin.private)}",
            style = EarthTypography.textSm,
            color = EarthColors.Text.textTertiary,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "Max",
            style = EarthTypography.textSm.copy(fontWeight = FontWeight.SemiBold),
            color = EarthAccent.ink,
            modifier = Modifier.clickable { onAmount(cap.fromBaseUnits()) }.padding(4.dp),
        )
    }
    error?.let { Text(it, style = EarthTypography.textSm, color = EarthColors.Utility.ErrorRed.utilityError700) }
    // Only when ERTH is spread over more notes than one bundle carries.
    if (!shielding && unshieldable < coin.private) {
        Text("Merge notes in Settings to move it all", style = EarthTypography.textSm, color = EarthColors.Text.textTertiary)
    }
    Spacer(Modifier.height(24.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PillButton("Cancel", primary = false, modifier = Modifier.weight(1f), onClick = onCancel)
        PillButton(
            if (shielding) "Shield" else "Unshield",
            enabled = amountUerth != null && amountUerth > 0 && error == null,
            modifier = Modifier.weight(1f),
        ) { if (amountUerth != null && error == null) onConfirm(amountUerth) }
    }
}
