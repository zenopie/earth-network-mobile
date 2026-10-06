package network.erth.wallet.ui.components

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

// Amounts as people read and type them. Every denom on earth-1 has six
// decimals, so these convert between base units and that one scale.

/** Base units to a decimal with thousands separators, trailing zeros trimmed. */
internal fun formatUerth(micro: Long): String {
    val whole = micro / 1_000_000
    val frac = (micro % 1_000_000).toString().padStart(6, '0').trimEnd('0')
    return if (frac.isEmpty()) "%,d".format(whole) else "%,d.%s".format(whole, frac)
}

/** [formatUerth] with the ERTH symbol. */
internal fun formatErth(uerth: Long): String = "${formatUerth(uerth)} ERTH"

/** "earth1jtc…aar6" — enough to recognise an address you know, short enough for a row. */
internal fun String.shortAddress(): String =
    if (length <= 16) this else "${take(10)}…${takeLast(4)}"

/** ERTH as typed, to uerth. Null when it is not a number. */
internal fun String.toUerthOrNull(): Long? =
    runCatching { BigDecimal(this).movePointRight(6).toLong() }.getOrNull()

/**
 * Keep only what can be part of a decimal number, and only one point.
 *
 * A filter rather than validation-after-the-fact: the numeric keyboard still
 * offers a comma on many locales and a paste can carry anything at all, so the
 * field has to refuse the character rather than accept it and complain.
 */
internal fun String.asAmountInput(previous: String): String {
    if (isEmpty()) return ""
    val cleaned = buildString {
        var seenPoint = false
        for (c in this@asAmountInput) {
            when {
                c.isDigit() -> append(c)
                (c == '.' || c == ',') && !seenPoint -> {
                    seenPoint = true
                    append('.')
                }
                else -> Unit
            }
        }
    }
    // Six decimals is the denomination's precision; more cannot be sent.
    val frac = cleaned.substringAfter('.', "")
    return if (frac.length > 6) previous else cleaned
}

/** Base units from a typed decimal, or null when it is not a number. */
internal fun String.toBaseUnits(): BigInteger? =
    runCatching { BigDecimal(this).movePointRight(6).setScale(0, RoundingMode.DOWN).toBigInteger() }
        .getOrNull()

/** Base units back to a plain decimal for display. */
internal fun BigInteger.fromBaseUnits(): String =
    BigDecimal(this).movePointLeft(6).stripTrailingZeros().toPlainString()

/** Base units back to a plain decimal, e.g. to fill an amount field. */
internal fun Long.fromBaseUnits(): String = BigInteger.valueOf(this).fromBaseUnits()
