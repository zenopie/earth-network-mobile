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
import network.erth.wallet.privacy.zk.Fr
import java.math.BigDecimal
import java.math.RoundingMode

/** Proves the privacy circuits. On a phone, PrivacyProver; in tests, a fake. */
interface Prover {
    fun proveAction(w: ActionWitness): ByteArray
    fun proveStake(w: StakeWitness): ByteArray
    fun proveMembership(w: MembershipWitness): ByteArray
}

/** A committed tx, as much of it as the wallet reads back. */
data class TxResult(
    val hash: String,
    val height: Long,
    /** Block time, unix seconds. */
    val time: Long,
    /** (type, attributes) of every event the tx emitted. */
    val events: List<Pair<String, Map<String, String>>>,
) {
    fun attr(type: String, key: String): String? = events.firstOrNull { it.first == type && key in it.second }?.second?.get(key)
}

/** What the engine needs from the chain. */
interface PrivateChain {
    /** Gas used by [tx], from the simulate endpoint. */
    fun simulate(tx: ByteArray): Long
    fun broadcast(tx: ByteArray): TxResult
    /** The node's min gas price in uerth (CheckTx holds a private fee to it). */
    fun gasPrice(): BigDecimal
    /** x/shielded params.min_fee: the consensus floor on any private fee. */
    fun minFee(): Long
    /** x/shielded params.max_actions_per_bundle. */
    fun maxActionsPerBundle(): Int
}

/**
 * A private msg in the making: its bundles, stake proof and membership
 * (everything but proofs, binding signatures and the sighash), and how to
 * assemble the msg once they exist. [build] gets the bundles (unproven or
 * proven, in [bundles] order), the stake proof and the membership.
 */
class Assembled(
    val bundles: List<BundlePlan>,
    val stake: StakePlan? = null,
    val membership: MembershipWitnessSpec? = null,
    val build: (bundles: List<Bundle>, stake: StakeProof?, membership: Membership?) -> MessageLite,
) {
    /** The pool notes the msg spends. */
    val spends: List<OwnedNote> get() = bundles.flatMap { it.spends }

    /** The stake notes the msg spends. */
    val stakeSpends: List<OwnedStakeNote> get() = stake?.spends.orEmpty()
}

/** A membership proof's statement, waiting for the sighash (its signal). */
class MembershipWitnessSpec(private val make: (signal: Fr) -> MembershipWitness) {
    fun witness(signal: Fr): MembershipWitness = make(signal)
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
) {
    data class Quote(val gasLimit: Long, val fee: Long)

    /** Lays out, prices and simulates without proving: what the confirm sheet shows. */
    fun quote(assemble: (fee: Long) -> Assembled): Quote = price(assemble).first

    fun run(assemble: (fee: Long) -> Assembled): Pair<TxResult, Assembled> {
        val (q, a) = price(assemble)
        val sighash = PrivateMsgs.sighash(draft(a), chainId)
        val bundles = a.bundles.map { it.prove(sighash, prover::proveAction) }
        bundles.forEachIndexed { i, b -> check(PrivateMsgs.checkBalance(b, sighash)) { "bundle $i does not balance" } }
        val stake = a.stake?.let { s -> s.proto(prover.proveStake(s.witness(sighash).also { it.check() })) }
        val membership = a.membership?.let { spec ->
            val w = spec.witness(sighash)
            Membership.newBuilder()
                .setProof(ByteString.copyFrom(prover.proveMembership(w)))
                .setRoot(ByteString.copyFrom(w.root.toBytes()))
                .setNullifier(ByteString.copyFrom(w.nullifier.toBytes()))
                .build()
        }
        val msg = a.build(bundles, stake, membership)
        check(PrivateMsgs.sighash(msg, chainId) == sighash)
        check(PrivateMsgs.totalFee(msg) == q.fee) { "the msg must pay exactly the quoted fee" }
        return chain.broadcast(UnsignedTx.build(msg, q.gasLimit)) to a
    }

    private fun price(assemble: (fee: Long) -> Assembled): Pair<Quote, Assembled> {
        val minFee = chain.minFee()
        val price = chain.gasPrice()
        var fee = maxOf(minFee, feeFor(price, GUESS_GAS))
        var a = assemble(fee)
        var first = true
        repeat(MAX_RELAYS) {
            val gas = chain.simulate(UnsignedTx.build(draft(a), 0))
            val limit = gas + maxOf(gas / 10, MIN_HEADROOM)
            val need = maxOf(minFee, feeFor(price, limit))
            // The guess is re-laid at the fee its layout needs; after that a
            // layout whose gas the fee covers is final (a fee needing one more
            // note, or one fewer leaving change, changes the action count, and
            // so the gas, so the fee may only rise from here).
            if (need == fee || (need < fee && !first)) return Quote(limit, fee) to a
            first = false
            fee = need
            a = assemble(fee)
        }
        throw IllegalStateException("the fee did not settle")
    }

    private fun draft(a: Assembled): MessageLite =
        a.build(a.bundles.map { it.proto() }, a.stake?.proto(PLACEHOLDER), a.membership?.let { placeholderMembership(it) })

    /** The membership's real root and nullifier (the chain checks both before any proof), a placeholder proof. */
    private fun placeholderMembership(spec: MembershipWitnessSpec): Membership {
        val w = spec.witness(Fr.ZERO)
        return Membership.newBuilder()
            .setProof(ByteString.copyFrom(PLACEHOLDER))
            .setRoot(ByteString.copyFrom(w.root.toBytes()))
            .setNullifier(ByteString.copyFrom(w.nullifier.toBytes()))
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

        fun feeFor(price: BigDecimal, gas: Long): Long =
            price.multiply(BigDecimal(gas)).setScale(0, RoundingMode.CEILING).toLong()
    }
}
