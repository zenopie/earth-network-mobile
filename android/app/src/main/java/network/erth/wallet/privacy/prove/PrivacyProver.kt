package network.erth.wallet.privacy.prove

import android.content.Context
import com.noirandroid.lib.Circuit
import network.erth.wallet.passport.NoirProver
import network.erth.wallet.privacy.zk.Fr

/**
 * On-device proofs of the privacy circuits (circuits/membership,
 * circuits/move, circuits/action, circuits/stake, circuits/vote), through the same noir_android build as
 * [network.erth.wallet.passport.PassportProver] — bb v5.0.0, in lockstep
 * with the chain's verifier; never float that pin.
 *
 * The chain never takes public inputs from the tx: it recomputes them from
 * the msg (zk/orchard Bundle.PublicInputs, StakeProof.PublicInputs, personhood
 * MembershipPublicInputs) and verifies the proof body against them. So the
 * public inputs noir_android returns ahead of the body are split off and
 * checked against the witness's own, and only the body is sent.
 */
object PrivacyProver {

    /** Expected body size of a bb v5.0.0 UltraHonk proof, whatever the circuit. */
    const val PROOF_BYTES = 14_656

    internal enum class Kind(val file: String, val srsSize: Int, val publicInputs: Int) {
        // Every kind asks the same SRS: bb honours only a process's first SRS
        // initialization, so whichever proves first sizes it for all five.
        // The hint must be at least the largest circuit's dyadic size: vote
        // (21,716 gates with two labelled slots) and stake (16,574 with its
        // Groundworks tags) are 2^15 circuits; membership 5,659, move 8,362
        // and action 8,098 are 2^14.
        // The bundled 2^15 + 1 points cover them all.
        MEMBERSHIP("membership", SRS_SIZE, 8),
        // root, scope, old_nullifier, new_nullifier, signal.
        MOVE("move", SRS_SIZE, 5),
        ACTION("action", SRS_SIZE, 6),
        // anchor, asset, nf_0, nf_1, cm_out, v_in, v_out, clear_before,
        // debt_root, cr_asset, cr_nf, cr_cm, cr_v_in, cr_move_time, gw_0, gw_1,
        // cr_gw, gw_out, w_out, cr_gw_out, cr_w_out, sighash.
        STAKE("stake", SRS_SIZE, 22),
        // note_root, nf_root, debt_root, asset, weight, proposal_id, vnf[0..1], sighash.
        VOTE("vote", SRS_SIZE, 9),
    }

    /** The privacy circuits' SRS size hint (2^15: 32,769 points). */
    const val SRS_SIZE = 1 shl 15

    /**
     * The bundled SRS: the first 32,769 G1 points of Aztec's
     * bn254 transcript (crs.aztec.network/g1.dat, bytes 0..2,097,215),
     * enough for every privacy circuit. Proving a private tx never fetches
     * the SRS, so nothing outside the chain sees when one is made. The
     * passport circuits (2^18 and up, 16 MB and more) still download theirs
     * once, at registration, which is public anyway.
     */
    const val SRS_ASSET = "srs/bn254_g1_32769.dat"
    const val SRS_POINTS = 32_769
    const val SRS_SHA256 = "d769ac6c98f8fab858a7e9967f2b7f181d8ad9fdcdf55438c915696febf0e99c"

    /**
     * The bundled SRS as a file noir_rs reads (a `.dat` path: raw G1
     * points), copied out of the APK and checked once; null when [size]
     * needs more points than it holds (noir_rs would abort on a short file),
     * which falls back to the download.
     */
    @Synchronized
    fun srsPath(context: Context, size: Int): String? {
        var subgroup = 1L
        while (subgroup < size) subgroup = subgroup shl 1
        if (subgroup + 1 > SRS_POINTS) return null
        val f = java.io.File(context.noBackupFilesDir, SRS_ASSET)
        if (f.length() == SRS_POINTS * 64L) return f.absolutePath
        f.parentFile?.mkdirs()
        val tmp = java.io.File(f.parentFile, f.name + ".tmp")
        val md = java.security.MessageDigest.getInstance("SHA-256")
        context.assets.open(SRS_ASSET).use { input ->
            java.io.FileOutputStream(tmp).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n); out.write(buf, 0, n) }
                out.fd.sync()
            }
        }
        val hex = md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        check(hex == SRS_SHA256 && tmp.length() == SRS_POINTS * 64L) { "the bundled SRS is corrupt" }
        check(tmp.renameTo(f)) { "could not install the bundled SRS" }
        return f.absolutePath
    }

    private class Loaded(val circuit: Circuit, val vk: String)

    private val loaded = HashMap<Kind, Loaded>()

    @Synchronized
    private fun load(context: Context, k: Kind): Loaded = loaded.getOrPut(k) {
        val json = context.assets.open("circuits/${k.file}.json").bufferedReader().use { it.readText() }
        val c = NoirProver.loadCircuit(json, k.srsSize)
        c.setupSrs(srsPath(context, k.srsSize))
        Loaded(c, c.getVerificationKey())
    }

    fun proveAction(context: Context, w: ActionWitness): ByteArray {
        w.check()
        return prove(context, Kind.ACTION, w.noirInputs(), w.publicInputs())
    }

    fun proveStake(context: Context, w: StakeWitness): ByteArray {
        w.check()
        return prove(context, Kind.STAKE, w.noirInputs(), w.publicInputs())
    }

    fun proveMembership(context: Context, w: MembershipWitness): ByteArray {
        w.check()
        return prove(context, Kind.MEMBERSHIP, w.noirInputs(), w.publicInputs())
    }

    fun proveMove(context: Context, w: MoveWitness): ByteArray {
        w.check()
        return prove(context, Kind.MOVE, w.noirInputs(), w.publicInputs())
    }

    fun proveVote(context: Context, w: VoteWitness): ByteArray {
        w.check()
        return prove(context, Kind.VOTE, w.noirInputs(), w.publicInputs())
    }

    private fun prove(context: Context, k: Kind, inputs: Map<String, Any>, expected: List<Fr>): ByteArray {
        val l = load(context, k)
        val proofHex = synchronized(l) { NoirProver.prove(l.circuit, inputs, l.vk) }
        val (body, pub) = split(proofHex, k.publicInputs)
        check(pub == expected.map { it.toHex() }) { "the ${k.file} proof's public inputs are not the witness's" }
        return body
    }

    /**
     * noir_android's output: a 4-byte count, the public inputs (32 bytes
     * each), then the proof body (as PassportProver.splitProof).
     */
    internal fun split(proofHex: String, numPublic: Int): Pair<ByteArray, List<String>> {
        val h = proofHex.removePrefix("0x")
        val all = ByteArray(h.length / 2) { h.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
        val off = 4
        require(all.size >= off + 32 * numPublic) { "proof too short for $numPublic public inputs" }
        val pub = (0 until numPublic).map { i ->
            all.copyOfRange(off + 32 * i, off + 32 * i + 32).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
        return all.copyOfRange(off + 32 * numPublic, all.size) to pub
    }
}
