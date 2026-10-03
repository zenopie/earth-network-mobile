import Foundation
import XCTest
@testable import EarthCore

/// Stake votes without spending (ORCHARD_DESIGN 15), the chain's
/// TestStakeVoteConcurrentProposals as wallet flows against `FakeChain`: one
/// note on two concurrently open proposals, unlinkable vote nullifiers, a
/// second vote refused (locally, and by the chain for a restored wallet), a
/// note spent before the snapshot refused locally, one restaked after it
/// still voting while its outputs cannot, the note spendable throughout; the
/// indexer's nullifier stream and the LCD fallback; the rounded weight.
/// Ports StakeVoteFlowTest.kt.
final class StakeVoteFlowTests: XCTestCase {
    let alice = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    let vB = "earthvaloper1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"
    let yes = [WeightedVoteOption(option: WeightedVoteOption.yes, weight: "1")]
    let no = [WeightedVoteOption(option: WeightedVoteOption.no, weight: "1")]
    let abstain = [WeightedVoteOption(option: WeightedVoteOption.abstain, weight: "1")]

    func wallet(_ chain: FakeChain, indexer: PrivacyIndexer? = nil) throws -> PrivacyWallet {
        PrivacyWallet(keys: try PrivacyKeys.fromMnemonic(alice), store: .memory(), indexer: indexer ?? chain, chain: chain,
                      reads: FakeReads(chain: chain), prover: chain.prover, chainID: chain.chainID, roots: chain,
                      now: { [unowned chain] in chain.now })
    }

    func funded(_ chain: FakeChain, _ w: PrivacyWallet, _ amount: UInt64) throws {
        let o = try w.shieldOutput(denom: "uerth", amount: 0)
        chain.shield("uerth", amount, o.pc, o.ciphertext)
    }

    func assertThrowsAsync<T>(_ body: () async throws -> T, _ check: (Error) -> Bool = { _ in true }, line: UInt = #line) async {
        do {
            _ = try await body()
            XCTFail("expected an error", line: line)
        } catch {
            XCTAssertTrue(check(error), "unexpected error \(error)", line: line)
        }
    }

    func note(_ a: PrivacyWallet, _ position: UInt64) -> OwnedStakeNote { a.stakeNotes.first { $0.position == position }! }

    func delegate(_ a: PrivacyWallet, _ amount: UInt64) async throws -> OwnedStakeNote {
        _ = try await a.delegate(validator: vB, amount: amount); try await a.sync()
        return a.stakeNotes.max { $0.position < $1.position }!
    }

    /// Restakes `n` into two notes; returns them.
    func restakeAll(_ a: PrivacyWallet, _ n: OwnedStakeNote) async throws -> [OwnedStakeNote] {
        let before = Set(a.stakeNotes.map(\.position))
        _ = try await a.restake(validator: vB, notes: [n], amounts: [n.amount / 2, n.amount - n.amount / 2]); try await a.sync()
        return a.stakeNotes.filter { !before.contains($0.position) }
    }

    struct Scenario { let chain: FakeChain; let a: PrivacyWallet; let n: OwnedStakeNote; let k: OwnedStakeNote; let m: OwnedStakeNote; let m1: [OwnedStakeNote] }

    /// n, k, m delegated; m restaked (spent) before proposals 1 and 2 open together.
    func scenario(_ chain: FakeChain = FakeChain()) async throws -> Scenario {
        let a = try wallet(chain)
        for _ in 0 ..< 12 { try funded(chain, a, 2_000_000) }
        try await a.sync()
        let n = try await delegate(a, 1_111_111)
        let k = try await delegate(a, 666_666)
        let m = try await delegate(a, 333_333)
        let m1 = try await restakeAll(a, m)
        let s1 = chain.openProposal(1)
        let s2 = chain.openProposal(2)
        XCTAssertEqual(2, s1.nfSize) // the sentinel and m's nullifier
        XCTAssertEqual(s1.nfRoot, s2.nfRoot)
        try await a.sync()
        return Scenario(chain: chain, a: a, n: n, k: k, m: note(a, m.position), m1: m1)
    }

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
    }

    func testOneNoteVotesOnTwoConcurrentProposals() async throws {
        let sc = try await scenario()
        let (chain, a, n) = (sc.chain, sc.a, sc.n)
        let proofsBefore = chain.prover.allStakes.count
        _ = try await a.stakeVote(proposalID: 1, note: n, options: yes)
        _ = try await a.stakeVote(proposalID: 2, note: n, options: no)
        // No stake proof, nothing minted, nothing spent.
        XCTAssertEqual(proofsBefore, chain.prover.allStakes.count)
        XCTAssertNil(chain.stakeNullifiers[n.nf])
        try await a.sync()
        XCTAssertTrue(note(a, n.position).spendable)
        // Unlinkable: one vote nullifier per proposal, neither the spend nullifier.
        let vnfs = chain.voteNullifiers.map { $0[1] }
        XCTAssertEqual(2, Set(vnfs).count)
        XCTAssertFalse(vnfs.contains(n.nf))
        // Weighted at its rounded amount (3 significant figures): 1,111,111 x 9/10 = 999,999 uderth.
        let w = try PrivacyWallet.voteWeight(n.amount)
        XCTAssertEqual(999_000, w)
        XCTAssertEqual(chain.stakeVotes.map { "\($0.0) \($0.1) \($0.2)" }, ["1 \(vB) \(w)", "2 \(vB) \(w)"])

        // Again on 1: refused here, nothing simulated.
        let sims = chain.simulated
        await assertThrowsAsync({ try await a.stakeVote(proposalID: 1, note: n, options: self.no) }) { $0 is PrivacyWallet.AlreadyVoted }
        XCTAssertEqual(sims, chain.simulated)
        let items1 = try await a.stakeVoteItems(proposalID: 1)
        let items2 = try await a.stakeVoteItems(proposalID: 2)
        XCTAssertFalse(items1.contains(.note(n.position)))
        XCTAssertFalse(items2.contains(.note(n.position)))

        // n is still an ordinary note: it undelegates.
        _ = try await a.undelegate(validator: vB, amount: 10_000); try await a.sync()
        dump(chain, "stakeVoteConcurrent")
    }

    func testSpentBeforeTheSnapshotIsRefusedLocally() async throws {
        let sc = try await scenario()
        let (chain, a) = (sc.chain, sc.a)
        // m's nullifier is under nf_root: no low leaf proves it absent, nothing is simulated.
        let items = try await a.stakeVoteItems(proposalID: 1)
        XCTAssertFalse(items.contains(.note(sc.m.position)))
        let sims = chain.simulated
        await assertThrowsAsync({ try await a.stakeVote(proposalID: 1, note: sc.m, options: self.yes) }) { $0 is PrivacyWallet.SpentBeforeSnapshot }
        let none = try await a.castStakeVote(proposalID: 1, item: .note(sc.m.position), options: yes)
        XCTAssertNil(none)
        XCTAssertEqual(sims, chain.simulated)
        // Its outputs came before the snapshot: they vote.
        for o in sc.m1 { _ = try await a.stakeVote(proposalID: 1, note: o, options: no) }
        XCTAssertEqual(try sc.m1.map { try PrivacyWallet.voteWeight($0.amount) }, chain.stakeVotes.map(\.2))
        dump(chain, "stakeVoteSpentBefore")
    }

    func testRestakedAfterTheSnapshotStillVotesItsOutputsCannot() async throws {
        let sc = try await scenario()
        let (chain, a) = (sc.chain, sc.a)
        let k1 = try await restakeAll(a, sc.k)
        XCTAssertNotNil(note(a, sc.k.position).spentHeight)
        // Its outputs are not under the note root.
        for o in k1 {
            await assertThrowsAsync({ try await a.stakeVote(proposalID: 1, note: o, options: self.yes) }) { $0 is PrivacyError }
            let items = try await a.stakeVoteItems(proposalID: 1)
            XCTAssertFalse(items.contains(.note(o.position)))
        }
        // k itself still votes: its nullifier went in after nf_root.
        let items = try await a.stakeVoteItems(proposalID: 1)
        XCTAssertTrue(items.contains(.note(sc.k.position)))
        _ = try await a.stakeVote(proposalID: 1, note: note(a, sc.k.position), options: abstain)
        XCTAssertEqual(1, chain.stakeVotes.count)
        XCTAssertEqual(try PrivacyWallet.voteWeight(sc.k.amount), chain.stakeVotes[0].2)
        dump(chain, "stakeVoteRestakedAfter")
    }

    /// Every eligible note on both proposals through castStakeVote: the tallies count each once.
    func testEveryNoteOnBothProposals() async throws {
        let sc = try await scenario()
        let (chain, a) = (sc.chain, sc.a)
        for p: UInt64 in [1, 2] {
            let items = try await a.stakeVoteItems(proposalID: p)
            XCTAssertEqual(Set([sc.n.position, sc.k.position] + sc.m1.map(\.position)),
                           Set(items.compactMap { if case let .note(pos) = $0 { return pos }; return nil }))
            for item in items {
                let r = try await a.castStakeVote(proposalID: p, item: item, options: yes)
                XCTAssertNotNil(r)
            }
            try await a.sync()
            let left = try await a.stakeVoteItems(proposalID: p)
            XCTAssertTrue(left.isEmpty)
        }
        XCTAssertEqual(8, chain.stakeVotes.count)
        XCTAssertEqual(8, chain.voteNullifiers.count)
    }

    /// A wallet restored from the mnemonic does not know its votes: the chain's refusal (at simulate) records it, nothing is paid.
    func testRestoredWalletLearnsItAlreadyVoted() async throws {
        let sc = try await scenario()
        _ = try await sc.a.stakeVote(proposalID: 1, note: sc.n, options: yes)
        let restored = try wallet(sc.chain)
        try await restored.sync()
        let before = sc.chain.height
        let r = try await restored.castStakeVote(proposalID: 1, item: .note(sc.n.position), options: no)
        XCTAssertNil(r)
        XCTAssertEqual(before, sc.chain.height)
        XCTAssertEqual(1, restored.store.state.stakeVotes.count)
        XCTAssertTrue(restored.store.state.stakeVotes[0].confirmed)
        let items = try await restored.stakeVoteItems(proposalID: 1)
        XCTAssertFalse(items.contains(.note(sc.n.position)))
    }

    /// A vote whose tx failed in its block is forgotten: the note votes again.
    func testAFailedVoteIsRetried() async throws {
        let sc = try await scenario()
        sc.chain.failInBlockNext = 1
        await assertThrowsAsync({ try await sc.a.stakeVote(proposalID: 1, note: sc.n, options: self.yes) })
        XCTAssertEqual(1, sc.a.store.state.stakeVotes.count)
        XCTAssertFalse(sc.a.store.state.stakeVotes[0].confirmed)
        try await sc.a.sync()
        let items = try await sc.a.stakeVoteItems(proposalID: 1)
        XCTAssertTrue(items.contains(.note(sc.n.position)))
        _ = try await sc.a.stakeVote(proposalID: 1, note: note(sc.a, sc.n.position), options: yes)
        XCTAssertEqual(1, sc.chain.stakeVotes.count)
    }

    /// An indexer without the streams: the snapshot and the nullifiers come from the LCD.
    func testLcdFallback() async throws {
        let chain = FakeChain()
        chain.indexerNfTree = false
        chain.indexerSnapshots = false
        let sc = try await scenario(chain)
        _ = try await sc.a.stakeVote(proposalID: 1, note: sc.n, options: yes)
        XCTAssertEqual([0], chain.nfTreeAsks)
        XCTAssertEqual(1, chain.stakeVotes.count)
    }

    /// An indexer whose nullifier stream does not rebuild nf_root: the wallet rebuilds from the chain instead.
    func testForgedNullifierStreamFallsBackToTheChain() async throws {
        let chain = FakeChain()
        let forged = WrappedIndexer(chain)
        forged.stakeNfLeavesMap = { p in
            StakeNfLeavesPage(leaves: p.leaves.map { (index: $0.index, value: Fr(UInt64(424_242))) }, nextIndex: p.nextIndex,
                              complete: p.complete, size: p.size, syncedHeight: p.syncedHeight)
        }
        let sc = try await scenario(chain)
        let a = try wallet(chain, indexer: forged)
        try await a.sync()
        _ = try await a.stakeVote(proposalID: 1, note: note(a, sc.n.position), options: yes)
        XCTAssertEqual([0], chain.nfTreeAsks)
        XCTAssertEqual(1, chain.stakeVotes.count)
    }

    func testWeightRounding() throws {
        XCTAssertEqual(1, try PrivacyWallet.voteWeight(1))
        XCTAssertEqual(999, try PrivacyWallet.voteWeight(999))
        XCTAssertEqual(1_000, try PrivacyWallet.voteWeight(1_000))
        XCTAssertEqual(1_000, try PrivacyWallet.voteWeight(1_009))
        XCTAssertEqual(1_230_000, try PrivacyWallet.voteWeight(1_234_567))
        XCTAssertEqual(999_000, try PrivacyWallet.voteWeight(999_999))
        XCTAssertEqual(123_000_000, try PrivacyWallet.voteWeight(123_456_789))
        XCTAssertEqual(9_220_000_000_000_000_000, try PrivacyWallet.voteWeight(UInt64(Int64.max)))
        XCTAssertEqual(18_400_000_000_000_000_000, try PrivacyWallet.voteWeight(UInt64.max))
        XCTAssertThrowsError(try PrivacyWallet.voteWeight(0))
    }
}
