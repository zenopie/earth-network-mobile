import Foundation

/// Bech32 (BIP-0173), lowercase HRP only.
///
/// Unlike the Android side this decodes as well as encodes — an iOS wallet has
/// to validate a pasted recipient address before it will let a send proceed.
public enum Bech32 {

    public enum Error: Swift.Error, Equatable {
        case emptyHRP
        case valueOutOfRange
        case cannotConvertWithoutPadding
        case mixedCase
        case missingSeparator
        case invalidCharacter(Character)
        case badChecksum
        case tooShort
    }

    private static let charset = Array("qpzry9x8gf2tvdw0s3jn54khce6mua7l")
    private static let generators: [UInt32] = [
        0x3b6a_57b2, 0x2650_8e6d, 0x1ea1_19fa, 0x3d42_33dd, 0x2a14_62b3,
    ]

    public static func encode(hrp: String, data: [UInt8]) throws -> String {
        guard !hrp.isEmpty else { throw Error.emptyHRP }
        let hrpLower = hrp.lowercased()
        let combined = data + checksum(hrp: hrpLower, data: data)

        var s = hrpLower + "1"
        for v in combined {
            guard Int(v) < charset.count else { throw Error.valueOutOfRange }
            s.append(charset[Int(v)])
        }
        return s
    }

    /// Returns the HRP and the decoded 8-bit payload.
    public static func decode(_ address: String) throws -> (hrp: String, data: [UInt8]) {
        let hasLower = address.contains { $0.isLowercase }
        let hasUpper = address.contains { $0.isUppercase }
        if hasLower, hasUpper { throw Error.mixedCase }

        let lower = address.lowercased()
        guard let sep = lower.lastIndex(of: "1") else { throw Error.missingSeparator }

        let hrp = String(lower[lower.startIndex ..< sep])
        guard !hrp.isEmpty else { throw Error.emptyHRP }

        let dataPart = lower[lower.index(after: sep)...]
        // Six characters of checksum, and BIP-173 permits nothing after them,
        // so six is the minimum rather than the exclusive bound. Callers that
        // need a payload check its length themselves.
        guard dataPart.count >= 6 else { throw Error.tooShort }

        var values = [UInt8]()
        values.reserveCapacity(dataPart.count)
        for c in dataPart {
            guard let idx = charset.firstIndex(of: c) else { throw Error.invalidCharacter(c) }
            values.append(UInt8(idx))
        }

        guard polymod(hrpExpand(hrp) + values) == 1 else { throw Error.badChecksum }

        let payload = Array(values[0 ..< (values.count - 6)])
        return (hrp, try convertBits(payload, from: 5, to: 8, pad: false))
    }

    /// Regroup bits. Bech32 encoding is 8 -> 5 with padding; decoding is the
    /// reverse and must refuse padding, since a payload that needs it is
    /// malformed rather than merely unusual.
    public static func convertBits(_ data: [UInt8], from: Int, to: Int, pad: Bool) throws -> [UInt8] {
        var acc = 0
        var bits = 0
        let maxv = (1 << to) - 1
        let maxAcc = (1 << (from + to - 1)) - 1

        var out = [UInt8]()
        for value in data {
            let b = Int(value)
            guard (b >> from) == 0 else { throw Error.valueOutOfRange }
            acc = ((acc << from) | b) & maxAcc
            bits += from
            while bits >= to {
                bits -= to
                out.append(UInt8((acc >> bits) & maxv))
            }
        }
        if pad {
            if bits > 0 { out.append(UInt8((acc << (to - bits)) & maxv)) }
        } else if bits >= from || ((acc << (to - bits)) & maxv) != 0 {
            throw Error.cannotConvertWithoutPadding
        }
        return out
    }

    private static func hrpExpand(_ hrp: String) -> [UInt8] {
        let scalars = Array(hrp.unicodeScalars)
        return scalars.map { UInt8($0.value >> 5) } + [0] + scalars.map { UInt8($0.value & 0x1f) }
    }

    private static func polymod(_ values: [UInt8]) -> UInt32 {
        var chk: UInt32 = 1
        for v in values {
            let top = chk >> 25
            chk = (chk & 0x01ff_ffff) << 5 ^ UInt32(v)
            for i in 0 ..< 5 where (top >> UInt32(i)) & 1 == 1 {
                chk ^= generators[i]
            }
        }
        return chk
    }

    private static func checksum(hrp: String, data: [UInt8]) -> [UInt8] {
        let values = hrpExpand(hrp) + data + [0, 0, 0, 0, 0, 0]
        let mod = polymod(values) ^ 1
        return (0 ..< 6).map { UInt8((mod >> (5 * (5 - UInt32($0)))) & 31) }
    }
}
