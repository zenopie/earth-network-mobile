import BigInt
import XCTest
@testable import EarthCore

/// The chain's own witness fixtures (tools/privacyfixtures membership,
/// tools/orchardfixtures action), rebuilt with the wallet's trees,
/// derivations and Grumpkin (ports FixtureWitnessTest.kt). nargo execute
/// accepting the chain's tomls is the Go<->Noir parity check; these tests are
/// the Go<->Swift one.
final class FixtureWitnessTests: XCTestCase {
    func det(_ label: String, _ i: UInt64) -> Fr { PrivacyHash.h(PrivacyHash.assetID("fixture/\(label)"), PrivacyHash.u64(i)) }

    func publicInputs(_ name: String) -> [String] {
        let raw = Vectors.resource("\(name)/public_inputs.expected")
        return (0 ..< raw.count / 32).map { Vectors.hex(raw.subdata(in: 32 * $0 ..< 32 * $0 + 32)) }
    }

    /// Minimal TOML: key = "scalar" | [ ... ], values normalised to integers.
    func parseToml(_ text: String) -> [String: String] {
        var out: [String: String] = [:]
        for line in text.split(separator: "\n") where !line.hasPrefix("#") && line.contains(" = ") {
            let parts = line.components(separatedBy: " = ")
            let normalized = parts[1].replacingOccurrences(of: "\"", with: "")
                .components(separatedBy: CharacterSet(charactersIn: "[], "))
                .filter { !$0.isEmpty }
                .map { v -> String in v.hasPrefix("0x") ? BigUInt(String(v.dropFirst(2)), radix: 16)!.description : v }
            out[parts[0]] = normalized.joined(separator: ",")
        }
        return out
    }

    func testMembershipFixture() throws {
        let t = MerkleTree(store: MemNodeStore())
        let ours: UInt64 = 13
        let idSecret = det("id_secret", 0), dscKey = det("dsc", 0)
        let country = PrivacyHash.countryField("DE")
        let activatedAt: UInt64 = 1_790_000_000
        for i in 0 ..< 21 as Range<UInt64> {
            let leaf = i == ours
                ? PrivacyHash.identityLeaf(idc: PrivacyHash.idc(idSecret), dscKey: dscKey, country: country, activatedAt: activatedAt)
                : PrivacyHash.identityLeaf(idc: PrivacyHash.idc(det("other", i)), dscKey: det("dsc", i % 3),
                                           country: PrivacyHash.countryField(["DE", "FR", ""][Int(i % 3)]), activatedAt: 1_780_000_000 + i)
            t.append(leaf)
        }
        t.update(3, .zero)
        let w = try MembershipWitness(idSecret: idSecret, dscKey: dscKey, country: country, activatedAt: activatedAt, leafIndex: ours,
                                      siblings: t.path(ours), root: t.root(), scope: PrivacyHash.assetID("claim:20360"), signal: det("signal", 0),
                                      excludedDsc: det("dsc", 99), excludedCountry: PrivacyHash.countryField("FR"), maxActivation: activatedAt + 86_400)
        try w.check()
        XCTAssertEqual(publicInputs("fixture_membership"), w.publicInputs().map(\.hex))
        XCTAssertEqual(parseToml(String(decoding: Vectors.resource("fixture_membership/Prover.toml"), as: UTF8.self)), parseToml(w.proverToml()))
    }

    /// tools/orchardfixtures' 3-action mixed-asset bundle: each action's
    /// witness rebuilt from its private inputs reproduces the chain's public
    /// inputs (cv included) and Prover.toml, and the bundle's digest, sighash
    /// and binding signature check under the wallet's own code.
    func testActionFixture() throws {
        let bj = try JSONSerialization.jsonObject(with: Vectors.resource("fixture_action/bundle.json")) as! [String: Any]
        let acts = bj["actions"] as! [[String: Any]]
        var actions: [ShieldedAction] = []
        for (i, a) in acts.enumerated() {
            let text = String(decoding: Vectors.resource("fixture_action/action_\(i)/Prover.toml"), as: UTF8.self)
            let t = parseToml(text)
            func f(_ k: String) -> Fr { Fr(BigUInt(t[k]!, radix: 10)!) }
            func u(_ k: String) -> UInt64 { UInt64(t[k]!)! }
            let w = try ActionWitness(nk: f("nk"), sAsset: f("s_asset"), sValue: u("s_value"), sRho: f("s_rho"), sRcm: f("s_rcm"),
                                      sPos: u("s_pos"), sPath: t["s_path"]!.split(separator: ",").map { Fr(BigUInt(String($0), radix: 10)!) },
                                      oAsset: f("o_asset"), oValue: u("o_value"), oPc: f("o_pc"), rcv: f("rcv"), anchor: f("anchor"),
                                      sighash: f("sighash"))
            try w.check()
            XCTAssertEqual(publicInputs("fixture_action/action_\(i)"), w.publicInputs().map(\.hex), "action \(i)")
            XCTAssertEqual(t, parseToml(w.proverToml()), "action \(i) toml")
            XCTAssertEqual(a["cv"] as? String, Vectors.hex(w.cv.bytes))
            actions.append(ShieldedAction(anchor: w.anchor.bytes, nullifier: w.nf.bytes, commitment: w.cmOut.bytes, cv: w.cv.bytes,
                                  ciphertext: Vectors.unhex(a["ct"] as! String), proof: Data()))
        }
        let balances = (bj["balances"] as! [[String: Any]]).map { ValueBalance(denom: $0["denom"] as! String, amount: ($0["value"] as! NSNumber).uint64Value) }
        let b = ShieldedBundle(actions: actions, balances: balances, bindingSig: Vectors.unhex(bj["binding_sig"] as! String))
        let sighash = PrivacyHash.signal(msgType: bj["msg_type"] as! String, chainID: bj["chain_id"] as! String,
                                         fields: [PrivacyHash.u64(1), try PrivateMsgs.digest(b)])
        XCTAssertEqual(String((bj["sighash"] as! String).dropFirst(2)), sighash.hex)
        XCTAssertTrue(PrivateMsgs.checkBalance(b, sighash: sighash))
    }
}

/// Ports AutomationTest.kt.
final class AutomationTests: XCTestCase {
    let day: Int64 = 20_000
    var base: PrivacyAutomation.Inputs {
        .init(now: day * 86_400 + 5 * 3600, identityLive: true, claimOpensAt: 0, claimedToday: false, claimOffset: 4 * 3600,
              caretakerDue: false, hasFeeErth: true, maturedUnbonds: [])
    }
    func note(_ denom: String, _ pos: UInt64 = 3) -> OwnedStakeNote {
        OwnedStakeNote(position: pos, height: 1, denom: denom, amount: 5, rho: .one, rcm: .one, cm: .one, nf: .one)
    }

    func testClaimsOnceTheDaysOffsetHasPassed() {
        XCTAssertEqual([.claimAnml(day: UInt64(day))], PrivacyAutomation.decide(base))
        var i = base; i.claimOffset = 6 * 3600; XCTAssertTrue(PrivacyAutomation.decide(i).isEmpty)
        i = base; i.claimedToday = true; XCTAssertTrue(PrivacyAutomation.decide(i).isEmpty)
        i = base; i.claimOpensAt = 123; XCTAssertTrue(PrivacyAutomation.decide(i).isEmpty)
        i = base; i.identityLive = false; XCTAssertTrue(PrivacyAutomation.decide(i).isEmpty)
        i = base; i.hasFeeErth = false; XCTAssertTrue(PrivacyAutomation.decide(i).isEmpty)
    }

    func testRefreshesCaretakerAndClaimsUnbonding() {
        let n = note("unbond/v/1")
        var i = base; i.claimedToday = true; i.caretakerDue = true; i.hasFeeErth = false; i.maturedUnbonds = [n.denom]
        // No fee note: the caretaker refresh waits, the unbonding claim pays from its output.
        XCTAssertEqual([.claimUnbonding(denom: n.denom)], PrivacyAutomation.decide(i))
        i = base; i.claimedToday = true; i.caretakerDue = true
        XCTAssertEqual([.refreshCaretaker], PrivacyAutomation.decide(i))
    }

    func testOffsetIsWithinTheWindowAndStableForADay() {
        let a = PrivacyAutomation.claimOffset(now: day * 86_400 + 10)
        XCTAssertEqual(a, PrivacyAutomation.claimOffset(now: day * 86_400 + 80_000))
        XCTAssertTrue((0 ..< PrivacyAutomation.claimWindow).contains(a))
    }

    func testMaturityComesFromEpochTimingAlone() {
        let d: Int64 = 86_400, unbonding = 21 * d, t: Int64 = 1_800_000_000
        XCTAssertEqual(t + unbonding + PrivacyAutomation.maturityMargin, PrivacyAutomation.maturesBy(9, current: 10, currentStart: t, epochSeconds: d, unbondingSeconds: unbonding))
        XCTAssertEqual(t - 2 * d + unbonding + PrivacyAutomation.maturityMargin, PrivacyAutomation.maturesBy(7, current: 10, currentStart: t, epochSeconds: d, unbondingSeconds: unbonding))
        XCTAssertNil(PrivacyAutomation.maturesBy(10, current: 10, currentStart: t, epochSeconds: d, unbondingSeconds: unbonding))
        let n9 = note("unbond/v/9", 1), n10 = note("unbond/v/10", 2)
        let by9 = PrivacyAutomation.maturesBy(9, current: 10, currentStart: t, epochSeconds: d, unbondingSeconds: unbonding)!
        func m(_ ns: [OwnedStakeNote], _ now: Int64, _ retry: [String: Int64] = [:]) -> [String] {
            PrivacyAutomation.matured(ns, now: now, current: 10, currentStart: t, epochSeconds: d, unbondingSeconds: unbonding, retryAt: retry)
        }
        XCTAssertTrue(m([n9, n10], by9 - 1).isEmpty)
        XCTAssertEqual([n9.denom], m([n9, n10], by9))
        XCTAssertTrue(m([n9], by9, ["unbond/v/9": by9 + 1]).isEmpty)
        var pending = n9; pending.pendingAt = 1
        XCTAssertTrue(m([pending], by9).isEmpty)
    }

    func testRefreshesTheReferrerBinding() {
        var i = base; i.claimedToday = true; i.referrerDue = true
        XCTAssertEqual([.refreshReferrer], PrivacyAutomation.decide(i))
        i.hasFeeErth = false
        XCTAssertTrue(PrivacyAutomation.decide(i).isEmpty)
    }
}

/// Ports GasTransparentTest.kt.
final class GasTransparentTests: XCTestCase {
    func testScopeAndSignalMatchTheChain() {
        XCTAssertEqual("189ca0017ef0d3fb8ebca623f3a5b50b0db38b16ff09ed877b8ed66a9548c9ff", PrivacyHash.gasScope(yyyymm: 202610).hex)
        XCTAssertEqual("1985e8e50ba97e2b2a44119f927d4c6ea9d58f8cafa89c8c2a60eabe3ba29c80",
                       PrivacyHash.gasTransparentSignal(chainID: "earth-1", address: Data((0 ..< 20).map { UInt8($0 + 1) })).hex)
    }

    func testMonthIsUtcYyyymm() {
        XCTAssertEqual(202610, GasTransparent.month(1_790_812_800))
        XCTAssertEqual(202609, GasTransparent.month(1_790_812_799))
        XCTAssertEqual(202612, GasTransparent.month(1_798_761_599))
    }

    func testWitnessProvesTheWalletsLeafForThisMonthAndAddress() async throws {
        let chain = FakeChain()
        let a = PrivacyWallet(keys: try PrivacyKeys.fromMnemonic(KeysAndNotesTests.mnemonic), store: .memory(), indexer: chain, chain: chain,
                              reads: FakeReads(chain: chain, snapshotSize: { 0 }), prover: chain.prover, chainID: chain.chainID,
                              now: { [unowned chain] in chain.now })
        try await a.sync()
        let target = try Bech32.encode(hrp: "earth", data: Bech32.convertBits(Array(repeating: 9, count: 20), from: 8, to: 5, pad: true))
        do { _ = try await GasTransparent.witness(wallet: a, address: target, now: chain.now); XCTFail("unregistered") } catch {}

        let prep = try await a.prepareRegistration(affiliate: nil)
        chain.shield("uerth", 100_000, prep.gas.pc)
        try await a.sync()
        _ = try await a.register(prep, proof: Data(count: 14_656),
                                 publicSignals: ["261001", prep.binding.bigUInt.description, "123456789", Fr(UInt64(77)).bigUInt.description],
                                 signatureAlgorithm: "lean_poa", dscDer: Data(count: 10))
        try await a.sync()
        do { _ = try await GasTransparent.witness(wallet: a, address: target, now: chain.now); XCTFail("too soon") } catch is PrivacyWallet.NotYet {}

        let now = chain.now + 2 * 3600
        let w = try await GasTransparent.witness(wallet: a, address: target, now: now)
        XCTAssertEqual(PrivacyHash.gasScope(yyyymm: GasTransparent.month(now)), w.scope)
        XCTAssertEqual(PrivacyHash.gasTransparentSignal(chainID: "earth-1", address: try PrivateMsgs.addressBytes(target)), w.signal)
        XCTAssertEqual(0, w.maxActivation % 3600)
        XCTAssertEqual(chain.identityTree.root(), w.root)
        XCTAssertEqual(PrivacyHash.scopeNullifier(idSecret: a.keys.idSecret, scope: w.scope), w.nullifier)

        let req = try await GasTransparent.request(wallet: a, address: target, prove: { _ in Data(repeating: 7, count: 3) }, now: now)
        let body = GasTransparent.body(req)
        XCTAssertEqual(target, body["address"] as? String)
        XCTAssertEqual("BwcH", body["proof"] as? String)
        XCTAssertEqual(w.root.bytes, Data(base64Encoded: body["root"] as! String))
        XCTAssertEqual(w.nullifier.bytes, Data(base64Encoded: body["nullifier"] as! String))
        XCTAssertEqual(w.maxActivation, body["max_activation"] as? UInt64)
        XCTAssertTrue(JSONSerialization.isValidJSONObject(body))
    }
}

/// SwapMath against x/dex/keeper/amm.go's own output (ports SwapMathTest.kt).
final class SwapMathTests: XCTestCase {
    lazy var json = try! JSONSerialization.jsonObject(with: Vectors.resource("dex_amm.json")) as! [String: Any]

    func testHopsMatchTheChain() {
        for h in json["hops"] as! [[String: Any]] {
            func b(_ k: String) -> BigInt { BigInt(h[k] as! String)! }
            let fee = Decimal(string: h["fee"] as! String)!
            let q = h["dir"] as! String == "hub_for_token"
                ? SwapMath.hubForToken(reserveErth: b("reserve_erth"), reserveToken: b("reserve_token"), amountIn: b("amount_in"), feePercent: fee)
                : SwapMath.tokenForHub(reserveErth: b("reserve_erth"), reserveToken: b("reserve_token"), amountIn: b("amount_in"), feePercent: fee)
            XCTAssertEqual(b("amount_out"), q?.amountOut, "\(h)")
            XCTAssertEqual(b("fee_erth"), q?.feeErth, "\(h)")
        }
    }

    func testTwoHopRoutesMatchTheChain() {
        for t in json["two_hop"] as! [[String: Any]] {
            func b(_ k: String) -> BigInt { BigInt(t[k] as! String)! }
            let pools = ["ua": SwapMath.Reserves(erth: b("in_pool_erth"), token: b("in_pool_token")),
                         "ub": SwapMath.Reserves(erth: b("out_pool_erth"), token: b("out_pool_token"))]
            let q = SwapMath.route(pools: pools, hub: "uerth", denomIn: "ua", amountIn: b("amount_in"), denomOut: "ub",
                                   feePercent: Decimal(string: t["fee"] as! String)!)
            if b("amount_out") == 0 { XCTAssertNil(q) } else { XCTAssertEqual(b("amount_out"), q?.amountOut) }
        }
    }

    func testSlippageFloorTruncates() {
        XCTAssertEqual(98_999, SwapMath.withSlippage(99_999, bps: 100))
        XCTAssertEqual(0, SwapMath.withSlippage(1, bps: 50))
    }

    func testChainSimulationReplacesLocalMaths() throws {
        func j(_ s: String) -> JSON { JSON(try! JSONSerialization.jsonObject(with: Data(s.utf8))) }
        let sim = Dex.parseSimulated(j(#"{"token_out":{"denom":"uanml","amount":"9871"},"fee":{"denom":"uerth","amount":"30"},"erth_burned":"15"}"#))!
        XCTAssertEqual(9871, sim.amountOut)
        XCTAssertEqual(30, sim.feeErth)
        XCTAssertNil(Dex.parseSimulated(j(#"{"code":12,"message":"Not Implemented"}"#)))
        XCTAssertNil(Dex.parseSimulated(j(#"{"token_out":{"denom":"uanml","amount":"0"},"fee":{"denom":"uerth","amount":"0"}}"#)))
        let local = SwapMath.hubForToken(reserveErth: 1_000_000, reserveToken: 1_000_000, amountIn: 10_000, feePercent: Decimal(string: "0.3")!)!
        let merged = SwapMath.withChain(local, chainOut: sim.amountOut, chainFee: sim.feeErth)!
        XCTAssertEqual(sim.amountOut, merged.amountOut)
        XCTAssertEqual(local.priceImpact, merged.priceImpact)
        XCTAssertEqual(local, SwapMath.withChain(local, chainOut: nil, chainFee: nil))
        XCTAssertNil(SwapMath.withChain(nil, chainOut: nil, chainFee: nil))
    }
}

/// derth is valued at rate_v (ERTH per derth), floored as the chain floors it,
/// and a stake vote weighs only what the snapshot admits.
final class StakeValueTests: XCTestCase {
    func testDerthValueFloorsAtTheRate() {
        XCTAssertEqual(1_050_000, PrivacyWallet.derthValue(1_000_000, rate: Decimal(string: "1.05")!))
        XCTAssertEqual(1, PrivacyWallet.derthValue(3, rate: Decimal(string: "0.5")!))
        XCTAssertEqual(1_234_567, PrivacyWallet.derthValue(1_234_567, rate: 1))
        // 999,999.999999999999999999: floored, not rounded up by the 18-place rate.
        XCTAssertEqual(999_999, PrivacyWallet.derthValue(999_999, rate: Decimal(string: "1.000001000001000001")!))
    }

    func testPositionsFromBeforeTheSnapshotVote() {
        func pos(_ id: UInt64, _ h: UInt64) -> PrivacyReads.Position {
            .init(id: id, validator: "v", derth: 1, ownerTag: .zero, createdHeight: h)
        }
        let snap = PrivacyReads.Snapshot(root: .zero, treeSize: 0, height: 100)
        XCTAssertEqual([1], PrivacyWallet.votingPositions([pos(1, 99), pos(2, 100), pos(3, 101)], snapshot: snap).map(\.id))
        // A snapshot without a height (unknown) admits every position.
        XCTAssertEqual(2, PrivacyWallet.votingPositions([pos(1, 99), pos(2, 100)], snapshot: .init(root: .zero, treeSize: 0)).count)
    }
}

/// The wallet home's Shield / Unshield limits.
final class ShieldMoveTests: XCTestCase {
    func note(_ value: UInt64, _ pos: UInt64, denom: String = "uerth", spent: Bool = false, pending: Bool = false) -> OwnedNote {
        OwnedNote(position: pos, height: 1, note: NotePlaintext(denom: denom, value: value, rho: .one, rcm: .one), cm: .one, nf: .one,
                  spentHeight: spent ? 2 : nil, pendingAt: pending ? 1 : nil)
    }

    func testMaxSpendableTakesTheLargestSpendableNotes() {
        let notes = [note(5, 1), note(40, 2), note(30, 3), note(20, 4), note(99, 5, spent: true), note(98, 6, pending: true), note(97, 7, denom: "uanml")]
        XCTAssertEqual(90, NoteSelection.maxSpendable(notes, denom: "uerth", maxNotes: 3))
        XCTAssertEqual(95, NoteSelection.maxSpendable(notes, denom: "uerth", maxNotes: 16))
        XCTAssertEqual(70, NoteSelection.maxSpendable(notes, denom: "uerth", maxNotes: 2))
        XCTAssertEqual(97, NoteSelection.maxSpendable(notes, denom: "uanml", maxNotes: 16))
        XCTAssertEqual(0, NoteSelection.maxSpendable([], denom: "uerth", maxNotes: 16))
    }

    /// Max is every note one bundle carries; at Max the fee comes out of the amount.
    func testMaxUnshieldIsEveryNoteABundleCarries() {
        let ns = (1 ... 20).map { note(10, UInt64($0)) }
        XCTAssertEqual(160, ShieldMove.maxUnshield(ns, maxNotes: 16))
        XCTAssertEqual(200, ShieldMove.maxUnshield(ns, maxNotes: 32))
        XCTAssertEqual(0, ShieldMove.maxUnshield([], maxNotes: 16))
        XCTAssertTrue(ShieldMove.feeFromAmount(amount: 160, spendable: 160, fee: 3))
        XCTAssertFalse(ShieldMove.feeFromAmount(amount: 150, spendable: 160, fee: 3))
    }

    func testMaxShieldLeavesTheFee() {
        XCTAssertEqual(BigInt(90), ShieldMove.maxShield(public: 100, fee: 10))
        XCTAssertEqual(BigInt(0), ShieldMove.maxShield(public: 5, fee: 10))
    }
}
