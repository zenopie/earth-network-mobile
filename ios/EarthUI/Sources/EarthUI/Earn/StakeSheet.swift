import BigInt
import EarthCore
import SwiftUI

/// Stake to a validator, or take stake back from one — privately.
///
/// Staking spends shielded ERTH into the pool's delegation to a validator and
/// comes back as derth/<validator> notes, worth more ERTH each epoch as
/// rewards compound; unstaking names a note of ours the chain pays the ERTH to
/// once the unbonding period ends: nothing more to send. Only a validator's
/// own self-bond is a transparent delegation now.
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
                            Text("Unstaked ERTH arrives in this wallet as private ERTH once the unbonding period ends. Nothing more to do or pay."
                                 + (model.stakeHoldings.contains { $0.locked > 0 } ? " Stake moved here recently can be unstaked once its window closes." : ""))
                                .font(EarthType.bodySmall)
                                .foregroundStyle(theme.colors.textTertiary)
                        } else {
                            // derth is not a coin: a stake note only its owner
                            // can merge, vote, lock or unstake. Nothing can
                            // send or sell it.
                            Text("Staked ERTH stays locked to this wallet: it can't be sent, unshielded or traded, only unstaked or moved.")
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

    /// Unstaking can only come from somewhere private stake may leave now
    /// (moved-in stake whose window is open stays where it is).
    private var choices: [String] {
        guard unstaking else { return model.validators.map(\.operatorAddress) }
        return model.stakeHoldings.filter { $0.free > 0 }.map(\.validator)
    }

    private func moniker(_ op: String) -> String {
        let m = model.validators.first { $0.operatorAddress == op }?.moniker ?? ""
        return m.isEmpty ? op : m
    }

    private var available: BigInt {
        if unstaking {
            guard let validator else { return 0 }
            return BigInt(model.stakeHoldings.first { $0.validator == validator }?.free ?? 0)
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
        guard let value = parsed, let validator, let amount = UInt64(value.description), let w = model.privacy else { return }
        let taking = unstaking
        let name = moniker(validator)
        let fee = ("Fee (estimate)", "\(Token.erth.format(Fees.forGas(PrivacyWallet.privateGasEstimate))) ERTH, shielded")
        dismiss()
        Task { @MainActor in
            do {
                if taking {
                    // What clearing a moved-in label costs, read before the sheet and sent as shown.
                    let cut = try await w.leaveHaircut(validator: validator, amount: amount)
                    tx.requestPrivate(.private(
                        action: "Unstake",
                        rows: [
                            ("Amount", "\(Figures.balance(value)) derth (\(Figures.balance(BigInt(model.derthValue(amount, validator: validator)))) ERTH)"),
                            ("From validator", name),
                            fee,
                        ],
                        notes: [StakeNotes.haircut(cut, from: nil)].compactMap { $0 }
                    ), onSuccess: { await model.refresh() }) { w in
                        // A stake proof spends two notes (at most one holding
                        // moved-in stake): spread over more, it is refused
                        // with "merge them first".
                        try await w.undelegate(validator: validator, amount: amount, maxHaircut: cut)
                    }
                } else {
                    // The quote: the derth the chain credits at the live rate, less a margin.
                    let q = try await w.quoteDelegate(validator: validator, amount: amount)
                    tx.requestPrivate(.private(
                        action: "Stake privately",
                        rows: [
                            ("Amount", "\(Figures.balance(value)) ERTH"),
                            ("You receive", "\(Figures.balance(BigInt(q.derth))) derth"),
                            ("Validator", name),
                            fee,
                        ],
                        notes: ["Quoted at the validator's live rate with a small margin. If the rate moves past it before this lands, the chain refuses it and nothing is spent: just try again.",
                                StakeNotes.haircut(q.haircut, from: nil)].compactMap { $0 }
                    ), onSuccess: { await model.refresh() }) { w in
                        try await w.delegate(q)
                    }
                }
            } catch {
                tx.showFailure(taking ? "Unstake" : "Stake privately", error, model: model)
            }
        }
    }
}

/// The sentences the staking sheets add for the chain's slash debt.
enum StakeNotes {
    /// What a confirm sheet says when the tx settles a slash's cut of stake
    /// moved in from another validator (the label clears at what the debt
    /// tree says it is worth). Nil when nothing is cut.
    static func haircut(_ haircut: UInt64, from: String?) -> String? {
        guard haircut > 0 else { return nil }
        return "A slash of the validator this stake was moved from\(from.map { " (\($0))" } ?? "") reached it before its window closed: "
            + "\(Figures.balance(BigInt(haircut))) derth of it is gone, and this transaction settles that."
    }
}
