import BigInt
import EarthCore
import SwiftUI

/// Stake to a validator, or take stake back from one — privately.
///
/// Staking spends shielded ERTH into the pool's delegation to a validator and
/// comes back as derth/<validator> notes, worth more ERTH each epoch as
/// rewards compound; unstaking turns derth into an unbonding note, which the
/// wallet claims on its own once its epoch's undelegation matures. Only a
/// validator's own self-bond is a transparent delegation now.
///
/// One sheet for both directions because the fields are the same and the
/// difference is a word — two screens would drift apart on the amount rules,
/// which are the part that matters.
struct StakeSheet: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    @Environment(TxController.self) private var tx
    @Environment(\.dismiss) private var dismiss

    let unstaking: Bool

    @State private var validator: String?
    @State private var amount = ""

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: theme.space.x16) {
                    EarthLabel(unstaking ? "Take back from" : "Stake with")
                    VStack(spacing: 0) {
                        if choices.isEmpty {
                            Text(unstaking ? "No private stake yet." : "No validators to stake with.")
                                .font(EarthType.bodySmall)
                                .foregroundStyle(theme.colors.textTertiary)
                        }
                        ForEach(choices, id: \.self) { option in
                            EarthListRow(
                                initial: String(moniker(option).prefix(1)).uppercased(),
                                name: moniker(option),
                                subtitle: subtitle(option),
                                value: validator == option ? "✓" : nil,
                                badgeBackground: theme.colors.accentTint,
                                badgeForeground: theme.colors.accentInk,
                                action: { validator = option }
                            )
                            EarthDivider()
                        }
                    }

                    if validator != nil {
                        EarthLabel("Amount")
                        HStack {
                            TextField("0", text: $amount)
                                .font(EarthType.amountField)
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
                                .keyboardType(.decimalPad)
                                .onChange(of: amount) { previous, new in
                                    amount = Amounts.filterAmountInput(new, previous: previous)
                                }
                            Text(unstaking ? "derth" : "ERTH")
                                .font(EarthType.body)
                                .foregroundStyle(theme.colors.textTertiary)
                            Button("Max") { amount = Amounts.fromBaseUnits(available) }
                                .font(EarthType.bodySmall)
                                .foregroundStyle(theme.colors.accentInk)
                        }
                        Text(unstaking
                             ? "Staked \(Figures.balance(available)) derth, worth \(Figures.balance(BigInt(model.derthValue(UInt64(available.description) ?? 0, validator: validator ?? "")))) ERTH"
                             : "Available \(Figures.balance(available)) shielded ERTH")
                            .font(EarthType.bodySmall)
                            .foregroundStyle(theme.colors.textTertiary)

                        if unstaking {
                            // Worth saying before the tap rather than after:
                            // the stake stops earning immediately and arrives
                            // weeks later, with nothing on screen in between
                            // but the unbonding row.
                            Text("Unstaking becomes an unbonding claim at this epoch's rate. It earns nothing while the chain's unbonding period runs, and the wallet claims it on its own once it matures.")
                                .font(EarthType.bodySmall)
                                .foregroundStyle(theme.colors.textTertiary)
                        }
                    }

                    EarthButton(title: unstaking ? "Review unstake" : "Review stake") { review() }
                        .disabled(parsed == nil)
                }
                .padding(theme.space.gutter)
            }
            .navigationTitle(unstaking ? "Unstake" : "Stake")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
            .background(theme.colors.bgPrimary)
            .scrollContentBackground(.hidden)
        }
    }

    /// Unstaking can only come from somewhere private stake already is.
    private var choices: [String] {
        guard unstaking else { return model.validators.map(\.operatorAddress) }
        return model.privateStake.keys.map { String($0.dropFirst("derth/".count)) }.sorted()
    }

    private func moniker(_ op: String) -> String {
        let m = model.validators.first { $0.operatorAddress == op }?.moniker ?? ""
        return m.isEmpty ? op : m
    }

    private var available: BigInt {
        if unstaking {
            guard let validator else { return 0 }
            return BigInt(model.privateStake[PrivacyWallet.derthDenom(validator)] ?? 0)
        }
        // Leave a reserve, not one fee: staking everything-but-the-fee leaves
        // no shielded ERTH to pay for unstaking.
        let reserve = BigInt(TransactionSigner.gasReserveUerth) ?? 0
        return max(0, BigInt(model.shieldedErth) - reserve)
    }

    private var parsed: BigInt? {
        guard validator != nil,
              let value = Token.erth.parse(amount), value > 0, value <= available
        else { return nil }
        return value
    }

    private func subtitle(_ option: String) -> String {
        if unstaking {
            let held = model.privateStake[PrivacyWallet.derthDenom(option)] ?? 0
            return "\(Figures.whole(BigInt(model.derthValue(held, validator: option)))) ERTH staked · \(Figures.whole(BigInt(held))) derth"
        }
        let c = model.validators.first { $0.operatorAddress == option }?.commission ?? 0
        return String(format: "%.0f%% commission", c * 100)
    }

    private func review() {
        guard let value = parsed, let validator, let amount = UInt64(value.description) else { return }
        let taking = unstaking
        tx.requestPrivate(.private(
            action: taking ? "Unstake" : "Stake privately",
            rows: [
                ("Amount", taking
                    ? "\(Figures.balance(value)) derth (\(Figures.balance(BigInt(model.derthValue(amount, validator: validator)))) ERTH)"
                    : "\(Figures.balance(value)) ERTH"),
                (taking ? "From validator" : "Validator", moniker(validator)),
                ("Fee (estimate)", "\(Token.erth.format(Fees.forGas(PrivacyWallet.privateGasEstimate))) ERTH, shielded"),
            ]
        ), onSuccess: { await model.refresh() }) { w in
            taking ? try await w.undelegate(validator: validator, amount: amount)
                   : try await w.delegate(validator: validator, amount: amount)
        }
        dismiss()
    }
}
