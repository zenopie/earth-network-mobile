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
import network.erth.earth.proto.personhood.MsgBindHandle
import network.erth.earth.proto.personhood.MsgClaimAnml
import network.erth.earth.proto.personhood.MsgMoveCaretaker
import network.erth.earth.proto.personhood.MsgMoveHandle
import network.erth.earth.proto.personhood.MsgRegister
import network.erth.earth.proto.personhood.MsgSetCaretaker
import network.erth.earth.proto.shielded.Bundle
import network.erth.earth.proto.shielded.MsgSend
import network.erth.earth.proto.shieldedstaking.MsgDelegate
import network.erth.earth.proto.shieldedstaking.MsgLockPosition
import network.erth.earth.proto.shieldedstaking.MsgPositionVote
import network.erth.earth.proto.shieldedstaking.MsgRedelegate
import network.erth.earth.proto.shieldedstaking.MsgRestake
import network.erth.earth.proto.shieldedstaking.MsgStakeVote
import network.erth.earth.proto.shieldedstaking.MsgUndelegate
import network.erth.earth.proto.shieldedstaking.MsgUnlockPosition
import network.erth.earth.proto.shieldedstaking.MsgUpdatePosition
import network.erth.earth.proto.shieldedstaking.StakeProof
import network.erth.wallet.crypto.Bech32
import network.erth.wallet.privacy.handles.Handles
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
    const val BIND_HANDLE = "/earth.personhood.v1.MsgBindHandle"
    const val MOVE_HANDLE = "/earth.personhood.v1.MsgMoveHandle"
    const val MOVE_CARETAKER = "/earth.personhood.v1.MsgMoveCaretaker"
    const val VOTE_PROPOSAL = "/earth.assembly.v1.MsgVoteProposal"
    const val PROPOSE_REMOVAL = "/earth.assembly.v1.MsgProposeRemoval"
    const val VOTE_REMOVAL = "/earth.assembly.v1.MsgVoteRemoval"
    const val DELEGATE = "/earth.shieldedstaking.v1.MsgDelegate"
    const val RESTAKE = "/earth.shieldedstaking.v1.MsgRestake"
    const val UNDELEGATE = "/earth.shieldedstaking.v1.MsgUndelegate"
    const val STAKE_VOTE = "/earth.shieldedstaking.v1.MsgStakeVote"
    const val LOCK_POSITION = "/earth.shieldedstaking.v1.MsgLockPosition"
    const val UPDATE_POSITION = "/earth.shieldedstaking.v1.MsgUpdatePosition"
    const val UNLOCK_POSITION = "/earth.shieldedstaking.v1.MsgUnlockPosition"
    const val POSITION_VOTE = "/earth.shieldedstaking.v1.MsgPositionVote"
    const val REDELEGATE = "/earth.shieldedstaking.v1.MsgRedelegate"
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

    /**
     * The registration binding's affiliate field (personhood
     * MsgRegister.AffiliateField): 0 when the registration names no
     * referrer, else H(TAG_AFFILIATE, Bytes(affiliate_handle)).
     */
    fun affiliateField(m: MsgRegister): Fr {
        if (m.affiliateHandle.isEmpty()) return Fr.ZERO
        require(Handles.valid(m.affiliateHandle)) { "affiliate_handle ${m.affiliateHandle} is not a handle" }
        return Privacy.affiliateField(m.affiliateHandle)
    }

    fun registrationBinding(m: MsgRegister, chainId: String): Fr = Privacy.registrationBinding(
        chainId, f(m.idc), f(m.pcAnml), m.ciphertextAnml.toByteArray(), f(m.pcErth), m.ciphertextErth.toByteArray(), affiliateField(m),
    )

    /**
     * MsgBindHandle's sighash fields: Bytes(handle), owner_pk, Bytes(ek_pub);
     * Bytes of nothing, 0 and Bytes of nothing for a release (both empty).
     * The address must be canonical (lowercase), as the chain requires.
     */
    fun bindHandleFields(m: MsgBindHandle): List<Fr> {
        require(m.handle.isEmpty() == m.address.isEmpty()) { "a bind names a handle and an address; a release neither" }
        if (m.address.isEmpty()) return listOf(Privacy.bytes(ByteArray(0)), Fr.ZERO, Privacy.bytes(ByteArray(0)))
        require(Handles.valid(m.handle)) { "${m.handle} is not a handle" }
        val a = network.erth.wallet.privacy.keys.ShieldedAddress.decode(m.address)
        require(a.encode() == m.address) { "the address is not in its canonical form" }
        return listOf(bytes(m.handle), a.ownerPk, Privacy.bytes(a.ekPub))
    }

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

    /** [opts] with every weight in its canonical LegacyDec form ("1" -> "1.000000000000000000"): the only form the chain takes. */
    fun canonicalOptions(opts: List<WeightedVoteOption>): List<WeightedVoteOption> =
        opts.map { it.toBuilder().setWeight(legacyDec(it.weight)).build() }

    /**
     * Every module account the chain declares (app_config moduleAccPerms):
     * an unshield to one is refused. Address = the first 20
     * bytes of SHA-256(name) (authtypes.NewModuleAddress).
     */
    val MODULE_ACCOUNTS = listOf(
        "fee_collector", "distribution", "mint", "bonded_tokens_pool", "not_bonded_tokens_pool", "gov", "nft", "transfer",
        "interchainaccounts", "shielded", "shieldedstaking", "dex", "allocation", "personhood", "earth", "wasm",
    )

    fun moduleAddress(name: String): ByteArray =
        java.security.MessageDigest.getInstance("SHA-256").digest(name.toByteArray(Charsets.US_ASCII)).copyOf(20)

    /** The module whose account [address] (20 raw bytes) is, or null. */
    fun moduleAccountOf(address: ByteArray): String? = MODULE_ACCOUNTS.firstOrNull { moduleAddress(it).contentEquals(address) }

    /** Whether YYMMDD [s] is a real calendar date (the chain refuses 250231). */
    fun isCalendarDate(s: String): Boolean {
        if (s.length != 6 || !s.all { it in '0'..'9' }) return false
        val y = 2000 + s.substring(0, 2).toInt(); val m = s.substring(2, 4).toInt(); val d = s.substring(4, 6).toInt()
        if (m !in 1..12 || d < 1) return false
        val days = intArrayOf(31, if ((y % 4 == 0 && y % 100 != 0) || y % 400 == 0) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        return d <= days[m - 1]
    }

    /** YYMMDD [s] as unix seconds at midnight UTC (the chain's yymmddToUnix), or null when it is no calendar date. */
    fun calendarDateUnix(s: String): Long? {
        if (!isCalendarDate(s)) return null
        val y = 2000 + s.substring(0, 2).toInt(); val m = s.substring(2, 4).toInt(); val d = s.substring(4, 6).toInt()
        // Days from the civil date (Hinnant), so no time-zone API is involved.
        val yy = if (m <= 2) y - 1 else y
        val era = yy / 400
        val yoe = yy - era * 400
        val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return (era * 146097L + doe - 719468) * 86_400
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
        is MsgBindHandle -> listOf(msg.fee)
        is MsgMoveHandle -> listOf(msg.fee)
        is MsgMoveCaretaker -> listOf(msg.fee)
        is MsgVoteProposal -> listOf(msg.fee)
        is MsgProposeRemoval -> listOf(msg.fee)
        is MsgVoteRemoval -> listOf(msg.fee)
        is MsgDelegate -> listOf(msg.bundle)
        is MsgRestake -> listOf(msg.bundle)
        is MsgUndelegate -> listOf(msg.bundle)
        is MsgStakeVote -> listOf(msg.bundle)
        is MsgLockPosition -> listOf(msg.bundle)
        is MsgUpdatePosition -> listOf(msg.bundle)
        is MsgUnlockPosition -> listOf(msg.bundle)
        is MsgPositionVote -> listOf(msg.bundle)
        is MsgRedelegate -> listOf(msg.bundle)
        is MsgNoteSwap -> listOf(msg.bundle)
        is MsgAddLiquidityShielded -> listOf(msg.bundle)
        is MsgRemoveLiquidityShielded -> listOf(msg.bundle)
        else -> throw IllegalArgumentException("not a private msg: ${msg.javaClass.simpleName}")
    }

    /** Vote nullifier slots in every MsgStakeVote (x/shieldedstaking MaxVoteNotes, circuits/vote MAX_NOTES). */
    const val MAX_VOTE_NOTES = 2

    /**
     * A stake vote with its vote nullifiers and proof set (the sighash binds
     * the nullifiers, not the proof): exactly [MAX_VOTE_NOTES], the used
     * slots' first, then zeros.
     */
    fun withVote(msg: MessageLite, vnfs: List<Fr>, proof: ByteArray): MessageLite = when (msg) {
        is MsgStakeVote -> {
            require(vnfs.size == MAX_VOTE_NOTES) { "a stake vote carries exactly $MAX_VOTE_NOTES vote nullifiers" }
            msg.toBuilder().clearVoteNullifiers().addAllVoteNullifiers(vnfs.map { ByteString.copyFrom(it.toBytes()) })
                .setProof(ByteString.copyFrom(proof)).build()
        }
        else -> throw IllegalArgumentException("not a stake vote: ${msg.javaClass.simpleName}")
    }

    /** The msg's stake proof, if it carries one. */
    fun stake(msg: MessageLite): StakeProof? = when (msg) {
        is MsgDelegate -> msg.stake
        is MsgRestake -> msg.stake
        is MsgUndelegate -> msg.stake
        is MsgLockPosition -> msg.stake
        is MsgUpdatePosition -> msg.stake
        is MsgUnlockPosition -> msg.stake
        is MsgPositionVote -> msg.stake
        is MsgRedelegate -> msg.stake
        else -> null
    }

    /**
     * StakeFields: anchor, nf_0, nf_1, cm, Bytes(ct), credit_nf, credit_cm,
     * Bytes(credit_ct), owner_tag, clear_before, debt_root (an absent
     * ciphertext is Bytes of nothing).
     */
    fun stakeFields(p: StakeProof): List<Fr> = listOf(
        fieldOrZero(p.anchor), fieldOrZero(p.nullifiersList.getOrNull(0)), fieldOrZero(p.nullifiersList.getOrNull(1)),
        fieldOrZero(p.commitment), bytes(p.ciphertext),
        fieldOrZero(p.creditNullifier), fieldOrZero(p.creditCommitment), bytes(p.creditCiphertext),
        fieldOrZero(p.ownerTag), u(p.clearBefore), fieldOrZero(p.debtRoot),
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

    /** The whole fee the tx declares (types.TotalFee): the private fee. */
    fun totalFee(msg: MessageLite): Long = privateFee(msg)

    /** The msg's own fields, bound after the bundle digests (each msg's Go SighashFields). */
    fun sighashFields(msg: MessageLite): List<Fr> = when (msg) {
        is MsgSend -> listOf(Privacy.bytes(if (msg.receiver.isEmpty()) ByteArray(0) else addressBytes(msg.receiver)), u(msg.fee))
        is MsgRegister -> listOf(
            f(msg.idc), f(msg.pcAnml), bytes(msg.ciphertextAnml), f(msg.pcErth), bytes(msg.ciphertextErth),
            affiliateField(msg), bytes(msg.signatureAlgorithm),
        ) + msg.publicSignalsList.map { decimalField(it) }
        is MsgClaimAnml -> listOf(u(msg.day), f(msg.pc), bytes(msg.ciphertext))
        is MsgSetCaretaker -> msg.percentagesList.flatMap { listOf(u(it.optionId), u(it.percent)) }
        is MsgBindHandle -> bindHandleFields(msg)
        is MsgMoveHandle -> listOf(bytes(msg.handle), f(msg.newOwner))
        is MsgMoveCaretaker -> listOf(f(msg.newOwner))
        is MsgVoteProposal -> listOf(u(msg.proposalId), u(msg.optionValue.toLong()))
        is MsgProposeRemoval -> listOf(u(msg.optionId))
        is MsgVoteRemoval -> listOf(u(msg.optionId), u(msg.optionValue.toLong()))
        is MsgDelegate -> stakeFields(msg.stake) + listOf(bytes(msg.validator), u(msg.amount), u(msg.derth))
        is MsgRestake -> stakeFields(msg.stake) + listOf(bytes(msg.validator))
        is MsgUndelegate -> stakeFields(msg.stake) + listOf(bytes(msg.validator), u(msg.amount), f(msg.pc), bytes(msg.ciphertext))
        // A vote carries no stake proof (ORCHARD_DESIGN 8.5): its vote proof's statement is the chain's.
        is MsgStakeVote -> {
            require(msg.voteNullifiersCount == MAX_VOTE_NOTES) { "a stake vote carries exactly $MAX_VOTE_NOTES vote nullifiers" }
            listOf(u(msg.proposalId), bytes(msg.validator), Privacy.bytes(optionsBytes(msg.optionsList)), u(msg.weight)) +
                msg.voteNullifiersList.map { f(it) } + listOf(f(msg.debtRoot))
        }
        is MsgLockPosition -> stakeFields(msg.stake) + listOf(
            bytes(msg.validator), u(msg.amount), Privacy.bytes(splitsBytes(msg.splitsList)),
        )
        is MsgUpdatePosition -> stakeFields(msg.stake) + listOf(u(msg.positionId), Privacy.bytes(splitsBytes(msg.splitsList)))
        is MsgUnlockPosition -> stakeFields(msg.stake) + listOf(u(msg.positionId))
        is MsgPositionVote -> stakeFields(msg.stake) + listOf(
            u(msg.positionId), u(msg.proposalId), Privacy.bytes(optionsBytes(msg.optionsList)),
        )
        is MsgRedelegate -> stakeFields(msg.stake) + listOf(
            bytes(msg.srcValidator), bytes(msg.dstValidator), u(msg.amount), u(msg.dstDerth), u(msg.moveTime),
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
        is MsgBindHandle -> BIND_HANDLE
        is MsgMoveHandle -> MOVE_HANDLE
        is MsgMoveCaretaker -> MOVE_CARETAKER
        is MsgVoteProposal -> VOTE_PROPOSAL
        is MsgProposeRemoval -> PROPOSE_REMOVAL
        is MsgVoteRemoval -> VOTE_REMOVAL
        is MsgDelegate -> DELEGATE
        is MsgRestake -> RESTAKE
        is MsgUndelegate -> UNDELEGATE
        is MsgStakeVote -> STAKE_VOTE
        is MsgLockPosition -> LOCK_POSITION
        is MsgUpdatePosition -> UPDATE_POSITION
        is MsgUnlockPosition -> UNLOCK_POSITION
        is MsgPositionVote -> POSITION_VOTE
        is MsgRedelegate -> REDELEGATE
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
