import BigInt
import EarthCore
import Foundation

func checkMath() {
    Check.group("swap quotes vs x/dex/keeper/amm.go")

    // Produced by calling the chain's own `swapHubForToken`, `swapTokenForHub`
    // and `feeOf` at swapFee 0.3, so these are the chain's answers rather than
    // a restatement of the same formula. Regenerate by dropping a test into
    // earth-network-chain/x/dex/keeper that prints them.
    struct Vector {
        let erth, token, amountIn: String
        let hubForToken: (out: String, fee: String)
        let tokenForHub: (out: String, fee: String)
    }
    let fee = Decimal(string: "0.3")!
    let vectors = [
        Vector(erth: "1000000000", token: "500000000", amountIn: "1000000",
               hubForToken: ("498003", "3000"), tokenForHub: ("1990019", "5988")),
        Vector(erth: "1000000000", token: "500000000", amountIn: "250000000",
               hubForToken: ("99759855", "750000"), tokenForHub: ("332333334", "999999")),
        // A single base unit: the fee truncates to zero, and the chain still
        // pays out. A quote that rounded the fee up would refuse this trade.
        Vector(erth: "123456789012", token: "987654321098", amountIn: "1",
               hubForToken: ("8", "0"), tokenForHub: ("0", "0")),
        Vector(erth: "123456789012", token: "987654321098", amountIn: "7777777",
               hubForToken: ("62031656", "23333"), tokenForHub: ("969298", "2916")),
        // Nearly draining a tiny pool — the region where truncation shows.
        Vector(erth: "1000000", token: "1000000", amountIn: "999999",
               hubForToken: ("499248", "2999"), tokenForHub: ("498500", "1499")),
    ]

    for v in vectors {
        let erth = BigInt(v.erth)!, token = BigInt(v.token)!, amountIn = BigInt(v.amountIn)!
        let label = "\(v.erth)/\(v.token) in \(v.amountIn)"

        let hub = SwapMath.hubForToken(reserveErth: erth, reserveToken: token,
                                       amountIn: amountIn, feePercent: fee)
        Check.equal("ERTH->token out  \(label)", hub?.amountOut.description, v.hubForToken.out)
        Check.equal("ERTH->token fee  \(label)", hub?.feeErth.description, v.hubForToken.fee)

        let spoke = SwapMath.tokenForHub(reserveErth: erth, reserveToken: token,
                                         amountIn: amountIn, feePercent: fee)
        Check.equal("token->ERTH out  \(label)", spoke?.amountOut.description, v.tokenForHub.out)
        Check.equal("token->ERTH fee  \(label)", spoke?.feeErth.description, v.tokenForHub.fee)
    }

    Check.that("refuses a zero input",
               SwapMath.hubForToken(reserveErth: 1000, reserveToken: 1000,
                                    amountIn: 0, feePercent: fee) == nil)
    Check.that("refuses an empty pool",
               SwapMath.tokenForHub(reserveErth: 1000, reserveToken: 0,
                                    amountIn: 10, feePercent: fee) == nil)

    Check.group("pool volume")

    // Nothing to decay any more: the chain returns 14-day-weighted volume in
    // real uerth, and the client reads it. These checks exist to stop the
    // client-side decay coming back — it was reintroducing chain arithmetic
    // that the chain had already stopped performing.
    let pool = Dex.Pool(id: 1, erthReserve: "1000000", tokenDenom: "uanml",
                        tokenReserve: "1000000", volumeErth: "1000000", lastTradedDay: 100)
    Check.equal("read as-is", AprMath.volumeErth(pool).description, "1000000")
    Check.equal(
        "an old last-traded day changes nothing",
        AprMath.volumeErth(
            Dex.Pool(id: 1, erthReserve: "1", tokenDenom: "u", tokenReserve: "1",
                     volumeErth: "500", lastTradedDay: 1)
        ).description,
        "500"
    )
    Check.equal(
        "a missing figure is zero, not a crash",
        AprMath.volumeErth(
            Dex.Pool(id: 1, erthReserve: "1", tokenDenom: "u", tokenReserve: "1",
                     volumeErth: "", lastTradedDay: 0)
        ).description,
        "0"
    )

    Check.group("staking apr")
    // 1 ERTH/sec over a year against the bonded total, with nothing skimmed.
    Check.equal("31.536m ERTH/yr over 1m ERTH bonded",
                StakingApr.base(bondedUerth: 1_000_000_000_000).map { ($0 * 1000).rounded() / 1000 },
                31.536)
    Check.that("nothing bonded has no honest rate", StakingApr.base(bondedUerth: 0) == nil)
    Check.equal("10% commission takes a tenth",
                StakingApr.forValidator(bondedUerth: 1_000_000_000_000, commission: 0.10)
                    .map { ($0 * 1000).rounded() / 1000 },
                28.382)

    Check.group("amount conversion")
    Check.equal("1.5 -> base units", Amounts.toBaseUnits("1.5")?.description, "1500000")
    Check.equal("bare integer", Amounts.toBaseUnits("42")?.description, "42000000")
    Check.equal("leading point", Amounts.toBaseUnits(".25")?.description, "250000")
    Check.equal("truncates past six places", Amounts.toBaseUnits("0.1234567")?.description, "123456")
    Check.that("rejects text", Amounts.toBaseUnits("1.2.3") == nil)
    Check.that("rejects letters", Amounts.toBaseUnits("12a") == nil)
    Check.equal("round trip", Amounts.fromBaseUnits(BigInt(1_500_000)), "1.5")
    Check.equal("drops trailing zeros", Amounts.fromBaseUnits(BigInt(42_000_000)), "42")
    Check.equal("sub-unit", Amounts.fromBaseUnits(BigInt(1)), "0.000001")
    Check.equal("filters a comma to a point", Amounts.filterAmountInput("1,5", previous: "1"), "1.5")
    Check.equal("keeps only the first point", Amounts.filterAmountInput("1.5.2", previous: "1.5"), "1.52")
    Check.equal("refuses a seventh decimal",
                Amounts.filterAmountInput("0.1234567", previous: "0.123456"), "0.123456")
}
