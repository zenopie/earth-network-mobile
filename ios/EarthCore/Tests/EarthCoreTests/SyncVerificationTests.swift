import BigInt
import Foundation
import XCTest
@testable import EarthCore

/// What a sync takes from the indexer and how it is checked against the
/// chain: roots verified per sync generation, pages and heights bounded by
/// the LCD tip, forged or stale trees left unverified (nothing built on
/// them), and a chain disagreement or a new genesis handled without losing
/// the registration.
final class SyncVerificationTests: PrivacyTestCase {
    /// An indexer appends a forged 50 ERTH note and then fails a later
    /// stream of the same sync. The roots were marked unverified (and
    /// persisted) before the first request: nothing is labelled verified and
    /// no tx is built on the forged tree.
    func testAFailedSyncLeavesNothingVerified() async throws {
        let chain = FakeChain()
        let idx = WrappedIndexer(chain)
        let root = try tmp()
        defer { try? FileManager.default.removeItem(at: root) }
        let w = try wallet(chain, indexer: idx, store: try PrivacyStore.open(root: root, walletID: "w", key: testDataKey))
        try funded(chain, w)
        try await w.sync()
        XCTAssertTrue(w.snapshot.rootsVerified)
        let fake = NotePlaintext.fresh("uerth", 50_000_000)
        let fakeCM = fake.cm(ownerPK: w.keys.ownerPK)
        let fakeCT = try NoteCipher.encrypt(fake, to: w.address)
        idx.notesOverride = { fromPos, _ in
            let rows = chain.notes + [NoteRow(position: UInt64(chain.notes.count), height: chain.height - 1, cm: fakeCM, ciphertext: fakeCT, amount: nil)]
            let page = Array(rows.dropFirst(Int(fromPos)))
            return NotesPage(rows: page, nextPos: fromPos + UInt64(page.count), complete: false, syncedHeight: chain.height - 1)
        }
        idx.identityOverride = { _, _ in throw URLError(.badServerResponse) }
        await assertThrowsAsync({ try await w.sync() })
        XCTAssertFalse(w.snapshot.rootsVerified)
        XCTAssertEqual(WalletSync.syncUnfinished, w.snapshot.rootsError)
        XCTAssertFalse(try PrivacyStore.open(root: root, walletID: "w", key: testDataKey).state.rootsVerified)
        let before = chain.height
        await assertThrowsAsync({ try await w.unshield(receiver: self.receiver, denom: "uerth", amount: 40_000_000) }) {
            ($0 as? PrivacyError)?.message == WalletSync.syncUnfinished
        }
        XCTAssertEqual(before, chain.height)
        XCTAssertEqual(0, chain.simulated)
        idx.notesOverride = nil
        idx.identityOverride = nil
        let r = try await w.sync()
        XCTAssertTrue(r.verified)
        XCTAssertEqual(1_000_000, bal(w, "uerth"))
    }

    func testVerifiedFlagOfAnotherGenerationIsRefused() async throws {
        let chain = FakeChain()
        let w = try wallet(chain)
        try funded(chain, w)
        try await w.sync()
        w.store.mutate { $0.syncGeneration += 1 }
        await assertThrowsAsync({ try await w.unshield(receiver: self.receiver, denom: "uerth", amount: 1000) })
        try await w.sync()
        _ = try await w.unshield(receiver: receiver, denom: "uerth", amount: 1000)
    }

    /// An indexer serving a note the chain never had (encrypted to
    /// us, with roots to match) leaves the wallet unverified: nothing is
    /// built on it, and the honest indexer's stream replaces it.
    func testForgedIndexerTreesAreRefused() async throws {
        let chain = FakeChain()
        let a = try wallet(chain)
        let o = try a.shieldOutput(denom: "uerth", amount: 0)
        chain.shield("uerth", 1_000_000, o.pc, o.ciphertext)
        let fake = NotePlaintext.fresh("uerth", 50_000_000)
        let fakeCM = fake.cm(ownerPK: a.keys.ownerPK)
        let fakeCT = try NoteCipher.encrypt(fake, to: a.address)
        let idx = WrappedIndexer(chain)
        var forge = true
        idx.notesOverride = { fromPos, _ in
            guard forge else { return nil }
            let rows = chain.notes + [NoteRow(position: UInt64(chain.notes.count), height: chain.height - 1, cm: fakeCM, ciphertext: fakeCT, amount: nil)]
            let page = Array(rows.dropFirst(Int(fromPos)))
            return NotesPage(rows: page, nextPos: fromPos + UInt64(page.count), complete: false, syncedHeight: chain.height - 1)
        }
        idx.rootsOverride = { r in
            guard forge else { return r }
            let t = MerkleTree(store: MemNodeStore())
            t.appendAll(chain.notes.map(\.cm) + [fakeCM])
            return LatestRoots(note: RootRecord(root: t.root(), treeSize: t.size, height: chain.height - 1, time: chain.now),
                               identity: r.identity, syncedHeight: r.syncedHeight, stake: r.stake)
        }
        let w = try wallet(chain, indexer: idx)
        let r = try await w.sync()
        XCTAssertFalse(r.verified)
        XCTAssertTrue(w.store.state.rootsError?.contains("no longer holds") == true)
        await assertThrowsAsync({ try await w.unshield(receiver: self.receiver, denom: "uerth", amount: 1) }) { $0 is PrivacyError }
        forge = false
        try await w.sync()
        XCTAssertEqual(1_000_000, bal(w, "uerth"))
        XCTAssertTrue(w.store.state.rootsVerified)
    }

    /// An identity tree the chain contradicts at the indexer's own height wipes what was synced.
    func testForgedIdentityTreeIsAMismatch() async throws {
        let chain = FakeChain()
        let a = try wallet(chain)
        let o = try a.shieldOutput(denom: "uerth", amount: 0)
        chain.shield("uerth", 1_000_000, o.pc, o.ciphertext)
        let leaf = Fr(UInt64(99))
        let idx = WrappedIndexer(chain)
        idx.identityOverride = { fromIndex, _ in
            IdentityPage(rows: fromIndex == 0 ? [IdentityRow(index: 0, height: chain.height - 1, leaf: leaf, zeroedHeight: nil)] : [],
                         nextIndex: 1, size: 1, syncedHeight: chain.height - 1)
        }
        idx.rootsOverride = { r in
            let t = MerkleTree(store: MemNodeStore())
            t.appendAll([leaf])
            return LatestRoots(note: r.note, identity: RootRecord(root: t.root(), treeSize: 1, height: chain.height - 1, time: chain.now),
                               syncedHeight: r.syncedHeight, stake: r.stake)
        }
        let w = try wallet(chain, indexer: idx)
        await assertThrowsAsync({ try await w.sync() }) { $0 is WalletSync.ChainMismatch }
        XCTAssertTrue(w.balances().isEmpty)
        XCTAssertTrue(w.store.state.rootsError?.contains("identity tree") == true)
    }

    /// The indexer is two weeks behind and the chain pruned its root: unverified, nothing wiped.
    func testPrunedRootIsUnverifiedNotAMismatch() async throws {
        let chain = FakeChain()
        let idx = FrozenIndexer(chain)
        let a = try wallet(chain, indexer: idx)
        try funded(chain, a)
        try await a.sync()
        XCTAssertTrue(a.store.state.rootsVerified)
        try await idx.freeze()
        try funded(chain, a, 2_000_000)
        chain.pruneNoteRoots()
        let r = try await a.sync()
        XCTAssertFalse(r.verified)
        XCTAssertTrue(a.store.state.rootsError?.contains("no longer holds") == true)
        XCTAssertEqual(1_000_000, bal(a, "uerth"))
        await assertThrowsAsync({ try await a.unshield(receiver: self.receiver, denom: "uerth", amount: 1) }) { $0 is PrivacyError }
        idx.thaw()
        let r2 = try await a.sync()
        XCTAssertTrue(r2.verified)
        XCTAssertEqual(3_000_000, bal(a, "uerth"))
    }

    /// A node answering a pinned query at another height cannot condemn the trees; equal ones still verify.
    func testOtherEchoedHeightIsUnverifiedNotAMismatch() async throws {
        let chain = FakeChain()
        let a = try wallet(chain)
        try funded(chain, a)
        chain.echoOtherHeight = true
        let r = try await a.sync()
        XCTAssertTrue(r.verified)
        let idx = FrozenIndexer(chain)
        let b = try wallet(chain, indexer: idx)
        let prep = try await b.prepareRegistration(referrer: nil)
        chain.shield("uerth", 100_000, prep.gas.pc, prep.gas.ciphertext)
        try await b.sync()
        try await idx.freeze()
        _ = try await b.register(prep, proof: Data(count: 14_656), publicSignals: signals(prep, "1"), signatureAlgorithm: "lean_poa", dscDer: Data(count: 10))
        let r2 = try await b.sync()
        XCTAssertFalse(r2.verified)
        XCTAssertTrue(b.store.state.rootsError?.contains("could not be read at the indexer's height") == true, b.store.state.rootsError ?? "")
    }

    /// A spend the chain does not hold, slipped into the nullifier stream, is caught by the sample.
    func testInventedSpendIsCaughtBySample() async throws {
        let chain = FakeChain()
        let a = try wallet(chain)
        try funded(chain, a)
        let idx = WrappedIndexer(chain)
        idx.nullifiersOverride = { p in
            HeightPage(blocks: [(height: 1, items: [Fr(UInt64(424_242))])] + p.blocks, nextHeight: p.nextHeight, complete: p.complete, syncedHeight: p.syncedHeight)
        }
        let w = try wallet(chain, indexer: idx)
        let r = try await w.sync()
        XCTAssertFalse(r.verified)
        XCTAssertTrue(w.store.state.rootsError?.contains("spend the chain does not hold") == true)
    }

    func testNullifierSampleExcludesOurOwn() async throws {
        let chain = FakeChain()
        let roots = RecordingRoots(chain)
        let w = try wallet(chain, roots: roots)
        try funded(chain, w)
        try await w.sync()
        _ = try await w.unshield(receiver: receiver, denom: "uerth", amount: 1000)
        try await w.sync()
        let ours = Set(w.notes.map(\.nf))
        XCTAssertFalse(ours.isEmpty)
        XCTAssertTrue(roots.asked.allSatisfy { !ours.contains($0) })
    }

    /// An indexer trailing the chain's tip is labelled unverified.
    func testIndexerBehindTipIsUnverified() async throws {
        let chain = FakeChain()
        let a = try wallet(chain)
        try funded(chain, a)
        chain.tipAhead = WalletSync.staleBlocks + 5
        let r = try await a.sync()
        XCTAssertFalse(r.verified)
        XCTAssertTrue(a.store.state.rootsError?.contains("blocks behind") == true)
        chain.tipAhead = 0
        let r2 = try await a.sync()
        XCTAssertTrue(r2.verified)
    }

    func testAStaleIndexerClaimingTheTipIsUnverified() async throws {
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

    /// One hostile response (a notes page naming synced_height 10^9, a
    /// nullifier page jumping next_height) cannot move the persisted
    /// nullifier cursor past future heights: any height past the LCD tip is
    /// Inconsistent, nothing of it is persisted, and with an honest indexer
    /// the spend is seen.
    func testIndexerHeightsPastTheTipAreRefused() async throws {
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

    /// synced_height = 2^64-1 is Inconsistent, never a trap on `ceiling + 1`.
    func testASyncedHeightOfUInt64MaxIsInconsistentNotATrap() async throws {
        let body = #"{"format":2,"fields":["position","height","cm","ciphertext","amount","owner_pk","rho","rcm"],"notes":[],"next_pos":0,"complete":false,"synced_height":"18446744073709551615"}"#
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

    /// Empty pages that say more follows are inconsistent at once, not asked forever.
    func testEmptyPagesThatSayMoreFollowsAreInconsistent() async throws {
        let chain = FakeChain()
        let idx = WrappedIndexer(chain)
        let calls = Tally()
        idx.notesOverride = { fromPos, _ in
            _ = calls.inc()
            return NotesPage(rows: [], nextPos: fromPos, complete: true, syncedHeight: chain.height - 1)
        }
        await assertThrowsAsync({ try await self.wallet(chain, indexer: idx).sync() }) { $0 is WalletSync.Inconsistent }
        XCTAssertLessThanOrEqual(calls.value, 2)
    }

    func testHeightPagesThatDoNotAdvanceAreInconsistent() async throws {
        let chain = FakeChain()
        try funded(chain, try wallet(chain))
        let idx = WrappedIndexer(chain)
        idx.nullifiersFromOverride = { from in HeightPage(blocks: [], nextHeight: from, complete: true, syncedHeight: chain.height - 1) }
        await assertThrowsAsync({ try await self.wallet(chain, indexer: idx).sync() }) { $0 is WalletSync.Inconsistent }
        let stuck = WrappedIndexer(chain)
        stuck.stakeNullifiersFromOverride = { from in HeightPage(blocks: [(height: from, items: [])], nextHeight: from, complete: true, syncedHeight: chain.height - 1) }
        await assertThrowsAsync({ try await self.wallet(chain, indexer: stuck).sync() }) { $0 is WalletSync.Inconsistent }
        let wrongNext = WrappedIndexer(chain)
        wrongNext.notesOverride = { fromPos, limit in
            let rows = Array(chain.notes.dropFirst(Int(fromPos)))
            return NotesPage(rows: rows, nextPos: fromPos + UInt64(rows.count) + 1, complete: false, syncedHeight: chain.height - 1)
        }
        await assertThrowsAsync({ try await self.wallet(chain, indexer: wrongNext).sync() }) { $0 is WalletSync.Inconsistent }
    }

    /// A position past u32 (or an oversized page) is an inconsistency, never a crash.
    func testOutOfRangePositionsAreInconsistent() async throws {
        let chain = FakeChain()
        let idx = WrappedIndexer(chain)
        idx.notesOverride = { fromPos, _ in
            let p: UInt64 = fromPos == 0 ? 0x1_0000_0000 : fromPos
            return NotesPage(rows: [NoteRow(position: p, height: 1, cm: .one, ciphertext: Data(count: 217), amount: nil)],
                             nextPos: fromPos + 1, complete: false, syncedHeight: 1)
        }
        await assertThrowsAsync({ try await self.wallet(chain, indexer: idx).sync() }) { $0 is WalletSync.Inconsistent }
        let big = WrappedIndexer(chain)
        big.notesOverride = { fromPos, _ in
            NotesPage(rows: (0 ... UInt64(WalletSync.pageSize)).map { NoteRow(position: fromPos + $0, height: 1, cm: .one, ciphertext: Data(), amount: nil) },
                      nextPos: fromPos, complete: false, syncedHeight: 1)
        }
        await assertThrowsAsync({ try await self.wallet(chain, indexer: big).sync() }) { $0 is WalletSync.Inconsistent }
    }

    func testPagesAreAlignedAndTheTipPageIsReasked() async throws {
        let chain = FakeChain()
        let a = try wallet(chain)
        try funded(chain, a)
        func others(_ n: Int) { for _ in 0 ..< n { chain.mint("uerth", 1, NotePlaintext.randomField(), Data(count: NoteCipher.blindCiphertextBytes)) }; chain.emptyBlock() }
        others(250)
        let asked = Box<[UInt64]>(); asked.v = []
        let idx = WrappedIndexer(chain)
        idx.notesOverride = { from, _ in asked.v!.append(from); return nil }
        let keys = try PrivacyKeys.fromMnemonic(alice)
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

    func testSyncHasAnOverallTimeLimit() async throws {
        let chain = FakeChain()
        let idx = WrappedIndexer(chain)
        idx.notesOverride = { fromPos, _ in
            let rows = (0 ..< 4).map { i in NoteRow(position: fromPos + UInt64(i), height: 1, cm: Fr(fromPos + UInt64(i) + 1), ciphertext: Data(), amount: nil) }
            return NotesPage(rows: rows, nextPos: fromPos + 4, complete: true, syncedHeight: chain.height - 1)
        }
        let t = Tally()
        let sync = WalletSync(indexer: idx, store: .memory(), keys: try PrivacyKeys.fromMnemonic(alice), chainID: chain.chainID, chain: chain,
                              now: { [unowned chain] in chain.now }, monotonic: { Double(t.inc()) * 60 })
        await assertThrowsAsync({ try await sync.sync() }) { $0 is WalletSync.SyncTimeout }
    }

    /// The indexer's base moved (404): the wallet reads the status again and carries on.
    func testAMovedIndexerBaseIsReread() async throws {
        let chain = FakeChain()
        let idx = WrappedIndexer(chain)
        var moved = 1
        idx.notesOverride = { _, _ in
            if moved > 0 { moved -= 1; throw IndexerBaseMoved("404") }
            return nil
        }
        try await wallet(chain, indexer: idx).sync()
        XCTAssertEqual(2, idx.statuses)
    }

    /// A status naming no chain is refused.
    func testAnIndexerStatusNamingNoChainIsRefused() async throws {
        let chain = FakeChain()
        let idx = WrappedIndexer(chain)
        idx.statusOverride = {
            IndexerStatus(chainID: nil, syncedHeight: chain.height - 1, syncedTime: chain.now, notes: 0, identityLeaves: 0, halted: nil,
                          genesis: chain.genesis, base: "/privacy/earth-1/\(chain.genesis)")
        }
        await assertThrowsAsync({ try await self.wallet(chain, indexer: idx).sync() }) { $0 is PrivacyError }
    }

    /// A halted indexer is not synced from; a new genesis under the same chain id wipes the local data.
    func testAHaltedIndexerIsNotSyncedAndARelaunchWipes() async throws {
        let chain = FakeChain()
        let a = try wallet(chain)
        let o = try a.shieldOutput(denom: "uerth", amount: 0)
        chain.shield("uerth", 1_000_000, o.pc, o.ciphertext)
        try await a.sync()
        XCTAssertEqual(1_000_000, bal(a, "uerth"))
        chain.halted = "note tree size differs from the chain's"
        await assertThrowsAsync({ try await a.sync() }) { $0 is IndexerHalted }
        chain.halted = nil
        a.store.mutate { _ = $0.claimedDays.insert(5) }
        chain.genesis = "fedcba9876543210"
        try await a.sync()
        XCTAssertEqual("fedcba9876543210", a.store.state.genesis)
        // Wiped and resynced from zero: nothing of the old chain's bookkeeping survives.
        XCTAssertTrue(a.store.state.claimedDays.isEmpty)
        XCTAssertEqual(1_000_000, bal(a, "uerth"))
    }

    /// A relaunch the LCD confirms keeps the registration (record, passport nullifier) and re-verifies its leaf.
    func testAConfirmedGenesisSwitchKeepsTheIdentity() async throws {
        let chain = FakeChain()
        let a = try wallet(chain)
        try await registered(chain, a)
        let id = a.store.state.identity
        a.store.mutate { _ = $0.claimedDays.insert(7) }
        chain.genesis = "fedcba9876543210"
        try await a.sync()
        XCTAssertEqual("fedcba9876543210", a.store.state.genesis)
        XCTAssertEqual(id, a.store.state.identity)
        XCTAssertEqual("555", a.store.state.identity?.passportNullifier)
        XCTAssertEqual(.live, a.identityStatus())
        XCTAssertTrue(a.store.state.claimedDays.isEmpty)
        XCTAssertTrue(a.store.state.rootsVerified)
    }

    /// An indexer's new genesis the LCD does not confirm (or cannot) wipes nothing and syncs nothing.
    func testAnUnconfirmedGenesisSwitchKeepsEverything() async throws {
        let chain = FakeChain()
        let a = try wallet(chain)
        try await registered(chain, a)
        let before = a.balances()
        let id = a.store.state.identity
        chain.lcdGenesis = chain.genesis
        chain.genesis = "fedcba9876543210"
        await assertThrowsAsync({ try await a.sync() }) { $0 is WalletSync.GenesisUnverified }
        chain.lcdGenesis = nil
        chain.lcdBlind = true
        await assertThrowsAsync({ try await a.sync() }) { $0 is WalletSync.GenesisUnverified }
        XCTAssertEqual("0123456789abcdef", a.store.state.genesis)
        XCTAssertEqual(before, a.balances())
        XCTAssertEqual(id, a.store.state.identity)
        XCTAssertFalse(a.store.state.rootsVerified)
        XCTAssertTrue(a.store.state.rootsError?.contains("does not confirm") == true)
        // A first sync goes ahead when the LCD cannot say, never when it contradicts.
        try await wallet(chain).sync()
        chain.lcdBlind = false
        chain.lcdGenesis = "1111111111111111"
        await assertThrowsAsync({ try await self.wallet(chain).sync() }) { $0 is WalletSync.GenesisUnverified }
    }

    func testAStoreWithNoGenesisRecordedKeepsItsIdentity() async throws {
        let chain = FakeChain()
        let w = try wallet(chain)
        try await registered(chain, w)
        let id = w.store.state.identity
        w.store.mutate { $0.genesis = nil }
        try w.store.save()
        try await w.sync()
        XCTAssertEqual(id, w.store.state.identity)
        XCTAssertEqual(.live, w.identityStatus())
        XCTAssertEqual(chain.genesis, w.store.state.genesis)
    }
}
