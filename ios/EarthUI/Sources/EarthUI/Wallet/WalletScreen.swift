import BigInt
import EarthCore
import SwiftUI

/// What this wallet holds, and the two things only a registered human can do.
struct WalletScreen: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    @Environment(TxController.self) private var tx

    @State private var sending = false
    @State private var receiving = false
    @State private var registering = false

    var body: some View {
        EarthScreen {
            balance
            actions
            holdings
        }
        .sheet(isPresented: $sending) { SendSheet().earthThemed() }
        .sheet(isPresented: $receiving) { ReceiveSheet().earthThemed() }
    }

    /// The balance is the loudest thing in the app by a wide margin, which is
    /// most of what makes a wallet feel simple: one number you cannot miss,
    /// everything else stepping well back.
    ///
    /// Whole ERTH, with thousands separators. The fractions live in the detail
    /// rows — six decimal places at 56pt is a number nobody reads.
    private var balance: some View {
        VStack(alignment: .leading, spacing: 0) {
            EarthLabel("Total balance")
            Text(model.balancesVisible ? whole : "••••")
                .font(EarthType.display)
                .foregroundStyle(theme.colors.textPrimary)
                .lineLimit(1)
                .minimumScaleFactor(0.4)
            Text(model.address)
                .font(EarthType.bodySmall)
                .foregroundStyle(theme.colors.textTertiary)
                .lineLimit(1)
                .truncationMode(.middle)
        }
    }

    private var whole: String { Figures.whole(model.balance(.erth)) }

    /// Stacked, not side by side. Send is the brand action and Receive is the
    /// quieter one; a row of two equal halves says they are equally likely,
    /// and they are not.
    private var actions: some View {
        VStack(spacing: theme.space.x8) {
            EarthButton(title: "Send") { sending = true }
                .disabled(model.holdings.allSatisfy { $0.amount == 0 })
            EarthButton(title: "Receive", role: .secondary) { receiving = true }
        }
        .padding(.top, theme.space.x8)
    }

    /// Flat rows on hairlines rather than a card of tinted circles. A balance
    /// sheet should read as a list of figures.
    private var holdings: some View {
        VStack(alignment: .leading, spacing: 0) {
            EarthLabel("Holdings")
            Spacer().frame(height: theme.space.x8)

            EarthRow(
                title: "ANML",
                subtitle: model.isRegistered ? "Proof of personhood" : "Not registered",
                value: figure(Figures.whole(model.balance(.anml), decimals: Token.anml.decimals)),
                action: { registering = !model.isRegistered ? true : registering }
            )

            if model.totalStaked > 0 {
                EarthDivider()
                EarthRow(title: "Staked", subtitle: "Delegated",
                         value: figure(Figures.whole(model.totalStaked)))
            }
            if model.rewards > 0 {
                EarthDivider()
                EarthRow(title: "Rewards", subtitle: "Claimable",
                         value: figure(Figures.precise(model.rewards)))
            }

            // Anything else the account holds — a token added to the chain
            // after this build, or an LP share. Shown by its denom rather than
            // hidden: a balance the wallet will not name is worse than an ugly
            // one.
            ForEach(other, id: \.token.denom) { row in
                EarthDivider()
                EarthRow(title: row.token.symbol, subtitle: row.token.denom,
                         value: figure(Figures.whole(row.amount, decimals: row.token.decimals)))
            }

            if model.canClaimAnml {
                Spacer().frame(height: theme.space.x16)
                EarthButton(title: "Claim today's ANML") { claim() }
            }
        }
        .padding(.top, theme.space.x16)
        .sheet(isPresented: $registering) { RegistrationSheet().earthThemed() }
    }

    private var other: [(token: Token, amount: BigInt)] {
        model.holdings.filter { $0.token != .erth && $0.token != .anml && $0.amount > 0 }
    }

    private func figure(_ text: String) -> String {
        model.balancesVisible ? text : "••••"
    }

    private func claim() {
        tx.request(.init(action: "Claim", rows: [("Token", "ANML"), ("Amount", "1 ANML")])) { key in
            [model.client.msgClaimAnml(creator: key.address)]
        }
    }
}


