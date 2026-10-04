import BigInt
import Foundation
import XCTest
@testable import EarthCore

/// The dex maths against x/dex/keeper/amm.go's own output (hops, two-hop
/// routes, deposits), the swap fee rounded up, slippage floors, the chain's
/// simulation, add-liquidity's min_shares and withdrawal note legs.
final class DexTests: XCTestCase {
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

    /// Deposits: the shares and the legs x/dex pulls, rounded
    /// up, match the chain's own maths; a leg derived with depositLeg never
    /// makes the other side the binding one and is never pulled past.
    func testDepositsMatchTheChain() {
        let deps = json["deposits"] as! [[String: Any]]
        XCTAssertEqual(144, deps.count)
        for d in deps {
            func b(_ k: String) -> BigInt { BigInt(d[k] as! String)! }
            let got = SwapMath.deposit(erthIn: b("in_erth"), tokenIn: b("in_token"), re: b("reserve_erth"), rt: b("reserve_token"), supply: b("supply"))
            if b("shares") == 0 { XCTAssertNil(got, "\(d)") } else {
                XCTAssertEqual(b("shares"), got?.shares, "\(d)")
                XCTAssertEqual(b("pull_erth"), got?.erth, "\(d)")
                XCTAssertEqual(b("pull_token"), got?.token, "\(d)")
            }
            let re = b("reserve_erth"), rt = b("reserve_token"), s = b("supply"), e = b("in_erth")
            let t = SwapMath.depositLeg(e, from: re, to: rt)
            let fromErth = e * s / re
            if fromErth > 0 {
                let p = SwapMath.deposit(erthIn: e, tokenIn: t, re: re, rt: rt, supply: s)
                XCTAssertEqual(fromErth, p?.shares, "\(d) from erth")
                XCTAssertTrue((p?.erth ?? 0) <= e && (p?.token ?? 0) <= t)
            }
        }
        XCTAssertEqual(3, SwapMath.depositLeg(5, from: 2, to: 1))
        XCTAssertEqual(0, SwapMath.depositLeg(1, from: 0, to: 1))
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

    func testTheSwapFeeRoundsUp() {
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

    func testPublicAddLiquidityCarriesMinShares() {
        // min(e*S/Re, t*S/Rt) = min(1000*500/2000, 300*500/1000) = 150, less 1%: 148.
        XCTAssertEqual("148", SwapMath.minShares(erthIn: 1000, tokenIn: 300, re: 2000, rt: 1000, supply: 500, bps: 100))
        XCTAssertEqual("", SwapMath.minShares(erthIn: 1, tokenIn: 1, re: 0, rt: 0, supply: 0, bps: 100))
        let m = Msg.AddLiquidity(creator: "earth1creator", poolID: 2, amountA: Coin(denom: "uerth", amount: "1000"),
                                 amountB: Coin(denom: "uusd", amount: "300"), minShares: "148")
        // Golden shared with Android: fields 1-5.
        XCTAssertEqual("0a0d65617274683163726561746f7210021a0d0a057565727468120431303030220b0a047575736412033330302a03313438",
                       m.encoded().map { String(format: "%02x", $0) }.joined())
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
}
