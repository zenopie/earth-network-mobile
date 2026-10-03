package network.erth.wallet.privacy.tx

import com.google.protobuf.ByteString
import network.erth.earth.proto.shieldedstaking.StakeProof
import network.erth.wallet.privacy.keys.PrivacyKeys
import network.erth.wallet.privacy.note.NoteCipher
import network.erth.wallet.privacy.note.NotePlaintext
import network.erth.wallet.privacy.note.OwnedStakeNote
import network.erth.wallet.privacy.prove.StakeWitness
import network.erth.wallet.privacy.zk.Fr
import network.erth.wallet.privacy.zk.Merkle
import network.erth.wallet.privacy.zk.Privacy

/**
 * One stake proof laid out (circuits/stake): up to two of this wallet's
 * stake notes spent under [anchor], up to two created back to it, value
 * [vOut] leaving the notes to the chain, the stake pc the chain may mint to
 * ([mint]) and the owner tag's salt. Everything the sighash binds (the
 * StakeFields) is final once built.
 *
 * [denom] is the msg's stake denom (derth/<valoper> or
 * unbond/<valoper>/<epoch>), null for a position's update, unlock or vote,
 * whose public asset is 0.
 */
class StakePlan(
    private val nk: Fr,
    val denom: String?,
    val spends: List<OwnedStakeNote>,
    private val paths: List<List<Fr>>,
    val outputs: List<Out>,
    val mint: Pair<Fr, Fr>,
    val tagSalt: Fr,
    val anchor: Fr,
    val vOut: Long,
    /** The blind stake ciphertext of [mint]'s note: for a msg that mints one, empty otherwise. */
    val mintCiphertext: ByteArray = ByteArray(0),
) {
    /** A created stake note: its amount, secrets and ciphertext (to ourselves). */
    class Out(val amount: Long, val rho: Fr, val rcm: Fr, val ciphertext: ByteArray)

    init {
        require(spends.size <= 2 && outputs.size <= 2 && paths.size == spends.size)
        require(spends.all { it.denom == denom && it.amount > 0 }) { "a stake proof spends notes of its own denom" }
        require(outputs.all { it.amount > 0 })
        val ins = spends.sumOf { java.math.BigInteger.valueOf(it.amount) }
        val outs = outputs.sumOf { java.math.BigInteger.valueOf(it.amount) }.add(java.math.BigInteger.valueOf(vOut))
        require(ins == outs) { "stake amounts do not balance: in $ins, out $outs" }
    }

    val asset: Fr = denom?.let(Privacy::assetId) ?: Fr.ZERO

    private val dummyIn = List(2) { NotePlaintext.randomField() to NotePlaintext.randomField() }
    private val dummyOut = List(2) { NotePlaintext.randomField() to NotePlaintext.randomField() }

    fun witness(sighash: Fr): StakeWitness = StakeWitness(
        nk = nk,
        inAmount = (0..1).map { spends.getOrNull(it)?.amount ?: 0L },
        inRho = (0..1).map { spends.getOrNull(it)?.rho ?: dummyIn[it].first },
        inRcm = (0..1).map { spends.getOrNull(it)?.rcm ?: dummyIn[it].second },
        inPos = (0..1).map { spends.getOrNull(it)?.position ?: 0L },
        inPath = (0..1).map { paths.getOrNull(it) ?: List(Merkle.DEPTH) { Fr.ZERO } },
        outAmount = (0..1).map { outputs.getOrNull(it)?.amount ?: 0L },
        outRho = (0..1).map { outputs.getOrNull(it)?.rho ?: dummyOut[it].first },
        outRcm = (0..1).map { outputs.getOrNull(it)?.rcm ?: dummyOut[it].second },
        mintRho = mint.first, mintRcm = mint.second, tagSalt = tagSalt,
        anchor = anchor, asset = asset, vIn = 0, vOut = vOut, sighash = sighash,
    )

    /** The msg's StakeProof, with [proof] (a placeholder before proving). */
    fun proto(proof: ByteArray): StakeProof {
        val w = witness(Fr.ZERO) // every public value but the sighash
        return StakeProof.newBuilder()
            .setProof(ByteString.copyFrom(proof))
            .setAnchor(ByteString.copyFrom(anchor.toBytes()))
            .addAllNullifiers(w.nullifiers.map { ByteString.copyFrom(it.toBytes()) })
            .addAllCommitments(w.commitments.map { ByteString.copyFrom(it.toBytes()) })
            .addAllCiphertexts((0..1).map { ByteString.copyFrom(outputs.getOrNull(it)?.ciphertext ?: ByteArray(0)) })
            .setSpcMint(ByteString.copyFrom(w.spcMint.toBytes()))
            .setOwnerTag(ByteString.copyFrom(w.otag.toBytes()))
            .setSpcCiphertext(ByteString.copyFrom(mintCiphertext))
            .build()
    }

    companion object {
        /** A created stake note of [amount] [denom] back to [keys], with its stake ciphertext. */
        fun out(keys: PrivacyKeys, denom: String, amount: Long): Out {
            val rho = NotePlaintext.randomField()
            val rcm = NotePlaintext.randomField()
            val asset = Privacy.assetId(denom)
            val cm = Privacy.stakeCm(asset, amount, Privacy.stakePc(keys.ownerPk, rho, rcm))
            return Out(amount, rho, rcm, NoteCipher.encryptStake(NoteCipher.StakeOpening(asset, amount, rho, rcm), keys.ekPub, cm))
        }

        /**
         * A stake note the chain will mint to us (spc_mint): fresh rho and
         * rcm, and their blind stake ciphertext to our own address, which
         * sync opens against the denom and amount the chain publishes.
         */
        fun selfMint(keys: PrivacyKeys): Pair<Pair<Fr, Fr>, ByteArray> {
            val rho = NotePlaintext.randomField()
            val rcm = NotePlaintext.randomField()
            return (rho to rcm) to NoteCipher.encryptBlindStake(rho, rcm, keys.ekPub)
        }

        /**
         * Secrets for a spc_mint or owner tag the msg does not use: fresh, so
         * the public value links to nothing (the circuit still proves it).
         */
        fun throwaway(): Pair<Fr, Fr> = NotePlaintext.randomField() to NotePlaintext.randomField()
    }
}
