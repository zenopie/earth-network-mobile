package network.erth.wallet.privacy.prove

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

/**
 * The stake circuit's witness (circuits/stake): up to two stake notes of one
 * owner spent under [anchor] (amount 0 = none: nf 0, no path), up to two
 * created (amount 0 = none: cm 0), all of [asset], with
 *
 *     in_0 + in_1 + v_in == out_0 + out_1 + v_out
 *
 * and spc_mint, otag of the same owner. Public inputs, in the chain's order
 * (x/shieldedstaking StakeProof.PublicInputs): anchor, asset, nf_0, nf_1,
 * cm_out_0, cm_out_1, v_in, v_out, spc_mint, otag, sighash.
 */
data class StakeWitness(
    val nk: Fr,
    val inAmount: List<Long>,
    val inRho: List<Fr>,
    val inRcm: List<Fr>,
    val inPos: List<Long>,
    val inPath: List<List<Fr>>,
    val outAmount: List<Long>,
    val outRho: List<Fr>,
    val outRcm: List<Fr>,
    val mintRho: Fr,
    val mintRcm: Fr,
    val tagSalt: Fr,
    val anchor: Fr,
    val asset: Fr,
    val vIn: Long,
    val vOut: Long,
    val sighash: Fr,
) {
    init {
        require(listOf(inAmount, inRho, inRcm, inPos, inPath, outAmount, outRho, outRcm).all { it.size == 2 })
        require(inPath.all { it.size == Merkle.DEPTH })
        require(inPos.all { it in 0..0xffffffffL }) { "position is a u32" }
    }

    private val opk: Fr by lazy { Privacy.ownerPk(nk) }

    val nullifiers: List<Fr> by lazy {
        (0..1).map { if (inAmount[it] == 0L) Fr.ZERO else Privacy.stakeNf(nk, inRho[it], inPos[it]) }
    }
    val commitments: List<Fr> by lazy {
        (0..1).map { if (outAmount[it] == 0L) Fr.ZERO else Privacy.stakeCm(asset, outAmount[it], Privacy.stakePc(opk, outRho[it], outRcm[it])) }
    }
    val spcMint: Fr by lazy { Privacy.stakePc(opk, mintRho, mintRcm) }
    val otag: Fr by lazy { Privacy.ownerTag(opk, tagSalt) }

    fun check() {
        for (i in 0..1) if (inAmount[i] != 0L) {
            val cm = Privacy.stakeCm(asset, inAmount[i], Privacy.stakePc(opk, inRho[i], inRcm[i]))
            require(Merkle.rootFromPath(cm, inPos[i], inPath[i]) == anchor) { "stake input $i not in the stake tree at its anchor" }
        }
        val ins = inAmount.sumOf { java.math.BigInteger.valueOf(it) }.add(java.math.BigInteger.valueOf(vIn))
        val outs = outAmount.sumOf { java.math.BigInteger.valueOf(it) }.add(java.math.BigInteger.valueOf(vOut))
        require(ins == outs) { "stake amounts do not balance" }
    }

    fun publicInputs(): List<Fr> = listOf(anchor, asset) + nullifiers + commitments +
        listOf(Privacy.u64(vIn), Privacy.u64(vOut), spcMint, otag, sighash)

    fun noirInputs(): Map<String, Any> = mapOf(
        "nk" to nk.toNoir(),
        "in_amount" to inAmount.map { hex(it) },
        "in_rho" to inRho.map { it.toNoir() },
        "in_rcm" to inRcm.map { it.toNoir() },
        "in_pos" to inPos.map { hex(it) },
        "in_path" to inPath.map { p -> p.map { it.toNoir() } },
        "out_amount" to outAmount.map { hex(it) },
        "out_rho" to outRho.map { it.toNoir() },
        "out_rcm" to outRcm.map { it.toNoir() },
        "mint_rho" to mintRho.toNoir(),
        "mint_rcm" to mintRcm.toNoir(),
        "tag_salt" to tagSalt.toNoir(),
        "anchor" to anchor.toNoir(),
        "asset" to asset.toNoir(),
        "nf_0" to nullifiers[0].toNoir(),
        "nf_1" to nullifiers[1].toNoir(),
        "cm_out_0" to commitments[0].toNoir(),
        "cm_out_1" to commitments[1].toNoir(),
        "v_in" to hex(vIn),
        "v_out" to hex(vOut),
        "spc_mint" to spcMint.toNoir(),
        "otag" to otag.toNoir(),
        "sighash" to sighash.toNoir(),
    )

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
