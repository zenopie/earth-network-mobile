import Foundation

/// A shielded address: what a sender needs to pay a note to its owner,
/// owner_pk (the note's pc commits to it) and ek_pub (the note's ciphertext is
/// encrypted to it). Ports `privacy/keys/ShieldedAddress.kt`.
///
/// Canonical encoding (shared with chain zk/privacy, Android and the web app):
///
///     bech32m( hrp "erthz", 8-to-5-bit( 0x01 || owner_pk (32, BE, < p) || ek_pub (32) ) )
///
/// 65 payload bytes, so 116 characters, past BIP-173's 90-character cap (the
/// cap exists for error-location guarantees; the checksum still detects
/// errors, as with Zcash unified addresses). Unknown versions, any other
/// length and a non-canonical owner_pk are refused.
public struct ShieldedAddress: Hashable, Sendable, CustomStringConvertible {
    public static let hrp = "erthz"
    public static let version: UInt8 = 1
    public static let payloadBytes = 65

    public let ownerPK: Fr
    public let ekPub: Data

    public enum Error: Swift.Error, Equatable {
        case badEkPub
        case wrongHRP
        case badLength
        case unknownVersion
        case malformed(String)
    }

    public init(ownerPK: Fr, ekPub: Data) throws {
        guard ekPub.count == 32 else { throw Error.badEkPub }
        self.ownerPK = ownerPK
        self.ekPub = ekPub
    }

    /// The 65 payload bytes.
    public var payload: Data { Data([Self.version]) + ownerPK.bytes + ekPub }

    public func encode() -> String {
        Bech32m.encode(hrp: Self.hrp, data: try! Bech32m.convertBits([UInt8](payload), from: 8, to: 5, pad: true))
    }

    public var description: String { encode() }

    public static func decode(_ s: String) throws -> ShieldedAddress {
        let (hrp, data) = try Bech32m.decode(s.trimmingCharacters(in: .whitespacesAndNewlines))
        guard hrp == Self.hrp else { throw Error.wrongHRP }
        let payload = try Bech32m.convertBits(data, from: 5, to: 8, pad: false)
        guard payload.count == payloadBytes else { throw Error.badLength }
        guard payload[0] == version else { throw Error.unknownVersion }
        return try ShieldedAddress(ownerPK: Fr(bytes: Data(payload[1 ..< 33])), ekPub: Data(payload[33 ..< 65]))
    }

    public static func isShielded(_ s: String) -> Bool { (try? decode(s)) != nil }
}

/// BIP-350 bech32m, lowercase, without BIP-173's 90-character cap.
enum Bech32m {
    private static let charset = Array("qpzry9x8gf2tvdw0s3jn54khce6mua7l")
    private static let const: UInt32 = 0x2bc8_30a3
    private static let gen: [UInt32] = [0x3b6a_57b2, 0x2650_8e6d, 0x1ea1_19fa, 0x3d42_33dd, 0x2a14_62b3]

    private static func polymod(_ values: [UInt8]) -> UInt32 {
        var chk: UInt32 = 1
        for v in values {
            let top = chk >> 25
            chk = (chk & 0x1ff_ffff) << 5 ^ UInt32(v)
            for i in 0 ..< 5 where (top >> UInt32(i)) & 1 == 1 { chk ^= gen[i] }
        }
        return chk
    }

    private static func hrpExpand(_ hrp: String) -> [UInt8] {
        let b = Array(hrp.utf8)
        return b.map { $0 >> 5 } + [0] + b.map { $0 & 31 }
    }

    static func encode(hrp: String, data: [UInt8]) -> String {
        let mod = polymod(hrpExpand(hrp) + data + [0, 0, 0, 0, 0, 0]) ^ const
        var s = hrp + "1"
        for d in data { s.append(charset[Int(d)]) }
        for i in 0 ..< 6 { s.append(charset[Int((mod >> UInt32(5 * (5 - i))) & 31)]) }
        return s
    }

    static func decode(_ s: String) throws -> (String, [UInt8]) {
        guard s == s.lowercased() || s == s.uppercased() else { throw ShieldedAddress.Error.malformed("mixed case") }
        let str = Array(s.lowercased())
        guard let pos = str.lastIndex(of: "1"), pos >= 1, pos + 7 <= str.count else {
            throw ShieldedAddress.Error.malformed("malformed bech32m")
        }
        let hrp = String(str[0 ..< pos])
        var d: [UInt8] = []
        for c in str[(pos + 1)...] {
            guard let i = charset.firstIndex(of: c) else { throw ShieldedAddress.Error.malformed("bad character") }
            d.append(UInt8(i))
        }
        guard polymod(hrpExpand(hrp) + d) == const else { throw ShieldedAddress.Error.malformed("bad checksum") }
        return (hrp, Array(d.dropLast(6)))
    }

    static func convertBits(_ data: [UInt8], from: Int, to: Int, pad: Bool) throws -> [UInt8] {
        var acc = 0, bits = 0
        var out: [UInt8] = []
        let maxv = (1 << to) - 1
        for b in data {
            let v = Int(b)
            guard v >> from == 0 else { throw ShieldedAddress.Error.malformed("invalid data") }
            acc = ((acc << from) | v) & 0xffff
            bits += from
            while bits >= to {
                bits -= to
                out.append(UInt8((acc >> bits) & maxv))
            }
        }
        if pad {
            if bits > 0 { out.append(UInt8((acc << (to - bits)) & maxv)) }
        } else {
            guard bits < from, (acc << (to - bits)) & maxv == 0 else { throw ShieldedAddress.Error.malformed("invalid padding") }
        }
        return out
    }
}
