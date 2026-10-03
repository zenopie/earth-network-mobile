import Foundation
import XCTest
@testable import EarthCore

/// The fourth audit's client findings, the auditor's PoCs
/// (audit4/clients/{ios/A,ios/B,ios/C,vote,android}) ported to assert the
/// safe outcome. Mirrors Android's Audit4Test.
final class Audit4Tests: XCTestCase {
    let a3 = Audit3Tests()
    var receiver: String { a3.receiver }
    let vB = "earthvaloper1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"
    var yes: [WeightedVoteOption] { a3.yes }

    func wallet(_ chain: FakeChain, indexer: PrivacyIndexer? = nil, store: PrivacyStore = .memory(), roots: ChainRoots? = nil,
                reads: PrivacyChainReads? = nil, privateChain: PrivateChain? = nil, words: String? = nil) throws -> PrivacyWallet {
        PrivacyWallet(keys: try PrivacyKeys.fromMnemonic(words ?? a3.alice), store: store, indexer: indexer ?? chain, chain: privateChain ?? chain,
                      reads: reads ?? FakeReads(chain: chain), prover: chain.prover, chainID: chain.chainID, roots: roots ?? chain,
                      now: { [unowned chain] in chain.now })
    }

    func funded(_ chain: FakeChain, _ w: PrivacyWallet, _ amount: UInt64 = 1_000_000) throws { try a3.funded(chain, w, amount) }

    func assertThrowsAsync<T>(_ body: () async throws -> T, _ check: (Error) -> Bool = { _ in true }, line: UInt = #line) async {
        await a3.assertThrowsAsync(body, check, line: line)
    }

    func isInconsistent(_ e: Error) -> Bool { e is WalletSync.Inconsistent }

    // MARK: M1: indexer heights bounded by the LCD tip

    /// Android PoC 1: one hostile response (a notes page naming
    /// synced_height 10^9, a nullifier page jumping next_height) used to move
    /// the persisted nullifier cursor past every future height. Any height
    /// past the LCD tip is now Inconsistent: nothing of it is persisted, and
    /// with an honest indexer the spend is seen.
    func testPoC1_HeightsPastTheTipAreRefused() async throws {
        let chain = FakeChain()
        let far: UInt64 = 1_000_000_000
        let idx = WrappedIndexer(chain)
        let a = try wallet(chain, indexer: idx)
        try funded(chain, a)
        let first = try await a.sync()
        XCTAssertTrue(first.verified)
        idx.notesOverride = { from, limit in
            let rows = Array(chain.notes.dropFirst(Int(from)).prefix(limit ?? 1000))
            return NotesPage(rows: rows, nextPos: from + UInt64(rows.count), complete: false, syncedHeight: far)
        }
        idx.nullifiersFromOverride = { _ in HeightPage(blocks: [], nextHeight: far + 1, complete: false, syncedHeight: far) }
        await assertThrowsAsync({ try await a.sync() }, isInconsistent)
        XCTAssertFalse(a.snapshot.rootsVerified)
        XCTAssertLessThanOrEqual(a.store.state.nullifiersNext, chain.height + WalletSync.tipSlack + 1)
        idx.notesOverride = nil; idx.nullifiersFromOverride = nil
        let honest = try await a.sync()
        XCTAssertTrue(honest.verified)
        _ = try await a.unshield(receiver: receiver, denom: "uerth", amount: 1000)
        while try await chain.tipHeight() <= chain.lastTimeoutHeight { chain.emptyBlock() }
        let after = try await a.sync()
        XCTAssertTrue(after.verified)
        XCTAssertFalse(a.snapshot.notes.contains { $0.unspent && $0.note.value == 1_000_000 })
        XCTAssertLessThan(a.poolBalances()["uerth"] ?? 0, 1_000_000)
    }

    /// iOS PoC A4: synced_height = 2^64-1 used to trap (`ceiling + 1`); it is now Inconsistent.
    func testA4_SyncedHeightMaxIsInconsistentNotATrap() async throws {
        let body = #"{"notes":[],"next_pos":0,"complete":false,"synced_height":"18446744073709551615"}"#
        let j = JSON(try JSONSerialization.jsonObject(with: Data(body.utf8)))
        XCTAssertEqual(UInt64.max, try HTTPPrivacyIndexer.parseNotes(j).syncedHeight)
        let chain = FakeChain()
        let idx = WrappedIndexer(chain)
        idx.notesOverride = { fromPos, _ in NotesPage(rows: [], nextPos: fromPos, complete: false, syncedHeight: UInt64.max) }
        let w = try wallet(chain, indexer: idx)
        await assertThrowsAsync({ try await w.sync() }, isInconsistent)
    }

    /// Every height a page can carry is bounded, a stake page's and /roots/latest's too.
    func testEveryIndexerHeightIsBounded() async throws {
        let chain = FakeChain()
        let far = chain.height + WalletSync.tipSlack + 50
        let cases: [(WrappedIndexer) -> Void] = [
            { $0.rootsOverride = { r in LatestRoots(note: r.note, identity: r.identity, syncedHeight: far, stake: r.stake) } },
            { $0.stakeNullifiersFromOverride = { _ in HeightPage(blocks: [(height: far, items: [Fr(UInt64(9))])], nextHeight: far + 1, complete: false, syncedHeight: far) } },
            { $0.identityZeroedOverride = { _ in HeightPage(blocks: [], nextHeight: far + 1, complete: false, syncedHeight: 1) } },
        ]
        for (i, c) in cases.enumerated() {
            let idx = WrappedIndexer(chain)
            c(idx)
            let a = try wallet(chain, indexer: idx)
            try funded(chain, a)
            await assertThrowsAsync({ try await a.sync() }, { XCTAssertTrue($0 is WalletSync.Inconsistent, "case \(i): \($0)"); return true })
        }
    }

    // MARK: M1: a stale indexer claiming the tip (Android PoC 2)

    func testPoC2_StaleIndexerClaimingTheTipIsUnverified() async throws {
        let chain = FakeChain()
        let a = try wallet(chain)
        try funded(chain, a)
        _ = try await a.sync()
        let n0 = chain.notes.count
        let h0 = chain.height
        let frozenTree = MerkleTree(store: MemNodeStore())
        frozenTree.appendAll(chain.notes.prefix(n0).map(\.cm))
        let rootHeight = chain.noteRootHeights[frozenTree.root()]!
        let frozen = WrappedIndexer(chain)
        frozen.notesOverride = { from, _ in
            let rows = Array(chain.notes.prefix(n0).dropFirst(Int(from)))
            return NotesPage(rows: rows, nextPos: from + UInt64(rows.count), complete: false, syncedHeight: chain.height - 1)
        }
        frozen.nullifiersOverride = { p in HeightPage(blocks: p.blocks.filter { $0.height < h0 }, nextHeight: p.nextHeight, complete: false, syncedHeight: p.syncedHeight) }
        frozen.rootsOverride = { _ in
            LatestRoots(note: RootRecord(root: frozenTree.root(), treeSize: frozenTree.size, height: rootHeight, time: chain.now), identity: nil,
                        syncedHeight: chain.height - 1)
        }
        let other = try wallet(chain)
        _ = try await other.sync()
        _ = try await other.unshield(receiver: receiver, denom: "uerth", amount: 1000)
        for _ in 0 ..< 100 { chain.emptyBlock() }
        let lied = try wallet(chain, indexer: frozen)
        let r = try await lied.sync()
        XCTAssertFalse(r.verified)
        XCTAssertTrue(lied.snapshot.rootsError?.contains("note tree") == true, lied.snapshot.rootsError ?? "")
        await assertThrowsAsync({ try await lied.unshield(receiver: self.receiver, denom: "uerth", amount: 1000) })
    }

    /// The indexer must date its note root as the chain recorded it (RootRecord.height).
    func testNoteRootHeightMustBeTheChains() async throws {
        let chain = FakeChain()
        let idx = WrappedIndexer(chain)
        idx.rootsOverride = { r in
            LatestRoots(note: r.note.map { RootRecord(root: $0.root, treeSize: $0.treeSize, height: $0.height - 1, time: $0.time) },
                        identity: r.identity, syncedHeight: r.syncedHeight, stake: r.stake)
        }
        let a = try wallet(chain, indexer: idx)
        try funded(chain, a)
        chain.emptyBlock()
        let r = try await a.sync()
        XCTAssertFalse(r.verified)
        XCTAssertTrue(a.snapshot.rootsError?.contains("dates its note root") == true)
    }

    // MARK: M2: the restore cover set (iOS PoC A4, Android PoC 5)

    func testA4_CoverSetIsNeverPastTheTip() async throws {
        let chain = FakeChain()
        try await a3.registered(chain, try wallet(chain))
        let regHeight = chain.identityRows[0].height
        for _ in 0 ..< 40 { chain.emptyBlock() }
        chain.identityRowTimes = false
        chain.blockTimeAsks = []
        let inflated = WrappedIndexer(chain)
        inflated.notesOverride = { from, limit in
            let rows = Array(chain.notes.dropFirst(Int(from)).prefix(limit ?? 1000))
            return NotesPage(rows: rows, nextPos: from + UInt64(rows.count), complete: false, syncedHeight: 1_000_000_000_000)
        }
        let bad = try wallet(chain, indexer: inflated)
        await assertThrowsAsync({ try await bad.sync() }, isInconsistent)
        XCTAssertTrue(chain.blockTimeAsks.isEmpty)
        let r = try wallet(chain)
        _ = try await r.sync()
        XCTAssertEqual(.live, r.identityStatus())
        let tip = chain.height - 1
        XCTAssertEqual(WalletSync.coverSet, chain.blockTimeAsks.count)
        XCTAssertTrue(chain.blockTimeAsks.allSatisfy { (1 ... tip).contains($0) })
        XCTAssertTrue(chain.blockTimeAsks.contains(regHeight))
    }

    /// Decoys are drawn from identity row heights first (other registrations' blocks).
    func testCoverSetPrefersIdentityRowHeights() async throws {
        let chain = FakeChain()
        for i in 0 ..< 20 {
            let o = try wallet(chain, words: try BIP39.generateMnemonic())
            let prep = try await o.prepareRegistration(affiliate: nil)
            chain.shield("uerth", 100_000, prep.gas.pc, prep.gas.ciphertext)
            _ = try await o.sync()
            var s = a3.sigs(prep); s[2] = "\(1000 + i)"
            _ = try await o.register(prep, proof: Data(count: 14_656), publicSignals: s, signatureAlgorithm: "lean_poa", dscDer: Data(count: 10))
        }
        try await a3.registered(chain, try wallet(chain))
        for _ in 0 ..< 40 { chain.emptyBlock() }
        chain.identityRowTimes = false
        chain.blockTimeAsks = []
        let r = try wallet(chain)
        _ = try await r.sync()
        XCTAssertEqual(.live, r.identityStatus())
        let rows = Set(chain.identityRows.map(\.height))
        XCTAssertEqual(WalletSync.coverSet, chain.blockTimeAsks.count)
        XCTAssertTrue(chain.blockTimeAsks.allSatisfy { rows.contains($0) })
    }

    // MARK: H1 / M5: forged identity rows (iOS PoC C)

    func forge(_ chain: FakeChain, _ t: UInt64) throws -> WrappedIndexer {
        let keys = try PrivacyKeys.fromMnemonic(a3.alice)
        let forge: (IdentityRow) -> IdentityRow = { r in
            IdentityRow(index: r.index, height: r.height, leaf: PrivacyHash.identityLeaf(idc: keys.idc, dscKey: Fr(UInt64(77)), country: .zero, activatedAt: t),
                        zeroedHeight: r.zeroedHeight, time: t)
        }
        let lying = WrappedIndexer(chain)
        lying.identityMap = { p in IdentityPage(rows: p.rows.map(forge), nextIndex: p.nextIndex, size: p.size, syncedHeight: p.syncedHeight) }
        lying.rootsOverride = { r in
            let tree = MerkleTree(store: MemNodeStore())
            tree.appendAll(chain.identityRows.map { forge($0).leaf })
            return LatestRoots(note: r.note, identity: RootRecord(root: tree.root(), treeSize: tree.size, height: r.identity!.height, time: 0),
                               syncedHeight: r.syncedHeight, stake: r.stake)
        }
        return lying
    }

    /// A row time the chain cannot have (2^64-1, past the tip, before 2025) is Inconsistent.
    func testIdentityRowTimesAreBounded() async throws {
        let chain = FakeChain()
        try await a3.registered(chain, try wallet(chain))
        for t in [UInt64.max, UInt64(chain.now) + WalletSync.timeSlack + 1, 1_000] {
            let idx = WrappedIndexer(chain)
            idx.identityMap = { p in IdentityPage(rows: p.rows.map { $0.with(time: t) }, nextIndex: p.nextIndex, size: p.size, syncedHeight: p.syncedHeight) }
            let w = try wallet(chain, indexer: idx)
            await assertThrowsAsync({ try await w.sync() }, isInconsistent)
        }
    }

    /// PoC C (claimOpensAt trap): the forged tree is unverified, so no record is matched against it: no identity, no trap.
    func testC_ForgedIdentityOnAnUnverifiedTreeIsNeverMatched() async throws {
        let chain = FakeChain()
        try await a3.registered(chain, try wallet(chain))
        for _ in 0 ..< 5 { chain.emptyBlock() }
        chain.echoOtherHeight = true
        let w = try wallet(chain, indexer: try forge(chain, UInt64(chain.now) - 10))
        let r = try await w.sync()
        XCTAssertFalse(r.verified)
        XCTAssertNil(w.store.state.identity)
        XCTAssertNil(w.claimOpensAt())
    }

    /// PoC C (forged activated_at survives a ChainMismatch): pinned, the forged tree is a mismatch and
    /// no identity was matched against it; an honest indexer afterwards finds the real one.
    func testC_ForgedIdentityNeverSurvivesAMismatch() async throws {
        let chain = FakeChain()
        try await a3.registered(chain, try wallet(chain))
        let realAt = chain.identityRows[0].time!
        for _ in 0 ..< 5 { chain.emptyBlock() }
        let store = PrivacyStore.memory()
        let lying = try wallet(chain, indexer: try forge(chain, UInt64(chain.now) - 10_000), store: store)
        await assertThrowsAsync({ try await lying.sync() }) { $0 is WalletSync.ChainMismatch }
        XCTAssertNil(store.state.identity)
        let honest = try wallet(chain, store: store)
        for _ in 0 ..< 3 { _ = try? await honest.sync() }
        XCTAssertEqual(.live, honest.identityStatus())
        XCTAssertEqual(realAt, store.state.identity?.activatedAt)
        XCTAssertEqual(true, store.state.identity?.verified)
    }

    /// A reset keeps only a verified identity.
    func testResetDropsAnUnverifiedIdentity() async throws {
        let chain = FakeChain()
        let w = try wallet(chain)
        try await a3.registered(chain, w)
        let id = try XCTUnwrap(w.store.state.identity)
        XCTAssertTrue(id.verified)
        try w.store.reset(chainID: chain.chainID)
        XCTAssertEqual(id, w.store.state.identity)
        w.store.mutate { $0.identity?.verified = false }
        try w.store.reset(chainID: chain.chainID)
        XCTAssertNil(w.store.state.identity)
        _ = try await w.sync()
        XCTAssertEqual(.live, w.identityStatus())
        XCTAssertEqual(id.activatedAt, w.store.state.identity?.activatedAt)
        XCTAssertEqual(true, w.store.state.identity?.verified)
    }

    /// PoC C (CRASH): claimOpensAt never traps; an activated_at at the end of time has no answer.
    func testC_ClaimOpensAtIsChecked() async throws {
        let chain = FakeChain()
        chain.now = Int64.max - 1000
        let w = try wallet(chain)
        try await a3.registered(chain, w)
        XCTAssertNil(w.claimOpensAt())
    }

    /// PoC C (legacyDec): a non-ASCII digit is refused, never a trap.
    func testC_LegacyDecRefusesNonAsciiDigits() {
        XCTAssertThrowsError(try PrivateMsgs.legacyDec("0.\u{0665}"))
        XCTAssertThrowsError(try PrivateMsgs.legacyDec("\u{0661}"))
        XCTAssertEqual("0.500000000000000000", try PrivateMsgs.legacyDec("0.5"))
    }

    // MARK: M3: the snapshot is the chain's (vote PoC)

    struct Voting { let chain: FakeChain; let a: PrivacyWallet; let k: OwnedStakeNote }

    /// k delegated before proposal 1's snapshot and restaked (spent) after it: on chain it still votes.
    func voting(indexer: WrappedIndexer? = nil) async throws -> Voting {
        let chain = FakeChain()
        let a = try wallet(chain)
        for _ in 0 ..< 6 { try funded(chain, a, 2_000_000) }
        _ = try await a.sync()
        _ = try await a.delegate(validator: vB, amount: 666_666); _ = try await a.sync()
        let k = a.snapshot.stakeNotes[0]
        _ = try await a.delegate(validator: vB, amount: 333_333); _ = try await a.sync()
        let m = a.snapshot.stakeNotes.max { $0.position < $1.position }!
        _ = try await a.restake(validator: vB, notes: [m], amounts: [m.amount / 2, m.amount - m.amount / 2]); _ = try await a.sync()
        chain.openProposal(1)
        let k2 = a.snapshot.stakeNotes.first { $0.position == k.position }!
        _ = try await a.restake(validator: vB, notes: [k2], amounts: [k.amount / 2, k.amount - k.amount / 2]); _ = try await a.sync()
        return Voting(chain: chain, a: a, k: k)
    }

    /// Vote PoC: a hostile indexer forged the snapshot's nf_root (adding k's
    /// post-snapshot nullifier) and the vote was silently skipped. The
    /// snapshot now comes from the LCD: k votes.
    func testVote_ForgedIndexerSnapshotDoesNotSuppressTheVote() async throws {
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
        XCTAssertTrue(items.contains(.note(v.k.position)))
        let r = try await a.castStakeVote(proposalID: 1, item: .note(v.k.position), options: yes)
        XCTAssertNotNil(r)
        XCTAssertEqual(1, chain.stakeVotes.count)
    }

    struct Reads: PrivacyChainReads, @unchecked Sendable {
        let inner: FakeReads
        let snap: PrivacyReads.Snapshot
        let values: [Fr]
        func personhoodParams() async throws -> PrivacyReads.PersonhoodParams { try await inner.personhoodParams() }
        func ballotInputs(proposalID: UInt64, optionID: UInt64) async throws -> PrivacyReads.BallotInputs {
            try await inner.ballotInputs(proposalID: proposalID, optionID: optionID)
        }
        func epochNumber() async throws -> UInt64 { try await inner.epochNumber() }
        func snapshot(proposalID: UInt64) async throws -> PrivacyReads.Snapshot { snap }
        func positions() async throws -> [PrivacyReads.Position] { try await inner.positions() }
        func stakeNullifierTree(start: UInt64, limit: Int) async throws -> PrivacyReads.NfTreePage {
            PrivacyReads.NfTreePage(values: Array(values.dropFirst(Int(start)).prefix(limit)), size: UInt64(values.count) + 1)
        }
    }

    /// The snapshot's nullifier tree holds the note's nullifier but sync saw no spend before it: an error, never a silent skip.
    func testVote_SpentBeforeSnapshotDisagreeingWithSyncIsAnError() async throws {
        let v = try await voting()
        let chain = v.chain
        let real = try chain.snapshotRead(1)
        let values = Array(chain.stakeNfValues.prefix(Int(real.nfSize) - 1)) + [v.k.nf]
        let forgedSnap = PrivacyReads.Snapshot(root: real.root, treeSize: real.treeSize, height: real.height, rates: real.rates,
                                               nfRoot: try IndexedTree(values).root(), nfSize: real.nfSize + 1)
        let a = try wallet(chain, reads: Reads(inner: FakeReads(chain: chain), snap: forgedSnap, values: values))
        _ = try await a.sync()
        chain.indexerNfTree = false
        let sims = chain.simulated
        await assertThrowsAsync({ try await a.castStakeVote(proposalID: 1, item: .note(v.k.position), options: self.yes) }) {
            "\($0.localizedDescription)".contains("sync saw no spend")
        }
        XCTAssertEqual(sims, chain.simulated)
    }

    /// A note spent in the snapshot's own block is spent before it: not eligible.
    func testVote_NoteSpentInTheSnapshotBlockIsNotEligible() async throws {
        let v = try await voting()
        let snap = try await v.a.snapshot(proposalID: 1)
        let i = v.a.store.state.stakeNotes.firstIndex { $0.position == v.k.position }!
        v.a.store.mutate { $0.stakeNotes[i].spentHeight = UInt64(snap.height) }
        var items = try await v.a.stakeVoteItems(proposalID: 1)
        XCTAssertFalse(items.contains(.note(v.k.position)))
        v.a.store.mutate { $0.stakeNotes[i].spentHeight = UInt64(snap.height) + 1 }
        items = try await v.a.stakeVoteItems(proposalID: 1)
        XCTAssertTrue(items.contains(.note(v.k.position)))
    }

    /// nf_size is the LCD's: an indexer's larger count is never fetched (L4); one aligned page.
    func testVote_NullifierFetchIsBoundedByTheLcdSize() async throws {
        let v = try await voting()
        let idx = WrappedIndexer(v.chain)
        idx.stakeSnapshotsMap = { p in
            StakeSnapshotsPage(rows: p.rows.map { StakeSnapshotRow(height: $0.height, proposalID: $0.proposalID, root: $0.root, treeSize: $0.treeSize,
                                                                  nfRoot: $0.nfRoot, nfSize: 1_000_000_000) },
                               nextHeight: p.nextHeight, complete: p.complete, syncedHeight: p.syncedHeight)
        }
        let a = try wallet(v.chain, indexer: idx)
        _ = try await a.sync()
        let r = try await a.castStakeVote(proposalID: 1, item: .note(v.k.position), options: yes)
        XCTAssertNotNil(r)
        XCTAssertEqual(1, idx.nfLeafAsks.count)
        XCTAssertEqual(0, idx.nfLeafAsks[0].0)
        XCTAssertEqual(WalletSync.pageSize, idx.nfLeafAsks[0].1)
        XCTAssertTrue(v.chain.misaligned.isEmpty, "\(v.chain.misaligned)")
    }

    /// L1: the stake votes cast survive a same-chain reset.
    func testStakeVotesSurviveAReset() async throws {
        let v = try await voting()
        _ = try await v.a.castStakeVote(proposalID: 1, item: .note(v.k.position), options: yes)
        let votes = v.a.store.state.stakeVotes
        XCTAssertEqual(1, votes.count)
        try v.a.store.reset(chainID: v.chain.chainID)
        XCTAssertEqual(votes, v.a.store.state.stakeVotes)
        _ = try await v.a.sync()
        let again = try await v.a.castStakeVote(proposalID: 1, item: .note(v.k.position), options: yes)
        XCTAssertNil(again)
        XCTAssertEqual(1, v.chain.stakeVotes.count)
    }

    /// 1119 counts as "already voted" only in x/shieldedstaking's codespace.
    func testAlreadyVotedNeedsTheCodespace() {
        XCTAssertTrue(PrivacyWallet.alreadyVotedError(UnsignedTx.TxRejected(code: 1119, log: "x", codespace: "shieldedstaking")))
        XCTAssertFalse(PrivacyWallet.alreadyVotedError(UnsignedTx.TxRejected(code: 1119, log: "x", codespace: "wasm")))
        XCTAssertFalse(PrivacyWallet.alreadyVotedError(PrivacyError("tx rejected (code 1119): something else")))
        XCTAssertTrue(PrivacyWallet.alreadyVotedError(PrivacyError("simulate failed (400) this stake note already voted on this proposal")))
    }

    /// A refused vote's record is forgotten: the note may vote again.
    func testRefusedVoteIsForgotten() async throws {
        let v = try await voting()
        v.chain.rejectNext = 1
        await assertThrowsAsync({ try await v.a.castStakeVote(proposalID: 1, item: .note(v.k.position), options: self.yes) }) { $0 is UnsignedTx.TxRejected }
        XCTAssertTrue(v.a.store.state.stakeVotes.isEmpty)
        let r = try await v.a.castStakeVote(proposalID: 1, item: .note(v.k.position), options: yes)
        XCTAssertNotNil(r)
        XCTAssertEqual(1, v.a.store.state.stakeVotes.count)
    }

    // MARK: M1: pending marked before the broadcast, released by the chain's word

    final class LostAnswer: PrivateChain, @unchecked Sendable {
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

    func testSpendsArePendingBeforeTheBroadcastAnswers() async throws {
        let chain = FakeChain()
        let a = try wallet(chain, privateChain: LostAnswer(chain))
        try funded(chain, a)
        _ = try await a.sync()
        await assertThrowsAsync({ try await a.unshield(receiver: self.receiver, denom: "uerth", amount: 1000) }) { $0 is URLError }
        let n = try XCTUnwrap(a.store.state.notes.first { $0.note.value == 1_000_000 })
        XCTAssertNotNil(n.pendingAt)
        XCTAssertEqual(chain.txs.keys.first, n.pendingTx)
    }

    func testARefusedBroadcastUnmarks() async throws {
        let chain = FakeChain()
        let a = try wallet(chain)
        try funded(chain, a)
        _ = try await a.sync()
        chain.rejectNext = 1
        await assertThrowsAsync({ try await a.unshield(receiver: self.receiver, denom: "uerth", amount: 1000) }) { $0 is UnsignedTx.TxRejected }
        XCTAssertTrue(a.store.state.notes.allSatisfy { $0.pendingAt == nil })
    }

    func testPendingIsReleasedOnlyOnTheChainsWord() async throws {
        let chain = FakeChain()
        let idx = WrappedIndexer(chain)
        let a = try wallet(chain, indexer: idx)
        try funded(chain, a)
        _ = try await a.sync()
        // Dropped from the mempool: missing once past its timeout, released.
        chain.dropNext = 1
        await assertThrowsAsync({ try await a.unshield(receiver: self.receiver, denom: "uerth", amount: 1000) })
        while try await chain.tipHeight() <= chain.lastTimeoutHeight { chain.emptyBlock() }
        let r1 = try await a.sync()
        XCTAssertTrue(r1.verified)
        XCTAssertTrue(a.store.state.notes.allSatisfy { $0.pendingAt == nil })
        // Committed, its spend hidden by the indexer: kept pending, the sync unverified.
        let hide = a.store.state.notes[0].nf
        idx.nullifiersOverride = { p in HeightPage(blocks: p.blocks.map { (height: $0.height, items: $0.items.filter { $0 != hide }) },
                                                   nextHeight: p.nextHeight, complete: p.complete, syncedHeight: p.syncedHeight) }
        chain.unconfirmedNext = 1
        await assertThrowsAsync({ try await a.unshield(receiver: self.receiver, denom: "uerth", amount: 1000) })
        while try await chain.tipHeight() <= chain.lastTimeoutHeight { chain.emptyBlock() }
        let r2 = try await a.sync()
        XCTAssertFalse(r2.verified)
        XCTAssertTrue(a.snapshot.rootsError?.contains("did not report its spend") == true)
        XCTAssertNotNil(a.store.state.notes.first { $0.nf == hide }?.pendingAt)
        chain.txLookupBlind = true
        _ = try await a.sync()
        XCTAssertNotNil(a.store.state.notes.first { $0.nf == hide }?.pendingAt)
    }

    // MARK: M4: the vote controller and the session (iOS PoC B)

    /// PoC B1: a suspend landing while a start is in flight stops it: nothing is cast.
    func testB1_SuspendDuringStartStopsIt() async throws {
        let chain = FakeChain()
        let a = try await a3.staked(chain)
        let box = Box<StakeVoteController>()
        let calls = Tally()
        let c = StakeVoteController(wallet: {
            if calls.inc() == 1 { box.v?.suspend() }
            return a
        }, pause: { _ in })
        box.v = c
        await assertThrowsAsync({ try await c.startAndAwaitFirst(proposalID: 12, options: self.yes) })
        await c.wait()
        XCTAssertEqual(0, chain.stakeVotes.count)
        XCTAssertEqual(0, chain.positionVotes.count)
        XCTAssertNotEqual(true, c.progress?.finished)
    }

    /// PoC B1b: the same through resume.
    func testB1b_SuspendDuringResumeStopsIt() async throws {
        let chain = FakeChain()
        let a = try await a3.staked(chain)
        a.store.mutate { $0.stakeVoteRun = StakeVoteRun(proposalID: 12, options: [.init(option: 1, weight: "1")], votedPositions: [], total: 3) }
        let box = Box<StakeVoteController>()
        let calls = Tally()
        let c = StakeVoteController(wallet: {
            if calls.inc() == 1 { box.v?.suspend() }
            return a
        }, pause: { _ in })
        box.v = c
        await c.resume()
        await c.wait()
        XCTAssertEqual(0, chain.stakeVotes.count + chain.positionVotes.count)
        // Kept for the next unlock.
        XCTAssertNotNil(a.store.state.stakeVoteRun)
    }

    /// PoC B2: once suspended, a run says nothing more on screen.
    func testB2_SuspendedRunReportsNothingMore() async throws {
        let chain = FakeChain()
        let a = try await a3.staked(chain)
        let seen = Box<[StakeVoteController.Progress?]>(); seen.v = []
        let lock = NSLock()
        let waiting = Tally()
        let c = StakeVoteController(wallet: { a }, pause: { _ in _ = waiting.inc(); try await Task.sleep(nanoseconds: 60_000_000_000) },
                                    onProgress: { p in lock.lock(); seen.v!.append(p); lock.unlock() })
        _ = try await c.startAndAwaitFirst(proposalID: 12, options: yes)
        while waiting.value == 0 { try await Task.sleep(nanoseconds: 10_000_000) }
        lock.lock(); let before = seen.v!.count; lock.unlock()
        c.suspend()
        await c.wait()
        lock.lock(); let after = Array(seen.v![before...]); lock.unlock()
        XCTAssertEqual(1, after.count)
        XCTAssertNil(after.first ?? nil)
        XCTAssertNil(c.progress)
    }

    /// PoC B3: cancelling the request stops the proof of work.
    func testB3_CancellingTheRequestStopsTheWork() async throws {
        let g = GasPowTests()
        let s = GasPowTests.Server(GasPow.maxBits, binding: g.binding, nullifier: g.nullifier)
        let p = GasPowTests.Progress()
        let outer = Task { try await g.request(s) { p.set($0) } }
        try await Task.sleep(nanoseconds: 200_000_000)
        outer.cancel()
        try await Task.sleep(nanoseconds: 300_000_000)
        let a = p.value
        try await Task.sleep(nanoseconds: 1_000_000_000)
        XCTAssertEqual(a, p.value, "the hashcash stopped with the request")
        XCTAssertEqual(24, GasPow.maxBits)
    }

    /// L5: a position's vote is reported (and persisted by the controller) when the node takes it.
    func testPositionVoteReportsAcceptanceBeforeItsBlock() async throws {
        let chain = FakeChain()
        let a = try wallet(chain)
        for _ in 0 ..< 4 { try funded(chain, a, 2_000_000) }
        _ = try await a.sync()
        _ = try await a.delegate(validator: vB, amount: 1_000_000); _ = try await a.sync()
        _ = try await a.lockPosition(validator: vB, amount: 100_000, splits: [2: 100]); _ = try await a.sync()
        chain.openProposal(12)
        _ = try await a.sync()
        let pos = try await a.stakeVoteItems(proposalID: 12).first { if case .position = $0 { return true } else { return false } }!
        let got = Box<String>()
        chain.unconfirmedNext = 1
        await assertThrowsAsync({ try await a.castStakeVote(proposalID: 12, item: pos, options: self.yes) { got.v = $0 } })
        XCTAssertNotNil(got.v)
        XCTAssertNotNil(chain.txs[got.v!])
        XCTAssertEqual(1, chain.positionVotes.count)
    }

    // MARK: the backend's paging rule; hostile bodies

    func testPagesAreAlignedAndTheTipPageIsReasked() async throws {
        let chain = FakeChain()
        let a = try wallet(chain)
        try funded(chain, a)
        func others(_ n: Int) { for _ in 0 ..< n { chain.mint("uerth", 1, NotePlaintext.randomField(), Data(count: NoteCipher.blindCiphertextBytes)) }; chain.emptyBlock() }
        others(250)
        let asked = Box<[UInt64]>(); asked.v = []
        let idx = WrappedIndexer(chain)
        idx.notesOverride = { from, _ in asked.v!.append(from); return nil }
        let keys = try PrivacyKeys.fromMnemonic(a3.alice)
        let store = PrivacyStore.memory()
        _ = try await WalletSync(indexer: idx, store: store, keys: keys, chainID: chain.chainID, chain: chain).sync(pageLimit: 100)
        XCTAssertEqual([0, 100, 200], asked.v)
        XCTAssertEqual(chain.noteTree.root(), store.noteTree.root())
        others(30)
        asked.v = []
        _ = try await WalletSync(indexer: idx, store: store, keys: keys, chainID: chain.chainID, chain: chain).sync(pageLimit: 100)
        XCTAssertEqual([200], asked.v)
        XCTAssertEqual(chain.noteTree.root(), store.noteTree.root())
        XCTAssertTrue(chain.misaligned.isEmpty)
        // A held row served differently on the re-asked page is an inconsistency.
        let lying = WrappedIndexer(chain)
        lying.notesOverride = { from, limit in
            let rows = Array(chain.notes.dropFirst(Int(from)).prefix(limit ?? 1000))
            let bad = rows.enumerated().map { i, r in i == 0 ? NoteRow(position: r.position, height: r.height, cm: Fr(UInt64(5)), ciphertext: r.ciphertext, amount: r.amount) : r }
            return NotesPage(rows: bad, nextPos: from + UInt64(rows.count), complete: rows.count == (limit ?? 1000), syncedHeight: chain.height - 1)
        }
        others(1)
        await assertThrowsAsync({ try await WalletSync(indexer: lying, store: store, keys: keys, chainID: chain.chainID, chain: chain).sync(pageLimit: 100) },
                                isInconsistent)
    }

    func testWalletRequestsFollowThePagingRule() async throws {
        let v = try await voting()
        let r = try await v.a.castStakeVote(proposalID: 1, item: .note(v.k.position), options: yes)
        XCTAssertNotNil(r)
        XCTAssertTrue(v.chain.misaligned.isEmpty, "\(v.chain.misaligned)")
    }

    /// Deep JSON is refused before any parser sees it; brackets inside strings do not count.
    func testDeepJsonIsRefusedBeforeParsing() throws {
        let deep = Data(("{\"notes\":" + String(repeating: "[", count: 500_000)).utf8)
        XCTAssertThrowsError(try EarthRest.checkJSONDepth(deep))
        XCTAssertNoThrow(try EarthRest.checkJSONDepth(Data(#"{"a":"[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[\"]","b":[[1]]}"#.utf8)))
    }

    func indexer() -> HTTPPrivacyIndexer {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [ScriptedProtocol.self]
        return HTTPPrivacyIndexer(host: URL(string: "https://indexer.test")!, chainID: "earth-1", configuration: config) { ms in ScriptedProtocol.slept(ms) }
    }

    /// A busy indexer (503 with Retry-After, 429) is waited out, then given up on; a page size it does not serve is never asked.
    func testBusyIndexerIsBackedOff() async throws {
        ScriptedProtocol.reset()
        ScriptedProtocol.busy = 2
        let idx = indexer()
        let page = try await idx.notes(fromPos: 0, limit: 1000)
        XCTAssertEqual(0, page.rows.count)
        XCTAssertEqual([3000, 3000], ScriptedProtocol.sleeps)
        ScriptedProtocol.reset()
        ScriptedProtocol.busy = 100
        ScriptedProtocol.busyCode = 429
        await assertThrowsAsync({ try await self.indexer().notes(fromPos: 0, limit: nil) }) {
            if case HTTPPrivacyIndexer.Error.busy = $0 { return true }
            return false
        }
        XCTAssertEqual([1000, 2000, 4000, 8000], ScriptedProtocol.sleeps)
        await assertThrowsAsync({ try await self.indexer().notes(fromPos: 0, limit: 5000) })
    }

    /// Android PoC 4: a redirect is never followed; the other origin is never asked.
    func testRedirectsAreNotFollowed() async throws {
        ScriptedProtocol.reset()
        ScriptedProtocol.redirect = true
        await assertThrowsAsync({ try await self.indexer().notes(fromPos: 0, limit: 1000) })
        XCTAssertFalse(ScriptedProtocol.hosts.contains("elsewhere.test"))
    }
}

final class Audit4IndexedCrossCheck: XCTestCase {
    /// Vote audit: 40 values inserted in a non-monotonic order by the chain's
    /// zk/indexed Insert (audit4 tools/ixpoc) give the root the wallet's batch build does.
    func testRandomOrderMatchesChain() throws {
        let values = [
            "0000000000000000000000000000000041a027f7785144a55afc45053c7f6819",
            "000000000000000000000000000000000e74f84711b59f587c9c1cad13ef5f24",
            "00000000000000000000000000000000162331bf65d3f2322ba8d991b6ee5024",
            "000000000000000000000000000000002f4f06b98574ded50d2ab81ec4774790",
            "0000000000000000000000000000000020b76d1f02eb1c11d68153f3947cec90",
            "00000000000000000000000000000000785ff57fe5a5217e61e2bad7778919e1",
            "000000000000000000000000000000005d9076f293f65d8143fcd0fd4c2e2f11",
            "000000000000000000000000000000007a2b38172940e200cecf9aff080966a4",
            "000000000000000000000000000000001ef049ca638d87936fc093b0a6030ba9",
            "0000000000000000000000000000000052b8ff4ebf53d5e76f2a209ff350c584",
            "0000000000000000000000000000000006465c2f9e05da1709157c2bd10d4fe9",
            "000000000000000000000000000000002c7855dc3608bdc4935514ef1bb8e139",
            "0000000000000000000000000000000004357e97fabe65184baac1abebd69b39",
            "0000000000000000000000000000000002d8bbe518b172eed7cb954c2b88d044",
            "00000000000000000000000000000000357eed4f7fbb3185aa23414cf6d9b171",
            "00000000000000000000000000000000053fbb00b4ecd04d4137a1ee380771b1",
            "0000000000000000000000000000000024a8473c44be89f91f5059c9579a5040",
            "00000000000000000000000000000000095b37c17798de8b140d01f830394ba9",
            "000000000000000000000000000000001de1b43699d6a00a48127ae035efde11",
            "000000000000000000000000000000003f0d619b304ae65c847be589f24fed21",
            "000000000000000000000000000000000151374cbbc54fa4b844ed100af63199",
            "0000000000000000000000000000000085dd1622c606bac6f477b5635da5aad1",
            "0000000000000000000000000000000001463d903b7df8c388cd319293b1bf64",
            "000000000000000000000000000000003a913350dcde598f2d3bcaea3e79bb44",
            "000000000000000000000000000000000272e77861f6426e6e0aa885142372b9",
            "0000000000000000000000000000000000f3d73053fedbe27ba9328854638e10",
            "000000000000000000000000000000006deed5b02eb9dd0fa0f153a316afde99",
            "00000000000000000000000000000000061c5c8cd37c721240e0267fe6e1ff31",
            "00000000000000000000000000000000230b475f441aff4a08cd5cc9eaa16919",
            "000000000000000000000000000000003bd136463b517a0c26d7e648454cb639",
            "00000000000000000000000000000000031127cf8a83168d011f3db6247d0281",
            "000000000000000000000000000000003e7821f58d76f39d6a5c48f4c2dc34a9",
            "00000000000000000000000000000000537a729d759281172d9c0e487a7da064",
            "00000000000000000000000000000000839fa3b92e3ee21284f6e39bcb0b7c90",
            "000000000000000000000000000000004b2fc9fe6e1e0bdf8b2239c288359a99",
            "0000000000000000000000000000000019e0fad5d4d9973563670ef60b7f5504",
            "00000000000000000000000000000000679e1b9056780fa53c1102de2e208924",
            "000000000000000000000000000000005971378e2fea038626401fe3f95a2b01",
            "00000000000000000000000000000000387ec0ee76642409ce9acf571e276059",
            "00000000000000000000000000000000552fbca30e05fa2e02fc57a6d7db8400",
        ]
        let t = try IndexedTree(try values.map { try Fr(hex: $0) })
        XCTAssertEqual("2e98e4e0f9c6b5fa34290a608292d4622e2447ac7e42139deb35a53eaf75976b", t.root().hex)
    }
}

final class Box<T>: @unchecked Sendable { var v: T?; init() {} }

/// Serves the indexer: /privacy/status, then per the script (busy, redirect) a notes page.
final class ScriptedProtocol: URLProtocol {
    private static let lock = NSLock()
    nonisolated(unsafe) static var busy = 0
    nonisolated(unsafe) static var busyCode = 503
    nonisolated(unsafe) static var redirect = false
    nonisolated(unsafe) static var sleeps: [UInt64] = []
    nonisolated(unsafe) static var hosts: [String] = []

    static func reset() { lock.lock(); busy = 0; busyCode = 503; redirect = false; sleeps = []; hosts = []; lock.unlock() }
    static func slept(_ ms: UInt64) { lock.lock(); sleeps.append(ms); lock.unlock() }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let url = request.url!
        Self.lock.lock()
        Self.hosts.append(url.host ?? "")
        let busy = Self.busy > 0 && url.path != "/privacy/status"
        if busy { Self.busy -= 1 }
        let code = Self.busyCode, redirect = Self.redirect
        Self.lock.unlock()
        func send(_ status: Int, _ headers: [String: String], _ body: Data) {
            let resp = HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: headers)!
            client?.urlProtocol(self, didReceive: resp, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: body)
            client?.urlProtocolDidFinishLoading(self)
        }
        if url.path == "/privacy/status" {
            send(200, ["Content-Type": "application/json"], Data(#"{"chain_id":"earth-1","genesis":"0123456789abcdef","base":"/privacy/earth-1/0123456789abcdef","synced_height":1,"halted":null}"#.utf8))
        } else if busy {
            send(code, code == 503 ? ["Retry-After": "3"] : [:], Data())
        } else if redirect {
            let to = URL(string: "https://elsewhere.test" + url.path)!
            let resp = HTTPURLResponse(url: url, statusCode: 302, httpVersion: "HTTP/1.1", headerFields: ["Location": to.absoluteString])!
            client?.urlProtocol(self, wasRedirectedTo: URLRequest(url: to), redirectResponse: resp)
            client?.urlProtocol(self, didReceive: resp, cacheStoragePolicy: .notAllowed)
            client?.urlProtocolDidFinishLoading(self)
        } else {
            send(200, ["Content-Type": "application/json"], Data(#"{"notes":[],"next_pos":0,"complete":false,"synced_height":1}"#.utf8))
        }
    }

    override func stopLoading() {}
}
