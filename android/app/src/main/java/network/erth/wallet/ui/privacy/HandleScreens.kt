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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
        state.reminders.filterIsInstance<Reminders.Reminder.HandleExpiring>().forEach {
            ReminderBanner(Reminders.text(it, now), onClick = onRenew)
        }
        state.directoryError?.let { Note("Couldn't read the handle directory: $it") }
        val e = state.handleEntry
        if (state.handle.isNotEmpty()) {
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
        val waitUntil = if (state.predecessorAt > 0) state.predecessorAt + state.handleLeaseSeconds + 86_400 + 3_600 else 0L
        if (state.handle.isEmpty() && waitUntil > now) {
            Note(
                "This identity replaced another on ${date(state.predecessorAt)}, so it can claim a handle " +
                    "from ${date(waitUntil)} (anything the old one held has lapsed by then). Moving a handle " +
                    "before a switch keeps it with no wait.",
            )
            return@Page
        }
        EarthLabel(if (state.handle.isEmpty()) "Claim a handle" else "Change to another handle")
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
 */
@Composable
fun SwitchIdentityScreen(
    state: PersonalState?,
    wallets: List<SecureWalletManager.WalletInfo>,
    currentIndex: Int,
    onMove: (targetIndex: Int, moveHandle: Boolean, moveCaretaker: Boolean) -> Unit,
    onContinue: (targetIndex: Int) -> Unit,
    onCreateWallet: () -> Unit,
    /** The recovery phrase of the wallet at an index, read for display only. */
    revealPhrase: (Int) -> String?,
    moved: Set<String>,
    modifier: Modifier = Modifier,
) {
    val dimens = EarthTheme.dimens
    var target by remember { mutableStateOf<Int?>(null) }
    var moveHandle by remember { mutableStateOf(true) }
    var moveCaretaker by remember { mutableStateOf(true) }
    var backedUp by remember { mutableStateOf(false) }
    var phrase by remember { mutableStateOf<String?>(null) }
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
            Row(
                Modifier.fillMaxWidth().clickable { target = w.index; phrase = null }.padding(vertical = dimens.space8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = target == w.index, onCheckedChange = { target = w.index; phrase = null })
                Column {
                    Text(w.name, style = EarthTypography.textMd, color = EarthColors.Text.textPrimary)
                    Text(Handles.truncate(w.address, 10, 6), style = EarthTypography.textXs, color = EarthColors.Text.textTertiary)
                }
            }
        }
        EarthButton(
            text = "Create a new wallet",
            onClick = onCreateWallet,
            modifier = Modifier.fillMaxWidth(),
            colors = EarthButtonDefaults.secondaryColors(),
        )
        Spacer(Modifier.height(dimens.space16))
        val holdsHandle = state?.handle?.isNotEmpty() == true
        val holdsVote = (state?.caretakerExpiresAt ?: 0L) > System.currentTimeMillis() / 1000 && state?.caretakerSplit?.isNotEmpty() == true
        if (holdsHandle || holdsVote) {
            EarthLabel("Move first")
            if (holdsHandle) Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = moveHandle, onCheckedChange = { moveHandle = it })
                Text("Move @${state?.handle}" + if ("handle" in moved) " (moved)" else "", style = EarthTypography.textSm, color = EarthColors.Text.textPrimary)
            }
            if (holdsVote || "caretaker" in moved) Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = moveCaretaker, onCheckedChange = { moveCaretaker = it })
                Text("Move my caretaker vote" + if ("caretaker" in moved) " (moved)" else "", style = EarthTypography.textSm, color = EarthColors.Text.textPrimary)
            }
            Note("Each move is a private transaction with its own fee. Once moved, this identity can never hold one again.")
            Spacer(Modifier.height(dimens.space8))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = backedUp, onCheckedChange = { backedUp = it })
            Text("I have backed up the new wallet's recovery phrase", style = EarthTypography.textSm, color = EarthColors.Text.textPrimary)
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
            onClick = { phrase = if (shown == null) target?.let(revealPhrase) else null },
            enabled = target != null,
            modifier = Modifier.fillMaxWidth(),
            colors = EarthButtonDefaults.secondaryColors(),
        )
        Spacer(Modifier.height(dimens.space16))
        val t = target
        val pendingMoves = (holdsHandle && moveHandle && "handle" !in moved) || (holdsVote && moveCaretaker && "caretaker" !in moved)
        if (pendingMoves) {
            EarthButton(
                text = "Move to the new wallet",
                onClick = { if (t != null) onMove(t, moveHandle && holdsHandle, moveCaretaker && holdsVote) },
                enabled = t != null && backedUp,
                modifier = Modifier.fillMaxWidth(),
                colors = brandButtonColors(),
            )
        } else {
            EarthButton(
                text = "Switch: register there",
                onClick = { if (t != null) onContinue(t) },
                enabled = t != null && backedUp,
                modifier = Modifier.fillMaxWidth(),
                colors = brandButtonColors(),
            )
        }
    }
}
