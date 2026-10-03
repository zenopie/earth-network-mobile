package network.erth.wallet.privacy.tx

import com.google.protobuf.ByteString
import network.erth.earth.proto.shielded.Action
import network.erth.earth.proto.shielded.Bundle
import network.erth.earth.proto.shielded.ValueBalance
import network.erth.wallet.privacy.keys.PrivacyKeys
import network.erth.wallet.privacy.keys.ShieldedAddress
import network.erth.wallet.privacy.note.NoteCipher
import network.erth.wallet.privacy.note.NotePlaintext
import network.erth.wallet.privacy.note.OwnedNote
import network.erth.wallet.privacy.prove.ActionWitness
import network.erth.wallet.privacy.zk.Fr
import network.erth.wallet.privacy.zk.Grumpkin
import network.erth.wallet.privacy.zk.Merkle
import network.erth.wallet.privacy.zk.MerkleTree
import network.erth.wallet.privacy.zk.Privacy
import java.math.BigInteger
import java.security.SecureRandom

/** One output of a bundle, or a note a msg has the chain mint: its denom, pc, ciphertext and (when ours) opening. */
class NoteOut private constructor(val denom: String, val value: Long, val pc: Fr, val ciphertext: ByteArray, val note: NotePlaintext?) {
    companion object {
        /** A note of [value] [denom] to [to]. */
        fun to(to: ShieldedAddress, denom: String, value: Long, memo: ByteArray = ByteArray(0)): NoteOut {
            val n = NotePlaintext.fresh(denom, value, memo)
            return NoteOut(denom, value, n.pc(to.ownerPk), NoteCipher.encrypt(n, to), n)
        }

        fun toSelf(keys: PrivacyKeys, denom: String, value: Long): NoteOut = to(keys.address, denom, value)

        /**
         * A note the chain will mint to us (a reward, a claim, a shield, an
         * unbonding payout, swap output, LP shares, refunds or withdrawal
         * legs): fresh rho and rcm and a value-blind (v2) ciphertext of them
         * to our own address, which sync opens against the amount the chain
         * publishes with the note. No counter: every such note is found by
         * trial decryption alone.
         */
        fun mintToSelf(keys: PrivacyKeys, denom: String, memo: ByteArray = ByteArray(0)): NoteOut {
            val n = NotePlaintext.fresh(denom, 0, memo)
            return NoteOut(denom, 0, n.pc(keys.ownerPk), NoteCipher.encryptBlind(n, keys.address), n)
        }

        /**
         * A note the chain will mint to [to] at a value and asset it decides
         * (a swap's output): [to]'s pc with a value-blind (v2) ciphertext,
         * which [to] opens against the amount the chain publishes.
         */
        fun blindTo(to: ShieldedAddress, denom: String, memo: ByteArray = ByteArray(0)): NoteOut {
            val n = NotePlaintext.fresh(denom, 0, memo)
            return NoteOut(denom, 0, n.pc(to.ownerPk), NoteCipher.encryptBlind(n, to), null)
        }

        /** A value-0 output nobody can open: the same shape as any other. */
        fun dummy(): NoteOut = NoteOut(DUMMY_DENOM, 0, NotePlaintext.randomField(), NoteCipher.dummy(), null)

        /** Dummies carry the fee asset; at value 0 the asset adds nothing to cv. */
        const val DUMMY_DENOM = "uerth"
    }
}

/** One action's spend: an owned note with its Merkle path, or a value-0 dummy. */
class ActionSpend private constructor(
    val denom: String,
    val value: Long,
    val rho: Fr,
    val rcm: Fr,
    val position: Long,
    val path: List<Fr>,
    val note: OwnedNote?,
) {
    companion object {
        fun of(n: OwnedNote, path: List<Fr>): ActionSpend =
            ActionSpend(n.note.denom, n.note.value, n.note.rho, n.note.rcm, n.position, path, n)

        /**
         * A dummy: value 0, so the circuit skips its membership, and a fresh
         * rho, so its nullifier (which is still published and spent) is new.
         */
        fun dummy(): ActionSpend = ActionSpend(
            NoteOut.DUMMY_DENOM, 0, NotePlaintext.randomField(), NotePlaintext.randomField(), 0, List(Merkle.DEPTH) { Fr.ZERO }, null,
        )
    }
}

/** A spend paired with an output, and the action's value-commitment randomness. */
class PlannedAction(val spend: ActionSpend, val out: NoteOut, val rcv: Fr)

/**
 * One bundle laid out: its actions (real or dummy spends and outputs, any
 * asset on either side), all proven against [anchor], and its public value
 * balance per denom, which is computed here from the actions themselves
 * (spends less outputs; zk/orchard refuses a negative one) so the plan can
 * never disagree with its own binding signature. Everything the sighash binds
 * is final once built; proofs and the binding signature are made over it.
 */
class BundlePlan(val actions: List<PlannedAction>, val anchor: Fr, private val nk: Fr) {
    init {
        require(actions.size in MIN_ACTIONS..MAX_ACTIONS) { "a bundle carries $MIN_ACTIONS..$MAX_ACTIONS actions" }
    }

    /** Public balances, positive only, in denom order (the chain's own order for a plan). */
    val balances: List<Pair<String, Long>> = run {
        val net = sortedMapOf<String, BigInteger>()
        for (a in actions) {
            net.merge(a.spend.denom, BigInteger.valueOf(a.spend.value), BigInteger::add)
            net.merge(a.out.denom, BigInteger.valueOf(a.out.value).negate(), BigInteger::add)
        }
        net.forEach { (d, v) -> require(v.signum() >= 0) { "bundle creates ${v.negate()}$d" } }
        net.filterValues { it.signum() > 0 }.map { (d, v) -> d to v.longValueExact() }
    }

    fun balance(denom: String): Long = balances.firstOrNull { it.first == denom }?.second ?: 0

    /** The owned notes this bundle spends. */
    val spends: List<OwnedNote> get() = actions.mapNotNull { it.spend.note }

    private val assets = HashMap<String, Fr>()
    private fun asset(d: String): Fr = assets.getOrPut(d) { NotePlaintext.assetOf(d) }

    val nullifiers: List<Fr> by lazy { actions.map { Privacy.nf(nk, it.spend.rho, it.spend.position) } }
    val commitments: List<Fr> by lazy { actions.map { Privacy.cm(asset(it.out.denom), it.out.value, it.out.pc) } }
    val cvs: List<Grumpkin.Point> by lazy {
        actions.map { Grumpkin.valueCommit(asset(it.spend.denom), it.spend.value, asset(it.out.denom), it.out.value, it.rcv) }
    }

    fun witness(i: Int, sighash: Fr): ActionWitness {
        val a = actions[i]
        return ActionWitness(
            nk = nk, sAsset = asset(a.spend.denom), sValue = a.spend.value, sRho = a.spend.rho, sRcm = a.spend.rcm,
            sPos = a.spend.position, sPath = a.spend.path, oAsset = asset(a.out.denom), oValue = a.out.value,
            oPc = a.out.pc, rcv = a.rcv, anchor = anchor, sighash = sighash,
        )
    }

    /** bsk = sum of rcv mod n. */
    fun bindingKey(): BigInteger = Grumpkin.bindingKey(actions.map { it.rcv })

    /** The bundle with [proofs] (placeholders before proving) and [bindingSig] (zeros before signing). */
    fun proto(proofs: List<ByteArray>? = null, bindingSig: ByteArray? = null): Bundle {
        val anchorBytes = ByteString.copyFrom(anchor.toBytes())
        val b = Bundle.newBuilder()
        actions.forEachIndexed { i, a ->
            b.addActions(
                Action.newBuilder()
                    .setAnchor(anchorBytes)
                    .setNullifier(ByteString.copyFrom(nullifiers[i].toBytes()))
                    .setCommitment(ByteString.copyFrom(commitments[i].toBytes()))
                    .setCv(ByteString.copyFrom(cvs[i].toBytes()))
                    .setCiphertext(ByteString.copyFrom(a.out.ciphertext))
                    .setProof(ByteString.copyFrom(proofs?.get(i) ?: PrivateTxEngine.PLACEHOLDER)),
            )
        }
        balances.forEach { (d, v) -> b.addBalances(ValueBalance.newBuilder().setDenom(d).setAmount(v)) }
        return b.setBindingSig(ByteString.copyFrom(bindingSig ?: ByteArray(Grumpkin.BINDING_SIG_BYTES))).build()
    }

    /** Proves every action under [sighash] and signs the balance. */
    fun prove(sighash: Fr, prove: (ActionWitness) -> ByteArray): Bundle {
        val proofs = actions.indices.map { prove(witness(it, sighash)) }
        val rnd = ByteArray(32).also { rng.nextBytes(it) }
        return proto(proofs, Grumpkin.signBinding(bindingKey(), sighash, rnd))
    }

    companion object {
        /** x/shielded MinActionsPerBundle: every bundle is padded to at least two. */
        const val MIN_ACTIONS = 2
        /** zk/orchard.MaxActions; the chain's max_actions_per_bundle param may only be lower. */
        const val MAX_ACTIONS = 32

        private val rng = SecureRandom()

        /**
         * Pairs [spends] with [outputs] into max(#spends, #outputs, [minActions])
         * actions, dummies filling either side, each list shuffled first so the
         * action order says nothing about which output is change.
         */
        fun pair(keys: PrivacyKeys, anchor: Fr, spends: List<ActionSpend>, outputs: List<NoteOut>, minActions: Int = MIN_ACTIONS, maxActions: Int = MAX_ACTIONS): BundlePlan {
            val n = maxOf(spends.size, outputs.size, minActions)
            if (n > maxActions) {
                throw NoteSelection.Insufficient("this needs $n actions, more than the $maxActions one transaction may carry; merge notes first")
            }
            val ss = spends.shuffled(rng)
            val os = outputs.shuffled(rng)
            val actions = (0 until n).map { i ->
                PlannedAction(ss.getOrElse(i) { ActionSpend.dummy() }, os.getOrElse(i) { NoteOut.dummy() }, NotePlaintext.randomField())
            }
            return BundlePlan(actions, anchor, keys.nk)
        }
    }
}

/**
 * Lays bundles out: picks notes for what must leave each denom (the payments
 * [outputs] plus the public [release]: a fee, an unshield, what a module
 * takes), returns each denom's change to us, and pairs it all into actions.
 * Any number of notes, any number of assets, up to max_actions_per_bundle.
 */
object BundleBuilder {
    fun plan(
        keys: PrivacyKeys,
        tree: MerkleTree,
        notes: List<OwnedNote>,
        outputs: List<NoteOut>,
        release: Map<String, Long>,
        maxActions: Int,
        exclude: Set<Long> = emptySet(),
        forced: List<OwnedNote> = emptyList(),
    ): BundlePlan {
        val need = sortedMapOf<String, Long>()
        outputs.forEach { if (it.value > 0) need.merge(it.denom, it.value, Math::addExact) }
        release.forEach { (d, v) -> require(v >= 0); if (v > 0) need.merge(d, v, Math::addExact) }
        forced.forEach { need.putIfAbsent(it.note.denom, 0L) }
        val spends = ArrayList<OwnedNote>(forced)
        val outs = outputs.toMutableList()
        for ((denom, amount) in need) {
            val have = forced.filter { it.note.denom == denom }.sumOf { it.note.value }
            val chosen = if (have >= amount) emptyList() else NoteSelection.cover(
                notes, denom, amount - have, exclude + spends.map { it.position }, maxActions - spends.size,
            )
            spends.addAll(chosen)
            val change = have + chosen.sumOf { it.note.value } - amount
            if (change > 0) outs.add(NoteOut.toSelf(keys, denom, change))
        }
        return fromNotes(keys, tree, spends, outs, maxActions)
    }

    /** A bundle spending exactly [spends] into [outputs] (no selection, no change). */
    fun fromNotes(keys: PrivacyKeys, tree: MerkleTree, spends: List<OwnedNote>, outputs: List<NoteOut>, maxActions: Int, anchor: Fr = tree.root()): BundlePlan =
        BundlePlan.pair(keys, anchor, spends.map { ActionSpend.of(it, tree.path(it.position)) }, outputs, maxActions = maxActions)
}

/** Picks notes to spend. */
object NoteSelection {
    class Insufficient(message: String) : Exception(message)

    fun spendable(notes: List<OwnedNote>, denom: String, exclude: Set<Long> = emptySet()): List<OwnedNote> =
        notes.filter { it.unspent && it.pendingAt == null && it.note.denom == denom && it.position !in exclude && it.note.value > 0 }

    /**
     * The most of [denom] one transaction can spend: its [maxNotes] largest
     * spendable notes (max_actions_per_bundle; the whole balance unless it is
     * spread over more notes than that).
     */
    fun maxSpendable(notes: List<OwnedNote>, denom: String, maxNotes: Int): Long =
        spendable(notes, denom).map { it.note.value }.sortedDescending().take(maxNotes).sum()

    /**
     * Notes of [denom] covering [amount]: the smallest single note that
     * does; else the fewest notes (largest first), the last swapped for the
     * smallest note that still covers. At most [maxNotes].
     */
    fun cover(notes: List<OwnedNote>, denom: String, amount: Long, exclude: Set<Long> = emptySet(), maxNotes: Int = Int.MAX_VALUE): List<OwnedNote> {
        if (amount <= 0) return emptyList()
        val c = spendable(notes, denom, exclude).sortedBy { it.note.value }
        c.firstOrNull { it.note.value >= amount }?.let { return listOf(it) }
        val chosen = ArrayList<OwnedNote>()
        var sum = 0L
        for (n in c.asReversed()) {
            chosen.add(n)
            sum += n.note.value
            if (sum >= amount) break
        }
        if (sum < amount) throw Insufficient("insufficient shielded $denom")
        if (chosen.size > maxNotes) throw Insufficient("$denom is spread over too many notes for one transaction; merge them first")
        val rest = sum - chosen.last().note.value
        val taken = chosen.map { it.position }.toSet()
        c.firstOrNull { it.position !in taken && rest + it.note.value >= amount }?.let { if (it.note.value < chosen.last().note.value) chosen[chosen.size - 1] = it }
        return chosen
    }
}

/** Picks stake notes: a stake proof spends at most two. */
object StakeSelection {
    /**
     * Notes covering [amount]: the smallest single one that does, else the
     * pair with the smallest sufficient sum. A balance spread over more than
     * two notes is merged first (PrivacyWallet.mergeStake).
     */
    fun cover(notes: List<network.erth.wallet.privacy.note.OwnedStakeNote>, amount: Long): List<network.erth.wallet.privacy.note.OwnedStakeNote> {
        require(amount > 0)
        val c = notes.filter { it.spendable }.sortedBy { it.amount }
        c.firstOrNull { it.amount >= amount }?.let { return listOf(it) }
        var best: List<network.erth.wallet.privacy.note.OwnedStakeNote>? = null
        var bestSum = Long.MAX_VALUE
        for (i in c.indices) for (j in i + 1 until c.size) {
            val s = c[i].amount + c[j].amount
            if (s >= amount && s < bestSum) { best = listOf(c[i], c[j]); bestSum = s }
        }
        return best ?: throw NoteSelection.Insufficient(
            if (c.sumOf { it.amount } >= amount) "this stake is spread over more than two notes; merge them first"
            else "insufficient stake",
        )
    }
}
