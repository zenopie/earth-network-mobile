import Foundation

/// The chain reads the wallet's private msgs are built from. Ports
/// PrivacyChainReads (PrivacyWallet.kt). Nothing here takes a note, a
/// nullifier or an identity of this wallet.
public protocol PrivacyChainReads: Sendable {
    func personhoodParams() async throws -> PrivacyReads.PersonhoodParams
    func ballotInputs(proposalID: UInt64, optionID: UInt64) async throws -> PrivacyReads.BallotInputs
    func epochNumber() async throws -> UInt64
    func snapshot(proposalID: UInt64) async throws -> PrivacyReads.Snapshot
    func positions() async throws -> [PrivacyReads.Position]
}

public enum PrivacyReads {
    public struct PersonhoodParams: Sendable, Equatable {
        public let caretakerVoteSeconds: Int64
        public let identityRootWindowSeconds: Int64
        public init(caretakerVoteSeconds: Int64, identityRootWindowSeconds: Int64) {
            self.caretakerVoteSeconds = caretakerVoteSeconds; self.identityRootWindowSeconds = identityRootWindowSeconds
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
        public init(scope: Fr, excludedDsc: Fr, excludedCountry: Fr, maxActivation: UInt64, round: UInt64, ballotID: UInt64) {
            self.scope = scope; self.excludedDsc = excludedDsc; self.excludedCountry = excludedCountry
            self.maxActivation = maxActivation; self.round = round; self.ballotID = ballotID
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
        public init(root: Fr, treeSize: UInt64, height: Int64 = 0, rates: [String: Decimal] = [:]) {
            self.root = root; self.treeSize = treeSize; self.height = height; self.rates = rates
        }
    }

    /// A Groundworks position (public), its owner known only by `ownerTag`.
    public struct Position: Sendable, Equatable, Identifiable {
        public let id: UInt64
        public let validator: String
        public let derth: UInt64
        public let ownerTag: Fr
        public let splits: [UInt64: UInt64]
        public let createdHeight: UInt64
        public init(id: UInt64, validator: String, derth: UInt64, ownerTag: Fr, splits: [UInt64: UInt64] = [:], createdHeight: UInt64 = 0) {
            self.id = id; self.validator = validator; self.derth = derth; self.ownerTag = ownerTag
            self.splits = splits; self.createdHeight = createdHeight
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

    public struct ValidatorBook: Sendable {
        public let validator: String
        public let rate: Decimal
        public let supply: UInt64
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
    public func lpShareSupply(poolID: UInt64) async throws -> String {
        try await rest.get("/cosmos/bank/v1beta1/supply/by_denom?denom=dexlp/\(poolID)").amount.amount.string(default: "0")
    }

    public func shieldedMinFee() async throws -> UInt64 {
        try await rest.get("/earth/shielded/v1/params").params.min_fee.uint64 ?? 1000
    }

    /// x/shielded params.max_actions_per_bundle (default 16).
    public func maxActionsPerBundle() async throws -> Int {
        let v = try await rest.get("/earth/shielded/v1/params").params.max_actions_per_bundle.uint64.map(Int.init) ?? 16
        return v >= 2 ? v : 16
    }

    public func personhoodParams() async throws -> PrivacyReads.PersonhoodParams {
        let p = try await rest.get("/earth/personhood/v1/params").params
        let r = p.caretaker_vote_seconds.int64(default: 0)
        let w = p.identity_root_window_seconds.int64(default: 0)
        return PrivacyReads.PersonhoodParams(caretakerVoteSeconds: r > 0 ? r : 30 * 86_400, identityRootWindowSeconds: w > 0 ? w : 3_600)
    }

    public func ballotInputs(proposalID: UInt64, optionID: UInt64) async throws -> PrivacyReads.BallotInputs {
        let q = proposalID != 0 ? "proposal_id=\(proposalID)" : "option_id=\(optionID)"
        let j = try await rest.get("/earth/assembly/v1/ballot_inputs?\(q)")
        return PrivacyReads.BallotInputs(scope: try field(j.scope), excludedDsc: try field(j.excluded_dsc),
                                         excludedCountry: try field(j.excluded_country), maxActivation: j.max_activation.uint64(default: 0),
                                         round: j.round.uint64(default: 0), ballotID: j.ballot_id.uint64(default: 0))
    }

    public func epoch() async throws -> PrivacyReads.Epoch {
        let e = try await rest.get("/earth/shieldedstaking/v1/epoch").epoch
        return PrivacyReads.Epoch(number: e.number.uint64(default: 0), startTime: e.start_time.int64(default: 0), endTime: e.end_time.int64(default: 0))
    }

    public func epochNumber() async throws -> UInt64 { try await epoch().number }

    public func validator(_ valoper: String) async throws -> PrivacyReads.ValidatorBook {
        let j = try await rest.get("/earth/shieldedstaking/v1/validators/\(valoper)")
        return PrivacyReads.ValidatorBook(validator: valoper, rate: Decimal(string: j.rate.string(default: "1")) ?? 1, supply: j.supply.uint64(default: 0))
    }

    public func snapshot(proposalID: UInt64) async throws -> PrivacyReads.Snapshot {
        let s = try await rest.get("/earth/shieldedstaking/v1/snapshots/\(proposalID)").snapshot
        var rates: [String: Decimal] = [:]
        for v in s.validators.array {
            if let r = Decimal(string: v.rate.string(default: "")) { rates[v.validator.string(default: "")] = r }
        }
        return PrivacyReads.Snapshot(root: try field(s.root), treeSize: s.tree_size.uint64(default: 0),
                                     height: s.height.int64(default: 0), rates: rates)
    }

    /// Chain-wide timing, the same answer for everyone: with the epoch it says
    /// when every epoch's unbond notes mature, so the wallet never has to ask
    /// the node about the (validator, epoch) records it holds.
    public func stakingTiming() async throws -> PrivacyReads.StakingTiming {
        let es = try await rest.get("/earth/shieldedstaking/v1/params").params.epoch_seconds.int64(default: 0)
        let ub = try await rest.get("/cosmos/staking/v1beta1/params").params.unbonding_time.string(default: "1814400s")
        let seconds = Int64(Double(ub.replacingOccurrences(of: "s", with: "")) ?? 1_814_400)
        return PrivacyReads.StakingTiming(epochSeconds: es > 0 ? es : 86_400, unbondingSeconds: seconds)
    }

    /// Every Groundworks position (public); the wallet finds its own by owner tag.
    public func positions() async throws -> [PrivacyReads.Position] {
        var out: [PrivacyReads.Position] = []
        var key: String?
        repeat {
            let path = "/earth/shieldedstaking/v1/positions" +
                (key.map { "?pagination.key=" + ($0.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? $0) } ?? "")
            let j = try await rest.get(path)
            for p in j.positions.array {
                var splits: [UInt64: UInt64] = [:]
                for s in p.splits.array { splits[s.option_id.uint64(default: 0)] = s.percent.uint64(default: 0) }
                out.append(PrivacyReads.Position(id: p.id.uint64(default: 0), validator: p.validator.string(default: ""),
                                                 derth: p.derth.uint64(default: 0), ownerTag: try field(p.owner_tag),
                                                 splits: splits, createdHeight: p.created_height.uint64(default: 0)))
            }
            key = j.pagination.next_key.string.flatMap { $0.isEmpty || $0 == "null" ? nil : $0 }
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
    /// non-zero code. `accepted` runs at CheckTx code 0, before the wait (K7).
    public func broadcast(_ tx: Data, accepted: @Sendable (String) -> Void) async throws -> TxResult {
        let j: JSON
        do {
            j = try await rest.postJSON("/cosmos/tx/v1beta1/txs", body: ["tx_bytes": tx.base64EncodedString(), "mode": "BROADCAST_MODE_SYNC"])
        } catch let e as EarthRest.Error {
            throw PrivacyError("broadcast failed \(Self.message(e))")
        }
        let code = j.tx_response.code.int64(default: 0)
        guard code == 0 else {
            throw EarthClient.Error.rejected(code: Int(code), log: j.tx_response.raw_log.string(default: ""))
        }
        guard let hash = j.tx_response.txhash.string else { throw EarthClient.Error.notCommitted(hash: "") }
        accepted(hash)
        _ = try await EarthClient(rest: rest).awaitCommit(hash)
        return try await fetch(hash)
    }

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
                        code: Int(tr.code.int64(default: 0)), log: tr.raw_log.string(default: ""))
    }
}

/// The chain's own view of the three trees (LCD), against which every root
/// the indexer served is checked before the wallet builds anything on it
/// (WalletSync.verifyRoots; PRIVACY_FORMATS 4b says what this trusts). The
/// identity and stake trees are read at the height the indexer's root is
/// from (`x-cosmos-block-height`), pinned only when the node echoes exactly
/// that height; otherwise (a pruned height, another height echoed) the
/// latest state is read and marked unpinned, which can verify equal trees but
/// never condemn different ones (K9). Ports LcdChainRoots.
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

    public func latestHeight() async -> UInt64? {
        (try? await rest.get("/cosmos/base/tendermint/v1beta1/blocks/latest")).flatMap { $0.block.header.height.uint64 }
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
        return ChainIdentity(chainID: net, genesis: hash.map { String($0.map { String(format: "%02x", $0) }.joined().prefix(16)) })
    }

    public func noteRoot(_ root: Fr) async throws -> NoteRootRecord? {
        let j = try await rest.get("/earth/shielded/v1/roots/\(root.hex)")
        guard j.record.exists, Self.field(j.record.root) == root else { return nil }
        return NoteRootRecord(valid: j.valid.bool(default: false), treeSize: j.record.tree_size.uint64(default: 0))
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
