import Foundation
import Swoir
import ProverGateCore

public struct GateCheck {
    public enum Outcome { case passed, failed, skipped, informational }
    public let name: String
    public let outcome: Outcome
    public let detail: String
}

public struct GateReport {
    public var checks: [GateCheck] = []
    public var passed: Bool { !checks.contains { $0.outcome == .failed } }

    mutating func record(_ name: String, _ outcome: GateCheck.Outcome, _ detail: String) {
        checks.append(GateCheck(name: name, outcome: outcome, detail: detail))
    }
}

/// The passport gate: does Barretenberg on Apple platforms prove every
/// register-circuit variant, from the witness its shared fixture holds, into
/// a proof the earth-1 chain will accept?
///
/// Lives in the library rather than in a test case so it can run from a plain
/// executable. The gate must be runnable with only the Command Line Tools
/// installed — XCTest needs full Xcode, and requiring a 10GB download to answer
/// the go/no-go question would defeat the point of asking it first.
public enum Gate {

    /// A variant as the manifest (passport_variants.json) lists it.
    public struct Variant {
        public let id: String
        public let bundled: Bool
        public let sha256: String
        public let log2CircuitSize: Int
    }

    public static func variants(paths: RepoLayout.Paths) throws -> [Variant] {
        let json = try JSONSerialization.jsonObject(with: Data(contentsOf: paths.manifest)) as? [String: Any]
        let list = json?["variants"] as? [[String: Any]] ?? []
        return list.compactMap { v in
            guard let id = v["id"] as? String, let bundled = v["bundled"] as? Bool, let sha = v["sha256"] as? String,
                  let log2 = v["log2_circuit_size"] as? Int else { return nil }
            return Variant(id: id, bundled: bundled, sha256: sha, log2CircuitSize: log2)
        }
    }

    /// Every variant, largest first: barretenberg sizes its SRS once per
    /// process, so the first circuit set up must be the largest.
    public static func runAll(paths: RepoLayout.Paths, only: String? = nil) throws -> [(String, GateReport)] {
        let all = try variants(paths: paths).filter { only == nil || $0.id == only }
            .sorted { $0.log2CircuitSize > $1.log2CircuitSize }
        return try all.map { ($0.id, try run(paths: paths, variant: $0)) }
    }

    public static func run(paths: RepoLayout.Paths, variant: Variant, compareWithAndroid: Bool = true) throws -> GateReport {
        var report = GateReport()

        let manifest: Data
        if variant.bundled {
            manifest = try Data(contentsOf: paths.circuits.appendingPathComponent("\(variant.id).json"))
        } else if let gz = paths.downloadable(variant.id), let json = Gzip.inflate(try Data(contentsOf: gz)) {
            manifest = json
        } else {
            report.record("circuit", .failed, "\(variant.id) is neither bundled nor in a backend checkout beside this one")
            return report
        }
        report.record("circuit is the pinned one", PassportCircuits.sha256(manifest) == variant.sha256 ? .passed : .failed,
                      variant.bundled ? "bundled" : "as the backend serves it")

        let fixture = try JSONSerialization.jsonObject(
            with: Data(contentsOf: paths.fixtures.appendingPathComponent("\(variant.id)/expected.json"))) as? [String: Any]
        guard let witness = fixture?["witness"] as? [String: Any] else {
            report.record("fixture", .failed, "no witness for \(variant.id)")
            return report
        }

        let circuit = try LeanPoaProver.loadCircuit(manifest: manifest, size: nil)
        let started = Date()
        let result = try LeanPoaProver.prove(circuit: circuit, inputs: witness)
        let elapsed = Date().timeIntervalSince(started)

        report.record("proof generated", result.rawProof.isEmpty ? .failed : .passed,
                      "\(result.proof.count)-byte body in \(String(format: "%.1f", elapsed))s")
        report.record("proof size", result.proof.count == PrivacyCircuitProver.proofBytes ? .passed : .failed,
                      "\(result.proof.count) bytes")

        // Against the circuit's own ABI, not against `numPublicInputs`. Checking
        // the constant against itself is what let a06180a through: making
        // `address` a public input took every variant from three public inputs
        // to four, and this check passed unchanged while splitProof kept the old
        // framing -- so the dsc_key stayed glued to the front of the proof body
        // and the chain rejected every registration with "dsc key index 3 out of
        // range". The ABI is the ground truth: bb flattens the public parameters
        // first, then the return values.
        let declared = try Self.declaredPublicInputCount(manifest: manifest)
        let countAgrees = result.publicSignals.count == declared
            && declared == LeanPoaProver.numPublicInputs
        report.record("public input count", countAgrees ? .passed : .failed,
                      "\(result.publicSignals.count) signals, circuit declares \(declared), "
                          + "splitProof assumes \(LeanPoaProver.numPublicInputs)")

        // splitProof's framing (4-byte prefix, then 4x32-byte public inputs) is
        // asserted rather than assumed: current_date is a known input, so a wrong
        // framing shows up as a signal that does not round-trip.
        let expectedDate = (witness["current_date"] as? String).map { decimalFromHex($0) }
        report.record("proof framing",
                      result.publicSignals.first == expectedDate ? .passed : .failed,
                      "signal[0]=\(result.publicSignals.first ?? "nil"), current_date=\(expectedDate ?? "nil")")
        report.record("nullifier", result.publicSignals.count > 2 && result.publicSignals[2] == fixture?["nullifier"] as? String
                      ? .passed : .failed, "the fixture's")
        report.record("dsc_key", result.publicSignals.count > 3 && result.publicSignals[3] == fixture?["dsc_key"] as? String
                      ? .passed : .failed, "the chain's commitment to the fixture's DSC")

        let selfVerified = try circuit.verify(result.rawProof,
                                              vkey: result.verificationKey,
                                              proof_type: LeanPoaProver.proofType)
        report.record("verifies against own VK", selfVerified ? .passed : .failed,
                      selfVerified ? "ok" : "bb rejected its own proof")

        try writeArtifacts(result, to: paths.artifactDir.appendingPathComponent(variant.id))

        // The chain's genesis key: a proof this prover makes is one the chain
        // verifies only if the keys are byte for byte equal.
        if let genesis = RepoLayout.genesisVK(root: paths.root, algorithm: variant.id) {
            report.record("VK is the genesis VK", result.verificationKey == genesis ? .passed : .failed,
                          result.verificationKey == genesis ? "\(genesis.count) bytes identical"
                                                            : "differs from verifying-keys/\(variant.id).vk.b64")
        } else {
            report.record("VK is the genesis VK", .skipped, "no chain checkout beside this one")
        }

        if compareWithAndroid {
            try compare(result: result, circuit: circuit, paths: paths, variant: variant.id, into: &report)
        }
        return report
    }

    private static func compare(result: LeanPoaProver.Result,
                                circuit: Circuit,
                                paths: RepoLayout.Paths,
                                variant: String,
                                into report: inout GateReport) throws {
        let fm = FileManager.default
        guard fm.fileExists(atPath: paths.androidVk(variant).path),
              fm.fileExists(atPath: paths.androidProof(variant).path) else {
            report.record("Android comparison", .skipped,
                          "no device artifacts in ios/ProverGate/Fixtures/android — see ios/README.md")
            return
        }

        // The VK is derived from the circuit alone, so it is deterministic across
        // platforms whatever the prover does with randomness. A mismatch here is
        // the unambiguous signature of a bb version difference.
        let androidVk = try Data(hexAt: paths.androidVk(variant))
        report.record("VK matches Android", result.verificationKey == androidVk ? .passed : .failed,
                      result.verificationKey == androidVk
                        ? "\(androidVk.count) bytes identical"
                        : "differ — the platforms are not running the same bb build")

        let crossVerified = try circuit.verify(result.rawProof, vkey: androidVk,
                                               proof_type: LeanPoaProver.proofType)
        report.record("Swift proof under Android VK", crossVerified ? .passed : .failed,
                      crossVerified ? "ok" : "rejected")

        let androidProof = try Data(hexAt: paths.androidProof(variant))
        let reverseVerified = try circuit.verify(androidProof, vkey: result.verificationKey,
                                                 proof_type: LeanPoaProver.proofType)
        report.record("Android proof under Swift VK", reverseVerified ? .passed : .failed,
                      reverseVerified ? "ok" : "rejected")

        let (androidBody, androidSignals) = try LeanPoaProver.splitProof(androidProof)
        report.record("public signals match", result.publicSignals == androidSignals ? .passed : .failed,
                      result.publicSignals == androidSignals ? "ok" : "differ")

        // Byte-equality is only meaningful if proving is deterministic, which
        // depends on whether this bb flavor blinds. Reported, not asserted, so a
        // randomised prover does not fail a check it was never going to pass —
        // the cross-verifications above are the operative result either way.
        report.record("proof bytes identical", .informational,
                      result.proof == androidBody
                        ? "yes — proving appears deterministic"
                        : "no — expected if bb blinds; judge by verification, not bytes")
    }

    private static func writeArtifacts(_ result: LeanPoaProver.Result, to dir: URL) throws {
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        // Both forms: the raw bb output, and the (body, signals) split the chain
        // verifier actually consumes — barretenberg-go's ParseProof takes the
        // body and the public inputs as decimal strings, separately.
        try result.rawProof.hexString.write(to: dir.appendingPathComponent("swift_proof.hex"),
                                            atomically: true, encoding: .utf8)
        try result.proof.hexString.write(to: dir.appendingPathComponent("swift_proof_body.hex"),
                                         atomically: true, encoding: .utf8)
        try result.verificationKey.hexString.write(to: dir.appendingPathComponent("swift_vk.hex"),
                                                   atomically: true, encoding: .utf8)
        try result.publicSignals.joined(separator: "\n")
            .write(to: dir.appendingPathComponent("swift_public_signals.txt"),
                   atomically: true, encoding: .utf8)
    }

    /// How many public inputs the compiled circuit declares — its public
    /// parameters, then its return values, in the order bb flattens them into
    /// the proof. Read from the manifest so the gate cannot agree with a stale
    /// constant.
    private static func declaredPublicInputCount(manifest: Data) throws -> Int {
        let json = try JSONSerialization.jsonObject(with: manifest) as? [String: Any]
        let abi = json?["abi"] as? [String: Any]
        let parameters = abi?["parameters"] as? [[String: Any]] ?? []
        let publicParameters = parameters.filter { $0["visibility"] as? String == "public" }.count

        // A tuple return contributes one field each; any other return type
        // contributes one; no return type contributes none.
        guard let returnType = abi?["return_type"] as? [String: Any],
              let type = returnType["abi_type"] as? [String: Any]
        else { return publicParameters }
        if type["kind"] as? String == "tuple", let fields = type["fields"] as? [Any] {
            return publicParameters + fields.count
        }
        return publicParameters + 1
    }
}
