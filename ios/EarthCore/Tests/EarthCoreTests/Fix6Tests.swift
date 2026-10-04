import BigInt
import XCTest
@testable import EarthCore

/// Clients round 6 (chain 203d3b2, ORCHARD_DESIGN 16), ports Fix6Test.kt: the
/// open referral note and the notes stream format 2, split LP payouts (one
/// ciphertext at several positions), handles and caretaker splits held but
/// not live, lease bounds from Query/LeaseBounds, anchors with margin, the swap
/// fee rounded up, withdrawal note legs and the new chain errors.
final class Fix6Tests: XCTestCase {
    let alice = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    let bob = "legal winner thank year wave sausage worth useful legal winner thank yellow"
    let carol = "letter advice cage absurd amount doctor acoustic avoid letter advice cage above"

    struct Reads: PrivacyChainReads, @unchecked Sendable {
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

    func wallet(_ chain: FakeChain, _ words: String, bounds: (() -> PrivacyReads.LeaseBounds)? = nil) throws -> PrivacyWallet {
        let reads: PrivacyChainReads = bounds.map { Reads(inner: FakeReads(chain: chain), bounds: $0) } ?? FakeReads(chain: chain)
        return PrivacyWallet(keys: try PrivacyKeys.fromMnemonic(words), store: .memory(), indexer: chain, chain: chain,
                             reads: reads, prover: chain.prover, chainID: chain.chainID, roots: chain,
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

    /// `w` registers `passport` after another wallet did: a switch, its leaf naming a predecessor. Funded for fees.
    func switched(_ chain: FakeChain, _ w: PrivacyWallet, passport: String) async throws {
        try await register(chain, w, passport: passport)
        let o = try w.shieldOutput(denom: "uerth", amount: 5_000_000)
        chain.shield("uerth", 5_000_000, o.pc, o.ciphertext)
        try await w.sync()
        XCTAssertGreaterThan(w.snapshot.identity!.predecessorAt, 0)
    }

    func entry(_ chain: FakeChain, _ h: String) async throws -> HandleEntry? { try await chain.handleDirectory().lookup(h) }

    func notes(_ s: String) throws -> NotesPage { try HTTPPrivacyIndexer.parseNotes(JSON(try JSONSerialization.jsonObject(with: Data(s.utf8)))) }

    // MARK: notes stream format 2

    let fields = #"["position","height","cm","ciphertext","amount","owner_pk","rho","rcm"]"#
    let hex0 = String(repeating: "00", count: 32)
    let hex1 = String(repeating: "00", count: 31) + "01"

    func testNotesFormat2ByNameWithOpenRows() throws {
        let page = try notes(#"{"format":2,"fields":["rcm","rho","owner_pk","amount","ciphertext","cm","height","position"],"notes":[[null,null,null,null,"AAAA",""# + hex1 + #"",5,0],[null,null,null,"7uerth","AAAA",""# + hex1 + #"",5,1],[""# + hex1 + #"",""# + hex0 + #"",""# + hex1 + #"","5000000uerth",null,""# + hex1 + #"",6,2]],"next_pos":3,"complete":false,"synced_height":6}"#)
        XCTAssertEqual([0, 1, 2], page.rows.map(\.position))
        XCTAssertNil(page.rows[0].amount); XCTAssertNil(page.rows[0].ownerPK)
        XCTAssertEqual("7uerth", page.rows[1].amount); XCTAssertNil(page.rows[1].rho)
        let open = page.rows[2]
        XCTAssertEqual(0, open.ciphertext.count)
        XCTAssertEqual(Fr(UInt64(1)), open.ownerPK); XCTAssertEqual(Fr.zero, open.rho); XCTAssertEqual(Fr(UInt64(1)), open.rcm)
        // An old backend (format 1, five columns) is refused, not misread.
        XCTAssertThrowsError(try notes(#"{"notes":[[0,1,""# + hex1 + #"","AAAA",null]],"next_pos":1,"complete":false,"synced_height":1}"#))
        // Part of an opening, an opening beside a ciphertext, an open row with no amount: refused.
        for row in [
            #"[0,1,""# + hex1 + #"",null,"5uerth",""# + hex1 + #"",null,""# + hex1 + #""]"#,
            #"[0,1,""# + hex1 + #"","AAAA","5uerth",""# + hex1 + #"",""# + hex1 + #"",""# + hex1 + #""]"#,
            #"[0,1,""# + hex1 + #"",null,null,""# + hex1 + #"",""# + hex1 + #"",""# + hex1 + #""]"#,
            #"[0,1,""# + hex1 + #"",null,"5uerth",null,null,null]"#,
        ] {
            XCTAssertThrowsError(try notes(#"{"format":2,"fields":"# + fields + #","notes":["# + row + #"],"next_pos":1,"complete":false,"synced_height":1}"#), row)
        }
    }

    func testOpenNoteIsOursOnlyByOwnerPKAndCm() async throws {
        let chain = FakeChain()
        let a = try wallet(chain, alice), b = try wallet(chain, bob)
        let o = PrivacyHash.referralOpening(nullifier: Fr(UInt64(4242)), leafIndex: 3)
        let pos = chain.mintOpen("uerth", 5_000_000, ownerPK: a.keys.ownerPK, rho: o.rho, rcm: o.rcm)
        chain.emptyBlock()
        // A row naming alice's owner_pk but a cm of another amount is not a note of hers.
        let cmOther = PrivacyHash.cm(asset: PrivacyHash.assetID("uerth"), value: 6_000_000, pc: PrivacyHash.pc(ownerPK: a.keys.ownerPK, rho: o.rho, rcm: o.rcm))
        let lie = chain.noteTree.append(cmOther)
        chain.notes.append(NoteRow(position: lie, height: chain.height, cm: cmOther, ciphertext: Data(), amount: "5000000uerth",
                                   ownerPK: a.keys.ownerPK, rho: o.rho, rcm: o.rcm))
        chain.emptyBlock()
        try await a.sync(); try await b.sync()
        XCTAssertEqual([pos], a.notes.map(\.position))
        XCTAssertEqual(5_000_000, bal(a))
        XCTAssertTrue(b.notes.isEmpty)
        // Spendable: alice sends from it.
        _ = try await a.send(to: b.address, denom: "uerth", amount: 1_000_000)
        try await b.sync()
        XCTAssertEqual(1_000_000, bal(b))
        dump(chain, "fix6OpenNote")
    }

    // MARK: split LP payouts

    func testSplitPayoutOneCiphertextAtSeveralPositions() async throws {
        let chain = FakeChain()
        let a = try wallet(chain, alice)
        // MintNoteSplit: one pc and one ciphertext, several notes, each its own amount and position
        // (the test's chunks are ones this wallet holds; the chain cuts at 2^64-1).
        let out = try await a.withdrawalNote()
        let values: [UInt64] = [3_000_000, 3_000_000, 1_234]
        let ps = chain.mintSplit("uanml", values, out.pc, out.ciphertext)
        chain.emptyBlock()
        try await a.sync()
        let got = a.notes.filter { ps.contains($0.position) }.sorted { $0.position < $1.position }
        XCTAssertEqual(values, got.map(\.note.value))
        // One rho and rcm, a nullifier per position: every chunk spends on its own.
        XCTAssertEqual(1, Set(got.map { $0.note.rho.hex + $0.note.rcm.hex }).count)
        XCTAssertEqual(3, Set(got.map(\.nf)).count)
        for n in got { XCTAssertEqual(PrivacyHash.nf(nk: a.keys.nk, rho: n.note.rho, position: n.position), n.nf) }
        XCTAssertEqual(6_001_234, bal(a, "uanml"))
        XCTAssertEqual(got[0].note, got[1].note)
        XCTAssertNotEqual(got[0], got[1])
    }

    func testWithdrawalNoteLegsAreBoundedAtStart() throws {
        let total = BigInt(1_000)
        let big = PrivacyWallet.maxWithdrawalNoteLeg * 2
        try PrivacyWallet.checkWithdrawalNoteLegs(shares: 500, totalShares: total, reserveErth: 10, reserveToken: big, tokenDenom: "uanml",
                                                  erthNote: true, tokenNote: true)
        XCTAssertThrowsError(try PrivacyWallet.checkWithdrawalNoteLegs(shares: 501, totalShares: total, reserveErth: 10, reserveToken: big,
                                                                       tokenDenom: "uanml", erthNote: true, tokenNote: true)) { e in
            let m = (e as? LocalizedError)?.errorDescription ?? ""
            XCTAssertTrue(m.contains("uanml") && m.contains("smaller parts"), m)
        }
        try PrivacyWallet.checkWithdrawalNoteLegs(shares: 501, totalShares: total, reserveErth: big, reserveToken: 10, tokenDenom: "uanml",
                                                  erthNote: false, tokenNote: true)
        XCTAssertEqual(PrivacyWallet.maxWithdrawalNoteLeg, BigInt(Int64.max) * 32)
    }

    // MARK: handles and caretaker splits held but not live

    func testHandleInItsRenewalPeriodIsBoundedLikeAClaim() async throws {
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
        dump(chain, "fix6HandleRenewal")
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

    // MARK: lease bounds

    func testClaimBoundUsesTheLongestLeaseNotParams() async throws {
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
        dump(chain, "fix6LeaseBounds")
    }

    func testLeaseBoundsThatDoNotAddUpAreRefused() async throws {
        let chain = FakeChain()
        let a = try wallet(chain, alice) { var lb = chain.leaseBounds(); lb.handleClaimBound += 1; return lb }
        try await register(chain, a, passport: "901")
        do { _ = try await a.bindHandle("alice"); XCTFail("used inconsistent bounds") } catch {
            XCTAssertTrue("\(error)".contains("do not add up"), "\(error)")
        }
        let z = try wallet(chain, bob) { var lb = chain.leaseBounds(); lb.handleLeaseSeconds = 0; return lb }
        try await register(chain, z, passport: "902")
        do { _ = try await z.bindHandle("bob"); XCTFail("used a zero lease") } catch {}
    }

    // MARK: anchors

    func testAnAnchorAboutToLapseIsReplacedOrRefused() async throws {
        let chain = FakeChain()
        let a = try wallet(chain, alice), b = try wallet(chain, bob)
        let n = try NoteOut.mintToSelf(a.keys, denom: "uerth")
        chain.shield("uerth", 3_000_000, n.pc, n.ciphertext)
        try await a.sync()
        let stale = a.store.noteTree.root()
        // The local root lapses in a minute (CheckTx would refuse it within 120 s).
        chain.rootExpiresAt = { [unowned chain] r in r == stale ? chain.now + 60 : 0 }
        let sent = chain.txs.count
        do { _ = try await a.send(to: b.address, denom: "uerth", amount: 1_000); XCTFail("sent on a lapsing anchor") } catch is PrivacyWallet.AnchorTooOld {}
        XCTAssertEqual(sent, chain.txs.count)
        // A newer root exists on the chain: the wallet syncs to it and sends.
        let m = try NoteOut.mintToSelf(b.keys, denom: "uerth")
        chain.shield("uerth", 1, m.pc, m.ciphertext)
        _ = try await a.send(to: b.address, denom: "uerth", amount: 1_000)
        XCTAssertNotEqual(stale, a.store.noteTree.root())
        // With room to spare (> the margin), the root is used as it is.
        chain.rootExpiresAt = { [unowned chain] _ in chain.now + PrivacyWallet.anchorMargin + 60 }
        try await a.sync()
        _ = try await a.send(to: b.address, denom: "uerth", amount: 1_000)
    }

    // MARK: dex and errors

    func testSwapFeeRoundsUp() {
        let f = Decimal(string: "0.3")!
        XCTAssertEqual(1, SwapMath.feeOf(1, f))
        XCTAssertEqual(3, SwapMath.feeOf(1_000, f))
        XCTAssertEqual(4, SwapMath.feeOf(1_001, f))
        XCTAssertEqual(0, SwapMath.feeOf(1_000, 0))
        // The 18th place rounds half-even before the ceiling (as LegacyDec.Quo): 1e-20 is 0, not 1.
        XCTAssertEqual(0, SwapMath.feeOf(1, Decimal(string: "0.000000000000000001")!))
        let q = SwapMath.hubForToken(reserveErth: 1_000_000, reserveToken: 1_000_000, amountIn: 1_001, feePercent: f)!
        XCTAssertEqual(4, q.feeErth)
    }

    func testNewChainErrorsAreExplained() {
        XCTAssertNotNil(ChainErrors.explain(code: 1101, codespace: "dex",
                                            log: "invalid amount: the uanml leg (1) is above 2, the most one withdrawal pays as notes; withdraw in smaller parts"))
        XCTAssertNil(ChainErrors.explain(code: 1101, codespace: "dex", log: "invalid amount: zero"))
        XCTAssertNotNil(ChainErrors.explain(code: 5, codespace: "bank", log: "uanml: send transactions are disabled"))
        XCTAssertNotNil(ChainErrors.explain(text: "\"alice\" is not live (renewal): renew it before moving it: invalid private msg"))
        XCTAssertNotNil(ChainErrors.explain(code: 1103, codespace: "shielded",
                                            log: "01AB expires at 5, within 120s of the last block: pick a newer anchor: root is not a recent note-tree root"))
        XCTAssertNil(ChainErrors.explain(code: 1103, codespace: "shielded", log: "root is not a recent note-tree root"))
    }

    func testRegistrationBindsTheHandleOnly() async throws {
        let chain = FakeChain()
        let a = try wallet(chain, alice)
        let prep = try await a.prepareRegistration(referrer: PrivacyWallet.Referrer(handle: "bobby", address: try wallet(chain, bob).address))
        let msg = a.registerMsg(prep, proof: Data(count: 1), publicSignals: ["1", "2", "3", "4"], signatureAlgorithm: "lean_poa", dscDer: Data(count: 1))
        XCTAssertEqual("bobby", msg.affiliateHandle)
        XCTAssertEqual(PrivacyHash.affiliateField(handle: "bobby"), try msg.affiliateField())
        let none = a.registerMsg(try await a.prepareRegistration(referrer: nil), proof: Data(count: 1), publicSignals: ["1", "2", "3", "4"],
                                 signatureAlgorithm: "lean_poa", dscDer: Data(count: 1))
        XCTAssertEqual("", none.affiliateHandle)
        XCTAssertEqual(Fr.zero, try none.affiliateField())
        // The gas grant body carries the handle alone.
        let body = GasGrant.Request.register(msg, pcGas: Data(count: 32), ciphertextGas: Data(count: 177)).body
        XCTAssertEqual("bobby", body["affiliate_handle"] as? String)
        XCTAssertNil(body["affiliate_pc"]); XCTAssertNil(body["affiliate_ciphertext"])
        // MsgRegister's wire form has no 11 / 12.
        let f = try ProtoFields(msg.encoded())
        XCTAssertFalse(f.has(11)); XCTAssertFalse(f.has(12)); XCTAssertTrue(f.has(15))
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
