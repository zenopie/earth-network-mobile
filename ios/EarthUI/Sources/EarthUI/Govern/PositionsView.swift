import BigInt
import EarthCore
import SwiftUI

/// The stake-weighted blend of this wallet's positions, as whole percentages
/// summing to 100 (largest remainder). Ports `positionSplit` in
/// AllocationViewModel.kt.
func positionSplit(_ positions: [PrivacyReads.Position]) -> [UInt64: UInt64] {
    var weight: [UInt64: Double] = [:]
    for p in positions { for (id, pct) in p.splits { weight[id, default: 0] += Double(p.derth) * Double(pct) } }
    let total = weight.values.reduce(0, +)
    guard total > 0, total.isFinite else { return [:] }
    let exact = weight.mapValues { $0 / total * 100 }
    var out = exact.mapValues { UInt64($0.rounded(.down)) }
    var left = 100 - Int(out.values.reduce(0, +))
    for id in exact.keys.sorted(by: { (exact[$0]! - exact[$0]!.rounded(.down)) > (exact[$1]! - exact[$1]!.rounded(.down)) }) where left > 0 {
        out[id]! += 1
        left -= 1
    }
    return out.filter { $0.value > 0 }
}

/// Groundworks positions: the private way to direct the Groundworks Fund.
///
/// A position locks staked ERTH (derth) at a validator under an owner tag (a
/// commitment to this wallet the stake proof opens again to update, vote or
/// unlock it); its split is public and weighted by the stake, its owner is
/// not. The stake keeps earning while locked, and unlocking returns
/// it as staked ERTH. Ports PositionsScreen (PrivacyScreens.kt).
///
/// Pushed inside the stream sheet, so its confirmations draw on that sheet's
/// overlay (host `.allocation`).
struct PositionsView: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    @Environment(TxController.self) private var tx

    let groundworks: StreamsModel.State
    let onChanged: () -> Void

    @State private var resplitting: AppModel.OwnedPosition?
    @State private var locking = false
    @State private var pendingLock: LockDraft?
    @State private var lockDraft: LockDraft?

    struct LockDraft: Identifiable {
        let validator: String
        let amount: UInt64
        var id: String { "\(validator)-\(amount)" }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: theme.space.x12) {
                Text("Lock staked ERTH in a position to direct the Groundworks Fund. The split and the amount are public; nothing links the position to you. Locked stake keeps earning, and unlocking returns it as staked ERTH.")
                    .font(EarthType.bodySmall)
                    .foregroundStyle(theme.colors.textTertiary)

                EarthLabel("Your positions")
                if model.positions.isEmpty {
                    Text("No positions yet.")
                        .font(EarthType.bodySmall)
                        .foregroundStyle(theme.colors.textTertiary)
                }
                ForEach(model.positions) { row in
                    EarthCard {
                        Text("\(Figures.balance(BigInt(model.derthValue(row.position.derth, validator: row.position.validator)))) ERTH")
                            .font(EarthType.body).fontWeight(.semibold)
                            .foregroundStyle(theme.colors.textPrimary)
                        Text("\(Figures.balance(BigInt(row.position.derth))) derth · \(moniker(row.position.validator))")
                            .font(EarthType.caption)
                            .foregroundStyle(theme.colors.textTertiary)
                        ForEach(row.position.splits.sorted { $0.value > $1.value }, id: \.key) { id, pct in
                            EarthDetailRow(label: optionName(id), value: "\(pct)%")
                        }
                        HStack(spacing: theme.space.x8) {
                            EarthButton(title: "Change split", role: .secondary) { resplitting = row }
                            EarthButton(title: "Unlock", role: .secondary) { unlock(row) }
                        }
                    }
                }

                if model.privateStake.values.contains(where: { $0 > 0 }) {
                    EarthButton(title: "Lock stake in a position") { locking = true }
                } else {
                    Text("Stake ERTH privately (Earn) to lock it in a position.")
                        .font(EarthType.bodySmall)
                        .foregroundStyle(theme.colors.textTertiary)
                }
            }
            .padding(theme.space.gutter)
        }
        .navigationTitle("Positions")
        .navigationBarTitleDisplayMode(.inline)
        .background(theme.colors.bgPrimary)
        .scrollContentBackground(.hidden)
        .sheet(item: $resplitting) { row in
            AllocationEditSheet(stream: .groundworks, state: groundworks, onChanged: onChanged,
                                initial: row.position.splits, title: "Position split") { split in
                update(row, split)
            }
            .earthThemed()
        }
        .sheet(isPresented: $locking, onDismiss: {
            // The split is chosen in a second sheet, once the first is gone.
            if let p = pendingLock { pendingLock = nil; lockDraft = p }
        }) {
            LockStakeSheet { validator, amount in pendingLock = LockDraft(validator: validator, amount: amount) }
                .earthThemed()
        }
        .sheet(item: $lockDraft) { draft in
            AllocationEditSheet(stream: .groundworks, state: groundworks, onChanged: onChanged,
                                initial: [:], title: "Position split") { split in
                lock(draft, split)
            }
            .earthThemed()
        }
    }

    private func moniker(_ op: String) -> String {
        let m = model.validators.first { $0.operatorAddress == op }?.moniker ?? ""
        return m.isEmpty ? op : m
    }

    private func optionName(_ id: UInt64) -> String {
        let d = groundworks.stream.options.first { $0.id == id }?.description ?? ""
        return d.isEmpty ? "Option \(id)" : d
    }

    private func done() async {
        await model.refresh()
        onChanged()
    }

    private func lock(_ draft: LockDraft, _ split: [UInt64: UInt64]) {
        tx.requestPrivate(.private(
            action: "Lock position",
            rows: [
                ("Locks", "\(Figures.balance(BigInt(draft.amount))) derth (\(Figures.balance(BigInt(model.derthValue(draft.amount, validator: draft.validator)))) ERTH)"),
                ("Validator", moniker(draft.validator)),
            ] + split.sorted { $0.key < $1.key }.map { (optionName($0.key), "\($0.value)%") }
        ), host: .allocation, onSuccess: { await done() }) { w in
            // A stake proof spends two notes: spread over more, it is refused with "merge first".
            return try await w.lockPosition(validator: draft.validator, amount: draft.amount, splits: split)
        }
    }

    private func update(_ row: AppModel.OwnedPosition, _ split: [UInt64: UInt64]) {
        tx.requestPrivate(.private(
            action: "Change position split",
            rows: split.sorted { $0.key < $1.key }.map { (optionName($0.key), "\($0.value)%") }
        ), host: .allocation, onSuccess: { await done() }) { w in
            try await w.updatePosition(row.position, counter: row.counter, splits: split)
        }
    }

    private func unlock(_ row: AppModel.OwnedPosition) {
        tx.requestPrivate(.private(
            action: "Unlock position",
            rows: [("Returns", "\(Figures.balance(BigInt(row.position.derth))) derth (\(Figures.balance(BigInt(model.derthValue(row.position.derth, validator: row.position.validator)))) ERTH)")]
        ), host: .allocation, onSuccess: { await done() }) { w in
            try await w.unlockPosition(row.position, counter: row.counter)
        }
    }
}

/// Which private stake to lock: a validator's derth notes and an amount.
struct LockStakeSheet: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss

    let onNext: (String, UInt64) -> Void

    @State private var validator: String?
    @State private var amount = ""

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: theme.space.x16) {
                    EarthLabel("Lock stake held with")
                    VStack(spacing: 0) {
                        ForEach(model.privateStake.sorted { $0.key < $1.key }, id: \.key) { denom, held in
                            let op = String(denom.dropFirst("derth/".count))
                            EarthListRow(
                                initial: String(moniker(op).prefix(1)).uppercased(),
                                name: moniker(op),
                                subtitle: "\(Figures.balance(BigInt(model.derthValue(held, validator: op)))) ERTH · \(Figures.balance(BigInt(held))) derth",
                                value: validator == op ? "✓" : nil,
                                badgeBackground: theme.colors.accentTint,
                                badgeForeground: theme.colors.accentInk,
                                action: { validator = op }
                            )
                            EarthDivider()
                        }
                    }
                    if validator != nil {
                        EarthLabel("Amount")
                        HStack {
                            TextField("0", text: $amount)
                                .font(EarthType.amountField)
                                .keyboardType(.decimalPad)
                                .onChange(of: amount) { previous, new in
                                    amount = Amounts.filterAmountInput(new, previous: previous)
                                }
                            Button("Max") { amount = Amounts.fromBaseUnits(BigInt(available)) }
                                .font(EarthType.bodySmall)
                                .foregroundStyle(theme.colors.accentInk)
                        }
                    }
                    EarthButton(title: "Next: choose the split") {
                        guard let validator, let v = parsed else { return }
                        onNext(validator, v)
                        dismiss()
                    }
                    .disabled(parsed == nil)
                }
                .padding(theme.space.gutter)
            }
            .navigationTitle("Lock stake")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
            .background(theme.colors.bgPrimary)
            .scrollContentBackground(.hidden)
        }
    }

    private var available: UInt64 {
        guard let validator else { return 0 }
        return model.privateStake[PrivacyWallet.derthDenom(validator)] ?? 0
    }

    private var parsed: UInt64? {
        guard let v = Token.erth.parse(amount), v > 0, let u = UInt64(v.description), u <= available else { return nil }
        return u
    }

    private func moniker(_ op: String) -> String {
        let m = model.validators.first { $0.operatorAddress == op }?.moniker ?? ""
        return m.isEmpty ? op : m
    }
}
