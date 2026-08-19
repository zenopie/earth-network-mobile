package network.erth.wallet.wallet.passport

import com.noirandroid.lib.Circuit

/**
 * NoirProver
 *
 * Thin wrapper over noir_android (com.github.madztheo:noir_android) — on-device
 * Noir/Barretenberg UltraHonk proving. This is the same proving library
 * zkPassport's app uses. Produces a bb proof + public inputs that the earth
 * chain's caretaker verifier (zk/ultrahonk) checks natively.
 *
 * VERSION ALIGNMENT: the bb version bundled in this noir_android release must
 * match the chain verifier's bb version (third_party/barretenberg-go pinned to
 * v5.0.0). If they differ, on-device proofs will not verify on-chain — keep them
 * in lockstep.
 */
object NoirProver {

    /**
     * Loads a compiled Noir circuit (the JSON emitted by `nargo compile`).
     * @param circuitJson stringified compiled-circuit manifest.
     * @param size circuit size hint (for SRS sizing).
     */
    @JvmStatic
    fun loadCircuit(circuitJson: String, size: Int): Circuit =
        Circuit.fromJsonManifest(circuitJson, size, false, 0L)

    /**
     * Generates a proof. Inputs are the circuit's named field inputs; vk is the
     * circuit's verifying key (`bb write_vk`) string. Returns the proof (with its
     * public inputs) in Barretenberg's encoding.
     */
    @JvmStatic
    fun prove(circuit: Circuit, inputs: Map<String, Any>, vk: String): String =
        circuit.prove(inputs, vk) ?: throw IllegalStateException("noir_android returned no proof")
}
