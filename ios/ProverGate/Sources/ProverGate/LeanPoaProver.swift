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
    /// [current_date, address, nullifier, dsc_key]: current_date and address are
    /// the declared public inputs, and bb appends the circuit's return values
    /// after them. address binds the proof to the wallet it was made for -- see
    /// circuits/lean_poa/SECURITY.md finding #9.
    ///
    /// These must match PassportProver.kt's constants and the chain's
    /// `nullifier_index` / `dsc_key_index` params. They are not free-standing:
    /// a wrong count leaves the trailing public inputs glued to the front of the
    /// proof body and short-changes public_signals, which the chain rejects with
    /// "dsc key index 3 out of range: proof public inputs do not match".
    public static let currentDateIndex = 0
    public static let addressIndex = 1
    public static let nullifierIndex = 2
    public static let numPublicInputs = 4

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
    /// provisions its SRS.
    ///
    /// `size` is the SRS provisioning hint. The default is `srsSize`, which is
    /// what the gate proves lean_poa with and what Android passes. Pass `nil`
    /// to size the SRS from the circuit's own gate count instead — which is
    /// what the app does, because it ships seven circuits of very different
    /// sizes and barretenberg **only honours the first SRS initialization of a
    /// process**. A hint that is too small for the circuit a passport selects
    /// cannot be corrected afterwards; deriving it from the bytecode cannot be
    /// wrong.
    ///
    /// With `srsPath` nil the SRS is fetched from Aztec — every call, there is
    /// no cache. Per process, though, not per proof: the first initialization
    /// is the only one that does any work. Do not pass a path unless the file
    /// is there. noir_rs reads it with `fs::read(..).unwrap()`, so a missing
    /// file is a Rust panic across the FFI boundary rather than a Swift error.
    public static func loadCircuit(
        manifest: Data,
        size: UInt32? = srsSize,
        srsPath: String? = nil
    ) throws -> Circuit {
        let swoir = Swoir(Swoirenberg.self)
        let circuit = try swoir.createCircuit(manifest: manifest, size: size)
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
