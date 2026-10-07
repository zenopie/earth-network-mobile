import BigInt
import Foundation

/// The chain reads the wallet's private msgs are built from. Ports
/// PrivacyChainReads (PrivacyWallet.kt). Nothing here takes a note, a
/// nullifier or an identity of this wallet.
public protocol PrivacyChainReads: Sendable {
    func personhoodParams() async throws -> PrivacyReads.PersonhoodParams
    /// x/personhood Query/LeaseBounds: what every predecessor bound is computed from, never Params.
    func leaseBounds() async throws -> PrivacyReads.LeaseBounds
    func ballotInputs(proposalID: UInt64, optionID: UInt64) async throws -> PrivacyReads.BallotInputs
    func epochNumber() async throws -> UInt64
    func snapshot(proposalID: UInt64) async throws -> PrivacyReads.Snapshot
    func positions() async throws -> [PrivacyReads.Position]
    /// x/shieldedstaking Query/StakeNullifierTree{start, limit} (at most 1000 a page).
    func stakeNullifierTree(start: UInt64, limit: Int) async throws -> PrivacyReads.NfTreePage
    /// x/shieldedstaking Query/DebtTree{start, limit} (at most 1000 a page).
    func debtTree(start: UInt64, limit: Int) async throws -> PrivacyReads.DebtTreePage
    /// x/shieldedstaking Query/Validators, every page at one height: what
    /// every staking quote reads. Never a query about one validator (asked
    /// shortly before a public msg, it would tie the asking IP to that intent).
    func validators() async throws -> PrivacyReads.ValidatorList
    /// x/shieldedstaking params.min_delegation (uerth, and the least derth one may credit).
    func minDelegation() async throws -> UInt64
    /// About when an undelegation booked in `epoch` is paid, from chain-wide
    /// timing alone (the current epoch, epoch length, x/staking's unbonding
    /// time): never a query about this wallet's undelegation. Nil: unknown.
    func unbondDueBy(epoch: UInt64) async -> Int64?
}

public extension PrivacyChainReads {
    func unbondDueBy(epoch: UInt64) async -> Int64? { nil }
}

public enum PrivacyReads {
    public struct PersonhoodParams: Sendable, Equatable {
        public let caretakerVoteSeconds: Int64
        public let identityRootWindowSeconds: Int64
        /// handle_lease_seconds (27) and handle_renewal_seconds (26); zero falls back to the chain's defaults.
        public let handleLeaseSeconds: Int64
        public let handleRenewalSeconds: Int64
        /// current_date_max_skew_seconds (7; 0: the node did not say): how long
        /// a failed registration may still land after its current_date.
        public let currentDateMaxSkewSeconds: Int64
        /// registration_validity_seconds: a registration lapses this long after its block time.
        public let registrationValiditySeconds: Int64
        public init(caretakerVoteSeconds: Int64, identityRootWindowSeconds: Int64,
                    handleLeaseSeconds: Int64 = Handles.defaultLeaseSeconds, handleRenewalSeconds: Int64 = Handles.defaultRenewalSeconds,
                    currentDateMaxSkewSeconds: Int64 = 0, registrationValiditySeconds: Int64 = 365 * 86_400) {
            self.caretakerVoteSeconds = caretakerVoteSeconds; self.identityRootWindowSeconds = identityRootWindowSeconds
            self.handleLeaseSeconds = handleLeaseSeconds; self.handleRenewalSeconds = handleRenewalSeconds
            self.currentDateMaxSkewSeconds = currentDateMaxSkewSeconds; self.registrationValiditySeconds = registrationValiditySeconds
        }
    }

    /// x/personhood Query/LeaseBounds: the lease lengths the
    /// chain's predecessor bounds use now (the longest handle lease ever in
    /// force; the caretaker lease including a held longer one after a cut),
    /// the activation margin, and both bounds at `blockTime`. Every
    /// max_predecessor a handle claim or a new caretaker split names comes
    /// from here, never from Params, which may be shorter.
    public struct LeaseBounds: Sendable, Equatable {
        public var blockTime: Int64
        public var activationMarginSeconds: Int64
        public var handleLeaseSeconds: Int64
        public var handleClaimBound: Int64
        public var caretakerLeaseSeconds: Int64
        public var caretakerCastBound: Int64
        public var caretakerLeaseHoldUntil: Int64
        public init(blockTime: Int64, activationMarginSeconds: Int64, handleLeaseSeconds: Int64, handleClaimBound: Int64,
                    caretakerLeaseSeconds: Int64, caretakerCastBound: Int64, caretakerLeaseHoldUntil: Int64 = 0) {
            self.blockTime = blockTime; self.activationMarginSeconds = activationMarginSeconds
            self.handleLeaseSeconds = handleLeaseSeconds; self.handleClaimBound = handleClaimBound
            self.caretakerLeaseSeconds = caretakerLeaseSeconds; self.caretakerCastBound = caretakerCastBound
            self.caretakerLeaseHoldUntil = caretakerLeaseHoldUntil
        }
    }

    /// x/assembly BallotInputs: the membership statement of a proposal's
    /// current round or an option's removal ballot.
    public struct BallotInputs: Sendable {
        public let scope: Fr
        public let excludedDsc: Fr
        public let excludedCountry: Fr
        public let maxActivation: UInt64
        public let round: UInt64
        public let ballotID: UInt64
        /// max_predecessor (7): the double-vote bound; max_activation is no bound for ballots.
        public let maxPredecessor: UInt64
        public init(scope: Fr, excludedDsc: Fr, excludedCountry: Fr, maxActivation: UInt64, round: UInt64, ballotID: UInt64,
                    maxPredecessor: UInt64 = PrivacyHash.noBound) {
            self.scope = scope; self.excludedDsc = excludedDsc; self.excludedCountry = excludedCountry
            self.maxActivation = maxActivation; self.round = round; self.ballotID = ballotID; self.maxPredecessor = maxPredecessor
        }
    }

    public struct Snapshot: Sendable {
        public let root: Fr
        public let treeSize: UInt64
        /// The block the proposal entered voting at; a position created at
        /// or after it cannot vote. 0 when unknown.
        public let height: Int64
        /// rate_v (ERTH per derth) per validator at the snapshot: what a
        /// stake vote's derth weighs.
        public let rates: [String: Decimal]
        /// The stake nullifier tree's root and size (sentinel included) at the
        /// same moment; nil: a snapshot from before stake votes stopped
        /// spending, which takes no stake vote.
        public let nfRoot: Fr?
        public let nfSize: UInt64
        public init(root: Fr, treeSize: UInt64, height: Int64 = 0, rates: [String: Decimal] = [:], nfRoot: Fr? = nil, nfSize: UInt64 = 0) {
            self.root = root; self.treeSize = treeSize; self.height = height; self.rates = rates; self.nfRoot = nfRoot; self.nfSize = nfSize
        }
    }

    /// Query/StakeNullifierTree: the values at leaf start+1.. in insertion order, and the tree's current size.
    public struct NfTreePage: Sendable {
        public let values: [Fr]
        public let size: UInt64
        public init(values: [Fr], size: UInt64) { self.values = values; self.size = size }
    }

    /// A Groundworks position (public), its owner known only by `ownerTag`.
    public struct Position: Sendable, Equatable, Identifiable {
        public let id: UInt64
        public let validator: String
        public let derth: UInt64
        public let ownerTag: Fr
        public let splits: [UInt64: UInt64]
        public let createdHeight: UInt64
        /// When the split stops counting (x/allocation groundworks_lease_seconds after it was cast or
        /// renewed; 0 without a split, or on a node before leases).
        public let splitExpiresAt: Int64
        public init(id: UInt64, validator: String, derth: UInt64, ownerTag: Fr, splits: [UInt64: UInt64] = [:], createdHeight: UInt64 = 0,
                    splitExpiresAt: Int64 = 0) {
            self.id = id; self.validator = validator; self.derth = derth; self.ownerTag = ownerTag
            self.splits = splits; self.createdHeight = createdHeight; self.splitExpiresAt = splitExpiresAt
        }
    }

    /// x/assembly's open removal ballots (public; every one).
    public struct RemovalBallot: Sendable, Identifiable {
        public let optionID: UInt64
        public let ballotID: UInt64
        public let openedAt: Int64
        public let closesAt: Int64
        public let yes: Int64
        public let no: Int64
        public var id: UInt64 { optionID }
    }

    public struct Epoch: Sendable {
        public let number: UInt64
        public let startTime: Int64
        public let endTime: Int64
    }

    /// shieldedstaking params.epoch_seconds and x/staking params.unbonding_time, in seconds.
    public struct StakingTiming: Sendable {
        public let epochSeconds: Int64
        public let unbondingSeconds: Int64
    }

    public static let bondStatusBonded = "BOND_STATUS_BONDED"
    public static let bondStatusUnbonded = "BOND_STATUS_UNBONDED"

    /// One entry of x/shieldedstaking Query/Validators (ORCHARD_DESIGN 12.2):
    /// what a delegation, undelegation, redelegation or stake vote at
    /// `validator` is quoted from. B = `backing`, S = `supply` (rate = B / S,
    /// exact integers; `rate` the chain's LegacyDec of it, for display), the
    /// queue P = `pendingDelegation`, U = `pendingUndelegation`, the module's
    /// delegation D = `delegation` and unwithdrawn rewards W = `rewards`
    /// (B = D + W + P - U), x/staking's `status` ("" for a book whose
    /// validator x/staking removed), `jailed`, `tombstoned`, whether a
    /// delegation or a redelegation into it is taken now (`delegatable`, else
    /// `refusal`, the chain's reason), and the module's redelegation record
    /// out of it per destination (`redelegations`, what MsgRedelegate's gas
    /// grows with). Ports PrivacyChainReads.ValidatorQuote.
    public struct ValidatorQuote: Sendable, Equatable {
        public var validator: String
        public var backing: BigUInt
        public var supply: BigUInt
        public var pendingDelegation: BigUInt
        public var pendingUndelegation: BigUInt
        public var delegation: BigUInt
        public var rewards: BigUInt
        public var rate: Decimal
        public var status: String
        public var jailed: Bool
        public var tombstoned: Bool
        public var delegatable: Bool
        public var refusal: String
        public var moniker: String
        /// x/staking's commission rate, a fraction (0.10 for 10%).
        public var commission: Double
        /// x/staking's bonded tokens (the validator's whole stake, not the module's).
        public var tokens: BigUInt
        public var redelegations: [String: RedelegationLoad]

        public init(validator: String, backing: BigUInt, supply: BigUInt, pendingDelegation: BigUInt = 0, pendingUndelegation: BigUInt = 0,
                    delegation: BigUInt? = nil, rewards: BigUInt = 0, rate: Decimal? = nil, status: String = PrivacyReads.bondStatusBonded,
                    jailed: Bool = false, tombstoned: Bool = false, delegatable: Bool = true, refusal: String = "", moniker: String = "",
                    commission: Double = 0, tokens: BigUInt = 0, redelegations: [String: RedelegationLoad] = [:]) {
            self.validator = validator; self.backing = backing; self.supply = supply
            self.pendingDelegation = pendingDelegation; self.pendingUndelegation = pendingUndelegation
            self.delegation = delegation ?? (backing + pendingUndelegation > pendingDelegation ? backing + pendingUndelegation - pendingDelegation : 0)
            self.rewards = rewards
            self.rate = rate ?? (supply == 0 ? 1 : (Decimal(string: String(backing)) ?? 0) / (Decimal(string: String(supply)) ?? 1))
            self.status = status; self.jailed = jailed; self.tombstoned = tombstoned; self.delegatable = delegatable
            self.refusal = refusal; self.moniker = moniker; self.commission = commission; self.tokens = tokens
            self.redelegations = redelegations
        }

        public var removed: Bool { status.isEmpty }
        public var bonded: Bool { status == PrivacyReads.bondStatusBonded }

        /// Whether a move out of here leaves the queue first (8.7 step 3): no
        /// slash can reach the stake (x/staking unbonded or removed it, or the
        /// bonded part D - U is nothing). Otherwise the value leaves the queue
        /// and the bonded stake pro rata.
        public var queueFirst: Bool { removed || status == PrivacyReads.bondStatusUnbonded || delegation <= pendingUndelegation }

        /// The queue a move draws on: P, and W, which a move withdraws into it first.
        public var queue: BigUInt { pendingDelegation + rewards }
    }

    /// RedelegationLoad: the pair's x/staking record, its `entries` and `countedEntries` (those of positive height).
    public struct RedelegationLoad: Sendable, Equatable {
        public let entries: UInt64
        public let countedEntries: UInt64
        public init(entries: UInt64, countedEntries: UInt64) { self.entries = entries; self.countedEntries = countedEntries }
    }

    /// Query/Validators, every page, read at one `height`.
    public struct ValidatorList: Sendable, Equatable {
        public let height: Int64
        public let validators: [ValidatorQuote]
        private let byOperator: [String: Int]

        public init(height: Int64, validators: [ValidatorQuote]) {
            self.height = height; self.validators = validators
            var m: [String: Int] = [:]
            for (i, v) in validators.enumerated() where m[v.validator] == nil { m[v.validator] = i }
            byOperator = m
        }

        public subscript(_ valoper: String) -> ValidatorQuote? { byOperator[valoper].map { validators[$0] } }

        /// `valoper`'s entry; a validator the list does not carry has no book and no x/staking record.
        public func of(_ valoper: String) throws -> ValidatorQuote {
            guard let v = self[valoper] else { throw PrivacyError("the chain lists no validator \(valoper)") }
            return v
        }
    }

    /// x/shieldedstaking Query/DebtTree{start, limit}: the
    /// slash debt tree's rows from leaf start+1 in insertion order (each with
    /// its latest retained), its size (sentinel included, 0 before the first
    /// row) and current root, the label window and the clear_before a proof
    /// may name now. The whole tree, paged: nothing names a move of ours.
    public struct DebtTreePage: Sendable {
        public let rows: [(key: Fr, retained: UInt64)]
        public let size: UInt64
        public let root: Fr
        public let windowSeconds: UInt64
        public let clearBefore: UInt64
        public init(rows: [(key: Fr, retained: UInt64)], size: UInt64, root: Fr, windowSeconds: UInt64, clearBefore: UInt64) {
            self.rows = rows; self.size = size; self.root = root; self.windowSeconds = windowSeconds; self.clearBefore = clearBefore
        }
    }
}

/// The chain's public state the private msgs are built from, over the LCD.
/// Ports PrivacyQueries (privacy/chain/PrivacyChain.kt).
public struct PrivacyQueries: PrivacyChainReads {
    public let rest: EarthRest

    public init(rest: EarthRest = EarthRest()) { self.rest = rest }

    private func field(_ j: JSON) throws -> Fr {
        let raw = Data(base64Encoded: j.string ?? "") ?? Data()
        return raw.isEmpty ? .zero : try Fr(bytes: raw)
    }

    public func removalBallots() async throws -> [PrivacyReads.RemovalBallot] {
        try await rest.get("/earth/assembly/v1/removal_ballots").ballots.array.map { b in
            PrivacyReads.RemovalBallot(optionID: b.option_id.uint64(default: 0), ballotID: b.ballot_id.uint64(default: 0),
                                       openedAt: b.opened_at.int64(default: 0), closesAt: b.closes_at.int64(default: 0),
                                       yes: b.tally.yes.int64(default: 0), no: b.tally.no.int64(default: 0))
        }
    }

    /// LP share supply of `poolID` (bank supply of dexlp/<id>).
    /// A missing or unreadable amount is a failed read, not a zero supply:
    /// zero prices a deposit with no bound at all.
    public func lpShareSupply(poolID: UInt64) async throws -> String {
        guard let s = try await rest.get("/cosmos/bank/v1beta1/supply/by_denom?denom=dexlp/\(poolID)").amount.amount.string,
              let v = BigInt(s), v >= 0 else { throw PrivacyError("the node did not say pool \(poolID)'s share supply") }
        return v.description
    }

    public func shieldedMinFee() async throws -> UInt64 {
        let v = try await rest.get("/earth/shielded/v1/params").params.min_fee
        guard v.exists else { return 1000 }
        guard let fee = v.uint64, fee <= UInt64(Int64.max) else { throw PrivacyError("x/shielded min_fee is not a fee") }
        return fee
    }

    /// The most actions the wallet lays out in one bundle, whatever the node's param says.
    public static let maxActionsBound = 64

    /// x/shielded params.max_actions_per_bundle (default 16), bounded to [2, 64] whatever the node says.
    public func maxActionsPerBundle() async throws -> Int {
        guard let v = try await rest.get("/earth/shielded/v1/params").params.max_actions_per_bundle.uint64 else { return 16 }
        return v >= 2 ? Int(min(v, UInt64(Self.maxActionsBound))) : 16
    }

    /// The longest chain duration the wallet takes (100 years): longer is refused, never wrapped or trapped on.
    public static let maxDurationSeconds: Int64 = 100 * 365 * 86_400

    /// A protobuf JSON duration ("1814400s", "0.5s") in whole seconds, nil unless finite, non-negative and at most `maxDurationSeconds`.
    public static func durationSeconds(_ d: String) -> Int64? {
        let t = d.hasSuffix("s") ? String(d.dropLast()) : d
        guard let v = Decimal(string: t, locale: Locale(identifier: "en_US_POSIX")), !v.isNaN, v >= 0, v <= Decimal(maxDurationSeconds) else { return nil }
        var x = v, r = Decimal()
        NSDecimalRound(&r, &x, 0, .down)
        return NSDecimalNumber(decimal: r).int64Value
    }

    public func personhoodParams() async throws -> PrivacyReads.PersonhoodParams {
        let p = try await rest.get("/earth/personhood/v1/params").params
        let r = p.caretaker_vote_seconds.int64(default: 0)
        let w = p.identity_root_window_seconds.int64(default: 0)
        let l = p.handle_lease_seconds.int64(default: 0)
        let n = p.handle_renewal_seconds.int64(default: 0)
        let k = p.current_date_max_skew_seconds.int64(default: 0)
        let v = p.registration_validity_seconds.int64(default: 0)
        // Zero falls back to the chain's defaults (365 days; 30 days for the renewal period).
        // Every duration at most Handles.maxAheadSeconds, so no sum of it with a
        // time can trap (a node's 2^63 lease is not one).
        let m = Handles.maxAheadSeconds
        return PrivacyReads.PersonhoodParams(caretakerVoteSeconds: min(r > 0 ? r : 365 * 86_400, m), identityRootWindowSeconds: min(w > 0 ? w : 3_600, m),
                                             handleLeaseSeconds: min(l > 0 ? l : Handles.defaultLeaseSeconds, m),
                                             handleRenewalSeconds: min(n > 0 ? n : Handles.defaultRenewalSeconds, m),
                                             currentDateMaxSkewSeconds: min(max(k, 0), m),
                                             registrationValiditySeconds: min(v > 0 ? v : 365 * 86_400, m))
    }

    /// x/personhood Query/LeaseBounds. int64 fields arrive as JSON strings; a
    /// field that is present but not an int64 is refused.
    public func leaseBounds() async throws -> PrivacyReads.LeaseBounds {
        let j = try await rest.get("/earth/personhood/v1/lease_bounds")
        func i64(_ v: JSON, _ name: String) throws -> Int64 {
            guard v.exists else { return 0 }
            guard let x = v.int64 else { throw PrivacyError("lease_bounds \(name) is not an int64") }
            return x
        }
        return PrivacyReads.LeaseBounds(
            blockTime: try i64(j.block_time, "block_time"), activationMarginSeconds: try i64(j.activation_margin_seconds, "activation_margin_seconds"),
            handleLeaseSeconds: try i64(j.handle_lease_seconds, "handle_lease_seconds"), handleClaimBound: try i64(j.handle_claim_bound, "handle_claim_bound"),
            caretakerLeaseSeconds: try i64(j.caretaker_lease_seconds, "caretaker_lease_seconds"),
            caretakerCastBound: try i64(j.caretaker_cast_bound, "caretaker_cast_bound"),
            caretakerLeaseHoldUntil: try i64(j.caretaker_lease_hold_until, "caretaker_lease_hold_until"))
    }

    public func ballotInputs(proposalID: UInt64, optionID: UInt64) async throws -> PrivacyReads.BallotInputs {
        let q = proposalID != 0 ? "proposal_id=\(proposalID)" : "option_id=\(optionID)"
        let j = try await rest.get("/earth/assembly/v1/ballot_inputs?\(q)")
        return PrivacyReads.BallotInputs(scope: try field(j.scope), excludedDsc: try field(j.excluded_dsc),
                                         excludedCountry: try field(j.excluded_country), maxActivation: j.max_activation.uint64(default: 0),
                                         round: j.round.uint64(default: 0), ballotID: j.ballot_id.uint64(default: 0),
                                         // Absent from an older node: 0, which only a fresh registrant meets.
                                         maxPredecessor: j.max_predecessor.uint64(default: 0))
    }

    /// One page of the handle directory (Query/Handles: handles after
    /// `start`, in order; next "" when exhausted). Only HandleDirectory
    /// calls this, from the first page to the last: never a lookup of one handle.
    public func handlesPage(start: String, limit: Int) async throws -> HandleDirectory.Page {
        var q = URLComponents()
        q.queryItems = [URLQueryItem(name: "start", value: start), URLQueryItem(name: "limit", value: String(min(max(limit, 1), 1000)))]
        let j = try await rest.get("/earth/personhood/v1/handles?" + (q.percentEncodedQuery ?? ""))
        let hs = j.handles.array.map { h in
            HandleEntry(handle: h.handle.string(default: ""), address: h.address.string(default: ""), status: h.status.string(default: ""),
                        expiresAt: h.expires_at.int64(default: 0), renewalUntil: h.renewal_until.int64(default: 0),
                        owner: Handles.owner(h.owner.string))
        }
        return HandleDirectory.Page(handles: hs, next: j.next.string(default: ""))
    }

    public func epoch() async throws -> PrivacyReads.Epoch {
        let e = try await rest.get("/earth/shieldedstaking/v1/epoch").epoch
        return PrivacyReads.Epoch(number: e.number.uint64(default: 0), startTime: e.start_time.int64(default: 0), endTime: e.end_time.int64(default: 0))
    }

    public func epochNumber() async throws -> UInt64 { try await epoch().number }

    public func unbondDueBy(epoch e: UInt64) async -> Int64? {
        guard let cur = try? await epoch(), let t = try? await stakingTiming() else { return nil }
        return PrivacyWallet.unbondDueBy(epoch: e, current: cur.number, currentStart: cur.startTime, currentEnd: cur.endTime,
                                         epochSeconds: t.epochSeconds, unbondingSeconds: t.unbondingSeconds)
    }

    public func snapshot(proposalID: UInt64) async throws -> PrivacyReads.Snapshot {
        let s = try await rest.get("/earth/shieldedstaking/v1/snapshots/\(proposalID)").snapshot
        var rates: [String: Decimal] = [:]
        for v in s.validators.array {
            if let r = Decimal(string: v.rate.string(default: "")) { rates[v.validator.string(default: "")] = r }
        }
        let nfRaw = Data(base64Encoded: s.nf_root.string(default: "")) ?? Data()
        return PrivacyReads.Snapshot(root: try field(s.root), treeSize: s.tree_size.uint64(default: 0),
                                     height: s.height.int64(default: 0), rates: rates,
                                     nfRoot: nfRaw.isEmpty ? nil : try Fr(bytes: nfRaw), nfSize: s.nf_size.uint64(default: 0))
    }

    /// Query/StakeNullifierTree: up to `limit` (at most 1000) values from leaf start+1, in insertion order.
    public func stakeNullifierTree(start: UInt64, limit: Int) async throws -> PrivacyReads.NfTreePage {
        let j = try await rest.get("/earth/shieldedstaking/v1/stake_nullifier_tree?start=\(start)&limit=\(min(max(limit, 1), 1000))")
        let values = try j.values.array.prefix(1000).enumerated().map { i, v -> Fr in
            guard let raw = Data(base64Encoded: v.string ?? "") else { throw PrivacyError("stake nullifier \(start + 1 + UInt64(i)) is not base64") }
            return try Fr(bytes: raw)
        }
        return PrivacyReads.NfTreePage(values: values, size: j.size.uint64(default: 0))
    }

    /// Query/DebtTree: up to `limit` (at most 1000) rows from leaf start+1, the tree's size and root, the label window and clear_before.
    public func debtTree(start: UInt64, limit: Int) async throws -> PrivacyReads.DebtTreePage {
        let j = try await rest.get("/earth/shieldedstaking/v1/debt_tree?start=\(start)&limit=\(min(max(limit, 1), 1000))")
        let rows = try j.rows.array.prefix(1000).enumerated().map { i, r -> (key: Fr, retained: UInt64) in
            guard let raw = Data(base64Encoded: r.key.string ?? ""), raw.count == 32 else { throw PrivacyError("debt row \(start + 1 + UInt64(i)): key is not 32 bytes") }
            guard let ret = UInt64(r.retained.string(default: "0")), ret <= UInt64(Int64.max) else { throw PrivacyError("debt row \(start + 1 + UInt64(i)): retained") }
            return (try Fr(bytes: raw), ret)
        }
        guard let rootRaw = Data(base64Encoded: j.root.string ?? ""), rootRaw.count == 32 else { throw PrivacyError("debt_tree: no root") }
        return PrivacyReads.DebtTreePage(rows: rows, size: j.size.uint64(default: 0), root: try Fr(bytes: rootRaw),
                                         windowSeconds: j.window_seconds.uint64(default: 0), clearBefore: j.clear_before.uint64(default: 0))
    }

    /// shieldedstaking params.min_delegation (uerth).
    public func minDelegation() async throws -> UInt64 {
        try await rest.get("/earth/shieldedstaking/v1/params").params.min_delegation.uint64(default: 0)
    }

    /// The last validator list read (`validators()`): what pickers, monikers
    /// and stake values show between reads. Nil until the first.
    public static var cachedValidators: PrivacyReads.ValidatorList? { ValidatorPages.cache.get() }

    /// Query/Validators, every page at one height (ValidatorPages), kept as
    /// `cachedValidators`. Read with every sync and every quote; the only
    /// read of a validator's book, status or redelegations the app makes.
    public func validators() async throws -> PrivacyReads.ValidatorList {
        let rest = self.rest
        let list = try await ValidatorPages.readAll { key, height in
            let path = "/earth/shieldedstaking/v1/validators?pagination.limit=\(ValidatorPages.pageLimit)" +
                (key.isEmpty ? "" : "&pagination.key=" + (key.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? key))
            guard let height else { return try ValidatorPages.parse(try await rest.get(path)) }
            // Not served at that height (pruned, or a node behind it): read again.
            guard let (j, echo) = try? await rest.getEcho(path, height: UInt64(height)), echo == nil || echo == UInt64(height) else { return nil }
            return try ValidatorPages.parse(j)
        }
        ValidatorPages.cache.set(list)
        return list
    }

    /// Chain-wide timing, the same answer for everyone: with the epoch it says
    /// about when an undelegation is paid, so the wallet never has to ask the
    /// node about its own undelegation.
    public func stakingTiming() async throws -> PrivacyReads.StakingTiming {
        let es = try await rest.get("/earth/shieldedstaking/v1/params").params.epoch_seconds.int64(default: 0)
        let ub = try await rest.get("/cosmos/staking/v1beta1/params").params.unbonding_time.string(default: "1814400s")
        guard let seconds = Self.durationSeconds(ub) else { throw PrivacyError("unbonding_time \(ub.prefix(40)) is not a duration") }
        return PrivacyReads.StakingTiming(epochSeconds: es > 0 ? min(es, Self.maxDurationSeconds) : 86_400, unbondingSeconds: seconds)
    }

    /// Every Groundworks position (public); the wallet finds its own by owner
    /// tag. Each id once (the first served): a page repeated by a proxy or a
    /// node must not count a position twice.
    public func positions() async throws -> [PrivacyReads.Position] {
        var out: [PrivacyReads.Position] = []
        var seen = Set<UInt64>()
        var keys = Set<String>()
        var key: String?
        repeat {
            let path = "/earth/shieldedstaking/v1/positions" +
                (key.map { "?pagination.key=" + ($0.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? $0) } ?? "")
            let j = try await rest.get(path)
            for p in j.positions.array {
                var splits: [UInt64: UInt64] = [:]
                for s in p.splits.array { splits[s.option_id.uint64(default: 0)] = s.percent.uint64(default: 0) }
                guard seen.insert(p.id.uint64(default: 0)).inserted else { continue }
                out.append(PrivacyReads.Position(id: p.id.uint64(default: 0), validator: p.validator.string(default: ""),
                                                 derth: p.derth.uint64(default: 0), ownerTag: try field(p.owner_tag),
                                                 splits: splits, createdHeight: p.created_height.uint64(default: 0),
                                                 splitExpiresAt: max(0, p.split_expires_at.int64(default: 0))))
            }
            key = j.pagination.next_key.string.flatMap { $0.isEmpty || $0 == "null" ? nil : $0 }
            if let k = key, !keys.insert(k).inserted { throw PrivacyError("the node's positions listing repeats a page") }
        } while key != nil
        return out
    }
}

/// `PrivateChain` over the LCD.
public struct RESTPrivateChain: PrivateChain {
    public let rest: EarthRest
    public init(rest: EarthRest = EarthRest()) { self.rest = rest }

    private static func message(_ e: Swift.Error) -> String {
        if case let EarthRest.Error.http(status, body) = e {
            let msg = (try? JSONSerialization.jsonObject(with: Data(body.utf8))).flatMap { JSON($0).message.string }
            return "(\(status)) \(msg ?? body)"
        }
        return String(describing: e)
    }

    /// Gas the chain charges this tx. In simulate mode the private ante
    /// charges every proof's fixed gas but does not verify it, and still runs
    /// every state check, so a tx carrying placeholder proofs over its real
    /// nullifiers and roots measures what the proven tx will cost and fails
    /// here for the same reasons it would.
    public func simulate(_ tx: Data) async throws -> UInt64 {
        do {
            let j = try await rest.postJSON("/cosmos/tx/v1beta1/simulate", body: ["tx_bytes": tx.base64EncodedString()])
            guard let gas = j.gas_info.gas_used.uint64 else { throw PrivacyError("simulate returned no gas") }
            return gas
        } catch let e as EarthRest.Error {
            throw PrivacyError("simulate failed \(Self.message(e))")
        }
    }

    /// Broadcasts, waits for the block and reads the tx back. Throws on any
    /// non-zero code. `accepted` runs at CheckTx code 0, before the wait.
    public func broadcast(_ tx: Data, accepted: @Sendable (String) -> Void) async throws -> TxResult {
        let j: JSON
        let lcd = rest.lcd
        do {
            j = try await rest.postJSON("/cosmos/tx/v1beta1/txs", body: ["tx_bytes": tx.base64EncodedString(), "mode": "BROADCAST_MODE_SYNC"], base: lcd)
        } catch let e as EarthRest.Error {
            throw PrivacyError("broadcast failed \(Self.message(e))")
        }
        let code = j.tx_response.code.int64(default: 0)
        guard code == 0 else {
            throw UnsignedTx.TxRejected(code: Int(clamping: code), log: j.tx_response.raw_log.string(default: ""),
                                        codespace: j.tx_response.codespace.string(default: ""), lcd: lcd.absoluteString)
        }
        guard let hash = j.tx_response.txhash.string else { throw EarthClient.Error.notCommitted(hash: "") }
        accepted(hash)
        // CheckTx took it, so nothing that fails from here says it was not
        // sent. A dropped connection while waiting is answered by asking for
        // the hash again; still unanswered, the outcome is unknown
        // (notCommitted): the spent notes stay marked until
        // WalletSync.releaseStalePending settles the hash, so no retry can
        // spend them twice.
        for i in 0 ..< Self.commitPolls {
            if let r = try? await self.tx(hash) {
                guard r.code == 0 else { throw EarthClient.Error.executionFailed(code: Int(r.code), log: r.log) }
                return r
            }
            if i < Self.commitPolls - 1 { try? await Task.sleep(for: .milliseconds(800)) }
        }
        throw EarthClient.Error.notCommitted(hash: hash)
    }

    private static let commitPolls = 20

    public func tx(_ hash: String) async throws -> TxResult? {
        do {
            return try await fetch(hash)
        } catch let EarthRest.Error.http(status, _) where status == 404 || status == 400 {
            return nil
        }
    }

    public func gasPrice() async throws -> Decimal { await Fees.price(rest: rest) }

    public func minFee() async throws -> UInt64 { try await PrivacyQueries(rest: rest).shieldedMinFee() }

    public func maxActionsPerBundle() async throws -> Int { try await PrivacyQueries(rest: rest).maxActionsPerBundle() }

    public func tipHeight() async throws -> UInt64 {
        guard let h = await LCDChainRoots(rest: rest).latestHeight() else { throw PrivacyError("the node did not say its latest height") }
        return h
    }

    /// RFC 3339 block time to unix seconds.
    static func parseTime(_ ts: String) -> Int64 {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = "yyyy-MM-dd'T'HH:mm:ss"
        return f.date(from: String(ts.prefix(19))).map { Int64($0.timeIntervalSince1970) } ?? 0
    }

    /// A committed tx's height, block time and events.
    public func fetch(_ hash: String) async throws -> TxResult {
        let tr = try await rest.get("/cosmos/tx/v1beta1/txs/\(hash)").tx_response
        let events = tr.events.array.map { e in
            (type: e.type.string(default: ""),
             attributes: Dictionary(e.attributes.array.map { ($0.key.string(default: ""), $0.value.string(default: "")) }, uniquingKeysWith: { a, _ in a }))
        }
        return TxResult(hash: hash, height: tr.height.uint64(default: 0), time: Self.parseTime(tr.timestamp.string(default: "")), events: events,
                        code: Int(clamping: tr.code.int64(default: 0)), log: tr.raw_log.string(default: ""),
                        codespace: tr.codespace.string(default: ""))
    }
}

/// The chain's own view of the three trees (LCD), against which every root
/// the indexer served is checked before the wallet builds anything on it
/// (WalletSync.verifyRoots; PRIVACY_FORMATS 18 says what this trusts). The
/// identity and stake trees are read at the height the indexer's root is
/// from (`x-cosmos-block-height`), pinned only when the node echoes exactly
/// that height; otherwise (a pruned height, another height echoed) the
/// latest state is read and marked unpinned, which can verify equal trees but
/// never condemn different ones. Ports LcdChainRoots.
public struct LCDChainRoots: ChainRoots {
    public let rest: EarthRest

    public init(rest: EarthRest = EarthRest()) { self.rest = rest }

    private static func field(_ j: JSON) -> Fr? {
        guard let raw = Data(base64Encoded: j.string ?? ""), !raw.isEmpty else { return nil }
        return try? Fr(bytes: raw)
    }

    /// (body, pinned): at `height` when the node answers at exactly it (the
    /// echoed header), else the latest state, never taken as pinned.
    private func at(_ path: String, _ height: UInt64?) async throws -> (JSON, Bool) {
        if let height, height > 0, let (j, echo) = try? await rest.getEcho(path, height: height) {
            return (j, echo == height)
        }
        return (try await rest.get(path), false)
    }

    private func spent(_ path: String) async -> Bool? {
        guard let j = try? await rest.get(path) else { return nil }
        return j.spent.bool(default: false)
    }

    public func nullifierSpent(_ nf: Fr) async -> Bool? { await spent("/earth/shielded/v1/nullifiers/\(nf.hex)") }

    public func stakeNullifierSpent(_ nf: Fr) async -> Bool? { await spent("/earth/shieldedstaking/v1/stake_nullifiers/\(nf.hex)") }

    public func latestHeight() async -> UInt64? { await latestBlock()?.height }

    public func latestBlock() async -> ChainTip? {
        guard let h = (try? await rest.get("/cosmos/base/tendermint/v1beta1/blocks/latest"))?.block.header, let height = h.height.uint64 else { return nil }
        let t = RESTPrivateChain.parseTime(h.time.string(default: ""))
        return ChainTip(height: height, time: t > 0 ? UInt64(t) : nil)
    }

    /// x/shielded Query/Tree at `height` (pinned when the node echoes it).
    public func noteTree(height: UInt64?) async -> TreeState? {
        guard let (j, pinned) = try? await at("/earth/shielded/v1/tree", height), let size = j.tree_size.uint64 else { return nil }
        return TreeState(size: size, root: nil, pinned: pinned)
    }

    /// x/shielded Query/Assets, every page, at most `Denoms.max` entries
    /// The caller learns an entry only if its id is the denom's own.
    public func assets() async -> [(denom: String, id: Fr)]? {
        var out: [(denom: String, id: Fr)] = []
        var key = ""
        while out.count < Denoms.max {
            let q = "pagination.limit=500" + (key.isEmpty ? "" : "&pagination.key=" + (key.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? key))
            guard let j = try? await rest.get("/earth/shielded/v1/assets?" + q) else { return nil }
            let a = j.assets.array
            for e in a {
                if out.count >= Denoms.max { break }
                guard let id = Self.field(e.asset_id) else { continue }
                out.append((e.denom.string(default: ""), id))
            }
            let next = j.pagination.next_key.string(default: "")
            if next.isEmpty || next == key || a.isEmpty { break }
            key = next
        }
        return out
    }

    /// Every validator with a book or an x/staking record, from the
    /// validator list read now (whole, as every sync reads it): asked only for
    /// a stake note no held denom names, which a cached list from before its
    /// validator existed would leave unnamed. At most Denoms.max; nil when
    /// the list cannot be read.
    public func validatorOperators() async -> [String]? {
        let list = try? await PrivacyQueries(rest: rest).validators()
        return list.map { Array($0.validators.map(\.validator).prefix(Denoms.max)) }
    }

    /// A tx this wallet broadcast, by hash: 404 missing, a non-zero code failed.
    public func txStatus(_ hash: String) async -> TxStatus? {
        do {
            let tr = try await rest.get("/cosmos/tx/v1beta1/txs/\(hash)").tx_response
            guard tr.exists else { return nil }
            return tr.code.int64(default: 0) == 0 ? .committed : .failed
        } catch let EarthRest.Error.http(status, _) where status == 404 {
            return .missing
        } catch {
            return nil
        }
    }

    public func blockTime(_ height: UInt64) async -> UInt64? {
        guard let h = (try? await rest.get("/cosmos/base/tendermint/v1beta1/blocks/\(height)"))?.block.header,
              h.height.uint64 == height else { return nil }
        let t = RESTPrivateChain.parseTime(h.time.string(default: ""))
        return t > 0 ? UInt64(t) : nil
    }

    /// node_info's network and block 1's hash (the indexer's genesis key: its first 16 hex digits).
    public func chainIdentity() async -> ChainIdentity? {
        guard let net = (try? await rest.get("/cosmos/base/tendermint/v1beta1/node_info"))?.default_node_info.network.string,
              !net.isEmpty else { return nil }
        let hash = (try? await rest.get("/cosmos/base/tendermint/v1beta1/blocks/1"))?.block_id.hash.string
            .flatMap { Data(base64Encoded: $0) }.flatMap { $0.count == 32 ? $0 : nil }
        return ChainIdentity(chainID: net, genesis: hash.map { String($0.hexString.prefix(16)) })
    }

    public func noteRoot(_ root: Fr) async throws -> NoteRootRecord? {
        let j = try await rest.get("/earth/shielded/v1/roots/\(root.hex)")
        guard j.record.exists, Self.field(j.record.root) == root else { return nil }
        return NoteRootRecord(valid: j.valid.bool(default: false), treeSize: j.record.tree_size.uint64(default: 0), height: j.record.height.uint64,
                              expiresAt: j.expires_at.exists ? j.expires_at.int64 : nil)
    }

    public func identityTree(height: UInt64?) async throws -> TreeState {
        let (j, pinned) = try await at("/earth/personhood/v1/identity_tree", height)
        return TreeState(size: j.size.uint64(default: 0), root: Self.field(j.latest_root), pinned: pinned)
    }

    public func stakeTree(height: UInt64?) async throws -> TreeState {
        let (j, pinned) = try await at("/earth/shieldedstaking/v1/stake_tree", height)
        return TreeState(size: j.size.uint64(default: 0), root: Self.field(j.root), pinned: pinned)
    }
}
