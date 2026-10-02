import Foundation

/// Proves the two privacy circuits. On a phone, the Barretenberg prover the
/// app installs; in tests, a fake that checks the witness instead.
public protocol PrivacyProver: Sendable {
    func proveTransfer(_ w: TransferWitness) async throws -> Data
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

    public init(hash: String, height: UInt64, time: Int64, events: [(type: String, attributes: [String: String])]) {
        self.hash = hash; self.height = height; self.time = time; self.events = events
    }

    public func attr(_ type: String, _ key: String) -> String? {
        events.first { $0.type == type && $0.attributes[key] != nil }?.attributes[key]
    }
}

/// What the engine needs from the chain.
public protocol PrivateChain: Sendable {
    /// Gas used by `tx`, from the simulate endpoint.
    func simulate(_ tx: Data) async throws -> UInt64
    func broadcast(_ tx: Data) async throws -> TxResult
    /// The node's min gas price in uerth (CheckTx holds a private fee to it).
    func gasPrice() async throws -> Decimal
    /// x/shielded params.min_fee: the consensus floor on any private fee.
    func minFee() async throws -> UInt64
}

/// A membership proof's statement, waiting for the signal.
public struct MembershipWitnessSpec: Sendable {
    let make: @Sendable (Fr) throws -> MembershipWitness
    public init(_ make: @escaping @Sendable (Fr) throws -> MembershipWitness) { self.make = make }
    public func witness(signal: Fr) throws -> MembershipWitness { try make(signal) }
}

/// A private msg in the making: its transfers and membership (everything but
/// proofs and the signal), and how to assemble the msg once they exist.
/// `build` gets the transfers' protos (proofs placeholder or real, in
/// `transfers` order) and the membership proto, if any.
public struct Assembled {
    public let transfers: [TransferPlan]
    public let membership: MembershipWitnessSpec?
    public let build: ([ShieldedTransfer], Membership?) throws -> any PrivateMsg

    public init(transfers: [TransferPlan], membership: MembershipWitnessSpec? = nil,
                build: @escaping ([ShieldedTransfer], Membership?) throws -> any PrivateMsg) {
        self.transfers = transfers; self.membership = membership; self.build = build
    }
}

/// Runs a private tx end to end, the way the chain's ante requires. Ports
/// `privacy/tx/PrivateTxEngine.kt`:
///
///  1. lay the tx out at a guessed fee, with placeholder proofs over its real
///     roots, nullifiers, commitments and ciphertexts, and simulate it: the
///     private ante charges every proof's fixed gas in simulate mode and runs
///     every state check, but verifies nothing;
///  2. fee = max(min_fee, ceil(min gas price x gas limit)), the gas limit the
///     simulated gas plus headroom; if it differs from the guess, lay the tx
///     out again at that fee (the fee note's change, and so its ciphertext and
///     the signal, change with it);
///  3. compute the msg's signal, prove every transfer and the membership over
///     it, and broadcast the unsigned tx.
///
/// Gas is a function of the tx's size and shape alone (x/shielded/ante), so a
/// fee computed from the placeholder tx holds for the proven one.
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
    public func quote(_ assemble: (UInt64) throws -> Assembled) async throws -> Quote {
        try await price(assemble).0
    }

    public func run(_ assemble: (UInt64) throws -> Assembled) async throws -> (TxResult, Assembled) {
        let (q, a) = try await price(assemble)
        let draft = try a.build(a.transfers.map { $0.proto(proof: Self.placeholder) }, try a.membership.map(placeholderMembership))
        let signal = try draft.signal(chainID: chainID)
        var proofs: [Data] = []
        for t in a.transfers { proofs.append(try await prover.proveTransfer(t.witness(signal: signal))) }
        var membership: Membership?
        if let spec = a.membership {
            let w = try spec.witness(signal: signal)
            membership = Membership(proof: try await prover.proveMembership(w), root: w.root.bytes, nullifier: w.nullifier.bytes)
        }
        let msg = try a.build(a.transfers.enumerated().map { $0.element.proto(proof: proofs[$0.offset]) }, membership)
        guard try msg.signal(chainID: chainID) == signal else { throw PrivacyError("the proven msg binds another signal") }
        guard msg.totalFee == q.fee else { throw PrivacyError("the msg must pay exactly the quoted fee") }
        return (try await chain.broadcast(UnsignedTx.build(msg, gasLimit: q.gasLimit)), a)
    }

    private func price(_ assemble: (UInt64) throws -> Assembled) async throws -> (Quote, Assembled) {
        let minFee = try await chain.minFee()
        let price = try await chain.gasPrice()
        let guess = max(minFee, Self.feeFor(price: price, gas: Self.guessGas))
        var a = try assemble(guess)
        let draft = try a.build(a.transfers.map { $0.proto(proof: Self.placeholder) }, try a.membership.map(placeholderMembership))
        let gas = try await chain.simulate(UnsignedTx.build(draft, gasLimit: 0))
        let limit = gas + max(gas / 10, Self.minHeadroom)
        let fee = max(minFee, Self.feeFor(price: price, gas: limit))
        if fee != guess { a = try assemble(fee) }
        return (Quote(gasLimit: limit, fee: fee), a)
    }

    /// The membership's real root and nullifier (the chain checks both before any proof), a placeholder proof.
    private func placeholderMembership(_ spec: MembershipWitnessSpec) throws -> Membership {
        let w = try spec.witness(signal: .zero)
        return Membership(proof: Self.placeholder, root: w.root.bytes, nullifier: w.nullifier.bytes)
    }
}
