import Foundation

/// x/dex — the hub-and-spoke AMM. Every pool pairs ERTH with one token.
public enum Dex {

    /// Days in the rolling volume window. Mirrors `types.VolumeWindowDays`.
    public static let volumeWindowDays = 7

    /// The LP share denom for a pool. Mirrors `types.LPShareDenom`.
    public static func shareDenom(poolID: UInt64) -> String { "dexlp/\(poolID)" }

    public struct Pool: Sendable, Equatable, Identifiable {
        public let id: UInt64
        /// uerth.
        public let erthReserve: String
        public let tokenDenom: String
        public let tokenReserve: String
        /// ERTH-denominated swap volume, decayed daily over a rolling window.
        ///
        /// Not a plain total: each elapsed day multiplies it by 6/7, so at a
        /// steady rate it settles at roughly seven times the daily volume. It
        /// weights this pool's share of the LP reward stream, and an APR
        /// estimate has to work back from it.
        public let volume: String
        /// Day index the volume was last decayed to (block time / 86400).
        public let lastVolumeDay: Int64

        public init(
            id: UInt64,
            erthReserve: String,
            tokenDenom: String,
            tokenReserve: String,
            volume: String = "0",
            lastVolumeDay: Int64 = 0
        ) {
            self.id = id
            self.erthReserve = erthReserve
            self.tokenDenom = tokenDenom
            self.tokenReserve = tokenReserve
            self.volume = volume
            self.lastVolumeDay = lastVolumeDay
        }
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
                volume: p.volume.string(default: "0"),
                lastVolumeDay: p.last_volume_day.int64(default: 0)
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

    func msgAddLiquidity(
        creator: String,
        poolID: UInt64,
        denomA: String, amountA: String,
        denomB: String, amountB: String
    ) -> ProtoAny {
        Msg.AddLiquidity(
            creator: creator,
            poolID: poolID,
            amountA: Coin(denom: denomA, amount: amountA),
            amountB: Coin(denom: denomB, amount: amountB)
        ).asAny(typeURL: Msg.AddLiquidity.typeURL)
    }

    func msgRemoveLiquidity(
        creator: String,
        poolID: UInt64,
        sharesDenom: String,
        sharesAmount: String
    ) -> ProtoAny {
        Msg.RemoveLiquidity(
            creator: creator,
            poolID: poolID,
            shares: Coin(denom: sharesDenom, amount: sharesAmount)
        ).asAny(typeURL: Msg.RemoveLiquidity.typeURL)
    }
}
