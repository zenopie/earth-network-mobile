import Foundation
import XCTest
@testable import EarthCore

/// Identity generations (PRIVACY_FORMATS.md 2): the chain registers each idc
/// once (1130), so a re-entry after a lapse or a fresh identity proves with
/// the wallet's next identity secret, derived from the same phrase. The
/// wallet picks the lowest generation that never registered, from its own
/// records and leaves; a restore finds the live (or last) one; moves between
/// generations need only the one phrase. As IdentityGenerationsTest on Android.
final class IdentityGenerationsTests: PrivacyTestCase {
    /// `w`'s generation `g` lapses (its leaf zeroed by the chain's sweep); `w` syncs past it.
    func lapse(_ chain: FakeChain, _ w: PrivacyWallet, _ g: Int) async throws {
        chain.lapse(w.keys.idc(g))
        try funded(chain, w)
        try await w.sync()
    }

    func recorder(_ chain: FakeChain, _ w: PrivacyWallet) -> PrivacyWallet.MoveRecorder {
        TestRecorder(store: w.store, now: { [unowned chain] in chain.now })
    }

    struct TestRecorder: PrivacyWallet.MoveRecorder, @unchecked Sendable {
        let store: PrivacyStore
        let now: () -> Int64
        var targetID: String { "w" }
        func record(_ move: PendingMove) throws { try PrivacyWallet.recordIncoming(store, move, now: now()) }
        func rollback(_ move: PendingMove) throws { try PrivacyWallet.rollbackIncoming(store, move, now: now()) }
        func refusal(_ move: PendingMove) -> String? { nil }
    }

    /// Pinned, and cross-checked with an independent Python derivation (BIP-39/32 + HMAC-SHA512). Generation 0 is the identity as before.
    func testGenerationsArePinnedAndGenerationZeroIsUnchanged() throws {
        let k = try PrivacyKeys.fromMnemonic(alice)
        XCTAssertEqual("059b96926ae7a563f2ddeb6fe425a6ccdd1c267f1d06d92457f1b6dba741fcba", k.idSecret(0).hex)
        XCTAssertEqual(k.idSecret, k.idSecret(0))
        XCTAssertEqual(k.idc, k.idc(0))
        XCTAssertEqual("1578439fcb7abad1387e5fe1f7a46ad6ed3300186b8f4e0c783ec7a39fc7de87", k.idSecret(1).hex)
        XCTAssertEqual("11b2df44a02f56ef93dbe19933e65939c426c7a58ef8d021c14809b2cce04164", k.idSecret(2).hex)
        XCTAssertEqual("0aa6bcc31d78717aab7c87ba4b8d88f785842444a48cc229ef673233400bd084", k.idSecret(3).hex)
        let b = try PrivacyKeys.fromMnemonic(bob)
        XCTAssertEqual("155ada956f52baa40ef19cde4bac90f0196a56b1167aaf995d4c1b06f6faf3ea", b.idSecret(1).hex)
        XCTAssertEqual(PrivacyHash.idc(k.idSecret(2)), k.idc(2))
        XCTAssertNotEqual(k.idc(1), k.idc(2))
        // nk and ek (notes, the address) are the same for every generation.
        XCTAssertEqual("0a67906d75dbdf06237678494da622b51aab4bcefea0134f5ead80d7c9440b81", k.nk.hex)
    }

    func testRecordsCarryTheirGenerationInTheTag() throws {
        let k = try PrivacyKeys.fromMnemonic(alice)
        // Generation 0's record is byte for byte the record as before (the golden in RestoreTests).
        XCTAssertEqual(WalletSync.regMemo(nk: k.nk, dscKey: Fr(UInt64(77)), country: "FR", builtAt: 1_790_000_000),
                       WalletSync.regMemo(nk: k.nk, dscKey: Fr(UInt64(77)), country: "FR", builtAt: 1_790_000_000, generation: 0))
        let m = WalletSync.regMemo(nk: k.nk, dscKey: Fr(UInt64(77)), country: "FR", builtAt: 1_790_000_000, generation: 3)
        let back = try XCTUnwrap(WalletSync.parseRegMemo(nk: k.nk, m, maxGeneration: 5))
        XCTAssertEqual(Fr(UInt64(77)), back.dscKey)
        XCTAssertEqual(3, back.generation)
        // Only generations up to the one asked are tried.
        XCTAssertNil(WalletSync.parseRegMemo(nk: k.nk, m, maxGeneration: 2))
        XCTAssertNil(WalletSync.parseRegMemo(nk: Fr(UInt64(5)), m, maxGeneration: 5))
        let h = WalletSync.handleMemo(nk: k.nk, kind: WalletSync.recordHolds, handle: "alice", generation: 2)
        let rec = try XCTUnwrap(WalletSync.parseStateRecord(nk: k.nk, h, maxGeneration: 4))
        XCTAssertEqual(.handle(kind: WalletSync.recordHolds, handle: "alice"), rec.record)
        XCTAssertEqual(2, rec.generation)
        XCTAssertNil(WalletSync.parseStateRecord(nk: k.nk, h, maxGeneration: 1))
        XCTAssertNil(WalletSync.parseStateMemo(nk: k.nk, h))
        let c = WalletSync.caretakerMemo(nk: k.nk, kind: WalletSync.recordMovedOut, generation: 1)
        XCTAssertEqual(1, WalletSync.parseStateRecord(nk: k.nk, c, maxGeneration: 1)?.generation)
    }

    /// A registration lapses; renewing registers the next generation with no new phrase, and its handle and split move over.
    func testReEntryAfterALapseUsesTheNextGenerationAndMovesWithinTheWallet() async throws {
        let chain = FakeChain()
        let a = try wallet(chain, alice)
        XCTAssertEqual(0, a.nextGeneration())
        try await register(chain, a, passport: "999")
        XCTAssertEqual(0, a.generation)
        XCTAssertEqual(1, a.nextGeneration())
        _ = try await a.bindHandle("alice"); try await a.sync()
        _ = try await a.setCaretaker(split: [1: 100]); try await a.sync()

        try await lapse(chain, a, 0)
        XCTAssertEqual(.zeroed, a.identityStatus())
        XCTAssertTrue(a.registeredBefore())
        XCTAssertEqual(1, a.nextGeneration())

        // Renew: the same wallet, generation 1. The chain would refuse generation 0 (1130).
        try await register(chain, a, passport: "999")
        XCTAssertEqual(1, a.generation)
        XCTAssertEqual(2, a.nextGeneration())
        XCTAssertTrue(chain.usedIdcs.contains(a.keys.idc(1)))
        XCTAssertEqual(a.keys.idc(1), a.idc)
        // The re-entry appended the succession from generation 0, right after the new leaf.
        let id = a.snapshot.identity!
        let si = await a.successionIndex(idcOld: a.keys.idc(0), idcNew: a.keys.idc(1), near: id.leafIndex + 1, nearOnly: true)
        XCTAssertEqual(id.leafIndex + 1, si)
        // What generation 0 held is still its own, not the new identity's.
        XCTAssertEqual("", a.snapshot.handle)
        XCTAssertEqual("alice", a.store.state.slot(0).handle)
        let live0 = await a.caretakerLive(generation: 0)
        let live1 = await a.caretakerLive()
        XCTAssertTrue(live0)
        XCTAssertFalse(live1)

        // Moved within the wallet: one phrase holds both secrets; this wallet pays.
        _ = try await a.moveHandleWithin(from: 0, expected: "alice"); try await a.sync()
        _ = try await a.moveCaretakerWithin(from: 0); try await a.sync()
        XCTAssertEqual("alice", a.snapshot.handle)
        XCTAssertEqual([1: 100], a.snapshot.caretakerSplit)
        XCTAssertTrue(a.store.state.slot(0).handleMovedOut && a.store.state.slot(0).caretakerMovedOut)
        XCTAssertTrue(a.store.state.identities.values.allSatisfy { $0.pendingMoves.isEmpty })
        let w = chain.prover.allMoves.last!
        XCTAssertEqual(a.keys.idSecret(0), w.oldSecret)
        XCTAssertEqual(a.keys.idSecret(1), w.newSecret)
        // The new identity renews with no predecessor wait.
        _ = try await a.bindHandle("alice", renewOnly: true)
        XCTAssertEqual(PrivacyHash.noBound, chain.prover.allMemberships.last!.maxPredecessor)
        XCTAssertEqual(PrivacyHash.scopeNullifier(idSecret: a.keys.idSecret(1), scope: PrivacyHash.handleScope()).hex, a.handleOwner)

        // Restored from the phrase alone: the live generation, what it holds, what moved away.
        let r = try wallet(chain, alice)
        try await r.sync()
        XCTAssertEqual(1, r.generation)
        XCTAssertEqual(.live, r.identityStatus())
        XCTAssertEqual(2, r.nextGeneration())
        XCTAssertEqual("alice", r.snapshot.handle)
        XCTAssertTrue(r.store.state.slot(0).handleMovedOut && r.store.state.slot(0).caretakerMovedOut)
    }

    /// A fresh identity while registered: the passport switches to the wallet's next generation (no new phrase).
    func testAFreshIdentityInTheSameWalletIsASwitchToTheNextGeneration() async throws {
        let chain = FakeChain()
        let a = try wallet(chain, alice)
        try await register(chain, a, passport: "777")
        _ = try await a.bindHandle("alice"); try await a.sync()
        try await register(chain, a, passport: "777")
        XCTAssertEqual(1, a.generation)
        // The chain zeroed generation 0's leaf: a switch, and the move suggestion is the new identity's.
        XCTAssertFalse(chain.zeroed.isEmpty)
        XCTAssertTrue(a.snapshot.identity!.predecessorAt > 0)
        XCTAssertTrue(a.store.state.slot(1).moveSuggestedAt > 0)
        XCTAssertEqual(0, a.store.state.slot(0).moveSuggestedAt)
        _ = try await a.moveHandleWithin(from: 0); try await a.sync()
        XCTAssertEqual("alice", a.snapshot.handle)
        // A move goes only from an earlier generation to the one the wallet acts as.
        await assertThrowsAsync({ try await a.moveHandleWithin(from: 1) })
    }

    func fundedPrep(_ chain: FakeChain, _ w: PrivacyWallet) async throws -> PrivacyWallet.RegistrationPrep {
        let prep = try await w.prepareRegistration(referrer: nil)
        chain.shield("uerth", 100_000, prep.gas.pc, prep.gas.ciphertext)
        try await w.sync()
        return prep
    }

    func send(_ w: PrivacyWallet, _ prep: PrivacyWallet.RegistrationPrep) async -> Swift.Error? {
        do {
            _ = try await w.register(prep, proof: Data(count: 14_656), publicSignals: signals(prep, "555"), signatureAlgorithm: "lean_poa",
                                     dscDer: Data(count: 10))
            return nil
        } catch { return error }
    }

    /// The wallet's records missed a used identity: CheckTx refuses it with
    /// the chain's code (1130, personhood) and the next try uses the next generation.
    func testARefusedIdentityMovesTheWalletToItsNextGeneration() async throws {
        let chain = FakeChain()
        let a = try wallet(chain, alice)
        try await register(chain, a, passport: "555")
        try await lapse(chain, a, 0)
        let prep = try await fundedPrep(chain, a)
        XCTAssertEqual(1, prep.generation)
        // Generation 1 registers somewhere the wallet cannot see, after the simulate.
        chain.usedOnBroadcast = a.keys.idc(1)
        let e = await send(a, prep)
        XCTAssertTrue(e is PrivacyWallet.IdentityUsed, "\(String(describing: e))")
        XCTAssertTrue(PrivacyWallet.identityRefusal(UnsignedTx.TxRejected(code: 1130, log: "x", codespace: "personhood")))
        XCTAssertFalse(PrivacyWallet.identityRefusal(UnsignedTx.TxRejected(code: 1130, log: "x", codespace: "dex")))
        XCTAssertEqual(2, a.nextGeneration())
        try await register(chain, a, passport: "555")
        XCTAssertEqual(2, a.generation)
        XCTAssertEqual(.live, a.identityStatus())
        // A stale prep (its generation landed since) is refused before anything is sent.
        let sent = chain.txs.count
        let stale = await send(a, prep)
        XCTAssertTrue(stale is PrivacyWallet.IdentityUsed)
        XCTAssertEqual(sent, chain.txs.count)
    }

    /// Text alone (a simulate's message, which anyone on the node path can
    /// write) never moves the floor: the wallet syncs for a record and, with
    /// none, keeps the identity.
    func testARefusalInTextAloneDoesNotMoveTheFloor() async throws {
        let chain = FakeChain()
        let a = try wallet(chain, alice)
        try await register(chain, a, passport: "555")
        try await lapse(chain, a, 0)
        chain.usedIdcs.insert(a.keys.idc(1))
        let prep = try await fundedPrep(chain, a)
        let e = await send(a, prep)
        XCTAssertTrue(e is PrivacyWallet.IdentityRefusalUnconfirmed, "\(String(describing: e))")
        XCTAssertEqual(0, a.store.state.generationFloor)
        XCTAssertEqual(1, a.nextGeneration())
        // The gas service's message without its kind: the same.
        let moved = await a.identityRefusedUnconfirmed(generation: prep.generation)
        XCTAssertFalse(moved)
        XCTAssertEqual(1, a.nextGeneration())
    }

    /// The chain's code from an http own node is anyone's on its network:
    /// treated as text. Trust is judged by the node that answered, whatever
    /// node is in use by the time the refusal is handled.
    func testACodeFromAnHttpNodeDoesNotMoveTheFloor() async throws {
        for (answered, moves) in [("http://192.168.1.2:1317", false), ("https://node.example.com", true)] {
            let chain = FakeChain()
            let a = PrivacyWallet(keys: try PrivacyKeys.fromMnemonic(alice), store: .memory(), indexer: chain, chain: chain, reads: FakeReads(chain: chain),
                                  prover: chain.prover, chainID: chain.chainID, roots: chain, now: { [unowned chain] in chain.now },
                                  chainCodesTrusted: { $0.hasPrefix("https://") })
            try await register(chain, a, passport: "555")
            try await lapse(chain, a, 0)
            let prep = try await fundedPrep(chain, a)
            chain.usedOnBroadcast = a.keys.idc(1)
            chain.answeringLcd = answered
            let e = await send(a, prep)
            if moves {
                XCTAssertTrue(e is PrivacyWallet.IdentityUsed, "\(String(describing: e))")
                XCTAssertEqual(2, a.nextGeneration())
            } else {
                XCTAssertTrue(e is PrivacyWallet.IdentityRefusalUnconfirmed, "\(String(describing: e))")
                XCTAssertEqual(1, a.nextGeneration())
            }
        }
    }

    /// Structured refusals move the floor at most generationLookahead past the
    /// highest recorded generation, so a restore, which looks that far past
    /// each record it finds, finds the registration that follows.
    func testTheFloorStaysWithinARestoresReach() async throws {
        let chain = FakeChain()
        let a = try wallet(chain, alice)
        try await register(chain, a, passport: "555")
        try await lapse(chain, a, 0)
        // A dishonest gas service answering "idc used" (a structured kind) every time.
        var moved = 0
        while true {
            let prep = try await a.prepareRegistration(referrer: nil)
            if !(await a.identityRefused(prep)) { break }
            moved += 1
        }
        XCTAssertEqual(WalletSync.generationLookahead - 1, moved)
        XCTAssertEqual(WalletSync.generationLookahead, a.nextGeneration())
        try await register(chain, a, passport: "555")
        XCTAssertEqual(WalletSync.generationLookahead, a.generation)
        let r = try wallet(chain, alice)
        try await r.sync()
        XCTAssertEqual(WalletSync.generationLookahead, r.generation)
        XCTAssertEqual(.live, r.identityStatus())
        XCTAssertEqual(WalletSync.generationLookahead + 1, r.nextGeneration())
    }

    /// A record past the window when its note was passed is tried again once
    /// a later record widens the window: the restore rescans the notes it
    /// passed from the earliest unmatched one.
    func testARestoreRescansWhenItsWindowGrows() async throws {
        let chain = FakeChain()
        let a = try wallet(chain, alice)
        let far = WalletSync.generationLookahead + 2
        // A record of generation `far` early in the stream (another device's, out of order).
        let memo = WalletSync.regMemo(nk: a.keys.nk, dscKey: Fr(UInt64(77)), country: "", builtAt: UInt64(chain.now), generation: far)
        let n = NotePlaintext.fresh("uerth", 0, memo: memo)
        let cm = n.cm(ownerPK: a.address.ownerPK)
        let pos = chain.noteTree.append(cm)
        chain.notes.append(NoteRow(position: pos, height: chain.height, cm: cm, ciphertext: try NoteCipher.encrypt(n, to: a.address), amount: nil))
        try await register(chain, a, passport: "555")
        // Generation 0's record widened the window to 9, short of `far`: still unmatched.
        XCTAssertEqual(pos, a.store.state.unmatchedRecordFrom)
        XCTAssertEqual(1, a.nextGeneration())
        try await lapse(chain, a, 0)
        try await register(chain, a, passport: "555")
        // Generation 1's widened it to 10: the early note was tried again and found.
        XCTAssertTrue(a.store.state.regRecords.contains { $0.generation == far })
        XCTAssertEqual(far + 1, a.nextGeneration())
        XCTAssertNil(a.store.state.unmatchedRecordFrom)
        let r = try wallet(chain, alice)
        try await r.sync()
        XCTAssertTrue(r.store.state.regRecords.contains { $0.generation == far })
        XCTAssertEqual(1, r.generation)
        XCTAssertEqual(far + 1, r.nextGeneration())
    }

    /// A restore scans the records' generations: the live one, or (all lapsed) the last, and never offers a used one.
    func testARestoreFindsTheLiveGenerationAndTheNextUnused() async throws {
        let chain = FakeChain()
        let a = try wallet(chain, alice)
        try await register(chain, a, passport: "321")
        try await lapse(chain, a, 0)
        try await register(chain, a, passport: "321")
        try await lapse(chain, a, 1)
        try await register(chain, a, passport: "321")
        XCTAssertEqual(2, a.generation)

        let r = try wallet(chain, alice)
        try await r.sync()
        XCTAssertEqual(2, r.generation)
        XCTAssertEqual(.live, r.identityStatus())
        XCTAssertEqual(3, r.nextGeneration())

        // All lapsed: nothing live to match, yet every used generation is known from its record.
        try await lapse(chain, a, 2)
        let r2 = try wallet(chain, alice)
        try await r2.sync()
        XCTAssertTrue(r2.registeredBefore())
        XCTAssertEqual(3, r2.nextGeneration())
        try await register(chain, r2, passport: "321")
        XCTAssertEqual(3, r2.generation)
    }

    /// Any wallet is a switch target: the target registers its own next generation, and the old one's moves reach it.
    func testASwitchBackToAnEarlierWalletUsesItsNextGeneration() async throws {
        let chain = FakeChain()
        let a = try wallet(chain, alice)
        try await register(chain, a, passport: "999")
        _ = try await a.bindHandle("alice"); try await a.sync()
        // To B, the handle moved along.
        let b = try wallet(chain, bob)
        try await register(chain, b, passport: "999")
        try await a.sync()
        _ = try await a.moveHandle(to: PrivacyWallet.Successor(keys: b.keys, identity: b.snapshot.identity!, generation: b.generation),
                                   recorder: recorder(chain, b))
        try await b.sync()
        XCTAssertEqual("alice", b.snapshot.handle)
        // Back to A: generation 1, since generation 0 registered.
        XCTAssertEqual(.zeroed, a.identityStatus())
        XCTAssertEqual(1, a.nextGeneration())
        try await register(chain, a, passport: "999")
        XCTAssertEqual(1, a.generation)
        try await b.sync()
        XCTAssertEqual(.zeroed, b.identityStatus())
        // B's identity is A's generation 1's predecessor: the handle comes home.
        _ = try await b.moveHandle(to: PrivacyWallet.Successor(keys: a.keys, identity: a.snapshot.identity!, generation: a.generation),
                                   recorder: recorder(chain, a))
        XCTAssertEqual("alice", a.store.state.handle)
        XCTAssertEqual(a.keys.idSecret(1), chain.prover.allMoves.last!.newSecret)
        XCTAssertEqual([a.keys.idc(0), b.keys.idc(0), a.keys.idc(1)], chain.usedIdcs)
        // Generation 0's slot keeps that it moved its handle away; the new identity holds it.
        try await a.sync()
        XCTAssertTrue(a.store.state.slot(0).handleMovedOut)
        XCTAssertEqual("alice", a.snapshot.handle)
    }

    /// Slots round-trip; a state file from before generations reads as generation 0.
    func testStoreKeepsEveryGenerationsSlot() throws {
        var s = PrivacyState()
        s.withSlot(0) { $0.handle = "old"; $0.handleMovedOut = true }
        s.generation = 1; s.generationFloor = 3
        s.handle = "new"; s.caretakerSplit = [2: 100]
        let back = try JSONDecoder().decode(PrivacyState.self, from: JSONEncoder().encode(s))
        XCTAssertEqual(1, back.generation)
        XCTAssertEqual(3, back.generationFloor)
        XCTAssertEqual("new", back.handle)
        XCTAssertEqual([2: 100], back.caretakerSplit)
        XCTAssertEqual("old", back.slot(0).handle)
        XCTAssertTrue(back.slot(0).handleMovedOut)
        XCTAssertEqual(3, back.nextGeneration())
        let legacy = try JSONDecoder().decode(PrivacyState.self, from: Data(#"{"handle":"kept","handleMovedOut":false,"caretakerCastAt":5}"#.utf8))
        XCTAssertEqual(0, legacy.generation)
        XCTAssertEqual("kept", legacy.handle)
        XCTAssertEqual(5, legacy.caretakerCastAt)
        XCTAssertTrue(IdentitySlot().empty)
        // A same-chain reset keeps every slot and the generation.
        let st = PrivacyStore.memory()
        st.mutate { $0.chainID = "earth-1"; $0.withSlot(0) { $0.handle = "old" }; $0.generation = 1; $0.handle = "new" }
        try st.reset(chainID: "earth-1")
        XCTAssertEqual(1, st.state.generation)
        XCTAssertEqual("new", st.state.handle)
        XCTAssertEqual("old", st.state.slot(0).handle)
    }
}
