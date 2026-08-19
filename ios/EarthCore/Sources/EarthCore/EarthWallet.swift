import CryptoKit
import Foundation
import secp256k1

/// A key pair and the address it owns, derived the way `earth-1` expects.
///
/// Mirrors `WalletCrypto.kt` / `EarthWallet.kt`: BIP-39 seed, BIP-44 at coin
/// type 118, and a Cosmos address of RIPEMD160(SHA256(compressed pubkey))
/// bech32-encoded under the `earth` prefix. Verified against the chain's own
/// cosmos-sdk derivation by `tools/keycheck` — see `corecheck`.
public struct EarthKey {
    public let privateKey: Data
    public let publicKey: Data     // 33-byte compressed
    public let address: String

    public init(mnemonic: String, passphrase: String = "", path: String = Constants.derivationPath) throws {
        let seed = BIP39.seed(fromMnemonic: mnemonic, passphrase: passphrase)
        try self.init(seed: seed, path: path)
    }

    public init(seed: Data, path: String = Constants.derivationPath) throws {
        let derived = try HDKey(seed: seed).derive(path: path)
        try self.init(privateKey: derived.privateKey)
    }

    public init(privateKey: Data) throws {
        let signing = try secp256k1.Signing.PrivateKey(dataRepresentation: privateKey)
        self.privateKey = privateKey
        self.publicKey = signing.publicKey.dataRepresentation
        self.address = try EarthKey.address(fromPublicKey: self.publicKey)
    }

    public static func address(fromPublicKey compressed: Data) throws -> String {
        let hash = Hashes.hash160(compressed)
        let words = try Bech32.convertBits([UInt8](hash), from: 8, to: 5, pad: true)
        return try Bech32.encode(hrp: Constants.bech32Prefix, data: words)
    }

    /// Whether a string is an address this chain would accept — right prefix,
    /// valid checksum, and a 20-byte payload.
    public static func isValidAddress(_ address: String) -> Bool {
        guard let decoded = try? Bech32.decode(address) else { return false }
        return decoded.hrp == Constants.bech32Prefix && decoded.data.count == 20
    }

    /// Sign a message, hashing it with SHA-256 first — which is what Cosmos
    /// signs and what the chain will re-derive when it verifies.
    ///
    /// Returns raw 64-byte r||s rather than DER, in the low-s form. libsecp256k1
    /// normalises s while signing, so this is only a re-framing of its output.
    public func sign(_ message: Data) throws -> Data {
        let signing = try secp256k1.Signing.PrivateKey(dataRepresentation: privateKey)
        // This overload hashes with SHA-256 before signing, which is what
        // Cosmos does to a SignDoc.
        let signature = try signing.signature(for: message)
        return try signature.compactRepresentation
    }
}
