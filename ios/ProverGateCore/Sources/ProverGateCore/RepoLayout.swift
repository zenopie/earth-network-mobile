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

    public struct Paths {
        public let circuit: URL
        public let witness: URL
        public let androidProof: URL
        public let androidVk: URL
        public let artifactDir: URL

        public init(root: URL) {
            circuit = root.appendingPathComponent("android/app/src/main/assets/circuits/lean_poa.json")
            witness = root.appendingPathComponent("android/app/src/androidTest/assets/lean_inputs.json")
            androidProof = root.appendingPathComponent("ios/ProverGate/Fixtures/android/lean_device_proof.hex")
            androidVk = root.appendingPathComponent("ios/ProverGate/Fixtures/android/lean_device_vk.hex")
            artifactDir = root.appendingPathComponent("ios/ProverGate/.artifacts")
        }
    }
}
