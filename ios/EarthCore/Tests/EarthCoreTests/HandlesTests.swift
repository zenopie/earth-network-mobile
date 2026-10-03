import BigInt
import XCTest
@testable import EarthCore

/// Round 5 (chain 4a663d5), ports HandlesTest.kt: handles end to end against
/// `FakeChain` (claim, renew, change, release, move; the whole-directory
/// lookup), paying a handle, a registration referred by a handle, an identity
/// switch that moves its handle and caretaker vote first, and the predecessor
/// bounds of every membership statement.
final class HandlesTests: XCTestCase {
    let alice = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    let bob = "legal winner thank year wave sausage worth useful legal winner thank yellow"
    let carol = "letter advice cage absurd amount doctor acoustic avoid letter advice cage above"

    func wallet(_ chain: FakeChain, _ words: String) throws -> PrivacyWallet {
        PrivacyWallet(keys: try PrivacyKeys.fromMnemonic(words), store: .memory(), indexer: chain, chain: chain,
                      reads: FakeReads(chain: chain), prover: chain.prover, chainID: chain.chainID, roots: chain,
                      now: { [unowned chain] in chain.now })
    }

    func bal(_ w: PrivacyWallet, _ d: String = "uerth") -> UInt64 { w.balances()[d] ?? 0 }

    /// Registers `w` with `passport` (a switch when another wallet holds it), referred by `referrer`.
    @discardableResult
    func register(_ chain: FakeChain, _ w: PrivacyWallet, passport: String, referrer: PrivacyWallet.Referrer? = nil) async throws -> PrivacyWallet.RegistrationPrep {
        let prep = try await w.prepareRegistration(referrer: referrer)
        chain.shield("uerth", 100_000, prep.gas.pc, prep.gas.ciphertext)
        try await w.sync()
        _ = try await w.register(prep, proof: Data(count: 14_656), publicSignals: signals(prep, passport), signatureAlgorithm: "lean_poa",
                                 dscDer: Data(count: 10))
        try await w.sync()
        XCTAssertEqual(.live, w.identityStatus())
        return prep
    }

    func signals(_ prep: PrivacyWallet.RegistrationPrep, _ passport: String) -> [String] {
        ["261001", prep.binding.bigUInt.description, passport, Fr(UInt64(77)).bigUInt.description]
    }

    func entry(_ chain: FakeChain, _ h: String) async throws -> HandleEntry? { try await chain.handleDirectory().lookup(h) }

    func fund(_ chain: FakeChain, _ w: PrivacyWallet, _ v: UInt64) async throws {
        let o = try w.shieldOutput(denom: "uerth", amount: v)
        chain.shield("uerth", v, o.pc, o.ciphertext)
        try await w.sync()
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
        // The binding commits to the handle and the referral note made to it.
        let ref = prep.referral!
        XCTAssertEqual(177, ref.ciphertext.count)
        XCTAssertEqual(PrivacyHash.registrationBinding(idc: b.keys.idc, pcAnml: prep.anml.pc, ctAnml: prep.anml.ciphertext, pcErth: prep.erth.pc,
                                                       ctErth: prep.erth.ciphertext, affiliate: PrivacyHash.affiliateField(handle: "alice", pc: ref.pc, ct: ref.ciphertext)),
                       prep.binding)
        XCTAssertEqual(1, chain.referralNotes.count)
        XCTAssertEqual("alice", chain.referralNotes.first?.0)
        // The referrer finds its half as a note, privately.
        try await a.sync()
        XCTAssertEqual(before + 5_000_000, bal(a))

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

    func testSwitchMovesTheHandleAndCaretakerVoteFirst() async throws {
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

    /// With PRIVACY_TOML_OUT set, every witness as a nargo Prover.toml (see WalletFlowTests).
    func dump(_ chain: FakeChain, _ test: String) {
        guard let out = ProcessInfo.processInfo.environment["PRIVACY_TOML_OUT"] else { return }
        func write(_ kind: String, _ i: Int, _ toml: String) {
            let dir = URL(fileURLWithPath: out).appendingPathComponent(kind).appendingPathComponent("ios_\(test)_\(i)")
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            try? toml.write(to: dir.appendingPathComponent("Prover.toml"), atomically: true, encoding: .utf8)
        }
        for (i, w) in chain.prover.allActions.enumerated() { write("action", i, w.proverToml()) }
        for (i, w) in chain.prover.allStakes.enumerated() { write("stake", i, w.proverToml()) }
        for (i, w) in chain.prover.allVotes.enumerated() { write("vote", i, w.proverToml()) }
        for (i, w) in chain.prover.allMemberships.enumerated() { write("membership", i, w.proverToml()) }
    }
}
