import BigInt
import EarthCore
import SwiftUI

/// A coin's own mark: its logo, or for a coin this build has none for, its
/// symbol's first letters on a neutral disc. Neutral rather than the accent:
/// the accent would make an unknown token look like one of Earth's own.
struct CoinMark: View {
    @Environment(\.earth) private var theme
    let token: Token
    var size: CGFloat = 44

    var body: some View {
        if let logo = Self.logo(token.denom) {
            logo.resizable().scaledToFit()
                .frame(width: size, height: size)
                .accessibilityHidden(true)
        } else {
            Text(String(token.symbol.filter(\.isLetter).prefix(2)).uppercased())
                .font(.system(size: size * 0.36, weight: .semibold))
                .foregroundStyle(theme.colors.textSecondary)
                .frame(width: size, height: size)
                .background(theme.colors.bgTertiary, in: .circle)
                .accessibilityHidden(true)
        }
    }

    static func logo(_ denom: String) -> Image? {
        switch denom {
        case Token.erth.denom: return EarthAsset.erth
        case Token.anml.denom: return EarthAsset.anml
        default: return nil
        }
    }
}

extension ShieldMove.Coin {
    var token: Token { Token.named(denom) ?? Token.unknown(denom: denom) }

    /// One line on what the coin is for, under its symbol.
    var role: String {
        switch denom {
        case Token.erth.denom: return "Pays network fees"
        case Token.anml.denom: return "Personhood · always private"
        default: return denom.hasPrefix("dexlp/") ? "Pool share" : denom
        }
    }

    /// ERTH shows both sides even at zero (the fee comes from the private
    /// one); ANML has no public side; anything else shows what it holds.
    var showsPrivate: Bool { privateAmount > 0 || denom == Token.erth.denom || denom == Token.anml.denom }
    var showsPublic: Bool { publicAmount > 0 || denom == Token.erth.denom }
}

/// One coin in Portfolio: its mark, what it is, and its private and public
/// amounts on their own lines, private first (it is the default home).
/// The whole row opens the coin's sheet.
struct CoinRow: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    let coin: ShieldMove.Coin
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 14) {
                CoinMark(token: coin.token, size: 44)
                VStack(alignment: .leading, spacing: 2) {
                    Text(coin.token.symbol)
                        .font(EarthType.title)
                        .foregroundStyle(theme.colors.textPrimary)
                        .lineLimit(1)
                    Text(coin.role)
                        .font(EarthType.caption)
                        .foregroundStyle(theme.colors.textTertiary)
                        .lineLimit(1)
                }
                .layoutPriority(1)
                Spacer(minLength: 8)
                VStack(alignment: .trailing, spacing: 3) {
                    if coin.showsPrivate { amountLine(coin.privateAmount, locked: true) }
                    if coin.showsPublic { amountLine(coin.publicAmount, locked: false) }
                }
                Image(systemName: "chevron.right")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(theme.colors.textTertiary)
            }
            .padding(.horizontal, 24)
            .padding(.vertical, 12)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .accessibilityHint("Shield or unshield")
    }

    private func amountLine(_ amount: BigInt, locked: Bool) -> some View {
        HStack(spacing: 5) {
            Text(model.balancesVisible ? Figures.balance(amount, decimals: coin.token.decimals) : "••••")
                .font(locked ? EarthType.amount : EarthType.bodySmall.monospacedDigit())
                .foregroundStyle(locked ? theme.colors.textPrimary : theme.colors.textSecondary)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            Image(systemName: locked ? "lock.fill" : "globe")
                .font(.system(size: 10))
                .foregroundStyle(locked ? theme.colors.accentInk : theme.colors.textTertiary)
                .frame(width: 12)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel((locked ? "Private " : "Public ") + Figures.balance(amount, decimals: coin.token.decimals))
    }
}

/// One coin, as the stake page shows a validator: its mark, the private
/// amount large, the public amount on one line beneath, and Shield /
/// Unshield. ANML is only ever private, so it is the number and nothing to do.
struct CoinSheet: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss

    let denom: String
    let onMove: (MoveSheet.Direction) -> Void

    /// Live from the model, so a refresh behind the sheet shows in it.
    private var coin: ShieldMove.Coin {
        ShieldMove.coins(public: model.balances, shielded: model.shielded).first { $0.denom == denom }
            ?? ShieldMove.Coin(denom: denom, publicAmount: 0, privateAmount: 0)
    }

    private var shieldFee: BigInt { BigInt(TransactionSigner.defaultFeeUerth) ?? 0 }
    private var unshieldFee: UInt64 { UInt64(Fees.forGas(PrivacyWallet.privateGasEstimate)) ?? 0 }
    private var privateOnly: Bool { denom == Token.anml.denom }

    var body: some View {
        let coin = coin
        let token = coin.token
        VStack(spacing: 0) {
            Spacer().frame(height: theme.space.x32)
            CoinMark(token: token, size: 56)
            Spacer().frame(height: theme.space.x12)
            Text(token.symbol)
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(theme.colors.textPrimary)

            Spacer().frame(height: theme.space.x24)
            if model.balancesVisible {
                SplitAmount(amount: Figures.plain(coin.privateAmount, decimals: token.decimals))
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
            } else {
                Text("-----").font(EarthType.header2).fontWeight(.semibold)
                    .foregroundStyle(theme.colors.textPrimary)
            }
            Label(privateOnly ? "Always private" : "Private", systemImage: "lock.fill")
                .font(EarthType.body)
                .foregroundStyle(theme.colors.textTertiary)
                .labelStyle(TightLabel())

            if !privateOnly, coin.showsPublic {
                Label("\(model.balancesVisible ? Figures.balance(coin.publicAmount, decimals: token.decimals) : "••••") public",
                      systemImage: "globe")
                    .font(EarthType.bodySmall)
                    .foregroundStyle(theme.colors.textTertiary)
                    .labelStyle(TightLabel())
                    .padding(.top, theme.space.x12)
            }

            if !privateOnly {
                Spacer().frame(height: theme.space.x32)
                HStack(spacing: theme.space.x8) {
                    EarthPillButton(title: "Shield") { move(.shield) }
                        .disabled(ShieldMove.shieldBlocked(denom: coin.denom, public: coin.publicAmount, fee: shieldFee) != nil)
                    EarthPillButton(title: "Unshield", role: .secondary) { move(.unshield) }
                        .disabled(ShieldMove.unshieldBlocked(denom: coin.denom, private: coin.privateAmount,
                                                             spendable: coin.denom == Token.erth.denom ? model.unshieldableErth : 0,
                                                             fee: unshieldFee) != nil)
                }
            }
            Spacer(minLength: theme.space.x16)
        }
        .padding(.horizontal, theme.space.gutter)
        .frame(maxWidth: .infinity)
        .earthBackground()
        .presentationDetents([.height(privateOnly ? 300 : 440)])
        .presentationDragIndicator(.visible)
    }

    private func move(_ direction: MoveSheet.Direction) {
        onMove(direction)
        dismiss()
    }
}

/// Icon and text a few points apart, centred: the system label spaces them
/// for a list row.
private struct TightLabel: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 5) {
            configuration.icon.font(.system(size: 11))
            configuration.title
        }
    }
}
