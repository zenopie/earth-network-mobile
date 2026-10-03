import Foundation

/// x/shielded Query/Root: a root the chain recorded, whether it is still an anchor, and its tree size.
public struct NoteRootRecord: Sendable, Equatable {
    public let valid: Bool
    public let treeSize: UInt64
    public init(valid: Bool, treeSize: UInt64) { self.valid = valid; self.treeSize = treeSize }
}

/// A tree's state as the chain reports it: size and latest recorded root
/// (nil for an empty tree); `pinned` only when the node answered at exactly
/// the height asked for (its echoed `x-cosmos-block-height`), false when that
/// height was unavailable or the echo differed and the state is some other
/// height's (K9).
public struct TreeState: Sendable, Equatable {
    public let size: UInt64
    public let root: Fr?
    public let pinned: Bool
    public init(size: UInt64, root: Fr?, pinned: Bool = true) { self.size = size; self.root = root; self.pinned = pinned }
}

/// The chain's own view of the three trees (the LCD in the app, the fake
/// chain in tests), against which every tree the indexer served is checked
/// (audit C3): an indexer can omit, add or forge rows, and a wallet that
/// trusted it would show forged notes and build proofs nobody accepts.
public protocol ChainRoots: Sendable {
    /// x/shielded Query/Root for `root`: nil when the chain never recorded it.
    func noteRoot(_ root: Fr) async throws -> NoteRootRecord?
    /// x/personhood Query/IdentityTree at `height` (latest when nil).
    func identityTree(height: UInt64?) async throws -> TreeState
    /// x/shieldedstaking Query/StakeTree at `height` (latest when nil).
    func stakeTree(height: UInt64?) async throws -> TreeState
    /// x/shielded Query/Nullifier: whether `nf` is spent (nil: the node could not say).
    func nullifierSpent(_ nf: Fr) async -> Bool?
    /// x/shieldedstaking Query/StakeNullifier likewise.
    func stakeNullifierSpent(_ nf: Fr) async -> Bool?
    /// The chain's latest block height (nil: unknown).
    func latestHeight() async -> UInt64?
    /// A block's time (unix seconds): a registration's activated_at (K1). Nil when the node cannot say.
    func blockTime(_ height: UInt64) async -> UInt64?
    /// The LCD's chain id and genesis key (first block hash, 16 hex); nil when it cannot say (K6).
    func chainIdentity() async -> ChainIdentity?
}

public extension ChainRoots {
    func nullifierSpent(_ nf: Fr) async -> Bool? { nil }
    func stakeNullifierSpent(_ nf: Fr) async -> Bool? { nil }
    func latestHeight() async -> UInt64? { nil }
    func blockTime(_ height: UInt64) async -> UInt64? { nil }
    func chainIdentity() async -> ChainIdentity? { nil }
}

/// Which chain the LCD serves: its chain id and the first 16 lowercase hex digits of its block 1 hash (nil: unavailable).
public struct ChainIdentity: Sendable, Equatable {
    public let chainID: String
    public let genesis: String?
    public init(chainID: String, genesis: String?) { self.chainID = chainID; self.genesis = genesis }
}

/// Brings a wallet's `PrivacyStore` up to the indexer's tip. Ports
/// `privacy/sync/WalletSync.kt`:
///
///  1. `/privacy/status`: refuse a halted indexer or another chain; a new
///     (chain id, genesis) wipes the local data;
///  2. every note commitment, appended to the local note tree, every
///     ciphertext trial-decrypted with this wallet's ek (v1, or v2 against
///     the row's public amount: one note-discovery rule, no counters);
///  3. every nullifier, up to the height the notes reached;
///  4. the stake tree the same way (the wallet's own stake ciphertexts, and
///     the blind stake ciphertexts of the notes the chain minted);
///  5. every identity leaf and zeroing up to the same height; registration
///     record notes matched to their leaf (restore), a pending registration
///     resolved (C2);
///  6. the local roots checked against the indexer's latest (repeating the
///     pass while the indexer moves) and then against the chain's own (C3).
///
/// Nothing is ever requested about one note or one leaf: the trees, and with
/// them this wallet's Merkle paths, are built here from the full streams.
public final class WalletSync {
    public struct Inconsistent: Swift.Error, LocalizedError {
        public let message: String
        public var errorDescription: String? { message }
    }

    /// The chain disagrees with what the indexer served: nothing synced is trusted (C3).
    public struct ChainMismatch: Swift.Error, LocalizedError {
        public let message: String
        public var errorDescription: String? { message }
    }

    /// The indexer names another genesis the LCD does not confirm: nothing is wiped, nothing synced (K6).
    public struct GenesisUnverified: Swift.Error, LocalizedError {
        public let message: String
        public var errorDescription: String? { message }
    }

    public struct Result: Sendable {
        public let syncedHeight: UInt64
        public let newNotes: [OwnedNote]
        public let spent: [OwnedNote]
        public let noteRoot: Fr
        public let identityRoot: Fr
        public let newStake: [OwnedStakeNote]
        public let identityStatus: IdentityStatus
        /// Whether every root matched the chain's (false: the indexer moved too fast to pin; sync again).
        public let verified: Bool
    }

    public enum IdentityStatus: Sendable, Equatable { case none, live, zeroed }

    public static let pendingTimeout: Int64 = 15 * 60
    /// The most rows a page may carry (the backend's PRIVACY_PAGE_MAX).
    public static let maxPageRows = 5000
    /// Passes over the streams while the indexer keeps moving, before giving up on pinning a height.
    static let maxPasses = 4
    /// The largest tree position the circuits take (u32).
    static let maxPosition: UInt64 = 0xffff_ffff

    /// Nullifiers of this sync's streams (pool, stake) spot-checked against the chain (K9).
    public static let nullifierSample = 4
    /// Blocks the indexer may trail the chain by before what it served is labelled stale (K9).
    public static let staleBlocks: UInt64 = 30

    /// Registration record memo: "ER", version 2 (PRIVACY_FORMATS.md 3a). Version 1 (untagged) is ignored.
    static let regMagic = Data([0x45, 0x52, 0x02])
    /// Bytes of the record memo's tag.
    static let regTagBytes = 16

    /// The record's tag: the first 16 bytes of H(TAG_RECTAG, nk, dsc_key, U64(built_at)). Only the owner (nk) can make one.
    public static func regTag(nk: Fr, dscKey: Fr, builtAt: UInt64) -> Data {
        PrivacyHash.h(PrivacyHash.tagRecTag, nk, dscKey, PrivacyHash.u64(builtAt)).bytes.prefix(regTagBytes)
    }

    /// The 64-byte memo of a registration record note.
    public static func regMemo(nk: Fr, dscKey: Fr, country: String, builtAt: UInt64) -> Data {
        var b = regMagic
        let c = country.uppercased()
        let valid = c.utf8.count == 2 && c.utf8.allSatisfy { (0x41 ... 0x5a).contains($0) }
        b += valid ? Data(c.utf8) : Data(count: 2)
        b += PrivateMsgs.be64(builtAt)
        b += dscKey.bytes
        b += regTag(nk: nk, dscKey: dscKey, builtAt: builtAt)
        return b + Data(count: NoteCipher.memoBytes - b.count)
    }

    /// (dsc_key, country, built_at) if `memo` is a version-2 registration
    /// record whose tag is `nk`'s (K1): anyone can send this wallet a value-0
    /// note with any memo, and an untagged record would cost a leaf search
    /// per leaf at its height. The tag is checked before anything else is
    /// done with it.
    public static func parseRegMemo(nk: Fr, _ memo: Data) -> (dscKey: Fr, country: String, builtAt: UInt64)? {
        guard memo.count <= NoteCipher.memoBytes else { return nil }
        let m = [UInt8](memo) + [UInt8](repeating: 0, count: NoteCipher.memoBytes - memo.count)
        guard Data(m[0 ..< 3]) == regMagic else { return nil }
        let c = m[3 ..< 5]
        let country: String
        if c.allSatisfy({ $0 == 0 }) { country = "" } else if c.allSatisfy({ (0x41 ... 0x5a).contains($0) }) {
            country = String(decoding: c, as: UTF8.self)
        } else { return nil }
        let builtAt = m[5 ..< 13].reduce(UInt64(0)) { $0 << 8 | UInt64($1) }
        guard let dsc = try? Fr(bytes: Data(m[13 ..< 45])) else { return nil }
        let tag = Data(m[45 ..< 45 + regTagBytes])
        guard m[(45 + regTagBytes)...].allSatisfy({ $0 == 0 }) else { return nil }
        guard constantTimeEqual(tag, regTag(nk: nk, dscKey: dsc, builtAt: builtAt)) else { return nil }
        return (dsc, country, builtAt)
    }

    static func constantTimeEqual(_ a: Data, _ b: Data) -> Bool {
        guard a.count == b.count else { return false }
        return zip(a, b).reduce(UInt8(0)) { $0 | ($1.0 ^ $1.1) } == 0
    }

    /// Unlock memo: "EU", version 1 (PRIVACY_FORMATS.md 1, K11).
    static let unlockMagic = Data([0x45, 0x55, 0x01])

    static func unlockTag(nk: Fr, counter: UInt32) -> Data {
        PrivacyHash.h(PrivacyHash.tagUnlockTag, nk, PrivacyHash.u64(UInt64(counter))).bytes.prefix(regTagBytes)
    }

    /// The memo of an unlock's re-minted stake note: the owner-tag counter of
    /// the position it closed, so a wallet restored from the mnemonic knows
    /// the tags of closed positions too and never locks under one again
    /// (K11). Tagged like the record (only nk makes one): a gift of stake
    /// carrying a huge counter cannot stretch the owner-tag scan.
    public static func unlockMemo(nk: Fr, counter: UInt32) -> Data {
        var b = unlockMagic
        b += Data((0 ..< 4).map { UInt8(truncatingIfNeeded: counter >> UInt32(24 - 8 * $0)) })
        b += unlockTag(nk: nk, counter: counter)
        return b + Data(count: NoteCipher.memoBytes - b.count)
    }

    /// The closed counter if `memo` is this wallet's unlock memo.
    public static func parseUnlockMemo(nk: Fr, _ memo: Data) -> UInt32? {
        guard memo.count <= NoteCipher.memoBytes else { return nil }
        let m = [UInt8](memo) + [UInt8](repeating: 0, count: NoteCipher.memoBytes - memo.count)
        guard Data(m[0 ..< 3]) == unlockMagic else { return nil }
        let counter = m[3 ..< 7].reduce(UInt32(0)) { $0 << 8 | UInt32($1) }
        guard counter <= UInt32(Int32.max) else { return nil }
        guard m[(7 + regTagBytes)...].allSatisfy({ $0 == 0 }) else { return nil }
        guard constantTimeEqual(Data(m[7 ..< 7 + regTagBytes]), unlockTag(nk: nk, counter: counter)) else { return nil }
        return counter
    }

    /// Record notes kept (newest first); only this wallet's own registrations carry a valid tag.
    public static let maxRecords = 32
    /// Identity leaves kept per record (registrations sharing its block).
    public static let maxRecordLeaves = 64
    /// Leaf hashes the fallback search may spend in one sync, across all
    /// records: bounds how long a sync holds the wallet lock.
    public static let syncSearchBudget: UInt64 = 50_000
    /// Leaf hashes the fallback search may spend on one record before it is given up.
    public static let recordSearchCap: UInt64 = 4_000_000
    /// The fallback windows around built_at (seconds before, after): hinted countries, then every other.
    static let narrowBefore: UInt64 = 3_600, narrowAfter: UInt64 = 86_400
    static let wideBefore: UInt64 = 600, wideAfter: UInt64 = 3_600

    /// The `i`th offset of an outward walk over [-before, after]: 0, +1, -1,
    /// +2, -2, ..., then the longer side alone.
    static func offsetAt(_ i: UInt64, before: UInt64, after: UInt64) -> Int64 {
        let m = min(before, after)
        if i <= 2 * m {
            if i == 0 { return 0 }
            return i % 2 == 1 ? Int64((i + 1) / 2) : -Int64(i / 2)
        }
        let k = Int64(m + (i - 2 * m))
        return after >= before ? k : -k
    }

    /// A uniform sample of up to `n` of everything offered (reservoir): nothing about which are ours.
    final class Reservoir {
        private let n: Int
        private(set) var items: [Fr] = []
        private var seen: UInt64 = 0
        init(_ n: Int) { self.n = n }
        func offer(_ x: Fr) {
            seen += 1
            if items.count < n { items.append(x); return }
            let j = UInt64.random(in: 0 ..< seen)
            if j < UInt64(n) { items[Int(j)] = x }
        }
    }

    /// Every country the chain's leaf may commit to: unknown (0), then each A..Z pair.
    static let allCountries: [Fr] = {
        var out: [Fr] = [.zero]
        let az = UInt8(ascii: "A") ... UInt8(ascii: "Z")
        for a in az { for b in az { out.append(PrivacyHash.countryField(String(bytes: [a, b], encoding: .ascii)!)) } }
        return out
    }()

    static func countryOrZero(_ c: String) -> Fr { c.isEmpty ? .zero : PrivacyHash.countryField(c) }

    private let indexer: PrivacyIndexer
    private let store: PrivacyStore
    private let keys: PrivacyKeys
    private let chainID: String
    private let chain: ChainRoots
    private let now: () -> Int64
    private let searchBudget: UInt64
    private let poolSample = Reservoir(WalletSync.nullifierSample)
    private let stakeSample = Reservoir(WalletSync.nullifierSample)

    public init(indexer: PrivacyIndexer, store: PrivacyStore, keys: PrivacyKeys, chainID: String, chain: ChainRoots,
                now: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970) }, searchBudget: UInt64 = WalletSync.syncSearchBudget) {
        self.indexer = indexer; self.store = store; self.keys = keys; self.chainID = chainID; self.chain = chain; self.now = now
        self.searchBudget = searchBudget
    }

    /// Syncs; on an inconsistency with the indexer, starts over once from an empty store.
    public func sync(pageLimit: Int? = nil) async throws -> Result {
        do {
            return try await syncRetryingBase(pageLimit)
        } catch is Inconsistent {
            store.reset(chainID: chainID)
            return try await syncRetryingBase(pageLimit)
        }
    }

    /// A 404 means the indexer's base moved (a relaunch): read the status again, once.
    private func syncRetryingBase(_ limit: Int?) async throws -> Result {
        do {
            return try await syncOnce(limit)
        } catch is IndexerBaseMoved {
            return try await syncOnce(limit)
        }
    }

    private func syncOnce(_ limit: Int?) async throws -> Result {
        let status = try await indexer.status()
        if let h = status.halted { throw IndexerHalted(h) }
        guard let c = status.chainID else { throw PrivacyError("the privacy indexer names no chain yet") }
        if c != chainID { throw PrivacyError("the privacy indexer follows \(c), not \(chainID)") }
        if store.state.chainID != chainID || store.state.genesis != status.genesis { try await switchChain(status.genesis) }
        var newNotes: [OwnedNote] = []
        var spent: [OwnedNote] = []
        var newStake: [OwnedStakeNote] = []
        var roots: LatestRoots
        var pass = 0
        while true {
            newNotes += try await syncNotes(limit)
            spent += try await syncNullifiers(limit)
            newStake += try await syncStakeNotes(limit)
            try await syncStakeNullifiers(limit)
            try await syncIdentity(limit)
            roots = try await indexer.rootsLatest()
            pass += 1
            if try atIndexerTip(roots) || pass >= Self.maxPasses { break }
        }
        releaseStalePending()
        // Once a sync, after every pass: the record search is budgeted per sync (K1).
        await matchRecords()
        resolvePending()
        let verified = try await verifyRoots(roots)
        store.save()
        return Result(syncedHeight: store.state.notesHeight, newNotes: newNotes, spent: spent, noteRoot: store.noteTree.root(),
                      identityRoot: store.identityTree.root(), newStake: newStake, identityStatus: identityStatus(), verified: verified)
    }

    /// K6: the indexer names a (chain id, genesis) other than the store's.
    /// The LCD must confirm it (its chain id, its block 1 hash) before
    /// anything is wiped; an indexer's word alone never drops the identity.
    /// A first sync goes ahead when the LCD cannot say (the root checks still
    /// guard it) but not when it says otherwise. A confirmed relaunch keeps
    /// the identity record (its leaf is re-verified against the new tree by
    /// `identityStatus`), the pending registration and the passport
    /// nullifier; everything else is the old chain's and goes.
    private func switchChain(_ genesis: String?) async throws {
        let old = store.state
        let switching = old.chainID == chainID && old.genesis != nil
        let id = await chain.chainIdentity()
        let confirmed = id != nil && id!.chainID == chainID && id!.genesis != nil && id!.genesis == genesis
        let contradicted = id != nil && (id!.chainID != chainID || (id!.genesis != nil && id!.genesis != genesis))
        if genesis == nil || contradicted || (switching && !confirmed) {
            let m = "unverified: the indexer names genesis \(genesis ?? "none"), which the chain does not confirm" +
                (id.map { " (the LCD serves \($0.chainID), genesis \($0.genesis ?? "unknown"))" } ?? " (the LCD could not say)")
            store.mutate { $0.rootsVerified = false; $0.rootsError = m }
            store.save()
            throw GenesisUnverified(message: m)
        }
        if switching { store.switchGenesis(genesis!) } else { store.reset(chainID: chainID, genesis: genesis) }
    }

    /// Whether every local tree is the indexer's latest; a tree of the same
    /// size with another root, or a larger one, is inconsistent (the stream
    /// and the roots disagree), a smaller one just means the indexer moved on.
    private func atIndexerTip(_ roots: LatestRoots) throws -> Bool {
        var tip = true
        func check(_ name: String, _ r: RootRecord?, _ tree: MerkleTree) throws {
            let size = r?.treeSize ?? 0
            // More than the indexer now says it has: what it served before is not its tree.
            if tree.size > size { throw Inconsistent(message: "\(name) tree holds \(tree.size) leaves, the indexer's latest \(size)") }
            if size != tree.size { tip = false; return }
            if let r, r.root != tree.root() { throw Inconsistent(message: "\(name) tree root differs from the indexer's at \(size)") }
        }
        try check("note", roots.note, store.noteTree)
        try check("identity", roots.identity, store.identityTree)
        try check("stake", roots.stake, store.stakeTree)
        return tip
    }

    /// C3: every local root against the chain's own (the LCD:
    /// PRIVACY_FORMATS 4b says what that trusts). Only a positive
    /// contradiction is a mismatch, which wipes the synced data and throws
    /// `ChainMismatch`: a note root the chain recorded at another tree size,
    /// or an identity or stake tree that differs from the chain's read at
    /// exactly the indexer's root height (the node echoed that height).
    /// Everything that cannot be established leaves the roots unverified,
    /// and the wallet builds nothing on them and labels what it shows until a
    /// later sync verifies them (K8, K9): a note root the chain no longer
    /// holds (pruned after its window: an indexer far behind, or a forged
    /// root; the two look the same), a tree read at another height, the
    /// indexer still moving, a sampled nullifier the chain does not hold
    /// spent, or the indexer trailing the chain's tip.
    private func verifyRoots(_ roots: LatestRoots) async throws -> Bool {
        var problems: [String] = []
        var mismatch: String?
        if store.noteTree.size > 0 {
            let rec = try await chain.noteRoot(store.noteTree.root())
            if rec == nil {
                problems.append("unverified: the chain no longer holds the indexer's note root (the indexer is behind, or its notes are not the chain's)")
            } else if rec!.treeSize != store.noteTree.size {
                mismatch = "the chain recorded the indexer's note root at \(rec!.treeSize) notes, not \(store.noteTree.size)"
            } else if !rec!.valid {
                problems.append("unverified: the indexer is too far behind the chain (its note root is no longer an anchor)")
            }
        }
        let tip = try atIndexerTip(roots)
        if !tip { problems.append("unverified: the indexer kept moving; sync again") }
        func tree(_ name: String, _ local: MerkleTree, _ r: RootRecord?, _ read: (UInt64?) async throws -> TreeState) async throws {
            // Pinned to the indexer's root height, which is only the local
            // tree's when the local tree is the indexer's latest.
            if mismatch != nil || !tip { return }
            let height: UInt64? = (r?.height).flatMap { $0 > 0 ? $0 : nil } ?? (roots.syncedHeight > 0 ? roots.syncedHeight : nil)
            let t = try await read(height)
            let localRoot: Fr? = local.size == 0 ? nil : local.root()
            if t.size == local.size && (t.root == localRoot || (local.size == 0 && t.root == nil)) {
                return
            } else if t.pinned {
                mismatch = "the chain's \(name) tree (\(t.size)) at height \(height.map(String.init) ?? "latest") differs from the indexer's (\(local.size))"
            } else {
                problems.append("unverified: the \(name) tree could not be read at the indexer's height \(height.map(String.init) ?? "latest")")
            }
        }
        try await tree("identity", store.identityTree, roots.identity) { try await self.chain.identityTree(height: $0) }
        try await tree("stake", store.stakeTree, roots.stake) { try await self.chain.stakeTree(height: $0) }
        if let m = mismatch {
            store.reset(chainID: chainID)
            store.mutate { $0.rootsVerified = false; $0.rootsError = m }
            store.save()
            throw ChainMismatch(message: m)
        }
        // A sample of the spends this sync read, asked of the chain: an
        // indexer inventing spends is caught without asking about ours.
        var invented = false
        for nf in poolSample.items where await chain.nullifierSpent(nf) == false { invented = true }
        for nf in stakeSample.items where await chain.stakeNullifierSpent(nf) == false { invented = true }
        if invented { problems.append("unverified: the indexer reported a spend the chain does not hold") }
        if let tipHeight = await chain.latestHeight(), tipHeight > roots.syncedHeight, tipHeight - roots.syncedHeight > Self.staleBlocks {
            problems.append("unverified: the indexer is \(tipHeight - roots.syncedHeight) blocks behind the chain")
        }
        store.mutate { $0.rootsVerified = problems.isEmpty; $0.rootsError = problems.first }
        return problems.isEmpty
    }

    private func checkPage(_ n: Int) throws {
        if n > Self.maxPageRows { throw Inconsistent(message: "the indexer sent \(n) rows in one page") }
    }

    /// Positions must follow the cursor one by one and stay within the tree (L7).
    private func checkPositions(_ positions: [UInt64], from next: UInt64, _ what: String) throws {
        for (i, p) in positions.enumerated() {
            let (want, o) = next.addingReportingOverflow(UInt64(i))
            if o || p != want { throw Inconsistent(message: "\(what) at position \(p), expected \(o ? "none" : String(want))") }
            if p > Self.maxPosition { throw Inconsistent(message: "\(what) position \(p) beyond the tree") }
        }
    }

    private func syncNotes(_ limit: Int?) async throws -> [OwnedNote] {
        var found: [OwnedNote] = []
        while true {
            let page = try await indexer.notes(fromPos: store.state.notesNext, limit: limit)
            try checkPage(page.rows.count)
            if !page.rows.isEmpty {
                try checkPositions(page.rows.map(\.position), from: store.state.notesNext, "note")
                store.noteTree.appendAll(page.rows.map(\.cm))
                for r in page.rows {
                    if let n = open(r) {
                        found.append(n)
                        store.mutate { $0.notes.append(n) }
                    }
                }
                store.mutate { $0.notesNext += UInt64(page.rows.count) }
            }
            store.mutate { $0.notesHeight = max($0.notesHeight, page.syncedHeight) }
            if !page.complete { break }
        }
        return found
    }

    /// A note row is ours if its ciphertext opens with our ek and the opening
    /// reproduces the cm under our owner key: a v1 ciphertext (217 bytes,
    /// value inside), or a value-blind v2 one (177 bytes) against the asset
    /// and value the chain published on the row. Every note the chain mints
    /// carries v2, so the mnemonic alone finds everything. A value-0 v1 note
    /// is kept only as a registration record (its memo).
    func open(_ r: NoteRow) -> OwnedNote? {
        let amount = Self.publicAmount(r.amount)
        if let a = amount { store.mutate { _ = $0.denoms.insert(a.denom) } }
        let note: NotePlaintext
        switch r.ciphertext.count {
        case NoteCipher.blindCiphertextBytes:
            guard let a = amount, let n = NoteCipher.tryDecryptBlind(r.ciphertext, cm: r.cm, denom: a.denom, value: a.value, keys: keys) else { return nil }
            note = n
        case NoteCipher.ciphertextBytes:
            guard let n = NoteCipher.tryDecrypt(r.ciphertext, cm: r.cm, keys: keys, denoms: AssetDenoms(store.state.denoms)) else { return nil }
            note = n
        default:
            return nil
        }
        // A value past 2^63-1 is not one the wallet holds (PRIVACY_FORMATS 3, amounts; as Android).
        if note.value > UInt64(Int64.max) { return nil }
        if note.value == 0 {
            if let m = Self.parseRegMemo(nk: keys.nk, note.memo), !store.state.regRecords.contains(where: { $0.position == r.position }) {
                store.mutate { s in
                    s.regRecords.append(RegRecord(height: r.height, position: r.position, dscKey: m.dscKey, country: m.country, builtAt: m.builtAt))
                    if s.regRecords.count > Self.maxRecords, let i = s.regRecords.indices.min(by: { s.regRecords[$0].height < s.regRecords[$1].height }) {
                        s.regRecords.remove(at: i)
                    }
                }
            }
            return nil
        }
        return OwnedNote(position: r.position, height: r.height, note: note, cm: r.cm,
                         nf: PrivacyHash.nf(nk: keys.nk, rho: note.rho, position: r.position))
    }

    static func publicAmount(_ amount: String?) -> (value: UInt64, denom: String)? {
        guard let amount else { return nil }
        let digits = amount.prefix { $0.isASCII && $0.isNumber }
        let denom = amount.dropFirst(digits.count)
        guard !digits.isEmpty, !denom.isEmpty, let v = UInt64(digits), v <= UInt64(Int64.max) else { return nil }
        return (v, String(denom))
    }

    private func syncNullifiers(_ limit: Int?) async throws -> [OwnedNote] {
        var mine: [Fr: Int] = [:]
        for (i, n) in store.state.notes.enumerated() where n.unspent { mine[n.nf] = i }
        var spent: [OwnedNote] = []
        // Only up to the height the note stream reached: a note found next
        // time could otherwise have been spent in a block this pass skipped.
        let ceiling = store.state.notesHeight
        while store.state.nullifiersNext <= ceiling {
            let page = try await indexer.nullifiers(fromHeight: store.state.nullifiersNext, limit: limit)
            try checkPage(page.blocks.reduce(0) { $0 + $1.items.count })
            for (h, nfs) in page.blocks {
                if h > ceiling { break }
                for nf in nfs { poolSample.offer(nf) }
                for nf in nfs {
                    if let i = mine[nf] {
                        store.mutate { $0.notes[i].spentHeight = h }
                        spent.append(store.state.notes[i])
                    }
                }
            }
            store.mutate { $0.nullifiersNext = min(page.nextHeight, ceiling + 1) }
            if !page.complete { break }
        }
        return spent
    }

    /// A note marked pending by a broadcast whose nullifier has not appeared
    /// after `pendingTimeout` is released: the tx did not land, and the note
    /// is spendable again.
    private func releaseStalePending() {
        let t = now()
        store.mutate { s in
            for i in s.notes.indices {
                if s.notes[i].unspent, let p = s.notes[i].pendingAt, t - p > Self.pendingTimeout { s.notes[i].pendingAt = nil }
            }
            for i in s.stakeNotes.indices {
                if s.stakeNotes[i].unspent, let p = s.stakeNotes[i].pendingAt, t - p > Self.pendingTimeout { s.stakeNotes[i].pendingAt = nil }
            }
        }
    }

    private func syncStakeNotes(_ limit: Int?) async throws -> [OwnedStakeNote] {
        var found: [OwnedStakeNote] = []
        while true {
            let page = try await indexer.stakeNotes(fromPos: store.state.stakeNext, limit: limit)
            try checkPage(page.rows.count)
            if !page.rows.isEmpty {
                try checkPositions(page.rows.map(\.position), from: store.state.stakeNext, "stake note")
                store.stakeTree.appendAll(page.rows.map(\.cm))
                for r in page.rows {
                    if let n = openStake(r) {
                        found.append(n)
                        store.mutate { $0.stakeNotes.append(n) }
                    }
                }
                store.mutate { $0.stakeNext += UInt64(page.rows.count) }
            }
            store.mutate { $0.stakeHeight = max($0.stakeHeight, page.syncedHeight) }
            if !page.complete { break }
        }
        return found
    }

    /// A stake row is ours if its ciphertext opens: a stake proof's own
    /// output carries the wallet stake ciphertext (153 bytes, amount inside);
    /// a note the chain minted carries the blind stake ciphertext (177 bytes)
    /// of its secrets, checked against the denom and amount the chain
    /// published with it.
    func openStake(_ r: StakeNoteRow) -> OwnedStakeNote? {
        if let d = r.denom { store.mutate { _ = $0.denoms.insert(d) } }
        let denom: String, amount: UInt64, rho: Fr, rcm: Fr
        switch r.ciphertext.count {
        case NoteCipher.stakeCiphertextBytes:
            guard let o = NoteCipher.tryDecryptStake(r.ciphertext, cm: r.cm, keys: keys) else { return nil }
            denom = AssetDenoms(store.state.denoms).resolve(o.asset); amount = o.amount; rho = o.rho; rcm = o.rcm
        case NoteCipher.blindCiphertextBytes:
            guard let d = r.denom, let a = r.amount,
                  let o = NoteCipher.tryOpenBlindStake(r.ciphertext, cm: r.cm, denom: d, amount: a, keys: keys) else { return nil }
            denom = d; amount = a; rho = o.rho; rcm = o.rcm
            if let c = Self.parseUnlockMemo(nk: keys.nk, o.memo), c > (store.state.closedOtagMax ?? 0) || store.state.closedOtagMax == nil {
                store.mutate { $0.closedOtagMax = c }
            }
        default:
            return nil
        }
        // Zero, or past 2^63-1: nothing the wallet holds (as Android).
        guard amount > 0, amount <= UInt64(Int64.max) else { return nil }
        return OwnedStakeNote(position: r.position, height: r.height, denom: denom, amount: amount, rho: rho, rcm: rcm, cm: r.cm,
                              nf: PrivacyHash.stakeNF(nk: keys.nk, rho: rho, position: r.position))
    }

    private func syncStakeNullifiers(_ limit: Int?) async throws {
        var mine: [Fr: Int] = [:]
        for (i, n) in store.state.stakeNotes.enumerated() where n.unspent { mine[n.nf] = i }
        let ceiling = store.state.stakeHeight
        while store.state.stakeNullifiersNext <= ceiling {
            let page = try await indexer.stakeNullifiers(fromHeight: store.state.stakeNullifiersNext, limit: limit)
            try checkPage(page.blocks.reduce(0) { $0 + $1.items.count })
            for (h, nfs) in page.blocks {
                if h > ceiling { break }
                for nf in nfs { stakeSample.offer(nf) }
                for nf in nfs { if let i = mine[nf] { store.mutate { $0.stakeNotes[i].spentHeight = h } } }
            }
            store.mutate { $0.stakeNullifiersNext = min(page.nextHeight, ceiling + 1) }
            if !page.complete { break }
        }
    }

    /// Identity leaves and zeroings, up to the height the notes reached (so a
    /// registration's record note is always seen no later than its leaf).
    /// Each appended leaf at the height of an unmatched record note is tried
    /// against it: that is how a wallet restored from the mnemonic finds its
    /// registration, with no query naming it.
    private func syncIdentity(_ limit: Int?) async throws {
        let ceiling = store.state.notesHeight
        // Zeroings of leaves already held, first: a leaf appended below
        // carries its own zeroed_height.
        while store.state.zeroedNext <= ceiling {
            let page = try await indexer.identityZeroed(fromHeight: store.state.zeroedNext, limit: limit)
            try checkPage(page.blocks.reduce(0) { $0 + $1.items.count })
            var updates: [UInt64: Fr] = [:]
            for (h, idxs) in page.blocks {
                if h > ceiling { break }
                for i in idxs where i < store.identityTree.size { updates[i] = .zero }
            }
            store.identityTree.updateAll(updates)
            store.mutate { $0.zeroedNext = min(page.nextHeight, ceiling + 1) }
            if !page.complete { break }
        }
        let recordHeights = Set(store.state.regRecords.map(\.height))
        while true {
            let page = try await indexer.identity(fromIndex: store.state.identityNext, limit: limit)
            try checkPage(page.rows.count)
            if page.rows.isEmpty { break }
            let take = Array(page.rows.prefix { $0.height <= ceiling })
            try checkPositions(take.map(\.index), from: store.state.identityNext, "identity leaf")
            // A leaf zeroed at or below the ceiling is zero here; one zeroed
            // later is zeroed by a later pass's zeroed stream.
            store.identityTree.appendAll(take.map { r in r.zeroedHeight.map { $0 <= ceiling } == true ? .zero : r.leaf })
            // Each record keeps the leaves of its block as they pass (persisted: never streamed again).
            for r in take where recordHeights.contains(r.height) {
                let leaf = store.identityTree.leaf(r.index)
                store.mutate { s in
                    for k in s.regRecords.indices where s.regRecords[k].height == r.height && s.regRecords[k].leaves.count < Self.maxRecordLeaves
                        && !s.regRecords[k].leaves.contains(where: { $0.index == r.index }) {
                        s.regRecords[k].leaves.append(RegRecord.Leaf(index: r.index, leaf: leaf))
                    }
                }
            }
            store.mutate { $0.identityNext += UInt64(take.count) }
            if take.count < page.rows.count || store.state.identityNext >= page.size { break }
        }
    }

    /// Matches record notes to the leaves appended at their heights (several
    /// registrations may share a block), newest record first, stopping at the
    /// newest that matched (it is the identity). K1, bounded:
    ///
    ///  1. activated_at is the registration block's time, so the chain's time
    ///     for the record's height (the LCD) is tried first: every country
    ///     (hint, unknown, then A..Z) at exactly that time, at most 677 hashes
    ///     a leaf. Known and unmatched, the record is given up.
    ///  2. Only when the chain cannot say (a pruned block), the device
    ///     clock's built_at is searched outward, hinted countries over [-1h,
    ///     +24h], then every other over [-10min, +1h], resumably: the cursor
    ///     and the hashes spent are persisted, each sync spends at most
    ///     `searchBudget` hashes over all records and a record at most
    ///     `recordSearchCap` before it is given up.
    private func matchRecords() async {
        var budget = Int64(searchBudget)
        for rec0 in store.state.regRecords.sorted(by: { $0.height > $1.height }) {
            if rec0.status == .matched { return }
            if rec0.status == .exhausted { continue }
            let leaves = rec0.leaves.filter { $0.leaf != .zero }
            if leaves.isEmpty { continue }
            guard let k = store.state.regRecords.firstIndex(where: { $0.position == rec0.position }) else { continue }
            var rec = rec0
            var found: (UInt64, Fr, UInt64)?
            if let time = await chain.blockTime(rec0.height) {
                var countries = [Self.countryOrZero(rec0.country)]
                for c in Self.allCountries where c != countries[0] { countries.append(c) }
                outer: for l in leaves {
                    for c in countries where PrivacyHash.identityLeaf(idc: keys.idc, dscKey: rec0.dscKey, country: c, activatedAt: time) == l.leaf {
                        found = (l.index, c, time)
                        break outer
                    }
                }
                rec.status = found != nil ? .matched : .exhausted
                rec.work += UInt64(countries.count * leaves.count)
            } else {
                let r = search(rec0, leaves, budget: UInt64(max(budget, 0)))
                found = r.found
                rec = r.rec
                budget -= Int64(rec.work - rec0.work)
            }
            let updated = rec
            store.mutate { $0.regRecords[k] = updated }
            if let (index, country, at) = found {
                let cur = store.state.identity
                if cur == nil || index > cur!.leafIndex {
                    let nullifier = (cur?.leafIndex == index ? cur?.passportNullifier : nil) ?? ""
                    store.mutate {
                        $0.identity = IdentityRecord(leafIndex: index, dscKey: rec.dscKey, country: country, activatedAt: at, passportNullifier: nullifier)
                    }
                }
                return
            }
            if budget <= 0 { return }
        }
    }

    /// The fallback search for `rec` from its persisted cursor, spending at
    /// most `budget` hashes (and the record's cap).
    private func search(_ rec: RegRecord, _ leaves: [RegRecord.Leaf], budget: UInt64) -> (found: (UInt64, Fr, UInt64)?, rec: RegRecord) {
        var hinted = [Self.countryOrZero(rec.country)]
        if hinted[0] != .zero { hinted.append(.zero) }
        let hs = Set(hinted)
        let others = Self.allCountries.filter { !hs.contains($0) }
        let narrowSteps = Self.narrowBefore + Self.narrowAfter + 1
        let total = narrowSteps + Self.wideBefore + Self.wideAfter + 1
        var out = rec
        var spent: UInt64 = 0
        while out.cursor < total {
            let narrow = out.cursor < narrowSteps
            let countries = narrow ? hinted : others
            let cost = UInt64(countries.count * leaves.count)
            if spent > 0 && spent + cost > budget { break }
            if out.work + cost > Self.recordSearchCap { out.status = .exhausted; return (nil, out) }
            let off = narrow ? Self.offsetAt(out.cursor, before: Self.narrowBefore, after: Self.narrowAfter)
                : Self.offsetAt(out.cursor - narrowSteps, before: Self.wideBefore, after: Self.wideAfter)
            out.cursor += 1
            out.work += cost
            spent += cost
            let t = Int64(clamping: rec.builtAt) + off
            if t < 0 { continue }
            for l in leaves {
                for c in countries where PrivacyHash.identityLeaf(idc: keys.idc, dscKey: rec.dscKey, country: c, activatedAt: UInt64(t)) == l.leaf {
                    out.status = .matched
                    return ((l.index, c, UInt64(t)), out)
                }
            }
        }
        out.status = out.cursor >= total ? .exhausted : .open
        return (nil, out)
    }

    /// C2: a committed registration whose leaf the wallet has not matched
    /// yet. Once the local identity tree holds its index, the leaf is
    /// recomputed for the hinted country, unknown, and every A..Z pair; the
    /// identity record replaces the pending one. It is never dropped
    /// unmatched: a failure is recorded for the UI and retried.
    private func resolvePending() {
        guard var p = store.state.pendingRegistration else { return }
        // Not committed yet as far as the wallet knows (PrivacyWallet.sync looks it up by hash).
        guard let index = p.leafIndex, let activatedAt = p.activatedAt else { return }
        if index >= store.identityTree.size {
            p.failure = nil
            store.mutate { $0.pendingRegistration = p }
            return
        }
        let leaf = store.identityTree.leaf(index)
        if let country = countryFor(leaf: leaf, dscKey: p.dscKey, activatedAt: activatedAt, hint: p.countryHint) {
            store.mutate {
                $0.identity = IdentityRecord(leafIndex: index, dscKey: p.dscKey, country: country, activatedAt: activatedAt,
                                             passportNullifier: p.passportNullifier)
                $0.pendingRegistration = nil
            }
        } else {
            p.failure = leaf == .zero ? "the registration's leaf \(index) has been zeroed" : "leaf \(index) does not match this registration"
            store.mutate { $0.pendingRegistration = p }
        }
    }

    func countryFor(leaf: Fr, dscKey: Fr, activatedAt: UInt64, hint: String = "") -> Fr? {
        if leaf == .zero { return nil }
        return ([Self.countryOrZero(hint)] + Self.allCountries).first {
            PrivacyHash.identityLeaf(idc: keys.idc, dscKey: dscKey, country: $0, activatedAt: activatedAt) == leaf
        }
    }

    public func identityStatus() -> IdentityStatus {
        Self.identityStatus(store: store, keys: keys)
    }

    static func identityStatus(store: PrivacyStore, keys: PrivacyKeys) -> IdentityStatus {
        guard let id = store.state.identity, id.leafIndex < store.identityTree.size else { return .none }
        let want = PrivacyHash.identityLeaf(idc: keys.idc, dscKey: id.dscKey, country: id.country, activatedAt: id.activatedAt)
        return store.identityTree.leaf(id.leafIndex) == want ? .live : .zeroed
    }
}
