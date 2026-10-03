package network.erth.wallet.privacy.tx

import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite
import cosmos.gov.v1.WeightedVoteOption
import network.erth.earth.proto.allocation.AllocationWeight
import network.erth.earth.proto.assembly.MsgProposeRemoval
import network.erth.earth.proto.assembly.MsgVoteProposal
import network.erth.earth.proto.assembly.MsgVoteRemoval
import network.erth.earth.proto.dex.MsgAddLiquidityShielded
import network.erth.earth.proto.dex.MsgNoteSwap
import network.erth.earth.proto.dex.MsgRemoveLiquidityShielded
import network.erth.earth.proto.personhood.MsgBindReferrer
import network.erth.earth.proto.personhood.MsgClaimAnml
import network.erth.earth.proto.personhood.MsgRegister
import network.erth.earth.proto.personhood.MsgSetCaretaker
import network.erth.earth.proto.shielded.Bundle
import network.erth.earth.proto.shielded.MsgSend
import network.erth.earth.proto.shieldedstaking.MsgClaimUnbonding
import network.erth.earth.proto.shieldedstaking.MsgDelegate
import network.erth.earth.proto.shieldedstaking.MsgLockPosition
import network.erth.earth.proto.shieldedstaking.MsgPositionVote
import network.erth.earth.proto.shieldedstaking.MsgRestake
import network.erth.earth.proto.shieldedstaking.MsgStakeVote
import network.erth.earth.proto.shieldedstaking.MsgUndelegate
import network.erth.earth.proto.shieldedstaking.MsgUnlockPosition
import network.erth.earth.proto.shieldedstaking.MsgUpdatePosition
import network.erth.earth.proto.shieldedstaking.StakeProof
import network.erth.wallet.crypto.Bech32
import network.erth.wallet.privacy.zk.Fr
import network.erth.wallet.privacy.zk.Grumpkin
import network.erth.wallet.privacy.zk.Privacy
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.ByteBuffer

/**
 * The chain's private msgs: their type URLs, their bundles, and the sighash
 * every proof and binding signature of one binds, ported field for field from
 * each msg's Go `SighashFields` (x/shielded, x/personhood, x/assembly,
 * x/shieldedstaking, x/dex types) and zk/orchard.Sighash. PrivateMsgsTest
 * pins every sighash and every encoding to the chain's output.
 *
 * Fields the chain parses as field elements (pcs, idc) must be canonical
 * 32-byte values; Fr.fromBytes refuses anything else, as the chain does.
 */
object PrivateMsgs {
    const val SEND = "/earth.shielded.v1.MsgSend"
    const val SHIELD = "/earth.shielded.v1.MsgShield"
    const val REGISTER = "/earth.personhood.v1.MsgRegister"
    const val CLAIM_ANML = "/earth.personhood.v1.MsgClaimAnml"
    const val SET_CARETAKER = "/earth.personhood.v1.MsgSetCaretaker"
    const val BIND_REFERRER = "/earth.personhood.v1.MsgBindReferrer"
    const val VOTE_PROPOSAL = "/earth.assembly.v1.MsgVoteProposal"
    const val PROPOSE_REMOVAL = "/earth.assembly.v1.MsgProposeRemoval"
    const val VOTE_REMOVAL = "/earth.assembly.v1.MsgVoteRemoval"
    const val DELEGATE = "/earth.shieldedstaking.v1.MsgDelegate"
    const val RESTAKE = "/earth.shieldedstaking.v1.MsgRestake"
    const val UNDELEGATE = "/earth.shieldedstaking.v1.MsgUndelegate"
    const val CLAIM_UNBONDING = "/earth.shieldedstaking.v1.MsgClaimUnbonding"
    const val STAKE_VOTE = "/earth.shieldedstaking.v1.MsgStakeVote"
    const val LOCK_POSITION = "/earth.shieldedstaking.v1.MsgLockPosition"
    const val UPDATE_POSITION = "/earth.shieldedstaking.v1.MsgUpdatePosition"
    const val UNLOCK_POSITION = "/earth.shieldedstaking.v1.MsgUnlockPosition"
    const val POSITION_VOTE = "/earth.shieldedstaking.v1.MsgPositionVote"
    const val NOTE_SWAP = "/earth.dex.v1.MsgNoteSwap"
    const val ADD_LIQUIDITY_SHIELDED = "/earth.dex.v1.MsgAddLiquidityShielded"
    const val REMOVE_LIQUIDITY_SHIELDED = "/earth.dex.v1.MsgRemoveLiquidityShielded"

    /** zk/orchard.TagBundle. */
    val TAG_BUNDLE: Fr = Fr.of(BigInteger(1, "earth.bundle".toByteArray(Charsets.US_ASCII)))

    private fun f(b: ByteString): Fr = Fr.fromBytes(b.toByteArray())
    private fun fieldOrZero(b: ByteString?): Fr = if (b == null || b.isEmpty) Fr.ZERO else Fr.fromBytes(b.toByteArray())
    private fun bytes(b: ByteString): Fr = Privacy.bytes(b.toByteArray())
    private fun bytes(s: String): Fr = Privacy.bytes(s.toByteArray())
    private fun u(v: Long): Fr = Privacy.u64(v)

    /** A bech32 address's raw bytes, as the chain's address codec gives them. */
    fun addressBytes(bech32: String): ByteArray = Bech32.decode(bech32)

    /** The registration binding's affiliate: Bytes(address bytes), or 0 for none. */
    fun affiliateField(affiliate: String): Fr =
        if (affiliate.isEmpty()) Fr.ZERO else Privacy.bytes(addressBytes(affiliate))

    fun registrationBinding(m: MsgRegister): Fr = Privacy.registrationBinding(
        f(m.idc), f(m.pcAnml), m.ciphertextAnml.toByteArray(), f(m.pcErth), m.ciphertextErth.toByteArray(), affiliateField(m.affiliate),
    )

    /** SplitsBytes: option_id then percent, big-endian u64, per entry. */
    fun splitsBytes(splits: List<AllocationWeight>): ByteArray {
        val b = ByteBuffer.allocate(16 * splits.size)
        splits.forEach { b.putLong(it.optionId).putLong(it.percent) }
        return b.array()
    }

    /** math.LegacyDec.String: the canonical 18-place decimal. */
    fun legacyDec(weight: String): String {
        val d = BigDecimal(weight)
        require(d.signum() > 0 && d <= BigDecimal.ONE) { "a vote weight is in (0, 1]" }
        return d.setScale(18, java.math.RoundingMode.UNNECESSARY).toPlainString()
    }

    /** OptionsBytes: per option, u64 BE option, u32 BE length, the weight's LegacyDec string. */
    fun optionsBytes(opts: List<WeightedVoteOption>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (o in opts) {
            val ws = legacyDec(o.weight).toByteArray()
            out.write(ByteBuffer.allocate(12).putLong(o.optionValue.toLong()).putInt(ws.size).array())
            out.write(ws)
        }
        return out.toByteArray()
    }

    // ---- bundles --------------------------------------------------------------

    /**
     * zk/orchard Bundle.Digest:
     * H(TAG_BUNDLE, N, [anchor_i, nf_i, cm_i, cvx_i, cvy_i, Bytes(ct_i)]..., M, [AssetID(denom_j), amount_j]...).
     */
    fun digest(b: Bundle): Fr {
        val xs = ArrayList<Fr>(3 + 6 * b.actionsCount + 2 * b.balancesCount)
        xs.add(TAG_BUNDLE); xs.add(u(b.actionsCount.toLong()))
        for (a in b.actionsList) {
            val cv = a.cv.toByteArray()
            require(cv.size == 64) { "cv is 64 bytes" }
            xs.add(f(a.anchor)); xs.add(f(a.nullifier)); xs.add(f(a.commitment))
            xs.add(Fr.fromBytes(cv.copyOfRange(0, 32))); xs.add(Fr.fromBytes(cv.copyOfRange(32, 64)))
            xs.add(bytes(a.ciphertext))
        }
        xs.add(u(b.balancesCount.toLong()))
        for (bal in b.balancesList) { xs.add(Privacy.assetId(bal.denom)); xs.add(u(bal.amount)) }
        return network.erth.wallet.privacy.zk.Poseidon2.hash(xs)
    }

    /** bvk = sum cv_i - sum value_a * G_a: what [b]'s binding signature verifies under. */
    fun bindingKey(b: Bundle): Grumpkin.Point {
        var bvk = Grumpkin.Point.INFINITY
        for (a in b.actionsList) bvk += Grumpkin.Point.fromBytes(a.cv.toByteArray())
        for (bal in b.balancesList) bvk -= Grumpkin.valueBase(Privacy.assetId(bal.denom)) * Grumpkin.u64(bal.amount)
        return bvk
    }

    /** Whether [b]'s binding signature holds over [sighash] (the chain's CheckBalance). */
    fun checkBalance(b: Bundle, sighash: Fr): Boolean =
        Grumpkin.verifyBinding(bindingKey(b), sighash, b.bindingSig.toByteArray())

    /** Every bundle [msg] spends, in sighash order (PrivateMsg.PrivateBundles). */
    fun bundles(msg: MessageLite): List<Bundle> = when (msg) {
        is MsgSend -> listOf(msg.bundle)
        is MsgRegister -> listOf(msg.fee)
        is MsgClaimAnml -> listOf(msg.fee)
        is MsgSetCaretaker -> listOf(msg.fee)
        is MsgBindReferrer -> listOf(msg.fee)
        is MsgVoteProposal -> listOf(msg.fee)
        is MsgProposeRemoval -> listOf(msg.fee)
        is MsgVoteRemoval -> listOf(msg.fee)
        is MsgDelegate -> listOf(msg.bundle)
        is MsgRestake -> listOf(msg.bundle)
        is MsgUndelegate -> listOf(msg.bundle)
        is MsgClaimUnbonding -> if (msg.hasBundle()) listOf(msg.bundle) else emptyList()
        is MsgStakeVote -> listOf(msg.bundle)
        is MsgLockPosition -> listOf(msg.bundle)
        is MsgUpdatePosition -> listOf(msg.bundle)
        is MsgUnlockPosition -> listOf(msg.bundle)
        is MsgPositionVote -> listOf(msg.bundle)
        is MsgNoteSwap -> listOf(msg.bundle)
        is MsgAddLiquidityShielded -> listOf(msg.bundle)
        is MsgRemoveLiquidityShielded -> listOf(msg.bundle)
        else -> throw IllegalArgumentException("not a private msg: ${msg.javaClass.simpleName}")
    }

    /** The msg's stake proof, if it carries one. */
    fun stake(msg: MessageLite): StakeProof? = when (msg) {
        is MsgDelegate -> msg.stake
        is MsgRestake -> msg.stake
        is MsgUndelegate -> msg.stake
        is MsgClaimUnbonding -> msg.stake
        is MsgStakeVote -> msg.stake
        is MsgLockPosition -> msg.stake
        is MsgUpdatePosition -> msg.stake
        is MsgUnlockPosition -> msg.stake
        is MsgPositionVote -> msg.stake
        else -> null
    }

    /**
     * StakeFields: anchor, nf_0, nf_1, cm_0, cm_1, Bytes(ct_0), Bytes(ct_1),
     * spc_mint, owner_tag, Bytes(spc_ciphertext) (an absent ciphertext is
     * Bytes of nothing).
     */
    fun stakeFields(p: StakeProof): List<Fr> = listOf(
        fieldOrZero(p.anchor), fieldOrZero(p.nullifiersList.getOrNull(0)), fieldOrZero(p.nullifiersList.getOrNull(1)),
        fieldOrZero(p.commitmentsList.getOrNull(0)), fieldOrZero(p.commitmentsList.getOrNull(1)),
        bytes(p.ciphertextsList.getOrNull(0) ?: ByteString.EMPTY), bytes(p.ciphertextsList.getOrNull(1) ?: ByteString.EMPTY),
        fieldOrZero(p.spcMint), fieldOrZero(p.ownerTag), bytes(p.spcCiphertext),
    )

    /** The bundles' summed uerth balance (shielded UerthBalance). */
    fun uerthBalance(msg: MessageLite): Long =
        bundles(msg).sumOf { b -> b.balancesList.filter { it.denom == "uerth" }.sumOf { it.amount } }

    /** The fee rule (shielded FeeAfter): the uerth balance less what the msg moves, 0 if it moves more. */
    private fun feeAfter(msg: MessageLite, moved: Long): Long = (uerthBalance(msg) - moved).coerceAtLeast(0)

    /**
     * The uerth the bundles pay to fee_collector (PrivateMsg.PrivateFee): the
     * one fee rule, every bundle's uerth balance less the uerth the msg moves
     * itself (a delegation's amount, a uerth swap's amount in, an LP
     * deposit's ERTH leg); MsgSend names its fee.
     */
    fun privateFee(msg: MessageLite): Long = when (msg) {
        is MsgSend -> msg.fee
        is MsgDelegate -> feeAfter(msg, msg.amount)
        is MsgNoteSwap -> feeAfter(msg, if (msg.denomIn == "uerth") msg.amountIn else 0)
        is MsgAddLiquidityShielded -> feeAfter(msg, msg.erthAmount)
        else -> feeAfter(msg, 0)
    }

    /** fee_from_output: only an unbonding claim pays its fee out of what it produces. */
    fun feeFromOutput(msg: MessageLite): Long = when (msg) {
        is MsgClaimUnbonding -> msg.feeFromOutput
        else -> 0
    }

    /** The whole fee the tx declares (types.TotalFee). */
    fun totalFee(msg: MessageLite): Long = privateFee(msg) + feeFromOutput(msg)

    /** The msg's own fields, bound after the bundle digests (each msg's Go SighashFields). */
    fun sighashFields(msg: MessageLite): List<Fr> = when (msg) {
        is MsgSend -> listOf(Privacy.bytes(if (msg.receiver.isEmpty()) ByteArray(0) else addressBytes(msg.receiver)), u(msg.fee))
        is MsgRegister -> listOf(
            f(msg.idc), f(msg.pcAnml), bytes(msg.ciphertextAnml), f(msg.pcErth), bytes(msg.ciphertextErth),
            affiliateField(msg.affiliate), bytes(msg.signatureAlgorithm),
        ) + msg.publicSignalsList.map { decimalField(it) }
        is MsgClaimAnml -> listOf(u(msg.day), f(msg.pc), bytes(msg.ciphertext))
        is MsgSetCaretaker -> msg.percentagesList.flatMap { listOf(u(it.optionId), u(it.percent)) }
        is MsgBindReferrer -> listOf(Privacy.bytes(if (msg.address.isEmpty()) ByteArray(0) else addressBytes(msg.address)))
        is MsgVoteProposal -> listOf(u(msg.proposalId), u(msg.optionValue.toLong()))
        is MsgProposeRemoval -> listOf(u(msg.optionId))
        is MsgVoteRemoval -> listOf(u(msg.optionId), u(msg.optionValue.toLong()))
        is MsgDelegate -> stakeFields(msg.stake) + listOf(bytes(msg.validator), u(msg.amount))
        is MsgRestake -> stakeFields(msg.stake) + listOf(bytes(msg.validator))
        is MsgUndelegate -> stakeFields(msg.stake) + listOf(bytes(msg.validator), u(msg.amount))
        is MsgClaimUnbonding -> stakeFields(msg.stake) + listOf(
            bytes(msg.validator), u(msg.epoch), u(msg.amount), f(msg.pc), bytes(msg.ciphertext), u(msg.feeFromOutput),
        )
        is MsgStakeVote -> stakeFields(msg.stake) + listOf(
            u(msg.proposalId), bytes(msg.validator), Privacy.bytes(optionsBytes(msg.optionsList)), u(msg.weight),
        )
        is MsgLockPosition -> stakeFields(msg.stake) + listOf(
            bytes(msg.validator), u(msg.amount), Privacy.bytes(splitsBytes(msg.splitsList)),
        )
        is MsgUpdatePosition -> stakeFields(msg.stake) + listOf(u(msg.positionId), Privacy.bytes(splitsBytes(msg.splitsList)))
        is MsgUnlockPosition -> stakeFields(msg.stake) + listOf(u(msg.positionId))
        is MsgPositionVote -> stakeFields(msg.stake) + listOf(
            u(msg.positionId), u(msg.proposalId), Privacy.bytes(optionsBytes(msg.optionsList)),
        )
        is MsgNoteSwap -> listOf(
            bytes(msg.denomIn), u(msg.amountIn), bytes(msg.denomOut), u(msg.minAmountOut), f(msg.pc), bytes(msg.ciphertext),
        )
        is MsgAddLiquidityShielded -> listOf(
            u(msg.poolId), bytes(msg.minShares), f(msg.sharePc), bytes(msg.shareCiphertext), f(msg.refundPc), bytes(msg.refundCiphertext),
            u(msg.erthAmount),
        )
        is MsgRemoveLiquidityShielded -> listOf(
            u(msg.poolId), f(msg.erthPc), bytes(msg.erthCiphertext), f(msg.tokenPc), bytes(msg.tokenCiphertext),
        )
        else -> throw IllegalArgumentException("not a private msg: ${msg.javaClass.simpleName}")
    }

    /**
     * The tx fields every private sighash binds (zk/orchard.TxFields): the
     * body's memo and timeout_height (0: none) and the auth info's gas limit.
     */
    data class TxFields(val memo: String = "", val timeoutHeight: Long = 0, val gasLimit: Long = 0)

    /**
     * [msg]'s sighash on [chainId] in a tx with fields [tx] (x/shielded
     * types.Sighash): Signal(type URL, chain id, K, digest(bundle_0..K-1),
     * Bytes(memo), timeout_height, gas_limit, fields...).
     */
    fun sighash(msg: MessageLite, chainId: String, tx: TxFields): Fr {
        val bs = bundles(msg)
        val txf = listOf(Privacy.bytes(tx.memo.toByteArray(Charsets.UTF_8)), u(tx.timeoutHeight), u(tx.gasLimit))
        return Privacy.signal(typeUrl(msg), chainId, listOf(u(bs.size.toLong())) + bs.map(::digest) + txf + sighashFields(msg))
    }

    fun typeUrl(msg: MessageLite): String = when (msg) {
        is MsgSend -> SEND
        is MsgRegister -> REGISTER
        is MsgClaimAnml -> CLAIM_ANML
        is MsgSetCaretaker -> SET_CARETAKER
        is MsgBindReferrer -> BIND_REFERRER
        is MsgVoteProposal -> VOTE_PROPOSAL
        is MsgProposeRemoval -> PROPOSE_REMOVAL
        is MsgVoteRemoval -> VOTE_REMOVAL
        is MsgDelegate -> DELEGATE
        is MsgRestake -> RESTAKE
        is MsgUndelegate -> UNDELEGATE
        is MsgClaimUnbonding -> CLAIM_UNBONDING
        is MsgStakeVote -> STAKE_VOTE
        is MsgLockPosition -> LOCK_POSITION
        is MsgUpdatePosition -> UPDATE_POSITION
        is MsgUnlockPosition -> UNLOCK_POSITION
        is MsgPositionVote -> POSITION_VOTE
        is MsgNoteSwap -> NOTE_SWAP
        is MsgAddLiquidityShielded -> ADD_LIQUIDITY_SHIELDED
        is MsgRemoveLiquidityShielded -> REMOVE_LIQUIDITY_SHIELDED
        else -> throw IllegalArgumentException("not a private msg: ${msg.javaClass.simpleName}")
    }

    /** A passport public signal (decimal) as a canonical field element (personhood ParseSignal). */
    fun decimalField(s: String): Fr {
        val n = BigInteger(s)
        require(n.signum() >= 0 && n < Fr.MODULUS) { "bad public signal $s" }
        return Fr.of(n)
    }
}
