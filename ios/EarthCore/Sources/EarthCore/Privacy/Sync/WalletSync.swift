import Foundation

/// x/shielded Query/Root: a root the chain recorded, whether it is still an
/// anchor, its tree size, and the height of the block that produced it (nil:
/// the node did not say). `expiresAt`: when it stops being an anchor (0: the
/// latest root, which does not lapse; nil: the node did not say).
public struct NoteRootRecord: Sendable, Equatable {
    public let valid: Bool
    public let treeSize: UInt64
    public let height: UInt64?
    public let expiresAt: Int64?
    public init(valid: Bool, treeSize: UInt64, height: UInt64? = nil, expiresAt: Int64? = nil) {
        self.valid = valid; self.treeSize = treeSize; self.height = height; self.expiresAt = expiresAt
    }
}

/// The chain's latest block: its height and time (unix seconds; nil when the node did not say).
public struct ChainTip: Sendable, Equatable {
    public let height: UInt64
    public let time: UInt64?
    public init(height: UInt64, time: UInt64?) { self.height = height; self.time = time }
}

/// What the chain says of a tx by hash: committed, failed in its block, or unknown to it.
public enum TxStatus: Sendable, Equatable { case committed, failed, missing }

/// A tree's state as the chain reports it: size and latest recorded root
/// (nil for an empty tree); `pinned` only when the node answered at exactly
/// the height asked for (its echoed `x-cosmos-block-height`), false when that
/// height was unavailable or the echo differed and the state is some other
/// height's.
public struct TreeState: Sendable, Equatable {
    public let size: UInt64
    public let root: Fr?
    public let pinned: Bool
    public init(size: UInt64, root: Fr?, pinned: Bool = true) { self.size = size; self.root = root; self.pinned = pinned }
}

/// The chain's own view of the three trees (the LCD in the app, the fake
/// chain in tests), against which every tree the indexer served is checked
/// An indexer can omit, add or forge rows, and a wallet that
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
    /// A block's time (unix seconds): a registration's activated_at. Nil when the node cannot say.
    func blockTime(_ height: UInt64) async -> UInt64?
    /// The LCD's chain id and genesis key (first block hash, 16 hex); nil when it cannot say.
    func chainIdentity() async -> ChainIdentity?
    /// The chain's latest block, with its time when the node says it (every indexer height and time is bounded by it).
    func latestBlock() async -> ChainTip?
    /// x/shielded Query/Tree at `height`: the note tree's size then (nil: the node cannot say).
    func noteTree(height: UInt64?) async -> TreeState?
    /// A tx by `hash` (one this wallet broadcast, so the node knows it
    /// already): nil when the node could not say. A pending note is released
    /// only on missing or failed.
    func txStatus(_ hash: String) async -> TxStatus?
    /// x/shielded Query/Assets: (denom, asset id) pairs, at most
    /// `Denoms.max`; nil when the node cannot say. Each is learned only if
    /// the id is the denom's own hash.
    func assets() async -> [(denom: String, id: Fr)]?
    /// x/staking's validators, every status (public): a stake ciphertext
    /// carries derth/<valoper>'s asset id only, so a wallet restored from the
    /// mnemonic names its stake by trying each (nil: the node cannot say).
    func validatorOperators() async -> [String]?
}

public extension ChainRoots {
    func nullifierSpent(_ nf: Fr) async -> Bool? { nil }
    func stakeNullifierSpent(_ nf: Fr) async -> Bool? { nil }
    func latestHeight() async -> UInt64? { nil }
    func blockTime(_ height: UInt64) async -> UInt64? { nil }
    func chainIdentity() async -> ChainIdentity? { nil }
    func latestBlock() async -> ChainTip? { await latestHeight().map { ChainTip(height: $0, time: nil) } }
    func noteTree(height: UInt64?) async -> TreeState? { nil }
    func txStatus(_ hash: String) async -> TxStatus? { nil }
    func assets() async -> [(denom: String, id: Fr)]? { nil }
    func validatorOperators() async -> [String]? { nil }
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
///     the row's public amount: one note-discovery rule, no counters), and
///     every open note (no ciphertext: the referral note) whose owner_pk is
///     ours checked against its cm; a split payout's rows sharing one
///     ciphertext are each a note of their own;
///  3. every nullifier, up to the height the notes reached;
///  4. the stake tree the same way (the wallet's own stake ciphertexts);
///  5. every identity leaf and zeroing up to the same height; registration
///     record notes matched to their leaf (restore), a pending registration
///     resolved;
///  6. the local roots checked against the indexer's latest (repeating the
///     pass while the indexer moves) and then against the chain's own.
///
/// Nothing is ever requested about one note or one leaf: the trees, and with
/// them this wallet's Merkle paths, are built here from the full streams.
public final class WalletSync {
    public struct Inconsistent: Swift.Error, LocalizedError {
        public let message: String
        public var errorDescription: String? { message }
    }

    /// The chain disagrees with what the indexer served: nothing synced is trusted.
    public struct ChainMismatch: Swift.Error, LocalizedError {
        public let message: String
        public var errorDescription: String? { message }
    }

    /// The indexer names another genesis the LCD does not confirm: nothing is wiped, nothing synced.
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

    /// Pending marks made before txs carried a timeout_height are released after this long (wall clock).
    public static let pendingTimeout: Int64 = 15 * 60
    /// One sync's time limit, its retries included.
    public static let syncTimeoutSeconds: Double = 10 * 60
    /// What rootsError says while a sync has not finished.
    public static let syncUnfinished = "unverified: the last sync did not finish; sync again"
    /// Block heights asked of the LCD with a record's own when the indexer serves no block time.
    public static let coverSet = 16
    /// LCD cover-set fetches a record may make.
    public static let maxCoverTries = 3

    /// The sync ran past `syncTimeoutSeconds`: an indexer that never stops serving, or one far too slow.
    public struct SyncTimeout: Swift.Error, LocalizedError {
        public let message: String
        public var errorDescription: String? { message }
    }
    /// The page size every stream is asked with (the backend's paging rule,
    /// `limit` is 100 or 1000, a position or index cursor a multiple
    /// of it). Every wallet asks for the same URLs, so a CDN keeps one copy.
    public static let pageSize = 1000
    /// The page sizes the backend serves (PRIVACY_PAGE_SIZES).
    public static let pageSizes: Set<Int> = [100, 1000]
    /// A height page never splits a block, so one block may bring more than the limit; never more than this.
    public static let maxHeightPageRows = 5000
    /// The aligned page holding `cursor`: page k is positions [k*limit, (k+1)*limit).
    public static func aligned(_ cursor: UInt64, _ limit: Int) -> UInt64 { cursor - cursor % UInt64(limit) }
    /// Blocks an indexer height may run past the chain's tip as the LCD
    /// reports it. Anything further is Inconsistent: a height past
    /// the tip would poison the persisted cursors for good.
    public static let tipSlack: UInt64 = 10
    /// Seconds an indexer row's block time may run past the tip's time (or the wallet's clock).
    public static let timeSlack: UInt64 = 3_600
    /// No block time before this (2025-01-01 UTC) is one of earth-1's.
    public static let minBlockTime: UInt64 = 1_735_689_600
    /// Identity row heights kept (a uniform sample) to draw a record's LCD cover set from.
    public static let identityHeightSample = 256
    /// Passes over the streams while the indexer keeps moving, before giving up on pinning a height.
    static let maxPasses = 4
    /// The largest tree position the circuits take (u32).
    static let maxPosition: UInt64 = 0xffff_ffff

    /// Nullifiers of this sync's streams (pool, stake) spot-checked against the chain.
    public static let nullifierSample = 4
    /// Blocks the indexer may trail the chain by before what it served is labelled stale.
    public static let staleBlocks: UInt64 = 30

    /// Registration record memo: "ER", version 2 (PRIVACY_FORMATS.md 6). Version 1 (untagged) is ignored.
    static let regMagic = Data([0x45, 0x52, 0x02])
    /// Bytes of the record memo's tag.
    static let regTagBytes = 16

    /// The record's tag: the first 16 bytes of H(TAG_RECTAG, nk, dsc_key,
    /// U64(built_at)), with U64(generation) appended for an identity
    /// generation >= 1 (PrivacyKeys). Only the owner (nk) can make one; the
    /// tag alone says which generation registered.
    public static func regTag(nk: Fr, dscKey: Fr, builtAt: UInt64, generation: Int = 0) -> Data {
        (generation == 0 ? PrivacyHash.h(PrivacyHash.tagRecTag, nk, dscKey, PrivacyHash.u64(builtAt))
            : PrivacyHash.h(PrivacyHash.tagRecTag, nk, dscKey, PrivacyHash.u64(builtAt), PrivacyHash.u64(UInt64(generation)))).bytes.prefix(regTagBytes)
    }

    /// The 64-byte memo of a registration record note for identity `generation`.
    public static func regMemo(nk: Fr, dscKey: Fr, country: String, builtAt: UInt64, generation: Int = 0) -> Data {
        var b = regMagic
        let c = country.uppercased()
        let valid = c.utf8.count == 2 && c.utf8.allSatisfy { (0x41 ... 0x5a).contains($0) }
        b += valid ? Data(c.utf8) : Data(count: 2)
        b += PrivateMsgs.be64(builtAt)
        b += dscKey.bytes
        b += regTag(nk: nk, dscKey: dscKey, builtAt: builtAt, generation: generation)
        return b + Data(count: NoteCipher.memoBytes - b.count)
    }

    /// (dsc_key, country, built_at) if `memo` is a version-2 registration
    /// record whose tag is `nk`'s: anyone can send this wallet a value-0
    /// note with any memo, and an untagged record would cost a leaf search
    /// per leaf at its height. The tag is checked before anything else is
    /// done with it.
    public static func parseRegMemo(nk: Fr, _ memo: Data, maxGeneration: Int = 0) -> (dscKey: Fr, country: String, builtAt: UInt64, generation: Int)? {
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
        // Generations 0...maxGeneration, each tag tried in turn.
        guard let g = (0 ... max(0, maxGeneration)).first(where: { constantTimeEqual(tag, regTag(nk: nk, dscKey: dsc, builtAt: builtAt, generation: $0)) })
        else { return nil }
        return (dsc, country, builtAt, g)
    }

    static func constantTimeEqual(_ a: Data, _ b: Data) -> Bool {
        guard a.count == b.count else { return false }
        return zip(a, b).reduce(UInt8(0)) { $0 | ($1.0 ^ $1.1) } == 0
    }

    /// Unlock memo: "EU", version 1 (PRIVACY_FORMATS.md 6).
    static let unlockMagic = Data([0x45, 0x55, 0x01])

    static func unlockTag(nk: Fr, counter: UInt32) -> Data {
        PrivacyHash.h(PrivacyHash.tagUnlockTag, nk, PrivacyHash.u64(UInt64(counter))).bytes.prefix(regTagBytes)
    }

    /// The memo of an unlock's record (a value-0 pool note to ourselves in
    /// the unlock's fee bundle): the owner-tag counter of the position it
    /// closed, so a wallet restored from the mnemonic knows the tags of
    /// closed positions too and never locks under one again. Tagged like the
    /// registration record (only nk makes one): a note carrying a huge
    /// counter cannot stretch the owner-tag scan.
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

    // MARK: state records (PRIVACY_FORMATS.md 6)

    /// State records: value-0 notes whose memo says what this identity holds
    /// in a scope, so a wallet restored from the mnemonic knows its handle
    /// and caretaker split (or that it moved them away). "EH" handle, "EC"
    /// caretaker, version 1.
    static let handleMagic = Data([0x45, 0x48, 0x01])
    static let caretakerMagic = Data([0x45, 0x43, 0x01])
    /// The tag sits at `stateTagAt` and covers every byte before it.
    static let stateTagAt = 48
    public static let recordHolds: UInt8 = 1
    /// Handle released / caretaker split cleared.
    public static let recordNone: UInt8 = 2
    public static let recordMovedOut: UInt8 = 3
    /// OR'd into a caretaker HOLDS kind: the split did not fit the memo and is not recorded.
    public static let splitUnrecorded: UInt8 = 0x80
    /// Entries of a recorded split: (option uvarint, percent u8)..., zero padded.
    static let splitAt = 8
    /// The most options a caretaker split names (x/allocation MaxVoterOptions).
    public static let maxSplitOptions = 20

    public enum StateRecord: Equatable, Sendable {
        case handle(kind: UInt8, handle: String)
        /// `split` nil: held, but the split was not recorded.
        case caretaker(kind: UInt8, expiresAt: Int64, split: [UInt64: UInt64]?)
    }

    /// H(TAG_STATETAG, nk, Bytes(memo[0..48))), with U64(generation) appended for a generation >= 1.
    static func stateTag(nk: Fr, _ body: [UInt8], generation: Int = 0) -> Data {
        let b = PrivacyHash.bytes(Data(body[0 ..< stateTagAt]))
        return (generation == 0 ? PrivacyHash.h(PrivacyHash.tagStateTag, nk, b)
            : PrivacyHash.h(PrivacyHash.tagStateTag, nk, b, PrivacyHash.u64(UInt64(generation)))).bytes.prefix(regTagBytes)
    }

    private static func sealState(nk: Fr, _ body: [UInt8], generation: Int) -> Data {
        var m = body + [UInt8](repeating: 0, count: NoteCipher.memoBytes - body.count)
        m.replaceSubrange(stateTagAt ..< stateTagAt + regTagBytes, with: stateTag(nk: nk, m, generation: generation))
        return Data(m)
    }

    /// A handle record for identity `generation` of the wallet whose nk is
    /// `nk`: HOLDS `handle`, or RELEASED / MOVED_OUT (no handle).
    public static func handleMemo(nk: Fr, kind: UInt8, handle: String = "", generation: Int = 0) -> Data {
        precondition((recordHolds ... recordMovedOut).contains(kind))
        precondition(kind == recordHolds ? Handles.valid(handle) : handle.isEmpty)
        var b = [UInt8](repeating: 0, count: stateTagAt)
        b.replaceSubrange(0 ..< 3, with: handleMagic)
        b[3] = kind
        let h = Array(handle.utf8)
        b.replaceSubrange(4 ..< 4 + h.count, with: h)
        return sealState(nk: nk, b, generation: generation)
    }

    /// A caretaker record: HOLDS with the split and its expiry (u32 unix
    /// seconds), or CLEARED / MOVED_OUT. A split whose entries do not fit 40
    /// bytes is marked unrecorded (its expiry still is).
    public static func caretakerMemo(nk: Fr, kind: UInt8, expiresAt: Int64 = 0, split: [UInt64: UInt64] = [:], generation: Int = 0) -> Data {
        precondition((recordHolds ... recordMovedOut).contains(kind))
        var b = [UInt8](repeating: 0, count: stateTagAt)
        b.replaceSubrange(0 ..< 3, with: caretakerMagic)
        var k = kind
        if kind == recordHolds {
            let e = UInt32(clamping: min(max(expiresAt, 1), 0xffff_ffff))
            b.replaceSubrange(4 ..< 8, with: PrivateMsgs.be32(e))
            var entries: [UInt8] = []
            var fits = !split.isEmpty && split.count <= maxSplitOptions
            for (option, percent) in split.sorted(by: { $0.key < $1.key }) {
                if option > UInt64(Int64.max) || !(1 ... 100).contains(percent) { fits = false }
                var v = option
                while v >= 0x80 { entries.append(UInt8(v & 0x7f) | 0x80); v >>= 7 }
                entries.append(UInt8(v))
                entries.append(UInt8(truncatingIfNeeded: percent))
            }
            if !fits || entries.count > stateTagAt - splitAt { k = kind | splitUnrecorded }
            else { b.replaceSubrange(splitAt ..< splitAt + entries.count, with: entries) }
        }
        b[3] = k
        return sealState(nk: nk, b, generation: generation)
    }

    /// The record in `memo` if it is a well-formed state record whose tag is
    /// `nk`'s (anyone can send this wallet a value-0 note with any memo;
    /// only the holder of nk can tag one). Checked before use.
    public static func parseStateMemo(nk: Fr, _ memo: Data) -> StateRecord? { parseStateRecord(nk: nk, memo)?.record }

    /// The record and the identity generation its tag names (0...`maxGeneration` tried).
    public static func parseStateRecord(nk: Fr, _ memo: Data, maxGeneration: Int = 0) -> (record: StateRecord, generation: Int)? {
        guard memo.count <= NoteCipher.memoBytes else { return nil }
        let m = [UInt8](memo) + [UInt8](repeating: 0, count: NoteCipher.memoBytes - memo.count)
        let head = Data(m[0 ..< 3])
        let isHandle = head == handleMagic
        guard isHandle || head == caretakerMagic else { return nil }
        let tag = Data(m[stateTagAt ..< stateTagAt + regTagBytes])
        guard let g = (0 ... max(0, maxGeneration)).first(where: { constantTimeEqual(tag, stateTag(nk: nk, m, generation: $0)) }) else { return nil }
        return parseStateBody(m, isHandle: isHandle).map { ($0, g) }
    }

    private static func parseStateBody(_ m: [UInt8], isHandle: Bool) -> StateRecord? {
        guard m[(stateTagAt + regTagBytes)...].allSatisfy({ $0 == 0 }) else { return nil }
        let kind = m[3]
        if isHandle {
            guard (recordHolds ... recordMovedOut).contains(kind) else { return nil }
            let raw = Array(m[4 ..< 36])
            guard m[36 ..< stateTagAt].allSatisfy({ $0 == 0 }) else { return nil }
            let len = raw.firstIndex(of: 0) ?? raw.count
            guard raw[len...].allSatisfy({ $0 == 0 }) else { return nil }
            let h = String(decoding: raw[0 ..< len], as: UTF8.self)
            if kind == recordHolds { guard Handles.valid(h) else { return nil } } else { guard h.isEmpty else { return nil } }
            return .handle(kind: kind, handle: h)
        }
        let base = kind & ~splitUnrecorded
        guard (recordHolds ... recordMovedOut).contains(base), kind == base || base == recordHolds else { return nil }
        let exp = Int64(m[4 ..< 8].reduce(UInt32(0)) { $0 << 8 | UInt32($1) })
        let entries = Array(m[splitAt ..< stateTagAt])
        if base != recordHolds {
            guard exp == 0, entries.allSatisfy({ $0 == 0 }) else { return nil }
            return .caretaker(kind: base, expiresAt: 0, split: [:])
        }
        guard exp != 0 else { return nil }
        if kind != base {
            guard entries.allSatisfy({ $0 == 0 }) else { return nil }
            return .caretaker(kind: base, expiresAt: exp, split: nil)
        }
        var split: [UInt64: UInt64] = [:]
        var i = 0
        var sum: UInt64 = 0
        while i < entries.count, entries[i...].contains(where: { $0 != 0 }) {
            var v: UInt64 = 0
            var shift: UInt64 = 0
            while true {
                guard i < entries.count, shift <= 56 else { return nil }
                let byte = entries[i]; i += 1
                v |= UInt64(byte & 0x7f) << shift
                if byte < 0x80 { break }
                shift += 7
            }
            guard i < entries.count, v <= UInt64(Int64.max) else { return nil }
            let pct = UInt64(entries[i]); i += 1
            guard (1 ... 100).contains(pct), split[v] == nil else { return nil }
            split[v] = pct; sum += pct
        }
        guard !split.isEmpty, split.count <= maxSplitOptions, sum == 100 else { return nil }
        return .caretaker(kind: base, expiresAt: exp, split: split)
    }

    /// Applies a state record found at note `position`: the
    /// newest record says what this identity holds, unless the wallet
    /// already applied a newer one (or acted since: a reset keeps the
    /// cursor), or the record's tx failed in its block (`height` void). A
    /// held split's expiry is bounded like any other lease time.
    public static func applyStateRecord(_ st: inout PrivacyState, position: UInt64, height: UInt64, _ rec: StateRecord, now: Int64, generation: Int = 0) {
        if st.voidRecordHeights.contains(height) { return }
        // What its generation holds; a later generation than the wallet acts
        // as registered (only a registered identity writes one).
        st.actAs(generation)
        st.withSlot(generation) { applyStateRecord(&$0, position: position, rec, now: now) }
    }

    private static func applyStateRecord(_ s: inout IdentitySlot, position: UInt64, _ rec: StateRecord, now: Int64) {
        switch rec {
        case let .handle(kind, handle):
            if let p = s.handleRecordPos, position <= p { return }
            s.handleRecordPos = position
            switch kind {
            case recordHolds: if !s.handleMovedOut { s.handle = handle }
            case recordNone: s.handle = ""
            default: s.handle = ""; s.handleMovedOut = true
            }
            s.handleSetAt = now
            // A move's record is in the chain: the move is no longer in doubt.
            settleMoves(&s, kind: PendingMove.handleKind, recordKind: kind) { $0.handle == handle }
        case let .caretaker(kind, expiresAt, split):
            if let p = s.caretakerRecordPos, position <= p { return }
            s.caretakerRecordPos = position
            if kind == recordHolds {
                if !s.caretakerMovedOut {
                    let exp = min(expiresAt, Handles.satAdd(now, Handles.maxAheadSeconds))
                    let same = split != nil && split == s.caretakerSplit
                    // The chain's own expiry, when the wallet has it for this split, is later than the record's estimate.
                    s.caretakerExpiresAt = same && s.caretakerExpiresAt > exp ? s.caretakerExpiresAt : exp
                    s.caretakerSplit = split ?? [:]
                    s.caretakerSplitUnknown = split == nil
                }
            } else {
                s.caretakerSplit = [:]; s.caretakerExpiresAt = 0; s.caretakerSplitUnknown = false
                if kind == recordMovedOut { s.caretakerMovedOut = true }
            }
            settleMoves(&s, kind: PendingMove.caretakerKind, recordKind: kind) { _ in true }
        }
    }

    /// A HOLDS record settles an incoming move of `kind` (`matches` it), a
    /// MOVED_OUT one an outgoing move (kept, confirmed, until recorded in its target).
    private static func settleMoves(_ s: inout IdentitySlot, kind: String, recordKind: UInt8, _ matches: (PendingMove) -> Bool) {
        let incoming: Bool
        switch recordKind {
        case recordHolds: incoming = true
        case recordMovedOut: incoming = false
        default: return
        }
        for i in s.pendingMoves.indices where s.pendingMoves[i].kind == kind && s.pendingMoves[i].incoming == incoming && matches(s.pendingMoves[i]) {
            s.pendingMoves[i].confirmed = true
        }
        // A confirmed outgoing move fixes the switch target.
        if !incoming, s.switchTarget.isEmpty,
           let m = s.pendingMoves.first(where: { !$0.incoming && $0.confirmed && !$0.target.isEmpty }) {
            s.switchTarget = m.target
        }
        s.pendingMoves.removeAll { $0.confirmed && ($0.incoming || $0.recorded) }
    }

    /// Record notes kept (newest first); only this wallet's own registrations carry a valid tag.
    public static let maxRecords = 32
    /// Generations past the highest the wallet knows whose record tags are
    /// tried: a 1130 refusal skips one with no record of it.
    public static let generationLookahead = 8

    /// The highest generation whose record tags `s`'s sync tries.
    public static func maxRecordGeneration(_ s: PrivacyState) -> Int {
        min(PrivacyKeys.maxGeneration, max(s.generation, s.nextGeneration()) + generationLookahead)
    }
    /// Identity leaves kept per record (registrations sharing its block).
    public static let maxRecordLeaves = 64
    /// Leaf hashes the fallback search may spend in one sync, across all
    /// records: bounds how long a sync holds the wallet lock.
    public static let syncSearchBudget: UInt64 = 50_000
    /// Leaf hashes the fallback search may spend on one record before it is given up.
    public static let recordSearchCap: UInt64 = 8_000_000

    /// predecessor_at candidates per (country, time): 0, or the time itself (a switch or re-entry).
    public static let predecessors: UInt64 = 2
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
    private let syncTimeout: Double
    private let monotonic: () -> Double
    private var deadline = Double.infinity
    private let poolSample = Reservoir(WalletSync.nullifierSample)
    private let stakeSample = Reservoir(WalletSync.nullifierSample)
    /// The chain's tip (LCD) every indexer height and row time this sync is bounded by.
    private var tip: ChainTip?
    /// Our own tx landed but the indexer never reported its spend: what it served is not the chain's.
    private var ownSpendMissing = false
    /// The asset-id lookup, built once per sync from the
    /// persisted denoms and grown as notes of our own name new ones.
    private var assetDenoms = AssetDenoms()
    /// Whether this sync has read the chain's asset list (at most once a sync, and only when a note of ours needs it).
    private var chainAssetsRead = false
    /// Whether this sync has read the chain's validator list (at most once, and only when a stake note of ours needs it).
    private var validatorsRead = false

    @discardableResult
    private func readTip() async throws -> ChainTip {
        guard let t = await chain.latestBlock() else { throw PrivacyError("the node did not say its latest height; nothing was synced") }
        tip = t
        return t
    }

    /// `h`, an indexer's height (a row's, a cursor, a synced_height), at most
    /// the chain's tip plus `tipSlack`: past it the tip is read
    /// again once (the chain moved), then the page is Inconsistent.
    @discardableResult
    private func bounded(_ name: String, _ h: UInt64) async throws -> UInt64 {
        let t0: ChainTip
        if let t = tip { t0 = t } else { t0 = try await readTip() }
        if h <= t0.height &+ Self.tipSlack { return h }
        if h <= (try await readTip()).height &+ Self.tipSlack { return h }
        throw Inconsistent(message: "the indexer's \(name) height \(h) is past the chain's tip \(tip?.height ?? 0)")
    }

    /// A height page's heights and cursors, bounded by the tip.
    private func bounded<T>(_ name: String, _ page: HeightPage<T>) async throws {
        try await bounded("\(name) synced", page.syncedHeight)
        try await bounded("\(name) next", page.nextHeight == 0 ? 0 : page.nextHeight - 1)
        for b in page.blocks { try await bounded(name, b.height) }
    }

    /// The latest block time an indexer row may carry: the tip's (or, unknown, the wallet's clock) plus `timeSlack`.
    private func maxTime() -> UInt64 {
        let base = tip?.time ?? UInt64(max(now(), 0))
        let (v, o) = base.addingReportingOverflow(Self.timeSlack)
        return o ? .max : v
    }

    /// Whether `t` can be a block time of this chain: never 0, never before `minBlockTime`, never past the tip.
    private func timeOK(_ t: UInt64) -> Bool { t >= Self.minBlockTime && t <= maxTime() }

    public init(indexer: PrivacyIndexer, store: PrivacyStore, keys: PrivacyKeys, chainID: String, chain: ChainRoots,
                now: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970) }, searchBudget: UInt64 = WalletSync.syncSearchBudget,
                syncTimeout: Double = WalletSync.syncTimeoutSeconds,
                monotonic: @escaping () -> Double = { Double(DispatchTime.now().uptimeNanoseconds) / 1e9 }) {
        self.indexer = indexer; self.store = store; self.keys = keys; self.chainID = chainID; self.chain = chain; self.now = now
        self.searchBudget = searchBudget; self.syncTimeout = syncTimeout; self.monotonic = monotonic
    }

    private func tick() throws {
        if monotonic() > deadline { throw SyncTimeout(message: "the privacy sync did not finish in \(Int(syncTimeout))s") }
    }

    /// Syncs; on an inconsistency with the indexer, starts over once from an
    /// empty store. The whole of it, retries included, is bounded by
    /// `syncTimeout`.
    public func sync(pageLimit: Int = WalletSync.pageSize) async throws -> Result {
        deadline = monotonic() + syncTimeout
        do {
            return try await syncRetryingBase(pageLimit)
        } catch is Inconsistent {
            try store.reset(chainID: chainID)
            return try await syncRetryingBase(pageLimit)
        }
    }

    /// Before a sync's first request the roots are unverified, and
    /// persisted so: a sync that fails part way (an indexer that serves
    /// forged notes and then breaks a later stream) leaves nothing labelled
    /// verified. Only `verifyRoots` at the end of this same sync sets the new
    /// generation verified.
    private func markSyncing() throws {
        store.mutate { s in
            s.syncGeneration &+= 1
            s.rootsVerified = false
            s.rootsError = Self.syncUnfinished
        }
        try store.save()
    }

    /// A 404 means the indexer's base moved (a relaunch): read the status again, once.
    private func syncRetryingBase(_ limit: Int) async throws -> Result {
        do {
            return try await syncOnce(limit)
        } catch is IndexerBaseMoved {
            return try await syncOnce(limit)
        }
    }

    private func syncOnce(_ limit: Int) async throws -> Result {
        try markSyncing()
        try tick()
        let status = try await indexer.status()
        if let h = status.halted { throw IndexerHalted(h) }
        guard let c = status.chainID else { throw PrivacyError("the privacy indexer names no chain yet") }
        if c != chainID { throw PrivacyError("the privacy indexer follows \(c), not \(chainID)") }
        if store.state.chainID != chainID || store.state.genesis != status.genesis {
            try await switchChain(status.genesis)
            try markSyncing()
        }
        try await readTip()
        ownSpendMissing = false
        beginDenoms()
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
            try tick()
            roots = try await indexer.rootsLatest()
            try await boundRoots(roots)
            pass += 1
            if try atIndexerTip(roots) || pass >= Self.maxPasses { break }
        }
        await resolveUnresolved()
        await releaseStalePending()
        let verified = try await verifyRoots(roots)
        // A registration is matched only against an identity
        // tree this same sync verified against the chain's; an unverified one
        // waits (its leaves are kept) for a sync that verifies. Once a sync,
        // after every pass: the record search is budgeted per sync.
        if verified {
            // The resync reached the verified tip: a carried mark whose note
            // it did not find again has nothing left to hold.
            store.mutate { $0.carriedMarks = [:] }
            await matchRecords()
            resolvePending()
            // An identity from before (or a reset) whose leaf this verified tree holds is verified now.
            if let id = store.state.identity, !id.verified, identityStatus() == .live { store.mutate { $0.identity?.verified = true } }
        }
        try store.save()
        return Result(syncedHeight: store.state.notesHeight, newNotes: newNotes, spent: spent, noteRoot: store.noteTree.root(),
                      identityRoot: store.identityTree.root(), newStake: newStake, identityStatus: identityStatus(), verified: verified)
    }

    /// The indexer names a (chain id, genesis) other than the store's.
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
            try store.save()
            throw GenesisUnverified(message: m)
        }
        // A store with no genesis recorded (same chain id, written before the
        // wallet kept one) is treated like a switch: the synced data goes,
        // the registration stays.
        let noGenesisRecorded = old.chainID == chainID && old.genesis == nil
        if switching || noGenesisRecorded { try store.switchGenesis(genesis!) } else { try store.reset(chainID: chainID, genesis: genesis) }
    }

    /// Every height /roots/latest names, bounded by the chain's tip.
    private func boundRoots(_ roots: LatestRoots) async throws {
        try await bounded("roots synced", roots.syncedHeight)
        for r in [roots.note, roots.identity, roots.stake].compactMap({ $0 }) { try await bounded("root", r.height) }
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

    /// Every local root against the chain's own (the LCD:
    /// PRIVACY_FORMATS 18 says what that trusts). Only a positive
    /// contradiction is a mismatch, which wipes the synced data and throws
    /// `ChainMismatch`: a note root the chain recorded at another tree size,
    /// or an identity or stake tree that differs from the chain's read at
    /// exactly the indexer's root height (the node echoed that height).
    /// Everything that cannot be established leaves the roots unverified,
    /// and the wallet builds nothing on them and labels what it shows until a
    /// later sync verifies them: a note root the chain no longer
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
            // The indexer dates its root as the chain does.
            if let h = rec?.height, let n = roots.note, h != n.height {
                problems.append("unverified: the indexer dates its note root at height \(n.height), the chain at \(h)")
            }
        }
        let tip = try atIndexerTip(roots)
        if !tip { problems.append("unverified: the indexer kept moving; sync again") }
        // The height the indexer claims to be synced to is
        // checked, not taken: the chain's note tree at exactly that height
        // (pinned) must be the one served. A stale indexer naming the
        // current height is caught here (every spend appends notes).
        if mismatch == nil, tip, roots.syncedHeight > 0, let t = await chain.noteTree(height: roots.syncedHeight), t.pinned, t.size != store.noteTree.size {
            problems.append("unverified: the chain's note tree at the indexer's height \(roots.syncedHeight) holds \(t.size) notes, the indexer served \(store.noteTree.size)")
        }
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
            try store.reset(chainID: chainID)
            store.mutate { $0.rootsVerified = false; $0.rootsError = m }
            try store.save()
            throw ChainMismatch(message: m)
        }
        // A sample of the spends this sync read, asked of the chain: an
        // indexer inventing spends is caught without asking about ours.
        var invented = false
        for nf in poolSample.items where await chain.nullifierSpent(nf) == false { invented = true }
        for nf in stakeSample.items where await chain.stakeNullifierSpent(nf) == false { invented = true }
        if invented { problems.append("unverified: the indexer reported a spend the chain does not hold") }
        if ownSpendMissing { problems.append("unverified: a tx of this wallet is in a block but the indexer did not report its spend") }
        // Behind the chain's tip as the LCD says it, the indexer's
        // height having been checked against the chain's note tree above.
        if let tipHeight = try? await readTip().height {
            if tipHeight > roots.syncedHeight, tipHeight - roots.syncedHeight > Self.staleBlocks {
                problems.append("unverified: the indexer is \(tipHeight - roots.syncedHeight) blocks behind the chain")
            }
        } else {
            problems.append("unverified: the node did not say its latest height")
        }
        store.mutate { s in
            s.rootsVerified = problems.isEmpty
            s.rootsError = problems.first
            if problems.isEmpty {
                s.verifiedGeneration = s.syncGeneration
                s.verifiedHeight = max(s.verifiedHeight, roots.syncedHeight)
            }
        }
        return problems.isEmpty
    }

    private func checkPage(_ n: Int, _ limit: Int) throws {
        if n > limit { throw Inconsistent(message: "the indexer sent \(n) rows in a page of \(limit)") }
    }

    /// The rows of an aligned position page past `held` (the backend's
    /// paging rule): the page starts at `from`, so the rows before `held` are
    /// ones the wallet holds already, and each must be the very leaf it holds.
    private func fresh<R>(_ name: String, _ rows: [R], from: UInt64, held: UInt64, _ position: (R) -> UInt64, same: (R) -> Bool) throws -> [R] {
        try checkPositions(rows.map(position), from: from, name)
        let n = Int(held - from)
        if rows.count < n { throw Inconsistent(message: "a \(name) page from \(from) ends before the \(held) held") }
        for r in rows.prefix(n) where !same(r) { throw Inconsistent(message: "\(name) \(position(r)) differs from the one held") }
        return Array(rows.dropFirst(n))
    }

    /// Positions must follow the cursor one by one and stay within the tree.
    private func checkPositions(_ positions: [UInt64], from next: UInt64, _ what: String) throws {
        for (i, p) in positions.enumerated() {
            let (want, o) = next.addingReportingOverflow(UInt64(i))
            if o || p != want { throw Inconsistent(message: "\(what) at position \(p), expected \(o ? "none" : String(want))") }
            if p > Self.maxPosition { throw Inconsistent(message: "\(what) position \(p) beyond the tree") }
        }
    }

    /// A position page must say where it ends (next = from + rows)
    /// and a page that says more follows must carry rows; otherwise the same
    /// page could be asked for forever while the wallet lock is held.
    private func checkPositions(_ name: String, from: UInt64, rows: Int, next: UInt64, complete: Bool) throws {
        let (want, o) = from.addingReportingOverflow(UInt64(rows))
        if o || next != want { throw Inconsistent(message: "a \(name) page from \(from) with \(rows) rows names next \(next)") }
        if complete && rows == 0 { throw Inconsistent(message: "an empty \(name) page from \(from) says more follows") }
    }

    /// A height page never moves backwards, and one that says more follows moves forwards.
    private func checkHeights<T>(_ name: String, from: UInt64, _ page: HeightPage<T>) async throws {
        try await bounded(name, page)
        if page.nextHeight < from || (page.complete && page.nextHeight <= from) {
            throw Inconsistent(message: "a \(name) page from height \(from) names next \(page.nextHeight)")
        }
        if page.blocks.contains(where: { $0.height < from }) { throw Inconsistent(message: "a \(name) page from height \(from) holds an earlier height") }
    }

    private func syncNotes(_ limit: Int) async throws -> [OwnedNote] {
        var found: [OwnedNote] = []
        while true {
            try tick()
            let from = Self.aligned(store.state.notesNext, limit)
            let page = try await indexer.notes(fromPos: from, limit: limit)
            try checkPage(page.rows.count, limit)
            try await bounded("note synced", page.syncedHeight)
            for r in page.rows { try await bounded("note", r.height) }
            try checkPositions("note", from: from, rows: page.rows.count, next: page.nextPos, complete: page.complete)
            let tree = store.noteTree
            let rows = try fresh("note", page.rows, from: from, held: store.state.notesNext, { $0.position }) { tree.leaf($0.position) == $0.cm }
            if !rows.isEmpty {
                store.noteTree.appendAll(rows.map(\.cm))
                for r in rows {
                    if var n = open(r) {
                        if let c = store.mutate({ $0.carriedMarks.removeValue(forKey: n.nf.hex) }) {
                            n.pendingAt = c.at; n.pendingUntil = c.until; n.pendingTx = c.tx
                        }
                        found.append(n)
                        store.mutate { $0.notes.append(n) }
                    }
                }
                store.mutate { $0.notesNext += UInt64(rows.count) }
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
        // The row's amount is only checked here, never
        // learned from: a denom is learned once a note of ours reproduces
        // its cm with it.
        let amount = Self.publicAmount(r.amount)
        let note: NotePlaintext
        switch r.ciphertext.count {
        // An open mint (the referral note to a handle we hold):
        // no ciphertext, the opening on the row. Ours if its owner_pk is ours
        // and the opening with the public amount recomputes the row's cm
        // (which the tree check pins to the chain's root). Matched here, over
        // the whole stream: no query ever names our owner_pk.
        case 0:
            guard let a = amount, let owner = r.ownerPK, let rho = r.rho, let rcm = r.rcm, owner == keys.ownerPK else { return nil }
            let n = NotePlaintext(denom: a.denom, value: a.value, rho: rho, rcm: rcm)
            guard n.cm(ownerPK: keys.ownerPK) == r.cm else { return nil }
            note = n
        case NoteCipher.blindCiphertextBytes:
            guard let a = amount, let n = NoteCipher.tryDecryptBlind(r.ciphertext, cm: r.cm, denom: a.denom, value: a.value, keys: keys) else { return nil }
            note = n
        case NoteCipher.ciphertextBytes:
            guard let n = NoteCipher.tryDecrypt(r.ciphertext, cm: r.cm, keys: keys, denoms: assetDenoms) else { return nil }
            note = n
        default:
            return nil
        }
        // A value past 2^63-1 is not one the wallet holds (PRIVACY_FORMATS 4; as Android).
        if note.value > UInt64(Int64.max) { return nil }
        if note.value == 0 {
            let gens = Self.maxRecordGeneration(store.state)
            if let m = Self.parseRegMemo(nk: keys.nk, note.memo, maxGeneration: gens), !store.state.regRecords.contains(where: { $0.position == r.position }) {
                store.mutate { s in
                    s.regRecords.append(RegRecord(height: r.height, position: r.position, dscKey: m.dscKey, country: m.country, builtAt: m.builtAt,
                                                  generation: m.generation))
                    if s.regRecords.count > Self.maxRecords, let i = s.regRecords.indices.min(by: { s.regRecords[$0].height < s.regRecords[$1].height }) {
                        s.regRecords.remove(at: i)
                    }
                }
            }
            if let rec = Self.parseStateRecord(nk: keys.nk, note.memo, maxGeneration: gens) {
                let t = now()
                store.mutate { Self.applyStateRecord(&$0, position: r.position, height: r.height, rec.record, now: t, generation: rec.generation) }
            }
            // An unlock's record: the owner-tag counter of the position it closed.
            if let c = Self.parseUnlockMemo(nk: keys.nk, note.memo), store.state.closedOtagMax.map({ c > $0 }) ?? true {
                store.mutate { $0.closedOtagMax = c }
            }
            return nil
        }
        learnOwn(note.denom)
        return OwnedNote(position: r.position, height: r.height, note: note, cm: r.cm,
                         nf: PrivacyHash.nf(nk: keys.nk, rho: note.rho, position: r.position))
    }

    static func publicAmount(_ amount: String?) -> (value: UInt64, denom: String)? {
        guard let amount else { return nil }
        let digits = amount.prefix { $0.isASCII && $0.isNumber }
        let denom = String(amount.dropFirst(digits.count))
        // An SDK denom only; never the wallet's own "asset/" name.
        guard !digits.isEmpty, Denoms.valid(denom), let v = UInt64(digits), v <= UInt64(Int64.max) else { return nil }
        return (v, denom)
    }

    /// A denom a note of ours carries (its cm binds it): learned and kept.
    private func learnOwn(_ denom: String) {
        if assetDenoms.learn(denom), store.state.denoms.count < Denoms.max { store.mutate { _ = $0.denoms.insert(denom) } }
    }

    /// The persisted denoms are the ones of notes this wallet
    /// holds (anything else a store learned before is dropped), at most
    /// `Denoms.max`, and the lookup is built from them once for the sync. A
    /// held note named "asset/<hex>" whose id is now known is renamed (same
    /// asset, same cm), which also undoes a relabel an indexer slipped into
    /// a store before rows' denoms were checked.
    private func beginDenoms() {
        chainAssetsRead = false
        validatorsRead = false
        let own = Set((store.state.notes.map(\.note.denom) + store.state.stakeNotes.map(\.denom)).filter(Denoms.valid))
        let kept = Set(own.sorted().prefix(Denoms.max))
        store.mutate { $0.denoms = kept }
        assetDenoms = AssetDenoms(kept)
        renameResolved()
    }

    /// Renames every held "asset/<hex>" note whose id the lookup now knows.
    private func renameResolved() {
        let p = NotePlaintext.unresolvedPrefix
        guard store.state.notes.contains(where: { $0.note.denom.hasPrefix(p) }) else { return }
        let d = assetDenoms
        var added: [String] = []
        store.mutate { s in
            for i in s.notes.indices where s.notes[i].note.denom.hasPrefix(p) {
                let name = d.resolve(s.notes[i].note.asset)
                if !name.hasPrefix(p) { s.notes[i] = s.notes[i].withDenom(name); added.append(name) }
            }
            for a in added where s.denoms.count < Denoms.max { s.denoms.insert(a) }
        }
    }

    /// A held note whose asset id the wallet cannot name: the chain's asset
    /// list, read at most once a sync, each entry learned only if its id is
    /// the denom's own.
    private func resolveUnresolved() async {
        guard !chainAssetsRead, store.state.notes.contains(where: { $0.note.denom.hasPrefix(NotePlaintext.unresolvedPrefix) }) else { return }
        chainAssetsRead = true
        guard let list = await chain.assets() else { return }
        for a in list.prefix(Denoms.max) { assetDenoms.learn(a.denom, id: a.id) }
        renameResolved()
    }

    private func syncNullifiers(_ limit: Int) async throws -> [OwnedNote] {
        var mine: [Fr: Int] = [:]
        for (i, n) in store.state.notes.enumerated() where n.unspent { mine[n.nf] = i }
        // The spot-check sample never holds one of ours (spent or not): asking
        // the chain about it would name our note (PRIVACY_FORMATS 18).
        let own = Set(store.state.notes.map(\.nf))
        var spent: [OwnedNote] = []
        // Only up to the height the note stream reached: a note found next
        // time could otherwise have been spent in a block this pass skipped.
        let ceiling = store.state.notesHeight
        while store.state.nullifiersNext <= ceiling {
            try tick()
            let page = try await indexer.nullifiers(fromHeight: store.state.nullifiersNext, limit: limit)
            try checkPage(page.blocks.reduce(0) { $0 + $1.items.count }, max(limit, Self.maxHeightPageRows))
            try await checkHeights("nullifier", from: store.state.nullifiersNext, page)
            for (h, nfs) in page.blocks {
                if h > ceiling { break }
                for nf in nfs where !own.contains(nf) { poolSample.offer(nf) }
                for nf in nfs {
                    if let i = mine[nf] {
                        store.mutate { $0.notes[i].spentHeight = h }
                        spent.append(store.state.notes[i])
                    }
                }
            }
            store.mutate { $0.nullifiersNext = min(page.nextHeight, ceiling &+ 1) }
            if !page.complete { break }
        }
        return spent
    }

    /// A note marked pending by a broadcast is released (spendable again)
    /// only once its tx can no longer land: the chain's tip (LCD)
    /// is past the tx's timeout_height and this wallet has read the nullifier
    /// stream through that height without seeing its nullifier. Never by the
    /// wall clock. Marks made before txs carried a timeout keep the old
    /// 15-minute rule.
    private func releaseStalePending() async {
        let t = now()
        let needsTip = store.state.notes.contains { $0.unspent && $0.pendingUntil != nil } ||
            store.state.stakeNotes.contains { $0.unspent && $0.pendingUntil != nil }
        let tipHeight = needsTip ? (try? await readTip())?.height : nil
        let st = store.state
        let poolRead = st.nullifiersNext == 0 ? 0 : st.nullifiersNext - 1
        let stakeRead = st.stakeNullifiersNext == 0 ? 0 : st.stakeNullifiersNext - 1
        // Past its timeout and read through: the chain's word on its tx.
        func due(_ at: Int64?, _ until: UInt64?, _ readThrough: UInt64) -> Bool {
            guard at != nil, let until, let tipHeight else { return false }
            return readThrough >= until && tipHeight > until
        }
        var status: [String: TxStatus?] = [:]
        // Marks whose timeout no sane tip gives are asked about too.
        func outsized(_ at: Int64?, _ until: UInt64?) -> Bool {
            guard let at, let until else { return false }
            return !PrivateTxEngine.timeoutSane(until, verifiedNow: st.verifiedHeight) && t - at > Self.pendingTimeout
        }
        for h in Set(st.notes.filter { $0.unspent && (due($0.pendingAt, $0.pendingUntil, poolRead) || outsized($0.pendingAt, $0.pendingUntil)) }.compactMap(\.pendingTx) +
                     st.stakeNotes.filter { $0.unspent && (due($0.pendingAt, $0.pendingUntil, stakeRead) || outsized($0.pendingAt, $0.pendingUntil)) }.compactMap(\.pendingTx)) {
            status[h] = await chain.txStatus(h)
        }
        var missing = false
        func release(_ at: Int64?, _ until: UInt64?, _ hash: String?, _ readThrough: UInt64) -> Bool {
            guard let at else { return false }
            guard let until else { return t - at > Self.pendingTimeout }
            // A timeout no sane tip gives (a node inflated the
            // tip at send) is never reached: the tx's status alone settles
            // it, after the mempool's grace.
            if !PrivateTxEngine.timeoutSane(until, verifiedNow: st.verifiedHeight) {
                guard let hash, t - at > Self.pendingTimeout else { return false }
                switch status[hash] ?? nil {
                case .missing?, .failed?: return true
                default: return false
                }
            }
            guard due(at, until, readThrough) else { return false }
            // A mark made before marks carried the hash: the timeout alone.
            guard let hash else { return true }
            switch status[hash] ?? nil {
            case .missing?, .failed?: return true
            case .committed?: missing = true; return false
            case nil: return false
            }
        }
        store.mutate { s in
            for i in s.notes.indices where s.notes[i].unspent && release(s.notes[i].pendingAt, s.notes[i].pendingUntil, s.notes[i].pendingTx, poolRead) {
                s.notes[i].pendingAt = nil; s.notes[i].pendingUntil = nil; s.notes[i].pendingTx = nil
            }
            for i in s.stakeNotes.indices where s.stakeNotes[i].unspent &&
                release(s.stakeNotes[i].pendingAt, s.stakeNotes[i].pendingUntil, s.stakeNotes[i].pendingTx, stakeRead) {
                s.stakeNotes[i].pendingAt = nil; s.stakeNotes[i].pendingUntil = nil; s.stakeNotes[i].pendingTx = nil
            }
        }
        if missing { ownSpendMissing = true }
    }

    private func syncStakeNotes(_ limit: Int) async throws -> [OwnedStakeNote] {
        var found: [OwnedStakeNote] = []
        while true {
            try tick()
            let from = Self.aligned(store.state.stakeNext, limit)
            let page = try await indexer.stakeNotes(fromPos: from, limit: limit)
            try checkPage(page.rows.count, limit)
            try await bounded("stake note synced", page.syncedHeight)
            for r in page.rows { try await bounded("stake note", r.height) }
            try checkPositions("stake note", from: from, rows: page.rows.count, next: page.nextPos, complete: page.complete)
            let tree = store.stakeTree
            let rows = try fresh("stake note", page.rows, from: from, held: store.state.stakeNext, { $0.position }) { tree.leaf($0.position) == $0.cm }
            if !rows.isEmpty {
                let opened = rows.compactMap { r in Self.openStake(r, keys: keys).map { (r, $0) } }
                // derth/<valoper> by the chain's validator list, read once, only when a note of ours needs it.
                // Before anything is appended: a list that cannot be read fails the sync
                // here, so the cursor never passes a note of ours it could not name.
                if !validatorsRead, opened.contains(where: { assetDenoms.resolve($0.1.asset).hasPrefix(NotePlaintext.unresolvedPrefix) }) {
                    guard let ops = await chain.validatorOperators() else {
                        throw PrivacyError("the validator list could not be read, so a stake note of this wallet cannot be named yet")
                    }
                    validatorsRead = true
                    for op in ops.prefix(Denoms.max) { assetDenoms.learn("derth/\(op)") }
                }
                store.stakeTree.appendAll(rows.map(\.cm))
                for (r, o) in opened {
                    if var n = ownedStake(r, o) {
                        if let c = store.mutate({ $0.carriedMarks.removeValue(forKey: n.nf.hex) }) {
                            n.pendingAt = c.at; n.pendingUntil = c.until; n.pendingTx = c.tx
                        }
                        found.append(n)
                        store.mutate { $0.stakeNotes.append(n) }
                    }
                }
                store.mutate { $0.stakeNext += UInt64(rows.count) }
            }
            store.mutate { $0.stakeHeight = max($0.stakeHeight, page.syncedHeight) }
            if !page.complete { break }
        }
        return found
    }

    /// A stake row is ours if its ciphertext opens (the wallet stake note,
    /// 201 bytes, label inside): every stake note is a stake proof's output.
    /// A zero note (a full exit's padding output) is dropped.
    static func openStake(_ r: StakeNoteRow, keys: PrivacyKeys) -> NoteCipher.StakeOpening? {
        guard let o = NoteCipher.tryDecryptStake(r.ciphertext, cm: r.cm, keys: keys) else { return nil }
        // Zero, or past 2^63-1: nothing the wallet holds (as Android).
        guard o.amount > 0, o.amount <= UInt64(Int64.max) else { return nil }
        return o
    }

    /// The note `o` opened at `r`, named derth/<valoper> (nil: an asset the wallet cannot name is not one it can use).
    private func ownedStake(_ r: StakeNoteRow, _ o: NoteCipher.StakeOpening) -> OwnedStakeNote? {
        let denom = assetDenoms.resolve(o.asset)
        guard !denom.hasPrefix(NotePlaintext.unresolvedPrefix) else { return nil }
        learnOwn(denom)
        return OwnedStakeNote(position: r.position, height: r.height, denom: denom, amount: o.amount, rho: o.rho, rcm: o.rcm, cm: r.cm,
                              nf: PrivacyHash.stakeNF(nk: keys.nk, rho: o.rho, position: r.position), label: o.label)
    }

    private func syncStakeNullifiers(_ limit: Int) async throws {
        var mine: [Fr: Int] = [:]
        for (i, n) in store.state.stakeNotes.enumerated() where n.unspent { mine[n.nf] = i }
        let own = Set(store.state.stakeNotes.map(\.nf))
        let ceiling = store.state.stakeHeight
        while store.state.stakeNullifiersNext <= ceiling {
            try tick()
            let page = try await indexer.stakeNullifiers(fromHeight: store.state.stakeNullifiersNext, limit: limit)
            try checkPage(page.blocks.reduce(0) { $0 + $1.items.count }, max(limit, Self.maxHeightPageRows))
            try await checkHeights("stake nullifier", from: store.state.stakeNullifiersNext, page)
            for (h, nfs) in page.blocks {
                if h > ceiling { break }
                for nf in nfs where !own.contains(nf) { stakeSample.offer(nf) }
                for nf in nfs { if let i = mine[nf] { store.mutate { $0.stakeNotes[i].spentHeight = h } } }
            }
            store.mutate { $0.stakeNullifiersNext = min(page.nextHeight, ceiling &+ 1) }
            if !page.complete { break }
        }
    }

    /// Identity leaves and zeroings, up to the height the notes reached (so a
    /// registration's record note is always seen no later than its leaf).
    /// Each appended leaf at the height of an unmatched record note is tried
    /// against it: that is how a wallet restored from the mnemonic finds its
    /// registration, with no query naming it.
    private func syncIdentity(_ limit: Int) async throws {
        let ceiling = store.state.notesHeight
        // Zeroings of leaves already held, first: a leaf appended below
        // carries its own zeroed_height.
        while store.state.zeroedNext <= ceiling {
            try tick()
            let page = try await indexer.identityZeroed(fromHeight: store.state.zeroedNext, limit: limit)
            try checkPage(page.blocks.reduce(0) { $0 + $1.items.count }, max(limit, Self.maxHeightPageRows))
            try await checkHeights("identity zeroing", from: store.state.zeroedNext, page)
            var updates: [UInt64: Fr] = [:]
            for (h, idxs) in page.blocks {
                if h > ceiling { break }
                for i in idxs where i < store.identityTree.size { updates[i] = .zero }
            }
            store.identityTree.updateAll(updates)
            store.mutate { $0.zeroedNext = min(page.nextHeight, ceiling &+ 1) }
            if !page.complete { break }
        }
        let recordHeights = Set(store.state.regRecords.map(\.height))
        while true {
            try tick()
            let from = Self.aligned(store.state.identityNext, limit)
            let page = try await indexer.identity(fromIndex: from, limit: limit)
            try checkPage(page.rows.count, limit)
            try await bounded("identity synced", page.syncedHeight)
            for r in page.rows {
                try await bounded("identity", r.height)
                if let z = r.zeroedHeight { try await bounded("identity zeroing", z) }
                // A row's block time is one of this chain's.
                if let t = r.time, t != 0, !timeOK(t) { throw Inconsistent(message: "identity leaf \(r.index) carries block time \(t), outside the chain's") }
            }
            // Rows already held are dropped unchecked: a leaf zeroed since reads differently.
            let rest = try fresh("identity leaf", page.rows, from: from, held: store.state.identityNext, { $0.index }) { _ in true }
            if rest.isEmpty { break }
            let take = Array(rest.prefix { $0.height <= ceiling })
            // A leaf zeroed at or below the ceiling is zero here; one zeroed
            // later is zeroed by a later pass's zeroed stream.
            store.identityTree.appendAll(take.map { r in r.zeroedHeight.map { $0 <= ceiling } == true ? .zero : r.leaf })
            // Each record keeps the leaves of its block as they pass (persisted:
            // never streamed again), and the block's time if the row carries it.
            for r in take { offerIdentityHeight(r.height) }
            for r in take where recordHeights.contains(r.height) {
                let leaf = store.identityTree.leaf(r.index)
                store.mutate { s in
                    for k in s.regRecords.indices where s.regRecords[k].height == r.height {
                        if s.regRecords[k].leaves.count < Self.maxRecordLeaves && !s.regRecords[k].leaves.contains(where: { $0.index == r.index }) {
                            s.regRecords[k].leaves.append(RegRecord.Leaf(index: r.index, leaf: leaf))
                        }
                        if let t = r.time, t > 0, s.regRecords[k].time == nil { s.regRecords[k].time = t }
                    }
                }
            }
            store.mutate { $0.identityNext += UInt64(take.count) }
            if take.count < rest.count || store.state.identityNext >= page.size || page.rows.count < limit { break }
        }
    }

    /// A uniform sample of identity row heights (registration blocks), the cover set's decoys.
    private func offerIdentityHeight(_ h: UInt64) {
        store.mutate { s in
            s.identityRowsSeen &+= 1
            if s.identityHeights.count < Self.identityHeightSample { s.identityHeights.append(h); return }
            let j = UInt64.random(in: 0 ..< s.identityRowsSeen)
            if j < UInt64(Self.identityHeightSample) { s.identityHeights[Int(j)] = h }
        }
    }

    /// Matches record notes to the leaves appended at their heights (several
    /// registrations may share a block), newest record first, stopping at the
    /// newest that matched (it is the identity). Bounded, and never asking
    /// the LCD about this wallet's own registration block alone:
    ///
    ///  1. activated_at is the registration block's time. The indexer's
    ///     identity rows carry it (the record keeps it as the leaves pass):
    ///     every country (hint, unknown, then A..Z) at exactly that time, at
    ///     most 677 hashes a leaf.
    ///  2. When the rows carry no time (or it did not match), the LCD is asked
    ///     for the block times of a cover set: the record's height among
    ///     `coverSet` - 1 others drawn uniformly from the chain so far, in a
    ///     shuffled order, the same set on every retry. Its time is tried the
    ///     same way; known and unmatched, the record is given up.
    ///  3. Only when no block time can be had, the device clock's built_at is
    ///     searched outward, hinted countries over [-1h, +24h], then every
    ///     other over [-10min, +1h], resumably: the cursor and the hashes
    ///     spent are persisted, each sync spends at most `searchBudget`
    ///     hashes over all records and a record at most `recordSearchCap`.
    ///
    /// A time already tried is not tried again; a new one (or new leaves at
    /// the record's height) is, even after the record was given up.
    private func matchRecords() async {
        var budget = Int64(searchBudget)
        let perTime = Self.predecessors * UInt64(Self.allCountries.count)
        for rec0 in store.state.regRecords.sorted(by: { $0.height > $1.height }) {
            if rec0.status == .matched { return }
            let leaves = rec0.leaves.filter { $0.leaf != .zero }
            if leaves.isEmpty { continue }
            guard let k = store.state.regRecords.firstIndex(where: { $0.position == rec0.position }) else { continue }
            var rec = rec0
            // New leaves since the last attempt: every time is worth trying again.
            if leaves.count > rec.leavesTried {
                rec.tried = []; rec.leavesTried = leaves.count; rec.cursor = 0
                if rec.status == .exhausted { rec.status = .open }
            }
            var found: (UInt64, Fr, UInt64, UInt64)?
            var lcdTime: UInt64?
            // 1. The indexer's block time (bounded by the chain's tip as it streamed).
            let rowTime = rec.time.flatMap { timeOK($0) ? $0 : nil }
            if let t = rowTime, !rec.tried.contains(t) {
                found = tryTime(rec, leaves, t)
                rec.tried.append(t); rec.work &+= perTime * UInt64(leaves.count)
            }
            // 2. The LCD's, asked with a cover set.
            if found == nil, rowTime.map({ rec.tried.contains($0) }) ?? true {
                let (t0, r) = await coverTime(rec)
                let t = t0.flatMap { timeOK($0) ? $0 : nil }
                rec = r
                lcdTime = t
                if let t, !rec.tried.contains(t) {
                    found = tryTime(rec, leaves, t)
                    rec.tried.append(t); rec.work &+= perTime * UInt64(leaves.count)
                }
                if found == nil, t != nil { rec.status = .exhausted }
            }
            // 3. The bounded fallback, only while no chain time is known.
            if found == nil, lcdTime == nil, rec.status == .open {
                let before = rec.work
                let r = search(rec, leaves, budget: UInt64(max(budget, 0)))
                found = r.found
                rec = r.rec
                budget -= Int64(clamping: rec.work &- before)
            }
            if found != nil { rec.status = .matched }
            let updated = rec
            store.mutate { $0.regRecords[k] = updated }
            if let (index, country, at, pred) = found {
                // The newest match is the identity: its generation's, which the wallet acts as from now on.
                let g = rec.generation
                let cur = store.state.slot(g).identity
                // At an index at least the identity's: a match there replaces
                // one made before (an identity from an unverified
                // tree, or another time, is re-matched rather than kept).
                if cur == nil || index >= cur!.leafIndex {
                    let nullifier = (cur?.leafIndex == index ? cur?.passportNullifier : nil) ?? ""
                    store.mutate {
                        $0.withSlot(g) {
                            $0.identity = IdentityRecord(leafIndex: index, dscKey: rec.dscKey, country: country, activatedAt: at, passportNullifier: nullifier,
                                                         verified: true, predecessorAt: pred)
                        }
                    }
                }
                store.mutate { $0.actAs(g) }
                return
            }
            if budget <= 0 { return }
        }
    }

    /// The predecessor_at with which `leaf` is ours at (`dscKey`, `country`,
    /// activated_at `t`), or nil. The chain sets it to the registration's own
    /// block time for a switch or re-entry, 0 for a passport never seen
    /// before, so those are the only two values to try.
    private func predecessorOf(_ idc: Fr, _ leaf: Fr, dscKey: Fr, country: Fr, t: UInt64) -> UInt64? {
        (t == 0 ? [0] : [0, t]).first { PrivacyHash.identityLeaf(idc: idc, dscKey: dscKey, country: country, activatedAt: t, predecessorAt: $0) == leaf }
    }

    /// (index, country, `t`, predecessor_at) if a leaf of `rec` is ours (its generation's idc) at activated_at = `t`.
    private func tryTime(_ rec: RegRecord, _ leaves: [RegRecord.Leaf], _ t: UInt64) -> (UInt64, Fr, UInt64, UInt64)? {
        var countries = [Self.countryOrZero(rec.country)]
        for c in Self.allCountries where c != countries[0] { countries.append(c) }
        let idc = keys.idc(rec.generation)
        for l in leaves {
            for c in countries {
                if let p = predecessorOf(idc, l.leaf, dscKey: rec.dscKey, country: c, t: t) { return (l.index, c, t, p) }
            }
        }
        return nil
    }

    /// `rec`'s block time from the LCD, asked together with a cover set of
    /// other heights (chosen once, uniformly over the chain so far, persisted
    /// with the record so a retry asks the same set), in a shuffled order. At
    /// most `maxCoverTries` fetches; once the LCD answered, its answer is kept
    /// and never asked again.
    private func coverTime(_ rec0: RegRecord) async -> (UInt64?, RegRecord) {
        var rec = rec0
        if let t = rec.chainTime { return (t, rec) }
        if rec.coverTries >= Self.maxCoverTries { return (nil, rec) }
        if rec.cover.isEmpty {
            // Never past the chain's tip: a decoy the chain has
            // not reached yet is no decoy, the LCD sees which height is real.
            var tipHeight = rec.height
            if let t = tip { tipHeight = t.height } else if let t = try? await readTip() { tipHeight = t.height }
            let top = max(min(max(store.state.notesHeight, rec.height), tipHeight), 1)
            var set: [UInt64] = [rec.height]
            // Decoys are other registrations' blocks first (a block time is
            // asked for exactly those when restoring), then any height.
            for h in Array(Set(store.state.identityHeights.filter { $0 >= 1 && $0 <= top && $0 != rec.height })).shuffled()
                .prefix(Self.coverSet - 1) { set.append(h) }
            let want = Int(min(UInt64(Self.coverSet), max(top, 1)))
            while set.count < want {
                let h = UInt64.random(in: 1 ... max(top, 1))
                if !set.contains(h) { set.append(h) }
            }
            rec.cover = set
        }
        var t: UInt64?
        for h in rec.cover.shuffled() {
            let x = await chain.blockTime(h)
            if h == rec.height { t = x }
        }
        rec.coverTries += 1
        rec.chainTime = t
        return (t, rec)
    }

    /// The fallback search for `rec` from its persisted cursor, spending at
    /// most `budget` hashes (and the record's cap).
    private func search(_ rec: RegRecord, _ leaves: [RegRecord.Leaf], budget: UInt64) -> (found: (UInt64, Fr, UInt64, UInt64)?, rec: RegRecord) {
        var hinted = [Self.countryOrZero(rec.country)]
        if hinted[0] != .zero { hinted.append(.zero) }
        let hs = Set(hinted)
        let others = Self.allCountries.filter { !hs.contains($0) }
        let narrowSteps = Self.narrowBefore + Self.narrowAfter + 1
        let total = narrowSteps + Self.wideBefore + Self.wideAfter + 1
        var out = rec
        var spent: UInt64 = 0
        let idc = keys.idc(rec.generation)
        while out.cursor < total {
            let narrow = out.cursor < narrowSteps
            let countries = narrow ? hinted : others
            let cost = Self.predecessors * UInt64(countries.count * leaves.count)
            if spent > 0 && spent + cost > budget { break }
            if out.work + cost > Self.recordSearchCap { out.status = .exhausted; return (nil, out) }
            let off = narrow ? Self.offsetAt(out.cursor, before: Self.narrowBefore, after: Self.narrowAfter)
                : Self.offsetAt(out.cursor - narrowSteps, before: Self.wideBefore, after: Self.wideAfter)
            out.cursor += 1
            out.work += cost
            spent += cost
            // Checked: a candidate that wraps, or that cannot be a block time, is skipped.
            let (t, o) = Int64(clamping: rec.builtAt).addingReportingOverflow(off)
            if o || t < 0 || !timeOK(UInt64(t)) { continue }
            for l in leaves {
                for c in countries {
                    if let p = predecessorOf(idc, l.leaf, dscKey: rec.dscKey, country: c, t: UInt64(t)) {
                        out.status = .matched
                        return ((l.index, c, UInt64(t), p), out)
                    }
                }
            }
        }
        out.status = out.cursor >= total ? .exhausted : .open
        return (nil, out)
    }

    /// A committed registration whose leaf the wallet has not matched
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
        if let (country, pred) = countryFor(leaf: leaf, dscKey: p.dscKey, activatedAt: activatedAt, hint: p.countryHint, generation: p.generation) {
            // The registration's generation: the wallet acts as it from now on.
            store.mutate {
                $0.withSlot(p.generation) {
                    $0.identity = IdentityRecord(leafIndex: index, dscKey: p.dscKey, country: country, activatedAt: activatedAt,
                                                 passportNullifier: p.passportNullifier, verified: true, predecessorAt: pred)
                }
                $0.actAs(p.generation)
                $0.pendingRegistration = nil
            }
        } else {
            p.failure = leaf == .zero ? "the registration's leaf \(index) has been zeroed" : "leaf \(index) does not match this registration"
            store.mutate { $0.pendingRegistration = p }
        }
    }

    /// (country, predecessor_at) with which `leaf` is ours at `activatedAt`, or nil.
    func countryFor(leaf: Fr, dscKey: Fr, activatedAt: UInt64, hint: String = "", generation: Int = 0) -> (Fr, UInt64)? {
        if leaf == .zero { return nil }
        let idc = keys.idc(generation)
        for c in [Self.countryOrZero(hint)] + Self.allCountries {
            if let p = predecessorOf(idc, leaf, dscKey: dscKey, country: c, t: activatedAt) { return (c, p) }
        }
        return nil
    }

    public func identityStatus() -> IdentityStatus {
        Self.identityStatus(store: store, keys: keys)
    }

    static func identityStatus(store: PrivacyStore, keys: PrivacyKeys) -> IdentityStatus {
        guard let id = store.state.identity, id.leafIndex < store.identityTree.size else { return .none }
        // The identity the wallet acts as: its generation's idc.
        let want = PrivacyHash.identityLeaf(idc: keys.idc(store.state.generation), dscKey: id.dscKey, country: id.country, activatedAt: id.activatedAt,
                                            predecessorAt: id.predecessorAt)
        return store.identityTree.leaf(id.leafIndex) == want ? .live : .zeroed
    }
}
