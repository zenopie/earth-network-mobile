package network.erth.wallet.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import network.erth.wallet.ui.vendor.theme.dimensions.EarthDimensions
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import java.math.BigInteger
import network.erth.wallet.chain.Dex
import network.erth.wallet.ui.theme.EarthAccent
import network.erth.wallet.ui.theme.EarthTheme
import network.erth.wallet.ui.vendor.component.EarthButton
import network.erth.wallet.ui.vendor.component.EarthTextField
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography

/** What the sheet is being opened to do. */
enum class LiquidityAction { Add, Remove }

/**
 * Add to a pool, or start withdrawing from it.
 *
 * One sheet for both, because they are the same shape — an amount, what it
 * converts to, a confirm — and the halves that differ are small enough to read
 * side by side.
 *
 * Adding takes one amount and derives the other. The chain pulls both sides in
 * the pool's exact ratio regardless of what it is sent, so entering the pair
 * independently would let someone type a number that is silently ignored; the
 * derived figure is what will actually be taken.
 */
@Composable
fun LiquiditySheet(
    action: LiquidityAction,
    pool: Dex.Pool,
    /** Spendable balances in base units, ERTH already net of the fee. */
    erthAvailable: Long,
    tokenAvailable: Long,
    shareBalance: Long,
    /** Escrow period before withdrawn shares pay out, in seconds. */
    unbondingSeconds: Long,
    onConfirm: (erthUerth: BigInteger, tokenUnits: BigInteger, shares: BigInteger) -> Unit,
    onDismiss: () -> Unit,
) {
    val dimens = EarthTheme.dimens
    val token = pool.tokenDenom.removePrefix("u").uppercase()
    val erthReserve = pool.erthReserve.toBigIntegerOrNull() ?: BigInteger.ZERO
    val tokenReserve = pool.tokenReserve.toBigIntegerOrNull() ?: BigInteger.ZERO

    var amount by remember { mutableStateOf("") }
    val entered = amount.toBaseUnits() ?: BigInteger.ZERO
    val keys = doneKeyboard(keyboardType = KeyboardType.Decimal)

    EarthSheet(onDismiss = onDismiss) {
        Text(
            text = if (action == LiquidityAction.Add) "Add liquidity" else "Withdraw liquidity",
            style = EarthTypography.header5,
            color = EarthColors.Text.textPrimary,
        )
        Spacer(Modifier.height(dimens.space4))
        Text(
            text = "ERTH · $token",
            style = EarthTypography.textSm,
            color = EarthColors.Text.textTertiary,
        )

        Spacer(Modifier.height(dimens.space16))

        if (action == LiquidityAction.Add) {
            // Two stacked panels, as on swap. Both sides go in together, so
            // showing them one above the other says that better than two
            // labelled fields in a column would.
            //
            // Either side can be typed and the other follows from the pool's
            // ratio. Which one was touched last is tracked so the derivation
            // only ever runs outward — deriving both directions at once feeds
            // each field its own rounded output and the numbers walk.
            var erthText by remember { mutableStateOf("") }
            var tokenText by remember { mutableStateOf("") }

            fun setErth(raw: String) {
                erthText = raw.asAmountInput(erthText)
                val units = erthText.toBaseUnits()
                tokenText = if (units == null || erthReserve.signum() == 0) {
                    ""
                } else {
                    (units * tokenReserve / erthReserve).asDecimalOrBlank()
                }
            }

            fun setToken(raw: String) {
                tokenText = raw.asAmountInput(tokenText)
                val units = tokenText.toBaseUnits()
                erthText = if (units == null || tokenReserve.signum() == 0) {
                    ""
                } else {
                    (units * erthReserve / tokenReserve).asDecimalOrBlank()
                }
            }

            val erthUnits = erthText.toBaseUnits() ?: BigInteger.ZERO
            val tokenUnits = tokenText.toBaseUnits() ?: BigInteger.ZERO
            val overErth = erthUnits > BigInteger.valueOf(erthAvailable)
            val overToken = tokenUnits > BigInteger.valueOf(tokenAvailable)

            DepositPanel(
                label = "ERTH",
                icon = Tokens.iconOf("uerth"),
                balance = erthAvailable,
                value = erthText,
                onValueChange = ::setErth,
                onMax = { setErth(erthAvailable.asDecimalAmount()) },
            )
            Spacer(Modifier.height(dimens.space8))
            DepositPanel(
                label = token,
                icon = Tokens.iconOf(pool.tokenDenom),
                balance = tokenAvailable,
                value = tokenText,
                onValueChange = ::setToken,
                onMax = { setToken(tokenAvailable.asDecimalAmount()) },
            )

            if (overErth || overToken) {
                Spacer(Modifier.height(dimens.space8))
                Text(
                    text = "That is more ${if (overErth) "ERTH" else token} than you " +
                        "hold. Both sides go in together, at the pool's ratio.",
                    style = EarthTypography.textXs,
                    color = EarthColors.Utility.ErrorRed.utilityError700,
                )
            }

            Spacer(Modifier.height(dimens.space16))
            EarthButton(
                text = "Add liquidity",
                onClick = { onConfirm(erthUnits, tokenUnits, BigInteger.ZERO) },
                enabled = erthUnits.signum() > 0 && tokenUnits.signum() > 0 &&
                    !overErth && !overToken,
                modifier = Modifier.fillMaxWidth(),
                colors = brandButtonColors(),
            )
        } else {
            EarthLabel("Shares to withdraw")
            Spacer(Modifier.height(dimens.space8))
            EarthTextField(
                value = amount,
                onValueChange = { amount = it.asAmountInput(amount) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("0") },
                keyboardOptions = keys.first,
                keyboardActions = keys.second,
            )

            Spacer(Modifier.height(dimens.space8))
            AmountShortcuts(available = shareBalance) { amount = it }

            Spacer(Modifier.height(dimens.space16))
            Detail("Your shares", formatUerth(shareBalance))

            Spacer(Modifier.height(dimens.space12))
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(EarthAccent.tint, RoundedCornerShape(dimens.space12))
                    .padding(dimens.space12),
            ) {
                Text(
                    text = "This starts a ${unbondingSeconds.asDays()} wait",
                    style = EarthTypography.textSm,
                    color = EarthColors.Text.textPrimary,
                )
                Spacer(Modifier.height(dimens.space4))
                // Not a detail to bury: the payout is priced when it matures,
                // not now, and the shares keep earning and keep their exposure
                // to the pool the whole time. Someone withdrawing to escape a
                // price move is not escaping it.
                Text(
                    text = "Your shares stay in the pool until then — still " +
                        "earning, and still exposed to the price. The payout " +
                        "is worked out when it matures, not now, and arrives " +
                        "on its own with nothing more to sign.",
                    style = EarthTypography.textXs,
                    color = EarthColors.Text.textSecondary,
                )
            }

            Spacer(Modifier.height(dimens.space16))
            EarthButton(
                text = "Start withdrawal",
                onClick = { onConfirm(BigInteger.ZERO, BigInteger.ZERO, entered) },
                enabled = entered.signum() > 0 &&
                    entered <= BigInteger.valueOf(shareBalance),
                modifier = Modifier.fillMaxWidth(),
                colors = brandButtonColors(),
            )
        }
        Spacer(Modifier.height(dimens.space16))
    }
}

@Composable
private fun AmountShortcuts(available: Long, onPick: (String) -> Unit) {
    if (available <= 0) return
    val dimens = EarthTheme.dimens
    Row(horizontalArrangement = Arrangement.spacedBy(dimens.space8)) {
        Chip("50%") { onPick((available / 2).asDecimalAmount()) }
        Chip("Max") { onPick(available.asDecimalAmount()) }
    }
}

@Composable
private fun Chip(label: String, onClick: () -> Unit) {
    val dimens = EarthTheme.dimens
    Text(
        text = label,
        style = EarthTypography.textXs,
        fontWeight = FontWeight.SemiBold,
        color = EarthColors.Btns.Secondary.btnSecondaryFg,
        modifier = Modifier
            .clip(RoundedCornerShape(dimens.space20))
            .background(EarthColors.Btns.Secondary.btnSecondaryBg)
            .clickable(onClick = onClick)
            .padding(horizontal = dimens.space12, vertical = dimens.space4),
    )
}

/** Base units to a plain decimal, for filling the field from a shortcut. */
private fun Long.asDecimalAmount(): String =
    java.math.BigDecimal(this).movePointLeft(6).stripTrailingZeros().toPlainString()

@Composable
private fun Detail(label: String, value: String) {
    val dimens = EarthTheme.dimens
    Row(Modifier.fillMaxWidth().padding(vertical = dimens.space2)) {
        Text(
            text = label,
            style = EarthTypography.textSm,
            color = EarthColors.Text.textTertiary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = EarthTypography.textSm,
            color = EarthColors.Text.textPrimary,
        )
    }
}

/** "7 days", for an escrow period the chain states in seconds. */
private fun Long.asDays(): String {
    val days = this / 86_400
    return when {
        days >= 2 -> "$days-day"
        days == 1L -> "1-day"
        else -> "${this / 3_600}-hour"
    }
}

private fun String.toBigIntegerOrNull(): BigInteger? =
    runCatching { BigInteger(this) }.getOrNull()

/**
 * One side of a deposit: a mark, an amount, and what is available.
 *
 * Same shape as the swap panels, because it is the same act — an amount of a
 * named token — and two different treatments for that would be two things to
 * learn.
 */
@Composable
private fun DepositPanel(
    label: String,
    icon: Int,
    balance: Long,
    value: String,
    onValueChange: (String) -> Unit,
    onMax: () -> Unit,
) {
    val dimens = EarthTheme.dimens
    val keys = doneKeyboard(keyboardType = KeyboardType.Decimal)

    Column(
        Modifier
            .fillMaxWidth()
            .background(
                EarthColors.Surfaces.bgSecondary,
                RoundedCornerShape(EarthDimensions.Radius.radius3xl),
            )
            .padding(dimens.space16),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            EarthLabel("Deposit")
            Spacer(Modifier.weight(1f))
            Text(
                text = "Balance ${formatUerth(balance)}",
                style = EarthTypography.textXs,
                color = EarthColors.Text.textTertiary,
            )
        }
        Spacer(Modifier.height(dimens.space8))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                EarthTextField(
                    value = value,
                    onValueChange = onValueChange,
                    placeholder = { Text("0") },
                    keyboardOptions = keys.first,
                    keyboardActions = keys.second,
                )
            }
            Spacer(Modifier.width(dimens.space12))
            Image(
                modifier = Modifier.size(dimens.space24),
                painter = painterResource(icon),
                contentDescription = null,
            )
            Spacer(Modifier.width(dimens.space8))
            Text(
                text = label,
                style = EarthTypography.textMd,
                fontWeight = FontWeight.SemiBold,
                color = EarthColors.Text.textPrimary,
            )
        }
        if (balance > 0) {
            Spacer(Modifier.height(dimens.space8))
            Chip("Max", onMax)
        }
    }
}

/** Base units to a decimal, or blank at zero — a derived "0" reads as an error. */
private fun BigInteger.asDecimalOrBlank(): String =
    if (signum() <= 0) "" else fromBaseUnits()
