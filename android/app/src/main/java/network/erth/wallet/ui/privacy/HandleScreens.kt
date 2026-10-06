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
import network.erth.wallet.privacy.sync.PendingMove
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
        EarthLabel("Your handle")
        Spacer(Modifier.height(dimens.space8))
        Note(
            "A handle lets anyone pay you by name: their wallet looks it up in the chain's public " +
                "directory and sends to your shielded address. Who holds a handle is not public. " +
                "A handle lasts a year from each renewal and never renews on its own; the app reminds " +
                "you before it ends.",
        )
        Spacer(Modifier.height(dimens.space12))
        if (state == null) {
            Note("Loading…")
            return@Page
        }
        if (!state.identityLive) {
            Note("Register this wallet (Identity) to claim a handle.")
            return@Page
        }
        state.reminders.filter { it is Reminders.Reminder.HandleExpiring && it.handle == state.handle || it is Reminders.Reminder.HandlePaysElsewhere }.forEach {
            ReminderBanner(Reminders.text(it, now), onClick = onRenew)
        }
        state.directoryError?.let { Note("Couldn't read the handle directory: $it") }
        val e = state.handleEntry
        val movingOut = state.outgoingMoves.any { it.kind == PendingMove.HANDLE && !it.confirmed }
        if (movingOut) {
            Note("A move of @${state.handle} to the identity that replaced this one was sent and is waiting for the chain. Check it on Identity in the new wallet.")
            Spacer(Modifier.height(dimens.space12))
        }
        if (state.incomingMoves.any { it.kind == PendingMove.HANDLE }) {
            Note("@${state.handle} was moved here and is waiting for the chain to confirm the move.")
            Spacer(Modifier.height(dimens.space12))
        }
        // Entries naming this wallet's address that the store does not hold
        // (a restore loses track of a handle): renewing one is checked by the chain, at no cost if it is not ours.
        // Only while no handle is held (a bind of another would free it), and renew-only.
        if (state.handle.isEmpty()) state.addressed.forEach { a ->
            Card {
                EarthDetailRow("Names this wallet", "@${a.handle}")
                EarthDetailRow("Expires", date(a.expiresAt))
                Spacer(Modifier.height(dimens.space8))
                Note("If this identity holds it, renew it here. If it does not, the chain refuses and nothing is charged.")
                Spacer(Modifier.height(dimens.space8))
                EarthButton(text = "Renew @${a.handle}", onClick = { onRenewAddressed(a.handle) }, modifier = Modifier.fillMaxWidth(), colors = brandButtonColors())
            }
            Spacer(Modifier.height(dimens.space12))
        }
        if (state.handle.isNotEmpty() && !movingOut) {
            Card {
                EarthDetailRow("Handle", "@${state.handle}")
                if (e != null) {
                    val st = e.statusAt(now)
                    EarthDetailRow(
                        "Status",
                        when (st) {
                            HandleEntry.LIVE -> "Live"
                            HandleEntry.RENEWAL -> "Expired: renewal period (only you may renew it)"
                            else -> "Released"
                        },
                    )
                    EarthDetailRow("Expires", date(e.expiresAt))
                    EarthDetailRow("Renewable until", date(e.renewalUntil))
                    if (st == HandleEntry.RENEWAL) {
                        // Past expiry, a renewal is bounded like a claim, and the handle cannot be moved.
                        val claimFrom = if (state.predecessorAt > 0) Handles.satAdd(Handles.satAdd(state.predecessorAt, state.handleLeaseSeconds), 86_400 + 3_600) else 0L
                        Spacer(Modifier.height(dimens.space8))
                        Note(
                            "Past its expiry, renewing counts as a new claim" +
                                (if (claimFrom > now) ", which this identity can make from ${date(claimFrom)} (it replaced another); renew before ${date(e.renewalUntil)} or the handle is freed." else ".") +
                                " A handle in this period cannot be moved.",
                        )
                    }
                    EarthDetailRow(
                        "Pays",
                        if (e.address == state.shieldedAddress) "this wallet (${Handles.truncate(e.address)})"
                        else "another address (${Handles.truncate(e.address)})",
                    )
                } else {
                    EarthDetailRow("Status", "Not in the directory")
                }
                Spacer(Modifier.height(dimens.space12))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(dimens.space8)) {
                    EarthButton(
                        text = if (e != null && e.address != state.shieldedAddress) "Renew to this wallet" else "Renew for 1 year",
                        onClick = onRenew,
                        modifier = Modifier.weight(1f),
                        colors = brandButtonColors(),
                    )
                    EarthButton(
                        text = "Share",
                        onClick = { onShare(state.handle) },
                        modifier = Modifier.weight(1f),
                        colors = EarthButtonDefaults.secondaryColors(),
                    )
                }
                Spacer(Modifier.height(dimens.space8))
                EarthButton(
                    text = "Release @${state.handle}",
                    onClick = onRelease,
                    modifier = Modifier.fillMaxWidth(),
                    colors = destructiveButtonColors(),
                )
            }
            Spacer(Modifier.height(dimens.space16))
        }
        if (state.handleMovedOut && state.handle.isEmpty()) {
            Note("This identity moved its handle to another identity, so it cannot claim one again.")
            return@Page
        }
        if (movingOut) return@Page
        // Clamped: a hostile lease param cannot overflow the date.
        val waitUntil = if (state.predecessorAt > 0) Handles.satAdd(Handles.satAdd(state.predecessorAt, state.handleLeaseSeconds), 86_400 + 3_600) else 0L
        if (state.handle.isEmpty() && waitUntil > now) {
            Note(
                "This identity replaced another on ${date(state.predecessorAt)}, so it can claim a new handle " +
                    "from ${date(waitUntil)} (anything the old one held has lapsed by then). Moving a handle " +
                    "before a switch keeps it with no wait. If this identity already holds a handle (a restored " +
                    "wallet can lose track of it), enter its name to renew it: the chain checks, and charges " +
                    "nothing if it does not.",
            )
            Spacer(Modifier.height(dimens.space12))
        }
        EarthLabel(if (state.handle.isEmpty()) (if (waitUntil > now) "Renew a handle this identity holds" else "Claim a handle") else "Change to another handle")
        Spacer(Modifier.height(dimens.space8))
        val parsed = Handles.parse(input)
        EarthTextField(
            value = input,
            onValueChange = { input = it.trim() },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("@name") },
            error = if (input.isNotEmpty() && parsed == null) "3-32 of a-z, 0-9 and -, no dash at either end" else null,
        )
        Spacer(Modifier.height(dimens.space8))
        Note(
            if (state.handle.isEmpty()) "One handle per person. If it is taken the chain refuses the claim and nothing but the fee is spent."
            else "Changing frees @${state.handle} at once: anyone may claim it.",
        )
        Spacer(Modifier.height(dimens.space12))
        EarthButton(
            text = if (state.handle.isEmpty()) "Claim ${parsed?.let { "@$it" } ?: "handle"}" else "Change to ${parsed?.let { "@$it" } ?: "…"}",
            onClick = { parsed?.let(onClaim) },
            enabled = parsed != null && parsed != state.handle,
            modifier = Modifier.fillMaxWidth(),
            colors = brandButtonColors(),
        )
    }
}

/**
 * A voluntary switch of identity: the same passport registered from another
 * of this phone's wallets. The chain zeroes this wallet's leaf and makes a
 * new one there. Once the switch has landed, the new identity can bring this
 * one's handle and caretaker vote over ([MoveOfferCard], on Identity in the
 * new wallet): a move proves knowledge of both identities' secrets, so it
 * comes after the switch and needs both recovery phrases on this phone.
 *
 * The new wallet's recovery phrase is shown only after a fresh unlock and
 * is dropped when the screen is paused or left; the backup box needs the
 * phrase shown first.
 */
@Composable
fun SwitchIdentityScreen(
    wallets: List<SecureWalletManager.WalletInfo>,
    currentIndex: Int,
    onContinue: (targetIndex: Int) -> Unit,
    onCreateWallet: () -> Unit,
    /** The recovery phrase of the wallet at an index, read for display only (after a fresh unlock). */
    revealPhrase: (Int) -> String?,
    /** What the chosen target already holds, as a warning (null: nothing). */
    targetWarning: String?,
    onTargetChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** Why the chosen target cannot be switched to (its identity registered before), or null. */
    targetBlocked: String? = null,
    /**
     * What this identity's predecessor (on this phone) still holds that has
     * not moved here, or null: a move goes one step, so a further switch
     * strands it there for good.
     */
    unmoved: String? = null,
) {
    val dimens = EarthTheme.dimens
    var target by remember { mutableStateOf<Int?>(null) }
    var backedUp by remember { mutableStateOf(false) }
    var phrase by remember { mutableStateOf<String?>(null) }
    var revealedFor by remember { mutableStateOf<Int?>(null) }
    var confirming by remember { mutableStateOf(false) }
    var strandAccepted by remember { mutableStateOf(false) }
    // The phrase lives only while the screen is in front: gone on pause and when left.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_PAUSE || e == Lifecycle.Event.ON_STOP) phrase = null }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs); phrase = null }
    }
    if (confirming) {
        network.erth.wallet.ui.unlock.ConfirmUnlockDialog(
            onConfirmed = {
                confirming = false
                val t = target
                phrase = t?.let(revealPhrase)
                if (phrase != null) revealedFor = t
            },
            onDismiss = { confirming = false },
        )
    }
    Page(modifier) {
        EarthLabel("Switch identity")
        Spacer(Modifier.height(dimens.space8))
        Note(
            "Switching moves your personhood to another wallet: register the same passport there and " +
                "this wallet stops counting as you. Once the switch lands, open Identity in the new wallet " +
                "to bring your handle and caretaker vote over; each is a private move proven with both " +
                "wallets' recovery phrases, which stay on this phone. Do it before switching again: a move " +
                "goes only to the passport's live identity. Anything not moved stays with this wallet until " +
                "it lapses, and the new identity cannot claim a handle or cast a caretaker vote until then " +
                "(up to a year).",
        )
        Spacer(Modifier.height(dimens.space8))
        Note(
            "A switch from a wallet whose recovery phrase is lost cannot move anything: the move needs " +
                "that wallet's secret. Its handle and caretaker vote wait out their leases. Back up every " +
                "wallet's recovery phrase.",
        )
        if (unmoved != null) {
            Spacer(Modifier.height(dimens.space8))
            Text(
                "$unmoved A move goes only from an identity to the one that replaced it, so after another switch " +
                    "it can never move. Bring it here first, from the Identity screen.",
                style = EarthTypography.textSm,
                color = EarthColors.Utility.ErrorRed.utilityError700,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = strandAccepted, onCheckedChange = { strandAccepted = it })
                Text("Switch anyway and leave it where it is", style = EarthTypography.textSm, color = EarthColors.Text.textPrimary)
            }
        }
        Spacer(Modifier.height(dimens.space16))
        EarthLabel("Switch to")
        val others = wallets.filter { it.index != currentIndex }
        if (others.isEmpty()) Note("You have no other wallet on this phone. Create one first.")
        others.forEach { w ->
            fun pick() {
                if (target == w.index) return
                target = w.index; phrase = null; backedUp = false
                onTargetChange(w.index)
            }
            Row(
                Modifier.fillMaxWidth().clickable { pick() }.padding(vertical = dimens.space8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = target == w.index, onCheckedChange = { pick() })
                Column {
                    Text(w.name, style = EarthTypography.textMd, color = EarthColors.Text.textPrimary)
                    Text(Handles.truncate(w.address, 10, 6), style = EarthTypography.textXs, color = EarthColors.Text.textTertiary)
                }
            }
        }
        targetBlocked?.let {
            Text(it, style = EarthTypography.textSm, color = EarthColors.Utility.ErrorRed.utilityError700)
        } ?: targetWarning?.let { Note(it) }
        EarthButton(
            text = "Create a new wallet",
            onClick = onCreateWallet,
            modifier = Modifier.fillMaxWidth(),
            colors = EarthButtonDefaults.secondaryColors(),
        )
        Spacer(Modifier.height(dimens.space16))
        val shown = phrase
        if (shown != null) {
            // Kept out of screenshots and the recents thumbnail while shown.
            network.erth.wallet.ui.components.SecureScreen()
            Card {
                Text(
                    shown.trim().split(Regex("\\s+")).mapIndexed { i, w -> "${i + 1}. $w" }.joinToString("   "),
                    style = EarthTypography.textMd,
                    color = EarthColors.Text.textPrimary,
                )
            }
            Note("Write these words down, in order, and keep them offline. Anyone with them controls that wallet.")
        }
        EarthButton(
            text = if (shown == null) "Show the new wallet's recovery phrase" else "Hide the recovery phrase",
            onClick = { if (shown == null) confirming = true else phrase = null },
            enabled = target != null,
            modifier = Modifier.fillMaxWidth(),
            colors = EarthButtonDefaults.secondaryColors(),
        )
        Spacer(Modifier.height(dimens.space8))
        // Ticked only once the phrase was shown for this target.
        val canTick = target != null && revealedFor == target
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = backedUp && canTick, onCheckedChange = { backedUp = it }, enabled = canTick)
            Text(
                if (canTick) "I have backed up the new wallet's recovery phrase" else "Show the new wallet's recovery phrase to confirm you have backed it up",
                style = EarthTypography.textSm, color = EarthColors.Text.textPrimary,
            )
        }
        Spacer(Modifier.height(dimens.space16))
        val t = target
        EarthButton(
            text = "Switch: register there",
            onClick = { if (t != null) onContinue(t) },
            enabled = t != null && backedUp && canTick && targetBlocked == null && (unmoved == null || strandAccepted),
            modifier = Modifier.fillMaxWidth(),
            colors = brandButtonColors(),
        )
    }
}

/**
 * After a switch has landed: what the identity this one replaced (another
 * wallet on this phone, same passport) still holds, and a move for each.
 * Each move is proven with both identities' secrets and paid from the
 * previous wallet's private ERTH; it shows as done only once confirmed.
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
    var early by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun move(go: () -> Unit) { if (offer.suggestedAt > now) early = go else go() }
    early?.let { go ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { early = null },
            title = { androidx.compose.material3.Text("Move before the suggested time?") },
            text = {
                androidx.compose.material3.Text(
                    "A move soon after your switch can be linked to it by its timing. The wallet suggests waiting until " +
                        "${moveTime(offer.suggestedAt)}. You can still move now.",
                )
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { early = null; go() }) { androidx.compose.material3.Text("Move now") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { early = null }) { androidx.compose.material3.Text("Wait") } },
        )
    }
    val handleInFlight = offer.inFlight.any { it.kind == PendingMove.HANDLE && !it.confirmed }
    val voteInFlight = offer.inFlight.any { it.kind == PendingMove.CARETAKER && !it.confirmed }
    Column(modifier.fillMaxWidth()) {
        EarthLabel("From your previous identity")
        Spacer(Modifier.height(dimens.space8))
        Note(
            "This identity replaced the one in ${offer.fromName}. You can bring what it holds here with no wait, " +
                "while this is still the passport's live identity. The fee comes from ${offer.fromName}'s private ERTH.",
        )
        if (offer.suggestedAt > 0) {
            Spacer(Modifier.height(dimens.space8))
            Note(
                if (offer.suggestedAt > now) {
                    "Suggested: move after ${moveTime(offer.suggestedAt)}. A move right after a switch can be linked to " +
                        "it by its timing, so the wallet picked a random time for you. Nothing hurries it while this stays " +
                        "your live identity, and the wallet reminds you then; it never moves anything on its own."
                } else {
                    "The suggested time to move has come. Nothing hurries it while this stays your live identity; " +
                        "move before you switch again."
                },
            )
        }
        Spacer(Modifier.height(dimens.space8))
        if (offer.handle.isNotEmpty() && !handleInFlight) {
            if (offer.handleLive) EarthButton(
                text = "Bring your handle @${offer.handle} to this identity",
                onClick = { move(onBringHandle) },
                modifier = Modifier.fillMaxWidth(),
                colors = brandButtonColors(),
            ) else Note("@${offer.handle} is in its renewal period: only a live handle can move, and the old identity can no longer renew it.")
            Spacer(Modifier.height(dimens.space8))
        }
        if (offer.voteLive && !voteInFlight) {
            EarthButton(
                text = "Bring your Caretaker split to this identity",
                onClick = { move(onBringVote) },
                modifier = Modifier.fillMaxWidth(),
                colors = brandButtonColors(),
            )
            Spacer(Modifier.height(dimens.space8))
        }
        if (offer.inFlight.isNotEmpty()) {
            Note(
                if (handleInFlight || voteInFlight) "A move was sent and the chain has not confirmed it yet; this identity already counts it as pending."
                else "A confirmed move has not been recorded in this wallet yet. It finds it on its own when it syncs; you can also record it now.",
            )
            Spacer(Modifier.height(dimens.space8))
            EarthButton(
                text = "Check the moves again",
                onClick = onCheckMoves,
                modifier = Modifier.fillMaxWidth(),
                colors = EarthButtonDefaults.secondaryColors(),
            )
        }
        Note("Once moved, the previous identity can never hold a handle or caretaker vote again.")
    }
}

/** A suggested move time, in the phone's time zone. */
private fun moveTime(at: Long): String =
    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(at * 1000))
