package network.erth.wallet.chain

import network.erth.earth.proto.allocation.MsgAddAddressOption
import network.erth.earth.proto.allocation.MsgClaimAllocation
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
        /** An address option's payee; "" for an integrated one. */
        val recipient: String = "",
        /** Who may trigger an address option's payout; "" lets anyone. */
        val claimer: String = "",
        /** Accrued and not yet paid out, in uerth, as of its last settlement. */
        val accumulated: String = "0",
        /** The stream's reward index at its last settlement (an integer scaled by 1e18). */
        val lastRewardIndex: String = "0",
    ) {
        /**
         * What a claim would pay now, in uerth: a claim settles first, so this
         * mirrors x/allocation settleOption against the stream's [rewardIndex].
         */
        fun owed(rewardIndex: String): java.math.BigInteger {
            val acc = accumulated.toBigIntegerOrNull() ?: java.math.BigInteger.ZERO
            val amount = amountAllocated.toBigIntegerOrNull() ?: return acc
            val index = rewardIndex.toBigIntegerOrNull() ?: return acc
            val last = lastRewardIndex.toBigIntegerOrNull() ?: return acc
            if (amount.signum() <= 0 || index <= last) return acc
            return acc + amount * (index - last) / INDEX_PRECISION
        }

        /** Built into the chain (its emission goes to a module handler), not an address someone listed. */
        val integrated: Boolean get() = kind == "ALLOCATION_KIND_INTEGRATED"

        /** The description as it may be shown ([OptionText.clean]): anyone can list a Caretaker option. */
        val name: String get() = OptionText.clean(description, id)

        /** What sets a listed option apart from a built-in one named the same: "Built in", or who it pays. */
        val source: String get() = if (integrated) "Built in" else "Pays ${OptionText.short(recipient)}"
    }

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
    data class Stream(
        val options: List<OptionInfo>,
        val totalWeight: String,
        /** The stream's reward index now, for what each option is owed ([OptionInfo.owed]). */
        val rewardIndex: String = "0",
    )

    /** x/allocation's indexPrecision: reward indices are integers scaled by this. */
    private val INDEX_PRECISION: java.math.BigInteger = java.math.BigInteger.TEN.pow(18)

    /** Every page (the chain serves at most 100 options a page); a page repeated is a failed read. */
    fun stream(streamId: StreamId): Stream {
        val options = ArrayList<OptionInfo>()
        var total = "0"
        var index = "0"
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
            if (key == null) {
                total = json.optString("total_weight", "0")
                index = json.optString("reward_index", "0")
            }
            options += parseOptions(json).filter { seen.add(it.id) }
            key = json.optJSONObject("pagination")?.optString("next_key")?.takeIf { it.isNotEmpty() && it != "null" }
            if (key != null && !keys.add(key)) return Stream(emptyList(), "0")
        } while (key != null)
        return Stream(options = options, totalWeight = total, rewardIndex = index)
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
                    recipient = o.optString("recipient", ""),
                    claimer = o.optString("claimer", ""),
                    accumulated = o.optString("accumulated", "0"),
                    lastRewardIndex = o.optString("last_reward_index", "0"),
                )
            )
        }
        return out
    }

    /** params.address_option_fee: the uerth burned to list a Caretaker option. Null when unread. */
    fun addressOptionFee(): Long? {
        val (code, body) = EarthRest.get("/earth/allocation/v1/params")
        if (code !in 200..299) return null
        return JSONObject(body).optJSONObject("params")?.optString("address_option_fee")?.toLongOrNull()?.takeIf { it >= 0 }
    }

    // --- messages ---
    //
    // Votes are not sent here: the Caretaker split is cast privately
    // (PrivacyWallet.setCaretaker) and Groundworks by the split stake notes
    // vote with (PrivacyWallet.castGroundworks). MsgSetAllocations remains
    // only for a validator operator's self-bond, which this wallet does not
    // manage.

    const val MSG_ADD_ADDRESS_OPTION_TYPE_URL = "/earth.allocation.v1.MsgAddAddressOption"
    const val MSG_CLAIM_ALLOCATION_TYPE_URL = "/earth.allocation.v1.MsgClaimAllocation"

    /** Pays an address option's accrual to its recipient, signed by [creator]. An option id means something only with its [stream]. */
    fun claim(creator: String, stream: StreamId, optionId: Long): MsgClaimAllocation =
        MsgClaimAllocation.newBuilder()
            .setCreator(creator)
            .setStream(stream)
            .setOptionId(optionId)
            .build()

    /** A description's limit on chain, in UTF-8 bytes. */
    const val DESCRIPTION_MAX_BYTES = 256

    /**
     * Lists a Caretaker option paying [recipient], signed by [submitter], who
     * pays the burn. Caretaker only: Groundworks options are governance's,
     * since there an option paying its lister makes voting your own stake to
     * yourself the dominant move. No claimer: anyone may trigger the payout,
     * which goes to [recipient] regardless.
     */
    fun addCaretakerOption(submitter: String, description: String, recipient: String): MsgAddAddressOption =
        MsgAddAddressOption.newBuilder()
            .setSubmitter(submitter)
            .setStream(StreamId.STREAM_ID_CARETAKER)
            .setDescription(description)
            .setRecipient(recipient)
            .build()
}
