import Foundation
import Swoir
import Swoirenberg
import ProverGateCore

/// Swift counterpart of android/.../wallet/passport/{NoirProver,PassportProver}.kt.
///
/// Generates the client-side proof-of-personhood proof on device: a Barretenberg
/// **UltraHonk** proof (bb v5.0.0, poseidon2 flavor) of the lean_poa circuit,
/// plus its public inputs, for the earth chain's caretaker verifier
/// (zk/ultrahonk).
///
/// This type deliberately stops at the prover boundary. Building the witness from
/// a scanned passport (`PassportInputs.kt`, ASN.1 over EF.SOD) is a separate port;
/// isolating the two is what lets the gate test compare against Android using a
/// witness both sides read verbatim.
public enum LeanPoaProver {

    /// Public-input positions in the lean_poa circuit. Public signals are
    /// [current_date, nullifier, dsc_key]: current_date is the only declared
    /// public input, and bb appends the circuit's return values.
    public static let nullifierIndex = 1
    public static let numPublicInputs = 3

    /// SRS size hint. Must cover the circuit's domain (next power of two >= gate
    /// count); lean_poa is ~130k gates -> 2^17, provisioned to 2^18 to be safe.
    /// Matches SRS_SIZE in PassportProver.kt.
    public static let srsSize: UInt32 = 1 << 18

    /// bb proof flavor. Matches the `"ultra_honk"` the Android side passes and
    /// the flavor the chain verifier is built for.
    public static let proofType = "ultra_honk"

    public struct Result {
        public let proof: Data
        public let publicSignals: [String]
        public let nullifierHex: String
        /// The full bb output, prefix and public inputs included, before splitting.
        public let rawProof: Data
        public let verificationKey: Data
    }

    public enum Failure: Error, CustomStringConvertible {
        case proofTooShort(got: Int, need: Int)

        public var description: String {
            switch self {
            case .proofTooShort(let got, let need):
                return "proof too short for \(numPublicInputs) public inputs: \(got) bytes, need >= \(need)"
            }
        }
    }

    /// Loads a compiled Noir circuit (the JSON emitted by `nargo compile`) and
    /// provisions its SRS. `setupSrs` downloads from Aztec on first run and
    /// caches, so the first call needs network.
    public static func loadCircuit(manifest: Data, srsPath: String? = nil) throws -> Circuit {
        let swoir = Swoir(Swoirenberg.self)
        let circuit = try swoir.createCircuit(manifest: manifest, size: srsSize)
        try circuit.setupSrs(srs_path: srsPath)
        return circuit
    }

    /// Proves the circuit over an already-built witness, then splits the result
    /// into the chain verifier's (body, public-signals) form.
    public static func prove(circuit: Circuit, inputs: [String: Any]) throws -> Result {
        let vk = try circuit.getVerificationKey(proof_type: proofType)
        let raw = try circuit.prove(inputs, proof_type: proofType, vkey: vk)
        let (body, signals) = try splitProof(raw)
        return Result(
            proof: body,
            publicSignals: signals,
            nullifierHex: "0x" + hexFromDecimal(signals[nullifierIndex]),
            rawProof: raw,
            verificationKey: vk)
    }

    /// Splits bb's flattened proof into the chain verifier's form: a leading
    /// 4-byte field-count prefix, then `numPublicInputs` 32-byte public inputs,
    /// then the proof body. The chain wants (body, public-signals-as-decimals).
    ///
    /// Byte-for-byte the same layout PassportProver.splitProof assumes on
    /// Android; the gate test asserts that rather than trusting it.
    public static func splitProof(_ raw: Data) throws -> (body: Data, signals: [String]) {
        let offset = 4 // 4-byte field-count prefix
        let pubBytes = numPublicInputs * 32
        guard raw.count >= offset + pubBytes else {
            throw Failure.proofTooShort(got: raw.count, need: offset + pubBytes)
        }
        let base = raw.startIndex
        var signals: [String] = []
        signals.reserveCapacity(numPublicInputs)
        for i in 0..<numPublicInputs {
            let start = base + offset + i * 32
            signals.append(decimalFromBigEndian(raw[start..<(start + 32)]))
        }
        let body = Data(raw[(base + offset + pubBytes)...])
        return (body, signals)
    }
}
