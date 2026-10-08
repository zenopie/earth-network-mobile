package network.erth.wallet.privacy

import network.erth.wallet.privacy.note.NoteOrigin
import network.erth.wallet.privacy.note.OwnedNote
import network.erth.wallet.privacy.note.OwnedStakeNote
import network.erth.wallet.privacy.sync.PrivacyState
import network.erth.wallet.privacy.zk.Fr
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger

/**
 * What a private activity row says happened. The first group are txs this
 * wallet sent (a [SentPrivateTx]); the rest are what sync alone shows:
 * notes received, and spends a restored wallet has no record of.
 */
enum class PrivateActivityKind(val label: String, val sent: Boolean) {
    REGISTER("Registered", true),
    SWITCH("Switched identity", true),
    MOVE("Moved to your new identity", true),
    SHIELD("Shielded", true),
    UNSHIELD("Unshielded", true),
    SEND("Sent privately", true),
    MERGE("Merged notes", true),
    SWAP("Swapped", true),
    ADD_LIQUIDITY("Added liquidity", true),
    REMOVE_LIQUIDITY("Removed liquidity", true),
    STAKE("Staked", true),
    UNSTAKE("Unstaked", true),
    REDELEGATE("Redelegated", true),
    RESTAKE("Merged stake", true),
    VOTE("Voted", true),
    CARETAKER("Caretaker vote", true),
    POSITION("Groundworks position", true),
    CLAIM_ANML("Claimed ANML", true),
    HANDLE("Handle", true),
    GAS_GRANT("Gas grant from Earth", false),
    UNBONDING_PAYOUT("Unbonding payout", false),
    LP_PAYOUT("Liquidity payout", false),
    REGISTRATION_REWARD("Registration reward", false),
    REFERRAL("Referral reward", false),
    FROM_EARTH("Received from Earth", false),
    RECEIVED("Received privately", false),
    INFERRED("Private transaction", false),
    ;

    companion object {
        fun of(name: String?): PrivateActivityKind? = entries.firstOrNull { it.name == name }
    }
}

/** An amount of one denom; [amount] signed in a row (negative: it left the wallet). */
data class ActivityCoin(val denom: String, val amount: Long)

/**
 * A private tx this wallet sent, recorded in its sealed store when the node
 * accepted it (never looked up by hash for this). [change] and [receives]
 * are the rho (hex) of every note the tx makes for this wallet: its own
 * bundle outputs and the notes the chain mints to it. A synced note with one
 * of them is this tx's, folded into its row: [change] never shows,
 * [receives] shows as what came in. [spent] are the nullifiers (hex) of the
 * notes and stake notes it spends. [height] is set once it is in a block
 * (the broadcast's own wait, or sync seeing its nullifiers or outputs);
 * [failure] once the wallet learns it failed (the broadcast's wait, or the
 * checks sync already makes on a tx it marked notes for).
 */
data class SentPrivateTx(
    val hash: String,
    val kind: PrivateActivityKind,
    val generation: Int,
    val counterparty: String,
    val fee: Long,
    val submittedAt: Long,
    val outs: List<ActivityCoin>,
    val ins: List<ActivityCoin>,
    val change: List<String>,
    val receives: List<String>,
    val spent: List<String>,
    val height: Long? = null,
    /** The block's time when the wallet learned it from the broadcast's wait. */
    val time: Long? = null,
    val failure: String? = null,
)

/**
 * The private activity the sealed store keeps: the txs this wallet sent,
 * the notes it expects the chain to mint to it later by kind (a gas grant,
 * an unbonding payout, a liquidity payout, a shield's note), and pairs of
 * (height, block time) sync read anyway, from which a note's time is
 * estimated. Goes with the store ("forget private data", forgetting the
 * wallet); a reset on the same chain keeps it, a relaunch drops it.
 */
class ActivityLog {
    val sent: MutableList<SentPrivateTx> = ArrayList()
    /** rho hex -> what the note will be; insertion order, oldest first. */
    val expected: LinkedHashMap<String, PrivateActivityKind> = LinkedHashMap()
    /** (height, unix seconds), ascending by height. */
    val clock: MutableList<Pair<Long, Long>> = ArrayList()

    fun copy(): ActivityLog = ActivityLog().also { c -> c.sent.addAll(sent); c.expected.putAll(expected); c.clock.addAll(clock) }

    /** Records [tx] (a hash seen again replaces its record). */
    fun record(tx: SentPrivateTx) {
        sent.removeAll { it.hash.equals(tx.hash, ignoreCase = true) }
        sent.add(tx)
        // Oldest settled first; one still pending is kept over any settled one.
        while (sent.size > MAX_SENT) {
            val drop = sent.indexOfFirst { it.height != null || it.failure != null }.takeIf { it >= 0 } ?: 0
            sent.removeAt(drop)
        }
    }

    /** The broadcast was refused outright: the tx is in no mempool, and nothing happened. */
    fun drop(hash: String) {
        sent.removeAll { it.hash.equals(hash, ignoreCase = true) }
    }

    fun update(hash: String, f: (SentPrivateTx) -> SentPrivateTx) {
        val i = sent.indexOfFirst { it.hash.equals(hash, ignoreCase = true) }
        if (i >= 0) sent[i] = f(sent[i])
    }

    /** In a block at [height] (block [time] when known): a failure seen before stays. */
    fun confirm(hash: String, height: Long, time: Long?) = update(hash) { it.copy(height = height, time = time ?: it.time) }

    /** Failed, with the wallet's reason; a tx failing in its block is in that block too. */
    fun fail(hash: String, reason: String, height: Long? = null) =
        update(hash) { if (it.failure != null) it else it.copy(failure = reason, height = height ?: it.height) }

    /** A note the chain will mint to this wallet later, and what it will be. */
    fun expect(rho: Fr, kind: PrivateActivityKind) {
        expected[rho.toHex()] = kind
        while (expected.size > MAX_EXPECTED) expected.remove(expected.keys.first())
    }

    /** A (height, block time) pair the sync read: kept ascending, thinned to [MAX_CLOCK]. */
    fun tick(height: Long, time: Long?) {
        if (time == null || height <= 0 || time <= 0) return
        if (clock.any { it.first == height }) return
        clock.add(height to time)
        clock.sortBy { it.first }
        // Keep the first and the newest; drop the inner pair closest together.
        while (clock.size > MAX_CLOCK) {
            var best = 1
            for (i in 1 until clock.size - 1) if (clock[i + 1].first - clock[i - 1].first < clock[best + 1].first - clock[best - 1].first) best = i
            clock.removeAt(best)
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("sent", JSONArray().apply { sent.forEach { put(sentJson(it)) } })
        put("expected", JSONArray().apply { expected.forEach { (rho, k) -> put(JSONArray().put(rho).put(k.name)) } })
        put("clock", JSONArray().apply { clock.forEach { (h, t) -> put(JSONArray().put(h).put(t)) } })
    }

    companion object {
        const val MAX_SENT = 200
        const val MAX_EXPECTED = 256
        const val MAX_CLOCK = 32

        fun fromJson(j: JSONObject?): ActivityLog = ActivityLog().apply {
            if (j == null) return@apply
            j.optJSONArray("sent")?.let { a -> for (i in 0 until a.length()) sentFromJson(a.getJSONObject(i))?.let { sent.add(it) } }
            j.optJSONArray("expected")?.let { a ->
                for (i in 0 until a.length()) a.optJSONArray(i)?.let { e -> PrivateActivityKind.of(e.optString(1))?.let { expected[e.optString(0)] = it } }
            }
            j.optJSONArray("clock")?.let { a ->
                for (i in 0 until a.length()) a.optJSONArray(i)?.let { e -> clock.add(e.getLong(0) to e.getLong(1)) }
                clock.sortBy { it.first }
            }
        }

        private fun coins(cs: List<ActivityCoin>) = JSONArray().apply { cs.forEach { put(JSONArray().put(it.denom).put(it.amount)) } }

        private fun coinsFrom(a: JSONArray?): List<ActivityCoin> =
            (0 until (a?.length() ?: 0)).mapNotNull { i -> a!!.optJSONArray(i)?.let { ActivityCoin(it.getString(0), it.getLong(1)) } }

        private fun strings(a: JSONArray?): List<String> = (0 until (a?.length() ?: 0)).map { a!!.getString(it) }

        private fun sentJson(t: SentPrivateTx) = JSONObject()
            .put("hash", t.hash).put("kind", t.kind.name).put("generation", t.generation).put("counterparty", t.counterparty)
            .put("fee", t.fee).put("submitted_at", t.submittedAt).put("outs", coins(t.outs)).put("ins", coins(t.ins))
            .put("change", JSONArray(t.change)).put("receives", JSONArray(t.receives)).put("spent", JSONArray(t.spent))
            .put("height", t.height ?: JSONObject.NULL).put("time", t.time ?: JSONObject.NULL).put("failure", t.failure ?: JSONObject.NULL)

        private fun sentFromJson(o: JSONObject): SentPrivateTx? {
            val kind = PrivateActivityKind.of(o.optString("kind")) ?: return null
            return SentPrivateTx(
                hash = o.getString("hash"), kind = kind, generation = o.optInt("generation"), counterparty = o.optString("counterparty"),
                fee = o.optLong("fee"), submittedAt = o.optLong("submitted_at"), outs = coinsFrom(o.optJSONArray("outs")), ins = coinsFrom(o.optJSONArray("ins")),
                change = strings(o.optJSONArray("change")), receives = strings(o.optJSONArray("receives")), spent = strings(o.optJSONArray("spent")),
                height = if (o.isNull("height")) null else o.optLong("height"), time = if (o.isNull("time")) null else o.optLong("time"),
                failure = if (o.isNull("failure")) null else o.optString("failure"),
            )
        }
    }
}

/** One row of private activity, ready for the activity list. */
data class PrivateActivityRow(
    val kind: PrivateActivityKind,
    /** Signed: negative left the wallet, positive came in. */
    val coins: List<ActivityCoin>,
    val counterparty: String,
    /** The fee this wallet paid (sent txs only). */
    val fee: Long?,
    val hash: String?,
    val height: Long?,
    /** Unix seconds; [timeExact] false when estimated from the block height. */
    val time: Long?,
    val timeExact: Boolean,
    val status: Status,
    val failure: String? = null,
    /**
     * The fee came out of a gas grant Earth minted to this wallet: a note the
     * tx spent was expected as one ([ActivityLog.expected]).
     */
    val feeFromGrant: Boolean = false,
) {
    enum class Status { PENDING, CONFIRMED, FAILED }

    /** A stable id: the tx's hash, else what the row is built from. */
    val id: String get() = hash ?: "${kind.name}:${height ?: 0}:${coins.joinToString(",") { "${it.amount}${it.denom}" }}"
}

/**
 * Private activity, built locally from the sealed store: nothing here asks
 * anything of a node or the indexer. Sent txs are what the wallet recorded
 * when it sent them; received notes and a restored wallet's spends are read
 * from the notes sync already found.
 */
object PrivateActivity {
    /** A block every this many seconds, for a height before or past every clock pair (earth-1's target). */
    const val DEFAULT_BLOCK_SECONDS = 6L

    const val FAILED_IN_BLOCK = "failed in its block"
    const val NEVER_LANDED = "did not reach a block before it expired"

    fun hex(f: Fr): String = f.toHex()

    /**
     * What a tx moves, from what it spends and makes: pool notes spent less
     * the outputs back to this wallet, per denom (the fee taken off ERTH),
     * and likewise for stake notes. A positive net left ([first]); a negative
     * one came in ([second], as positive amounts).
     */
    fun coins(
        poolSpent: List<ActivityCoin>,
        poolBack: List<ActivityCoin>,
        stakeSpent: List<ActivityCoin>,
        stakeBack: List<ActivityCoin>,
        fee: Long,
    ): Pair<List<ActivityCoin>, List<ActivityCoin>> {
        val net = sortedMapOf<String, BigInteger>()
        fun add(cs: List<ActivityCoin>, sign: Int) = cs.forEach { c ->
            if (c.amount != 0L) net.merge(c.denom, BigInteger.valueOf(c.amount).multiply(BigInteger.valueOf(sign.toLong())), BigInteger::add)
        }
        add(poolSpent, 1); add(poolBack, -1); add(stakeSpent, 1); add(stakeBack, -1)
        if (fee > 0) net.merge(PrivacyWallet.FEE, BigInteger.valueOf(fee).negate(), BigInteger::add)
        val outs = ArrayList<ActivityCoin>()
        val ins = ArrayList<ActivityCoin>()
        net.forEach { (d, v) ->
            when (v.signum()) {
                1 -> outs.add(ActivityCoin(d, clamp(v)))
                -1 -> ins.add(ActivityCoin(d, clamp(v.negate())))
            }
        }
        return outs to ins
    }

    private fun clamp(v: BigInteger): Long = if (v.bitLength() > 63) Long.MAX_VALUE else v.toLong()

    /**
     * Marks every pending sent tx in a block once sync holds its nullifiers
     * (a spent note of its) or one of its outputs: the lowest such height.
     * Local: what sync found already, nothing asked by hash.
     */
    fun settle(s: PrivacyState) {
        val log = s.activity
        if (log.sent.none { it.height == null && it.failure == null }) return
        val spentAt = HashMap<String, Long>()
        val byTx = HashMap<String, Long>()
        fun spent(nf: Fr, h: Long?, tx: String?) {
            if (h == null) return
            spentAt.merge(nf.toHex(), h, ::minOf)
            tx?.let { byTx.merge(it.uppercase(), h, ::minOf) }
        }
        s.notes.forEach { spent(it.nf, it.spentHeight, it.pendingTx) }
        s.stakeNotes.forEach { spent(it.nf, it.spentHeight, it.pendingTx) }
        val madeAt = HashMap<String, Long>()
        s.notes.forEach { madeAt.merge(it.note.rho.toHex(), it.height, ::minOf) }
        s.stakeNotes.forEach { madeAt.merge(it.rho.toHex(), it.height, ::minOf) }
        for (t in log.sent.toList()) {
            if (t.height != null || t.failure != null) continue
            val h = (t.spent.mapNotNull { spentAt[it] } + listOfNotNull(byTx[t.hash.uppercase()]) + (t.change + t.receives).mapNotNull { madeAt[it] }).minOrNull()
            if (h != null) log.confirm(t.hash, h, null)
        }
    }

    /**
     * A block time for [height] from the clock pairs: between two, by
     * interpolation; outside them, at their average pace (or
     * [DEFAULT_BLOCK_SECONDS] with fewer than two). Null with none.
     */
    fun timeAt(clock: List<Pair<Long, Long>>, height: Long): Long? {
        if (clock.isEmpty()) return null
        clock.firstOrNull { it.first == height }?.let { return it.second }
        val first = clock.first()
        val last = clock.last()
        val pace = if (clock.size >= 2 && last.first > first.first) {
            ((last.second - first.second).toDouble() / (last.first - first.first)).takeIf { it > 0.1 && it < 600.0 } ?: DEFAULT_BLOCK_SECONDS.toDouble()
        } else DEFAULT_BLOCK_SECONDS.toDouble()
        if (height < first.first) return (first.second - (first.first - height) * pace).toLong()
        if (height > last.first) return (last.second + (height - last.first) * pace).toLong()
        val i = clock.indexOfFirst { it.first > height }
        val (h0, t0) = clock[i - 1]
        val (h1, t1) = clock[i]
        return t0 + ((t1 - t0).toDouble() * (height - h0) / (h1 - h0)).toLong()
    }

    /**
     * Every private row, newest first:
     *  1. each sent tx, with the notes the chain minted for it (found by
     *     their rho) as what came in; its change never shows;
     *  2. spends no record names (a restored wallet's history), one
     *     "Private transaction" per block height, net of the notes made for
     *     this wallet at that height (change, and what the tx minted); one at
     *     the height of a registration record of this wallet's is its
     *     registration;
     *  3. every other note as a received row, labelled by what the wallet
     *     expected of it, else by its format (open: the referral note; v2:
     *     minted by the chain; v1, or a note synced before the format was
     *     kept: received privately).
     */
    fun rows(s: PrivacyState): List<PrivateActivityRow> {
        val log = s.activity
        val clock = (log.clock + s.regRecords.mapNotNull { r -> (r.time ?: r.chainTime)?.let { r.height to it } })
            .distinctBy { it.first }.sortedBy { it.first }
        fun estimated(h: Long?): Long? = h?.let { timeAt(clock, it) }

        val owner = HashMap<String, SentPrivateTx>()
        log.sent.forEach { t -> (t.change + t.receives).forEach { owner[it] = t } }
        val recordedNf = log.sent.flatMapTo(HashSet()) { it.spent }
        val recordedTx = log.sent.mapTo(HashSet()) { it.hash.uppercase() }
        val notes = s.notes.filter { it.note.value > 0 }
        val stake = s.stakeNotes.filter { it.amount > 0 }

        val rows = ArrayList<PrivateActivityRow>()
        for (t in log.sent) {
            val minted = notes.filter { it.note.rho.toHex() in t.receives }
            val ins = sum(t.ins + minted.map { ActivityCoin(it.note.denom, it.note.value) })
            val status = when {
                t.failure != null -> PrivateActivityRow.Status.FAILED
                t.height != null -> PrivateActivityRow.Status.CONFIRMED
                else -> PrivateActivityRow.Status.PENDING
            }
            rows.add(
                PrivateActivityRow(
                    kind = t.kind, coins = t.outs.map { it.copy(amount = -it.amount) } + ins, counterparty = t.counterparty,
                    fee = t.fee, hash = t.hash, height = t.height, time = t.time ?: t.submittedAt, timeExact = true,
                    status = status, failure = t.failure,
                    feeFromGrant = s.notes.any { it.nf.toHex() in t.spent && log.expected[it.note.rho.toHex()] == PrivateActivityKind.GAS_GRANT },
                ),
            )
        }

        fun unrecorded(nf: Fr, tx: String?) = nf.toHex() !in recordedNf && (tx == null || tx.uppercase() !in recordedTx)
        val spentNotes = s.notes.filter { it.spentHeight != null && it.note.value > 0 && unrecorded(it.nf, it.pendingTx) }
        val spentStake = s.stakeNotes.filter { it.spentHeight != null && it.amount > 0 && unrecorded(it.nf, it.pendingTx) }
        val inferredAt = (spentNotes.map { it.spentHeight!! } + spentStake.map { it.spentHeight!! }).toSortedSet()
        val regHeights = s.regRecords.mapTo(HashSet()) { it.height }
        fun mine(n: OwnedNote) = owner.containsKey(n.note.rho.toHex()) || log.expected.containsKey(n.note.rho.toHex())
        val folded = HashSet<Long>()
        for (h in inferredAt) {
            val made = notes.filter { it.height == h && !mine(it) }
            val madeStake = stake.filter { it.height == h && !owner.containsKey(it.rho.toHex()) }
            made.forEach { folded.add(it.position) }
            val (outs, ins) = coins(
                spentNotes.filter { it.spentHeight == h }.map { ActivityCoin(it.note.denom, it.note.value) },
                made.map { ActivityCoin(it.note.denom, it.note.value) },
                spentStake.filter { it.spentHeight == h }.map { ActivityCoin(it.denom, it.amount) },
                madeStake.map { ActivityCoin(it.denom, it.amount) },
                0,
            )
            rows.add(
                PrivateActivityRow(
                    kind = if (h in regHeights) PrivateActivityKind.REGISTER else PrivateActivityKind.INFERRED,
                    coins = outs.map { it.copy(amount = -it.amount) } + ins, counterparty = "", fee = null, hash = null,
                    height = h, time = estimated(h), timeExact = false, status = PrivateActivityRow.Status.CONFIRMED,
                ),
            )
        }

        for (n in notes) {
            val rho = n.note.rho.toHex()
            if (owner.containsKey(rho) || n.position in folded) continue
            val expected = log.expected[rho]
            // A shield's note is shown by the shield's own (public) row.
            if (expected == PrivateActivityKind.SHIELD) continue
            val kind = expected ?: when {
                    n.height in regHeights && n.origin == NoteOrigin.BLIND -> PrivateActivityKind.REGISTRATION_REWARD
                    n.origin == NoteOrigin.OPEN -> PrivateActivityKind.REFERRAL
                    n.origin == NoteOrigin.BLIND -> PrivateActivityKind.FROM_EARTH
                    else -> PrivateActivityKind.RECEIVED
                }
            rows.add(
                PrivateActivityRow(
                    kind = kind, coins = listOf(ActivityCoin(n.note.denom, n.note.value)), counterparty = "", fee = null, hash = null,
                    height = n.height, time = estimated(n.height), timeExact = false, status = PrivateActivityRow.Status.CONFIRMED,
                ),
            )
        }
        return rows.sortedWith(compareByDescending<PrivateActivityRow> { it.time ?: Long.MIN_VALUE }.thenByDescending { it.height ?: Long.MAX_VALUE })
    }

    private fun sum(cs: List<ActivityCoin>): List<ActivityCoin> =
        cs.groupBy { it.denom }.map { (d, xs) -> ActivityCoin(d, Amounts.satSum(xs) { it.amount }) }.filter { it.amount > 0 }

    /** "uerth" -> "ERTH"; stake, LP shares and unresolved assets by what they are. */
    fun symbol(denom: String): String = when {
        denom.startsWith(PrivacyWallet.DERTH_PREFIX) -> "dERTH"
        denom.startsWith(PrivacyWallet.LP_PREFIX) -> "LP"
        denom.startsWith(network.erth.wallet.privacy.note.NotePlaintext.UNRESOLVED_PREFIX) -> "token"
        else -> denom.removePrefix("u").uppercase()
    }

    /** The coins of [notes] per denom. */
    fun ofNotes(notes: List<OwnedNote>): List<ActivityCoin> = notes.map { ActivityCoin(it.note.denom, it.note.value) }

    fun ofStake(notes: List<OwnedStakeNote>): List<ActivityCoin> = notes.map { ActivityCoin(it.denom, it.amount) }
}
