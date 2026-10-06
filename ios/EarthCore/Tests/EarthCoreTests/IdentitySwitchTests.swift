import BigInt
import Foundation
import XCTest
@testable import EarthCore

/// Switching identity to another wallet: once the switch lands, the old
/// identity's wallet moves its handle and caretaker vote to the new identity
/// with move proofs (both secrets on the phone), each move recorded in both
/// wallets before its broadcast and settled only by the chain's word, the
/// target fixed only by a confirmed move, and the new identity able to renew
/// what moved to it. As IdentitySwitchTest and HandlesTest on Android.
final class IdentitySwitchTests: PrivacyTestCase {
    let dave = "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong"

    /// A registers `passport` and binds "alice" (and casts a split); B (on
    /// `bStore`) then switches to the same passport. Returns A (synced past the
    /// switch), B, and B as the successor a move names.
    func switched(_ chain: FakeChain, _ passport: String, bStore: PrivacyStore = .memory(), split: Bool = false) async throws
        -> (PrivacyWallet, PrivacyWallet, PrivacyWallet.Successor) {
        let a = try wallet(chain, alice)
        try await register(chain, a, passport: passport)
        _ = try await a.bindHandle("alice"); try await a.sync()
        if split { _ = try await a.setCaretaker(split: [1: 100]); try await a.sync() }
        let b = try wallet(chain, bob, store: bStore)
        try await register(chain, b, passport: passport)
        try await a.sync()
        return (a, b, PrivacyWallet.Successor(keys: b.keys, identity: b.snapshot.identity!))
    }

    func testTheNewIdentityBringsTheHandleAndCaretakerVoteAfterTheSwitch() async throws {
        let chain = FakeChain()
        let a = try wallet(chain, alice)
        try await register(chain, a, passport: "999")
        _ = try await a.bindHandle("alice")
        try await a.sync()
        _ = try await a.setCaretaker(split: [1: 100])
        try await a.sync()
        let split = a.snapshot.caretakerSplit
        let exp = await a.caretakerExpiresAt()
        let live = await a.caretakerLive()
        XCTAssertTrue(live)
        let bKeys = try PrivacyKeys.fromMnemonic(bob)

        // The switch: the same passport from the new wallet. Its leaf has a predecessor,
        // and the chain appended the succession (A, B) right after it.
        let b = try wallet(chain, bob)
        try await register(chain, b, passport: "999")
        let id = b.snapshot.identity!
        XCTAssertEqual(id.activatedAt, id.predecessorAt)
        XCTAssertEqual(PrivacyHash.successionLeaf(idcOld: a.keys.idc, idcNew: bKeys.idc), chain.identityTree.leaf(id.leafIndex + 1))
        try await a.sync()
        XCTAssertEqual(.zeroed, a.identityStatus())
        // Both wallets' trees hold it (the identity stream carries it like any leaf).
        let fromB = await b.successionIndex(idcOld: a.keys.idc, idcNew: bKeys.idc, near: id.leafIndex + 1)
        let fromA = await a.successionIndex(idcOld: a.keys.idc, idcNew: bKeys.idc, near: 0)
        XCTAssertEqual(id.leafIndex + 1, fromB)
        XCTAssertEqual(id.leafIndex + 1, fromA)

        // A (the old identity, which pays) proves the moves with both secrets.
        let to = PrivacyWallet.Successor(keys: bKeys, identity: id)
        _ = try await a.moveHandle(to: to)
        try await a.sync()
        _ = try await a.moveCaretaker(to: to)
        try await a.sync()
        XCTAssertTrue(a.snapshot.handleMovedOut && a.snapshot.caretakerMovedOut)
        XCTAssertEqual(PrivacyHash.scopeNullifier(idSecret: bKeys.idSecret, scope: PrivacyHash.handleScope()), chain.handles["alice"]?.nullifier)
        XCTAssertEqual(exp, chain.caretakerExpiry[PrivacyHash.scopeNullifier(idSecret: bKeys.idSecret, scope: PrivacyHash.caretakerScope())])
        XCTAssertEqual(id.leafIndex + 1, chain.prover.allMoves.last?.successionIndex)
        XCTAssertEqual(id.leafIndex, chain.prover.allMoves.last?.leafIndex)
        // The old identity can never hold them again.
        do { _ = try await a.setCaretaker(split: [2: 100]); XCTFail("moved out") } catch {}
        do { _ = try await a.bindHandle("alice-again"); XCTFail("moved out") } catch {}

        // B finds both in its state records and renews and refreshes at once (no bound: it holds them).
        try await b.sync()
        XCTAssertEqual("alice", b.snapshot.handle)
        XCTAssertEqual(split, b.snapshot.caretakerSplit)
        _ = try await b.bindHandle("alice")
        let e = try await entry(chain, "alice")
        XCTAssertEqual(b.address.encode(), e?.address)
        XCTAssertEqual(PrivacyHash.noBound, chain.prover.allMemberships.last?.maxPredecessor)
        try await b.sync()
        _ = try await b.setCaretaker(split: [1: 50, 2: 50])
        XCTAssertEqual([1: 50, 2: 50], chain.caretakerVotes[PrivacyHash.scopeNullifier(idSecret: bKeys.idSecret, scope: PrivacyHash.caretakerScope())])
        dump(chain, "switchMoves")
    }

    func testAMoveGoesOnlyToTheLiveDirectSuccessor() async throws {
        let chain = FakeChain()
        let a = try wallet(chain, alice)
        try await register(chain, a, passport: "998")
        _ = try await a.bindHandle("alice"); try await a.sync()
        // Another passport's identity: no succession links A to it.
        let c = try wallet(chain, carol)
        try await register(chain, c, passport: "997")
        try await a.sync()
        let before = chain.txs.count
        do {
            _ = try await a.moveHandle(to: .init(keys: c.keys, identity: c.snapshot.identity!))
            XCTFail("moved to another passport's identity")
        } catch let e as PrivacyWallet.MoveNotPossible {
            XCTAssertTrue(e.message.contains("did not directly succeed"), e.message)
        }
        XCTAssertEqual(before, chain.txs.count)
        // A -> B -> D: B switched on, so B is no longer live and D is not A's direct successor.
        let b = try wallet(chain, bob)
        try await register(chain, b, passport: "998")
        let bID = b.snapshot.identity!
        chain.now += 86_400
        let d = try wallet(chain, dave)
        try await register(chain, d, passport: "998")
        try await a.sync()
        let sent = chain.txs.count
        do {
            _ = try await a.moveHandle(to: .init(keys: b.keys, identity: bID))
            XCTFail("moved to a successor that switched on")
        } catch let e as PrivacyWallet.MoveNotPossible {
            XCTAssertTrue(e.message.contains("no longer the passport's live one"), e.message)
        }
        do {
            _ = try await a.moveHandle(to: .init(keys: d.keys, identity: d.snapshot.identity!))
            XCTFail("skipped a successor")
        } catch let e as PrivacyWallet.MoveNotPossible {
            XCTAssertTrue(e.message.contains("did not directly succeed"), e.message)
        }
        XCTAssertEqual(sent, chain.txs.count)
        XCTAssertEqual("alice", a.snapshot.handle)
    }

    func testAnUnconfirmedMoveStaysPendingInBothWalletsUntilTheChainSays() async throws {
        let chain = FakeChain()
        let bStore = PrivacyStore.memory()
        let (a, _, to) = try await switched(chain, "999", bStore: bStore)
        chain.unconfirmedNext = 1
        do {
            _ = try await a.moveHandle(to: to, recorder: MoveRecorderStub(bStore, now: { chain.now }))
            XCTFail("unconfirmed")
        } catch {}
        XCTAssertEqual("alice", a.snapshot.handle)
        XCTAssertFalse(a.snapshot.handleMovedOut)
        XCTAssertEqual(1, a.outgoingMoves().count)
        XCTAssertTrue(!a.outgoingMoves()[0].confirmed && a.outgoingMoves()[0].recorded)
        XCTAssertEqual("alice", bStore.state.handle)
        XCTAssertEqual(true, bStore.state.pendingMoves.first?.incoming)
        do { _ = try await a.bindHandle("alice"); XCTFail("in doubt") } catch {}
        let still = await a.resolvePendingMoves()
        XCTAssertFalse(still)
        XCTAssertEqual("", a.snapshot.handle)
        XCTAssertTrue(a.snapshot.handleMovedOut)
        XCTAssertTrue(a.outgoingMoves().isEmpty)
        let b = try wallet(chain, bob, store: bStore)
        let bStill = await b.resolvePendingMoves()
        XCTAssertFalse(bStill)
        XCTAssertEqual("alice", bStore.state.handle)
        XCTAssertTrue(bStore.state.pendingMoves.isEmpty)
    }

    func testAMoveThatDidNotHappenIsUndoneInBothWallets() async throws {
        let chain = FakeChain()
        let (a, _, to) = try await switched(chain, "999")
        for mode in ["reject", "fail", "drop"] {
            // Fees of a tx that may have landed stay pending until its timeout: fresh funds each round.
            try await fund(chain, a, 5_000_000)
            let bStore = PrivacyStore.memory()
            switch mode { case "reject": chain.rejectNext = 1; case "fail": chain.failInBlockNext = 1; default: chain.dropNext = 1 }
            do { _ = try await a.moveHandle(to: to, recorder: MoveRecorderStub(bStore, now: { chain.now })); XCTFail(mode) } catch {}
            let b = try wallet(chain, bob, store: bStore)
            if mode == "drop" { chain.tipAhead = 100 }
            _ = await a.resolvePendingMoves(); _ = await b.resolvePendingMoves()
            chain.tipAhead = 0
            XCTAssertEqual("alice", a.snapshot.handle, mode)
            XCTAssertFalse(a.snapshot.handleMovedOut, mode)
            XCTAssertTrue(a.outgoingMoves().isEmpty, mode)
            XCTAssertEqual("", bStore.state.handle, mode)
            XCTAssertTrue(bStore.state.pendingMoves.isEmpty, mode)
            try await a.sync()
        }
        _ = try await a.moveHandle(to: to, recorder: MoveRecorderStub(.memory(), now: { chain.now }))
        XCTAssertTrue(a.snapshot.handleMovedOut)
    }

    func testRecordingInTheNewWalletIsRetryableAndItsSyncFindsTheMoveAnyway() async throws {
        let chain = FakeChain()
        let bStore = PrivacyStore.memory()
        let (a, _, to) = try await switched(chain, "999", bStore: bStore)
        let rec = MoveRecorderStub(bStore, now: { chain.now }, failFirst: true)
        _ = try await a.moveHandle(to: to, recorder: rec)
        XCTAssertTrue(a.snapshot.handleMovedOut)
        let p = a.outgoingMoves()[0]
        XCTAssertTrue(p.confirmed && !p.recorded)
        XCTAssertEqual("", bStore.state.handle)
        var inc = p; inc.incoming = true; inc.target = ""; inc.recorded = true
        try rec.record(inc); await a.markRecorded(p.txHash)
        XCTAssertTrue(a.outgoingMoves().isEmpty)
        XCTAssertEqual("alice", bStore.state.handle)
        let b2 = try wallet(chain, bob)
        try await b2.sync()
        XCTAssertEqual("alice", b2.snapshot.handle)
    }

    func testARefusedMoveDoesNotFixTheSwitchTarget() async throws {
        let chain = FakeChain()
        let bStore = PrivacyStore.memory()
        let (a, _, to) = try await switched(chain, "602", bStore: bStore)
        chain.rejectNext = 1
        _ = try? await a.moveHandle(to: to, recorder: CheckingMoveRecorder(bStore, now: { chain.now }, targetID: "b"))
        XCTAssertEqual("", a.store.state.switchTarget)
        XCTAssertEqual("", bStore.state.handle)
        // A target that already holds a handle is refused before anything is laid out.
        let cStore = PrivacyStore.memory()
        cStore.mutate { $0.handle = "taken" }
        let sent = chain.txs.count
        do {
            _ = try await a.moveHandle(to: to, recorder: CheckingMoveRecorder(cStore, now: { chain.now }, targetID: "c"))
            XCTFail("moved to a target holding a handle")
        } catch let e as PrivacyError {
            XCTAssertTrue(e.description.contains("already holds"), e.description)
        }
        XCTAssertEqual(sent, chain.txs.count)
        // A target fixed with no move behind it (by an earlier version) is freed.
        a.store.mutate { $0.switchTarget = "stale" }
        await a.resolvePendingMoves()
        XCTAssertEqual("", a.store.state.switchTarget)
        // Confirmed: fixed to that target.
        _ = try await a.moveHandle(to: to, recorder: CheckingMoveRecorder(bStore, now: { chain.now }, targetID: "b"))
        XCTAssertEqual("b", a.store.state.switchTarget)
    }

    func testARestoredSwitchedIdentityRenewsWhatMovedToIt() async throws {
        let chain = FakeChain()
        let bStore = PrivacyStore.memory()
        let (a, b, to) = try await switched(chain, "999", bStore: bStore, split: true)
        XCTAssertGreaterThan(b.snapshot.identity?.predecessorAt ?? 0, 0)
        let rec = MoveRecorderStub(bStore, now: { chain.now })
        _ = try await a.moveHandle(to: to, recorder: rec)
        try await a.sync()
        _ = try await a.moveCaretaker(to: to, recorder: rec)
        try await a.sync()
        XCTAssertTrue(a.outgoingMoves().isEmpty)
        // Recorded in B's store at once.
        XCTAssertEqual("alice", bStore.state.handle)
        XCTAssertEqual([1: 100], bStore.state.caretakerSplit)

        let b2 = try wallet(chain, bob)
        try await b2.sync()
        XCTAssertEqual("alice", b2.snapshot.handle)
        XCTAssertEqual([1: 100], b2.snapshot.caretakerSplit)
        _ = try await b2.bindHandle("alice")
        XCTAssertEqual(PrivacyHash.noBound, chain.prover.allMemberships.last!.maxPredecessor)
        let entry = try await chain.handleDirectory().chainDirectory()["alice"]
        XCTAssertEqual(b2.address.encode(), entry?.address)
        try await b2.sync()
        _ = try await b2.setCaretaker(split: [2: 100])
        XCTAssertEqual(PrivacyHash.noBound, chain.prover.allMemberships.last!.maxPredecessor)

        let a2 = try wallet(chain, alice)
        try await a2.sync()
        XCTAssertTrue(a2.snapshot.handleMovedOut && a2.snapshot.caretakerMovedOut)
        XCTAssertEqual("", a2.snapshot.handle)
    }

    /// An identity registers once (personhood 1130): the switched-away wallet
    /// registers again only with its next generation, restored or not, and a
    /// wallet that never registered starts at generation 0.
    func testASwitchBackToAnEarlierWalletProvesWithItsNextIdentity() async throws {
        let chain = FakeChain()
        let (a, b, _) = try await switched(chain, "999")
        XCTAssertTrue(a.registeredBefore())
        XCTAssertTrue(b.registeredBefore())
        let prep = try await a.prepareRegistration(referrer: nil)
        XCTAssertEqual(1, prep.generation)
        // Restored from the phrase: its registration record says generation 0 is spent.
        let restored = try wallet(chain, alice)
        try await restored.sync()
        XCTAssertTrue(restored.registeredBefore())
        XCTAssertEqual(1, restored.nextGeneration())
        let rprep = try await restored.prepareRegistration(referrer: nil)
        XCTAssertEqual(a.keys.idc(1), rprep.idc)
        // A wallet that never registered starts at its first identity.
        let c = try wallet(chain, carol)
        XCTAssertFalse(c.registeredBefore())
        XCTAssertEqual(0, c.nextGeneration())
        XCTAssertTrue(chain.usedIdcs.contains(a.keys.idc) && chain.usedIdcs.contains(b.keys.idc))
    }

    /// After a switch the wallet suggests a random time to move, drawn once; it only reminds, never moves.
    func testASwitchSuggestsARandomDelayBeforeMoving() async throws {
        let chain = FakeChain()
        let (a, b, _) = try await switched(chain, "999")
        let act = Int64(b.snapshot.identity!.activatedAt)
        let at = await b.suggestedMoveAt()
        XCTAssertTrue((act + PrivacyWallet.moveDelayMinSeconds ... act + PrivacyWallet.moveDelayMaxSeconds).contains(at), "\(at)")
        let again = await b.suggestedMoveAt()
        XCTAssertEqual(at, again)
        XCTAssertEqual(0, b.moveSuggestionDue())
        XCTAssertEqual(0, a.moveSuggestionDue())
        chain.now = at
        XCTAssertEqual(at, b.moveSuggestionDue())
        let due = Reminders.due(Reminders.Inputs(now: at, identityLive: true, claimOpensAt: nil, claimedToday: false, caretakerExpiresAt: 0,
                                                 handle: "", handleEntry: nil, moveSuggestedAt: b.moveSuggestionDue()))
        XCTAssertTrue(due.contains(.moveSuggested(at: at)))
        // Nothing moved on its own: A still holds its handle.
        XCTAssertEqual("alice", a.snapshot.handle)
        await b.clearMoveSuggestion()
        XCTAssertEqual(0, b.moveSuggestionDue())
    }
}
