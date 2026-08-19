package network.erth.wallet.wallet.passport

import android.content.Context
import java.math.BigInteger

/**
 * PassportProver
 *
 * Generates the client-side proof-of-personhood proof from a scanned passport,
 * on-device, for the earth chain's caretaker verifier (zk/ultrahonk): a
 * Barretenberg **UltraHonk** proof (bb v5.0.0, poseidon2 flavor) of the lean_poa
 * circuit, plus its public inputs.
 *
 * Pipeline: [PassportInputs.leanInputs] (BouncyCastle extraction) -> [NoirProver]
 * (on-device Barretenberg) -> split the returned proof into the chain's
 * (proof, publicSignals) form.
 *
 * The lean_poa circuit's public inputs are, in order:
 *   [0] current_date, [1] nullifier, [2] dsc_key (the DSC commitment).
 *
 * VERSION LOCKSTEP: on-device proofs verify on-chain only when noir_android's
 * bundled bb matches the chain verifier's bb (v5.0.0). A nightly bb produces
 * proofs the v5.0.0 chain lib rejects for large circuits — keep them in step.
 */
object PassportProver {

    /** Public-input positions in the lean_poa circuit. */
    private const val ROOT_INDEX = 0
    private const val CURRENT_DATE_INDEX = 1
    // Public signals are [current_date, nullifier, dsc_key]: current_date is the
    // only declared public input, and bb appends the circuit's return values.
    private const val NULLIFIER_INDEX = 1
    private const val NUM_PUBLIC_INPUTS = 3

    /**
     * SRS size hint for [NoirProver.loadCircuit]. Must cover the circuit's domain
     * (next power of two >= gate count); lean_poa is ~130k gates -> 2^18.
     */
    private const val SRS_SIZE = 1 shl 18

    data class Result(
        val proof: ByteArray,
        val publicSignals: List<String>,
        val signatureAlgorithm: String,
        val nullifierHex: String,
    )

    /**
     * Proves proof-of-personhood from the scanned passport.
     *
     * @param context to read the compiled circuit from assets.
     * @param dg1 raw EF.DG1 bytes.
     * @param sodBytes raw EF.SOD bytes.
     * @param currentDateYymmdd today as YYMMDD (the chain pins it to block time).
     * @param registry the DSC's certificate-registry inclusion proof (from the
     *   registry service; its root must equal the chain's params.dsc_root).
     */
    @JvmStatic
    fun prove(
        context: Context,
        dg1: ByteArray,
        sodBytes: ByteArray,
        currentDateYymmdd: Int,
    ): Result {
        // Build inputs + select the circuit matching the passport's DSC algorithm
        // (lean_poa / lean_poa_rsa2048 / lean_poa_rsa4096).
        val inputs = PassportInputs.buildInputs(dg1, sodBytes, currentDateYymmdd)
        val circuitJson = context.assets.open("circuits/${inputs.algorithm}.json")
            .bufferedReader().use { it.readText() }
        val circuit = NoirProver.loadCircuit(circuitJson, SRS_SIZE)
        circuit.setupSrs()

        val vk = circuit.getVerificationKey()
        val proofHex = NoirProver.prove(circuit, inputs.map, vk)

        val (proofBytes, signals) = splitProof(proofHex)
        val nullifier = signals[NULLIFIER_INDEX]
        return Result(
            proof = proofBytes,
            publicSignals = signals,
            signatureAlgorithm = inputs.algorithm,
            nullifierHex = "0x" + BigInteger(nullifier).toString(16),
        )
    }

    /**
     * Splits noir_android's flattened proof into the chain verifier's form: a
     * leading 4-byte length prefix, then NUM_PUBLIC_INPUTS 32-byte public inputs,
     * then the proof body. The chain wants (body, public-signals-as-decimals).
     */
    private fun splitProof(proofHex: String): Pair<ByteArray, List<String>> {
        val all = hexToBytes(proofHex)
        val offset = 4 // 4-byte field-count prefix
        val pubBytes = NUM_PUBLIC_INPUTS * 32
        require(all.size >= offset + pubBytes) { "proof too short for $NUM_PUBLIC_INPUTS public inputs" }
        val signals = ArrayList<String>(NUM_PUBLIC_INPUTS)
        for (i in 0 until NUM_PUBLIC_INPUTS) {
            val start = offset + i * 32
            signals.add(BigInteger(1, all.copyOfRange(start, start + 32)).toString())
        }
        val body = all.copyOfRange(offset + pubBytes, all.size)
        return body to signals
    }

    private fun hexToBytes(s: String): ByteArray {
        val h = if (s.startsWith("0x")) s.substring(2) else s
        val out = ByteArray(h.length / 2)
        var i = 0
        while (i < h.length) {
            out[i / 2] = ((Character.digit(h[i], 16) shl 4) + Character.digit(h[i + 1], 16)).toByte()
            i += 2
        }
        return out
    }
}
