package network.erth.wallet.ui.tx

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import network.erth.wallet.ui.components.EarthCodeBlock
import network.erth.wallet.ui.components.EarthSheet
import network.erth.wallet.ui.components.OrbitLoader
import network.erth.wallet.ui.components.brandButtonColors
import network.erth.wallet.ui.designsystem.component.EarthButton
import network.erth.wallet.ui.designsystem.component.EarthButtonDefaults
import network.erth.wallet.ui.designsystem.theme.colors.EarthColors
import network.erth.wallet.ui.designsystem.theme.typography.EarthTypography
import network.erth.wallet.ui.theme.EarthTheme

/** What happened. Success carries a hash; failure carries the whole error. */
sealed interface TxOutcome {
    data class Success(val action: String, val txHash: String) : TxOutcome

    data class Failure(val action: String, val error: Throwable?) : TxOutcome

    /** Broadcast, but not seen in a block before the wait ran out. */
    data class Pending(val action: String, val txHash: String) : TxOutcome

    data class Message(val title: String, val detail: String) : TxOutcome
}

/**
 * The result sheet.
 *
 * A sheet rather than a toast: a chain error is long, it is usually the
 * only explanation of what happened, and
 * "out of gas in location: ReadFlat; gasWanted: 400000, gasUsed: 400324"
 * diagnoses a problem in one read and is useless truncated to one line.
 */
@Composable
fun TxResultSheet(outcome: TxOutcome, onDismiss: () -> Unit) {
    val colors = EarthTheme.colors
    val dimens = EarthTheme.dimens
    val context = LocalContext.current

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
            is TxOutcome.Pending ->
                Quint(
                    "…", EarthColors.Utility.Gray.utilityGray100, EarthColors.Utility.Gray.utilityGray700,
                    "${outcome.action} not confirmed yet",
                    "It was sent but has not appeared in a block yet, so it may " +
                        "still succeed or fail. Check Activity before trying again.\n\n" +
                        "Transaction hash\n${outcome.txHash}",
                )
            is TxOutcome.Message ->
                Quint(
                    "!", EarthColors.Utility.Gray.utilityGray100, EarthColors.Utility.Gray.utilityGray700,
                    outcome.title, outcome.detail,
                )
        }

    // The badge pops in: half size, past full, then settles — 0.5 to 1.1 over
    // 200ms, back to 1.0 over 150ms, accelerate-decelerate throughout.
    //
    // The glyph inside is deliberately not animated: a check that draws
    // itself competes with the circle for the same moment of attention, and
    // the circle is what carries the arrival.
    val pop = remember { Animatable(BADGE_START_SCALE) }
    LaunchedEffect(outcome) {
        pop.snapTo(BADGE_START_SCALE)
        pop.animateTo(
            targetValue = BADGE_SETTLED_SCALE,
            animationSpec = keyframes {
                durationMillis = BADGE_RISE_MS + BADGE_SETTLE_MS
                BADGE_START_SCALE at 0 using FastOutSlowInEasing
                BADGE_OVERSHOOT_SCALE at BADGE_RISE_MS using FastOutSlowInEasing
                BADGE_SETTLED_SCALE at BADGE_RISE_MS + BADGE_SETTLE_MS
            },
        )
    }

    EarthSheet(onDismiss = onDismiss) {
        Box(Modifier.fillMaxWidth(), Alignment.Center) {
            Box(
                Modifier
                    .size(dimens.space48)
                    .scale(pop.value)
                    .background(badgeBg, CircleShape),
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
        Row(
            Modifier.fillMaxWidth().padding(top = dimens.space16),
            horizontalArrangement = Arrangement.spacedBy(dimens.space12),
        ) {
            EarthButton(
                text = "Copy",
                onClick = { network.erth.wallet.ui.components.Clipboard.copy(context, title, "$title\n\n$detail") },
                modifier = Modifier.weight(1f),
                colors = EarthButtonDefaults.secondaryColors(),
            )
            EarthButton(
                text = "Done",
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
                // Green even on a failure. Done dismisses a sheet; it does not
                // undo the transaction, and colouring it red would suggest the
                // failure is still something you can act on.
                colors = brandButtonColors(),
            )
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
    // A refusal the chain explains in a known way leads with that sentence.
    val known = generateSequence(error) { it.cause }.firstNotNullOfOrNull { e ->
        if (e is network.erth.wallet.privacy.tx.UnsignedTx.TxRejected) network.erth.wallet.chain.ChainErrors.explain(e.code, e.codespace, e.message.orEmpty())
        else e.message?.let { network.erth.wallet.chain.ChainErrors.explain(it) }
    }
    val detail = parts.joinToString("\n\ncaused by:\n")
    return if (known != null) "$known\n\n$detail" else detail
}

// The success badge's animation.
private const val BADGE_START_SCALE = 0.5f
private const val BADGE_OVERSHOOT_SCALE = 1.1f
private const val BADGE_SETTLED_SCALE = 1.0f
private const val BADGE_RISE_MS = 200
private const val BADGE_SETTLE_MS = 150

/**
 * The sheet while the transaction is in flight.
 *
 * It exists so the badge's pop reads as an arrival rather than a sheet
 * appearing from nowhere. Broadcasting takes seconds — a registration carries a
 * 3,000,000-gas proof verification — and without this the app shows nothing at
 * all between confirming and the result, which reads as a tap that did not
 * register.
 *
 * Deliberately not dismissible. The transaction is already signed and on its
 * way; a sheet that could be swiped away would imply it could be called back.
 */
@Composable
fun TxPendingSheet(action: String) {
    val dimens = EarthTheme.dimens

    // Not progress: nothing here knows how far along a broadcast is, and a
    // determinate bar that invents a percentage is worse than an honest wait.
    // The web app's orbit loader, so the three clients wait the same way —
    // smaller than its 160, which is drawn across a page rather than into a
    // sheet.
    EarthSheet(onDismiss = {}) {
        Box(Modifier.fillMaxWidth(), Alignment.Center) {
            OrbitLoader(diameter = 120.dp)
        }
        Text(
            text = "Sending",
            style = EarthTypography.header5,
            color = EarthColors.Text.textPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "$action is on its way to the chain. This takes a few seconds.",
            style = EarthTypography.textSm,
            color = EarthColors.Text.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = dimens.space8),
        )
        Box(Modifier.padding(top = dimens.space24))
    }
}
