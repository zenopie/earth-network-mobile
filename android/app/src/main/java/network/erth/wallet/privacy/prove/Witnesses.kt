package network.erth.wallet.privacy.prove

import network.erth.wallet.privacy.note.StakeLabel
import network.erth.wallet.privacy.zk.DebtTree
import network.erth.wallet.privacy.zk.Fr
import network.erth.wallet.privacy.zk.Grumpkin
import network.erth.wallet.privacy.zk.Merkle
import network.erth.wallet.privacy.zk.Privacy

/**
 * The action circuit's witness (circuits/action): one spend (a real note with
 * its path, or a value-0 dummy whose path is not checked) and one output,
 * either of any asset, and the value commitment
 *
 *     cv = s_value*G(s_asset) - o_value*G(o_asset) + rcv*R
 *
 * Public inputs, in the chain's order (zk/orchard Bundle.PublicInputs):
 * anchor, nf, cm_out, cv_x, cv_y, sighash.
 */
data class ActionWitness(
    val nk: Fr,
    val sAsset: Fr,
    val sValue: Long,
    val sRho: Fr,
    val sRcm: Fr,
    val sPos: Long,
    val sPath: List<Fr>,
    val oAsset: Fr,
    val oValue: Long,
    val oPc: Fr,
    val rcv: Fr,
    val anchor: Fr,
    val sighash: Fr,
) {
    init {
        require(sPos in 0..0xffffffffL) { "position is a u32" }
        require(sPath.size == Merkle.DEPTH)
    }

    val nf: Fr by lazy { Privacy.nf(nk, sRho, sPos) }
    val cmOut: Fr by lazy { Privacy.cm(oAsset, oValue, oPc) }
    val cv: Grumpkin.Point by lazy { Grumpkin.valueCommit(sAsset, sValue, oAsset, oValue, rcv) }

    /** What the circuit will assert of the spend, checked before spending a second on a proof that cannot verify. */
    fun check() {
        if (sValue != 0L) {
            val cm = Privacy.cm(sAsset, sValue, Privacy.pc(Privacy.ownerPk(nk), sRho, sRcm))
            require(Merkle.rootFromPath(cm, sPos, sPath) == anchor) { "spend not in the note tree at its anchor" }
        }
    }

    fun publicInputs(): List<Fr> = listOf(anchor, nf, cmOut, cv.x, cv.y, sighash)

    fun noirInputs(): Map<String, Any> = mapOf(
        "nk" to nk.toNoir(),
        "s_asset" to sAsset.toNoir(),
        "s_value" to hex(sValue),
        "s_rho" to sRho.toNoir(),
        "s_rcm" to sRcm.toNoir(),
        "s_pos" to hex(sPos),
        "s_path" to sPath.map { it.toNoir() },
        "o_asset" to oAsset.toNoir(),
        "o_value" to hex(oValue),
        "o_pc" to oPc.toNoir(),
        "rcv" to rcv.toNoir(),
        "anchor" to anchor.toNoir(),
        "nf" to nf.toNoir(),
        "cm_out" to cmOut.toNoir(),
        "cv_x" to cv.x.toNoir(),
        "cv_y" to cv.y.toNoir(),
        "sighash" to sighash.toNoir(),
    )

    fun proverToml(): String = toml(noirInputs())
}

/** One stake input slot: a note of this owner (amount > 0) under the anchor, or padding (amount 0). */
data class StakeIn(
    val amount: Long,
    val rho: Fr,
    val rcm: Fr,
    val pos: Long,
    val path: List<Fr>,
    /** Its label (lane A only; lane B inputs are unlabelled). */
    val label: StakeLabel? = null,
    /** For amount 0: publish its own would-be nullifier (padding) rather than 0 (no input). */
    val pad: Boolean = false,
) {
    init {
        require(path.size == Merkle.DEPTH)
        require(pos in 0..0xffffffffL) { "position is a u32" }
        require(amount >= 0) { "a stake amount is at most 2^63-1" }
        require(label == null || (amount > 0 && label.exposed <= amount)) { "a label sits in a note holding its exposure" }
    }

    companion object {
        /** No input: nullifier 0. */
        fun none(rho: Fr, rcm: Fr) = StakeIn(0, rho, rcm, 0, List(Merkle.DEPTH) { Fr.ZERO })

        /** Padding: an amount-0 input at position 0 publishing its own nullifier (a first delegation looks like a top-up). */
        fun padding(rho: Fr, rcm: Fr) = StakeIn(0, rho, rcm, 0, List(Merkle.DEPTH) { Fr.ZERO }, pad = true)
    }
}

/**
 * The stake circuit's witness (circuits/stake v2, ORCHARD_DESIGN 4.1): two
 * lanes of one owner (nk), every real input under [anchor].
 *
 * Lane A ([asset]): up to two inputs (at most one labelled), one output;
 * [vIn] credited, [vOut] leaving. A labelled input either keeps its label
 * (the output carries it and its exposure; only unexposed value moves) or,
 * with [clear], clears it once its move_time < [clearBefore] at what the
 * debt tree under [debtRoot] says it is worth ([debt]):
 *
 *     in_0 + in_1 - exposed + retained + v_in == out - out_exposed + v_out
 *
 * Lane B ([crAsset], the credit lane): one unlabelled input or padding, one
 * output = cr_in + [crVIn], labelled (move key = its own nullifier,
 * [crMoveTime], exposed = crVIn) when crMoveTime != 0. All zero when the msg
 * credits no second asset.
 *
 * An amount-0 output publishes 0 or, with [padOut], the zero note's
 * commitment (a full exit looks like a partial one). Public inputs, in the
 * chain's order (StakeProof.PublicInputs): anchor, asset, nf_0, nf_1,
 * cm_out, v_in, v_out, clear_before, debt_root, cr_asset, cr_nf, cr_cm,
 * cr_v_in, cr_move_time, otag, sighash.
 */
data class StakeWitness(
    val nk: Fr,
    val ins: List<StakeIn>,
    val outAmount: Long,
    val outRho: Fr,
    val outRcm: Fr,
    val padOut: Boolean,
    val clear: Boolean,
    val debt: DebtTree.Witness,
    val crIn: StakeIn,
    val crOutRho: Fr,
    val crOutRcm: Fr,
    val tagSalt: Fr,
    val anchor: Fr,
    val asset: Fr,
    val vIn: Long,
    val vOut: Long,
    val clearBefore: Long,
    val debtRoot: Fr,
    val crAsset: Fr,
    val crVIn: Long,
    val crMoveTime: Long,
    val sighash: Fr,
) {
    init {
        require(ins.size == 2)
        require(crIn.label == null) { "the credit lane merges only into an unlabelled note" }
        require(debt.lowPath.size == Merkle.DEPTH)
        require(debt.lowIndex in 0..0xffffffffL && debt.lowNextIndex in 0..0xffffffffL) { "a u32" }
        require(outAmount >= 0) { "a stake amount is at most 2^63-1" }
    }

    private val opk: Fr by lazy { Privacy.ownerPk(nk) }

    private fun nfOf(i: StakeIn): Fr = if (i.amount != 0L || i.pad) Privacy.stakeNf(nk, i.rho, i.pos) else Fr.ZERO

    val nullifiers: List<Fr> by lazy { ins.map(::nfOf) }

    /** The labelled input, if any (the circuit takes at most one). */
    private val labelled: StakeLabel? get() = ins.firstNotNullOfOrNull { it.label }

    /** The output's label: the input's, kept unless cleared. */
    val outLabel: StakeLabel? get() = labelled?.takeIf { !clear }

    val commitment: Fr by lazy {
        if (outAmount == 0L && !padOut) Fr.ZERO
        else Privacy.stakeCm(asset, outAmount, Privacy.stakePc(opk, outRho, outRcm), StakeLabel.hash(outLabel))
    }

    val crNf: Fr by lazy { nfOf(crIn) }

    /** Lane B's output amount: cr_in + cr_v_in. */
    val crOutAmount: Long get() = Math.addExact(crIn.amount, crVIn)

    /** The credit's label, when a redelegation labels it. */
    val crLabel: StakeLabel? get() = if (crMoveTime != 0L) StakeLabel(crNf, crMoveTime, crVIn) else null

    val crCm: Fr by lazy {
        if (crOutAmount == 0L) Fr.ZERO
        else Privacy.stakeCm(crAsset, crOutAmount, Privacy.stakePc(opk, crOutRho, crOutRcm), StakeLabel.hash(crLabel))
    }

    val otag: Fr by lazy { Privacy.ownerTag(opk, tagSalt) }

    /** What the circuit asserts, checked before spending a second on a proof that cannot verify. */
    fun check() {
        require(ins.count { it.label != null } <= 1) { "two labelled inputs" }
        for ((i, s) in ins.withIndex()) if (s.amount != 0L) {
            val cm = Privacy.stakeCm(asset, s.amount, Privacy.stakePc(opk, s.rho, s.rcm), StakeLabel.hash(s.label))
            require(Merkle.rootFromPath(cm, s.pos, s.path) == anchor) { "stake input $i not in the stake tree at its anchor" }
        }
        val l = labelled
        var retained = 0L
        if (clear) {
            require(l != null) { "nothing to clear" }
            require(java.lang.Long.compareUnsigned(l.moveTime, clearBefore) < 0) { "the move's window is still open" }
            retained = debt.retained(l.moveKey, l.exposed, debtRoot) ?: throw IllegalArgumentException("the debt witness does not read the move under debt_root")
        }
        val outEx = outLabel?.exposed ?: 0L
        require(outAmount >= outEx) { "the exposure stays in the note" }
        fun big(v: Long) = java.math.BigInteger(java.lang.Long.toUnsignedString(v))
        val unexposedIn = ins.fold(java.math.BigInteger.ZERO) { a, s -> a + big(s.amount) } - big(l?.exposed ?: 0L) + big(retained) + big(vIn)
        val unexposedOut = big(outAmount - outEx) + big(vOut)
        require(unexposedIn == unexposedOut) { "stake amounts do not balance" }
        if (crIn.amount != 0L) {
            val cm = Privacy.stakeCm(crAsset, crIn.amount, Privacy.stakePc(opk, crIn.rho, crIn.rcm), Fr.ZERO)
            require(Merkle.rootFromPath(cm, crIn.pos, crIn.path) == anchor) { "the credit lane's input is not in the stake tree at its anchor" }
        }
        require(crOutAmount >= 0) { "the credit lane's note is at most 2^63-1" }
        if (crMoveTime != 0L) require(!crNf.isZero && crVIn != 0L) { "a labelled credit names its move and exposure" }
    }

    fun publicInputs(): List<Fr> = listOf(
        anchor, asset, nullifiers[0], nullifiers[1], commitment, Privacy.u64(vIn), Privacy.u64(vOut),
        Privacy.u64(clearBefore), debtRoot, crAsset, crNf, crCm, Privacy.u64(crVIn), Privacy.u64(crMoveTime), otag, sighash,
    )

    fun noirInputs(): Map<String, Any> = mapOf(
        "nk" to nk.toNoir(),
        "in_amount" to ins.map { hex(it.amount) },
        "in_rho" to ins.map { it.rho.toNoir() },
        "in_rcm" to ins.map { it.rcm.toNoir() },
        "in_pos" to ins.map { hex(it.pos) },
        "in_path" to ins.map { i -> i.path.map { it.toNoir() } },
        "in_move_key" to ins.map { (it.label?.moveKey ?: Fr.ZERO).toNoir() },
        "in_move_time" to ins.map { hex(it.label?.moveTime ?: 0L) },
        "in_exposed" to ins.map { hex(it.label?.exposed ?: 0L) },
        "out_amount" to hex(outAmount),
        "out_rho" to outRho.toNoir(),
        "out_rcm" to outRcm.toNoir(),
        // A bool is a field in the witness map: 0x1 / 0x0 (noirc_abi parses a string as a field).
        "clear" to hex(if (clear) 1L else 0L),
        "debt_low_key" to debt.lowKey.toNoir(),
        "debt_low_next_key" to debt.lowNextKey.toNoir(),
        "debt_low_next_index" to hex(debt.lowNextIndex),
        "debt_low_retained" to hex(debt.lowRetained),
        "debt_low_index" to hex(debt.lowIndex),
        "debt_low_path" to debt.lowPath.map { it.toNoir() },
        "cr_in_amount" to hex(crIn.amount),
        "cr_in_rho" to crIn.rho.toNoir(),
        "cr_in_rcm" to crIn.rcm.toNoir(),
        "cr_in_pos" to hex(crIn.pos),
        "cr_in_path" to crIn.path.map { it.toNoir() },
        "cr_out_rho" to crOutRho.toNoir(),
        "cr_out_rcm" to crOutRcm.toNoir(),
        "tag_salt" to tagSalt.toNoir(),
        "anchor" to anchor.toNoir(),
        "asset" to asset.toNoir(),
        "nf_0" to nullifiers[0].toNoir(),
        "nf_1" to nullifiers[1].toNoir(),
        "cm_out" to commitment.toNoir(),
        "v_in" to hex(vIn),
        "v_out" to hex(vOut),
        "clear_before" to hex(clearBefore),
        "debt_root" to debtRoot.toNoir(),
        "cr_asset" to crAsset.toNoir(),
        "cr_nf" to crNf.toNoir(),
        "cr_cm" to crCm.toNoir(),
        "cr_v_in" to hex(crVIn),
        "cr_move_time" to hex(crMoveTime),
        "otag" to otag.toNoir(),
        "sighash" to sighash.toNoir(),
    )

    fun proverToml(): String = toml(noirInputs())
}

/**
 * The membership circuit's witness (circuits/membership). Public inputs in
 * the chain's order (personhood MembershipPublicInputs): root, scope,
 * nullifier, signal, excluded_dsc, excluded_country, max_activation,
 * max_predecessor. A bound of Privacy.NO_BOUND (2^63 - 1) bounds nothing.
 */
data class MembershipWitness(
    val idSecret: Fr,
    val dscKey: Fr,
    val country: Fr,
    val activatedAt: Long,
    /** The leaf's predecessor_at: its switch or re-entry time, 0 for a passport never registered before. */
    val predecessorAt: Long,
    val leafIndex: Long,
    val siblings: List<Fr>,
    val root: Fr,
    val scope: Fr,
    val signal: Fr,
    val excludedDsc: Fr,
    val excludedCountry: Fr,
    val maxActivation: Long,
    val maxPredecessor: Long,
) {
    init {
        require(siblings.size == Merkle.DEPTH)
        require(activatedAt >= 0 && predecessorAt >= 0 && maxActivation >= 0 && maxPredecessor >= 0) { "a bound is a u64 below 2^63" }
        require(leafIndex in 0..0xffffffffL)
    }

    val nullifier: Fr by lazy { Privacy.scopeNullifier(idSecret, scope) }

    val leaf: Fr get() = Privacy.identityLeaf(Privacy.idc(idSecret), dscKey, country, activatedAt, predecessorAt)

    /** What the circuit will assert, checked before spending seconds on a proof that cannot verify. */
    fun check() {
        require(Merkle.rootFromPath(leaf, leafIndex, siblings) == root) { "identity leaf is not in the tree at this root" }
        require(dscKey != excludedDsc) { "this registration's document signer is excluded from this ballot" }
        require(excludedCountry.isZero || country != excludedCountry) { "this registration's country is excluded from this ballot" }
        require(activatedAt <= maxActivation) { "this identity was activated too recently for this action" }
        require(predecessorAt <= maxPredecessor) { "this identity replaced another too recently for this action" }
    }

    fun publicInputs(): List<Fr> =
        listOf(root, scope, nullifier, signal, excludedDsc, excludedCountry, Privacy.u64(maxActivation), Privacy.u64(maxPredecessor))

    fun noirInputs(): Map<String, Any> = mapOf(
        "id_secret" to idSecret.toNoir(),
        "dsc_key" to dscKey.toNoir(),
        "country" to country.toNoir(),
        "activated_at" to hex(activatedAt),
        "predecessor_at" to hex(predecessorAt),
        "leaf_index" to hex(leafIndex),
        "siblings" to siblings.map { it.toNoir() },
        "root" to root.toNoir(),
        "scope" to scope.toNoir(),
        "nullifier" to nullifier.toNoir(),
        "signal" to signal.toNoir(),
        "excluded_dsc" to excludedDsc.toNoir(),
        "excluded_country" to excludedCountry.toNoir(),
        "max_activation" to hex(maxActivation),
        "max_predecessor" to hex(maxPredecessor),
    )

    fun proverToml(): String = toml(noirInputs())
}

/**
 * One used slot of a vote witness: a derth stake note under the proposal's
 * snapshot note root, the low leaf proving its spend nullifier absent from
 * the snapshot stake nullifier tree, and, for a labelled note, the debt tree
 * witness its value is read by (current debt root).
 */
data class VoteSlot(
    val amount: Long,
    val rho: Fr,
    val rcm: Fr,
    val pos: Long,
    val path: List<Fr>,
    val low: network.erth.wallet.privacy.zk.IndexedTree.Witness,
    val label: StakeLabel? = null,
    val debt: DebtTree.Witness = DebtTree.Witness.NONE,
) {
    init {
        require(path.size == Merkle.DEPTH && low.lowPath.size == Merkle.DEPTH && debt.lowPath.size == Merkle.DEPTH)
        require(pos in 0..0xffffffffL && low.lowIndex in 0..0xffffffffL && low.lowNextIndex in 0..0xffffffffL) { "a u32" }
        require(debt.lowIndex in 0..0xffffffffL && debt.lowNextIndex in 0..0xffffffffL) { "a u32" }
        require(amount > 0) { "a used slot holds a note" }
        require(label == null || label.exposed <= amount) { "bad exposure" }
    }

    /** What the note votes: its amount, less what slashes cut from a label's exposure (null: the witness reads nothing under [debtRoot]). */
    fun value(debtRoot: Fr): Long? {
        val l = label ?: return amount
        val r = debt.retained(l.moveKey, l.exposed, debtRoot) ?: return null
        return amount - l.exposed + r
    }
}

/**
 * The vote circuit's witness (circuits/vote v2, ORCHARD_DESIGN 4.2): up to
 * [MAX_NOTES] derth stake notes of one owner (one nk) at one validator, each
 * under the proposal's snapshot note root with its spend nullifier absent
 * from the snapshot stake nullifier tree, and one weight, 0 < weight <= the
 * sum of their values (a labelled note's at the CURRENT [debtRoot]). [slots]
 * are the used slots; [layout] places them and the padding in the circuit's
 * slots (an unused slot: amount 0, rho the padding's r, everything else 0,
 * its vnf the padding nullifier).
 * Public inputs in the chain's order (MsgStakeVote.VotePublicInputs):
 * note_root, nf_root, debt_root, asset, weight, proposal_id, vnf[0..1],
 * sighash.
 */
data class VoteWitness(
    val nk: Fr,
    val slots: List<VoteSlot>,
    val layout: VoteLayout,
    val noteRoot: Fr,
    val nfRoot: Fr,
    val debtRoot: Fr,
    val asset: Fr,
    val weight: Long,
    val proposalId: Long,
    val sighash: Fr,
) {
    init {
        require(slots.size in 1..MAX_NOTES) { "a vote carries 1..$MAX_NOTES notes" }
        require(layout.used == slots.size) { "the layout places ${layout.used} notes, the vote has ${slots.size}" }
    }

    /** The used slots' spend nullifiers: private, never published by a vote. */
    val spendNfs: List<Fr> by lazy { slots.map { Privacy.stakeNf(nk, it.rho, it.pos) } }

    /** Every circuit slot's vote nullifier: a note's, or a padding nullifier ([layout]). */
    val vnfs: List<Fr> by lazy { layout.vnfs(nk, slots, proposalId) }

    /** What the circuit asserts, checked before spending seconds on a proof that cannot verify. */
    fun check() {
        val opk = Privacy.ownerPk(nk)
        var sum = java.math.BigInteger.ZERO
        for ((i, sl) in slots.withIndex()) {
            val cm = Privacy.stakeCm(asset, sl.amount, Privacy.stakePc(opk, sl.rho, sl.rcm), StakeLabel.hash(sl.label))
            require(Merkle.rootFromPath(cm, sl.pos, sl.path) == noteRoot) { "stake note $i is not under the snapshot root" }
            require(sl.low.proves(spendNfs[i], nfRoot)) { "stake note $i was spent before the snapshot" }
            val v = sl.value(debtRoot) ?: throw IllegalArgumentException("stake note $i's label is not read under the current debt root")
            sum += java.math.BigInteger.valueOf(v)
        }
        require(vnfs.none { it.isZero } && vnfs.toSet().size == vnfs.size) { "the same note twice" }
        require(weight != 0L) { "zero vote weight" }
        require(java.math.BigInteger(java.lang.Long.toUnsignedString(weight)) <= sum) { "the vote weighs more than its notes" }
    }

    fun publicInputs(): List<Fr> =
        listOf(noteRoot, nfRoot, debtRoot, asset, Privacy.u64(weight), Privacy.u64(proposalId)) + vnfs + listOf(sighash)

    fun noirInputs(): Map<String, Any> {
        val zero = Fr.ZERO.toNoir()
        val zeros = List(Merkle.DEPTH) { zero }
        fun <T> slot(i: Int, used: (VoteSlot) -> T, unused: T): T = layout.order[i]?.let { used(slots[it]) } ?: unused
        val idx = 0 until MAX_NOTES
        return mapOf(
            "nk" to nk.toNoir(),
            "amount" to idx.map { i -> slot(i, { hex(it.amount) }, "0x0") },
            "rho" to idx.map { i -> slot(i, { it.rho.toNoir() }, layout.padR(i).toNoir()) },
            "rcm" to idx.map { i -> slot(i, { it.rcm.toNoir() }, zero) },
            "pos" to idx.map { i -> slot(i, { hex(it.pos) }, "0x0") },
            "path" to idx.map { i -> slot(i, { s -> s.path.map { it.toNoir() } }, zeros) },
            "move_key" to idx.map { i -> slot(i, { (it.label?.moveKey ?: Fr.ZERO).toNoir() }, zero) },
            "move_time" to idx.map { i -> slot(i, { hex(it.label?.moveTime ?: 0L) }, "0x0") },
            "exposed" to idx.map { i -> slot(i, { hex(it.label?.exposed ?: 0L) }, "0x0") },
            "low_value" to idx.map { i -> slot(i, { it.low.lowValue.toNoir() }, zero) },
            "low_next_value" to idx.map { i -> slot(i, { it.low.lowNextValue.toNoir() }, zero) },
            "low_next_index" to idx.map { i -> slot(i, { hex(it.low.lowNextIndex) }, "0x0") },
            "low_index" to idx.map { i -> slot(i, { hex(it.low.lowIndex) }, "0x0") },
            "low_path" to idx.map { i -> slot(i, { s -> s.low.lowPath.map { it.toNoir() } }, zeros) },
            "debt_low_key" to idx.map { i -> slot(i, { it.debt.lowKey.toNoir() }, zero) },
            "debt_low_next_key" to idx.map { i -> slot(i, { it.debt.lowNextKey.toNoir() }, zero) },
            "debt_low_next_index" to idx.map { i -> slot(i, { hex(it.debt.lowNextIndex) }, "0x0") },
            "debt_low_retained" to idx.map { i -> slot(i, { hex(it.debt.lowRetained) }, "0x0") },
            "debt_low_index" to idx.map { i -> slot(i, { hex(it.debt.lowIndex) }, "0x0") },
            "debt_low_path" to idx.map { i -> slot(i, { s -> s.debt.lowPath.map { it.toNoir() } }, zeros) },
            "note_root" to noteRoot.toNoir(),
            "nf_root" to nfRoot.toNoir(),
            "debt_root" to debtRoot.toNoir(),
            "asset" to asset.toNoir(),
            "weight" to hex(weight),
            "proposal_id" to hex(proposalId),
            "vnf" to vnfs.map { it.toNoir() },
            "sighash" to sighash.toNoir(),
        )
    }

    fun proverToml(): String = toml(noirInputs())

    companion object {
        /** circuits/vote MAX_NOTES: the most notes one vote proof carries. */
        const val MAX_NOTES = 2
    }
}

/**
 * Where a vote's notes and padding sit in the circuit's [VoteWitness.MAX_NOTES]
 * slots: [order] gives each slot's used-note index, or null for padding,
 * whose r values are [pads] in order. Every vote publishes MAX_NOTES non-zero
 * vote nullifiers; a padding nullifier H(TAG_VPAD, nk, r, proposal_id) looks
 * like a note's, so the number of notes voted is hidden. The wallet draws r
 * fresh at random and puts the padding in a random slot ([random]).
 */
data class VoteLayout(val order: List<Int?>, val pads: List<Fr>) {
    init {
        require(order.size == VoteWitness.MAX_NOTES) { "a vote has ${VoteWitness.MAX_NOTES} slots" }
        require(order.count { it == null } == pads.size) { "one padding value per unused slot" }
        require(order.filterNotNull().sorted() == (0 until used).toList()) { "each note in one slot" }
        require(pads.none { it.isZero }) { "a padding value is random" }
    }

    /** How many notes the layout places. */
    val used: Int get() = order.count { it != null }

    /** The padding r of circuit slot [i] (0 for a note's slot). */
    fun padR(i: Int): Fr = if (order[i] != null) Fr.ZERO else pads[order.take(i).count { it == null }]

    fun vnfs(nk: Fr, slots: List<VoteSlot>, proposalId: Long): List<Fr> = order.indices.map { i ->
        order[i]?.let { Privacy.voteNf(nk, slots[it].rho, slots[it].pos, proposalId) } ?: Privacy.votePadNf(nk, padR(i), proposalId)
    }

    companion object {
        private val rng = java.security.SecureRandom()

        /** [used] notes and fresh random padding, in a random slot order. */
        fun random(used: Int): VoteLayout {
            require(used in 1..VoteWitness.MAX_NOTES) { "a vote carries 1..${VoteWitness.MAX_NOTES} notes" }
            val order = ((0 until used).map<Int, Int?> { it } + List(VoteWitness.MAX_NOTES - used) { null }).shuffled(rng)
            return VoteLayout(order, List(VoteWitness.MAX_NOTES - used) { network.erth.wallet.privacy.note.NotePlaintext.randomField() })
        }
    }
}

private fun hex(v: Long): String = "0x" + java.lang.Long.toUnsignedString(v, 16)

private fun toml(m: Map<String, Any>): String = buildString {
    fun v(x: Any): String = when (x) {
        is String -> "\"$x\""
        is List<*> -> x.joinToString(", ", "[", "]") { v(it!!) }
        else -> error("unexpected $x")
    }
    for ((k, x) in m) append(k).append(" = ").append(v(x)).append('\n')
}
