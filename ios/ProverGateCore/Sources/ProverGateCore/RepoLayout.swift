import Foundation

/// Locates the repo so both the CLI and the XCTest wrapper read one copy of the
/// circuit and witness — the Android copy. A duplicated fixture that drifted
/// would quietly turn a cross-platform comparison into a comparison of two
/// different problems.
public enum RepoLayout {

    public enum Failure: Error, CustomStringConvertible {
        case rootNotFound(startedAt: String)

        public var description: String {
            switch self {
            case .rootNotFound(let start):
                return "could not find the repo root walking up from \(start) (looked for a .git directory)"
            }
        }
    }

    public static func root(from start: String) throws -> URL {
        var url = URL(fileURLWithPath: start).standardizedFileURL
        // `start` may be a file; walking up from its directory is equivalent.
        while url.pathComponents.count > 1 {
            if FileManager.default.fileExists(atPath: url.appendingPathComponent(".git").path) {
                return url
            }
            url.deleteLastPathComponent()
        }
        throw Failure.rootNotFound(startedAt: start)
    }

    /// The chain's genesis verifying key for the register circuit `algorithm`
    /// (`networks/genesis/verifying-keys/<algorithm>.vk.b64`), from a chain
    /// checkout beside this one; nil when there is none. The VK derives from
    /// the circuit and the bb build alone, so equality says this prover's
    /// proofs are the ones the chain's genesis verifies.
    public static func genesisVK(root: URL, algorithm: String) -> Data? {
        for name in ["chain-orch", "chain-orchard", "chain-privacy", "earth-network-chain"] {
            let f = root.deletingLastPathComponent()
                .appendingPathComponent("\(name)/networks/genesis/verifying-keys/\(algorithm).vk.b64")
            if let b64 = try? String(contentsOf: f, encoding: .utf8) {
                return Data(base64Encoded: b64.trimmingCharacters(in: .whitespacesAndNewlines))
            }
        }
        return nil
    }

    public struct Paths {
        public let root: URL
        public let circuit: URL
        public let witness: URL
        public let androidProof: URL
        public let androidVk: URL
        public let artifactDir: URL

        public init(root: URL) {
            self.root = root
            circuit = root.appendingPathComponent("android/app/src/main/assets/circuits/lean_poa.json")
            witness = root.appendingPathComponent("android/app/src/androidTest/assets/lean_inputs.json")
            androidProof = root.appendingPathComponent("ios/ProverGate/Fixtures/android/lean_device_proof.hex")
            androidVk = root.appendingPathComponent("ios/ProverGate/Fixtures/android/lean_device_vk.hex")
            artifactDir = root.appendingPathComponent("ios/ProverGate/.artifacts")
        }
    }
}
