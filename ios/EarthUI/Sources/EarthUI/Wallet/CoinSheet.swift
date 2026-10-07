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

/// One coin: its private and public balances, and Shield / Unshield preset to
/// it. The moves themselves are MoveSheet's; this only says which way, or why
/// a way is not open.
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

    var body: some View {
        let coin = coin
        let token = coin.token
        VStack(spacing: 0) {
            CoinMark(token: token, size: 64)
                .padding(.top, 28)
            Text(token.symbol)
                .font(EarthType.textXl).fontWeight(.semibold)
                .foregroundStyle(theme.colors.textPrimary)
                .padding(.top, 10)
            Text(coin.role)
                .font(EarthType.bodySmall)
                .foregroundStyle(theme.colors.textTertiary)
                .padding(.top, 2)

            VStack(spacing: 0) {
                balanceLine("Private", "Invisible on chain", coin.privateAmount, token, locked: true)
                Divider().overlay(theme.colors.strokeSecondary).padding(.horizontal, 14)
                balanceLine("Public", "Seen by Keplr, exchanges and validators", coin.publicAmount, token, locked: false)
            }
            .background(theme.colors.bgSecondary, in: .rect(cornerRadius: theme.space.radiusLg))
            .padding(.top, 20)

            VStack(spacing: 8) {
                moveButton("Shield", "Public → Private", "lock.fill", .shield,
                           blocked: ShieldMove.shieldBlocked(denom: coin.denom, public: coin.publicAmount, fee: shieldFee))
                moveButton("Unshield", "Private → Public", "lock.open.fill", .unshield,
                           blocked: ShieldMove.unshieldBlocked(denom: coin.denom, private: coin.privateAmount,
                                                               spendable: coin.denom == Token.erth.denom ? model.unshieldableErth : 0,
                                                               fee: unshieldFee))
            }
            .padding(.top, 16)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, theme.space.gutter)
        .frame(maxWidth: .infinity)
        .earthBackground()
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }

    private func balanceLine(_ label: String, _ detail: String, _ amount: BigInt, _ token: Token, locked: Bool) -> some View {
        HStack(spacing: 10) {
            Image(systemName: locked ? "lock.fill" : "globe")
                .font(.system(size: 13))
                .foregroundStyle(locked ? theme.colors.accentInk : theme.colors.textTertiary)
                .frame(width: 18)
            VStack(alignment: .leading, spacing: 1) {
                Text(label)
                    .font(EarthType.body)
                    .foregroundStyle(theme.colors.textPrimary)
                Text(detail)
                    .font(EarthType.caption)
                    .foregroundStyle(theme.colors.textTertiary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.85)
            }
            Spacer(minLength: 8)
            Text(model.balancesVisible ? Figures.balance(amount, token) : "••••")
                .font(EarthType.amount)
                .foregroundStyle(theme.colors.textPrimary)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
    }

    /// The way across, or why not: the reason takes the direction's place.
    private func moveButton(_ title: String, _ way: String, _ symbol: String, _ direction: MoveSheet.Direction, blocked: String?) -> some View {
        Button {
            onMove(direction)
            dismiss()
        } label: {
            HStack(spacing: 12) {
                Image(systemName: symbol)
                    .font(.system(size: 15, weight: .semibold))
                    .frame(width: 20)
                VStack(alignment: .leading, spacing: 1) {
                    Text(title)
                        .font(EarthType.body).fontWeight(.semibold)
                    // A reason must stay readable: the disabled ink is too faint for a sentence.
                    Text(blocked ?? way)
                        .font(EarthType.caption)
                        .foregroundStyle(blocked == nil ? theme.colors.secondaryButtonFg.opacity(0.8) : theme.colors.textSecondary)
                        .lineLimit(2)
                        .multilineTextAlignment(.leading)
                }
                Spacer(minLength: 0)
                if blocked == nil {
                    Image(systemName: "chevron.right")
                        .font(.system(size: 12, weight: .semibold))
                }
            }
            .foregroundStyle(blocked == nil ? theme.colors.secondaryButtonFg : theme.colors.buttonDisabledFg)
            .padding(.horizontal, 16)
            .frame(maxWidth: .infinity, minHeight: theme.space.buttonHeight + 4)
            .background(blocked == nil ? theme.colors.secondaryButtonBg : theme.colors.buttonDisabledBg,
                        in: .rect(cornerRadius: theme.space.radiusMd))
        }
        .buttonStyle(.plain)
        .disabled(blocked != nil)
    }
}
