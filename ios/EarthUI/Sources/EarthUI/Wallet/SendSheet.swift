import BigInt
import EarthCore
import SwiftUI

struct SendSheet: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    @Environment(TxController.self) private var tx
    @Environment(\.dismiss) private var dismiss

    @State private var token = Token.erth
    @State private var recipient = ""
    @State private var amount = ""

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: theme.space.x16) {
                    picker
                    recipientField
                    amountField
                    Spacer(minLength: theme.space.x16)
                    EarthButton(title: "Review") { review() }
                        .disabled(!isValid)
                }
                .padding(theme.space.gutter)
            }
            .navigationTitle("Send")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
            .earthBackground()
            .scrollContentBackground(.hidden)
        }
    }

    private var picker: some View {
        VStack(alignment: .leading, spacing: theme.space.x8) {
            EarthLabel("Token")
            Picker("Token", selection: $token) {
                ForEach(model.holdings.map(\.token)) { Text($0.symbol).tag($0) }
            }
            .pickerStyle(.segmented)
        }
    }

    private var recipientField: some View {
        VStack(alignment: .leading, spacing: theme.space.x8) {
            EarthLabel("To")
            TextField("earth1…", text: $recipient, axis: .vertical)
                .font(EarthType.mono)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .padding(theme.space.x12)
                .background(theme.colors.bgPrimary, in: .rect(cornerRadius: theme.space.radiusMd))
                .overlay {
                    RoundedRectangle(cornerRadius: theme.space.radiusMd)
                        .strokeBorder(recipientStroke, lineWidth: theme.space.stroke)
                }
            // Validated as typed rather than on submit: a bech32 checksum
            // catches a mistyped address before a fee is spent finding out, and
            // the chain's error for one is not readable.
            if !recipient.isEmpty, !EarthKey.isValidAddress(recipient) {
                Text("Not a valid earth address.")
                    .font(EarthType.bodySmall)
                    .foregroundStyle(theme.colors.textError)
            }
        }
    }

    private var amountField: some View {
        VStack(alignment: .leading, spacing: theme.space.x8) {
            HStack {
                EarthLabel("Amount")
                Spacer()
                Button("Max") { amount = maxSendable }
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
                Text(token.symbol)
                    .font(EarthType.title)
                    .foregroundStyle(theme.colors.textTertiary)
            }
            Text("Balance \(token.format(model.balance(token))) \(token.symbol)")
                .font(EarthType.bodySmall)
                .foregroundStyle(theme.colors.textTertiary)
        }
    }

    /// The whole balance, less the fee when sending the fee's own denom —
    /// otherwise "Max" builds a transaction the account cannot pay for.
    private var maxSendable: String {
        let balance = model.balance(token)
        guard token == .erth else { return token.format(balance) }
        let fee = BigInt(TransactionSigner.defaultFeeUerth) ?? 0
        return token.format(max(0, balance - fee))
    }

    private var parsedAmount: BigInt? {
        guard let value = token.parse(amount), value > 0 else { return nil }
        return value <= model.balance(token) ? value : nil
    }

    private var isValid: Bool {
        EarthKey.isValidAddress(recipient) && parsedAmount != nil
    }

    private var recipientStroke: Color {
        if recipient.isEmpty { return theme.colors.strokePrimary }
        return EarthKey.isValidAddress(recipient) ? theme.colors.strokePrimary : theme.colors.textError
    }

    private func review() {
        guard let value = parsedAmount else { return }
        let to = recipient
        let denom = token.denom
        tx.request(.init(
            action: "Send",
            rows: [
                ("Amount", "\(token.format(value)) \(token.symbol)"),
                ("To", to),
                ("Fee", "\(Token.erth.format(TransactionSigner.defaultFeeUerth)) ERTH"),
            ]
        )) { key in
            [model.client.msgSend(from: key.address, to: to, denom: denom, amount: String(value))]
        }
        dismiss()
    }
}
