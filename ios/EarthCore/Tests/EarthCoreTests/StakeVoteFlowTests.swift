import Foundation
import XCTest
@testable import EarthCore

/// Stake votes without spending (ORCHARD_DESIGN 15, 18.2, 20.4) as wallet
/// flows against `FakeChain`: one vote per validator carrying up to two notes
/// with one weight (their rounded value), on two concurrently open proposals,
/// unlinkable vote nullifiers, a second vote refused (locally, and by the
/// chain for a restored wallet), a note spent before the snapshot left out, a
/// note merged by a top-up after it still voting the value it held then while
/// the merged note cannot, a third note in a second vote, a labelled note at
/// its value after a slash (the current debt root); the indexer's nullifier
/// stream and the LCD fallback. Ports StakeVoteFlowTest.kt.
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

    var derth: String { PrivacyWallet.derthDenom(vB) }

    func live(_ a: PrivacyWallet) -> [OwnedStakeNote] { a.stakeNotes.filter { $0.unspent && $0.denom == derth } }

    struct Scenario { let chain: FakeChain; let a: PrivacyWallet; let n: OwnedStakeNote; let p: OwnedStakeNote }

    /// n delegated (a first delegation pads its input), p a second note of
    /// ours at the same validator (another device's), before proposals 1 and
    /// 2 open together.
    func scenario(_ chain: FakeChain = FakeChain()) async throws -> Scenario {
        let a = try wallet(chain)
        for _ in 0 ..< 12 { try funded(chain, a, 2_000_000) }
        try await a.sync()
        _ = try await a.delegate(validator: vB, amount: 1_111_111); try await a.sync()
        let n = live(a)[0]
        XCTAssertEqual(1, live(a).count)
        chain.plantStake(a.keys, derth, 300_000); try await a.sync()
        let p = live(a).first { $0.position != n.position }!
        let s1 = chain.openProposal(1)
        let s2 = chain.openProposal(2)
        XCTAssertEqual(2, s1.nfSize) // the sentinel and the first delegation's padding nullifier
        XCTAssertEqual(s1.nfRoot, s2.nfRoot)
        try await a.sync()
        return Scenario(chain: chain, a: a, n: n, p: p)
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

    func testOneVoteCarriesBothNotesOnTwoConcurrentProposals() async throws {
        let sc = try await scenario()
        let (chain, a, n) = (sc.chain, sc.a, sc.n)
        let eligible = [sc.n, sc.p]
        let items0 = try await a.stakeVoteItems(proposalID: 1)
        XCTAssertEqual([.validator(vB, notes: 2)], items0)
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
        XCTAssertEqual(4, Set(vnfs).count)
        XCTAssertFalse(eligible.contains { vnfs.contains($0.nf) })
        XCTAssertNotEqual(PrivacyHash.voteNF(nk: a.keys.nk, rho: n.rho, position: n.position, proposalID: 1),
                          PrivacyHash.voteNF(nk: a.keys.nk, rho: n.rho, position: n.position, proposalID: 2))
        // One weight per vote: the notes' sum rounded down to 3 significant figures, and the current debt root.
        let w = try PrivacyWallet.voteWeight(eligible.reduce(UInt64(0)) { $0 + $1.amount })
        XCTAssertEqual(chain.stakeVotes.map { "\($0.0) \($0.1) \($0.2)" }, ["1 \(vB) \(w)", "2 \(vB) \(w)"])
        XCTAssertEqual([2, 2], chain.stakeVoteSlots)
        XCTAssertEqual(chain.debtRoot(), chain.prover.allVotes.last!.debtRoot)

        // Again on 1: refused here, nothing simulated.
        let sims = chain.simulated
        await assertThrowsAsync({ try await a.stakeVote(proposalID: 1, validator: self.vB, options: self.no) }) { $0 is PrivacyWallet.AlreadyVoted }
        XCTAssertEqual(sims, chain.simulated)
        let items1 = try await a.stakeVoteItems(proposalID: 1)
        let items2 = try await a.stakeVoteItems(proposalID: 2)
        XCTAssertTrue(items1.isEmpty)
        XCTAssertTrue(items2.isEmpty)
        let none = try await a.castStakeVote(proposalID: 1, item: .validator(vB, notes: 2), options: yes)
        XCTAssertNil(none)

        // n is still an ordinary note: it undelegates.
        _ = try await a.undelegate(validator: vB, amount: 10_000); try await a.sync()
        dump(chain, "stakeVoteConcurrent")
    }

    /// A note a top-up merged before the snapshot is spent under nf_root: the merged note votes in its place.
    func testSpentBeforeTheSnapshotIsLeftOut() async throws {
        let chain = FakeChain()
        let a = try wallet(chain)
        for _ in 0 ..< 6 { try funded(chain, a, 2_000_000) }
        try await a.sync()
        _ = try await a.delegate(validator: vB, amount: 1_111_111); try await a.sync()
        let n = live(a)[0]
        _ = try await a.delegate(validator: vB, amount: 500_000); try await a.sync()
        let merged = live(a)[0]
        XCTAssertEqual(1, live(a).count)
        XCTAssertEqual(n.amount + (chain.lastMsg as! MsgShieldedDelegate).derth, merged.amount)
        chain.openProposal(1)
        try await a.sync()
        let items = try await a.stakeVoteItems(proposalID: 1)
        XCTAssertEqual([.validator(vB, notes: 1)], items)
        _ = try await a.stakeVote(proposalID: 1, validator: vB, options: no)
        XCTAssertEqual([merged.position], lastVotePositions(chain))
        dump(chain, "stakeVoteSpentBefore")
    }

    /// The snapshot rule (ORCHARD_DESIGN 20.4): a top-up after the snapshot
    /// spends both notes into one; the old notes still vote the value they
    /// held (their openings kept), the merged note, not under the snapshot
    /// root, cannot. No unit votes twice.
    func testToppedUpAfterTheSnapshotVotesThePreexistingValue() async throws {
        let sc = try await scenario()
        let (chain, a) = (sc.chain, sc.a)
        _ = try await a.delegate(validator: vB, amount: 700_000); try await a.sync()
        let merged = live(a)[0]
        XCTAssertEqual(1, live(a).count)
        XCTAssertNotNil(note(a, sc.n.position).spentHeight)
        XCTAssertNotNil(note(a, sc.p.position).spentHeight)
        let items = try await a.stakeVoteItems(proposalID: 1)
        XCTAssertEqual([.validator(vB, notes: 2)], items)
        _ = try await a.stakeVote(proposalID: 1, validator: vB, options: abstain)
        let voted = lastVotePositions(chain)
        XCTAssertEqual([sc.n.position, sc.p.position], voted)
        XCTAssertFalse(voted.contains(merged.position))
        XCTAssertEqual(1, chain.stakeVotes.count)
        XCTAssertEqual(try PrivacyWallet.voteWeight(sc.n.amount + sc.p.amount), chain.stakeVotes[0].2)
        // A proposal opened after the top-up sees the merged note alone.
        chain.openProposal(3); try await a.sync()
        let items3 = try await a.stakeVoteItems(proposalID: 3)
        XCTAssertEqual([.validator(vB, notes: 1)], items3)
        _ = try await a.stakeVote(proposalID: 3, validator: vB, options: yes)
        XCTAssertEqual([merged.position], lastVotePositions(chain))
        dump(chain, "stakeVoteToppedUpAfter")
    }

    /// Three notes at one validator: the largest two in one vote, the third in a second (the user's choice: two weights).
    func testAThirdNoteVotesInASecondPart() async throws {
        let chain = FakeChain()
        let a = try wallet(chain)
        for _ in 0 ..< 6 { try funded(chain, a, 2_000_000) }
        try await a.sync()
        _ = try await a.delegate(validator: vB, amount: 1_111_111); try await a.sync()
        chain.plantStake(a.keys, derth, 666_666)
        chain.plantStake(a.keys, derth, 111_111)
        chain.openProposal(1)
        try await a.sync()
        let ns = live(a).sorted { $0.amount > $1.amount }
        let items = try await a.stakeVoteItems(proposalID: 1)
        XCTAssertEqual([.validator(vB, notes: 3)], items)
        XCTAssertEqual(2, items[0].parts)
        let top2 = try PrivacyWallet.voteWeight(ns.prefix(2).reduce(UInt64(0)) { $0 + $1.amount })
        let p1 = try await a.stakeVotePreview(proposalID: 1, item: items[0])
        XCTAssertEqual(PrivacyWallet.VotePreview(notes: 2, uerth: top2), p1)
        let r1 = try await a.castStakeVote(proposalID: 1, item: items[0], options: yes)
        XCTAssertNotNil(r1)
        XCTAssertEqual(Set(ns.prefix(2).map(\.position)), lastVotePositions(chain))
        try await a.sync()
        let p2 = try await a.stakeVotePreview(proposalID: 1, item: items[0])
        XCTAssertEqual(PrivacyWallet.VotePreview(notes: 1, uerth: try PrivacyWallet.voteWeight(ns[2].amount)), p2)
        let r2 = try await a.castStakeVote(proposalID: 1, item: items[0], options: yes)
        XCTAssertNotNil(r2)
        XCTAssertEqual([ns[2].position], lastVotePositions(chain))
        let r3 = try await a.castStakeVote(proposalID: 1, item: items[0], options: yes)
        XCTAssertNil(r3)
        XCTAssertEqual([2, 1], chain.stakeVoteSlots)
        dump(chain, "stakeVoteThreeNotes")
    }

    /// Votes at two validators: one msg each, each its own weight; a second delegation to one merged into its note.
    func testOneVotePerValidator() async throws {
        let chain = FakeChain()
        let a = try wallet(chain)
        for _ in 0 ..< 6 { try funded(chain, a, 2_000_000) }
        try await a.sync()
        let vC = Vectors.json["validator2"] as! String
        _ = try await a.delegate(validator: vB, amount: 1_000_000); try await a.sync()
        _ = try await a.delegate(validator: vC, amount: 2_000_000); try await a.sync()
        _ = try await a.delegate(validator: vC, amount: 500_000); try await a.sync()
        chain.openProposal(3)
        try await a.sync()
        let items = try await a.stakeVoteItems(proposalID: 3)
        XCTAssertEqual([.validator(vB, notes: 1), .validator(vC, notes: 1)], items)
        for item in items {
            let r = try await a.castStakeVote(proposalID: 3, item: item, options: yes)
            XCTAssertNotNil(r)
            try await a.sync()
        }
        let atB = a.stakeNotes.first { $0.unspent && $0.denom == derth }!.amount
        let atC = a.stakeNotes.first { $0.unspent && $0.denom == PrivacyWallet.derthDenom(vC) }!.amount
        XCTAssertEqual(["3 \(vB) \(try PrivacyWallet.voteWeight(atB))", "3 \(vC) \(try PrivacyWallet.voteWeight(atC))"],
                       chain.stakeVotes.map { "\($0.0) \($0.1) \($0.2)" })
        XCTAssertEqual([1, 1], chain.stakeVoteSlots)
        dump(chain, "stakeVoteTwoValidators")
    }

    /// A labelled note (stake moved in) votes its amount less the slash cut of
    /// its exposure under the CURRENT debt tree: a slash after the snapshot counts.
    func testALabelledNoteVotesItsValueAfterASlash() async throws {
        let chain = FakeChain()
        let a = try wallet(chain)
        for _ in 0 ..< 6 { try funded(chain, a, 2_000_000) }
        try await a.sync()
        let vA = Vectors.json["validator2"] as! String
        _ = try await a.delegate(validator: vA, amount: 2_000_000); try await a.sync()
        _ = try await a.redelegate(try await a.quoteMove(src: vA, dst: vB, amount: 1_000_000)); try await a.sync()
        let l = live(a)[0]
        let label = l.label!
        XCTAssertEqual(l.amount, label.exposed)
        chain.openProposal(1)
        // The source is slashed after the snapshot: the move's exposure is worth 60% now.
        chain.slashMove(label.moveKey, retained: label.exposed * 6 / 10)
        try await a.sync()
        _ = try await a.stakeVote(proposalID: 1, validator: vB, options: yes)
        let w = chain.prover.allVotes.last!
        XCTAssertEqual(chain.debtRoot(), w.debtRoot)
        XCTAssertEqual(label, w.slots[0].label)
        XCTAssertEqual(1, w.slots.count)
        XCTAssertEqual(try PrivacyWallet.voteWeight(l.amount - label.exposed + label.exposed * 6 / 10), chain.stakeVotes[0].2)
        dump(chain, "stakeVoteLabelled")
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
        let r = try await restored.castStakeVote(proposalID: 1, item: .validator(vB, notes: 2), options: no)
        XCTAssertNil(r)
        XCTAssertEqual(before, sc.chain.height)
        XCTAssertEqual(2, restored.store.state.stakeVotes.filter { $0.confirmed && $0.proposalID == 1 }.count)
        let items = try await restored.stakeVoteItems(proposalID: 1)
        XCTAssertTrue(items.isEmpty)
    }

    /// A restored wallet whose first part voted: each voted note is learned from a refusal, the vote goes out with what is left.
    func testRestoredWalletVotesWhatIsLeft() async throws {
        let chain = FakeChain()
        let a = try wallet(chain)
        for _ in 0 ..< 6 { try funded(chain, a, 2_000_000) }
        try await a.sync()
        _ = try await a.delegate(validator: vB, amount: 1_111_111); try await a.sync()
        chain.plantStake(a.keys, derth, 666_666)
        chain.plantStake(a.keys, derth, 111_111)
        chain.openProposal(1)
        try await a.sync()
        let ns = live(a).sorted { $0.amount > $1.amount }
        _ = try await a.stakeVote(proposalID: 1, validator: vB, options: yes)
        let restored = try wallet(chain)
        try await restored.sync()
        let items = try await restored.stakeVoteItems(proposalID: 1)
        XCTAssertEqual([.validator(vB, notes: 3)], items)
        let before = chain.height
        let r = try await restored.castStakeVote(proposalID: 1, item: items[0], options: no)
        XCTAssertNotNil(r)
        // One block: the two refusals were simulations, nothing paid.
        XCTAssertEqual(before + 1, chain.height)
        XCTAssertEqual([ns[2].position], lastVotePositions(chain))
        XCTAssertEqual(3, restored.store.state.stakeVotes.filter(\.confirmed).count)
    }

    /// A vote whose tx failed in its block is forgotten: its notes vote again.
    func testAFailedVoteIsRetried() async throws {
        let sc = try await scenario()
        sc.chain.failInBlockNext = 1
        await assertThrowsAsync({ try await sc.a.stakeVote(proposalID: 1, validator: self.vB, options: self.yes) })
        XCTAssertEqual(2, sc.a.store.state.stakeVotes.filter { !$0.confirmed }.count)
        try await sc.a.sync()
        let items = try await sc.a.stakeVoteItems(proposalID: 1)
        XCTAssertEqual([.validator(vB, notes: 2)], items)
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
