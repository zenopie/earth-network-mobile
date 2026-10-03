package network.erth.wallet.privacy.prove

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.noirandroid.lib.Circuit
import network.erth.wallet.passport.NoirProver
import network.erth.wallet.privacy.zk.Fr

/**
 * On-device proofs of the privacy circuits (circuits/membership,
 * circuits/action, circuits/stake, circuits/vote), through the same noir_android build as
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
    private const val TAG = "PrivacyProver"

    /** Expected body size of a bb v5.0.0 UltraHonk proof, whatever the circuit. */
    const val PROOF_BYTES = 14_656

    private enum class Kind(val file: String, val srsSize: Int, val publicInputs: Int) {
        // Every kind asks the same SRS: bb honours only a process's first SRS
        // initialization, so whichever proves first sizes it for all three
        // (twice the next power of two above the largest gate count, stake's
        // 9,647; membership 5,645, action 8,120 and vote 9,046 fit under it:
        // 2^14 circuits, which the bundled 2^15 + 1 points cover).
        MEMBERSHIP("membership", SRS_SIZE, 7),
        ACTION("action", SRS_SIZE, 6),
        STAKE("stake", SRS_SIZE, 11),
        VOTE("vote", SRS_SIZE, 7),
    }

    /** The privacy circuits' SRS size hint (2^15: 32,769 points). */
    const val SRS_SIZE = 1 shl 15

    /**
     * The bundled SRS (audit 3): the first 32,769 G1 points of Aztec's
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

    /** Last prove times in ms, for the settings screen and the device test. */
    @Volatile var lastActionMs: Long = 0; private set
    @Volatile var lastStakeMs: Long = 0; private set
    @Volatile var lastMembershipMs: Long = 0; private set
    @Volatile var lastVoteMs: Long = 0; private set

    @Synchronized
    private fun load(context: Context, k: Kind): Loaded = loaded.getOrPut(k) {
        val json = context.assets.open("circuits/${k.file}.json").bufferedReader().use { it.readText() }
        val c = NoirProver.loadCircuit(json, k.srsSize)
        c.setupSrs(srsPath(context, k.srsSize))
        Loaded(c, c.getVerificationKey())
    }

    fun proveAction(context: Context, w: ActionWitness): ByteArray {
        w.check()
        val t0 = SystemClock.elapsedRealtime()
        return prove(context, Kind.ACTION, w.noirInputs(), w.publicInputs()).also {
            lastActionMs = SystemClock.elapsedRealtime() - t0
            Log.i(TAG, "action proved in ${lastActionMs}ms")
        }
    }

    fun proveStake(context: Context, w: StakeWitness): ByteArray {
        w.check()
        val t0 = SystemClock.elapsedRealtime()
        return prove(context, Kind.STAKE, w.noirInputs(), w.publicInputs()).also {
            lastStakeMs = SystemClock.elapsedRealtime() - t0
            Log.i(TAG, "stake proved in ${lastStakeMs}ms")
        }
    }

    fun proveMembership(context: Context, w: MembershipWitness): ByteArray {
        w.check()
        val t0 = SystemClock.elapsedRealtime()
        return prove(context, Kind.MEMBERSHIP, w.noirInputs(), w.publicInputs()).also {
            lastMembershipMs = SystemClock.elapsedRealtime() - t0
            Log.i(TAG, "membership proved in ${lastMembershipMs}ms")
        }
    }

    fun proveVote(context: Context, w: VoteWitness): ByteArray {
        w.check()
        val t0 = SystemClock.elapsedRealtime()
        return prove(context, Kind.VOTE, w.noirInputs(), w.publicInputs()).also {
            lastVoteMs = SystemClock.elapsedRealtime() - t0
            Log.i(TAG, "vote proved in ${lastVoteMs}ms")
        }
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
