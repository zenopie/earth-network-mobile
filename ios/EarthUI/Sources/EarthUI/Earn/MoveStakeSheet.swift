import BigInt
import EarthCore
import SwiftUI

/// Move stake: from one of your validators to another, with no unbonding gap
/// (MsgRedelegate). Ports `ui/earn/MoveStakeSheet.kt`, laid out like Swap: the
/// amount big, Max, where it leaves and where it goes as two rows, one button.
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
    @State private var picking: Side?

    enum Side: Hashable { case from, to }

    init(source: String? = nil) {
        _source = State(initialValue: source)
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                StakeAmountField(amount: $amount, unit: "derth", available: available,
                                 error: Token.erth.parse(amount).flatMap { $0 > available ? "More than available" : nil })
                Spacer().frame(height: theme.space.x24)
                VStack(spacing: theme.space.x8) {
                    ValidatorSelectRow(label: "From", validator: source) { picking = .from }
                    ValidatorSelectRow(label: "To", validator: destination) { picking = .to }
                }
                Spacer(minLength: theme.space.x24)
                EarthPillButton(title: "Move") { review() }
                    .disabled(parsed == nil || destination == nil)
                Spacer().frame(height: theme.space.x16)
            }
            .padding(.horizontal, theme.space.gutter)
            .navigationTitle("Move")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
            .background(theme.colors.bgPrimary)
            .navigationDestination(item: $picking) { side in
                if side == .from {
                    ValidatorPicker(title: "Move from", options: sources.map { h in
                        .init(validator: h.validator, detail: "\(Figures.display(BigInt(model.derthValue(h.free, validator: h.validator)))) ERTH", enabled: true)
                    }, selected: source) { op in
                        if source != op { amount = "" }
                        source = op
                        if destination == op { destination = nil }
                        picking = nil
                    }
                } else {
                    ValidatorPicker(title: "Move to", options: destinations.map {
                        .init(validator: $0, detail: ValidatorPicker.commission(model.commission(of: $0)), enabled: true)
                    }, selected: destination) { op in
                        destination = op
                        picking = nil
                    }
                }
            }
        }
    }

    private var sources: [PrivacyWallet.StakeHolding] { model.stakeHoldings.filter { $0.free > 0 } }

    private var destinations: [String] { model.stakeTargets.filter { $0 != source } }

    private var available: BigInt { BigInt(sources.first { $0.validator == source }?.free ?? 0) }

    private var parsed: BigInt? {
        guard source != nil, let value = Token.erth.parse(amount), value > 0, value <= available else { return nil }
        return value
    }

    private func moniker(_ op: String) -> String {
        model.moniker(of: op)
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
                        // A consequence, not an explanation: the stake is held there for the window.
                        "Can't move, unstake or lock it again for about \(q.windowSeconds / 86_400) days.",
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
