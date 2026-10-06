import Foundation

/// Proves the privacy circuits. On a phone, the Barretenberg prover the app
/// installs; in tests, a fake that checks the witness instead.
public protocol PrivacyProver: Sendable {
    func proveAction(_ w: ActionWitness) async throws -> Data
    func proveStake(_ w: StakeWitness) async throws -> Data
    func proveMembership(_ w: MembershipWitness) async throws -> Data
    func proveVote(_ w: VoteWitness) async throws -> Data
}

/// A committed tx, as much of it as the wallet reads back.
public struct TxResult: Sendable {
    public let hash: String
    public let height: UInt64
    /// Block time, unix seconds.
    public let time: Int64
    /// (type, attributes) of every event the tx emitted.
    public let events: [(type: String, attributes: [String: String])]
    /// DeliverTx code: 0 is success (a looked-up tx may have failed in its block).
    public let code: Int
    public let log: String
    /// The module the code is from ("" for success or unknown): a code means nothing without it.
    public let codespace: String

    public init(hash: String, height: UInt64, time: Int64, events: [(type: String, attributes: [String: String])], code: Int = 0, log: String = "",
                codespace: String = "") {
        self.hash = hash; self.height = height; self.time = time; self.events = events; self.code = code; self.log = log; self.codespace = codespace
    }

    public func attr(_ type: String, _ key: String) -> String? {
        events.first { $0.type == type && $0.attributes[key] != nil }?.attributes[key]
    }
}

/// What the engine needs from the chain.
public protocol PrivateChain: Sendable {
    /// Gas used by `tx`, from the simulate endpoint.
    func simulate(_ tx: Data) async throws -> UInt64
    /// Broadcasts `tx` and waits for its block. `accepted` runs with the tx
    /// hash as soon as the node accepts it into the mempool (CheckTx code 0),
    /// before the wait: the caller records what it spent there, so a
    /// wait that times out or a killed app cannot lose it.
    func broadcast(_ tx: Data, accepted: @Sendable (String) -> Void) async throws -> TxResult
    /// A tx by hash: nil while the node does not know it (still in the mempool, or dropped).
    func tx(_ hash: String) async throws -> TxResult?
    /// The node's min gas price in uerth (CheckTx holds a private fee to it).
    func gasPrice() async throws -> Decimal
    /// x/shielded params.min_fee: the consensus floor on any private fee.
    func minFee() async throws -> UInt64
    /// x/shielded params.max_actions_per_bundle.
    func maxActionsPerBundle() async throws -> Int
    /// The chain's latest block height: a private tx's timeout_height is set from it.
    func tipHeight() async throws -> UInt64
}

/// A membership proof's statement, waiting for the sighash (its signal).
public struct MembershipWitnessSpec: Sendable {
    let make: @Sendable (Fr) throws -> MembershipWitness
    public init(_ make: @escaping @Sendable (Fr) throws -> MembershipWitness) { self.make = make }
    public func witness(signal: Fr) throws -> MembershipWitness { try make(signal) }
}

/// A vote proof's statement, waiting for the sighash; `vnfs` (both slots:
/// the notes' and the padding's, in the layout's order) are known before:
/// the sighash binds them.
public struct VoteWitnessSpec: Sendable {
    public let vnfs: [Fr]
    let make: @Sendable (Fr) throws -> VoteWitness
    public init(vnfs: [Fr], _ make: @escaping @Sendable (Fr) throws -> VoteWitness) throws {
        guard vnfs.count == MsgStakeVote.maxVoteNotes else { throw PrivacyError("a vote has \(MsgStakeVote.maxVoteNotes) vote nullifier slots") }
        guard !vnfs.contains(where: \.isZero) else { throw PrivacyError("every vote slot carries a vote nullifier (padding included)") }
        self.vnfs = vnfs; self.make = make
    }
    public func witness(sighash: Fr) throws -> VoteWitness {
        let w = try make(sighash)
        guard w.vnfs == vnfs else { throw PrivacyError("the vote witness is for other vote nullifiers") }
        return w
    }
}

/// A private msg in the making: its bundles, stake proof and membership
/// (everything but proofs, binding signatures and the sighash), and how to
/// assemble the msg once they exist. `build` gets the bundles (unproven or
/// proven, in `bundles` order), the stake proof and the membership. A stake
/// vote's `vote` proof and vote nullifier are set on the built msg by the
/// engine.
public struct Assembled {
    public let bundles: [BundlePlan]
    public let stake: StakePlan?
    public let membership: MembershipWitnessSpec?
    public let vote: VoteWitnessSpec?
    /// Gas declared beyond the simulation and its headroom: what the chain
    /// may charge by the tx's block that it did not when simulated (a move's
    /// pair reaching its entry cap: PrivacyWallet.redelegateHeadroom).
    public let extraGas: UInt64
    public let build: ([ShieldedBundle], StakeProof?, Membership?) throws -> any PrivateMsg

    public init(bundles: [BundlePlan], stake: StakePlan? = nil, membership: MembershipWitnessSpec? = nil, vote: VoteWitnessSpec? = nil,
                extraGas: UInt64 = 0, build: @escaping ([ShieldedBundle], StakeProof?, Membership?) throws -> any PrivateMsg) {
        self.bundles = bundles; self.stake = stake; self.membership = membership; self.vote = vote; self.extraGas = extraGas; self.build = build
    }

    /// The pool notes the msg spends.
    public var spends: [OwnedNote] { bundles.flatMap(\.spends) }

    /// The stake notes the msg spends: lane A's and the credit lane's (a move's destination note).
    public var stakeSpends: [OwnedStakeNote] { (stake?.spends ?? []) + [stake?.credit?.spend].compactMap { $0 } }
}

/// Runs a private tx end to end, the way the chain's ante requires. Ports
/// `privacy/tx/PrivateTxEngine.kt`:
///
///  1. lay the tx out at a fee, with placeholder proofs and binding
///     signatures over its real anchors, nullifiers, commitments, value
///     commitments and ciphertexts, and simulate it: the private ante charges
///     every proof's fixed gas in simulate mode and runs every state check,
///     but verifies nothing;
///  2. fee = max(min_fee, ceil(min gas price x gas limit)), the gas limit the
///     simulated gas plus headroom; lay the tx out again at that fee (its
///     change, and so the bundle, change with it) until the layout's own gas
///     is covered (a fee needing one more note adds an action);
///  3. compute the sighash, prove every action, the stake proof and the
///     membership over it, sign every bundle's balance, broadcast the
///     unsigned tx.
///
/// Gas is a function of the tx's shape alone (x/shielded/ante), so a fee
/// computed from the placeholder tx holds for the proven one.
public struct PrivateTxEngine: Sendable {
    public let chainID: String
    public let chain: PrivateChain
    public let prover: PrivacyProver

    /// A run's numbers, for display.
    public struct Quote: Equatable, Sendable {
        public let gasLimit: UInt64
        public let fee: UInt64
    }

    /// A proof-sized placeholder, so the simulated tx's size gas is the real tx's.
    public static let proofBytes = 14_656
    public static let placeholder = Data(count: proofBytes)
    /// A first guess at a private tx's gas; only sets the placeholder fee's size.
    public static let guessGas: UInt64 = 3_000_000
    /// Covers a fee's varint growing by a byte or two between the simulated and the final tx.
    public static let minHeadroom: UInt64 = 20_000
    static let maxRelays = 4
    /// Blocks past the chain's tip a private tx stays valid for (its timeout_height).
    public static let timeoutBlocks: UInt64 = 50
    /// The absolute cap on a private fee: 2 ERTH.
    public static let maxPrivateFee: UInt64 = 2_000_000
    /// How far past the last verified sync height a tip may be.
    public static let maxTipAhead: UInt64 = 1_000

    public static func tipSane(_ tip: UInt64, verified: UInt64) -> Bool { tip < verified || tip - verified <= maxTipAhead }

    /// Whether a pending mark's timeout_height `until` could have come from a
    /// sane tip, given the last verified height now (heights only grow): an
    /// outsized one is resolved by the tx's status alone.
    public static func timeoutSane(_ until: UInt64, verifiedNow: UInt64) -> Bool {
        until < verifiedNow || until - verifiedNow <= maxTipAhead + timeoutBlocks
    }

    // The chain's default gas schedule (x/shielded params, the proof
    // modules' verification charges), for the wallet's own estimate.
    public static let baseGas: UInt64 = 100_000
    public static let txByteGas: UInt64 = 10
    public static let bundleGas: UInt64 = 100_000
    /// One action: its proof (2,000,000) and two note writes (150,000 each).
    public static let actionGas: UInt64 = 2_300_000
    /// A stake proof (PRIVACY_FORMATS 11): its proof (2,000,000), two note
    /// writes per lane A nullifier slot and one for its output (the indexed
    /// nullifier tree rewrites two paths an insert), 5 x 150,000, and the
    /// msg's base (at most 400,000; the chain's PrivateActionGas).
    public static let stakeGas: UInt64 = 3_150_000
    /// A credit lane (a redelegation's), 750,000: its three note writes, and
    /// the 300,000 by which MsgRedelegate's base (700,000) exceeds the
    /// 400,000 in `stakeGas`.
    public static let creditGas: UInt64 = 3 * 150_000 + 300_000
    /// MsgRedelegate's gas for the (src, dst) pair's x/staking record at its
    /// worst (the chain's redelegateGas): 2,500 an entry read and written,
    /// 2,500 more each while the pair is at its 1,024-entry cap, and 128
    /// re-filed moves at 20,000. Simulation prices the real record (and
    /// Assembled.extraGas a merge the pair may reach before the tx lands);
    /// this keeps the cap above both whatever the node says. As Android.
    public static let redelegateRecordGas: UInt64 = 1_024 * (2_500 + 2_500) + 128 * 20_000
    /// A stake vote's fixed part: gasVote (250,000) and its proof (2,000,000);
    /// the chain adds a note write for the vote and one per vote nullifier,
    /// padding included: gasVote + proof + (1 + 2) x note_gas (PRIVACY_FORMATS 11).
    public static let voteGas: UInt64 = 2_250_000
    /// A membership proof and its nullifier write.
    public static let membershipGas: UInt64 = 2_150_000
    /// MsgRegister: the passport proof (3,000,000), the DSC chain (300,000) and two minted notes.
    public static let registerGas: UInt64 = 3_600_000
    /// One note write (x/shielded note_gas default).
    public static let noteGas: UInt64 = 150_000
    /// MsgBindHandle's writes beyond the one in `membershipGas`: the chain
    /// prices a bind as nine note writes.
    public static let bindHandleExtraGas: UInt64 = 8 * noteGas

    /// The absolute cap on any private fee, in uerth.
    public let maxFee: UInt64

    /// The fee the chain asks is more than the sheet showed: ask the user again at `fee`.
    public struct FeeAboveQuote: Swift.Error, LocalizedError {
        public let fee: UInt64
        public let shown: UInt64
        public var errorDescription: String? { "The fee is now \(fee)uerth, more than the \(shown)uerth shown; confirm again." }
    }

    /// The node's tip is not near the last verified sync height: sync and retry.
    public struct TipOutOfRange: Swift.Error, LocalizedError {
        public let tip: UInt64
        public let verified: UInt64
        public var errorDescription: String? {
            "The node says the chain is at height \(tip), far from the \(verified) this wallet last verified; sync again."
        }
    }

    /// The fee the node's pricing asks is past the wallet's cap: nothing is proven or sent.
    public struct FeeAboveCap: Swift.Error, LocalizedError {
        public let fee: UInt64
        public let cap: UInt64
        public var errorDescription: String? { "The node asks a \(fee)uerth fee, above this wallet's \(cap)uerth cap for this transaction." }
    }

    public init(chainID: String, chain: PrivateChain, prover: PrivacyProver, maxFee: UInt64 = PrivateTxEngine.maxPrivateFee) {
        self.chainID = chainID; self.chain = chain; self.prover = prover; self.maxFee = maxFee
    }

    /// The wallet's estimate of `msg`'s gas from its shape alone.
    public static func estimateGas(_ msg: any PrivateMsg, _ a: Assembled, txBytes: Int) -> UInt64 {
        var g = baseGas &+ txByteGas &* UInt64(txBytes)
        for b in msg.bundles { g = g &+ bundleGas &+ actionGas &* UInt64(b.actions.count) }
        if let p = msg.stakeProof {
            g = g &+ stakeGas
            if !p.creditNullifier.allSatisfy({ $0 == 0 }) { g = g &+ creditGas &+ redelegateRecordGas }
        }
        if a.membership != nil { g = g &+ membershipGas }
        if let v = a.vote { g = g &+ voteGas &+ (1 &+ UInt64(v.vnfs.count)) &* noteGas }
        if msg is MsgRegisterPrivate { g = g &+ registerGas }
        if msg is MsgBindHandle { g = g &+ bindHandleExtraGas }
        return g
    }

    /// The most this tx may pay: twice the wallet's own estimate
    /// from the tx's shape at the chain's default gas schedule, priced like
    /// the node's quote (and at least min_fee), never more than `maxFee`.
    public func feeCap(_ msg: any PrivateMsg, _ a: Assembled, txBytes: Int, minFee: UInt64, price: Decimal) -> UInt64 {
        let estimate = max(minFee, Self.feeFor(price: price, gas: Self.estimateGas(msg, a, txBytes: txBytes)))
        let (twice, o) = estimate.multipliedReportingOverflow(by: 2)
        return min(maxFee, o ? UInt64.max : twice)
    }

    /// The node's tip + `timeoutBlocks`. A tip past the last
    /// verified sync height (`verifiedHeight`, nil: no bound) by more than
    /// `maxTipAhead` is refused before anything is laid out: a node inflating
    /// it would leave the spent notes pending until a height the chain never
    /// reaches.
    private func timeoutHeight(_ verifiedHeight: UInt64?) async throws -> UInt64 {
        let tip = try await chain.tipHeight()
        if let v = verifiedHeight, !Self.tipSane(tip, verified: v) { throw TipOutOfRange(tip: tip, verified: v) }
        let (t, o) = tip.addingReportingOverflow(Self.timeoutBlocks)
        guard !o else { throw PrivacyError("the chain's height is out of range") }
        return t
    }

    public static func feeFor(price: Decimal, gas: UInt64) -> UInt64 {
        var raw = price * Decimal(gas)
        var rounded = Decimal()
        NSDecimalRound(&rounded, &raw, 0, .up)
        return NSDecimalNumber(decimal: rounded).uint64Value
    }

    /// Lays out, prices and simulates without proving: what a confirm sheet
    /// may show. Simulated with random placeholder nullifiers: the
    /// node learns nothing about which notes would be spent before the user
    /// confirms (gas is the tx's shape, the same either way).
    public func quote(_ assemble: (UInt64) throws -> Assembled, memo: String = "", verifiedHeight: UInt64? = nil) async throws -> Quote {
        try await price(assemble, memo: memo, timeout: try await timeoutHeight(verifiedHeight), placeholders: true).0
    }

    /// Prices, proves and broadcasts. The tx's `memo`, timeout_height (the
    /// chain's tip + `timeoutBlocks`) and the gas limit the pricing settled
    /// on are fixed first: the sighash binds them, so every proof is made
    /// over the tx exactly as broadcast. The fee is capped (`feeCap`) and,
    /// with `shownFee`, may not exceed what the confirm sheet showed.
    /// `accepted` gets the hash and the timeout height before the broadcast:
    /// spent notes stay pending until the chain is past it and says the tx is
    /// not in it; `rejected`, a refusal proving the tx is in no mempool.
    public func run(_ assemble: (UInt64) throws -> Assembled, memo: String = "", shownFee: UInt64? = nil, verifiedHeight: UInt64? = nil,
                    accepted: (String, Assembled, UInt64) -> Void = { _, _, _ in },
                    rejected: (String, Assembled) -> Void = { _, _ in }) async throws -> (TxResult, Assembled) {
        let timeout = try await timeoutHeight(verifiedHeight)
        let (q, a) = try await price(assemble, memo: memo, timeout: timeout, placeholders: false)
        if let shownFee, q.fee > shownFee { throw FeeAboveQuote(fee: q.fee, shown: shownFee) }
        let tx = PrivateMsgs.TxFields(memo: memo, timeoutHeight: timeout, gasLimit: q.gasLimit)
        let sighash = try draft(a).sighash(chainID: chainID, tx: tx)
        var bundles: [ShieldedBundle] = []
        for (i, plan) in a.bundles.enumerated() {
            let b = try await plan.prove(sighash: sighash) { try Self.proofSized(try await prover.proveAction($0)) }
            guard PrivateMsgs.checkBalance(b, sighash: sighash) else { throw PrivacyError("bundle \(i) does not balance") }
            bundles.append(b)
        }
        var stake: StakeProof?
        if let s = a.stake {
            let w = try s.witness(sighash: sighash)
            try w.check()
            stake = try s.proto(proof: try Self.proofSized(try await prover.proveStake(w)))
        }
        var membership: Membership?
        if let spec = a.membership {
            let w = try spec.witness(signal: sighash)
            membership = Membership(proof: try Self.proofSized(try await prover.proveMembership(w)), root: w.root.bytes, nullifier: w.nullifier.bytes)
        }
        var msg = try a.build(bundles, stake, membership)
        if let v = a.vote {
            let w = try v.witness(sighash: sighash)
            try w.check()
            msg = try Self.withVote(msg, vnfs: v.vnfs, proof: try Self.proofSized(try await prover.proveVote(w)))
        }
        guard try msg.sighash(chainID: chainID, tx: tx) == sighash else { throw PrivacyError("the proven msg binds another sighash") }
        guard msg.totalFee == q.fee else { throw PrivacyError("the msg must pay exactly the quoted fee") }
        try Self.checkShape(msg)
        let raw = UnsignedTx.build(msg, tx: tx)
        // What the tx spends is marked before it is sent, under the
        // hash computed here (the chain's own: SHA-256 of the bytes). A
        // broadcast whose answer is lost (a timeout, a killed app) after the
        // node took it never leaves its notes spendable; they are released
        // only once the chain says the tx is missing or failed past its
        // timeout_height. Only a refusal that proves the tx never entered a
        // mempool (CheckTx's code, no connection at all) undoes the mark.
        let hash = UnsignedTx.hash(raw)
        accepted(hash, a, timeout)
        // Whether the node took it: decided by the submit alone. Once it has,
        // a failure while waiting for the block (a dropped connection
        // included) leaves the marks for the chain to settle by hash.
        let submitted = SubmitFlag()
        do {
            let r = try await chain.broadcast(raw) { _ in submitted.set() }
            guard r.hash.uppercased() == hash else { throw PrivacyError("the node names the tx \(r.hash), not \(hash)") }
            return (r, a)
        } catch {
            if !submitted.isSet, error is UnsignedTx.TxRejected || Self.neverSent(error) { rejected(hash, a) }
            throw error
        }
    }

    private final class SubmitFlag: @unchecked Sendable {
        private let lock = NSLock()
        private var value = false
        func set() { lock.withLock { value = true } }
        var isSet: Bool { lock.withLock { value } }
    }

    /// No connection was ever made: the tx reached no node.
    static func neverSent(_ e: Swift.Error) -> Bool {
        guard let u = e as? URLError else { return false }
        return [.cannotConnectToHost, .cannotFindHost, .dnsLookupFailed, .notConnectedToInternet].contains(u.code)
    }

    /// The chain's wallet format rules, checked before broadcast: every
    /// action's output ciphertext exactly 217 bytes (dummies too); a stake
    /// proof's every field 32 bytes, exactly two lane A nullifiers, and a
    /// 201-byte wallet stake ciphertext exactly for each non-zero commitment;
    /// debt_root zero exactly when clear_before is 0.
    static func checkShape(_ msg: any PrivateMsg) throws {
        for b in msg.bundles {
            for a in b.actions where a.ciphertext.count != NoteCipher.ciphertextBytes {
                throw PrivacyError("an action ciphertext is \(a.ciphertext.count) bytes")
            }
        }
        if let p = msg.stakeProof {
            guard p.nullifiers.count == 2 else { throw PrivacyError("a stake proof carries two nullifiers") }
            for f in p.nullifiers + [p.anchor, p.ownerTag, p.commitment, p.creditNullifier, p.creditCommitment, p.debtRoot] where f.count != 32 {
                throw PrivacyError("a stake proof field is \(f.count) bytes")
            }
            for (cm, ct) in [(p.commitment, p.ciphertext), (p.creditCommitment, p.creditCiphertext)] {
                let zero = cm.allSatisfy { $0 == 0 }
                guard zero ? ct.isEmpty : ct.count == NoteCipher.stakeCiphertextBytes else { throw PrivacyError("a stake ciphertext is \(ct.count) bytes") }
            }
            guard (p.clearBefore == 0) == p.debtRoot.allSatisfy({ $0 == 0 }) else { throw PrivacyError("debt_root is zero exactly when clear_before is 0") }
        }
    }

    /// The chain refuses any proof that is not exactly `proofBytes` (bb ignored trailing bytes).
    static func proofSized(_ p: Data) throws -> Data {
        guard p.count == proofBytes else { throw PrivacyError("a proof is \(proofBytes) bytes, got \(p.count)") }
        return p
    }

    private func price(_ assemble: (UInt64) throws -> Assembled, memo: String, timeout: UInt64, placeholders: Bool) async throws -> (Quote, Assembled) {
        let minFee = try await chain.minFee()
        let price = try await chain.gasPrice()
        var fee = max(minFee, Self.feeFor(price: price, gas: Self.guessGas))
        var a = try assemble(fee)
        var first = true
        for _ in 0 ..< Self.maxRelays {
            let d = try draft(a, placeholders: placeholders)
            let raw = UnsignedTx.build(d, gasLimit: 0, memo: memo, timeoutHeight: timeout)
            let gas = try await chain.simulate(raw)
            let (headed, o1) = gas.addingReportingOverflow(max(gas / 10, Self.minHeadroom))
            let (limit, o) = headed.addingReportingOverflow(a.extraGas)
            guard !o1, !o else { throw PrivacyError("the simulated gas is out of range") }
            let need = max(minFee, Self.feeFor(price: price, gas: limit))
            // The guess is re-laid at the fee its layout needs; after that a
            // layout whose gas the fee covers is final (a fee needing one more
            // note, or one fewer leaving change, changes the action count, and
            // so the gas, so the fee may only rise from here).
            if need == fee || (need < fee && !first) {
                let cap = feeCap(d, a, txBytes: raw.count, minFee: minFee, price: price)
                if fee > cap { throw FeeAboveCap(fee: fee, cap: cap) }
                return (Quote(gasLimit: limit, fee: fee), a)
            }
            first = false
            fee = need
            a = try assemble(fee)
        }
        throw PrivacyError("the fee did not settle")
    }

    private func draft(_ a: Assembled, placeholders: Bool = false) throws -> any PrivateMsg {
        var bundles = a.bundles.map { $0.proto() }
        var stake = try a.stake?.proto(proof: Self.placeholder)
        if placeholders {
            for i in bundles.indices { for j in bundles[i].actions.indices { bundles[i].actions[j].nullifier = NotePlaintext.randomField().bytes } }
            if var p = stake {
                // A zero marks an unused slot and stays.
                for i in p.nullifiers.indices where !p.nullifiers[i].allSatisfy({ $0 == 0 }) { p.nullifiers[i] = NotePlaintext.randomField().bytes }
                if !p.creditNullifier.allSatisfy({ $0 == 0 }) { p.creditNullifier = NotePlaintext.randomField().bytes }
                stake = p
            }
        }
        let msg = try a.build(bundles, stake, try a.membership.map { try placeholderMembership($0, placeholders: placeholders) })
        // A quote's vote nullifiers are random too: the node learns nothing
        // of the notes before the user confirms.
        guard let v = a.vote else { return msg }
        let vnfs = placeholders ? v.vnfs.map { _ in NotePlaintext.randomField() } : v.vnfs
        return try Self.withVote(msg, vnfs: vnfs, proof: Self.placeholder)
    }

    /// A stake vote with its vote nullifiers and proof set (the sighash binds
    /// the nullifiers, not the proof): exactly `MsgStakeVote.maxVoteNotes`,
    /// the used slots' first, then zeros.
    static func withVote(_ msg: any PrivateMsg, vnfs: [Fr], proof: Data) throws -> any PrivateMsg {
        guard var m = msg as? MsgStakeVote else { throw PrivacyError("not a stake vote") }
        guard vnfs.count == MsgStakeVote.maxVoteNotes else { throw PrivacyError("a stake vote carries exactly \(MsgStakeVote.maxVoteNotes) vote nullifiers") }
        m.voteNullifiers = vnfs.map(\.bytes)
        m.proof = proof
        return m
    }

    /// The membership's real root and nullifier (the chain checks both before any proof), a placeholder proof.
    private func placeholderMembership(_ spec: MembershipWitnessSpec, placeholders: Bool) throws -> Membership {
        let w = try spec.witness(signal: .zero)
        return Membership(proof: Self.placeholder, root: w.root.bytes,
                          nullifier: placeholders ? NotePlaintext.randomField().bytes : w.nullifier.bytes)
    }
}
