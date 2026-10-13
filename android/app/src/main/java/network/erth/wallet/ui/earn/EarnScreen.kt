package network.erth.wallet.ui.earn

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.sp
import com.valentinilk.shimmer.shimmer
import kotlinx.coroutines.delay
import network.erth.wallet.R
import network.erth.wallet.chain.Dex
import network.erth.wallet.chain.math.StakingApr
import network.erth.wallet.privacy.Amounts
import network.erth.wallet.privacy.StakeRound
import network.erth.wallet.ui.components.EarthSegmented
import network.erth.wallet.ui.components.EarthSheet
import network.erth.wallet.ui.components.brandButtonColors
import network.erth.wallet.ui.components.formatUerth
import network.erth.wallet.ui.designsystem.component.EarthButton
import network.erth.wallet.ui.designsystem.component.EarthButtonDefaults
import network.erth.wallet.ui.designsystem.component.ShimmerRectangle
import network.erth.wallet.ui.designsystem.component.rememberEarthShimmer
import network.erth.wallet.ui.designsystem.theme.colors.EarthColors
import network.erth.wallet.ui.designsystem.theme.typography.EarthTypography
import network.erth.wallet.ui.swap.PoolList
import network.erth.wallet.ui.swap.asPercent
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
 * The stake half is private stake only: one big number, Stake and Unstake,
 * a row per validator and vintage (its Add / Move / Unstake in its own
 * sheet), then what is on its way back. There is no claim: private stake
 * compounds into its validator's rate. A delegation bonds in its own block,
 * so stake earns from then on while its validator does; a validator
 * operator's public self-bond is not shown.
 *
 * Stake vintages (v1.3.0): stake earns for 12 to 13 months after it was
 * last checked in. Each row says until when ("check in by"); within a month
 * of it the row warns, and one Check in button checks in everything older
 * than this month (a tx per note, on one confirmation). A retired vintage
 * earns nothing: its row says since when and offers Withdraw.
 */
@Composable
fun EarnScreen(
    state: EarnUiState?,
    /** Open the stake sheet, for one validator or the picker. */
    onStake: (validator: String?) -> Unit,
    /** Open the unstake sheet, for one row's stake (its denom) or (null) the picker. */
    onUnstake: (stake: String?) -> Unit,
    modifier: Modifier = Modifier,
    /** This wallet's private stake per validator and vintage (one note each, as a rule). */
    /** Null until the wallet's stake notes are read. */
    privateStake: List<PrivateStakeRow>? = emptyList(),
    /** Undelegations waiting for their payout, from this wallet's own record. */
    /** Null until the wallet's own record is read. */
    unstaking: List<UnstakingRow>? = emptyList(),
    /** Whether a stake can be started (the private wallet is open). */
    canStake: Boolean = true,
    balancesVisible: Boolean = true,
    /** Move a row's stake (its denom) to another validator (MsgRedelegate). */
    onMove: (stake: String) -> Unit = {},
    /** Merge a row's notes into one (MsgRestake): only offered when it holds more than one. */
    onMerge: (stake: String) -> Unit = {},
    /** Check in every row older than this month (MsgCheckIn, a tx per note). */
    onCheckIn: () -> Unit = {},
    /** Withdraw a retired row's stake (its denom). */
    onWithdraw: (stake: String) -> Unit = {},
    // --- the liquidity half ---
    pools: List<Dex.Pool>? = null,
    swapFeePercent: String? = null,
    lpOptionShare: Double = 0.0,
    lpUnbondings: List<Dex.Unbonding> = emptyList(),
    lpShares: Map<Long, Long>? = emptyMap(),
    onAddLiquidity: (Dex.Pool) -> Unit = {},
    onRemoveLiquidity: (Dex.Pool) -> Unit = {},
) {
    val dimens = EarthTheme.dimens
    var showPools by rememberSaveable { mutableStateOf(false) }
    /** The row (its denom) whose sheet is open. */
    var opened by rememberSaveable { mutableStateOf<String?>(null) }
    // Re-drawn each minute so a payout date that passes updates.
    val now by produceState(System.currentTimeMillis() / 1000) {
        while (true) {
            delay(60_000)
            value = System.currentTimeMillis() / 1000
        }
    }

    // The hero is the validators' rates over the stake notes: either unread
    // and it would say "0" and "Earn by staking" to someone who has stake.
    val view = StakeView(state, now, balancesVisible, loaded = state != null && privateStake != null && unstaking != null)
    val privateStake = privateStake.orEmpty()
    val unstaking = unstaking.orEmpty()
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

        val empty = view.loaded && privateStake.isEmpty() && unstaking.isEmpty()
        StakeHero(view, privateStake, empty)

        Spacer(Modifier.height(dimens.space32))
        Row(horizontalArrangement = Arrangement.spacedBy(dimens.space12)) {
            PillButton("Stake", enabled = view.loaded && canStake, modifier = Modifier.weight(1f)) { onStake(null) }
            if (view.loaded && !empty) {
                // One row: straight to it. Several: pick first.
                val leaving = privateStake.filter { it.free > 0 && !it.retired }
                PillButton("Unstake", enabled = leaving.isNotEmpty(), primary = false, modifier = Modifier.weight(1f)) {
                    onUnstake(leaving.singleOrNull()?.denom)
                }
            }
        }
        // One button for every check-in due: shown from a month before the oldest vintage retires.
        if (view.loaded && canStake && privateStake.any { it.checkInDue(view.now) }) {
            Spacer(Modifier.height(dimens.space12))
            PillButton("Check in", onClick = onCheckIn)
        }

        Spacer(Modifier.height(dimens.space24))
        privateStake.forEach { p ->
            // A retiring row waits for the epoch end: nothing to tap.
            StakeListRow(p, view) { if (p.retiring) Unit else if (p.retired) onWithdraw(p.denom) else opened = p.denom }
        }
        unstaking.forEach { u -> UnstakingListRow(u, view) }
        Spacer(Modifier.height(dimens.space32))
    }

    privateStake.firstOrNull { it.denom == opened && !it.retired }?.let { p ->
        ValidatorStakeSheet(
            p = p,
            view = view,
            canAdd = canStake && state?.validators?.any { it.validatorOperator == p.validator } == true,
            onAdd = { opened = null; onStake(p.validator) },
            onMove = { opened = null; onMove(p.denom) },
            onUnstake = { opened = null; onUnstake(p.denom) },
            onMerge = { opened = null; onMerge(p.denom) },
            onDismiss = { opened = null },
        )
    }
}

/** One validator's private stake of one vintage, resolved for display. */
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
    /** Its validator's standing; null when the list lacks it. */
    val standing: StakeRound.Standing? = null,
    val commission: Double = 0.0,
    /** The stake denom (derth/<validator>[/<YYYYMM>]): the row's key. */
    val denom: String = network.erth.wallet.privacy.PrivacyWallet.derthDenom(validator),
    /** When its vintage retires (or retired), unix seconds; 0 unknown (no vintages yet). */
    val retiresAt: Long = 0,
    /** Its vintage has retired: it earns nothing and is only withdrawn. */
    val retired: Boolean = false,
    /** Retired, not booked yet: nothing to do until [withdrawFrom] (the next epoch end; 0 unknown). */
    val retiring: Boolean = false,
    val withdrawFrom: Long = 0,
) {
    /** Within a month of its retirement: the row warns and Check in shows. */
    fun checkInDue(now: Long): Boolean = !retired && retiresAt > 0 && now >= retiresAt - network.erth.wallet.privacy.Vintages.CHECK_IN_LEAD_SECONDS
}

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
private class StakeView(val state: EarnUiState?, val now: Long, val visible: Boolean, val loaded: Boolean) {
    fun amount(uerth: Long): String = if (visible) stakeAmount(uerth) else "••••"

    /** The validator's rate after its commission; null when unknown or it earns nothing now. */
    fun apr(p: PrivateStakeRow): Double? =
        if (p.retired || p.standing?.earns == false) null else state?.let { StakingApr.forValidator(it.totalBondedUerth, p.commission) }

    /**
     * What the whole stake earns, weighted by value: idle stake counts at
     * zero, so a jailed validator pulls the figure down as it does the
     * daily one. Null without earning stake.
     */
    fun blendedApr(rows: List<PrivateStakeRow>): Double? {
        val total = rows.sumOf { it.valueUerth.toDouble() }
        if (total <= 0) return null
        val earned = rows.sumOf { it.valueUerth.toDouble() * (apr(it) ?: 0.0) }
        return if (earned > 0) earned / total else null
    }
}

/** A picker row's line: the validator's rate after its commission, then the commission. */
internal fun validatorDetail(totalBondedUerth: Long, commission: Double): String {
    val cut = "${"%.0f".format(commission * 100)}% commission"
    return StakingApr.forValidator(totalBondedUerth, commission)?.let { "${it.asPercent()} APR · $cut" } ?: cut
}

private enum class StakeStatus { EARNING, IDLE }

/** The row's dot. */
private val PrivateStakeRow.status: StakeStatus
    get() = if (retired || standing?.earns == false) StakeStatus.IDLE else StakeStatus.EARNING

/**
 * The row's one line: "Earning · check in by Oct 31, 2027"; within a month
 * of it, "Check in by Oct 31, 2027" (warning); retiring (past it, until the
 * epoch end books it), "Retiring · withdraw from Nov 2, 2026"; retired, "Not
 * earning since Nov 1, 2026 · Withdraw". Null before vintages (nothing to say).
 */
private fun PrivateStakeRow.vintageLine(now: Long): Pair<String, Boolean>? = when {
    retiresAt <= 0 -> null
    retiring -> (if (withdrawFrom > 0) "Retiring · withdraw from ${longDay(withdrawFrom)}" else "Retiring · withdraw soon") to false
    retired -> "Not earning since ${longDay(retiresAt)} · Withdraw" to false
    checkInDue(now) -> "Check in by ${longDay(retiresAt)}" to true
    standing?.earns == false -> "${standing.label} · check in by ${longDay(retiresAt)}" to false
    else -> "Earning · check in by ${longDay(retiresAt)}" to false
}

/** The big number, "ERTH" under it, and at most one short line. */
@Composable
private fun StakeHero(view: StakeView, rows: List<PrivateStakeRow>, empty: Boolean) {
    val total = Amounts.satSum(rows) { it.valueUerth }
    Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        when {
            !view.loaded -> Box(Modifier.shimmer(rememberEarthShimmer())) { ShimmerRectangle(width = 160.dp, height = 48.dp) }
            !view.visible -> Text("-----", style = EarthTypography.header2.copy(fontWeight = FontWeight.SemiBold), color = EarthColors.Text.textPrimary)
            else -> BigAmount(formatUerth(total))
        }
        Text("ERTH", style = EarthTypography.textMd, color = EarthColors.Text.textTertiary)
        if (view.loaded) heroLine(view, rows, total, empty)?.let { (text, color) ->
            Spacer(Modifier.height(12.dp))
            Text(text, style = EarthTypography.textMd.copy(fontSize = 15.sp), fontWeight = FontWeight.SemiBold, color = color)
        }
    }
}

/** What it earns a day, and at what rate. A rate is not private, so it stays with balances hidden. */
@Composable
private fun heroLine(view: StakeView, rows: List<PrivateStakeRow>, total: Long, empty: Boolean): Pair<String, Color>? {
    if (total == 0L) {
        if (!empty) return null
        val base = view.state?.let { StakingApr.base(it.totalBondedUerth) }
            ?: return "Earn by staking ERTH" to EarthColors.Text.textTertiary
        return "Earn up to ${base.asPercent()} APR" to EarthAccent.ink
    }
    val daily = rows.sumOf { it.valueUerth.toDouble() * (view.apr(it) ?: 0.0) / 365 }
    val apr = view.blendedApr(rows)
    if (daily <= 0 || apr == null) return "Not earning" to EarthColors.Text.textTertiary
    val rate = "${apr.asPercent()} APR"
    return (if (view.visible) "+${dailyText(daily)} ERTH / day · $rate" else rate) to EarthAccent.ink
}

/** The whole in display size, the fraction one step down: the wallet's balance. */
@Composable
private fun BigAmount(amount: String) {
    Row {
        Text(
            text = amount.substringBefore('.'),
            style = EarthTypography.header2.copy(fontWeight = FontWeight.SemiBold),
            color = EarthColors.Text.textPrimary,
            maxLines = 1,
        )
        val frac = amount.substringAfter('.', "")
        if (frac.isNotEmpty()) {
            Text(
                text = ".$frac",
                style = EarthTypography.textXs.copy(fontWeight = FontWeight.SemiBold),
                color = EarthColors.Text.textPrimary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** A big capsule action: the screen's two headline buttons and the sheets' one confirm. */
@Composable
internal fun PillButton(text: String, enabled: Boolean = true, primary: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    EarthButton(
        text = text,
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = RoundedCornerShape(50),
        style = EarthTypography.textMd.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
        colors = if (primary) brandButtonColors() else EarthButtonDefaults.secondaryColors(borderColor = Color.Unspecified),
    )
}

/** Green earning, grey not earning. */
@Composable
private fun StatusDot(status: StakeStatus, size: Dp = 8.dp) {
    Box(
        Modifier.size(size).background(
            when (status) {
                StakeStatus.EARNING -> Color(0xFF00C244)
                StakeStatus.IDLE -> Color(0xFFAAB4A5)
            },
            CircleShape,
        ),
    )
}

/** A validator's initial on the accent: validators have no logos. */
@Composable
internal fun ValidatorMark(moniker: String, muted: Boolean = false, size: Dp = 44.dp) {
    Box(
        Modifier
            .size(size)
            .background(if (muted) EarthColors.Surfaces.bgTertiary else EarthAccent.tint, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = moniker.take(1).uppercase(),
            style = EarthTypography.textMd.copy(fontSize = (size.value * 0.4f).sp),
            fontWeight = FontWeight.Bold,
            color = if (muted) EarthColors.Text.textTertiary else EarthAccent.ink,
        )
    }
}

/** A validator you are staked with (one vintage): initial, name and dot, its vintage line, amount. Tapping opens its sheet, or a retired row's withdrawal. */
@Composable
private fun StakeListRow(p: PrivateStakeRow, view: StakeView, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ValidatorMark(p.moniker, muted = p.status == StakeStatus.IDLE)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.moniker, style = EarthTypography.textMd, fontWeight = FontWeight.SemiBold, color = EarthColors.Text.textPrimary, maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(6.dp))
                StatusDot(p.status)
            }
            p.vintageLine(view.now)?.let { (text, warn) ->
                Text(text, style = EarthTypography.textSm, maxLines = 1,
                    fontWeight = if (warn || p.retired) FontWeight.SemiBold else FontWeight.Normal,
                    color = when {
                        warn -> EarthAccent.warnInk
                        p.retired -> EarthAccent.ink
                        else -> EarthColors.Text.textTertiary
                    })
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(view.amount(p.valueUerth), style = EarthTypography.textMd.copy(fontSize = 17.sp), fontWeight = FontWeight.SemiBold,
            color = EarthColors.Text.textPrimary, maxLines = 1)
    }
}

/** "Unstaking · Oct 28" and the amount: the chain pays it, nothing to do. */
@Composable
private fun UnstakingListRow(u: UnstakingRow, view: StakeView) {
    val unbonding = view.state?.unbondingSeconds
    val whenText = when {
        u.dueBy != null -> if (u.dueBy > view.now) shortDay(u.dueBy) else "Soon"
        unbonding != null -> "~${maxOf(1, unbonding / 86_400)} days"
        else -> "Soon"
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).background(EarthColors.Surfaces.bgTertiary, CircleShape), contentAlignment = Alignment.Center) {
            Image(
                painter = painterResource(R.drawable.ic_clock),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                colorFilter = ColorFilter.tint(EarthColors.Text.textTertiary),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text("Unstaking · $whenText", style = EarthTypography.textMd, fontWeight = FontWeight.Medium,
            color = EarthColors.Text.textSecondary, maxLines = 1, modifier = Modifier.weight(1f))
        Text(view.amount(u.valueUerth), style = EarthTypography.textMd.copy(fontSize = 17.sp), fontWeight = FontWeight.SemiBold,
            color = EarthColors.Text.textTertiary, maxLines = 1)
    }
}

/**
 * One validator: the big amount and Add / Move / Unstake. A window still
 * closed, or notes to merge get one short line each, and only when they apply.
 */
@Composable
private fun ValidatorStakeSheet(
    p: PrivateStakeRow,
    view: StakeView,
    canAdd: Boolean,
    onAdd: () -> Unit,
    onMove: () -> Unit,
    onUnstake: () -> Unit,
    onMerge: () -> Unit,
    onDismiss: () -> Unit,
) {
    EarthSheet(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(8.dp))
            ValidatorMark(p.moniker, muted = p.status == StakeStatus.IDLE, size = 56.dp)
            Spacer(Modifier.height(12.dp))
            Text(p.moniker, style = EarthTypography.textMd.copy(fontSize = 17.sp), fontWeight = FontWeight.SemiBold,
                color = EarthColors.Text.textPrimary, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(p.status)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = when (p.status) {
                        StakeStatus.EARNING -> view.apr(p)?.let { "Earning · ${it.asPercent()} APR" } ?: "Earning"
                        StakeStatus.IDLE -> p.standing?.label ?: "Not earning"
                    },
                    style = EarthTypography.textSm,
                    color = EarthColors.Text.textTertiary,
                )
            }
            Spacer(Modifier.height(24.dp))
            if (view.visible) BigAmount(formatUerth(p.valueUerth))
            else Text("-----", style = EarthTypography.header2.copy(fontWeight = FontWeight.SemiBold), color = EarthColors.Text.textPrimary)
            Text("ERTH", style = EarthTypography.textMd, color = EarthColors.Text.textTertiary)
            Spacer(Modifier.height(12.dp))
            if (p.locked > 0 && p.free == 0L) {
                ShortLine(p.lockedUntil?.let { "Can move ${day(it)}" } ?: "Recently moved here")
            }
            if (p.notes > 1 && p.mergeable) {
                Text(
                    text = "Merge ${p.notes} notes",
                    style = EarthTypography.textSm,
                    fontWeight = FontWeight.SemiBold,
                    color = EarthAccent.ink,
                    modifier = Modifier.clickable(onClick = onMerge).padding(4.dp),
                )
            }
            Spacer(Modifier.height(32.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Add", enabled = canAdd, modifier = Modifier.weight(1f), onClick = onAdd)
                PillButton("Move", enabled = p.free > 0, primary = false, modifier = Modifier.weight(1f), onClick = onMove)
                PillButton("Unstake", enabled = p.free > 0, primary = false, modifier = Modifier.weight(1f), onClick = onUnstake)
            }
        }
    }
}

@Composable
private fun ShortLine(text: String) {
    Text(text, style = EarthTypography.textSm, color = EarthColors.Text.textTertiary, modifier = Modifier.padding(vertical = 2.dp))
}

/** ERTH to at most three decimals, as the cards show it. */
internal fun stakeAmount(uerth: Long): String {
    val whole = uerth / 1_000_000
    val frac = ((uerth % 1_000_000) / 1_000).toString().padStart(3, '0').trimEnd('0')
    return if (frac.isEmpty()) "%,d".format(whole) else "%,d.%s".format(whole, frac)
}

private fun dailyText(uerth: Double): String {
    val erth = uerth / 1_000_000
    return when {
        erth >= 100 -> "%,.0f".format(erth)
        erth >= 1 -> "%.1f".format(erth)
        erth >= 0.001 -> "%.3f".format(erth)
        else -> "<0.001"
    }
}

/** A payout or window date: "Oct 28, 6:00 AM". */
internal fun day(unix: Long): String {
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), "MMMdjmm")
    return java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).format(java.util.Date(unix * 1000))
}

/** A vintage date: "Oct 31, 2027". */
internal fun longDay(unix: Long): String {
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), "MMMdyyyy")
    return java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).format(java.util.Date(unix * 1000))
}

/** A payout date: "Oct 28". */
internal fun shortDay(unix: Long): String {
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), "MMMd")
    return java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).format(java.util.Date(unix * 1000))
}
