package network.erth.wallet.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import network.erth.wallet.ui.theme.EarthTheme
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography

/** A transaction, resolved for display. */
data class ActivityRow(
    val txHash: String,
    val kind: ActivityKind,
    val counterparty: String,
    val amount: String,
    val timestamp: String,
    val failed: Boolean = false,
    val onClick: () -> Unit = {},
)

enum class ActivityKind { Sent, Received, Staked, Unstaked, Claimed, Registered, Swapped, Allocated }

/**
 * Transaction history.
 *
 * Adapted from their ActivityHistoryView: an icon carrying the kind, the
 * counterparty and time stacked beside it, and the signed amount on the right.
 * Direction is in the sign and the word, never in colour alone — a red number
 * is invisible to a chunk of users and in a screenshot sent for help.
 */
@Composable
fun ActivityScreen(
    rows: List<ActivityRow>,
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
) {
    val dimens = EarthTheme.dimens
    Column(
        modifier
            .fillMaxSize()
            .background(EarthColors.Surfaces.bgPrimary)
            .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier),
    ) {
        Text(
            text = "Activity",
            style = EarthTypography.header5.copy(color = EarthColors.Text.textPrimary),
            modifier = Modifier.padding(dimens.gutter),
        )
        if (rows.isEmpty()) {
            Spacer(Modifier.height(dimens.space32))
            Text(
                text = "Nothing yet. Transactions appear here once they are confirmed on chain.",
                style = EarthTypography.textSm.copy(color = EarthColors.Text.textTertiary),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = dimens.space32),
            )
        }
        rows.forEach { ActivityItem(it) }
    }
}

@Composable
internal fun ActivityItem(row: ActivityRow) {
    val dimens = EarthTheme.dimens
    val (glyph, tint, bg) = when (row.kind) {
        ActivityKind.Sent -> Triple("↑", EarthColors.Text.textPrimary, EarthColors.Surfaces.bgSecondary)
        ActivityKind.Received -> Triple("↓", EarthTheme.domain.stakingFg, EarthTheme.domain.stakingBg)
        ActivityKind.Staked -> Triple("▲", EarthTheme.domain.stakingFg, EarthTheme.domain.stakingBg)
        ActivityKind.Unstaked -> Triple("▼", EarthTheme.domain.stakingFg, EarthTheme.domain.stakingBg)
        ActivityKind.Claimed -> Triple("✦", EarthTheme.domain.stakingFg, EarthTheme.domain.stakingBg)
        ActivityKind.Registered -> Triple("✓", EarthTheme.domain.anmlFg, EarthTheme.domain.anmlBg)
        ActivityKind.Swapped -> Triple("⇄", EarthTheme.domain.dexFg, EarthTheme.domain.dexBg)
        ActivityKind.Allocated -> Triple("◴", EarthTheme.domain.governanceFg, EarthTheme.domain.governanceBg)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = row.onClick)
            .padding(horizontal = dimens.gutter, vertical = dimens.space12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(dimens.space32)
                .background(bg, RoundedCornerShape(dimens.radiusMd)),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = glyph, style = EarthTypography.textSm.copy(color = tint))
        }
        Column(Modifier.weight(1f).padding(start = dimens.space12)) {
            Text(
                text = row.kind.name + (if (row.failed) " · failed" else ""),
                style = EarthTypography.textMd.copy(
                    color = if (row.failed) {
                        EarthColors.Utility.ErrorRed.utilityError700
                    } else {
                        EarthColors.Text.textPrimary
                    },
                ),
            )
            Text(
                text = "${row.counterparty} · ${row.timestamp}",
                style = EarthTypography.textSm.copy(color = EarthColors.Text.textTertiary),
            )
        }
        Text(
            text = row.amount,
            style = EarthTypography.textMd.copy(color = EarthColors.Text.textPrimary),
        )
    }
}
