import BigInt
import Foundation
import XCTest
@testable import EarthCore

/// Handles and the caretaker split end to end against `FakeChain`: claim,
/// renew, change, release and the whole-directory lookup; paying a handle;
/// a registration referred by one; lease bounds and the renewal period; a
/// held handle adopted only by its owner; hostile directory times; the
/// predecessor bounds of every membership statement.
final class HandlesTests: PrivacyTestCase {
    /// `w` registers `passport` after another wallet did: a switch, its leaf naming a predecessor. Funded for fees.
    func switched(_ chain: FakeChain, _ w: PrivacyWallet, passport: String) async throws {
        try await register(chain, w, passport: passport)
        let o = try w.shieldOutput(denom: "uerth", amount: 5_000_000)
        chain.shield("uerth", 5_000_000, o.pc, o.ciphertext)
        try await w.sync()
        XCTAssertGreaterThan(w.snapshot.identity!.predecessorAt, 0)
    }

    /// The chain's reads, with Query/LeaseBounds replaced.
    struct LeaseBoundsReads: PrivacyChainReads, @unchecked Sendable {
        let inner: FakeReads
        let bounds: () -> PrivacyReads.LeaseBounds
        func personhoodParams() async throws -> PrivacyReads.PersonhoodParams { try await inner.personhoodParams() }
        func leaseBounds() async throws -> PrivacyReads.LeaseBounds { bounds() }
        func ballotInputs(proposalID: UInt64, optionID: UInt64) async throws -> PrivacyReads.BallotInputs {
            try await inner.ballotInputs(proposalID: proposalID, optionID: optionID)
        }
        func epochNumber() async throws -> UInt64 { try await inner.epochNumber() }
        func snapshot(proposalID: UInt64) async throws -> PrivacyReads.Snapshot { try await inner.snapshot(proposalID: proposalID) }
        func positions() async throws -> [PrivacyReads.Position] { try await inner.positions() }
        func debtTree(start: UInt64, limit: Int) async throws -> PrivacyReads.DebtTreePage { try await inner.debtTree(start: start, limit: limit) }
        func validatorBook(_ valoper: String) async throws -> PrivacyReads.Book { try await inner.validatorBook(valoper) }
        func minDelegation() async throws -> UInt64 { try await inner.minDelegation() }
        func stakeNullifierTree(start: UInt64, limit: Int) async throws -> PrivacyReads.NfTreePage {
            try await inner.stakeNullifierTree(start: start, limit: limit)
        }
    }

    /// A wallet whose chain reads give `bounds` for Query/LeaseBounds.
    func wallet(_ chain: FakeChain, _ words: String, bounds: @escaping () -> PrivacyReads.LeaseBounds) throws -> PrivacyWallet {
        try wallet(chain, words, reads: LeaseBoundsReads(inner: FakeReads(chain: chain), bounds: bounds))
    }

    func testHandleLifecycle() async throws {
        let chain = FakeChain()
        let a = try wallet(chain, alice), b = try wallet(chain, bob)
        try await register(chain, a, passport: "111")
        try await register(chain, b, passport: "222")
        // A fresh registrant (predecessor_at 0) claims at once.
        XCTAssertEqual(0, a.snapshot.identity?.predecessorAt)
        _ = try await a.bindHandle("alice")
        let e = try await entry(chain, "alice")
        XCTAssertEqual(HandleEntry.live, e?.status)
        XCTAssertEqual(a.address.encode(), e?.address)
        XCTAssertEqual(chain.now + chain.handleLease, e?.expiresAt)
        XCTAssertEqual((e?.expiresAt ?? 0) + chain.handleRenewal, e?.renewalUntil)
        // The claim proved the lease bound, not "no bound".
        let claim = chain.prover.allMemberships.last!
        XCTAssertLessThan(Int64(claim.maxPredecessor), chain.now - chain.handleLease - 86_400)
        XCTAssertEqual(PrivacyHash.noBound, claim.maxActivation)

        // Taken: another human cannot claim it (1122).
        do { _ = try await b.bindHandle("alice"); XCTFail("taken") } catch { XCTAssertTrue("\(error)".contains("1122"), "\(error)") }

        // Renewal: a year from now, the address may change.
        chain.now += 100 * 86_400
        try await a.sync()
        _ = try await a.bindHandle("alice")
        let renewed = try await entry(chain, "alice")
        XCTAssertEqual(chain.now + chain.handleLease, renewed?.expiresAt)

        // A change frees the old handle at once: bob claims it in the next block.
        _ = try await a.bindHandle("alice-two")
        let freed = try await entry(chain, "alice")
        XCTAssertNil(freed)
        XCTAssertEqual("alice-two", a.snapshot.handle)
        try await b.sync()
        _ = try await b.bindHandle("alice")
        let bobs = try await entry(chain, "alice")
        XCTAssertEqual(b.address.encode(), bobs?.address)

        // Release: gone at once; a holder of none claims again (fresh: no wait).
        try await a.sync()
        _ = try await a.releaseHandle()
        let released = try await entry(chain, "alice-two")
        XCTAssertNil(released)
        XCTAssertEqual("", a.snapshot.handle)
        try await a.sync()
        _ = try await a.bindHandle("alice-three")
        let three = try await entry(chain, "alice-three")
        XCTAssertNotNil(three)

        // Lapse: the entry stays reserved for the renewal period, then is free.
        chain.now += chain.handleLease + 1
        let lapsing = try await entry(chain, "alice-three")
        XCTAssertEqual(HandleEntry.renewal, lapsing?.status)
        chain.now += chain.handleRenewal
        let gone = try await entry(chain, "alice-three")
        XCTAssertNil(gone)
        dump(chain, "handleLifecycle")
    }

    func testPayAHandleFromTheWholeDirectory() async throws {
        let chain = FakeChain()
        let a = try wallet(chain, alice), b = try wallet(chain, bob)
        try await register(chain, a, passport: "111")
        try await register(chain, b, passport: "222")
        _ = try await a.bindHandle("alice")
        try await a.sync(); try await b.sync()
        chain.handleAsks = []
        guard case let .payable(e, addr) = try await chain.handleDirectory().resolveForPayment("@Alice") else { return XCTFail("not payable") }
        XCTAssertEqual("alice", e.handle)
        XCTAssertEqual(a.address, addr)
        // Never one handle: the indexer's stream from 0, and the chain's directory from the first.
        XCTAssertFalse(chain.handleAsks.isEmpty)
        XCTAssertTrue(chain.handleAsks.allSatisfy { $0 == "indexer:0" || $0 == "chain:" }, "\(chain.handleAsks)")
        let before = bal(a)
        _ = try await b.send(to: addr, denom: "uerth", amount: 1_234_000)
        try await a.sync()
        XCTAssertEqual(before + 1_234_000, bal(a))

        // An indexer naming another address for the handle is caught by the chain's directory.
        chain.forgeHandleAddress = b.address.encode()
        if case .payable = try await chain.handleDirectory().resolveForPayment("alice") { XCTFail("forged address paid") }
        chain.forgeHandleAddress = nil

        // A handle never claimed, or one that lapsed, pays nobody.
        if case .payable = try await chain.handleDirectory().resolveForPayment("nobody") { XCTFail("unclaimed paid") }
        if case .payable = try await chain.handleDirectory().resolveForPayment("not a handle!") { XCTFail("not a handle") }
        chain.now += chain.handleLease + 10
        if case .payable = try await chain.handleDirectory().resolveForPayment("alice") { XCTFail("lapsed paid") }
        XCTAssertEqual("alice", Handles.parse(" @ALICE "))
        XCTAssertTrue(Handles.looksLikeHandle("@alice") && Handles.looksLikeHandle("alice") && !Handles.looksLikeHandle("earth1abc"))
        XCTAssertTrue(!Handles.valid("-ab") && !Handles.valid("ab") && !Handles.valid("a_b") && Handles.valid("a-b"))
    }

    func testRegistrationReferredByAHandle() async throws {
        let chain = FakeChain()
        let a = try wallet(chain, alice)
        try await register(chain, a, passport: "111")
        _ = try await a.bindHandle("alice")
        try await a.sync()
        let before = bal(a)
        let b = try wallet(chain, bob)
        guard case let .payable(e, addr) = try await chain.handleDirectory().resolveForPayment("@alice") else { return XCTFail("not payable") }
        let prep = try await register(chain, b, passport: "222", referrer: PrivacyWallet.Referrer(handle: e.handle, address: addr))
        // The binding commits to the handle alone: the chain makes the referral note.
        XCTAssertEqual(PrivacyHash.registrationBinding(chainID: chain.chainID, idc: b.keys.idc, pcAnml: prep.anml.pc, ctAnml: prep.anml.ciphertext, pcErth: prep.erth.pc,
                                                       ctErth: prep.erth.ciphertext, affiliate: PrivacyHash.affiliateField(handle: "alice")),
                       prep.binding)
        // Minted to the handle's owner_pk with the opening derived from the passport nullifier and the leaf.
        let leaf = b.store.state.identity!.leafIndex
        let o = PrivacyHash.referralOpening(nullifier: Fr(UInt64(222)), leafIndex: leaf)
        XCTAssertEqual(1, chain.referralNotes.count)
        XCTAssertEqual("alice", chain.referralNotes.first?.0)
        XCTAssertEqual(PrivacyHash.pc(ownerPK: a.keys.ownerPK, rho: o.rho, rcm: o.rcm), chain.referralNotes.first?.1)
        // The referrer's wallet finds it in the stream (no ciphertext: by owner_pk, cm checked), privately.
        try await a.sync()
        XCTAssertEqual(before + 5_000_000, bal(a))
        let note = try XCTUnwrap(a.notes.first { $0.position == chain.referralPositions.first })
        XCTAssertEqual(o.rho, note.note.rho)
        XCTAssertEqual(o.rcm, note.note.rcm)
        XCTAssertEqual(PrivacyHash.nf(nk: a.keys.nk, rho: o.rho, position: note.position), note.nf)
        // Nobody else's wallet takes it.
        try await b.sync()
        XCTAssertFalse(b.notes.contains { $0.position == note.position })

        // A lapsed handle is refused (1121); a registration cannot name its own wallet.
        chain.now += chain.handleLease + 10
        let c = try wallet(chain, carol)
        let cprep = try await c.prepareRegistration(referrer: PrivacyWallet.Referrer(handle: "alice", address: a.address))
        chain.shield("uerth", 100_000, cprep.gas.pc, cprep.gas.ciphertext)
        try await c.sync()
        do {
            _ = try await c.register(cprep, proof: Data(count: 14_656), publicSignals: signals(cprep, "333"), signatureAlgorithm: "lean_poa", dscDer: Data(count: 10))
            XCTFail("lapsed referrer accepted")
        } catch { XCTAssertTrue("\(error)".contains("1121"), "\(error)") }
        do { _ = try await a.prepareRegistration(referrer: PrivacyWallet.Referrer(handle: "alice", address: a.address)); XCTFail("self-referral") } catch {}
    }

    func testPredecessorBounds() async throws {
        let chain = FakeChain()
        let a = try wallet(chain, alice)
        try await register(chain, a, passport: "777")
        // A switch to a wallet that moved nothing: its leaf names the switch.
        chain.now += 2 * 86_400
        let c = try wallet(chain, carol)
        try await register(chain, c, passport: "777")
        // A switch pays nothing: fees from a shield.
        try await fund(chain, c, 5_000_000)
        let pred = Int64(c.snapshot.identity!.predecessorAt)
        XCTAssertEqual(c.snapshot.identity!.activatedAt, UInt64(pred))
        // No new handle, no new caretaker vote until anything the old one held has lapsed.
        do { _ = try await c.bindHandle("carol"); XCTFail("handle too soon") } catch let e as PrivacyWallet.NotYet {
            XCTAssertGreaterThan(e.waitSeconds, chain.handleLease)
        }
        do { _ = try await c.setCaretaker(split: [1: 100]); XCTFail("split too soon") } catch let e as PrivacyWallet.NotYet {
            XCTAssertGreaterThan(e.waitSeconds, chain.caretakerLease)
        }
        // No ballot vote on a ballot opened before the switch (opened - 86400 bound).
        do { _ = try await c.voteProposal(proposalID: 5, yes: true); XCTFail("ballot too soon") } catch is PrivacyWallet.NotYet {}
        // Claims bound the activation only: the switched identity claims from the day after next.
        chain.now += 2 * 86_400
        try await c.sync()
        _ = try await c.claimAnml()
        XCTAssertEqual(PrivacyHash.noBound, chain.prover.allMemberships.last?.maxPredecessor)
        // A day later the ballot bound has passed the switch.
        _ = try await c.voteProposal(proposalID: 6, yes: true)
        // Past the caretaker lease (and the margin): a split; past the longest handle lease: a handle.
        chain.now = pred + chain.caretakerLease + 86_400 + 2 * 3_600 + 600
        try await c.sync()
        _ = try await c.setCaretaker(split: [1: 100])
        chain.now = pred + chain.handleLease + 86_400 + 2 * 3_600 + 600
        try await c.sync()
        _ = try await c.bindHandle("carol")
        let e = try await entry(chain, "carol")
        XCTAssertNotNil(e)
        dump(chain, "predecessorBounds")
    }

    func testAHandleInItsRenewalPeriodIsBoundedLikeAClaim() async throws {
        let chain = FakeChain()
        let a = try wallet(chain, alice)
        try await register(chain, a, passport: "501")
        _ = try await a.bindHandle("alice")
        let ae = try await entry(chain, "alice")
        let aExp = await a.handleExpiresAt()
        XCTAssertEqual(ae?.expiresAt, aExp)
        // Lapsed into its renewal period: a fresh identity meets the claim bound and renews.
        chain.now += chain.handleLease + 10
        try await a.sync()
        let renewal = try await entry(chain, "alice")
        XCTAssertEqual(HandleEntry.renewal, renewal?.status)
        _ = try await a.bindHandle("alice")
        let live = try await entry(chain, "alice")
        XCTAssertEqual(HandleEntry.live, live?.status)
        XCTAssertLessThan(chain.prover.allMemberships.last!.maxPredecessor, PrivacyHash.noBound)

        // A switched identity whose own bound has not passed holds a handle (moved to it), lets it
        // lapse: renewing now is a claim it cannot make. Refused before anything is sent.
        let b = try wallet(chain, bob)
        try await register(chain, b, passport: "502")
        _ = try await b.bindHandle("bobby")
        try await b.sync()
        chain.now += 86_400
        let c = try wallet(chain, carol)
        try await switched(chain, c, passport: "502")
        chain.handles["bobby"]!.nullifier = PrivacyWallet.newOwner(c.keys, scope: PrivacyHash.handleScope())
        try await c.adoptMoved(handle: "bobby", split: nil, splitExpiresAt: 0)
        _ = await c.reconcileHandle(["bobby": try await entry(chain, "bobby")!], readAt: chain.now + 1)
        let cExp = await c.handleExpiresAt()
        XCTAssertGreaterThan(cExp, chain.now)
        // Live: renewed with no bound (it holds it).
        _ = try await c.bindHandle("bobby")
        XCTAssertEqual(PrivacyHash.noBound, chain.prover.allMemberships.last!.maxPredecessor)
        try await c.sync()
        chain.now += chain.handleLease + 10
        try await c.sync()
        _ = await c.reconcileHandle(["bobby": try await entry(chain, "bobby")!], readAt: chain.now + 1)
        let sent = chain.txs.count
        do { _ = try await c.bindHandle("bobby"); XCTFail("renewal in the renewal period with an unmet bound") } catch let e as PrivacyWallet.NotYet {
            XCTAssertEqual(.handle("bobby"), e.lapsed)
            XCTAssertTrue(e.localizedDescription.contains("renewal period"), e.localizedDescription)
        }
        XCTAssertEqual(sent, chain.txs.count)
        // ... and it cannot be moved either.
        do {
            _ = try await c.moveHandle(newOwner: PrivacyWallet.newOwner(try PrivacyKeys.fromMnemonic(alice), scope: PrivacyHash.handleScope()))
            XCTFail("moved a handle in its renewal period")
        } catch is PrivacyWallet.HandleNotMovable {}
        XCTAssertEqual(sent, chain.txs.count)
        dump(chain, "handleRenewal")
    }

    func testAHandleWhoseExpiryTheWalletLacksIsTriedAndTheChainDecides() async throws {
        let chain = FakeChain()
        let b = try wallet(chain, bob)
        try await register(chain, b, passport: "602")
        _ = try await b.bindHandle("bobby")
        chain.now += 86_400
        let c = try wallet(chain, carol)
        try await switched(chain, c, passport: "602")
        chain.handles["bobby"]!.nullifier = PrivacyWallet.newOwner(c.keys, scope: PrivacyHash.handleScope())
        try await c.adoptMoved(handle: "bobby", split: nil, splitExpiresAt: 0)
        let exp = await c.handleExpiresAt()
        XCTAssertEqual(0, exp)
        chain.now += chain.handleLease + 10
        try await c.sync()
        // No expiry known: a no-bound attempt; the chain refuses it in its ante (no fee): notHeld.
        do { _ = try await c.bindHandle("bobby"); XCTFail("renewed") } catch let e as PrivacyWallet.NotYet { XCTAssertTrue(e.notHeld) }
    }

    func testALapsedCaretakerSplitIsANewSplit() async throws {
        let chain = FakeChain()
        let b = try wallet(chain, bob)
        try await register(chain, b, passport: "702")
        _ = try await b.setCaretaker(split: [1: 100])
        try await b.sync()
        chain.now += 86_400
        let c = try wallet(chain, carol)
        try await switched(chain, c, passport: "702")
        // The split moved to c (as MsgMoveCaretaker would), then lapses unswept.
        let exp = chain.now + 3 * 86_400
        let nfB = PrivacyHash.scopeNullifier(idSecret: b.keys.idSecret, scope: PrivacyHash.caretakerScope())
        let nfC = PrivacyWallet.newOwner(c.keys, scope: PrivacyHash.caretakerScope())
        chain.caretakerVotes[nfC] = chain.caretakerVotes.removeValue(forKey: nfB)
        chain.caretakerExpiry.removeValue(forKey: nfB); chain.caretakerExpiry[nfC] = exp
        try await c.adoptMoved(handle: nil, split: [1: 100], splitExpiresAt: exp)
        // Live: refreshed with no bound.
        _ = try await c.setCaretaker(split: [1: 100])
        XCTAssertEqual(PrivacyHash.noBound, chain.prover.allMemberships.last!.maxPredecessor)
        try await c.sync()
        chain.now = await c.caretakerExpiresAt() + 10
        try await c.sync()
        let sent = chain.txs.count
        do { _ = try await c.setCaretaker(split: [1: 100]); XCTFail("refreshed a lapsed split") } catch let e as PrivacyWallet.NotYet {
            XCTAssertEqual(.caretaker, e.lapsed)
        }
        XCTAssertEqual(sent, chain.txs.count)
        // Clearing needs no bound.
        _ = try await c.setCaretaker(split: [:])
    }

    func testTheClaimBoundUsesTheLongestLeaseNotParams() async throws {
        let chain = FakeChain()
        let a = try wallet(chain, alice)
        try await register(chain, a, passport: "801")
        chain.now += 2 * 86_400
        let c = try wallet(chain, carol)
        try await switched(chain, c, passport: "801")
        let pred = Int64(c.snapshot.identity!.predecessorAt)
        // Governance cut the lease to 30 days; the chain still bounds claims by the year ever in force.
        chain.handleLeaseMax = chain.handleLease
        chain.handleLease = 30 * 86_400
        chain.now = pred + 30 * 86_400 + 86_400 + 2 * 3_600 + 600
        try await c.sync()
        let reads0 = chain.leaseBoundsReads
        do { _ = try await c.bindHandle("carol"); XCTFail("claimed before the longest lease") } catch let e as PrivacyWallet.NotYet {
            XCTAssertGreaterThan(e.waitSeconds, 300 * 86_400)
        }
        XCTAssertGreaterThan(chain.leaseBoundsReads, reads0)
        let none = try await entry(chain, "carol")
        XCTAssertNil(none)
        // Past the longest lease: a claim, bounded by it.
        chain.now = pred + chain.handleLeaseMax + 86_400 + 2 * 3_600 + 600
        try await c.sync()
        _ = try await c.bindHandle("carol")
        let some = try await entry(chain, "carol")
        XCTAssertNotNil(some)
        let mp = Int64(chain.prover.allMemberships.last!.maxPredecessor)
        XCTAssertTrue(mp < chain.now - chain.handleLeaseMax - 86_400 && mp % 3_600 == 0 && mp >= pred)
        // The caretaker bound likewise: a held longer lease after a cut keeps bounding splits.
        chain.caretakerLeaseHold = 400 * 86_400
        do { _ = try await c.setCaretaker(split: [1: 100]); XCTFail("split before the held lease") } catch is PrivacyWallet.NotYet {}
        dump(chain, "leaseBounds")
    }

    func testLeaseBoundsThatDoNotAddUpAreRefused() async throws {
        let chain = FakeChain()
        let a = try wallet(chain, alice, bounds: { var lb = chain.leaseBounds(); lb.handleClaimBound += 1; return lb })
        try await register(chain, a, passport: "901")
        do { _ = try await a.bindHandle("alice"); XCTFail("used inconsistent bounds") } catch {
            XCTAssertTrue("\(error)".contains("do not add up"), "\(error)")
        }
        let z = try wallet(chain, bob, bounds: { var lb = chain.leaseBounds(); lb.handleLeaseSeconds = 0; return lb })
        try await register(chain, z, passport: "902")
        do { _ = try await z.bindHandle("bob"); XCTFail("used a zero lease") } catch {}
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

    func testTheDirectoryScanAdoptsAHandleAtThisAddressAndDropsASweptOne() async throws {
        let chain = FakeChain()
        let a = try wallet(chain, alice)
        try await register(chain, a, passport: "111")
        _ = try await a.bindHandle("alice"); try await a.sync()
        a.store.mutate { $0.handle = ""; $0.handleSetAt = 0 }
        let addressed = await a.reconcileHandle(try await chain.handleDirectory().chainDirectory(), readAt: chain.now + 1)
        // Adopted by its owner; held, no other entry is offered.
        XCTAssertEqual([], addressed.map(\.handle))
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

    func testDirectoryOwnersParseOnlyAs64Hex() throws {
        let h = String(repeating: "ab", count: 32)
        XCTAssertEqual(h, Handles.owner(h.uppercased()))
        XCTAssertEqual("", Handles.owner(nil))
        XCTAssertEqual("", Handles.owner(String(repeating: "ab", count: 31)))
        XCTAssertEqual("", Handles.owner(String(repeating: "zz", count: 32)))
        let body = #"{"handles":[["alice","erthz1x","live",10,20,"\#(h)"],["bob","erthz1y","live",10,20]],"height":5,"size":2,"from_index":0,"last_page":true}"#
        let page = HTTPPrivacyIndexer.parseHandles(JSON(try JSONSerialization.jsonObject(with: Data(body.utf8))))
        XCTAssertEqual([h, ""], page.handles.map(\.owner))
    }

    func testHostileHandleAndCaretakerTimesNeitherWrapNorTrap() async throws {
        let now: Int64 = 1_800_000_000
        // Reminders on Int64 extremes neither wrap nor trap.
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
}
