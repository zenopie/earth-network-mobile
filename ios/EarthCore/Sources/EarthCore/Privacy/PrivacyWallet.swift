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
    public static let anmlPerClaim: UInt64 = 1_000_000
    /// MsgRegister's gas, for the fee estimate before simulating: the
    /// passport proof (3M) and DSC chain (300k), the fee bundle's two action
    /// proofs and note writes, and the tx's bytes.
    public static let registerGasEstimate: UInt64 = 7_000_000
    /// A private tx's gas for the confirm sheet: what the sheet shows is the
    /// most the tx may then pay without asking again (audit 3: a higher
    /// simulated fee shows the sheet again at it). Two actions, a stake or
    /// membership proof, the tx's bytes and the 10% headroom fit under it.
    public static let privateGasEstimate: UInt64 = 10_000_000
    /// Slack against the chain's clock for bounds the wallet must stay under.
    public static let clockMargin: Int64 = 600
    /// x/shielded's default max_actions_per_bundle, until the chain is read.
    public static let defaultMaxActions = 16

    public static let fee = "uerth"
    public static let derthPrefix = "derth/"
    public static let unbondPrefix = "unbond/"
    public static let lpPrefix = "dexlp/"
    /// Owner-tag counters scanned past the highest known (PRIVACY_FORMATS.md 1).
    public static let otagGap: UInt32 = 1024
    /// A pending registration whose tx failed in its block (K7).
    public static let txFailed = "the registration tx failed"

    public let keys: PrivacyKeys
    let store: PrivacyStore
    private let indexer: PrivacyIndexer
    private let chain: PrivateChain
    private let reads: PrivacyChainReads
    public let chainID: String
    /// The chain's own trees, every synced root is checked against (C3).
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
        public let referrerAddress: String
        public let referrerBoundAt: Int64
        public let unbondRetryAt: [String: Int64]
        public let syncedHeight: UInt64
        /// A committed registration whose leaf is not matched yet (nil: none), and why, if it failed.
        public let pendingRegistration: PendingRegistration?
        /// Whether the last sync's roots matched the chain's (C3); no private tx is built on unverified ones.
        public let rootsVerified: Bool
        public let rootsError: String?
        /// x/shielded max_actions_per_bundle: the most notes (and outputs) one bundle carries.
        public let maxActions: Int
        /// Why the last save that could not throw failed (nil: saved).
        public let saveError: String?

        init(store: PrivacyStore, keys: PrivacyKeys, maxActions: Int, saveError: String? = nil) {
            let s = store.state
            notes = s.notes; stakeNotes = s.stakeNotes; identity = s.identity; claimedDays = s.claimedDays
            caretakerSplit = s.caretakerSplit; caretakerCastAt = s.caretakerCastAt
            referrerAddress = s.referrerAddress; referrerBoundAt = s.referrerBoundAt
            unbondRetryAt = s.unbondRetryAt; syncedHeight = s.notesHeight
            pendingRegistration = s.pendingRegistration; rootsVerified = s.rootsVerified; rootsError = s.rootsError
            identityStatus = WalletSync.identityStatus(store: store, keys: keys)
            self.maxActions = maxActions
            self.saveError = saveError
        }

        /// Spendable pool balance per denom (pending spends excluded).
        public var poolBalances: [String: UInt64] {
            var out: [String: UInt64] = [:]
            for n in notes where n.unspent && n.pendingAt == nil {
                out[n.note.denom] = PrivateMsgs.saturatingAdd(out[n.note.denom] ?? 0, n.note.value)
            }
            return out
        }

        /// Stake (derth/<valoper>) and unbonding claims (unbond/<valoper>/<epoch>) per denom: owner-locked, never sendable.
        public var stakeBalances: [String: UInt64] {
            var out: [String: UInt64] = [:]
            for n in stakeNotes where n.spendable { out[n.denom] = PrivateMsgs.saturatingAdd(out[n.denom] ?? 0, n.amount) }
            return out
        }

        /// Everything held privately: the pool's denoms and the stake denoms.
        public var balances: [String: UInt64] { poolBalances.merging(stakeBalances, uniquingKeysWith: PrivateMsgs.saturatingAdd) }

        /// Spendable note counts per denom with more than one note.
        public var mergeable: [String: Int] {
            var counts: [String: Int] = [:]
            for n in notes where n.unspent && n.pendingAt == nil && n.note.value > 0 { counts[n.note.denom, default: 0] += 1 }
            return counts.filter { $0.value >= 2 }
        }

        /// derth denoms held in more than one stake note, with their note counts.
        public var stakeMergeable: [String: Int] {
            var counts: [String: Int] = [:]
            for n in stakeNotes where n.spendable && n.denom.hasPrefix(PrivacyWallet.derthPrefix) { counts[n.denom, default: 0] += 1 }
            return counts.filter { $0.value >= 2 }
        }

        /// Unbonding claim denoms this wallet holds.
        public var unbondDenoms: Set<String> {
            Set(stakeNotes.filter { $0.spendable && $0.denom.hasPrefix(PrivacyWallet.unbondPrefix) }.map(\.denom))
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
        return try await locked {
            await fillPendingRegistration()
            return try await syncLocked()
        }
    }

    private func syncLocked() async throws -> WalletSync.Result {
        try await WalletSync(indexer: indexer, store: store, keys: keys, chainID: chainID, chain: roots, now: now).sync()
    }

    /// K7: a registration recorded at acceptance whose block the wallet has
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

    /// A stake note the chain will mint to us: spc_mint's fresh secrets and their blind stake ciphertext.
    private func stakeMint() throws -> StakePlan.SelfMint { try StakePlan.selfMint(keys) }

    private func today() -> UInt64 { UInt64(max(0, now()) / Self.secondsPerDay) }

    // MARK: - running

    /// Proves and broadcasts. Only on trees the chain itself vouched for at
    /// the last sync (C3): a proof over an indexer's forged tree is refused by
    /// the chain anyway, and its notes may not exist.
    private func run(memo: String = "", accepted: @escaping @Sendable (String, UInt64) -> Void = { _, _ in },
                     _ assemble: (UInt64) throws -> Assembled) async throws -> TxResult {
        try requireVerified()
        // The spent notes are marked the moment the node accepts the tx
        // (K7), before the wait for its block: a wait that times out (the tx
        // may still land) or a killed app never leaves them spendable. They
        // stay pending until the chain is past the tx's timeout_height.
        let (result, _) = try await engine.run(assemble, memo: memo, shownFee: Self.shownFee) { [self] hash, a, timeout in
            markPending(a.spends, a.stakeSpends, timeoutHeight: timeout)
            accepted(hash, timeout)
        }
        return result
    }

    /// The fee the confirm sheet showed, for the private run in this task
    /// (TxController binds it around the run): a fee above it throws
    /// `PrivateTxEngine.FeeAboveQuote` and the sheet asks again (audit 3).
    /// Unbound (automation, later stake-vote casts): only the cap applies.
    @TaskLocal public static var shownFee: UInt64?

    /// Audit 3: only on roots verified by the last sync, in that sync's own
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
            return try await engine.quote { fee in
                let b = try self.bundle([out], release: [Self.fee: fee], maxActions: m)
                return Assembled(bundles: [b]) { bs, _, _ in MsgSend(bundle: bs[0], fee: fee) }
            }
        }
    }

    private func markPending(_ spent: [OwnedNote], _ stake: [OwnedStakeNote], timeoutHeight: UInt64) {
        let positions = Set(spent.map(\.position))
        let stakePositions = Set(stake.map(\.position))
        let t = now()
        store.mutate { s in
            for i in s.notes.indices where positions.contains(s.notes[i].position) {
                s.notes[i].pendingAt = t; s.notes[i].pendingUntil = timeoutHeight
            }
            for i in s.stakeNotes.indices where stakePositions.contains(s.stakeNotes[i].position) {
                s.stakeNotes[i].pendingAt = t; s.stakeNotes[i].pendingUntil = timeoutHeight
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

    /// The identity is too recent for this action; it opens `waitSeconds` from now.
    public struct NotYet: Swift.Error, LocalizedError {
        public let waitSeconds: Int64
        public var errorDescription: String? {
            "This registration is too recent for this action; try again in \(waitSeconds / 3600 + 1)h."
        }
    }

    private func identity() throws -> IdentityRecord {
        guard let id = store.state.identity else { throw PrivacyError("This wallet has no registration.") }
        guard WalletSync.identityStatus(store: store, keys: keys) == .live else {
            throw PrivacyError("This wallet's registration is no longer live; register again.")
        }
        return id
    }

    private func membership(scope: Fr, excludedDsc: Fr, excludedCountry: Fr, maxActivation: UInt64) throws -> MembershipWitnessSpec {
        let id = try identity()
        if id.activatedAt > maxActivation { throw NotYet(waitSeconds: Int64(clamping: id.activatedAt - maxActivation)) }
        let tree = store.identityTree
        let path = tree.path(id.leafIndex)
        let root = tree.root()
        let idSecret = keys.idSecret
        return MembershipWitnessSpec { signal in
            try MembershipWitness(idSecret: idSecret, dscKey: id.dscKey, country: id.country, activatedAt: id.activatedAt,
                                  leafIndex: id.leafIndex, siblings: path, root: root, scope: scope, signal: signal,
                                  excludedDsc: excludedDsc, excludedCountry: excludedCountry, maxActivation: maxActivation)
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
        // Wave 3 (B/F2): the chain refuses an unshield to any module account.
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
        try require(!denom.hasPrefix(derthPrefix) && !denom.hasPrefix(unbondPrefix), "stake is owner-locked: it cannot be sent or unshielded")
    }

    // MARK: - personhood

    /// The notes a registration pays and the binding its passport proof
    /// carries. Hold until `register`. Both notes are chain-minted, so each
    /// carries a v2 ciphertext of fresh secrets to our own address; the
    /// binding covers those ciphertexts, so they are written here, before the
    /// passport is proven, and sent exactly as they are. `gas` is the
    /// /gas/register note (its ciphertext is not bound).
    public struct RegistrationPrep: Sendable {
        public let anml: NoteOut
        public let erth: NoteOut
        public let gas: NoteOut
        public let affiliate: String
        public let binding: Fr
        public let idc: Fr
    }

    public func prepareRegistration(affiliate: String?) async throws -> RegistrationPrep {
        try await locked {
            let aff = affiliate?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            let anml = try mint("uanml")
            let erth = try mint("uerth")
            let gas = try mint("uerth")
            let binding = PrivacyHash.registrationBinding(idc: keys.idc, pcAnml: anml.pc, ctAnml: anml.ciphertext, pcErth: erth.pc,
                                                          ctErth: erth.ciphertext, affiliate: try PrivateMsgs.affiliateField(aff))
            return RegistrationPrep(anml: anml, erth: erth, gas: gas, affiliate: aff, binding: binding, idc: keys.idc)
        }
    }

    /// MsgRegister without its fee bundle: what /gas/register checks.
    public func registerMsg(_ prep: RegistrationPrep, proof: Data, publicSignals: [String], signatureAlgorithm: String, dscDer: Data) -> MsgRegisterPrivate {
        MsgRegisterPrivate(fee: nil, proof: proof, publicSignals: publicSignals, signatureAlgorithm: signatureAlgorithm, dscDer: dscDer,
                           idc: prep.idc.bytes, pcAnml: prep.anml.pc.bytes, ciphertextAnml: prep.anml.ciphertext,
                           pcErth: prep.erth.pc.bytes, ciphertextErth: prep.erth.ciphertext, affiliate: prep.affiliate)
    }

    /// Broadcasts the registration, its fee paid by a fee bundle (the gas
    /// grant's note, on a first registration) that also carries the
    /// registration record note (PRIVACY_FORMATS.md 3a: a value-0 note to
    /// ourselves whose memo lets a wallet restored from the mnemonic find the
    /// leaf), and records the registration as pending before anything else
    /// (C2), then tries to resolve it. `publicSignals` are the passport
    /// proof's: [current_date, address, nullifier, dsc_key].
    public func register(_ prep: RegistrationPrep, proof: Data, publicSignals: [String], signatureAlgorithm: String, dscDer: Data) async throws -> TxResult {
        let mx = await maxActions()
        let result: TxResult = try await locked {
            try require(publicSignals.count == 4, "a passport proof has four public signals")
            try require(try PrivateMsgs.decimalField(publicSignals[1]) == prep.binding, "the passport proof is bound to other notes")
            try require(PrivateMsgs.isCalendarDate(publicSignals[0]), "the passport proof's current_date \(publicSignals[0]) is not a calendar date")
            let base = registerMsg(prep, proof: proof, publicSignals: publicSignals, signatureAlgorithm: signatureAlgorithm, dscDer: dscDer)
            let dscKey = try PrivateMsgs.decimalField(publicSignals[3])
            let hint = Self.dscCountry(dscDer)
            let record = try NoteOut.to(keys.address, denom: Self.fee, value: 0,
                                        memo: WalletSync.regMemo(nk: keys.nk, dscKey: dscKey, country: hint, builtAt: UInt64(max(0, now()))))
            let pending: @Sendable (String, UInt64) -> Void = { [self] hash, _ in
                // K7: by hash, the moment the node accepts it; the leaf comes later.
                store.mutate {
                    $0.pendingRegistration = PendingRegistration(
                        txHash: hash, leafIndex: nil, dscKey: dscKey, passportNullifier: publicSignals[2],
                        publicSignals: publicSignals, activatedAt: nil, countryHint: hint)
                }
                persistNoThrow()
            }
            let result = try await run(accepted: pending) { fee in
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

    /// Fills the pending registration (C2, K7) from its committed tx — the
    /// leaf index from its register event, activated_at its block time —
    /// and syncs; every later sync retries until the leaf is in the local
    /// identity tree and matches.
    public func recordRegistration(_ result: TxResult) async throws {
        try await locked { try recordPendingLocked(result) }
        _ = try? await sync()
    }

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
            let m = try membership(scope: PrivacyHash.claimScope(day: day), excludedDsc: .zero, excludedCountry: .zero,
                                   maxActivation: (day - 1).multipliedReportingOverflow(by: UInt64(Self.secondsPerDay)).partialValue)
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
        let a = Int64(clamping: id.activatedAt)
        let firstDay = a / Self.secondsPerDay + 1 + (a % Self.secondsPerDay == 0 ? 0 : 1)
        let t = Int64(today())
        let day = max(t + (snap.claimedDays.contains(UInt64(t)) ? 1 : 0), firstDay)
        return day == t ? 0 : day * Self.secondsPerDay
    }

    /// The day every activation bound keeps from now (wave 3: the largest identity root window).
    public static let activationMargin: Int64 = 86_400

    /// The max_activation a caretaker split or referrer binding names: at most
    /// now - R - 86400 (the chain's bound since wave 3, L4/L5: the largest
    /// root window, not the live one), rounded down to the hour so it says
    /// nothing about when the tx was made, less a margin for clock skew.
    private func leaseBound() async throws -> UInt64 {
        let p = try await reads.personhoodParams()
        let bound = now() - p.caretakerVoteSeconds - Self.activationMargin - Self.clockMargin
        return UInt64(max(0, bound / 3600 * 3600))
    }

    private static func weights(_ split: [UInt64: UInt64]) -> [Msg.AllocationWeight] {
        split.sorted { $0.key < $1.key }.map { Msg.AllocationWeight(optionID: $0.key, percent: $0.value) }
    }

    /// Casts, refreshes or (empty) clears the caretaker split, option id -> percent.
    public func setCaretaker(split: [UInt64: UInt64]) async throws -> TxResult {
        let mx = await maxActions()
        return try await locked {
            let maxAct = try await leaseBound()
            let m = try membership(scope: PrivacyHash.caretakerScope(), excludedDsc: .zero, excludedCountry: .zero, maxActivation: maxAct)
            let w = Self.weights(split)
            let r = try await run { fee in
                Assembled(bundles: [try self.feeBundle(fee, maxActions: mx)], membership: m) { bs, _, mem in
                    MsgSetCaretaker(fee: bs[0], membership: mem!, percentages: w, maxActivation: maxAct)
                }
            }
            store.mutate { $0.caretakerCastAt = now(); $0.caretakerSplit = split }
            try store.save()
            return r
        }
    }

    /// Whether the split needs refreshing to stay counted: past half of R.
    public func caretakerDue() async throws -> Bool {
        let snap = snapshot
        guard !snap.caretakerSplit.isEmpty else { return false }
        let r = try await reads.personhoodParams().caretakerVoteSeconds
        return now() - snap.caretakerCastAt > r / 2
    }

    /// Binds (or, empty, clears) the transparent address this person's
    /// referral rewards are paid to. The binding is public (the address is),
    /// the person behind it is not; it lapses after R unless refreshed.
    ///
    /// Binding an address needs its owner's consent (wave 3, L6): `consent`
    /// signs (domain, chain id, the membership's nullifier, the address) with
    /// the key whose address it is, a transparent account of this wallet.
    /// The nullifier is the scope's, known before proving; not in the sighash.
    public func bindReferrer(address: String, consent: ((Data) throws -> (publicKey: Data, signature: Data))? = nil) async throws -> TxResult {
        let mx = await maxActions()
        return try await locked {
            let maxAct = try await leaseBound()
            let m = try membership(scope: PrivacyHash.referrerScope(), excludedDsc: .zero, excludedCountry: .zero, maxActivation: maxAct)
            var pub = Data(), sig = Data()
            if !address.isEmpty {
                guard let consent else { throw PrivacyError("binding a referrer address needs its owner's signature") }
                let decoded = try Bech32.decode(address)
                let c = try consent(try PrivateMsgs.referrerConsentBytes(chainID: chainID, nullifier: try m.witness(signal: .zero).nullifier.bytes,
                                                                          address: Data(decoded.data)))
                try require(c.publicKey.count == 33 && c.signature.count == 64, "a referrer consent is a 33-byte key and a 64-byte signature")
                try require(try EarthKey.address(fromPublicKey: c.publicKey) == address, "\(address) is not an address this wallet controls")
                pub = c.publicKey; sig = c.signature
            }
            let r = try await run { fee in
                Assembled(bundles: [try self.feeBundle(fee, maxActions: mx)], membership: m) { bs, _, mem in
                    MsgBindReferrer(fee: bs[0], membership: mem!, address: address, maxActivation: maxAct, referrerPubKey: pub, referrerSignature: sig)
                }
            }
            store.mutate { $0.referrerAddress = address; $0.referrerBoundAt = address.isEmpty ? 0 : now() }
            try store.save()
            return r
        }
    }

    /// Whether the referrer binding needs refreshing to stay live: past half of R.
    public func referrerDue() async throws -> Bool {
        let snap = snapshot
        guard !snap.referrerAddress.isEmpty else { return false }
        let r = try await reads.personhoodParams().caretakerVoteSeconds
        return now() - snap.referrerBoundAt > r / 2
    }

    // MARK: - assembly

    /// A human vote on an x/gov proposal. The scope is recomputed here rather than taken from the node.
    public func voteProposal(proposalID: UInt64, yes: Bool) async throws -> TxResult {
        let mx = await maxActions()
        return try await locked {
            let b = try await reads.ballotInputs(proposalID: proposalID, optionID: 0)
            guard b.scope == PrivacyHash.proposalScope(proposalID: proposalID, round: b.round) else {
                throw PrivacyError("the node's ballot scope is not this proposal's")
            }
            let m = try membership(scope: b.scope, excludedDsc: b.excludedDsc, excludedCountry: b.excludedCountry, maxActivation: b.maxActivation)
            return try await run { fee in
                Assembled(bundles: [try self.feeBundle(fee, maxActions: mx)], membership: m) { bs, _, mem in
                    MsgVoteProposalPrivate(fee: bs[0], membership: mem!, proposalID: proposalID, option: yes ? .yes : .no)
                }
            }
        }
    }

    public func proposeRemoval(optionID: UInt64) async throws -> TxResult {
        let mx = await maxActions()
        return try await locked {
            let day = today()
            // Wave 3 (L4/L5): the start of today (UTC) less a day, whatever the root window.
            let maxAct = UInt64(max(0, Int64(day) * Self.secondsPerDay - Self.activationMargin))
            let m = try membership(scope: PrivacyHash.proposeRemovalScope(optionID: optionID, day: day), excludedDsc: .zero,
                                   excludedCountry: .zero, maxActivation: maxAct)
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
            let m = try membership(scope: b.scope, excludedDsc: b.excludedDsc, excludedCountry: b.excludedCountry, maxActivation: b.maxActivation)
            return try await run { fee in
                Assembled(bundles: [try self.feeBundle(fee, maxActions: mx)], membership: m) { bs, _, mem in
                    MsgVoteRemoval(fee: bs[0], membership: mem!, optionID: optionID, option: yes ? .yes : .no)
                }
            }
        }
    }

    // MARK: - private staking (owner-locked stake notes)

    public static func derthDenom(_ valoper: String) -> String { "\(derthPrefix)\(valoper)" }
    public static func unbondDenom(_ valoper: String, epoch: UInt64) -> String { "\(unbondPrefix)\(valoper)/\(epoch)" }
    public static func lpDenom(_ poolID: UInt64) -> String { "\(lpPrefix)\(poolID)" }

    public static func parseDerth(_ denom: String) throws -> String {
        guard denom.hasPrefix(derthPrefix) else { throw PrivacyError("not a derth note") }
        return String(denom.dropFirst(derthPrefix.count))
    }

    public static func parseUnbond(_ denom: String) throws -> (validator: String, epoch: UInt64) {
        let parts = denom.split(separator: "/", omittingEmptySubsequences: false)
        guard parts.count == 3, parts[0] == "unbond", let e = UInt64(parts[2]) else { throw PrivacyError("not an unbond note") }
        return (String(parts[1]), e)
    }

    /// The stake tree's latest root (anchors a stake proof; unchecked when it spends nothing).
    private func stakeAnchor() -> Fr { store.stakeTree.size == 0 ? .zero : store.stakeTree.root() }

    private func stakePlan(_ denom: String?, spends: [OwnedStakeNote], outAmounts: [UInt64], vOut: UInt64,
                           mint: StakePlan.SelfMint? = nil, salt: Fr = StakePlan.throwaway().rho,
                           anchor: Fr? = nil, paths: [[Fr]]? = nil) throws -> StakePlan {
        // mint: a self-mint for a msg the chain mints a stake note for; nil: throwaway secrets, no ciphertext.
        let outs = try outAmounts.filter { $0 > 0 }.map { try StakePlan.out(keys, denom: denom!, amount: $0) }
        return try StakePlan(nk: keys.nk, denom: denom, spends: spends, paths: paths ?? spends.map { store.stakeTree.path($0.position) },
                             outputs: outs, mint: mint.map { ($0.rho, $0.rcm) } ?? StakePlan.throwaway(), tagSalt: salt,
                             anchor: anchor ?? stakeAnchor(), vOut: vOut, mintCiphertext: mint?.ciphertext ?? Data())
    }

    /// Stake notes of `denom` this wallet can spend now.
    private func spendableStake(_ denom: String) -> [OwnedStakeNote] {
        store.state.stakeNotes.filter { $0.spendable && $0.denom == denom }
    }

    /// Stakes `amount` uerth with `validator`: the bundle releases it (and
    /// the fee) into the module, the chain mints derth at the live rate as a
    /// stake note to our stake self-mint pc, delegated at the epoch's end.
    public func delegate(validator: String, amount: UInt64) async throws -> TxResult {
        try require(amount > 0, "the amount must be positive")
        let mx = await maxActions()
        return try await locked {
            let stake = try stakePlan(Self.derthDenom(validator), spends: [], outAmounts: [], vOut: 0, mint: try stakeMint())
            return try await run { fee in
                // The fee is the bundle's uerth balance less amount.
                let b = try self.bundle(release: try Self.plus([Self.fee: amount], Self.fee, fee), maxActions: mx)
                return Assembled(bundles: [b], stake: stake) { bs, sp, _ in
                    MsgShieldedDelegate(bundle: bs[0], validator: validator, amount: amount, stake: sp!)
                }
            }
        }
    }

    /// Merges or splits stake notes of `validator`: spends `notes` (1-2) and
    /// creates notes of `amounts` (1-2, summing to theirs), all ours. Stake
    /// moves in at most two notes a proof, so a balance spread over more is
    /// merged first.
    public func restake(validator: String, notes: [OwnedStakeNote], amounts: [UInt64]) async throws -> TxResult {
        let mx = await maxActions()
        return try await locked { try await restakeLocked(validator: validator, notes: notes, amounts: amounts, mx: mx) }
    }

    private func restakeLocked(validator: String, notes: [OwnedStakeNote], amounts: [UInt64], mx: Int) async throws -> TxResult {
        let denom = Self.derthDenom(validator)
        try require((1 ... 2).contains(notes.count) && (1 ... 2).contains(amounts.count) && amounts.allSatisfy { $0 > 0 },
                    "a restake spends and creates one or two notes")
        try require(notes.reduce(UInt64(0)) { PrivateMsgs.saturatingAdd($0, $1.amount) } == amounts.reduce(0, PrivateMsgs.saturatingAdd)
                    && !amounts.contains(.max), "a restake keeps the amount")
        let stake = try stakePlan(denom, spends: notes, outAmounts: amounts, vOut: 0)
        return try await run { fee in
            Assembled(bundles: [try self.feeBundle(fee, maxActions: mx)], stake: stake) { bs, sp, _ in
                MsgRestake(bundle: bs[0], validator: validator, stake: sp!)
            }
        }
    }

    /// derth denoms held in more than one stake note, with their note counts.
    public func stakeMergeable() -> [String: Int] { snapshot.stakeMergeable }

    /// Makes `amount` of `denom` spendable by one stake proof (two notes):
    /// while the two largest fall short, merges the two smallest (one restake
    /// each) and syncs. Returns the merge txs it broadcast.
    public func consolidateStake(denom: String, amount: UInt64) async throws -> [TxResult] {
        var out: [TxResult] = []
        while true {
            let ns = snapshot.stakeNotes.filter { $0.spendable && $0.denom == denom }
            let top2 = ns.map(\.amount).sorted(by: >).prefix(2).reduce(0, PrivateMsgs.saturatingAdd)
            if top2 >= amount || ns.count < 3 { return out }
            out.append(try await mergeStake(denom: denom))
            try await sync()
        }
    }

    /// Merges the two smallest stake notes of `denom` (derth/<valoper>) into one.
    public func mergeStake(denom: String) async throws -> TxResult {
        let mx = await maxActions()
        return try await locked {
            let two = Array(spendableStake(denom).sorted { $0.amount < $1.amount }.prefix(2))
            try require(two.count == 2, "nothing to merge")
            let (sum, o) = two[0].amount.addingReportingOverflow(two[1].amount)
            try require(!o, "stake amounts overflow")
            return try await restakeLocked(validator: try Self.parseDerth(denom), notes: two, amounts: [sum], mx: mx)
        }
    }

    /// Turns `amount` derth/`validator` into an owner-locked unbonding claim,
    /// minted to our stake self-mint pc at the live rate; claimable once its
    /// epoch's undelegation matures.
    public func undelegate(validator: String, amount: UInt64) async throws -> TxResult {
        let mx = await maxActions()
        return try await locked {
            let denom = Self.derthDenom(validator)
            let ins = try StakeSelection.cover(spendableStake(denom), amount: amount)
            let stake = try stakePlan(denom, spends: ins, outAmounts: [try Self.sum(ins) - amount], vOut: amount, mint: try stakeMint())
            return try await run { fee in
                Assembled(bundles: [try self.feeBundle(fee, maxActions: mx)], stake: stake) { bs, sp, _ in
                    MsgShieldedUndelegate(bundle: bs[0], validator: validator, amount: amount, stake: sp!)
                }
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

    /// Claims matured unbonding claims of `denom` (unbond/<valoper>/<epoch>,
    /// up to two notes a claim): the chain mints their ERTH to a pc of ours in
    /// the pool (v2 ciphertext), the fee out of it (fee_from_output, the one
    /// msg that may), so the msg carries no bundle at all.
    public func claimUnbonding(denom: String) async throws -> TxResult {
        try await locked {
            let (validator, epoch) = try Self.parseUnbond(denom)
            let ins = Array(spendableStake(denom).sorted { $0.amount > $1.amount }.prefix(2))
            try require(!ins.isEmpty, "no unbonding claim of \(denom)")
            let amount = try Self.sum(ins)
            let stake = try stakePlan(denom, spends: ins, outAmounts: [], vOut: amount)
            let erth = try mint(Self.fee)
            return try await run { fee in
                Assembled(bundles: [], stake: stake) { _, sp, _ in
                    MsgClaimUnbonding(validator: validator, epoch: epoch, amount: amount, pc: erth.pc.bytes, ciphertext: erth.ciphertext,
                                      feeFromOutput: fee, stake: sp!)
                }
            }
        }
    }

    /// Unbonding claim denoms this wallet holds.
    public func unbondDenoms() -> Set<String> { snapshot.unbondDenoms }

    /// Votes one derth note on `proposalID` without spending it (ORCHARD_DESIGN
    /// 15): a vote proof that the note is under the proposal's snapshot root,
    /// that its spend nullifier is absent from the snapshot's stake nullifier
    /// tree (rebuilt here and checked against nf_root), with weight
    /// `voteWeight` of its amount, and its per-proposal vote nullifier. The
    /// note is untouched: it votes on every other open proposal and is spent
    /// as usual. The fee bundle is against the pool's current roots. The
    /// (proposal, vote nullifier) is remembered from the moment the node
    /// accepts the tx, so the note never votes twice on the proposal.
    public func stakeVote(proposalID: UInt64, note: OwnedStakeNote, options: [WeightedVoteOption]) async throws -> TxResult {
        let mx = await maxActions()
        let snap = try await snapshot(proposalID: proposalID)
        return try await locked { try await stakeVoteLocked(proposalID: proposalID, note: note, snap: snap, options: options, mx: mx) }
    }

    /// This note already voted on this proposal (the chain's code 1119): votes are final.
    public struct AlreadyVoted: Swift.Error, LocalizedError {
        public var errorDescription: String? { "This stake note already voted on this proposal." }
    }

    /// The note's nullifier is in the proposal's snapshot nullifier tree: it was spent before voting opened.
    public struct SpentBeforeSnapshot: Swift.Error, LocalizedError {
        public var errorDescription: String? { "This stake was spent before the proposal's snapshot and cannot vote on it." }
    }

    private func stakeVoteLocked(proposalID: UInt64, note: OwnedStakeNote, snap: PrivacyReads.Snapshot, options: [WeightedVoteOption],
                                 mx: Int) async throws -> TxResult {
        let validator = try Self.parseDerth(note.denom)
        try require(note.amount > 0, "an empty note has no vote")
        let tree = store.stakeTree
        // Audit 3: a snapshot past the local tree (stake landed since the last
        // sync) cannot be checked here: "sync first", never a trap.
        guard snap.treeSize <= tree.size else { throw SyncFirst() }
        try require(note.position < snap.treeSize, "this stake arrived after the proposal's snapshot and cannot vote on it")
        guard tree.rootAt(snap.treeSize) == snap.root else {
            throw PrivacyError("the local stake tree disagrees with the proposal's snapshot root")
        }
        let vnf = PrivacyHash.voteNF(nk: keys.nk, rho: note.rho, position: note.position, proposalID: proposalID)
        await resolveVotesLocked()
        if voted(proposalID, vnf) { throw AlreadyVoted() }
        guard let nfRoot = snap.nfRoot else { throw PrivacyError("this proposal's snapshot has no stake nullifier root; it takes no stake vote") }
        guard let low = try await snapshotNullifiers(snap).nonMembership(PrivacyHash.stakeNF(nk: keys.nk, rho: note.rho, position: note.position))
        else { throw SpentBeforeSnapshot() }
        let weight = try Self.voteWeight(note.amount)
        let path = tree.pathAt(note.position, size: snap.treeSize)
        let asset = PrivacyHash.assetID(note.denom)
        let nk = keys.nk
        let vote = VoteWitnessSpec(vnf: vnf) { sighash in
            try VoteWitness(nk: nk, amount: note.amount, rho: note.rho, rcm: note.rcm, pos: note.position, path: path, low: low,
                            noteRoot: snap.root, nfRoot: nfRoot, asset: asset, weight: weight, proposalID: proposalID, sighash: sighash)
        }
        do {
            let r = try await run(accepted: { [self] hash, timeout in
                recordVote(StakeVoteRecord(proposalID: proposalID, vnf: vnf, txHash: hash, until: timeout, confirmed: false))
            }) { fee in
                Assembled(bundles: [try self.feeBundle(fee, maxActions: mx)], vote: vote) { bs, _, _ in
                    MsgStakeVote(bundle: bs[0], proposalID: proposalID, validator: validator, options: try PrivateMsgs.canonicalOptions(options),
                                 weight: weight)
                }
            }
            recordVote(StakeVoteRecord(proposalID: proposalID, vnf: vnf, txHash: r.hash, until: nil, confirmed: true))
            return r
        } catch {
            // Already voted (a restored wallet, a vote whose block the wallet missed): final either way.
            if Self.alreadyVotedError(error) {
                recordVote(StakeVoteRecord(proposalID: proposalID, vnf: vnf, txHash: nil, until: nil, confirmed: true))
                throw AlreadyVoted()
            }
            throw error
        }
    }

    /// x/shieldedstaking ErrVoteNullifierUsed.
    public static let voteNullifierUsed = 1119

    /// Whether `e` is the chain refusing a vote nullifier already used on the proposal.
    public static func alreadyVotedError(_ e: Swift.Error) -> Bool {
        let m = "\(e) \(e.localizedDescription)"
        return m.contains("already voted on this proposal") || m.range(of: "code\\s*\(voteNullifierUsed)\\b", options: .regularExpression) != nil
    }

    /// A stake vote's public weight for a note of `amount` uderth
    /// (PRIVACY_FORMATS 4e): the amount rounded down to three significant
    /// decimal digits (whole below 1000), so the published weight names a
    /// bucket rather than the note's exact amount (a delegation's minted
    /// amount is public). Gives up less than 1% of the note's voice.
    public static func voteWeight(_ amount: UInt64) throws -> UInt64 {
        try require(amount > 0, "an empty note has no vote")
        var unit: UInt64 = 1
        while amount / unit >= 1000 { unit *= 10 }
        return amount / unit * unit
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
                next = r.code == 0 || r.code == Self.voteNullifierUsed ? StakeVoteRecord(proposalID: v.proposalID, vnf: v.vnf, txHash: v.txHash,
                                                                                        until: v.until, confirmed: true) : nil
            } else if v.txHash == nil {
                next = nil
            } else {
                if tip == nil { tip = .some(try? await chain.tipHeight()) }
                if let until = v.until, let t = tip ?? nil, t > until { next = nil } else { continue }
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
    /// Stake nullifier leaves asked of the indexer a page, and of the LCD (its maximum).
    static let nfPage = 5000
    static let lcdNfPage = 1000

    /// The stake nullifier tree at `snap` (ORCHARD_DESIGN 15, wallet format 3):
    /// its first nf_size - 1 values in insertion order, from the indexer's
    /// stream by leaf index (full ranges only: nothing names a note of ours),
    /// the chain's Query/StakeNullifierTree for whatever the indexer lacks,
    /// inserted in order; its root must be the snapshot's nf_root. A mismatch
    /// drops what was fetched and rebuilds from the chain alone once.
    private func snapshotNullifiers(_ snap: PrivacyReads.Snapshot) async throws -> IndexedTree {
        guard let nfRoot = snap.nfRoot else { throw PrivacyError("this proposal's snapshot has no stake nullifier root; it takes no stake vote") }
        if let t = nfTrees.first(where: { $0.root == nfRoot }) { return t.tree }
        try require(snap.nfSize <= Merkle.capacity && snap.nfSize <= UInt64(Int.max), "nf_size \(snap.nfSize)")
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
                    let page = try await indexer.stakeNullifierLeaves(fromIndex: UInt64(nfValues.count) + 1, limit: min(n - nfValues.count, Self.nfPage))
                    if page.leaves.isEmpty { break }
                    for (index, v) in page.leaves {
                        // Contiguous from where we are, or the page is not the tree's order.
                        guard index == UInt64(nfValues.count) + 1 else { throw WalletSync.Inconsistent(message: "stake nullifier leaf \(index) out of order") }
                        nfValues.append(v)
                    }
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
    private var snapshotRows: [UInt64: StakeSnapshotRow] = [:]
    private var snapshotsNext: UInt64 = 0
    private static let maxSnapshotPages = 1000

    private func cachedSnapshotRow(_ id: UInt64) -> (StakeSnapshotRow?, UInt64) {
        snapshotLock.lock(); defer { snapshotLock.unlock() }
        return (snapshotRows[id], snapshotsNext)
    }

    private func cacheSnapshotRows(_ rows: [StakeSnapshotRow], next: UInt64) {
        snapshotLock.lock(); defer { snapshotLock.unlock() }
        for r in rows { snapshotRows[r.proposalID] = r }
        snapshotsNext = max(snapshotsNext, next)
    }

    /// `proposalID`'s snapshot: from the indexer's full snapshot stream (no
    /// request names the proposal), else the chain's Query/Snapshot (a
    /// legacy snapshot without a nullifier root, or one the indexer has not
    /// reached). Whatever the source, the note root is checked against the
    /// wallet's verified stake tree and the nullifier root against the tree
    /// the nullifiers rebuild; the chain proves against its own.
    public func snapshot(proposalID: UInt64) async throws -> PrivacyReads.Snapshot {
        var (row, from) = cachedSnapshotRow(proposalID)
        if row == nil {
            do {
                for _ in 0 ..< Self.maxSnapshotPages {
                    let page = try await indexer.stakeSnapshots(fromHeight: from, limit: nil)
                    cacheSnapshotRows(page.rows, next: page.nextHeight)
                    from = max(from, page.nextHeight)
                    if !page.complete { break }
                }
            } catch {}
            row = cachedSnapshotRow(proposalID).0
        }
        if let row, let root = row.root, let nfRoot = row.nfRoot {
            return PrivacyReads.Snapshot(root: root, treeSize: row.treeSize, height: Int64(clamping: row.height), nfRoot: nfRoot, nfSize: row.nfSize)
        }
        return try await reads.snapshot(proposalID: proposalID)
    }

    /// Derth notes that may vote on `proposalID`: in the stake tree at the
    /// snapshot, not spent before it as far as sync knows (the cast checks
    /// the snapshot's nullifier tree itself), and not already voted on it. A
    /// note spent after the snapshot still votes; its outputs cannot.
    private func eligible(_ proposalID: UInt64, _ snap: PrivacyReads.Snapshot) async -> [OwnedStakeNote] {
        guard snap.nfRoot != nil else { return [] }
        return await locked {
            await resolveVotesLocked()
            let nk = keys.nk
            return store.state.stakeNotes.filter {
                $0.denom.hasPrefix(Self.derthPrefix) && $0.amount > 0 && $0.position < snap.treeSize &&
                    ($0.spentHeight == nil || snap.height <= 0 || $0.spentHeight! >= UInt64(snap.height)) &&
                    !voted(proposalID, PrivacyHash.voteNF(nk: nk, rho: $0.rho, position: $0.position, proposalID: proposalID))
            }
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

    /// This wallet's weight on `proposalID`: every derth note that can still
    /// stake-vote on it (at its rounded `voteWeight`) and every position
    /// created before the snapshot's block, each at its validator's rate at
    /// the snapshot (1 where it names none).
    public func stakeVoteWeight(proposalID: UInt64, positions: [PrivacyReads.Position]) async throws -> StakeWeight {
        let snap = try await snapshot(proposalID: proposalID)
        let notes = await eligible(proposalID, snap)
        let ps = Self.votingPositions(positions, snapshot: snap)
        var total: UInt64 = 0
        for n in notes {
            let w = (try? Self.voteWeight(n.amount)) ?? 0
            total = PrivateMsgs.saturatingAdd(total, Self.derthValue(w, rate: snap.rates[(try? Self.parseDerth(n.denom)) ?? ""] ?? 1))
        }
        for p in ps { total = PrivateMsgs.saturatingAdd(total, Self.derthValue(p.derth, rate: snap.rates[p.validator] ?? 1)) }
        return StakeWeight(notes: notes.count, positionIDs: Set(ps.map(\.id)), uerth: total)
    }

    /// One cast of a stake vote: one derth note, or a position.
    public enum StakeVoteItem: Sendable, Equatable {
        case note(UInt64)
        case position(id: UInt64, counter: UInt32)
    }

    /// Every cast a stake vote on `proposalID` takes (K5): each eligible derth
    /// note on its own, and every position of ours that may vote (created
    /// before the snapshot's block). Cast them through `StakeVoteController`,
    /// the one path the app uses: one at a time, a sync and a random pause
    /// between.
    public func stakeVoteItems(proposalID: UInt64) async throws -> [StakeVoteItem] {
        let snap = try await snapshot(proposalID: proposalID)
        var out: [StakeVoteItem] = await eligible(proposalID, snap).map { .note($0.position) }
        let mine = try await positions()
        let voting = Set(Self.votingPositions(mine.map(\.position), snapshot: snap).map(\.id))
        for p in mine where voting.contains(p.position.id) { out.append(.position(id: p.position.id, counter: p.counter)) }
        return out
    }

    /// Casts `item` as the last sync left things; nil when there is nothing
    /// left of it to cast (the note already voted on this proposal, or was
    /// spent before its snapshot).
    public func castStakeVote(proposalID: UInt64, item: StakeVoteItem, options: [WeightedVoteOption]) async throws -> TxResult? {
        switch item {
        case let .note(position):
            guard let n = snapshot.stakeNotes.first(where: { $0.position == position }) else { return nil }
            do {
                return try await stakeVote(proposalID: proposalID, note: n, options: options)
            } catch is AlreadyVoted {
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
        // A restored wallet knows the closed positions' counters from their unlock memos (K11).
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
            let denom = Self.derthDenom(validator)
            let ins = try StakeSelection.cover(spendableStake(denom), amount: amount)
            // A restored wallet's counter starts past every tag it already holds.
            _ = positionsLocked(all)
            let counter = store.mutate { s -> UInt32 in
                let c = s.nextOtagCounter
                s.nextOtagCounter += 1
                return c
            }
            try store.save()
            let stake = try stakePlan(denom, spends: ins, outAmounts: [try Self.sum(ins) - amount], vOut: amount,
                                      salt: keys.otagSalt(counter))
            let w = Self.weights(splits)
            return try await run { fee in
                Assembled(bundles: [try self.feeBundle(fee, maxActions: mx)], stake: stake) { bs, sp, _ in
                    MsgLockPosition(bundle: bs[0], validator: validator, amount: amount, splits: w, stake: sp!)
                }
            }
        }
    }

    private func ownerPlan(_ position: PrivacyReads.Position, counter: UInt32, mint: StakePlan.SelfMint? = nil) throws -> StakePlan {
        guard keys.ownerTag(counter) == position.ownerTag else { throw PrivacyError("position \(position.id) is not owned by tag \(counter)") }
        return try stakePlan(nil, spends: [], outAmounts: [], vOut: 0, mint: mint, salt: keys.otagSalt(counter))
    }

    public func updatePosition(_ position: PrivacyReads.Position, counter: UInt32, splits: [UInt64: UInt64]) async throws -> TxResult {
        let mx = await maxActions()
        return try await locked {
            let stake = try ownerPlan(position, counter: counter)
            let w = Self.weights(splits)
            return try await run { fee in
                Assembled(bundles: [try self.feeBundle(fee, maxActions: mx)], stake: stake) { bs, sp, _ in
                    MsgUpdatePosition(bundle: bs[0], positionID: position.id, splits: w, stake: sp!)
                }
            }
        }
    }

    /// Closes `position`; its derth comes back as a stake note to our stake
    /// self-mint pc, whose memo names the closed counter (K11) so no restore
    /// ever locks under its tag again.
    public func unlockPosition(_ position: PrivacyReads.Position, counter: UInt32) async throws -> TxResult {
        let mx = await maxActions()
        return try await locked {
            let stake = try ownerPlan(position, counter: counter,
                                      mint: try StakePlan.selfMint(keys, memo: WalletSync.unlockMemo(nk: keys.nk, counter: counter)))
            return try await run { fee in
                Assembled(bundles: [try self.feeBundle(fee, maxActions: mx)], stake: stake) { bs, sp, _ in
                    MsgUnlockPosition(bundle: bs[0], positionID: position.id, stake: sp!)
                }
            }
        }
    }

    public func positionVote(_ position: PrivacyReads.Position, counter: UInt32, proposalID: UInt64, options: [WeightedVoteOption]) async throws -> TxResult {
        let mx = await maxActions()
        return try await locked {
            let stake = try ownerPlan(position, counter: counter)
            return try await run { fee in
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

    /// The output for a MsgBuyAnml (signed by the caller's transparent key).
    public func buyAnmlOutput(to: ShieldedAddress? = nil) async throws -> NoteOut {
        try await locked { try payout("uanml", to: to) }
    }

    // MARK: - automation bookkeeping

    /// Records a refused unbonding claim so the automation waits before retrying.
    public func deferUnbondClaim(denom: String, until: Int64) async {
        await locked {
            store.mutate { $0.unbondRetryAt[denom] = until }
            persistNoThrow()
        }
    }

    // MARK: - saving

    /// Saves where nothing can be thrown (a broadcast's acceptance callback):
    /// a failure is kept and shown (`Snapshot.saveError`), never dropped
    /// (audit 3). The next save that succeeds clears it.
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
