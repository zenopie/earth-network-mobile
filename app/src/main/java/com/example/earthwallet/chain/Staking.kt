package network.erth.wallet.chain

import com.google.protobuf.Any as ProtoAny
import cosmos.base.v1beta1.CoinOuterClass
import cosmos.distribution.v1beta1.MsgWithdrawDelegatorReward
import cosmos.staking.v1beta1.MsgBeginRedelegate
import cosmos.staking.v1beta1.MsgCancelUnbondingDelegation
import cosmos.staking.v1beta1.MsgDelegate
import cosmos.staking.v1beta1.MsgUndelegate
import network.erth.wallet.Constants
import org.json.JSONObject

/** Native x/staking + x/distribution queries and messages. */
object Staking {

    data class Validator(
        val operator: String,
        val moniker: String,
        val tokens: String,
        /** Commission rate as a fraction, e.g. 0.10 for 10%. */
        val commission: Double = 0.0,
    )
    data class Delegation(val validator: String, val amount: String)
    /** A single native unbonding entry (funds auto-return to balance at [completionTime]). */
    data class UnbondingEntry(
        val validator: String,
        val balance: String,
        val completionTime: String,
        /**
         * Height the entry was created at. Cancelling addresses an entry by
         * (validator, creationHeight) — unbonding entries have no id — so this
         * must be carried through or the cancel cannot be built.
         */
        val creationHeight: Long,
    )

    /** Stake in flight between validators: still bonded, but locked until it matures. */
    data class RedelegationEntry(
        val src: String,
        val dst: String,
        val balance: String,
        val completionTime: String,
    )

    private fun uerth(amount: String) =
        CoinOuterClass.Coin.newBuilder().setDenom(Constants.UERTH_DENOM).setAmount(amount).build()

    // --- queries ---

    fun bondedValidators(): List<Validator> {
        val (code, body) = EarthRest.get(
            "/cosmos/staking/v1beta1/validators?status=BOND_STATUS_BONDED&pagination.limit=200"
        )
        if (code !in 200..299) return emptyList()
        val arr = JSONObject(body).optJSONArray("validators") ?: return emptyList()
        val out = ArrayList<Validator>(arr.length())
        for (i in 0 until arr.length()) {
            val v = arr.getJSONObject(i)
            out.add(
                Validator(
                    operator = v.getString("operator_address"),
                    moniker = v.optJSONObject("description")?.optString("moniker", "") ?: "",
                    tokens = v.optString("tokens", "0"),
                    commission = v.optJSONObject("commission")
                        ?.optJSONObject("commission_rates")
                        ?.optString("rate", "0")?.toDoubleOrNull() ?: 0.0,
                )
            )
        }
        return out
    }

    fun delegations(delegator: String): List<Delegation> {
        val (code, body) = EarthRest.get("/cosmos/staking/v1beta1/delegations/$delegator")
        if (code !in 200..299) return emptyList()
        val arr = JSONObject(body).optJSONArray("delegation_responses") ?: return emptyList()
        val out = ArrayList<Delegation>(arr.length())
        for (i in 0 until arr.length()) {
            val d = arr.getJSONObject(i)
            out.add(
                Delegation(
                    validator = d.getJSONObject("delegation").getString("validator_address"),
                    amount = d.getJSONObject("balance").getString("amount"),
                )
            )
        }
        return out
    }

    /** Total uerth bonded across the whole network (staking pool bonded_tokens). */
    fun totalBonded(): String {
        val (code, body) = EarthRest.get("/cosmos/staking/v1beta1/pool")
        if (code !in 200..299) return "0"
        return JSONObject(body).optJSONObject("pool")?.optString("bonded_tokens", "0") ?: "0"
    }

    /** In-progress unbonding entries for a delegator (native staking auto-releases at completion). */
    fun unbondingDelegations(delegator: String): List<UnbondingEntry> {
        val (code, body) = EarthRest.get("/cosmos/staking/v1beta1/delegators/$delegator/unbonding_delegations")
        if (code !in 200..299) return emptyList()
        val arr = JSONObject(body).optJSONArray("unbonding_responses") ?: return emptyList()
        val out = ArrayList<UnbondingEntry>()
        for (i in 0 until arr.length()) {
            val resp = arr.getJSONObject(i)
            val validator = resp.getString("validator_address")
            val entries = resp.optJSONArray("entries") ?: continue
            for (j in 0 until entries.length()) {
                val e = entries.getJSONObject(j)
                out.add(
                    UnbondingEntry(
                        validator = validator,
                        balance = e.optString("balance", "0"),
                        creationHeight = e.optString("creation_height", "0").toLongOrNull() ?: 0L,
                        completionTime = e.optString("completion_time", ""),
                    )
                )
            }
        }
        return out
    }

    /** Total pending uerth rewards across all validators. */
    fun totalRewards(delegator: String): String {
        val (code, body) = EarthRest.get("/cosmos/distribution/v1beta1/delegators/$delegator/rewards")
        if (code !in 200..299) return "0"
        val total = JSONObject(body).optJSONArray("total") ?: return "0"
        for (i in 0 until total.length()) {
            val c = total.getJSONObject(i)
            if (c.getString("denom") == Constants.UERTH_DENOM) {
                // rewards are DecCoins (may be fractional); truncate to integer uerth
                return c.getString("amount").substringBefore(".")
            }
        }
        return "0"
    }

    /** In-progress redelegations for a delegator. */
    fun redelegations(delegator: String): List<RedelegationEntry> {
        val (code, body) = EarthRest.get("/cosmos/staking/v1beta1/delegators/$delegator/redelegations")
        if (code !in 200..299) return emptyList()
        val arr = JSONObject(body).optJSONArray("redelegation_responses") ?: return emptyList()
        val out = ArrayList<RedelegationEntry>()
        for (i in 0 until arr.length()) {
            val resp = arr.getJSONObject(i)
            val red = resp.optJSONObject("redelegation")
            val entries = resp.optJSONArray("entries") ?: continue
            for (j in 0 until entries.length()) {
                val e = entries.getJSONObject(j)
                out.add(
                    RedelegationEntry(
                        src = red?.optString("validator_src_address", "") ?: "",
                        dst = red?.optString("validator_dst_address", "") ?: "",
                        balance = e.optString("balance", "0"),
                        completionTime = e.optJSONObject("redelegation_entry")
                            ?.optString("completion_time", "") ?: "",
                    )
                )
            }
        }
        return out
    }

    // --- messages ---

    fun msgDelegate(delegator: String, validator: String, amountUerth: String): ProtoAny =
        EarthTx.anyOf(
            "/cosmos.staking.v1beta1.MsgDelegate",
            MsgDelegate.newBuilder()
                .setDelegatorAddress(delegator).setValidatorAddress(validator).setAmount(uerth(amountUerth))
                .build()
        )

    fun msgUndelegate(delegator: String, validator: String, amountUerth: String): ProtoAny =
        EarthTx.anyOf(
            "/cosmos.staking.v1beta1.MsgUndelegate",
            MsgUndelegate.newBuilder()
                .setDelegatorAddress(delegator).setValidatorAddress(validator).setAmount(uerth(amountUerth))
                .build()
        )

    /**
     * Move stake between validators without unbonding — it keeps earning, with no
     * 21-day gap. The chain refuses to redelegate stake that is already in flight,
     * and caps concurrent entries between any validator pair.
     */
    fun msgBeginRedelegate(delegator: String, src: String, dst: String, amountUerth: String): ProtoAny =
        EarthTx.anyOf(
            "/cosmos.staking.v1beta1.MsgBeginRedelegate",
            MsgBeginRedelegate.newBuilder()
                .setDelegatorAddress(delegator)
                .setValidatorSrcAddress(src)
                .setValidatorDstAddress(dst)
                .setAmount(uerth(amountUerth))
                .build()
        )

    /**
     * Cancel an in-progress unbonding, returning the stake to the same validator.
     * Partial cancels are allowed; the remainder keeps its original schedule.
     */
    fun msgCancelUnbonding(
        delegator: String,
        validator: String,
        amountUerth: String,
        creationHeight: Long,
    ): ProtoAny =
        EarthTx.anyOf(
            "/cosmos.staking.v1beta1.MsgCancelUnbondingDelegation",
            MsgCancelUnbondingDelegation.newBuilder()
                .setDelegatorAddress(delegator)
                .setValidatorAddress(validator)
                .setAmount(uerth(amountUerth))
                .setCreationHeight(creationHeight)
                .build()
        )

    fun msgWithdrawReward(delegator: String, validator: String): ProtoAny =
        EarthTx.anyOf(
            "/cosmos.distribution.v1beta1.MsgWithdrawDelegatorReward",
            MsgWithdrawDelegatorReward.newBuilder()
                .setDelegatorAddress(delegator).setValidatorAddress(validator)
                .build()
        )
}
