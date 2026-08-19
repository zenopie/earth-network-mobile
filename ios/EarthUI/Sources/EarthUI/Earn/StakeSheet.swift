import BigInt
import EarthCore
import SwiftUI

/// Stake to a validator, or take stake back from one.
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

    @State private var validator: Staking.Validator?
    @State private var amount = ""

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: theme.space.x16) {
                    EarthLabel(unstaking ? "Take back from" : "Stake with")
                    VStack(spacing: 0) {
                        ForEach(choices) { option in
                            EarthListRow(
                                initial: String(option.moniker.prefix(1)).uppercased(),
                                name: option.moniker.isEmpty ? option.operatorAddress : option.moniker,
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
                                .font(EarthType.header2)
                                .keyboardType(.decimalPad)
                                .onChange(of: amount) { previous, new in
                                    amount = Amounts.filterAmountInput(new, previous: previous)
                                }
                            Text("ERTH")
                                .font(EarthType.body)
                                .foregroundStyle(theme.colors.textTertiary)
                            Button("Max") { amount = Amounts.fromBaseUnits(available) }
                                .font(EarthType.bodySmall)
                                .foregroundStyle(theme.colors.accentInk)
                        }
                        Text("Available \(Figures.whole(available)) ERTH")
                            .font(EarthType.bodySmall)
                            .foregroundStyle(theme.colors.textTertiary)

                        if unstaking {
                            // Worth saying before the tap rather than after:
                            // the stake stops earning immediately and arrives
                            // weeks later, with nothing on screen in between
                            // but the unbonding row.
                            Text("Unstaked ERTH is locked for the chain's unbonding period and earns nothing while it waits.")
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

    /// Unstaking can only come from somewhere stake already is.
    private var choices: [Staking.Validator] {
        guard unstaking else { return model.validators }
        let mine = Set(model.delegations.map(\.validator))
        return model.validators.filter { mine.contains($0.operatorAddress) }
    }

    private var staked: BigInt {
        guard let validator else { return 0 }
        return model.delegations.first { $0.validator == validator.operatorAddress }
            .flatMap { BigInt($0.amount) } ?? 0
    }

    private var available: BigInt {
        if unstaking { return staked }
        // Leave the fee behind, or the delegation cannot be paid for.
        let fee = BigInt(TransactionSigner.defaultFeeUerth) ?? 0
        return max(0, model.balance(.erth) - fee)
    }

    private var parsed: BigInt? {
        guard validator != nil,
              let value = Token.erth.parse(amount), value > 0, value <= available
        else { return nil }
        return value
    }

    private func subtitle(_ option: Staking.Validator) -> String {
        let percent = String(format: "%.0f%%", option.commission * 100)
        if unstaking {
            let mine = model.delegations.first { $0.validator == option.operatorAddress }?.amount ?? "0"
            return "\(Figures.whole(mine)) ERTH staked"
        }
        return "\(percent) commission"
    }

    private func review() {
        guard let value = parsed, let validator else { return }
        let target = validator.operatorAddress
        let taking = unstaking
        tx.request(.init(
            action: taking ? "Unstake" : "Stake",
            rows: [
                ("Amount", "\(Figures.whole(value)) ERTH"),
                ("Validator", validator.moniker.isEmpty ? target : validator.moniker),
            ]
        )) { key in
            [taking
                ? model.client.msgUndelegate(delegator: key.address, validator: target, amountUerth: String(value))
                : model.client.msgDelegate(delegator: key.address, validator: target, amountUerth: String(value))]
        }
        dismiss()
    }
}
