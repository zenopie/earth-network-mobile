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
import network.erth.earth.proto.personhood.MsgBindReferrer
import network.erth.earth.proto.personhood.MsgClaimAnml
import network.erth.earth.proto.personhood.MsgRegister
import network.erth.earth.proto.personhood.MsgSetCaretaker
import network.erth.earth.proto.shielded.MsgTransfer
import network.erth.earth.proto.shielded.Transfer
import network.erth.earth.proto.shieldedstaking.MsgClaimUnbonding
import network.erth.earth.proto.shieldedstaking.MsgDelegate
import network.erth.earth.proto.shieldedstaking.MsgLockPosition
import network.erth.earth.proto.shieldedstaking.MsgPositionVote
import network.erth.earth.proto.shieldedstaking.MsgStakeVote
import network.erth.earth.proto.shieldedstaking.MsgUndelegate
import network.erth.earth.proto.shieldedstaking.MsgUnlockPosition
import network.erth.earth.proto.shieldedstaking.MsgUpdatePosition
import network.erth.wallet.crypto.Bech32
import network.erth.wallet.privacy.zk.Fr
import network.erth.wallet.privacy.zk.Privacy
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.ByteBuffer

/**
 * The chain's private msgs: their type URLs, and the signal each one's proofs
 * bind, ported field for field from the msg's Go `Signal` (x/shielded,
 * x/personhood, x/assembly and x/shieldedstaking types/msgs.go).
 * PrivateMsgsTest pins every signal and every encoding to the chain's output.
 *
 * Fields the chain parses as field elements (pcs, idc, nullifiers) must be
 * canonical 32-byte values; Fr.fromBytes refuses anything else, as the chain
 * does.
 */
object PrivateMsgs {
    const val TRANSFER = "/earth.shielded.v1.MsgTransfer"
    const val SHIELD = "/earth.shielded.v1.MsgShield"
    const val REGISTER = "/earth.personhood.v1.MsgRegister"
    const val CLAIM_ANML = "/earth.personhood.v1.MsgClaimAnml"
    const val SET_CARETAKER = "/earth.personhood.v1.MsgSetCaretaker"
    const val BIND_REFERRER = "/earth.personhood.v1.MsgBindReferrer"
    const val VOTE_PROPOSAL = "/earth.assembly.v1.MsgVoteProposal"
    const val PROPOSE_REMOVAL = "/earth.assembly.v1.MsgProposeRemoval"
    const val VOTE_REMOVAL = "/earth.assembly.v1.MsgVoteRemoval"
    const val DELEGATE = "/earth.shieldedstaking.v1.MsgDelegate"
    const val UNDELEGATE = "/earth.shieldedstaking.v1.MsgUndelegate"
    const val CLAIM_UNBONDING = "/earth.shieldedstaking.v1.MsgClaimUnbonding"
    const val STAKE_VOTE = "/earth.shieldedstaking.v1.MsgStakeVote"
    const val LOCK_POSITION = "/earth.shieldedstaking.v1.MsgLockPosition"
    const val UPDATE_POSITION = "/earth.shieldedstaking.v1.MsgUpdatePosition"
    const val UNLOCK_POSITION = "/earth.shieldedstaking.v1.MsgUnlockPosition"
    const val POSITION_VOTE = "/earth.shieldedstaking.v1.MsgPositionVote"
    const val NOTE_SWAP = "/earth.dex.v1.MsgNoteSwap"
    const val ADD_LIQUIDITY_SHIELDED = "/earth.dex.v1.MsgAddLiquidityShielded"

    private fun f(b: ByteString): Fr = Fr.fromBytes(b.toByteArray())
    private fun bytes(b: ByteString): Fr = Privacy.bytes(b.toByteArray())
    private fun bytes(s: String): Fr = Privacy.bytes(s.toByteArray())
    private fun u(v: Long): Fr = Privacy.u64(v)

    fun ciphertexts(t: Transfer): List<ByteArray> {
        require(t.ciphertextsCount == 3) { "a transfer carries three ciphertexts" }
        return t.ciphertextsList.map { it.toByteArray() }
    }

    fun nullifiers(t: Transfer): List<Fr> {
        require(t.nullifiersCount == 3) { "a transfer carries three nullifiers" }
        return t.nullifiersList.map(::f)
    }

    private fun action(type: String, chainId: String, fee: Transfer, extra: List<Fr>): Fr =
        Privacy.actionSignal(type, chainId, ciphertexts(fee), nullifiers(fee), extra)

    private fun spend(type: String, chainId: String, t: Transfer, extra: List<Fr>): Fr =
        Privacy.spendSignal(type, chainId, ciphertexts(t), extra)

    /** A bech32 address's raw bytes, as the chain's address codec gives them. */
    fun addressBytes(bech32: String): ByteArray = Bech32.decode(bech32)

    /** The registration binding's affiliate: Bytes(address bytes), or 0 for none. */
    fun affiliateField(affiliate: String): Fr =
        if (affiliate.isEmpty()) Fr.ZERO else Privacy.bytes(addressBytes(affiliate))

    fun registrationBinding(m: MsgRegister): Fr =
        Privacy.registrationBinding(f(m.idc), f(m.pcAnml), f(m.pcErth), affiliateField(m.affiliate))

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

    /**
     * PositionSignBytes: what a position key signs (secp256k1 over its sha256,
     * low-S, 64-byte r||s):
     * "earth.shieldedstaking.position" 0 action 0 chain_id 0 id(u64 BE) nonce(u64 BE) payload.
     */
    fun positionSignBytes(chainId: String, action: String, positionId: Long, nonce: Long, payload: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write("earth.shieldedstaking.position".toByteArray()); out.write(0)
        out.write(action.toByteArray()); out.write(0)
        out.write(chainId.toByteArray()); out.write(0)
        out.write(ByteBuffer.allocate(16).putLong(positionId).putLong(nonce).array())
        out.write(payload)
        return out.toByteArray()
    }

    fun positionVotePayload(proposalId: Long, opts: List<WeightedVoteOption>): ByteArray =
        ByteBuffer.allocate(8).putLong(proposalId).array() + optionsBytes(opts)

    /** The signal [msg]'s proofs bind on [chainId] (the msg's Go Signal). */
    fun signal(msg: MessageLite, chainId: String): Fr = when (msg) {
        is MsgTransfer -> Privacy.transferSignal(
            chainId,
            if (msg.receiver.isEmpty()) null else addressBytes(msg.receiver),
            ciphertexts(msg.transfer),
            msg.feeFromOutput,
        )
        is MsgRegister -> action(
            REGISTER, chainId, msg.fee,
            listOf(
                f(msg.idc), f(msg.pcAnml), bytes(msg.ciphertextAnml), f(msg.pcErth), bytes(msg.ciphertextErth),
                affiliateField(msg.affiliate), bytes(msg.signatureAlgorithm),
            ) + msg.publicSignalsList.map { decimalField(it) },
        )
        is MsgClaimAnml -> action(CLAIM_ANML, chainId, msg.fee, listOf(u(msg.day), f(msg.pc), bytes(msg.ciphertext)))
        is MsgSetCaretaker -> action(
            SET_CARETAKER, chainId, msg.fee,
            msg.percentagesList.flatMap { listOf(u(it.optionId), u(it.percent)) },
        )
        is MsgBindReferrer -> action(
            BIND_REFERRER, chainId, msg.fee,
            listOf(Privacy.bytes(if (msg.address.isEmpty()) ByteArray(0) else addressBytes(msg.address))),
        )
        is MsgVoteProposal -> action(VOTE_PROPOSAL, chainId, msg.fee, listOf(u(msg.proposalId), u(msg.optionValue.toLong())))
        is MsgProposeRemoval -> action(PROPOSE_REMOVAL, chainId, msg.fee, listOf(u(msg.optionId)))
        is MsgVoteRemoval -> action(VOTE_REMOVAL, chainId, msg.fee, listOf(u(msg.optionId), u(msg.optionValue.toLong())))
        is MsgDelegate -> spend(DELEGATE, chainId, msg.transfer, listOf(bytes(msg.validator), f(msg.pc), bytes(msg.ciphertext)))
        is MsgUndelegate -> spend(UNDELEGATE, chainId, msg.transfer, listOf(bytes(msg.validator), f(msg.pc), bytes(msg.ciphertext)))
        is MsgClaimUnbonding -> spend(
            CLAIM_UNBONDING, chainId, msg.transfer,
            listOf(bytes(msg.validator), u(msg.epoch), f(msg.pc), bytes(msg.ciphertext), u(msg.feeFromOutput)),
        )
        is MsgStakeVote -> multi(
            STAKE_VOTE, chainId, transfers(msg),
            listOf(
                u(msg.proposalId), bytes(msg.validator), Privacy.bytes(optionsBytes(msg.optionsList)),
                f(msg.pc), bytes(msg.ciphertext),
            ),
        )
        is MsgLockPosition -> spend(
            LOCK_POSITION, chainId, msg.transfer,
            listOf(bytes(msg.validator), bytes(msg.pubkey), Privacy.bytes(splitsBytes(msg.splitsList))),
        )
        is MsgUpdatePosition -> spend(
            UPDATE_POSITION, chainId, msg.transfer,
            listOf(u(msg.positionId), Privacy.bytes(splitsBytes(msg.splitsList)), bytes(msg.signature)),
        )
        is MsgUnlockPosition -> spend(
            UNLOCK_POSITION, chainId, msg.transfer,
            listOf(u(msg.positionId), f(msg.pc), bytes(msg.ciphertext), bytes(msg.signature)),
        )
        is MsgPositionVote -> spend(
            POSITION_VOTE, chainId, msg.transfer,
            listOf(u(msg.positionId), u(msg.proposalId), Privacy.bytes(optionsBytes(msg.optionsList)), bytes(msg.signature)),
        )
        is MsgNoteSwap -> spend(
            NOTE_SWAP, chainId, msg.transfer,
            listOf(bytes(msg.denomOut), u(msg.minAmountOut), f(msg.pc), bytes(msg.ciphertext), u(msg.feeFromOutput)),
        )
        is MsgAddLiquidityShielded -> multi(
            ADD_LIQUIDITY_SHIELDED, chainId, transfers(msg),
            listOf(
                u(msg.poolId), Privacy.bytes(addressBytes(msg.provider)), bytes(msg.minShares),
                f(msg.refundPc), bytes(msg.refundCiphertext),
            ),
        )
        else -> throw IllegalArgumentException("not a private msg: ${msg.javaClass.simpleName}")
    }

    private fun multi(type: String, chainId: String, ts: List<Transfer>, extra: List<Fr>): Fr =
        Privacy.multiSpendSignal(type, chainId, ts.map(::ciphertexts), ts.map(::nullifiers), extra)

    /** Every transfer [msg] spends, the primary first (MultiTransferMsg.PrivateTransfers). */
    fun transfers(msg: MessageLite): List<Transfer> = when (msg) {
        is MsgTransfer -> listOf(msg.transfer)
        is MsgRegister -> listOf(msg.fee)
        is MsgClaimAnml -> listOf(msg.fee)
        is MsgSetCaretaker -> listOf(msg.fee)
        is MsgBindReferrer -> listOf(msg.fee)
        is MsgVoteProposal -> listOf(msg.fee)
        is MsgProposeRemoval -> listOf(msg.fee)
        is MsgVoteRemoval -> listOf(msg.fee)
        is MsgDelegate -> listOf(msg.transfer)
        is MsgUndelegate -> listOf(msg.transfer)
        is MsgClaimUnbonding -> listOf(msg.transfer)
        is MsgStakeVote -> listOf(msg.transfer, msg.feeTransfer)
        is MsgLockPosition -> listOf(msg.transfer)
        is MsgUpdatePosition -> listOf(msg.transfer)
        is MsgUnlockPosition -> listOf(msg.transfer)
        is MsgPositionVote -> listOf(msg.transfer)
        is MsgNoteSwap -> listOf(msg.transfer)
        is MsgAddLiquidityShielded -> listOf(msg.transfer, msg.erthTransfer)
        else -> throw IllegalArgumentException("not a private msg: ${msg.javaClass.simpleName}")
    }

    /** fee_from_output, for the msgs that may pay their fee out of the ERTH they produce. */
    fun feeFromOutput(msg: MessageLite): Long = when (msg) {
        is MsgTransfer -> msg.feeFromOutput
        is MsgClaimUnbonding -> msg.feeFromOutput
        is MsgNoteSwap -> msg.feeFromOutput
        else -> 0
    }

    fun typeUrl(msg: MessageLite): String = when (msg) {
        is MsgTransfer -> TRANSFER
        is MsgRegister -> REGISTER
        is MsgClaimAnml -> CLAIM_ANML
        is MsgSetCaretaker -> SET_CARETAKER
        is MsgBindReferrer -> BIND_REFERRER
        is MsgVoteProposal -> VOTE_PROPOSAL
        is MsgProposeRemoval -> PROPOSE_REMOVAL
        is MsgVoteRemoval -> VOTE_REMOVAL
        is MsgDelegate -> DELEGATE
        is MsgUndelegate -> UNDELEGATE
        is MsgClaimUnbonding -> CLAIM_UNBONDING
        is MsgStakeVote -> STAKE_VOTE
        is MsgLockPosition -> LOCK_POSITION
        is MsgUpdatePosition -> UPDATE_POSITION
        is MsgUnlockPosition -> UNLOCK_POSITION
        is MsgPositionVote -> POSITION_VOTE
        is MsgNoteSwap -> NOTE_SWAP
        is MsgAddLiquidityShielded -> ADD_LIQUIDITY_SHIELDED
        else -> throw IllegalArgumentException("not a private msg: ${msg.javaClass.simpleName}")
    }

    /** The whole fee the tx declares: every transfer's fee plus fee_from_output (types.TotalFee). */
    fun totalFee(msg: MessageLite): Long = transfers(msg).sumOf { it.fee } + feeFromOutput(msg)

    /** A passport public signal (decimal) as a canonical field element (personhood ParseSignal). */
    fun decimalField(s: String): Fr {
        val n = BigInteger(s)
        require(n.signum() >= 0 && n < Fr.MODULUS) { "bad public signal $s" }
        return Fr.of(n)
    }
}
