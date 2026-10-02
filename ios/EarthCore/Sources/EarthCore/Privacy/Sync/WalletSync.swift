import Foundation

/// Brings a wallet's `PrivacyStore` up to the indexer's tip. Ports
/// `privacy/sync/WalletSync.kt`:
///
///  1. every note commitment, appended to the local note tree, every
///     ciphertext trial-decrypted with this wallet's ek;
///  2. every nullifier, up to the height the notes reached, matched against
///     this wallet's notes to mark them spent;
///  3. every identity leaf, and every zeroing since the last sync, into the
///     local identity tree;
///  4. the local roots checked against the indexer's latest recorded roots.
///
/// Nothing is ever requested about one note or one leaf: the trees, and with
/// them this wallet's Merkle paths, are built here from the full streams.
public final class WalletSync {
    struct Inconsistent: Swift.Error { let message: String }

    public struct Result: Sendable {
        public let syncedHeight: UInt64
        public let newNotes: [OwnedNote]
        public let spent: [OwnedNote]
        public let noteRoot: Fr
        public let identityRoot: Fr
        public let identityStatus: IdentityStatus
    }

    public enum IdentityStatus: Sendable, Equatable { case none, live, zeroed }

    public static let pendingTimeout: Int64 = 15 * 60
    /// Self-mint counters tried past the last one found (abandoned txs leave gaps).
    public static let mintGap: UInt32 = 20

    private let indexer: PrivacyIndexer
    private let store: PrivacyStore
    private let keys: PrivacyKeys
    private let chainID: String
    private let now: () -> Int64
    private var mintPCs: [UInt32: Fr] = [:]

    public init(indexer: PrivacyIndexer, store: PrivacyStore, keys: PrivacyKeys, chainID: String,
                now: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970) }) {
        self.indexer = indexer; self.store = store; self.keys = keys; self.chainID = chainID; self.now = now
    }

    /// Syncs; on any inconsistency with the indexer, starts over once from an empty store.
    public func sync(pageLimit: Int? = nil) async throws -> Result {
        do {
            return try await syncOnce(pageLimit)
        } catch is Inconsistent {
            store.reset(chainID: chainID)
            return try await syncOnce(pageLimit)
        }
    }

    private func syncOnce(_ limit: Int?) async throws -> Result {
        let status = try await indexer.status()
        if let c = status.chainID, c != chainID {
            throw PrivacyError("the privacy indexer follows \(c), not \(chainID)")
        }
        if store.state.chainID != chainID {
            // A fresh genesis (or a first sync): nothing from another chain carries over.
            store.reset(chainID: chainID)
        }
        let newNotes = try await syncNotes(limit)
        let spent = try await syncNullifiers(limit)
        releaseStalePending()
        try await syncIdentity(limit)
        let roots = try await indexer.rootsLatest()
        if let r = roots.note, r.treeSize == store.noteTree.size, r.root != store.noteTree.root() {
            throw Inconsistent(message: "note tree root differs from the indexer's at \(r.treeSize) notes")
        }
        if let r = roots.identity, r.treeSize == store.identityTree.size, r.root != store.identityTree.root() {
            throw Inconsistent(message: "identity tree root differs from the indexer's at \(r.treeSize) leaves")
        }
        store.save()
        return Result(syncedHeight: store.state.notesHeight, newNotes: newNotes, spent: spent, noteRoot: store.noteTree.root(),
                      identityRoot: store.identityTree.root(), identityStatus: identityStatus())
    }

    private func syncNotes(_ limit: Int?) async throws -> [OwnedNote] {
        var found: [OwnedNote] = []
        while true {
            let page = try await indexer.notes(fromPos: store.state.notesNext, limit: limit)
            if !page.rows.isEmpty {
                let next = store.state.notesNext
                for (i, r) in page.rows.enumerated() where r.position != next + UInt64(i) {
                    throw Inconsistent(message: "note at position \(r.position), expected \(next + UInt64(i))")
                }
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

    private func mintPC(_ c: UInt32) -> Fr {
        if let pc = mintPCs[c] { return pc }
        let pc = keys.mintPC(c)
        mintPCs[c] = pc
        return pc
    }

    /// A note row is ours if its ciphertext opens with our ek for its cm and
    /// the opening reproduces the cm under our owner key (NoteCipher). A note
    /// the chain minted at a value we could not know when we named its pc has
    /// no ciphertext; it is ours if its public amount and one of our
    /// self-mint pcs (the next `mintGap` past the last used) reproduce its cm.
    /// A value-blind (v2, 177-byte) ciphertext opens to the note's secrets,
    /// and is ours if they reproduce its cm with the asset and value the
    /// chain published. Every path needs nothing but the mnemonic.
    func open(_ r: NoteRow) -> OwnedNote? {
        let amount = Self.publicAmount(r.amount)
        if let a = amount { store.mutate { _ = $0.denoms.insert(a.denom) } }
        let note: NotePlaintext
        if r.ciphertext.count == NoteCipher.blindCiphertextBytes {
            guard let a = amount, let n = NoteCipher.tryDecryptBlind(r.ciphertext, cm: r.cm, denom: a.denom, value: a.value, keys: keys) else { return nil }
            note = n
        } else if !r.ciphertext.isEmpty {
            guard let n = NoteCipher.tryDecrypt(r.ciphertext, cm: r.cm, keys: keys, denoms: AssetDenoms(store.state.denoms)) else { return nil }
            note = n
        } else {
            guard let a = amount else { return nil }
            let asset = PrivacyHash.assetID(a.denom)
            let upTo = store.state.nextMintCounter + Self.mintGap
            guard let c = (0 ..< upTo).first(where: { PrivacyHash.cm(asset: asset, value: a.value, pc: mintPC($0)) == r.cm }) else { return nil }
            store.mutate { $0.nextMintCounter = max($0.nextMintCounter, c + 1) }
            let (rho, rcm) = keys.mintSecrets(c)
            note = NotePlaintext(denom: a.denom, value: a.value, rho: rho, rcm: rcm)
        }
        guard note.value != 0 else { return nil }
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
        }
    }

    private func syncIdentity(_ limit: Int?) async throws {
        // Zeroings of leaves already held, first: a leaf appended below
        // carries its own zeroed_height.
        while true {
            let page = try await indexer.identityZeroed(fromHeight: store.state.zeroedNext, limit: limit)
            var updates: [UInt64: Fr] = [:]
            for (_, idxs) in page.blocks { for i in idxs where i < store.identityTree.size { updates[i] = .zero } }
            store.identityTree.updateAll(updates)
            store.mutate { $0.zeroedNext = page.nextHeight }
            if !page.complete { break }
        }
        while true {
            let page = try await indexer.identity(fromIndex: store.state.identityNext, limit: limit)
            if page.rows.isEmpty { break }
            let next = store.state.identityNext
            for (i, r) in page.rows.enumerated() where r.index != next + UInt64(i) {
                throw Inconsistent(message: "identity leaf \(r.index), expected \(next + UInt64(i))")
            }
            store.identityTree.appendAll(page.rows.map { $0.zeroedHeight != nil ? .zero : $0.leaf })
            store.mutate { $0.identityNext += UInt64(page.rows.count) }
            if store.state.identityNext >= page.size { break }
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
