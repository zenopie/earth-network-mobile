package network.erth.wallet.ui.wallet

import network.erth.wallet.chain.Explorer
import network.erth.wallet.privacy.ActivityCoin
import network.erth.wallet.privacy.PrivateActivity
import network.erth.wallet.privacy.PrivateActivityKind
import network.erth.wallet.privacy.PrivateActivityRow
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * One line of the wallet's activity, from either source: a public tx this
 * wallet signed (looked up by hash, SentTxLog) or a private row built from
 * the sealed store alone (PrivateActivity). Carries everything the wallet
 * knows about it, so the list can show three words of it and the detail
 * sheet all of it. As iOS's ActivityEntry.
 *
 * Anything unrecognised falls through to the message's own name rather than
 * being dropped: a wallet that hides txs it does not understand looks like
 * funds vanished.
 */
data class ActivityEntry(
    val id: String,
    /** "Sent", "Swapped", "Gas from Earth". */
    val title: String,
    /** The detail sheet's Type: what it was, as a noun. */
    val typeName: String,
    val glyph: Glyph,
    /** Signed: negative left the wallet, positive came in. */
    val coins: List<ActivityCoin>,
    /** As recorded: a handle, an address, a validator, "proposal 4". */
    val counterparty: String,
    val status: Status,
    val failure: String?,
    /** Unix seconds; [timeExact] false when estimated from the block height. */
    val time: Long?,
    val timeExact: Boolean,
    /** What this wallet paid, as positive amounts (empty: none, or unknown). */
    val fee: List<ActivityCoin>,
    val feePayer: String?,
    val height: Long?,
    val isPrivate: Boolean,
    /** The tx hash, when there is one (a received note has none). */
    val hash: String?,
    val memo: String? = null,
    /** "84,123 of 200,000" (public txs). */
    val gas: String? = null,
    /** The private kind, or null for a public tx. */
    val kind: PrivateActivityKind? = null,
) {
    enum class Status { COMPLETED, PENDING, FAILED }

    /** The list's leading mark when the row is not about one coin. */
    enum class Glyph { SEND, RECEIVE, SWAP, STAKE, REGISTER, VOTE, HANDLE, OTHER }

    data class Detail(val label: String, val value: String, val copy: String? = null)

    val sortTime: Long get() = time ?: 0L
    val outs: List<ActivityCoin> get() = coins.filter { it.amount < 0 }
    val ins: List<ActivityCoin> get() = coins.filter { it.amount > 0 }

    /**
     * The one amount the row shows: what came in for a swap, a claim, a
     * registration or anything received; what left for the rest; the other
     * side when there is only that one.
     */
    val primary: ActivityCoin?
        get() {
            val prefersIn = when (kind) {
                PrivateActivityKind.SWAP, PrivateActivityKind.REMOVE_LIQUIDITY, PrivateActivityKind.CLAIM_ANML, PrivateActivityKind.REGISTER -> true
                PrivateActivityKind.INFERRED -> false
                null -> glyph == Glyph.RECEIVE || glyph == Glyph.SWAP
                else -> !kind.sent
            }
            val (a, b) = if (prefersIn) ins to outs else outs to ins
            return a.firstOrNull() ?: b.firstOrNull()
        }

    /** The coin's logo when the row is about moving it, the kind's glyph when it is about an action. */
    val showsCoin: Boolean get() = glyph in setOf(Glyph.SEND, Glyph.RECEIVE, Glyph.SWAP) && primary != null

    /** "Out of gas", "Expired": the status pill's few words. */
    val shortFailure: String? get() = if (status == Status.FAILED) shortReason(failure) else null

    /** The list's second line when it names someone: "@alice", "Moss Validator", "Proposal 4". */
    fun listParty(name: (String) -> String): String? = counterparty.takeIf { it.isNotEmpty() }?.let { people(it, name) }

    /** Every detail the wallet knows, in order, leaving out what it does not. */
    fun details(name: (String) -> String = { it }, zone: TimeZone = TimeZone.getDefault()): List<Detail> {
        val d = mutableListOf(Detail("Type", typeName))
        time?.let {
            val f = SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.US).apply { timeZone = zone }
            d += Detail("Date", (if (timeExact) "" else "About ") + f.format(Date(it * 1000)))
        }
        if (outs.isNotEmpty()) d += Detail(if (kind == PrivateActivityKind.SWAP) "You paid" else "Sent", outs.joinToString("\n") { coinText(it) })
        if (ins.isNotEmpty()) d += Detail(if (kind == PrivateActivityKind.SWAP) "You got" else "Received", ins.joinToString("\n") { coinText(it) })
        party(name)?.let { d += it }
        if (fee.isNotEmpty()) d += Detail("Network fee", fee.joinToString("\n") { coinText(it, signed = false) })
        feePayer?.let { d += Detail("Fee paid by", it) }
        gas?.let { d += Detail("Gas used", it) }
        height?.let { d += Detail("Block", grouped(it.toString())) }
        d += Detail("Privacy", if (isPrivate) "Private" else "Public")
        memo?.let { d += Detail("Memo", it) }
        if (status == Status.FAILED && !failure.isNullOrEmpty()) d += Detail("Error", failure)
        hash?.let { d += Detail("Transaction", abbreviate(it, 6, 6), copy = it) }
        return d
    }

    /** Who or what is on the other side, labelled for the kind. */
    private fun party(name: (String) -> String): Detail? {
        if (counterparty.isEmpty()) {
            return when (kind) {
                PrivateActivityKind.GAS_GRANT, PrivateActivityKind.FROM_EARTH, PrivateActivityKind.REGISTRATION_REWARD,
                PrivateActivityKind.UNBONDING_PAYOUT, PrivateActivityKind.LP_PAYOUT -> Detail("From", "Earth")
                PrivateActivityKind.REFERRAL -> Detail("From", "A referral")
                else -> null
            }
        }
        val copy = counterparty.takeIf { ' ' !in it && it.startsWith("earth") }
        val label = when (kind) {
            PrivateActivityKind.SEND, PrivateActivityKind.UNSHIELD, PrivateActivityKind.MOVE -> "To"
            PrivateActivityKind.STAKE, PrivateActivityKind.UNSTAKE, PrivateActivityKind.RESTAKE -> "Validator"
            PrivateActivityKind.REDELEGATE -> "Validators"
            PrivateActivityKind.VOTE, PrivateActivityKind.CARETAKER -> "Vote"
            PrivateActivityKind.ADD_LIQUIDITY, PrivateActivityKind.REMOVE_LIQUIDITY -> "Pool"
            PrivateActivityKind.HANDLE -> "Handle"
            PrivateActivityKind.POSITION -> "Position"
            null -> when {
                glyph == Glyph.SEND -> "To"
                glyph == Glyph.RECEIVE && title == "Received" -> "From"
                glyph == Glyph.STAKE || glyph == Glyph.RECEIVE -> "Validator"
                glyph == Glyph.VOTE -> "Vote"
                else -> "With"
            }
            else -> "With"
        }
        return Detail(label, people(counterparty, name), copy)
    }

    companion object {
        /** A public tx this wallet signed. */
        fun of(tx: Explorer.Tx, self: String): ActivityEntry {
            val type = tx.types.firstOrNull().orEmpty()
            val m = tx.messages.firstOrNull()
            fun f(k: String) = m?.optString(k).orEmpty()
            val mine = f("from_address") == self || f("sender") == self
            var sign = 0L
            var party = ""
            val (title, glyph) = when (type) {
                "MsgSend" -> if (f("from_address") == self) {
                    sign = -1; party = f("to_address"); "Sent" to Glyph.SEND
                } else {
                    sign = 1; party = f("from_address"); "Received" to Glyph.RECEIVE
                }
                "MsgShield" -> { sign = if (mine) -1 else 0; "Shielded" to Glyph.SEND }
                "MsgDelegate" -> { sign = -1; party = f("validator_address"); "Staked" to Glyph.STAKE }
                "MsgUndelegate" -> { sign = 1; party = f("validator_address"); "Unstaked" to Glyph.STAKE }
                "MsgBeginRedelegate" -> {
                    party = listOf(f("validator_src_address"), f("validator_dst_address")).filter { it.isNotEmpty() }.joinToString(" → ")
                    "Moved stake" to Glyph.STAKE
                }
                "MsgWithdrawDelegatorReward" -> { party = f("validator_address"); "Claimed rewards" to Glyph.RECEIVE }
                "MsgRegister" -> "Registered" to Glyph.REGISTER
                // Distinct from registration: you register once and claim every day.
                "MsgClaimAnml" -> "Claimed ANML" to Glyph.RECEIVE
                "MsgSwap" -> "Swapped" to Glyph.SWAP
                "MsgSetAllocations", "MsgSetAllocation" -> "Allocated" to Glyph.VOTE
                "MsgVote" -> { party = f("proposal_id").takeIf { it.isNotEmpty() }?.let { "proposal $it" }.orEmpty(); "Voted" to Glyph.VOTE }
                "MsgAddLiquidity" -> { sign = -1; "Added liquidity" to Glyph.SWAP }
                "MsgRemoveLiquidity" -> "Removed liquidity" to Glyph.SWAP
                else -> readable(type.removePrefix("Msg")).ifEmpty { "Transaction" } to Glyph.OTHER
            }
            val fee = tx.fee.filter { it.amount > 0 }
            return ActivityEntry(
                id = tx.hash,
                title = title,
                typeName = readable(type.removePrefix("Msg")) + if (tx.types.size > 1) " + ${tx.types.size - 1} more" else "",
                glyph = glyph,
                coins = if (sign == 0L) emptyList() else listOfNotNull(messageCoin(m)?.let { ActivityCoin(it.denom, it.amount * sign) }),
                counterparty = party,
                status = when {
                    !tx.success -> Status.FAILED
                    tx.height > 0 -> Status.COMPLETED
                    else -> Status.PENDING
                },
                failure = if (tx.success) null else tx.rawLog.ifEmpty { "code ${tx.code}" },
                time = tx.timestamp.toUnix(),
                timeExact = true,
                fee = fee,
                feePayer = when {
                    tx.feeGranter.isNotEmpty() -> "Fee grant from ${abbreviate(tx.feeGranter)}"
                    tx.feePayer.isNotEmpty() && tx.feePayer != self -> abbreviate(tx.feePayer)
                    fee.isNotEmpty() -> "You"
                    else -> null
                },
                height = tx.height.takeIf { it > 0 },
                isPrivate = false,
                hash = tx.hash,
                memo = tx.memo.ifEmpty { null },
                gas = if (tx.gasWanted > 0) "${grouped(tx.gasUsed.toString())} of ${grouped(tx.gasWanted.toString())}" else null,
            )
        }

        /** A private row, built from the sealed store alone. */
        fun of(row: PrivateActivityRow): ActivityEntry = ActivityEntry(
            id = row.id,
            title = title(row.kind, row.counterparty),
            typeName = typeName(row.kind),
            glyph = glyph(row.kind),
            coins = row.coins,
            counterparty = row.counterparty,
            status = when (row.status) {
                PrivateActivityRow.Status.PENDING -> Status.PENDING
                PrivateActivityRow.Status.CONFIRMED -> Status.COMPLETED
                PrivateActivityRow.Status.FAILED -> Status.FAILED
            },
            failure = row.failure,
            time = row.time,
            timeExact = row.timeExact,
            fee = row.fee?.takeIf { it > 0 }?.let { listOf(ActivityCoin("uerth", it)) }.orEmpty(),
            // A private tx's fee comes out of its own shielded ERTH, never a signer's account.
            feePayer = if (row.kind.sent) (if (row.feeFromGrant) "Earth (gas grant)" else "You (private ERTH)") else null,
            height = row.height,
            isPrivate = true,
            hash = row.hash,
            kind = row.kind,
        )

        /** Short, as the list says it. */
        internal fun title(k: PrivateActivityKind, counterparty: String): String = when (k) {
            PrivateActivityKind.REGISTER -> "Registered"
            PrivateActivityKind.SWITCH -> "Switched identity"
            PrivateActivityKind.MOVE -> "Moved to new identity"
            PrivateActivityKind.SHIELD -> "Shielded"
            PrivateActivityKind.UNSHIELD -> "Unshielded"
            PrivateActivityKind.SEND -> "Sent"
            PrivateActivityKind.MERGE -> "Merged notes"
            PrivateActivityKind.SWAP -> "Swapped"
            PrivateActivityKind.ADD_LIQUIDITY -> "Added liquidity"
            PrivateActivityKind.REMOVE_LIQUIDITY -> "Removed liquidity"
            PrivateActivityKind.STAKE -> "Staked"
            PrivateActivityKind.UNSTAKE -> "Unstaked"
            PrivateActivityKind.REDELEGATE -> "Moved stake"
            PrivateActivityKind.RESTAKE -> "Merged stake"
            PrivateActivityKind.VOTE, PrivateActivityKind.CARETAKER -> "Voted"
            PrivateActivityKind.POSITION -> if (counterparty.startsWith("unlocked")) "Unlocked Groundworks" else "Groundworks"
            PrivateActivityKind.CLAIM_ANML -> "Claimed ANML"
            PrivateActivityKind.HANDLE -> if (counterparty.startsWith("released")) "Released handle" else "Claimed handle"
            PrivateActivityKind.GAS_GRANT -> "Gas from Earth"
            PrivateActivityKind.UNBONDING_PAYOUT -> "Stake returned"
            PrivateActivityKind.LP_PAYOUT -> "Liquidity returned"
            PrivateActivityKind.REGISTRATION_REWARD -> "Registration reward"
            PrivateActivityKind.REFERRAL -> "Referral reward"
            PrivateActivityKind.FROM_EARTH -> "From Earth"
            PrivateActivityKind.RECEIVED -> "Received"
            PrivateActivityKind.INFERRED -> "Private transaction"
        }

        /** The detail sheet's Type: what it was, as a noun (the title says what happened). */
        internal fun typeName(k: PrivateActivityKind): String = when (k) {
            PrivateActivityKind.REGISTER -> "Registration"
            PrivateActivityKind.SWITCH -> "Identity switch"
            PrivateActivityKind.MOVE -> "Move to a new identity"
            PrivateActivityKind.SHIELD -> "Shield"
            PrivateActivityKind.UNSHIELD -> "Unshield"
            PrivateActivityKind.SEND -> "Send"
            PrivateActivityKind.MERGE -> "Note merge"
            PrivateActivityKind.SWAP -> "Swap"
            PrivateActivityKind.ADD_LIQUIDITY -> "Add liquidity"
            PrivateActivityKind.REMOVE_LIQUIDITY -> "Remove liquidity"
            PrivateActivityKind.STAKE -> "Stake"
            PrivateActivityKind.UNSTAKE -> "Unstake"
            PrivateActivityKind.REDELEGATE -> "Redelegation"
            PrivateActivityKind.RESTAKE -> "Stake merge"
            PrivateActivityKind.VOTE -> "Vote"
            PrivateActivityKind.CARETAKER -> "Caretaker vote"
            PrivateActivityKind.POSITION -> "Groundworks position"
            PrivateActivityKind.CLAIM_ANML -> "Daily ANML claim"
            PrivateActivityKind.HANDLE -> "Handle"
            PrivateActivityKind.GAS_GRANT -> "Gas grant"
            PrivateActivityKind.UNBONDING_PAYOUT -> "Unbonding payout"
            PrivateActivityKind.LP_PAYOUT -> "Liquidity payout"
            PrivateActivityKind.REGISTRATION_REWARD -> "Registration reward"
            PrivateActivityKind.REFERRAL -> "Referral reward"
            PrivateActivityKind.FROM_EARTH -> "Note from Earth"
            PrivateActivityKind.RECEIVED -> "Received note"
            PrivateActivityKind.INFERRED -> "Private transaction (restored wallet)"
        }

        internal fun glyph(k: PrivateActivityKind): Glyph = when (k) {
            PrivateActivityKind.SEND, PrivateActivityKind.UNSHIELD, PrivateActivityKind.MOVE, PrivateActivityKind.SHIELD -> Glyph.SEND
            PrivateActivityKind.SWAP, PrivateActivityKind.ADD_LIQUIDITY, PrivateActivityKind.REMOVE_LIQUIDITY, PrivateActivityKind.LP_PAYOUT -> Glyph.SWAP
            PrivateActivityKind.STAKE, PrivateActivityKind.UNSTAKE, PrivateActivityKind.REDELEGATE, PrivateActivityKind.RESTAKE,
            PrivateActivityKind.POSITION, PrivateActivityKind.UNBONDING_PAYOUT -> Glyph.STAKE
            PrivateActivityKind.REGISTER, PrivateActivityKind.SWITCH -> Glyph.REGISTER
            PrivateActivityKind.VOTE, PrivateActivityKind.CARETAKER -> Glyph.VOTE
            PrivateActivityKind.HANDLE -> Glyph.HANDLE
            PrivateActivityKind.MERGE, PrivateActivityKind.INFERRED -> Glyph.OTHER
            else -> Glyph.RECEIVE
        }

        fun shortReason(text: String?): String {
            val t = text.orEmpty().lowercase()
            return when {
                t == PrivateActivity.NEVER_LANDED -> "Expired before a block"
                "out of gas" in t -> "Out of gas"
                "insufficient fee" in t -> "Fee too low"
                "insufficient funds" in t || "insufficient balance" in t -> "Not enough funds"
                "already spent" in t || "nullifier" in t -> "Notes already spent"
                "slippage" in t || "min out" in t || "minimum" in t -> "Price moved"
                "expired" in t || "timeout" in t -> "Expired"
                else -> "Refused by the chain"
            }
        }

        /** "Today", "Yesterday", "Oct 5", "Oct 5, 2025"; "Earlier" when the time is unknown. */
        fun dayTitle(time: Long?, now: Long, zone: TimeZone = TimeZone.getDefault()): String {
            time ?: return "Earlier"
            val day = Calendar.getInstance(zone).apply { timeInMillis = time * 1000 }
            val today = Calendar.getInstance(zone).apply { timeInMillis = now * 1000 }
            fun same(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
            if (same(day, today)) return "Today"
            if (same(day, (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) })) return "Yesterday"
            val pattern = if (day.get(Calendar.YEAR) == today.get(Calendar.YEAR)) "MMM d" else "MMM d, yyyy"
            return SimpleDateFormat(pattern, Locale.US).apply { timeZone = zone }.format(Date(time * 1000))
        }

        /** The list under day headers, newest day first, rows in the order given. */
        fun days(entries: List<ActivityEntry>, now: Long, zone: TimeZone = TimeZone.getDefault()): List<Pair<String, List<ActivityEntry>>> {
            val out = LinkedHashMap<String, MutableList<ActivityEntry>>()
            entries.forEach { out.getOrPut(dayTitle(it.time, now, zone)) { mutableListOf() }.add(it) }
            return out.map { it.key to it.value }
        }

        /** "9:41 AM", "~9:41 AM" when estimated. */
        fun clock(time: Long?, exact: Boolean, zone: TimeZone = TimeZone.getDefault()): String {
            time ?: return ""
            return (if (exact) "" else "~") + SimpleDateFormat("h:mm a", Locale.US).apply { timeZone = zone }.format(Date(time * 1000))
        }

        /** "+1,250.5 ERTH" at full precision. */
        fun coinText(c: ActivityCoin, signed: Boolean = true): String {
            val sign = if (!signed) "" else if (c.amount < 0) "−" else "+"
            return "$sign${figure(c.amount)} ${PrivateActivity.symbol(c.denom)}"
        }

        /** Six decimals' base units as "1,250.5": grouped, trailing zeros dropped, unsigned. */
        fun figure(units: Long): String {
            val v = if (units == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(units)
            val frac = (v % 1_000_000).toString().padStart(6, '0').trimEnd('0')
            return grouped((v / 1_000_000).toString()) + if (frac.isEmpty()) "" else ".$frac"
        }

        internal fun grouped(digits: String): String =
            digits.reversed().chunked(3).joinToString(",").reversed()

        /** Operator addresses as monikers, addresses shortened, the first letter up. */
        internal fun people(text: String, name: (String) -> String): String {
            val joined = text.split(' ').joinToString(" ") {
                when {
                    it.startsWith("earthvaloper") -> name(it)
                    it.startsWith("earth1") -> abbreviate(it)
                    else -> it
                }
            }
            return if (text.startsWith("earth") || joined.firstOrNull()?.isLowerCase() != true) joined
            else joined.replaceFirstChar { it.uppercase() }
        }

        /** "earth1jtc…aar6". */
        fun abbreviate(text: String, head: Int = 10, tail: Int = 4): String =
            if (text.length <= head + tail + 2) text else "${text.take(head)}…${text.takeLast(tail)}"

        private fun messageCoin(m: org.json.JSONObject?): ActivityCoin? {
            m ?: return null
            val coin = m.optJSONArray("amount")?.optJSONObject(0) ?: m.optJSONObject("amount") ?: return null
            val units = coin.optString("amount").toLongOrNull() ?: return null
            return ActivityCoin(coin.optString("denom"), units)
        }

        /** "SetAllocations" -> "Set allocations". */
        private fun readable(text: String): String =
            text.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ").lowercase().replaceFirstChar { it.uppercase() }
    }
}

/**
 * Public rows (timestamps from the chain) and private rows (the sealed
 * store's), one list, newest first. A tx in both is shown once, as its
 * private row (the richer one).
 */
internal fun mergeActivity(public: List<ActivityEntry>, private: List<ActivityEntry>): List<ActivityEntry> {
    val privateHashes = private.mapNotNullTo(HashSet()) { it.hash?.uppercase() }
    return (public.filter { it.hash?.uppercase() !in privateHashes } + private).sortedByDescending { it.sortTime }
}

/** An RFC 3339 chain timestamp to unix seconds; null when it does not parse. */
internal fun String.toUnix(): Long? = runCatching {
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .parse(substringBefore('.').removeSuffix("Z"))!!.time / 1000
}.getOrNull()
