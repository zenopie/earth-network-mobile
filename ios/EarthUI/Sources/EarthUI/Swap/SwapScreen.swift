import BigInt
import EarthCore
import SwiftUI

/// Swap, against the chain's pools.
///
/// Two stacked panels for what goes in and what comes out, with a circular
/// flip button straddling the seam. The button is the reason the panels are
/// stacked rather than side by side — it has to sit on the boundary to read as
/// reversing it.
///
/// Only ERTH/ANML. It is the one pool, and pairing arbitrary spokes would need
/// a two-hop quote through the hub that this screen has no way to let you
/// choose yet.
struct SwapScreen: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    @Environment(TxController.self) private var tx

    @State private var erthIn = true
    @State private var amount = ""
    @State private var slippageBps = SwapScreen.defaultSlippageBps

    /// The reverse button's diameter, needed to centre it on the seam.
    private static let reverseSize: CGFloat = 48
    /// What a swap costs at the node's minimum gas price. Derived from the gas
    /// the swap actually broadcasts with rather than a copy of the number: this
    /// is subtracted from the spendable balance, so a stale value lets the user
    /// spend past what the fee needs.
    private static var feeUerth: BigInt {
        BigInt(TransactionSigner.defaultFeeUerth) ?? 0
    }
    /// Tolerances offered, in basis points.
    ///
    /// A short list rather than a free-text field. The useful range is narrow,
    /// the failure modes at each end are opposite — too tight and it never
    /// fills, too loose and a reordering takes the difference — and neither is
    /// obvious from a number typed into a box.
    private static let slippageChoices = [50, 100, 300]
    private static let defaultSlippageBps = 100

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Spacer().frame(height: theme.space.x16)
                panels
                details
                Spacer().frame(height: theme.space.x24)
                EarthButton(title: "Review swap") { review() }
                    .disabled(quote == nil || (quote?.amountOut ?? 0) <= 0)
                // No pool list here. Reserves and LP shares are what a
                // liquidity provider needs; someone swapping needs the rate,
                // the fee and what they get, all of which are above. Pools are
                // one tap away in the bar.
                Spacer().frame(height: theme.space.x32)
            }
            .padding(.horizontal, theme.space.gutter)
        }
        .refreshable { await model.refresh() }
        .background(theme.colors.bgPrimary)
        .scrollContentBackground(.hidden)
        .scrollDismissesKeyboard(.interactively)
    }

    private var panels: some View {
        VStack(spacing: theme.space.x8) {
            payPanel
                // Hung off the paying panel's bottom edge rather than placed
                // by measuring it. The seam is that edge plus half the gap
                // whatever the panel's height, and the amount chips change
                // that height — so measuring is both unnecessary and the part
                // that breaks.
                .overlay(alignment: .bottom) { reverseButton }
                // Over the panel below, so the ring covers the seam rather
                // than being painted under it.
                .zIndex(1)
            receivePanel
        }
    }

    private var payPanel: some View {
        SwapPanel(
            label: "You pay",
            symbol: fromToken.symbol,
            icon: fromToken == .erth ? EarthAsset.erthLogo : EarthAsset.anml,
            balance: model.balancesVisible ? Figures.plain(fromUnits) : "---",
            value: $amount,
            spendable: spendable,
            onFraction: { numerator, denominator in
                let units = spendable * BigInt(numerator) / BigInt(denominator)
                amount = units > 0 ? Amounts.fromBaseUnits(units) : ""
            }
        )
    }

    private var receivePanel: some View {
        SwapPanel(
            label: "You receive",
            symbol: toToken.symbol,
            icon: toToken == .erth ? EarthAsset.erthLogo : EarthAsset.anml,
            balance: model.balancesVisible ? Figures.plain(toUnits) : "---",
            readOnlyValue: quote.map { Amounts.fromBaseUnits($0.amountOut) } ?? ""
        )
    }

    /// Straddles the seam: half its height in each panel, which is what makes
    /// it read as reversing them rather than as an action on the panel above.
    /// The ring is the page colour, so it punches a hole through the seam
    /// instead of sitting on top of it.
    private var reverseButton: some View {
        Button {
            erthIn.toggle()
        } label: {
            VectorGlyph(pathData: TabGlyphPaths.swapVertical)
                .foregroundStyle(theme.colors.textPrimary)
                .frame(width: 18, height: 18)
                .frame(width: Self.reverseSize - 6, height: Self.reverseSize - 6)
                .background(theme.colors.bgSecondary, in: .circle)
                .overlay { Circle().strokeBorder(theme.colors.strokeSecondary, lineWidth: 1) }
                .padding(3)
                .background(theme.colors.bgPrimary, in: .circle)
        }
        .buttonStyle(.plain)
        // Aligned bottom-to-bottom, so this moves its centre down to the
        // panel's edge and then half the gap further.
        .offset(y: Self.reverseSize / 2 + theme.space.x8 / 2)
    }

    @ViewBuilder
    private var details: some View {
        if let quote {
            Spacer().frame(height: theme.space.x16)
            EarthDetailRow(label: "Fee", value: "\(Amounts.fromBaseUnits(quote.feeErth)) ERTH")
            EarthDetailRow(label: "Price impact",
                           value: String(format: "%.2f%%", quote.priceImpact * 100))
            // The number that actually goes on chain. The quote above is what
            // the pool would pay right now; this is the floor the transaction
            // refuses to go below, and it is the only one of the two that is
            // enforced — so it is worth showing rather than leaving implied by
            // a tolerance setting.
            EarthDetailRow(
                label: "Minimum received",
                value: "\(Amounts.fromBaseUnits(minimumOut)) \(toToken.symbol)"
            )

            Spacer().frame(height: theme.space.x12)
            HStack(spacing: theme.space.x8) {
                Text("Max slippage")
                    .font(EarthType.bodySmall)
                    .foregroundStyle(theme.colors.textTertiary)
                Spacer()
                ForEach(Self.slippageChoices, id: \.self) { bps in
                    SlippageChip(bps: bps, selected: bps == slippageBps) { slippageBps = bps }
                }
            }

            // Small pools move a long way on an ordinary trade, so a tolerance
            // under the impact the quote already shows will simply fail. Said
            // here rather than left to the chain, which reports it as a
            // rejected transaction after the fee is spent.
            if quote.priceImpact * 10_000 > Double(slippageBps) {
                Spacer().frame(height: theme.space.x8)
                Text("This trade moves the price more than your tolerance allows, so it will be rejected. Raise the tolerance or trade a smaller amount.")
                    .font(EarthType.caption)
                    .foregroundStyle(theme.colors.textError)
            }
        }
    }

    // MARK: - the trade

    private var fromToken: Token { erthIn ? .erth : .anml }
    private var toToken: Token { erthIn ? .anml : .erth }
    private var fromUnits: BigInt { model.balance(fromToken) }
    private var toUnits: BigInt { model.balance(toToken) }

    /// What can actually be swapped.
    ///
    /// The fee is always paid in ERTH, so selling ERTH has to leave it behind —
    /// a "max" that spends the fee too produces a transaction the ante handler
    /// rejects, which costs a round trip to discover. Selling ANML, the whole
    /// balance is available and the ERTH for the fee has to already be there.
    private var spendable: BigInt {
        erthIn ? max(0, fromUnits - Self.feeUerth) : fromUnits
    }

    private var pool: Dex.Pool? { model.pool(for: .anml) }

    private var quote: SwapQuote? {
        guard let pool,
              let input = Token.erth.parse(amount), input > 0,
              let erth = BigInt(pool.erthReserve),
              let token = BigInt(pool.tokenReserve)
        else { return nil }

        return erthIn
            ? SwapMath.hubForToken(reserveErth: erth, reserveToken: token,
                                   amountIn: input, feePercent: model.swapFeePercent)
            : SwapMath.tokenForHub(reserveErth: erth, reserveToken: token,
                                   amountIn: input, feePercent: model.swapFeePercent)
    }

    /// The floor the swap will accept, given a tolerance in basis points.
    ///
    /// The quote is computed against reserves read a moment ago, and anything
    /// landing in a block before this one moves them. Without a floor the chain
    /// fills at whatever price results; with the floor set at the quote itself,
    /// an unrelated transaction in the same block fails the swap.
    ///
    /// Truncating division, so rounding always moves the floor down. Rounding
    /// up would quote a minimum the chain might refuse by a single unit.
    private var minimumOut: BigInt {
        guard let quote else { return 0 }
        return quote.amountOut * BigInt(10_000 - slippageBps) / BigInt(10_000)
    }

    private func review() {
        guard let input = Token.erth.parse(amount), let quote, quote.amountOut > 0 else { return }
        let inDenom = fromToken.denom
        let outDenom = toToken.denom
        let floor = minimumOut
        let paying = "\(Figures.whole(input)) \(fromToken.symbol)"

        tx.request(.init(
            action: "Swap",
            rows: [
                ("You pay", paying),
                ("You receive", "\(Amounts.fromBaseUnits(quote.amountOut)) \(toToken.symbol)"),
                ("Minimum", "\(Amounts.fromBaseUnits(floor)) \(toToken.symbol)"),
                ("Fee", "\(Amounts.fromBaseUnits(quote.feeErth)) ERTH"),
            ]
        ), onSuccess: { amount = "" }) { key in
            [model.client.msgSwap(
                creator: key.address,
                tokenInDenom: inDenom,
                tokenInAmount: String(input),
                denomOut: outDenom,
                minAmountOut: String(floor)
            )]
        }
    }
}

/// One side of the trade.
struct SwapPanel: View {
    @Environment(\.earth) private var theme

    let label: String
    let symbol: String
    /// The token's own mark, in its own colours — never tinted.
    let icon: Image?
    let balance: String

    var value: Binding<String>?
    /// Non-nil on the paying side, with the fee already set aside.
    var spendable: BigInt?
    var onFraction: ((Int, Int) -> Void)?
    var readOnlyValue: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                EarthLabel(label)
                Spacer()
                Text("Balance \(balance)")
                    .font(EarthType.caption)
                    .foregroundStyle(theme.colors.textTertiary)
            }

            Spacer().frame(height: theme.space.x8)

            HStack(spacing: 0) {
                if let value {
                    TextField("0", text: value)
                        .font(EarthType.body).fontWeight(.medium)
                        .keyboardType(.decimalPad)
                        .onChange(of: value.wrappedValue) { previous, new in
                            value.wrappedValue = Amounts.filterAmountInput(new, previous: previous)
                        }
                } else {
                    Text(readOnlyValue?.isEmpty == false ? readOnlyValue! : "0")
                        .font(EarthType.headline)
                        .foregroundStyle(theme.colors.textTertiary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                Spacer().frame(width: theme.space.x12)
                icon?.resizable().scaledToFit().frame(width: 24, height: 24)
                Spacer().frame(width: theme.space.x8)
                Text(symbol)
                    .font(EarthType.body).fontWeight(.semibold)
                    .foregroundStyle(theme.colors.textPrimary)
            }

            // Only on the side being spent, and only when there is something
            // to spend. Shortcuts over a zero balance are two controls that do
            // nothing, on the screen where a new wallet spends most of its
            // time.
            if let spendable, spendable > 0 {
                Spacer().frame(height: theme.space.x12)
                HStack(spacing: theme.space.x8) {
                    AmountChip(label: "50%") { onFraction?(1, 2) }
                    AmountChip(label: "Max") { onFraction?(1, 1) }
                }
            }
        }
        .padding(theme.space.x16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(theme.colors.bgSecondary, in: .rect(cornerRadius: 20))
    }
}

struct AmountChip: View {
    @Environment(\.earth) private var theme
    let label: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(label)
                .font(EarthType.caption).fontWeight(.semibold)
                .foregroundStyle(theme.colors.secondaryButtonFg)
                .padding(.horizontal, theme.space.x12)
                .padding(.vertical, theme.space.x4)
                .background(theme.colors.secondaryButtonBg, in: .capsule)
        }
        .buttonStyle(.plain)
    }
}

struct SlippageChip: View {
    @Environment(\.earth) private var theme
    let bps: Int
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(bps % 100 == 0 ? "\(bps / 100)%" : String(format: "%.1f%%", Double(bps) / 100))
                .font(EarthType.caption)
                .fontWeight(selected ? .semibold : .regular)
                .foregroundStyle(selected ? theme.colors.secondaryButtonFg : theme.colors.textTertiary)
                .padding(.horizontal, theme.space.x12)
                .padding(.vertical, theme.space.x4)
                .background(
                    selected ? theme.colors.secondaryButtonBg : theme.colors.bgSecondary,
                    in: .capsule
                )
        }
        .buttonStyle(.plain)
    }
}
