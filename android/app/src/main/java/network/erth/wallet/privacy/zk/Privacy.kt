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
    // A registration's referrer: H(TAG_AFFILIATE, Bytes(handle)).
    val TAG_AFFILIATE = tag("earth.affiliate")
    // The opening of the referral note the chain mints to a referrer handle's address.
    val TAG_REFERRAL = tag("earth.referral")
    // The stake note tree (x/shieldedstaking, circuits/stake).
    val TAG_STAKE = tag("earth.stake")
    val TAG_SPC = tag("earth.spc")
    val TAG_SNF = tag("earth.snf")
    val TAG_OTAG = tag("earth.otag")
    // The stake nullifier indexed tree and stake votes (circuits/vote, ORCHARD_DESIGN 3.4, 8.5).
    val TAG_SNFL = tag("earth.snfl")
    val TAG_VNF = tag("earth.vnf")
    val TAG_VPAD = tag("earth.vpad")
    // Stake note slash labels and the slash debt tree (circuits/stake, circuits/vote; ORCHARD_DESIGN 3.3, 3.5).
    val TAG_SLABEL = tag("earth.slabel")
    val TAG_DEBTL = tag("earth.debtl")
    // Wallet-defined (PRIVACY_FORMATS.md §6): the registration record note's tag.
    val TAG_RECTAG = tag("earth.rectag")
    // Wallet-defined (PRIVACY_FORMATS.md §6): an unlock's closed owner-tag counter.
    val TAG_UNLOCKTAG = tag("earth.unlocktag")
    // Wallet-defined (PRIVACY_FORMATS.md §6): a handle or caretaker state record's tag.
    val TAG_STATETAG = tag("earth.statetag")

    /** The fee asset, privacy_core::ASSET_ERTH. */
    val ASSET_ERTH: Fr by lazy { assetId("uerth") }

    private fun tag(s: String): Fr = Fr.of(BigInteger(1, s.toByteArray(Charsets.US_ASCII)))

    fun h(vararg xs: Fr): Fr = Poseidon2.hash(*xs)

    fun u64(v: Long): Fr = Fr.ofU64(v)

    fun idc(idSecret: Fr): Fr = h(TAG_ID, idSecret)

    fun ownerPk(nk: Fr): Fr = h(TAG_OWNER, nk)

    /**
     * H(TAG_LEAF, idc, dsc_key, country, activated_at, predecessor_at):
     * predecessor_at is the time of the switch or re-entry that made the
     * leaf (its activated_at), 0 for a passport never registered before.
     */
    fun identityLeaf(idc: Fr, dscKey: Fr, country: Fr, activatedAt: Long, predecessorAt: Long): Fr =
        h(TAG_LEAF, idc, dscKey, country, u64(activatedAt), u64(predecessorAt))

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

    /**
     * A stake note: H(TAG_STAKE, AssetID(stake denom), amount, spc, label),
     * label 0 for an ordinary note or [stakeLabel] of the redelegation whose
     * exposure it holds (ORCHARD_DESIGN 3.3).
     */
    fun stakeCm(asset: Fr, amount: Long, spc: Fr, label: Fr): Fr = h(TAG_STAKE, asset, u64(amount), spc, label)

    /**
     * A stake note's slash label: H(TAG_SLABEL, move_key, move_time,
     * exposed). The note holds [exposed] derth a redelegation credited
     * ([moveKey]: its credit nullifier; [moveTime]: the unix seconds it
     * named) while a slash of its source may still cut it.
     */
    fun stakeLabel(moveKey: Fr, moveTime: Long, exposed: Long): Fr = h(TAG_SLABEL, moveKey, u64(moveTime), u64(exposed))

    /** A slash debt tree leaf: H(TAG_DEBTL, key, next_key, next_index, retained), next_index a u32. */
    fun debtLeaf(key: Fr, nextKey: Fr, nextIndex: Long, retained: Long): Fr {
        require(nextIndex in 0..0xffffffffL) { "next_index is a u32" }
        return h(TAG_DEBTL, key, nextKey, u64(nextIndex), u64(retained))
    }

    /** A stake note's nullifier: H(TAG_SNF, nk, rho, position), position a u32. */
    fun stakeNf(nk: Fr, rho: Fr, position: Long): Fr {
        require(position in 0..0xffffffffL) { "position is a u32" }
        return h(TAG_SNF, nk, rho, u64(position))
    }

    /** A stake nullifier tree leaf: H(TAG_SNFL, value, next_value, next_index), next_index a u32. */
    fun nfLeaf(value: Fr, nextValue: Fr, nextIndex: Long): Fr {
        require(nextIndex in 0..0xffffffffL) { "next_index is a u32" }
        return h(TAG_SNFL, value, nextValue, u64(nextIndex))
    }

    /** A stake note's vote nullifier on a proposal: H(TAG_VNF, nk, rho, position, proposal_id). */
    fun voteNf(nk: Fr, rho: Fr, position: Long, proposalId: Long): Fr {
        require(position in 0..0xffffffffL) { "position is a u32" }
        return h(TAG_VNF, nk, rho, u64(position), u64(proposalId))
    }

    /**
     * An unused vote slot's padding nullifier: H(TAG_VPAD, nk, r, proposal_id),
     * r fresh random per vote. Looks like a [voteNf] and never equals one.
     */
    fun votePadNf(nk: Fr, r: Fr, proposalId: Long): Fr = h(TAG_VPAD, nk, r, u64(proposalId))

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
    fun handleScope(): Fr = scope("handle")
    fun proposalScope(proposalId: Long, round: Long): Fr = scope("proposal", u64(proposalId), u64(round))
    fun removalScope(ballotId: Long): Fr = scope("removal", u64(ballotId))
    fun proposeRemovalScope(optionId: Long, day: Long): Fr = scope("propose_removal", u64(optionId), u64(day))

    /**
     * The passport proof's `address` input:
     * H(TAG_REG, Bytes(chain_id), idc, pc_anml, Bytes(ct_anml), pc_erth, Bytes(ct_erth), affiliate).
     * The chain id keeps a registration seen on one network
     * from being replayed onto another.
     */
    fun registrationBinding(chainId: String, idc: Fr, pcAnml: Fr, ctAnml: ByteArray, pcErth: Fr, ctErth: ByteArray, affiliate: Fr): Fr =
        h(TAG_REG, bytes(chainId.toByteArray(Charsets.UTF_8)), idc, pcAnml, bytes(ctAnml), pcErth, bytes(ctErth), affiliate)

    /**
     * The registration binding's affiliate field for a referrer named by
     * handle: H(TAG_AFFILIATE, Bytes(handle)). A registration naming no
     * referrer carries 0. The chain makes the referral note itself
     * ([referralOpening]), so the handle is all the registrant binds.
     */
    fun affiliateField(handle: String): Fr =
        h(TAG_AFFILIATE, bytes(handle.toByteArray(Charsets.US_ASCII)))

    /**
     * The (rho, rcm) of the referral note a registration mints to its
     * referrer handle's owner_pk (chain zk/privacy.ReferralOpening):
     * H(TAG_REFERRAL, nullifier, leaf_index, 0) and (..., 1), nullifier the
     * passport nullifier, leaf_index the new identity leaf's. Public: the
     * mint event carries them.
     */
    fun referralOpening(nullifier: Fr, leafIndex: Long): Pair<Fr, Fr> =
        h(TAG_REFERRAL, nullifier, u64(leafIndex), u64(0)) to h(TAG_REFERRAL, nullifier, u64(leafIndex), u64(1))

    /** The membership bound that bounds nothing (max_activation / max_predecessor): 2^63 - 1. */
    const val NO_BOUND: Long = Long.MAX_VALUE
}
