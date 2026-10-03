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
            Note("A move of @${state.handle} to another wallet was sent and is waiting for the chain. Check it on Switch identity.")
            Spacer(Modifier.height(dimens.space12))
        }
        if (state.incomingMoves.any { it.kind == PendingMove.HANDLE }) {
            Note("@${state.handle} was moved here and is waiting for the chain to confirm the move.")
            Spacer(Modifier.height(dimens.space12))
        }
        // Audit 5 (M1): entries naming this wallet's address that the store does not hold
        // (a restore loses track of a handle): renewing one is checked by the chain, at no cost if it is not ours.
        state.addressed.filter { it.handle != state.handle }.forEach { a ->
            Card {
                EarthDetailRow("Names this wallet", "@${a.handle}")
                EarthDetailRow("Expires", date(a.expiresAt))
                Spacer(Modifier.height(dimens.space8))
                Note("If this identity holds it, renew it here. If it does not, the chain refuses and nothing is charged.")
                Spacer(Modifier.height(dimens.space8))
                EarthButton(text = "Renew @${a.handle}", onClick = { onClaim(a.handle) }, modifier = Modifier.fillMaxWidth(), colors = brandButtonColors())
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
        // Clamped (audit 5, L7): a hostile lease param cannot overflow the date.
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
 * new one there. What this identity holds (its handle, its caretaker vote)
 * can be moved to the new identity first, so it keeps them with no wait;
 * otherwise the new identity waits until they lapse (up to a year).
 *
 * Audit 5: a move counts as done only once the chain confirmed it (M2); the
 * new wallet's recovery phrase is shown only after a fresh unlock and is
 * dropped when the screen is paused or left (M3); the backup box needs the
 * phrase shown first (L9); the first move fixes the target (L8).
 */
@Composable
fun SwitchIdentityScreen(
    state: PersonalState?,
    wallets: List<SecureWalletManager.WalletInfo>,
    currentIndex: Int,
    onMove: (targetIndex: Int, moveHandle: Boolean, moveCaretaker: Boolean) -> Unit,
    onContinue: (targetIndex: Int) -> Unit,
    onCreateWallet: () -> Unit,
    /** The recovery phrase of the wallet at an index, read for display only (after a fresh unlock). */
    revealPhrase: (Int) -> String?,
    /** Settles moves in flight by their tx and retries recording them in the new wallet. */
    onCheckMoves: () -> Unit,
    /** The wallet index this identity's moves already went to (null: none yet). */
    frozenTarget: Int?,
    /** What the chosen target already holds, as a warning (null: nothing). */
    targetWarning: String?,
    onTargetChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = EarthTheme.dimens
    var target by remember { mutableStateOf<Int?>(null) }
    var moveHandle by remember { mutableStateOf(true) }
    var moveCaretaker by remember { mutableStateOf(true) }
    var backedUp by remember { mutableStateOf(false) }
    var phrase by remember { mutableStateOf<String?>(null) }
    var revealedFor by remember { mutableStateOf<Int?>(null) }
    var confirming by remember { mutableStateOf(false) }
    LaunchedEffect(frozenTarget) { frozenTarget?.let { if (target != it) { target = it; phrase = null; onTargetChange(it) } } }
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
                "this wallet stops counting as you. Your handle and caretaker vote can move with you first, " +
                "so the new identity keeps them at once. Anything not moved stays with this wallet until it " +
                "lapses, and the new identity cannot claim a handle or cast a caretaker vote until then " +
                "(up to a year).",
        )
        Spacer(Modifier.height(dimens.space8))
        Note(
            "If you ever lose a wallet, nothing it holds can be moved: a new identity waits out its handle " +
                "and caretaker vote. Back up every wallet's recovery phrase.",
        )
        Spacer(Modifier.height(dimens.space16))
        EarthLabel("Switch to")
        val others = wallets.filter { it.index != currentIndex }
        if (others.isEmpty()) Note("You have no other wallet on this phone. Create one first.")
        others.forEach { w ->
            val selectable = frozenTarget == null || frozenTarget == w.index
            fun pick() {
                if (!selectable || target == w.index) return
                target = w.index; phrase = null; backedUp = false
                onTargetChange(w.index)
            }
            Row(
                Modifier.fillMaxWidth().clickable(enabled = selectable) { pick() }.padding(vertical = dimens.space8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = target == w.index, onCheckedChange = { pick() }, enabled = selectable)
                Column {
                    Text(w.name, style = EarthTypography.textMd, color = EarthColors.Text.textPrimary)
                    Text(Handles.truncate(w.address, 10, 6), style = EarthTypography.textXs, color = EarthColors.Text.textTertiary)
                }
            }
        }
        if (frozenTarget != null) Note("This identity already moved to that wallet, so the switch goes there.")
        targetWarning?.let { Note(it) }
        if (frozenTarget == null) EarthButton(
            text = "Create a new wallet",
            onClick = onCreateWallet,
            modifier = Modifier.fillMaxWidth(),
            colors = EarthButtonDefaults.secondaryColors(),
        )
        Spacer(Modifier.height(dimens.space16))
        val now = System.currentTimeMillis() / 1000
        val outgoing = state?.outgoingMoves.orEmpty()
        val handleInFlight = outgoing.any { it.kind == PendingMove.HANDLE && !it.confirmed }
        val voteInFlight = outgoing.any { it.kind == PendingMove.CARETAKER && !it.confirmed }
        val inFlight = handleInFlight || voteInFlight
        val unrecorded = outgoing.any { !it.recorded }
        val handleMoved = state?.handleMovedOut == true
        val voteMoved = state?.caretakerMovedOut == true
        val holdsHandle = state?.handle?.isNotEmpty() == true && !handleInFlight
        val holdsVote = !voteInFlight && (state?.caretakerExpiresAt ?: 0L) > now &&
            (state?.caretakerSplit?.isNotEmpty() == true || state?.caretakerSplitUnknown == true)
        fun suffix(moved: Boolean, flying: Boolean) = when {
            flying -> " (sent, waiting for the chain)"
            moved -> " (moved)"
            else -> ""
        }
        if (holdsHandle || holdsVote || inFlight || handleMoved || voteMoved) {
            EarthLabel("Move first")
            if (holdsHandle || handleInFlight || handleMoved) Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = moveHandle && holdsHandle, onCheckedChange = { moveHandle = it }, enabled = holdsHandle)
                val name = state?.handle?.takeIf { it.isNotEmpty() } ?: outgoing.firstOrNull { it.kind == PendingMove.HANDLE }?.handle.orEmpty()
                Text("Move " + (if (name.isNotEmpty()) "@$name" else "my handle") + suffix(handleMoved, handleInFlight), style = EarthTypography.textSm, color = EarthColors.Text.textPrimary)
            }
            if (holdsVote || voteInFlight || voteMoved) Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = moveCaretaker && holdsVote, onCheckedChange = { moveCaretaker = it }, enabled = holdsVote)
                Text("Move my caretaker vote" + suffix(voteMoved, voteInFlight), style = EarthTypography.textSm, color = EarthColors.Text.textPrimary)
            }
            Note("Each move is a private transaction with its own fee. Once moved, this identity can never hold one again.")
            if (inFlight) {
                Spacer(Modifier.height(dimens.space8))
                Note("A move was sent but the chain has not confirmed it yet. Check again before switching; the new wallet already counts it as pending.")
            }
            if (unrecorded) {
                Spacer(Modifier.height(dimens.space8))
                Note("The new wallet has not recorded a move yet. It finds it on its own when it syncs; you can also record it now.")
            }
            if (inFlight || unrecorded) {
                Spacer(Modifier.height(dimens.space8))
                EarthButton(
                    text = "Check the moves again",
                    onClick = onCheckMoves,
                    modifier = Modifier.fillMaxWidth(),
                    colors = EarthButtonDefaults.secondaryColors(),
                )
            }
            Spacer(Modifier.height(dimens.space8))
        }
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
        // Ticked only once the phrase was shown for this target (audit 5, L9).
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
        val ready = t != null && backedUp && canTick && !inFlight
        val pendingMoves = (holdsHandle && moveHandle) || (holdsVote && moveCaretaker)
        if (pendingMoves) {
            EarthButton(
                text = "Move to the new wallet",
                onClick = { if (t != null) onMove(t, moveHandle && holdsHandle, moveCaretaker && holdsVote) },
                enabled = ready,
                modifier = Modifier.fillMaxWidth(),
                colors = brandButtonColors(),
            )
        } else {
            EarthButton(
                text = "Switch: register there",
                onClick = { if (t != null) onContinue(t) },
                enabled = ready,
                modifier = Modifier.fillMaxWidth(),
                colors = brandButtonColors(),
            )
        }
    }
}
