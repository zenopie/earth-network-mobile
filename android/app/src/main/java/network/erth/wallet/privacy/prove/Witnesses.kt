package network.erth.wallet.privacy.prove

import network.erth.wallet.privacy.zk.Fr
import network.erth.wallet.privacy.zk.Merkle
import network.erth.wallet.privacy.zk.Privacy

/** One transfer input: a real note (with its path), or a value-0 dummy. */
data class TransferInput(
    val value: Long,
    val rho: Fr,
    val rcm: Fr,
    val position: Long,
    val path: List<Fr>,
) {
    init {
        require(position in 0..0xffffffffL) { "position is a u32" }
        require(path.size == Merkle.DEPTH)
        require(value >= 0)
    }

    companion object {
        /**
         * A dummy: value 0, so the circuit skips its membership, and a fresh
         * rho, so its nullifier (which is still published and spent) is new.
         */
        fun dummy(): TransferInput = TransferInput(
            0, network.erth.wallet.privacy.note.NotePlaintext.randomField(),
            network.erth.wallet.privacy.note.NotePlaintext.randomField(), 0, List(Merkle.DEPTH) { Fr.ZERO },
        )
    }
}

/** One transfer output: a value to a pc (the recipient's hidden owner). */
data class TransferOutput(val value: Long, val pc: Fr) {
    init { require(value >= 0) }
}

/**
 * The transfer circuit's witness (circuits/transfer): slots 0-1 one hidden
 * asset A, slot 2 ERTH for the fee.
 *
 *     in0 + in1 == out0 + out1 + v_pub_out    (asset A)
 *     in2       == out2 + fee                 (ERTH)
 *
 * Public inputs, in the chain's order (types.Transfer.PublicInputs): root,
 * nf[3], cm_out[3], fee, v_pub_out, asset_pub, signal. asset_pub is A when
 * value leaves the pool and 0 otherwise; the chain requires the 0.
 */
data class TransferWitness(
    val asset: Fr,
    val nk: Fr,
    val inputs: List<TransferInput>,
    val outputs: List<TransferOutput>,
    val root: Fr,
    val fee: Long,
    val vPubOut: Long,
    val signal: Fr,
) {
    init {
        require(inputs.size == 3 && outputs.size == 3)
        require(fee >= 0 && vPubOut >= 0)
        require(inputs[0].value + inputs[1].value == outputs[0].value + outputs[1].value + vPubOut) { "asset A unbalanced" }
        require(inputs[2].value == outputs[2].value + fee) { "fee slot unbalanced" }
    }

    val assets: List<Fr> get() = listOf(asset, asset, Privacy.ASSET_ERTH)
    val assetPub: Fr get() = if (vPubOut > 0) asset else Fr.ZERO
    val nullifiers: List<Fr> by lazy { inputs.map { Privacy.nf(nk, it.rho, it.position) } }
    val commitments: List<Fr> by lazy { outputs.mapIndexed { i, o -> Privacy.cm(assets[i], o.value, o.pc) } }

    fun publicInputs(): List<Fr> =
        listOf(root) + nullifiers + commitments + listOf(Privacy.u64(fee), Privacy.u64(vPubOut), assetPub, signal)

    /** noir_android's input map: every scalar a "0x" hex string, arrays as lists. */
    fun noirInputs(): Map<String, Any> = mapOf(
        "asset" to asset.toNoir(),
        "nk" to nk.toNoir(),
        "in_value" to inputs.map { hex(it.value) },
        "in_rho" to inputs.map { it.rho.toNoir() },
        "in_rcm" to inputs.map { it.rcm.toNoir() },
        "in_pos" to inputs.map { hex(it.position) },
        "in_path" to inputs.map { i -> i.path.map { it.toNoir() } },
        "out_value" to outputs.map { hex(it.value) },
        "out_pc" to outputs.map { it.pc.toNoir() },
        "root" to root.toNoir(),
        "nf" to nullifiers.map { it.toNoir() },
        "cm_out" to commitments.map { it.toNoir() },
        "fee" to hex(fee),
        "v_pub_out" to hex(vPubOut),
        "asset_pub" to assetPub.toNoir(),
        "signal" to signal.toNoir(),
    )

    /** The same witness as a nargo Prover.toml, for checking against the circuit off-device. */
    fun proverToml(): String = toml(noirInputs())
}

/**
 * The membership circuit's witness (circuits/membership). Public inputs in
 * the chain's order (personhood MembershipPublicInputs): root, scope,
 * nullifier, signal, excluded_dsc, excluded_country, max_activation.
 */
data class MembershipWitness(
    val idSecret: Fr,
    val dscKey: Fr,
    val country: Fr,
    val activatedAt: Long,
    val leafIndex: Long,
    val siblings: List<Fr>,
    val root: Fr,
    val scope: Fr,
    val signal: Fr,
    val excludedDsc: Fr,
    val excludedCountry: Fr,
    val maxActivation: Long,
) {
    init {
        require(siblings.size == Merkle.DEPTH)
        require(leafIndex in 0..0xffffffffL)
    }

    val nullifier: Fr by lazy { Privacy.scopeNullifier(idSecret, scope) }

    val leaf: Fr get() = Privacy.identityLeaf(Privacy.idc(idSecret), dscKey, country, activatedAt)

    /** What the circuit will assert, checked before spending seconds on a proof that cannot verify. */
    fun check() {
        require(Merkle.rootFromPath(leaf, leafIndex, siblings) == root) { "identity leaf is not in the tree at this root" }
        require(dscKey != excludedDsc) { "this registration's document signer is excluded from this ballot" }
        require(excludedCountry.isZero || country != excludedCountry) { "this registration's country is excluded from this ballot" }
        require(activatedAt <= maxActivation) { "this identity was activated too recently for this action" }
    }

    fun publicInputs(): List<Fr> =
        listOf(root, scope, nullifier, signal, excludedDsc, excludedCountry, Privacy.u64(maxActivation))

    fun noirInputs(): Map<String, Any> = mapOf(
        "id_secret" to idSecret.toNoir(),
        "dsc_key" to dscKey.toNoir(),
        "country" to country.toNoir(),
        "activated_at" to hex(activatedAt),
        "leaf_index" to hex(leafIndex),
        "siblings" to siblings.map { it.toNoir() },
        "root" to root.toNoir(),
        "scope" to scope.toNoir(),
        "nullifier" to nullifier.toNoir(),
        "signal" to signal.toNoir(),
        "excluded_dsc" to excludedDsc.toNoir(),
        "excluded_country" to excludedCountry.toNoir(),
        "max_activation" to hex(maxActivation),
    )

    fun proverToml(): String = toml(noirInputs())
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
