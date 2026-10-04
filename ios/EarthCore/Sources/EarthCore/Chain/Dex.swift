import BigInt
import Foundation

/// x/dex — the hub-and-spoke AMM. Every pool pairs ERTH with one token.
public enum Dex {

    /// Days in the rolling volume window. Mirrors `types.VolumeDecayWindowDays`.
    public static let volumeWindowDays = 14

    /// The LP share denom for a pool. Mirrors `types.LPShareDenom`.
    public static func shareDenom(poolID: UInt64) -> String { "dexlp/\(poolID)" }

    public struct Pool: Sendable, Equatable, Identifiable {
        public let id: UInt64
        /// uerth.
        public let erthReserve: String
        public let tokenDenom: String
        public let tokenReserve: String
        /// 14-day-weighted swap volume, in real uerth.
        ///
        /// The chain does the weighting and returns a plain number: a trade a
        /// week ago counts (13/14)^7 of one made today. Nothing here ages it,
        /// and nothing here should try — the wallet used to decay this
        /// client-side against a mechanism the chain had already replaced, and
        /// the fee APR drifted further out every day as a result.
        ///
        /// At a steady trading rate it settles at about fourteen times the
        /// daily volume.
        public let volumeErth: String
        /// Day index this pool last traded (block time / 86400).
        public let lastTradedDay: Int64

        public init(
            id: UInt64,
            erthReserve: String,
            tokenDenom: String,
            tokenReserve: String,
            volumeErth: String = "0",
            lastTradedDay: Int64 = 0
        ) {
            self.id = id
            self.erthReserve = erthReserve
            self.tokenDenom = tokenDenom
            self.tokenReserve = tokenReserve
            self.volumeErth = volumeErth
            self.lastTradedDay = lastTradedDay
        }
    }

    /// The chain's own price for a swap: what it pays, and its uerth fee over every hop.
    public struct Simulated: Sendable, Equatable {
        public let amountOut: BigInt
        public let feeErth: BigInt
    }

    /// The token only shields hold: its pool's legs are note paths
    /// (MsgNoteSwap, MsgAddLiquidityShielded).
    public static let shieldedOnly = "uanml"

    /// The REST response body of SimulateSwapExactIn, or nil if it is not one.
    public static func parseSimulated(_ j: JSON) -> Simulated? {
        guard let out = j.token_out.amount.string.flatMap({ BigInt($0) }),
              let fee = j.fee.amount.string.flatMap({ BigInt($0) }), out > 0 else { return nil }
        return Simulated(amountOut: out, feeErth: fee)
    }

    /// A withdrawal waiting out its escrow.
    public struct Unbonding: Sendable, Equatable {
        public let poolID: UInt64
        public let shares: String
        /// Unix seconds at which it pays out on its own.
        public let completionTime: Int64
    }
}

public extension EarthClient {

    func pools() async -> [Dex.Pool] {
        guard let json = try? await rest.get("/earth/dex/v1/pool") else { return [] }
        return json.pool.array.compactMap { p in
            Dex.Pool(
                id: p.pool_id.uint64(default: 0),
                erthReserve: p.reserve_erth.amount.string(default: "0"),
                tokenDenom: p.reserve_token.denom.string(default: ""),
                tokenReserve: p.reserve_token.amount.string(default: "0"),
                volumeErth: p.volume_erth.string(default: "0"),
                lastTradedDay: p.last_traded_day.int64(default: 0)
            )
        }
    }

    /// The pool pairing ERTH with the given spoke token, if there is one.
    func pool(forToken denom: String) async -> Dex.Pool? {
        await pools().first { $0.tokenDenom == denom }
    }

    /// Swap fee as a percent string, e.g. "0.3".
    func swapFeePercent() async -> String {
        guard let json = try? await rest.get("/earth/dex/v1/params") else { return "0" }
        return json.params.swap_fee.string(default: "0")
    }

    /// How long withdrawn shares are escrowed before they pay out.
    func lpUnbondingSeconds() async -> Int64 {
        guard let json = try? await rest.get("/earth/dex/v1/params") else { return 0 }
        return json.params.lp_unbonding_seconds.int64(default: 0)
    }

    /// Withdrawals this address has waiting.
    ///
    /// Between submitting one and it landing there is nothing in the balance to
    /// show for it — the shares have left and the assets have not arrived — so
    /// without this the week looks like the funds went nowhere.
    func unbondings(_ address: String) async -> [Dex.Unbonding] {
        guard let json = try? await rest.get("/earth/dex/v1/unbondings/\(address)")
        else { return [] }
        return json.unbondings.array.map { u in
            Dex.Unbonding(
                poolID: u.pool_id.uint64(default: 0),
                shares: u.shares.amount.string(default: "0"),
                completionTime: u.completion_time.int64(default: 0)
            )
        }
    }

    // --- messages ---

    func msgSwap(
        creator: String,
        tokenInDenom: String,
        tokenInAmount: String,
        denomOut: String,
        minAmountOut: String
    ) -> ProtoAny {
        Msg.Swap(
            creator: creator,
            tokenIn: Coin(denom: tokenInDenom, amount: tokenInAmount),
            denomOut: denomOut,
            minAmountOut: minAmountOut
        ).asAny(typeURL: Msg.Swap.typeURL)
    }

    /// `minShares`: the fewest shares the deposit accepts (field 5, audit 6
    /// M9), computed as the shielded deposit's; "" only for an empty pool.
    func msgAddLiquidity(
        creator: String,
        poolID: UInt64,
        denomA: String, amountA: String,
        denomB: String, amountB: String,
        minShares: String
    ) -> ProtoAny {
        Msg.AddLiquidity(
            creator: creator,
            poolID: poolID,
            amountA: Coin(denom: denomA, amount: amountA),
            amountB: Coin(denom: denomB, amount: amountB),
            minShares: minShares
        ).asAny(typeURL: Msg.AddLiquidity.typeURL)
    }

    /// x/dex SimulateSwapExactIn: the swap itself run at the current state
    /// (pending LP rewards settled into the reserves first) and discarded.
    /// Nil when the node does not serve it or refuses the swap; callers fall
    /// back to SwapMath over the pool reserves.
    func simulateSwapExactIn(offerDenom: String, offerAmount: BigInt, askDenom: String) async -> Dex.Simulated? {
        func enc(_ s: String) -> String { s.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? s }
        guard let j = try? await rest.get(
            "/earth/dex/v1/simulate_swap_exact_in?offer_denom=\(enc(offerDenom))&offer_amount=\(offerAmount)&ask_denom=\(enc(askDenom))"
        ) else { return nil }
        return Dex.parseSimulated(j)
    }

    /// `pc` (pool 1 only): the note the ANML leg is minted to at maturity
    /// (PrivacyWallet.withdrawalPC).
    func msgRemoveLiquidity(
        creator: String,
        poolID: UInt64,
        sharesDenom: String,
        sharesAmount: String,
        pc: Data = Data()
    ) -> ProtoAny {
        Msg.RemoveLiquidity(
            creator: creator,
            poolID: poolID,
            shares: Coin(denom: sharesDenom, amount: sharesAmount),
            pc: pc
        ).asAny(typeURL: Msg.RemoveLiquidity.typeURL)
    }
}
