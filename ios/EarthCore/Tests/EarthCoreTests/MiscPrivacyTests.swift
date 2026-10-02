import BigInt
import XCTest
@testable import EarthCore

/// The chain's own witness fixtures (tools/privacyfixtures), rebuilt from
/// scratch with the wallet's trees and derivations (ports FixtureWitnessTest.kt).
/// Their public inputs are byte for byte those of the real proofs in the
/// chain's zk/ultrahonk/testdata, so a witness these builders produce is one
/// the chain verifies.
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

    func testTransferFixture() throws {
        let t = MerkleTree(store: MemNodeStore())
        let nk = det("nk", 0)
        let opk = PrivacyHash.ownerPK(nk)
        let assetA = PrivacyHash.assetID("uanml"), erth = PrivacyHash.assetID("uerth")
        let inVal: [UInt64] = [700_000, 300_000, 50_000], outVal: [UInt64] = [600_000, 350_000, 40_000]
        let assets = [assetA, assetA, erth]
        let positions: [UInt64] = [4, 7, 9]
        let rho = (0 ..< 3).map { det("rho", UInt64($0)) }, rcm = (0 ..< 3).map { det("rcm", UInt64($0)) }
        var next = 0
        for p in 0 ..< 12 as Range<UInt64> {
            var cm = PrivacyHash.cm(asset: erth, value: p + 1, pc: det("otherpc", p))
            if next < 3, p == positions[next] {
                cm = PrivacyHash.cm(asset: assets[next], value: inVal[next], pc: PrivacyHash.pc(ownerPK: opk, rho: rho[next], rcm: rcm[next])); next += 1
            }
            t.append(cm)
        }
        let w = try TransferWitness(
            asset: assetA, nk: nk,
            inputs: (0 ..< 3).map { try TransferInput(value: inVal[$0], rho: rho[$0], rcm: rcm[$0], position: positions[$0], path: t.path(positions[$0])) },
            outputs: (0 ..< 3).map { i in
                TransferOutput(value: outVal[i], pc: PrivacyHash.pc(ownerPK: PrivacyHash.ownerPK(det("recipient", UInt64(i))), rho: det("orho", UInt64(i)), rcm: det("orcm", UInt64(i))))
            },
            root: t.root(), fee: 10_000, vPubOut: 50_000, signal: det("signal", 1)
        )
        XCTAssertEqual(publicInputs("fixture_transfer"), w.publicInputs().map(\.hex))
        XCTAssertEqual(parseToml(String(decoding: Vectors.resource("fixture_transfer/Prover.toml"), as: UTF8.self)), parseToml(w.proverToml()))
    }
}

/// The witness mirrors the transfer circuit's balance rule (ports TransferBalanceTest.kt).
final class TransferBalanceTests: XCTestCase {
    func ins(_ v: UInt64...) -> [TransferInput] {
        v.enumerated().map { try! TransferInput(value: $0.element, rho: Fr(UInt64(10 + $0.offset)), rcm: Fr(UInt64(20 + $0.offset)),
                                                position: UInt64($0.offset), path: Array(repeating: .zero, count: Merkle.depth)) }
    }
    func outs(_ v: UInt64...) -> [TransferOutput] { v.enumerated().map { TransferOutput(value: $0.element, pc: Fr(UInt64(30 + $0.offset))) } }
    func w(_ asset: Fr, _ i: [TransferInput], _ o: [TransferOutput], _ fee: UInt64, _ vPub: UInt64) throws {
        _ = try TransferWitness(asset: asset, nk: Fr(UInt64(7)), inputs: i, outputs: o, root: .zero, fee: fee, vPubOut: vPub, signal: .zero)
    }

    func testErthOneNotePaysSpendAndFee() throws {
        try w(PrivacyHash.assetErth, ins(100, 0, 0), outs(60, 0, 37), 3, 0)
        try w(PrivacyHash.assetErth, ins(0, 0, 100), outs(60, 37, 0), 3, 0)
        try w(PrivacyHash.assetErth, ins(100, 0, 0), outs(0, 0, 10), 5, 85)
    }

    func testErthCombinedInflationRejected() {
        XCTAssertThrowsError(try w(PrivacyHash.assetErth, ins(100, 0, 0), outs(60, 0, 38), 3, 0))
        XCTAssertThrowsError(try w(PrivacyHash.assetErth, ins(100, 0, 0), outs(0, 0, 10), 5, 86))
    }

    func testOtherAssetKeepsTwoBalances() throws {
        let a = PrivacyHash.assetID("uanml")
        try w(a, ins(70, 30, 10), outs(60, 40, 7), 3, 0)
        XCTAssertThrowsError(try w(a, ins(100, 0, 0), outs(97, 0, 0), 3, 0))
        XCTAssertThrowsError(try w(a, ins(70, 30, 10), outs(70, 40, 0), 0, 0))
        XCTAssertThrowsError(try w(a, ins(70, 30, 10), outs(60, 30, 17), 3, 0))
    }
}

/// Ports AutomationTest.kt.
final class AutomationTests: XCTestCase {
    let day: Int64 = 20_000
    var base: PrivacyAutomation.Inputs {
        .init(now: day * 86_400 + 5 * 3600, identityLive: true, claimOpensAt: 0, claimedToday: false, claimOffset: 4 * 3600,
              caretakerDue: false, hasFeeErth: true, maturedUnbonds: [])
    }
    func note(_ denom: String, _ pos: UInt64 = 3) -> OwnedNote {
        OwnedNote(position: pos, height: 1, note: NotePlaintext(denom: denom, value: 5, rho: .one, rcm: .one), cm: .one, nf: .one)
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
        var i = base; i.claimedToday = true; i.caretakerDue = true; i.hasFeeErth = false; i.maturedUnbonds = [n]
        // No fee note: the caretaker refresh waits, the unbonding claim pays from its output.
        XCTAssertEqual([.claimUnbonding(n)], PrivacyAutomation.decide(i))
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
        func m(_ ns: [OwnedNote], _ now: Int64, _ retry: [String: Int64] = [:]) -> [OwnedNote] {
            PrivacyAutomation.matured(ns, now: now, current: 10, currentStart: t, epochSeconds: d, unbondingSeconds: unbonding, retryAt: retry)
        }
        XCTAssertTrue(m([n9, n10], by9 - 1).isEmpty)
        XCTAssertEqual([n9], m([n9, n10], by9))
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
