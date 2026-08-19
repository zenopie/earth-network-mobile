import CommonCrypto
import CryptoKit
import Foundation

/// The digests and derivations the wallet needs, in one place.
public enum Hashes {

    public static func sha256(_ data: Data) -> Data {
        Data(SHA256.hash(data: data))
    }

    /// The Cosmos address hash: RIPEMD160(SHA256(x)).
    public static func hash160(_ data: Data) -> Data {
        RIPEMD160.hash(sha256(data))
    }

    public static func hmacSHA512(key: Data, message: Data) -> Data {
        let mac = HMAC<SHA512>.authenticationCode(
            for: message,
            using: SymmetricKey(data: key)
        )
        return Data(mac)
    }

    /// PBKDF2-HMAC-SHA512, as BIP-39 specifies for mnemonic -> seed.
    ///
    /// CryptoKit has no PBKDF2, so this goes through CommonCrypto. The
    /// password is UTF-8 *bytes*, not a C string: a passphrase is arbitrary
    /// text and NUL-terminating it would be wrong.
    public static func pbkdf2SHA512(password: Data, salt: Data, rounds: UInt32, keyLength: Int) -> Data {
        var out = Data(repeating: 0, count: keyLength)
        let status = out.withUnsafeMutableBytes { outBuf in
            salt.withUnsafeBytes { saltBuf in
                password.withUnsafeBytes { pwBuf in
                    CCKeyDerivationPBKDF(
                        CCPBKDFAlgorithm(kCCPBKDF2),
                        pwBuf.baseAddress!.assumingMemoryBound(to: CChar.self),
                        password.count,
                        saltBuf.baseAddress!.assumingMemoryBound(to: UInt8.self),
                        salt.count,
                        CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA512),
                        rounds,
                        outBuf.baseAddress!.assumingMemoryBound(to: UInt8.self),
                        keyLength
                    )
                }
            }
        }
        precondition(status == kCCSuccess, "PBKDF2 failed with status \(status)")
        return out
    }
}

public extension Data {
    var hexString: String {
        map { String(format: "%02x", $0) }.joined()
    }

    init?(hexString: String) {
        let chars = Array(hexString)
        guard chars.count % 2 == 0 else { return nil }
        var bytes = [UInt8]()
        bytes.reserveCapacity(chars.count / 2)
        for i in stride(from: 0, to: chars.count, by: 2) {
            guard let b = UInt8(String(chars[i ... i + 1]), radix: 16) else { return nil }
            bytes.append(b)
        }
        self = Data(bytes)
    }
}
