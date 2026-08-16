package network.erth.wallet.ui.pages.explorer

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Display helpers shared by the explorer tabs and detail screen. */
object ExplorerFormat {

    /** Middle-elided identifier, e.g. "A1B2C3…9F8E". */
    fun short(value: String, head: Int = 8, tail: Int = 6): String =
        if (value.length <= head + tail + 1) value
        else value.take(head) + "…" + value.takeLast(tail)

    fun thousands(n: Long): String = String.format(Locale.US, "%,d", n)

    /**
     * Relative age of an RFC3339 timestamp, e.g. "5s ago".
     *
     * The LCD emits nanosecond precision ("2026-08-15T21:00:00.123456789Z"),
     * which SimpleDateFormat cannot parse, so the fractional seconds are cut
     * before parsing rather than tried and caught.
     */
    fun ago(rfc3339: String): String {
        val millis = epochMillis(rfc3339) ?: return ""
        val seconds = ((System.currentTimeMillis() - millis) / 1000).coerceAtLeast(0)
        return when {
            seconds < 60 -> "${seconds}s ago"
            seconds < 3600 -> "${seconds / 60}m ago"
            seconds < 86400 -> "${seconds / 3600}h ago"
            else -> "${seconds / 86400}d ago"
        }
    }

    /** Local wall-clock rendering of an RFC3339 timestamp. */
    fun dateTime(rfc3339: String): String {
        val millis = epochMillis(rfc3339) ?: return rfc3339
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(java.util.Date(millis))
    }

    private fun epochMillis(rfc3339: String): Long? {
        if (rfc3339.length < 19) return null
        // "yyyy-MM-ddTHH:mm:ss" is exactly 19 characters; everything after it is
        // the fraction and the zone, both of which SimpleDateFormat would choke
        // on. The LCD always emits UTC, so the zone carries no information.
        val trimmed = rfc3339.take(19)
        return try {
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            fmt.parse(trimmed)?.time
        } catch (e: Exception) {
            null
        }
    }

    /**
     * "🇩🇪 Germany" from an ISO 3166-1 alpha-2 code.
     *
     * The chain records "" when the Document Signer's certificate carries no
     * country attribute, which is a real and expected case — the passport is
     * valid, the issuer just didn't populate the field — so it gets its own
     * label rather than being dropped from the list.
     */
    fun countryLabel(code: String): String {
        if (code.isBlank()) return "Unknown issuer"
        val upper = code.uppercase()
        val name = Locale("", upper).displayCountry
        val flag = flagEmoji(upper)
        return if (name.equals(upper, ignoreCase = true)) "$flag $upper" else "$flag $name"
    }

    /** Regional-indicator pair for an alpha-2 code; empty for anything else. */
    private fun flagEmoji(code: String): String {
        if (code.length != 2 || !code.all { it in 'A'..'Z' }) return ""
        val base = 0x1F1E6 - 'A'.code
        return String(Character.toChars(base + code[0].code)) +
            String(Character.toChars(base + code[1].code))
    }
}
