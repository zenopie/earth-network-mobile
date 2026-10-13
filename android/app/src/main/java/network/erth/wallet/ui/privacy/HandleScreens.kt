package network.erth.wallet.ui.privacy

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import network.erth.wallet.R
import network.erth.wallet.privacy.sync.PendingMove
import network.erth.wallet.ui.components.HeroColumn
import network.erth.wallet.ui.components.HeroGap
import network.erth.wallet.ui.components.HeroIcon
import network.erth.wallet.ui.components.HeroLine
import network.erth.wallet.ui.components.HeroTitle
import network.erth.wallet.ui.earn.PillButton
import network.erth.wallet.ui.theme.EarthAccent
import network.erth.wallet.privacy.Reminders
import network.erth.wallet.privacy.handles.HandleEntry
import network.erth.wallet.privacy.handles.Handles
import network.erth.wallet.ui.components.EarthDetailRow
import network.erth.wallet.ui.components.EarthLabel
import network.erth.wallet.ui.components.brandButtonColors
import network.erth.wallet.ui.components.destructiveButtonColors
import network.erth.wallet.ui.designsystem.component.EarthButton
import network.erth.wallet.ui.designsystem.component.EarthButtonDefaults
import network.erth.wallet.ui.designsystem.component.EarthTextField
import network.erth.wallet.ui.designsystem.theme.colors.EarthColors
import network.erth.wallet.ui.designsystem.theme.dimensions.EarthDimensions
import network.erth.wallet.ui.designsystem.theme.typography.EarthTypography
import network.erth.wallet.ui.theme.EarthTheme
import network.erth.wallet.wallet.SecureWalletManager

/** A reminder, as a tappable banner: what is due and where to do it. Nothing happens until the owner acts. */
@Composable
fun ReminderBanner(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val dimens = EarthTheme.dimens
    Column(
        modifier
            .fillMaxWidth()
            .padding(vertical = dimens.space4)
            .background(EarthColors.Surfaces.bgSecondary, RoundedCornerShape(EarthDimensions.Radius.radius3xl))
            .clickable(onClick = onClick)
            .padding(dimens.space16),
    ) {
        Text(text = text, style = EarthTypography.textSm, color = EarthColors.Text.textPrimary)
    }
}

/**
 * The handle: a name in the chain's public directory for this wallet's
 * shielded address, so others can pay "@name" privately. Claimed, renewed,
 * changed or released with a membership proof (one per person, who holds it
 * is not public). A lease lasts a year; nothing renews it on its own, so the
 * app reminds the owner from 30 days before it ends and through the 30-day
 * renewal period after.
 */
@Composable
fun HandleScreen(
    state: PersonalState?,
    now: Long,
    onClaim: (String) -> Unit,
    onRenew: () -> Unit,
    /** Renew-only: an entry at this address, while no handle is held. */
    onRenewAddressed: (String) -> Unit,
    onRelease: () -> Unit,
    onShare: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = EarthTheme.dimens
    var input by remember { mutableStateOf("") }
    Page(modifier) {
        val e = state?.handleEntry
        val held = state != null && state.handle.isNotEmpty()
        val movingOut = state?.outgoingMoves?.any { it.kind == PendingMove.HANDLE && !it.confirmed } == true
        val st = e?.statusAt(now)
        HeroColumn {
            HeroIcon(R.drawable.ic_public, muted = !held)
            HeroGap()
            HeroTitle(
                when {
                    state == null -> network.erth.wallet.ui.components.LOADING
                    held -> "@${state.handle}"
                    else -> "No handle"
                },
            )
            HeroLine(
                when {
                    state == null -> network.erth.wallet.ui.components.LOADING
                    !state.identityLive -> "Verify to claim a handle"
                    movingOut -> "Moving to your new identity"
                    state.incomingMoves.any { it.kind == PendingMove.HANDLE } -> "Arriving · waiting for the chain"
                    !held -> "Get paid by name"
                    e == null -> "Not in the directory"
                    e.address != state.shieldedAddress -> "Pays another address"
                    st == HandleEntry.LIVE -> "Live · until ${date(e.expiresAt)}"
                    st == HandleEntry.RENEWAL -> "Expired · renew by ${date(e.renewalUntil)}"
                    else -> "Released"
                },
                color = if (st == HandleEntry.RENEWAL || (e != null && state != null && e.address != state.shieldedAddress)) EarthColors.Utility.ErrorRed.utilityError700
                else EarthColors.Text.textTertiary,
            )
        }
        Spacer(Modifier.height(dimens.space24))
        if (state == null || !state.identityLive) return@Page
        state.reminders.filter { it is Reminders.Reminder.HandleExpiring && it.handle == state.handle || it is Reminders.Reminder.HandlePaysElsewhere }.forEach {
            ReminderBanner(Reminders.text(it, now), onClick = onRenew)
        }
        state.directoryError?.let { Note("Couldn't read the handle directory") }
        // Entries naming this wallet's address that the store does not hold
        // (a restore loses track of a handle): renewing one is checked by the chain, at no cost if it is not ours.
        // Only while no handle is held (a bind of another would free it), and renew-only.
        if (!held) state.addressed.forEach { a ->
            Row(Modifier.fillMaxWidth().padding(vertical = dimens.space8), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("@${a.handle}", style = EarthTypography.textMd, fontWeight = FontWeight.SemiBold, color = EarthColors.Text.textPrimary)
                    Text("Names this wallet · free if not yours", style = EarthTypography.textXs, color = EarthColors.Text.textTertiary)
                }
                PillButton("Renew", modifier = Modifier.width(120.dp)) { onRenewAddressed(a.handle) }
            }
        }
        if (held && !movingOut) {
            if (st == HandleEntry.RENEWAL) {
                // Past expiry, a renewal is bounded like a claim, and the handle cannot be moved.
                val claimFrom = if (state.predecessorAt > 0) Handles.satAdd(Handles.satAdd(state.predecessorAt, state.handleLeaseSeconds), 86_400 + 3_600) else 0L
                Note(if (claimFrom > now) "Renewable from ${date(claimFrom)}" else "Renewing now counts as a new claim")
                Spacer(Modifier.height(dimens.space8))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(dimens.space8)) {
                PillButton(if (e != null && e.address != state.shieldedAddress) "Renew here" else "Renew", modifier = Modifier.weight(1f), onClick = onRenew)
                PillButton("Share", primary = false, modifier = Modifier.weight(1f)) { onShare(state.handle) }
            }
            Spacer(Modifier.height(dimens.space24))
        }
        if (state.handleMovedOut && !held) {
            Note("Moved away · this identity can't claim again")
            return@Page
        }
        if (movingOut) return@Page
        // Clamped: a hostile lease param cannot overflow the date.
        val waitUntil = if (state.predecessorAt > 0) Handles.satAdd(Handles.satAdd(state.predecessorAt, state.handleLeaseSeconds), 86_400 + 3_600) else 0L
        if (!held && waitUntil > now) {
            // A held handle a restore lost track of can still be renewed by name: the chain checks.
            Note("New claims from ${date(waitUntil)} · renewals now")
            Spacer(Modifier.height(dimens.space8))
        }
        val parsed = Handles.parse(input)
        EarthTextField(
            value = input,
            onValueChange = { input = it.trim() },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(if (held) "Change to @name" else "@name") },
            error = if (input.isNotEmpty() && parsed == null) "3–32 of a–z, 0–9, dash" else null,
        )
        Spacer(Modifier.height(dimens.space8))
        Note(if (!held) "One per person · fee spent if taken" else "Frees @${state.handle} for anyone")
        Spacer(Modifier.height(dimens.space12))
        PillButton(
            text = if (!held) "Claim ${parsed?.let { "@$it" } ?: "handle"}" else "Change to ${parsed?.let { "@$it" } ?: "…"}",
            enabled = parsed != null && parsed != state.handle,
        ) { parsed?.let(onClaim) }
        if (held) {
            Spacer(Modifier.height(dimens.space24))
            Note("Anyone can claim it after")
            Spacer(Modifier.height(dimens.space8))
            Text(
                "Release @${state.handle}",
                style = EarthTypography.textMd,
                fontWeight = FontWeight.SemiBold,
                color = EarthColors.Utility.ErrorRed.utilityError700,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clickable(onClick = onRelease).padding(vertical = dimens.space12),
            )
        }
    }
}

/**
 * Moving to another wallet: the same passport registered to another of this
 * phone's wallets' next identity, a new one or one already here. The chain
 * zeroes the live leaf and makes a new one. Once that lands, the new identity
 * brings this one's handle and caretaker vote over ([MoveOfferCard], on
 * Identity where it registered): a move proves knowledge of both identities'
 * secrets, so it needs both wallets on this phone, and a wallet whose phrase
 * is lost can move nothing. Hence the recommended target is a new wallet
 * (whose creation shows its phrase), and an existing one asks the user to
 * say they hold its phrase.
 *
 * A fresh identity in this wallet is not offered: it derives from the same
 * phrase, so it helps only when an identity secret leaked by itself off the
 * phone, never for a lost phone or an exposed phrase (the wallet holds the
 * key every generation derives from). Renewal (Identity) still registers
 * this wallet's next identity.
 *
 * Two steps: where to ([target] null), then the passport scan for [target].
 */
@Composable
fun MoveToWalletScreen(
    wallets: List<SecureWalletManager.WalletInfo>,
    currentIndex: Int,
    /** The chosen wallet, or null while choosing. */
    target: Int?,
    /** Whether [target] was created in this flow (its phrase was just shown). */
    targetIsNew: Boolean,
    onPick: (Int) -> Unit,
    onNewWallet: () -> Unit,
    onBack: () -> Unit,
    onScan: (Int) -> Unit,
    /** What the chosen target already holds, as a warning (null: nothing). */
    targetWarning: String?,
    modifier: Modifier = Modifier,
    /**
     * What this identity's predecessor (on this phone) still holds that has
     * not moved here, or null: a move goes one step, so a further switch
     * strands it there for good.
     */
    unmoved: String? = null,
    /**
     * What this identity holds whose lease ends soon (renewSoonText), or
     * null: renewed before the switch, its move afterwards has a full lease
     * to land in. [onRenewFirst] opens where it is renewed.
     */
    renewFirst: String? = null,
    onRenewFirst: () -> Unit = {},
) {
    val dimens = EarthTheme.dimens
    val red = EarthColors.Utility.ErrorRed.utilityError700
    var strandAccepted by remember { mutableStateOf(false) }
    var hasPhrase by remember(target) { mutableStateOf(false) }
    val chosen = target?.let { t -> wallets.firstOrNull { it.index == t } }
    Page(modifier) {
        if (target == null) {
            HeroColumn {
                HeroIcon(R.drawable.ic_swap_vertical)
                HeroGap()
                HeroTitle("Where to?")
                HeroLine("This wallet stops counting as you")
            }
            Spacer(Modifier.height(dimens.space24))
            if (renewFirst != null) {
                Text("Expiring soon · renew before moving", style = EarthTypography.textSm, color = red)
                Text(
                    "Renew first",
                    style = EarthTypography.textSm,
                    fontWeight = FontWeight.SemiBold,
                    color = EarthAccent.ink,
                    modifier = Modifier.clickable(onClick = onRenewFirst).padding(vertical = dimens.space8),
                )
            }
            if (unmoved != null) {
                Text("$unmoved Moving strands it.", style = EarthTypography.textSm, color = red)
                Row(Modifier.fillMaxWidth().clickable { strandAccepted = !strandAccepted }.padding(vertical = dimens.space8), verticalAlignment = Alignment.CenterVertically) {
                    Text("Move anyway", style = EarthTypography.textMd, color = EarthColors.Text.textPrimary, modifier = Modifier.weight(1f))
                    androidx.compose.material3.Switch(
                        checked = strandAccepted,
                        onCheckedChange = { strandAccepted = it },
                        colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = EarthAccent.ink),
                    )
                }
            }
            val open = unmoved == null || strandAccepted
            @Composable
            fun Option(title: String, detail: String, mark: String, recommended: Boolean, onClick: () -> Unit) {
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = open, onClick = onClick).padding(vertical = dimens.space12),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.foundation.layout.Box(
                        Modifier.size(44.dp).background(if (recommended) EarthAccent.tint else EarthColors.Surfaces.bgTertiary, androidx.compose.foundation.shape.CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(mark, style = EarthTypography.textMd, fontWeight = FontWeight.Bold,
                            color = if (recommended) EarthAccent.ink else EarthColors.Text.textTertiary)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(title, style = EarthTypography.textMd, fontWeight = FontWeight.SemiBold,
                            color = if (open) EarthColors.Text.textPrimary else EarthColors.Text.textTertiary, maxLines = 1)
                        Text(detail, style = EarthTypography.textXs,
                            color = if (recommended && open) EarthAccent.ink else EarthColors.Text.textTertiary, maxLines = 1)
                    }
                    Text("›", style = EarthTypography.textLg, color = EarthColors.Text.textTertiary)
                }
            }
            Option("New wallet", "Recommended", "+", recommended = true, onClick = onNewWallet)
            wallets.filter { it.index != currentIndex }.forEach { w ->
                Option(w.name, Handles.truncate(w.address, 10, 6), w.name.take(1).uppercase(), recommended = false) { onPick(w.index) }
            }
            return@Page
        }
        val name = chosen?.name ?: "That wallet"
        HeroColumn {
            HeroIcon(R.drawable.ic_zk_proof)
            HeroGap()
            HeroTitle("Scan your passport")
            HeroLine("$name will count as you")
        }
        Spacer(Modifier.height(dimens.space24))
        targetWarning?.let {
            Text(it, style = EarthTypography.textSm, color = red, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(dimens.space8))
        }
        // A move after the switch needs both wallets' secrets: a wallet whose phrase is lost moves nothing.
        if (!targetIsNew) {
            Row(Modifier.fillMaxWidth().clickable { hasPhrase = !hasPhrase }.padding(vertical = dimens.space8), verticalAlignment = Alignment.CenterVertically) {
                Text("I have $name's phrase", style = EarthTypography.textMd, color = EarthColors.Text.textPrimary, modifier = Modifier.weight(1f))
                androidx.compose.material3.Switch(
                    checked = hasPhrase,
                    onCheckedChange = { hasPhrase = it },
                    colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = EarthAccent.ink),
                )
            }
            Spacer(Modifier.height(dimens.space8))
        }
        PillButton("Scan passport", enabled = targetIsNew || hasPhrase) { onScan(target) }
        Text(
            "Back",
            style = EarthTypography.textMd,
            fontWeight = FontWeight.SemiBold,
            color = EarthColors.Text.textTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onBack).padding(vertical = dimens.space12),
        )
    }
}

/**
 * After a switch has landed: what the identity this one replaced (another
 * wallet on this phone, same passport) still holds, and a move for each.
 * Each move is proven with both identities' secrets and paid from the
 * previous wallet's private ERTH; it shows as done only once confirmed.
 * Once moved, the previous identity can never hold either again.
 */
@Composable
fun MoveOfferCard(
    offer: network.erth.wallet.privacy.PrivacySession.MoveOffer,
    onBringHandle: () -> Unit,
    onBringVote: () -> Unit,
    onCheckMoves: () -> Unit,
    modifier: Modifier = Modifier,
    now: Long = System.currentTimeMillis() / 1000,
) {
    val dimens = EarthTheme.dimens
    // Before the suggested time a move asks first; it is never sent on its own.
    // The suggestion is already capped before the deadline.
    var early by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun move(go: () -> Unit) { if (offer.suggestedAt > now) early = go else go() }
    early?.let { go ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { early = null },
            title = { androidx.compose.material3.Text("Move now?") },
            text = { androidx.compose.material3.Text("Timing can link it to your switch. Suggested: ${moveTime(offer.suggestedAt)}.") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { early = null; go() }) { androidx.compose.material3.Text("Move now") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { early = null }) { androidx.compose.material3.Text("Wait") } },
        )
    }
    val handleInFlight = offer.inFlight.any { it.kind == PendingMove.HANDLE && !it.confirmed }
    val voteInFlight = offer.inFlight.any { it.kind == PendingMove.CARETAKER && !it.confirmed }
    Column(modifier.fillMaxWidth()) {
        Text(
            if (offer.withinWallet) "From your previous identity" else "From ${offer.fromName}",
            style = EarthTypography.textMd, fontWeight = FontWeight.SemiBold, color = EarthColors.Text.textPrimary,
        )
        val d = offer.deadline
        val urgent = d > 0 && d - now <= network.erth.wallet.privacy.PrivacyWallet.MOVE_DEADLINE_MARGIN_SECONDS
        Text(
            when {
                urgent -> "Move now · lost after ${moveTime(d)}"
                offer.suggestedAt > now -> "Suggested after ${moveTime(offer.suggestedAt)}" + (if (d > 0) " · by ${moveTime(d)}" else "")
                d > 0 -> "Move by ${moveTime(d)}"
                else -> "Move before switching again"
            },
            style = EarthTypography.textSm,
            color = if (urgent) EarthColors.Utility.ErrorRed.utilityError700 else EarthColors.Text.textTertiary,
        )
        Spacer(Modifier.height(dimens.space12))
        if (offer.handle.isNotEmpty() && !handleInFlight) {
            if (offer.handleLive) PillButton("Bring @${offer.handle}") { move(onBringHandle) }
            else Note("@${offer.handle} expired · can't move")
            Spacer(Modifier.height(dimens.space8))
        }
        if (offer.voteLive && !voteInFlight) {
            PillButton("Bring Caretaker split") { move(onBringVote) }
            Spacer(Modifier.height(dimens.space8))
        }
        if (offer.inFlight.isNotEmpty()) {
            Note(if (handleInFlight || voteInFlight) "Move sent · waiting for the chain" else "Move confirmed · not recorded yet")
            Spacer(Modifier.height(dimens.space8))
            PillButton("Check again", primary = false, onClick = onCheckMoves)
        }
    }
}

/**
 * Before a switch: what this identity holds whose lease ends within
 * PrivacyWallet.RENEW_FIRST_WINDOW_SECONDS ([handleExpiresAt],
 * [voteExpiresAt]; 0 for none), as a prompt to renew it first, or null.
 */
internal fun renewSoonText(handle: String, handleExpiresAt: Long, voteExpiresAt: Long, now: Long): String? {
    val soon = network.erth.wallet.privacy.handles.Handles.satAdd(now, network.erth.wallet.privacy.PrivacyWallet.RENEW_FIRST_WINDOW_SECONDS)
    val what = listOfNotNull(
        handleExpiresAt.takeIf { handle.isNotEmpty() && it in (now + 1) until soon }?.let { "@$handle (its lease ends ${moveTime(it)})" },
        voteExpiresAt.takeIf { it in (now + 1) until soon }?.let { "your caretaker vote (it ends ${moveTime(it)})" },
    )
    if (what.isEmpty()) return null
    return "Renew ${what.joinToString(" and ")} before you switch. After the switch a move can bring it to your new " +
        "identity only until its lease ends, and this identity can no longer renew it; renewed now, the move has a full lease."
}

/** A suggested move time, in the phone's time zone. */
private fun moveTime(at: Long): String =
    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(at * 1000))
