import BigInt
import Foundation
import XCTest
@testable import EarthCore

/// Stake votes without spending (ORCHARD_DESIGN 8.5) as wallet
/// flows against `FakeChain`: one vote per validator carrying up to two notes
/// with one weight (their rounded value), and one per position, each its own
/// tx; on two concurrently open proposals; unlinkable vote nullifiers; a
/// second vote refused (locally, and by the chain for a restored wallet); a
/// note spent before the snapshot left out; a note merged by a top-up after
/// it still voting the value it held then while the merged note cannot; a
/// third note in a second vote; a labelled note at its value after a slash;
/// the snapshot taken from the chain, never the indexer; the indexer's
/// nullifier stream and the LCD fallback.
final class StakeVoteFlowTests: PrivacyTestCase {
    let no = [WeightedVoteOption(option: WeightedVoteOption.no, weight: "1")]
    let abstain = [WeightedVoteOption(option: WeightedVoteOption.abstain, weight: "1")]

    func note(_ a: PrivacyWallet, _ position: UInt64) -> OwnedStakeNote { a.stakeNotes.first { $0.position == position }! }

    var derth: String { PrivacyWallet.derthDenom(validator) }

    func live(_ a: PrivacyWallet) -> [OwnedStakeNote] { a.stakeNotes.filter { $0.unspent && $0.denom == derth } }

    struct Scenario { let chain: FakeChain; let a: PrivacyWallet; let n: OwnedStakeNote; let p: OwnedStakeNote }

    /// n delegated (a first delegation pads its input), p a second note of
    /// ours at the same validator (another device's), before proposals 1 and
    /// 2 open together.
    func scenario(_ chain: FakeChain = FakeChain()) async throws -> Scenario {
        let a = try wallet(chain)
        for _ in 0 ..< 12 { try funded(chain, a, 2_000_000) }
        try await a.sync()
        _ = try await a.delegate(validator: validator, amount: 1_111_111); try await a.sync()
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

    /// The vote's notes, by position, as its witness carried them.
    func lastVotePositions(_ chain: FakeChain) -> Set<UInt64> { Set(chain.prover.allVotes.last!.slots.map(\.pos)) }

    struct Voting { let chain: FakeChain; let a: PrivacyWallet; let k: OwnedStakeNote }

    /// k delegated before proposal 1's snapshot, beside a second note of ours
    /// (another device's), and merged by a top-up (spent) after it: on chain
    /// it still votes the value it held at the snapshot.
    func voting(indexer: WrappedIndexer? = nil) async throws -> Voting {
        let chain = FakeChain()
        let a = try wallet(chain)
        for _ in 0 ..< 6 { try funded(chain, a, 2_000_000) }
        _ = try await a.sync()
        _ = try await a.delegate(validator: validator, amount: 666_666); _ = try await a.sync()
        let k = a.snapshot.stakeNotes.first { $0.unspent }!
        chain.plantStake(a.keys, PrivacyWallet.derthDenom(validator), 300_000); _ = try await a.sync()
        chain.openProposal(1)
        _ = try await a.delegate(validator: validator, amount: 333_333); _ = try await a.sync()
        return Voting(chain: chain, a: a, k: a.snapshot.stakeNotes.first { $0.position == k.position }!)
    }

    /// The chain's reads, with Query/Snapshot and the stake nullifier tree replaced.
    struct SnapshotReads: PrivacyChainReads, @unchecked Sendable {
        let inner: FakeReads
        let snap: PrivacyReads.Snapshot
        let values: [Fr]
        func personhoodParams() async throws -> PrivacyReads.PersonhoodParams { try await inner.personhoodParams() }
        func leaseBounds() async throws -> PrivacyReads.LeaseBounds { try await inner.leaseBounds() }
        func ballotInputs(proposalID: UInt64, optionID: UInt64) async throws -> PrivacyReads.BallotInputs {
            try await inner.ballotInputs(proposalID: proposalID, optionID: optionID)
        }
        func epochNumber() async throws -> UInt64 { try await inner.epochNumber() }
        func snapshot(proposalID: UInt64) async throws -> PrivacyReads.Snapshot { snap }
        func positions() async throws -> [PrivacyReads.Position] { try await inner.positions() }
        func debtTree(start: UInt64, limit: Int) async throws -> PrivacyReads.DebtTreePage { try await inner.debtTree(start: start, limit: limit) }
        func validators() async throws -> PrivacyReads.ValidatorList { try await inner.validators() }
        func minDelegation() async throws -> UInt64 { try await inner.minDelegation() }
        func stakeNullifierTree(start: UInt64, limit: Int) async throws -> PrivacyReads.NfTreePage {
            PrivacyReads.NfTreePage(values: Array(values.dropFirst(Int(start)).prefix(limit)), size: UInt64(values.count) + 1)
        }
    }

    /// Two validators' derth and a position, all before proposal 12's snapshot.
    func stakedAtTwoValidatorsWithAPosition(_ chain: FakeChain) async throws -> PrivacyWallet {
        let a = try wallet(chain)
        for _ in 0 ..< 4 { try funded(chain, a, 2_000_000) }
        try await a.sync()
        _ = try await a.delegate(validator: validator, amount: 1_000_000); try await a.sync()
        _ = try await a.delegate(validator: validator2, amount: 1_000_000); try await a.sync()
        _ = try await a.lockPosition(validator: validator, amount: 100_000, splits: [2: 100]); try await a.sync()
        chain.openProposal(12)
        return a
    }

    func testOneVoteCarriesBothNotesOnTwoConcurrentProposals() async throws {
        let sc = try await scenario()
        let (chain, a, n) = (sc.chain, sc.a, sc.n)
        let eligible = [sc.n, sc.p]
        let items0 = try await a.stakeVoteItems(proposalID: 1)
        XCTAssertEqual([.validator(validator, notes: 2)], items0)
        let proofsBefore = chain.prover.allStakes.count
        _ = try await a.stakeVote(proposalID: 1, validator: validator, options: yes)
        XCTAssertEqual(Set(eligible.map(\.position)), lastVotePositions(chain))
        _ = try await a.stakeVote(proposalID: 2, validator: validator, options: no)
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
        XCTAssertEqual(chain.stakeVotes.map { "\($0.0) \($0.1) \($0.2)" }, ["1 \(validator) \(w)", "2 \(validator) \(w)"])
        XCTAssertEqual([2, 2], chain.stakeVoteSlots)
        XCTAssertEqual(chain.debtRoot(), chain.prover.allVotes.last!.debtRoot)

        // Again on 1: refused here, nothing simulated.
        let sims = chain.simulated
        await assertThrowsAsync({ try await a.stakeVote(proposalID: 1, validator: self.validator, options: self.no) }) { $0 is PrivacyWallet.AlreadyVoted }
        XCTAssertEqual(sims, chain.simulated)
        let items1 = try await a.stakeVoteItems(proposalID: 1)
        let items2 = try await a.stakeVoteItems(proposalID: 2)
        XCTAssertTrue(items1.isEmpty)
        XCTAssertTrue(items2.isEmpty)
        let none = try await a.castStakeVote(proposalID: 1, item: .validator(validator, notes: 2), options: yes)
        XCTAssertNil(none)

        // n is still an ordinary note: it undelegates.
        _ = try await a.undelegate(validator: validator, amount: 10_000); try await a.sync()
        dump(chain, "stakeVoteConcurrent")
    }

    /// A note a top-up merged before the snapshot is spent under nf_root: the merged note votes in its place.
    func testSpentBeforeTheSnapshotIsLeftOut() async throws {
        let chain = FakeChain()
        let a = try wallet(chain)
        for _ in 0 ..< 6 { try funded(chain, a, 2_000_000) }
        try await a.sync()
        _ = try await a.delegate(validator: validator, amount: 1_111_111); try await a.sync()
        let n = live(a)[0]
        _ = try await a.delegate(validator: validator, amount: 500_000); try await a.sync()
        let merged = live(a)[0]
        XCTAssertEqual(1, live(a).count)
        XCTAssertEqual(n.amount + (chain.lastMsg as! MsgShieldedDelegate).derth, merged.amount)
        chain.openProposal(1)
        try await a.sync()
        let items = try await a.stakeVoteItems(proposalID: 1)
        XCTAssertEqual([.validator(validator, notes: 1)], items)
        _ = try await a.stakeVote(proposalID: 1, validator: validator, options: no)
        XCTAssertEqual([merged.position], lastVotePositions(chain))
        dump(chain, "stakeVoteSpentBefore")
    }

    /// The snapshot rule (ORCHARD_DESIGN 8.5): a top-up after the snapshot
    /// spends both notes into one; the old notes still vote the value they
    /// held (their openings kept), the merged note, not under the snapshot
    /// root, cannot. No unit votes twice.
    func testToppedUpAfterTheSnapshotVotesThePreexistingValue() async throws {
        let sc = try await scenario()
        let (chain, a) = (sc.chain, sc.a)
        _ = try await a.delegate(validator: validator, amount: 700_000); try await a.sync()
        let merged = live(a)[0]
        XCTAssertEqual(1, live(a).count)
        XCTAssertNotNil(note(a, sc.n.position).spentHeight)
        XCTAssertNotNil(note(a, sc.p.position).spentHeight)
        let items = try await a.stakeVoteItems(proposalID: 1)
        XCTAssertEqual([.validator(validator, notes: 2)], items)
        _ = try await a.stakeVote(proposalID: 1, validator: validator, options: abstain)
        let voted = lastVotePositions(chain)
        XCTAssertEqual([sc.n.position, sc.p.position], voted)
        XCTAssertFalse(voted.contains(merged.position))
        XCTAssertEqual(1, chain.stakeVotes.count)
        XCTAssertEqual(try PrivacyWallet.voteWeight(sc.n.amount + sc.p.amount), chain.stakeVotes[0].2)
        // A proposal opened after the top-up sees the merged note alone.
        chain.openProposal(3); try await a.sync()
        let items3 = try await a.stakeVoteItems(proposalID: 3)
        XCTAssertEqual([.validator(validator, notes: 1)], items3)
        _ = try await a.stakeVote(proposalID: 3, validator: validator, options: yes)
        XCTAssertEqual([merged.position], lastVotePositions(chain))
        dump(chain, "stakeVoteToppedUpAfter")
    }

    /// Three notes at one validator: the largest two in one vote, the third in a second (the user's choice: two weights).
    func testAThirdNoteVotesInASecondPart() async throws {
        let chain = FakeChain()
        let a = try wallet(chain)
        for _ in 0 ..< 6 { try funded(chain, a, 2_000_000) }
        try await a.sync()
        _ = try await a.delegate(validator: validator, amount: 1_111_111); try await a.sync()
        chain.plantStake(a.keys, derth, 666_666)
        chain.plantStake(a.keys, derth, 111_111)
        chain.openProposal(1)
        try await a.sync()
        let ns = live(a).sorted { $0.amount > $1.amount }
        let items = try await a.stakeVoteItems(proposalID: 1)
        XCTAssertEqual([.validator(validator, notes: 3)], items)
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
        _ = try await a.delegate(validator: validator, amount: 1_000_000); try await a.sync()
        _ = try await a.delegate(validator: vC, amount: 2_000_000); try await a.sync()
        _ = try await a.delegate(validator: vC, amount: 500_000); try await a.sync()
        chain.openProposal(3)
        try await a.sync()
        let items = try await a.stakeVoteItems(proposalID: 3)
        XCTAssertEqual([.validator(validator, notes: 1), .validator(vC, notes: 1)], items)
        for item in items {
            let r = try await a.castStakeVote(proposalID: 3, item: item, options: yes)
            XCTAssertNotNil(r)
            try await a.sync()
        }
        let atB = a.stakeNotes.first { $0.unspent && $0.denom == derth }!.amount
        let atC = a.stakeNotes.first { $0.unspent && $0.denom == PrivacyWallet.derthDenom(vC) }!.amount
        XCTAssertEqual(["3 \(validator) \(try PrivacyWallet.voteWeight(atB))", "3 \(vC) \(try PrivacyWallet.voteWeight(atC))"],
                       chain.stakeVotes.map { "\($0.0) \($0.1) \($0.2)" })
        XCTAssertEqual([1, 1], chain.stakeVoteSlots)
        dump(chain, "stakeVoteTwoValidators")
    }

    /// One vote per validator and one per position, each its own tx the user confirms; nothing is cast in
    /// the background. A validator voted once is not voted again.
    func testStakeVotesAreOneTxPerValidatorAndPosition() async throws {
        let chain = FakeChain()
        let a = try await stakedAtTwoValidatorsWithAPosition(chain)
        try await a.sync()
        let items = try await a.stakeVoteItems(proposalID: 12)
        XCTAssertEqual(3, items.count)
        XCTAssertEqual(2, items.filter { if case .validator = $0 { return true } else { return false } }.count)
        let before = chain.height
        for item in items {
            let r = try await a.castStakeVote(proposalID: 12, item: item, options: yes)
            XCTAssertNotNil(r)
            try await a.sync()
        }
        XCTAssertEqual(2, chain.stakeVotes.count)
        XCTAssertEqual(1, chain.positionVotes.count)
        XCTAssertEqual(before + 3, chain.height)
        // Final: the validators' notes have voted.
        let again = try await a.castStakeVote(proposalID: 12, item: items[0], options: yes)
        XCTAssertNil(again)
        XCTAssertEqual(2, chain.stakeVotes.count)
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
        _ = try await a.redelegate(try await a.quoteMove(src: vA, dst: validator, amount: 1_000_000)); try await a.sync()
        let l = live(a)[0]
        let label = l.label!
        XCTAssertEqual(l.amount, label.exposed)
        chain.openProposal(1)
        // The source is slashed after the snapshot: the move's exposure is worth 60% now.
        chain.slashMove(label.moveKey, retained: label.exposed * 6 / 10)
        try await a.sync()
        _ = try await a.stakeVote(proposalID: 1, validator: validator, options: yes)
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
        _ = try await sc.a.stakeVote(proposalID: 1, validator: validator, options: yes)
        let restored = try wallet(sc.chain)
        try await restored.sync()
        let before = sc.chain.height
        let r = try await restored.castStakeVote(proposalID: 1, item: .validator(validator, notes: 2), options: no)
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
        _ = try await a.delegate(validator: validator, amount: 1_111_111); try await a.sync()
        chain.plantStake(a.keys, derth, 666_666)
        chain.plantStake(a.keys, derth, 111_111)
        chain.openProposal(1)
        try await a.sync()
        let ns = live(a).sorted { $0.amount > $1.amount }
        _ = try await a.stakeVote(proposalID: 1, validator: validator, options: yes)
        let restored = try wallet(chain)
        try await restored.sync()
        let items = try await restored.stakeVoteItems(proposalID: 1)
        XCTAssertEqual([.validator(validator, notes: 3)], items)
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
        await assertThrowsAsync({ try await sc.a.stakeVote(proposalID: 1, validator: self.validator, options: self.yes) })
        XCTAssertEqual(2, sc.a.store.state.stakeVotes.filter { !$0.confirmed }.count)
        try await sc.a.sync()
        let items = try await sc.a.stakeVoteItems(proposalID: 1)
        XCTAssertEqual([.validator(validator, notes: 2)], items)
        _ = try await sc.a.stakeVote(proposalID: 1, validator: validator, options: yes)
        XCTAssertEqual(1, sc.chain.stakeVotes.count)
    }

    /// An indexer without the streams: the snapshot and the nullifiers come from the LCD.
    func testLcdFallback() async throws {
        let chain = FakeChain()
        chain.indexerNfTree = false
        chain.indexerSnapshots = false
        let sc = try await scenario(chain)
        _ = try await sc.a.stakeVote(proposalID: 1, validator: validator, options: yes)
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
        _ = try await a.stakeVote(proposalID: 1, validator: validator, options: yes)
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

    func testRoundVoteWeightMatchesTheChain() throws {
        let v = Vectors.json["round_vote_weight"] as! [String: String]
        for (k, want) in v {
            XCTAssertEqual(UInt64(want), try PrivacyWallet.voteWeight(UInt64(k)!), k)
        }
    }

    func testAVoteHasTwoSlotsAndNinePublicInputs() async throws {
        let chain = FakeChain()
        let a = try await delegatedWallet(chain)
        // A top-up merges into the one note.
        _ = try await a.delegate(validator: validator, amount: 500_000); try await a.sync()
        let note = try XCTUnwrap(a.stakeNotes.filter(\.unspent).first)
        XCTAssertEqual(1, a.stakeNotes.filter(\.unspent).count)
        chain.openProposal(7)
        try await a.sync()
        _ = try await a.stakeVote(proposalID: 7, validator: validator, options: yes)
        let m = try XCTUnwrap(chain.lastMsg as? MsgStakeVote)
        XCTAssertEqual(2, m.voteNullifiers.count)
        let vs = try m.voteNullifiers.map { try Fr(bytes: $0) }
        // One note, two non-zero vote nullifiers: the note's and a padding one.
        XCTAssertEqual([false, false], vs.map(\.isZero))
        XCTAssertEqual(2, Set(vs).count)
        XCTAssertEqual(chain.debtRoot(), try Fr(bytes: m.debtRoot))
        let w = chain.prover.allVotes.last!
        XCTAssertEqual(9, w.publicInputs().count)
        XCTAssertEqual(vs, w.vnfs)
        let padAt = try XCTUnwrap(w.layout.order.firstIndex(where: { $0 == nil }))
        XCTAssertEqual(PrivacyHash.votePadNF(nk: w.nk, r: w.layout.padR(padAt), proposalID: 7), vs[padAt])
        XCTAssertEqual(PrivacyHash.voteNF(nk: w.nk, rho: note.rho, position: note.position, proposalID: 7), vs[1 - padAt])
        let inputs = w.noirInputs()
        for k in ["amount", "rho", "rcm", "pos", "path", "move_key", "move_time", "exposed", "low_value", "low_next_value", "low_next_index",
                  "low_index", "low_path", "debt_low_key", "debt_low_next_key", "debt_low_next_index", "debt_low_retained", "debt_low_index",
                  "debt_low_path", "vnf"] {
            XCTAssertEqual(2, (inputs[k] as! [Any]).count, k)
        }
        XCTAssertEqual("0x0", (inputs["amount"] as! [String])[padAt])
        XCTAssertEqual(w.layout.padR(padAt).noir, (inputs["rho"] as! [String])[padAt])
        XCTAssertEqual(32, ((inputs["path"] as! [[String]])[padAt]).count)
        XCTAssertEqual(m.weight, try PrivacyWallet.voteWeight(note.amount))
        // The msg's own checks: two slots.
        XCTAssertThrowsError(try PrivateTxEngine.withVote(m, vnfs: Array(vs.prefix(1)), proof: Data()))
        dump(chain, "voteSlots")
    }

    func testAVoteWitnessRefusesWhatTheCircuitWould() async throws {
        let chain = FakeChain()
        let a = try await delegatedWallet(chain)
        chain.openProposal(8)
        try await a.sync()
        _ = try await a.stakeVote(proposalID: 8, validator: validator, options: yes)
        let w = chain.prover.allVotes.last!
        // The same note twice; more weight than the notes; three slots.
        let two = try VoteLayout.inOrder(used: 2, pads: [])
        XCTAssertThrowsError(try VoteWitness(nk: w.nk, slots: w.slots + w.slots, layout: two, noteRoot: w.noteRoot, nfRoot: w.nfRoot, debtRoot: w.debtRoot,
                                             asset: w.asset, weight: w.weight, proposalID: w.proposalID, sighash: w.sighash).check())
        XCTAssertThrowsError(try VoteWitness(nk: w.nk, slots: w.slots, layout: w.layout, noteRoot: w.noteRoot, nfRoot: w.nfRoot, debtRoot: w.debtRoot, asset: w.asset,
                                             weight: w.slots.reduce(0) { $0 + $1.amount } + 1, proposalID: w.proposalID, sighash: w.sighash).check())
        XCTAssertThrowsError(try VoteWitness(nk: w.nk, slots: Array(repeating: w.slots[0], count: 3), layout: two, noteRoot: w.noteRoot, nfRoot: w.nfRoot,
                                             debtRoot: w.debtRoot, asset: w.asset, weight: w.weight, proposalID: w.proposalID, sighash: w.sighash))
        XCTAssertEqual(VoteWitness.maxNotes, MsgStakeVote.maxVoteNotes)
    }

    /// A 1119 in a block names one note: only that one is final, the vote's others may vote again.
    func testARefusalInABlockSettlesOnlyTheNamedNote() {
        let vnf = Fr(UInt64(77))
        XCTAssertTrue(PrivacyWallet.namesVoteNullifier("this stake note already voted on this proposal: proposal 3, vote nullifier \(vnf.hex.uppercased())", vnf))
        XCTAssertFalse(PrivacyWallet.namesVoteNullifier("this stake note already voted on this proposal: proposal 3, vote nullifier 00", vnf))
        XCTAssertEqual(vnf, PrivacyWallet.usedVoteNullifier(UnsignedTx.TxRejected(code: 1119, log: "vote nullifier \(vnf.hex.uppercased())",
                                                                                   codespace: "shieldedstaking"), [Fr(UInt64(1)), vnf]))
    }

    /// 1119 counts as "already voted" only in x/shieldedstaking's codespace.
    func testAlreadyVotedNeedsTheShieldedStakingCodespace() {
        XCTAssertTrue(PrivacyWallet.alreadyVotedError(UnsignedTx.TxRejected(code: 1119, log: "x", codespace: "shieldedstaking")))
        XCTAssertFalse(PrivacyWallet.alreadyVotedError(UnsignedTx.TxRejected(code: 1119, log: "x", codespace: "wasm")))
        XCTAssertFalse(PrivacyWallet.alreadyVotedError(PrivacyError("tx rejected (code 1119): something else")))
        XCTAssertTrue(PrivacyWallet.alreadyVotedError(PrivacyError("simulate failed (400) this stake note already voted on this proposal")))
    }

    /// A refused vote's record is forgotten: the note may vote again.
    func testARefusedVoteIsForgotten() async throws {
        let v = try await voting()
        v.chain.rejectNext = 1
        await assertThrowsAsync({ try await v.a.castStakeVote(proposalID: 1, item: .validator(validator, notes: 2), options: self.yes) }) { $0 is UnsignedTx.TxRejected }
        XCTAssertTrue(v.a.store.state.stakeVotes.isEmpty)
        let r = try await v.a.castStakeVote(proposalID: 1, item: .validator(validator, notes: 2), options: yes)
        XCTAssertNotNil(r)
        XCTAssertEqual(2, v.a.store.state.stakeVotes.count)
    }

    /// The stake votes cast survive a same-chain reset.
    func testStakeVotesSurviveAReset() async throws {
        let v = try await voting()
        _ = try await v.a.castStakeVote(proposalID: 1, item: .validator(validator, notes: 2), options: yes)
        let votes = v.a.store.state.stakeVotes
        // One vote, two notes: each note's vote nullifier is remembered.
        XCTAssertEqual(2, votes.count)
        try v.a.store.reset(chainID: v.chain.chainID)
        XCTAssertEqual(votes, v.a.store.state.stakeVotes)
        _ = try await v.a.sync()
        let again = try await v.a.castStakeVote(proposalID: 1, item: .validator(validator, notes: 2), options: yes)
        XCTAssertNil(again)
        XCTAssertEqual(1, v.chain.stakeVotes.count)
    }

    /// A stake snapshot larger than the local stake tree is "sync first", not a trap.
    func testASnapshotAheadOfTheLocalTreeAsksForASync() async throws {
        let chain = FakeChain()
        let w = try wallet(chain)
        for _ in 0 ..< 2 { try funded(chain, w, 2_000_000) }
        try await w.sync()
        _ = try await w.delegate(validator: validator, amount: 1_000_000)
        try await w.sync()
        // Someone else's stake lands after this wallet's sync.
        chain.plantStake(try PrivacyKeys.fromMnemonic(bob), "derth/\(validator)", 7)
        chain.openProposal(1)
        await assertThrowsAsync({ try await w.stakeVote(proposalID: 1, validator: self.validator, options: self.yes) }) { $0 is PrivacyWallet.SyncFirst }
        try await w.sync()
        _ = try await w.stakeVote(proposalID: 1, validator: validator, options: yes)
    }

    /// A hostile indexer forging the snapshot's nf_root (adding k's
    /// post-snapshot nullifier) cannot make the vote skip k: the snapshot
    /// comes from the LCD, and k votes.
    func testAForgedIndexerSnapshotDoesNotSuppressTheVote() async throws {
        let v = try await voting()
        let chain = v.chain
        let kNf = v.k.nf
        let forged = WrappedIndexer(chain)
        forged.stakeNfLeavesMap = { p in
            var leaves = p.leaves
            leaves.append((index: (leaves.last?.index ?? 0) + 1, value: kNf))
            return StakeNfLeavesPage(leaves: leaves, nextIndex: p.nextIndex + 1, complete: false, size: p.size + 1, syncedHeight: p.syncedHeight)
        }
        forged.stakeSnapshotsMap = { p in
            StakeSnapshotsPage(rows: p.rows.map { r in
                let values = Array(chain.stakeNfValues.prefix(Int(r.nfSize) - 1)) + [kNf]
                return StakeSnapshotRow(height: r.height, proposalID: r.proposalID, root: r.root, treeSize: r.treeSize,
                                        nfRoot: try! IndexedTree(values).root(), nfSize: r.nfSize + 1)
            }, nextHeight: p.nextHeight, complete: p.complete, syncedHeight: p.syncedHeight)
        }
        let a = try wallet(chain, indexer: forged)
        _ = try await a.sync()
        let items = try await a.stakeVoteItems(proposalID: 1)
        XCTAssertTrue(items.contains(.validator(validator, notes: 2)))
        let r = try await a.castStakeVote(proposalID: 1, item: .validator(validator, notes: 2), options: yes)
        XCTAssertNotNil(r)
        XCTAssertEqual(1, chain.stakeVotes.count)
    }

    /// The snapshot's nullifier tree holds the note's nullifier but sync saw no spend before it: an error, never a silent skip.
    func testASnapshotSpendThatSyncNeverSawIsAnError() async throws {
        let v = try await voting()
        let chain = v.chain
        let real = try chain.snapshotRead(1)
        let values = Array(chain.stakeNfValues.prefix(Int(real.nfSize) - 1)) + [v.k.nf]
        let forgedSnap = PrivacyReads.Snapshot(root: real.root, treeSize: real.treeSize, height: real.height, rates: real.rates,
                                               nfRoot: try IndexedTree(values).root(), nfSize: real.nfSize + 1)
        let a = try wallet(chain, reads: SnapshotReads(inner: FakeReads(chain: chain), snap: forgedSnap, values: values))
        _ = try await a.sync()
        chain.indexerNfTree = false
        let sims = chain.simulated
        await assertThrowsAsync({ try await a.castStakeVote(proposalID: 1, item: .validator(validator, notes: 2), options: self.yes) }) {
            "\($0.localizedDescription)".contains("sync saw no spend")
        }
        XCTAssertEqual(sims, chain.simulated)
    }

    /// A note spent in the snapshot's own block is spent before it: not eligible.
    func testANoteSpentInTheSnapshotBlockIsNotEligible() async throws {
        let v = try await voting()
        let snap = try await v.a.snapshot(proposalID: 1)
        let i = v.a.store.state.stakeNotes.firstIndex { $0.position == v.k.position }!
        v.a.store.mutate { $0.stakeNotes[i].spentHeight = UInt64(snap.height) }
        var items = try await v.a.stakeVoteItems(proposalID: 1)
        XCTAssertEqual([.validator(validator, notes: 1)], items)
        v.a.store.mutate { $0.stakeNotes[i].spentHeight = UInt64(snap.height) + 1 }
        items = try await v.a.stakeVoteItems(proposalID: 1)
        XCTAssertEqual([.validator(validator, notes: 2)], items)
    }

    /// nf_size is the LCD's: an indexer's larger count is never fetched; one aligned page.
    func testTheNullifierFetchIsBoundedByTheLcdSize() async throws {
        let v = try await voting()
        let idx = WrappedIndexer(v.chain)
        idx.stakeSnapshotsMap = { p in
            StakeSnapshotsPage(rows: p.rows.map { StakeSnapshotRow(height: $0.height, proposalID: $0.proposalID, root: $0.root, treeSize: $0.treeSize,
                                                                  nfRoot: $0.nfRoot, nfSize: 1_000_000_000) },
                               nextHeight: p.nextHeight, complete: p.complete, syncedHeight: p.syncedHeight)
        }
        let a = try wallet(v.chain, indexer: idx)
        _ = try await a.sync()
        let r = try await a.castStakeVote(proposalID: 1, item: .validator(validator, notes: 2), options: yes)
        XCTAssertNotNil(r)
        XCTAssertEqual(1, idx.nfLeafAsks.count)
        XCTAssertEqual(0, idx.nfLeafAsks[0].0)
        XCTAssertEqual(WalletSync.pageSize, idx.nfLeafAsks[0].1)
        XCTAssertTrue(v.chain.misaligned.isEmpty, "\(v.chain.misaligned)")
    }

    func testAVotesRequestsFollowThePagingRule() async throws {
        let v = try await voting()
        let r = try await v.a.castStakeVote(proposalID: 1, item: .validator(validator, notes: 2), options: yes)
        XCTAssertNotNil(r)
        XCTAssertTrue(v.chain.misaligned.isEmpty, "\(v.chain.misaligned)")
    }

    /// A position's vote is reported (and persisted by the controller) when the node takes it.
    func testAPositionVoteReportsAcceptanceBeforeItsBlock() async throws {
        let chain = FakeChain()
        let a = try wallet(chain)
        for _ in 0 ..< 4 { try funded(chain, a, 2_000_000) }
        _ = try await a.sync()
        _ = try await a.delegate(validator: validator, amount: 1_000_000); _ = try await a.sync()
        _ = try await a.lockPosition(validator: validator, amount: 100_000, splits: [2: 100]); _ = try await a.sync()
        chain.openProposal(12)
        _ = try await a.sync()
        let pos = try await a.stakeVoteItems(proposalID: 12).first { if case .position = $0 { return true } else { return false } }!
        guard case let .position(id, counter) = pos else { return XCTFail("no position") }
        let p = try await a.positions().first { $0.position.id == id }!.position
        let got = Box<String>()
        chain.unconfirmedNext = 1
        await assertThrowsAsync({ try await a.positionVote(p, counter: counter, proposalID: 12, options: self.yes) { got.v = $0 } })
        XCTAssertNotNil(got.v)
        XCTAssertNotNil(chain.txs[got.v!])
        XCTAssertEqual(1, chain.positionVotes.count)
    }
}
