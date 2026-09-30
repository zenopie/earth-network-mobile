/*
 * Vendored from Zodl (https://github.com/zodl-inc/zodl-android)
 * Copyright (c) 2024 Electric Coin Company. Licensed under the MIT License.
 *
 * Adapted for Earth: the Zatoshi and FiatCurrency members are removed, since
 * they carry Zcash money types. The abstraction — a string that may be a
 * resource id, a literal or a formatted number, resolved at render time — is
 * what 38 of their components depend on, and is why this file is here.
 */
@file:Suppress("TooManyFunctions")

package network.erth.wallet.ui.designsystem.util

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.platform.LocalContext
import network.erth.wallet.R
import network.erth.wallet.ui.designsystem.theme.balances.LocalBalancesAvailable
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.DateFormat
import java.time.YearMonth
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

sealed interface StringResource {
    data class ByResource(
        @param:StringRes val resource: Int,
        val args: List<Any>
    ) : StringResource

    @JvmInline
    value class ByString(
        val value: String
    ) : StringResource

    data class ByDateTime(
        val zonedDateTime: ZonedDateTime,
        val useFullFormat: Boolean
    ) : StringResource

    data class ByYearMonth(
        val yearMonth: YearMonth
    ) : StringResource

    data class ByTransactionId(
        val transactionId: String,
        val abbreviated: Boolean
    ) : StringResource

    data class ByAddress(
        val address: String,
        val ellipsize: Ellipsize
    ) : StringResource

    data class ByCurrencyNumber(
        val amount: Number,
        val ticker: String,
        val tickerLocation: TickerLocation,
        val minDecimals: Int,
        val maxDecimals: Int?,
        val includeGroupingSeparator: Boolean
    ) : StringResource

    data class ByDynamicCurrencyNumber(
        val amount: Number,
        val ticker: String,
        val includeGroupingSeparator: Boolean,
        val tickerLocation: TickerLocation
    ) : StringResource

    data class ByNumber(
        val number: Number,
        val minDecimals: Int,
        val maxDecimals: Int?,
        val includeGroupingSeparator: Boolean
    ) : StringResource

    data class ByDynamicNumber(
        val number: Number,
        val includeGroupingSeparator: Boolean
    ) : StringResource

    operator fun plus(other: StringResource): StringResource = CompositeStringResource(listOf(this, other))

    operator fun plus(other: String): StringResource = CompositeStringResource(listOf(this, stringRes(other)))

    fun isEmpty(): Boolean = if (this is ByString) value.isEmpty() else false

    companion object {
        /**
         * Locale used for formatting and parsing every monetary/number value across the app (both
         * displayed Texts and editable TextFields). We deliberately force a period decimal separator
         * and comma grouping regardless of the device region/language so amounts render and parse
         * uniformly everywhere. Dates and other locale-sensitive copy keep using the device locale.
         *
         * See MOB-1356 (Android regression: en-ZA showed a comma) and MOB-1394 (force periods everywhere).
         */
        val NUMBER_FORMAT_LOCALE: Locale = Locale.US
    }
}

private data class CompositeStringResource(
    val resources: List<StringResource>
) : StringResource

private data class PrivacySensitiveResource(
    val value: StringResource,
    val hiddenValue: StringResource = stringRes(R.string.general_hideBalancesMost)
) : StringResource

@Stable
fun stringRes(
    @StringRes resource: Int,
    vararg args: Any
): StringResource =
    StringResource.ByResource(resource, args.toList())

@Stable
fun stringRes(value: String): StringResource =
    StringResource.ByString(value)

@Stable
fun stringRes(yearMonth: YearMonth): StringResource =
    StringResource.ByYearMonth(yearMonth)

@Stable
@Composable
fun StringResource.getValue(): String =
    getString(
        StringContext(
            context = LocalContext.current,
            locale = rememberDesiredFormatLocale(),
            isHideBalances = LocalBalancesAvailable.current.not()
        )
    )

data class StringContext(
    val context: Context,
    val locale: Locale,
    val isHideBalances: Boolean
)

fun StringResource.getString(
    context: Context,
    locale: Locale = context.resources.configuration.getPreferredLocale(),
    isHideBalances: Boolean = false,
) = getString(
    StringContext(
        context = context,
        locale = locale,
        isHideBalances = isHideBalances,
    )
)

@Suppress("CyclomaticComplexMethod")
fun StringResource.getString(
    context: StringContext
): String {
    val string =
        when (this) {
            is StringResource.ByResource -> convertResource(context)
            is StringResource.ByString -> value
            is StringResource.ByCurrencyNumber -> convertCurrencyNumber()
            is StringResource.ByDynamicCurrencyNumber -> convertDynamicCurrencyNumber()
            is StringResource.ByDateTime -> convertDateTime(context)
            is StringResource.ByYearMonth -> convertYearMonth(context)
            is StringResource.ByAddress -> convertAddress()
            is StringResource.ByTransactionId -> convertTransactionId()
            is StringResource.ByNumber -> convertNumber()
            is StringResource.ByDynamicNumber -> convertDynamicNumber()
            is CompositeStringResource -> convertComposite(context)
            is PrivacySensitiveResource -> convertPrivacySensitive(context)
        }
    return string
}

private fun PrivacySensitiveResource.convertPrivacySensitive(context: StringContext) =
    if (context.isHideBalances) {
        hiddenValue.getString(context)
    } else {
        value.getString(context)
    }

private fun CompositeStringResource.convertComposite(
    context: StringContext
) = this.resources.joinToString(separator = "") { it.getString(context) }

@Suppress("SpreadOperator")
private fun StringResource.ByResource.convertResource(context: StringContext) =
    context.context.getString(
        resource,
        *args.map { if (it is StringResource) it.getString(context) else it }.toTypedArray()
    )

private fun StringResource.ByNumber.convertNumber(): String =
    convertNumberToString(number, minDecimals, maxDecimals, includeGroupingSeparator)

private fun StringResource.ByCurrencyNumber.convertCurrencyNumber(): String {
    val amount = convertNumberToString(amount, minDecimals, maxDecimals, includeGroupingSeparator)
    return when (this.tickerLocation) {
        TickerLocation.BEFORE -> "$ticker$amount"
        TickerLocation.AFTER -> "$amount $ticker"
        TickerLocation.HIDDEN -> amount
    }
}

private fun convertNumberToString(
    amount: Number,
    minDecimals: Int,
    maxDecimals: Int?,
    includeGroupingSeparator: Boolean
): String {
    val bigDecimalAmount = amount.toBigDecimal().stripTrailingZeros()
    val maxFractionDigits = maxDecimals ?: bigDecimalAmount.scale().coerceAtLeast(minDecimals)
    val formatter =
        currencyFormatter(
            maximumFractionDigits = maxFractionDigits,
            minimumFractionDigits = minDecimals,
        ).apply {
            roundingMode = RoundingMode.HALF_EVEN
            minimumIntegerDigits = 1
            isGroupingUsed = includeGroupingSeparator
        }
    return formatter.format(bigDecimalAmount)
}

private fun StringResource.ByDynamicCurrencyNumber.convertDynamicCurrencyNumber(): String {
    val amount = convertDynamicNumberToString(amount, includeGroupingSeparator)
    return when (this.tickerLocation) {
        TickerLocation.BEFORE -> "$ticker$amount"
        TickerLocation.AFTER -> "$amount $ticker"
        TickerLocation.HIDDEN -> amount
    }
}

private fun StringResource.ByDynamicNumber.convertDynamicNumber(): String =
    convertDynamicNumberToString(number, includeGroupingSeparator)

private fun convertDynamicNumberToString(
    number: Number,
    includeGroupingSeparator: Boolean
): String {
    val bigDecimalAmount = number.toBigDecimal().stripTrailingZeros()
    val dynamicAmount = bigDecimalAmount.stripFractionsDynamically()
    val maxDecimals = if (bigDecimalAmount.scale() > 0) bigDecimalAmount.scale() else 0
    val formatter =
        currencyFormatter(
            minimumFractionDigits = 2,
            maximumFractionDigits = maxDecimals.coerceAtLeast(2)
        ).apply {
            roundingMode = RoundingMode.DOWN
            minimumIntegerDigits = 1
            isGroupingUsed = includeGroupingSeparator
        }
    return formatter.format(dynamicAmount)
}

private fun Number.toBigDecimal() =
    when (this) {
        is BigDecimal -> this
        is Int -> BigDecimal(this)
        is Long -> BigDecimal(this)
        is Float -> BigDecimal(this.toDouble())
        is Double -> BigDecimal(this)
        is Short -> BigDecimal(this.toInt())
        else -> BigDecimal(this.toDouble())
    }

private fun StringResource.ByDateTime.convertDateTime(context: StringContext): String {
    if (useFullFormat) {
        return DateFormat
            .getDateTimeInstance(
                DateFormat.MEDIUM,
                DateFormat.SHORT,
                context.locale
            ).format(
                Date.from(zonedDateTime.toInstant())
            )
    } else {
        val pattern = DateTimeFormatter.ofPattern("MMM dd", context.locale)
        val start = zonedDateTime.format(pattern).orEmpty()
        val end =
            DateFormat
                .getTimeInstance(DateFormat.SHORT, context.locale)
                .format(Date.from(zonedDateTime.toInstant()))

        return "$start $end"
    }
}

private fun StringResource.ByYearMonth.convertYearMonth(context: StringContext): String {
    val pattern = DateTimeFormatter.ofPattern("MMMM yyyy", context.locale)
    return yearMonth.format(pattern).orEmpty()
}

private fun StringResource.ByAddress.convertAddress(): String =
    when (ellipsize) {
        Ellipsize.MIDDLE if address.length > ADDRESS_MAX_LENGTH_ABBREVIATED -> {
            address.ellipsizeMiddle(ADDRESS_MAX_LENGTH_ABBREVIATED)
        }

        Ellipsize.END if address.length > ADDRESS_MAX_LENGTH_ABBREVIATED -> {
            address.ellipsizeEnd(ADDRESS_MAX_LENGTH_ABBREVIATED)
        }

        else -> {
            address
        }
    }

private fun String.ellipsizeMiddle(size: Int): String {
    val half = size / 2
    if (this.length <= size) return this
    return "${this.take(half)}$DOTS${this.takeLast(half)}"
}

private fun String.ellipsizeEnd(size: Int) = "${this.take(size)}$DOTS"

private fun StringResource.ByTransactionId.convertTransactionId(): String =
    if (abbreviated) transactionId.ellipsizeMiddle(TRANSACTION_MAX_LENGTH_ABBREVIATED) else transactionId

private const val DOTS = "..."

private const val TRANSACTION_MAX_LENGTH_ABBREVIATED = 10

private const val ADDRESS_MAX_LENGTH_ABBREVIATED = 20

enum class TickerLocation { BEFORE, AFTER, HIDDEN }

@Suppress("ReturnCount", "MagicNumber")
private fun BigDecimal.stripFractionsDynamically(): BigDecimal {
    val tolerance = BigDecimal(".005")
    val minDecimals = 2
    val maxDecimals = 8

    val original = this.stripTrailingZeros()
    val originalScale = original.scale()
    if (originalScale <= minDecimals) return original.setScale(maxDecimals, RoundingMode.HALF_EVEN)

    for (scale in minDecimals..maxDecimals) {
        val rounded = original.setScale(scale, RoundingMode.HALF_EVEN)

        val diff =
            original
                .minus(rounded)
                .divide(original, MathContext.DECIMAL128)
                .abs(MathContext.DECIMAL128)

        if (diff <= tolerance) return rounded
    }

    return original.setScale(maxDecimals, RoundingMode.HALF_EVEN)
}

enum class Ellipsize {
    NONE,
    MIDDLE,
    END
}
