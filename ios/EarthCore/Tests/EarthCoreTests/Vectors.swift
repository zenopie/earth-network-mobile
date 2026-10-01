import BigInt
import CryptoKit
import Foundation
@testable import EarthCore

/// Golden vectors generated from the chain's own Go code by
/// tools/privacyvectors/gen.sh — the same files Android's tests read.
enum Vectors {
    static func resource(_ path: String) -> Data {
        let url = Bundle.module.url(forResource: "privacy", withExtension: nil)!.appendingPathComponent(path)
        return try! Data(contentsOf: url)
    }

    static let json: [String: Any] = try! JSONSerialization.jsonObject(with: resource("vectors.json")) as! [String: Any]

    static func obj(_ k: String) -> [String: Any] { json[k] as! [String: Any] }

    static func fr(_ hex: String) -> Fr { try! Fr(hex: hex) }

    static func hex(_ b: Data) -> String { b.map { String(format: "%02x", $0) }.joined() }

    static func unhex(_ s: String) -> Data { Data(hexString: s)! }

    /// The generator's fe(i) = Poseidon2([i]).
    static func fe(_ i: UInt64) -> Fr { Poseidon2.hash([Fr(i)]) }
}
