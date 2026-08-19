import Foundation
import secp256k1

/// BIP-32 hierarchical deterministic derivation over secp256k1.
///
/// Only what a Cosmos wallet needs: private parent -> private child, both
/// hardened and not. Public-parent derivation is absent because no path here
/// uses it, and an unused branch of key derivation is a liability.
public struct HDKey {
    public let privateKey: Data      // 32 bytes
    public let chainCode: Data       // 32 bytes

    public enum Error: Swift.Error, Equatable {
        case invalidSeedLength(Int)
        case invalidPath(String)
        /// Both of these mean "derive the next index instead". The chance is
        /// about 2^-127, so it has never happened, but the spec requires the
        /// branch and skipping it would silently produce an invalid key.
        case keyOutOfRange
        case derivationFailed
    }

    /// Master key from a BIP-39 seed: HMAC-SHA512 under the fixed key
    /// "Bitcoin seed" — the string is part of the spec, not a Bitcoin
    /// dependency, and every Cosmos wallet uses it too.
    public init(seed: Data) throws {
        guard (16 ... 64).contains(seed.count) else { throw Error.invalidSeedLength(seed.count) }
        let i = Hashes.hmacSHA512(key: Data("Bitcoin seed".utf8), message: seed)
        let key = i.prefix(32)
        guard Self.isValidScalar(Data(key)) else { throw Error.keyOutOfRange }
        self.privateKey = Data(key)
        self.chainCode = Data(i.suffix(32))
    }

    private init(privateKey: Data, chainCode: Data) {
        self.privateKey = privateKey
        self.chainCode = chainCode
    }

    public var publicKeyCompressed: Data {
        get throws {
            let key = try secp256k1.Signing.PrivateKey(dataRepresentation: privateKey)
            return key.publicKey.dataRepresentation
        }
    }

    /// Derive along a path such as `m/44'/118'/0'/0/0`.
    public func derive(path: String) throws -> HDKey {
        var components = path.split(separator: "/").map(String.init)
        guard let first = components.first else { throw Error.invalidPath(path) }
        if first == "m" || first == "M" {
            components.removeFirst()
        }

        var key = self
        for component in components {
            let hardened = component.hasSuffix("'") || component.hasSuffix("h") || component.hasSuffix("H")
            let digits = hardened ? String(component.dropLast()) : component
            guard let index = UInt32(digits), index < 0x8000_0000 else {
                throw Error.invalidPath(path)
            }
            key = try key.child(index: hardened ? index + 0x8000_0000 : index)
        }
        return key
    }

    public func child(index: UInt32) throws -> HDKey {
        var data = Data()
        if index >= 0x8000_0000 {
            // Hardened: the parent's *private* key goes in, prefixed with a
            // zero byte so the payload is the same 37 bytes either way.
            data.append(0x00)
            data.append(privateKey)
        } else {
            data.append(try publicKeyCompressed)
        }
        for shift in [24, 16, 8, 0] { data.append(UInt8((index >> UInt32(shift)) & 0xff)) }

        let i = Hashes.hmacSHA512(key: chainCode, message: data)
        let tweak = Data(i.prefix(32))
        guard Self.isValidScalar(tweak) else { throw Error.keyOutOfRange }

        // child = parent + IL (mod n). libsecp256k1 does the modular add and
        // rejects an overflow or a zero result, which is exactly the check the
        // spec asks for.
        let parent = try secp256k1.Signing.PrivateKey(dataRepresentation: privateKey)
        guard let child = try? parent.add(Array(tweak)) else { throw Error.derivationFailed }

        return HDKey(privateKey: child.dataRepresentation, chainCode: Data(i.suffix(32)))
    }

    /// In [1, n). Zero and anything at or above the curve order are invalid.
    private static func isValidScalar(_ scalar: Data) -> Bool {
        guard scalar.count == 32, scalar.contains(where: { $0 != 0 }) else { return false }
        let n: [UInt8] = [
            0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF,
            0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFE,
            0xBA, 0xAE, 0xDC, 0xE6, 0xAF, 0x48, 0xA0, 0x3B,
            0xBF, 0xD2, 0x5E, 0x8C, 0xD0, 0x36, 0x41, 0x41,
        ]
        for (a, b) in zip(scalar, n) {
            if a < b { return true }
            if a > b { return false }
        }
        return false // exactly n
    }
}
