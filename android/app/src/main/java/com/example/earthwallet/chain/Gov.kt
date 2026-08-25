package network.erth.wallet.chain

import com.google.protobuf.Any as ProtoAny
import cosmos.gov.v1.MsgVote
import cosmos.gov.v1.VoteOption
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

    const val MSG_VOTE_TYPE_URL = "/cosmos.gov.v1.MsgVote"

    /** A vote carries no coins and touches one record; it does not need 400k. */
    const val VOTE_GAS_LIMIT = 150_000L

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
        /** The first message's type URL — what the proposal would actually do. */
        val messageType: String = "",
        /** Expedited proposals run for a day and need a higher threshold. */
        val expedited: Boolean = false,
        val totalDepositUerth: Long = 0L,
        val submitTime: String = "",
        val votingStartTime: String = "",
        val proposer: String = "",
        /**
         * The upgrade plan, when this is a MsgSoftwareUpgrade — the two fields
         * that decide what happens and when. Null for every other proposal
         * type, which is why they are read off the message rather than promoted
         * into the shape of every proposal.
         */
        val planName: String? = null,
        val planHeight: String? = null,
    ) {
        val total: Long get() = yes + no + abstain + veto

        /** Only an open proposal can be voted on; the rest are read-only history. */
        val isVoting: Boolean get() = status == "PROPOSAL_STATUS_VOTING_PERIOD"
    }

    /** The four options the chain accepts, and how they read on a button. */
    enum class Vote(val proto: VoteOption, val label: String) {
        Yes(VoteOption.VOTE_OPTION_YES, "Yes"),
        No(VoteOption.VOTE_OPTION_NO, "No"),
        Veto(VoteOption.VOTE_OPTION_NO_WITH_VETO, "Veto"),
        Abstain(VoteOption.VOTE_OPTION_ABSTAIN, "Abstain"),
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
            val id = p.optString("id", "0").toLongOrNull() ?: 0L
            val status = p.optString("status")

            // final_tally_result is zeroed until the EndBlocker that closes the
            // vote fills it in, so reading it while voting is open shows every
            // open proposal at 0% however much has been cast — which reads as a
            // vote that did not land. Live proposals are counted separately.
            val tally = if (status == "PROPOSAL_STATUS_VOTING_PERIOD") {
                liveTally(id) ?: p.optJSONObject("final_tally_result")
            } else {
                p.optJSONObject("final_tally_result")
            }

            val msg = p.optJSONArray("messages")?.optJSONObject(0)
            val plan = msg?.optJSONObject("plan")

            Proposal(
                id = id,
                title = p.optString("title").ifBlank { "Proposal ${p.optString("id")}" },
                summary = p.optString("summary"),
                status = status,
                votingEndTime = p.optString("voting_end_time"),
                yes = tally.count("yes_count"),
                no = tally.count("no_count"),
                abstain = tally.count("abstain_count"),
                veto = tally.count("no_with_veto_count"),
                messageType = msg?.optString("@type").orEmpty(),
                expedited = p.optBoolean("expedited"),
                totalDepositUerth = p.optJSONArray("total_deposit")
                    ?.optJSONObject(0)?.optString("amount")?.toLongOrNull() ?: 0L,
                submitTime = p.optString("submit_time"),
                votingStartTime = p.optString("voting_start_time"),
                proposer = p.optString("proposer"),
                planName = plan?.optString("name")?.ifBlank { null },
                planHeight = plan?.optString("height")?.ifBlank { null },
            )
        }
    }

    /** Votes cast so far on a proposal still open. Null if the node will not say. */
    private fun liveTally(id: Long): JSONObject? {
        val (code, body) = EarthRest.get("/cosmos/gov/v1/proposals/$id/tally")
        if (code !in 200..299) return null
        return JSONObject(body).optJSONObject("tally")
    }

    private fun JSONObject?.count(field: String): Long =
        this?.optString(field, "0")?.toLongOrNull() ?: 0L

    /**
     * A vote on [proposalId], ready for [EarthTx.broadcast].
     *
     * Weighted by bonded stake alone: an address with nothing delegated can
     * broadcast this successfully and still move the tally by nothing, so the
     * screen says so rather than letting it look like a vote that failed.
     */
    fun msgVote(voter: String, proposalId: Long, vote: Vote): ProtoAny =
        EarthTx.anyOf(
            MSG_VOTE_TYPE_URL,
            MsgVote.newBuilder()
                .setProposalId(proposalId)
                .setVoter(voter)
                .setOption(vote.proto)
                .build(),
        )
}
