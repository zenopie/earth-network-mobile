import BigInt
import EarthCore
import SwiftUI

/// The pools, and what they pay.
///
/// A list rather than a sheet: it sits inline under Earn's Liquidity selector,
/// so tapping a pool presents `PoolActionSheet` as a single presentation from a
/// tab. That is the whole reason it moved — as a sheet opened from Swap it put
/// the deposit sheet two deep, and a confirmation raised from there drew behind
/// both of them.
struct PoolList: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if model.pools.isEmpty {
                EarthEmpty(
                    systemName: "drop",
                    title: "No pools yet",
                    detail: "Nothing has been listed against ERTH on this chain."
                )
            } else {
                ForEach(model.pools) { pool in
                    PoolRow(pool: pool)
                    EarthDivider()
                }
            }

            if !model.lpUnbondings.isEmpty {
                Spacer().frame(height: theme.space.x24)
                EarthLabel("Withdrawing")
                // Listed apart from the pools, like unbonding stake is listed
                // apart from delegations. A withdrawal in escrow is not a
                // position any more — the shares have left the pool and the
                // assets have not arrived — and the only thing to know about it
                // is when it lands.
                ForEach(Array(model.lpUnbondings.enumerated()), id: \.offset) { _, entry in
                    EarthListRow(
                        initial: String(symbol(entry.poolID).prefix(1)),
                        name: "ERTH / \(symbol(entry.poolID))",
                        subtitle: "Returns \(returnDate(entry.completionTime))",
                        value: "\(Figures.balance(entry.shares)) shares",
                        badgeBackground: theme.colors.bgSecondary,
                        badgeForeground: theme.colors.textTertiary
                    )
                }
            }
        }
    }

    /// The pool's other side, by id. Falls back to the id itself — a pool that
    /// has been withdrawn from can have left the list this build knows about,
    /// and a row naming nothing is worse than one naming a number.
    private func symbol(_ poolID: UInt64) -> String {
        guard let pool = model.pools.first(where: { $0.id == poolID }) else { return "#\(poolID)" }
        return (Token.named(pool.tokenDenom) ?? Token.unknown(denom: pool.tokenDenom)).symbol
    }

    private func returnDate(_ unixSeconds: Int64) -> String {
        // Absolute, not "in 6 days": the payout is a date someone plans around,
        // and a relative figure has to be re-read against today every time.
        let date = Date(timeIntervalSince1970: TimeInterval(unixSeconds))
        return date.formatted(date: .abbreviated, time: .omitted)
    }
}

struct PoolRow: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    let pool: Dex.Pool

    /// Opening the add/withdraw sheet. The row is the only way in, so the pool
    /// list stopped being read-only the moment this appeared.
    @State private var acting = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            EarthListRow(
                initial: String(token.symbol.prefix(1)),
                name: "ERTH / \(token.symbol)",
                // Split apart because the two halves behave differently: fees
                // scale with the pool, emissions do not.
                subtitle: apr.map { "\(Figures.rate($0.fee)) fees · \(Figures.rate($0.emission)) emissions" },
                value: apr.map { Figures.rate($0.total) },
                badgeBackground: theme.colors.accentTint,
                badgeForeground: theme.colors.accentInk,
                action: { acting = true }
            )
            HStack {
                Text("\(Figures.amount(pool.erthReserve, .erth)) · \(Figures.amount(pool.tokenReserve, token))")
                    .font(EarthType.caption)
                    .foregroundStyle(theme.colors.textTertiary)
                if shares > 0 {
                    // Only when there is a position. A "your shares: 0" on every
                    // row is noise on the rows where it is 0, which is most.
                    Text("· \(Figures.whole(shares)) shares")
                        .font(EarthType.caption)
                        .foregroundStyle(theme.colors.accentInk)
                }
            }
            .padding(.bottom, theme.space.x12)
        }
        .sheet(isPresented: $acting) { PoolActionSheet(pool: pool).earthThemed() }
    }

    private var shares: BigInt { model.lpShares(poolID: pool.id) }

    private var token: Token {
        Token.named(pool.tokenDenom) ?? Token.unknown(denom: pool.tokenDenom)
    }

    private var apr: PoolApr? {
        AprMath.apr(
            for: pool,
            allPools: model.pools,
            lpOptionShare: model.lpOptionShare,
            swapFeePercent: model.swapFeePercent
        )
    }
}
