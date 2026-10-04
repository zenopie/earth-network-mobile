import BigInt
import XCTest
@testable import EarthCore

/// Audit round 6 (mobile), ports Audit6Test.kt: indexer denoms (validated,
/// learned only from our own notes or the chain's asset list, never
/// "asset/"), the send tip bounded by the verified sync height, the switch
/// target fixed only by a confirmed move, handles adopted only by owner,
/// renew-only binds, one store per wallet, public add-liquidity's min_shares.
final class Audit6Tests: XCTestCase {
    let alice = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    let bob = "legal winner thank year wave sausage worth useful legal winner thank yellow"

    func wallet(_ chain: FakeChain, _ words: String, store: PrivacyStore = .memory()) throws -> PrivacyWallet {
        PrivacyWallet(keys: try PrivacyKeys.fromMnemonic(words), store: store, indexer: chain, chain: chain,
                      reads: FakeReads(chain: chain), prover: chain.prover, chainID: chain.chainID, roots: chain,
                      now: { [unowned chain] in chain.now })
    }

    func register(_ chain: FakeChain, _ w: PrivacyWallet, passport: String) async throws {
        let prep = try await w.prepareRegistration(referrer: nil)
        chain.shield("uerth", 100_000, prep.gas.pc, prep.gas.ciphertext)
        try await w.sync()
        _ = try await w.register(prep, proof: Data(count: 14_656),
                                 publicSignals: ["261001", prep.binding.bigUInt.description, passport, Fr(UInt64(77)).bigUInt.description],
                                 signatureAlgorithm: "lean_poa", dscDer: Data(count: 10))
        try await w.sync()
        XCTAssertEqual(.live, w.identityStatus())
    }

    /// A v2 mint of `value` `denom` to `w` (what every chain mint is), one block.
    @discardableResult
    func mintTo(_ chain: FakeChain, _ w: PrivacyWallet, _ denom: String, _ value: UInt64) throws -> Int {
        let n = NotePlaintext.fresh(denom, value)
        let pos = chain.notes.count
        chain.shield(denom, value, n.pc(ownerPK: w.keys.ownerPK), try NoteCipher.encryptBlind(n, to: w.keys.address))
        return pos
    }

    func withAmount(_ r: NoteRow, _ amount: String) -> NoteRow {
        NoteRow(position: r.position, height: r.height, cm: r.cm, ciphertext: r.ciphertext, amount: amount, ownerPK: r.ownerPK, rho: r.rho, rcm: r.rcm)
    }

    final class Recorder: PrivacyWallet.MoveRecorder, @unchecked Sendable {
        let store: PrivacyStore
        let now: () -> Int64
        let targetID: String
        init(_ store: PrivacyStore, now: @escaping () -> Int64, targetID: String = "target") { self.store = store; self.now = now; self.targetID = targetID }
        func record(_ move: PendingMove) throws { try PrivacyWallet.recordIncoming(store, move, now: now()) }
        func rollback(_ move: PendingMove) throws { try PrivacyWallet.rollbackIncoming(store, move, now: now()) }
        func refusal(_ move: PendingMove) -> String? { PrivacyWallet.targetRefusal(store.state, kind: move.kind, now: now()) }
    }

    // MARK: - M2, M3: indexer denoms

    func testDenomsFollowTheSdkRuleAndNeverTheInternalName() {
        XCTAssertTrue(Denoms.valid("uerth"))
        XCTAssertTrue(Denoms.valid("derth/earthvaloper1abc"))
        XCTAssertTrue(Denoms.valid("unbond/earthvaloper1abc/42"))
        XCTAssertFalse(Denoms.valid("asset/0a"))
        XCTAssertFalse(Denoms.valid("ab"))
        XCTAssertFalse(Denoms.valid("1abc"))
        XCTAssertFalse(Denoms.valid("a b c"))
        XCTAssertFalse(Denoms.valid(String(repeating: "a", count: 129)))
        var d = AssetDenoms()
        XCTAssertFalse(d.learn("asset/" + PrivacyHash.assetID("uerth").hex))
        // An id the chain states for a denom is learned only if it is the denom's own.
        XCTAssertFalse(d.learn("ufoo", id: PrivacyHash.assetID("ubar")))
        XCTAssertTrue(d.learn("ufoo", id: PrivacyHash.assetID("ufoo")))
        XCTAssertEqual("ufoo", d.resolve(PrivacyHash.assetID("ufoo")))
    }

    func testAnAssetSentinelOrMalformedRowAmountIsRefusedNotStoredAndNeverDuplicates() async throws {
        let chain = FakeChain()
        let w = try wallet(chain, alice)
        let good = try mintTo(chain, w, "uerth", 1_000)
        let relabeled = try mintTo(chain, w, "uerth", 1_000_000)
        let broken = try mintTo(chain, w, "uerth", 2_000)
        // A hostile indexer relabels a mint as the internal "asset/<hex>" name of uerth's own id,
        // and serves another with a non-hex sentinel.
        chain.notes[relabeled] = withAmount(chain.notes[relabeled], "1000000asset/" + PrivacyHash.assetID("uerth").hex)
        chain.notes[broken] = withAmount(chain.notes[broken], "2000asset/zz")
        try await w.sync()
        XCTAssertTrue(w.notes.allSatisfy { !$0.note.denom.hasPrefix(NotePlaintext.unresolvedPrefix) })
        XCTAssertEqual([UInt64(good)], w.notes.map(\.position))
        try await w.sync()
        XCTAssertEqual(1, w.notes.count)
        XCTAssertEqual(UInt64(chain.notes.count), w.store.state.notesNext)
    }

    func testAStoredSentinelNoteIsRenamedBack() async throws {
        let chain = FakeChain()
        let w = try wallet(chain, alice)
        try mintTo(chain, w, "uerth", 5_000)
        try await w.sync()
        // As a store the old code let an indexer relabel (audit 6, M3).
        w.store.mutate { $0.notes[0] = $0.notes[0].withDenom(NotePlaintext.unresolvedPrefix + PrivacyHash.assetID("uerth").hex) }
        try await w.sync()
        XCTAssertEqual("uerth", w.store.state.notes[0].note.denom)
        XCTAssertEqual(5_000, w.balances()["uerth"])
    }

    func testDenomsAreLearnedOnlyFromOwnNotesOrTheChainsAssetList() async throws {
        let chain = FakeChain()
        let a = try wallet(chain, alice)
        let b = try wallet(chain, bob)
        try await register(chain, a, passport: "601")
        // Rows not ours, each with a junk denom in the amount column.
        for i in 0 ..< 40 {
            let n = NotePlaintext.fresh("uerth", 7)
            let pos = chain.notes.count
            chain.shield("uerth", 7, n.pc(ownerPK: Fr(UInt64(1000 + i))), try NoteCipher.encryptBlind(n, to: b.keys.address))
            chain.notes[pos] = withAmount(chain.notes[pos], "7junk\(i)")
        }
        try mintTo(chain, a, "ufoo", 9_000)
        try await a.sync()
        XCTAssertTrue(a.store.state.denoms.allSatisfy { !$0.hasPrefix("junk") })
        XCTAssertTrue(a.store.state.denoms.contains("ufoo"))
        // b learns ufoo from a v1 note only through the chain's asset list (id checked).
        try await b.sync()
        _ = try await a.send(to: b.keys.address, denom: "ufoo", amount: 4_000)
        chain.assetList = [("ufoo", PrivacyHash.assetID("ubar"))]
        try await b.sync()
        XCTAssertEqual([NotePlaintext.unresolvedPrefix + PrivacyHash.assetID("ufoo").hex], b.notes.map(\.note.denom))
        chain.assetList = [("ufoo", PrivacyHash.assetID("ufoo"))]
        try await b.sync()
        XCTAssertEqual(["ufoo"], b.notes.map(\.note.denom))
        XCTAssertEqual(4_000, b.balances()["ufoo"])
    }

    // MARK: - M4: the send tip

    func testATipFarPastTheVerifiedHeightIsRefusedBeforeAnythingIsSent() async throws {
        let chain = FakeChain()
        let a = try wallet(chain, alice)
        let b = try wallet(chain, bob)
        try mintTo(chain, a, "uerth", 500_000)
        try await a.sync()
        let sent = chain.txs.count
        chain.sendTipAhead = 1_000_000_000_000
        do {
            _ = try await a.send(to: b.keys.address, denom: "uerth", amount: 1_000)
            XCTFail("sent on an inflated tip")
        } catch let e as PrivacyError {
            XCTAssertTrue(e.description.contains("far past"), e.description)
        }
        XCTAssertEqual(sent, chain.txs.count)
        XCTAssertTrue(a.notes.allSatisfy { $0.pendingAt == nil })
        chain.sendTipAhead = 0
        _ = try await a.send(to: b.keys.address, denom: "uerth", amount: 1_000)
        let tip = try await chain.tipHeight()
        XCTAssertTrue(PrivateTxEngine.tipSane(tip, verified: a.store.state.verifiedHeight))
    }

    func testAnOutsizedPendingTimeoutIsSettledByTheTxStatus() async throws {
        let chain = FakeChain()
        let a = try wallet(chain, alice)
        try mintTo(chain, a, "uerth", 500_000)
        try await a.sync()
        // As a mark the old code made against an inflated tip, for a tx the node never relayed.
        a.store.mutate {
            $0.notes[0].pendingAt = chain.now; $0.notes[0].pendingUntil = 1_000_000_000_000_050; $0.notes[0].pendingTx = String(repeating: "AB", count: 32)
        }
        try await a.sync()
        XCTAssertNotNil(a.store.state.notes[0].pendingAt)
        chain.now += WalletSync.pendingTimeout + 1
        try await a.sync()
        XCTAssertNil(a.store.state.notes[0].pendingAt)
        XCTAssertFalse(PrivateTxEngine.timeoutSane(1_000_000_000_000_050, verifiedNow: a.store.state.verifiedHeight))
        XCTAssertTrue(PrivateTxEngine.timeoutSane(a.store.state.verifiedHeight + PrivateTxEngine.timeoutBlocks, verifiedNow: a.store.state.verifiedHeight))
    }

    // MARK: - M5: the switch target

    func testARefusedMoveDoesNotFixTheSwitchTarget() async throws {
        let chain = FakeChain()
        let a = try wallet(chain, alice)
        try await register(chain, a, passport: "602")
        _ = try await a.bindHandle("alice"); try await a.sync()
        let bKeys = try PrivacyKeys.fromMnemonic(bob)
        let bStore = PrivacyStore.memory()
        let owner = PrivacyWallet.newOwner(bKeys, scope: PrivacyHash.handleScope())
        chain.rejectNext = 1
        _ = try? await a.moveHandle(newOwner: owner, target: bKeys, recorder: Recorder(bStore, now: { chain.now }, targetID: "b"))
        XCTAssertEqual("", a.store.state.switchTarget)
        XCTAssertEqual("", bStore.state.handle)
        // A target that already holds a handle is refused before anything is laid out.
        let cStore = PrivacyStore.memory()
        cStore.mutate { $0.handle = "taken" }
        let sent = chain.txs.count
        do {
            _ = try await a.moveHandle(newOwner: owner, target: bKeys, recorder: Recorder(cStore, now: { chain.now }, targetID: "c"))
            XCTFail("moved to a target holding a handle")
        } catch let e as PrivacyError {
            XCTAssertTrue(e.description.contains("already holds"), e.description)
        }
        XCTAssertEqual(sent, chain.txs.count)
        // A target fixed by the old code with no move behind it is freed.
        a.store.mutate { $0.switchTarget = "stale" }
        await a.resolvePendingMoves()
        XCTAssertEqual("", a.store.state.switchTarget)
        // Confirmed: fixed to that target.
        _ = try await a.moveHandle(newOwner: owner, target: bKeys, recorder: Recorder(bStore, now: { chain.now }, targetID: "b"))
        XCTAssertEqual("b", a.store.state.switchTarget)
    }

    // MARK: - M6, M7: handles

    func testAnEntryIsAdoptedOnlyWhenItsOwnerIsThisIdentity() async throws {
        let chain = FakeChain()
        let a = try wallet(chain, alice)
        let b = try wallet(chain, bob)
        try await register(chain, a, passport: "603")
        try await register(chain, b, passport: "604")
        // b binds "bait" to a's address: the directory names a's address, owner b.
        _ = try await b.bindHandle("bait", address: a.keys.address); try await b.sync()
        try await a.sync()
        let dir = try await chain.handleDirectory().chainDirectory()
        XCTAssertEqual(b.handleOwner, dir["bait"]?.owner)
        let none = await a.reconcileHandle(dir, readAt: chain.now + 1)
        XCTAssertTrue(none.isEmpty)
        XCTAssertEqual("", a.store.state.handle)
        // A directory without owners: nothing adopted, the entry shown unverified.
        chain.ownerHex = { _ in "" }
        let addressed = await a.reconcileHandle(try await chain.handleDirectory().chainDirectory(), readAt: chain.now + 1)
        XCTAssertEqual(["bait"], addressed.map(\.handle))
        XCTAssertEqual("", a.store.state.handle)
        chain.ownerHex = nil
        // a's own handle, lost by the store: adopted by owner.
        _ = try await a.bindHandle("alice"); try await a.sync()
        a.store.mutate { $0.handle = ""; $0.handleSetAt = 0 }
        let held = await a.reconcileHandle(try await chain.handleDirectory().chainDirectory(), readAt: chain.now + 1)
        XCTAssertTrue(held.isEmpty)
        XCTAssertEqual("alice", a.store.state.handle)
        // A held handle whose entry names another owner is dropped.
        a.store.mutate { $0.handle = "bait"; $0.handleSetAt = 0 }
        _ = await a.reconcileHandle(try await chain.handleDirectory().chainDirectory(), readAt: chain.now + 1)
        XCTAssertEqual("alice", a.store.state.handle)
    }

    func testARenewOnlyBindNeverChangesTheHandleHeld() async throws {
        let chain = FakeChain()
        let a = try wallet(chain, alice)
        try await register(chain, a, passport: "605")
        _ = try await a.bindHandle("mine"); try await a.sync()
        let sent = chain.txs.count
        do {
            _ = try await a.bindHandle("other", renewOnly: true)
            XCTFail("a renewal changed the handle")
        } catch let e as PrivacyError {
            XCTAssertTrue(e.description.contains("free @mine"), e.description)
        }
        XCTAssertEqual(sent, chain.txs.count)
        _ = try await a.bindHandle("mine", renewOnly: true)
        XCTAssertEqual("mine", a.store.state.handle)
    }

    func testOwnersParseOnlyAs64Hex() throws {
        let h = String(repeating: "ab", count: 32)
        XCTAssertEqual(h, Handles.owner(h.uppercased()))
        XCTAssertEqual("", Handles.owner(nil))
        XCTAssertEqual("", Handles.owner(String(repeating: "ab", count: 31)))
        XCTAssertEqual("", Handles.owner(String(repeating: "zz", count: 32)))
        let body = #"{"handles":[["alice","erthz1x","live",10,20,"\#(h)"],["bob","erthz1y","live",10,20]],"height":5,"size":2,"from_index":0,"last_page":true}"#
        let page = HTTPPrivacyIndexer.parseHandles(JSON(try JSONSerialization.jsonObject(with: Data(body.utf8))))
        XCTAssertEqual([h, ""], page.handles.map(\.owner))
    }

    // MARK: - M8: one store per wallet

    func testTheProcessHoldsOneStorePerWalletDirectory() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("a6-" + UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        let s1 = try PrivacyStore.shared(root: dir, walletID: "w")
        XCTAssertTrue(s1 === (try PrivacyStore.shared(root: dir, walletID: "w")))
        s1.mutate { $0.stakeVoteRun = StakeVoteRun(proposalID: 1, options: [], votedPositions: [42], total: 1) }
        try s1.save()
        XCTAssertEqual([42], try PrivacyStore.shared(root: dir, walletID: "w").state.stakeVoteRun?.votedPositions)
        try PrivacyStore.delete(root: dir, walletID: "w")
        XCTAssertFalse(s1 === (try PrivacyStore.shared(root: dir, walletID: "w")))
    }

    // MARK: - M9: public add-liquidity

    func testPublicAddLiquidityCarriesMinShares() {
        // min(e*S/Re, t*S/Rt) = min(1000*500/2000, 300*500/1000) = 150, less 1%: 148.
        XCTAssertEqual("148", SwapMath.minShares(erthIn: 1000, tokenIn: 300, re: 2000, rt: 1000, supply: 500, bps: 100))
        XCTAssertEqual("", SwapMath.minShares(erthIn: 1, tokenIn: 1, re: 0, rt: 0, supply: 0, bps: 100))
        let m = Msg.AddLiquidity(creator: "earth1creator", poolID: 2, amountA: Coin(denom: "uerth", amount: "1000"),
                                 amountB: Coin(denom: "uusd", amount: "300"), minShares: "148")
        // Golden shared with Android (Audit6Test): fields 1-5.
        XCTAssertEqual("0a0d65617274683163726561746f7210021a0d0a057565727468120431303030220b0a047575736412033330302a03313438",
                       m.encoded().map { String(format: "%02x", $0) }.joined())
    }
}
