import CryptoKit
import Foundation

/// The wallet's privacy keys, all derived from the BIP-39 mnemonic so the
/// mnemonic stays the only backup. Ports `privacy/keys/PrivacyKeys.kt`; the
/// derivation is the wallet's own (PRIVACY_FORMATS.md §2), pinned by golden
/// tests on both platforms:
///
///     m/2026'/118'/0'/0'   id_secret   (identity: idc = H(TAG_ID, id_secret))
///     m/2026'/118'/0'/1'   nk          (spending: owner_pk = H(TAG_OWNER, nk))
///     m/2026'/118'/0'/2'   ek          (x25519 note-encryption key)
///
/// A child's 32-byte private key k becomes a secret by
/// HMAC-SHA512(key = "earth.privacy.v1", label || k): reduced mod p for the
/// field secrets (64 bytes, so uniform), its first 32 bytes for ek.
public final class PrivacyKeys: @unchecked Sendable {
    public static let purpose: UInt32 = 2026
    private static let coin: UInt32 = 118
    private static let hmacKey = Data("earth.privacy.v1".utf8)

    public let idSecret: Fr
    public let nk: Fr
    private let ekSecret: Data

    public let idc: Fr
    public let ownerPK: Fr
    public let ekPub: Data
    public let address: ShieldedAddress

    private init(idSecret: Fr, nk: Fr, ekSecret: Data) throws {
        self.idSecret = idSecret
        self.nk = nk
        self.ekSecret = ekSecret
        idc = PrivacyHash.idc(idSecret)
        ownerPK = PrivacyHash.ownerPK(nk)
        ekPub = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: ekSecret).publicKey.rawRepresentation
        address = try ShieldedAddress(ownerPK: ownerPK, ekPub: ekPub)
    }

    public static func fromMnemonic(_ mnemonic: String) throws -> PrivacyKeys {
        var seed = BIP39.seed(fromMnemonic: BIP39.canonical(mnemonic), passphrase: "")
        // The seed is zeroed once the keys are derived.
        defer { seed.resetBytes(in: 0 ..< seed.count) }
        return try fromSeed(seed)
    }

    public static func fromSeed(_ seed: Data) throws -> PrivacyKeys {
        let master = try HDKey(seed: seed)
        let coin = try master.child(index: purpose | 0x8000_0000).child(index: Self.coin | 0x8000_0000)
        let keys = try coin.child(index: 0x8000_0000)
        func secret(_ i: UInt32, _ label: String) throws -> Data {
            hmac(Data(label.utf8) + (try keys.child(index: i | 0x8000_0000)).privateKey)
        }
        return try PrivacyKeys(
            idSecret: Fr.fromWideBytes(secret(0, "id_secret")),
            nk: Fr.fromWideBytes(secret(1, "nk")),
            ekSecret: Data(try secret(2, "ek").prefix(32))
        )
    }

    static func hmac(_ data: Data) -> Data { Hashes.hmacSHA512(key: hmacKey, message: data) }

    /// The x25519 secret, for trial decryption only.
    func ek() throws -> Curve25519.KeyAgreement.PrivateKey {
        try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: ekSecret)
    }

    // Nothing here derives a note's secrets from a counter: every note the
    // chain mints to this wallet carries a value-blind ciphertext of fresh
    // secrets (PRIVACY_FORMATS.md 5), found by trial decryption.

    private func counted(_ label: String, _ counter: UInt32) -> Fr {
        let c = Data([UInt8(counter >> 24 & 0xff), UInt8(counter >> 16 & 0xff), UInt8(counter >> 8 & 0xff), UInt8(counter & 0xff)])
        return try! Fr.fromWideBytes(Self.hmac(Data(label.utf8) + nk.bytes + c))
    }

    /// Groundworks position `counter`'s owner-tag salt: a position stores
    /// H(TAG_OTAG, owner_pk, salt) and its owner proves it again to update,
    /// unlock or vote it. Found again from the mnemonic by recomputing the
    /// tags of counters 0 ... last+gap against the public positions.
    ///
    ///     salt = HMAC-SHA512("earth.privacy.v1", "otag-salt" || nk (32) || counter u32 BE) mod p
    public func otagSalt(_ counter: UInt32) -> Fr { counted("otag-salt", counter) }

    public func ownerTag(_ counter: UInt32) -> Fr { PrivacyHash.ownerTag(ownerPK: ownerPK, salt: otagSalt(counter)) }
}
