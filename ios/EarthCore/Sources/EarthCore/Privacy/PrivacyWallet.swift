import BigInt
import Foundation

/// One wallet's private side: its notes, stake notes and registration, kept
/// in sync with the chain through the indexer, and every private action the
/// chain offers, laid out as Orchard-style bundles (any number of notes, any
/// asset, padded to at least two actions), proven, signed and broadcast as an
/// unsigned tx whose fee comes out of the bundle's ERTH balance. Ports
/// `privacy/PrivacyWallet.kt` method for method.
///
/// Every public operation holds one lock for its whole run (the Kotlin side's
/// `@Synchronized`), so a sync never interleaves with a spend. Screens read
/// `snapshot`, which is replaced after each operation and never torn.
public final class PrivacyWallet: @unchecked Sendable {
    public static let secondsPerDay: Int64 = 86_400
    /// The chain's current_date_max_skew_seconds (48 h): a registration lands
    /// only while its proof's current_date is within it of the block time. A
    /// switch must also be proven on a later date than the live registration
    /// (error 1128), so the wallet always proves on today's UTC date.
    public static let registrationSkewSeconds: Int64 = 172_800
    /// MsgRegister's gas, for the fee estimate before simulating: the
    /// passport proof (3M) and DSC chain (300k), the fee bundle's two action
    /// proofs and note writes, and the tx's bytes.
    public static let registerGasEstimate: UInt64 = 7_000_000
    /// A private tx's gas for the confirm sheet: what the sheet shows is the
    /// most the tx may then pay without asking again (a higher
    /// simulated fee shows the sheet again at it). Two actions, a stake or
    /// membership proof, the tx's bytes and the 10% headroom fit under it.
    public static let privateGasEstimate: UInt64 = 10_000_000
    /// A handle bind's: the chain prices it as nine note writes, 1.2M more
    /// than `privateGasEstimate`'s
    /// membership, with a three-action fee bundle (the state record). As Android.
    public static let bindHandleGasEstimate: UInt64 = 12_500_000
    /// Slack against the chain's clock for bounds the wallet must stay under.
    public static let clockMargin: Int64 = 600
    /// x/shielded's default max_actions_per_bundle, until the chain is read.
    public static let defaultMaxActions = 16

    public static let fee = "uerth"
    public static let derthPrefix = "derth/"
    public static let lpPrefix = "dexlp/"
    /// Owner-tag counters scanned past the highest known (PRIVACY_FORMATS.md 7).
    public static let otagGap: UInt32 = 1024
    /// A pending registration whose tx failed in its block.
    public static let txFailed = "the registration tx failed"

    public let keys: PrivacyKeys
    let store: PrivacyStore
    private let indexer: PrivacyIndexer
    private let chain: PrivateChain
    private let reads: PrivacyChainReads
    public let chainID: String
    /// The chain's own trees, every synced root is checked against.
    private let roots: ChainRoots
    private let now: @Sendable () -> Int64
    private let engine: PrivateTxEngine
    private let mutex = AsyncMutex()

    public init(keys: PrivacyKeys, store: PrivacyStore, indexer: PrivacyIndexer, chain: PrivateChain, reads: PrivacyChainReads,
                prover: PrivacyProver, chainID: String, roots: ChainRoots,
                now: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970) }) {
        self.keys = keys; self.store = store; self.indexer = indexer; self.chain = chain; self.reads = reads
        self.chainID = chainID; self.roots = roots; self.now = now
        engine = PrivateTxEngine(chainID: chainID, chain: chain, prover: prover)
        snapshotValue = Snapshot(store: store, keys: keys, maxActions: Self.defaultMaxActions)
    }

    public var address: ShieldedAddress { keys.address }

    // MARK: - snapshot

    /// What the screens show, as of the last operation.
    public struct Snapshot: Sendable {
        public let notes: [OwnedNote]
        public let stakeNotes: [OwnedStakeNote]
        public let identity: IdentityRecord?
        public let identityStatus: WalletSync.IdentityStatus
        public let claimedDays: Set<UInt64>
        public let caretakerSplit: [UInt64: UInt64]
        public let caretakerCastAt: Int64
        /// The split's expiry as the chain reported it (0: unknown or none).
        public let caretakerExpiresAt: Int64
        public let caretakerMovedOut: Bool
        /// This identity's handle ("" for none), and whether it moved one away.
        public let handle: String
        public let handleMovedOut: Bool
        /// The split is held but was restored without its options.
        public let caretakerSplitUnknown: Bool
        /// Moves in flight, either way, and the wallet a switch's moves went to.
        public let pendingMoves: [PendingMove]
        public let switchTarget: String
        /// Undelegations waiting for their payout (local only).
        public let pendingUnbonds: [PendingUnbond]
        public let syncedHeight: UInt64
        /// A committed registration whose leaf is not matched yet (nil: none), and why, if it failed.
        public let pendingRegistration: PendingRegistration?
        /// Until when a registration this wallet broadcast can still land (0: none ever).
        public let registrationKeepUntil: Int64
        /// Whether the last sync's roots matched the chain's; no private tx is built on unverified ones.
        public let rootsVerified: Bool
        public let rootsError: String?
        /// x/shielded max_actions_per_bundle: the most notes (and outputs) one bundle carries.
        public let maxActions: Int
        /// Why the last save that could not throw failed (nil: saved).
        public let saveError: String?
        /// The genesis the synced data is from.
        public let genesis: String?

        init(store: PrivacyStore, keys: PrivacyKeys, maxActions: Int, saveError: String? = nil) {
            let s = store.state
            notes = s.notes; stakeNotes = s.stakeNotes; identity = s.identity; claimedDays = s.claimedDays
            caretakerSplit = s.caretakerSplit; caretakerCastAt = s.caretakerCastAt
            caretakerExpiresAt = s.caretakerExpiresAt; caretakerMovedOut = s.caretakerMovedOut
            handle = s.handle; handleMovedOut = s.handleMovedOut
            caretakerSplitUnknown = s.caretakerSplitUnknown; pendingMoves = s.pendingMoves; switchTarget = s.switchTarget
            pendingUnbonds = s.pendingUnbonds; syncedHeight = s.notesHeight
            pendingRegistration = s.pendingRegistration; registrationKeepUntil = s.registrationKeepUntil; rootsVerified = s.rootsVerified; rootsError = s.rootsError
            identityStatus = WalletSync.identityStatus(store: store, keys: keys)
            self.maxActions = maxActions
            self.saveError = saveError
            genesis = s.genesis
        }

        /// Spendable pool balance per denom (pending spends excluded),
        /// saturating at 2^63-1, as Android.
        public var poolBalances: [String: UInt64] {
            var out: [String: UInt64] = [:]
            for n in notes where n.unspent && n.pendingAt == nil {
                out[n.note.denom] = Self.satAdd63(out[n.note.denom] ?? 0, n.note.value)
            }
            return out
        }

        static func satAdd63(_ a: UInt64, _ b: UInt64) -> UInt64 { min(PrivateMsgs.saturatingAdd(a, b), UInt64(Int64.max)) }

        /// Stake (derth/<valoper>) per denom: owner-locked, never sendable.
        public var stakeBalances: [String: UInt64] {
            var out: [String: UInt64] = [:]
            for n in stakeNotes where n.spendable { out[n.denom] = Self.satAdd63(out[n.denom] ?? 0, n.amount) }
            return out
        }

        /// Everything held privately: the pool's denoms and the stake denoms.
        public var balances: [String: UInt64] { poolBalances.merging(stakeBalances, uniquingKeysWith: Self.satAdd63) }

        /// Spendable note counts per denom with more than one note.
        public var mergeable: [String: Int] {
            var counts: [String: Int] = [:]
            for n in notes where n.unspent && n.pendingAt == nil && n.note.value > 0 { counts[n.note.denom, default: 0] += 1 }
            return counts.filter { $0.value >= 2 }
        }

        /// derth denoms held in more than one stake note that can merge now (at most one labelled), with their note counts.
        public var stakeMergeable: [String: Int] {
            var counts: [String: Int] = [:]
            var unlabelled: Set<String> = []
            for n in stakeNotes where n.spendable && n.denom.hasPrefix(PrivacyWallet.derthPrefix) {
                counts[n.denom, default: 0] += 1
                if n.label == nil { unlabelled.insert(n.denom) }
            }
            return counts.filter { $0.value >= 2 && unlabelled.contains($0.key) }
        }

        /// Private LP shares per pool id (dexlp/<id> notes).
        public var lpShares: [UInt64: UInt64] {
            var out: [UInt64: UInt64] = [:]
            for (d, v) in poolBalances where d.hasPrefix(PrivacyWallet.lpPrefix) {
                if let id = UInt64(d.dropFirst(PrivacyWallet.lpPrefix.count)) { out[id] = v }
            }
            return out
        }

        /// The most of ERTH one unshield can release (every note one bundle carries).
        public var unshieldableErth: UInt64 { ShieldMove.maxUnshield(notes, maxNotes: maxActions) }
    }

    private let snapLock = NSLock()
    private var snapshotValue: Snapshot
    private var maxActionsValue = PrivacyWallet.defaultMaxActions
    private var saveErrorValue: String?

    public var snapshot: Snapshot {
        snapLock.lock(); defer { snapLock.unlock() }
        return snapshotValue
    }

    private func publish() {
        snapLock.lock()
        let m = maxActionsValue
        let e = saveErrorValue
        snapLock.unlock()
        let s = Snapshot(store: store, keys: keys, maxActions: m, saveError: e)
        snapLock.lock(); snapshotValue = s; snapLock.unlock()
    }

    /// Runs `body` under the wallet's lock, then republishes the snapshot.
    private func locked<T>(_ body: () async throws -> T) async rethrows -> T {
        try await mutex.withLock {
            defer { publish() }
            return try await body()
        }
    }

    /// `locked` for a body that cannot fail.
    func lockedNoThrow(_ body: () -> Void) async { await locked { body() } }

    public var notes: [OwnedNote] { snapshot.notes }
    public var stakeNotes: [OwnedStakeNote] { snapshot.stakeNotes }
    public func balances() -> [String: UInt64] { snapshot.balances }
    public func poolBalances() -> [String: UInt64] { snapshot.poolBalances }
    public func stakeBalances() -> [String: UInt64] { snapshot.stakeBalances }
    public func mergeable() -> [String: Int] { snapshot.mergeable }
    public func identityStatus() -> WalletSync.IdentityStatus { snapshot.identityStatus }
    /// A committed registration whose leaf is not matched yet (nil: none), and why, if it failed.
    public var pendingRegistration: PendingRegistration? { snapshot.pendingRegistration }
    /// Until when a registration this wallet broadcast can still land (nil:
    /// none can any more). Its identity, so its recovery phrase, must be kept
    /// until then, and the wallet counts as possibly registered.
    public func registrationMayLandUntil() -> Int64? {
        let t = snapshot.registrationKeepUntil
        return t > now() ? t : nil
    }

    /// x/shielded max_actions_per_bundle, read from the chain (the last value on a read failure).
    public func maxActions() async -> Int {
        if let m = try? await chain.maxActionsPerBundle() {
            setMaxActions(m)
            return m
        }
        return cachedMaxActions()
    }

    private func setMaxActions(_ m: Int) { snapLock.lock(); maxActionsValue = m; snapLock.unlock() }

    private func cachedMaxActions() -> Int { snapLock.lock(); defer { snapLock.unlock() }; return maxActionsValue }

    // MARK: - sync

    @discardableResult
    public func sync() async throws -> WalletSync.Result {
        _ = await maxActions()
        // The validator list (Query/Validators, whole) is read again with
        // every sync, as with every quote: what pickers and stake values
        // show, and the derth denoms sync names stake notes by. A failed read
        // keeps the last one.
        _ = try? await reads.validators()
        return try await locked {
            await fillPendingRegistration()
            _ = await resolvePendingMovesLocked()
            let r = try await syncLocked()
            await resolveUnbondsLocked()
            return r
        }
    }

    private func syncLocked() async throws -> WalletSync.Result {
        try await WalletSync(indexer: indexer, store: store, keys: keys, chainID: chainID, chain: roots, now: now).sync()
    }

    /// A registration recorded at acceptance whose block the wallet has
    /// not seen (the wait timed out, the app was killed) is looked up by its
    /// hash: committed, it gets its leaf index and activated_at; failed in
    /// its block, the failure is kept for the UI (a new registration
    /// replaces it).
    private func fillPendingRegistration() async {
        guard var p = store.state.pendingRegistration, p.leafIndex == nil, p.failure?.hasPrefix(Self.txFailed) != true,
              let r = try? await chain.tx(p.txHash) else { return }
        if r.code != 0 {
            p.failure = "\(Self.txFailed) (code \(r.code)): \(r.log.prefix(200))"
        } else {
            guard let index = r.attr("register", "leaf_index").flatMap(UInt64.init) else { return }
            p.leafIndex = index; p.activatedAt = UInt64(max(0, r.time)); p.failure = nil
        }
        let updated = p
        store.mutate { $0.pendingRegistration = updated }
        persistNoThrow()
    }

    /// A note the chain will mint to us: fresh secrets, their v2 ciphertext to our own address.
    private func mint(_ denom: String) throws -> NoteOut { try NoteOut.mintToSelf(keys, denom: denom) }

    private func today() -> UInt64 { UInt64(max(0, now()) / Self.secondsPerDay) }

    /// The chain's time, the LCD tip's block time, for what the
    /// chain checks against its own clock (predecessor bounds, the removal
    /// day); the device clock only when the node cannot say.
    private func chainNow() async -> Int64 {
        if let t = await roots.latestBlock()?.time, t > 0, t <= UInt64(Int64.max) { return Int64(t) }
        return now()
    }

    // MARK: - running

    /// Proves and broadcasts. Only on trees the chain itself vouched for at
    /// the last sync: a proof over an indexer's forged tree is refused by
    /// the chain anyway, and its notes may not exist.
    private func run(memo: String = "", accepted: (String, UInt64) -> Void = { _, _ in }, rejected: (String) -> Void = { _ in },
                     _ assemble: (UInt64) throws -> Assembled) async throws -> TxResult {
        try requireVerified()
        try await requireFreshAnchor()
        // The spent notes are marked before the tx is sent,
        // under its hash: a wait that times out (the tx may still land), a
        // lost answer or a killed app never leaves them spendable. They stay
        // pending until the chain is past the tx's timeout_height and says the
        // tx is not in a block (WalletSync.releaseStalePending).
        let (result, _) = try await tipChecked { [self] in
            try await engine.run(assemble, memo: memo, shownFee: Self.shownFee, verifiedHeight: store.state.verifiedHeight, accepted: { [self] hash, a, timeout in
                markPending(a.spends, a.stakeSpends, timeoutHeight: timeout, hash: hash)
                accepted(hash, timeout)
            }, rejected: { [self] hash, a in
                unmarkPending(a.spends, a.stakeSpends, hash: hash)
                rejected(hash)
            })
        }
        return result
    }

    /// A tip far past the last verified sync height is either a
    /// stale sync (sync, and try once more) or a node lying about the tip
    /// (refused again: nothing was laid out, proven or sent). Under the lock.
    private func tipChecked<T>(_ body: () async throws -> T) async throws -> T {
        do {
            return try await body()
        } catch is PrivateTxEngine.TipOutOfRange {
            _ = try await syncLocked()
            try requireVerified()
            do {
                return try await body()
            } catch let e as PrivateTxEngine.TipOutOfRange {
                throw PrivacyError("the node says the chain is at height \(e.tip), far past the \(e.verified) this wallet verified; try another node or sync again")
            }
        }
    }

    /// The fee the confirm sheet showed, for the private run in this task
    /// (TxController binds it around the run): a fee above it throws
    /// `PrivateTxEngine.FeeAboveQuote` and the sheet asks again.
    /// Every tx the app sends comes from a confirm sheet, so it is always
    /// bound there; unbound (tests) only the cap applies.
    @TaskLocal public static var shownFee: UInt64?

    /// Whether the local note tree's root, every bundle's anchor, stays an
    /// anchor long enough: CheckTx refuses one lapsing within
    /// 120 s of the last block, and a proposer leaves out a tx whose anchor
    /// lapsed by its block. `anchorMargin` covers proving and the tx's
    /// timeout_height on top. Nil: the node could not say (the chain's own
    /// check stands).
    private func anchorFresh() async -> Bool? {
        if store.noteTree.size == 0 { return true }
        guard let rec = try? await roots.noteRoot(store.noteTree.root()) else { return nil }
        if !rec.valid { return false }
        guard let exp = rec.expiresAt else { return nil }
        if exp == 0 { return true }
        let t = await chainNow()
        return exp >= Handles.satAdd(t, Self.anchorMargin)
    }

    /// A root lapsing soon is replaced by a newer one before anything is laid
    /// out: sync (newer notes, a newer root), and refuse if it is still too
    /// old (an indexer behind the chain). Runs under the wallet's lock.
    private func requireFreshAnchor() async throws {
        if await anchorFresh() != false { return }
        _ = try await syncLocked()
        try requireVerified()
        if await anchorFresh() == false { throw AnchorTooOld() }
    }

    /// How long an anchor must stay valid past the chain's last block for a
    /// tx built on it: the chain's CheckTx margin (120 s), the tx's
    /// timeout_height (50 blocks) and proving on a phone, with room.
    public static let anchorMargin: Int64 = 1_800

    /// The local note tree's root lapses too soon to anchor a tx, and a sync found none newer. Nothing was sent.
    public struct AnchorTooOld: Swift.Error, LocalizedError {
        public var errorDescription: String? {
            "This wallet's notes are anchored to a note tree the chain stops accepting within \(PrivacyWallet.anchorMargin / 60) minutes; sync again and retry."
        }
    }

    /// Only on roots verified by the last sync, in that sync's own
    /// generation (a sync that failed part way leaves them unverified).
    private func requireVerified() throws {
        let s = store.state
        guard s.rootsVerified, s.verifiedGeneration == s.syncGeneration else {
            throw PrivacyError(s.rootsError ?? "the wallet has not checked its notes against the chain yet; sync again")
        }
    }

    /// What `send` would charge (simulated with placeholder nullifiers, nothing proven): for a confirm sheet.
    public func quoteSend(to: ShieldedAddress, denom: String, amount: UInt64, memo: Data = Data()) async throws -> PrivateTxEngine.Quote {
        try Self.requireTransferable(denom)
        let m = await maxActions()
        return try await locked {
            let out = try NoteOut.to(to, denom: denom, value: amount, memo: memo)
            return try await tipChecked { [self] in
                try await engine.quote({ fee in
                    let b = try self.bundle([out], release: [Self.fee: fee], maxActions: m)
                    return Assembled(bundles: [b]) { bs, _, _ in MsgSend(bundle: bs[0], fee: fee) }
                }, verifiedHeight: store.state.verifiedHeight)
            }
        }
    }

    private func markPending(_ spent: [OwnedNote], _ stake: [OwnedStakeNote], timeoutHeight: UInt64, hash: String) {
        let positions = Set(spent.map(\.position))
        let stakePositions = Set(stake.map(\.position))
        let t = now()
        store.mutate { s in
            for i in s.notes.indices where positions.contains(s.notes[i].position) {
                s.notes[i].pendingAt = t; s.notes[i].pendingUntil = timeoutHeight; s.notes[i].pendingTx = hash
            }
            for i in s.stakeNotes.indices where stakePositions.contains(s.stakeNotes[i].position) {
                s.stakeNotes[i].pendingAt = t; s.stakeNotes[i].pendingUntil = timeoutHeight; s.stakeNotes[i].pendingTx = hash
            }
        }
        persistNoThrow()
    }

    /// A broadcast refused outright (in no mempool): the notes it marked are spendable again.
    private func unmarkPending(_ spent: [OwnedNote], _ stake: [OwnedStakeNote], hash: String) {
        let positions = Set(spent.map(\.position))
        let stakePositions = Set(stake.map(\.position))
        store.mutate { s in
            for i in s.notes.indices where positions.contains(s.notes[i].position) && s.notes[i].pendingTx == hash {
                s.notes[i].pendingAt = nil; s.notes[i].pendingUntil = nil; s.notes[i].pendingTx = nil
            }
            for i in s.stakeNotes.indices where stakePositions.contains(s.stakeNotes[i].position) && s.stakeNotes[i].pendingTx == hash {
                s.stakeNotes[i].pendingAt = nil; s.stakeNotes[i].pendingUntil = nil; s.stakeNotes[i].pendingTx = nil
            }
        }
        persistNoThrow()
    }

    /// A bundle releasing `release` (per denom, the fee included in uerth)
    /// and paying `outputs`, from whichever notes cover it; `forced` are
    /// spent whatever (a merge), their surplus coming back as change.
    private func bundle(_ outputs: [NoteOut] = [], release: [String: UInt64], forced: [OwnedNote] = [], maxActions: Int) throws -> BundlePlan {
        try BundleBuilder.plan(keys: keys, tree: store.noteTree, notes: store.state.notes, outputs: outputs, release: release,
                               maxActions: maxActions, forced: forced)
    }

    /// A bundle whose only balance is `fee` uerth: a private action's fee.
    private func feeBundle(_ fee: UInt64, maxActions: Int) throws -> BundlePlan {
        try bundle(release: [Self.fee: fee], maxActions: maxActions)
    }

    private static func plus(_ a: [String: UInt64], _ d: String, _ v: UInt64) throws -> [String: UInt64] {
        var out = a
        let (s, o) = (a[d] ?? 0).addingReportingOverflow(v)
        try require(!o, "amount overflows")
        out[d] = s
        return out
    }

    // MARK: - membership

    /// The chain is ahead of the local trees for what is asked: sync, then try again.
    public struct SyncFirst: Swift.Error, LocalizedError {
        public var errorDescription: String? { "The proposal's stake snapshot is ahead of this wallet; sync first." }
    }

    /// The identity is too recent for this action (or replaced another too recently); it opens `waitSeconds` from now.
    /// `notHeld`: a renewal or refresh sent with no bound, by an identity whose own bound has not
    /// passed, which the chain refused (in its ante, before any fee): this identity holds nothing there.
    /// `lapsed`: held but not live, so bounded like a claim, which this identity cannot make yet;
    /// refused before anything was sent (a handle in its renewal period, a caretaker split past its expiry).
    public struct NotYet: Swift.Error, LocalizedError {
        public enum Lapsed: Sendable, Equatable { case handle(String), caretaker }
        public let waitSeconds: Int64
        public var notHeld: Bool = false
        public var lapsed: Lapsed?
        public init(waitSeconds: Int64, notHeld: Bool = false, lapsed: Lapsed? = nil) {
            self.waitSeconds = waitSeconds; self.notHeld = notHeld; self.lapsed = lapsed
        }
        public var errorDescription: String? {
            let days = waitSeconds / PrivacyWallet.secondsPerDay + 1
            switch lapsed {
            case let .handle(h)?:
                return "@\(h) is past its expiry (in its renewal period): renewing or changing it now counts as a new claim, and this identity "
                    + "replaced another too recently to make one; that opens in \(days) days. Until its renewal period ends nobody else can take it."
            case .caretaker?:
                return "Your caretaker vote has lapsed: casting again counts as a new vote, and this identity replaced another too recently to make one; "
                    + "that opens in \(days) days."
            case nil: break
            }
            if notHeld {
                return "The chain says this identity holds nothing live here (nothing was charged): a lapsed one renews only as a new claim, "
                    + "and this identity replaced another too recently to make one; that opens in \(days) days."
            }
            return waitSeconds > 2 * PrivacyWallet.secondsPerDay
                ? "This identity replaced another too recently for this action; it opens in \(waitSeconds / PrivacyWallet.secondsPerDay + 1) days."
                : "This registration is too recent for this action; try again in \(waitSeconds / 3600 + 1)h."
        }
    }

    private func identity() throws -> IdentityRecord {
        guard let id = store.state.identity else { throw PrivacyError("This wallet has no registration.") }
        guard WalletSync.identityStatus(store: store, keys: keys) == .live else {
            throw PrivacyError("This wallet's registration is no longer live; register again.")
        }
        return id
    }

    /// A membership proof's witness for `scope` under the chain's statement:
    /// activated_at <= `maxActivation`, predecessor_at <= `maxPredecessor`
    /// (PrivacyHash.noBound: none).
    private func membership(scope: Fr, excludedDsc: Fr, excludedCountry: Fr, maxActivation: UInt64, maxPredecessor: UInt64) throws -> MembershipWitnessSpec {
        let id = try identity()
        if id.activatedAt > maxActivation { throw NotYet(waitSeconds: Int64(clamping: id.activatedAt - maxActivation)) }
        if id.predecessorAt > maxPredecessor { throw NotYet(waitSeconds: Int64(clamping: id.predecessorAt - maxPredecessor)) }
        let tree = store.identityTree
        let path = tree.path(id.leafIndex)
        let root = tree.root()
        let idSecret = keys.idSecret
        return MembershipWitnessSpec { signal in
            try MembershipWitness(idSecret: idSecret, dscKey: id.dscKey, country: id.country, activatedAt: id.activatedAt,
                                  predecessorAt: id.predecessorAt,
                                  leafIndex: id.leafIndex, siblings: path, root: root, scope: scope, signal: signal,
                                  excludedDsc: excludedDsc, excludedCountry: excludedCountry, maxActivation: maxActivation,
                                  maxPredecessor: maxPredecessor)
        }
    }

    // MARK: - pool

    /// A private send of `amount` `denom` to `to`; the fee comes out of ERTH notes.
    public func send(to: ShieldedAddress, denom: String, amount: UInt64, memo: Data = Data()) async throws -> TxResult {
        try Self.requireTransferable(denom)
        let m = await maxActions()
        return try await locked {
            let out = try NoteOut.to(to, denom: denom, value: amount, memo: memo)
            return try await run { fee in
                let b = try self.bundle([out], release: [Self.fee: fee], maxActions: m)
                return Assembled(bundles: [b]) { bs, _, _ in MsgSend(bundle: bs[0], fee: fee) }
            }
        }
    }

    /// Unshields so that `receiver` gets exactly `amount` `denom`, the fee
    /// from ERTH notes. With `feeFromAmount` (ERTH only) the receiver gets
    /// `amount` less the fee instead: the bundle releases exactly `amount`,
    /// so the most a wallet holds can leave in one go (Max).
    public func unshield(receiver: String, denom: String, amount: UInt64, feeFromAmount: Bool = false, memo: String = "") async throws -> TxResult {
        try Self.requireTransferable(denom)
        try require(!denom.hasPrefix(Self.lpPrefix), "LP shares leave the pool only by a withdrawal")
        try require(!feeFromAmount || denom == Self.fee, "only an ERTH unshield pays its fee from the amount")
        // The chain refuses an unshield to any module account.
        if let module = PrivateMsgs.moduleAccount(of: Data(try Bech32.decode(receiver).data)) {
            throw PrivacyError("\(receiver) is the \(module) module account; it cannot receive an unshield")
        }
        let m = await maxActions()
        return try await locked {
            // The memo (an exchange's deposit tag) is bound by the sighash.
            try await run(memo: memo) { fee in
                let release: [String: UInt64]
                if feeFromAmount {
                    try require(amount > fee, "the amount must exceed the \(fee)uerth fee")
                    release = [Self.fee: amount]
                } else {
                    release = try Self.plus([denom: amount], Self.fee, fee)
                }
                let b = try self.bundle(release: release, maxActions: m)
                return Assembled(bundles: [b]) { bs, _, _ in MsgSend(bundle: bs[0], receiver: receiver, fee: fee) }
            }
        }
    }

    /// Consolidates `denom`: its smallest spendable notes (as many as one
    /// bundle carries) become one, the fee paid from ERTH notes (for ERTH,
    /// from the merged notes themselves). Only needed when a balance is
    /// spread over more notes than max_actions_per_bundle.
    public func merge(denom: String) async throws -> TxResult {
        let m = await maxActions()
        return try await locked {
            try await run { fee in
                let budget = denom == Self.fee ? m : m - 1
                let ns = Array(NoteSelection.spendable(self.store.state.notes, denom: denom).sorted(by: NoteSelection.ascending).prefix(budget))
                try require(ns.count >= 2, "nothing to merge")
                if denom == Self.fee {
                    try require(ns.reduce(UInt64(0)) { PrivateMsgs.saturatingAdd($0, $1.note.value) } > fee,
                                "these notes do not cover the \(fee)uerth fee")
                }
                let b = try self.bundle(release: [Self.fee: fee], forced: ns, maxActions: m)
                return Assembled(bundles: [b]) { bs, _, _ in MsgSend(bundle: bs[0], fee: fee) }
            }
        }
    }

    /// A note to self for MsgShield (transparent coins into the pool; signed,
    /// so built by the caller's key): the chain mints it, so it carries a v2
    /// ciphertext of fresh secrets, which sync opens against the shield's
    /// public amount.
    public func shieldOutput(denom: String, amount: UInt64) throws -> NoteOut { try mint(denom) }

    static func requireTransferable(_ denom: String) throws {
        try require(!denom.hasPrefix(derthPrefix), "stake is owner-locked: it cannot be sent or unshielded")
    }

    // MARK: - personhood

    /// The notes a registration pays and the binding its passport proof
    /// carries. Hold until `register`. Both notes are chain-minted, so each
    /// carries a v2 ciphertext of fresh secrets to our own address; the
    /// binding covers those ciphertexts, so they are written here, before the
    /// passport is proven, and sent exactly as they are. `gas` is the
    /// /gas/register note (its ciphertext is not bound). `referrer`, when the
    /// registrant names one, is a handle, bound into the affiliate field as
    /// H(TAG_AFFILIATE, Bytes(handle)); the chain mints the referrer's half
    /// itself, to the address the handle resolves to.
    public struct RegistrationPrep: Sendable {
        public let anml: NoteOut
        public let erth: NoteOut
        public let gas: NoteOut
        /// The referrer's handle ("" for none).
        public let referrer: String
        public let binding: Fr
        public let idc: Fr
    }

    /// A referrer named by handle, resolved from the directory: the handle and the address it names now.
    public struct Referrer: Sendable, Equatable {
        public let handle: String
        public let address: ShieldedAddress
        public init(handle: String, address: ShieldedAddress) { self.handle = handle; self.address = address }
    }

    public func prepareRegistration(referrer: Referrer?) async throws -> RegistrationPrep {
        try await locked {
            let anml = try mint("uanml")
            let erth = try mint("uerth")
            let gas = try mint("uerth")
            var aff = Fr.zero
            if let r = referrer {
                try require(Handles.valid(r.handle), "\(r.handle) is not a handle")
                try require(r.address.ownerPK != keys.ownerPK, "a registration cannot name its own wallet as its referrer")
                aff = PrivacyHash.affiliateField(handle: r.handle)
            }
            let binding = PrivacyHash.registrationBinding(chainID: chainID, idc: keys.idc, pcAnml: anml.pc, ctAnml: anml.ciphertext, pcErth: erth.pc,
                                                          ctErth: erth.ciphertext, affiliate: aff)
            return RegistrationPrep(anml: anml, erth: erth, gas: gas, referrer: referrer?.handle ?? "",
                                    binding: binding, idc: keys.idc)
        }
    }

    /// MsgRegister without its fee bundle: what /gas/register checks.
    public func registerMsg(_ prep: RegistrationPrep, proof: Data, publicSignals: [String], signatureAlgorithm: String, dscDer: Data) -> MsgRegisterPrivate {
        MsgRegisterPrivate(fee: nil, proof: proof, publicSignals: publicSignals, signatureAlgorithm: signatureAlgorithm, dscDer: dscDer,
                           idc: prep.idc.bytes, pcAnml: prep.anml.pc.bytes, ciphertextAnml: prep.anml.ciphertext,
                           pcErth: prep.erth.pc.bytes, ciphertextErth: prep.erth.ciphertext,
                           affiliateHandle: prep.referrer)
    }

    /// Broadcasts the registration, its fee paid by a fee bundle (the gas
    /// grant's note, on a first registration) that also carries the
    /// registration record note (PRIVACY_FORMATS.md 6: a value-0 note to
    /// ourselves whose memo lets a wallet restored from the mnemonic find the
    /// leaf), and records the registration as pending before anything else,
    /// then tries to resolve it. `publicSignals` are the passport
    /// proof's: [current_date, address, nullifier, dsc_key].
    public func register(_ prep: RegistrationPrep, proof: Data, publicSignals: [String], signatureAlgorithm: String, dscDer: Data) async throws -> TxResult {
        let mx = await maxActions()
        let result: TxResult = try await locked {
            try require(publicSignals.count == 4, "a passport proof has four public signals")
            try require(try PrivateMsgs.decimalField(publicSignals[1]) == prep.binding, "the passport proof is bound to other notes")
            try require(PrivateMsgs.isCalendarDate(publicSignals[0]), "the passport proof's current_date \(publicSignals[0]) is not a calendar date")
            let base = registerMsg(prep, proof: proof, publicSignals: publicSignals, signatureAlgorithm: signatureAlgorithm, dscDer: dscDer)
            // Before any byte leaves: a registration that fails or is refused
            // is public and may still land while its current_date is in the
            // chain's skew, so this identity counts as possibly registered
            // until then.
            let proofDate = PrivateMsgs.calendarDateUnix(publicSignals[0])!
            store.mutate { $0.registrationKeepUntil = max($0.registrationKeepUntil, Handles.satAdd(proofDate, Self.registrationSkewSeconds)) }
            try store.save()
            let dscKey = try PrivateMsgs.decimalField(publicSignals[3])
            let hint = Self.dscCountry(dscDer)
            let record = try NoteOut.to(keys.address, denom: Self.fee, value: 0,
                                        memo: WalletSync.regMemo(nk: keys.nk, dscKey: dscKey, country: hint, builtAt: UInt64(max(0, now()))))
            let pending: (String, UInt64) -> Void = { [self] hash, _ in
                // By hash, the moment the node accepts it; the leaf comes later.
                store.mutate {
                    $0.pendingRegistration = PendingRegistration(
                        txHash: hash, leafIndex: nil, dscKey: dscKey, passportNullifier: publicSignals[2],
                        publicSignals: publicSignals, activatedAt: nil, countryHint: hint)
                }
                persistNoThrow()
            }
            let refused: (String) -> Void = { [self] hash in
                if store.state.pendingRegistration?.txHash == hash { store.mutate { $0.pendingRegistration = nil }; persistNoThrow() }
            }
            let result = try await run(accepted: pending, rejected: refused) { fee in
                Assembled(bundles: [try self.bundle([record], release: [Self.fee: fee], maxActions: mx)]) { bs, _, _ in
                    var m = base
                    m.fee = bs[0]
                    return m
                }
            }
            try recordPendingLocked(result)
            return result
        }
        _ = try? await sync()
        return result
    }

    /// Fills the pending registration from its committed tx: the leaf index
    /// from its register event, activated_at its block time. Every later
    /// sync retries until the leaf is in the local identity tree and matches.
    private func recordPendingLocked(_ result: TxResult) throws {
        guard let index = result.attr("register", "leaf_index").flatMap(UInt64.init) else {
            throw PrivacyError("registration tx \(result.hash) has no leaf_index")
        }
        guard var p = store.state.pendingRegistration, p.txHash == result.hash else { return }
        p.leafIndex = index; p.activatedAt = UInt64(max(0, result.time)); p.failure = nil
        let updated = p
        store.mutate { $0.pendingRegistration = updated }
        try store.save()
    }

    /// The ISO alpha-2 of the DSC's issuer (C=): the wallet's guess at the
    /// country the chain records for the registration (the verifying CSCA's).
    /// "" when unparsable.
    public static func dscCountry(_ dscDer: Data) -> String {
        guard let c = try? Certificate.issuerCountry(der: dscDer) else { return "" }
        let u = c.uppercased()
        return u.utf8.count == 2 && u.utf8.allSatisfy({ (0x41 ... 0x5a).contains($0) }) ? u : ""
    }

    /// Today's ANML. Opens once the identity was activated before yesterday began.
    public func claimAnml(day: UInt64? = nil) async throws -> TxResult {
        let mx = await maxActions()
        return try await locked {
            let day = day ?? today()
            // Day 0 has no day before it: (day - 1) would underflow.
            try require(day >= 1, "no claim day before day 1")
            // Chain-minted: a v2 ciphertext, opened against the mint's public amount.
            let anml = try mint("uanml")
            // Claims bound the activation only (start of yesterday); the predecessor is no bound.
            let m = try membership(scope: PrivacyHash.claimScope(day: day), excludedDsc: .zero, excludedCountry: .zero,
                                   maxActivation: (day - 1).multipliedReportingOverflow(by: UInt64(Self.secondsPerDay)).partialValue,
                                   maxPredecessor: PrivacyHash.noBound)
            let r = try await run { fee in
                Assembled(bundles: [try self.feeBundle(fee, maxActions: mx)], membership: m) { bs, _, mem in
                    MsgClaimAnmlPrivate(fee: bs[0], membership: mem!, day: day, pc: anml.pc.bytes, ciphertext: anml.ciphertext)
                }
            }
            store.mutate { _ = $0.claimedDays.insert(day) }
            try store.save()
            return r
        }
    }

    public func claimedToday() -> Bool { snapshot.claimedDays.contains(today()) }

    /// When ANML can next be claimed: 0 for now, nil without a live
    /// registration. A claim for day d needs activated_at <= (d - 1) * 86400,
    /// so a fresh registration first claims on the day after next.
    public func claimOpensAt() -> Int64? {
        let snap = snapshot
        guard snap.identityStatus == .live, let id = snap.identity else { return nil }
        // Checked throughout: an activated_at no block can have
        // (sync bounds them; an old store may hold one) has no answer, never a trap.
        guard id.activatedAt <= UInt64(Int64.max) else { return nil }
        let a = Int64(id.activatedAt)
        let (firstDay, o1) = (a / Self.secondsPerDay).addingReportingOverflow(1 + (a % Self.secondsPerDay == 0 ? 0 : 1))
        let t = Int64(today())
        let day = max(t + (snap.claimedDays.contains(UInt64(t)) ? 1 : 0), firstDay)
        let (at, o2) = day.multipliedReportingOverflow(by: Self.secondsPerDay)
        if o1 || o2 { return nil }
        return day == t ? 0 : at
    }

    /// The day every activation bound keeps from now (the largest identity root window).
    public static let activationMargin: Int64 = 86_400

    /// The chain's lease bounds now (Query/LeaseBounds), checked before use:
    /// lease lengths in range, a sane margin, and bounds that are what the
    /// lengths give at its block time. Every max_predecessor below comes from
    /// these lease lengths, never from Params.
    private func leaseBounds() async throws -> PrivacyReads.LeaseBounds {
        let lb = try await reads.leaseBounds()
        guard lb.blockTime > 0 else { throw PrivacyError("the node's lease bounds have no block time") }
        _ = try Self.leaseParam(lb.handleLeaseSeconds, "handle lease")
        _ = try Self.leaseParam(lb.caretakerLeaseSeconds, "caretaker lease")
        guard (0 ... Handles.maxAheadSeconds).contains(lb.activationMarginSeconds) else {
            throw PrivacyError("the node's activation margin \(lb.activationMarginSeconds) is out of range")
        }
        guard lb.handleClaimBound == lb.blockTime - lb.handleLeaseSeconds - lb.activationMarginSeconds,
              lb.caretakerCastBound == lb.blockTime - lb.caretakerLeaseSeconds - lb.activationMarginSeconds
        else { throw PrivacyError("the node's lease bounds do not add up") }
        return lb
    }

    /// The max_predecessor a new caretaker split or handle claim names: the
    /// chain needs it strictly below block time - `lease` - the activation
    /// margin, so an identity that replaced another waits until anything its
    /// predecessor could hold there has lapsed. `lease` is the length
    /// LeaseBounds reports (the longest handle lease ever in force; a held
    /// longer caretaker lease after a cut). Rounded down to the hour so it
    /// says nothing about when the tx was made, less a margin for clock skew.
    /// Every wallet names the same bound (a fresh registrant's predecessor_at
    /// 0 meets it), so the proof does not tell a fresh identity from an old one.
    private func predecessorBound(_ lb: PrivacyReads.LeaseBounds, lease: Int64) -> UInt64 {
        let bound = Handles.satSub(Handles.satSub(lb.blockTime, lease), Handles.satAdd(lb.activationMarginSeconds, Self.clockMargin))
        return UInt64(max(0, bound / 3600 * 3600))
    }

    /// The max_predecessor for a msg bounded unless the prover holds something
    /// live in its scope, and the wait it implies: the lease bound when this
    /// identity meets it (the chain takes it either way); else no bound when
    /// the wallet believes it holds a live one (`held` true) or cannot tell
    /// (nil), and the chain checks that in its ante, before any fee: a
    /// renewal or refresh of a live one goes through, anything else is
    /// refused at no cost (`boundAttempt` says why, with the wait). Held but
    /// known lapsed (`held` false; a handle in its renewal
    /// period, a split past its expiry) needs the bound like a claim, so
    /// NotYet(`lapsed`) is thrown before anything is sent.
    private func leaseStatement(bound: UInt64, held: Bool?, lapsed: NotYet.Lapsed) throws -> (UInt64, Int64?) {
        let id = try identity()
        if id.predecessorAt <= bound { return (bound, nil) }
        let wait = Int64(clamping: id.predecessorAt - bound)
        if held == false { throw NotYet(waitSeconds: wait, lapsed: lapsed) }
        return (PrivacyHash.noBound, wait)
    }

    /// Runs `body`; a chain refusal of its unmet predecessor bound becomes NotYet(notHeld) when `wait` is set.
    private func boundAttempt(_ wait: Int64?, _ body: () async throws -> TxResult) async throws -> TxResult {
        guard let wait else { return try await body() }
        do {
            return try await body()
        } catch {
            if "\(error) \(error.localizedDescription)".contains("max_predecessor") { throw NotYet(waitSeconds: wait, notHeld: true) }
            throw error
        }
    }

    /// A value-0 state record note (PRIVACY_FORMATS.md 6) to `to`'s own address, tagged with its nk.
    private func stateRecord(_ to: PrivacyKeys, _ memo: (Fr) -> Data) throws -> NoteOut {
        try NoteOut.to(to.address, denom: Self.fee, value: 0, memo: memo(to.nk))
    }

    private static func leaseParam(_ v: Int64, _ name: String) throws -> Int64 {
        try require((1 ... Handles.maxAheadSeconds).contains(v), "the node's \(name) (\(v) s) is out of range")
        return v
    }

    private static func weights(_ split: [UInt64: UInt64]) -> [Msg.AllocationWeight] {
        split.sorted { $0.key < $1.key }.map { Msg.AllocationWeight(optionID: $0.key, percent: $0.value) }
    }

    /// When the split lapses: the chain's expires_at, or its cast time + R. 0 for none.
    public func caretakerExpiresAt() async -> Int64 {
        let snap = snapshot
        if snap.caretakerSplit.isEmpty && !snap.caretakerSplitUnknown { return 0 }
        if snap.caretakerExpiresAt > 0 { return snap.caretakerExpiresAt }
        guard let r = try? await reads.personhoodParams().caretakerVoteSeconds else { return 0 }
        let (v, o) = snap.caretakerCastAt.addingReportingOverflow(r)
        return o ? 0 : v
    }

    /// Whether the split this wallet holds is live at chain time `t`: true, or
    /// false when the chain's own expiry has passed (a lapsed split the sweep
    /// has not reached is not held: refreshing it is a new split, bounded),
    /// nil when it holds none or knows only an estimate.
    private func caretakerHeldLive(at t: Int64) async -> Bool? {
        let snap = snapshot
        if snap.caretakerSplit.isEmpty && !snap.caretakerSplitUnknown { return nil }
        let exp = await caretakerExpiresAt()
        if exp > t { return true }
        return snap.caretakerExpiresAt > 0 ? false : nil
    }

    /// Whether this wallet holds a caretaker split the chain still counts (as far as it knows).
    public func caretakerLive() async -> Bool {
        if snapshot.caretakerSplit.isEmpty && !snapshot.caretakerSplitUnknown { return false }
        return await caretakerExpiresAt() > now()
    }

    /// Casts, refreshes or (empty) clears the caretaker split, option id ->
    /// percent. Nothing refreshes it on its own: it lapses at expires_at
    /// unless its owner casts again (the app reminds them).
    public func setCaretaker(split: [UInt64: UInt64]) async throws -> TxResult {
        let mx = await maxActions()
        // Validated before anything is sent: nothing after the broadcast can trap on it.
        // r0: the lease a cast gets now (Params), for the record's expiry estimate. The
        // bound: LeaseBounds' caretaker lease, which a held longer one keeps after a cut.
        let r0 = try Self.leaseParam(try await reads.personhoodParams().caretakerVoteSeconds, "caretaker lease")
        let lb: PrivacyReads.LeaseBounds? = split.isEmpty ? nil : try await leaseBounds()
        let held = await caretakerHeldLive(at: lb?.blockTime ?? 0)
        return try await locked {
            try require(!store.state.caretakerMovedOut || split.isEmpty, "this identity moved its caretaker vote to another; it cannot cast one again")
            try checkNoMove(PendingMove.caretakerKind)
            var (maxPred, wait): (UInt64, Int64?) = (PrivacyHash.noBound, nil)
            if let lb { (maxPred, wait) = try leaseStatement(bound: predecessorBound(lb, lease: lb.caretakerLeaseSeconds), held: held, lapsed: .caretaker) }
            let m = try membership(scope: PrivacyHash.caretakerScope(), excludedDsc: .zero, excludedCountry: .zero,
                                   maxActivation: PrivacyHash.noBound, maxPredecessor: maxPred)
            let w = Self.weights(split)
            // The state record: what a wallet restored from the mnemonic finds. Its
            // expiry is the wallet's estimate; the chain's own (from the result) replaces it here.
            let estimate = Handles.satAdd(now(), r0)
            let record = try stateRecord(keys) { nk in
                split.isEmpty ? WalletSync.caretakerMemo(nk: nk, kind: WalletSync.recordNone)
                    : WalletSync.caretakerMemo(nk: nk, kind: WalletSync.recordHolds, expiresAt: estimate, split: split)
            }
            let r = try await boundAttempt(wait) {
                try await run { fee in
                    Assembled(bundles: [try self.bundle([record], release: [Self.fee: fee], maxActions: mx)], membership: m) { bs, _, mem in
                        MsgSetCaretaker(fee: bs[0], membership: mem!, percentages: w, maxPredecessor: maxPred)
                    }
                }
            }
            // The node's expires_at only within the lease range; else the block time + R, saturating.
            let limit = Handles.satAdd(now(), Handles.maxAheadSeconds)
            let exp = r.attr("set_caretaker", "expires_at").flatMap(Int64.init).flatMap { $0 > 0 && $0 <= limit ? $0 : nil }
            let at = r.time > 0 ? r.time : now()
            store.mutate {
                $0.caretakerCastAt = now(); $0.caretakerSplit = split; $0.caretakerSplitUnknown = false
                $0.caretakerExpiresAt = split.isEmpty ? 0 : (exp ?? Handles.satAdd(at, r0))
            }
            try store.save()
            return r
        }
    }

    /// The identity that succeeded this one under the same passport, on this
    /// phone: another wallet's keys (derived from its recovery phrase) and its
    /// registration as its own store records it. A move proves knowledge of
    /// both identity secrets, so a wallet whose phrase is lost can move nothing.
    public struct Successor: Sendable {
        public let keys: PrivacyKeys
        public let identity: IdentityRecord
        public init(keys: PrivacyKeys, identity: IdentityRecord) { self.keys = keys; self.identity = identity }
    }

    /// A move cannot be made to that identity (nothing was sent).
    public struct MoveNotPossible: Swift.Error, LocalizedError {
        public let message: String
        public var errorDescription: String? { message }
    }

    /// The local trees are behind what a move needs: sync, then try again.
    public struct MoveSyncFirst: Swift.Error, LocalizedError {
        public var errorDescription: String? { "This wallet has not synced the new identity's registration yet: sync, then try again." }
    }

    /// Where the succession leaf H(TAG_SUCC, `idcOld`, `idcNew`) sits in the
    /// local identity tree, or nil. The chain appends it right after the new
    /// identity's leaf (`near`), in the same tx; the rest of the tree is
    /// searched only if it is not there. Local: nothing asked names it.
    public func successionIndex(idcOld: Fr, idcNew: Fr, near: UInt64) async -> UInt64? {
        await locked { successionIndexLocked(idcOld: idcOld, idcNew: idcNew, near: near) }
    }

    private func successionIndexLocked(idcOld: Fr, idcNew: Fr, near: UInt64) -> UInt64? {
        let want = PrivacyHash.successionLeaf(idcOld: idcOld, idcNew: idcNew)
        let tree = store.identityTree
        if near < tree.size, tree.leaf(near) == want { return near }
        var i = tree.size
        while i > 0 { i -= 1; if tree.leaf(i) == want { return i } }
        return nil
    }

    /// The move proof's statement in `scope` from this identity to `to`: the
    /// succession leaf (this identity, `to`) and `to`'s live leaf, both under
    /// the local tree's root (verified at the last sync, so one the chain
    /// recorded; a move goes out within the root window of it).
    private func moveStatement(scope: Fr, to: Successor) throws -> MoveWitnessSpec {
        let id = to.identity
        let newIdc = to.keys.idc
        try require(newIdc != keys.idc, "a move goes to another identity")
        let leaf = PrivacyHash.identityLeaf(idc: newIdc, dscKey: id.dscKey, country: id.country, activatedAt: id.activatedAt, predecessorAt: id.predecessorAt)
        let tree = store.identityTree
        guard id.leafIndex < tree.size else { throw MoveSyncFirst() }
        let at = tree.leaf(id.leafIndex)
        if at.isZero {
            throw MoveNotPossible(message: "the new identity is no longer the passport's live one (it switched again or lapsed): a move goes only to the live successor")
        }
        guard at == leaf else { throw MoveNotPossible(message: "the new wallet's registration record does not match the identity tree; sync it, then try again") }
        guard let si = successionIndexLocked(idcOld: keys.idc, idcNew: newIdc, near: id.leafIndex &+ 1) else {
            throw MoveNotPossible(message: "that identity did not directly succeed this one under this passport: a move goes only to the identity the passport registered next")
        }
        let root = tree.root()
        let sp = tree.path(si)
        let lp = tree.path(id.leafIndex)
        let oldSecret = keys.idSecret
        let newSecret = to.keys.idSecret
        return try MoveWitnessSpec { signal in
            try MoveWitness(oldSecret: oldSecret, newSecret: newSecret, successionIndex: si, successionSiblings: sp, dscKey: id.dscKey,
                            country: id.country, activatedAt: id.activatedAt, predecessorAt: id.predecessorAt, leafIndex: id.leafIndex,
                            siblings: lp, root: root, scope: scope, signal: signal)
        }
    }

    /// Hands the live caretaker split (and its expiry) to `to`, the identity
    /// that succeeded this one under the same passport, once its registration
    /// has landed and while it is still the passport's live one: how a switch
    /// keeps its vote. This wallet pays the fee. This identity may never cast
    /// one again (ErrCaretakerMovedOut, 1126).
    public func moveCaretaker(to: Successor, recorder: MoveRecorder? = nil) async throws -> TxResult {
        let mx = await maxActions()
        let live = await caretakerLive()
        let exp = await caretakerExpiresAt()
        return try await locked {
            try require(live, "this identity holds no live caretaker vote to move")
            try checkNoMove(PendingMove.caretakerKind)
            let st = store.state
            let move = PendingMove(kind: PendingMove.caretakerKind, txHash: "", timeoutHeight: 0, incoming: false, split: st.caretakerSplit,
                                   splitUnknown: st.caretakerSplitUnknown, expiresAt: exp, target: recorder?.targetID ?? "")
            // State records: moved out for this identity, held (split, expiry) for the new one.
            let outs = [
                try stateRecord(keys) { WalletSync.caretakerMemo(nk: $0, kind: WalletSync.recordMovedOut) },
                try stateRecord(to.keys) {
                    WalletSync.caretakerMemo(nk: $0, kind: WalletSync.recordHolds, expiresAt: move.expiresAt, split: move.splitUnknown ? [:] : move.split)
                },
            ]
            let mv = try moveStatement(scope: PrivacyHash.caretakerScope(), to: to)
            let placeholder = MoveProof(proof: Data(), root: Data(), oldNullifier: Data(), newNullifier: Data())
            let r = try await moveRun(move, recorder) { fee in
                Assembled(bundles: [try self.bundle(outs, release: [Self.fee: fee], maxActions: mx)], move: mv) { bs, _, _ in
                    MsgMoveCaretaker(fee: bs[0], move: placeholder)
                }
            }
            confirmMove(r.hash)
            try store.save()
            return r
        }
    }

    /// Writes a move into the new identity's wallet: before the
    /// broadcast, as pending, so neither a lost answer nor a killed app can
    /// strand what moved; undone only on a definite refusal. `targetID` is
    /// that wallet's store id.
    public protocol MoveRecorder: Sendable {
        var targetID: String { get }
        func record(_ move: PendingMove) throws
        func rollback(_ move: PendingMove) throws
        /// Why the target cannot take `move` (`targetRefusal`), or nil.
        func refusal(_ move: PendingMove) -> String?
    }

    /// Why a target wallet cannot take a move of `kind`:
    /// its identity moved one away already (the chain refuses that owner),
    /// or it holds one of its own (a handle; a live caretaker split) that the
    /// move would overwrite here. Nil: it can.
    public static func targetRefusal(_ s: PrivacyState, kind: String, now: Int64) -> String? {
        if kind == PendingMove.handleKind {
            if s.handleMovedOut { return "that wallet's identity already moved a handle away; it can never hold one again" }
            if !s.handle.isEmpty { return "that wallet already holds @\(s.handle)" }
            return nil
        }
        if s.caretakerMovedOut { return "that wallet's identity already moved a caretaker vote away; it can never hold one again" }
        if s.caretakerExpiresAt > now, !s.caretakerSplit.isEmpty || s.caretakerSplitUnknown { return "that wallet already holds a caretaker vote" }
        return nil
    }

    /// The wallet this identity's moves must go to: the one a
    /// confirmed move went to, else the one a move still in flight names
    /// (empty: any).
    private func switchTargetNow() -> String {
        let s = store.state
        if !s.switchTarget.isEmpty { return s.switchTarget }
        return s.pendingMoves.first { !$0.incoming && !$0.confirmed && !$0.target.isEmpty }?.target ?? ""
    }

    /// Frees the switch target when nothing moved: no handle or
    /// split moved out and no move of this identity confirmed or in flight.
    /// Returns whether it changed.
    @discardableResult
    static func clearSwitchTargetIfUnmoved(_ s: inout PrivacyState) -> Bool {
        if s.switchTarget.isEmpty || s.handleMovedOut || s.caretakerMovedOut || s.pendingMoves.contains(where: { !$0.incoming }) { return false }
        s.switchTarget = ""
        return true
    }

    private func checkNoMove(_ kind: String) throws {
        try require(!store.state.pendingMoves.contains { !$0.incoming && $0.kind == kind && !$0.confirmed },
                    "a move of this identity's \(kind) is waiting for the chain")
    }

    /// Runs a move: recorded here (outgoing) and in the target (incoming)
    /// before the broadcast; a refusal undoes both; a confirmed tx is applied
    /// by the caller (`confirmMove`); anything else (a wait that timed out, a
    /// tx that may yet land or fail) stays pending for `resolvePendingMoves`.
    private func moveRun(_ move: PendingMove, _ recorder: MoveRecorder?, _ assemble: (UInt64) throws -> Assembled) async throws -> TxResult {
        if let rc = recorder {
            // The target a confirmed move went to, or one a move
            // still in flight names; a refused, failed or expired move frees it.
            let fixed = switchTargetNow()
            try require(fixed.isEmpty || fixed == rc.targetID, "this identity already moved to another wallet; switch to that one")
            if let why = rc.refusal(move) { throw PrivacyError(why) }
        }
        return try await run(accepted: { [self] hash, timeout in
            var p = move
            p.txHash = hash; p.timeoutHeight = timeout
            var ok = true
            if let rc = recorder {
                var inc = p
                inc.incoming = true; inc.target = ""; inc.recorded = true
                ok = (try? rc.record(inc)) != nil
            }
            p.recorded = ok
            let pm = p
            store.mutate { s in s.pendingMoves.append(pm) }
            persistNoThrow()
        }, rejected: { [self] hash in
            store.mutate { s in
                s.pendingMoves.removeAll { $0.txHash == hash && !$0.incoming }
                Self.clearSwitchTargetIfUnmoved(&s)
            }
            persistNoThrow()
            if let rc = recorder {
                var inc = move
                inc.txHash = hash; inc.incoming = true
                try? rc.rollback(inc)
            }
        }, assemble)
    }

    /// The move `hash` is in a block and succeeded: this identity no longer holds what it moved.
    private func confirmMove(_ hash: String) {
        let t = now()
        store.mutate { s in
            guard let i = s.pendingMoves.firstIndex(where: { $0.txHash == hash }) else { return }
            let p = s.pendingMoves[i]
            if !p.incoming {
                if p.kind == PendingMove.handleKind { s.handle = ""; s.handleMovedOut = true; s.handleSetAt = t }
                else { s.caretakerSplit = [:]; s.caretakerSplitUnknown = false; s.caretakerExpiresAt = 0; s.caretakerMovedOut = true }
                // The target is fixed only by a confirmed move.
                if s.switchTarget.isEmpty, !p.target.isEmpty { s.switchTarget = p.target }
            }
            if p.incoming || p.recorded { s.pendingMoves.remove(at: i) } else { s.pendingMoves[i].confirmed = true }
        }
    }

    /// The move `p` is definitely not in the chain (refused, failed in its block, or gone past its timeout_height).
    private func dropMove(_ p: PendingMove) {
        let t = now()
        store.mutate { s in
            s.pendingMoves.removeAll { $0.txHash == p.txHash && $0.incoming == p.incoming }
            if p.incoming { Self.undoIncoming(&s, p, now: t) } else { Self.clearSwitchTargetIfUnmoved(&s) }
        }
    }

    /// Settles every move in flight by its tx: committed, it is
    /// applied; failed in its block, or unknown to the chain past its
    /// timeout_height, it is undone (and its state records void). A move the
    /// chain cannot say anything about yet stays. Returns whether any is
    /// still unconfirmed.
    @discardableResult
    public func resolvePendingMoves() async -> Bool {
        await locked { await resolvePendingMovesLocked() }
    }

    private func resolvePendingMovesLocked() async -> Bool {
        // A target fixed by a move that never landed (a store from an earlier version) is freed.
        store.mutate { Self.clearSwitchTargetIfUnmoved(&$0) }
        for p in store.state.pendingMoves where !p.confirmed {
            let r = try? await chain.tx(p.txHash)
            if let r, r.code == 0 { confirmMove(p.txHash) }
            else if let r { store.mutate { _ = $0.voidRecordHeights.insert(r.height) }; dropMove(p) }
            // A timeout no sane tip gives is settled by the tx's status alone.
            else if !PrivateTxEngine.timeoutSane(p.timeoutHeight, verifiedNow: store.state.verifiedHeight) {
                if await roots.txStatus(p.txHash) == .missing { dropMove(p) }
            }
            else if let tip = try? await chain.tipHeight(), tip > p.timeoutHeight { dropMove(p) }
        }
        persistNoThrow()
        return store.state.pendingMoves.contains { !$0.confirmed }
    }

    /// Moves away from this identity that the chain has not confirmed yet, and confirmed ones not yet recorded in their target.
    public func outgoingMoves() -> [PendingMove] { snapshot.pendingMoves.filter { !$0.incoming } }

    /// Marks a confirmed move recorded in its target (a retried `MoveRecorder.record` succeeded).
    public func markRecorded(_ hash: String) async {
        await lockedNoThrow {
            store.mutate { s in
                guard let i = s.pendingMoves.firstIndex(where: { $0.txHash == hash && !$0.incoming }) else { return }
                if s.pendingMoves[i].confirmed { s.pendingMoves.remove(at: i) } else { s.pendingMoves[i].recorded = true }
            }
            persistNoThrow()
        }
    }

    /// Writes `p` (a move to this store's identity) into `store`: what it now
    /// holds, and the move as pending until its own wallet settles it by hash
    /// Used by the mover for the other wallet's store.
    public static func recordIncoming(_ store: PrivacyStore, _ p: PendingMove, now: Int64) throws {
        store.mutate { s in
            if p.kind == PendingMove.handleKind { s.handle = p.handle; s.handleSetAt = now }
            else {
                s.caretakerSplit = p.split; s.caretakerSplitUnknown = p.splitUnknown || p.split.isEmpty
                s.caretakerExpiresAt = min(max(p.expiresAt, 0), Handles.satAdd(now, Handles.maxAheadSeconds)); s.caretakerCastAt = now
            }
            if !p.txHash.isEmpty, !s.pendingMoves.contains(where: { $0.txHash == p.txHash && $0.incoming }) {
                var inc = p
                inc.incoming = true; inc.target = ""; inc.recorded = true; inc.confirmed = false
                s.pendingMoves.append(inc)
            }
        }
        try store.save()
    }

    /// Undoes `recordIncoming` for a move that definitely did not happen.
    public static func rollbackIncoming(_ store: PrivacyStore, _ p: PendingMove, now: Int64) throws {
        store.mutate { s in
            s.pendingMoves.removeAll { $0.txHash == p.txHash && $0.incoming }
            undoIncoming(&s, p, now: now)
        }
        try store.save()
    }

    static func undoIncoming(_ s: inout PrivacyState, _ p: PendingMove, now: Int64) {
        if p.kind == PendingMove.handleKind {
            if s.handle == p.handle { s.handle = ""; s.handleSetAt = now }
        } else if s.caretakerSplit == p.split && s.caretakerSplitUnknown == (p.splitUnknown || p.split.isEmpty) {
            s.caretakerSplit = [:]; s.caretakerSplitUnknown = false; s.caretakerExpiresAt = 0
        }
    }

    // MARK: - handles

    /// Claims `handle` for `address` (default: this wallet's shielded
    /// address), renews the one held (the same handle: lease now +
    /// handle_lease_seconds, address updated), or changes to another (the old
    /// one is freed at once). A claim by an identity holding none bounds its
    /// predecessor by the longest lease; a renewal or change does not.
    /// Nothing renews on its own: the app reminds the owner before expiry.
    public func bindHandle(_ handle: String, address: ShieldedAddress? = nil, renewOnly: Bool = false) async throws -> TxResult {
        let mx = await maxActions()
        // The lease this bind gets (Params), validated before anything is sent.
        let leaseNow = try Self.leaseParam(try await reads.personhoodParams().handleLeaseSeconds, "handle lease")
        // Only a live handle renews or changes unbounded; one in its
        // renewal period is bounded like a claim, by the longest lease ever in force.
        let lb = try await leaseBounds()
        return try await locked {
            try require(Handles.valid(handle), "\"\(handle)\" is not a handle: 3-32 of a-z, 0-9 and -, no dash at either end")
            // A renewal binds only the handle held (or, holding none, one this
            // identity may hold): a bind of another would be a change, freeing the held one.
            if renewOnly {
                let held = store.state.handle
                try require(held.isEmpty || held == handle, "this identity holds @\(held); renewing @\(handle) would change to it and free @\(held)")
            }
            let holds = !store.state.handle.isEmpty
            try require(holds || !store.state.handleMovedOut, "this identity moved its handle to another; it cannot claim one again")
            try checkNoMove(PendingMove.handleKind)
            let exp = handleExpiresAtLocked()
            let held: Bool? = !holds || exp == 0 ? nil : exp > lb.blockTime
            let (maxPred, wait) = try leaseStatement(bound: predecessorBound(lb, lease: lb.handleLeaseSeconds), held: held,
                                                     lapsed: .handle(store.state.handle))
            let addr = (address ?? keys.address).encode()
            let m = try membership(scope: PrivacyHash.handleScope(), excludedDsc: .zero, excludedCountry: .zero,
                                   maxActivation: PrivacyHash.noBound, maxPredecessor: maxPred)
            let record = try stateRecord(keys) { WalletSync.handleMemo(nk: $0, kind: WalletSync.recordHolds, handle: handle) }
            let r = try await boundAttempt(wait) {
                try await run { fee in
                    Assembled(bundles: [try self.bundle([record], release: [Self.fee: fee], maxActions: mx)], membership: m) { bs, _, mem in
                        MsgBindHandle(fee: bs[0], membership: mem!, handle: handle, address: addr, maxPredecessor: maxPred)
                    }
                }
            }
            // The chain's expires_at (handle_bound), within the lease range; else the block time + the lease.
            let limit = Handles.satAdd(now(), Handles.maxAheadSeconds)
            let bound = r.attr("handle_bound", "expires_at").flatMap(Int64.init).flatMap { $0 > 0 && $0 <= limit ? $0 : nil }
            let at = r.time > 0 ? r.time : now()
            store.mutate {
                $0.handle = handle; $0.handleSetAt = now()
                $0.handleExpiresFor = handle; $0.handleExpiresAt = bound ?? Handles.satAdd(at, leaseNow)
            }
            try store.save()
            return r
        }
    }

    /// When this identity's handle stops being live, as the chain last said
    /// (its bind, or its directory); 0 when the wallet does not know.
    public func handleExpiresAt() async -> Int64 { await locked { handleExpiresAtLocked() } }

    private func handleExpiresAtLocked() -> Int64 {
        let s = store.state
        return !s.handle.isEmpty && s.handleExpiresFor == s.handle ? s.handleExpiresAt : 0
    }

    /// MsgMoveHandle moves only a live handle. Nothing was sent.
    public struct HandleNotMovable: Swift.Error, LocalizedError {
        public let handle: String
        public var errorDescription: String? {
            "@\(handle) is past its expiry (in its renewal period), and only a live handle can be moved: renew it first, then move it."
        }
    }

    /// Releases this identity's handle at once (anyone may claim it).
    public func releaseHandle() async throws -> TxResult {
        let mx = await maxActions()
        return try await locked {
            // The chain refuses a release by a holder of none only after taking the fee.
            try require(!store.state.handle.isEmpty, "this identity holds no handle to release")
            try checkNoMove(PendingMove.handleKind)
            let m = try membership(scope: PrivacyHash.handleScope(), excludedDsc: .zero, excludedCountry: .zero,
                                   maxActivation: PrivacyHash.noBound, maxPredecessor: PrivacyHash.noBound)
            let record = try stateRecord(keys) { WalletSync.handleMemo(nk: $0, kind: WalletSync.recordNone) }
            let r = try await run { fee in
                Assembled(bundles: [try self.bundle([record], release: [Self.fee: fee], maxActions: mx)], membership: m) { bs, _, mem in
                    MsgBindHandle(fee: bs[0], membership: mem!, handle: "", address: "", maxPredecessor: PrivacyHash.noBound)
                }
            }
            store.mutate { $0.handle = ""; $0.handleSetAt = now() }
            try store.save()
            return r
        }
    }

    /// Hands this identity's handle (lease unchanged) to `to`, the identity
    /// that succeeded it under the same passport (see `moveCaretaker`). This
    /// identity may never claim one again (ErrHandleMovedOut, 1125).
    public func moveHandle(to: Successor, recorder: MoveRecorder? = nil) async throws -> TxResult {
        let mx = await maxActions()
        let t = await chainNow()
        return try await locked {
            let handle = store.state.handle
            try require(!handle.isEmpty, "this identity holds no handle to move")
            try checkNoMove(PendingMove.handleKind)
            // MsgMoveHandle refuses a handle that is not live (its renewal period).
            let exp = handleExpiresAtLocked()
            if exp > 0, exp <= t { throw HandleNotMovable(handle: handle) }
            let move = PendingMove(kind: PendingMove.handleKind, txHash: "", timeoutHeight: 0, incoming: false, handle: handle,
                                   target: recorder?.targetID ?? "")
            // State records: moved out for this identity, held for the new one.
            let outs = [
                try stateRecord(keys) { WalletSync.handleMemo(nk: $0, kind: WalletSync.recordMovedOut) },
                try stateRecord(to.keys) { WalletSync.handleMemo(nk: $0, kind: WalletSync.recordHolds, handle: handle) },
            ]
            let mv = try moveStatement(scope: PrivacyHash.handleScope(), to: to)
            let placeholder = MoveProof(proof: Data(), root: Data(), oldNullifier: Data(), newNullifier: Data())
            let r = try await moveRun(move, recorder) { fee in
                Assembled(bundles: [try self.bundle(outs, release: [Self.fee: fee], maxActions: mx)], move: mv) { bs, _, _ in
                    MsgMoveHandle(fee: bs[0], move: placeholder, handle: handle)
                }
            }
            confirmMove(r.hash)
            try store.save()
            return r
        }
    }

    /// Records what a switch moved to this wallet's identity (the other
    /// wallet's moves named its nullifiers): the handle, and the split with
    /// its expiry. Its renewal or refresh then takes no predecessor bound.
    public func adoptMoved(handle: String?, split: [UInt64: UInt64]?, splitExpiresAt: Int64) async throws {
        try await locked {
            if let handle {
                try Self.recordIncoming(store, PendingMove(kind: PendingMove.handleKind, txHash: "", timeoutHeight: 0, incoming: true, handle: handle), now: now())
            }
            if let split, !split.isEmpty {
                try Self.recordIncoming(store, PendingMove(kind: PendingMove.caretakerKind, txHash: "", timeoutHeight: 0, incoming: true,
                                                           split: split, expiresAt: splitExpiresAt), now: now())
            }
        }
    }

    /// Squares the store's handle with the
    /// chain's directory `dir`, read at `readAt` (wallet clock): a handle the
    /// chain swept (absent or free), or one whose entry names another owner,
    /// is dropped; with none held, the one entry whose owner is this
    /// identity's handle-scope nullifier is taken as held (a restore lost
    /// it). An entry merely naming this wallet's address is never adopted:
    /// anyone may bind a handle to any address. Nothing changes while a move
    /// is in flight or when the directory predates the store's last change.
    /// Returns, while no handle is held, the non-free entries naming this
    /// wallet's address whose owner is not someone else (unverified: a
    /// directory without owners), for the cards and reminders.
    public func reconcileHandle(_ dir: [String: HandleEntry], readAt: Int64) async -> [HandleEntry] {
        await locked {
            let t = now()
            let own = keys.address.encode()
            let mine = handleOwner
            let addressed = dir.values.filter { $0.address == own && $0.status(at: t) != HandleEntry.free && ($0.owner.isEmpty || $0.owner == mine) }
                .sorted { $0.handle < $1.handle }
            let owned = dir.values.filter { $0.owner == mine && $0.status(at: t) != HandleEntry.free }
            let s = store.state
            let moving = s.pendingMoves.contains { $0.kind == PendingMove.handleKind && !$0.confirmed }
            if !moving, readAt > s.handleSetAt {
                if !s.handle.isEmpty {
                    if let e = dir[s.handle], e.status(at: t) != HandleEntry.free, e.owner.isEmpty || e.owner == mine {} else {
                        store.mutate { $0.handle = ""; $0.handleSetAt = t }; persistNoThrow()
                    }
                }
                if store.state.handle.isEmpty, !store.state.handleMovedOut, owned.count == 1 {
                    let h = owned[0].handle
                    store.mutate { $0.handle = h; $0.handleSetAt = t }; persistNoThrow()
                }
                // The chain's expiry of the handle held: whether it is live.
                let cur = store.state
                if !cur.handle.isEmpty, let e = dir[cur.handle], e.status(at: t) != HandleEntry.free,
                   cur.handleExpiresFor != cur.handle || cur.handleExpiresAt != e.expiresAt {
                    store.mutate { $0.handleExpiresFor = cur.handle; $0.handleExpiresAt = e.expiresAt }; persistNoThrow()
                }
            }
            // While a handle is held, no other entry is offered (a bind of it would change, freeing the held one).
            return store.state.handle.isEmpty ? addressed : []
        }
    }

    /// This identity's handle-scope nullifier as a directory entry's owner (64 lowercase hex).
    public var handleOwner: String { PrivacyHash.scopeNullifier(idSecret: keys.idSecret, scope: PrivacyHash.handleScope()).hex }

    // MARK: - assembly

    /// A human vote on an x/gov proposal. The scope is recomputed here rather than taken from the node.
    public func voteProposal(proposalID: UInt64, yes: Bool) async throws -> TxResult {
        let mx = await maxActions()
        return try await locked {
            let b = try await reads.ballotInputs(proposalID: proposalID, optionID: 0)
            guard b.scope == PrivacyHash.proposalScope(proposalID: proposalID, round: b.round) else {
                throw PrivacyError("the node's ballot scope is not this proposal's")
            }
            // The chain's statement: max_activation no bound, max_predecessor the ballot's (opened - 86400).
            let m = try membership(scope: b.scope, excludedDsc: b.excludedDsc, excludedCountry: b.excludedCountry,
                                   maxActivation: b.maxActivation, maxPredecessor: b.maxPredecessor)
            return try await run { fee in
                Assembled(bundles: [try self.feeBundle(fee, maxActions: mx)], membership: m) { bs, _, mem in
                    MsgVoteProposalPrivate(fee: bs[0], membership: mem!, proposalID: proposalID, option: yes ? .yes : .no)
                }
            }
        }
    }

    public func proposeRemoval(optionID: UInt64) async throws -> TxResult {
        let mx = await maxActions()
        let t = await chainNow()
        return try await locked {
            // The chain's day: its scope is the including block's UTC day.
            let day = UInt64(max(0, t) / Self.secondsPerDay)
            // The predecessor bound: the start of today (UTC) less a day, whatever the root window; no activation bound.
            let maxPred = UInt64(max(0, Int64(day) * Self.secondsPerDay - Self.activationMargin))
            let m = try membership(scope: PrivacyHash.proposeRemovalScope(optionID: optionID, day: day), excludedDsc: .zero,
                                   excludedCountry: .zero, maxActivation: PrivacyHash.noBound, maxPredecessor: maxPred)
            return try await run { fee in
                Assembled(bundles: [try self.feeBundle(fee, maxActions: mx)], membership: m) { bs, _, mem in
                    MsgProposeRemoval(fee: bs[0], membership: mem!, optionID: optionID)
                }
            }
        }
    }

    public func voteRemoval(optionID: UInt64, yes: Bool) async throws -> TxResult {
        let mx = await maxActions()
        return try await locked {
            let b = try await reads.ballotInputs(proposalID: 0, optionID: optionID)
            guard b.scope == PrivacyHash.removalScope(ballotID: b.ballotID) else {
                throw PrivacyError("the node's ballot scope is not this ballot's")
            }
            let m = try membership(scope: b.scope, excludedDsc: b.excludedDsc, excludedCountry: b.excludedCountry,
                                   maxActivation: b.maxActivation, maxPredecessor: b.maxPredecessor)
            return try await run { fee in
                Assembled(bundles: [try self.feeBundle(fee, maxActions: mx)], membership: m) { bs, _, mem in
                    MsgVoteRemoval(fee: bs[0], membership: mem!, optionID: optionID, option: yes ? .yes : .no)
                }
            }
        }
    }

    // MARK: - private staking (owner-locked stake notes)

    public static func derthDenom(_ valoper: String) -> String { "\(derthPrefix)\(valoper)" }
    public static func lpDenom(_ poolID: UInt64) -> String { "\(lpPrefix)\(poolID)" }

    public static func parseDerth(_ denom: String) throws -> String {
        guard denom.hasPrefix(derthPrefix) else { throw PrivacyError("not a derth note") }
        return String(denom.dropFirst(derthPrefix.count))
    }

    /// The stake tree's latest root: every stake proof's anchor, the empty
    /// tree's root before the first note (the chain records it at its first
    /// block, and a first delegation pads its input against it).
    private func stakeAnchor() -> Fr { store.stakeTree.root() }

    /// Stake notes of `denom` this wallet can spend now.
    private func spendableStake(_ denom: String) -> [OwnedStakeNote] {
        store.state.stakeNotes.filter { $0.spendable && $0.denom == denom }
    }

    /// The chain's slash debt now (Query/DebtTree, ORCHARD_DESIGN 8.7): its
    /// root and size, the label window and the clear_before a proof may name,
    /// and the rows (`tree`) when a label is cleared or voted, read whole
    /// (the indexer's stream, else the chain's pages) and checked against
    /// `root`: nothing asked names a move of this wallet.
    public struct DebtView: Sendable {
        public let root: Fr
        public let size: UInt64
        public let windowSeconds: UInt64
        public let clearBefore: UInt64
        let tree: DebtTree?

        /// Whether `l`'s window has closed: a proof naming `clearBefore` clears it.
        public func clearable(_ l: StakeLabel) -> Bool { l.moveTime < clearBefore }

        /// What `l`'s exposure is worth now: its row's retained, or all of it when the move was never slashed.
        public func retained(_ l: StakeLabel) -> UInt64 { min(tree?.retainedOf(l.moveKey) ?? l.exposed, l.exposed) }
    }

    /// Reads the chain's debt view (one page of Query/DebtTree: its root,
    /// window and clear_before), with the rows when `rows` says a label of
    /// `notes` needs them (a closed window to clear, or `always`).
    private func debtView(_ notes: [OwnedStakeNote] = [], always: Bool = false) async throws -> DebtView {
        let p = try await reads.debtTree(start: 0, limit: 1)
        try require(p.size < Merkle.capacity, "debt tree size \(p.size)")
        try require(p.windowSeconds <= UInt64(PrivacyQueries.maxDurationSeconds), "label window \(p.windowSeconds)")
        if p.windowSeconds > 0, store.state.labelWindowSeconds != p.windowSeconds {
            store.mutate { $0.labelWindowSeconds = p.windowSeconds }
            persistNoThrow()
        }
        let need = notes.contains { n in n.label.map { always || $0.moveTime < p.clearBefore } ?? false }
        let tree = need ? try await debtTreeAt(root: p.root, size: p.size) : nil
        return DebtView(root: p.root, size: p.size, windowSeconds: p.windowSeconds, clearBefore: p.clearBefore, tree: tree)
    }

    /// Debt trees by root, the last two (used under the wallet's lock only).
    private var debtTrees: [(root: Fr, tree: DebtTree)] = []
    /// Debt rows asked of the indexer a page (a size it serves), and of the LCD (its maximum).
    static let debtPage = 1000
    static let lcdDebtPage = 1000

    /// The debt tree whose root is `root` (`size` leaves, sentinel included):
    /// the indexer's whole stream first, the chain's own pages when it does
    /// not rebuild `root` (an indexer behind, or lying).
    private func debtTreeAt(root: Fr, size: UInt64) async throws -> DebtTree {
        if let t = debtTrees.first(where: { $0.root == root }) { return t.tree }
        try require(size <= UInt64(Int.max), "debt tree size \(size)")
        let n = Int(size > 0 ? size - 1 : 0)
        for chainOnly in [false, true] {
            guard let rows = try? await (chainOnly ? lcdDebtRows(n) : indexerDebtRows(n)) else { continue }
            if let t = try? DebtTree(rows), t.root() == root {
                debtTrees.append((root, t))
                if debtTrees.count > 2 { debtTrees.removeFirst() }
                return t
            }
        }
        throw PrivacyError("the slash debt rows served do not rebuild the chain's debt root; sync and try again")
    }

    /// The first `n` debt rows from the indexer's stream (aligned pages from leaf 0; the sentinel is never a row).
    private func indexerDebtRows(_ n: Int) async throws -> [(key: Fr, retained: UInt64)] {
        var out: [(key: Fr, retained: UInt64)] = []
        var from: UInt64 = 0
        while out.count < n {
            let page = try await indexer.debtRows(fromIndex: from, limit: Self.debtPage)
            guard page.rows.count <= Self.debtPage else {
                throw WalletSync.Inconsistent(message: "the indexer sent \(page.rows.count) debt rows in a page of \(Self.debtPage)")
            }
            for r in page.rows {
                guard r.index == UInt64(out.count) + 1 else { throw WalletSync.Inconsistent(message: "debt row \(r.index) out of order") }
                if out.count >= n { break }
                out.append((r.key, r.retained))
            }
            if page.rows.isEmpty { break }
            from += UInt64(Self.debtPage)
        }
        try require(out.count == n, "only \(out.count) of the chain's \(n) debt rows were served")
        return out
    }

    /// The first `n` debt rows from the chain's Query/DebtTree pages.
    private func lcdDebtRows(_ n: Int) async throws -> [(key: Fr, retained: UInt64)] {
        var out: [(key: Fr, retained: UInt64)] = []
        while out.count < n {
            let page = try await reads.debtTree(start: UInt64(out.count), limit: min(n - out.count, Self.lcdDebtPage))
            if page.rows.isEmpty { break }
            out.append(contentsOf: page.rows.prefix(min(Self.lcdDebtPage, n - out.count)))
        }
        try require(out.count == n, "only \(out.count) of the chain's \(n) debt rows were served")
        return out
    }

    /// The label window as last read (0: never): for showing when moved stake may move again.
    public var labelWindowSeconds: UInt64 { store.state.labelWindowSeconds }

    /// When `l`'s exposure may leave its note (unix seconds): after move_time + the label window.
    public static func movableAfter(_ l: StakeLabel, windowSeconds: UInt64) -> UInt64 { PrivateMsgs.saturatingAdd(l.moveTime, windowSeconds) }

    /// What `n` may give up now (ORCHARD_DESIGN 8.7): its amount; for a
    /// labelled note with its window open, the amount less the exposure (the
    /// exposure stays in place); with the window closed, less the exposure
    /// plus what it retains (the proof clears it).
    private static func freeOf(_ n: OwnedStakeNote, _ d: DebtView) -> UInt64 {
        guard let l = n.label else { return n.amount }
        return d.clearable(l) ? n.amount - l.exposed + d.retained(l) : n.amount - l.exposed
    }

    /// Why a move of exposed stake waits: the earliest date one of `notes`' open labels frees its exposure.
    private static func lockedText(_ notes: [OwnedStakeNote], _ d: DebtView) -> String? {
        guard let until = notes.compactMap(\.label).filter({ !d.clearable($0) }).map({ movableAfter($0, windowSeconds: d.windowSeconds) }).min()
        else { return nil }
        return "moved stake can move again after \(dateText(until)): until then a slash of the validator it left can still reach it, so it stays where it is (the rest of this stake moves freely)"
    }

    /// One validator's stake as this wallet holds it.
    public struct StakeHolding: Sendable, Equatable {
        public let validator: String
        /// derth held (every spendable note).
        public let derth: UInt64
        /// derth that may leave now (what undelegating, locking or moving can take).
        public let free: UInt64
        /// Moved-in derth whose window is open, and when the first of it may move again (nil: none).
        public let locked: UInt64
        public let lockedUntil: UInt64?
        /// Notes held here: more than one can be merged (`restake`).
        public let notes: Int
        /// Whether two of them can merge now (at most one labelled).
        public let mergeable: Bool
    }

    /// This wallet's stake per validator. The chain's debt view is read only
    /// when a label is held; without it a closed window counts as still open
    /// (nothing is shown as movable that is not).
    public func stakeHoldings() async -> [StakeHolding] {
        await locked {
            let notes = store.state.stakeNotes.filter { $0.spendable && $0.denom.hasPrefix(Self.derthPrefix) }
            let view = notes.contains { $0.label != nil } ? try? await debtView(notes) : nil
            let window = view?.windowSeconds ?? store.state.labelWindowSeconds
            return Dictionary(grouping: notes, by: \.denom).sorted { $0.key < $1.key }.map { denom, ns in
                let open = ns.compactMap(\.label).filter { l in view.map { !$0.clearable(l) } ?? true }
                return StakeHolding(
                    validator: String(denom.dropFirst(Self.derthPrefix.count)),
                    derth: ns.reduce(UInt64(0)) { Snapshot.satAdd63($0, $1.amount) },
                    free: ns.reduce(UInt64(0)) { acc, n in Snapshot.satAdd63(acc, view.map { Self.freeOf(n, $0) } ?? (n.amount - (n.label?.exposed ?? 0))) },
                    locked: open.reduce(UInt64(0)) { Snapshot.satAdd63($0, $1.exposed) },
                    lockedUntil: open.map { Self.movableAfter($0, windowSeconds: window) }.min(),
                    notes: ns.count,
                    mergeable: ns.count >= 2 && ns.contains { $0.label == nil }
                )
            }
        }
    }

    /// The clear_before and debt root every stake proof names (also when it
    /// clears nothing, so a clearing proof looks like any other), and the
    /// clear of `l` when its window has closed.
    private static func clearOf(_ l: StakeLabel?, _ d: DebtView) throws -> StakePlan.Clear {
        if d.clearBefore == 0 { return .none }
        guard let l, d.clearable(l) else { return StakePlan.Clear(clearBefore: d.clearBefore, debtRoot: d.root) }
        guard let w = d.tree?.witness(l.moveKey), let r = w.retained(key: l.moveKey, exposed: l.exposed, root: d.root) else {
            throw PrivacyError("the debt tree does not read this stake's move")
        }
        return StakePlan.Clear(clearBefore: d.clearBefore, debtRoot: d.root, witness: w, retained: r)
    }

    /// Lane A of `denom`: `spends` (at most one labelled) merged with `vIn`
    /// less `vOut` into one note back to us (the change, or a zero note), the
    /// label kept or cleared (`clearOf`).
    private func laneA(_ denom: String, spends: [OwnedStakeNote], vIn: UInt64, vOut: UInt64, _ d: DebtView,
                       salt: Fr = StakePlan.freshSalt(), credit: StakePlan.Credit? = nil) throws -> StakePlan {
        let l = spends.compactMap(\.label).first
        let clear = try Self.clearOf(l, d)
        var amount = BigUInt(try Self.sum(spends))
        if clear.clears, let l { amount = amount - BigUInt(l.exposed) + BigUInt(clear.retained) }
        amount += BigUInt(vIn)
        try require(amount >= BigUInt(vOut), "insufficient stake")
        amount -= BigUInt(vOut)
        try require(amount <= BigUInt(Int64.max), "a stake note holds at most 2^63-1")
        let out = try StakePlan.out(keys, denom: denom, amount: UInt64(amount), label: clear.clears ? nil : l)
        return try StakePlan(nk: keys.nk, denom: denom, spends: spends, paths: spends.map { store.stakeTree.path($0.position) }, out: out,
                             vIn: vIn, vOut: vOut, clear: clear, credit: credit, tagSalt: salt, anchor: stakeAnchor())
    }

    /// What a lane A clear gives up: the slash's cut of the cleared exposure (0 when nothing is cleared, or nothing was cut).
    private static func haircutOf(_ p: StakePlan) -> UInt64 {
        guard p.clear.clears, let l = p.spends.compactMap(\.label).first else { return 0 }
        return l.exposed - p.clear.retained
    }

    /// The chain refused nothing yet, but what the sheet showed no longer holds: show it again.
    public struct QuoteChanged: Swift.Error, LocalizedError, Equatable {
        public let message: String
        public var errorDescription: String? { message }
    }

    /// The margin a credit is quoted with for the rate's drift until its
    /// block (ORCHARD_DESIGN 12.2: ~10 ppm covers minutes on a chain with
    /// real stake), in ppm of the derth the value buys. A quote the rate
    /// outran is refused in the ante at no cost, and retried.
    public static let creditMarginPPM: UInt64 = 10

    /// What of a redelegation's value beyond the source's queue may stay in its book (chain bondedDust, 0.001 ERTH).
    public static let bondedDust: UInt64 = 1_000

    /// What of a move's value `u` arrives at dst (ORCHARD_DESIGN 8.7, 12.2),
    /// from src's list entry `a`: all of u when the value leaves the queue
    /// first (`ValidatorQuote.queueFirst`) and the queue P + W covers it;
    /// otherwise u splits between the queue and the bonded stake pro rata, a
    /// bonded part of at most 0.001 ERTH stays in src's book and x/staking
    /// may truncate a uerth: u - 1001.
    public static func arrives(_ a: PrivacyReads.ValidatorQuote, _ u: BigUInt) -> BigUInt {
        if a.queueFirst && u <= a.queue { return u }
        let dust = BigUInt(bondedDust + 1)
        return u > dust ? u - dust : 0
    }

    /// x/shieldedstaking MaxEntryHeightsPerPair: at this many counted entries a move first merges two.
    public static let maxEntryHeightsPerPair: UInt64 = 1_024

    /// Gas a move declares beyond its simulation's headroom: the pair's
    /// record gains at most an entry a block until the tx lands (within
    /// PrivateTxEngine.timeoutBlocks of the tip), and the 10% headroom covers
    /// those entries' 2,500 each, but not the merge (2,500 an entry more and
    /// 128 x 20,000, the chain's redelegateGas) the move pays once the pair
    /// reaches `maxEntryHeightsPerPair` counted entries. So when it could
    /// reach the cap before the tx lands without being there when simulated,
    /// that much more; otherwise nothing (at the cap, the simulation priced it).
    public static func redelegateHeadroom(entries: UInt64, counted: UInt64) -> UInt64 {
        let ahead = PrivateTxEngine.timeoutBlocks + 1
        if counted >= maxEntryHeightsPerPair || counted + ahead < maxEntryHeightsPerPair { return 0 }
        return (Swift.min(entries, maxEntryHeightsPerPair) + ahead) * 2_500 + 128 * 20_000
    }

    /// derth bought for `value` uerth at `book`'s live rate, less a margin for
    /// the rate's drift until the tx's block (ORCHARD_DESIGN 12.2): the chain
    /// refuses a credit the value does not buy, in its ante, at no cost.
    static func creditFor(_ value: BigUInt, _ book: PrivacyReads.ValidatorQuote) throws -> UInt64 {
        if book.supply == 0 {
            try require(book.backing == 0, "this validator's book is settling (no derth, some backing); try again after the epoch ends")
            return UInt64(min(value, BigUInt(Int64.max)))
        }
        try require(book.backing > 0, "this validator's stake is backed by nothing (slashed to zero)")
        let buys = value * book.supply / book.backing
        let margin = (buys * BigUInt(creditMarginPPM) + 999_999) / 1_000_000
        guard buys > margin else { return 0 }
        return UInt64(min(buys - margin, BigUInt(Int64.max)))
    }

    /// A delegation's quote (ORCHARD_DESIGN 12.2), shown on its confirm
    /// sheet: `derth` credited to our note at `validator` for `amount` uerth,
    /// and the `haircut` a merge clearing a moved-in label takes (0: none).
    public struct DelegateQuote: Sendable, Equatable {
        public let validator: String
        public let amount: UInt64
        public let derth: UInt64
        public let haircut: UInt64
    }

    public func quoteDelegate(validator: String, amount: UInt64) async throws -> DelegateQuote {
        try require(amount > 0, "the amount must be positive")
        let min = try await reads.minDelegation()
        try require(amount >= min, "a private delegation is at least \(min)uerth")
        let book = try Self.takesStake(try await reads.validators().of(validator))
        let derth = try Self.creditFor(BigUInt(amount), book)
        try require(derth >= min && derth > 0, "\(amount)uerth buys less than the least derth a delegation may credit; stake more")
        return try await locked {
            let denom = Self.derthDenom(validator)
            let d = try await debtView(spendableStake(denom))
            let plan = try laneA(denom, spends: StakeSelection.merge(spendableStake(denom)) { Self.freeOf($0, d) }, vIn: derth, vOut: 0, d)
            return DelegateQuote(validator: validator, amount: amount, derth: derth, haircut: Self.haircutOf(plan))
        }
    }

    /// Stakes `q.amount` uerth with its validator (ORCHARD_DESIGN 8.1): the
    /// bundle releases it (and the fee) into the module; the stake proof
    /// merges the quoted derth into our note there (up to two of them, a
    /// labelled one cleared once its window closed), or pads its input when
    /// we hold none, so a first delegation looks like a top-up. A rate that
    /// outran the quote is refused in the ante: nothing spent, nothing paid.
    public func delegate(_ q: DelegateQuote) async throws -> TxResult {
        let mx = await maxActions()
        return try await locked {
            let denom = Self.derthDenom(q.validator)
            let d = try await debtView(spendableStake(denom))
            let stake = try laneA(denom, spends: StakeSelection.merge(spendableStake(denom)) { Self.freeOf($0, d) }, vIn: q.derth, vOut: 0, d)
            if Self.haircutOf(stake) > q.haircut {
                throw QuoteChanged(message: "a slash reached stake you moved to this validator since the quote; review it again")
            }
            return try await run { fee in
                // The fee is the bundle's uerth balance less amount.
                let b = try self.bundle(release: try Self.plus([Self.fee: q.amount], Self.fee, fee), maxActions: mx)
                return Assembled(bundles: [b], stake: stake) { bs, sp, _ in
                    MsgShieldedDelegate(bundle: bs[0], validator: q.validator, amount: q.amount, derth: q.derth, stake: sp!)
                }
            }
        }
    }

    /// Stakes `amount` uerth with `validator` at a quote taken now.
    public func delegate(validator: String, amount: UInt64) async throws -> TxResult {
        try await delegate(try await quoteDelegate(validator: validator, amount: amount))
    }

    /// Merges two of our notes at `validator` (MsgRestake, ORCHARD_DESIGN
    /// 20.3): needed only for a note made beside a labelled one (a move into a
    /// validator where ours was labelled) or by another device. At most one
    /// labelled; a closed window clears. One user tap; nothing merges by itself.
    public func restake(validator: String) async throws -> TxResult {
        let mx = await maxActions()
        return try await locked {
            let denom = Self.derthDenom(validator)
            let d = try await debtView(spendableStake(denom))
            let two = StakeSelection.merge(spendableStake(denom)) { Self.freeOf($0, d) }
            guard two.count == 2 else {
                throw PrivacyError(spendableStake(denom).count >= 2 ? "these notes each hold stake moved here recently; they merge once one of their windows closes"
                    : "nothing to merge")
            }
            let stake = try laneA(denom, spends: two, vIn: 0, vOut: 0, d)
            return try await run { fee in
                Assembled(bundles: [try self.feeBundle(fee, maxActions: mx)], stake: stake) { bs, sp, _ in
                    MsgRestake(bundle: bs[0], validator: validator, stake: sp!)
                }
            }
        }
    }

    /// derth denoms held in more than one stake note that can merge now.
    public func stakeMergeable() -> [String: Int] { snapshot.stakeMergeable }

    /// Merges two stake notes of `denom` (derth/<valoper>) into one.
    public func mergeStake(denom: String) async throws -> TxResult { try await restake(validator: try Self.parseDerth(denom)) }

    /// What leaving `amount` derth/`validator` (an undelegation or a lock)
    /// spends: the notes whose free value covers it, refused up front when
    /// the exposure a window keeps in place is what it would take.
    private func leave(_ validator: String, amount: UInt64, _ d: DebtView, salt: Fr = StakePlan.freshSalt(),
                       credit: StakePlan.Credit? = nil) throws -> StakePlan {
        let denom = Self.derthDenom(validator)
        let notes = spendableStake(denom)
        let ins = try StakeSelection.cover(notes, amount: amount, free: { Self.freeOf($0, d) }) { Self.lockedText(notes, d) }
        return try laneA(denom, spends: ins, vIn: 0, vOut: amount, d, salt: salt, credit: credit)
    }

    /// What leaving `amount` derth/`validator` costs beyond its fee: a cleared label's slash cut (0: none). Refuses as the tx would.
    public func leaveHaircut(validator: String, amount: UInt64) async throws -> UInt64 {
        try await locked {
            let d = try await debtView(spendableStake(Self.derthDenom(validator)))
            return Self.haircutOf(try leave(validator, amount: amount, d))
        }
    }

    /// An undelegation's quote, for its confirm sheet: `amount` derth worth
    /// `value` uerth at the validator's live rate now (what the chain books it
    /// at, floor(amount x B / S); a slash before the payout lowers what
    /// arrives), and the `haircut` a cleared label takes (0: none).
    public struct UndelegateQuote: Sendable, Equatable {
        public let validator: String
        public let amount: UInt64
        public let value: UInt64
        public let haircut: UInt64
    }

    public func quoteUndelegate(validator: String, amount: UInt64) async throws -> UndelegateQuote {
        try require(amount > 0, "the amount must be positive")
        let haircut = try await leaveHaircut(validator: validator, amount: amount)
        let book = try await reads.validators().of(validator)
        try require(book.supply > 0 && BigUInt(amount) <= book.supply, "more derth than this validator has")
        let value = BigUInt(amount) * book.backing / book.supply
        return UndelegateQuote(validator: validator, amount: amount, value: UInt64(Swift.min(value, BigUInt(Int64.max))), haircut: haircut)
    }

    /// `book`, refused with the chain's own reason when it takes no delegation or redelegation now (1102).
    static func takesStake(_ book: PrivacyReads.ValidatorQuote) throws -> PrivacyReads.ValidatorQuote {
        guard book.delegatable else {
            throw PrivacyError("this validator is not taking stake now" + (book.refusal.isEmpty ? "" : ": \(book.refusal)"))
        }
        return book
    }

    /// Undelegates `amount` derth/`validator` (ORCHARD_DESIGN 8.4): the
    /// stake proof spends it (the change, or a zero note when nothing is left,
    /// back to us) and the msg names where the chain pays it out, a fresh pool
    /// note opening of our own (pc and its v2 amount-blind ciphertext). At
    /// maturity the chain mints the ERTH there by itself, as one note or,
    /// past 2^63-1, several sharing that ciphertext; sync finds them by trial
    /// decryption like any minted note. Nothing to claim, nothing sent later.
    /// Moved-in derth whose window is open cannot leave: refused up front.
    ///
    /// The undelegation is remembered locally (`PendingUnbond`) from the
    /// moment the node takes the tx, with its epoch, value and payout id from
    /// the committed event, until a note to its pc arrives: what the wallet
    /// shows while it waits. The chain's per-id payout query is never asked
    /// (it would tie this wallet's IP to the undelegation).
    public func undelegate(validator: String, amount: UInt64, maxHaircut: UInt64 = .max) async throws -> TxResult {
        let mx = await maxActions()
        let r = try await locked { () -> TxResult in
            let d = try await debtView(spendableStake(Self.derthDenom(validator)))
            let stake = try leave(validator, amount: amount, d)
            if Self.haircutOf(stake) > maxHaircut {
                throw QuoteChanged(message: "a slash reached stake you moved to this validator since the sheet was shown; review it again")
            }
            let payout = try mint(Self.fee)
            let pc = payout.pc
            let started = now()
            return try await run(accepted: { [self] hash, timeout in
                recordUnbond(PendingUnbond(txHash: hash, validator: validator, derth: amount, pc: pc, startedAt: started, until: timeout))
            }, rejected: { [self] hash in
                dropUnbond(hash)
            }) { fee in
                Assembled(bundles: [try self.feeBundle(fee, maxActions: mx)], stake: stake) { bs, sp, _ in
                    MsgShieldedUndelegate(bundle: bs[0], validator: validator, amount: amount, stake: sp!, pc: pc.bytes,
                                          ciphertext: payout.ciphertext)
                }
            }
        }
        await confirmUnbond(r)
        return r
    }

    /// A move's quote (MsgRedelegate, ORCHARD_DESIGN 8.7), shown on its
    /// confirm sheet: `amount` derth/`src` worth `value` uerth at src's live
    /// rate arrives as `dstDerth` derth/`dst` (dst's live rate, less the
    /// margin and what may stay behind in src's book), merged into our
    /// unlabelled note there (`merges`) or a new note; `haircut` is a cleared
    /// label's slash cut on the src side (0: none). The credited derth is
    /// labelled: it cannot move again until the label window
    /// (`windowSeconds`) has passed. `pairEntries` and `pairCounted` are the
    /// (src, dst) x/staking record's entries when quoted: what the move's gas
    /// headroom is sized by (`redelegateHeadroom`).
    public struct MoveQuote: Sendable, Equatable {
        public let src: String
        public let dst: String
        public let amount: UInt64
        public let value: UInt64
        public let dstDerth: UInt64
        public let haircut: UInt64
        public let merges: Bool
        public let windowSeconds: UInt64
        public var pairEntries: UInt64 = 0
        public var pairCounted: UInt64 = 0
    }

    /// The note at `dst` a move's credit merges into: our largest unlabelled one there (none: the lane pads, making a second note beside a labelled one).
    private func creditTarget(_ dst: String) -> OwnedStakeNote? {
        spendableStake(Self.derthDenom(dst)).filter { $0.label == nil }
            .max { $0.amount != $1.amount ? $0.amount < $1.amount : $0.position > $1.position }
    }

    public func quoteMove(src: String, dst: String, amount: UInt64) async throws -> MoveQuote {
        try require(amount > 0, "the amount must be positive")
        try require(src != dst, "move stake to another validator")
        let (haircut, window) = try await locked { () -> (UInt64, UInt64) in
            let d = try await debtView(spendableStake(Self.derthDenom(src)))
            return (Self.haircutOf(try leave(src, amount: amount, d)), d.windowSeconds)
        }
        let min = try await reads.minDelegation()
        let list = try await reads.validators()
        let a = try list.of(src)
        let b = try Self.takesStake(try list.of(dst))
        try require(a.supply > 0 && BigUInt(amount) <= a.supply, "more derth than this validator has")
        let u = BigUInt(amount) * a.backing / a.supply
        try require(u >= BigUInt(min), "this stake is worth \(u)uerth, less than the \(min)uerth a move must carry")
        let credit = try Self.creditFor(Self.arrives(a, u), b)
        try require(credit >= min && credit > 0, "this move would credit less than the least derth a move may credit; move more")
        let merges = await locked { creditTarget(dst) != nil }
        let load = a.redelegations[dst]
        return MoveQuote(src: src, dst: dst, amount: amount, value: UInt64(Swift.min(u, BigUInt(Int64.max))), dstDerth: credit,
                         haircut: haircut, merges: merges, windowSeconds: window,
                         pairEntries: load?.entries ?? 0, pairCounted: load?.countedEntries ?? 0)
    }

    /// Moves `q.amount` derth from `q.src` to `q.dst` with no unbonding gap
    /// (MsgRedelegate; the user's one approved addition to the freeze). Lane
    /// A spends our src notes (free value only: moved-in stake whose window
    /// is open stays put, refused up front) with the change back; the credit
    /// lane merges the quoted derth/dst into our unlabelled note there (or
    /// pads), labelled with this move: move key = the lane's nullifier,
    /// move_time = the chain's latest block time (the chain takes it within
    /// 600 s before its block), exposed = the credit. A refusal (the rates
    /// outran the quote, move_time too old) costs nothing: the ante runs
    /// before any spend.
    public func redelegate(_ q: MoveQuote) async throws -> TxResult {
        let mx = await maxActions()
        return try await locked {
            let d = try await debtView(spendableStake(Self.derthDenom(q.src)))
            guard let moveTime = await roots.latestBlock()?.time, moveTime > 0 else {
                throw PrivacyError("the node did not say its latest block time; try again")
            }
            let target = creditTarget(q.dst)
            let credit = try StakePlan.credit(keys, denom: Self.derthDenom(q.dst), spend: target,
                                              path: target.map { store.stakeTree.path($0.position) }, vIn: q.dstDerth, moveTime: moveTime)
            let stake = try leave(q.src, amount: q.amount, d, credit: credit)
            if Self.haircutOf(stake) > q.haircut {
                throw QuoteChanged(message: "a slash reached stake you moved to this validator since the quote; review it again")
            }
            return try await run { fee in
                Assembled(bundles: [try self.feeBundle(fee, maxActions: mx)], stake: stake,
                          extraGas: Self.redelegateHeadroom(entries: q.pairEntries, counted: q.pairCounted)) { bs, sp, _ in
                    MsgRedelegate(bundle: bs[0], srcValidator: q.src, dstValidator: q.dst, amount: q.amount, stake: sp!,
                                  dstDerth: q.dstDerth, moveTime: moveTime)
                }
            }
        }
    }

    /// A unix time as the wallet shows it in a sentence: "2026-10-25 14:03 UTC".
    public static func dateText(_ unix: UInt64) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = "yyyy-MM-dd HH:mm 'UTC'"
        return f.string(from: Date(timeIntervalSince1970: TimeInterval(min(unix, 253_402_300_799))))
    }

    /// Undelegations of this wallet whose payout has not arrived yet, oldest first.
    public var pendingUnbonds: [PendingUnbond] { snapshot.pendingUnbonds }

    private func recordUnbond(_ u: PendingUnbond) {
        store.mutate { s in
            s.pendingUnbonds.removeAll { $0.txHash == u.txHash }
            s.pendingUnbonds.append(u)
        }
        persistNoThrow()
    }

    private func dropUnbond(_ hash: String) {
        store.mutate { s in s.pendingUnbonds.removeAll { $0.txHash == hash } }
        persistNoThrow()
    }

    /// The committed undelegation's epoch, value and payout id, from its event.
    private func confirmUnbond(_ r: TxResult) async {
        let epoch = r.attr(Self.undelegateEvent, "epoch").flatMap(UInt64.init)
        var due: Int64?
        if r.code == 0, let e = epoch { due = await reads.unbondDueBy(epoch: e) }
        await locked { confirmUnbondLocked(r, epoch: epoch, due: due) }
    }

    private func confirmUnbondLocked(_ r: TxResult, epoch: UInt64?, due: Int64?) {
        guard let i = store.state.pendingUnbonds.firstIndex(where: { $0.txHash.caseInsensitiveCompare(r.hash) == .orderedSame }) else { return }
        if r.code != 0 {
            store.mutate { $0.pendingUnbonds.remove(at: i) }
            persistNoThrow()
            return
        }
        func attr(_ k: String) -> UInt64? { r.attr(Self.undelegateEvent, k).flatMap(UInt64.init) }
        store.mutate { s in
            s.pendingUnbonds[i].confirmed = true
            s.pendingUnbonds[i].epoch = epoch
            s.pendingUnbonds[i].value = attr("value")
            s.pendingUnbonds[i].payoutID = attr("payout_id")
            s.pendingUnbonds[i].dueBy = due
        }
        persistNoThrow()
    }

    /// Settles the undelegations remembered locally (after a sync): one whose
    /// pc a note of ours now carries has been paid; one still unconfirmed is
    /// looked up by its own hash, dropped when it failed in its block or is
    /// missing past its timeout_height, confirmed when committed.
    private func resolveUnbondsLocked() async {
        let list = store.state.pendingUnbonds
        guard !list.isEmpty else { return }
        let opk = keys.ownerPK
        let paid = Set(store.state.notes.map { $0.note.pc(ownerPK: opk) })
        var tip: UInt64??
        for u in list {
            if paid.contains(u.pc) { dropUnbond(u.txHash); continue }
            if u.confirmed { continue }
            if let r = (try? await chain.tx(u.txHash)) ?? nil {
                let epoch = r.attr(Self.undelegateEvent, "epoch").flatMap(UInt64.init)
                var due: Int64?
                if r.code == 0, let e = epoch { due = await reads.unbondDueBy(epoch: e) }
                confirmUnbondLocked(r, epoch: epoch, due: due)
            } else {
                if tip == nil { tip = .some(try? await chain.tipHeight()) }
                if let until = u.until, let t = tip ?? nil, t > until { dropUnbond(u.txHash) }
            }
        }
    }

    /// Stake notes' summed amount; an overflowing sum (a forged index) is an error, never a trap.
    private static func sum(_ ns: [OwnedStakeNote]) throws -> UInt64 {
        var t: UInt64 = 0
        for n in ns {
            let (s, o) = t.addingReportingOverflow(n.amount)
            try require(!o, "stake amounts overflow")
            t = s
        }
        return t
    }


    /// What a note votes (ORCHARD_DESIGN 8.5): its amount, or for a labelled
    /// note its amount less the slash cut of its exposure under the CURRENT
    /// debt tree (a slash after the snapshot counts).
    private static func voteValue(_ n: OwnedStakeNote, _ d: DebtView?) -> UInt64 {
        guard let l = n.label else { return n.amount }
        guard let d else { return n.amount - l.exposed }
        return n.amount - l.exposed + d.retained(l)
    }

    /// Votes this wallet's stake at `validator` on `proposalID` (ORCHARD_DESIGN
    /// 18.2, 20.4): one msg, one vote proof for up to `MsgStakeVote.maxVoteNotes`
    /// of its derth notes (the largest eligible ones), one weight, their value
    /// rounded down to three significant digits (`voteWeight`). The proof
    /// shows every note under the proposal's snapshot root, its spend
    /// nullifier absent from the snapshot's stake nullifier tree (rebuilt here
    /// and checked against nf_root), a labelled note's value under the
    /// current debt root, and each note's vote nullifier; an unused slot
    /// carries a padding nullifier (fresh r, random slot), so every vote looks
    /// alike. Nothing is spent: a note spent since the snapshot (a top-up, a move)
    /// still votes the value it held then, its opening kept for that; the
    /// merged note it became cannot vote on this proposal. The fee bundle is
    /// against the pool's current roots. Each note's (proposal, vote
    /// nullifier) is remembered from the moment the node accepts the tx, so no
    /// note votes twice. A validator with more notes than one vote holds votes
    /// the rest in another msg (`stakeVoteItems` says how many).
    ///
    /// A note the wallet does not know already voted (a restored wallet) is
    /// refused by the chain before anything is sent (1119 at simulate, naming
    /// its vote nullifier): it is recorded and the vote laid out again without
    /// it, within this one confirmed action, until it carries only fresh notes.
    public func stakeVote(proposalID: UInt64, validator: String, options: [WeightedVoteOption]) async throws -> TxResult {
        let mx = await maxActions()
        let snap = try await snapshot(proposalID: proposalID)
        for _ in 0 ..< MsgStakeVote.maxVoteNotes {
            do {
                return try await locked { try await stakeVoteLocked(proposalID: proposalID, validator: validator, snap: snap, options: options, mx: mx) }
            } catch is NoteVoted {
                // Recorded; lay the vote out again with the notes left.
            }
        }
        return try await locked { try await stakeVoteLocked(proposalID: proposalID, validator: validator, snap: snap, options: options, mx: mx) }
    }

    /// This stake already voted on this proposal (the chain's code 1119): votes are final.
    public struct AlreadyVoted: Swift.Error, LocalizedError {
        public var errorDescription: String? { "This stake already voted on this proposal; vote again to cast any of it that has not." }
    }

    /// The note's nullifier is in the proposal's snapshot nullifier tree: it was spent before voting opened.
    public struct SpentBeforeSnapshot: Swift.Error, LocalizedError {
        public var errorDescription: String? { "This stake was spent before the proposal's snapshot and cannot vote on it." }
    }

    /// A 1119 naming one of the vote's notes, refused before it reached a block: that note is recorded, the rest may vote.
    private struct NoteVoted: Swift.Error {}

    /// Whether the vote's tx reached a mempool (and so may have landed, fee paid).
    private final class Sent: @unchecked Sendable { var value = false }

    private func stakeVoteLocked(proposalID: UInt64, validator: String, snap: PrivacyReads.Snapshot, options: [WeightedVoteOption],
                                 mx: Int) async throws -> TxResult {
        let denom = Self.derthDenom(validator)
        let tree = store.stakeTree
        // A snapshot past the local tree (stake landed since the last
        // sync) cannot be checked here: "sync first", never a trap.
        guard snap.treeSize <= tree.size else { throw SyncFirst() }
        guard tree.rootAt(snap.treeSize) == snap.root else {
            throw PrivacyError("the local stake tree disagrees with the proposal's snapshot root")
        }
        guard let nfRoot = snap.nfRoot else { throw PrivacyError("this proposal's snapshot has no stake nullifier root; it takes no stake vote") }
        await resolveVotesLocked()
        let candidates = eligibleLocked(proposalID, snap).filter { $0.denom == denom }.sorted(by: Self.voteOrder)
        guard !candidates.isEmpty else { throw AlreadyVoted() }
        // Every vote names the current debt root (the chain checks it is current), labelled notes or not.
        let d = try await debtView(Array(candidates.prefix(MsgStakeVote.maxVoteNotes + 1)), always: true)
        let nfs = try await snapshotNullifiers(snap)
        var chosen: [OwnedStakeNote] = []
        var slots: [VoteSlot] = []
        for note in candidates {
            if chosen.count == MsgStakeVote.maxVoteNotes { break }
            guard let low = nfs.nonMembership(PrivacyHash.stakeNF(nk: keys.nk, rho: note.rho, position: note.position)) else {
                // Skipped only when sync, too, saw the spend at
                // or before the snapshot's block. Otherwise the two disagree (a
                // stream or a snapshot that is not the chain's): an error, never
                // a vote silently skipped.
                if let h = note.spentHeight, snap.height > 0, h <= UInt64(snap.height) { continue }
                throw PrivacyError("the proposal's snapshot nullifier tree holds a note's nullifier, but sync saw no spend before the snapshot; sync again")
            }
            // A labelled note's value is read from the debt tree under the current root.
            var debt = DebtTree.Witness.none
            if let l = note.label {
                var t = d.tree
                if t == nil { t = try await debtTreeAt(root: d.root, size: d.size) }
                guard let w = t?.witness(l.moveKey) else { throw PrivacyError("no debt witness for a move key") }
                debt = w
            }
            let slot = try VoteSlot(amount: note.amount, rho: note.rho, rcm: note.rcm, pos: note.position,
                                    path: tree.pathAt(note.position, size: snap.treeSize), low: low, label: note.label, debt: debt)
            // A note slashed to nothing votes nothing.
            guard (slot.value(debtRoot: d.root) ?? 0) > 0 else { continue }
            chosen.append(note)
            slots.append(slot)
        }
        guard !chosen.isEmpty else { throw SpentBeforeSnapshot() }
        let weight = try Self.voteWeight(slots.reduce(UInt64(0)) { Snapshot.satAdd63($0, $1.value(debtRoot: d.root) ?? 0) })
        let nk = keys.nk
        let used = chosen.map { PrivacyHash.voteNF(nk: nk, rho: $0.rho, position: $0.position, proposalID: proposalID) }
        let layout = try VoteLayout.random(used: slots.count)
        let vnfs = layout.vnfs(nk: nk, slots: slots, proposalID: proposalID)
        let asset = PrivacyHash.assetID(denom)
        let voteSlots = slots
        let debtRoot = d.root
        let vote = try VoteWitnessSpec(vnfs: vnfs) { sighash in
            try VoteWitness(nk: nk, slots: voteSlots, layout: layout, noteRoot: snap.root, nfRoot: nfRoot, debtRoot: debtRoot, asset: asset, weight: weight,
                            proposalID: proposalID, sighash: sighash)
        }
        let sent = Sent()
        do {
            let r = try await run(accepted: { [self] hash, timeout in
                sent.value = true
                for v in used { recordVote(StakeVoteRecord(proposalID: proposalID, vnf: v, txHash: hash, until: timeout, confirmed: false)) }
            }, rejected: { [self] hash in
                sent.value = false
                for v in used { forgetVote(proposalID, v, hash: hash) }
            }) { fee in
                Assembled(bundles: [try self.feeBundle(fee, maxActions: mx)], vote: vote) { bs, _, _ in
                    MsgStakeVote(bundle: bs[0], proposalID: proposalID, validator: validator, options: try PrivateMsgs.canonicalOptions(options),
                                 weight: weight, debtRoot: debtRoot.bytes)
                }
            }
            for v in used { recordVote(StakeVoteRecord(proposalID: proposalID, vnf: v, txHash: r.hash, until: nil, confirmed: true)) }
            return r
        } catch {
            // One note already voted (a restored wallet, a vote whose block
            // the wallet missed): the chain names it. It is recorded, so the
            // next vote leaves it out; the others never voted.
            if Self.alreadyVotedError(error) {
                guard let named = Self.usedVoteNullifier(error, used) else { throw AlreadyVoted() }
                recordVote(StakeVoteRecord(proposalID: proposalID, vnf: named, txHash: nil, until: nil, confirmed: true))
                // Refused before any mempool (simulate, CheckTx): nothing was
                // paid and the other notes never voted, so lay it out again.
                // A refusal in a block is settled by resolveVotes.
                if !sent.value { throw NoteVoted() }
                throw AlreadyVoted()
            }
            throw error
        }
    }

    /// Whether a 1119 refusal's `log` names `vnf` (the chain: "vote nullifier <HEX>").
    public static func namesVoteNullifier(_ log: String, _ vnf: Fr) -> Bool { log.lowercased().contains(vnf.hex.lowercased()) }

    /// Which of `vnfs` a 1119 refusal names, if any.
    public static func usedVoteNullifier(_ e: Swift.Error, _ vnfs: [Fr]) -> Fr? {
        var text = "\(e) \(e.localizedDescription)"
        if let r = e as? UnsignedTx.TxRejected { text += " " + r.log }
        return vnfs.first { namesVoteNullifier(text, $0) }
    }

    /// The notes one stake vote takes first: the largest (the most weight in one msg), then by position.
    static func voteOrder(_ a: OwnedStakeNote, _ b: OwnedStakeNote) -> Bool {
        a.amount != b.amount ? a.amount > b.amount : a.position < b.position
    }

    /// The event an undelegation emits: its epoch, value and payout_id.
    public static let undelegateEvent = "shieldedstaking_undelegate"

    /// Slack past the computed payout time for the epoch transition and the block that pays it.
    public static let payoutMarginSeconds: Int64 = 15 * 60

    /// The latest moment an undelegation booked in epoch `e` is paid, given
    /// the current epoch `current` (started `currentStart`, due to end
    /// `currentEnd`). Epoch e ends in the block that starts e+1 and every
    /// epoch lasts at least `epochSeconds`: an epoch not ended yet ends
    /// (e - current) epochs after the current one does; an ended one by
    /// start(current) - (current - 1 - e) x epochSeconds. The SDK entry then
    /// matures `unbondingSeconds` later and the chain pays it in a following
    /// block. Nil on nonsense or overflow (chain-supplied numbers: never a
    /// wrapped answer).
    public static func unbondDueBy(epoch e: UInt64, current: UInt64, currentStart: Int64, currentEnd: Int64, epochSeconds: Int64,
                                   unbondingSeconds: Int64) -> Int64? {
        guard e <= UInt64(Int64.max), current <= UInt64(Int64.max), epochSeconds > 0, unbondingSeconds >= 0 else { return nil }
        let ei = Int64(e), ci = Int64(current)
        func add(_ a: Int64, _ b: Int64) -> Int64? { let (r, o) = a.addingReportingOverflow(b); return o ? nil : r }
        func mul(_ a: Int64, _ b: Int64) -> Int64? { let (r, o) = a.multipliedReportingOverflow(by: b); return o ? nil : r }
        let end: Int64?
        if ei >= ci {
            guard let s = add(currentStart, epochSeconds), let k = mul(ei - ci, epochSeconds) else { return nil }
            end = add(max(currentEnd, s), k)
        } else {
            guard let k = mul(ci - 1 - ei, epochSeconds) else { return nil }
            let (r, o) = currentStart.subtractingReportingOverflow(k)
            end = o ? nil : r
        }
        guard let end, let u = add(end, unbondingSeconds) else { return nil }
        return add(u, payoutMarginSeconds)
    }

    /// x/shieldedstaking ErrVoteNullifierUsed.
    public static let voteNullifierUsed = 1119

    /// The codespace `voteNullifierUsed` is registered in: the code alone could be any module's.
    public static let voteCodespace = "shieldedstaking"

    /// Whether `e` is the chain refusing a vote nullifier already used on the proposal.
    public static func alreadyVotedError(_ e: Swift.Error) -> Bool {
        if let r = e as? UnsignedTx.TxRejected { return r.code == voteNullifierUsed && r.codespace == voteCodespace }
        // Simulate answers with the error's registered text, not its code.
        return "\(e) \(e.localizedDescription)".contains("this stake note already voted on this proposal")
    }

    /// A stake vote's public weight for a note of `amount` uderth
    /// (PRIVACY_FORMATS 15): the amount rounded down to three significant
    /// decimal digits (whole below 1000), so the published weight names a
    /// bucket rather than the note's exact amount (a delegation's minted
    /// amount is public). Gives up less than 1% of the note's voice.
    public static func voteWeight(_ amount: UInt64) throws -> UInt64 {
        try require(amount > 0, "an empty note has no vote")
        var unit: UInt64 = 1
        while amount / unit >= 1000 { unit *= 10 }
        return amount / unit * unit
    }

    /// A vote the node refused outright (in no mempool): the note may vote again.
    private func forgetVote(_ proposalID: UInt64, _ vnf: Fr, hash: String) {
        store.mutate { s in s.stakeVotes.removeAll { $0.proposalID == proposalID && $0.vnf == vnf && !$0.confirmed && $0.txHash == hash } }
        persistNoThrow()
    }

    private func voted(_ proposalID: UInt64, _ vnf: Fr) -> Bool {
        store.state.stakeVotes.contains { $0.proposalID == proposalID && $0.vnf == vnf }
    }

    private func recordVote(_ v: StakeVoteRecord) {
        store.mutate { s in
            s.stakeVotes.removeAll { $0.proposalID == v.proposalID && $0.vnf == v.vnf }
            s.stakeVotes.append(v)
        }
        persistNoThrow()
    }

    /// Settles the votes still pending: committed (or refused as already
    /// voted) they are final; failed in their block, or unknown once the
    /// chain is past their timeout_height, they are forgotten and the note
    /// may vote again.
    private func resolveVotesLocked() async {
        let pending = store.state.stakeVotes.filter { !$0.confirmed }
        guard !pending.isEmpty else { return }
        var tip: UInt64??
        for v in pending {
            var r: TxResult?
            if let h = v.txHash { r = (try? await chain.tx(h)) ?? nil }
            var next: StakeVoteRecord? = v
            if let r {
                // A 1119 refuses the whole msg for the one note the chain names: only that one is final.
                let final = r.code == 0 || (r.code == Self.voteNullifierUsed && r.codespace == Self.voteCodespace && Self.namesVoteNullifier(r.log, v.vnf))
                next = final ? StakeVoteRecord(proposalID: v.proposalID, vnf: v.vnf, txHash: v.txHash, until: v.until, confirmed: true) : nil
            } else if v.txHash == nil {
                next = nil
            } else {
                if tip == nil { tip = .some(try? await chain.tipHeight()) }
                if let until = v.until, let t = tip ?? nil, t > until { next = nil }
                // A timeout no sane tip gives is settled by the tx's status alone.
                else if let until = v.until, let h = v.txHash, !PrivateTxEngine.timeoutSane(until, verifiedNow: store.state.verifiedHeight),
                        await roots.txStatus(h) == .missing { next = nil }
                else { continue }
            }
            store.mutate { s in
                s.stakeVotes.removeAll { $0.proposalID == v.proposalID && $0.vnf == v.vnf }
                if let next { s.stakeVotes.append(next) }
            }
            persistNoThrow()
        }
    }

    // The stake nullifier tree's values in insertion order (leaf 1 on), as far
    // as fetched (a prefix of the chain's), and the last two trees by nf_root.
    // Used under the wallet's lock only.
    private var nfValues: [Fr] = []
    private var nfTrees: [(root: Fr, tree: IndexedTree)] = []
    /// Stake nullifier leaves asked of the indexer a page (the backend's paging rule), and of the LCD (its maximum).
    static let nfPage = WalletSync.pageSize
    static let lcdNfPage = 1000

    /// The stake nullifier tree at `snap` (ORCHARD_DESIGN 3.4):
    /// its first nf_size - 1 values in insertion order, from the indexer's
    /// stream by leaf index (full ranges only: nothing names a note of ours),
    /// the chain's Query/StakeNullifierTree for whatever the indexer lacks,
    /// inserted in order; its root must be the snapshot's nf_root. A mismatch
    /// drops what was fetched and rebuilds from the chain alone once.
    private func snapshotNullifiers(_ snap: PrivacyReads.Snapshot) async throws -> IndexedTree {
        guard let nfRoot = snap.nfRoot else { throw PrivacyError("this proposal's snapshot has no stake nullifier root; it takes no stake vote") }
        snapshotLock.lock(); let stale = nfCacheStale; nfCacheStale = false; snapshotLock.unlock()
        if stale { nfValues = []; nfTrees = [] }
        if let t = nfTrees.first(where: { $0.root == nfRoot }) { return t.tree }
        try require(snap.nfSize <= Merkle.capacity && snap.nfSize <= UInt64(Int.max), "nf_size \(snap.nfSize)")
        // nf_size is the LCD's (snapshot()), so the fetch below
        // is bounded by the chain's own count, never an indexer's.
        let n = Int(snap.nfSize > 0 ? snap.nfSize - 1 : 0)
        for chainOnly in [false, true] {
            let values: [Fr]
            do {
                values = try await fetchNullifiers(n, chainOnly: chainOnly)
            } catch {
                if chainOnly { throw error }
                nfValues = []
                continue
            }
            if let t = try? IndexedTree(values), t.root() == nfRoot {
                nfTrees.append((nfRoot, t))
                if nfTrees.count > 2 { nfTrees.removeFirst() }
                return t
            }
            nfValues = []
        }
        throw PrivacyError("the stake nullifiers served do not rebuild the proposal's snapshot nullifier root")
    }

    /// The first `n` stake nullifiers in insertion order.
    private func fetchNullifiers(_ n: Int, chainOnly: Bool) async throws -> [Fr] {
        if !chainOnly {
            do {
                while nfValues.count < n {
                    // The backend's paging rule: aligned pages of nfPage leaf
                    // indexes from 0 (leaf 0, the sentinel, is never a row); the
                    // leaves already held are dropped, each checked against ours.
                    let next = UInt64(nfValues.count) + 1
                    let page = try await indexer.stakeNullifierLeaves(fromIndex: WalletSync.aligned(next, Self.nfPage), limit: Self.nfPage)
                    guard page.leaves.count <= Self.nfPage else {
                        throw WalletSync.Inconsistent(message: "the indexer sent \(page.leaves.count) stake nullifiers in a page of \(Self.nfPage)")
                    }
                    var added = 0
                    for (index, v) in page.leaves {
                        if index >= 1 && index < next {
                            guard nfValues[Int(index - 1)] == v else { throw WalletSync.Inconsistent(message: "stake nullifier leaf \(index) differs from the one held") }
                            continue
                        }
                        // Contiguous from where we are, or the page is not the tree's order.
                        guard index == UInt64(nfValues.count) + 1 else { throw WalletSync.Inconsistent(message: "stake nullifier leaf \(index) out of order") }
                        if nfValues.count >= n { break }
                        nfValues.append(v); added += 1
                    }
                    if added == 0 { break }
                }
            } catch is WalletSync.Inconsistent {
                throw PrivacyError("the indexer's stake nullifiers are out of order")
            } catch {
                // An indexer without the stream, or down: the chain serves the rest.
            }
        }
        while nfValues.count < n {
            let page = try await reads.stakeNullifierTree(start: UInt64(nfValues.count), limit: min(n - nfValues.count, Self.lcdNfPage))
            if page.values.isEmpty { break }
            nfValues.append(contentsOf: page.values.prefix(Self.lcdNfPage))
        }
        try require(nfValues.count >= n, "only \(nfValues.count) of the snapshot's \(n) stake nullifiers were served")
        return Array(nfValues.prefix(n))
    }

    private let snapshotLock = NSLock()
    private var snapshots: [UInt64: PrivacyReads.Snapshot] = [:]
    /// The genesis the per-wallet caches (snapshots, the stake nullifier
    /// tree) were built under: dropped when the store's changes.
    private var cacheGenesis: String?

    private func cachedSnapshot(_ id: UInt64) -> PrivacyReads.Snapshot? {
        let g = snapshot.genesis
        snapshotLock.lock(); defer { snapshotLock.unlock() }
        if g != cacheGenesis { snapshots = [:]; cacheGenesis = g; nfCacheStale = true }
        return snapshots[id]
    }

    private func cacheSnapshot(_ id: UInt64, _ s: PrivacyReads.Snapshot) {
        snapshotLock.lock(); snapshots[id] = s; snapshotLock.unlock()
    }

    /// Set when the genesis changed: the nullifier values and trees (held under the wallet's lock) are dropped at their next use.
    private var nfCacheStale = false

    /// `proposalID`'s snapshot, from the chain's own Query/Snapshot: its
    /// stake root and size, nullifier root and size, block and validator
    /// rates are taken from the LCD, never from the indexer (a
    /// forged nf_root would make a note look spent before the snapshot). The
    /// proposal id is public, so asking names nothing of this wallet. The
    /// note root is then checked against the wallet's verified stake tree and
    /// the nullifier root against the tree the nullifiers rebuild.
    public func snapshot(proposalID: UInt64) async throws -> PrivacyReads.Snapshot {
        if let s = cachedSnapshot(proposalID) { return s }
        let s = try await reads.snapshot(proposalID: proposalID)
        cacheSnapshot(proposalID, s)
        return s
    }

    /// Derth notes that may vote on `proposalID`: in the stake tree at the
    /// snapshot, not spent before it as far as sync knows (the cast checks
    /// the snapshot's nullifier tree itself), and not already voted on it. A
    /// note spent after the snapshot still votes; its outputs cannot.
    private func eligible(_ proposalID: UInt64, _ snap: PrivacyReads.Snapshot) async -> [OwnedStakeNote] {
        guard snap.nfRoot != nil else { return [] }
        return await locked {
            await resolveVotesLocked()
            return eligibleLocked(proposalID, snap)
        }
    }

    private func eligibleLocked(_ proposalID: UInt64, _ snap: PrivacyReads.Snapshot) -> [OwnedStakeNote] {
        guard snap.nfRoot != nil else { return [] }
        let nk = keys.nk
        return store.state.stakeNotes.filter {
            $0.denom.hasPrefix(Self.derthPrefix) && $0.amount > 0 && $0.position < snap.treeSize &&
                // Spent in the snapshot's own block is spent before it (the
                // snapshot is the trees at that block's end).
                ($0.spentHeight == nil || snap.height <= 0 || $0.spentHeight! > UInt64(snap.height)) &&
                !voted(proposalID, PrivacyHash.voteNF(nk: nk, rho: $0.rho, position: $0.position, proposalID: proposalID))
        }
    }

    /// The positions that can vote on `proposalID`: created before the block
    /// it entered voting at (the chain refuses later ones).
    public static func votingPositions(_ positions: [PrivacyReads.Position], snapshot: PrivacyReads.Snapshot) -> [PrivacyReads.Position] {
        positions.filter { snapshot.height <= 0 || $0.createdHeight < UInt64(snapshot.height) }
    }

    /// What a stake vote on a proposal weighs, in uerth.
    public struct StakeWeight: Sendable, Equatable {
        public let notes: Int
        /// The ids of the positions that can vote.
        public let positionIDs: Set<UInt64>
        public let uerth: UInt64
    }

    /// floor(derth x rate), the chain's conversion.
    public static func derthValue(_ derth: UInt64, rate: Decimal) -> UInt64 {
        var product = Decimal(derth) * rate
        var floored = Decimal()
        NSDecimalRound(&floored, &product, 0, .down)
        // Saturating at 2^63-1, as Android (a negative rate is 0).
        if floored <= 0 { return 0 }
        if floored >= Decimal(Int64.max) { return UInt64(Int64.max) }
        return UInt64(truncating: floored as NSNumber)
    }

    /// This wallet's weight on `proposalID`: per validator, its eligible derth
    /// notes in votes of up to `MsgStakeVote.maxVoteNotes` (each the rounded
    /// `voteWeight` of its notes' sum), and every position created before the
    /// snapshot's block, each at its validator's rate (`rateFor`: the
    /// snapshot's, else the live book's; a validator with neither adds nothing).
    public func stakeVoteWeight(proposalID: UInt64, positions: [PrivacyReads.Position]) async throws -> StakeWeight {
        let snap = try await snapshot(proposalID: proposalID)
        let books = snap.rates.isEmpty ? try? await reads.validators() : nil
        let notes = await eligible(proposalID, snap)
        let ps = Self.votingPositions(positions, snapshot: snap)
        let d = notes.contains(where: { $0.label != nil }) ? try? await locked { try await debtView(notes, always: true) } : nil
        var total: UInt64 = 0
        for (denom, ns) in Dictionary(grouping: notes, by: \.denom) {
            guard let rate = Self.rateFor(snap, (try? Self.parseDerth(denom)) ?? "", books) else { continue }
            for part in Self.parts(ns.sorted(by: Self.voteOrder)) {
                let v = part.reduce(UInt64(0)) { Snapshot.satAdd63($0, Self.voteValue($1, d)) }
                guard v > 0, let w = try? Self.voteWeight(v) else { continue }
                total = PrivateMsgs.saturatingAdd(total, Self.derthValue(w, rate: rate))
            }
        }
        for p in ps {
            guard let rate = Self.rateFor(snap, p.validator, books) else { continue }
            total = PrivateMsgs.saturatingAdd(total, Self.derthValue(p.derth, rate: rate))
        }
        return StakeWeight(notes: notes.count, positionIDs: Set(ps.map(\.id)), uerth: total)
    }

    /// `ns` in votes of up to `MsgStakeVote.maxVoteNotes`, in order.
    static func parts(_ ns: [OwnedStakeNote]) -> [[OwnedStakeNote]] {
        stride(from: 0, to: ns.count, by: MsgStakeVote.maxVoteNotes).map { Array(ns[$0 ..< min($0 + MsgStakeVote.maxVoteNotes, ns.count)]) }
    }

    /// One stake vote msg: a validator's eligible notes (one msg votes up to
    /// `MsgStakeVote.maxVoteNotes`; more take `parts` msgs, each its own
    /// weight and fee), or a position.
    public enum StakeVoteItem: Sendable, Equatable {
        case validator(String, notes: Int)
        case position(id: UInt64, counter: UInt32)

        /// How many msgs the item takes.
        public var parts: Int {
            if case let .validator(_, n) = self { return (n + MsgStakeVote.maxVoteNotes - 1) / MsgStakeVote.maxVoteNotes }
            return 1
        }
    }

    /// Every stake vote `proposalID` takes from this wallet: one per
    /// validator with eligible derth notes, and one per position of ours that
    /// may vote (created before the snapshot's block). Each is its own tx,
    /// confirmed and sent by the user one at a time (`castStakeVote`);
    /// nothing is cast for them in the background.
    public func stakeVoteItems(proposalID: UInt64) async throws -> [StakeVoteItem] {
        let snap = try await snapshot(proposalID: proposalID)
        let byValidator = Dictionary(grouping: await eligible(proposalID, snap)) { (try? Self.parseDerth($0.denom)) ?? "" }
        var out: [StakeVoteItem] = byValidator.keys.sorted().map { .validator($0, notes: byValidator[$0]!.count) }
        let mine = try await positions()
        let voting = Set(Self.votingPositions(mine.map(\.position), snapshot: snap).map(\.id))
        for p in mine where voting.contains(p.position.id) { out.append(.position(id: p.position.id, counter: p.counter)) }
        return out
    }

    /// What one stake vote weighs: the notes it carries (0 for a position) and the ERTH its weight is worth at the snapshot.
    public struct VotePreview: Sendable, Equatable {
        public let notes: Int
        public let uerth: UInt64
        public init(notes: Int, uerth: UInt64) { self.notes = notes; self.uerth = uerth }
    }

    /// What `item`'s next vote on `proposalID` carries, for its confirm sheet (nil: nothing left).
    public func stakeVotePreview(proposalID: UInt64, item: StakeVoteItem) async throws -> VotePreview? {
        let snap = try await snapshot(proposalID: proposalID)
        switch item {
        case let .validator(v, _):
            let part = Array(await eligible(proposalID, snap).filter { $0.denom == Self.derthDenom(v) }.sorted(by: Self.voteOrder)
                .prefix(MsgStakeVote.maxVoteNotes))
            guard !part.isEmpty else { return nil }
            let d = part.contains(where: { $0.label != nil }) ? try? await locked { try await debtView(part, always: true) } : nil
            let value = part.reduce(UInt64(0)) { Snapshot.satAdd63($0, Self.voteValue($1, d)) }
            guard value > 0 else { return nil }
            let w = try Self.voteWeight(value)
            let books = snap.rates[v] == nil ? try? await reads.validators() : nil
            guard let rate = Self.rateFor(snap, v, books) else { return nil }
            return VotePreview(notes: part.count, uerth: Self.derthValue(w, rate: rate))
        case let .position(id, _):
            guard let p = try await positions().first(where: { $0.position.id == id })?.position else { return nil }
            let books = snap.rates[p.validator] == nil ? try? await reads.validators() : nil
            guard let rate = Self.rateFor(snap, p.validator, books) else { return nil }
            return VotePreview(notes: 0, uerth: Self.derthValue(p.derth, rate: rate))
        }
    }

    /// ERTH per derth of `validator` for a vote's ERTH figure: the
    /// snapshot's, which a snapshot with a seq does not carry, else the live
    /// book's (backing / supply, Query/Validators read whole). Nil when
    /// neither is known: no ERTH figure rather than the derth count as one.
    static func rateFor(_ snap: PrivacyReads.Snapshot, _ validator: String, _ books: PrivacyReads.ValidatorList?) -> Decimal? {
        if let r = snap.rates[validator] { return r }
        guard let book = books?[validator], book.supply > 0,
              let backing = Decimal(string: book.backing.description), let supply = Decimal(string: book.supply.description)
        else { return nil }
        return backing / supply
    }

    /// Casts `item` as the last sync left things: a validator's next vote (up
    /// to two notes) or a position's. Nil when there is nothing left of it to
    /// cast (its notes already voted on this proposal, or were spent before
    /// its snapshot; the position is gone).
    public func castStakeVote(proposalID: UInt64, item: StakeVoteItem, options: [WeightedVoteOption]) async throws -> TxResult? {
        switch item {
        case let .validator(v, _):
            do {
                return try await stakeVote(proposalID: proposalID, validator: v, options: options)
            } catch is AlreadyVoted {
                let snap = try await snapshot(proposalID: proposalID)
                if await eligible(proposalID, snap).contains(where: { $0.denom == Self.derthDenom(v) }) { throw AlreadyVoted() }
                return nil
            } catch is SpentBeforeSnapshot {
                return nil
            }
        case let .position(id, counter):
            guard let p = try await positions().first(where: { $0.position.id == id && $0.counter == counter }) else { return nil }
            return try await positionVote(p.position, counter: p.counter, proposalID: proposalID, options: options)
        }
    }

    // MARK: - Groundworks positions

    /// This wallet's Groundworks positions: the public positions whose owner
    /// tag is one of ours (owner-tag counters 0 ... next + otagGap, extended
    /// past every match, so a wallet restored from the mnemonic finds them
    /// too), each with its counter, by position id.
    public func positions() async throws -> [(position: PrivacyReads.Position, counter: UInt32)] {
        let all = try await reads.positions()
        return await locked { positionsLocked(all) }
    }

    private var otags: [UInt32: Fr] = [:]

    private func ownerTag(_ c: UInt32) -> Fr {
        if let t = otags[c] { return t }
        let t = keys.ownerTag(c)
        otags[c] = t
        return t
    }

    private func positionsLocked(_ all: [PrivacyReads.Position]) -> [(position: PrivacyReads.Position, counter: UInt32)] {
        // A restored wallet knows the closed positions' counters from their unlock memos.
        let closedNext = store.state.closedOtagMax.map { $0 == UInt32.max ? $0 : $0 + 1 } ?? 0
        let next = max(store.state.nextOtagCounter, closedNext)
        // Closed positions vanish from the chain, so the window must cross a
        // run of them (and of failed locks) to reach a live one.
        var limit = next.addingReportingOverflow(Self.otagGap).overflow ? UInt32.max : next + Self.otagGap
        var out: [(position: PrivacyReads.Position, counter: UInt32)] = []
        var from: UInt32 = 0
        while from < limit {
            var mine: [Fr: UInt32] = [:]
            for c in from ..< limit { mine[ownerTag(c)] = c }
            let found = all.compactMap { p in mine[p.ownerTag].map { (position: p, counter: $0) } }
            out += found
            from = limit
            if let top = found.map(\.counter).max() {
                let (want, o) = top.addingReportingOverflow(1 + Self.otagGap)
                if !o, want > limit { limit = want }
            }
        }
        let found = out.map(\.counter).max().map { $0 == UInt32.max ? $0 : $0 + 1 } ?? 0
        let newNext = max(next, found)
        if newNext > store.state.nextOtagCounter {
            store.mutate { $0.nextOtagCounter = newNext }
            persistNoThrow()
        }
        return out.sorted { $0.position.id < $1.position.id }
    }

    /// Locks `amount` derth/`validator` into a new position split by `splits`, under a fresh owner tag.
    public func lockPosition(validator: String, amount: UInt64, splits: [UInt64: UInt64]) async throws -> TxResult {
        let mx = await maxActions()
        let all = try await reads.positions()
        return try await locked {
            let d = try await debtView(spendableStake(Self.derthDenom(validator)))
            // Refused up front, before a counter is taken, when moved-in stake whose window is open would have to leave.
            _ = try leave(validator, amount: amount, d)
            // A restored wallet's counter starts past every tag it already holds.
            _ = positionsLocked(all)
            let counter = store.mutate { s -> UInt32 in
                let c = s.nextOtagCounter
                s.nextOtagCounter += 1
                return c
            }
            try store.save()
            let stake = try leave(validator, amount: amount, d, salt: keys.otagSalt(counter))
            let w = Self.weights(splits)
            return try await run { fee in
                Assembled(bundles: [try self.feeBundle(fee, maxActions: mx)], stake: stake) { bs, sp, _ in
                    MsgLockPosition(bundle: bs[0], validator: validator, amount: amount, splits: w, stake: sp!)
                }
            }
        }
    }

    /// A position's own proof (its update, its vote): no notes, the position's owner tag, the chain's current clear_before and debt root.
    private func ownerPlan(_ position: PrivacyReads.Position, counter: UInt32) async throws -> StakePlan {
        guard keys.ownerTag(counter) == position.ownerTag else { throw PrivacyError("position \(position.id) is not owned by tag \(counter)") }
        return try StakePlan(nk: keys.nk, denom: nil, spends: [], paths: [], out: nil, vIn: 0, vOut: 0, clear: try Self.clearOf(nil, try await debtView()),
                             credit: nil, tagSalt: keys.otagSalt(counter), anchor: stakeAnchor())
    }

    public func updatePosition(_ position: PrivacyReads.Position, counter: UInt32, splits: [UInt64: UInt64]) async throws -> TxResult {
        let mx = await maxActions()
        return try await locked {
            let stake = try await ownerPlan(position, counter: counter)
            let w = Self.weights(splits)
            return try await run { fee in
                Assembled(bundles: [try self.feeBundle(fee, maxActions: mx)], stake: stake) { bs, sp, _ in
                    MsgUpdatePosition(bundle: bs[0], positionID: position.id, splits: w, stake: sp!)
                }
            }
        }
    }

    /// Closes `position`: the stake proof (its owner tag) merges the
    /// position's derth into our note at its validator, or pads when we hold
    /// none there (ORCHARD_DESIGN 8.3). The fee bundle carries a value-0
    /// record note to ourselves naming the closed counter, so no
    /// restore ever locks under its tag again.
    public func unlockPosition(_ position: PrivacyReads.Position, counter: UInt32) async throws -> TxResult {
        let mx = await maxActions()
        return try await locked {
            guard keys.ownerTag(counter) == position.ownerTag else { throw PrivacyError("position \(position.id) is not owned by tag \(counter)") }
            let denom = Self.derthDenom(position.validator)
            let d = try await debtView(spendableStake(denom))
            let stake = try laneA(denom, spends: StakeSelection.merge(spendableStake(denom)) { Self.freeOf($0, d) }, vIn: position.derth, vOut: 0, d,
                                  salt: keys.otagSalt(counter))
            let record = try NoteOut.to(keys.address, denom: Self.fee, value: 0, memo: WalletSync.unlockMemo(nk: keys.nk, counter: counter))
            return try await run { fee in
                Assembled(bundles: [try self.bundle([record], release: [Self.fee: fee], maxActions: mx)], stake: stake) { bs, sp, _ in
                    MsgUnlockPosition(bundle: bs[0], positionID: position.id, stake: sp!)
                }
            }
        }
    }

    public func positionVote(_ position: PrivacyReads.Position, counter: UInt32, proposalID: UInt64, options: [WeightedVoteOption],
                             accepted: @escaping (String) -> Void = { _ in }) async throws -> TxResult {
        let mx = await maxActions()
        return try await locked {
            let stake = try await ownerPlan(position, counter: counter)
            return try await run(accepted: { hash, _ in accepted(hash) }) { fee in
                Assembled(bundles: [try self.feeBundle(fee, maxActions: mx)], stake: stake) { bs, sp, _ in
                    MsgPositionVote(bundle: bs[0], positionID: position.id, proposalID: proposalID, options: try PrivateMsgs.canonicalOptions(options), stake: sp!)
                }
            }
        }
    }

    // MARK: - dex

    /// Swaps `amountIn` `denomIn` from notes for at least `minOut` `denomOut`
    /// (any pools, through the ERTH hub), the output minted to us or to `to`
    /// with a value-blind (v2) ciphertext opened against the amount the chain
    /// publishes. The fee comes from the bundle's ERTH balance (the one fee
    /// rule: a swap of ANML into ERTH needs an ERTH note for it too).
    public func noteSwap(denomIn: String, amountIn: UInt64, denomOut: String, minOut: UInt64, to: ShieldedAddress? = nil) async throws -> TxResult {
        try require(denomIn != denomOut && amountIn > 0 && minOut > 0, "a swap needs two denoms and positive amounts")
        try Self.requireTransferable(denomIn)
        let mx = await maxActions()
        return try await locked {
            let out = try payout(denomOut, to: to)
            return try await run { fee in
                let b = try self.bundle(release: try Self.plus([denomIn: amountIn], Self.fee, fee), maxActions: mx)
                return Assembled(bundles: [b]) { bs, _, _ in
                    MsgNoteSwap(bundle: bs[0], denomIn: denomIn, amountIn: amountIn, denomOut: denomOut, minAmountOut: minOut,
                                pc: out.pc.bytes, ciphertext: out.ciphertext)
                }
            }
        }
    }

    /// Deposits `tokenAmount` `token` and `erthAmount` uerth from notes into
    /// `poolID`, in one bundle (its token balance the token leg, its uerth
    /// balance less the fee the ERTH leg, named as erth_amount). The LP
    /// shares are private: minted as a dexlp/<pool> note to a pc of ours.
    /// Whatever the pool ratio does not take is minted back to one refund pc
    /// (a note per asset, both opened by the one v2 refund ciphertext).
    public func addLiquidityShielded(poolID: UInt64, token: String, tokenAmount: UInt64, erthAmount: UInt64,
                                     minShares: String) async throws -> TxResult {
        try require(tokenAmount > 0 && erthAmount > 0 && token != Self.fee, "both legs must be positive")
        let mx = await maxActions()
        return try await locked {
            let refund = try mint(token)
            let shares = try mint(Self.lpDenom(poolID))
            return try await run { fee in
                let b = try self.bundle(release: try Self.plus([token: tokenAmount, Self.fee: erthAmount], Self.fee, fee), maxActions: mx)
                return Assembled(bundles: [b]) { bs, _, _ in
                    MsgAddLiquidityShielded(bundle: bs[0], poolID: poolID, minShares: minShares, refundPC: refund.pc.bytes,
                                            refundCiphertext: refund.ciphertext, sharePC: shares.pc.bytes,
                                            shareCiphertext: shares.ciphertext, erthAmount: erthAmount)
                }
            }
        }
    }

    /// Withdraws `shares` dexlp/`poolID` from share notes: escrowed for the
    /// dex's LP unbonding period with no account named, then both legs (ERTH
    /// and `token`) minted to pcs of ours as notes (v2 ciphertexts).
    public func removeLiquidityShielded(poolID: UInt64, token: String, shares: UInt64) async throws -> TxResult {
        try require(shares > 0, "the shares must be positive")
        let mx = await maxActions()
        return try await locked {
            let erth = try mint(Self.fee)
            let tok = try mint(token)
            return try await run { fee in
                let b = try self.bundle(release: [Self.lpDenom(poolID): shares, Self.fee: fee], maxActions: mx)
                return Assembled(bundles: [b]) { bs, _, _ in
                    MsgRemoveLiquidityShielded(bundle: bs[0], poolID: poolID, erthPC: erth.pc.bytes, erthCiphertext: erth.ciphertext,
                                               tokenPC: tok.pc.bytes, tokenCiphertext: tok.ciphertext)
                }
            }
        }
    }

    /// A withdrawal whose note-paid leg is too large to start (nothing was sent).
    public struct WithdrawalTooLarge: Swift.Error, LocalizedError {
        public let message: String
        public var errorDescription: String? { message }
    }

    /// The most a withdrawal's note-paid leg may be worth when it starts: a
    /// quarter of one note this wallet can hold (2^63 - 1), so the pool can
    /// move 4x against the provider before maturity, as x/dex allows for its
    /// own cap. Exactly x/dex's maxWithdrawalNoteLeg:
    /// MaxSplitNotes / 4 = 32 notes of MaxNoteValue (2^63 - 1), paid as
    /// several notes, each one this wallet can hold. As Android.
    public static let maxWithdrawalNoteLeg = BigInt(Int64.max) * 32

    /// Refuses, before anything is proven or signed, a withdrawal of `shares`
    /// of `totalShares` whose note legs (`erthNote`, `tokenNote`) at the
    /// pool's reserves now are above `maxWithdrawalNoteLeg` (x/dex
    /// checkWithdrawalNoteLegs: floor(shares x reserve / total)).
    public static func checkWithdrawalNoteLegs(shares: BigInt, totalShares: BigInt, reserveErth: BigInt, reserveToken: BigInt,
                                               tokenDenom: String, erthNote: Bool, tokenNote: Bool) throws {
        guard totalShares > 0 else { return }
        for (on, reserve, denom) in [(erthNote, reserveErth, fee), (tokenNote, reserveToken, tokenDenom)] where on {
            let v = shares * reserve / totalShares
            if v > maxWithdrawalNoteLeg {
                throw WithdrawalTooLarge(message: "this withdrawal's \(denom) leg (\(v)) is more than one withdrawal can pay as private notes; withdraw in smaller parts")
            }
        }
    }

    /// Private LP shares per pool id (dexlp/<id> notes).
    public func lpShares() -> [UInt64: UInt64] { snapshot.lpShares }

    /// The note a pool-1 MsgRemoveLiquidity names for its ANML leg (signed
    /// by the provider's transparent key): pc and v2 ciphertext to us, since
    /// the payout is priced when the withdrawal matures.
    public func withdrawalNote() async throws -> NoteOut {
        try await locked { try payout("uanml", to: nil) }
    }

    /// Where a chain-priced payment goes (a swap's or a MsgBuyAnml's output,
    /// a withdrawal's token leg): a pc of fresh secrets, ours or `to`'s, with
    /// a value-blind (v2) ciphertext the owner opens once the chain publishes
    /// the amount.
    private func payout(_ denom: String, to: ShieldedAddress?) throws -> NoteOut {
        if let to, to.ownerPK != keys.ownerPK { return try NoteOut.blindTo(to, denom: denom) }
        return try mint(denom)
    }

    /// The note a MsgShield mints to `to` (paying a handle from the public
    /// balance): a pc of its owner_pk and a blind ciphertext to its ek_pub;
    /// the shield's amount is public, its recipient is not.
    public func shieldOutput(denom: String, to: ShieldedAddress) throws -> NoteOut { try payout(denom, to: to) }

    // MARK: - saving

    /// Saves where nothing can be thrown (a broadcast's acceptance callback):
    /// a failure is kept and shown (`Snapshot.saveError`), never dropped
    /// The next save that succeeds clears it.
    func persistNoThrow() {
        do {
            try store.save()
            setSaveError(nil)
        } catch {
            setSaveError(error.localizedDescription)
        }
    }

    private func setSaveError(_ e: String?) { snapLock.lock(); saveErrorValue = e; snapLock.unlock() }
}

public extension PrivacyWallet.MoveRecorder {
    /// No refusal by default: the chain still refuses an owner that cannot take the move (at no fee).
    func refusal(_ move: PendingMove) -> String? { nil }
}
