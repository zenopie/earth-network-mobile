import BigInt
import EarthCore
import SwiftUI

/// The hub-and-spoke AMM, one hop at a time.
///
/// Every pool pairs ERTH with one token, so a swap is either ERTH -> token or
/// token -> ERTH. Two spoke tokens would be two hops, and this screen does not
/// offer them: routing a multi-hop trade means quoting the second hop against
/// reserves the first hop has already moved, and getting that subtly wrong
/// shows up as a failed minimum-out rather than as a bad number on screen.
struct SwapScreen: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    @Environment(TxController.self) private var tx

    @State private var spoke = Token.anml
    @State private var buyingSpoke = true
    @State private var amount = ""
    /// How much worse than the quote the trade may land before the chain
    /// refuses it.
    @State private var slippagePercent = 1.0

    var body: some View {
        EarthScreen(title: "Swap") {
            if model.pools.isEmpty {
                EarthEmpty(
                    systemName: "arrow.left.arrow.right",
                    title: "No pools yet",
                    detail: "Nothing has been listed against ERTH on this chain."
                )
            } else {
                pair
                amountField
                quoteCard
                EarthButton(title: "Review swap") { review() }
                    .disabled(quote == nil)
            }
        }
        // ANML is the default because it is the token this chain is about, but
        // it need not have a pool on every deployment — land on one that does
        // rather than on a picker with nothing behind it.
        .onChange(of: model.pools) { _, _ in adoptAvailableSpoke() }
        .task { adoptAvailableSpoke() }
    }

    private func adoptAvailableSpoke() {
        guard !spokes.isEmpty, !spokes.contains(spoke), let first = spokes.first else { return }
        spoke = first
    }

    private var spokes: [Token] {
        model.pools.compactMap { pool in
            Token.named(pool.tokenDenom) ?? Token.unknown(denom: pool.tokenDenom)
        }
    }

    private var pair: some View {
        VStack(alignment: .leading, spacing: theme.space.x12) {
            EarthCard {
                HStack {
                    VStack(alignment: .leading, spacing: theme.space.x2) {
                        EarthLabel("From")
                        Text(fromToken.symbol)
                            .font(EarthType.headline)
                            .foregroundStyle(theme.colors.textPrimary)
                    }
                    Spacer()
                    Button {
                        buyingSpoke.toggle()
                        amount = ""
                    } label: {
                        Image(systemName: "arrow.left.arrow.right")
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundStyle(theme.colors.accentInk)
                            .frame(width: 40, height: 40)
                            .background(theme.colors.accentTint, in: .circle)
                    }
                    Spacer()
                    VStack(alignment: .trailing, spacing: theme.space.x2) {
                        EarthLabel("To")
                        Text(toToken.symbol)
                            .font(EarthType.headline)
                            .foregroundStyle(theme.colors.textPrimary)
                    }
                }
            }

            if spokes.count > 1 {
                Picker("Token", selection: $spoke) {
                    ForEach(spokes) { Text($0.symbol).tag($0) }
                }
                .pickerStyle(.segmented)
            }
        }
    }

    private var amountField: some View {
        EarthCard {
            HStack {
                EarthLabel("You pay")
                Spacer()
                Button("Max") { amount = fromToken.format(model.balance(fromToken)) }
                    .font(EarthType.eyebrow)
                    .foregroundStyle(theme.colors.accentInk)
            }
            HStack {
                TextField("0", text: $amount)
                    .font(EarthType.display)
                    .keyboardType(.decimalPad)
                    .onChange(of: amount) { previous, new in
                        amount = Amounts.filterAmountInput(new, previous: previous)
                    }
                Text(fromToken.symbol)
                    .font(EarthType.title)
                    .foregroundStyle(theme.colors.textTertiary)
            }
            Text("Balance \(fromToken.format(model.balance(fromToken)))")
                .font(EarthType.bodySmall)
                .foregroundStyle(theme.colors.textTertiary)
        }
    }

    @ViewBuilder
    private var quoteCard: some View {
        if let quote {
            EarthCard {
                EarthDetailRow(
                    label: "You receive",
                    value: "\(toToken.format(quote.amountOut)) \(toToken.symbol)",
                    emphasis: true
                )
                Divider().overlay(theme.colors.strokeSecondary)
                // Always in ERTH, whichever way the trade goes: buying the
                // token it comes off the input before the curve, selling it off
                // the ERTH output after.
                EarthDetailRow(label: "Fee", value: "\(Token.erth.format(quote.feeErth)) ERTH")
                EarthDetailRow(label: "Price impact", value: impactText(quote.priceImpact))
                EarthDetailRow(label: "Minimum received", value: "\(toToken.format(minimumOut)) \(toToken.symbol)")

                VStack(alignment: .leading, spacing: theme.space.x4) {
                    HStack {
                        EarthLabel("Slippage tolerance")
                        Spacer()
                        Text(String(format: "%.1f%%", slippagePercent))
                            .font(EarthType.amount)
                            .foregroundStyle(theme.colors.textPrimary)
                    }
                    Slider(value: $slippagePercent, in: 0.1 ... 5, step: 0.1)
                        .tint(Palette.Brand.b600)
                }

                if quote.priceImpact > 0.05 {
                    // These pools are small: a trade that would be invisible on
                    // a deep market can move this one several percent, and the
                    // quote alone does not say whether the number is the
                    // market's doing or your own.
                    HStack(alignment: .top, spacing: theme.space.x8) {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .foregroundStyle(theme.colors.warnInk)
                        Text("This trade moves the price against you by \(impactText(quote.priceImpact)).")
                            .font(EarthType.bodySmall)
                            .foregroundStyle(theme.colors.textSecondary)
                    }
                    .padding(theme.space.x12)
                    .background(theme.colors.warnTint, in: .rect(cornerRadius: theme.space.radiusMd))
                }
            }
        }
    }

    // MARK: - maths

    private var fromToken: Token { buyingSpoke ? .erth : spoke }
    private var toToken: Token { buyingSpoke ? spoke : .erth }

    private var pool: Dex.Pool? { model.pool(for: spoke) }

    /// Priced the way the chain prices it — `SwapMath` mirrors
    /// `x/dex/keeper/amm.go`, including the truncation. A rounded quote would
    /// promise a fraction of a unit more than the chain pays, which is exactly
    /// the direction that makes a minimum-out check fail.
    private var quote: SwapQuote? {
        guard let pool,
              let reserveErth = BigInt(pool.erthReserve),
              let reserveToken = BigInt(pool.tokenReserve),
              let amountIn = fromToken.parse(amount), amountIn > 0,
              amountIn <= model.balance(fromToken)
        else { return nil }

        return buyingSpoke
            ? SwapMath.hubForToken(reserveErth: reserveErth, reserveToken: reserveToken,
                                   amountIn: amountIn, feePercent: model.swapFeePercent)
            : SwapMath.tokenForHub(reserveErth: reserveErth, reserveToken: reserveToken,
                                   amountIn: amountIn, feePercent: model.swapFeePercent)
    }

    /// The floor sent to the chain. Truncated, not rounded, for the same reason
    /// the quote is: a minimum that rounds up is a minimum the chain can miss.
    private var minimumOut: BigInt {
        guard let quote else { return 0 }
        let scale = BigInt(10_000)
        let keep = scale - BigInt(Int(slippagePercent * 100))
        return quote.amountOut * keep / scale
    }

    private func impactText(_ value: Double) -> String {
        value < 0.0001 ? "<0.01%" : String(format: "%.2f%%", value * 100)
    }

    private func review() {
        guard let quote, let amountIn = fromToken.parse(amount) else { return }
        let inDenom = fromToken.denom
        let outDenom = toToken.denom
        let floor = minimumOut

        tx.request(.init(
            action: "Swap",
            rows: [
                ("You pay", "\(fromToken.format(amountIn)) \(fromToken.symbol)"),
                ("You receive", "\(toToken.format(quote.amountOut)) \(toToken.symbol)"),
                ("Minimum", "\(toToken.format(floor)) \(toToken.symbol)"),
                ("Fee", "\(Token.erth.format(quote.feeErth)) ERTH"),
                ("Price impact", impactText(quote.priceImpact)),
            ]
        ), onSuccess: { amount = "" }) { key in
            [model.client.msgSwap(
                creator: key.address,
                tokenInDenom: inDenom,
                tokenInAmount: String(amountIn),
                denomOut: outDenom,
                minAmountOut: String(floor)
            )]
        }
    }
}
