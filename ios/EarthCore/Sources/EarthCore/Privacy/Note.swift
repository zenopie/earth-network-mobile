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
    /// That tx's timeout_height: the note is released only once the chain is past it (nil: a pre-timeout mark).
    public var pendingUntil: UInt64?
    /// That tx's hash: released only once the chain says it is missing or failed (nil: a mark made before marks carried the hash).
    public var pendingTx: String?

    public init(position: UInt64, height: UInt64, note: NotePlaintext, cm: Fr, nf: Fr, spentHeight: UInt64? = nil, pendingAt: Int64? = nil,
                pendingUntil: UInt64? = nil) {
        self.position = position; self.height = height; self.note = note; self.cm = cm; self.nf = nf
        self.spentHeight = spentHeight; self.pendingAt = pendingAt; self.pendingUntil = pendingUntil
    }

    public var unspent: Bool { spentHeight == nil }

    /// The same note under another name for its asset (an "asset/<hex>" note whose id is now known).
    func withDenom(_ denom: String) -> OwnedNote {
        var n = OwnedNote(position: position, height: height,
                          note: NotePlaintext(denom: denom, value: note.value, rho: note.rho, rcm: note.rcm, memo: note.memo),
                          cm: cm, nf: nf, spentHeight: spentHeight, pendingAt: pendingAt, pendingUntil: pendingUntil)
        n.pendingTx = pendingTx
        return n
    }
}

/// A stake note's slash label (ORCHARD_DESIGN 20.6): the note holds
/// `exposed` derth a private redelegation credited, the move `moveKey` (its
/// credit nullifier) named at `moveTime` (unix seconds). Until the move's
/// window closes (moveTime + the chain's label window) a slash of the move's
/// source may still cut it, and it cannot leave the note; after, any lane-A
/// proof clears it at what the slash debt tree says it is worth.
public struct StakeLabel: Hashable, Sendable, Codable {
    public let moveKey: Fr
    public let moveTime: UInt64
    public let exposed: UInt64

    public init(moveKey: Fr, moveTime: UInt64, exposed: UInt64) {
        precondition(!moveKey.isZero && moveTime > 0 && exposed > 0, "a label names its move, time and a positive exposure")
        self.moveKey = moveKey; self.moveTime = moveTime; self.exposed = exposed
    }

    public var hash: Fr { PrivacyHash.stakeLabel(moveKey: moveKey, moveTime: moveTime, exposed: exposed) }

    /// The label field of a stake commitment: 0 for none.
    public static func hash(_ l: StakeLabel?) -> Fr { l?.hash ?? .zero }
}

/// A stake note the wallet owns (x/shieldedstaking's stake tree): delegated
/// stake (derth/<valoper>). One per validator as a rule (every delegation,
/// unlock and redelegation merges into it); a second appears only beside a
/// labelled note or from another device. Owner-locked: it can be merged,
/// undelegated, redelegated, voted or locked by its owner, never sent.
///
///     spc = H(TAG_SPC, owner_pk, rho, rcm)    cm = H(TAG_STAKE, AssetID(denom), amount, spc, label)
///     nf  = H(TAG_SNF, nk, rho, position)
public struct OwnedStakeNote: Hashable, Sendable, Codable {
    public let position: UInt64
    public let height: UInt64
    public let denom: String
    public let amount: UInt64
    public let rho: Fr
    public let rcm: Fr
    public let cm: Fr
    public let nf: Fr
    public var spentHeight: UInt64?
    public var pendingAt: Int64?
    /// The spending tx's timeout_height (see OwnedNote.pendingUntil).
    public var pendingUntil: UInt64?
    /// The spending tx's hash (see OwnedNote.pendingTx).
    public var pendingTx: String?
    /// The redelegation exposure it holds, nil for an unlabelled note.
    public var label: StakeLabel?

    public init(position: UInt64, height: UInt64, denom: String, amount: UInt64, rho: Fr, rcm: Fr, cm: Fr, nf: Fr,
                spentHeight: UInt64? = nil, pendingAt: Int64? = nil, pendingUntil: UInt64? = nil, label: StakeLabel? = nil) {
        self.position = position; self.height = height; self.denom = denom; self.amount = amount
        self.rho = rho; self.rcm = rcm; self.cm = cm; self.nf = nf; self.spentHeight = spentHeight; self.pendingAt = pendingAt
        self.pendingUntil = pendingUntil; self.label = label
    }

    public var unspent: Bool { spentHeight == nil }
    public var spendable: Bool { unspent && pendingAt == nil && amount > 0 }
}

/// Asset id -> denom, for the ids note ciphertexts carry. Seeded with the fee
/// and personhood denoms. It learns only denoms the wallet
/// has reason to trust (a note of its own whose cm the denom reproduces, or
/// the chain's asset list, each entry checked against its id), never a public
/// amount an indexer merely serves; only well-formed denoms (`Denoms.valid`),
/// and at most `Denoms.max` of them. Built once per sync and grown as it goes.
public struct AssetDenoms {
    private var byID: [Fr: String] = [:]

    public init<S: Sequence>(_ known: S) where S.Element == String {
        for d in ["uerth", "uanml"] { learn(d) }
        for d in known { learn(d) }
    }

    public init() { self.init([String]()) }

    /// Whether `denom` is new here (false: invalid, known already, or the set is full).
    @discardableResult
    public mutating func learn(_ denom: String) -> Bool {
        guard Denoms.valid(denom), byID.count < Denoms.max else { return false }
        return byID.updateValue(denom, forKey: PrivacyHash.assetID(denom)) == nil
    }

    /// Learn `denom` under an id the chain stated for it: only if the id is the denom's own.
    @discardableResult
    public mutating func learn(_ denom: String, id: Fr) -> Bool {
        guard Denoms.valid(denom), byID.count < Denoms.max, byID[id] == nil else { return false }
        guard PrivacyHash.assetID(denom) == id else { return false }
        byID[id] = denom
        return true
    }

    public var denoms: Set<String> { Set(byID.values) }

    public func resolve(_ asset: Fr) -> String { byID[asset] ?? NotePlaintext.unresolvedPrefix + asset.hex }
}

/// Which denoms the wallet accepts from outside: the SDK's
/// own denom rule (`[a-zA-Z][a-zA-Z0-9/:._-]{2,127}`), and never the wallet's
/// internal "asset/<hex>" name for an id it cannot resolve.
public enum Denoms {
    /// The most denoms a wallet keeps (learned and persisted).
    public static let max = 4096

    public static func valid(_ denom: String) -> Bool {
        let b = Array(denom.utf8)
        guard (3 ... 128).contains(b.count), !denom.hasPrefix(NotePlaintext.unresolvedPrefix) else { return false }
        func letter(_ c: UInt8) -> Bool { (c >= 0x41 && c <= 0x5A) || (c >= 0x61 && c <= 0x7A) }
        guard letter(b[0]) else { return false }
        return b.dropFirst().allSatisfy { c in
            letter(c) || (c >= 0x30 && c <= 0x39) || c == 0x2F || c == 0x3A || c == 0x2E || c == 0x5F || c == 0x2D
        }
    }
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
