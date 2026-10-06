package network.erth.wallet.passport

import android.content.Context
import java.math.BigInteger

/**
 * PassportProver
 *
 * Generates the client-side proof-of-personhood proof from a scanned passport,
 * on-device, for the earth chain's caretaker verifier (zk/ultrahonk): a
 * Barretenberg **UltraHonk** proof (bb v5.0.0, poseidon2 flavor) of the register
 * circuit, plus its public inputs.
 *
 * Pipeline: [PassportInputs.buildInputs] (variant selection + witness) ->
 * [PassportCircuits] (bundled, or fetched and hash-checked) + [PassportSrs]
 * (the setup for the circuit's size tier) -> [NoirProver] (on-device
 * Barretenberg) -> split the returned proof into the chain's (proof,
 * publicSignals) form.
 *
 * Every register circuit's public inputs are, in order:
 *   [0] current_date, [1] address, [2] nullifier, [3] dsc_key (the DSC
 *   commitment).
 *
 * VERSION LOCKSTEP: on-device proofs verify on-chain only when noir_android's
 * bundled bb matches the chain verifier's bb (v5.0.0). A nightly bb produces
 * proofs the v5.0.0 chain lib rejects for large circuits — keep them in step.
 */
object PassportProver {

    // Public signals are [current_date, address, nullifier, dsc_key]:
    // current_date and address are the declared public inputs, and bb appends
    // the circuit's return values after them. `address` carries
    // zk/privacy.RegistrationBinding(idc, pc_anml, pc_erth, affiliate), which
    // binds the proof to the registration it is broadcast in.
    private const val NUM_PUBLIC_INPUTS = 4

    data class Result(
        val proof: ByteArray,
        val publicSignals: List<String>,
        val signatureAlgorithm: String,
    )

    /**
     * The variant a passport selects, before anything slow happens: an
     * unsupported scheme or bad data is reported here, by
     * [PassportInputs.UnsupportedPassportException] or
     * [PassportInputs.PassportDataException].
     */
    fun inputs(context: Context, dg1: ByteArray, sodBytes: ByteArray, currentDateYymmdd: Int, address: String) =
        PassportInputs.buildInputs(dg1, sodBytes, currentDateYymmdd, address, PassportVariants.get(context))

    /**
     * Proves proof-of-personhood from the scanned passport.
     *
     * @param context to read the compiled circuit from assets.
     * @param dg1 raw EF.DG1 bytes.
     * @param sodBytes raw EF.SOD bytes.
     * @param currentDateYymmdd today as YYMMDD (the chain pins it to block time).
     */
    fun prove(
        context: Context,
        dg1: ByteArray,
        sodBytes: ByteArray,
        currentDateYymmdd: Int,
        /** The `address` input: the registration binding as a "0x" field. */
        address: String,
    ): Result {
        // Select the variant the passport's key, padding and hashes need, and
        // build its witness.
        val inputs = inputs(context, dg1, sodBytes, currentDateYymmdd, address)
        val variant = inputs.variant
        val circuitJson = PassportCircuits.load(context, variant)
        val srs = PassportSrs.ensure(context, variant.log2CircuitSize)
        val circuit = NoirProver.loadCircuit(circuitJson, 1 shl variant.log2CircuitSize)
        circuit.setupSrs(srs)

        val vk = circuit.getVerificationKey()
        val proofHex = NoirProver.prove(circuit, inputs.map, vk)

        val (proofBytes, signals) = splitProof(proofHex)
        return Result(
            proof = proofBytes,
            publicSignals = signals,
            signatureAlgorithm = inputs.algorithm,
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
