import Foundation
import XCTest
@testable import EarthCore

/// The client audit's findings, each driven against `FakeChain` (ports
/// AuditFixesTest.kt): C1 (every chain-minted note found again from the
/// mnemonic, however many attempts failed), C2 (a registration is never lost
/// to a lagging indexer), C3 (indexer trees checked against the chain), C4
/// (the privacy store kept out of device backups), L1 (sums of
/// chain-published amounts never trap), L4 (stake votes one at a time), L6
/// (bounded response bodies), L7 (an out-of-range position is an
/// inconsistency, not a crash), L8 (identity restored from the mnemonic
/// alone), and the indexer's URL scheme.
final class AuditFixesTests: XCTestCase {
    let alice = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    let validator = "earthvaloper1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"
    let validator2 = "earthvaloper1qyqszqgpqyqszqgpqyqszqgpqyqszqgpjnp7du"
    let receiver = "earth1qqqsyqcyq5rqwzqfpg9scrgwpugpzysncc2uls"
    let yes = [WeightedVoteOption(option: WeightedVoteOption.yes, weight: "1")]

    func wallet(_ chain: FakeChain, _ words: String? = nil, indexer: PrivacyIndexer? = nil,
                store: PrivacyStore = .memory()) throws -> PrivacyWallet {
        let reads = FakeReads(chain: chain)
        return PrivacyWallet(keys: try PrivacyKeys.fromMnemonic(words ?? alice), store: store, indexer: indexer ?? chain, chain: chain,
                             reads: reads, prover: chain.prover, chainID: chain.chainID, roots: chain,
                             now: { [unowned chain] in chain.now })
    }

    func bal(_ w: PrivacyWallet, _ d: String) -> UInt64 { w.balances()[d] ?? 0 }

    func signals(_ prep: PrivacyWallet.RegistrationPrep, nullifier: String = "123456789") -> [String] {
        ["261001", prep.binding.bigUInt.description, nullifier, Fr(UInt64(77)).bigUInt.description]
    }

    func register(_ a: PrivacyWallet, _ prep: PrivacyWallet.RegistrationPrep) async throws -> TxResult {
        try await a.register(prep, proof: Data(count: 14_656), publicSignals: signals(prep), signatureAlgorithm: "lean_poa", dscDer: Data(count: 10))
    }

    func assertThrowsAsync<T>(_ body: () async throws -> T, _ check: (Error) -> Bool = { _ in true }, line: UInt = #line) async {
        do {
            _ = try await body()
            XCTFail("expected an error", line: line)
        } catch {
            XCTAssertTrue(check(error), "unexpected error \(error)", line: line)
        }
    }

    /// C1 + L8: thirty abandoned registration attempts (the audit PoC took
    /// eleven), proofs whose broadcast failed, forty failed position locks:
    /// a wallet restored from the mnemonic alone still finds every note the
    /// chain minted (gas grant, registration ANML and reward, claim, swap
    /// output, LP shares and refunds, derth, unbond claim, withdrawal legs),
    /// every position, and its registration.
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
        await assertThrowsAsync({ try await self.register(a, prep) }) { $0 is UnsignedTx.TxRejected }
        try await a.sync()
        _ = try await register(a, prep)
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
        // Forty locks proved and refused: owner-tag counters 0..39 burnt.
        chain.rejectNext = 40
        for _ in 0 ..< 40 {
            await assertThrowsAsync({ try await a.lockPosition(validator: self.validator, amount: 100_000, splits: [2: 100]) }) { $0 is UnsignedTx.TxRejected }
        }
        try await a.sync()
        _ = try await a.lockPosition(validator: validator, amount: 100_000, splits: [2: 100])
        try await a.sync()
        let mine = try await a.positions()
        XCTAssertEqual([40], mine.map(\.counter))
        _ = try await a.undelegate(validator: validator, amount: 200_000)
        try await a.sync()
        XCTAssertTrue(Set(a.balances().keys).isSuperset(of: ["uerth", "uanml", "dexlp/1", PrivacyWallet.derthDenom(validator)]))
        XCTAssertFalse(a.unbondDenoms().isEmpty)

        let restored = try wallet(chain)
        try await restored.sync()
        XCTAssertEqual(a.balances(), restored.balances())
        let ap = try await a.positions().map { [$0.position.id, UInt64($0.counter)] }
        let rp = try await restored.positions().map { [$0.position.id, UInt64($0.counter)] }
        XCTAssertEqual(ap, rp)
        // L8: the registration, from its record note and the identity stream alone.
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
        // A next lock takes a fresh tag past every one in use.
        _ = try await restored.lockPosition(validator: validator, amount: 100_000, splits: [3: 100])
        try await restored.sync()
        let after = try await restored.positions()
        XCTAssertEqual([40, 41], after.map(\.counter))
        dump(chain, "restore")
    }

    /// The registration record memo (version 2) round-trips with its tag, and only its own format parses.
    func testRegistrationRecordMemo() throws {
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

    /// The DSC's issuer country is the record note's hint.
    func testDscCountryFromTheIssuer() {
        func tlv(_ tag: UInt8, _ c: Data) -> Data { DER.encode(tag: tag, content: c) }
        let oidC = tlv(0x06, Data([0x55, 0x04, 0x06]))
        let issuer = tlv(0x30, tlv(0x31, tlv(0x30, oidC + tlv(0x13, Data("fr".utf8)))))
        let tbs = tlv(0x30, tlv(0xa0, tlv(0x02, Data([2]))) + tlv(0x02, Data([1])) + tlv(0x30, tlv(0x06, Data([0x2a]))) + issuer)
        XCTAssertEqual("FR", PrivacyWallet.dscCountry(tlv(0x30, tbs)))
        XCTAssertEqual("", PrivacyWallet.dscCountry(Data(count: 10)))
    }

    /// C2: the indexer is down when the registration commits; the pending record survives and resolves later.
    func testRegistrationIsNeverLostToALaggingIndexer() async throws {
        let chain = FakeChain()
        let idx = WrappedIndexer(chain)
        let a = try wallet(chain, indexer: idx)
        try await a.sync()
        let prep = try await a.prepareRegistration(referrer: nil)
        chain.shield("uerth", 100_000, prep.gas.pc, prep.gas.ciphertext)
        try await a.sync()
        idx.statusOverride = { throw URLError(.notConnectedToInternet) }
        _ = try await register(a, prep)
        let p = try XCTUnwrap(a.pendingRegistration)
        XCTAssertEqual("123456789", p.passportNullifier)
        XCTAssertEqual(.none, a.identityStatus())
        await assertThrowsAsync({ try await a.sync() }) { $0 is URLError }
        XCTAssertNotNil(a.pendingRegistration)
        idx.statusOverride = nil
        try await a.sync()
        XCTAssertNil(a.pendingRegistration)
        XCTAssertEqual(.live, a.identityStatus())
        XCTAssertEqual("123456789", a.snapshot.identity?.passportNullifier)
    }

    /// C3 (K8): an indexer serving a note the chain never had (encrypted to
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

    /// C3: an identity tree the chain contradicts at the indexer's own height wipes what was synced.
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

    /// A halted indexer is not synced from; a new genesis under the same chain id wipes the local data.
    func testHaltedIndexerAndRelaunch() async throws {
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

    /// The indexer's base moved (404): the wallet reads the status again and carries on.
    func testMovedBaseIsReread() async throws {
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

    /// L7: a position past u32 (or an oversized page) is an inconsistency, never a crash.
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

    /// L1: amounts the chain (or an indexer) publishes near u64's top saturate in sums, never trap.
    func testHugeAmountsNeverTrap() async throws {
        let chain = FakeChain()
        let a = try wallet(chain)
        // Past 2^63-1 a value is not one the wallet holds (K12, as Android): ignored, never wrapped.
        let o0 = try a.shieldOutput(denom: "uerth", amount: 0)
        chain.mint("uerth", UInt64.max - 5, o0.pc, o0.ciphertext)
        for _ in 0 ..< 3 {
            let o = try a.shieldOutput(denom: "uerth", amount: 0)
            chain.mint("uerth", UInt64(Int64.max) - 5, o.pc, o.ciphertext)
        }
        chain.emptyBlock()
        try await a.sync()
        XCTAssertEqual(3, a.notes.count)
        XCTAssertEqual(UInt64.max, bal(a, "uerth"))
        XCTAssertEqual(UInt64.max, a.snapshot.unshieldableErth)
        XCTAssertEqual(UInt64.max, PrivateMsgs.saturatingAdd(UInt64.max, 1))
        // Two notes that must both be spent sum past u64: refused, not a trap.
        XCTAssertThrowsError(try BundleBuilder.plan(keys: a.keys, tree: a.store.noteTree, notes: a.store.state.notes, outputs: [],
                                                    release: ["uerth": UInt64.max - 2], maxActions: 16,
                                                    forced: Array(a.store.state.notes.prefix(2))))
        // A msg whose bundles' uerth sums past u64 has a saturated balance.
        let b = ShieldedBundle(balances: [ValueBalance(denom: "uerth", amount: UInt64.max)])
        let m = MsgRemoveLiquidityShielded(bundle: b, poolID: 1, erthPC: Data(), tokenPC: Data())
        XCTAssertEqual(UInt64.max, m.privateFee)
        let d = MsgShieldedDelegate(bundle: ShieldedBundle(balances: [ValueBalance(denom: "uerth", amount: 5)]), validator: validator, amount: 9,
                                    stake: StakeProof(proof: Data(), anchor: Data(), nullifiers: [], commitments: [], ciphertexts: [], spcMint: Data(),
                                                      ownerTag: Data()))
        XCTAssertEqual(0, d.privateFee)
    }

    /// C4: the privacy store's directory is excluded from iCloud and iTunes/Finder backups.
    func testPrivacyStoreIsExcludedFromBackup() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("earth-c4-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: root) }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let store = try PrivacyStore.open(root: root, walletID: "w1")
        store.mutate { $0.chainID = "earth-1" }
        try store.save()
        for dir in [root.appendingPathComponent("privacy"), root.appendingPathComponent("privacy/w1")] {
            var u = dir
            u.removeAllCachedResourceValues()
            let v = try u.resourceValues(forKeys: [.isExcludedFromBackupKey])
            XCTAssertEqual(true, v.isExcludedFromBackup, dir.path)
        }
        XCTAssertTrue(FileManager.default.fileExists(atPath: root.appendingPathComponent("privacy/w1/state.json").path))
        // The app's root itself is untouched (only the private data is excluded).
        var r = root
        r.removeAllCachedResourceValues()
        XCTAssertNotEqual(true, try r.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup)
    }

    /// L6: a response past the 8 MiB cap is refused while it streams in, never held whole.
    func testResponseBodiesAreBounded() async throws {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [BigBodyProtocol.self]
        let session = URLSession(configuration: config)
        BigBodyProtocol.size = EarthRest.maxBodyBytes + 1
        await assertThrowsAsync({ try await EarthRest.boundedData(session, URLRequest(url: URL(string: "https://indexer.test/privacy/status")!)) }) {
            if case EarthRest.Error.tooLarge = $0 { return true }
            return false
        }
        BigBodyProtocol.size = 1024
        let (data, status) = try await EarthRest.boundedData(session, URLRequest(url: URL(string: "https://indexer.test/privacy/status")!))
        XCTAssertEqual(1024, data.count)
        XCTAssertEqual(200, status)
    }

    /// With PRIVACY_TOML_OUT set, every witness as a nargo Prover.toml (see WalletFlowTests).
    func dump(_ chain: FakeChain, _ test: String) {
        guard let out = ProcessInfo.processInfo.environment["PRIVACY_TOML_OUT"] else { return }
        func write(_ kind: String, _ i: Int, _ toml: String) {
            let dir = URL(fileURLWithPath: out).appendingPathComponent(kind).appendingPathComponent("audit_\(test)_\(i)")
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            try? toml.write(to: dir.appendingPathComponent("Prover.toml"), atomically: true, encoding: .utf8)
        }
        for (i, w) in chain.prover.allActions.enumerated() { write("action", i, w.proverToml()) }
        for (i, w) in chain.prover.allStakes.enumerated() { write("stake", i, w.proverToml()) }
        for (i, w) in chain.prover.allVotes.enumerated() { write("vote", i, w.proverToml()) }
        for (i, w) in chain.prover.allMemberships.enumerated() { write("membership", i, w.proverToml()) }
    }
}

/// An indexer delegating to another; a test overrides what it needs.
final class WrappedIndexer: PrivacyIndexer, @unchecked Sendable {
    let inner: PrivacyIndexer
    var statuses = 0
    var statusOverride: (() throws -> IndexerStatus)?
    /// A page to serve instead (nil: the inner indexer's).
    var notesOverride: ((UInt64, Int?) throws -> NotesPage?)?
    var rootsOverride: ((LatestRoots) -> LatestRoots)?
    var identityOverride: ((UInt64, Int?) throws -> IdentityPage?)?
    var nullifiersOverride: ((HeightPage<Fr>) -> HeightPage<Fr>)?
    /// A nullifier page to serve instead, by its from height (nil: the inner indexer's).
    var nullifiersFromOverride: ((UInt64) -> HeightPage<Fr>?)?
    var stakeNullifiersFromOverride: ((UInt64) -> HeightPage<Fr>?)?
    /// Rewrites every identity page served.
    var identityMap: ((IdentityPage) -> IdentityPage)?

    init(_ inner: PrivacyIndexer) { self.inner = inner }

    func status() async throws -> IndexerStatus {
        statuses += 1
        if let o = statusOverride { return try o() }
        return try await inner.status()
    }

    func notes(fromPos: UInt64, limit: Int?) async throws -> NotesPage {
        if let o = notesOverride, let p = try o(fromPos, limit) { return p }
        return try await inner.notes(fromPos: fromPos, limit: limit)
    }

    func nullifiers(fromHeight: UInt64, limit: Int?) async throws -> HeightPage<Fr> {
        if let o = nullifiersFromOverride, let p = o(fromHeight) { return p }
        let p = try await inner.nullifiers(fromHeight: fromHeight, limit: limit)
        return nullifiersOverride?(p) ?? p
    }
    func identity(fromIndex: UInt64, limit: Int?) async throws -> IdentityPage {
        if let o = identityOverride, let p = try o(fromIndex, limit) { return p }
        let p = try await inner.identity(fromIndex: fromIndex, limit: limit)
        return identityMap?(p) ?? p
    }
    var identityZeroedOverride: ((UInt64) -> HeightPage<UInt64>?)?
    func identityZeroed(fromHeight: UInt64, limit: Int?) async throws -> HeightPage<UInt64> {
        if let o = identityZeroedOverride, let p = o(fromHeight) { return p }
        return try await inner.identityZeroed(fromHeight: fromHeight, limit: limit)
    }

    func rootsLatest() async throws -> LatestRoots {
        let r = try await inner.rootsLatest()
        return rootsOverride?(r) ?? r
    }

    func rates(epoch: UInt64?) async throws -> [RateRow] { try await inner.rates(epoch: epoch) }
    func stakeNotes(fromPos: UInt64, limit: Int?) async throws -> StakeNotesPage { try await inner.stakeNotes(fromPos: fromPos, limit: limit) }
    func stakeNullifiers(fromHeight: UInt64, limit: Int?) async throws -> HeightPage<Fr> {
        if let o = stakeNullifiersFromOverride, let p = o(fromHeight) { return p }
        return try await inner.stakeNullifiers(fromHeight: fromHeight, limit: limit)
    }
    /// Rewrites every stake nullifier tree page served.
    var stakeNfLeavesMap: ((StakeNfLeavesPage) -> StakeNfLeavesPage)?
    func stakeNullifierLeaves(fromIndex: UInt64, limit: Int?) async throws -> StakeNfLeavesPage {
        nfLeafAsks.append((fromIndex, limit))
        let p = try await inner.stakeNullifierLeaves(fromIndex: fromIndex, limit: limit)
        return stakeNfLeavesMap?(p) ?? p
    }
    /// Rewrites every snapshot page served.
    var stakeSnapshotsMap: ((StakeSnapshotsPage) -> StakeSnapshotsPage)?
    /// Every stake nullifier tree page asked: (from_index, limit).
    var nfLeafAsks: [(UInt64, Int?)] = []
    func stakeSnapshots(fromHeight: UInt64, limit: Int?) async throws -> StakeSnapshotsPage {
        let p = try await inner.stakeSnapshots(fromHeight: fromHeight, limit: limit)
        return stakeSnapshotsMap?(p) ?? p
    }
}

/// Serves a body of `size` bytes in 64 KiB chunks.
final class BigBodyProtocol: URLProtocol {
    nonisolated(unsafe) static var size = 0

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let resp = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "application/json"])!
        client?.urlProtocol(self, didReceive: resp, cacheStoragePolicy: .notAllowed)
        var left = Self.size
        let chunk = Data(repeating: 0x20, count: 64 * 1024)
        while left > 0 {
            let n = min(left, chunk.count)
            client?.urlProtocol(self, didLoad: chunk.prefix(n))
            left -= n
        }
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}
