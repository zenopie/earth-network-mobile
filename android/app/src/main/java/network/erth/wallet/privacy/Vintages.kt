package network.erth.wallet.privacy

/**
 * Stake vintages (chain v1.3.0, ORCHARD_DESIGN 8.9), ported from
 * x/shieldedstaking types/vintage.go. iOS: `Vintages.swift`.
 *
 * A stake note's asset names its validator and its vintage, the UTC month
 * (YYYYMM) its stake was last checked in (delegated, credited by a move, or
 * moved by MsgCheckIn):
 *
 *     derth/<valoper>/<YYYYMM>
 *
 * except the base vintage (the month of the v1.3.0 upgrade on earth-1; a new
 * chain's genesis month), whose denom is the unsuffixed derth/<valoper> every
 * note from before v1.3.0 carries in its commitment. One denom per
 * (validator, vintage): the suffixed spelling of the base month is another
 * asset the chain never supplies.
 *
 * A vintage is live in its own month and the 12 after it, and retires at
 * 00:00 UTC on the 1st of its month + 13: from then its notes earn nothing,
 * vote nothing and can only be withdrawn, at the vintage's value when it
 * retired.
 */
object Vintages {
    /** How many vintages are live at once: the current month and the 12 before it. */
    const val LIVE_MONTHS = 13

    const val MIN_YEAR = 2000
    const val MAX_YEAR = 9999

    /**
     * How long into a month a credit (a delegation, a move's credit lane, a
     * check-in) may still name the previous month (the chain's
     * VintageGraceSeconds, = ClearBeforeSlackSeconds): a proof built against
     * a block before 00:00 UTC on the 1st and included after it.
     */
    const val GRACE_SECONDS = 3_600L

    /** How long before a vintage retires its check-in is due (the reminder, the row's warning). */
    const val CHECK_IN_LEAD_SECONDS = 30L * 86_400

    /** Whether [m] is a YYYYMM month in range (the chain's ValidateVintage). */
    fun valid(m: Int): Boolean {
        val y = m / 100
        val mo = m % 100
        return y in MIN_YEAR..MAX_YEAR && mo in 1..12
    }

    /** [m] as months since year 0 (comparable, subtractable). */
    fun index(m: Int): Long = (m / 100).toLong() * 12 + (m % 100) - 1

    fun fromIndex(i: Long): Int = if (i < 0) 0 else ((i / 12) * 100 + i % 12 + 1).toInt()

    fun addMonths(m: Int, n: Long): Int = fromIndex(index(m) + n)

    /** The UTC month of unix time [t] (civil-from-days, Hinnant: java.time needs API 26; minSdk is 24). */
    fun monthOf(t: Long): Int {
        val z = Math.floorDiv(t.coerceIn(0, 253_402_300_799), 86_400L) + 719_468
        val era = Math.floorDiv(z, 146_097L)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val m = if (mp < 10) mp + 3 else mp - 9
        val y = yoe + era * 400 + if (m <= 2) 1 else 0
        return (y * 100 + m).toInt()
    }

    /** 00:00 UTC on the 1st of [m], unix seconds (days-from-civil, Hinnant). */
    fun start(m: Int): Long {
        val mo = m % 100
        val yy = (m / 100).toLong() - if (mo <= 2) 1 else 0
        val era = Math.floorDiv(yy, 400L)
        val yoe = yy - era * 400
        val doy = (153L * (if (mo > 2) mo - 3 else mo + 9) + 2) / 5
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return (era * 146_097 + doe - 719_468) * 86_400
    }

    /** When [m] retires: 00:00 UTC on the 1st of [m] + 13. */
    fun retiresAt(m: Int): Long = start(addMonths(m, LIVE_MONTHS.toLong()))

    /** The oldest vintage live in month [cur]: cur - 12. */
    fun oldestLive(cur: Int): Int = addMonths(cur, -(LIVE_MONTHS - 1).toLong())

    /** Whether [m] is live in month [cur]: cur - 12 <= m <= cur. */
    fun live(m: Int, cur: Int): Boolean {
        val i = index(m)
        val c = index(cur)
        return i <= c && i > c - LIVE_MONTHS
    }

    /** Whether [m] has retired by month [cur]. */
    fun retired(m: Int, cur: Int): Boolean = index(m) <= index(cur) - LIVE_MONTHS

    /** The stake denom of [valoper]'s vintage [m]: unsuffixed for the base vintage. */
    fun denom(valoper: String, m: Int, base: Int): String =
        if (m == base) "${PrivacyWallet.DERTH_PREFIX}$valoper" else "${PrivacyWallet.DERTH_PREFIX}$valoper/${"%06d".format(java.util.Locale.ROOT, m)}"

    /** A derth denom's validator and vintage ([month] 0: the unsuffixed form, the base vintage's). */
    data class Parsed(val validator: String, val month: Int)

    /** The chain's ParseDerthDenom: both forms; null for anything else (an empty validator, a suffix that is not a vintage). */
    fun parse(denom: String): Parsed? {
        if (!denom.startsWith(PrivacyWallet.DERTH_PREFIX)) return null
        val rest = denom.removePrefix(PrivacyWallet.DERTH_PREFIX)
        if (rest.isEmpty()) return null
        val slash = rest.indexOf('/')
        if (slash < 0) return Parsed(rest, 0)
        val v = rest.substring(0, slash)
        val suffix = rest.substring(slash + 1)
        if (v.isEmpty() || suffix.length != 6 || !suffix.all { it in '0'..'9' }) return null
        val m = suffix.toInt()
        return if (valid(m)) Parsed(v, m) else null
    }

    /**
     * What resolves a stake note's vintage: the [base] vintage (the
     * unsuffixed denom's month, Query/VintageInfo) and the [current] month
     * (the vintage a delegation, a move's credit or a check-in lands in);
     * [grace] the previous month during the first [GRACE_SECONDS] of the
     * current one (0 otherwise), which a credit may still name.
     */
    data class Context(val base: Int, val current: Int, val grace: Int = 0) {
        init {
            require(valid(base) && valid(current) && (grace == 0 || grace == addMonths(current, -1))) { "vintages $base, $current, $grace" }
        }

        /** Whether a credit may name [m] now (the chain's CheckCredit): the current month, or the previous one during the grace. */
        fun credits(m: Int): Boolean = m == current || (grace != 0 && m == grace)

        /** [denom]'s month, the base vintage's for the unsuffixed form; null when it is no derth denom. */
        fun monthOf(denom: String): Int? = parse(denom)?.let { if (it.month == 0) base else it.month }

        /** The denom of [valoper]'s vintage [m]. */
        fun denom(valoper: String, m: Int): String = Vintages.denom(valoper, m, base)

        /** The denom a delegation to [valoper] credits now. */
        fun currentDenom(valoper: String): String = denom(valoper, current)

        fun live(m: Int): Boolean = live(m, current)
        fun retired(m: Int): Boolean = retired(m, current)
    }

    /**
     * The chain's NewVintageCtx at unix time [t]: [base], t's month (never
     * before [base]: a clock behind the chain reads as the base month), and
     * the grace month during the first [GRACE_SECONDS] of it.
     */
    fun at(base: Int, t: Long): Context {
        val m = monthOf(t)
        val cur = if (index(m) < index(base)) base else m
        val grace = if (cur == m && t - start(cur) < GRACE_SECONDS) addMonths(cur, -1) else 0
        return Context(base, cur, grace)
    }
}
