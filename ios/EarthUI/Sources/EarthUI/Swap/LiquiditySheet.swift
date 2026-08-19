import BigInt
import EarthCore
import SwiftUI

/// The pools, and what they pay.
///
/// Reached from the swap tab rather than from Earn: this is the same market
/// seen from the other side, and staking is a different thing entirely.
struct LiquiditySheet: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
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
                        Text("Estimates from a snapshot. The emission half falls as a pool grows — a deposit does not change its share of the stream, it splits the same emission across more capital.")
                            .font(EarthType.bodySmall)
                            .foregroundStyle(theme.colors.textTertiary)
                            .padding(.top, theme.space.x16)
                    }
                }
                .padding(theme.space.gutter)
            }
            .navigationTitle("Liquidity")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } } }
            .background(theme.colors.bgPrimary)
            .scrollContentBackground(.hidden)
        }
    }
}

struct PoolRow: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    let pool: Dex.Pool

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
                badgeForeground: theme.colors.accentInk
            )
            Text("\(Figures.amount(pool.erthReserve, .erth)) · \(Figures.amount(pool.tokenReserve, token))")
                .font(EarthType.caption)
                .foregroundStyle(theme.colors.textTertiary)
                .padding(.bottom, theme.space.x12)
        }
    }

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
