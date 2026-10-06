import Foundation
import XCTest
@testable import EarthCore

/// What the wallet-against-`FakeChain` suites share: the test mnemonics and
/// validators, a wallet over a chain (any part of it replaceable), funding,
/// registration, and the witness dump. Holds no tests itself, so nothing is
/// run twice by a subclass.
class PrivacyTestCase: XCTestCase {
    let alice = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    let bob = "legal winner thank year wave sausage worth useful legal winner thank yellow"
    let carol = "letter advice cage absurd amount doctor acoustic avoid letter advice cage above"
    let validator = "earthvaloper1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"
    let validator2 = "earthvaloper1qyqszqgpqyqszqgpqyqszqgpqyqszqgpjnp7du"
    let receiver = "earth1qqqsyqcyq5rqwzqfpg9scrgwpugpzysncc2uls"
    let yes = [WeightedVoteOption(option: WeightedVoteOption.yes, weight: "1")]

    /// A wallet over `chain` (alice's unless `words`), with any of the
    /// chain's roles replaced.
    func wallet(_ chain: FakeChain, _ words: String? = nil, indexer: PrivacyIndexer? = nil, store: PrivacyStore = .memory(),
                roots: ChainRoots? = nil, reads: PrivacyChainReads? = nil, privateChain: PrivateChain? = nil,
                now: (@Sendable () -> Int64)? = nil) throws -> PrivacyWallet {
        PrivacyWallet(keys: try PrivacyKeys.fromMnemonic(words ?? alice), store: store, indexer: indexer ?? chain, chain: privateChain ?? chain,
                      reads: reads ?? FakeReads(chain: chain), prover: chain.prover, chainID: chain.chainID, roots: roots ?? chain,
                      now: now ?? { [unowned chain] in chain.now })
    }

    func bal(_ w: PrivacyWallet, _ d: String = "uerth") -> UInt64 { w.balances()[d] ?? 0 }

    /// Shields `amount` uerth to `w` (not synced).
    func funded(_ chain: FakeChain, _ w: PrivacyWallet, _ amount: UInt64 = 1_000_000) throws {
        let o = try w.shieldOutput(denom: "uerth", amount: 0)
        chain.shield("uerth", amount, o.pc, o.ciphertext)
    }

    /// Shields `v` uerth to `w` and syncs.
    func fund(_ chain: FakeChain, _ w: PrivacyWallet, _ v: UInt64) async throws {
        let o = try w.shieldOutput(denom: "uerth", amount: v)
        chain.shield("uerth", v, o.pc, o.ciphertext)
        try await w.sync()
    }

    /// A value-blind mint of `value` `denom` to `w` (what every chain mint is), in the block being built; its position.
    @discardableResult
    func mintTo(_ chain: FakeChain, _ w: PrivacyWallet, _ denom: String, _ value: UInt64) throws -> Int {
        let n = NotePlaintext.fresh(denom, value)
        let pos = chain.notes.count
        chain.shield(denom, value, n.pc(ownerPK: w.keys.ownerPK), try NoteCipher.encryptBlind(n, to: w.keys.address))
        return pos
    }

    /// A passport proof's public signals with `nullifier` as the passport nullifier.
    func signals(_ prep: PrivacyWallet.RegistrationPrep, _ nullifier: String) -> [String] {
        ["261001", prep.binding.bigUInt.description, nullifier, Fr(UInt64(77)).bigUInt.description]
    }

    /// Registers `w` with `passport` (a switch when another wallet holds it), referred by `referrer`, and syncs it live.
    @discardableResult
    func register(_ chain: FakeChain, _ w: PrivacyWallet, passport: String, referrer: PrivacyWallet.Referrer? = nil) async throws -> PrivacyWallet.RegistrationPrep {
        let prep = try await w.prepareRegistration(referrer: referrer)
        chain.shield("uerth", 100_000, prep.gas.pc, prep.gas.ciphertext)
        try await w.sync()
        _ = try await w.register(prep, proof: Data(count: 14_656), publicSignals: signals(prep, passport), signatureAlgorithm: "lean_poa",
                                 dscDer: Data(count: 10))
        try await w.sync()
        XCTAssertEqual(.live, w.identityStatus())
        return prep
    }

    func registered(_ chain: FakeChain, _ w: PrivacyWallet, nullifier: String = "555") async throws {
        try await register(chain, w, passport: nullifier)
    }

    /// A wallet funded with 4 x 2 ERTH that delegated 1 ERTH to `validator`
    /// (synced); `due` is when FakeReads says an epoch's undelegations pay out.
    func delegatedWallet(_ chain: FakeChain, due: @escaping (UInt64) -> Int64? = { _ in nil }) async throws -> PrivacyWallet {
        let a = try wallet(chain, reads: FakeReads(chain: chain, due: due))
        for _ in 0 ..< 4 { try funded(chain, a, 2_000_000) }
        try await a.sync()
        _ = try await a.delegate(validator: validator, amount: 1_000_000); try await a.sync()
        return a
    }

    func assertThrowsAsync<T>(_ body: () async throws -> T, _ check: (Error) -> Bool = { _ in true }, line: UInt = #line) async {
        do {
            _ = try await body()
            XCTFail("expected an error", line: line)
        } catch {
            XCTAssertTrue(check(error), "unexpected error \(error)", line: line)
        }
    }

    func isInconsistent(_ e: Error) -> Bool { e is WalletSync.Inconsistent }

    func tmp() throws -> URL {
        let u = FileManager.default.temporaryDirectory.appendingPathComponent("earth-test-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
        return u
    }

    func entry(_ chain: FakeChain, _ h: String) async throws -> HandleEntry? { try await chain.handleDirectory().lookup(h) }

    /// With PRIVACY_TOML_OUT set, every witness `chain` proved as a nargo
    /// Prover.toml under <out>/<kind>/<test>_<i>, for `nargo execute` against
    /// the real circuits.
    func dump(_ chain: FakeChain, _ test: String) {
        guard let out = ProcessInfo.processInfo.environment["PRIVACY_TOML_OUT"] else { return }
        func write(_ kind: String, _ i: Int, _ toml: String) {
            let dir = URL(fileURLWithPath: out).appendingPathComponent(kind).appendingPathComponent("\(test)_\(i)")
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            try? toml.write(to: dir.appendingPathComponent("Prover.toml"), atomically: true, encoding: .utf8)
        }
        for (i, w) in chain.prover.allActions.enumerated() { write("action", i, w.proverToml()) }
        for (i, w) in chain.prover.allStakes.enumerated() { write("stake", i, w.proverToml()) }
        for (i, w) in chain.prover.allVotes.enumerated() { write("vote", i, w.proverToml()) }
        for (i, w) in chain.prover.allMemberships.enumerated() { write("membership", i, w.proverToml()) }
    }
}

final class Tally: @unchecked Sendable {
    private let l = NSLock()
    private var n = 0
    func inc() -> Int { l.lock(); defer { l.unlock() }; n += 1; return n }
    var value: Int { l.lock(); defer { l.unlock() }; return n }
}

final class Box<T>: @unchecked Sendable { var v: T?; init() {} }

/// The chain's own queries, recording the nullifiers asked and shifting block times.
final class RecordingRoots: ChainRoots, @unchecked Sendable {
    let chain: FakeChain
    var asked: [Fr] = []
    var timeShift: Int64 = 0
    init(_ chain: FakeChain) { self.chain = chain }
    func noteRoot(_ root: Fr) async throws -> NoteRootRecord? { try await chain.noteRoot(root) }
    func identityTree(height: UInt64?) async throws -> TreeState { try await chain.identityTree(height: height) }
    func stakeTree(height: UInt64?) async throws -> TreeState { try await chain.stakeTree(height: height) }
    func nullifierSpent(_ nf: Fr) async -> Bool? { asked.append(nf); return await chain.nullifierSpent(nf) }
    func stakeNullifierSpent(_ nf: Fr) async -> Bool? { await chain.stakeNullifierSpent(nf) }
    func latestHeight() async -> UInt64? { await chain.latestHeight() }
    func blockTime(_ height: UInt64) async -> UInt64? { await chain.blockTime(height).map { UInt64(Int64($0) + timeShift) } }
    func chainIdentity() async -> ChainIdentity? { await chain.chainIdentity() }
}

/// A private chain that takes a broadcast and never answers it.
final class LostAnswerChain: PrivateChain, @unchecked Sendable {
    let chain: FakeChain
    init(_ chain: FakeChain) { self.chain = chain }
    func simulate(_ tx: Data) async throws -> UInt64 { try await chain.simulate(tx) }
    func broadcast(_ tx: Data, accepted: @Sendable (String) -> Void) async throws -> TxResult {
        _ = try await chain.broadcast(tx) { _ in }
        throw URLError(.timedOut)
    }
    func tx(_ hash: String) async throws -> TxResult? { try await chain.tx(hash) }
    func gasPrice() async throws -> Decimal { try await chain.gasPrice() }
    func minFee() async throws -> UInt64 { try await chain.minFee() }
    func maxActionsPerBundle() async throws -> Int { try await chain.maxActionsPerBundle() }
    func tipHeight() async throws -> UInt64 { try await chain.tipHeight() }
}

/// Records moves into `store` as the app does for another wallet on the
/// phone; `failFirst` makes the first write fail. Takes every move.
final class MoveRecorderStub: PrivacyWallet.MoveRecorder, @unchecked Sendable {
    let store: PrivacyStore
    let now: () -> Int64
    var failFirst: Bool
    let targetID = "target"
    init(_ store: PrivacyStore, now: @escaping () -> Int64, failFirst: Bool = false) { self.store = store; self.now = now; self.failFirst = failFirst }
    func record(_ move: PendingMove) throws {
        if failFirst { failFirst = false; throw PrivacyError("disk full (test)") }
        try PrivacyWallet.recordIncoming(store, move, now: now())
    }
    func rollback(_ move: PendingMove) throws { try PrivacyWallet.rollbackIncoming(store, move, now: now()) }
}

/// As `MoveRecorderStub`, but refusing what the target store cannot take (`targetRefusal`).
final class CheckingMoveRecorder: PrivacyWallet.MoveRecorder, @unchecked Sendable {
    let store: PrivacyStore
    let now: () -> Int64
    let targetID: String
    init(_ store: PrivacyStore, now: @escaping () -> Int64, targetID: String = "target") { self.store = store; self.now = now; self.targetID = targetID }
    func record(_ move: PendingMove) throws { try PrivacyWallet.recordIncoming(store, move, now: now()) }
    func rollback(_ move: PendingMove) throws { try PrivacyWallet.rollbackIncoming(store, move, now: now()) }
    func refusal(_ move: PendingMove) -> String? { PrivacyWallet.targetRefusal(store.state, kind: move.kind, now: now()) }
}

/// The install data key the tests seal stores under (WalletStore.Opened.dataKey in the app).
let testDataKey = Data((0 ..< 32).map { UInt8($0) })

/// `validator`'s ERTH value of `derth` at its live book rate: what a vote preview shows when the snapshot carries no rates.
func liveValue(_ chain: FakeChain, _ validator: String, _ derth: UInt64) -> UInt64 {
    let book = try! chain.validatorsRead().of(validator)
    return PrivacyWallet.derthValue(derth, rate: Decimal(string: book.backing.description)! / Decimal(string: book.supply.description)!)
}
