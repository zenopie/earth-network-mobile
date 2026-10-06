import CryptoKit
import Foundation

/// A wallet's private state at rest: AES-256-GCM under the install's data
/// key (WalletStore.Opened.dataKey, kept inside the sealed vault, so it opens
/// exactly when the wallet does, whatever the unlock method). The notes, the
/// registration record, the handle and the moves in flight are what tie this
/// phone's wallet to its shielded history and to a passport registration; on
/// disk they are this envelope, never plaintext. The trees beside it hold the
/// chain's public leaves and are not sealed. Mirrors Android's StateSeal.kt;
/// the format is the same:
///
///     {"sealed": 1, "alg": "AES-256-GCM", "kid": <hex>, "nonce": <hex>, "ct": <hex>}
///
/// `ct` is the ciphertext with its 16-byte tag appended (javax.crypto's
/// layout). `kid` names the key (a hash of it) so a store sealed under an
/// earlier install's key is told apart from a damaged one. The wallet id is
/// the AAD: one wallet's state cannot be passed off as another's.
public enum StateSeal {
    static let format = 1
    static let alg = "AES-256-GCM"

    private struct Envelope: Codable {
        let sealed: Int
        let alg: String
        let kid: String
        let nonce: String
        let ct: String
    }

    /// The first 8 bytes of SHA-256("earth/privacy-store/kid" || key), hex.
    public static func kid(_ key: Data) -> String {
        var h = SHA256()
        h.update(data: Data("earth/privacy-store/kid".utf8))
        h.update(data: key)
        return Data(h.finalize().prefix(8)).hexString
    }

    private static func aad(_ walletID: String) -> Data { Data("earth/privacy-state/v1|\(walletID)".utf8) }

    public static func seal(_ plain: Data, key: Data, walletID: String) throws -> Data {
        precondition(key.count == 32, "the data key is 32 bytes")
        let box = try AES.GCM.seal(plain, using: SymmetricKey(data: key), nonce: AES.GCM.Nonce(), authenticating: aad(walletID))
        return try JSONEncoder().encode(Envelope(sealed: format, alg: alg, kid: kid(key),
                                                 nonce: Data(box.nonce).hexString, ct: (box.ciphertext + box.tag).hexString))
    }

    public enum Contents {
        /// Written before sealing existed: read once, sealed on the next save.
        case legacy(Data)
        case opened(Data)
        /// Sealed under another key: an earlier install's data, which this one cannot read.
        case otherKey
    }

    public struct Damaged: Swift.Error, LocalizedError {
        public let message: String
        public var errorDescription: String? { message }
    }

    /// Throws `Damaged` when the file is not a state this app reads (an
    /// unknown format, a failed tag under this key, no key for a sealed one).
    public static func open(_ file: Data, key: Data?, walletID: String) throws -> Contents {
        guard let object = try? JSONSerialization.jsonObject(with: file) as? [String: Any] else { throw Damaged(message: "not JSON") }
        guard object["sealed"] != nil else { return .legacy(file) }
        guard let e = try? JSONDecoder().decode(Envelope.self, from: file), e.sealed == format, e.alg == alg else {
            throw Damaged(message: "a sealed state format this app does not read")
        }
        guard let key else { throw Damaged(message: "sealed, and no key is open") }
        guard e.kid == kid(key) else { return .otherKey }
        guard let nonceBytes = Data(hexString: e.nonce), let ct = Data(hexString: e.ct), ct.count >= 16,
              let nonce = try? AES.GCM.Nonce(data: nonceBytes),
              let box = try? AES.GCM.SealedBox(nonce: nonce, ciphertext: ct.prefix(ct.count - 16), tag: ct.suffix(16)),
              let plain = try? AES.GCM.open(box, using: SymmetricKey(data: key), authenticating: aad(walletID))
        else { throw Damaged(message: "the seal does not open under this key") }
        return .opened(plain)
    }
}
