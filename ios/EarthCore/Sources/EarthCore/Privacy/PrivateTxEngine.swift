import Foundation

/// Proves the privacy circuits. On a phone, the Barretenberg prover the app
/// installs; in tests, a fake that checks the witness instead.
public protocol PrivacyProver: Sendable {
    func proveAction(_ w: ActionWitness) async throws -> Data
    func proveStake(_ w: StakeWitness) async throws -> Data
    func proveMembership(_ w: MembershipWitness) async throws -> Data
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

    public init(hash: String, height: UInt64, time: Int64, events: [(type: String, attributes: [String: String])], code: Int = 0, log: String = "") {
        self.hash = hash; self.height = height; self.time = time; self.events = events; self.code = code; self.log = log
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
    /// before the wait (K7): the caller records what it spent there, so a
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
}

/// A membership proof's statement, waiting for the sighash (its signal).
public struct MembershipWitnessSpec: Sendable {
    let make: @Sendable (Fr) throws -> MembershipWitness
    public init(_ make: @escaping @Sendable (Fr) throws -> MembershipWitness) { self.make = make }
    public func witness(signal: Fr) throws -> MembershipWitness { try make(signal) }
}

/// A private msg in the making: its bundles, stake proof and membership
/// (everything but proofs, binding signatures and the sighash), and how to
/// assemble the msg once they exist. `build` gets the bundles (unproven or
/// proven, in `bundles` order), the stake proof and the membership.
public struct Assembled {
    public let bundles: [BundlePlan]
    public let stake: StakePlan?
    public let membership: MembershipWitnessSpec?
    public let build: ([ShieldedBundle], StakeProof?, Membership?) throws -> any PrivateMsg

    public init(bundles: [BundlePlan], stake: StakePlan? = nil, membership: MembershipWitnessSpec? = nil,
                build: @escaping ([ShieldedBundle], StakeProof?, Membership?) throws -> any PrivateMsg) {
        self.bundles = bundles; self.stake = stake; self.membership = membership; self.build = build
    }

    /// The pool notes the msg spends.
    public var spends: [OwnedNote] { bundles.flatMap(\.spends) }

    /// The stake notes the msg spends.
    public var stakeSpends: [OwnedStakeNote] { stake?.spends ?? [] }
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

    public init(chainID: String, chain: PrivateChain, prover: PrivacyProver) {
        self.chainID = chainID; self.chain = chain; self.prover = prover
    }

    public static func feeFor(price: Decimal, gas: UInt64) -> UInt64 {
        var raw = price * Decimal(gas)
        var rounded = Decimal()
        NSDecimalRound(&rounded, &raw, 0, .up)
        return NSDecimalNumber(decimal: rounded).uint64Value
    }

    /// Lays out, prices and simulates without proving: what the confirm sheet shows.
    public func quote(_ assemble: (UInt64) throws -> Assembled, memo: String = "") async throws -> Quote {
        try await price(assemble, memo: memo).0
    }

    /// Prices, proves and broadcasts. The tx's `memo`, timeout_height (none)
    /// and the gas limit the pricing settled on are fixed first: the sighash
    /// binds them, so every proof is made over the tx exactly as broadcast.
    public func run(_ assemble: (UInt64) throws -> Assembled, memo: String = "",
                    accepted: @Sendable (String, Assembled) -> Void = { _, _ in }) async throws -> (TxResult, Assembled) {
        let (q, a) = try await price(assemble, memo: memo)
        let tx = PrivateMsgs.TxFields(memo: memo, timeoutHeight: 0, gasLimit: q.gasLimit)
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
        let msg = try a.build(bundles, stake, membership)
        guard try msg.sighash(chainID: chainID, tx: tx) == sighash else { throw PrivacyError("the proven msg binds another sighash") }
        guard msg.totalFee == q.fee else { throw PrivacyError("the msg must pay exactly the quoted fee") }
        try Self.checkShape(msg)
        let assembled = a
        return (try await chain.broadcast(UnsignedTx.build(msg, tx: tx)) { accepted($0, assembled) }, a)
    }

    /// The chain's wallet format rules (round 2), checked before broadcast:
    /// every action's output ciphertext exactly 217 bytes (dummies too); a
    /// stake proof's ciphertexts exactly two, entry i empty iff commitment i
    /// is zero, a non-empty one exactly 153 bytes.
    static func checkShape(_ msg: any PrivateMsg) throws {
        for b in msg.bundles {
            for a in b.actions where a.ciphertext.count != NoteCipher.ciphertextBytes {
                throw PrivacyError("an action ciphertext is \(a.ciphertext.count) bytes")
            }
        }
        if let p = msg.stakeProof {
            guard p.ciphertexts.count == 2, p.commitments.count == 2 else { throw PrivacyError("a stake proof carries two ciphertext slots") }
            for i in 0 ..< 2 {
                let zero = p.commitments[i].allSatisfy { $0 == 0 }
                let n = p.ciphertexts[i].count
                guard zero ? n == 0 : n == NoteCipher.stakeCiphertextBytes else { throw PrivacyError("stake ciphertext \(i) is \(n) bytes") }
            }
        }
    }

    /// The chain refuses any proof that is not exactly `proofBytes` (bb ignored trailing bytes).
    static func proofSized(_ p: Data) throws -> Data {
        guard p.count == proofBytes else { throw PrivacyError("a proof is \(proofBytes) bytes, got \(p.count)") }
        return p
    }

    private func price(_ assemble: (UInt64) throws -> Assembled, memo: String) async throws -> (Quote, Assembled) {
        let minFee = try await chain.minFee()
        let price = try await chain.gasPrice()
        var fee = max(minFee, Self.feeFor(price: price, gas: Self.guessGas))
        var a = try assemble(fee)
        var first = true
        for _ in 0 ..< Self.maxRelays {
            let gas = try await chain.simulate(UnsignedTx.build(try draft(a), gasLimit: 0, memo: memo))
            let (limit, o) = gas.addingReportingOverflow(max(gas / 10, Self.minHeadroom))
            guard !o else { throw PrivacyError("the simulated gas is out of range") }
            let need = max(minFee, Self.feeFor(price: price, gas: limit))
            // The guess is re-laid at the fee its layout needs; after that a
            // layout whose gas the fee covers is final (a fee needing one more
            // note, or one fewer leaving change, changes the action count, and
            // so the gas, so the fee may only rise from here).
            if need == fee || (need < fee && !first) { return (Quote(gasLimit: limit, fee: fee), a) }
            first = false
            fee = need
            a = try assemble(fee)
        }
        throw PrivacyError("the fee did not settle")
    }

    private func draft(_ a: Assembled) throws -> any PrivateMsg {
        try a.build(a.bundles.map { $0.proto() }, try a.stake?.proto(proof: Self.placeholder), try a.membership.map(placeholderMembership))
    }

    /// The membership's real root and nullifier (the chain checks both before any proof), a placeholder proof.
    private func placeholderMembership(_ spec: MembershipWitnessSpec) throws -> Membership {
        let w = try spec.witness(signal: .zero)
        return Membership(proof: Self.placeholder, root: w.root.bytes, nullifier: w.nullifier.bytes)
    }
}
