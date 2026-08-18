package network.erth.wallet.chain

import com.google.protobuf.Any as ProtoAny
import network.erth.earth.proto.allocation.AllocationWeight
import network.erth.earth.proto.allocation.MsgClaimAllocation
import network.erth.earth.proto.allocation.MsgSetAllocations
import network.erth.earth.proto.allocation.StreamId
import org.json.JSONObject

/**
 * x/allocation — both vote-directed emission streams, over one engine.
 *
 * Every read and message names a stream. The two share the option mechanics and
 * share no state: ids, totals and epochs are per stream, so an option id only
 * means something together with the stream it belongs to.
 *
 *   HUMAN   — the Caretaker Fund. One human, one vote; requires a live
 *             proof-of-personhood registration (see [Personhood]).
 *   CAPITAL — the Deflation Fund. Weighted by bonded stake, and kept in step
 *             with it by the chain's staking hooks.
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
    )

    /**
     * The LCD spells the stream out in full: grpc-gateway parses the enum by
     * name and rejects the short `human` / `capital` form the chain's CLI takes.
     */
    private fun path(stream: StreamId): String = when (stream) {
        StreamId.STREAM_ID_HUMAN -> "STREAM_ID_HUMAN"
        StreamId.STREAM_ID_CAPITAL -> "STREAM_ID_CAPITAL"
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

    fun stream(streamId: StreamId): Stream {
        val (code, body) = EarthRest.get(
            "/earth-network/earth/allocation/v1/options/${path(streamId)}"
        )
        if (code !in 200..299) return Stream(emptyList(), "0")
        val json = JSONObject(body)
        return Stream(
            options = parseOptions(json),
            totalWeight = json.optString("total_weight", "0"),
        )
    }

    /** All of a stream's allocation options. */
    fun allocationOptions(stream: StreamId): List<OptionInfo> {
        val (code, body) = EarthRest.get("/earth-network/earth/allocation/v1/options/${path(stream)}")
        if (code !in 200..299) return emptyList()
        return parseOptions(JSONObject(body))
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
                )
            )
        }
        return out
    }

    /**
     * A voter's current split in one stream as (optionId, percent) pairs.
     *
     * QueryVoterResponse wraps the record in a `voter` field, so the percentages
     * are one level down. Reading them off the top level parses as empty and the
     * UI shows an unallocated voter — silently, since an empty split is a valid
     * state for someone who has never allocated.
     */
    fun voterAllocations(stream: StreamId, address: String): List<Pair<Long, Long>> {
        val (code, body) = EarthRest.get(
            "/earth-network/earth/allocation/v1/voter/${path(stream)}/$address"
        )
        if (code !in 200..299) return emptyList()
        val voter = JSONObject(body).optJSONObject("voter") ?: return emptyList()
        val arr = voter.optJSONArray("percentages") ?: return emptyList()
        val out = ArrayList<Pair<Long, Long>>(arr.length())
        for (i in 0 until arr.length()) {
            val w = arr.getJSONObject(i)
            out.add(w.optString("option_id", "0").toLong() to w.optString("percent", "0").toLong())
        }
        return out
    }

    // --- messages ---

    fun msgSetAllocations(creator: String, stream: StreamId, weights: List<Pair<Long, Long>>): ProtoAny {
        val builder = MsgSetAllocations.newBuilder().setCreator(creator).setStream(stream)
        weights.forEach { (optionId, percent) ->
            builder.addPercentages(
                AllocationWeight.newBuilder().setOptionId(optionId).setPercent(percent).build()
            )
        }
        return EarthTx.anyOf("/earth.allocation.v1.MsgSetAllocations", builder.build())
    }

    fun msgClaimAllocation(creator: String, stream: StreamId, optionId: Long): ProtoAny {
        val msg = MsgClaimAllocation.newBuilder()
            .setCreator(creator)
            .setStream(stream)
            .setOptionId(optionId)
            .build()
        return EarthTx.anyOf("/earth.allocation.v1.MsgClaimAllocation", msg)
    }
}
