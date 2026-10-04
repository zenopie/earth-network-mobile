import Foundation

/// The passport register circuits: one per DSC key type, signature scheme and
/// hash profile (circuits/variants.json, bundled as the Android asset
/// circuits/passport_variants.json, which the app references; see
/// PASSPORT_COVERAGE.md). Ports `passport/PassportVariants.kt`.
///
/// A variant at or below the 2^18 tier ships in the app; the others are
/// fetched on demand from `downloadBase` and must hash to `sha256`. The SRS
/// each tier needs is fetched the same way, pinned by `srs`.
public struct PassportVariants: Decodable {

    public struct Variant: Decodable, Equatable {
        public let id: String
        /// rsa2048 | rsa3072 | rsa4096 | p224 | p256 | p384 | p521 | bp224 | bp256 | bp384 | bp512
        public let key: String
        /// pkcs1 | pss | ecdsa
        public let scheme: String
        /// (data groups, eContent, signature)
        public let hashes: [String]
        public let eContentMax: Int
        public let signedAttrsMax: Int
        public let log2CircuitSize: Int
        public let bundled: Bool
        /// sha256 of the compiled circuit JSON, bundled or served (inflated).
        public let sha256: String

        enum CodingKeys: String, CodingKey {
            case id, key, scheme, hashes, bundled, sha256
            case eContentMax = "e_content_max"
            case signedAttrsMax = "signed_attrs_max"
            case log2CircuitSize = "log2_circuit_size"
        }
    }

    public struct Srs: Decodable, Equatable {
        public let points: UInt64
        public let sha256: String
    }

    public let variants: [Variant]
    public let downloadBase: String
    /// By tier: "18", "19", "20".
    public let srs: [String: Srs]

    enum CodingKeys: String, CodingKey {
        case variants, srs
        case downloadBase = "download_base"
    }

    public init(json: Data) throws {
        self = try JSONDecoder().decode(PassportVariants.self, from: json)
    }

    public func variant(id: String) -> Variant? { variants.first { $0.id == id } }

    /// The smallest SRS tier covering a 2^log2 circuit.
    public func srsTier(for log2: Int) -> (log2: Int, srs: Srs)? {
        srs.compactMap { k, v in Int(k).map { ($0, v) } }.filter { $0.0 >= log2 }.min { $0.0 < $1.0 }
    }

    /// The manifest the app bundles, installed at launch; the domain layer
    /// cannot reach the bundle itself.
    nonisolated(unsafe) public static var installed: PassportVariants?
}
