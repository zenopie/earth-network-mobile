package network.erth.wallet.privacy.zk

import java.math.BigInteger

/**
 * The domain-tagged Poseidon2 derivations of the chain's zk/privacy, which the
 * Noir privacy_core recomputes in-proof. Every function here must match its Go
 * twin bit for bit (PrivacyVectorsTest pins them to vectors generated from the
 * chain's own code): a divergence makes every proof fail, or makes the wallet
 * miss its own notes.
 */
object Privacy {
    val TAG_ID = tag("earth.id")
    val TAG_OWNER = tag("earth.owner")
    val TAG_LEAF = tag("earth.leaf")
    val TAG_SN = tag("earth.sn")
    val TAG_PC = tag("earth.pc")
    val TAG_CM = tag("earth.cm")
    val TAG_NF = tag("earth.nf")
    val TAG_REG = tag("earth.reg")
    val TAG_ASSET = tag("earth.asset")
    val TAG_SIGNAL = tag("earth.signal")
    val TAG_BYTES = tag("earth.bytes")
    val TAG_SCOPE = tag("earth.scope")
    // The stake note tree (x/shieldedstaking, circuits/stake).
    val TAG_STAKE = tag("earth.stake")
    val TAG_SPC = tag("earth.spc")
    val TAG_SNF = tag("earth.snf")
    val TAG_OTAG = tag("earth.otag")

    /** The fee asset, privacy_core::ASSET_ERTH. */
    val ASSET_ERTH: Fr by lazy { assetId("uerth") }

    private fun tag(s: String): Fr = Fr.of(BigInteger(1, s.toByteArray(Charsets.US_ASCII)))

    fun h(vararg xs: Fr): Fr = Poseidon2.hash(*xs)

    fun u64(v: Long): Fr = Fr.ofU64(v)

    fun idc(idSecret: Fr): Fr = h(TAG_ID, idSecret)

    fun ownerPk(nk: Fr): Fr = h(TAG_OWNER, nk)

    fun identityLeaf(idc: Fr, dscKey: Fr, country: Fr, activatedAt: Long): Fr =
        h(TAG_LEAF, idc, dscKey, country, u64(activatedAt))

    /** ISO 3166-1 alpha-2 as two big-endian ASCII bytes; anything else is 0 (unknown). */
    fun countryField(cc: String): Fr {
        if (cc.length != 2 || cc[0] !in 'A'..'Z' || cc[1] !in 'A'..'Z') return Fr.ZERO
        return Fr.of((cc[0].code.toLong() shl 8) or cc[1].code.toLong())
    }

    fun scopeNullifier(idSecret: Fr, scope: Fr): Fr = h(TAG_SN, idSecret, scope)

    fun pc(ownerPk: Fr, rho: Fr, rcm: Fr): Fr = h(TAG_PC, ownerPk, rho, rcm)

    fun cm(asset: Fr, value: Long, pc: Fr): Fr = h(TAG_CM, asset, u64(value), pc)

    /** position is a u32 in the circuit. */
    fun nf(nk: Fr, rho: Fr, position: Long): Fr {
        require(position in 0..0xffffffffL) { "position is a u32" }
        return h(TAG_NF, nk, rho, u64(position))
    }

    /** A stake note's hidden owner: H(TAG_SPC, owner_pk, rho, rcm). */
    fun stakePc(ownerPk: Fr, rho: Fr, rcm: Fr): Fr = h(TAG_SPC, ownerPk, rho, rcm)

    /** A stake note: H(TAG_STAKE, AssetID(stake denom), amount, spc). */
    fun stakeCm(asset: Fr, amount: Long, spc: Fr): Fr = h(TAG_STAKE, asset, u64(amount), spc)

    /** A stake note's nullifier: H(TAG_SNF, nk, rho, position), position a u32. */
    fun stakeNf(nk: Fr, rho: Fr, position: Long): Fr {
        require(position in 0..0xffffffffL) { "position is a u32" }
        return h(TAG_SNF, nk, rho, u64(position))
    }

    /** A Groundworks position's owner tag: H(TAG_OTAG, owner_pk, salt). */
    fun ownerTag(ownerPk: Fr, salt: Fr): Fr = h(TAG_OTAG, ownerPk, salt)

    fun assetId(denom: String): Fr {
        val b = denom.toByteArray(Charsets.UTF_8)
        return Poseidon2.hash(listOf(TAG_ASSET, u64(b.size.toLong())) + chunks31(b))
    }

    /** H(TAG_BYTES, len(b), 31-byte big-endian chunks...). */
    fun bytes(b: ByteArray): Fr = Poseidon2.hash(listOf(TAG_BYTES, u64(b.size.toLong())) + chunks31(b))

    private fun chunks31(b: ByteArray): List<Fr> {
        val out = ArrayList<Fr>((b.size + 30) / 31)
        var i = 0
        while (i < b.size) {
            val j = minOf(i + 31, b.size)
            out.add(Fr.of(BigInteger(1, b.copyOfRange(i, j))))
            i = j
        }
        return out
    }

    /** H(TAG_SIGNAL, Bytes(msg_type), Bytes(chain_id), fields...). */
    fun signal(msgType: String, chainId: String, fields: List<Fr>): Fr =
        Poseidon2.hash(listOf(TAG_SIGNAL, bytes(msgType.toByteArray()), bytes(chainId.toByteArray())) + fields)

    fun scope(kind: String, vararg args: Fr): Fr =
        Poseidon2.hash(listOf(TAG_SCOPE, bytes(kind.toByteArray())) + args)

    fun claimScope(day: Long): Fr = scope("claim", u64(day))
    fun caretakerScope(): Fr = scope("caretaker")
    fun referrerScope(): Fr = scope("referrer")
    fun proposalScope(proposalId: Long, round: Long): Fr = scope("proposal", u64(proposalId), u64(round))
    fun removalScope(ballotId: Long): Fr = scope("removal", u64(ballotId))
    fun proposeRemovalScope(optionId: Long, day: Long): Fr = scope("propose_removal", u64(optionId), u64(day))

    /**
     * The passport proof's `address` input:
     * H(TAG_REG, idc, pc_anml, Bytes(ct_anml), pc_erth, Bytes(ct_erth), affiliate).
     */
    fun registrationBinding(idc: Fr, pcAnml: Fr, ctAnml: ByteArray, pcErth: Fr, ctErth: ByteArray, affiliate: Fr): Fr =
        h(TAG_REG, idc, pcAnml, bytes(ctAnml), pcErth, bytes(ctErth), affiliate)
}
