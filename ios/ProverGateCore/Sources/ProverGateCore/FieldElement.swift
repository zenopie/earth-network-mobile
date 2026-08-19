import Foundation

// Public signals cross the wire as decimal strings (Android uses BigInteger for
// this). Swift has no bignum in the stdlib and the chain layer does not need a
// general one, so these do base conversion by long division over the raw bytes
// rather than pulling in a dependency.
//
// Deliberately free of any Barretenberg dependency: this module builds and its
// checks run on a toolchain that cannot link the Swoirenberg xcframework, which
// keeps half the port verifiable when the prover half is blocked.

/// Big-endian bytes -> unsigned decimal string.
public func decimalFromBigEndian<C: Collection>(_ bytes: C) -> String where C.Element == UInt8 {
    var digits: [UInt8] = [0] // base-10, least-significant first
    for byte in bytes {
        var carry = Int(byte)
        for i in digits.indices {
            let value = Int(digits[i]) * 256 + carry
            digits[i] = UInt8(value % 10)
            carry = value / 10
        }
        while carry > 0 {
            digits.append(UInt8(carry % 10))
            carry /= 10
        }
    }
    return String(digits.reversed().map { Character(UnicodeScalar($0 + 48)) })
}

/// Unsigned decimal string -> minimal lowercase hex, no `0x`. Mirrors Android's
/// `BigInteger(nullifier).toString(16)`, including dropping leading zeros.
public func hexFromDecimal(_ decimal: String) -> String {
    var digits = Array(decimal.utf8).map { $0 - 48 }
    var out: [Character] = []
    let hexAlphabet = Array("0123456789abcdef")
    while !(digits.count == 1 && digits[0] == 0) {
        var remainder = 0
        var quotient: [UInt8] = []
        for d in digits {
            let value = remainder * 10 + Int(d)
            quotient.append(UInt8(value / 16))
            remainder = value % 16
        }
        while quotient.count > 1 && quotient[0] == 0 { quotient.removeFirst() }
        out.append(hexAlphabet[remainder])
        digits = quotient
    }
    return out.isEmpty ? "0" : String(out.reversed())
}

/// `0x…` hex -> unsigned decimal string, for comparing a witness value against
/// the decimal form bb reports in the public inputs.
public func decimalFromHex(_ hex: String) -> String {
    let body = hex.hasPrefix("0x") ? String(hex.dropFirst(2)) : hex
    let padded = body.count % 2 == 1 ? "0" + body : body
    var bytes = [UInt8]()
    var index = padded.startIndex
    while index < padded.endIndex, let next = padded.index(index, offsetBy: 2, limitedBy: padded.endIndex) {
        bytes.append(UInt8(padded[index..<next], radix: 16) ?? 0)
        index = next
    }
    return decimalFromBigEndian(bytes)
}

public extension Data {
    /// Reads a file holding hex text (optionally `0x`-prefixed, trailing newline
    /// tolerated) as raw bytes — the format both device tests write.
    init(hexAt url: URL) throws {
        let text = try String(contentsOf: url, encoding: .utf8)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let body = text.hasPrefix("0x") ? String(text.dropFirst(2)) : text
        var bytes = [UInt8]()
        bytes.reserveCapacity(body.count / 2)
        var index = body.startIndex
        while index < body.endIndex, let next = body.index(index, offsetBy: 2, limitedBy: body.endIndex) {
            guard let byte = UInt8(body[index..<next], radix: 16) else {
                throw NSError(domain: "ProverGate", code: 1, userInfo: [
                    NSLocalizedDescriptionKey: "non-hex byte in \(url.lastPathComponent)",
                ])
            }
            bytes.append(byte)
            index = next
        }
        self.init(bytes)
    }

    var hexString: String { map { String(format: "%02x", $0) }.joined() }
}
