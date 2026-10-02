import BigInt
import EarthCore
import SwiftUI

/// Moves ERTH between this wallet's public account and its private notes.
///
/// Shield is MsgShield, signed by the account (the coins are its), the note
/// to this wallet's own shielded address. Unshield is a private transfer out
/// of the notes to this wallet's own account, its fee from the same notes.
/// One sheet for both, because the fields are the same and the difference is
/// which balance the amount comes out of.
struct MoveSheet: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    @Environment(TxController.self) private var tx
    @Environment(\.dismiss) private var dismiss

    @State var direction: Direction
    @State private var amount = ""

    enum Direction: Hashable { case shield, unshield }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: theme.space.x16) {
                    Picker("Direction", selection: $direction) {
                        Text("Shield").tag(Direction.shield)
                        Text("Unshield").tag(Direction.unshield)
                    }
                    .pickerStyle(.segmented)
                    .onChange(of: direction) { amount = "" }

                    Text(direction == .shield
                         ? "Public → Private. The amount is public as it enters; after that, what you do with it is not."
                         : "Private → Public, to this wallet's own account. The amount is public as it leaves.")
                        .font(EarthType.bodySmall)
                        .foregroundStyle(theme.colors.textSecondary)

                    VStack(alignment: .leading, spacing: theme.space.x8) {
                        HStack {
                            EarthLabel("Amount")
                            Spacer()
                            Button("Max") { amount = Token.erth.format(maxAmount) }
                                .font(EarthType.bodySmall)
                                .foregroundStyle(theme.colors.accentInk)
                        }
                        HStack {
                            TextField("0", text: $amount)
                                .font(EarthType.amountField)
                                .lineLimit(1)
                                .minimumScaleFactor(0.5)
                                .keyboardType(.decimalPad)
                                .onChange(of: amount) { previous, new in
                                    amount = Amounts.filterAmountInput(new, previous: previous)
                                }
                            Text("ERTH")
                                .font(EarthType.title)
                                .foregroundStyle(theme.colors.textTertiary)
                        }
                        Text(direction == .shield
                             ? "Public \(Figures.balance(model.balance(.erth))) ERTH"
                             : "Private \(Figures.balance(BigInt(model.shieldedErth))) ERTH")
                            .font(EarthType.bodySmall)
                            .foregroundStyle(theme.colors.textTertiary)
                        if direction == .unshield, model.unshieldableErth < model.shieldedErth {
                            // One unshield spends at most three notes.
                            Text("Your private ERTH is spread over many notes. Merge them in Settings → Shielded notes to move more at once.")
                                .font(EarthType.bodySmall)
                                .foregroundStyle(theme.colors.textTertiary)
                        }
                    }

                    Text("Private ERTH can't be seen on-chain; public ERTH is needed for Keplr, exchanges and validator actions.")
                        .font(EarthType.bodySmall)
                        .foregroundStyle(theme.colors.textTertiary)

                    EarthButton(title: direction == .shield ? "Review shield" : "Review unshield") { review() }
                        .disabled(parsed == nil)
                }
                .padding(theme.space.gutter)
            }
            .navigationTitle(direction == .shield ? "Shield ERTH" : "Unshield ERTH")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
            .earthBackground()
            .scrollContentBackground(.hidden)
        }
    }

    private var shieldFee: BigInt { BigInt(TransactionSigner.defaultFeeUerth) ?? 0 }
    private var unshieldFee: UInt64 { UInt64(Fees.forGas(PrivacyWallet.privateGasEstimate)) ?? 0 }

    /// The most one transaction can move, its fee left behind.
    private var maxAmount: BigInt {
        switch direction {
        case .shield:
            return ShieldMove.maxShield(public: model.balance(.erth), fee: shieldFee)
        case .unshield:
            let spendable = model.unshieldableErth
            return BigInt(spendable > unshieldFee ? spendable - unshieldFee : 0)
        }
    }

    private var parsed: BigInt? {
        guard let v = Token.erth.parse(amount), v > 0, v <= maxAmount else { return nil }
        if direction == .shield, model.privacy == nil { return nil }
        return v
    }

    private func review() {
        guard let value = parsed, let amount = UInt64(value.description) else { return }
        let display = "\(Token.erth.format(value)) ERTH"
        switch direction {
        case .shield:
            // MsgShield is signed by the account: the note goes to this
            // wallet's own shielded address, its ciphertext v1.
            guard let w = model.privacy, let out = try? w.shieldOutput(denom: Constants.gasDenom, amount: amount) else { return }
            tx.request(.init(action: "Shield ERTH", rows: [("Amount", display), ("From", "your public balance"), ("To", "your private balance")]),
                       onSuccess: { await model.syncPrivacy() }) { key in
                [MsgShield(sender: key.address, amount: Coin(denom: Constants.gasDenom, amount: String(value)),
                           pc: out.pc.bytes, ciphertext: out.ciphertext).asAny(typeURL: MsgShield.typeURL)]
            }
        case .unshield:
            let to = model.address
            tx.requestPrivate(.private(action: "Unshield ERTH", rows: [
                ("Amount", display),
                ("From", "your private balance"),
                ("To", "your public balance"),
                ("Fee (estimate)", "\(Token.erth.format(Fees.forGas(PrivacyWallet.privateGasEstimate))) ERTH, private"),
            ]), onSuccess: { await model.syncPrivacy() }) { w in
                try await w.unshield(receiver: to, denom: Constants.gasDenom, amount: amount)
            }
        }
        dismiss()
    }
}
