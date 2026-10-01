package network.erth.wallet.privacy.tx

import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite
import network.erth.earth.proto.personhood.Membership
import network.erth.earth.proto.shielded.Transfer
import network.erth.wallet.privacy.prove.MembershipWitness
import network.erth.wallet.privacy.prove.TransferWitness
import network.erth.wallet.privacy.zk.Fr
import java.math.BigDecimal
import java.math.RoundingMode

/** Proves the two privacy circuits. On a phone, PrivacyProver; in tests, a fake. */
interface Prover {
    fun proveTransfer(w: TransferWitness): ByteArray
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
}

/**
 * A private msg in the making: its transfers and membership (everything but
 * proofs and the signal), and how to assemble the msg once they exist.
 * [build] gets the transfers' protos (proofs placeholder or real, in
 * [transfers] order) and the membership proto, if any.
 */
class Assembled(
    val transfers: List<TransferPlan>,
    val membership: MembershipWitnessSpec?,
    val build: (transfers: List<Transfer>, membership: Membership?) -> MessageLite,
)

/** A membership proof's statement, waiting for the signal. */
class MembershipWitnessSpec(private val make: (signal: Fr) -> MembershipWitness) {
    fun witness(signal: Fr): MembershipWitness = make(signal)
}

/**
 * Runs a private tx end to end, the way the chain's ante requires:
 *
 *  1. lay the tx out at a guessed fee, with placeholder proofs over its real
 *     roots, nullifiers, commitments and ciphertexts, and simulate it: the
 *     private ante charges every proof's fixed gas in simulate mode and runs
 *     every state check, but verifies nothing;
 *  2. fee = max(min_fee, ceil(min gas price x gas limit)), the gas limit the
 *     simulated gas plus headroom; if it differs from the guess, lay the tx
 *     out again at that fee (the fee note's change, and so its ciphertext and
 *     the signal, change with it);
 *  3. compute the msg's signal, prove every transfer and the membership over
 *     it, and broadcast the unsigned tx.
 *
 * Gas is a function of the tx's size and shape alone (x/shielded/ante), so a
 * fee computed from the placeholder tx holds for the proven one.
 */
class PrivateTxEngine(
    private val chainId: String,
    private val chain: PrivateChain,
    private val prover: Prover,
) {
    /** Last run's numbers, for display. */
    data class Quote(val gasLimit: Long, val fee: Long)

    /** Lays out, prices and simulates without proving: what the confirm sheet shows. */
    fun quote(assemble: (fee: Long) -> Assembled): Quote = price(assemble).first

    fun run(assemble: (fee: Long) -> Assembled): Pair<TxResult, Assembled> {
        val (q, a) = price(assemble)
        val draft = a.build(a.transfers.map { it.proto(PLACEHOLDER) }, a.membership?.let { placeholderMembership(it) })
        val signal = PrivateMsgs.signal(draft, chainId)
        val proofs = a.transfers.map { prover.proveTransfer(it.witness(signal)) }
        val membership = a.membership?.let { spec ->
            val w = spec.witness(signal)
            Membership.newBuilder()
                .setProof(ByteString.copyFrom(prover.proveMembership(w)))
                .setRoot(ByteString.copyFrom(w.root.toBytes()))
                .setNullifier(ByteString.copyFrom(w.nullifier.toBytes()))
                .build()
        }
        val msg = a.build(a.transfers.mapIndexed { i, t -> t.proto(proofs[i]) }, membership)
        check(PrivateMsgs.signal(msg, chainId) == signal)
        check(PrivateMsgs.totalFee(msg) == q.fee) { "the msg must pay exactly the quoted fee" }
        return chain.broadcast(UnsignedTx.build(msg, q.gasLimit)) to a
    }

    private fun price(assemble: (fee: Long) -> Assembled): Pair<Quote, Assembled> {
        val minFee = chain.minFee()
        val price = chain.gasPrice()
        val guess = maxOf(minFee, feeFor(price, GUESS_GAS))
        var a = assemble(guess)
        val gas = chain.simulate(UnsignedTx.build(draft(a), 0))
        val limit = gas + maxOf(gas / 10, MIN_HEADROOM)
        val fee = maxOf(minFee, feeFor(price, limit))
        if (fee != guess) a = assemble(fee)
        return Quote(limit, fee) to a
    }

    private fun draft(a: Assembled): MessageLite =
        a.build(a.transfers.map { it.proto(PLACEHOLDER) }, a.membership?.let { placeholderMembership(it) })

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

        fun feeFor(price: BigDecimal, gas: Long): Long =
            price.multiply(BigDecimal(gas)).setScale(0, RoundingMode.CEILING).toLong()
    }
}
