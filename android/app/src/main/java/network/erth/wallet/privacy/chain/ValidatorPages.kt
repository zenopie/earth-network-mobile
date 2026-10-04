package network.erth.wallet.privacy.chain

import network.erth.wallet.privacy.PrivacyChainReads
import org.json.JSONObject
import java.io.IOException
import java.math.BigDecimal
import java.math.BigInteger

/**
 * x/shieldedstaking Query/Validators (`/earth/shieldedstaking/v1/validators`,
 * ORCHARD_DESIGN 12.2): every validator's quote inputs as one paged list,
 * read whole so that no query names the validator a staking msg is about to
 * act on. Every page is read at the first page's height (the later ones
 * pinned with `x-cosmos-block-height`); a page from another height, or one
 * the node cannot serve at it, starts the read over.
 */
object ValidatorPages {
    /** One page: its entries, next_key ("" on the last) and the height it was read at. */
    data class Page(val validators: List<PrivacyChainReads.ValidatorQuote>, val nextKey: String, val height: Long)

    /** The chain's largest page (MaxValidatorsPage). */
    const val PAGE_LIMIT = 200

    /** The most entries the wallet takes (far above any validator set). */
    const val MAX_VALIDATORS = 10_000

    /** Reads started over before giving up, each on a height change. */
    const val MAX_READS = 4

    /** The page after [key] ("" the first), pinned to [height] (null: the latest); null when not served at that height. */
    fun interface Fetch {
        fun page(key: String, height: Long?): Page?
    }

    /** Every page at one height, or an error: never a mix of two states. */
    fun readAll(fetch: Fetch): PrivacyChainReads.ValidatorList {
        repeat(MAX_READS) {
            readOnce(fetch)?.let { return it }
        }
        throw IOException("the validator list changed height on every read; try again")
    }

    private fun readOnce(fetch: Fetch): PrivacyChainReads.ValidatorList? {
        val first = fetch.page("", null) ?: return null
        val height = first.height
        if (height <= 0) throw IOException("the validator list names no height")
        val out = LinkedHashMap<String, PrivacyChainReads.ValidatorQuote>()
        var page = first
        val keys = HashSet<String>()
        while (true) {
            if (page.height != height) return null
            for (v in page.validators) {
                if (out.put(v.validator, v) != null) throw IOException("the validator list carries ${v.validator} twice")
                if (out.size > MAX_VALIDATORS) throw IOException("the validator list is longer than $MAX_VALIDATORS")
            }
            if (page.nextKey.isEmpty()) return PrivacyChainReads.ValidatorList(height, out.values.toList())
            if (!keys.add(page.nextKey)) throw IOException("the validator list's pages loop")
            page = fetch.page(page.nextKey, height) ?: return null
        }
    }

    /** A Query/Validators response body. */
    fun parse(j: JSONObject): Page {
        val height = str(j, "height").ifEmpty { "0" }.toLongOrNull() ?: throw IOException("the validator list's height is not an int64")
        val a = j.optJSONArray("validators")
        val vs = (0 until (a?.length() ?: 0)).map { i -> quote(a!!.getJSONObject(i)) }
        val next = j.optJSONObject("pagination")?.let { str(it, "next_key") }.orEmpty()
        return Page(vs, next, height)
    }

    /** One ValidatorQuote. */
    fun quote(v: JSONObject): PrivacyChainReads.ValidatorQuote {
        val op = str(v, "validator")
        if (op.isEmpty()) throw IOException("a validator list entry names no validator")
        val st = v.optJSONObject("staking") ?: JSONObject()
        val stOp = str(st, "operator_address")
        if (stOp.isNotEmpty() && stOp != op) throw IOException("$op's x/staking record is $stOp's")
        val book = v.optJSONObject("book") ?: JSONObject()
        fun int(o: JSONObject, k: String): BigInteger {
            val s = str(o, k).ifEmpty { "0" }
            if (s.length > 80 || !s.all { it in '0'..'9' }) throw IOException("$op's $k is not a non-negative integer")
            return BigInteger(s)
        }
        val reds = v.optJSONArray("redelegations")
        val loads = HashMap<String, PrivacyChainReads.RedelegationLoad>()
        for (i in 0 until (reds?.length() ?: 0)) {
            val r = reds!!.getJSONObject(i)
            val dst = str(r, "dst_validator")
            if (dst.isEmpty()) continue
            loads[dst] = PrivacyChainReads.RedelegationLoad(int(r, "entries").min(U32).toLong(), int(r, "counted_entries").min(U32).toLong())
        }
        val backing = int(v, "backing")
        val supply = int(v, "supply")
        val rate = str(v, "rate").toBigDecimalOrNull()?.takeIf { it.signum() >= 0 }
            ?: if (supply.signum() == 0) BigDecimal.ONE else throw IOException("$op's rate is not a non-negative decimal")
        return PrivacyChainReads.ValidatorQuote(
            validator = op,
            backing = backing,
            supply = supply,
            pendingDelegation = int(book, "pending_delegation"),
            pendingUndelegation = int(book, "pending_undelegation"),
            delegation = int(v, "delegation"),
            rewards = int(v, "rewards"),
            rate = rate,
            // A book whose validator x/staking removed: no status.
            status = if (stOp.isEmpty()) "" else str(st, "status"),
            jailed = stOp.isNotEmpty() && st.optBoolean("jailed"),
            tombstoned = v.optBoolean("tombstoned"),
            delegatable = v.optBoolean("delegatable"),
            refusal = str(v, "refusal").take(300),
            moniker = st.optJSONObject("description")?.let { str(it, "moniker") }.orEmpty().take(70),
            commission = st.optJSONObject("commission")?.optJSONObject("commission_rates")?.let { str(it, "rate") }
                ?.toDoubleOrNull()?.takeIf { it in 0.0..1.0 } ?: 0.0,
            tokens = int(st, "tokens"),
            redelegations = loads,
        )
    }

    private val U32 = BigInteger.valueOf(0xffff_ffffL)

    /** [k]'s string, "" when absent or null (org.json's optString gives "null" for a JSON null). */
    private fun str(o: JSONObject, k: String): String = if (o.isNull(k)) "" else o.optString(k)
}
