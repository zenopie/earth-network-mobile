package network.erth.wallet.ui.wallet

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import network.erth.wallet.R
import network.erth.wallet.privacy.ActivityCoin
import network.erth.wallet.privacy.PrivateActivity
import network.erth.wallet.ui.components.Clipboard
import network.erth.wallet.ui.components.EarthSheet
import network.erth.wallet.ui.designsystem.theme.colors.EarthColors
import network.erth.wallet.ui.designsystem.theme.typography.EarthTypography
import network.erth.wallet.ui.earn.PillButton
import network.erth.wallet.ui.theme.EarthAccent

/** How the activity list reads names and figures: shared by the list and its sheet. */
data class ActivityView(
    val visible: Boolean = true,
    val name: (String) -> String = { it },
    val now: Long = System.currentTimeMillis() / 1000,
)

/** The full activity list (Settings → Activity). The wallet screen shows the same rows. */
@Composable
fun ActivityScreen(
    rows: List<ActivityEntry>,
    view: ActivityView,
    onOpen: (ActivityEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier
            .fillMaxSize()
            .background(EarthColors.Surfaces.bgPrimary),
    ) {
        activityItems(rows, view, onOpen)
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/**
 * The activity list: day headers, then one quiet row per tx (its mark, a
 * short title, who or when, the amount). Everything else is in the row's
 * detail sheet.
 */
fun LazyListScope.activityItems(rows: List<ActivityEntry>, view: ActivityView, onOpen: (ActivityEntry) -> Unit) {
    if (rows.isEmpty()) {
        item {
            Text(
                text = "No activity yet",
                style = EarthTypography.textSm,
                color = EarthColors.Text.textTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
            )
        }
        return
    }
    ActivityEntry.days(rows, view.now).forEach { (title, entries) ->
        item(key = "day:$title") {
            Text(
                text = title,
                style = EarthTypography.textSm,
                fontWeight = FontWeight.SemiBold,
                color = EarthColors.Text.textTertiary,
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp),
            )
        }
        items(entries, key = { it.id }) { ActivityItem(it, view) { onOpen(it) } }
    }
}

/** One tx: mark, title (a tiny lock when private), the counterparty or the time, and the amount. */
@Composable
internal fun ActivityItem(entry: ActivityEntry, view: ActivityView, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActivityMark(entry, 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.title,
                    style = EarthTypography.textMd,
                    fontWeight = FontWeight.SemiBold,
                    color = EarthColors.Text.textPrimary,
                    maxLines = 1,
                )
                if (entry.isPrivate) {
                    Spacer(Modifier.width(5.dp))
                    PrivateLock(11.dp)
                }
            }
            val (text, color) = when (entry.status) {
                ActivityEntry.Status.PENDING -> "Pending" to EarthColors.Utility.WarningYellow.utilityOrange700
                ActivityEntry.Status.FAILED -> "Failed" to EarthColors.Utility.ErrorRed.utilityError700
                ActivityEntry.Status.COMPLETED ->
                    (entry.listParty(view.name) ?: ActivityEntry.clock(entry.time, entry.timeExact)) to EarthColors.Text.textTertiary
            }
            Text(text, style = EarthTypography.textSm, color = color, maxLines = 1)
        }
        entry.primary?.let {
            Spacer(Modifier.width(8.dp))
            ActivityAmount(it, entry.status, 17, view.visible)
        }
    }
}

@Composable
private fun PrivateLock(size: Dp) {
    Image(
        painter = painterResource(R.drawable.ic_lock),
        contentDescription = "Private",
        colorFilter = ColorFilter.tint(EarthColors.Text.textTertiary),
        modifier = Modifier.size(size),
    )
}

/** "+3.25 ANML": the figure in weight, the symbol small. Green when it came in; grey while pending or failed. */
@Composable
private fun ActivityAmount(coin: ActivityCoin, status: ActivityEntry.Status, size: Int, visible: Boolean) {
    val ink = when {
        status != ActivityEntry.Status.COMPLETED -> EarthColors.Text.textTertiary
        coin.amount > 0 -> EarthAccent.ink
        else -> EarthColors.Text.textPrimary
    }
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = if (visible) (if (coin.amount > 0) "+" else "−") + ActivityEntry.figure(coin.amount) else "••••",
            style = EarthTypography.textMd.copy(fontSize = size.sp, lineHeight = (size * 1.2).sp),
            fontWeight = FontWeight.SemiBold,
            color = ink,
            maxLines = 1,
        )
        Spacer(Modifier.width(if (size > 30) 6.dp else 3.dp))
        Text(
            text = PrivateActivity.symbol(coin.denom),
            style = EarthTypography.textXs.copy(fontSize = if (size > 30) 17.sp else 12.sp),
            fontWeight = FontWeight.Medium,
            color = EarthColors.Text.textTertiary,
            modifier = Modifier.padding(bottom = if (size > 30) 8.dp else 2.dp),
        )
    }
}

/** The coin's logo when the row is about moving a coin, else a glyph for what was done. */
@Composable
private fun ActivityMark(entry: ActivityEntry, size: Dp) {
    val failed = entry.status == ActivityEntry.Status.FAILED
    val coin = entry.primary
    if (entry.showsCoin && coin != null) {
        CoinMark(coin.denom, PrivateActivity.symbol(coin.denom), size, Modifier.alpha(if (failed) 0.5f else 1f))
        return
    }
    val ink = if (failed) EarthColors.Text.textTertiary else EarthAccent.ink
    Box(
        Modifier.size(size).background(if (failed) EarthColors.Surfaces.bgTertiary else EarthAccent.tint, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val icon = when (entry.glyph) {
            ActivityEntry.Glyph.SEND -> R.drawable.ic_home_send
            ActivityEntry.Glyph.RECEIVE -> R.drawable.ic_home_receive
            ActivityEntry.Glyph.SWAP -> R.drawable.ic_swap_vertical
            ActivityEntry.Glyph.STAKE -> R.drawable.ic_earnings
            ActivityEntry.Glyph.REGISTER -> R.drawable.ic_shield_check
            ActivityEntry.Glyph.VOTE -> R.drawable.ic_check
            else -> null
        }
        if (icon != null) {
            Image(painterResource(icon), null, colorFilter = ColorFilter.tint(ink), modifier = Modifier.size(size * 0.42f))
        } else {
            Text(
                text = if (entry.glyph == ActivityEntry.Glyph.HANDLE) "@" else "…",
                style = EarthTypography.textMd.copy(fontSize = (size.value * 0.4f).sp),
                fontWeight = FontWeight.Bold,
                color = ink,
            )
        }
    }
}

/**
 * A tx in full: the big amount, the title, its status, then every detail
 * the wallet knows as label and value. A public tx can be opened in the
 * explorer; a private one cannot (opening it would tell the explorer which tx
 * is this wallet's, the lookup the list never makes), but its hash can still
 * be copied.
 */
@Composable
fun ActivityDetailSheet(
    entry: ActivityEntry,
    view: ActivityView,
    onOpenUrl: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(copied) {
        if (copied != null) {
            delay(1_500)
            copied = null
        }
    }
    EarthSheet(onDismiss = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(8.dp))
            ActivityMark(entry, 56.dp)
            Spacer(Modifier.height(16.dp))
            entry.primary?.let {
                ActivityAmount(it, entry.status, 44, view.visible)
                Spacer(Modifier.height(8.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.title, style = EarthTypography.textMd.copy(fontSize = 17.sp), fontWeight = FontWeight.SemiBold,
                    color = EarthColors.Text.textPrimary)
                if (entry.isPrivate) {
                    Spacer(Modifier.width(6.dp))
                    PrivateLock(13.dp)
                }
            }
            Spacer(Modifier.height(12.dp))
            StatusPill(entry)
            Spacer(Modifier.height(24.dp))

            val lines = entry.details(view.name)
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(EarthColors.Surfaces.bgSecondary, RoundedCornerShape(20.dp)),
            ) {
                lines.forEachIndexed { i, d ->
                    val masked = !view.visible && d.label in AMOUNT_LABELS
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .then(
                                if (d.copy != null) {
                                    Modifier.clickable {
                                        Clipboard.copy(context, d.label, d.copy)
                                        copied = d.label
                                    }
                                } else {
                                    Modifier
                                },
                            )
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(d.label, style = EarthTypography.textMd, color = EarthColors.Text.textTertiary)
                        Spacer(Modifier.width(12.dp).weight(1f))
                        Text(
                            text = if (copied == d.label) "Copied" else if (masked) "••••" else d.value,
                            style = if (d.label == "Error") EarthTypography.textSm else EarthTypography.textMd,
                            color = if (copied == d.label) EarthAccent.ink else EarthColors.Text.textPrimary,
                            textAlign = TextAlign.End,
                            maxLines = if (d.label == "Error") 4 else Int.MAX_VALUE,
                            modifier = Modifier.weight(2f, fill = false),
                        )
                        if (d.copy != null) {
                            Spacer(Modifier.width(8.dp))
                            Image(
                                painterResource(R.drawable.ic_copy), "Copy",
                                colorFilter = ColorFilter.tint(EarthColors.Text.textTertiary),
                                modifier = Modifier.size(16.dp).padding(top = 2.dp),
                            )
                        }
                    }
                    if (i < lines.lastIndex) {
                        HorizontalDivider(Modifier.padding(start = 16.dp), color = EarthColors.Surfaces.strokeSecondary)
                    }
                }
            }
            val hash = entry.hash
            if (!entry.isPrivate && hash != null) {
                Spacer(Modifier.height(24.dp))
                PillButton("View in explorer", primary = false) { onOpenUrl(EXPLORER_TX + hash) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun StatusPill(entry: ActivityEntry) {
    val (text, bg, fg) = when (entry.status) {
        ActivityEntry.Status.COMPLETED -> Triple("Completed", EarthAccent.tint, EarthAccent.ink)
        ActivityEntry.Status.PENDING -> Triple("Pending", EarthColors.Utility.WarningYellow.utilityOrange50, EarthColors.Utility.WarningYellow.utilityOrange700)
        ActivityEntry.Status.FAILED -> Triple(
            "Failed · " + (entry.shortFailure ?: "Refused by the chain"),
            EarthColors.Utility.ErrorRed.utilityError50,
            EarthColors.Utility.ErrorRed.utilityError700,
        )
    }
    Text(
        text = text,
        style = EarthTypography.textSm,
        color = fg,
        modifier = Modifier.background(bg, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

private val AMOUNT_LABELS = setOf("Sent", "Received", "You paid", "You got")
internal const val EXPLORER_TX = "https://explorer.erth.network/tx/"
