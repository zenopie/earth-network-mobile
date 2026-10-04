import BigInt
import EarthCore
import SwiftUI

/// Move stake: from one of your validators to another, with no unbonding gap
/// (MsgRedelegate). Pick where it leaves, where it goes, then how much. Ports
/// `ui/earn/MoveStakeSheet.kt`.
///
/// The sources carry what may move now: stake moved in recently stays where
/// it is until its window closes, so it is not offered. The destinations are
/// the bonded set less the source. The confirm sheet that follows shows the
/// chain's own numbers (what arrives, at the live rates).
struct MoveStakeSheet: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    @Environment(TxController.self) private var tx
    @Environment(\.dismiss) private var dismiss

    @State private var source: String?
    @State private var destination: String?
    @State private var amount = ""

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: theme.space.x16) {
                    EarthLabel("From")
                    VStack(spacing: 0) {
                        if sources.isEmpty {
                            Text("No private stake that can move now.")
                                .font(EarthType.bodySmall)
                                .foregroundStyle(theme.colors.textTertiary)
                        }
                        ForEach(sources, id: \.validator) { h in
                            EarthListRow(
                                initial: String(moniker(h.validator).prefix(1)).uppercased(),
                                name: moniker(h.validator),
                                subtitle: "\(Figures.balance(BigInt(h.free))) derth can move now",
                                value: source == h.validator ? "✓" : nil,
                                badgeBackground: theme.colors.accentTint,
                                badgeForeground: theme.colors.accentInk,
                                action: {
                                    source = h.validator
                                    if destination == h.validator { destination = nil }
                                    amount = ""
                                }
                            )
                            EarthDivider()
                        }
                    }

                    if source != nil {
                        EarthLabel("To")
                        VStack(spacing: 0) {
                            ForEach(destinations, id: \.self) { op in
                                EarthListRow(
                                    initial: String(moniker(op).prefix(1)).uppercased(),
                                    name: moniker(op),
                                    subtitle: commission(op),
                                    value: destination == op ? "✓" : nil,
                                    badgeBackground: theme.colors.accentTint,
                                    badgeForeground: theme.colors.accentInk,
                                    action: { destination = op }
                                )
                                EarthDivider()
                            }
                        }

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
                            Text("derth")
                                .font(EarthType.body)
                                .foregroundStyle(theme.colors.textTertiary)
                            Button("Max") { amount = Amounts.fromBaseUnits(available) }
                                .font(EarthType.bodySmall)
                                .foregroundStyle(theme.colors.accentInk)
                        }
                        Text("Can move now \(Figures.balance(available)) derth")
                            .font(EarthType.bodySmall)
                            .foregroundStyle(theme.colors.textTertiary)
                        Text(note)
                            .font(EarthType.bodySmall)
                            .foregroundStyle(theme.colors.textTertiary)
                    }

                    EarthButton(title: "Review move") { review() }
                        .disabled(parsed == nil || destination == nil)
                }
                .padding(theme.space.gutter)
            }
            .navigationTitle("Move stake")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
            .background(theme.colors.bgPrimary)
            .scrollContentBackground(.hidden)
        }
    }

    private var sources: [PrivacyWallet.StakeHolding] { model.stakeHoldings.filter { $0.free > 0 } }

    private var destinations: [String] { model.stakeTargets.filter { $0 != source } }

    private var available: BigInt { BigInt(sources.first { $0.validator == source }?.free ?? 0) }

    private var parsed: BigInt? {
        guard source != nil, let value = Token.erth.parse(amount), value > 0, value <= available else { return nil }
        return value
    }

    private var note: String {
        let days = model.labelWindowSeconds / 86_400
        return "Moved stake keeps earning, with no unbonding gap. It stays at the new validator"
            + (days > 0 ? " for about \(days) days" : " for the unbonding period")
            + " before it can move, unstake or lock again: a slash of the validator it left can still reach it until then."
    }

    private func moniker(_ op: String) -> String {
        model.moniker(of: op)
    }

    private func commission(_ op: String) -> String {
        String(format: "%.0f%% commission", (model.commission(of: op) ?? 0) * 100)
    }

    private func review() {
        guard let value = parsed, let src = source, let dst = destination, let amount = UInt64(value.description), let w = model.privacy else { return }
        dismiss()
        Task { @MainActor in
            do {
                // The chain's own numbers first (live rates, the debt tree): the sheet shows what the tx will carry.
                let q = try await w.quoteMove(src: src, dst: dst, amount: amount)
                tx.requestPrivate(.private(
                    action: "Move stake",
                    rows: [
                        ("Moves", "\(Figures.balance(BigInt(q.amount))) derth (\(Figures.balance(BigInt(q.value))) ERTH)"),
                        ("From", moniker(q.src)),
                        ("To", moniker(q.dst)),
                        ("Arrives as", "\(Figures.balance(BigInt(q.dstDerth))) derth or more"),
                        ("Fee (estimate)", "\(Token.erth.format(Fees.forGas(PrivacyWallet.privateGasEstimate))) ERTH, shielded"),
                    ],
                    notes: [
                        "The arriving stake is quoted at both validators' live rates with a small margin. If the rates move past it before the move lands, the chain refuses it and nothing is spent: just try again.",
                        q.merges ? nil : "You hold no other stake at \(moniker(q.dst)) it can join, so it arrives as its own note there (merge it later with a tap).",
                        "It can move, unstake or lock again after about \(q.windowSeconds / 86_400) days.",
                        StakeNotes.haircut(q.haircut, from: moniker(q.src)),
                    ].compactMap { $0 }
                ), onSuccess: { await model.refresh() }) { w in
                    try await w.redelegate(q)
                }
            } catch {
                tx.showFailure("Move stake", error, model: model)
            }
        }
    }
}
