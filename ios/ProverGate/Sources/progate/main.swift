import Foundation
import ProverGate
import ProverGateCore

// Runs the Phase 1 gate and prints a report. An executable rather than only an
// XCTest case because XCTest needs full Xcode, and the gate is meant to be
// answerable before committing to any of the iOS toolchain.
//
//   cd ios/ProverGate && swift run progate
//
// Exits non-zero if any check fails, so CI can use it directly.

// Arguments: an optional path to start the repo-root search from, and an
// optional `--witness <fixture>`.
var arguments = Array(CommandLine.arguments.dropFirst())
var witnessPath: String?
if let flag = arguments.firstIndex(of: "--witness") {
    guard arguments.indices.contains(flag + 1) else {
        FileHandle.standardError.write(Data("--witness needs a path to a JSON fixture\n".utf8))
        exit(2)
    }
    witnessPath = arguments[flag + 1]
    arguments.removeSubrange(flag ... flag + 1)
}
let start = arguments.first ?? FileManager.default.currentDirectoryPath

// `--witness <path>` proves a witness built elsewhere instead of running the
// gate. It exists for Phase 3: EarthCore builds a witness from a passport's
// DG1 and EF.SOD, and nothing inside EarthCore can say whether that witness is
// the one the circuit wants. Proving it here is what says so.
if let witnessPath {
    exit(runWitness(path: witnessPath, start: start))
}

do {
    let root = try RepoLayout.root(from: start)
    let paths = RepoLayout.Paths(root: root)

    FileHandle.standardError.write(Data("running the lean_poa gate (first run downloads the SRS)…\n".utf8))
    let report = try Gate.run(paths: paths)

    let width = report.checks.map { $0.name.count }.max() ?? 0
    for check in report.checks {
        let mark: String
        switch check.outcome {
        case .passed: mark = "PASS"
        case .failed: mark = "FAIL"
        case .skipped: mark = "SKIP"
        case .informational: mark = "····"
        }
        let name = check.name.padding(toLength: width, withPad: " ", startingAt: 0)
        print("[\(mark)] \(name)  \(check.detail)")
    }

    print(report.passed
          ? "\ngate PASSED — Barretenberg on this platform proves lean_poa and the proof verifies"
          : "\ngate FAILED — see the failing checks above")
    exit(report.passed ? 0 : 1)
} catch {
    FileHandle.standardError.write(Data("gate errored: \(error)\n".utf8))
    exit(2)
}


/// Proves a witness written by `EarthCore`'s corecheck.
///
/// The fixture names the circuit variant the passport's Document Signer
/// selects, so the right compiled circuit is loaded rather than assumed.
func runWitness(path: String, start: String) -> Int32 {
    do {
        let root = try RepoLayout.root(from: start)
        let data = try Data(contentsOf: URL(fileURLWithPath: path))
        guard let fixture = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let algorithm = fixture["algorithm"] as? String,
              let witness = fixture["witness"] as? [String: Any]
        else {
            FileHandle.standardError.write(Data("\(path) is not a witness fixture\n".utf8))
            return 2
        }

        let circuitURL = root.appendingPathComponent(
            "android/app/src/main/assets/circuits/\(algorithm).json")
        print("circuit   \(algorithm)")
        print("witness   \(witness.count) inputs from \(path)")

        FileHandle.standardError.write(Data("proving (first run downloads the SRS)…\n".utf8))
        let circuit = try LeanPoaProver.loadCircuit(manifest: try Data(contentsOf: circuitURL))
        let result = try LeanPoaProver.prove(circuit: circuit, inputs: witness)

        print("proof     \(result.proof.count) bytes")
        print("signals   \(result.publicSignals)")
        print("nullifier \(result.nullifierHex)")

        // Self-verification only shows bb agrees with itself; the point of the
        // exercise is that a witness built from a passport proves at all.
        let verified = try circuit.verify(
            result.rawProof,
            vkey: result.verificationKey,
            proof_type: LeanPoaProver.proofType)
        print(verified
              ? "\nPROVED — a witness built from a passport's DG1 and EF.SOD satisfies \(algorithm)"
              : "\nFAILED — the proof does not verify")

        // Written in the shape tools/chainverify reads, under a `passport`
        // prefix so it sits beside the Phase 1 gate's own artifacts rather
        // than overwriting them.
        let artifacts = RepoLayout.Paths(root: root).artifactDir
        try? FileManager.default.createDirectory(at: artifacts, withIntermediateDirectories: true)
        func write(_ text: String, _ name: String) {
            try? Data(text.utf8).write(to: artifacts.appendingPathComponent(name))
        }
        write(result.verificationKey.map { String(format: "%02x", $0) }.joined(), "passport_vk.hex")
        write(result.proof.map { String(format: "%02x", $0) }.joined(), "passport_proof_body.hex")
        write(result.publicSignals.joined(separator: "\n"), "passport_public_signals.txt")
        print("\nartifacts \(artifacts.path)")

        return verified ? 0 : 1
    } catch {
        FileHandle.standardError.write(Data("witness proof failed: \(error)\n".utf8))
        return 2
    }
}
