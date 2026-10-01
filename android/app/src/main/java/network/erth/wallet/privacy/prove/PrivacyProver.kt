package network.erth.wallet.privacy.prove

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.noirandroid.lib.Circuit
import network.erth.wallet.passport.NoirProver
import network.erth.wallet.privacy.zk.Fr

/**
 * On-device proofs of the two privacy circuits (circuits/membership,
 * circuits/transfer), through the same noir_android build as
 * [network.erth.wallet.passport.PassportProver] — bb v5.0.0, in lockstep
 * with the chain's verifier; never float that pin.
 *
 * The chain never takes public inputs from the tx: it recomputes them from
 * the msg (x/shielded Transfer.PublicInputs, personhood
 * MembershipPublicInputs) and verifies the proof body against them. So the
 * public inputs noir_android returns ahead of the body are split off and
 * checked against the witness's own, and only the body is sent.
 */
object PrivacyProver {
    private const val TAG = "PrivacyProver"

    /** Expected body size of a bb v5.0.0 UltraHonk proof, whatever the circuit. */
    const val PROOF_BYTES = 14_656

    private enum class Kind(val file: String, val srsSize: Int, val publicInputs: Int) {
        // Twice the next power of two above the gate count (5,645 and
        // 12,238), with the same headroom PassportProver gives lean_poa.
        MEMBERSHIP("membership", 1 shl 14, 7),
        TRANSFER("transfer", 1 shl 15, 11),
    }

    private class Loaded(val circuit: Circuit, val vk: String)

    private val loaded = HashMap<Kind, Loaded>()

    /** Last prove times in ms, for the settings screen and the device test. */
    @Volatile var lastTransferMs: Long = 0; private set
    @Volatile var lastMembershipMs: Long = 0; private set

    @Synchronized
    private fun load(context: Context, k: Kind): Loaded = loaded.getOrPut(k) {
        val json = context.assets.open("circuits/${k.file}.json").bufferedReader().use { it.readText() }
        val c = NoirProver.loadCircuit(json, k.srsSize)
        c.setupSrs()
        Loaded(c, c.getVerificationKey())
    }

    fun proveTransfer(context: Context, w: TransferWitness): ByteArray {
        val t0 = SystemClock.elapsedRealtime()
        return prove(context, Kind.TRANSFER, w.noirInputs(), w.publicInputs()).also {
            lastTransferMs = SystemClock.elapsedRealtime() - t0
            Log.i(TAG, "transfer proved in ${lastTransferMs}ms")
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
