import Foundation
import XCTest
@testable import EarthCore

/// Stake votes without spending (ORCHARD_DESIGN 15, 18.2), the chain's
/// TestStakeVoteConcurrentProposals and TestStakeVoteManyNotesOneWeight as
/// wallet flows against `FakeChain`: one vote per validator carrying up to
/// four notes with one weight (their rounded sum), on two concurrently open
/// proposals, unlinkable vote nullifiers, a second vote refused (locally, and
/// by the chain for a restored wallet), a note spent before the snapshot left
/// out, one restaked after it still voting while its outputs cannot, a fifth
/// note in a second vote; the indexer's nullifier stream and the LCD
/// fallback. Ports StakeVoteFlowTest.kt.
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

    /// The vote's notes, by position, as its witness carried them.
    func lastVotePositions(_ chain: FakeChain) -> Set<UInt64> { Set(chain.prover.allVotes.last!.slots.map(\.pos)) }

    func testOneVoteCarriesEveryNoteOnTwoConcurrentProposals() async throws {
        let sc = try await scenario()
        let (chain, a, n) = (sc.chain, sc.a, sc.n)
        let eligible = [sc.n, sc.k] + sc.m1
        let items0 = try await a.stakeVoteItems(proposalID: 1)
        XCTAssertEqual([.validator(vB, notes: 4)], items0)
        let proofsBefore = chain.prover.allStakes.count
        _ = try await a.stakeVote(proposalID: 1, validator: vB, options: yes)
        XCTAssertEqual(Set(eligible.map(\.position)), lastVotePositions(chain))
        _ = try await a.stakeVote(proposalID: 2, validator: vB, options: no)
        // No stake proof, nothing minted, nothing spent.
        XCTAssertEqual(proofsBefore, chain.prover.allStakes.count)
        XCTAssertNil(chain.stakeNullifiers[n.nf])
        try await a.sync()
        XCTAssertTrue(note(a, n.position).spendable)
        // Unlinkable: a vote nullifier per note and proposal, none a spend nullifier.
        let vnfs = chain.voteNullifiers.map { $0[1] }
        XCTAssertEqual(8, Set(vnfs).count)
        XCTAssertFalse(eligible.contains { vnfs.contains($0.nf) })
        XCTAssertNotEqual(PrivacyHash.voteNF(nk: a.keys.nk, rho: n.rho, position: n.position, proposalID: 1),
                          PrivacyHash.voteNF(nk: a.keys.nk, rho: n.rho, position: n.position, proposalID: 2))
        // One weight per vote: the notes' sum rounded down to 3 significant figures.
        let w = try PrivacyWallet.voteWeight(eligible.reduce(UInt64(0)) { $0 + $1.amount })
        XCTAssertEqual(1_890_000, w) // 999,999 + 599,999 + 299,999 (two halves)
        XCTAssertEqual(chain.stakeVotes.map { "\($0.0) \($0.1) \($0.2)" }, ["1 \(vB) \(w)", "2 \(vB) \(w)"])
        XCTAssertEqual([4, 4], chain.stakeVoteSlots)

        // Again on 1: refused here, nothing simulated.
        let sims = chain.simulated
        await assertThrowsAsync({ try await a.stakeVote(proposalID: 1, validator: self.vB, options: self.no) }) { $0 is PrivacyWallet.AlreadyVoted }
        XCTAssertEqual(sims, chain.simulated)
        let items1 = try await a.stakeVoteItems(proposalID: 1)
        let items2 = try await a.stakeVoteItems(proposalID: 2)
        XCTAssertTrue(items1.isEmpty)
        XCTAssertTrue(items2.isEmpty)
        let none = try await a.castStakeVote(proposalID: 1, item: .validator(vB, notes: 4), options: yes)
        XCTAssertNil(none)

        // n is still an ordinary note: it undelegates.
        _ = try await a.undelegate(validator: vB, amount: 10_000); try await a.sync()
        dump(chain, "stakeVoteConcurrent")
    }

    func testSpentBeforeTheSnapshotIsLeftOut() async throws {
        let sc = try await scenario()
        let (chain, a) = (sc.chain, sc.a)
        // m's nullifier is under nf_root: it is no candidate; its outputs, from before the snapshot, are.
        _ = try await a.stakeVote(proposalID: 1, validator: vB, options: no)
        XCTAssertFalse(lastVotePositions(chain).contains(sc.m.position))
        XCTAssertTrue(sc.m1.allSatisfy { lastVotePositions(chain).contains($0.position) })
        dump(chain, "stakeVoteSpentBefore")
    }

    func testRestakedAfterTheSnapshotStillVotesItsOutputsCannot() async throws {
        let sc = try await scenario()
        let (chain, a) = (sc.chain, sc.a)
        let k1 = try await restakeAll(a, sc.k)
        XCTAssertNotNil(note(a, sc.k.position).spentHeight)
        // k itself still votes (its nullifier went in after nf_root); its outputs are not under the note root.
        let items = try await a.stakeVoteItems(proposalID: 1)
        XCTAssertEqual([.validator(vB, notes: 4)], items)
        _ = try await a.stakeVote(proposalID: 1, validator: vB, options: abstain)
        let voted = lastVotePositions(chain)
        XCTAssertTrue(voted.contains(sc.k.position))
        XCTAssertFalse(k1.contains { voted.contains($0.position) })
        XCTAssertEqual(1, chain.stakeVotes.count)
        XCTAssertEqual(try PrivacyWallet.voteWeight(([sc.n, sc.k] + sc.m1).reduce(UInt64(0)) { $0 + $1.amount }), chain.stakeVotes[0].2)
        dump(chain, "stakeVoteRestakedAfter")
    }

    /// Five notes at one validator: the largest four in one vote, the fifth in a second (the user's choice: two weights).
    func testAFifthNoteVotesInASecondPart() async throws {
        let chain = FakeChain()
        let a = try wallet(chain)
        for _ in 0 ..< 12 { try funded(chain, a, 2_000_000) }
        try await a.sync()
        var ns: [OwnedStakeNote] = []
        for amt: UInt64 in [1_111_111, 666_666, 333_333, 222_222, 111_111] { ns.append(try await delegate(a, amt)) }
        chain.openProposal(1)
        try await a.sync()
        let items = try await a.stakeVoteItems(proposalID: 1)
        XCTAssertEqual([.validator(vB, notes: 5)], items)
        XCTAssertEqual(2, items[0].parts)
        let top4 = try PrivacyWallet.voteWeight(ns.prefix(4).reduce(UInt64(0)) { $0 + $1.amount })
        let p1 = try await a.stakeVotePreview(proposalID: 1, item: items[0])
        XCTAssertEqual(PrivacyWallet.VotePreview(notes: 4, uerth: top4), p1)
        let r1 = try await a.castStakeVote(proposalID: 1, item: items[0], options: yes)
        XCTAssertNotNil(r1)
        XCTAssertEqual(Set(ns.prefix(4).map(\.position)), lastVotePositions(chain))
        try await a.sync()
        let p2 = try await a.stakeVotePreview(proposalID: 1, item: items[0])
        XCTAssertEqual(PrivacyWallet.VotePreview(notes: 1, uerth: try PrivacyWallet.voteWeight(ns[4].amount)), p2)
        let r2 = try await a.castStakeVote(proposalID: 1, item: items[0], options: yes)
        XCTAssertNotNil(r2)
        XCTAssertEqual([ns[4].position], lastVotePositions(chain))
        let r3 = try await a.castStakeVote(proposalID: 1, item: items[0], options: yes)
        XCTAssertNil(r3)
        XCTAssertEqual([4, 1], chain.stakeVoteSlots)
        XCTAssertEqual([top4, try PrivacyWallet.voteWeight(ns[4].amount)], chain.stakeVotes.map(\.2))
        dump(chain, "stakeVoteFiveNotes")
    }

    /// Votes at two validators: one msg each, each its own weight.
    func testOneVotePerValidator() async throws {
        let chain = FakeChain()
        let a = try wallet(chain)
        for _ in 0 ..< 6 { try funded(chain, a, 2_000_000) }
        try await a.sync()
        let vC = "earthvaloper1qyqszqgpqyqszqgpqyqszqgpqyqszqgpjnp7du"
        _ = try await a.delegate(validator: vB, amount: 1_000_000); try await a.sync()
        _ = try await a.delegate(validator: vC, amount: 2_000_000); try await a.sync()
        _ = try await a.delegate(validator: vC, amount: 500_000); try await a.sync()
        chain.openProposal(3)
        try await a.sync()
        let items = try await a.stakeVoteItems(proposalID: 3)
        XCTAssertEqual([.validator(vB, notes: 1), .validator(vC, notes: 2)], items)
        for item in items {
            let r = try await a.castStakeVote(proposalID: 3, item: item, options: yes)
            XCTAssertNotNil(r)
            try await a.sync()
        }
        XCTAssertEqual(["3 \(vB) 900000", "3 \(vC) \(try PrivacyWallet.voteWeight(1_800_000 + 450_000))"], chain.stakeVotes.map { "\($0.0) \($0.1) \($0.2)" })
        XCTAssertEqual([1, 2], chain.stakeVoteSlots)
        dump(chain, "stakeVoteTwoValidators")
    }

    /// A wallet restored from the mnemonic does not know its votes: the
    /// chain's refusal at simulate names a vote nullifier, which is recorded,
    /// and the vote is laid out again without it; nothing is paid or sent.
    func testRestoredWalletLearnsItAlreadyVoted() async throws {
        let sc = try await scenario()
        _ = try await sc.a.stakeVote(proposalID: 1, validator: vB, options: yes)
        let restored = try wallet(sc.chain)
        try await restored.sync()
        let before = sc.chain.height
        let r = try await restored.castStakeVote(proposalID: 1, item: .validator(vB, notes: 4), options: no)
        XCTAssertNil(r)
        XCTAssertEqual(before, sc.chain.height)
        XCTAssertEqual(4, restored.store.state.stakeVotes.filter { $0.confirmed && $0.proposalID == 1 }.count)
        let items = try await restored.stakeVoteItems(proposalID: 1)
        XCTAssertTrue(items.isEmpty)
    }

    /// A restored wallet whose first part voted: each voted note is learned from a refusal, the vote goes out with what is left.
    func testRestoredWalletVotesWhatIsLeft() async throws {
        let chain = FakeChain()
        let a = try wallet(chain)
        for _ in 0 ..< 12 { try funded(chain, a, 2_000_000) }
        try await a.sync()
        var ns: [OwnedStakeNote] = []
        for amt: UInt64 in [1_111_111, 666_666, 333_333, 222_222, 111_111] { ns.append(try await delegate(a, amt)) }
        chain.openProposal(1)
        try await a.sync()
        _ = try await a.stakeVote(proposalID: 1, validator: vB, options: yes)
        let restored = try wallet(chain)
        try await restored.sync()
        let items = try await restored.stakeVoteItems(proposalID: 1)
        XCTAssertEqual([.validator(vB, notes: 5)], items)
        let before = chain.height
        let r = try await restored.castStakeVote(proposalID: 1, item: items[0], options: no)
        XCTAssertNotNil(r)
        // One block: the four refusals were simulations, nothing paid.
        XCTAssertEqual(before + 1, chain.height)
        XCTAssertEqual([ns[4].position], lastVotePositions(chain))
        XCTAssertEqual(5, restored.store.state.stakeVotes.filter(\.confirmed).count)
    }

    /// A vote whose tx failed in its block is forgotten: its notes vote again.
    func testAFailedVoteIsRetried() async throws {
        let sc = try await scenario()
        sc.chain.failInBlockNext = 1
        await assertThrowsAsync({ try await sc.a.stakeVote(proposalID: 1, validator: self.vB, options: self.yes) })
        XCTAssertEqual(4, sc.a.store.state.stakeVotes.filter { !$0.confirmed }.count)
        try await sc.a.sync()
        let items = try await sc.a.stakeVoteItems(proposalID: 1)
        XCTAssertEqual([.validator(vB, notes: 4)], items)
        _ = try await sc.a.stakeVote(proposalID: 1, validator: vB, options: yes)
        XCTAssertEqual(1, sc.chain.stakeVotes.count)
    }

    /// An indexer without the streams: the snapshot and the nullifiers come from the LCD.
    func testLcdFallback() async throws {
        let chain = FakeChain()
        chain.indexerNfTree = false
        chain.indexerSnapshots = false
        let sc = try await scenario(chain)
        _ = try await sc.a.stakeVote(proposalID: 1, validator: vB, options: yes)
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
        _ = try await a.stakeVote(proposalID: 1, validator: vB, options: yes)
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
