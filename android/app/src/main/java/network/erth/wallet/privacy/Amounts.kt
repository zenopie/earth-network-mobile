package network.erth.wallet.privacy

/**
 * Note and stake amounts are u64 on chain. The wallet holds them as Long and
 * so only takes values in 0..2^63-1 (PRIVACY_FORMATS.md §4, "Amounts"): a
 * public amount or a decrypted value above that is ignored, never wrapped to
 * a negative. No supply reaches 2^63, so nothing real is lost; iOS (UInt64)
 * applies the same bound. Totals shown to the user saturate; amounts a tx is
 * built from are checked (an overflow is an error, never a wrong change).
 */
object Amounts {
    /** A decimal u64 (no sign, no spaces) that fits a Long; null otherwise. */
    fun parseU64(s: String?): Long? {
        if (s.isNullOrEmpty() || s.length > 20 || !s.all { it in '0'..'9' }) return null
        val v = s.toULongOrNull() ?: return null
        return if (v > Long.MAX_VALUE.toULong()) null else v.toLong()
    }

    /** a + b, at most Long.MAX_VALUE (both non-negative). */
    fun satAdd(a: Long, b: Long): Long {
        val r = a + b
        return if (((a xor r) and (b xor r)) < 0) Long.MAX_VALUE else r
    }

    fun <T> satSum(xs: Iterable<T>, f: (T) -> Long): Long = xs.fold(0L) { acc, x -> satAdd(acc, f(x)) }

    fun satSum(xs: Iterable<Long>): Long = satSum(xs) { it }

    /** The exact sum; throws ArithmeticException on overflow (building a tx). */
    fun <T> exactSum(xs: Iterable<T>, f: (T) -> Long): Long = xs.fold(0L) { acc, x -> Math.addExact(acc, f(x)) }
}
