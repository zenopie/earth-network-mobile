package network.erth.wallet.privacy.tx

import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite
import network.erth.earth.proto.personhood.Membership
import network.erth.earth.proto.shielded.Bundle
import network.erth.earth.proto.shieldedstaking.StakeProof
import network.erth.wallet.privacy.note.OwnedNote
import network.erth.wallet.privacy.note.OwnedStakeNote
import network.erth.wallet.privacy.prove.ActionWitness
import network.erth.wallet.privacy.prove.MembershipWitness
import network.erth.wallet.privacy.prove.StakeWitness
import network.erth.wallet.privacy.prove.VoteWitness
import network.erth.wallet.privacy.zk.Fr
import java.math.BigDecimal
import java.math.RoundingMode

/** Proves the privacy circuits. On a phone, PrivacyProver; in tests, a fake. */
interface Prover {
    fun proveAction(w: ActionWitness): ByteArray
    fun proveStake(w: StakeWitness): ByteArray
    fun proveMembership(w: MembershipWitness): ByteArray
    fun proveVote(w: VoteWitness): ByteArray
}

/** A committed tx, as much of it as the wallet reads back. */
data class TxResult(
    val hash: String,
    val height: Long,
    /** Block time, unix seconds. */
    val time: Long,
    /** (type, attributes) of every event the tx emitted. */
    val events: List<Pair<String, Map<String, String>>>,
    /** DeliverTx code: 0 is success (a looked-up tx may have failed in its block). */
    val code: Int = 0,
    val log: String = "",
    /** The module the code is from ("" for success or unknown): a code means nothing without it. */
    val codespace: String = "",
) {
    fun attr(type: String, key: String): String? = events.firstOrNull { it.first == type && key in it.second }?.second?.get(key)
}

/** What the engine needs from the chain. */
interface PrivateChain {
    /** Gas used by [tx], from the simulate endpoint. */
    fun simulate(tx: ByteArray): Long
    /**
     * Broadcasts [tx] and waits for its block. [accepted] runs with the tx
     * hash as soon as the node accepts it into the mempool (CheckTx code 0),
     * before the wait: the caller records what it spent there, so a
     * wait that times out or a killed app cannot lose it.
     */
    fun broadcast(tx: ByteArray, accepted: (hash: String) -> Unit = {}): TxResult
    /** A tx by hash: null while the node does not know it (still in the mempool, or dropped). */
    fun tx(hash: String): TxResult? = null
    /** The node's min gas price in uerth (CheckTx holds a private fee to it). */
    fun gasPrice(): BigDecimal
    /** x/shielded params.min_fee: the consensus floor on any private fee. */
    fun minFee(): Long
    /** x/shielded params.max_actions_per_bundle. */
    fun maxActionsPerBundle(): Int
    /** The chain's latest block height: a private tx's timeout_height is set from it. */
    fun tipHeight(): Long
}

/**
 * A private msg in the making: its bundles, stake proof and membership
 * (everything but proofs, binding signatures and the sighash), and how to
 * assemble the msg once they exist. [build] gets the bundles (unproven or
 * proven, in [bundles] order), the stake proof and the membership. A stake
 * vote's [vote] proof and vote nullifier are set on the built msg by the
 * engine (PrivateMsgs.withVote).
 */
class Assembled(
    val bundles: List<BundlePlan>,
    val stake: StakePlan? = null,
    val membership: MembershipWitnessSpec? = null,
    val vote: VoteWitnessSpec? = null,
    /**
     * Gas declared beyond the simulation and its headroom: what the chain
     * may charge by the tx's block that it did not when simulated (a move's
     * pair reaching its entry cap: PrivacyWallet.redelegateHeadroom).
     */
    val extraGas: Long = 0,
    val build: (bundles: List<Bundle>, stake: StakeProof?, membership: Membership?) -> MessageLite,
) {
    /** The pool notes the msg spends. */
    val spends: List<OwnedNote> get() = bundles.flatMap { it.spends }

    /** The stake notes the msg spends: lane A's and the credit lane's (a move's destination note). */
    val stakeSpends: List<OwnedStakeNote> get() = stake?.let { it.spends + listOfNotNull(it.credit?.spend) }.orEmpty()
}

/** A membership proof's statement, waiting for the sighash (its signal). */
class MembershipWitnessSpec(private val make: (signal: Fr) -> MembershipWitness) {
    fun witness(signal: Fr): MembershipWitness = make(signal)
}

/**
 * A vote proof's statement, waiting for the sighash; [vnfs] (both slots: the
 * notes' and the padding's, in the layout's order) are known before: the
 * sighash binds them.
 */
class VoteWitnessSpec(val vnfs: List<Fr>, private val make: (sighash: Fr) -> VoteWitness) {
    init {
        require(vnfs.size == PrivateMsgs.MAX_VOTE_NOTES) { "a vote has ${PrivateMsgs.MAX_VOTE_NOTES} vote nullifier slots" }
    }

    init {
        require(vnfs.none { it.isZero }) { "every vote slot carries a vote nullifier (padding included)" }
    }

    fun witness(sighash: Fr): VoteWitness = make(sighash).also { check(it.vnfs == vnfs) { "the vote witness is for other vote nullifiers" } }
}

/**
 * Runs a private tx end to end, the way the chain's ante requires:
 *
 *  1. lay the tx out at a fee, with placeholder proofs and binding
 *     signatures over its real anchors, nullifiers, commitments, value
 *     commitments and ciphertexts, and simulate it: the private ante charges
 *     every proof's fixed gas in simulate mode and runs every state check, but
 *     verifies nothing;
 *  2. fee = max(min_fee, ceil(min gas price x gas limit)), the gas limit the
 *     simulated gas plus headroom; lay the tx out again at that fee (its
 *     change, and so the bundle, change with it) until the layout's own gas
 *     is covered (a fee needing one more note adds an action);
 *  3. compute the sighash, prove every action, the stake proof and the
 *     membership over it, sign every bundle's balance, broadcast the
 *     unsigned tx.
 *
 * Gas is a function of the tx's shape alone (x/shielded/ante), so a fee
 * computed from the placeholder tx holds for the proven one.
 */
class PrivateTxEngine(
    private val chainId: String,
    private val chain: PrivateChain,
    private val prover: Prover,
    /** The absolute cap on any private fee, in uerth. */
    private val maxFee: Long = MAX_PRIVATE_FEE,
    /**
     * The height the wallet's last verified sync reached (null: no bound).
     * The tip a timeout_height is set from must be within
     * [MAX_TIP_AHEAD] of it.
     */
    private val verifiedHeight: () -> Long? = { null },
) {
    data class Quote(val gasLimit: Long, val fee: Long)

    /** The fee the chain asks is more than the sheet showed: ask the user again at [fee]. */
    class FeeAboveQuote(val fee: Long, val shown: Long) :
        IllegalStateException("the fee is now ${fee}uerth, more than the ${shown}uerth shown; confirm again")

    /** The node's tip is not near the last verified sync height: sync and retry. */
    class TipOutOfRange(val tip: Long, val verified: Long) :
        IllegalStateException("the node says the chain is at height $tip, far from the $verified this wallet last verified; sync again")

    /** The fee the node's pricing asks is past the wallet's cap: nothing is proven or sent. */
    class FeeAboveCap(val fee: Long, val cap: Long) :
        IllegalStateException("the node asks a ${fee}uerth fee, above this wallet's ${cap}uerth cap for this transaction")

    /**
     * Lays out, prices and simulates without proving: what a confirm sheet
     * may show. Simulated with random placeholder nullifiers: the
     * node learns nothing about which notes would be spent before the user
     * confirms (gas is the tx's shape, the same either way).
     */
    fun quote(assemble: (fee: Long) -> Assembled, memo: String = ""): Quote =
        price(assemble, memo, timeoutHeight(), placeholders = true).first

    /**
     * The node's tip + [TIMEOUT_BLOCKS]. A tip past the last
     * verified sync height by more than [MAX_TIP_AHEAD] is refused before
     * anything is laid out: a node inflating it would leave the spent notes
     * pending until a height the chain never reaches.
     */
    private fun timeoutHeight(): Long {
        val tip = chain.tipHeight()
        verifiedHeight()?.let { v -> if (!tipSane(tip, v)) throw TipOutOfRange(tip, v) }
        return Math.addExact(tip, TIMEOUT_BLOCKS)
    }

    /**
     * Prices, proves and broadcasts. The tx's [memo], timeout_height (the
     * chain's tip + [TIMEOUT_BLOCKS]) and the gas limit the pricing settled
     * on are fixed first: the sighash binds them, so every proof is made over
     * the tx exactly as broadcast. The fee is capped ([feeCap]) and, with
     * [shownFee], may not exceed what the confirm sheet showed. [accepted]
     * gets the hash and the timeout height before the broadcast: spent notes
     * stay pending until the chain is past it and says the tx is not in it.
     */
    fun run(
        assemble: (fee: Long) -> Assembled,
        memo: String = "",
        shownFee: Long? = null,
        accepted: (hash: String, Assembled, timeoutHeight: Long) -> Unit = { _, _, _ -> },
        /** The broadcast was refused outright (the tx is in no mempool): undo what [accepted] marked. */
        rejected: (hash: String, Assembled) -> Unit = { _, _ -> },
    ): Pair<TxResult, Assembled> {
        val timeout = timeoutHeight()
        val (q, a) = price(assemble, memo, timeout, placeholders = false)
        if (shownFee != null && q.fee > shownFee) throw FeeAboveQuote(q.fee, shownFee)
        val tx = PrivateMsgs.TxFields(memo = memo, timeoutHeight = timeout, gasLimit = q.gasLimit)
        val sighash = PrivateMsgs.sighash(draft(a), chainId, tx)
        val bundles = a.bundles.map { it.prove(sighash) { w -> proofSized(prover.proveAction(w)) } }
        bundles.forEachIndexed { i, b -> check(PrivateMsgs.checkBalance(b, sighash)) { "bundle $i does not balance" } }
        val stake = a.stake?.let { s -> s.proto(proofSized(prover.proveStake(s.witness(sighash).also { it.check() }))) }
        val membership = a.membership?.let { spec ->
            val w = spec.witness(sighash)
            Membership.newBuilder()
                .setProof(ByteString.copyFrom(proofSized(prover.proveMembership(w))))
                .setRoot(ByteString.copyFrom(w.root.toBytes()))
                .setNullifier(ByteString.copyFrom(w.nullifier.toBytes()))
                .build()
        }
        val built = a.build(bundles, stake, membership)
        val msg = a.vote?.let { v ->
            PrivateMsgs.withVote(built, v.vnfs, proofSized(prover.proveVote(v.witness(sighash).also { it.check() })))
        } ?: built
        check(PrivateMsgs.sighash(msg, chainId, tx) == sighash)
        check(PrivateMsgs.totalFee(msg) == q.fee) { "the msg must pay exactly the quoted fee" }
        checkShape(msg)
        val raw = UnsignedTx.build(msg, tx)
        // What the tx spends is marked before it is sent, under the
        // hash computed here (the chain's own: SHA-256 of the bytes). A
        // broadcast whose answer is lost (a timeout, a killed app) after the
        // node took it never leaves its notes spendable; they are released
        // only once the chain says the tx is missing or failed past its
        // timeout_height. Only a refusal that proves the tx never entered a
        // mempool (CheckTx's code, no connection at all) undoes the mark.
        val hash = UnsignedTx.hash(raw)
        accepted(hash, a, timeout)
        // Whether the node took it: decided by the submit alone. Once it has,
        // a failure while waiting for the block (a dropped connection
        // included) leaves the marks for the chain to settle by hash.
        var submitted = false
        val result = try {
            chain.broadcast(raw) { nodeHash ->
                submitted = true
                check(nodeHash.equals(hash, ignoreCase = true)) { "the node names the tx $nodeHash, not $hash" }
            }
        } catch (e: Exception) {
            if (!submitted && (e is UnsignedTx.TxRejected || e is java.net.ConnectException)) rejected(hash, a)
            throw e
        }
        return result to a
    }

    /**
     * The chain's wallet format rules, checked before broadcast: every
     * action's output ciphertext exactly 217 bytes (dummies too); a stake
     * proof's every field 32 bytes, exactly two lane A nullifiers, and a
     * 201-byte wallet stake ciphertext exactly for each non-zero
     * commitment; debt_root zero exactly when clear_before is 0.
     */
    private fun checkShape(msg: MessageLite) {
        for (b in PrivateMsgs.bundles(msg)) for (a in b.actionsList) {
            check(a.ciphertext.size() == network.erth.wallet.privacy.note.NoteCipher.CIPHERTEXT_BYTES) { "an action ciphertext is ${a.ciphertext.size()} bytes" }
        }
        PrivateMsgs.stake(msg)?.let { p ->
            check(p.nullifiersCount == 2) { "a stake proof carries two nullifiers" }
            for (f in p.nullifiersList + listOf(p.anchor, p.ownerTag, p.commitment, p.creditNullifier, p.creditCommitment, p.debtRoot)) {
                check(f.size() == 32) { "a stake proof field is ${f.size()} bytes" }
            }
            for ((cm, ct) in listOf(p.commitment to p.ciphertext, p.creditCommitment to p.creditCiphertext)) {
                val zero = Fr.fromBytes(cm.toByteArray()).isZero
                check(if (zero) ct.isEmpty else ct.size() == network.erth.wallet.privacy.note.NoteCipher.STAKE_CIPHERTEXT_BYTES) { "a stake ciphertext is ${ct.size()} bytes" }
            }
            check((p.clearBefore == 0L) == Fr.fromBytes(p.debtRoot.toByteArray()).isZero) { "debt_root is zero exactly when clear_before is 0" }
        }
    }

    /** The chain refuses any proof that is not exactly PROOF_BYTES (bb ignored trailing bytes). */
    private fun proofSized(p: ByteArray): ByteArray {
        check(p.size == PLACEHOLDER.size) { "a proof is ${PLACEHOLDER.size} bytes, got ${p.size}" }
        return p
    }

    private fun price(assemble: (fee: Long) -> Assembled, memo: String, timeout: Long, placeholders: Boolean): Pair<Quote, Assembled> {
        val minFee = chain.minFee()
        val price = chain.gasPrice()
        var fee = maxOf(minFee, feeFor(price, GUESS_GAS))
        var a = assemble(fee)
        var first = true
        repeat(MAX_RELAYS) {
            val d = draft(a, placeholders)
            val raw = UnsignedTx.build(d, 0, memo, timeout)
            val gas = chain.simulate(raw)
            val limit = Math.addExact(gas + maxOf(gas / 10, MIN_HEADROOM), a.extraGas.coerceAtLeast(0))
            val need = maxOf(minFee, feeFor(price, limit))
            // The guess is re-laid at the fee its layout needs; after that a
            // layout whose gas the fee covers is final (a fee needing one more
            // note, or one fewer leaving change, changes the action count, and
            // so the gas, so the fee may only rise from here).
            if (need == fee || (need < fee && !first)) {
                val cap = feeCap(d, a, raw.size, minFee, price)
                if (fee > cap) throw FeeAboveCap(fee, cap)
                return Quote(limit, fee) to a
            }
            first = false
            fee = need
            a = assemble(fee)
        }
        throw IllegalStateException("the fee did not settle")
    }

    /**
     * The most this tx may pay: twice the wallet's own estimate
     * from the tx's shape at x/shielded's (and the proof modules') default
     * gas, priced like the node's quote, and never more than [maxFee]. A node
     * whose simulation or prices ask more than that is refused before
     * anything is proven.
     */
    fun feeCap(msg: MessageLite, a: Assembled, txBytes: Int, minFee: Long, price: BigDecimal): Long {
        val estimate = maxOf(minFee, feeFor(price, estimateGas(msg, a, txBytes)))
        return minOf(maxFee, if (estimate > Long.MAX_VALUE / 2) Long.MAX_VALUE else 2 * estimate)
    }

    private fun draft(a: Assembled, placeholders: Boolean = false): MessageLite {
        val bundles = a.bundles.map { it.proto() }.map { if (placeholders) randomNullifiers(it) else it }
        val stake = a.stake?.proto(PLACEHOLDER)?.let { if (placeholders) randomNullifiers(it) else it }
        val msg = a.build(bundles, stake, a.membership?.let { placeholderMembership(it, placeholders) })
        // A quote's vote nullifiers are random too: the node learns nothing
        // of the notes before the user confirms.
        return a.vote?.let { v ->
            val vnfs = if (placeholders) v.vnfs.map { Fr.fromBytes(randomField().toByteArray()) } else v.vnfs
            PrivateMsgs.withVote(msg, vnfs, PLACEHOLDER)
        } ?: msg
    }

    private fun randomField(): ByteString = ByteString.copyFrom(network.erth.wallet.privacy.note.NotePlaintext.randomField().toBytes())

    private fun randomNullifiers(b: Bundle): Bundle = b.toBuilder().apply {
        for (i in 0 until actionsCount) setActions(i, getActions(i).toBuilder().setNullifier(randomField()))
    }.build()

    /** A stake proof's nullifiers (lane A's and the credit lane's), each non-zero one replaced (a zero marks an unused slot and stays). */
    private fun randomNullifiers(p: StakeProof): StakeProof = p.toBuilder().apply {
        for (i in 0 until nullifiersCount) if (!Fr.fromBytes(getNullifiers(i).toByteArray()).isZero) setNullifiers(i, randomField())
        if (!Fr.fromBytes(creditNullifier.toByteArray()).isZero) setCreditNullifier(randomField())
    }.build()

    /** The membership's real root and nullifier (the chain checks both before any proof), a placeholder proof. */
    private fun placeholderMembership(spec: MembershipWitnessSpec, placeholders: Boolean): Membership {
        val w = spec.witness(Fr.ZERO)
        return Membership.newBuilder()
            .setProof(ByteString.copyFrom(PLACEHOLDER))
            .setRoot(ByteString.copyFrom(w.root.toBytes()))
            .setNullifier(if (placeholders) randomField() else ByteString.copyFrom(w.nullifier.toBytes()))
            .build()
    }

    companion object {
        /** A proof-sized placeholder, so the simulated tx's size gas is the real tx's. */
        val PLACEHOLDER = ByteArray(network.erth.wallet.privacy.prove.PrivacyProver.PROOF_BYTES)

        /** A first guess at a private tx's gas; only sets the placeholder fee's size. */
        const val GUESS_GAS = 3_000_000L

        /** Covers a fee's varint growing by a byte or two between the simulated and the final tx. */
        const val MIN_HEADROOM = 20_000L

        private const val MAX_RELAYS = 4

        /** Blocks past the chain's tip a private tx stays valid for (its timeout_height). */
        const val TIMEOUT_BLOCKS = 50L

        /** How far past the last verified sync height a tip may be. */
        const val MAX_TIP_AHEAD = 1_000L

        fun tipSane(tip: Long, verified: Long): Boolean = tip >= 0 && verified >= 0 && tip - verified <= MAX_TIP_AHEAD

        /**
         * Whether a pending mark's timeout_height [until] could have come from
         * a sane tip, given the last verified height now (heights only grow):
         * an outsized one is resolved by the tx's status alone.
         */
        fun timeoutSane(until: Long, verifiedNow: Long): Boolean =
            verifiedNow >= 0 && until - verifiedNow <= MAX_TIP_AHEAD + TIMEOUT_BLOCKS

        /** The absolute cap on a private fee: 2 ERTH. */
        const val MAX_PRIVATE_FEE = 2_000_000L

        // The chain's default gas schedule (x/shielded params, the proof
        // modules' verification charges), for the wallet's own estimate.
        const val BASE_GAS = 100_000L
        const val TX_BYTE_GAS = 10L
        const val BUNDLE_GAS = 100_000L
        /** One action: its proof (2,000,000) and two note writes (150,000 each). */
        const val ACTION_GAS = 2_300_000L
        /**
         * A stake proof: its proof (2,000,000), two note writes per lane A
         * nullifier slot and one for its output (the indexed nullifier tree
         * rewrites two paths an insert), and the msg's base (at most 400,000;
         * the chain's PrivateActionGas).
         */
        const val STAKE_GAS = 3_150_000L
        /** A credit lane's (a redelegation's) writes, and MsgRedelegate's base beyond [STAKE_GAS]'s (700,000). */
        const val CREDIT_GAS = 3 * 150_000L + 300_000L
        /**
         * MsgRedelegate's gas for the (src, dst) pair's x/staking record at
         * its worst (the chain's redelegateGas): 2,500 an entry read and
         * written, 2,500 more each while the pair is at its 1,024-entry cap,
         * and 128 re-filed moves at 20,000. Simulation prices the real record
         * (and Assembled.extraGas a merge the pair may reach before the tx
         * lands); this keeps the cap above both whatever the node says.
         */
        const val REDELEGATE_RECORD_GAS = 1_024 * (2_500L + 2_500L) + 128 * 20_000L
        /**
         * A stake vote's fixed part: gasVote (250,000) and its proof; the
         * chain adds a note write for the vote and one per vote nullifier,
         * padding included (gasVote + proof + (1 + 2) x note_gas).
         */
        const val VOTE_GAS = 2_250_000L
        /** A membership proof and its nullifier write. */
        const val MEMBERSHIP_GAS = 2_150_000L
        /** One note write (x/shielded note_gas default). */
        const val NOTE_GAS = 150_000L
        /**
         * MsgBindHandle's writes beyond the one in [MEMBERSHIP_GAS]: the chain
         * prices a bind as nine note writes.
         */
        const val BIND_HANDLE_EXTRA_GAS = 8 * NOTE_GAS
        /** MsgRegister: the passport proof (3,000,000), the DSC chain (300,000) and two minted notes. */
        const val REGISTER_GAS = 3_600_000L

        /** The wallet's estimate of [msg]'s gas from its shape alone. */
        fun estimateGas(msg: MessageLite, a: Assembled, txBytes: Int): Long {
            var g = BASE_GAS + TX_BYTE_GAS * txBytes
            for (b in PrivateMsgs.bundles(msg)) g += BUNDLE_GAS + ACTION_GAS * b.actionsCount
            PrivateMsgs.stake(msg)?.let { p ->
                g += STAKE_GAS
                if (!Fr.fromBytes(p.creditNullifier.toByteArray()).isZero) g += CREDIT_GAS + REDELEGATE_RECORD_GAS
            }
            if (a.membership != null) g += MEMBERSHIP_GAS
            a.vote?.let { g += VOTE_GAS + (1L + it.vnfs.size) * NOTE_GAS }
            if (msg is network.erth.earth.proto.personhood.MsgRegister) g += REGISTER_GAS
            if (msg is network.erth.earth.proto.personhood.MsgBindHandle) g += BIND_HANDLE_EXTRA_GAS
            return g
        }

        fun feeFor(price: BigDecimal, gas: Long): Long =
            price.multiply(BigDecimal(gas)).setScale(0, RoundingMode.CEILING).toLong()
    }
}
