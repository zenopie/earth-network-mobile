package network.erth.wallet.ui.earn

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.valentinilk.shimmer.shimmer
import kotlinx.coroutines.delay
import network.erth.wallet.R
import network.erth.wallet.chain.Dex
import network.erth.wallet.chain.math.StakingApr
import network.erth.wallet.privacy.Amounts
import network.erth.wallet.privacy.StakeRound
import network.erth.wallet.ui.components.EarthSegmented
import network.erth.wallet.ui.components.brandButtonColors
import network.erth.wallet.ui.components.formatUerth
import network.erth.wallet.ui.designsystem.component.EarthButton
import network.erth.wallet.ui.designsystem.component.EarthButtonDefaults
import network.erth.wallet.ui.designsystem.component.EarthHorizontalDivider
import network.erth.wallet.ui.designsystem.component.ShimmerRectangle
import network.erth.wallet.ui.designsystem.component.rememberEarthShimmer
import network.erth.wallet.ui.designsystem.theme.colors.EarthColors
import network.erth.wallet.ui.designsystem.theme.dimensions.EarthDimensions
import network.erth.wallet.ui.designsystem.theme.typography.EarthTypography
import network.erth.wallet.ui.swap.PoolList
import network.erth.wallet.ui.theme.EarthAccent
import network.erth.wallet.ui.theme.EarthTheme

/**
 * Earn: the two ways to put capital to work — staking, and pools.
 *
 * Pools are here rather than beside swapping: the question people arrive
 * with is "where do I earn on what I hold", which has one answer rather than
 * two places to look, so both live here, one selector apart. iOS is laid out
 * the same way.
 *
 * The daily ANML claim is on the wallet screen's action row, not here:
 * claiming ANML is a one-tap action on a balance, not a position to manage.
 *
 * The stake half is private stake only: a summary (worth, earning, the daily
 * round), then a card per validator with where its stake stands and Add /
 * Move / Unstake, then what is on its way back. There is no claim: private
 * stake compounds into its validator's rate. A validator operator's public
 * self-bond is not shown, and Groundworks positions live in Govern.
 */
@Composable
fun EarnScreen(
    state: EarnUiState?,
    /** Open the stake sheet, for one validator or the picker. */
    onStake: (validator: String?) -> Unit,
    onUnstake: (validator: String) -> Unit,
    modifier: Modifier = Modifier,
    /** This wallet's private stake per validator (one note each, as a rule). */
    privateStake: List<PrivateStakeRow> = emptyList(),
    /** Undelegations waiting for their payout, from this wallet's own record. */
    unstaking: List<UnstakingRow> = emptyList(),
    /** Whether a stake can be started (the private wallet is open). */
    canStake: Boolean = true,
    balancesVisible: Boolean = true,
    /** Move stake from a validator (MsgRedelegate). */
    onMove: (validator: String) -> Unit = {},
    /** Merge a validator's notes into one (MsgRestake): only offered when it holds more than one. */
    onMerge: (validator: String) -> Unit = {},
    // --- the liquidity half ---
    pools: List<Dex.Pool>? = null,
    swapFeePercent: String? = null,
    lpOptionShare: Double = 0.0,
    lpUnbondings: List<Dex.Unbonding> = emptyList(),
    lpShares: Map<Long, Long> = emptyMap(),
    onAddLiquidity: (Dex.Pool) -> Unit = {},
    onRemoveLiquidity: (Dex.Pool) -> Unit = {},
) {
    val dimens = EarthTheme.dimens
    var showPools by rememberSaveable { mutableStateOf(false) }
    // Re-drawn each minute so the countdowns move.
    val now by produceState(System.currentTimeMillis() / 1000) {
        while (true) {
            delay(60_000)
            value = System.currentTimeMillis() / 1000
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .background(EarthColors.Surfaces.bgPrimary)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = dimens.gutter),
    ) {
        Spacer(Modifier.height(dimens.space16))

        EarthSegmented(
            options = listOf("Stake", "Liquidity"),
            selectedIndex = if (showPools) 1 else 0,
            onSelect = { showPools = it == 1 },
        )
        Spacer(Modifier.height(dimens.space16))

        if (showPools) {
            PoolList(
                pools = pools,
                swapFeePercent = swapFeePercent,
                lpOptionShare = lpOptionShare,
                unbondings = lpUnbondings,
                shares = lpShares,
                onAdd = onAddLiquidity,
                onRemove = onRemoveLiquidity,
            )
            Spacer(Modifier.height(dimens.space32))
            return@Column
        }

        val view = StakeView(state, now, balancesVisible)
        StakeSummary(view, privateStake)

        Spacer(Modifier.height(dimens.space16))
        EarthButton(
            text = "Stake",
            onClick = { onStake(null) },
            enabled = state != null && canStake,
            modifier = Modifier.fillMaxWidth(),
            colors = brandButtonColors(),
        )

        Spacer(Modifier.height(dimens.space24))
        Text(
            text = "Your stake",
            style = EarthTypography.textSm,
            fontWeight = FontWeight.SemiBold,
            color = EarthColors.Text.textSecondary,
        )
        Spacer(Modifier.height(dimens.space8))
        if (privateStake.isEmpty() && unstaking.isEmpty()) {
            Text(
                text = "Nothing staked yet. Stake private ERTH with a validator: it earns from the moment it lands, and no one can see it's yours.",
                style = EarthTypography.textSm,
                color = EarthColors.Text.textTertiary,
                modifier = Modifier.padding(vertical = dimens.space16),
            )
        }
        privateStake.forEach { p ->
            StakeCard(
                p, view,
                canAdd = canStake && state?.validators?.any { it.validatorOperator == p.validator } == true,
                onAdd = { onStake(p.validator) },
                onMove = { onMove(p.validator) },
                onUnstake = { onUnstake(p.validator) },
                onMerge = { onMerge(p.validator) },
            )
            Spacer(Modifier.height(dimens.space12))
        }
        unstaking.forEach { u ->
            UnstakingCard(u, view)
            Spacer(Modifier.height(dimens.space12))
        }
        Spacer(Modifier.height(dimens.space32))
    }
}

/** One validator's private stake, resolved for display. */
data class PrivateStakeRow(
    val validator: String,
    val moniker: String,
    val derth: Long,
    /** Its ERTH value at the live rate. */
    val valueUerth: Long,
    /** derth that may leave now. */
    val free: Long,
    val locked: Long,
    /** The locked part's ERTH value. */
    val lockedUerth: Long = 0,
    val lockedUntil: Long?,
    val notes: Int,
    val mergeable: Boolean,
    /** The part still queued for its validator, in ERTH; null when unknown (StakeRound.joining). */
    val joiningUerth: Long? = null,
    /** Its validator's standing; null when the list lacks it. */
    val standing: StakeRound.Standing? = null,
    val commission: Double = 0.0,
)

/** An undelegation on its way back: the chain pays it, nothing to do. */
data class UnstakingRow(
    val key: String,
    val validator: String,
    val moniker: String,
    val valueUerth: Long,
    /** About when it is paid (unix seconds); null when unknown. */
    val dueBy: Long?,
)

/** What every part of the stake half reads. */
private class StakeView(val state: EarnUiState?, val now: Long, val visible: Boolean) {
    fun amount(uerth: Long): String = if (visible) stakeAmount(uerth) else "••••"

    /** The validator's rate after its commission; null when unknown or it earns nothing now. */
    fun apr(p: PrivateStakeRow): Double? =
        if (p.standing?.earns == false) null else state?.let { StakingApr.forValidator(it.totalBondedUerth, p.commission) }

    val endsText: String?
        get() = state?.roundEndsAt?.let { "when today's round ends at ${clock(it)} (in ${StakeRound.countdown(it - now)})" }
}

@Composable
private fun StakeSummary(view: StakeView, rows: List<PrivateStakeRow>) {
    val dimens = EarthTheme.dimens
    val total = Amounts.satSum(rows) { it.valueUerth }
    val daily = rows.sumOf { it.valueUerth.toDouble() * (view.apr(it) ?: 0.0) / 365 }
    Column(
        Modifier
            .fillMaxWidth()
            .background(EarthAccent.tint, RoundedCornerShape(EarthDimensions.Radius.radius3xl))
            .padding(dimens.space16),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(R.drawable.ic_lock),
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                colorFilter = ColorFilter.tint(EarthAccent.ink),
            )
            Spacer(Modifier.width(6.dp))
            Text("Private stake", style = EarthTypography.textSm, fontWeight = FontWeight.SemiBold, color = EarthColors.Text.textSecondary)
        }
        Spacer(Modifier.height(dimens.space4))
        if (view.state == null) {
            Column(Modifier.shimmer(rememberEarthShimmer())) { ShimmerRectangle(width = 140.dp, height = 28.dp) }
        } else {
            Text(
                text = "${if (view.visible) formatUerth(total).let(::trimTo2) else "••••"} ERTH",
                style = EarthTypography.header5,
                color = EarthColors.Text.textPrimary,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = when {
                total == 0L -> "Earns from the moment it lands. Nothing to claim."
                daily > 0 -> "Earning about ${dailyText(daily, view.visible)} ERTH a day"
                else -> "Not earning now: see below"
            },
            style = EarthTypography.textSm,
            color = if (total > 0 && daily > 0) EarthAccent.ink else EarthColors.Text.textSecondary,
        )

        Spacer(Modifier.height(dimens.space12))
        EarthHorizontalDivider()
        Spacer(Modifier.height(dimens.space12))

        view.state?.roundEndsAt?.let { end ->
            InfoRow(
                "Daily round",
                "Ends ${clock(end)} · in ${StakeRound.countdown(end - view.now)}",
                "New stake joins its validator and unstaking starts when each round ends.",
            )
            Spacer(Modifier.height(dimens.space12))
        }
        val rate = view.state?.let { StakingApr.base(it.totalBondedUerth) }
        if (rate != null) {
            // Not a policy the chain aims at: a fixed stream divided by
            // however much stake is competing for it.
            InfoRow(
                "Network rate",
                rate.asRate() + " a year",
                "1 ERTH a second shared across ${formatUerth(view.state.totalBondedUerth / 1_000_000 * 1_000_000)} ERTH staked, before commission.",
            )
        }
    }
}

@Composable
private fun InfoRow(title: String, value: String, detail: String) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = EarthTypography.textSm, color = EarthColors.Text.textSecondary, modifier = Modifier.weight(1f))
            Text(value, style = EarthTypography.textSm, fontWeight = FontWeight.SemiBold, color = EarthAccent.ink, maxLines = 1)
        }
        Text(detail, style = EarthTypography.textXs, color = EarthColors.Text.textTertiary)
    }
}

/** A validator's initial on the accent: validators have no logos. */
@Composable
internal fun ValidatorMark(moniker: String, muted: Boolean = false, size: Dp = 40.dp) {
    Box(
        Modifier
            .size(size)
            .background(if (muted) EarthColors.Surfaces.bgTertiary else EarthAccent.tint, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = moniker.take(1).uppercase(),
            style = EarthTypography.textMd,
            fontWeight = FontWeight.SemiBold,
            color = if (muted) EarthColors.Text.textTertiary else EarthAccent.ink,
        )
    }
}

@Composable
private fun CardHeader(moniker: String, subtitle: String, value: String, muted: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ValidatorMark(moniker, muted)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(moniker, style = EarthTypography.textMd, fontWeight = FontWeight.SemiBold, color = EarthColors.Text.textPrimary, maxLines = 1)
            Text(subtitle, style = EarthTypography.textXs, color = EarthColors.Text.textTertiary, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(value, style = EarthTypography.textMd, color = EarthColors.Text.textPrimary, maxLines = 1)
                Spacer(Modifier.width(5.dp))
                Image(
                    painter = painterResource(R.drawable.ic_lock),
                    contentDescription = "Private",
                    modifier = Modifier.size(11.dp),
                    colorFilter = ColorFilter.tint(EarthAccent.ink),
                )
            }
            Text("ERTH", style = EarthTypography.textXs, color = EarthColors.Text.textTertiary)
        }
    }
}

private enum class Pill { SUCCESS, PENDING, FAILED, NEUTRAL }

/** A state as colour and word: the colour alone says nothing to a third of men. */
@Composable
private fun StatusLine(pill: Pill, label: String, text: String) {
    val (bg, fg) = when (pill) {
        Pill.SUCCESS -> EarthAccent.tint to EarthAccent.ink
        Pill.PENDING -> EarthAccent.warnTint to EarthAccent.warnInk
        Pill.FAILED -> Color(0xFFFDECEA) to Color(0xFFB4231A)
        Pill.NEUTRAL -> EarthColors.Surfaces.bgTertiary to EarthColors.Text.textTertiary
    }
    Column {
        Text(
            text = label,
            style = EarthTypography.textSm,
            color = fg,
            modifier = Modifier
                .background(bg, RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(text, style = EarthTypography.textSm, color = EarthColors.Text.textSecondary)
    }
}

@Composable
private fun CardFrame(content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(EarthDimensions.Radius.radius2xl)
    Column(
        Modifier
            .fillMaxWidth()
            .background(EarthColors.Surfaces.bgPrimary, shape)
            .border(1.dp, EarthColors.Surfaces.strokePrimary, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun StakeCard(
    p: PrivateStakeRow,
    view: StakeView,
    canAdd: Boolean,
    onAdd: () -> Unit,
    onMove: () -> Unit,
    onUnstake: () -> Unit,
    onMerge: () -> Unit,
) {
    val commission = "${"%.0f".format(p.commission * 100)}% commission"
    val subtitle = view.apr(p)?.let { "$commission · ${it.asRate()} APR" } ?: commission
    val ends = view.endsText ?: "when today's round ends"
    CardFrame {
        CardHeader(p.moniker, subtitle, view.amount(p.valueUerth))
        val reason = p.standing?.takeIf { !it.earns }?.reason
        val joining = p.joiningUerth ?: 0L
        when {
            reason != null -> StatusLine(Pill.FAILED, "Not earning", "$reason Move it to an active validator to earn again.")
            joining > 0 && joining >= p.valueUerth -> StatusLine(
                Pill.PENDING,
                view.state?.roundEndsAt?.let { "Joins at ${clock(it)}" } ?: "Joins soon",
                "Staked. It joins ${p.moniker} $ends, and earns the validator's rate from now on.",
            )
            else -> StatusLine(
                Pill.SUCCESS,
                "Earning",
                "Grows with every block: rewards compound into it, nothing to claim." +
                    (if (joining > 0) " ${view.amount(joining)} ERTH more joins $ends." else ""),
            )
        }
        if (p.locked > 0) {
            val until = p.lockedUntil?.let { "after ${day(it)}" } ?: "once its window closes"
            StatusLine(Pill.NEUTRAL, "Moving", "${view.amount(p.lockedUerth)} ERTH moved here. It earns here now, and can move or unstake $until.")
        }
        if (p.notes > 1) {
            Text(
                text = if (p.mergeable) "Held as ${p.notes} notes · merge them" else "Held as ${p.notes} notes",
                style = EarthTypography.textXs,
                color = if (p.mergeable) EarthAccent.ink else EarthColors.Text.textTertiary,
                modifier = if (p.mergeable) Modifier.clickable(onClick = onMerge) else Modifier,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CardButton("Add", canAdd, onAdd, Modifier.weight(1f))
            CardButton("Move", p.free > 0, onMove, Modifier.weight(1f))
            CardButton("Unstake", p.free > 0, onUnstake, Modifier.weight(1f))
        }
    }
}

@Composable
private fun CardButton(text: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    EarthButton(
        text = text,
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 40.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        colors = EarthButtonDefaults.secondaryColors(),
    )
}

@Composable
private fun UnstakingCard(u: UnstakingRow, view: StakeView) {
    val unbonding = view.state?.unbondingSeconds
    val whenText = when {
        u.dueBy != null -> if (u.dueBy > view.now) "around ${day(u.dueBy)}" else "any time now"
        unbonding != null -> "about ${maxOf(1, unbonding / 86_400)} days after the round it was asked in ends"
        else -> "once the unbonding period ends"
    }
    CardFrame {
        CardHeader(u.moniker, "Unstaked", view.amount(u.valueUerth), muted = true)
        StatusLine(Pill.PENDING, "Unstaking", "Not earning. It's paid to your private ERTH automatically $whenText. Nothing to claim.")
    }
}

/** ERTH to at most three decimals, as the cards show it. */
internal fun stakeAmount(uerth: Long): String {
    val whole = uerth / 1_000_000
    val frac = ((uerth % 1_000_000) / 1_000).toString().padStart(3, '0').trimEnd('0')
    return if (frac.isEmpty()) "%,d".format(whole) else "%,d.%s".format(whole, frac)
}

private fun trimTo2(s: String): String {
    val dot = s.indexOf('.')
    return if (dot < 0) s else s.substring(0, minOf(s.length, dot + 3)).trimEnd('0').trimEnd('.')
}

private fun dailyText(uerth: Double, visible: Boolean): String {
    if (!visible) return "••••"
    val erth = uerth / 1_000_000
    return when {
        erth >= 100 -> "%.0f".format(erth)
        erth >= 1 -> "%.2f".format(erth)
        erth >= 0.001 -> "%.3f".format(erth)
        else -> "<0.001"
    }
}

/** When the round ends, as "6:00 AM" (today or tomorrow) or "Tue 6:00 AM". */
internal fun clock(unix: Long): String {
    val d = java.util.Date(unix * 1000)
    val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(d)
    val days = java.util.concurrent.TimeUnit.MILLISECONDS.toDays(
        startOfDay(unix * 1000) - startOfDay(System.currentTimeMillis()),
    )
    return if (days in 0..1) time else java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(d) + " " + time
}

private fun startOfDay(ms: Long): Long = java.util.Calendar.getInstance().apply {
    timeInMillis = ms
    set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
    set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
}.timeInMillis

/** A payout or window date: "Oct 28, 6:00 AM". */
internal fun day(unix: Long): String {
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), "MMMdjmm")
    return java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).format(java.util.Date(unix * 1000))
}

/**
 * A rate at whatever magnitude it lands.
 *
 * On a young chain with little bonded this runs to millions of percent, which
 * is arithmetically right and worth showing rather than capping — a capped
 * number invites the reader to believe the cap.
 */
internal fun Double.asRate(): String {
    val pct = this * 100
    return when {
        pct == 0.0 -> "0%"
        pct < 0.01 -> "<0.01%"
        pct < 1 -> "%.2f%%".format(pct)
        pct < 1_000 -> "%.1f%%".format(pct)
        else -> "%,.0f%%".format(pct)
    }
}
