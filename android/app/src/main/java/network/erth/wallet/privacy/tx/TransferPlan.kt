package network.erth.wallet.privacy.tx

import com.google.protobuf.ByteString
import network.erth.earth.proto.shielded.Transfer
import network.erth.wallet.privacy.keys.PrivacyKeys
import network.erth.wallet.privacy.keys.ShieldedAddress
import network.erth.wallet.privacy.note.NoteCipher
import network.erth.wallet.privacy.note.NotePlaintext
import network.erth.wallet.privacy.note.OwnedNote
import network.erth.wallet.privacy.prove.TransferInput
import network.erth.wallet.privacy.prove.TransferOutput
import network.erth.wallet.privacy.prove.TransferWitness
import network.erth.wallet.privacy.zk.Fr
import network.erth.wallet.privacy.zk.MerkleTree
import network.erth.wallet.privacy.zk.Privacy

/** One output of a transfer, or of a msg that mints a note: its pc, ciphertext and (when ours) opening. */
class NoteOut private constructor(val value: Long, val pc: Fr, val ciphertext: ByteArray, val note: NotePlaintext?) {
    companion object {
        /** A note of [value] [denom] to [to]. */
        fun to(to: ShieldedAddress, denom: String, value: Long, memo: ByteArray = ByteArray(0)): NoteOut {
            val n = NotePlaintext.fresh(denom, value, memo)
            return NoteOut(value, n.pc(to.ownerPk), NoteCipher.encrypt(n, to), n)
        }

        fun toSelf(keys: PrivacyKeys, denom: String, value: Long): NoteOut = to(keys.address, denom, value)

        /**
         * A note the chain will mint to us at a value we cannot know yet (a
         * reward, derth at the live rate, an unbonding payout): its pc is
         * self-mint [counter]'s (PrivacyKeys.mintSecrets) and it carries no
         * ciphertext; sync finds it by its public mint amount.
         */
        fun mintToSelf(keys: PrivacyKeys, denom: String, counter: Int): NoteOut {
            val (rho, rcm) = keys.mintSecrets(counter)
            val n = NotePlaintext(denom, 0, rho, rcm)
            return NoteOut(0, n.pc(keys.ownerPk), ByteArray(0), n)
        }

        /**
         * A note the chain will mint to [to] at a value and asset it decides
         * (a swap's output, an LP withdrawal): [to]'s pc with a value-blind
         * (v2) ciphertext, which [to] opens against the amount the chain
         * publishes. For ourselves, [mintToSelf] needs no ciphertext.
         */
        fun blindTo(to: ShieldedAddress, denom: String, memo: ByteArray = ByteArray(0)): NoteOut {
            val n = NotePlaintext.fresh(denom, 0, memo)
            return NoteOut(0, n.pc(to.ownerPk), NoteCipher.encryptBlind(n, to), null)
        }

        /** A value-0 output nobody can open: same shape as any other. */
        fun dummy(): NoteOut = NoteOut(0, NotePlaintext.randomField(), NoteCipher.dummy(), null)
    }
}

/**
 * Everything a transfer proof needs except the signal: which notes fill the
 * three input slots (dummies where empty), the three outputs, the anchor and
 * the public values. Slots 0-1 carry asset [denom]; slot 2 is ERTH and pays
 * [fee]. Built once per fee: its nullifiers, commitments and ciphertexts are
 * final, so the msg's signal can be computed before anything is proven.
 */
class TransferPlan(
    val denom: String,
    val inputs: List<TransferInput>,
    val spends: List<OwnedNote>,
    val outputs: List<NoteOut>,
    val root: Fr,
    val fee: Long,
    val vPubOut: Long,
    private val nk: Fr,
) {
    init {
        require(inputs.size == 3 && outputs.size == 3)
    }

    val denomOut: String get() = if (vPubOut > 0) denom else ""

    fun witness(signal: Fr): TransferWitness = TransferWitness(
        asset = Privacy.assetId(denom), nk = nk, inputs = inputs,
        outputs = outputs.map { TransferOutput(it.value, it.pc) },
        root = root, fee = fee, vPubOut = vPubOut, signal = signal,
    )

    /** The msg's Transfer, with [proof] (a placeholder before proving). */
    fun proto(proof: ByteArray): Transfer {
        val w = witness(Fr.ZERO) // nullifiers and commitments do not depend on the signal
        return Transfer.newBuilder()
            .setProof(ByteString.copyFrom(proof))
            .setRoot(ByteString.copyFrom(root.toBytes()))
            .addAllNullifiers(w.nullifiers.map { ByteString.copyFrom(it.toBytes()) })
            .addAllCommitments(w.commitments.map { ByteString.copyFrom(it.toBytes()) })
            .addAllCiphertexts(outputs.map { ByteString.copyFrom(it.ciphertext) })
            .setFee(fee)
            .setValueOut(vPubOut)
            .setDenomOut(denomOut)
            .build()
    }

    companion object {
        fun input(n: OwnedNote, tree: MerkleTree, pathOverride: List<Fr>? = null): TransferInput =
            TransferInput(n.note.value, n.note.rho, n.note.rcm, n.position, pathOverride ?: tree.path(n.position))

        /**
         * Lays a transfer out. [aInputs] (0-2 notes of [denom]) fund [aOutputs]
         * (0-2) plus [vPubOut]; any surplus returns to [keys] as change in the
         * first free A output. [feeNote] (ERTH) pays [fee] with its change to
         * [keys] in slot 2; without one, [fee] must be 0.
         */
        fun build(
            keys: PrivacyKeys,
            tree: MerkleTree,
            denom: String,
            aInputs: List<OwnedNote>,
            aOutputs: List<NoteOut>,
            vPubOut: Long,
            feeNote: OwnedNote?,
            fee: Long,
            root: Fr = tree.root(),
            paths: Map<Long, List<Fr>> = emptyMap(),
        ): TransferPlan {
            require(aInputs.size <= 2 && aOutputs.size <= 2)
            require(aInputs.all { it.note.denom == denom }) { "inputs must all be $denom" }
            require(feeNote == null || feeNote.note.denom == "uerth") { "the fee note must be ERTH" }
            require(feeNote == null || aInputs.none { it.position == feeNote.position }) { "a note fills one slot" }
            val inSum = aInputs.sumOf { it.note.value }
            val outSum = aOutputs.sumOf { it.value } + vPubOut
            require(inSum >= outSum) { "insufficient $denom: have $inSum, need $outSum" }
            val outs = aOutputs.toMutableList()
            if (inSum > outSum) {
                require(outs.size < 2) { "no output slot left for change" }
                outs.add(NoteOut.toSelf(keys, denom, inSum - outSum))
            }
            while (outs.size < 2) outs.add(NoteOut.dummy())
            val ins = aInputs.map { input(it, tree, paths[it.position]) }.toMutableList()
            while (ins.size < 2) ins.add(TransferInput.dummy())

            val slot2In: TransferInput
            val slot2Out: NoteOut
            if (feeNote == null) {
                require(fee == 0L) { "a fee needs a fee note" }
                slot2In = TransferInput.dummy()
                slot2Out = NoteOut.dummy()
            } else {
                require(feeNote.note.value >= fee) { "fee note too small" }
                slot2In = input(feeNote, tree, paths[feeNote.position])
                val change = feeNote.note.value - fee
                slot2Out = if (change > 0) NoteOut.toSelf(keys, "uerth", change) else NoteOut.dummy()
            }
            return TransferPlan(
                denom = denom,
                inputs = ins + slot2In,
                spends = aInputs + listOfNotNull(feeNote),
                outputs = outs + slot2Out,
                root = root, fee = fee, vPubOut = vPubOut, nk = keys.nk,
            )
        }

        /** A transfer that only pays [fee] from [feeNote]: the fee proof of a private action. */
        fun feeOnly(keys: PrivacyKeys, tree: MerkleTree, feeNote: OwnedNote, fee: Long): TransferPlan =
            build(keys, tree, "uerth", emptyList(), emptyList(), 0, feeNote, fee)
    }
}

/** Picks notes to spend. A transfer has two slots for its asset and one ERTH slot for the fee. */
object NoteSelection {
    class Insufficient(message: String) : Exception(message)

    fun spendable(notes: List<OwnedNote>, denom: String, exclude: Set<Long> = emptySet()): List<OwnedNote> =
        notes.filter { it.unspent && it.pendingAt == null && it.note.denom == denom && it.position !in exclude && it.note.value > 0 }

    /** The smallest ERTH note covering [fee], so larger notes stay whole for payments. */
    fun feeNote(notes: List<OwnedNote>, fee: Long, exclude: Set<Long> = emptySet()): OwnedNote =
        spendable(notes, "uerth", exclude).filter { it.note.value >= fee }.minByOrNull { it.note.value }
            ?: throw Insufficient("no shielded ERTH note covers the ${fee}uerth fee")

    /**
     * One or two notes of [denom] covering [amount]: the smallest single note
     * that does, else the pair with the smallest sufficient sum.
     */
    fun inputs(notes: List<OwnedNote>, denom: String, amount: Long, exclude: Set<Long> = emptySet()): List<OwnedNote> {
        val c = spendable(notes, denom, exclude).sortedBy { it.note.value }
        c.firstOrNull { it.note.value >= amount }?.let { return listOf(it) }
        var best: List<OwnedNote>? = null
        for (i in c.indices) for (j in i + 1 until c.size) {
            val s = c[i].note.value + c[j].note.value
            if (s >= amount && (best == null || s < best.sumOf { it.note.value })) best = listOf(c[i], c[j])
        }
        return best ?: throw Insufficient(
            if (c.sumOf { it.note.value } >= amount) "$denom is spread over too many notes; merge them first"
            else "insufficient shielded $denom",
        )
    }
}
