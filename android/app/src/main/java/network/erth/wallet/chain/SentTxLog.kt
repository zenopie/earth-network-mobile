package network.erth.wallet.chain

import android.content.Context

/**
 * The transactions this wallet sent, kept on the device: what the activity
 * list is built from.
 *
 * Earth's public node lists no transactions by address (a search by address
 * is a scan of that address's whole history on the only validator, round-5
 * R5-E-1), so the wallet remembers the hash of every transaction it
 * broadcast and looks each up by hash. Per address, newest first, at most
 * [LIMIT]; hashes only, which are public on the chain anyway. Plain
 * SharedPreferences, like the referral: nothing here is secret, and it must
 * be readable before the vault is unlocked. Ports nothing; iOS ports this
 * (`SentTxLog.swift`).
 */
object SentTxLog {
    const val LIMIT = 50
    private const val PREFS = "sent_txs"

    /** A tx hash as the LCD names one: 64 hex digits. */
    fun isHash(s: String): Boolean =
        s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    /** [list] with [hash] recorded: moved to the front, capped. */
    internal fun add(list: List<String>, hash: String): List<String> {
        if (!isHash(hash)) return list
        val h = hash.uppercase()
        return (listOf(h) + list.filter { it != h }).take(LIMIT)
    }

    internal fun decode(raw: String?): List<String> =
        raw.orEmpty().split(',').filter(::isHash)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Records a broadcast the node accepted (CheckTx passed): it may still
     * land, fail in its block, or be dropped; the lookup says which.
     */
    @Synchronized
    fun record(context: Context, address: String, hash: String) {
        if (address.isEmpty() || !isHash(hash)) return
        val p = prefs(context)
        val next = add(decode(p.getString(address, null)), hash)
        p.edit().putString(address, next.joinToString(",")).apply()
    }

    /** This address's sent hashes, newest first. */
    @Synchronized
    fun hashes(context: Context, address: String): List<String> =
        decode(prefs(context).getString(address, null))

    /**
     * Forgets an address's log, with the wallet's other per-address data
     * ("forget private data"): the hashes are public, but a phone that keeps
     * them still says which address was used on it and what it sent. iOS
     * does the same on forget.
     */
    @Synchronized
    fun clear(context: Context, address: String) {
        if (address.isEmpty()) return
        prefs(context).edit().remove(address).commit()
    }
}
