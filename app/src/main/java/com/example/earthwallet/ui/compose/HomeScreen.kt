package network.erth.wallet.ui.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import network.erth.wallet.R
import network.erth.wallet.ui.vendor.component.ShimmerCircle
import network.erth.wallet.ui.vendor.component.ShimmerRectangle
import network.erth.wallet.ui.vendor.component.rememberEarthShimmer
import com.valentinilk.shimmer.shimmer
import network.erth.wallet.ui.vendor.component.BigIconButtonState
import network.erth.wallet.ui.vendor.component.EarthBigIconButton
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography
import network.erth.wallet.ui.vendor.util.stringRes

/**
 * Home.
 *
 * A close adaptation of their HomeView, because the composition is the thing
 * worth having: the balance centred with nothing beside it, four equal actions
 * on a row of squarish cards, and the activity list rising to tuck 24dp under
 * those cards so the screen reads as one surface rather than three stacked
 * panels.
 *
 * What changed is the state behind it. Zcash's balance is a Zatoshi with a dust
 * threshold and a shielded/transparent split; Earth's is one integer in uerth,
 * so the widget takes a formatted string and the split is only for type sizing.
 * Their third and fourth actions are Scan and Buy — a payment URI and an
 * on-ramp partner, neither of which Earth has. Earn and Claim take those slots,
 * which are the two things an Earth wallet does that a Zcash one cannot:
 * stake, and collect what personhood accrues.
 */
@Composable
fun HomeScreen(
    /** Null while the balance is still being read; their shimmer stands in. */
    erthBalance: String?,
    anmlBalance: String?,
    balancesVisible: Boolean,
    activity: List<ActivityRow>?,
    onReceive: () -> Unit,
    onSend: () -> Unit,
    onEarn: () -> Unit,
    onClaimAnml: () -> Unit,
    /** Unix seconds until ANML can be claimed; 0 means now, and null means never. */
    anmlClaimableAt: Long?,
    onSeeAllActivity: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))
        BalanceWidget(
            erth = erthBalance,
            anml = anmlBalance,
            visible = balancesVisible,
        )
        Spacer(Modifier.height(16.dp))
        HomeActions(
            modifier = Modifier.zIndex(1f).offset(y = 8.dp).padding(horizontal = 24.dp),
            onReceive = onReceive,
            onSend = onSend,
            onEarn = onEarn,
            onClaimAnml = onClaimAnml,
            anmlClaimableAt = anmlClaimableAt,
        )
        Spacer(Modifier.height(2.dp))
        ActivityPanel(
            activity = activity,
            onSeeAll = onSeeAllActivity,
            contentPadding = contentPadding,
        )
    }
}

/**
 * The balance: ERTH large, ANML beneath it.
 *
 * The fractional part is set smaller than the whole — their StyledBalance
 * trick. It keeps a six-decimal micro-denomination from dominating a glance
 * without truncating it away, which matters when the fee is measured in the
 * digits being shrunk.
 *
 * ANML sits under ERTH rather than beside it because they are not peers: ERTH
 * is what the wallet spends and what the fee comes out of, ANML is what
 * personhood accrues. Two equal-sized numbers side by side would invite adding
 * them together.
 *
 * While either is null the shimmer stands in, as theirs does. A zero that is
 * really "not loaded yet" is the one wrong answer a wallet must never give.
 */
@Composable
private fun BalanceWidget(erth: String?, anml: String?, visible: Boolean) {
    val shimmer = rememberEarthShimmer()

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                modifier = Modifier.size(28.dp),
                painter = painterResource(R.drawable.ic_erth_logo),
                contentDescription = null,
            )
            Spacer(Modifier.width(6.dp))
            when {
                !visible -> Text(
                    text = "-----",
                    style = EarthTypography.header2.copy(fontWeight = FontWeight.SemiBold),
                    color = EarthColors.Text.textPrimary,
                )
                erth == null -> Box(Modifier.shimmer(shimmer)) {
                    ShimmerRectangle(width = 120.dp, height = 34.dp)
                }
                else -> SplitAmount(erth)
            }
        }

        Spacer(Modifier.height(6.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                modifier = Modifier.size(16.dp),
                painter = painterResource(R.drawable.anml),
                contentDescription = null,
            )
            Spacer(Modifier.width(4.dp))
            when {
                !visible -> Text(
                    text = "---",
                    style = EarthTypography.textMd,
                    color = EarthColors.Text.textTertiary,
                )
                anml == null -> Box(Modifier.shimmer(shimmer)) {
                    ShimmerRectangle(width = 56.dp, height = 16.dp)
                }
                else -> Text(
                    text = "$anml ANML",
                    style = EarthTypography.textMd,
                    color = EarthColors.Text.textTertiary,
                )
            }
        }
    }
}

/** The whole in display size, the fraction one step down. */
@Composable
private fun SplitAmount(amount: String) {
    val whole = amount.substringBefore('.')
    val frac = amount.substringAfter('.', "")
    Text(
        text = whole,
        style = EarthTypography.header2.copy(fontWeight = FontWeight.SemiBold),
        color = EarthColors.Text.textPrimary,
    )
    if (frac.isNotEmpty()) {
        Text(
            text = ".$frac",
            style = EarthTypography.textXs.copy(fontWeight = FontWeight.SemiBold),
            color = EarthColors.Text.textPrimary,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun HomeActions(
    onReceive: () -> Unit,
    onSend: () -> Unit,
    onEarn: () -> Unit,
    onClaimAnml: () -> Unit,
    anmlClaimableAt: Long?,
    modifier: Modifier = Modifier,
) {
    // Recomputed once a second only while a claim is actually pending, so the
    // label counts down without the whole row recomposing the rest of the time.
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    val pending = anmlClaimableAt != null && anmlClaimableAt > now
    LaunchedEffect(anmlClaimableAt, pending) {
        while (pending) {
            delay(1_000)
            now = System.currentTimeMillis() / 1000
        }
    }

    val claimable = anmlClaimableAt == 0L
    val claimLabel = when {
        anmlClaimableAt == null -> "Claim"
        claimable -> "Claim"
        else -> (anmlClaimableAt - now).coerceAtLeast(0).asCountdown()
    }

    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        EarthBigIconButton(
            modifier = Modifier.weight(1f).aspectRatio(HOME_ACTION_RATIO),
            state = BigIconButtonState(stringRes("Receive"), R.drawable.ic_home_receive, onReceive),
        )
        EarthBigIconButton(
            modifier = Modifier.weight(1f).aspectRatio(HOME_ACTION_RATIO),
            state = BigIconButtonState(stringRes("Send"), R.drawable.ic_home_send, onSend),
        )
        EarthBigIconButton(
            modifier = Modifier.weight(1f).aspectRatio(HOME_ACTION_RATIO),
            state = BigIconButtonState(stringRes("Earn"), R.drawable.ic_home_earn, onEarn),
        )
        EarthBigIconButton(
            modifier = Modifier.weight(1f).aspectRatio(HOME_ACTION_RATIO),
            // The ANML coin in its own colour: this is the one action here that
            // is about a specific token rather than about the balance, and the
            // mark says which token faster than the word does.
            state = BigIconButtonState(
                text = stringRes(claimLabel),
                icon = R.drawable.anml,
                onClick = onClaimAnml,
                // Greyed out when the day's claim is already taken, and again
                // when there is no registration to claim against — both are
                // "nothing to collect", and both should look it.
                isEnabled = claimable,
                tint = false,
            ),
        )
    }
}

@Composable
private fun ActivityPanel(
    activity: List<ActivityRow>?,
    onSeeAll: () -> Unit,
    contentPadding: PaddingValues,
) {
    val shimmer = rememberEarthShimmer()
    OverlappingBoxes {
        // Their HomeMessage slot. Earth has no sync state to report yet, so it
        // is an empty box rather than a removed one — the overlap measurement
        // below places the second child against the first, and dropping the
        // first would move the list rather than leaving it flush.
        Box(Modifier.zIndex(0f))
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)),
            contentPadding = PaddingValues(
                top = 24.dp,
                bottom = contentPadding.calculateBottomPadding(),
            ),
        ) {
            item {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Activity",
                        style = EarthTypography.textMd.copy(fontWeight = FontWeight.SemiBold),
                        color = EarthColors.Text.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    if (!activity.isNullOrEmpty()) {
                        Text(
                            text = "See all",
                            style = EarthTypography.textSm,
                            color = EarthColors.Text.textTertiary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(onClick = onSeeAll)
                                .padding(4.dp),
                        )
                    }
                }
            }
            when {
                // Still loading. Three placeholder rows rather than a spinner:
                // the list keeps its shape, so nothing jumps when the real rows
                // land, and the shape itself says what is coming.
                activity == null -> items(3) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 12.dp)
                            .shimmer(shimmer),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ShimmerCircle(size = 32.dp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            ShimmerRectangle(width = 96.dp, height = 14.dp)
                            Spacer(Modifier.height(6.dp))
                            ShimmerRectangle(width = 140.dp, height = 12.dp)
                        }
                    }
                }

                activity.isEmpty() -> item {
                    Text(
                        text = "Nothing yet. Transactions appear here once they are confirmed.",
                        style = EarthTypography.textSm,
                        color = EarthColors.Text.textTertiary,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                    )
                }

                else -> items(activity) { row -> ActivityItem(row) }
            }
        }
    }
}

/**
 * Their Layout: place the second child so it overlaps the first by 24dp.
 *
 * Not a Box with a negative offset, because the amount to lift depends on the
 * first child's measured height, and a Box cannot read that.
 */
@Composable
private fun OverlappingBoxes(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val overlapPx by remember { mutableIntStateOf(with(density) { 24.dp.toPx().toInt() }) }

    Layout(modifier = modifier, content = content) { measurables, constraints ->
        val looseConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val first = measurables.getOrNull(0)?.measure(looseConstraints)
        val second = measurables.getOrNull(1)?.measure(looseConstraints)
        layout(constraints.maxWidth, constraints.maxHeight) {
            first?.placeRelative(0, 0)
            second?.placeRelative(0, ((first?.height ?: 0) - overlapPx).coerceAtLeast(0))
        }
    }
}

/**
 * The action cards' proportion: slightly wider than tall, as in their design.
 *
 * They get there with a double measure that turns the weighted width into a
 * *minimum* height. That leaves the final height dependent on each card's
 * content, and with four labels of different lengths the four cards came out
 * visibly unequal. aspectRatio fixes the height outright, which is what the
 * row needs — a row of actions where one card is four pixels taller reads as a
 * mistake, and no card here has content that wants to grow.
 */
private const val HOME_ACTION_RATIO = 106f / 100f

/**
 * "5h 12m" until the claim opens, or "48s" in the last minute.
 *
 * Minutes are dropped past an hour and seconds past a minute: at that distance
 * the extra unit is noise, and a label that reads "5h 12m 44s" changes every
 * second while telling you nothing new.
 */
private fun Long.asCountdown(): String {
    val hours = this / 3600
    val minutes = (this % 3600) / 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "${this}s"
    }
}
