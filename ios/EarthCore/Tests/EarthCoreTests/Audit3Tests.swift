import CryptoKit
import Foundation
import XCTest
@testable import EarthCore

/// The third audit's client findings (1-14), each against `FakeChain`
/// (ports Audit3Test.kt); the auditor's PoCs A-C come first, fixed.
final class Audit3Tests: XCTestCase {
    let alice = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    let bob = "legal winner thank year wave sausage worth useful legal winner thank yellow"
    let receiver = "earth1qqqsyqcyq5rqwzqfpg9scrgwpugpzysncc2uls"
    let v1 = "earthvaloper1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"
    let v2 = "earthvaloper1qyqszqgpqyqszqgpqyqszqgpqyqszqgpjnp7du"
    let yes = [WeightedVoteOption(option: WeightedVoteOption.yes, weight: "1")]

    func wallet(_ chain: FakeChain, indexer: PrivacyIndexer? = nil, store: PrivacyStore = .memory(), roots: ChainRoots? = nil,
                words: String? = nil) throws -> PrivacyWallet {
        let reads = FakeReads(chain: chain)
        return PrivacyWallet(keys: try PrivacyKeys.fromMnemonic(words ?? alice), store: store, indexer: indexer ?? chain, chain: chain,
                             reads: reads, prover: chain.prover, chainID: chain.chainID, roots: roots ?? chain,
                             now: { [unowned chain] in chain.now })
    }

    func bal(_ w: PrivacyWallet, _ d: String) -> UInt64 { w.balances()[d] ?? 0 }

    func funded(_ chain: FakeChain, _ w: PrivacyWallet, _ amount: UInt64 = 1_000_000) throws {
        let o = try w.shieldOutput(denom: "uerth", amount: 0)
        chain.shield("uerth", amount, o.pc, o.ciphertext)
    }

    func sigs(_ prep: PrivacyWallet.RegistrationPrep) -> [String] {
        ["261001", prep.binding.bigUInt.description, "555", Fr(UInt64(77)).bigUInt.description]
    }

    func registered(_ chain: FakeChain, _ w: PrivacyWallet) async throws {
        let prep = try await w.prepareRegistration(referrer: nil)
        chain.shield("uerth", 100_000, prep.gas.pc, prep.gas.ciphertext)
        try await w.sync()
        _ = try await w.register(prep, proof: Data(count: 14_656), publicSignals: sigs(prep), signatureAlgorithm: "lean_poa", dscDer: Data(count: 10))
        try await w.sync()
        XCTAssertEqual(.live, w.identityStatus())
    }

    func assertThrowsAsync<T>(_ body: () async throws -> T, _ check: (Error) -> Bool = { _ in true }, line: UInt = #line) async {
        do {
            _ = try await body()
            XCTFail("expected an error", line: line)
        } catch {
            XCTAssertTrue(check(error), "unexpected error \(error)", line: line)
        }
    }

    func tmp() throws -> URL {
        let u = FileManager.default.temporaryDirectory.appendingPathComponent("audit3-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
        return u
    }

    /// The chain's own queries, with what the test overrides.
    final class Roots: ChainRoots, @unchecked Sendable {
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

    // MARK: 1. sync generations (PoC A)

    /// PoC A: an indexer appends a forged 50 ERTH note and then fails a later
    /// stream of the same sync. The roots were marked unverified (and
    /// persisted) before the first request: nothing is labelled verified and
    /// no tx is built on the forged tree.
    func testPoC_A_FailedSyncLeavesNothingVerified() async throws {
        let chain = FakeChain()
        let idx = WrappedIndexer(chain)
        let root = try tmp()
        defer { try? FileManager.default.removeItem(at: root) }
        let w = try wallet(chain, indexer: idx, store: try PrivacyStore.open(root: root, walletID: "w"))
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
        XCTAssertFalse(try PrivacyStore.open(root: root, walletID: "w").state.rootsVerified)
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

    // MARK: 3. paging (PoC B), sync timeout

    /// PoC B: empty pages that say more follows are inconsistent at once, not asked forever.
    func testPoC_B_EmptyPagesThatSayMoreFollowsAreInconsistent() async throws {
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

    // MARK: 2. a snapshot ahead of the local tree (PoC C), untrusted numbers

    /// PoC C: a stake snapshot larger than the local stake tree is "sync first", not a trap.
    func testPoC_C_SnapshotAheadOfLocalTreeAsksForASync() async throws {
        let chain = FakeChain()
        let w = try wallet(chain)
        for _ in 0 ..< 2 { try funded(chain, w, 2_000_000) }
        try await w.sync()
        _ = try await w.delegate(validator: v1, amount: 1_000_000)
        try await w.sync()
        let m = try StakePlan.selfMint(try PrivacyKeys.fromMnemonic(bob))
        chain.mintStake("derth/\(v1)", 7, PrivacyHash.stakePC(ownerPK: Fr(UInt64(5)), rho: m.rho, rcm: m.rcm), m.ciphertext)
        chain.emptyBlock()
        chain.openProposal(1)
        let n = try XCTUnwrap(w.stakeNotes.first { $0.spendable })
        await assertThrowsAsync({ try await w.stakeVote(proposalID: 1, note: n, options: self.yes) }) { $0 is PrivacyWallet.SyncFirst }
        try await w.sync()
        _ = try await w.stakeVote(proposalID: 1, note: try XCTUnwrap(w.stakeNotes.first { $0.spendable }), options: yes)
    }

    func testChainNumbersNeverTrapOrWrap() {
        XCTAssertNil(PrivacyQueries.durationSeconds("1e30s"))
        XCTAssertNil(PrivacyQueries.durationSeconds("-5s"))
        XCTAssertNil(PrivacyQueries.durationSeconds("99999999999999999999999s"))
        XCTAssertNil(PrivacyQueries.durationSeconds("nan"))
        XCTAssertEqual(1_814_400, PrivacyQueries.durationSeconds("1814400s"))
        XCTAssertEqual(0, PrivacyQueries.durationSeconds("0.5s"))
        XCTAssertNil(PrivacyAutomation.maturesBy(0, current: UInt64.max, currentStart: 0, epochSeconds: Int64.max, unbondingSeconds: 0))
        XCTAssertNil(PrivacyAutomation.maturesBy(1, current: 3, currentStart: Int64.max, epochSeconds: 1, unbondingSeconds: Int64.max))
        let positions = [PrivacyReads.Position(id: 1, validator: v1, derth: 1, ownerTag: .zero, createdHeight: UInt64.max)]
        XCTAssertTrue(PrivacyWallet.votingPositions(positions, snapshot: .init(root: .zero, treeSize: 0, height: 10)).isEmpty)
    }

    // MARK: 4. restore takes the block time from the indexer; 13. retry

    func restoredLive(_ chain: FakeChain, indexer: PrivacyIndexer? = nil, roots: ChainRoots? = nil) async throws -> PrivacyWallet {
        let r = try wallet(chain, indexer: indexer, roots: roots)
        try await r.sync()
        XCTAssertEqual(.live, r.identityStatus())
        return r
    }

    func testRestoreTakesTheBlockTimeFromTheIndexer() async throws {
        let chain = FakeChain()
        try await registered(chain, try wallet(chain))
        for _ in 0 ..< 40 { chain.emptyBlock() }
        chain.blockTimeAsks = []
        let r = try await restoredLive(chain)
        XCTAssertTrue(chain.blockTimeAsks.isEmpty)
        XCTAssertLessThanOrEqual(r.store.state.regRecords[0].work, 2 * 677, "every country, each with predecessor_at 0 or the time itself")
    }

    func testRestoreWithoutRowTimesAsksACoverSet() async throws {
        let chain = FakeChain()
        try await registered(chain, try wallet(chain))
        let height = chain.identityRows[0].height
        for _ in 0 ..< 40 { chain.emptyBlock() }
        chain.identityRowTimes = false
        chain.blockTimeAsks = []
        let r = try await restoredLive(chain)
        XCTAssertEqual(WalletSync.coverSet, chain.blockTimeAsks.count)
        XCTAssertEqual(WalletSync.coverSet, Set(chain.blockTimeAsks).count)
        XCTAssertTrue(chain.blockTimeAsks.contains(height))
        XCTAssertEqual(Set(r.store.state.regRecords[0].cover), Set(chain.blockTimeAsks))
        try await r.sync()
        XCTAssertEqual(WalletSync.coverSet, chain.blockTimeAsks.count)
    }

    /// K13: a wrong indexer time does not give the record up; the LCD's (cover set) still finds it.
    func testWrongIndexerTimeDoesNotBlockRestore() async throws {
        let chain = FakeChain()
        try await registered(chain, try wallet(chain))
        for _ in 0 ..< 20 { chain.emptyBlock() }
        let lying = WrappedIndexer(chain)
        lying.identityMap = { p in IdentityPage(rows: p.rows.map { $0.with(time: ($0.time ?? 0) + 1) }, nextIndex: p.nextIndex, size: p.size, syncedHeight: p.syncedHeight) }
        _ = try await restoredLive(chain, indexer: lying)
        // And with the LCD pruned too, the bounded fallback still runs, a budget a sync.
        chain.blockTimesPruned = true
        let r = try wallet(chain, indexer: lying)
        var syncs = 0
        while r.identityStatus() != .live {
            try await r.sync()
            syncs += 1
            XCTAssertLessThan(syncs, 10)
            if syncs >= 10 { return }
        }
    }

    /// K13: a record given up is tried again after a store reset (it is found afresh).
    func testExhaustedRecordIsRetriedAfterAReset() async throws {
        let chain = FakeChain()
        try await registered(chain, try wallet(chain))
        let lying = WrappedIndexer(chain)
        lying.identityMap = { p in IdentityPage(rows: p.rows.map { $0.with(time: ($0.time ?? 0) + 1) }, nextIndex: p.nextIndex, size: p.size, syncedHeight: p.syncedHeight) }
        let lcd = Roots(chain)
        lcd.timeShift = 7
        let r = try wallet(chain, indexer: lying, roots: lcd)
        try await r.sync()
        XCTAssertEqual(.exhausted, r.store.state.regRecords[0].status)
        XCTAssertEqual(WalletSync.IdentityStatus.none, r.identityStatus())
        try r.store.reset(chainID: chain.chainID)
        _ = try await WalletSync(indexer: chain, store: r.store, keys: r.keys, chainID: chain.chainID, chain: chain, now: { [unowned chain] in chain.now }).sync()
        XCTAssertEqual(.live, WalletSync.identityStatus(store: r.store, keys: r.keys))
    }

    // MARK: 5. timeout_height

    func testPrivateTxsCarryATimeoutAndPendingWaitsForIt() async throws {
        let chain = FakeChain()
        let w = try wallet(chain)
        try funded(chain, w)
        try await w.sync()
        let tip = try await chain.tipHeight()
        _ = try await w.unshield(receiver: receiver, denom: "uerth", amount: 1000)
        XCTAssertEqual(tip + PrivateTxEngine.timeoutBlocks, chain.lastTimeoutHeight)
        try await w.sync()
        chain.dropNext = 1
        let t = try await chain.tipHeight() + PrivateTxEngine.timeoutBlocks
        await assertThrowsAsync({ try await w.unshield(receiver: self.receiver, denom: "uerth", amount: 1000) })
        XCTAssertEqual(t, w.notes.first { $0.unspent }?.pendingUntil)
        chain.now += 24 * 3600
        while try await chain.tipHeight() < t { chain.emptyBlock() }
        try await w.sync()
        XCTAssertNotNil(w.notes.first { $0.unspent }?.pendingAt)
        chain.emptyBlock()
        try await w.sync()
        XCTAssertNil(w.notes.first { $0.unspent }?.pendingAt)
        _ = try await w.unshield(receiver: receiver, denom: "uerth", amount: 1000)
    }

    // MARK: 6. fee cap

    func testFeeIsCappedAndReconfirmedAboveTheSheet() async throws {
        let chain = FakeChain()
        let w = try wallet(chain)
        try funded(chain, w, 50_000_000)
        try await w.sync()
        chain.price = 10
        await assertThrowsAsync({ try await w.unshield(receiver: self.receiver, denom: "uerth", amount: 1000) }) {
            guard let e = $0 as? PrivateTxEngine.FeeAboveCap else { return false }
            return e.cap <= PrivateTxEngine.maxPrivateFee
        }
        XCTAssertTrue(chain.prover.actions.isEmpty)
        chain.price = Decimal(string: "0.001")!
        let before = chain.height
        var shown: UInt64 = 0
        do {
            _ = try await PrivacyWallet.$shownFee.withValue(1) { try await w.unshield(receiver: receiver, denom: "uerth", amount: 1000) }
            XCTFail("expected FeeAboveQuote")
        } catch let e as PrivateTxEngine.FeeAboveQuote {
            shown = e.fee
        }
        XCTAssertEqual(before, chain.height)
        _ = try await PrivacyWallet.$shownFee.withValue(shown) { try await w.unshield(receiver: receiver, denom: "uerth", amount: 1000) }
        XCTAssertEqual(before + 1, chain.height)
    }

    // MARK: 14a. quotes

    func testQuoteNeverShowsTheNodeOurNullifiers() async throws {
        let chain = FakeChain()
        let w = try wallet(chain)
        try funded(chain, w)
        try funded(chain, w)
        try await w.sync()
        let q = try await w.quoteSend(to: w.address, denom: "uerth", amount: 1_500_000)
        XCTAssertGreaterThan(q.fee, 0)
        let ours = Set(w.notes.map(\.nf))
        XCTAssertFalse(chain.simulatedNullifiers.isEmpty)
        XCTAssertTrue(chain.simulatedNullifiers.allSatisfy { !ours.contains($0) })
        XCTAssertTrue(chain.prover.actions.isEmpty)
    }

    // MARK: 12. the nullifier spot-check never names ours

    func testNullifierSampleExcludesOurOwn() async throws {
        let chain = FakeChain()
        let roots = Roots(chain)
        let w = try wallet(chain, roots: roots)
        try funded(chain, w)
        try await w.sync()
        _ = try await w.unshield(receiver: receiver, denom: "uerth", amount: 1000)
        try await w.sync()
        let ours = Set(w.notes.map(\.nf))
        XCTAssertFalse(ours.isEmpty)
        XCTAssertTrue(roots.asked.allSatisfy { !ours.contains($0) })
    }

    // MARK: 7. automation spacing; 14b. logs

    func testAutomationSpacesActionsWithASyncBetween() async throws {
        final class S: @unchecked Sendable { var events: [String] = []; var due = ["unbond/v/1", "unbond/v/2"] }
        let s = S()
        let taken = try await PrivacyAutomation.runPass(
            sync: { s.events.append("sync") },
            inputs: { PrivacyAutomation.Inputs(now: 20_000 * 86_400 + 5 * 3600, maturedUnbonds: s.due) },
            act: { a in
                s.events.append(PrivacyAutomation.kind(a))
                if case let .claimUnbonding(d) = a { s.due.removeAll { $0 == d } }
            },
            pause: { ms in
                s.events.append("pause")
                XCTAssertTrue((PrivacyAutomation.actionPauseMinMs ... PrivacyAutomation.actionPauseMaxMs).contains(ms))
            })
        XCTAssertEqual(2, taken.count)
        XCTAssertEqual(["sync", PrivacyAutomation.kind(taken[0]), "pause", "sync", PrivacyAutomation.kind(taken[1])], s.events)
    }

    func testAutomationTriesEachActionOncePerPass() async throws {
        final class S: @unchecked Sendable { var pauses = 0 }
        let s = S()
        // A claim that keeps failing stays due: tried once this pass.
        let taken = try await PrivacyAutomation.runPass(
            sync: {},
            inputs: { PrivacyAutomation.Inputs(now: 20_000 * 86_400, maturedUnbonds: ["unbond/v/1", "unbond/v/2"]) },
            act: { _ in throw PrivacyError("not matured after all") },
            pause: { _ in s.pauses += 1 },
            pick: { _ in 0 })
        XCTAssertEqual(2, taken.count)
        XCTAssertEqual(taken.count - 1, s.pauses)
    }

    func testAutomationLogsNameNoDenom() {
        XCTAssertEqual("ClaimUnbonding", PrivacyAutomation.kind(.claimUnbonding(denom: "unbond/\(v1)/3")))
    }

    // MARK: 8. the stake vote run

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

    func testStakeVoteSuspendsAndResumesAndRefusesADoubleStart() async throws {
        let chain = FakeChain()
        let a = try await staked(chain)
        let waiting = Tally()
        let c = StakeVoteController(wallet: { a }, pause: { _ in _ = waiting.inc(); try await Task.sleep(nanoseconds: 60_000_000_000) })
        _ = try await c.startAndAwaitFirst(proposalID: 12, options: yes)
        await assertThrowsAsync({ try await c.startAndAwaitFirst(proposalID: 12, options: self.yes) })
        while waiting.value == 0 { try await Task.sleep(nanoseconds: 10_000_000) }
        c.suspend()
        await c.wait()
        let kept = try XCTUnwrap(a.store.state.stakeVoteRun)
        XCTAssertEqual(1, kept.done)
        let c2 = StakeVoteController(wallet: { a }, pause: { _ in })
        await c2.resume()
        await c2.wait()
        XCTAssertEqual(true, c2.progress?.finished)
        XCTAssertEqual(3, c2.progress?.done)
        XCTAssertNil(a.store.state.stakeVoteRun)
    }

    func testResumeIsPerWallet() async throws {
        let chain = FakeChain()
        let a = try await staked(chain)
        let b = try wallet(chain, words: bob)
        a.store.mutate { $0.stakeVoteRun = StakeVoteRun(proposalID: 12, options: [.init(option: 1, weight: "1")], votedPositions: [], total: 3) }
        let c = StakeVoteController(wallet: { b }, pause: { _ in })
        await c.resume()
        await c.wait()
        XCTAssertNil(c.progress)
        XCTAssertTrue(chain.stakeVotes.isEmpty)
    }

    // MARK: 9. forget; 10. saves

    func testForgettingAWalletDeletesItsPrivateData() async throws {
        let root = try tmp()
        defer { try? FileManager.default.removeItem(at: root) }
        let chain = FakeChain()
        let w = try wallet(chain, store: try PrivacyStore.open(root: root, walletID: "w1"))
        try funded(chain, w)
        try await w.sync()
        try PrivacyStore.open(root: root, walletID: "w2").save()
        XCTAssertTrue(FileManager.default.fileExists(atPath: root.appendingPathComponent("privacy/w1/state.json").path))
        try PrivacyStore.delete(root: root, walletID: "w1")
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent("privacy/w1").path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: root.appendingPathComponent("privacy/w2").path))
        try PrivacyStore.delete(root: root)
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent("privacy").path))
    }

    func testCorruptStateIsAnErrorNotAnEmptyWallet() throws {
        let root = try tmp()
        defer { try? FileManager.default.removeItem(at: root) }
        try PrivacyStore.open(root: root, walletID: "w").save()
        try Data("{\"notes\": [".utf8).write(to: root.appendingPathComponent("privacy/w/state.json"))
        XCTAssertThrowsError(try PrivacyStore.open(root: root, walletID: "w")) { XCTAssertTrue($0 is PrivacyStore.CorruptState) }
    }

    func testASaveThatFailsThrows() throws {
        let root = try tmp()
        defer { try? FileManager.default.removeItem(at: root) }
        let s = try PrivacyStore.open(root: root, walletID: "w")
        try s.save()
        // The directory goes read-only: the atomic write cannot place its temp file.
        let d = root.appendingPathComponent("privacy/w")
        try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: d.path)
        defer { try? FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: d.path) }
        XCTAssertThrowsError(try s.save()) { XCTAssertTrue($0 is PrivacyStore.SaveFailed) }
    }

    // MARK: 11. the bundled SRS (the Android asset the app references)

    func testBundledSrsIsTheTranscriptPrefix() throws {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
            .appendingPathComponent("../../../../android/app/src/main/assets/srs/bn254_g1_32769.dat").standardized
        let bytes = try Data(contentsOf: url)
        XCTAssertEqual(32_769 * 64, bytes.count)
        XCTAssertEqual("d769ac6c98f8fab858a7e9967f2b7f181d8ad9fdcdf55438c915696febf0e99c",
                       SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined())
        XCTAssertEqual(1, bytes[31]); XCTAssertEqual(2, bytes[63])
    }

    // MARK: 14c. a store from before K6 keeps its identity

    func testPreK6StoreKeepsIdentity() async throws {
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

    // MARK: chain wave 3 (06ea4d6)

    /// B/F2: an unshield to any module account is refused before anything is proven.
    func testUnshieldToAModuleAccountIsRefused() async throws {
        let chain = FakeChain()
        let w = try wallet(chain)
        try funded(chain, w)
        try await w.sync()
        let staking = try Bech32.encode(hrp: "earth", data: try Bech32.convertBits([UInt8](PrivateMsgs.moduleAddress("shieldedstaking")), from: 8, to: 5, pad: true))
        await assertThrowsAsync({ try await w.unshield(receiver: staking, denom: "uerth", amount: 1000) }) { String(describing: $0).contains("shieldedstaking") }
        XCTAssertEqual(0, chain.simulated)
        _ = try await w.unshield(receiver: receiver, denom: "uerth", amount: 1000)
    }

    /// I1: a passport proof whose current_date is not a calendar date is refused before broadcast.
    func testRegistrationNeedsACalendarDate() async throws {
        let chain = FakeChain()
        let w = try wallet(chain)
        let prep = try await w.prepareRegistration(referrer: nil)
        chain.shield("uerth", 100_000, prep.gas.pc, prep.gas.ciphertext)
        try await w.sync()
        var s = sigs(prep)
        s[0] = "250231"
        await assertThrowsAsync({ try await w.register(prep, proof: Data(count: 14_656), publicSignals: s, signatureAlgorithm: "lean_poa", dscDer: Data(count: 10)) })
        XCTAssertEqual(0, chain.simulated)
    }
}

final class Tally: @unchecked Sendable {
    private let l = NSLock()
    private var n = 0
    func inc() -> Int { l.lock(); defer { l.unlock() }; n += 1; return n }
    var value: Int { l.lock(); defer { l.unlock() }; return n }
}
