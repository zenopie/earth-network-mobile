package network.erth.wallet.ui.compose

import network.erth.wallet.ui.vendor.component.EarthButton
import network.erth.wallet.ui.vendor.component.EarthButtonDefaults
import network.erth.wallet.ui.vendor.component.EarthCard
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import network.erth.wallet.ui.theme.EarthTheme

/** What happened. Success carries a hash; failure carries the whole error. */
sealed interface TxOutcome {
    data class Success(val action: String, val txHash: String) : TxOutcome

    data class Failure(val action: String, val error: Throwable?) : TxOutcome

    data class Message(val title: String, val detail: String) : TxOutcome
}

/**
 * The result sheet.
 *
 * The Compose successor to the Toast that used to carry
 * `"Failed: ${e.message}"` — one truncated line for two seconds, which is the
 * worst possible treatment of a chain error. Those messages are long, they are
 * usually the only explanation of what happened, and
 * "out of gas in location: ReadFlat; gasWanted: 400000, gasUsed: 400324"
 * diagnoses a problem in one read and is useless at forty characters.
 */
@Composable
fun TxResultSheet(outcome: TxOutcome, onDismiss: () -> Unit) {
    val colors = EarthTheme.colors
    val dimens = EarthTheme.dimens
    val clipboard = LocalClipboardManager.current

    val (glyph, badgeBg, badgeFg, title, detail) =
        when (outcome) {
            is TxOutcome.Success ->
                Quint(
                    "✓", EarthColors.Utility.SuccessGreen.utilitySuccess50, EarthColors.Utility.SuccessGreen.utilitySuccess700,
                    "${outcome.action} confirmed", "Transaction hash\n${outcome.txHash}",
                )
            is TxOutcome.Failure ->
                Quint(
                    "✕", EarthColors.Utility.ErrorRed.utilityError50, EarthColors.Utility.ErrorRed.utilityError700,
                    "${outcome.action} failed", describe(outcome.error),
                )
            is TxOutcome.Message ->
                Quint(
                    "!", EarthColors.Utility.Gray.utilityGray100, EarthColors.Utility.Gray.utilityGray700,
                    outcome.title, outcome.detail,
                )
        }

    EarthSheet(onDismiss = onDismiss) {
        Box(Modifier.fillMaxWidth(), Alignment.Center) {
            Box(
                Modifier.size(dimens.space48).background(badgeBg, CircleShape),
                Alignment.Center,
            ) {
                Text(glyph, style = EarthTypography.header5, color = badgeFg)
            }
        }
        Text(
            text = title,
            style = EarthTypography.header5,
            color = EarthColors.Text.textPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Box(Modifier.padding(top = dimens.space8)) { EarthCodeBlock(detail) }
        Row(Modifier.fillMaxWidth().padding(top = dimens.space16)) {
            Box(Modifier.weight(1f)) {
                EarthButton(
                    text = "Copy",
                    colors = EarthButtonDefaults.secondaryColors(),
                    onClick = { clipboard.setText(AnnotatedString("$title\n\n$detail")) },
                )
            }
            Box(Modifier.size(dimens.space12))
            Box(Modifier.weight(1f)) {
                EarthButton(text = "Done", onClick = onDismiss,
            colors = brandButtonColors(),
        )
            }
        }
    }
}

private data class Quint(
    val glyph: String,
    val badgeBg: androidx.compose.ui.graphics.Color,
    val badgeFg: androidx.compose.ui.graphics.Color,
    val title: String,
    val detail: String,
)

/**
 * The full text of a failure, cause chain included.
 *
 * Cosmos errors arrive wrapped and the useful part is usually the innermost
 * message, so unwrapping matters more than tidiness.
 */
private fun describe(error: Throwable?): String {
    if (error == null) return "No further detail was reported."
    val parts = mutableListOf<String>()
    var current: Throwable? = error
    val seen = mutableSetOf<Throwable>()
    while (current != null && seen.add(current)) {
        val msg = current.message?.takeIf { it.isNotBlank() } ?: current::class.java.simpleName
        if (parts.isEmpty() || parts.last() != msg) parts.add(msg)
        current = current.cause
    }
    return parts.joinToString("\n\ncaused by:\n")
}
