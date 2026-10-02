import Foundation

/// One wallet's private side: its notes and registration, kept in sync with
/// the chain through the indexer, and every private action the chain offers,
/// built, proven and broadcast as an unsigned tx whose fee comes out of a
/// shielded ERTH note. Ports `privacy/PrivacyWallet.kt` method for method.
///
/// Every public operation holds one lock for its whole run (the Kotlin side's
/// `@Synchronized`), so a sync never interleaves with a spend. Screens read
/// `snapshot`, which is replaced after each operation and never torn.
public final class PrivacyWallet: @unchecked Sendable {
    public static let secondsPerDay: Int64 = 86_400
    public static let anmlPerClaim: UInt64 = 1_000_000
    /// MsgRegister's gas, for the fee estimate before simulating: the passport
    /// proof (3M) and DSC chain (300k), the fee transfer's proof (2M) and note
    /// writes, and the tx's bytes.
    public static let registerGasEstimate: UInt64 = 7_000_000
    /// A private tx's gas for the confirm sheet's estimate: two proofs at
    /// x/shielded's default 2M each plus note writes and size. The exact
    /// figure comes from simulating at confirm time.
    public static let privateGasEstimate: UInt64 = 5_000_000
    /// Slack against the chain's clock for bounds the wallet must stay under.
    public static let clockMargin: Int64 = 600

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
        snapshotValue = Snapshot(store: store, keys: keys)
    }

    public var address: ShieldedAddress { keys.address }

    // MARK: - snapshot

    /// What the screens show, as of the last operation.
    public struct Snapshot: Sendable {
        public let notes: [OwnedNote]
        public let identity: IdentityRecord?
        public let identityStatus: WalletSync.IdentityStatus
        public let claimedDays: Set<UInt64>
        public let caretakerSplit: [UInt64: UInt64]
        public let caretakerCastAt: Int64
        public let referrerAddress: String
        public let referrerBoundAt: Int64
        public let unbondRetryAt: [String: Int64]
        public let syncedHeight: UInt64

        init(store: PrivacyStore, keys: PrivacyKeys) {
            let s = store.state
            notes = s.notes; identity = s.identity; claimedDays = s.claimedDays
            caretakerSplit = s.caretakerSplit; caretakerCastAt = s.caretakerCastAt
            referrerAddress = s.referrerAddress; referrerBoundAt = s.referrerBoundAt
            unbondRetryAt = s.unbondRetryAt; syncedHeight = s.notesHeight
            identityStatus = WalletSync.identityStatus(store: store, keys: keys)
        }

        /// Spendable balance per denom (pending spends excluded).
        public var balances: [String: UInt64] {
            var out: [String: UInt64] = [:]
            for n in notes where n.unspent && n.pendingAt == nil { out[n.note.denom, default: 0] += n.note.value }
            return out
        }

        /// Spendable note counts per denom with more than one note.
        public var mergeable: [String: Int] {
            var counts: [String: Int] = [:]
            for n in notes where n.unspent && n.pendingAt == nil && n.note.value > 0 { counts[n.note.denom, default: 0] += 1 }
            return counts.filter { $0.value >= 2 }
        }
    }

    private let snapLock = NSLock()
    private var snapshotValue: Snapshot

    public var snapshot: Snapshot {
        snapLock.lock(); defer { snapLock.unlock() }
        return snapshotValue
    }

    private func publish() {
        let s = Snapshot(store: store, keys: keys)
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
    public func balances() -> [String: UInt64] { snapshot.balances }
    public func mergeable() -> [String: Int] { snapshot.mergeable }
    public func identityStatus() -> WalletSync.IdentityStatus { snapshot.identityStatus }

    // MARK: - sync

    @discardableResult
    public func sync() async throws -> WalletSync.Result { try await locked { try await syncLocked() } }

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

    private func today() -> UInt64 { UInt64(max(0, now()) / Self.secondsPerDay) }

    // MARK: - running

    private func run(_ assemble: (UInt64) throws -> Assembled) async throws -> TxResult {
        let (result, a) = try await engine.run(assemble)
        markPending(a.transfers.flatMap(\.spends))
        return result
    }

    private func markPending(_ spent: [OwnedNote]) {
        let positions = Set(spent.map(\.position))
        let t = now()
        store.mutate { s in for i in s.notes.indices where positions.contains(s.notes[i].position) { s.notes[i].pendingAt = t } }
        store.save()
    }

    private func feeOnly(_ fee: UInt64, exclude: Set<UInt64> = []) throws -> TransferPlan {
        try TransferPlan.feeOnly(keys: keys, tree: store.noteTree, feeNote: NoteSelection.feeNote(store.state.notes, fee: fee, exclude: exclude), fee: fee)
    }

    /// A transfer of `amount` `denom` (`outputs` + `vPubOut`) paying `fee`.
    /// ERTH: one balance over all three slots, so 1-3 notes covering amount +
    /// fee (a single note pays both). Any other asset: the fee note is chosen
    /// first (the smallest ERTH note that covers it), the inputs from the rest.
    private func spendWithFee(_ denom: String, amount: UInt64, fee: UInt64, outputs: [NoteOut], vPubOut: UInt64) throws -> TransferPlan {
        let notes = store.state.notes
        if denom == "uerth" {
            let ins = try NoteSelection.inputs(notes, denom: "uerth", amount: amount + fee, maxNotes: 3)
            return try TransferPlan.erth(keys: keys, tree: store.noteTree, notes: ins, outputs: outputs, vPubOut: vPubOut, fee: fee)
        }
        let feeNote = try NoteSelection.feeNote(notes, fee: fee)
        let inputs = try NoteSelection.inputs(notes, denom: denom, amount: amount, exclude: [feeNote.position])
        return try TransferPlan.build(keys: keys, tree: store.noteTree, denom: denom, aInputs: inputs, aOutputs: outputs,
                                      vPubOut: vPubOut, feeNote: feeNote, fee: fee)
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

    /// A private send of `amount` `denom` to `to`.
    public func send(to: ShieldedAddress, denom: String, amount: UInt64, memo: Data = Data()) async throws -> TxResult {
        try await locked {
            let out = try NoteOut.to(to, denom: denom, value: amount, memo: memo)
            return try await run { fee in
                let plan = try self.spendWithFee(denom, amount: amount, fee: fee, outputs: [out], vPubOut: 0)
                return Assembled(transfers: [plan]) { ts, _ in MsgShieldedTransfer(transfer: ts[0]) }
            }
        }
    }

    /// Unshields so that `receiver` gets exactly `amount` `denom`. The fee
    /// comes from ERTH notes: for ERTH the same notes as the amount (one
    /// balance), for any other asset a separate ERTH note.
    public func unshield(receiver: String, denom: String, amount: UInt64) async throws -> TxResult {
        try await locked {
            try await run { fee in
                let plan = try self.spendWithFee(denom, amount: amount, fee: fee, outputs: [], vPubOut: amount)
                return Assembled(transfers: [plan]) { ts, _ in MsgShieldedTransfer(transfer: ts[0], receiver: receiver) }
            }
        }
    }

    /// Consolidates `denom` (a transfer has two input slots for its asset, so
    /// a payment larger than any two notes cannot be made until small ones are
    /// merged). Any other asset: its two smallest spendable notes become one,
    /// the fee paid by a separate ERTH note. ERTH: its up to three smallest
    /// notes become one, the fee paid from them.
    public func merge(denom: String) async throws -> TxResult {
        try await locked {
            try await run { fee in
                let plan: TransferPlan
                if denom == "uerth" {
                    let ns = Array(NoteSelection.spendable(self.store.state.notes, denom: "uerth").sorted(by: NoteSelection.ascending).prefix(3))
                    try require(ns.count >= 2, "nothing to merge")
                    try require(ns.reduce(0) { $0 + $1.note.value } > fee, "these notes do not cover the \(fee)uerth fee")
                    plan = try TransferPlan.erth(keys: self.keys, tree: self.store.noteTree, notes: ns, outputs: [], vPubOut: 0, fee: fee)
                } else {
                    let feeNote = try NoteSelection.feeNote(self.store.state.notes, fee: fee)
                    let two = Array(NoteSelection.spendable(self.store.state.notes, denom: denom, exclude: [feeNote.position])
                        .sorted(by: NoteSelection.ascending).prefix(2))
                    try require(two.count == 2, "nothing to merge")
                    plan = try TransferPlan.build(keys: self.keys, tree: self.store.noteTree, denom: denom, aInputs: two, aOutputs: [],
                                                  vPubOut: 0, feeNote: feeNote, fee: fee)
                }
                return Assembled(transfers: [plan]) { ts, _ in MsgShieldedTransfer(transfer: ts[0]) }
            }
        }
    }

    /// A note to self for MsgShield (transparent coins into the pool; signed,
    /// so built by the caller's key).
    public func shieldOutput(denom: String, amount: UInt64) throws -> NoteOut { try NoteOut.toSelf(keys, denom: denom, value: amount) }

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

    /// MsgRegister without its fee transfer: what /gas/register checks.
    public func registerMsg(_ prep: RegistrationPrep, proof: Data, publicSignals: [String], signatureAlgorithm: String, dscDer: Data) -> MsgRegisterPrivate {
        MsgRegisterPrivate(fee: nil, proof: proof, publicSignals: publicSignals, signatureAlgorithm: signatureAlgorithm, dscDer: dscDer,
                           idc: prep.idc.bytes, pcAnml: prep.anml.pc.bytes, ciphertextAnml: prep.anml.ciphertext,
                           pcErth: prep.erth.pc.bytes, ciphertextErth: prep.erth.ciphertext, affiliate: prep.affiliate)
    }

    /// Broadcasts the registration, its fee paid from a shielded ERTH note
    /// (the gas grant's, on a first registration), and records the identity
    /// leaf it wrote. `publicSignals` are the passport proof's: [current_date,
    /// address, nullifier, dsc_key].
    public func register(_ prep: RegistrationPrep, proof: Data, publicSignals: [String], signatureAlgorithm: String, dscDer: Data) async throws -> TxResult {
        try await locked {
            try require(publicSignals.count == 4, "a passport proof has four public signals")
            try require(try PrivateMsgs.decimalField(publicSignals[1]) == prep.binding, "the passport proof is bound to other notes")
            let base = registerMsg(prep, proof: proof, publicSignals: publicSignals, signatureAlgorithm: signatureAlgorithm, dscDer: dscDer)
            let result = try await run { fee in
                Assembled(transfers: [try self.feeOnly(fee)]) { ts, _ in
                    var m = base
                    m.fee = ts[0]
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
        try await locked {
            let day = day ?? today()
            let anml = try NoteOut.toSelf(keys, denom: "uanml", value: Self.anmlPerClaim)
            let m = try membership(scope: PrivacyHash.claimScope(day: day), excludedDsc: .zero, excludedCountry: .zero,
                                   maxActivation: (day - 1) * UInt64(Self.secondsPerDay))
            let r = try await run { fee in
                Assembled(transfers: [try self.feeOnly(fee)], membership: m) { ts, mem in
                    MsgClaimAnmlPrivate(fee: ts[0], membership: mem!, day: day, pc: anml.pc.bytes, ciphertext: anml.ciphertext)
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
        try await locked {
            let maxAct = try await leaseBound()
            let m = try membership(scope: PrivacyHash.caretakerScope(), excludedDsc: .zero, excludedCountry: .zero, maxActivation: maxAct)
            let w = Self.weights(split)
            let r = try await run { fee in
                Assembled(transfers: [try self.feeOnly(fee)], membership: m) { ts, mem in
                    MsgSetCaretaker(fee: ts[0], membership: mem!, percentages: w, maxActivation: maxAct)
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
        try await locked {
            let maxAct = try await leaseBound()
            let m = try membership(scope: PrivacyHash.referrerScope(), excludedDsc: .zero, excludedCountry: .zero, maxActivation: maxAct)
            let r = try await run { fee in
                Assembled(transfers: [try self.feeOnly(fee)], membership: m) { ts, mem in
                    MsgBindReferrer(fee: ts[0], membership: mem!, address: address, maxActivation: maxAct)
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
        try await locked {
            let b = try await reads.ballotInputs(proposalID: proposalID, optionID: 0)
            guard b.scope == PrivacyHash.proposalScope(proposalID: proposalID, round: b.round) else {
                throw PrivacyError("the node's ballot scope is not this proposal's")
            }
            let m = try membership(scope: b.scope, excludedDsc: b.excludedDsc, excludedCountry: b.excludedCountry, maxActivation: b.maxActivation)
            return try await run { fee in
                Assembled(transfers: [try self.feeOnly(fee)], membership: m) { ts, mem in
                    MsgVoteProposalPrivate(fee: ts[0], membership: mem!, proposalID: proposalID, option: yes ? .yes : .no)
                }
            }
        }
    }

    public func proposeRemoval(optionID: UInt64) async throws -> TxResult {
        try await locked {
            let day = today()
            let window = try await reads.personhoodParams().identityRootWindowSeconds
            let maxAct = UInt64(max(0, Int64(day) * Self.secondsPerDay - window))
            let m = try membership(scope: PrivacyHash.proposeRemovalScope(optionID: optionID, day: day), excludedDsc: .zero,
                                   excludedCountry: .zero, maxActivation: maxAct)
            return try await run { fee in
                Assembled(transfers: [try self.feeOnly(fee)], membership: m) { ts, mem in
                    MsgProposeRemoval(fee: ts[0], membership: mem!, optionID: optionID)
                }
            }
        }
    }

    public func voteRemoval(optionID: UInt64, yes: Bool) async throws -> TxResult {
        try await locked {
            let b = try await reads.ballotInputs(proposalID: 0, optionID: optionID)
            guard b.scope == PrivacyHash.removalScope(ballotID: b.ballotID) else {
                throw PrivacyError("the node's ballot scope is not this ballot's")
            }
            let m = try membership(scope: b.scope, excludedDsc: b.excludedDsc, excludedCountry: b.excludedCountry, maxActivation: b.maxActivation)
            return try await run { fee in
                Assembled(transfers: [try self.feeOnly(fee)], membership: m) { ts, mem in
                    MsgVoteRemoval(fee: ts[0], membership: mem!, optionID: optionID, option: yes ? .yes : .no)
                }
            }
        }
    }

    // MARK: - private staking

    public static func derthDenom(_ valoper: String) -> String { "derth/\(valoper)" }
    public static func unbondDenom(_ valoper: String, epoch: UInt64) -> String { "unbond/\(valoper)/\(epoch)" }

    public static func parseDerth(_ denom: String) throws -> String {
        guard denom.hasPrefix("derth/") else { throw PrivacyError("not a derth note") }
        return String(denom.dropFirst("derth/".count))
    }

    public static func parseUnbond(_ denom: String) throws -> (validator: String, epoch: UInt64) {
        let parts = denom.split(separator: "/", omittingEmptySubsequences: false)
        guard parts.count == 3, parts[0] == "unbond", let e = UInt64(parts[2]) else { throw PrivacyError("not an unbond note") }
        return (String(parts[1]), e)
    }

    /// Stakes `amount` uerth with `validator`: derth minted to us at the live rate, delegated at the epoch's end.
    public func delegate(validator: String, amount: UInt64) async throws -> TxResult {
        try await locked {
            let derth = mint(Self.derthDenom(validator))
            return try await run { fee in
                let plan = try self.spendWithFee("uerth", amount: amount, fee: fee, outputs: [], vPubOut: amount)
                return Assembled(transfers: [plan]) { ts, _ in
                    MsgShieldedDelegate(transfer: ts[0], validator: validator, pc: derth.pc.bytes, ciphertext: derth.ciphertext)
                }
            }
        }
    }

    /// Turns `amount` derth/`validator` into an unbonding claim, claimable once its epoch's undelegation matures.
    public func undelegate(validator: String, amount: UInt64) async throws -> TxResult {
        try await locked {
            let unbond = mint(Self.unbondDenom(validator, epoch: try await reads.epochNumber()))
            return try await run { fee in
                let plan = try self.spendWithFee(Self.derthDenom(validator), amount: amount, fee: fee, outputs: [], vPubOut: amount)
                return Assembled(transfers: [plan]) { ts, _ in
                    MsgShieldedUndelegate(transfer: ts[0], validator: validator, pc: unbond.pc.bytes, ciphertext: unbond.ciphertext)
                }
            }
        }
    }

    /// Claims a matured unbond note; the fee comes out of the ERTH it pays.
    public func claimUnbonding(note: OwnedNote) async throws -> TxResult {
        try await locked {
            let (validator, epoch) = try Self.parseUnbond(note.note.denom)
            let erth = mint("uerth")
            return try await run { fee in
                let plan = try TransferPlan.build(keys: self.keys, tree: self.store.noteTree, denom: note.note.denom, aInputs: [note],
                                                  aOutputs: [], vPubOut: note.note.value, feeNote: nil, fee: 0)
                return Assembled(transfers: [plan]) { ts, _ in
                    MsgClaimUnbonding(transfer: ts[0], validator: validator, epoch: epoch, pc: erth.pc.bytes,
                                      ciphertext: erth.ciphertext, feeFromOutput: fee)
                }
            }
        }
    }

    /// Spend-to-vote: `note` (derth, created before the proposal's snapshot)
    /// is spent whole against the snapshot root, its value is the vote's
    /// weight, and the chain mints the same value back to us. The fee is a
    /// second transfer against the current root.
    public func stakeVote(proposalID: UInt64, note: OwnedNote, options: [WeightedVoteOption]) async throws -> TxResult {
        try await locked { try await stakeVoteLocked(proposalID: proposalID, note: note, options: options) }
    }

    private func stakeVoteLocked(proposalID: UInt64, note: OwnedNote, options: [WeightedVoteOption]) async throws -> TxResult {
        let validator = try Self.parseDerth(note.note.denom)
        let snap = try await reads.snapshot(proposalID: proposalID)
        try require(note.position < snap.treeSize, "this stake arrived after the proposal's snapshot and cannot vote on it")
        let tree = store.noteTree
        guard tree.rootAt(snap.treeSize) == snap.root else {
            throw PrivacyError("the local note tree disagrees with the proposal's snapshot root")
        }
        let path = tree.pathAt(note.position, size: snap.treeSize)
        let back = try NoteOut.toSelf(keys, denom: note.note.denom, value: note.note.value)
        return try await run { fee in
            let vote = try TransferPlan.build(keys: self.keys, tree: tree, denom: note.note.denom, aInputs: [note], aOutputs: [],
                                              vPubOut: note.note.value, feeNote: nil, fee: 0, root: snap.root, paths: [note.position: path])
            let feePlan = try self.feeOnly(fee, exclude: [note.position])
            return Assembled(transfers: [vote, feePlan]) { ts, _ in
                MsgStakeVote(transfer: ts[0], proposalID: proposalID, validator: validator, options: options,
                             pc: back.pc.bytes, ciphertext: back.ciphertext, feeTransfer: ts[1])
            }
        }
    }

    /// The derth notes that can stake-vote on `proposalID`: unspent, and in
    /// the tree at the proposal's snapshot.
    public func stakeVoteNotes(proposalID: UInt64) async throws -> [OwnedNote] {
        let snap = try await reads.snapshot(proposalID: proposalID)
        return snapshot.notes.filter {
            $0.unspent && $0.pendingAt == nil && $0.note.denom.hasPrefix("derth/") && $0.note.value > 0 && $0.position < snap.treeSize
        }
    }

    /// The positions that can vote on `proposalID`: created before the block
    /// it entered voting at (the chain refuses later ones, whose derth could
    /// still vote as a note from the snapshot root).
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
    /// stake-vote and every position that can, each at its validator's rate
    /// at the snapshot (1 where the snapshot names none).
    public func stakeVoteWeight(proposalID: UInt64, positions: [PrivacyReads.Position]) async throws -> StakeWeight {
        let snap = try await reads.snapshot(proposalID: proposalID)
        let notes = snapshot.notes.filter {
            $0.unspent && $0.pendingAt == nil && $0.note.denom.hasPrefix("derth/") && $0.note.value > 0 && $0.position < snap.treeSize
        }
        let ps = Self.votingPositions(positions, snapshot: snap)
        var total: UInt64 = 0
        for n in notes {
            let v = String(n.note.denom.dropFirst("derth/".count))
            total += Self.derthValue(n.note.value, rate: snap.rates[v] ?? 1)
        }
        for p in ps { total += Self.derthValue(p.derth, rate: snap.rates[p.validator] ?? 1) }
        return StakeWeight(notes: notes.count, positionIDs: Set(ps.map(\.id)), uerth: total)
    }

    /// Votes every eligible derth note on `proposalID` (one tx per note: a
    /// stake vote spends its note whole). Final: the spent nullifier and the
    /// re-minted note's absence from the snapshot root stop a second vote.
    public func stakeVoteAll(proposalID: UInt64, options: [WeightedVoteOption]) async throws -> [TxResult] {
        let ns = try await stakeVoteNotes(proposalID: proposalID)
        try require(!ns.isEmpty, "no stake from before this proposal's snapshot")
        var out: [TxResult] = []
        for n in ns { out.append(try await stakeVote(proposalID: proposalID, note: n, options: options)) }
        return out
    }

    // MARK: - dex

    /// Swaps `amountIn` `denomIn` from notes for at least `minOut` `denomOut`
    /// (any pools, through the ERTH hub), the output minted to us or to `to`.
    /// Its value is the pool's to decide: to us a self-mint found by its
    /// public amount, to anyone else a value-blind (v2) ciphertext. A swap
    /// into ERTH pays its fee out of the output (fee_from_output; the chain
    /// needs min_out above the fee); any other pays from ERTH notes (an ERTH
    /// swap from the same notes it spends).
    public func noteSwap(denomIn: String, amountIn: UInt64, denomOut: String, minOut: UInt64, to: ShieldedAddress? = nil) async throws -> TxResult {
        try await locked {
            try require(denomIn != denomOut && amountIn > 0 && minOut > 0, "a swap needs two denoms and positive amounts")
            let out = try payout(denomOut, to: to)
            return try await run { fee in
                if denomOut == "uerth" {
                    try require(minOut > fee, "the minimum received must exceed the \(fee)uerth fee paid from it")
                    let plan = try TransferPlan.build(keys: self.keys, tree: self.store.noteTree, denom: denomIn,
                                                      aInputs: NoteSelection.inputs(self.store.state.notes, denom: denomIn, amount: amountIn),
                                                      aOutputs: [], vPubOut: amountIn, feeNote: nil, fee: 0)
                    return Assembled(transfers: [plan]) { ts, _ in
                        MsgNoteSwap(transfer: ts[0], denomOut: denomOut, minAmountOut: minOut, pc: out.pc.bytes, ciphertext: out.ciphertext, feeFromOutput: fee)
                    }
                }
                let plan = try self.spendWithFee(denomIn, amount: amountIn, fee: fee, outputs: [], vPubOut: amountIn)
                return Assembled(transfers: [plan]) { ts, _ in
                    MsgNoteSwap(transfer: ts[0], denomOut: denomOut, minAmountOut: minOut, pc: out.pc.bytes, ciphertext: out.ciphertext, feeFromOutput: 0)
                }
            }
        }
    }

    /// Deposits `tokenAmount` `token` and `erthAmount` uerth from notes into
    /// `poolID` (pool 1: ANML/ERTH), the LP shares to `provider` (a
    /// transparent address: providing liquidity is public). The ERTH leg pays
    /// the fee from the same ERTH notes it spends. Whatever the pool ratio
    /// does not take is minted back to one self-mint pc (a note per asset).
    public func addLiquidityShielded(poolID: UInt64, token: String, tokenAmount: UInt64, erthAmount: UInt64,
                                     provider: String, minShares: String) async throws -> TxResult {
        try await locked {
            try require(tokenAmount > 0 && erthAmount > 0, "both legs must be positive")
            let refund = mint(token)
            return try await run { fee in
                let tokenPlan = try TransferPlan.build(keys: self.keys, tree: self.store.noteTree, denom: token,
                                                       aInputs: NoteSelection.inputs(self.store.state.notes, denom: token, amount: tokenAmount),
                                                       aOutputs: [], vPubOut: tokenAmount, feeNote: nil, fee: 0)
                let erthPlan = try self.spendWithFee("uerth", amount: erthAmount, fee: fee, outputs: [], vPubOut: erthAmount)
                return Assembled(transfers: [tokenPlan, erthPlan]) { ts, _ in
                    MsgAddLiquidityShielded(transfer: ts[0], erthTransfer: ts[1], poolID: poolID, provider: provider, minShares: minShares,
                                            refundPC: refund.pc.bytes, refundCiphertext: refund.ciphertext)
                }
            }
        }
    }

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

    // MARK: - Groundworks positions

    /// This wallet's Groundworks positions: the public positions whose key is one of ours.
    public func positions() async throws -> [(position: PrivacyReads.Position, keyIndex: UInt32)] {
        var mine: [Data: UInt32] = [:]
        let n = store.state.nextPositionKey
        for i in 0 ..< n { mine[try keys.positionPubKey(i)] = i }
        return try await reads.positions().compactMap { p in mine[p.pubkey].map { (p, $0) } }
    }

    public func lockPosition(validator: String, amount: UInt64, splits: [UInt64: UInt64]) async throws -> TxResult {
        try await locked {
            let keyIndex = store.state.nextPositionKey
            let pub = try keys.positionPubKey(keyIndex)
            let w = Self.weights(splits)
            let r = try await run { fee in
                let plan = try self.spendWithFee(Self.derthDenom(validator), amount: amount, fee: fee, outputs: [], vPubOut: amount)
                return Assembled(transfers: [plan]) { ts, _ in MsgLockPosition(transfer: ts[0], validator: validator, splits: w, pubkey: pub) }
            }
            store.mutate { $0.nextPositionKey = keyIndex + 1 }
            store.save()
            return r
        }
    }

    public func updatePosition(_ position: PrivacyReads.Position, keyIndex: UInt32, splits: [UInt64: UInt64]) async throws -> TxResult {
        try await locked {
            let w = Self.weights(splits)
            let sig = try positionSign(keyIndex, action: "update", position, payload: PrivateMsgs.splitsBytes(w))
            return try await run { fee in
                Assembled(transfers: [try self.feeOnly(fee)]) { ts, _ in
                    MsgUpdatePosition(transfer: ts[0], positionID: position.id, splits: w, signature: sig)
                }
            }
        }
    }

    public func unlockPosition(_ position: PrivacyReads.Position, keyIndex: UInt32) async throws -> TxResult {
        try await locked {
            let back = try NoteOut.toSelf(keys, denom: Self.derthDenom(position.validator), value: position.derth)
            let sig = try positionSign(keyIndex, action: "unlock", position, payload: back.pc.bytes + back.ciphertext)
            return try await run { fee in
                Assembled(transfers: [try self.feeOnly(fee)]) { ts, _ in
                    MsgUnlockPosition(transfer: ts[0], positionID: position.id, pc: back.pc.bytes, ciphertext: back.ciphertext, signature: sig)
                }
            }
        }
    }

    public func positionVote(_ position: PrivacyReads.Position, keyIndex: UInt32, proposalID: UInt64, options: [WeightedVoteOption]) async throws -> TxResult {
        try await locked {
            let sig = try positionSign(keyIndex, action: "vote", position,
                                       payload: PrivateMsgs.positionVotePayload(proposalID: proposalID, options: options))
            return try await run { fee in
                Assembled(transfers: [try self.feeOnly(fee)]) { ts, _ in
                    MsgPositionVote(transfer: ts[0], positionID: position.id, proposalID: proposalID, options: options, signature: sig)
                }
            }
        }
    }

    /// secp256k1 over sha256(PositionSignBytes), low-S, 64-byte r||s.
    private func positionSign(_ keyIndex: UInt32, action: String, _ p: PrivacyReads.Position, payload: Data) throws -> Data {
        guard try keys.positionPubKey(keyIndex) == p.pubkey else { throw PrivacyError("position \(p.id) is not held by key \(keyIndex)") }
        return try keys.positionSign(keyIndex, message: PrivateMsgs.positionSignBytes(chainID: chainID, action: action,
                                                                                       positionID: p.id, nonce: p.nonce, payload: payload))
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
