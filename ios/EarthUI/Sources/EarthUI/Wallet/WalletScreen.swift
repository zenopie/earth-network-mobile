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

    var body: some View {
        EarthScreen(title: "Wallet") {
            balanceCard
            actions
            PersonhoodCard()
            holdings
        }
        .sheet(isPresented: $sending) { SendSheet().earthThemed() }
        .sheet(isPresented: $receiving) { ReceiveSheet().earthThemed() }
    }

    /// The balance is the loudest thing in the app by a wide margin, which is
    /// most of what makes a wallet feel simple: one number you cannot miss.
    private var balanceCard: some View {
        VStack(alignment: .leading, spacing: theme.space.x4) {
            EarthLabel("Total balance")
            HStack(alignment: .firstTextBaseline, spacing: theme.space.x8) {
                Text(Token.erth.format(model.balance(.erth)))
                    .font(EarthType.display)
                    .foregroundStyle(theme.colors.textPrimary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
                Text("ERTH")
                    .font(EarthType.title)
                    .foregroundStyle(theme.colors.textTertiary)
            }
            if !model.hasGas {
                // Not an error: this is every new account, and the gas gate is
                // the answer to it.
                Text("No ERTH for fees yet.")
                    .font(EarthType.bodySmall)
                    .foregroundStyle(theme.colors.textTertiary)
            }
        }
        .padding(.top, theme.space.x8)
    }

    private var actions: some View {
        HStack(spacing: theme.space.x12) {
            EarthButton(title: "Send", role: .secondary) { sending = true }
                .disabled(model.balance(.erth) == 0 && model.holdings.allSatisfy { $0.amount == 0 })
            EarthButton(title: "Receive", role: .secondary) { receiving = true }
        }
    }

    private var holdings: some View {
        VStack(alignment: .leading, spacing: theme.space.x12) {
            EarthSectionHeader(title: "Holdings")
            EarthCard(padding: theme.space.x8) {
                ForEach(Array(model.holdings.enumerated()), id: \.element.token.denom) { index, row in
                    if index > 0 {
                        Divider().overlay(theme.colors.strokeSecondary)
                    }
                    TokenRow(token: row.token, amount: row.amount)
                }
            }
        }
    }
}

struct TokenRow: View {
    @Environment(\.earth) private var theme
    let token: Token
    let amount: BigInt

    var body: some View {
        HStack(spacing: theme.space.x12) {
            EarthGlyph(systemName: glyph)
            VStack(alignment: .leading, spacing: 0) {
                Text(token.symbol)
                    .font(EarthType.title)
                    .foregroundStyle(theme.colors.textPrimary)
                Text(token.denom)
                    .font(EarthType.bodySmall)
                    .foregroundStyle(theme.colors.textTertiary)
            }
            Spacer()
            Text(token.format(amount))
                .font(EarthType.amount)
                .foregroundStyle(amount > 0 ? theme.colors.textPrimary : theme.colors.textDisabled)
        }
        .padding(.vertical, theme.space.x8)
        .padding(.horizontal, theme.space.x8)
    }

    private var glyph: String {
        switch token.symbol {
        case "ERTH": "globe.europe.africa.fill"
        case "ANML": "pawprint.fill"
        default: token.symbol.hasPrefix("LP ") ? "drop.fill" : "circle.hexagongrid.fill"
        }
    }
}

/// Registration, and the daily claim it unlocks.
///
/// On the wallet tab rather than behind its own because for an unregistered
/// person it is the only thing to do, and for a registered one the claim is the
/// thing they open the app for.
struct PersonhoodCard: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    @Environment(TxController.self) private var tx
    @State private var registering = false

    var body: some View {
        EarthCard {
            HStack(spacing: theme.space.x12) {
                EarthGlyph(systemName: model.isRegistered ? "checkmark.seal.fill" : "person.badge.key.fill")
                VStack(alignment: .leading, spacing: theme.space.x2) {
                    Text(model.isRegistered ? "Registered human" : "Not registered")
                        .font(EarthType.title)
                        .foregroundStyle(theme.colors.textPrimary)
                    Text(subtitle)
                        .font(EarthType.bodySmall)
                        .foregroundStyle(theme.colors.textTertiary)
                }
                Spacer()
            }

            if model.isRegistered {
                EarthButton(
                    title: model.canClaimAnml ? "Claim today's ANML" : "Claimed today",
                    role: model.canClaimAnml ? .primary : .secondary,
                    action: claim
                )
                .disabled(!model.canClaimAnml)
            } else {
                EarthButton(title: "Register with your passport") { registering = true }
            }
        }
        .sheet(isPresented: $registering) { RegistrationSheet().earthThemed() }
    }

    private var subtitle: String {
        if !model.isRegistered {
            return "Prove you are a unique human to claim ANML daily and vote."
        }
        if model.canClaimAnml { return "Your ANML is ready." }
        // Once per UTC day, compared as day numbers the way the chain does.
        let next = Date(timeIntervalSince1970: TimeInterval(Personhood.nextClaimOpensAt()))
        return "Next claim \(next.formatted(date: .omitted, time: .shortened))."
    }

    private func claim() {
        tx.request(.init(action: "Claim", rows: [("Token", "ANML"), ("Amount", "1 ANML")])) { key in
            [model.client.msgClaimAnml(creator: key.address)]
        }
    }
}
