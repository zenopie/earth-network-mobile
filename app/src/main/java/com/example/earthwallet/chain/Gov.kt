package network.erth.wallet.chain

import org.json.JSONObject

/**
 * Chain governance: the SDK's x/gov, unchanged.
 *
 * Separate from x/allocation, which Earth also calls governance and which is a
 * different thing: allocation votes direct an emission stream continuously and
 * are weighted by personhood or stake, while these proposals change the chain
 * itself, run for a fixed period and are weighted by bonded stake alone.
 */
object Gov {

    data class Proposal(
        val id: Long,
        val title: String,
        val summary: String,
        /** PROPOSAL_STATUS_VOTING_PERIOD and friends, as the chain names them. */
        val status: String,
        val votingEndTime: String,
        val yes: Long,
        val no: Long,
        val abstain: Long,
        val veto: Long,
    ) {
        val total: Long get() = yes + no + abstain + veto
    }

    /**
     * Proposals, newest first.
     *
     * v1 rather than v1beta1: v1 carries the title and summary as fields, where
     * v1beta1 buries them in a content Any that has to be unpacked per message
     * type.
     */
    fun proposals(limit: Int = 20): List<Proposal> {
        val (code, body) = EarthRest.get(
            "/cosmos/gov/v1/proposals?pagination.limit=$limit&pagination.reverse=true",
        )
        if (code !in 200..299) return emptyList()
        val arr = JSONObject(body).optJSONArray("proposals") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val p = arr.getJSONObject(i)
            val tally = p.optJSONObject("final_tally_result")
            Proposal(
                id = p.optString("id", "0").toLongOrNull() ?: 0L,
                title = p.optString("title").ifBlank { "Proposal ${p.optString("id")}" },
                summary = p.optString("summary"),
                status = p.optString("status"),
                votingEndTime = p.optString("voting_end_time"),
                yes = tally?.optString("yes_count", "0")?.toLongOrNull() ?: 0L,
                no = tally?.optString("no_count", "0")?.toLongOrNull() ?: 0L,
                abstain = tally?.optString("abstain_count", "0")?.toLongOrNull() ?: 0L,
                veto = tally?.optString("no_with_veto_count", "0")?.toLongOrNull() ?: 0L,
            )
        }
    }

    // Voting needs cosmos/gov/v1beta1/tx.proto vendored — only bank, base,
    // crypto, distribution, staking and tx are here, and MsgVote pulls in
    // gov.proto behind it. Reading comes first: there is nothing to vote on
    // until a proposal exists, and a chain with no proposals still needs to say
    // so rather than have no governance screen at all.
}
