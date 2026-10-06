package network.erth.wallet.privacy.handles

import network.erth.wallet.privacy.keys.ShieldedAddress

/**
 * Handles (x/personhood): a registered human's name in the chain's public
 * directory for a shielded address. Lowercase a-z, 0-9 and -, 3 to 32
 * characters, no leading or trailing dash; one spelling per handle (the
 * chain does no case folding).
 *
 * Lifecycle: live until expires_at (it resolves: payments and referrals may
 * name it); then, until renewal_until, reserved to its owner and not
 * resolving; then free. Nothing renews on its own.
 */
object Handles {
    const val MIN_LEN = 3
    const val MAX_LEN = 32

    /** Params defaults (handle_lease_seconds 27, handle_renewal_seconds 26). */
    const val DEFAULT_LEASE_SECONDS = 365L * 86_400
    const val DEFAULT_RENEWAL_SECONDS = 30L * 86_400

    /** Whether [h] is a handle as the chain spells one (ValidateHandle). */
    fun valid(h: String): Boolean {
        if (h.length !in MIN_LEN..MAX_LEN) return false
        if (!h.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }) return false
        return h.first() != '-' && h.last() != '-'
    }

    /**
     * What someone typed ("@Alice", " alice ") as a handle: the leading @
     * and surrounding space dropped, lowercased; null when that is not a
     * handle. Lowercasing is safe: the chain only holds lowercase handles.
     */
    fun parse(input: String): String? {
        val h = input.trim().removePrefix("@").trim().lowercase(java.util.Locale.ROOT)
        return h.takeIf { valid(it) }
    }

    /** Whether [input] reads as a handle rather than an address ("@x", or a bare handle that is no address). */
    fun looksLikeHandle(input: String): Boolean {
        val t = input.trim()
        if (t.startsWith("@")) return true
        if (t.startsWith("earth1") || t.startsWith(ShieldedAddress.HRP + "1")) return false
        return parse(t) != null
    }

    /** "erthz1abcd…wxyz": a shielded address shown for confirmation. */
    fun truncate(address: String, head: Int = 12, tail: Int = 8): String =
        if (address.length <= head + tail + 1) address else address.take(head) + "…" + address.takeLast(tail)

    /** Seconds before expiry the reminder starts (and it stays through the renewal period). */
    const val REMINDER_LEAD_SECONDS = 30L * 86_400

    /**
     * The furthest ahead any lease time the wallet takes may lie: a
     * directory entry's expiry and renewal end, a caretaker split's
     * expiry, and the lease params themselves. Anything past it is a hostile
     * or broken answer, refused or clamped before it reaches any arithmetic.
     */
    const val MAX_AHEAD_SECONDS = 10L * 365 * 86_400

    /**
     * A directory entry's owner: the handle-scope nullifier
     * that holds it, as 64 lowercase hex digits (the MsgBindHandle membership
     * nullifier, MsgMoveHandle new_owner). Anything else, or none, is "":
     * no owner said, and nothing is adopted on it.
     */
    fun owner(raw: String?): String {
        val h = raw?.trim()?.lowercase(java.util.Locale.ROOT) ?: return ""
        return if (h.length == 64 && h.all { it in '0'..'9' || it in 'a'..'f' }) h else ""
    }

    /** The longest address a directory entry may carry: an erthz1 address is far shorter. */
    const val MAX_ADDRESS_LEN = 256

    /** a + b, clamped to the Long range rather than wrapped (iOS would trap). */
    fun satAdd(a: Long, b: Long): Long {
        val r = a + b
        return if (((a xor r) and (b xor r)) < 0) (if (a < 0) Long.MIN_VALUE else Long.MAX_VALUE) else r
    }

    /** a - b, clamped likewise. */
    fun satSub(a: Long, b: Long): Long {
        val r = a - b
        return if (((a xor b) and (a xor r)) < 0) (if (a < 0) Long.MIN_VALUE else Long.MAX_VALUE) else r
    }
}

/** A directory entry as Query/Handles serves it. */
data class HandleEntry(
    val handle: String,
    /** bech32m "erthz1..." */
    val address: String,
    /** "live" | "renewal" | "free" */
    val status: String,
    val expiresAt: Long,
    val renewalUntil: Long,
    /** [Handles.owner]: the holder's handle-scope nullifier (hex), "" when the source does not say. */
    val owner: String = "",
) {
    val live: Boolean get() = status == LIVE

    /** The entry's status as of [now]: the served status, demoted when its times have passed since. */
    fun statusAt(now: Long): String = when {
        status == FREE -> FREE
        now < expiresAt -> status
        now < renewalUntil -> RENEWAL
        else -> FREE
    }

    companion object {
        const val LIVE = "live"
        const val RENEWAL = "renewal"
        const val FREE = "free"
    }
}

/**
 * The whole handle directory, fetched in full and never asked about one
 * handle: a lookup of the handle a wallet is about to pay would tell the
 * server who pays whom.
 *
 * Read from the privacy backend's stream ([fetchStream]: a snapshot of the
 * chain's Handles query at one height, paged by place from 0, restarted
 * when the height moves between pages), or, when the backend cannot serve
 * it, from the chain's own Query/Handles pages ([fetchChainPage]). Kept for
 * [maxAgeSeconds]. Before money moves on a handle (a payment, a referral)
 * [resolveForPayment] reads a fresh copy and checks the entry against the
 * chain's own directory (also fetched whole), so neither an indexer nor a
 * stale cache can redirect a payment.
 *
 * That check is worth something only while the two copies come from
 * different places. [requireBackend] says when the node cannot be the
 * other one (the user's own node over plain http, which anyone on that
 * network can answer for): a payment then needs the backend's copy, read
 * over https, to agree with the node's, and never resolves from the node
 * alone.
 */
class HandleDirectory(
    private val fetchChainPage: (start: String, limit: Int) -> Page,
    private val fetchStream: ((fromIndex: Long, limit: Int) -> StreamPage)? = null,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    private val maxAgeSeconds: Long = 600,
    private val requireBackend: () -> Boolean = { false },
) {
    /** One page of the chain's Query/Handles: handles after start; next "" when exhausted. */
    data class Page(val handles: List<HandleEntry>, val next: String)

    /** One page of the backend's stream: the snapshot's rows from fromIndex, its height and size. */
    data class StreamPage(val handles: List<HandleEntry>, val height: Long?, val size: Long, val fromIndex: Long, val lastPage: Boolean)

    class Inconsistent(message: String) : java.io.IOException(message)

    private var entries: Map<String, HandleEntry>? = null
    private var fetchedAt = 0L
    /** Whether [entries] is the backend's copy (not the chain pages it falls back on). */
    private var fromBackend = false
    private var chainEntries: Map<String, HandleEntry>? = null
    private var chainFetchedAt = 0L

    /** The directory, from cache when it is younger than [maxAge] seconds. */
    @Synchronized
    fun all(maxAge: Long = maxAgeSeconds): Map<String, HandleEntry> = read(maxAge).first

    /** The directory and whether it is the backend's copy. */
    @Synchronized
    private fun read(maxAge: Long): Pair<Map<String, HandleEntry>, Boolean> {
        entries?.let { if (now() - fetchedAt in 0..maxAge) return it to fromBackend }
        val stream = fetchStream?.let { f -> runCatching { readStream(f) }.getOrNull() }
        val out = stream ?: chainDirectory(maxAge)
        entries = out
        fromBackend = stream != null
        fetchedAt = now()
        return out to fromBackend
    }

    /** The chain's own directory (Query/Handles, every page), from cache when younger than [maxAge]. */
    @Synchronized
    fun chainDirectory(maxAge: Long = maxAgeSeconds): Map<String, HandleEntry> {
        chainEntries?.let { if (now() - chainFetchedAt in 0..maxAge) return it }
        val out = readChain()
        chainEntries = out
        chainFetchedAt = now()
        return out
    }

    /** The chain's own directory and when it was read (wallet clock): what the wallet squares its own handle with. */
    @Synchronized
    fun chainDirectoryRead(maxAge: Long = maxAgeSeconds): Pair<Map<String, HandleEntry>, Long> {
        val d = chainDirectory(maxAge)
        return d to chainFetchedAt
    }

    private fun check(e: HandleEntry, after: String, out: Map<String, HandleEntry>) {
        // In handle order, each once, well formed: anything else is not the chain's directory.
        if (!Handles.valid(e.handle)) throw Inconsistent("the directory holds ${e.handle.take(40)}, not a handle")
        if (e.handle <= after || out.containsKey(e.handle)) throw Inconsistent("the directory is out of order at ${e.handle}")
        if (e.status !in STATUSES) throw Inconsistent("handle ${e.handle}: status ${e.status.take(20)}")
        if (e.address.length > Handles.MAX_ADDRESS_LEN) throw Inconsistent("handle ${e.handle}: address too long")
        // Times a lease can have, 0 < expires_at <= renewal_until <= now + 10 years;
        // anything else is refused before any reminder or status does arithmetic on it.
        if (!timesOk(e, now())) throw Inconsistent("handle ${e.handle}: times out of range")
        if (out.size >= MAX_ROWS) throw Inconsistent("the directory has more than $MAX_ROWS handles")
    }

    private fun readChain(): Map<String, HandleEntry> {
        val out = LinkedHashMap<String, HandleEntry>()
        var start = ""
        var pages = 0
        while (true) {
            val page = fetchChainPage(start, PAGE)
            if (page.handles.size > PAGE) throw Inconsistent("the node sent ${page.handles.size} handles in a page of $PAGE")
            for (e in page.handles) { check(e, start, out); out[e.handle] = e; start = e.handle }
            if (page.next.isEmpty() || page.handles.isEmpty()) break
            if (page.next != start) throw Inconsistent("the directory's next is not its last handle")
            if (++pages > MAX_PAGES) throw Inconsistent("the directory has more than $MAX_PAGES pages")
        }
        return out
    }

    /**
     * The backend's snapshot, pages 0, PAGE, 2 PAGE, ... (its paging rule:
     * fixed, aligned pages) until its last; started over when the snapshot's
     * height changes between pages.
     */
    private fun readStream(fetch: (Long, Int) -> StreamPage): Map<String, HandleEntry> {
        repeat(STREAM_RESTARTS) {
            val out = LinkedHashMap<String, HandleEntry>()
            var from = 0L
            var height: Long? = null
            var last = ""
            var moved = false
            while (true) {
                val page = fetch(from, PAGE)
                if (page.fromIndex != from || page.handles.size > PAGE) throw Inconsistent("the indexer's handle page is not the one asked for")
                if (from == 0L) {
                    height = page.height
                    if (page.size !in 0..MAX_ROWS.toLong()) throw Inconsistent("the indexer's directory claims ${page.size} handles")
                } else if (page.height != height) { moved = true; break }
                for (e in page.handles) { check(e, last, out); out[e.handle] = e; last = e.handle }
                if (page.lastPage || page.handles.size < PAGE) {
                    if (out.size.toLong() != page.size) throw Inconsistent("the indexer's directory holds ${out.size} of its ${page.size} handles")
                    return out
                }
                from += PAGE
                if (from / PAGE > MAX_PAGES) throw Inconsistent("the directory has more than $MAX_PAGES pages")
            }
            if (!moved) throw Inconsistent("the indexer's handle stream ended early")
        }
        throw Inconsistent("the indexer's handle directory kept changing while it was read")
    }

    /** A copy fetched within the last [FRESH_SECONDS]: for a payment about to be confirmed. */
    fun fresh(): Map<String, HandleEntry> = all(FRESH_SECONDS)

    fun lookup(handle: String, maxAge: Long = maxAgeSeconds): HandleEntry? = all(maxAge)[handle]

    @Synchronized
    fun invalidate() { entries = null; chainEntries = null }

    /** Why a handle cannot be paid now, or its shielded address when it can. */
    sealed interface Resolution {
        data class Payable(val entry: HandleEntry, val address: ShieldedAddress) : Resolution
        data class NotPayable(val reason: String) : Resolution
    }

    /**
     * [input] ("@alice", "alice") resolved for a payment or a referral: live
     * now in a fresh copy of the directory, and the same (address, live) in
     * the chain's own, also fetched whole.
     */
    fun resolveForPayment(input: String): Resolution {
        val h = Handles.parse(input) ?: return Resolution.NotPayable("\"${input.trim().take(40)}\" is not a handle")
        val (dir, backend) = read(FRESH_SECONDS)
        if (requireBackend() && !backend) {
            invalidate()
            return Resolution.NotPayable(UNCONFIRMED_OVER_HTTP)
        }
        val e = dir[h] ?: return Resolution.NotPayable("@$h is not claimed by anyone")
        if (e.statusAt(now()) != HandleEntry.LIVE) return Resolution.NotPayable("@$h has lapsed and names no address now")
        val c = chainDirectory(FRESH_SECONDS)[h]
        if (c == null || c.address != e.address || c.statusAt(now()) != HandleEntry.LIVE) {
            invalidate()
            return Resolution.NotPayable("@$h changed on chain since the directory was read; try again")
        }
        val a = runCatching { ShieldedAddress.decode(e.address) }.getOrNull()
            ?: return Resolution.NotPayable("@$h names an address this wallet cannot read")
        return Resolution.Payable(e, a)
    }

    companion object {
        /** Query/Handles' largest page, and the backend stream's page. */
        const val PAGE = 1000
        /** The most rows the wallet holds (the backend's own cap); more fails closed. */
        /** Near the backend's 200k (README), well under what a phone holds. */
        const val MAX_ROWS = 250_000
        const val MAX_PAGES = MAX_ROWS / PAGE
        const val FRESH_SECONDS = 60L
        const val STREAM_RESTARTS = 3
        const val UNCONFIRMED_OVER_HTTP = "Your node is reached over plain http, so a handle is paid only when Earth's directory " +
            "confirms it, and it could not be read. Try again, or pay the address itself."
        private val STATUSES = setOf(HandleEntry.LIVE, HandleEntry.RENEWAL, HandleEntry.FREE)

        /** 0 < expires_at <= renewal_until <= now + [Handles.MAX_AHEAD_SECONDS]. */
        fun timesOk(e: HandleEntry, now: Long): Boolean =
            e.expiresAt > 0 && e.expiresAt <= e.renewalUntil && e.renewalUntil <= Handles.satAdd(now, Handles.MAX_AHEAD_SECONDS)
    }
}
