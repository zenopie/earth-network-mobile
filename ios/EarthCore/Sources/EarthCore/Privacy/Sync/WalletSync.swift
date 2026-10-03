import Foundation

/// x/shielded Query/Root: a root the chain recorded, whether it is still an anchor, and its tree size.
public struct NoteRootRecord: Sendable, Equatable {
    public let valid: Bool
    public let treeSize: UInt64
    public init(valid: Bool, treeSize: UInt64) { self.valid = valid; self.treeSize = treeSize }
}

/// A tree's state as the chain reports it: size and latest recorded root
/// (nil for an empty tree); `pinned` when it was read at the height asked
/// for, false when that height was unavailable and the latest was read.
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

    /// Registration record memo: "ER", version 1 (PRIVACY_FORMATS.md 3a).
    static let regMagic = Data([0x45, 0x52, 0x01])

    /// The 64-byte memo of a registration record note.
    public static func regMemo(dscKey: Fr, country: String, builtAt: UInt64) -> Data {
        var b = regMagic
        let c = country.uppercased()
        let valid = c.utf8.count == 2 && c.utf8.allSatisfy { (0x41 ... 0x5a).contains($0) }
        b += valid ? Data(c.utf8) : Data(count: 2)
        b += PrivateMsgs.be64(builtAt)
        b += dscKey.bytes
        return b + Data(count: NoteCipher.memoBytes - b.count)
    }

    /// (dsc_key, country, built_at) if `memo` is a registration record.
    public static func parseRegMemo(_ memo: Data) -> (dscKey: Fr, country: String, builtAt: UInt64)? {
        guard memo.count <= NoteCipher.memoBytes else { return nil }
        let m = [UInt8](memo) + [UInt8](repeating: 0, count: NoteCipher.memoBytes - memo.count)
        guard Data(m[0 ..< 3]) == regMagic else { return nil }
        let c = m[3 ..< 5]
        let country = c.allSatisfy { $0 == 0 } ? "" : (String(bytes: c, encoding: .ascii) ?? "")
        let builtAt = m[5 ..< 13].reduce(UInt64(0)) { $0 << 8 | UInt64($1) }
        guard let dsc = try? Fr(bytes: Data(m[13 ..< 45])) else { return nil }
        return (dsc, country, builtAt)
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

    public init(indexer: PrivacyIndexer, store: PrivacyStore, keys: PrivacyKeys, chainID: String, chain: ChainRoots,
                now: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970) }) {
        self.indexer = indexer; self.store = store; self.keys = keys; self.chainID = chainID; self.chain = chain; self.now = now
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
        if let c = status.chainID, c != chainID {
            throw PrivacyError("the privacy indexer follows \(c), not \(chainID)")
        }
        if store.state.chainID != chainID || store.state.genesis != status.genesis {
            // A fresh genesis (a relaunch under the same chain id) or a first
            // sync: nothing from another chain carries over.
            store.reset(chainID: chainID, genesis: status.genesis)
        }
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
        resolvePending()
        let verified = try await verifyRoots(roots)
        store.save()
        return Result(syncedHeight: store.state.notesHeight, newNotes: newNotes, spent: spent, noteRoot: store.noteTree.root(),
                      identityRoot: store.identityTree.root(), newStake: newStake, identityStatus: identityStatus(), verified: verified)
    }

    /// Whether every local tree is the indexer's latest; a tree of the same
    /// size with another root is inconsistent (the stream and the roots
    /// disagree), a different size just means the indexer moved on.
    private func atIndexerTip(_ roots: LatestRoots) throws -> Bool {
        var tip = true
        func check(_ name: String, _ r: RootRecord?, _ tree: MerkleTree) throws {
            let size = r?.treeSize ?? 0
            if size != tree.size { tip = false; return }
            if let r, r.root != tree.root() { throw Inconsistent(message: "\(name) tree root differs from the indexer's at \(size)") }
        }
        try check("note", roots.note, store.noteTree)
        try check("identity", roots.identity, store.identityTree)
        try check("stake", roots.stake, store.stakeTree)
        return tip
    }

    /// C3: every local root against the chain's own. The note root must be
    /// one the chain recorded, at the local size; the identity and stake
    /// trees must be the chain's at the height the indexer's root is from.
    /// A disagreement wipes the synced data and throws `ChainMismatch`; a
    /// check that cannot be pinned (the indexer moved on, a pruned height)
    /// leaves the roots unverified, and the wallet builds nothing on them
    /// until a later sync verifies them.
    private func verifyRoots(_ roots: LatestRoots) async throws -> Bool {
        var problems: [String] = []
        var mismatch: String?
        if store.noteTree.size > 0 {
            let rec = try await chain.noteRoot(store.noteTree.root())
            if rec == nil || rec!.treeSize != store.noteTree.size {
                mismatch = "the chain never recorded the note root the indexer's notes give (\(store.noteTree.size) notes)"
            } else if !rec!.valid {
                problems.append("the indexer is too far behind the chain: its note root is no longer an anchor")
            }
        }
        let tip = try atIndexerTip(roots)
        if !tip { problems.append("the indexer kept moving; sync again") }
        func tree(_ name: String, _ local: MerkleTree, _ r: RootRecord?, _ read: (UInt64?) async throws -> TreeState) async throws {
            // Pinned to the indexer's root height, which is only the local
            // tree's when the local tree is the indexer's latest.
            if mismatch != nil || !tip { return }
            let height: UInt64? = (r?.height).flatMap { $0 > 0 ? $0 : nil } ?? (roots.syncedHeight > 0 ? roots.syncedHeight : nil)
            let t = try await read(height)
            let localRoot: Fr? = local.size == 0 ? nil : local.root()
            if t.size == local.size && (t.root == localRoot || (local.size == 0 && t.root == nil)) {
                return
            } else if t.size == local.size || t.pinned {
                mismatch = "the chain's \(name) tree (\(t.size)) differs from the indexer's (\(local.size))"
            } else {
                problems.append("the \(name) tree could not be checked at the indexer's height")
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
        if note.value == 0 {
            if let m = Self.parseRegMemo(note.memo), !store.state.regRecords.contains(where: { $0.position == r.position }) {
                store.mutate { $0.regRecords.append(RegRecord(height: r.height, position: r.position, dscKey: m.dscKey, country: m.country, builtAt: m.builtAt)) }
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
        guard !digits.isEmpty, !denom.isEmpty, let v = UInt64(digits) else { return nil }
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
                  let o = NoteCipher.tryDecryptBlindStake(r.ciphertext, cm: r.cm, denom: d, amount: a, keys: keys) else { return nil }
            denom = d; amount = a; rho = o.rho; rcm = o.rcm
        default:
            return nil
        }
        guard amount > 0 else { return nil }
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
        var candidates: [UInt64: [(UInt64, Fr)]] = [:]
        while true {
            let page = try await indexer.identity(fromIndex: store.state.identityNext, limit: limit)
            try checkPage(page.rows.count)
            if page.rows.isEmpty { break }
            let take = Array(page.rows.prefix { $0.height <= ceiling })
            try checkPositions(take.map(\.index), from: store.state.identityNext, "identity leaf")
            // A leaf zeroed at or below the ceiling is zero here; one zeroed
            // later is zeroed by a later pass's zeroed stream.
            store.identityTree.appendAll(take.map { r in r.zeroedHeight.map { $0 <= ceiling } == true ? .zero : r.leaf })
            for r in take where recordHeights.contains(r.height) {
                candidates[r.height, default: []].append((r.index, store.identityTree.leaf(r.index)))
            }
            store.mutate { $0.identityNext += UInt64(take.count) }
            if take.count < page.rows.count || store.state.identityNext >= page.size { break }
        }
        matchRecords(candidates)
    }

    /// Matches record notes to the leaves appended at their heights (several
    /// registrations may share a block): every leaf with the hinted country
    /// first, the full country search only if none matched. The newest match
    /// becomes the identity.
    private func matchRecords(_ candidates: [UInt64: [(UInt64, Fr)]]) {
        for rec in store.state.regRecords.sorted(by: { $0.height > $1.height }) {
            guard let leaves = candidates[rec.height]?.filter({ $0.1 != .zero }), !leaves.isEmpty else { continue }
            func first(wide: Bool) -> (UInt64, (Fr, UInt64))? {
                for (i, leaf) in leaves {
                    if let f = findLeaf(leaf, dscKey: rec.dscKey, hint: rec.country, builtAt: rec.builtAt, wide: wide) { return (i, f) }
                }
                return nil
            }
            guard let match = first(wide: false) ?? first(wide: true) else { continue }
            let (index, found) = match
            let cur = store.state.identity
            if cur == nil || index > cur!.leafIndex {
                let nullifier = (cur?.leafIndex == index ? cur?.passportNullifier : nil) ?? ""
                store.mutate {
                    $0.identity = IdentityRecord(leafIndex: index, dscKey: rec.dscKey, country: found.0, activatedAt: found.1,
                                                 passportNullifier: nullifier)
                }
            }
            return
        }
    }

    /// (country, activated_at) with H(TAG_LEAF, idc, `dscKey`, country,
    /// activated_at) == `leaf`: activated_at searched outward from `builtAt`
    /// (the block came after the bundle was laid out; the clocks may differ),
    /// the hinted country first, then every country over a narrower window.
    func findLeaf(_ leaf: Fr, dscKey: Fr, hint: String, builtAt: UInt64, wide: Bool) -> (Fr, UInt64)? {
        func scan(_ countries: [Fr], before: UInt64, after: UInt64) -> (Fr, UInt64)? {
            for dt in 0 ... max(before, after) {
                var ts: [UInt64] = []
                if dt <= after, builtAt <= UInt64.max - dt { ts.append(builtAt + dt) }
                if dt > 0, dt <= before, builtAt >= dt { ts.append(builtAt - dt) }
                for t in ts {
                    for c in countries where PrivacyHash.identityLeaf(idc: keys.idc, dscKey: dscKey, country: c, activatedAt: t) == leaf {
                        return (c, t)
                    }
                }
            }
            return nil
        }
        var hinted = [Self.countryOrZero(hint)]
        if hinted[0] != .zero { hinted.append(.zero) }
        if !wide { return scan(hinted, before: 3_600, after: 86_400) }
        let hs = Set(hinted)
        return scan(Self.allCountries.filter { !hs.contains($0) }, before: 600, after: 3_600)
    }

    /// C2: a committed registration whose leaf the wallet has not matched
    /// yet. Once the local identity tree holds its index, the leaf is
    /// recomputed for the hinted country, unknown, and every A..Z pair; the
    /// identity record replaces the pending one. It is never dropped
    /// unmatched: a failure is recorded for the UI and retried.
    private func resolvePending() {
        guard var p = store.state.pendingRegistration else { return }
        if p.leafIndex >= store.identityTree.size {
            p.failure = nil
            store.mutate { $0.pendingRegistration = p }
            return
        }
        let leaf = store.identityTree.leaf(p.leafIndex)
        if let country = countryFor(leaf: leaf, dscKey: p.dscKey, activatedAt: p.activatedAt, hint: p.countryHint) {
            store.mutate {
                $0.identity = IdentityRecord(leafIndex: p.leafIndex, dscKey: p.dscKey, country: country, activatedAt: p.activatedAt,
                                             passportNullifier: p.passportNullifier)
                $0.pendingRegistration = nil
            }
        } else {
            p.failure = leaf == .zero ? "the registration's leaf \(p.leafIndex) has been zeroed" : "leaf \(p.leafIndex) does not match this registration"
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
