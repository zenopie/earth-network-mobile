package network.erth.wallet.privacy.tx

import com.google.protobuf.ByteString
import network.erth.earth.proto.shieldedstaking.StakeProof
import network.erth.wallet.privacy.keys.PrivacyKeys
import network.erth.wallet.privacy.note.NoteCipher
import network.erth.wallet.privacy.note.NotePlaintext
import network.erth.wallet.privacy.note.OwnedStakeNote
import network.erth.wallet.privacy.note.StakeLabel
import network.erth.wallet.privacy.prove.StakeIn
import network.erth.wallet.privacy.prove.StakeWitness
import network.erth.wallet.privacy.zk.DebtTree
import network.erth.wallet.privacy.zk.Fr
import network.erth.wallet.privacy.zk.Merkle
import network.erth.wallet.privacy.zk.Privacy

/**
 * One stake proof laid out (circuits/stake v2, ORCHARD_DESIGN 4.1): lane A
 * spends up to two of this wallet's notes of [denom] (or pads its first slot)
 * and creates one note back to it (the merged note, the change, or a zero
 * note), crediting [vIn] and releasing [vOut]; the credit lane ([credit])
 * merges a redelegation's credit into the wallet's note at the destination.
 * Everything the sighash binds (the StakeFields) is final once built.
 *
 * [denom] null: a position's update or vote, whose lane A is all zero (its
 * public asset 0). Every proof names the chain's current clear_before and
 * debt root ([clear]) whether or not it clears a label:
 * a proof naming them only to clear would be linkable to the redelegation
 * that labelled the note.
 */
class StakePlan(
    private val nk: Fr,
    val denom: String?,
    val spends: List<OwnedStakeNote>,
    private val paths: List<List<Fr>>,
    /** Lane A's output; null for a msg that spends and creates nothing (a position's update or vote). */
    val out: Out?,
    val vIn: Long,
    val vOut: Long,
    val clear: Clear,
    val credit: Credit?,
    val tagSalt: Fr,
    val anchor: Fr,
) {
    /** A created stake note: its amount, secrets, label and ciphertext (to ourselves). */
    class Out(val amount: Long, val rho: Fr, val rcm: Fr, val label: StakeLabel?, val ciphertext: ByteArray)

    /**
     * What the proof names of the slash debt: [clearBefore] and [debtRoot]
     * (0 and zero only while the block time is below the label window, which no real chain sees), and
     * whether lane A's labelled input clears ([witness] and [retained], read
     * from the debt tree at debtRoot) or keeps its label.
     */
    data class Clear(val clearBefore: Long, val debtRoot: Fr, val witness: DebtTree.Witness? = null, val retained: Long = 0) {
        val clears: Boolean get() = witness != null

        companion object {
            val NONE = Clear(0, Fr.ZERO)
        }
    }

    /**
     * The credit lane (a redelegation): the wallet's unlabelled note of
     * [denom] merged with [vIn] ([spend], or a padding input when there is
     * none), labelled with the move made at [moveTime].
     */
    class Credit(
        val denom: String,
        val spend: OwnedStakeNote?,
        val path: List<Fr>?,
        val vIn: Long,
        val moveTime: Long,
        val padRho: Fr,
        val padRcm: Fr,
        val out: Out,
    ) {
        /** The credit nullifier: the move's key. */
        val nullifier: Fr get() = out.label!!.moveKey
    }

    init {
        require(spends.size <= 2 && paths.size == spends.size)
        require(spends.all { it.denom == denom && it.amount > 0 }) { "a stake proof spends notes of its own denom" }
        require(spends.count { it.label != null } <= 1) { "a stake proof spends at most one labelled note" }
        require(denom != null || (spends.isEmpty() && out == null && vIn == 0L && vOut == 0L && credit == null)) { "a position's proof moves nothing" }
        require(denom == null || out != null) { "a note-moving proof creates a note" }
        require(!clear.clears || spends.any { it.label != null }) { "nothing to clear" }
        require(clear.clearBefore != 0L || (clear.debtRoot.isZero && !clear.clears)) { "debt_root is zero exactly when clear_before is" }
        val l = spends.firstNotNullOfOrNull { it.label }
        if (out != null) {
            require(out.label == (if (clear.clears) null else l)) { "the output keeps the label unless it clears" }
            val ex = l?.exposed ?: 0L
            fun big(v: Long) = java.math.BigInteger.valueOf(v)
            val ins = spends.fold(java.math.BigInteger.ZERO) { a, n -> a + big(n.amount) } - big(ex) + big(if (clear.clears) clear.retained else 0L) + big(vIn)
            val outs = big(out.amount - (out.label?.exposed ?: 0L)) + big(vOut)
            require(ins == outs) { "stake amounts do not balance: in $ins, out $outs" }
        }
        credit?.let { c ->
            require(c.spend == null || (c.spend.label == null && c.spend.denom == c.denom)) { "the credit lane merges only into an unlabelled note" }
            require(c.out.amount == Math.addExact(c.spend?.amount ?: 0L, c.vIn))
        }
    }

    val asset: Fr = denom?.let(Privacy::assetId) ?: Fr.ZERO

    private val noneIn = List(2) { NotePlaintext.randomField() to NotePlaintext.randomField() }
    private val noneOut = NotePlaintext.randomField() to NotePlaintext.randomField()

    private fun zeros() = List(Merkle.DEPTH) { Fr.ZERO }

    fun witness(sighash: Fr): StakeWitness {
        val ins = (0..1).map { i ->
            spends.getOrNull(i)?.let { n -> StakeIn(n.amount, n.rho, n.rcm, n.position, paths[i], n.label) }
                // A note-moving msg pads its first slot when it spends nothing of ours.
                ?: if (i == 0 && out != null && spends.isEmpty()) StakeIn.padding(noneIn[0].first, noneIn[0].second)
                else StakeIn.none(noneIn[i].first, noneIn[i].second)
        }
        val c = credit
        val crIn = when {
            c == null -> StakeIn.none(noneIn[1].first, noneIn[1].second)
            c.spend != null -> StakeIn(c.spend.amount, c.spend.rho, c.spend.rcm, c.spend.position, c.path!!)
            else -> StakeIn.padding(c.padRho, c.padRcm)
        }
        return StakeWitness(
            nk = nk, ins = ins,
            outAmount = out?.amount ?: 0L, outRho = out?.rho ?: noneOut.first, outRcm = out?.rcm ?: noneOut.second,
            padOut = out != null,
            clear = clear.clears, debt = clear.witness ?: DebtTree.Witness.NONE,
            crIn = crIn, crOutRho = c?.out?.rho ?: noneOut.first, crOutRcm = c?.out?.rcm ?: noneOut.second,
            tagSalt = tagSalt, anchor = anchor, asset = asset, vIn = vIn, vOut = vOut,
            clearBefore = clear.clearBefore, debtRoot = clear.debtRoot,
            crAsset = c?.let { Privacy.assetId(it.denom) } ?: Fr.ZERO, crVIn = c?.vIn ?: 0L, crMoveTime = c?.moveTime ?: 0L,
            sighash = sighash,
        )
    }

    /** The msg's StakeProof, with [proof] (a placeholder before proving). */
    fun proto(proof: ByteArray): StakeProof {
        val w = witness(Fr.ZERO) // every public value but the sighash
        fun b(f: Fr) = ByteString.copyFrom(f.toBytes())
        check(w.commitment.isZero == (out == null)) { "lane A creates exactly when it moves notes" }
        // The credit's label (in its ciphertext) names the nullifier the proof publishes.
        check(credit == null || credit.nullifier == w.crNf) { "the credit's move key is not its nullifier" }
        return StakeProof.newBuilder()
            .setProof(ByteString.copyFrom(proof))
            .setAnchor(b(anchor))
            .addAllNullifiers(w.nullifiers.map(::b))
            .setOwnerTag(b(w.otag))
            .setCommitment(b(w.commitment))
            .setCiphertext(ByteString.copyFrom(out?.ciphertext ?: ByteArray(0)))
            .setCreditNullifier(b(w.crNf))
            .setCreditCommitment(b(w.crCm))
            .setCreditCiphertext(ByteString.copyFrom(credit?.out?.ciphertext ?: ByteArray(0)))
            .setClearBefore(clear.clearBefore)
            .setDebtRoot(b(clear.debtRoot))
            .build()
    }

    companion object {
        /** A created stake note of [amount] [denom] back to [keys] with [label], and its stake ciphertext. */
        fun out(keys: PrivacyKeys, denom: String, amount: Long, label: StakeLabel? = null): Out {
            val rho = NotePlaintext.randomField()
            val rcm = NotePlaintext.randomField()
            val o = NoteCipher.StakeOpening(Privacy.assetId(denom), amount, rho, rcm, label)
            val cm = o.cm(keys.ownerPk)
            return Out(amount, rho, rcm, label, NoteCipher.encryptStake(o, keys.ekPub, cm))
        }

        /**
         * The credit lane of a redelegation into [denom]: [spend] (our
         * unlabelled note there) or a fresh padding input, merged with [vIn]
         * and labelled (move key = the lane's own nullifier, [moveTime],
         * exposed = vIn).
         */
        fun credit(keys: PrivacyKeys, denom: String, spend: OwnedStakeNote?, path: List<Fr>?, vIn: Long, moveTime: Long): Credit {
            require(vIn > 0 && moveTime > 0)
            require(spend == null || (spend.label == null && path != null))
            val padRho = NotePlaintext.randomField()
            val padRcm = NotePlaintext.randomField()
            val nf = if (spend != null) spend.nf else Privacy.stakeNf(keys.nk, padRho, 0)
            val label = StakeLabel(nf, moveTime, vIn)
            return Credit(denom, spend, path, vIn, moveTime, padRho, padRcm, out(keys, denom, Math.addExact(spend?.amount ?: 0L, vIn), label))
        }

        /** A fresh owner-tag salt: every proof that is not a position's links to nothing. */
        fun freshSalt(): Fr = NotePlaintext.randomField()
    }
}
