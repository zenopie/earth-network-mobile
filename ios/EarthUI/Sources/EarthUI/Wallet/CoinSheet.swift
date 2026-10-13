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
///
/// Shield and Unshield happen here, not on a second screen: the big number
/// turns into the amount being moved, with Max under it, and Review raises
/// the confirmation.
struct CoinSheet: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    @Environment(TxController.self) private var tx
    @Environment(\.dismiss) private var dismiss

    let denom: String

    /// Which way an amount is being entered; nil while just looking.
    @State private var moving: ShieldDirection?
    @State private var amount = ""
    @FocusState private var amountFocused: Bool

    /// Live from the model, so a refresh behind the sheet shows in it.
    private var coin: ShieldMove.Coin {
        ShieldMove.coins(public: model.balances, shielded: model.shielded).first { $0.denom == denom }
            ?? ShieldMove.Coin(denom: denom, publicAmount: 0, privateAmount: 0)
    }

    private var shieldFee: BigInt { BigInt(TransactionSigner.defaultFeeUerth) ?? 0 }
    private var unshieldFee: UInt64 { UInt64(Fees.forGas(PrivacyWallet.privateGasEstimate)) ?? 0 }
    private var privateOnly: Bool { denom == Token.anml.denom }
    /// The content's own height: the sheet is exactly that tall, no blank below.
    @State private var height: CGFloat = 300

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
            if let moving {
                entry(moving, token: token)
            } else {
                overview(coin, token: token)
            }
        }
        .padding(.horizontal, theme.space.gutter)
        .padding(.bottom, theme.space.x24)
        .frame(maxWidth: .infinity)
        .fixedSize(horizontal: false, vertical: true)
        .background(GeometryReader { g in
            Color.clear.preference(key: SheetHeight.self, value: g.size.height)
        })
        .onPreferenceChange(SheetHeight.self) { if $0 > 0 { height = $0 } }
        .frame(maxHeight: .infinity, alignment: .top)
        .earthBackground()
        .presentationDetents([.height(height)])
        .presentationDragIndicator(.visible)
        #if targetEnvironment(simulator)
        // `-demoMove unshield` opens straight into an amount, as `-demoCoin` opens this sheet.
        .task { if let d = UserDefaults.standard.string(forKey: "demoMove") { start(d == "unshield" ? .unshield : .shield) } }
        #endif
    }

    @ViewBuilder
    private func overview(_ coin: ShieldMove.Coin, token: Token) -> some View {
            if model.balancesVisible {
                SplitAmount(amount: Figures.when(model.has(.privacy), Figures.plain(coin.privateAmount, decimals: token.decimals)))
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
                Label("\(model.balancesVisible ? Figures.when(model.has(.balances), Figures.balance(coin.publicAmount, decimals: token.decimals)) : "••••") public",
                      systemImage: "globe")
                    .font(EarthType.bodySmall)
                    .foregroundStyle(theme.colors.textTertiary)
                    .labelStyle(TightLabel())
                    .padding(.top, theme.space.x12)
            }

            if !privateOnly {
                Spacer().frame(height: theme.space.x32)
                HStack(spacing: theme.space.x8) {
                    EarthPillButton(title: "Shield") { start(.shield) }
                        .disabled(ShieldMove.shieldBlocked(denom: coin.denom, public: coin.publicAmount, fee: shieldFee) != nil)
                    EarthPillButton(title: "Unshield", role: .secondary) { start(.unshield) }
                        .disabled(ShieldMove.unshieldBlocked(denom: coin.denom, private: coin.privateAmount,
                                                             spendable: coin.denom == Token.erth.denom ? model.unshieldableErth : 0,
                                                             fee: unshieldFee) != nil)
                }
            }
    }

    /// The amount being moved, in the hero's place.
    @ViewBuilder
    private func entry(_ direction: ShieldDirection, token: Token) -> some View {
        let flow = ShieldFlow(direction: direction, model: model)
        TextField("0", text: $amount)
            .font(EarthType.header2).fontWeight(.semibold)
            .multilineTextAlignment(.center)
            .keyboardType(.decimalPad)
            .focused($amountFocused)
            .lineLimit(1)
            .minimumScaleFactor(0.5)
            .onChange(of: amount) { previous, new in amount = Amounts.filterAmountInput(new, previous: previous) }
        HStack(spacing: 6) {
            Text("\(direction == .shield ? "Public" : "Private") \(Figures.when(model.has(direction == .shield ? .balances : .privacy), Figures.balance(flow.available)))")
                .font(EarthType.body)
                .foregroundStyle(theme.colors.textTertiary)
            Button("Max") { amount = Token.erth.format(flow.max) }
                .font(EarthType.body).fontWeight(.semibold)
                .foregroundStyle(theme.colors.accentInk)
        }
        .padding(.top, theme.space.x4)
        if direction == .unshield, model.unshieldableErth < model.shieldedErth {
            // More notes than one transaction carries: Max is short of the balance.
            Text("Merge notes in Settings to move it all")
                .font(EarthType.bodySmall)
                .foregroundStyle(theme.colors.textTertiary)
                .padding(.top, theme.space.x4)
        }

        Spacer().frame(height: theme.space.x32)
        HStack(spacing: theme.space.x8) {
            EarthPillButton(title: "Cancel", role: .secondary) { moving = nil; amount = "" }
            EarthPillButton(title: direction == .shield ? "Shield" : "Unshield") {
                if flow.submit(amount, tx: tx) { dismiss() }
            }
            .disabled(flow.parse(amount) == nil)
        }
    }

    private func start(_ direction: ShieldDirection) {
        amount = ""
        moving = direction
        amountFocused = true
    }
}

enum ShieldDirection: Hashable { case shield, unshield }

/// Moves ERTH between this wallet's public account and its private notes.
///
/// Shield is MsgShield, signed by the account (the coins are its), the note
/// to this wallet's own shielded address. Unshield is a private send out of
/// the notes to this wallet's own account, its fee from the same bundle (at
/// Max, out of the amount).
@MainActor
struct ShieldFlow {
    let direction: ShieldDirection
    let model: AppModel

    private var shieldFee: BigInt { BigInt(TransactionSigner.defaultFeeUerth) ?? 0 }
    private var unshieldFee: UInt64 { UInt64(Fees.forGas(PrivacyWallet.privateGasEstimate)) ?? 0 }

    /// The side the amount comes out of.
    var available: BigInt { direction == .shield ? model.balance(.erth) : BigInt(model.shieldedErth) }

    /// The most one transaction can move. A shield leaves its fee behind; an
    /// unshield at Max releases every note one bundle carries and pays its fee
    /// out of the amount.
    var max: BigInt {
        switch direction {
        case .shield: return ShieldMove.maxShield(public: model.balance(.erth), fee: shieldFee)
        case .unshield: return BigInt(model.unshieldableErth)
        }
    }

    /// Whether this unshield pays its fee from the amount: when the amount and
    /// the estimated fee would not both fit what one bundle can spend.
    private func feeFromAmount(_ amount: UInt64) -> Bool {
        ShieldMove.feeFromAmount(amount: amount, spendable: model.unshieldableErth, fee: unshieldFee)
    }

    func parse(_ text: String) -> BigInt? {
        guard let v = Token.erth.parse(text), v > 0, v <= max else { return nil }
        if direction == .shield, model.privacy == nil { return nil }
        // Fee from the amount: the amount must still exceed the fee.
        if direction == .unshield, let u = UInt64(v.description), feeFromAmount(u), u <= unshieldFee { return nil }
        return v
    }

    /// Raises the confirmation; false when the amount does not parse.
    func submit(_ text: String, tx: TxController) -> Bool {
        guard let value = parse(text), let amount = UInt64(value.description) else { return false }
        let display = "\(Token.erth.format(value)) ERTH"
        let model = self.model
        switch direction {
        case .shield:
            guard let w = model.privacy, let out = try? w.shieldOutput(denom: Constants.gasDenom, amount: amount) else { return false }
            tx.request(.init(action: "Shield ERTH", rows: [("Amount", display), ("From", "your public balance"), ("To", "your private balance")])) { key in
                [MsgShield(sender: key.address, amount: Coin(denom: Constants.gasDenom, amount: String(value)),
                           pc: out.pc.bytes, ciphertext: out.ciphertext).asAny(typeURL: MsgShield.typeURL)]
            }
        case .unshield:
            let to = model.address
            let fromAmount = feeFromAmount(amount)
            tx.requestPrivate(.private(action: "Unshield ERTH", rows: [
                ("Amount", fromAmount ? display + " less the fee" : display),
                ("From", "your private balance"),
                ("To", "your public balance"),
                ("Fee (estimate)", "\(Token.erth.format(Fees.forGas(PrivacyWallet.privateGasEstimate))) ERTH, private"),
            ])) { w in
                try await w.unshield(receiver: to, denom: Constants.gasDenom, amount: amount, feeFromAmount: fromAmount)
            }
        }
        return true
    }
}

private struct SheetHeight: PreferenceKey {
    static let defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) }
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
