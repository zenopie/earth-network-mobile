import CryptoKit
import Foundation

/// Note ciphertexts, in the canonical format shared with chain zk/privacy,
/// Android and the web app. Ports `privacy/note/NoteCipher.kt`; the chain
/// treats them as opaque bytes (at most 1024, bound into the signal so a relay
/// cannot swap them).
///
/// v1:
///
///     ct    = epk (32) || ChaCha20-Poly1305(key, nonce = 0^12, aad = empty, pt)      217 bytes
///     key   = HKDF-SHA256(ikm = X25519(esk, ek_pub), salt = "earth.note.v1", info = epk || cm)
///     pt    = 0x01 || asset_id (32) || value (u64 BE) || rho (32) || rcm (32) || memo (64, zero padded)
///
/// Every key is single-use (a fresh esk per note), so the zero nonce never
/// repeats under one key. cm in the info binds the ciphertext to its note's
/// commitment, and the recipient recomputes cm from the decrypted fields under
/// its own owner key before accepting. A dummy output (value 0) is encrypted
/// to a throwaway key: same length, openable by no one.
///
/// v2 (value-blind), for a note whose asset and value the chain decides when
/// the msg runs and publishes with the note (the mint event's amount):
///
///     ct    = epk (32) || ChaCha20-Poly1305(key, nonce = 0^12, aad = empty, pt)      177 bytes
///     key   = HKDF-SHA256(ikm = X25519(esk, ek_pub), salt = "earth.note.v2", info = epk)
///     pt    = 0x02 || rho (32) || rcm (32) || memo (64, zero padded)
///
/// The length alone tells the two apart. info cannot bind cm (the sender does
/// not know it), so the binding is the recipient's check that
/// CM(asset, value, PC(owner_pk, rho, rcm)) is the note's cm, with the asset
/// and value the chain published for that position.
///
/// "earth stake note v1" (v3), for a stake note a stake proof creates (a
/// restake's outputs, an undelegation's or a lock's change). Stake notes are
/// owner-locked, so it is always encrypted to the wallet's own address, for
/// its other devices and for recovery from the mnemonic:
///
///     ct    = epk (32) || ChaCha20-Poly1305(key, nonce = 0^12, aad = empty, pt)      153 bytes
///     key   = HKDF-SHA256(ikm = X25519(esk, ek_pub), salt = "earth.stake.v1", info = epk || cm)
///     pt    = 0x03 || asset_id (32) || amount (u64 BE) || rho (32) || rcm (32)
///
/// accepted only if StakeCM(asset, amount, StakePC(owner_pk, rho, rcm)) is
/// the note's cm.
public enum NoteCipher {
    public static let version: UInt8 = 1
    public static let memoBytes = 64
    public static let plaintextBytes = 1 + 32 + 8 + 32 + 32 + memoBytes
    public static let ciphertextBytes = 32 + plaintextBytes + 16
    private static let salt = Data("earth.note.v1".utf8)
    public static let blindVersion: UInt8 = 2
    public static let blindPlaintextBytes = 1 + 32 + 32 + memoBytes
    public static let blindCiphertextBytes = 32 + blindPlaintextBytes + 16
    private static let blindSalt = Data("earth.note.v2".utf8)
    public static let stakeVersion: UInt8 = 3
    public static let stakePlaintextBytes = 1 + 32 + 8 + 32 + 32
    public static let stakeCiphertextBytes = 32 + stakePlaintextBytes + 16
    private static let stakeSalt = Data("earth.stake.v1".utf8)

    public enum Error: Swift.Error {
        case lowOrderPoint
        case memoTooLong
        case badKey
    }

    typealias SecretKey = Curve25519.KeyAgreement.PrivateKey

    /// Encrypts `note` to `to`; cm is the note's commitment under to's owner key.
    public static func encrypt(_ note: NotePlaintext, to: ShieldedAddress) throws -> Data {
        try encrypt(note, ekPub: to.ekPub, cm: note.cm(ownerPK: to.ownerPK))
    }

    public static func encrypt(_ note: NotePlaintext, ekPub: Data, cm: Fr) throws -> Data {
        try seal(esk: SecretKey(), ekPub: ekPub, salt: salt, info: { $0 + cm.bytes }, pt: plaintext(note))
    }

    /// Deterministic encryption under a given ephemeral secret: for golden vectors only.
    static func encryptWith(esk: Data, _ note: NotePlaintext, to: ShieldedAddress) throws -> Data {
        let cm = note.cm(ownerPK: to.ownerPK)
        return try seal(esk: SecretKey(rawRepresentation: esk), ekPub: to.ekPub, salt: salt, info: { $0 + cm.bytes }, pt: plaintext(note))
    }

    private static func seal(esk: SecretKey, ekPub: Data, salt: Data, info: (Data) -> Data, pt: Data) throws -> Data {
        let epk = esk.publicKey.rawRepresentation
        let key = try kdf(esk: esk, peer: ekPub, salt: salt, info: info(epk))
        let box = try ChaChaPoly.seal(pt, using: key, nonce: zeroNonce, authenticating: Data())
        return epk + box.ciphertext + box.tag
    }

    private static let zeroNonce = try! ChaChaPoly.Nonce(data: Data(count: 12))

    private static func kdf(esk: SecretKey, peer: Data, salt: Data, info: Data) throws -> SymmetricKey {
        guard let pub = try? Curve25519.KeyAgreement.PublicKey(rawRepresentation: peer) else { throw Error.badKey }
        let shared: SharedSecret
        do { shared = try esk.sharedSecretFromKeyAgreement(with: pub) } catch { throw Error.lowOrderPoint }
        let raw = shared.withUnsafeBytes { Data($0) }
        guard raw.contains(where: { $0 != 0 }) else { throw Error.lowOrderPoint }
        return HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: raw), salt: salt, info: info, outputByteCount: 32)
    }

    private static func open(_ body: Data, key: SymmetricKey) throws -> Data {
        let b = Data(body)
        guard b.count >= 16 else { throw Error.badKey }
        let box = try ChaChaPoly.SealedBox(nonce: zeroNonce, ciphertext: b.prefix(b.count - 16), tag: b.suffix(16))
        return try ChaChaPoly.open(box, using: key, authenticating: Data())
    }

    /// A ciphertext for a dummy output: well formed, and openable by no one.
    public static func dummy() -> Data {
        let throwaway = SecretKey().publicKey.rawRepresentation
        return try! encrypt(NotePlaintext.fresh("uerth", 0), ekPub: throwaway, cm: NotePlaintext.randomField())
    }

    /// The note if `ct` opens with our ek for commitment `cm` and its fields
    /// recompute `cm` under our owner key; else nil. Never throws.
    public static func tryDecrypt(_ ct: Data, cm: Fr, keys: PrivacyKeys, denoms: AssetDenoms = AssetDenoms()) -> NotePlaintext? {
        guard ct.count == ciphertextBytes, let ek = try? keys.ek() else { return nil }
        let c = Data(ct)
        let epk = c.prefix(32)
        guard let key = try? kdf(esk: ek, peer: epk, salt: salt, info: epk + cm.bytes),
              let pt = try? open(c.suffix(from: 32), key: key),
              let (asset, note) = parse(pt, denoms: denoms)
        else { return nil }
        guard PrivacyHash.cm(asset: asset, value: note.value, pc: note.pc(ownerPK: keys.ownerPK)) == cm else { return nil }
        return note
    }

    // MARK: - v2 (value-blind)

    /// Encrypts `note`'s secrets (rho, rcm, memo; not its asset or value) to `to`.
    public static func encryptBlind(_ note: NotePlaintext, to: ShieldedAddress) throws -> Data {
        try seal(esk: SecretKey(), ekPub: to.ekPub, salt: blindSalt, info: { $0 }, pt: blindPlaintext(note))
    }

    /// Deterministic v2 encryption: for golden vectors only.
    static func encryptBlindWith(esk: Data, _ note: NotePlaintext, ekPub: Data) throws -> Data {
        try seal(esk: SecretKey(rawRepresentation: esk), ekPub: ekPub, salt: blindSalt, info: { $0 }, pt: blindPlaintext(note))
    }

    private static func blindPlaintext(_ n: NotePlaintext) throws -> Data {
        try blindPlaintext(rho: n.rho, rcm: n.rcm, memo: n.memo, version: blindVersion)
    }

    private static func blindPlaintext(rho: Fr, rcm: Fr, memo: Data, version: UInt8) throws -> Data {
        guard memo.count <= memoBytes else { throw Error.memoTooLong }
        return Data([version]) + rho.bytes + rcm.bytes + memo + Data(count: memoBytes - memo.count)
    }

    /// Opens a 177-byte blind ciphertext under `salt` and `version`: (rho, rcm, memo with its padding dropped).
    private static func openBlind(_ ct: Data, ek: SecretKey, salt: Data, version: UInt8) -> (rho: Fr, rcm: Fr, memo: Data)? {
        guard ct.count == blindCiphertextBytes else { return nil }
        let c = Data(ct)
        let epk = c.prefix(32)
        guard let key = try? kdf(esk: ek, peer: epk, salt: salt, info: epk),
              let pt = try? open(c.suffix(from: 32), key: key),
              pt.count == blindPlaintextBytes, pt[pt.startIndex] == version
        else { return nil }
        let p = [UInt8](pt)
        guard let rho = try? Fr(bytes: Data(p[1 ..< 33])), let rcm = try? Fr(bytes: Data(p[33 ..< 65])) else { return nil }
        return (rho, rcm, trimMemo(Array(p[65...])))
    }

    /// A v2 note: opens `ct` with our ek, and accepts it only if the secrets
    /// with the chain-published `denom` and `value` recompute `cm` under our
    /// owner key. Nil otherwise; never throws.
    public static func tryDecryptBlind(_ ct: Data, cm: Fr, denom: String, value: UInt64, keys: PrivacyKeys) -> NotePlaintext? {
        guard let ek = try? keys.ek() else { return nil }
        return tryDecryptBlind(ct, cm: cm, denom: denom, value: value, ek: ek, ownerPK: keys.ownerPK)
    }

    static func tryDecryptBlind(_ ct: Data, cm: Fr, denom: String, value: UInt64, ek: SecretKey, ownerPK: Fr) -> NotePlaintext? {
        guard let o = openBlind(ct, ek: ek, salt: blindSalt, version: blindVersion) else { return nil }
        let n = NotePlaintext(denom: denom, value: value, rho: o.rho, rcm: o.rcm, memo: o.memo)
        return n.cm(ownerPK: ownerPK) == cm ? n : nil
    }

    // MARK: - blind stake ciphertext (chain zk/privacy EncryptBlindStakeNote)

    /// The blind stake ciphertext of a stake note the chain will mint to
    /// spc = StakePC(owner_pk, `rho`, `rcm`) (StakeProof.spc_ciphertext): as
    /// v2, under salt "earth.stake.v1" and version 0x03, 177 bytes.
    public static func encryptBlindStake(rho: Fr, rcm: Fr, ekPub: Data, memo: Data = Data()) throws -> Data {
        try seal(esk: SecretKey(), ekPub: ekPub, salt: stakeSalt, info: { $0 }, pt: blindPlaintext(rho: rho, rcm: rcm, memo: memo, version: stakeVersion))
    }

    /// Deterministic blind stake encryption: for golden vectors only.
    static func encryptBlindStakeWith(esk: Data, rho: Fr, rcm: Fr, ekPub: Data, memo: Data = Data()) throws -> Data {
        try seal(esk: SecretKey(rawRepresentation: esk), ekPub: ekPub, salt: stakeSalt, info: { $0 },
                 pt: blindPlaintext(rho: rho, rcm: rcm, memo: memo, version: stakeVersion))
    }

    /// A minted stake note: opens `ct` (177 bytes) with our ek and accepts it
    /// only if StakeCM(AssetID(`denom`), `amount`, StakePC(owner_pk, rho, rcm))
    /// is `cm`, with the denom and amount the chain published. (rho, rcm) or nil.
    public static func tryDecryptBlindStake(_ ct: Data, cm: Fr, denom: String, amount: UInt64, keys: PrivacyKeys) -> (rho: Fr, rcm: Fr)? {
        guard let ek = try? keys.ek() else { return nil }
        return tryDecryptBlindStake(ct, cm: cm, denom: denom, amount: amount, ek: ek, ownerPK: keys.ownerPK)
    }

    static func tryDecryptBlindStake(_ ct: Data, cm: Fr, denom: String, amount: UInt64, ek: SecretKey, ownerPK: Fr) -> (rho: Fr, rcm: Fr)? {
        tryOpenBlindStake(ct, cm: cm, denom: denom, amount: amount, ek: ek, ownerPK: ownerPK).map { ($0.rho, $0.rcm) }
    }

    /// `tryDecryptBlindStake` with the memo (trailing zeros dropped).
    public static func tryOpenBlindStake(_ ct: Data, cm: Fr, denom: String, amount: UInt64, keys: PrivacyKeys) -> (rho: Fr, rcm: Fr, memo: Data)? {
        guard let ek = try? keys.ek() else { return nil }
        return tryOpenBlindStake(ct, cm: cm, denom: denom, amount: amount, ek: ek, ownerPK: keys.ownerPK)
    }

    static func tryOpenBlindStake(_ ct: Data, cm: Fr, denom: String, amount: UInt64, ek: SecretKey, ownerPK: Fr) -> (rho: Fr, rcm: Fr, memo: Data)? {
        guard let o = openBlind(ct, ek: ek, salt: stakeSalt, version: stakeVersion) else { return nil }
        let spc = PrivacyHash.stakePC(ownerPK: ownerPK, rho: o.rho, rcm: o.rcm)
        return PrivacyHash.stakeCM(asset: PrivacyHash.assetID(denom), amount: amount, spc: spc) == cm ? o : nil
    }

    // MARK: - stake notes (v3)

    /// A stake note's opening, as its stake ciphertext carries it.
    public struct StakeOpening: Equatable, Sendable {
        public let asset: Fr
        public let amount: UInt64
        public let rho: Fr
        public let rcm: Fr
        public init(asset: Fr, amount: UInt64, rho: Fr, rcm: Fr) { self.asset = asset; self.amount = amount; self.rho = rho; self.rcm = rcm }
    }

    /// Encrypts a stake note to `ekPub` (the wallet's own); cm is its stake commitment.
    public static func encryptStake(_ o: StakeOpening, ekPub: Data, cm: Fr) throws -> Data {
        try seal(esk: SecretKey(), ekPub: ekPub, salt: stakeSalt, info: { $0 + cm.bytes }, pt: stakePlaintext(o))
    }

    /// Deterministic stake encryption: for golden vectors only.
    static func encryptStakeWith(esk: Data, _ o: StakeOpening, ekPub: Data, cm: Fr) throws -> Data {
        try seal(esk: SecretKey(rawRepresentation: esk), ekPub: ekPub, salt: stakeSalt, info: { $0 + cm.bytes }, pt: stakePlaintext(o))
    }

    static func stakePlaintext(_ o: StakeOpening) -> Data {
        Data([stakeVersion]) + o.asset.bytes + PrivateMsgs.be64(o.amount) + o.rho.bytes + o.rcm.bytes
    }

    /// The stake note if `ct` opens with our ek for `cm` and recomputes it under our owner key; else nil.
    public static func tryDecryptStake(_ ct: Data, cm: Fr, keys: PrivacyKeys) -> StakeOpening? {
        guard ct.count == stakeCiphertextBytes, let ek = try? keys.ek() else { return nil }
        let c = Data(ct)
        let epk = c.prefix(32)
        guard let key = try? kdf(esk: ek, peer: epk, salt: stakeSalt, info: epk + cm.bytes),
              let pt = try? open(c.suffix(from: 32), key: key),
              pt.count == stakePlaintextBytes, pt[pt.startIndex] == stakeVersion
        else { return nil }
        let p = [UInt8](pt)
        guard let asset = try? Fr(bytes: Data(p[1 ..< 33])) else { return nil }
        var amount: UInt64 = 0
        for b in p[33 ..< 41] { amount = amount << 8 | UInt64(b) }
        guard let rho = try? Fr(bytes: Data(p[41 ..< 73])), let rcm = try? Fr(bytes: Data(p[73 ..< 105])) else { return nil }
        guard PrivacyHash.stakeCM(asset: asset, amount: amount, spc: PrivacyHash.stakePC(ownerPK: keys.ownerPK, rho: rho, rcm: rcm)) == cm
        else { return nil }
        return StakeOpening(asset: asset, amount: amount, rho: rho, rcm: rcm)
    }

    // MARK: - plaintext

    static func plaintext(_ n: NotePlaintext) throws -> Data {
        guard n.memo.count <= memoBytes else { throw Error.memoTooLong }
        var v = Data(count: 8)
        for i in 0 ..< 8 { v[i] = UInt8(truncatingIfNeeded: n.value >> UInt64(56 - 8 * i)) }
        return Data([version]) + n.asset.bytes + v + n.rho.bytes + n.rcm.bytes + n.memo + Data(count: memoBytes - n.memo.count)
    }

    private static func trimMemo(_ memo: [UInt8]) -> Data {
        let end = (memo.lastIndex { $0 != 0 }).map { $0 + 1 } ?? 0
        return Data(memo[0 ..< end])
    }

    /// (asset id, note). The memo's trailing zero padding is dropped.
    static func parse(_ pt: Data, denoms: AssetDenoms) -> (Fr, NotePlaintext)? {
        let p = [UInt8](pt)
        guard p.count == plaintextBytes, p[0] == version else { return nil }
        guard let asset = try? Fr(bytes: Data(p[1 ..< 33])) else { return nil }
        var value: UInt64 = 0
        for b in p[33 ..< 41] { value = value << 8 | UInt64(b) }
        guard let rho = try? Fr(bytes: Data(p[41 ..< 73])), let rcm = try? Fr(bytes: Data(p[73 ..< 105])) else { return nil }
        let memo = trimMemo(Array(p[105 ..< 169]))
        return (asset, NotePlaintext(denom: denoms.resolve(asset), value: value, rho: rho, rcm: rcm, memo: memo))
    }
}
