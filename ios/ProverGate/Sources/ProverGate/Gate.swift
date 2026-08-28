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

/// The Phase 1 gate: does Barretenberg on Apple platforms produce a lean_poa
/// proof the earth-1 chain will accept?
///
/// Lives in the library rather than in a test case so it can run from a plain
/// executable. The gate must be runnable with only the Command Line Tools
/// installed — XCTest needs full Xcode, and requiring a 10GB download to answer
/// the go/no-go question would defeat the point of asking it first.
public enum Gate {

    public static func run(paths: RepoLayout.Paths, compareWithAndroid: Bool = true) throws -> GateReport {
        var report = GateReport()

        let manifest = try Data(contentsOf: paths.circuit)
        let witness = try NoirWitness.decode(Data(contentsOf: paths.witness))

        report.record("witness shape", witness.count == 15 ? .passed : .failed,
                      "\(witness.count) named inputs (Android feeds bb 15)")

        let circuit = try LeanPoaProver.loadCircuit(manifest: manifest)
        let started = Date()
        let result = try LeanPoaProver.prove(circuit: circuit, inputs: witness)
        let elapsed = Date().timeIntervalSince(started)

        report.record("proof generated", result.rawProof.isEmpty ? .failed : .passed,
                      "\(result.rawProof.count) bytes in \(String(format: "%.1f", elapsed))s")

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

        let selfVerified = try circuit.verify(result.rawProof,
                                              vkey: result.verificationKey,
                                              proof_type: LeanPoaProver.proofType)
        report.record("verifies against own VK", selfVerified ? .passed : .failed,
                      selfVerified ? "ok" : "bb rejected its own proof")

        try writeArtifacts(result, to: paths.artifactDir)
        report.record("artifacts written", .informational, paths.artifactDir.path)
        report.record("nullifier", .informational, result.nullifierHex)

        if compareWithAndroid {
            try compare(result: result, circuit: circuit, paths: paths, into: &report)
        }
        return report
    }

    private static func compare(result: LeanPoaProver.Result,
                                circuit: Circuit,
                                paths: RepoLayout.Paths,
                                into report: inout GateReport) throws {
        let fm = FileManager.default
        guard fm.fileExists(atPath: paths.androidVk.path),
              fm.fileExists(atPath: paths.androidProof.path) else {
            report.record("Android comparison", .skipped,
                          "no reference artifacts in ios/ProverGate/Fixtures/android — see ios/README.md")
            return
        }

        // The VK is derived from the circuit alone, so it is deterministic across
        // platforms whatever the prover does with randomness. A mismatch here is
        // the unambiguous signature of a bb version difference.
        let androidVk = try Data(hexAt: paths.androidVk)
        report.record("VK matches Android", result.verificationKey == androidVk ? .passed : .failed,
                      result.verificationKey == androidVk
                        ? "\(androidVk.count) bytes identical"
                        : "differ — the platforms are not running the same bb build")

        let crossVerified = try circuit.verify(result.rawProof, vkey: androidVk,
                                               proof_type: LeanPoaProver.proofType)
        report.record("Swift proof under Android VK", crossVerified ? .passed : .failed,
                      crossVerified ? "ok" : "rejected")

        let androidProof = try Data(hexAt: paths.androidProof)
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
