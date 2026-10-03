import BigInt
import XCTest
@testable import EarthCore

/// Audit round 5 (mobile), ports Audit5Test.kt: restore of a held handle and
/// caretaker split (state records, the directory scan, renewals the chain
/// checks at no cost), moves that stay pending until the chain says
/// (recorded in the new wallet before the broadcast), hostile handle and
/// caretaker times (Int64.min / Int64.max: these trapped), referral links.
final class Audit5Tests: XCTestCase {
    let alice = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    let bob = "legal winner thank year wave sausage worth useful legal winner thank yellow"
    let carol = "letter advice cage absurd amount doctor acoustic avoid letter advice cage above"

    func wallet(_ chain: FakeChain, _ words: String, store: PrivacyStore = .memory()) throws -> PrivacyWallet {
        PrivacyWallet(keys: try PrivacyKeys.fromMnemonic(words), store: store, indexer: chain, chain: chain,
                      reads: FakeReads(chain: chain), prover: chain.prover, chainID: chain.chainID, roots: chain,
                      now: { [unowned chain] in chain.now })
    }

    func bal(_ w: PrivacyWallet, _ d: String = "uerth") -> UInt64 { w.balances()[d] ?? 0 }

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

    func fund(_ chain: FakeChain, _ w: PrivacyWallet, _ v: UInt64) async throws {
        let o = try w.shieldOutput(denom: "uerth", amount: v)
        chain.shield("uerth", v, o.pc, o.ciphertext)
        try await w.sync()
    }

    /// Records moves into `store` as the app does for another wallet on the phone; `failFirst` makes the first write fail.
    final class Recorder: PrivacyWallet.MoveRecorder, @unchecked Sendable {
        let store: PrivacyStore
        let now: () -> Int64
        var failFirst: Bool
        let targetID = "target"
        init(_ store: PrivacyStore, now: @escaping () -> Int64, failFirst: Bool = false) { self.store = store; self.now = now; self.failFirst = failFirst }
        func record(_ move: PendingMove) throws {
            if failFirst { failFirst = false; throw PrivacyError("disk full (test)") }
            try PrivacyWallet.recordIncoming(store, move, now: now())
        }
        func rollback(_ move: PendingMove) throws { try PrivacyWallet.rollbackIncoming(store, move, now: now()) }
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
        // Goldens, the same in Audit5Test.kt (Android/iOS byte parity).
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

    func testRestoredSwitchedIdentityRenewsWhatMovedToIt() async throws {
        let chain = FakeChain()
        let a = try wallet(chain, alice)
        try await register(chain, a, passport: "999")
        _ = try await a.bindHandle("alice"); try await a.sync()
        _ = try await a.setCaretaker(split: [1: 100]); try await a.sync()
        let bKeys = try PrivacyKeys.fromMnemonic(bob)
        let bStore = PrivacyStore.memory()
        let rec = Recorder(bStore, now: { chain.now })
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

    func testANonHolderRenewalIsRefusedAtNoCost() async throws {
        let chain = FakeChain()
        let a = try wallet(chain, alice)
        try await register(chain, a, passport: "777")
        chain.now += 2 * 86_400
        let c = try wallet(chain, carol)
        try await register(chain, c, passport: "777")
        try await fund(chain, c, 5_000_000)
        let before = bal(c)
        do { _ = try await c.bindHandle("carol"); XCTFail("refused") } catch let e as PrivacyWallet.NotYet {
            XCTAssertTrue(e.notHeld); XCTAssertGreaterThan(e.waitSeconds, chain.handleLease)
        }
        do { _ = try await c.setCaretaker(split: [1: 100]); XCTFail("refused") } catch let e as PrivacyWallet.NotYet { XCTAssertTrue(e.notHeld) }
        try await c.sync()
        XCTAssertEqual(before, bal(c))
        XCTAssertTrue(c.notes.filter(\.unspent).allSatisfy { $0.pendingAt == nil })
        XCTAssertEqual("", c.snapshot.handle)
    }

    func testTheDirectoryScanAdoptsAHandleAtThisAddressAndDropsASweptOne() async throws {
        let chain = FakeChain()
        let a = try wallet(chain, alice)
        try await register(chain, a, passport: "111")
        _ = try await a.bindHandle("alice"); try await a.sync()
        a.store.mutate { $0.handle = ""; $0.handleSetAt = 0 }
        let addressed = await a.reconcileHandle(try await chain.handleDirectory().chainDirectory(), readAt: chain.now + 1)
        XCTAssertEqual(["alice"], addressed.map(\.handle))
        XCTAssertEqual("alice", a.store.state.handle)
        chain.now += chain.handleLease - 86_400
        let e = try await chain.handleDirectory().chainDirectory()["alice"]!
        let due = Reminders.due(.init(now: chain.now, identityLive: true, claimOpensAt: nil, claimedToday: true, caretakerExpiresAt: 0,
                                      handle: "", handleEntry: nil, addressed: [e]))
        XCTAssertEqual(due, [.handleExpiring(handle: "alice", expiresAt: e.expiresAt, renewalUntil: e.renewalUntil, inRenewal: false)])
        chain.now += 86_400 + chain.handleRenewal + 1
        _ = await a.reconcileHandle(try await chain.handleDirectory().chainDirectory(), readAt: chain.now + 1)
        XCTAssertEqual("", a.store.state.handle)
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
                                       recorder: Recorder(bStore, now: { chain.now }))
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
            do { _ = try await a.moveHandle(newOwner: owner, target: bKeys, recorder: Recorder(bStore, now: { chain.now })); XCTFail(mode) } catch {}
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
        _ = try await a.moveHandle(newOwner: owner, target: bKeys, recorder: Recorder(.memory(), now: { chain.now }))
        XCTAssertTrue(a.snapshot.handleMovedOut)
    }

    func testRecordingInTheNewWalletIsRetryableAndItsSyncFindsTheMoveAnyway() async throws {
        let chain = FakeChain()
        let a = try wallet(chain, alice)
        try await register(chain, a, passport: "999")
        _ = try await a.bindHandle("alice"); try await a.sync()
        let bKeys = try PrivacyKeys.fromMnemonic(bob)
        let bStore = PrivacyStore.memory()
        let rec = Recorder(bStore, now: { chain.now }, failFirst: true)
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

    func testHostileHandleAndCaretakerTimesNeitherWrapNorTrap() async throws {
        let now: Int64 = 1_800_000_000
        // Reminders on Int64 extremes: these trapped (audit 5, M4).
        let e = HandleEntry(handle: "alice", address: "erthz1x", status: "live", expiresAt: .min, renewalUntil: .max)
        for c in [Int64.min, Int64.max, 1] {
            let due = Reminders.due(.init(now: now, identityLive: true, claimOpensAt: 0, claimedToday: false, caretakerExpiresAt: c,
                                          handle: "alice", handleEntry: e,
                                          addressed: [HandleEntry(handle: "bob", address: "erthz1x", status: "live", expiresAt: .min, renewalUntil: .max)],
                                          ownAddress: "erthz1y"))
            for r in due { _ = Reminders.text(r, now: now) }
        }
        _ = Reminders.text(.handleExpiring(handle: "alice", expiresAt: .min, renewalUntil: .max, inRenewal: true), now: .min)
        _ = Reminders.text(.caretakerExpiring(expiresAt: .max, lapsed: false), now: .min)
        XCTAssertEqual(.max, Handles.satAdd(.max, 1))
        XCTAssertEqual(.min, Handles.satSub(.min, 1))
        func dir(_ x: HandleEntry) -> HandleDirectory { HandleDirectory(fetchChainPage: { _, _ in .init(handles: [x], next: "") }, now: { now }) }
        let ok = HandleEntry(handle: "alice", address: "erthz1x", status: "live", expiresAt: now + 86_400, renewalUntil: now + 31 * 86_400)
        let got = try await dir(ok).chainDirectory()
        XCTAssertEqual(["alice"], Array(got.keys))
        for bad in [
            HandleEntry(handle: "alice", address: "erthz1x", status: "live", expiresAt: .min, renewalUntil: .max),
            HandleEntry(handle: "alice", address: "erthz1x", status: "live", expiresAt: 0, renewalUntil: ok.renewalUntil),
            HandleEntry(handle: "alice", address: "erthz1x", status: "live", expiresAt: ok.expiresAt, renewalUntil: ok.expiresAt - 1),
            HandleEntry(handle: "alice", address: "erthz1x", status: "live", expiresAt: ok.expiresAt, renewalUntil: now + Handles.maxAheadSeconds + 1),
        ] {
            do { _ = try await dir(bad).chainDirectory(); XCTFail("\(bad)") } catch is HandleDirectory.Inconsistent {}
        }
        let chain = FakeChain()
        let a = try wallet(chain, alice)
        try await register(chain, a, passport: "111")
        chain.forgeCaretakerExpiry = .max
        _ = try await a.setCaretaker(split: [1: 100])
        let exp = await a.caretakerExpiresAt()
        XCTAssertEqual(chain.now + chain.caretakerLease, exp)
        var s = a.store.state
        WalletSync.applyStateRecord(&s, position: .max, height: 1, .caretaker(kind: WalletSync.recordHolds, expiresAt: 0xffff_ffff, split: [1: 100]), now: chain.now)
        XCTAssertLessThanOrEqual(s.caretakerExpiresAt, chain.now + Handles.maxAheadSeconds)
    }

    func testReferralLinksOnlyFromTheVerifiedHost() {
        XCTAssertEqual("alice", ReferralLink.handle(fromLink: "https://erth.network/ref/alice"))
        XCTAssertEqual("a-b-c", ReferralLink.handle(fromLink: "https://erth.network/ref/a-b-c/"))
        for bad in ["earth://ref/alice", "http://erth.network/ref/alice", "https://erth.network.evil.com/ref/alice",
                    "https://evil.com/ref/alice", "https://erth.network/ref/alice/more", "https://erth.network/r/alice",
                    "https://erth.network:8443/ref/alice", "https://user@erth.network/ref/alice", "https://erth.network/ref/Alice",
                    "https://erth.network/ref/-x-", "https://erth.network/ref/", nil] as [String?] {
            XCTAssertNil(ReferralLink.handle(fromLink: bad), bad ?? "nil")
        }
    }
}
