package network.erth.wallet.chain

import com.google.protobuf.Any as ProtoAny
import network.erth.earth.proto.assembly.MsgVoteProposal
import network.erth.earth.proto.assembly.VoteOption
import org.json.JSONObject

/**
 * x/assembly — the chain's second governance house.
 *
 * Every x/gov proposal is voted twice. [Gov] is the stake house, weighted by
 * bonded ERTH; this is the human one, where a live proof-of-personhood
 * registration is one vote and holdings count for nothing. A proposal needs two
 * thirds of the votes cast here before x/gov's own result is allowed to take
 * effect, so neither house substitutes for the other and a wallet with both
 * kinds of standing has two separate votes to cast.
 *
 * There is no abstain and no quorum. Approval is measured against the votes
 * cast, which has two consequences worth surfacing in the UI rather than
 * burying: a proposal nobody votes on FAILS, and at low turnout a single vote
 * decides it. One objector defeats one supporter — two thirds of (1 yes, 1 no)
 * is not met — so turnout scales with disagreement instead of being demanded up
 * front.
 */
object Assembly {

    const val MSG_VOTE_PROPOSAL_TYPE_URL = "/earth.assembly.v1.MsgVoteProposal"

    /** One record, no coins — the same shape of work as a stake vote. */
    const val VOTE_GAS_LIMIT = 150_000L

    /** Yes or no. See the class note on why there is no abstain. */
    enum class Vote(val proto: VoteOption, val label: String) {
        Yes(VoteOption.VOTE_OPTION_YES, "Yes"),
        No(VoteOption.VOTE_OPTION_NO, "No"),
    }

    /**
     * The human vote on one proposal.
     *
     * [approved] is the chain's own answer rather than arithmetic repeated
     * here: the two-thirds rule lives in one place, and a client that
     * recomputed it would quietly disagree the day the constant moves in a
     * chain upgrade.
     */
    data class Tally(
        val yes: Long,
        val no: Long,
        val approved: Boolean,
    ) {
        val total: Long get() = yes + no

        /**
         * True while nobody has voted — which is a refusal, not a blank. Worth
         * distinguishing from a low tally because it is the state most likely
         * to be mistaken for "nothing has happened yet".
         */
        val silent: Boolean get() = total == 0L
    }

    /**
     * The human tally on a proposal, or null if this chain has no assembly.
     *
     * Null is the expected answer before the v0.9.0 upgrade lands, not an
     * error: the endpoint does not exist on an older node, and a wallet that
     * shipped ahead of the upgrade — as it must, or nobody could vote when it
     * arrives — has to render a proposal without a second house and without an
     * error message about it.
     */
    fun tally(proposalId: Long): Tally? {
        val (code, body) = EarthRest.get("/earth/assembly/v1/proposal_tally/$proposalId")
        if (code !in 200..299) return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val t = json.optJSONObject("tally") ?: return null
        return Tally(
            yes = t.optString("yes", "0").toLongOrNull() ?: 0L,
            no = t.optString("no", "0").toLongOrNull() ?: 0L,
            approved = json.optBoolean("approved"),
        )
    }

    /**
     * A human vote on [proposalId], ready for [EarthTx.broadcast].
     *
     * Deliberately not [Gov.msgVote]: the chain takes both, on the same
     * proposal id, into two separate tallies. Sending this one does not cast
     * the other.
     */
    fun msgVoteProposal(voter: String, proposalId: Long, vote: Vote): ProtoAny =
        EarthTx.anyOf(
            MSG_VOTE_PROPOSAL_TYPE_URL,
            MsgVoteProposal.newBuilder()
                .setVoter(voter)
                .setProposalId(proposalId)
                .setOption(vote.proto)
                .build(),
        )
}
