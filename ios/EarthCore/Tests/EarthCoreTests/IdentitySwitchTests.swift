import BigInt
import Foundation
import XCTest
@testable import EarthCore

/// Switching identity to another wallet: the handle and caretaker vote move
/// first, each move recorded in both wallets before its broadcast and
/// settled only by the chain's word, the target fixed only by a confirmed
/// move, and the new identity able to renew what moved to it.
final class IdentitySwitchTests: PrivacyTestCase {
    func testASwitchMovesTheHandleAndCaretakerVoteFirst() async throws {
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

        // The new wallet on this phone: its handle- and caretaker-scope nullifiers.
        let bKeys = try PrivacyKeys.fromMnemonic(bob)
        _ = try await a.moveHandle(newOwner: PrivacyWallet.newOwner(bKeys, scope: PrivacyHash.handleScope()))
        try await a.sync()
        _ = try await a.moveCaretaker(newOwner: PrivacyWallet.newOwner(bKeys, scope: PrivacyHash.caretakerScope()))
        try await a.sync()
        XCTAssertTrue(a.snapshot.handleMovedOut && a.snapshot.caretakerMovedOut)
        XCTAssertEqual(PrivacyHash.scopeNullifier(idSecret: bKeys.idSecret, scope: PrivacyHash.handleScope()), chain.handles["alice"]?.nullifier)
        XCTAssertEqual(exp, chain.caretakerExpiry[PrivacyHash.scopeNullifier(idSecret: bKeys.idSecret, scope: PrivacyHash.caretakerScope())])
        // The old identity can never hold them again.
        do { _ = try await a.setCaretaker(split: [2: 100]); XCTFail("moved out") } catch {}
        do { _ = try await a.bindHandle("alice-again"); XCTFail("moved out") } catch {}

        // The switch: the same passport from the new wallet. Its leaf has a predecessor.
        let b = try wallet(chain, bob)
        try await b.adoptMoved(handle: "alice", split: split, splitExpiresAt: exp)
        try await register(chain, b, passport: "999")
        try await a.sync()
        XCTAssertEqual(.zeroed, a.identityStatus())
        let id = b.snapshot.identity!
        XCTAssertEqual(id.activatedAt, id.predecessorAt)
        // What moved is renewed and refreshed at once (no bound: it holds them).
        _ = try await b.bindHandle("alice")
        let e = try await entry(chain, "alice")
        XCTAssertEqual(b.address.encode(), e?.address)
        XCTAssertEqual(PrivacyHash.noBound, chain.prover.allMemberships.last?.maxPredecessor)
        try await b.sync()
        _ = try await b.setCaretaker(split: [1: 50, 2: 50])
        XCTAssertEqual([1: 50, 2: 50], chain.caretakerVotes[PrivacyHash.scopeNullifier(idSecret: bKeys.idSecret, scope: PrivacyHash.caretakerScope())])
        dump(chain, "switchMoves")
    }

    func testAnUnconfirmedMoveStaysPendingInBothWalletsUntilTheChainSays() async throws {
        let chain = FakeChain()
        let a = try wallet(chain, alice)
        try await register(chain, a, passport: "999")
        _ = try await a.bindHandle("alice"); try await a.sync()
        let bKeys = try PrivacyKeys.fromMnemonic(bob)
        let bStore = PrivacyStore.memory()
        chain.unconfirmedNext = 1
        do {
            _ = try await a.moveHandle(newOwner: PrivacyWallet.newOwner(bKeys, scope: PrivacyHash.handleScope()), target: bKeys,
                                       recorder: MoveRecorderStub(bStore, now: { chain.now }))
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
        let a = try wallet(chain, alice)
        try await register(chain, a, passport: "999")
        _ = try await a.bindHandle("alice"); try await a.sync()
        let bKeys = try PrivacyKeys.fromMnemonic(bob)
        let owner = PrivacyWallet.newOwner(bKeys, scope: PrivacyHash.handleScope())
        for mode in ["reject", "fail", "drop"] {
            // Fees of a tx that may have landed stay pending until its timeout: fresh funds each round.
            try await fund(chain, a, 5_000_000)
            let bStore = PrivacyStore.memory()
            switch mode { case "reject": chain.rejectNext = 1; case "fail": chain.failInBlockNext = 1; default: chain.dropNext = 1 }
            do { _ = try await a.moveHandle(newOwner: owner, target: bKeys, recorder: MoveRecorderStub(bStore, now: { chain.now })); XCTFail(mode) } catch {}
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
        _ = try await a.moveHandle(newOwner: owner, target: bKeys, recorder: MoveRecorderStub(.memory(), now: { chain.now }))
        XCTAssertTrue(a.snapshot.handleMovedOut)
    }

    func testRecordingInTheNewWalletIsRetryableAndItsSyncFindsTheMoveAnyway() async throws {
        let chain = FakeChain()
        let a = try wallet(chain, alice)
        try await register(chain, a, passport: "999")
        _ = try await a.bindHandle("alice"); try await a.sync()
        let bKeys = try PrivacyKeys.fromMnemonic(bob)
        let bStore = PrivacyStore.memory()
        let rec = MoveRecorderStub(bStore, now: { chain.now }, failFirst: true)
        _ = try await a.moveHandle(newOwner: PrivacyWallet.newOwner(bKeys, scope: PrivacyHash.handleScope()), target: bKeys, recorder: rec)
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
        let a = try wallet(chain, alice)
        try await register(chain, a, passport: "602")
        _ = try await a.bindHandle("alice"); try await a.sync()
        let bKeys = try PrivacyKeys.fromMnemonic(bob)
        let bStore = PrivacyStore.memory()
        let owner = PrivacyWallet.newOwner(bKeys, scope: PrivacyHash.handleScope())
        chain.rejectNext = 1
        _ = try? await a.moveHandle(newOwner: owner, target: bKeys, recorder: CheckingMoveRecorder(bStore, now: { chain.now }, targetID: "b"))
        XCTAssertEqual("", a.store.state.switchTarget)
        XCTAssertEqual("", bStore.state.handle)
        // A target that already holds a handle is refused before anything is laid out.
        let cStore = PrivacyStore.memory()
        cStore.mutate { $0.handle = "taken" }
        let sent = chain.txs.count
        do {
            _ = try await a.moveHandle(newOwner: owner, target: bKeys, recorder: CheckingMoveRecorder(cStore, now: { chain.now }, targetID: "c"))
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
        _ = try await a.moveHandle(newOwner: owner, target: bKeys, recorder: CheckingMoveRecorder(bStore, now: { chain.now }, targetID: "b"))
        XCTAssertEqual("b", a.store.state.switchTarget)
    }

    func testARestoredSwitchedIdentityRenewsWhatMovedToIt() async throws {
        let chain = FakeChain()
        let a = try wallet(chain, alice)
        try await register(chain, a, passport: "999")
        _ = try await a.bindHandle("alice"); try await a.sync()
        _ = try await a.setCaretaker(split: [1: 100]); try await a.sync()
        let bKeys = try PrivacyKeys.fromMnemonic(bob)
        let bStore = PrivacyStore.memory()
        let rec = MoveRecorderStub(bStore, now: { chain.now })
        _ = try await a.moveHandle(newOwner: PrivacyWallet.newOwner(bKeys, scope: PrivacyHash.handleScope()), target: bKeys, recorder: rec)
        try await a.sync()
        _ = try await a.moveCaretaker(newOwner: PrivacyWallet.newOwner(bKeys, scope: PrivacyHash.caretakerScope()), target: bKeys, recorder: rec)
        try await a.sync()
        XCTAssertTrue(a.outgoingMoves().isEmpty)
        let b = try wallet(chain, bob, store: bStore)
        try await register(chain, b, passport: "999")
        XCTAssertGreaterThan(b.snapshot.identity?.predecessorAt ?? 0, 0)

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
}
