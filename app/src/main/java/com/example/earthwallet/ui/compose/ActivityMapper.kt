package network.erth.wallet.ui.compose

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import network.erth.wallet.chain.Explorer

/**
 * Chain transactions, resolved into rows a person can read.
 *
 * This is the layer their app spends the most code on and the one that could
 * not be ported at all: theirs resolves a Zcash transaction against a memo, a
 * pool, an address book and a sync state. Earth's equivalent is a message type
 * and a signer, which is enough to name what happened.
 *
 * Anything unrecognised falls through to the raw message name rather than being
 * dropped. A wallet that silently hides transactions it does not understand is
 * worse than one that shows an unfamiliar word — the second can be searched
 * for, the first looks like funds vanished.
 */
internal fun Explorer.Tx.toActivityRow(self: String): ActivityRow {
    val type = types.firstOrNull().orEmpty()
    val msg = messages.firstOrNull()

    val kind = when (type) {
        "MsgSend" -> if (msg?.optString("from_address") == self) {
            ActivityKind.Sent
        } else {
            ActivityKind.Received
        }
        "MsgDelegate" -> ActivityKind.Staked
        "MsgUndelegate" -> ActivityKind.Unstaked
        "MsgBeginRedelegate" -> ActivityKind.Staked
        "MsgWithdrawDelegatorReward" -> ActivityKind.Claimed
        "MsgRegister" -> ActivityKind.Registered
        // Distinct from registration: you register once and claim every day,
        // so folding them together labels the whole history "Registered".
        "MsgClaimAnml" -> ActivityKind.ClaimedAnml
        "MsgSwap" -> ActivityKind.Swapped
        "MsgSetAllocations", "MsgSetAllocation" -> ActivityKind.Allocated
        else -> ActivityKind.Sent
    }

    val counterparty = when (kind) {
        ActivityKind.Sent -> msg?.optString("to_address").orEmpty()
        ActivityKind.Received -> msg?.optString("from_address").orEmpty()
        ActivityKind.Staked, ActivityKind.Unstaked, ActivityKind.Claimed ->
            msg?.optString("validator_address").orEmpty()
        else -> ""
    }.ifEmpty {
        // Nothing to name on the other side — say what the message was instead,
        // spaced out so "SetAllocations" does not read as one long token.
        type.removePrefix("Msg").splitCamelCase()
    }

    return ActivityRow(
        txHash = hash,
        kind = kind,
        counterparty = counterparty.abbreviate(),
        amount = msg.amountLabel(kind),
        timestamp = timestamp.toRelative(),
        failed = !success,
    )
}

/** "earth1jtc…aar6" — enough to recognise an address you know, short enough for a row. */
private fun String.abbreviate(): String =
    if (length <= 16) this else "${take(10)}…${takeLast(4)}"

/**
 * The signed amount, from the message's own coin field.
 *
 * The sign is which way the balance moved, not which way the transaction went:
 * staking and sending both leave the spendable balance, so both are negative,
 * while unstaking and claiming return to it. Anything that does not move the
 * balance in a way this message can state gets no sign at all rather than a
 * guessed one.
 */
private fun org.json.JSONObject?.amountLabel(kind: ActivityKind): String {
    if (this == null) return ""
    val coin = optJSONArray("amount")?.optJSONObject(0) ?: optJSONObject("amount")
    val raw = coin?.optString("amount")?.toLongOrNull() ?: return ""
    val denom = coin.optString("denom").removePrefix("u").uppercase()
    val sign = when (kind) {
        ActivityKind.Sent, ActivityKind.Staked -> "-"
        ActivityKind.Received, ActivityKind.Unstaked, ActivityKind.Claimed -> "+"
        else -> ""
    }
    return "$sign${formatUerth(raw)} $denom"
}

/** "SetAllocations" -> "Set allocations". */
private fun String.splitCamelCase(): String =
    replace(Regex("(?<=[a-z])(?=[A-Z])"), " ").replaceFirstChar { it.uppercase() }
        .let { it.take(1) + it.drop(1).lowercase() }

/**
 * Relative for the recent past, absolute beyond a week.
 *
 * "3 days ago" is easier to place than a date while the memory is fresh, and
 * useless once it is not — nobody counts back 43 days.
 */
private fun String.toRelative(): String {
    val parsed = runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .parse(substringBefore('.').removeSuffix("Z"))
    }.getOrNull() ?: return this

    val minutes = (Date().time - parsed.time) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 1_440 -> "${minutes / 60}h ago"
        minutes < 10_080 -> "${minutes / 1_440}d ago"
        else -> SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(parsed)
    }
}
