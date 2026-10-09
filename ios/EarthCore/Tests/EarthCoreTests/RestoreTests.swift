import BigInt
import Foundation
import XCTest
@testable import EarthCore

/// A wallet restored from its mnemonic alone: every chain-minted note,
/// position and closed owner tag found again; the registration from its
/// tagged record note (forgeries cost nothing, the search is bounded and
/// resumes), with its block time from the indexer or a cover set of LCD
/// reads that never names the wallet's own block; forged identity rows never
/// matched; the handle and caretaker split from their state records.
final class RestoreTests: PrivacyTestCase {
    /// Registers `a` with the prepared `prep` (passport nullifier 123456789).
    func registerPrepared(_ a: PrivacyWallet, _ prep: PrivacyWallet.RegistrationPrep) async throws -> TxResult {
        try await a.register(prep, proof: Data(count: 14_656), publicSignals: signals(prep, "123456789"), signatureAlgorithm: "lean_poa",
                             dscDer: Data(count: 10))
    }

    func restoredLive(_ chain: FakeChain, indexer: PrivacyIndexer? = nil, roots: ChainRoots? = nil) async throws -> PrivacyWallet {
        let r = try wallet(chain, indexer: indexer, roots: roots)
        try await r.sync()
        XCTAssertEqual(.live, r.identityStatus())
        return r
    }

    func forge(_ chain: FakeChain, _ t: UInt64) throws -> WrappedIndexer {
        let keys = try PrivacyKeys.fromMnemonic(alice)
        let forge: (IdentityRow) -> IdentityRow = { r in
            IdentityRow(index: r.index, height: r.height, leaf: PrivacyHash.identityLeaf(idc: keys.idc, dscKey: Fr(UInt64(77)), country: .zero, activatedAt: t, predecessorAt: 0),
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

    /// Appends a note row (a v1 note someone sent) at the block being built.
    func sendNote(_ chain: FakeChain, to: ShieldedAddress, memo: Data) throws {
        let n = NotePlaintext.fresh("uerth", 0, memo: memo)
        let cm = n.cm(ownerPK: to.ownerPK)
        let pos = chain.noteTree.append(cm)
        chain.notes.append(NoteRow(position: pos, height: chain.height, cm: cm, ciphertext: try NoteCipher.encrypt(n, to: to), amount: nil))
    }

    /// Thirty abandoned registration attempts, proofs whose broadcast
    /// failed, forty failed position locks: a wallet restored from the
    /// mnemonic alone still finds every note the chain minted (gas grant,
    /// registration ANML and reward, claim, swap output, LP shares and
    /// refunds, derth, withdrawal legs), every position, and its registration.
    func testRestoreFindsEverythingAfterManyFailedAttempts() async throws {
        let chain = FakeChain()
        chain.registrationCountry = "FR"
        let a = try wallet(chain)
        try await a.sync()
        for _ in 0 ..< 30 { _ = try await a.prepareRegistration(referrer: nil) }
        let prep = try await a.prepareRegistration(referrer: nil)
        chain.shield("uerth", 100_000, prep.gas.pc, prep.gas.ciphertext)
        try await a.sync()
        // The first broadcast of the registration fails after proving; the retry lands.
        chain.rejectNext = 1
        await assertThrowsAsync({ try await self.registerPrepared(a, prep) }) { $0 is UnsignedTx.TxRejected }
        try await a.sync()
        _ = try await registerPrepared(a, prep)
        try await a.sync()
        XCTAssertEqual(.live, a.identityStatus())
        XCTAssertNil(a.pendingRegistration)
        chain.now += 2 * 86_400
        try await a.sync()
        _ = try await a.claimAnml()
        try await a.sync()
        // Swaps: five refused by the node after proving, one through.
        chain.rejectNext = 5
        for _ in 0 ..< 5 {
            await assertThrowsAsync({ try await a.noteSwap(denomIn: "uanml", amountIn: 100_000, denomOut: "uerth", minOut: 1) }) { $0 is UnsignedTx.TxRejected }
        }
        try await a.sync()
        _ = try await a.noteSwap(denomIn: "uanml", amountIn: 100_000, denomOut: "uerth", minOut: 1)
        try await a.sync()
        _ = try await a.addLiquidityShielded(poolID: 1, token: "uanml", tokenAmount: 300_000, erthAmount: 1_000_000, minShares: "1")
        try await a.sync()
        _ = try await a.removeLiquidityShielded(poolID: 1, token: "uanml", shares: bal(a, "dexlp/1") / 3)
        try await a.sync()
        chain.matureWithdrawals()
        _ = try await a.delegate(validator: validator, amount: 1_500_000)
        try await a.sync()
        chain.minGroundworksVote = 100_000
        _ = try await a.castGroundworks(split: [2: 100])
        let mine = try await a.groundworksVotes()
        XCTAssertEqual(1, mine.count)
        _ = try await a.undelegate(validator: validator, amount: 200_000)
        try await a.sync()
        XCTAssertTrue(Set(a.balances().keys).isSuperset(of: ["uerth", "uanml", "dexlp/1", PrivacyWallet.derthDenom(validator)]))
        // The undelegation waits for its payout, which the chain mints by itself.
        XCTAssertEqual(1, a.pendingUnbonds.count)

        let restored = try wallet(chain)
        try await restored.sync()
        XCTAssertEqual(a.balances(), restored.balances())
        // Its Groundworks vote, found by its notes' tags, and the split adopted from it.
        let av = try await a.groundworksVotes()
        let rv = try await restored.groundworksVotes()
        XCTAssertEqual(1, rv.count)
        XCTAssertEqual(av.map(\.derth), rv.map(\.derth))
        XCTAssertEqual([2: 100], restored.groundworksSplit)
        // The registration, from its record note and the identity stream alone.
        XCTAssertEqual(.live, restored.identityStatus())
        let id = try XCTUnwrap(a.snapshot.identity)
        let rid = try XCTUnwrap(restored.snapshot.identity)
        XCTAssertEqual(id.leafIndex, rid.leafIndex)
        XCTAssertEqual(id.dscKey, rid.dscKey)
        XCTAssertEqual(id.country, rid.country)
        XCTAssertEqual(id.activatedAt, rid.activatedAt)
        XCTAssertEqual(PrivacyHash.countryField("FR"), rid.country)
        // A restored wallet acts as one: it claims the next day.
        chain.now += 86_400
        try await restored.sync()
        _ = try await restored.claimAnml()
        try await restored.sync()
        XCTAssertEqual(bal(a, "uanml") + 1_000_000, bal(restored, "uanml"))
        // Its next stake tx keeps voting with the adopted split.
        _ = try await restored.delegate(validator: validator, amount: 1_000_000)
        try await restored.sync()
        let after = try await restored.groundworksVotes()
        XCTAssertEqual(1, after.count)
        XCTAssertEqual([2: 100], after[0].split)
        dump(chain, "restore")
    }

    /// A wallet that stopped voting never adopts a split back off the chain;
    /// a restored one whose stake no longer votes has nothing to adopt.
    func testAStoppedSplitStaysStopped() async throws {
        let chain = FakeChain()
        chain.minGroundworksVote = 100_000
        let a = try wallet(chain)
        try funded(chain, a, 5_000_000)
        try await a.sync()
        _ = try await a.delegate(validator: validator, amount: 3_000_000)
        try await a.sync()
        _ = try await a.castGroundworks(split: [2: 100])
        _ = try await a.castGroundworks(split: [:])
        XCTAssertTrue(chain.gwVotes.isEmpty)
        _ = try await a.delegate(validator: validator, amount: 1_000_000)
        try await a.sync()
        XCTAssertTrue(chain.gwVotes.isEmpty)
        XCTAssertTrue(a.groundworksSplit.isEmpty)
        let restored = try wallet(chain)
        try await restored.sync()
        let none = try await restored.groundworksVotes()
        XCTAssertTrue(none.isEmpty)
        XCTAssertTrue(restored.groundworksSplit.isEmpty)
    }

    /// The registration record memo (version 2) round-trips with its tag, and only its own format parses.
    func testTheRegistrationRecordMemoRoundTrips() throws {
        let nk = try PrivacyKeys.fromMnemonic(alice).nk
        let m = WalletSync.regMemo(nk: nk, dscKey: Fr(UInt64(77)), country: "fr", builtAt: 1_790_000_123)
        XCTAssertEqual(64, m.count)
        XCTAssertEqual(Data([0x45, 0x52, 0x02, 0x46, 0x52]), m.prefix(5))
        // A memo arrives with its trailing zeros dropped.
        var trimmed = m
        while trimmed.last == 0 { trimmed.removeLast() }
        let back = try XCTUnwrap(WalletSync.parseRegMemo(nk: nk, trimmed))
        XCTAssertEqual(Fr(UInt64(77)), back.dscKey)
        XCTAssertEqual("FR", back.country)
        XCTAssertEqual(1_790_000_123, back.builtAt)
        XCTAssertEqual("", WalletSync.parseRegMemo(nk: nk, WalletSync.regMemo(nk: nk, dscKey: .one, country: "F1", builtAt: 0))?.country)
        XCTAssertNil(WalletSync.parseRegMemo(nk: nk, Data("hi".utf8)))
        XCTAssertNil(WalletSync.parseRegMemo(nk: nk, Data()))
    }

    /// The record memo, version 2 with its tag (PRIVACY_FORMATS 6 golden, as Android).
    func testTheRegistrationRecordMemoMatchesItsGolden() throws {
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

    /// Forged record notes (untagged version 1, or version 2 with a
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
            var validator = forged.prefix(45); validator[validator.startIndex + 2] = 1
            try sendNote(chain, to: a.address, memo: Data(validator))
        }
        _ = try await a.register(prep, proof: Data(count: 14_656), publicSignals: signals(prep, "9"), signatureAlgorithm: "lean_poa", dscDer: Data(count: 10))
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

    /// With no block time from the node, the fallback search is bounded
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
        _ = try await skewed.register(prep, proof: Data(count: 14_656), publicSignals: signals(prep, "9"), signatureAlgorithm: "lean_poa", dscDer: Data(count: 10))
        chain.blockTimesPruned = true
        chain.identityRowTimes = false
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("earth-restore-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: root) }
        let keys = try PrivacyKeys.fromMnemonic(alice)
        let budget: UInt64 = 30_000
        var works: [UInt64] = []
        while true {
            // A fresh store each time: what a killed and restarted app reads back from disk.
            let store = try PrivacyStore.open(root: root, walletID: "w", key: testDataKey)
            _ = try await WalletSync(indexer: chain, store: store, keys: keys, chainID: chain.chainID, chain: chain,
                                     now: { [unowned chain] in chain.now }, searchBudget: budget).sync()
            let rec = store.state.regRecords[0]
            works.append(rec.work)
            if rec.status != .open { break }
            XCTAssertLessThan(works.count, 20)
            if works.count >= 20 { return }
        }
        let store = try PrivacyStore.open(root: root, walletID: "w", key: testDataKey)
        XCTAssertEqual(.matched, store.state.regRecords[0].status)
        XCTAssertGreaterThanOrEqual(works.count, 3)
        for (x, y) in zip(works, works.dropFirst()) { XCTAssertTrue((1 ... budget + 2).contains(y - x)) }
        XCTAssertLessThanOrEqual(works[0], budget + 2)
        let id = try XCTUnwrap(store.state.identity)
        XCTAssertEqual(chain.identityTree.leaf(id.leafIndex),
                       PrivacyHash.identityLeaf(idc: keys.idc, dscKey: id.dscKey, country: id.country, activatedAt: id.activatedAt, predecessorAt: id.predecessorAt))
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

    func testTheCoverSetIsNeverPastTheTip() async throws {
        let chain = FakeChain()
        try await registered(chain, try wallet(chain))
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
    func testTheCoverSetPrefersIdentityRowHeights() async throws {
        let chain = FakeChain()
        for i in 0 ..< 20 {
            let o = try wallet(chain, try BIP39.generateMnemonic())
            let prep = try await o.prepareRegistration(referrer: nil)
            chain.shield("uerth", 100_000, prep.gas.pc, prep.gas.ciphertext)
            _ = try await o.sync()
            var s = signals(prep, "555"); s[2] = "\(1000 + i)"
            _ = try await o.register(prep, proof: Data(count: 14_656), publicSignals: s, signatureAlgorithm: "lean_poa", dscDer: Data(count: 10))
        }
        try await registered(chain, try wallet(chain))
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

    /// A wrong indexer time does not give the record up; the LCD's (cover set) still finds it.
    func testAWrongIndexerTimeDoesNotBlockRestore() async throws {
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

    /// A record given up is tried again after a store reset (it is found afresh).
    func testAnExhaustedRecordIsRetriedAfterAReset() async throws {
        let chain = FakeChain()
        try await registered(chain, try wallet(chain))
        let lying = WrappedIndexer(chain)
        lying.identityMap = { p in IdentityPage(rows: p.rows.map { $0.with(time: ($0.time ?? 0) + 1) }, nextIndex: p.nextIndex, size: p.size, syncedHeight: p.syncedHeight) }
        let lcd = RecordingRoots(chain)
        lcd.timeShift = 7
        let r = try wallet(chain, indexer: lying, roots: lcd)
        try await r.sync()
        XCTAssertEqual(.exhausted, r.store.state.regRecords[0].status)
        XCTAssertEqual(WalletSync.IdentityStatus.none, r.identityStatus())
        try r.store.reset(chainID: chain.chainID)
        _ = try await WalletSync(indexer: chain, store: r.store, keys: r.keys, chainID: chain.chainID, chain: chain, now: { [unowned chain] in chain.now }).sync()
        XCTAssertEqual(.live, WalletSync.identityStatus(store: r.store, keys: r.keys))
    }

    /// A row time the chain cannot have (2^64-1, past the tip, before 2025) is Inconsistent.
    func testIdentityRowTimesAreBounded() async throws {
        let chain = FakeChain()
        try await registered(chain, try wallet(chain))
        for t in [UInt64.max, UInt64(chain.now) + WalletSync.timeSlack + 1, 1_000] {
            let idx = WrappedIndexer(chain)
            idx.identityMap = { p in IdentityPage(rows: p.rows.map { $0.with(time: t) }, nextIndex: p.nextIndex, size: p.size, syncedHeight: p.syncedHeight) }
            let w = try wallet(chain, indexer: idx)
            await assertThrowsAsync({ try await w.sync() }, isInconsistent)
        }
    }

    /// A forged identity tree is unverified, so no record is matched against it: no identity, so claimOpensAt has nothing to trap on.
    func testAForgedIdentityOnAnUnverifiedTreeIsNeverMatched() async throws {
        let chain = FakeChain()
        try await registered(chain, try wallet(chain))
        for _ in 0 ..< 5 { chain.emptyBlock() }
        chain.echoOtherHeight = true
        let w = try wallet(chain, indexer: try forge(chain, UInt64(chain.now) - 10))
        let r = try await w.sync()
        XCTAssertFalse(r.verified)
        XCTAssertNil(w.store.state.identity)
        XCTAssertNil(w.claimOpensAt())
    }

    /// Pinned, a forged identity tree is a mismatch and no identity (no forged
    /// activated_at) was matched against it; an honest indexer afterwards finds the real one.
    func testAForgedIdentityNeverSurvivesAMismatch() async throws {
        let chain = FakeChain()
        try await registered(chain, try wallet(chain))
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

    func testStateRecordsRoundTripAndRefuseForgeries() throws {
        let k = try PrivacyKeys.fromMnemonic(alice), other = try PrivacyKeys.fromMnemonic(bob)
        let h = WalletSync.handleMemo(nk: k.nk, kind: WalletSync.recordHolds, handle: "alice")
        XCTAssertEqual(64, h.count)
        XCTAssertEqual(.handle(kind: WalletSync.recordHolds, handle: "alice"), WalletSync.parseStateMemo(nk: k.nk, h))
        XCTAssertEqual(.handle(kind: WalletSync.recordMovedOut, handle: ""),
                       WalletSync.parseStateMemo(nk: k.nk, WalletSync.handleMemo(nk: k.nk, kind: WalletSync.recordMovedOut)))
        // Only nk tags one: another wallet's record, or a byte changed, is nothing.
        XCTAssertNil(WalletSync.parseStateMemo(nk: other.nk, h))
        var bad = h; bad[5] = UInt8(ascii: "x")
        XCTAssertNil(WalletSync.parseStateMemo(nk: k.nk, bad))
        let split: [UInt64: UInt64] = [1: 60, 300: 40]
        let c = WalletSync.caretakerMemo(nk: k.nk, kind: WalletSync.recordHolds, expiresAt: 1_900_000_000, split: split)
        XCTAssertEqual(.caretaker(kind: WalletSync.recordHolds, expiresAt: 1_900_000_000, split: split), WalletSync.parseStateMemo(nk: k.nk, c))
        // Twenty options with large ids do not fit: held, the split not recorded.
        let big = Dictionary(uniqueKeysWithValues: (0 ..< 20).map { ((UInt64(1) << 40) + UInt64($0), UInt64(5)) })
        XCTAssertEqual(.caretaker(kind: WalletSync.recordHolds, expiresAt: 1_900_000_000, split: nil),
                       WalletSync.parseStateMemo(nk: k.nk, WalletSync.caretakerMemo(nk: k.nk, kind: WalletSync.recordHolds, expiresAt: 1_900_000_000, split: big)))
        XCTAssertEqual(.caretaker(kind: WalletSync.recordNone, expiresAt: 0, split: [:]),
                       WalletSync.parseStateMemo(nk: k.nk, WalletSync.caretakerMemo(nk: k.nk, kind: WalletSync.recordNone)))
        XCTAssertNil(WalletSync.parseStateMemo(nk: k.nk, WalletSync.regMemo(nk: k.nk, dscKey: Fr(UInt64(77)), country: "FR", builtAt: 1_790_000_000)))
        XCTAssertNil(WalletSync.parseRegMemo(nk: k.nk, h))
        // Goldens, the same on Android (byte parity).
        XCTAssertEqual("45480101616c6963650000000000000000000000000000000000000000000000000000000000000000000000000000001d745226aa7b8d3ed9c363b444ef95bb", h.map { String(format: "%02x", $0) }.joined())
        XCTAssertEqual("45430101713fb300013cac02280000000000000000000000000000000000000000000000000000000000000000000000035eda1d684c370b3c709013ca43fb93", c.map { String(format: "%02x", $0) }.joined())
    }

    func testRestoreFindsTheHandleAndCaretakerSplit() async throws {
        let chain = FakeChain()
        let a = try wallet(chain, alice)
        try await register(chain, a, passport: "111")
        _ = try await a.bindHandle("alice"); try await a.sync()
        _ = try await a.setCaretaker(split: [1: 70, 2: 30]); try await a.sync()
        let exp = await a.caretakerExpiresAt()
        let a2 = try wallet(chain, alice)
        try await a2.sync()
        XCTAssertEqual("alice", a2.snapshot.handle)
        XCTAssertEqual([1: 70, 2: 30], a2.snapshot.caretakerSplit)
        let live = await a2.caretakerLive()
        XCTAssertTrue(live)
        let e2 = await a2.caretakerExpiresAt()
        XCTAssertTrue((exp - 3_600 ... exp).contains(e2))
        _ = try await a.releaseHandle(); try await a.sync()
        _ = try await a.setCaretaker(split: [:]); try await a.sync()
        let a3 = try wallet(chain, alice)
        try await a3.sync()
        XCTAssertEqual("", a3.snapshot.handle)
        let live3 = await a3.caretakerLive()
        XCTAssertFalse(live3)
    }
}
