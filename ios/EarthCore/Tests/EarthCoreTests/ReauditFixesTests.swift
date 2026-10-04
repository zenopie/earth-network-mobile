import Foundation
import XCTest
@testable import EarthCore

/// The clients re-audit's findings, each against `FakeChain` (ports
/// ReauditFixesTest.kt): K1 (tagged record notes, bounded restore), K5
/// (stake votes spaced through the UI's controller), K6 (genesis switch
/// verified), K7 (registration recorded at acceptance), K8 (pruned root),
/// K9 (pinned heights, sampled nullifiers, indexer behind), K10 (indexer
/// base), K11 (closed owner tags), K12 (amounts).
final class ReauditFixesTests: XCTestCase {
    let alice = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    let v1 = "earthvaloper1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"
    let v2 = "earthvaloper1qyqszqgpqyqszqgpqyqszqgpqyqszqgpjnp7du"
    let receiver = "earth1qqqsyqcyq5rqwzqfpg9scrgwpugpzysncc2uls"
    let yes = [WeightedVoteOption(option: WeightedVoteOption.yes, weight: "1")]

    func wallet(_ chain: FakeChain, indexer: PrivacyIndexer? = nil, store: PrivacyStore = .memory(),
                now: (@Sendable () -> Int64)? = nil) throws -> PrivacyWallet {
        let reads = FakeReads(chain: chain)
        return PrivacyWallet(keys: try PrivacyKeys.fromMnemonic(alice), store: store, indexer: indexer ?? chain, chain: chain,
                             reads: reads, prover: chain.prover, chainID: chain.chainID, roots: chain,
                             now: now ?? { [unowned chain] in chain.now })
    }

    func bal(_ w: PrivacyWallet, _ d: String) -> UInt64 { w.balances()[d] ?? 0 }

    func funded(_ chain: FakeChain, _ w: PrivacyWallet, _ amount: UInt64 = 1_000_000) throws {
        let o = try w.shieldOutput(denom: "uerth", amount: 0)
        chain.shield("uerth", amount, o.pc, o.ciphertext)
    }

    func sigs(_ prep: PrivacyWallet.RegistrationPrep, _ nullifier: String) -> [String] {
        ["261001", prep.binding.bigUInt.description, nullifier, Fr(UInt64(77)).bigUInt.description]
    }

    func assertThrowsAsync<T>(_ body: () async throws -> T, _ check: (Error) -> Bool = { _ in true }, line: UInt = #line) async {
        do {
            _ = try await body()
            XCTFail("expected an error", line: line)
        } catch {
            XCTAssertTrue(check(error), "unexpected error \(error)", line: line)
        }
    }

    /// A frozen view of the chain: what it served when `freeze` was called.
    final class Frozen: PrivacyIndexer, @unchecked Sendable {
        let chain: FakeChain
        var notes = -1
        var height: UInt64 = 0
        var roots: LatestRoots?
        init(_ chain: FakeChain) { self.chain = chain }
        func freeze() async throws { notes = chain.notes.count; height = chain.height - 1; roots = try await chain.rootsLatest() }
        func thaw() { notes = -1; roots = nil }
        func status() async throws -> IndexerStatus { try await chain.status() }
        func notes(fromPos: UInt64, limit: Int?) async throws -> NotesPage {
            if notes < 0 { return try await chain.notes(fromPos: fromPos, limit: limit) }
            let rows = Array(chain.notes.prefix(notes).dropFirst(Int(fromPos)))
            return NotesPage(rows: rows, nextPos: fromPos + UInt64(rows.count), complete: false, syncedHeight: height)
        }
        func nullifiers(fromHeight: UInt64, limit: Int?) async throws -> HeightPage<Fr> {
            let p = try await chain.nullifiers(fromHeight: fromHeight, limit: limit)
            if notes < 0 { return p }
            return HeightPage(blocks: p.blocks.filter { $0.height <= height }, nextHeight: height + 1, complete: false, syncedHeight: height)
        }
        func identity(fromIndex: UInt64, limit: Int?) async throws -> IdentityPage { try await chain.identity(fromIndex: fromIndex, limit: limit) }
        func identityZeroed(fromHeight: UInt64, limit: Int?) async throws -> HeightPage<UInt64> {
            try await chain.identityZeroed(fromHeight: fromHeight, limit: limit)
        }
        func rootsLatest() async throws -> LatestRoots { if let roots { return roots }; return try await chain.rootsLatest() }
        func rates(epoch: UInt64?) async throws -> [RateRow] { [] }
        func stakeNotes(fromPos: UInt64, limit: Int?) async throws -> StakeNotesPage { try await chain.stakeNotes(fromPos: fromPos, limit: limit) }
        func stakeNullifiers(fromHeight: UInt64, limit: Int?) async throws -> HeightPage<Fr> {
            try await chain.stakeNullifiers(fromHeight: fromHeight, limit: limit)
        }
        func stakeNullifierLeaves(fromIndex: UInt64, limit: Int?) async throws -> StakeNfLeavesPage {
            try await chain.stakeNullifierLeaves(fromIndex: fromIndex, limit: limit)
        }
        func stakeSnapshots(fromHeight: UInt64, limit: Int?) async throws -> StakeSnapshotsPage {
            try await chain.stakeSnapshots(fromHeight: fromHeight, limit: limit)
        }
    }

    func registered(_ chain: FakeChain, _ w: PrivacyWallet, nullifier: String = "555") async throws {
        let prep = try await w.prepareRegistration(referrer: nil)
        chain.shield("uerth", 100_000, prep.gas.pc, prep.gas.ciphertext)
        try await w.sync()
        _ = try await w.register(prep, proof: Data(count: 14_656), publicSignals: sigs(prep, nullifier), signatureAlgorithm: "lean_poa", dscDer: Data(count: 10))
        try await w.sync()
        XCTAssertEqual(.live, w.identityStatus())
    }

    // MARK: K8, K9

    /// K8: the indexer is two weeks behind and the chain pruned its root: unverified, nothing wiped.
    func testPrunedRootIsUnverifiedNotAMismatch() async throws {
        let chain = FakeChain()
        let idx = Frozen(chain)
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

    /// K9: a node answering a pinned query at another height cannot condemn the trees; equal ones still verify.
    func testOtherEchoedHeightIsUnverifiedNotAMismatch() async throws {
        let chain = FakeChain()
        let a = try wallet(chain)
        try funded(chain, a)
        chain.echoOtherHeight = true
        let r = try await a.sync()
        XCTAssertTrue(r.verified)
        let idx = Frozen(chain)
        let b = try wallet(chain, indexer: idx)
        let prep = try await b.prepareRegistration(referrer: nil)
        chain.shield("uerth", 100_000, prep.gas.pc, prep.gas.ciphertext)
        try await b.sync()
        try await idx.freeze()
        _ = try await b.register(prep, proof: Data(count: 14_656), publicSignals: sigs(prep, "1"), signatureAlgorithm: "lean_poa", dscDer: Data(count: 10))
        let r2 = try await b.sync()
        XCTAssertFalse(r2.verified)
        XCTAssertTrue(b.store.state.rootsError?.contains("could not be read at the indexer's height") == true, b.store.state.rootsError ?? "")
    }

    /// K9: a spend the chain does not hold, slipped into the nullifier stream, is caught by the sample.
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

    /// K9: an indexer trailing the chain's tip is labelled unverified.
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

    // MARK: K6

    /// K6: a relaunch the LCD confirms keeps the registration (record, passport nullifier) and re-verifies its leaf.
    func testGenesisSwitchKeepsIdentity() async throws {
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

    /// K6: an indexer's new genesis the LCD does not confirm (or cannot) wipes nothing and syncs nothing.
    func testUnverifiedGenesisSwitchKeepsEverything() async throws {
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

    // MARK: K7

    /// K7: the registration is recorded by hash the moment the node accepts it
    /// and the gas note marked spent, so a wait that times out followed by a
    /// killed app loses nothing: the next wallet finds the tx by hash.
    func testRegistrationRecordedAtAcceptance() async throws {
        let chain = FakeChain()
        let store = PrivacyStore.memory()
        let a = try wallet(chain, store: store)
        let prep = try await a.prepareRegistration(referrer: nil)
        chain.shield("uerth", 100_000, prep.gas.pc, prep.gas.ciphertext)
        try await a.sync()
        XCTAssertEqual(100_000, bal(a, "uerth"))
        chain.unconfirmedNext = 1
        await assertThrowsAsync({
            try await a.register(prep, proof: Data(count: 14_656), publicSignals: self.sigs(prep, "31337"), signatureAlgorithm: "lean_poa", dscDer: Data(count: 10))
        })
        let p = try XCTUnwrap(store.state.pendingRegistration)
        XCTAssertEqual(chain.txs.first { $0.value.events.contains { $0.type == "register" } }?.key, p.txHash)
        XCTAssertNil(p.leafIndex)
        XCTAssertEqual(0, a.store.state.notes.filter { $0.unspent && $0.pendingAt == nil }.count)
        // The app is killed; a new wallet over the same store resolves it by hash.
        let b = try wallet(chain, store: store)
        try await b.sync()
        XCTAssertNil(b.pendingRegistration)
        XCTAssertEqual(.live, b.identityStatus())
        XCTAssertEqual("31337", store.state.identity?.passportNullifier)
    }

    /// K7: accepted then failed in its block: the failure is kept for the UI, the gas note released later.
    func testRegistrationFailedInBlockIsShown() async throws {
        let chain = FakeChain()
        let a = try wallet(chain)
        let prep = try await a.prepareRegistration(referrer: nil)
        chain.shield("uerth", 100_000, prep.gas.pc, prep.gas.ciphertext)
        try await a.sync()
        chain.failInBlockNext = 1
        await assertThrowsAsync({
            try await a.register(prep, proof: Data(count: 14_656), publicSignals: self.sigs(prep, "1"), signatureAlgorithm: "lean_poa", dscDer: Data(count: 10))
        })
        try await a.sync()
        XCTAssertTrue(a.pendingRegistration?.failure?.hasPrefix(PrivacyWallet.txFailed) == true)
        XCTAssertEqual(0, bal(a, "uerth"))
        // Audit 3: released only once the chain is past the tx's timeout_height, not by the clock.
        chain.now += WalletSync.pendingTimeout + 1
        try await a.sync()
        XCTAssertEqual(0, bal(a, "uerth"))
        for _ in 0 ... PrivateTxEngine.timeoutBlocks { chain.emptyBlock() }
        try await a.sync()
        XCTAssertEqual(100_000, bal(a, "uerth"))
        _ = try await a.register(prep, proof: Data(count: 14_656), publicSignals: sigs(prep, "1"), signatureAlgorithm: "lean_poa", dscDer: Data(count: 10))
        try await a.sync()
        XCTAssertEqual(.live, a.identityStatus())
    }

    // MARK: K1

    /// K1: the record memo, version 2 with its tag (PRIVACY_FORMATS 3a golden, as Android).
    func testRecordMemoGolden() throws {
        let k = try PrivacyKeys.fromMnemonic(alice)
        let m = WalletSync.regMemo(nk: k.nk, dscKey: Fr(UInt64(77)), country: "FR", builtAt: 1_790_000_000)
        let hex = m.map { String(format: "%02x", $0) }.joined()
        XCTAssertEqual("4552024652000000006ab13b80" + String(repeating: "00", count: 31) + "4d" + "1d3756b83dfd918fa510770bc257079b" + "000000", hex)
        let back = try XCTUnwrap(WalletSync.parseRegMemo(nk: k.nk, m))
        XCTAssertEqual(Fr(UInt64(77)), back.dscKey)
        XCTAssertEqual("FR", back.country)
        XCTAssertEqual(1_790_000_000, back.builtAt)
        XCTAssertNil(WalletSync.parseRegMemo(nk: Fr(UInt64(5)), m))
        var flipped = m; flipped[50] ^= 1
        XCTAssertNil(WalletSync.parseRegMemo(nk: k.nk, flipped))
        var v1 = m; v1[2] = 1
        XCTAssertNil(WalletSync.parseRegMemo(nk: k.nk, v1))
        var junk = m; junk[63] = 1
        XCTAssertNil(WalletSync.parseRegMemo(nk: k.nk, junk))
    }

    /// Appends a note row (a v1 note someone sent) at the block being built.
    func sendNote(_ chain: FakeChain, to: ShieldedAddress, memo: Data) throws {
        let n = NotePlaintext.fresh("uerth", 0, memo: memo)
        let cm = n.cm(ownerPK: to.ownerPK)
        let pos = chain.noteTree.append(cm)
        chain.notes.append(NoteRow(position: pos, height: chain.height, cm: cm, ciphertext: try NoteCipher.encrypt(n, to: to), amount: nil))
    }

    /// K1: forged record notes (untagged version 1, or version 2 with a
    /// guessed tag) in the registration's block cost the restore nothing:
    /// not one is kept or searched, and the real record still finds the identity.
    func testForgedRecordSpamIsIgnored() async throws {
        let chain = FakeChain()
        let a = try wallet(chain)
        let prep = try await a.prepareRegistration(referrer: nil)
        chain.shield("uerth", 100_000, prep.gas.pc, prep.gas.ciphertext)
        try await a.sync()
        for i in 0 ..< 16 {
            let forged = WalletSync.regMemo(nk: Fr(UInt64(1000 + i)), dscKey: Fr(UInt64(77)), country: "DE", builtAt: UInt64(chain.now) + UInt64(i))
            try sendNote(chain, to: a.address, memo: forged)
            var v1 = forged.prefix(45); v1[v1.startIndex + 2] = 1
            try sendNote(chain, to: a.address, memo: Data(v1))
        }
        _ = try await a.register(prep, proof: Data(count: 14_656), publicSignals: sigs(prep, "9"), signatureAlgorithm: "lean_poa", dscDer: Data(count: 10))
        try await a.sync()
        let restored = try wallet(chain)
        let t0 = Date()
        try await restored.sync()
        XCTAssertEqual(1, restored.store.state.regRecords.count)
        XCTAssertEqual(.live, restored.identityStatus())
        // The chain's block time: one country pass for one leaf.
        XCTAssertLessThanOrEqual(restored.store.state.regRecords[0].work, 2 * 677)
        XCTAssertLessThan(Date().timeIntervalSince(t0), 20)
    }

    /// K1: with no block time from the node, the fallback search is bounded
    /// per sync and resumes from its persisted cursor after a kill, never
    /// redoing work, and still finds a registration whose device clock was
    /// 22 hours behind.
    func testFallbackSearchIsBoundedAndResumes() async throws {
        let chain = FakeChain()
        chain.registrationCountry = ""
        let skewed = try wallet(chain, now: { [unowned chain] in chain.now - 80_000 })
        let prep = try await skewed.prepareRegistration(referrer: nil)
        chain.shield("uerth", 100_000, prep.gas.pc, prep.gas.ciphertext)
        try await skewed.sync()
        _ = try await skewed.register(prep, proof: Data(count: 14_656), publicSignals: sigs(prep, "9"), signatureAlgorithm: "lean_poa", dscDer: Data(count: 10))
        chain.blockTimesPruned = true
        chain.identityRowTimes = false
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("earth-k1-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: root) }
        let keys = try PrivacyKeys.fromMnemonic(alice)
        let budget: UInt64 = 30_000
        var works: [UInt64] = []
        while true {
            // A fresh store each time: what a killed and restarted app reads back from disk.
            let store = try PrivacyStore.open(root: root, walletID: "w")
            _ = try await WalletSync(indexer: chain, store: store, keys: keys, chainID: chain.chainID, chain: chain,
                                     now: { [unowned chain] in chain.now }, searchBudget: budget).sync()
            let rec = store.state.regRecords[0]
            works.append(rec.work)
            if rec.status != .open { break }
            XCTAssertLessThan(works.count, 20)
            if works.count >= 20 { return }
        }
        let store = try PrivacyStore.open(root: root, walletID: "w")
        XCTAssertEqual(.matched, store.state.regRecords[0].status)
        XCTAssertGreaterThanOrEqual(works.count, 3)
        for (x, y) in zip(works, works.dropFirst()) { XCTAssertTrue((1 ... budget + 2).contains(y - x)) }
        XCTAssertLessThanOrEqual(works[0], budget + 2)
        let id = try XCTUnwrap(store.state.identity)
        XCTAssertEqual(chain.identityTree.leaf(id.leafIndex),
                       PrivacyHash.identityLeaf(idc: keys.idc, dscKey: id.dscKey, country: id.country, activatedAt: id.activatedAt, predecessorAt: id.predecessorAt))
    }

    // MARK: K11

    /// K11: positions closed before a restore are known from their unlock
    /// memos, so the restored wallet's next lock never reuses a tag the chain
    /// has already seen.
    func testRestoredWalletNeverReusesAClosedTag() async throws {
        let chain = FakeChain()
        let a = try wallet(chain)
        try funded(chain, a, 5_000_000)
        try await a.sync()
        _ = try await a.delegate(validator: v1, amount: 3_000_000)
        try await a.sync()
        for _ in 0 ..< 3 { _ = try await a.lockPosition(validator: v1, amount: 100_000, splits: [2: 100]); try await a.sync() }
        let used = Set(chain.positions.values.map(\.ownerTag))
        XCTAssertEqual(3, used.count)
        for p in try await a.positions() where p.counter >= 1 { _ = try await a.unlockPosition(p.position, counter: p.counter); try await a.sync() }
        let left = try await a.positions().map(\.counter)
        XCTAssertEqual([0], left)
        XCTAssertEqual(2, a.store.state.closedOtagMax)
        let restored = try wallet(chain)
        try await restored.sync()
        XCTAssertEqual(2, restored.store.state.closedOtagMax)
        let found = try await restored.positions().map(\.counter)
        XCTAssertEqual([0], found)
        _ = try await restored.lockPosition(validator: v1, amount: 100_000, splits: [3: 100])
        try await restored.sync()
        let fresh = try XCTUnwrap(chain.positionOrder.last.flatMap { chain.positions[$0] }).ownerTag
        XCTAssertFalse(used.contains(fresh))
        let after = try await restored.positions().map(\.counter)
        XCTAssertEqual([0, 3], after)
        XCTAssertNil(WalletSync.parseUnlockMemo(nk: a.keys.nk, WalletSync.unlockMemo(nk: Fr(UInt64(9)), counter: 1_000_000)))
    }

    // MARK: K5

    /// Two validators' derth and a position, all before proposal 12's snapshot.
    func staked(_ chain: FakeChain) async throws -> PrivacyWallet {
        let a = try wallet(chain)
        for _ in 0 ..< 4 { try funded(chain, a, 2_000_000) }
        try await a.sync()
        _ = try await a.delegate(validator: v1, amount: 1_000_000); try await a.sync()
        _ = try await a.delegate(validator: v2, amount: 1_000_000); try await a.sync()
        _ = try await a.lockPosition(validator: v1, amount: 100_000, splits: [2: 100]); try await a.sync()
        chain.openProposal(12)
        return a
    }

    /// K5, as the chain now takes it (48b631c): one vote per validator and one
    /// per position, each its own tx the user confirms; nothing is cast in
    /// the background. A validator voted once is not voted again.
    func testStakeVotesAreOneTxPerValidatorAndPosition() async throws {
        let chain = FakeChain()
        let a = try await staked(chain)
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

    // MARK: K10, K12, info

    /// K10: a status naming no chain is refused.
    func testNullChainIDIsRefused() async throws {
        let chain = FakeChain()
        let idx = WrappedIndexer(chain)
        idx.statusOverride = {
            IndexerStatus(chainID: nil, syncedHeight: chain.height - 1, syncedTime: chain.now, notes: 0, identityLeaves: 0, halted: nil,
                          genesis: chain.genesis, base: "/privacy/earth-1/\(chain.genesis)")
        }
        await assertThrowsAsync({ try await self.wallet(chain, indexer: idx).sync() }) { $0 is PrivacyError }
    }

    /// K10: the status's base is taken only as exactly /privacy/<chain_id>/<genesis>, never as a host; no path traps.
    func testHostileIndexerBasesAreRefused() async throws {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StatusProtocol.self]
        func indexer() -> HTTPPrivacyIndexer { HTTPPrivacyIndexer(host: URL(string: "https://indexer.test")!, chainID: "earth-1", configuration: config) }
        StatusProtocol.base = "/privacy/earth-1/0123456789abcdef"
        StatusProtocol.paths = []
        _ = try await indexer().notes(fromPos: 0, limit: nil)
        XCTAssertEqual(["/privacy/status", "/privacy/earth-1/0123456789abcdef/notes?from_pos=0"], StatusProtocol.paths)
        for b in ["@evil.example/privacy/earth-1/0123456789abcdef", "//evil.example/privacy/earth-1/0123456789abcdef",
                  "https://evil.example/privacy/earth-1/0123456789abcdef", "/privacy/earth-1/0123456789abcdef/../../x",
                  "/privacy/earth-1/0123456789abcdef?x=", "/privacy/earth-2/0123456789abcdef", "/privacy/earth-1/0123456789ABCDEF",
                  "privacy/earth-1/0123456789abcdef", "/privacy/earth-1/0123456789abcdef/ x"] {
            StatusProtocol.base = b
            StatusProtocol.paths = []
            await assertThrowsAsync({ try await indexer().notes(fromPos: 0, limit: nil) })
            XCTAssertEqual(["/privacy/status"], StatusProtocol.paths, b)
        }
        XCTAssertFalse(HTTPPrivacyIndexer.validBase("/privacy/null/0123456789abcdef", expected: "earth-1", chainID: nil, genesis: "0123456789abcdef"))
        XCTAssertFalse(HTTPPrivacyIndexer.validBase("/privacy/earth 1/0123456789abcdef", expected: "earth 1", chainID: "earth 1", genesis: "0123456789abcdef"))
        XCTAssertTrue(HTTPPrivacyIndexer.validBase("/privacy/earth-1/0123456789abcdef", expected: "earth-1", chainID: "earth-1", genesis: "0123456789abcdef"))
    }

    /// K12: amounts past 2^63-1 are ignored on rows, never wrapped; derth values saturate.
    func testAmountsBoundedAsAndroid() throws {
        XCTAssertNil(WalletSync.publicAmount("9223372036854775808uerth"))
        XCTAssertEqual(UInt64(Int64.max), WalletSync.publicAmount("9223372036854775807uerth")?.value)
        XCTAssertNil(WalletSync.publicAmount("-1uerth"))
        // A stake row is [position, height, cm, ciphertext] (format 2, chain dff3a9b); an older row's extra columns are ignored, never trusted.
        let page = try HTTPPrivacyIndexer.parseStakeNotes(JSON(try JSONSerialization.jsonObject(with: Data(
            #"{"format":2,"notes":[[0,1,"\#(String(repeating: "00", count: 32))","AAAA"],[1,1,"\#(String(repeating: "00", count: 32))",null,"derth/x","18446744073709551615",null]],"next_pos":2,"complete":true,"synced_height":1}"#.utf8))))
        XCTAssertEqual([3, 0], page.rows.map(\.ciphertext.count))
        XCTAssertEqual(UInt64(Int64.max), PrivacyWallet.derthValue(UInt64(Int64.max), rate: 2.5))
        XCTAssertEqual(0, PrivacyWallet.derthValue(5, rate: -1))
    }

    /// Info: a claim for day 0 is refused, never an underflow trap.
    func testClaimDayZeroIsRefused() async throws {
        let chain = FakeChain()
        let a = try wallet(chain)
        try await registered(chain, a)
        await assertThrowsAsync({ try await a.claimAnml(day: 0) }) { $0 is PrivacyError }
    }
}

/// Serves /privacy/status with `base`, and an empty notes page for anything else; records every path asked.
final class StatusProtocol: URLProtocol {
    nonisolated(unsafe) static var base = ""
    nonisolated(unsafe) static var paths: [String] = []

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let url = request.url!
        let path = url.path + (url.query.map { "?" + $0 } ?? "")
        Self.paths.append(path)
        let body: [String: Any] = url.path == "/privacy/status"
            ? ["chain_id": "earth-1", "genesis": "0123456789abcdef", "base": Self.base, "synced_height": 1]
            : ["format": 2, "fields": ["position", "height", "cm", "ciphertext", "amount", "owner_pk", "rho", "rcm"],
               "notes": [], "next_pos": 0, "complete": true, "synced_height": 1]
        let resp = HTTPURLResponse(url: url, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "application/json"])!
        client?.urlProtocol(self, didReceive: resp, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: try! JSONSerialization.data(withJSONObject: body))
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}
