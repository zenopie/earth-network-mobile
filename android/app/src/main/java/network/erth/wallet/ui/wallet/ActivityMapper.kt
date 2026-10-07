package network.erth.wallet.ui.wallet

import network.erth.wallet.chain.Explorer
import network.erth.wallet.privacy.ActivityCoin
import network.erth.wallet.privacy.PrivateActivity
import network.erth.wallet.privacy.PrivateActivityKind
import network.erth.wallet.privacy.PrivateActivityRow
import network.erth.wallet.ui.components.formatUerth
import network.erth.wallet.ui.components.shortAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

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
        counterparty = counterparty.shortAddress(),
        amount = msg.amountLabel(kind),
        timestamp = timestamp.toRelative(),
        failed = !success,
        sortTime = timestamp.toUnix() ?: 0L,
        hash = hash,
    )
}

/**
 * A private row (PrivateActivity, built from the sealed store alone) in the
 * list's terms. Its time is the wallet's own when it sent the tx, or one
 * estimated from the block height ("~"); a received note names its block.
 */
internal fun PrivateActivityRow.toActivityRow(now: Long = System.currentTimeMillis() / 1000): ActivityRow {
    val glyph = when (kind) {
        PrivateActivityKind.SEND, PrivateActivityKind.UNSHIELD, PrivateActivityKind.MOVE,
        PrivateActivityKind.MERGE, PrivateActivityKind.INFERRED -> ActivityKind.Sent
        PrivateActivityKind.STAKE, PrivateActivityKind.REDELEGATE, PrivateActivityKind.RESTAKE, PrivateActivityKind.POSITION -> ActivityKind.Staked
        PrivateActivityKind.UNSTAKE -> ActivityKind.Unstaked
        PrivateActivityKind.CLAIM_ANML -> ActivityKind.ClaimedAnml
        PrivateActivityKind.REGISTER, PrivateActivityKind.SWITCH, PrivateActivityKind.HANDLE -> ActivityKind.Registered
        PrivateActivityKind.SWAP, PrivateActivityKind.ADD_LIQUIDITY, PrivateActivityKind.REMOVE_LIQUIDITY -> ActivityKind.Swapped
        PrivateActivityKind.VOTE, PrivateActivityKind.CARETAKER -> ActivityKind.Allocated
        else -> ActivityKind.Received
    }
    val party = counterparty.split(' ').joinToString(" ") { if (it.startsWith("earth") && it.length > 16) it.shortAddress() else it }
        .ifEmpty { if (!kind.sent && height != null) "block ${"%,d".format(height)}" else "" }
    val whenText = time?.let { (if (timeExact) "" else "~") + relative(it, now) }.orEmpty()
    return ActivityRow(
        txHash = id,
        kind = glyph,
        counterparty = party,
        amount = coinsLabel(coins),
        timestamp = when (status) {
            PrivateActivityRow.Status.PENDING -> listOf("pending", whenText).filter { it.isNotEmpty() }.joinToString(" · ")
            else -> whenText
        },
        failed = status == PrivateActivityRow.Status.FAILED,
        isPrivate = true,
        title = kind.label,
        sortTime = time ?: 0L,
        hash = hash,
        fee = fee?.takeIf { it > 0 }?.let { "${formatUerth(it)} ERTH" },
        pending = status == PrivateActivityRow.Status.PENDING,
        failure = failure,
        height = height,
    )
}

/** "−1.5 ERTH, +2 ANML": every coin a row moved, signed. */
internal fun coinsLabel(coins: List<ActivityCoin>): String =
    coins.joinToString(", ") { c ->
        val sign = if (c.amount < 0) "-" else "+"
        val v = if (c.amount == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(c.amount)
        "$sign${formatUerth(v)} ${PrivateActivity.symbol(c.denom)}"
    }

/**
 * Public rows (timestamps from the chain) and private rows (the sealed
 * store's), one list, newest first. A tx in both is shown once, as its
 * private row (the richer one).
 */
internal fun mergeActivity(public: List<ActivityRow>, private: List<ActivityRow>): List<ActivityRow> {
    val privateHashes = private.mapNotNullTo(HashSet()) { it.hash?.uppercase() }
    return (public.filter { it.hash?.uppercase() !in privateHashes } + private).sortedByDescending { it.sortTime }
}

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
    val unix = toUnix() ?: return this
    return relative(unix, Date().time / 1000)
}

/** An RFC 3339 chain timestamp to unix seconds; null when it does not parse. */
internal fun String.toUnix(): Long? = runCatching {
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .parse(substringBefore('.').removeSuffix("Z"))!!.time / 1000
}.getOrNull()

internal fun relative(unix: Long, now: Long): String {
    val minutes = (now - unix) / 60
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 1_440 -> "${minutes / 60}h ago"
        minutes < 10_080 -> "${minutes / 1_440}d ago"
        else -> SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(unix * 1000))
    }
}
