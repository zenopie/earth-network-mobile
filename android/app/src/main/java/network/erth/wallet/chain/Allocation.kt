package network.erth.wallet.chain

import network.erth.earth.proto.allocation.StreamId
import org.json.JSONObject

/**
 * x/allocation — both vote-directed emission streams, over one engine.
 *
 * Every read and message names a stream. The two share the option mechanics and
 * share no state: ids, totals and epochs are per stream, so an option id only
 * means something together with the stream it belongs to.
 *
 *   CARETAKER   — the Caretaker Fund. One human, one vote; requires a live
 *                 proof-of-personhood registration (see [Personhood]).
 *   GROUNDWORKS — the Deflation Fund. Weighted by private stake: each stake
 *                 note votes the wallet's split (privacy/PrivacyWallet castGroundworks).
 */
object Allocation {

    /** An allocation destination voters can direct emissions to. */
    data class OptionInfo(
        val id: Long,
        val description: String,
        val kind: String,
        val amountAllocated: String = "0",
        /**
         * What the chain does with this option's emission.
         *
         * "lp_rewards" is the one the liquidity screen cares about — it names
         * the option whose accrual is handed to the dex. Matching on the
         * handler rather than the description means a rename in governance
         * does not silently detach the APR from its source.
         */
        val handler: String = "",
        /** Struck by the assembly (x/allocation Option.removed): kept until pruned, but no split may name it. */
        val removed: Boolean = false,
    )

    /**
     * The LCD spells the stream out in full: grpc-gateway parses the enum by
     * name and rejects the short `human` / `capital` form the chain's CLI takes.
     */
    private fun path(stream: StreamId): String = when (stream) {
        StreamId.STREAM_ID_CARETAKER -> "STREAM_ID_CARETAKER"
        StreamId.STREAM_ID_GROUNDWORKS -> "STREAM_ID_GROUNDWORKS"
        else -> throw IllegalArgumentException("unknown allocation stream: $stream")
    }

    // --- queries ---

    /**
     * A stream's options, and the weight they are shares of.
     *
     * total_weight is the denominator: an option earns
     * amountAllocated / totalWeight of the stream's 1 ERTH/sec. Returned
     * alongside rather than left to the caller to sum, because the response
     * carries it and a client-side sum would drift the moment an option is
     * added between queries.
     */
    data class Stream(val options: List<OptionInfo>, val totalWeight: String)

    /** Every page (the chain serves at most 100 options a page); a page repeated is a failed read. */
    fun stream(streamId: StreamId): Stream {
        val options = ArrayList<OptionInfo>()
        var total = "0"
        var key: String? = null
        val keys = HashSet<String>()
        val seen = HashSet<Long>()
        do {
            // Capped: a node serving new keys forever is not followed.
            if (keys.size >= 100) return Stream(emptyList(), "0")
            val (code, body) = EarthRest.get(
                "/earth/allocation/v1/options/${path(streamId)}" +
                    (key?.let { "?pagination.key=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: "")
            )
            if (code !in 200..299) return Stream(emptyList(), "0")
            val json = JSONObject(body)
            if (key == null) total = json.optString("total_weight", "0")
            options += parseOptions(json).filter { seen.add(it.id) }
            key = json.optJSONObject("pagination")?.optString("next_key")?.takeIf { it.isNotEmpty() && it != "null" }
            if (key != null && !keys.add(key)) return Stream(emptyList(), "0")
        } while (key != null)
        return Stream(options = options, totalWeight = total)
    }

    private fun parseOptions(json: JSONObject): List<OptionInfo> {
        val arr = json.optJSONArray("options") ?: return emptyList()
        val out = ArrayList<OptionInfo>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                OptionInfo(
                    id = o.optString("id", "0").toLong(),
                    description = o.optString("description", ""),
                    kind = o.optString("kind", ""),
                    amountAllocated = o.optString("amount_allocated", "0"),
                    handler = o.optString("handler", ""),
                    removed = o.optBoolean("removed", false),
                )
            )
        }
        return out
    }

    // No messages. The Caretaker split is cast privately
    // (PrivacyWallet.setCaretaker) and Groundworks by the split stake notes
    // vote with (PrivacyWallet.castGroundworks). MsgSetAllocations
    // remains only for a validator operator's self-bond, which this wallet
    // does not manage.
}
