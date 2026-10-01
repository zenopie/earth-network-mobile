import Foundation
import Security

/// A note's opening: what its owner needs to prove it. Ports
/// `privacy/note/Note.kt`. The ciphertext carries the asset id, not the denom
/// (NoteCipher); the wallet resolves it from the denoms it has seen
/// (`AssetDenoms`), and an id it cannot resolve is kept as "asset/<hex>",
/// spendable inside the pool all the same.
///
///     pc = H(TAG_PC, owner_pk, rho, rcm)     cm = H(TAG_CM, AssetID(denom), value, pc)
public struct NotePlaintext: Hashable, Sendable, Codable {
    public let denom: String
    public let value: UInt64
    public let rho: Fr
    public let rcm: Fr
    public let memo: Data

    public init(denom: String, value: UInt64, rho: Fr, rcm: Fr, memo: Data = Data()) {
        self.denom = denom; self.value = value; self.rho = rho; self.rcm = rcm; self.memo = memo
    }

    public static let unresolvedPrefix = "asset/"

    public var asset: Fr { Self.assetOf(denom) }

    public func pc(ownerPK: Fr) -> Fr { PrivacyHash.pc(ownerPK: ownerPK, rho: rho, rcm: rcm) }

    public func cm(ownerPK: Fr) -> Fr { PrivacyHash.cm(asset: asset, value: value, pc: pc(ownerPK: ownerPK)) }

    public static func assetOf(_ denom: String) -> Fr {
        if denom.hasPrefix(unresolvedPrefix), let f = try? Fr(hex: String(denom.dropFirst(unresolvedPrefix.count))) {
            return f
        }
        return PrivacyHash.assetID(denom)
    }

    public static func randomBytes(_ n: Int) -> Data {
        var b = Data(count: n)
        let status = b.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, n, $0.baseAddress!) }
        precondition(status == errSecSuccess, "SecRandomCopyBytes failed")
        return b
    }

    public static func randomField() -> Fr { try! Fr.fromWideBytes(randomBytes(64)) }

    /// A fresh note of `value` `denom`: random rho and rcm.
    public static func fresh(_ denom: String, _ value: UInt64, memo: Data = Data()) -> NotePlaintext {
        NotePlaintext(denom: denom, value: value, rho: randomField(), rcm: randomField(), memo: memo)
    }
}

/// A note the wallet owns: its opening, where it sits in the tree, and its nullifier.
public struct OwnedNote: Hashable, Sendable, Codable {
    public let position: UInt64
    public let height: UInt64
    public let note: NotePlaintext
    public let cm: Fr
    public let nf: Fr
    public var spentHeight: UInt64?
    /// When a tx spending this note was broadcast (unix seconds), until sync sees its nullifier.
    public var pendingAt: Int64?

    public init(position: UInt64, height: UInt64, note: NotePlaintext, cm: Fr, nf: Fr, spentHeight: UInt64? = nil, pendingAt: Int64? = nil) {
        self.position = position; self.height = height; self.note = note; self.cm = cm; self.nf = nf
        self.spentHeight = spentHeight; self.pendingAt = pendingAt
    }

    public var unspent: Bool { spentHeight == nil }
}

/// Asset id -> denom, for the ids note ciphertexts carry. Seeded with the fee
/// and personhood denoms; every public amount the indexer serves (shields and
/// mints, which name their denom) teaches it more, and the first note of any
/// derth/ or unbond/ denom is always a public mint, so a wallet learns a denom
/// before it can be sent one privately.
public struct AssetDenoms {
    private var byID: [Fr: String] = [:]

    public init<S: Sequence>(_ known: S) where S.Element == String {
        for d in ["uerth", "uanml"] { learn(d) }
        for d in known { learn(d) }
    }

    public init() { self.init([String]()) }

    public mutating func learn(_ denom: String) {
        guard !denom.isEmpty, !denom.hasPrefix(NotePlaintext.unresolvedPrefix) else { return }
        byID[PrivacyHash.assetID(denom)] = denom
    }

    public func resolve(_ asset: Fr) -> String { byID[asset] ?? NotePlaintext.unresolvedPrefix + asset.hex }
}

extension Fr: Codable {
    public init(from decoder: Decoder) throws {
        let s = try decoder.singleValueContainer().decode(String.self)
        try self.init(hex: s)
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(hex)
    }
}
