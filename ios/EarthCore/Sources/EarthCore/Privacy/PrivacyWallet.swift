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
    /// A private tx's gas for the confirm sheet's estimate: a two-action
    /// bundle and a stake proof plus note writes and size. The exact figure
    /// comes from simulating at confirm time.
    public static let privateGasEstimate: UInt64 = 5_000_000
    /// Slack against the chain's clock for bounds the wallet must stay under.
    public static let clockMargin: Int64 = 600
    /// x/shielded's default max_actions_per_bundle, until the chain is read.
    public static let defaultMaxActions = 16

    public static let fee = "uerth"
    public static let derthPrefix = "derth/"
    public static let unbondPrefix = "unbond/"
    public static let lpPrefix = "dexlp/"

    public let keys: PrivacyKeys
    let store: PrivacyStore
    private let indexer: PrivacyIndexer
    private let chain: PrivateChain
    private let reads: PrivacyChainReads
    public let chainID: String
    private let now: @Sendable () -> Int64
    private let engine: PrivateTxEngine
    private let mutex = AsyncMutex()

    public init(keys: PrivacyKeys, store: PrivacyStore, indexer: PrivacyIndexer, chain: PrivateChain, reads: PrivacyChainReads,
                prover: PrivacyProver, chainID: String, now: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970) }) {
        self.keys = keys; self.store = store; self.indexer = indexer; self.chain = chain; self.reads = reads
        self.chainID = chainID; self.now = now
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
        /// x/shielded max_actions_per_bundle: the most notes (and outputs) one bundle carries.
        public let maxActions: Int

        init(store: PrivacyStore, keys: PrivacyKeys, maxActions: Int) {
            let s = store.state
            notes = s.notes; stakeNotes = s.stakeNotes; identity = s.identity; claimedDays = s.claimedDays
            caretakerSplit = s.caretakerSplit; caretakerCastAt = s.caretakerCastAt
            referrerAddress = s.referrerAddress; referrerBoundAt = s.referrerBoundAt
            unbondRetryAt = s.unbondRetryAt; syncedHeight = s.notesHeight
            identityStatus = WalletSync.identityStatus(store: store, keys: keys)
            self.maxActions = maxActions
        }

        /// Spendable pool balance per denom (pending spends excluded).
        public var poolBalances: [String: UInt64] {
            var out: [String: UInt64] = [:]
            for n in notes where n.unspent && n.pendingAt == nil { out[n.note.denom, default: 0] += n.note.value }
            return out
        }

        /// Stake (derth/<valoper>) and unbonding claims (unbond/<valoper>/<epoch>) per denom: owner-locked, never sendable.
        public var stakeBalances: [String: UInt64] {
            var out: [String: UInt64] = [:]
            for n in stakeNotes where n.spendable { out[n.denom, default: 0] += n.amount }
            return out
        }

        /// Everything held privately: the pool's denoms and the stake denoms.
        public var balances: [String: UInt64] { poolBalances.merging(stakeBalances) { $0 + $1 } }

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

    public var snapshot: Snapshot {
        snapLock.lock(); defer { snapLock.unlock() }
        return snapshotValue
    }

    private func publish() {
        snapLock.lock()
        let m = maxActionsValue
        snapLock.unlock()
        let s = Snapshot(store: store, keys: keys, maxActions: m)
        snapLock.lock(); snapshotValue = s; snapLock.unlock()
    }

    /// Runs `body` under the wallet's lock, then republishes the snapshot.
    private func locked<T>(_ body: () async throws -> T) async rethrows -> T {
        try await mutex.withLock {
            defer { publish() }
            return try await body()
        }
    }

    public var notes: [OwnedNote] { snapshot.notes }
    public var stakeNotes: [OwnedStakeNote] { snapshot.stakeNotes }
    public func balances() -> [String: UInt64] { snapshot.balances }
    public func poolBalances() -> [String: UInt64] { snapshot.poolBalances }
    public func stakeBalances() -> [String: UInt64] { snapshot.stakeBalances }
    public func mergeable() -> [String: Int] { snapshot.mergeable }
    public func identityStatus() -> WalletSync.IdentityStatus { snapshot.identityStatus }

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
        return try await locked { try await syncLocked() }
    }

    private func syncLocked() async throws -> WalletSync.Result {
        try await WalletSync(indexer: indexer, store: store, keys: keys, chainID: chainID, now: now).sync()
    }

    /// Allocates the next self-mint pc (see PrivacyKeys.mintSecrets).
    private func mint(_ denom: String) -> NoteOut {
        let c = store.mutate { s -> UInt32 in
            let c = s.nextMintCounter
            s.nextMintCounter += 1
            return c
        }
        store.save()
        return NoteOut.mintToSelf(keys, denom: denom, counter: c)
    }

    /// Allocates the next stake self-mint (PrivacyKeys.stakeMintSecrets): a spc_mint the chain mints to.
    private func stakeMint() -> (rho: Fr, rcm: Fr) {
        let c = store.mutate { s -> UInt32 in
            let c = s.nextStakeMintCounter
            s.nextStakeMintCounter += 1
            return c
        }
        store.save()
        return keys.stakeMintSecrets(c)
    }

    private func today() -> UInt64 { UInt64(max(0, now()) / Self.secondsPerDay) }

    // MARK: - running

    private func run(_ assemble: (UInt64) throws -> Assembled) async throws -> TxResult {
        let (result, a) = try await engine.run(assemble)
        markPending(a.spends, a.stakeSpends)
        return result
    }

    private func markPending(_ spent: [OwnedNote], _ stake: [OwnedStakeNote]) {
        let positions = Set(spent.map(\.position))
        let stakePositions = Set(stake.map(\.position))
        let t = now()
        store.mutate { s in
            for i in s.notes.indices where positions.contains(s.notes[i].position) { s.notes[i].pendingAt = t }
            for i in s.stakeNotes.indices where stakePositions.contains(s.stakeNotes[i].position) { s.stakeNotes[i].pendingAt = t }
        }
        store.save()
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
        if id.activatedAt > maxActivation { throw NotYet(waitSeconds: Int64(id.activatedAt - maxActivation)) }
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
    public func unshield(receiver: String, denom: String, amount: UInt64, feeFromAmount: Bool = false) async throws -> TxResult {
        try Self.requireTransferable(denom)
        try require(!denom.hasPrefix(Self.lpPrefix), "LP shares leave the pool only by a withdrawal")
        try require(!feeFromAmount || denom == Self.fee, "only an ERTH unshield pays its fee from the amount")
        let m = await maxActions()
        return try await locked {
            try await run { fee in
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
                    try require(ns.reduce(0) { $0 + $1.note.value } > fee, "these notes do not cover the \(fee)uerth fee")
                }
                let b = try self.bundle(release: [Self.fee: fee], forced: ns, maxActions: m)
                return Assembled(bundles: [b]) { bs, _, _ in MsgSend(bundle: bs[0], fee: fee) }
            }
        }
    }

    /// A note to self for MsgShield (transparent coins into the pool; signed,
    /// so built by the caller's key).
    public func shieldOutput(denom: String, amount: UInt64) throws -> NoteOut { try NoteOut.toSelf(keys, denom: denom, value: amount) }

    static func requireTransferable(_ denom: String) throws {
        try require(!denom.hasPrefix(derthPrefix) && !denom.hasPrefix(unbondPrefix), "stake is owner-locked: it cannot be sent or unshielded")
    }

    // MARK: - personhood

    /// The notes a registration pays, and the binding its passport proof carries. Hold until `register`.
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
            let anml = try NoteOut.toSelf(keys, denom: "uanml", value: Self.anmlPerClaim)
            // The reward's value is the chain's to decide: minted at its public amount.
            let erth = mint("uerth")
            let gas = mint("uerth")
            let binding = PrivacyHash.registrationBinding(idc: keys.idc, pcAnml: anml.pc, pcErth: erth.pc,
                                                          affiliate: try PrivateMsgs.affiliateField(aff))
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
    /// grant's note, on a first registration), and records the identity
    /// leaf it wrote. `publicSignals` are the passport proof's: [current_date,
    /// address, nullifier, dsc_key].
    public func register(_ prep: RegistrationPrep, proof: Data, publicSignals: [String], signatureAlgorithm: String, dscDer: Data) async throws -> TxResult {
        let mx = await maxActions()
        return try await locked {
            try require(publicSignals.count == 4, "a passport proof has four public signals")
            try require(try PrivateMsgs.decimalField(publicSignals[1]) == prep.binding, "the passport proof is bound to other notes")
            let base = registerMsg(prep, proof: proof, publicSignals: publicSignals, signatureAlgorithm: signatureAlgorithm, dscDer: dscDer)
            let result = try await run { fee in
                Assembled(bundles: [try self.feeBundle(fee, maxActions: mx)]) { bs, _, _ in
                    var m = base
                    m.fee = bs[0]
                    return m
                }
            }
            try await recordRegistrationLocked(result, dscKey: try PrivateMsgs.decimalField(publicSignals[3]), passportNullifier: publicSignals[2])
            return result
        }
    }

    /// The leaf the registration appended: its index from the tx's register
    /// event, activated_at the block time. The country (the verifying CSCA's,
    /// which the chain records) is found by recomputing the leaf for every
    /// ISO alpha-2 code and unknown, against the leaf the indexer serves at
    /// that index: no query names this registration.
    public func recordRegistration(_ result: TxResult, dscKey: Fr, passportNullifier: String) async throws {
        try await locked { try await recordRegistrationLocked(result, dscKey: dscKey, passportNullifier: passportNullifier) }
    }

    private func recordRegistrationLocked(_ result: TxResult, dscKey: Fr, passportNullifier: String) async throws {
        guard let index = result.attr("register", "leaf_index").flatMap(UInt64.init) else {
            throw PrivacyError("registration tx \(result.hash) has no leaf_index")
        }
        _ = try await syncLocked()
        try require(index < store.identityTree.size, "the indexer has not seen leaf \(index) yet")
        let leaf = store.identityTree.leaf(index)
        let activatedAt = UInt64(max(0, result.time))
        guard let country = countryFor(leaf: leaf, dscKey: dscKey, activatedAt: activatedAt) else {
            throw PrivacyError("leaf \(index) does not match this registration")
        }
        store.mutate { $0.identity = IdentityRecord(leafIndex: index, dscKey: dscKey, country: country, activatedAt: activatedAt,
                                                     passportNullifier: passportNullifier) }
        store.save()
    }

    private func countryFor(leaf: Fr, dscKey: Fr, activatedAt: UInt64) -> Fr? {
        var candidates: [Fr] = [.zero]
        let az = (UInt8(ascii: "A") ... UInt8(ascii: "Z"))
        for a in az { for b in az { candidates.append(PrivacyHash.countryField(String(bytes: [a, b], encoding: .ascii)!)) } }
        return candidates.first { PrivacyHash.identityLeaf(idc: keys.idc, dscKey: dscKey, country: $0, activatedAt: activatedAt) == leaf }
    }

    /// Today's ANML. Opens once the identity was activated before yesterday began.
    public func claimAnml(day: UInt64? = nil) async throws -> TxResult {
        let mx = await maxActions()
        return try await locked {
            let day = day ?? today()
            let anml = try NoteOut.toSelf(keys, denom: "uanml", value: Self.anmlPerClaim)
            let m = try membership(scope: PrivacyHash.claimScope(day: day), excludedDsc: .zero, excludedCountry: .zero,
                                   maxActivation: (day - 1) * UInt64(Self.secondsPerDay))
            let r = try await run { fee in
                Assembled(bundles: [try self.feeBundle(fee, maxActions: mx)], membership: m) { bs, _, mem in
                    MsgClaimAnmlPrivate(fee: bs[0], membership: mem!, day: day, pc: anml.pc.bytes, ciphertext: anml.ciphertext)
                }
            }
            store.mutate { _ = $0.claimedDays.insert(day) }
            store.save()
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
        let a = Int64(id.activatedAt)
        let firstDay = a / Self.secondsPerDay + 1 + (a % Self.secondsPerDay == 0 ? 0 : 1)
        let t = Int64(today())
        let day = max(t + (snap.claimedDays.contains(UInt64(t)) ? 1 : 0), firstDay)
        return day == t ? 0 : day * Self.secondsPerDay
    }

    /// The max_activation a caretaker split or referrer binding names: at most
    /// now - R - root window (the chain's bound), rounded down to the hour so it
    /// says nothing about when the tx was made, less a margin for clock skew.
    private func leaseBound() async throws -> UInt64 {
        let p = try await reads.personhoodParams()
        let bound = now() - p.caretakerVoteSeconds - p.identityRootWindowSeconds - Self.clockMargin
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
            store.save()
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
    public func bindReferrer(address: String) async throws -> TxResult {
        let mx = await maxActions()
        return try await locked {
            let maxAct = try await leaseBound()
            let m = try membership(scope: PrivacyHash.referrerScope(), excludedDsc: .zero, excludedCountry: .zero, maxActivation: maxAct)
            let r = try await run { fee in
                Assembled(bundles: [try self.feeBundle(fee, maxActions: mx)], membership: m) { bs, _, mem in
                    MsgBindReferrer(fee: bs[0], membership: mem!, address: address, maxActivation: maxAct)
                }
            }
            store.mutate { $0.referrerAddress = address; $0.referrerBoundAt = address.isEmpty ? 0 : now() }
            store.save()
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
            let window = try await reads.personhoodParams().identityRootWindowSeconds
            let maxAct = UInt64(max(0, Int64(day) * Self.secondsPerDay - window))
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
                           mint: (rho: Fr, rcm: Fr) = StakePlan.throwaway(), salt: Fr = StakePlan.throwaway().rho,
                           anchor: Fr? = nil, paths: [[Fr]]? = nil) throws -> StakePlan {
        let outs = try outAmounts.filter { $0 > 0 }.map { try StakePlan.out(keys, denom: denom!, amount: $0) }
        return try StakePlan(nk: keys.nk, denom: denom, spends: spends, paths: paths ?? spends.map { store.stakeTree.path($0.position) },
                             outputs: outs, mint: mint, tagSalt: salt, anchor: anchor ?? stakeAnchor(), vOut: vOut)
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
            let stake = try stakePlan(Self.derthDenom(validator), spends: [], outAmounts: [], vOut: 0, mint: stakeMint())
            return try await run { fee in
                let b = try self.bundle(release: try Self.plus([Self.fee: amount], Self.fee, fee), maxActions: mx)
                return Assembled(bundles: [b], stake: stake) { bs, sp, _ in
                    MsgShieldedDelegate(bundle: bs[0], validator: validator, fee: fee, stake: sp!)
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
        try require(notes.reduce(0) { $0 + $1.amount } == amounts.reduce(0, +), "a restake keeps the amount")
        let stake = try stakePlan(denom, spends: notes, outAmounts: amounts, vOut: 0)
        return try await run { fee in
            Assembled(bundles: [try self.feeBundle(fee, maxActions: mx)], stake: stake) { bs, sp, _ in
                MsgRestake(bundle: bs[0], validator: validator, fee: fee, stake: sp!)
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
            let top2 = ns.map(\.amount).sorted(by: >).prefix(2).reduce(0, +)
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
            return try await restakeLocked(validator: try Self.parseDerth(denom), notes: two, amounts: [two[0].amount + two[1].amount], mx: mx)
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
            let stake = try stakePlan(denom, spends: ins, outAmounts: [ins.reduce(0) { $0 + $1.amount } - amount], vOut: amount, mint: stakeMint())
            return try await run { fee in
                Assembled(bundles: [try self.feeBundle(fee, maxActions: mx)], stake: stake) { bs, sp, _ in
                    MsgShieldedUndelegate(bundle: bs[0], validator: validator, amount: amount, fee: fee, stake: sp!)
                }
            }
        }
    }

    /// Claims matured unbonding claims of `denom` (unbond/<valoper>/<epoch>,
    /// up to two notes a claim): the chain mints their ERTH to our self-mint
    /// pc in the pool, the fee out of it (fee_from_output), so the msg
    /// carries no bundle at all.
    public func claimUnbonding(denom: String) async throws -> TxResult {
        try await locked {
            let (validator, epoch) = try Self.parseUnbond(denom)
            let ins = Array(spendableStake(denom).sorted { $0.amount > $1.amount }.prefix(2))
            try require(!ins.isEmpty, "no unbonding claim of \(denom)")
            let amount = ins.reduce(0) { $0 + $1.amount }
            let stake = try stakePlan(denom, spends: ins, outAmounts: [], vOut: amount)
            let erth = mint(Self.fee)
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

    /// Spend-to-vote: `notes` (1-2 derth notes of one validator, in the stake
    /// tree at the proposal's snapshot) are spent against the snapshot root,
    /// their sum is the vote's weight, and the chain re-mints it to our stake
    /// self-mint pc. The fee bundle is against the pool's current roots.
    public func stakeVote(proposalID: UInt64, notes: [OwnedStakeNote], options: [WeightedVoteOption]) async throws -> TxResult {
        let mx = await maxActions()
        return try await locked { try await stakeVoteLocked(proposalID: proposalID, notes: notes, options: options, mx: mx) }
    }

    private func stakeVoteLocked(proposalID: UInt64, notes: [OwnedStakeNote], options: [WeightedVoteOption], mx: Int) async throws -> TxResult {
        try require((1 ... 2).contains(notes.count), "a stake vote spends one or two notes")
        let denom = notes[0].denom
        try require(notes.allSatisfy { $0.denom == denom }, "one validator per stake vote")
        let validator = try Self.parseDerth(denom)
        let snap = try await reads.snapshot(proposalID: proposalID)
        try require(notes.allSatisfy { $0.position < snap.treeSize }, "this stake arrived after the proposal's snapshot and cannot vote on it")
        let tree = store.stakeTree
        guard tree.rootAt(snap.treeSize) == snap.root else {
            throw PrivacyError("the local stake tree disagrees with the proposal's snapshot root")
        }
        let weight = notes.reduce(0) { $0 + $1.amount }
        let stake = try stakePlan(denom, spends: notes, outAmounts: [], vOut: weight, mint: stakeMint(), anchor: snap.root,
                                  paths: notes.map { tree.pathAt($0.position, size: snap.treeSize) })
        return try await run { fee in
            Assembled(bundles: [try self.feeBundle(fee, maxActions: mx)], stake: stake) { bs, sp, _ in
                MsgStakeVote(bundle: bs[0], proposalID: proposalID, validator: validator, options: options, weight: weight, fee: fee, stake: sp!)
            }
        }
    }

    private static func eligible(_ notes: [OwnedStakeNote], _ snap: PrivacyReads.Snapshot) -> [OwnedStakeNote] {
        notes.filter { $0.spendable && $0.denom.hasPrefix(derthPrefix) && $0.position < snap.treeSize }
    }

    /// The derth notes that can stake-vote on `proposalID`: unspent, and in
    /// the stake tree at its snapshot.
    public func stakeVoteNotes(proposalID: UInt64) async throws -> [OwnedStakeNote] {
        Self.eligible(snapshot.stakeNotes, try await reads.snapshot(proposalID: proposalID))
    }

    /// The positions that can vote on `proposalID`: created before the block
    /// it entered voting at (the chain refuses later ones).
    public static func votingPositions(_ positions: [PrivacyReads.Position], snapshot: PrivacyReads.Snapshot) -> [PrivacyReads.Position] {
        positions.filter { snapshot.height == 0 || Int64($0.createdHeight) < snapshot.height }
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
        return UInt64(truncating: floored as NSNumber)
    }

    /// This wallet's weight on `proposalID`: every derth note that can
    /// stake-vote and every position created before the snapshot's block,
    /// each at its validator's rate at the snapshot (1 where it names none).
    public func stakeVoteWeight(proposalID: UInt64, positions: [PrivacyReads.Position]) async throws -> StakeWeight {
        let snap = try await reads.snapshot(proposalID: proposalID)
        let notes = Self.eligible(snapshot.stakeNotes, snap)
        let ps = Self.votingPositions(positions, snapshot: snap)
        var total: UInt64 = 0
        for n in notes { total += Self.derthValue(n.amount, rate: snap.rates[(try? Self.parseDerth(n.denom)) ?? ""] ?? 1) }
        for p in ps { total += Self.derthValue(p.derth, rate: snap.rates[p.validator] ?? 1) }
        return StakeWeight(notes: notes.count, positionIDs: Set(ps.map(\.id)), uerth: total)
    }

    /// Votes every eligible derth note on `proposalID`, two notes of one
    /// validator a tx. Final: the spent nullifiers and the re-minted notes'
    /// absence from the snapshot root stop a second vote.
    public func stakeVoteAll(proposalID: UInt64, options: [WeightedVoteOption]) async throws -> [TxResult] {
        let ns = try await stakeVoteNotes(proposalID: proposalID)
        try require(!ns.isEmpty, "no stake from before this proposal's snapshot")
        var byDenom: [String: [OwnedStakeNote]] = [:]
        var order: [String] = []
        for n in ns {
            if byDenom[n.denom] == nil { order.append(n.denom) }
            byDenom[n.denom, default: []].append(n)
        }
        var out: [TxResult] = []
        for d in order {
            let group = byDenom[d]!
            for i in stride(from: 0, to: group.count, by: 2) {
                out.append(try await stakeVote(proposalID: proposalID, notes: Array(group[i ..< min(i + 2, group.count)]), options: options))
            }
        }
        return out
    }

    // MARK: - Groundworks positions

    /// This wallet's Groundworks positions: the public positions whose owner
    /// tag is one of ours (owner-tag counters 0 ... last+gap, so a wallet
    /// restored from the mnemonic finds them too), each with its counter.
    public func positions() async throws -> [(position: PrivacyReads.Position, counter: UInt32)] {
        let all = try await reads.positions()
        return await locked {
            let next = store.state.nextOtagCounter
            var mine: [Fr: UInt32] = [:]
            for c in 0 ..< next + WalletSync.mintGap { mine[keys.ownerTag(c)] = c }
            let out = all.compactMap { p in mine[p.ownerTag].map { (position: p, counter: $0) } }
            if let top = out.map(\.counter).max(), top + 1 > next {
                store.mutate { $0.nextOtagCounter = top + 1 }
                store.save()
            }
            return out
        }
    }

    /// Locks `amount` derth/`validator` into a new position split by `splits`, under a fresh owner tag.
    public func lockPosition(validator: String, amount: UInt64, splits: [UInt64: UInt64]) async throws -> TxResult {
        let mx = await maxActions()
        return try await locked {
            let denom = Self.derthDenom(validator)
            let ins = try StakeSelection.cover(spendableStake(denom), amount: amount)
            let counter = store.mutate { s -> UInt32 in
                let c = s.nextOtagCounter
                s.nextOtagCounter += 1
                return c
            }
            store.save()
            let stake = try stakePlan(denom, spends: ins, outAmounts: [ins.reduce(0) { $0 + $1.amount } - amount], vOut: amount,
                                      salt: keys.otagSalt(counter))
            let w = Self.weights(splits)
            return try await run { fee in
                Assembled(bundles: [try self.feeBundle(fee, maxActions: mx)], stake: stake) { bs, sp, _ in
                    MsgLockPosition(bundle: bs[0], validator: validator, amount: amount, splits: w, fee: fee, stake: sp!)
                }
            }
        }
    }

    private func ownerPlan(_ position: PrivacyReads.Position, counter: UInt32, mint: (rho: Fr, rcm: Fr)? = nil) throws -> StakePlan {
        guard keys.ownerTag(counter) == position.ownerTag else { throw PrivacyError("position \(position.id) is not owned by tag \(counter)") }
        return try stakePlan(nil, spends: [], outAmounts: [], vOut: 0, mint: mint ?? StakePlan.throwaway(), salt: keys.otagSalt(counter))
    }

    public func updatePosition(_ position: PrivacyReads.Position, counter: UInt32, splits: [UInt64: UInt64]) async throws -> TxResult {
        let mx = await maxActions()
        return try await locked {
            let stake = try ownerPlan(position, counter: counter)
            let w = Self.weights(splits)
            return try await run { fee in
                Assembled(bundles: [try self.feeBundle(fee, maxActions: mx)], stake: stake) { bs, sp, _ in
                    MsgUpdatePosition(bundle: bs[0], positionID: position.id, splits: w, fee: fee, stake: sp!)
                }
            }
        }
    }

    /// Closes `position`; its derth comes back as a stake note to our stake self-mint pc.
    public func unlockPosition(_ position: PrivacyReads.Position, counter: UInt32) async throws -> TxResult {
        let mx = await maxActions()
        return try await locked {
            let stake = try ownerPlan(position, counter: counter, mint: stakeMint())
            return try await run { fee in
                Assembled(bundles: [try self.feeBundle(fee, maxActions: mx)], stake: stake) { bs, sp, _ in
                    MsgUnlockPosition(bundle: bs[0], positionID: position.id, fee: fee, stake: sp!)
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
                    MsgPositionVote(bundle: bs[0], positionID: position.id, proposalID: proposalID, options: options, fee: fee, stake: sp!)
                }
            }
        }
    }

    // MARK: - dex

    /// Swaps `amountIn` `denomIn` from notes for at least `minOut` `denomOut`
    /// (any pools, through the ERTH hub), the output minted to us or to `to`.
    /// Its value is the pool's to decide: to us a self-mint found by its
    /// public amount, to anyone else a value-blind (v2) ciphertext. A swap
    /// into ERTH pays its fee out of the output (fee_from_output; the chain
    /// needs min_out above the fee); any other pays from the bundle's ERTH
    /// balance.
    public func noteSwap(denomIn: String, amountIn: UInt64, denomOut: String, minOut: UInt64, to: ShieldedAddress? = nil) async throws -> TxResult {
        try require(denomIn != denomOut && amountIn > 0 && minOut > 0, "a swap needs two denoms and positive amounts")
        try Self.requireTransferable(denomIn)
        let mx = await maxActions()
        return try await locked {
            let out = try payout(denomOut, to: to)
            return try await run { fee in
                if denomOut == Self.fee {
                    try require(minOut > fee, "the minimum received must exceed the \(fee)uerth fee paid from it")
                    let b = try self.bundle(release: [denomIn: amountIn], maxActions: mx)
                    return Assembled(bundles: [b]) { bs, _, _ in
                        MsgNoteSwap(bundle: bs[0], denomOut: denomOut, minAmountOut: minOut, pc: out.pc.bytes, ciphertext: out.ciphertext,
                                    feeFromOutput: fee, fee: 0)
                    }
                }
                let b = try self.bundle(release: try Self.plus([denomIn: amountIn], Self.fee, fee), maxActions: mx)
                return Assembled(bundles: [b]) { bs, _, _ in
                    MsgNoteSwap(bundle: bs[0], denomOut: denomOut, minAmountOut: minOut, pc: out.pc.bytes, ciphertext: out.ciphertext,
                                feeFromOutput: 0, fee: fee)
                }
            }
        }
    }

    /// Deposits `tokenAmount` `token` and `erthAmount` uerth from notes into
    /// `poolID`, in one bundle (its token balance the token leg, its uerth
    /// balance less the fee the ERTH leg). The LP shares are private: minted
    /// as a dexlp/<pool> note to our self-mint pc. Whatever the pool ratio
    /// does not take is minted back to one self-mint pc (a note per asset).
    public func addLiquidityShielded(poolID: UInt64, token: String, tokenAmount: UInt64, erthAmount: UInt64,
                                     minShares: String) async throws -> TxResult {
        try require(tokenAmount > 0 && erthAmount > 0 && token != Self.fee, "both legs must be positive")
        let mx = await maxActions()
        return try await locked {
            let refund = mint(token)
            let shares = mint(Self.lpDenom(poolID))
            return try await run { fee in
                let b = try self.bundle(release: try Self.plus([token: tokenAmount, Self.fee: erthAmount], Self.fee, fee), maxActions: mx)
                return Assembled(bundles: [b]) { bs, _, _ in
                    MsgAddLiquidityShielded(bundle: bs[0], poolID: poolID, minShares: minShares, refundPC: refund.pc.bytes,
                                            refundCiphertext: refund.ciphertext, fee: fee, sharePC: shares.pc.bytes,
                                            shareCiphertext: shares.ciphertext)
                }
            }
        }
    }

    /// Withdraws `shares` dexlp/`poolID` from share notes: escrowed for the
    /// dex's LP unbonding period with no account named, then both legs (ERTH
    /// and `token`) minted to our self-mint pcs as notes.
    public func removeLiquidityShielded(poolID: UInt64, token: String, shares: UInt64) async throws -> TxResult {
        try require(shares > 0, "the shares must be positive")
        let mx = await maxActions()
        return try await locked {
            let erth = mint(Self.fee)
            let tok = mint(token)
            return try await run { fee in
                let b = try self.bundle(release: [Self.lpDenom(poolID): shares, Self.fee: fee], maxActions: mx)
                return Assembled(bundles: [b]) { bs, _, _ in
                    MsgRemoveLiquidityShielded(bundle: bs[0], poolID: poolID, fee: fee, erthPC: erth.pc.bytes, erthCiphertext: erth.ciphertext,
                                               tokenPC: tok.pc.bytes, tokenCiphertext: tok.ciphertext)
                }
            }
        }
    }

    /// Private LP shares per pool id (dexlp/<id> notes).
    public func lpShares() -> [UInt64: UInt64] { snapshot.lpShares }

    /// The pc a pool-1 MsgRemoveLiquidity names for its ANML leg (signed by
    /// the provider's transparent key): a self-mint, since the payout is
    /// priced when the withdrawal matures.
    public func withdrawalPC() async throws -> Data {
        try await locked { try payout("uanml", to: nil).pc.bytes }
    }

    /// Where a chain-priced payment goes (a swap's or a MsgBuyAnml's output,
    /// a withdrawal's token leg): to us, a self-mint pc; to `to`, `to`'s pc
    /// with a value-blind (v2) ciphertext it can open once the chain
    /// publishes the amount.
    private func payout(_ denom: String, to: ShieldedAddress?) throws -> NoteOut {
        if let to, to.ownerPK != keys.ownerPK { return try NoteOut.blindTo(to, denom: denom) }
        return mint(denom)
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
            store.save()
        }
    }

    /// The membership witness for a transparent gas grant (GasTransparent),
    /// against the synced identity tree.
    func gasWitness(scope: Fr, signal: Fr, maxActivation: UInt64) async throws -> MembershipWitness {
        try await locked {
            let w = try membership(scope: scope, excludedDsc: .zero, excludedCountry: .zero, maxActivation: maxActivation).witness(signal: signal)
            try w.check()
            return w
        }
    }
}
