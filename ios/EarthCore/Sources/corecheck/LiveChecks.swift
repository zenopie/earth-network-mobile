import BigInt
import EarthCore
import Foundation

/// Queries the real LCD.
///
/// Off by default — the rest of `corecheck` must pass on a plane. What this
/// adds is the one thing offline vectors cannot: whether the response shapes
/// this code reads are the shapes `earth-1` actually serves. A field renamed
/// on the chain shows up here as an empty result, not as a build failure.
func checkLive() async {
    let client = EarthClient()

    Check.group("live LCD — \(Constants.lcdURL.absoluteString)")

    // A known-populated account: the chain's own community pool address would
    // do, but any registered human is enough — so this asks for something that
    // exists on every chain instead.
    do {
        let pool = try await client.rest.get("/cosmos/staking/v1beta1/pool")
        Check.that("reachable", pool.pool.bonded_tokens.exists)
    } catch {
        Check.that("reachable", false, detail: "\(error)")
        print("  (skipping the rest — no chain to ask)")
        return
    }

    let bonded = await client.totalBonded()
    Check.that("staking pool parses", BigInt(bonded) != nil, detail: "bonded_tokens = \(bonded)")
    if let bondedInt = Int64(bonded), bondedInt > 0 {
        Check.that("staking APR is a number", StakingApr.base(bondedUerth: bondedInt) != nil)
    }

    let validators = await client.bondedValidators()
    Check.that("validators parse", !validators.isEmpty, detail: "got \(validators.count)")
    if let v = validators.first {
        Check.that("validator has an operator address", v.operatorAddress.hasPrefix("earthvaloper"))
        Check.that("commission is a fraction", (0 ... 1).contains(v.commission))
    }

    let pools = await client.pools()
    Check.that("dex pools parse", !pools.isEmpty, detail: "got \(pools.count)")
    if let p = pools.first {
        Check.that("pool reserves are integers",
                   BigInt(p.erthReserve) != nil && BigInt(p.tokenReserve) != nil)
        Check.that("pool names a spoke token", !p.tokenDenom.isEmpty)
    }

    let feePercent = await client.swapFeePercent()
    Check.that("swap fee parses as a decimal", Decimal(string: feePercent) != nil,
               detail: "swap_fee = \(feePercent)")

    // Quote a real pool at its real fee. The numbers cannot be asserted — they
    // move — but a nil here means the reserves did not parse.
    if let p = pools.first,
       let erth = BigInt(p.erthReserve), let token = BigInt(p.tokenReserve),
       let fee = Decimal(string: feePercent), erth > 0, token > 0 {
        let quote = SwapMath.hubForToken(reserveErth: erth, reserveToken: token,
                                         amountIn: BigInt(1_000_000), feePercent: fee)
        Check.that("quotes 1 ERTH into a live pool", quote != nil,
                   detail: "\(p.tokenDenom): out \(quote?.amountOut.description ?? "nil")")
    }

    let caretaker = await client.stream(.caretaker)
    Check.that("caretaker stream parses", !caretaker.options.isEmpty,
               detail: "\(caretaker.options.count) options, total weight \(caretaker.totalWeight)")

    let registered = await client.registrationCount()
    Check.that("registration count parses", registered >= 0, detail: "\(registered) humans")

    // An address that derives from a public test mnemonic and holds nothing.
    // Absent accounts are the normal case before a user's first receipt, and
    // the query has to report empty rather than throw.
    let empty = try! EarthKey(
        mnemonic: "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    ).address
    let balances = await client.balances(empty)
    Check.that(
        "an unfunded address reads as a balance map rather than failing",
        balances.values.allSatisfy { BigInt($0) != nil },
        detail: "\(balances)"
    )

    let proposals = await client.proposals(limit: 5)
    Check.that("gov proposals parse", proposals.allSatisfy { !$0.title.isEmpty },
               detail: "\(proposals.count) proposals")
}
