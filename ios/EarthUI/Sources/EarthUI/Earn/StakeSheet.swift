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
/// which are the part that matters. Laid out like Swap: the amount big, Max,
/// the validator as one row (its picker a push away), one button.
struct StakeSheet: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    @Environment(TxController.self) private var tx
    @Environment(\.dismiss) private var dismiss

    let unstaking: Bool

    @State private var validator: String?
    @State private var amount = ""
    @State private var picking = false
    /// The whole validator list (Query/Validators, every page): never a read about one validator.
    @State private var list: PrivacyReads.ValidatorList? = PrivacyQueries.cachedValidators

    init(unstaking: Bool, validator: String? = nil) {
        self.unstaking = unstaking
        _validator = State(initialValue: validator)
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                StakeAmountField(amount: $amount, unit: unstaking ? "derth" : "ERTH",
                                 available: available, error: error)
                Spacer().frame(height: theme.space.x24)
                ValidatorSelectRow(label: unstaking ? "From" : "To", validator: validator) { picking = true }
                Spacer(minLength: theme.space.x24)
                EarthPillButton(title: unstaking ? "Unstake" : "Stake") { review() }
                    .disabled(parsed == nil)
                Spacer().frame(height: theme.space.x16)
            }
            .padding(.horizontal, theme.space.gutter)
            .navigationTitle(unstaking ? "Unstake" : "Stake")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
            .background(theme.colors.bgPrimary)
            .navigationDestination(isPresented: $picking) {
                ValidatorPicker(title: unstaking ? "Unstake from" : "Stake with", options: options, selected: validator) { pick($0) }
            }
            .onAppear {
                // One place it can go: chosen. Several to unstake from: pick first.
                if validator == nil, options.filter(\.enabled).count == 1 { validator = options.first { $0.enabled }?.validator }
                else if validator == nil, unstaking, options.count > 1 { picking = true }
            }
            .task {
                guard !unstaking else { return }
                if let fresh = try? await PrivacyQueries(rest: model.client.rest).validators() { list = fresh }
            }
        }
    }

    private func pick(_ option: String) {
        if validator != option { amount = "" }
        validator = option
        picking = false
    }

    /// Staking: every validator the chain lists, active first, the chain's
    /// order within each standing: unsorted by stake, which would only
    /// concentrate it. Unstaking: where private stake may leave now
    /// (moved-in stake whose window is open stays where it is).
    private var options: [ValidatorPicker.Option] {
        if unstaking {
            return model.stakeHoldings.filter { $0.free > 0 }.map { h in
                .init(validator: h.validator, detail: "\(Figures.display(BigInt(model.derthValue(h.free, validator: h.validator)))) ERTH", enabled: true)
            }
        }
        let all = list?.validators ?? []
        return all.enumerated().sorted { a, b in
            let (ra, rb) = (StakeRound.Standing(a.element).rank, StakeRound.Standing(b.element).rank)
            return ra != rb ? ra < rb : a.offset < b.offset
        }.map { v in
            let standing = StakeRound.Standing(v.element)
            let open = model.stakeTargets.contains(v.element.validator) && standing == .active
            return .init(validator: v.element.validator,
                         detail: open ? ValidatorPicker.commission(model.commission(of: v.element.validator)) : standing.label,
                         enabled: open)
        }
    }

    private func moniker(_ op: String) -> String {
        model.moniker(of: op)
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

    private var error: String? {
        guard !amount.isEmpty, let value = Token.erth.parse(amount) else { return nil }
        return value > available ? "More than available" : nil
    }

    private var parsed: BigInt? {
        guard validator != nil,
              let value = Token.erth.parse(amount), value > 0, value <= available
        else { return nil }
        return value
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
                    // The quote: the value at the validator's live rate, and
                    // what clearing a moved-in label costs, sent as shown.
                    let q = try await w.quoteUndelegate(validator: validator, amount: amount)
                    let cut = q.haircut
                    tx.requestPrivate(.private(
                        action: "Unstake",
                        rows: [
                            ("Amount", "\(Figures.balance(value)) derth (\(Figures.balance(BigInt(q.value))) ERTH)"),
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
                        notes: [StakeNotes.haircut(q.haircut, from: nil)].compactMap { $0 }
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

/// The amount, big and centred as on Swap, with what is available and Max
/// under it, and an error only when there is one.
struct StakeAmountField: View {
    @Environment(\.earth) private var theme
    @Binding var amount: String
    let unit: String
    let available: BigInt
    var error: String?

    var body: some View {
        VStack(spacing: 0) {
            Spacer().frame(height: theme.space.x32)
            TextField("0", text: $amount)
                .font(.system(size: 56, weight: .semibold).monospacedDigit())
                .foregroundStyle(theme.colors.textPrimary)
                .multilineTextAlignment(.center)
                .lineLimit(1)
                .minimumScaleFactor(0.4)
                .keyboardType(.decimalPad)
                .onChange(of: amount) { previous, new in
                    amount = Amounts.filterAmountInput(new, previous: previous)
                }
            Text(unit)
                .font(EarthType.body)
                .foregroundStyle(theme.colors.textTertiary)
            Spacer().frame(height: theme.space.x16)
            HStack(spacing: theme.space.x8) {
                Text("\(Figures.display(available)) available")
                    .font(EarthType.bodySmall)
                    .foregroundStyle(theme.colors.textTertiary)
                if available > 0 {
                    AmountChip(label: "Max") { amount = Amounts.fromBaseUnits(available) }
                }
            }
            Text(error ?? " ")
                .font(EarthType.bodySmall)
                .foregroundStyle(theme.colors.textError)
                .padding(.top, theme.space.x8)
        }
        .frame(maxWidth: .infinity)
    }
}

/// The validator, as one row in a Swap panel's style: tap to choose.
struct ValidatorSelectRow: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    let label: String
    let validator: String?
    /// nil: fixed, nothing to choose.
    var action: (() -> Void)?

    var body: some View {
        Button { action?() } label: {
            HStack(spacing: theme.space.x12) {
                ValidatorMark(validator: validator, size: 40, muted: validator == nil)
                VStack(alignment: .leading, spacing: 0) {
                    Text(label)
                        .font(EarthType.caption)
                        .foregroundStyle(theme.colors.textTertiary)
                    Text(validator.map(model.moniker(of:)) ?? "Choose validator")
                        .font(EarthType.body).fontWeight(.semibold)
                        .foregroundStyle(validator == nil ? theme.colors.textSecondary : theme.colors.textPrimary)
                        .lineLimit(1)
                }
                Spacer(minLength: theme.space.x8)
                if action != nil {
                    Image(systemName: "chevron.right")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(theme.colors.textTertiary)
                }
            }
            .padding(theme.space.x16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(theme.colors.bgSecondary, in: .rect(cornerRadius: 20))
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .disabled(action == nil)
    }
}

/// Choose a validator: initial, name, one small figure (commission, or what
/// you hold there), a check on the chosen one. Ones that cannot be picked are
/// greyed with their standing.
struct ValidatorPicker: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model

    struct Option: Identifiable {
        let validator: String
        let detail: String
        let enabled: Bool
        var id: String { validator }
    }

    let title: String
    let options: [Option]
    let selected: String?
    let pick: (String) -> Void

    static func commission(_ c: Double?) -> String {
        String(format: "%.0f%% commission", (c ?? 0) * 100)
    }

    var body: some View {
        ScrollView {
            LazyVStack(spacing: 0) {
                if options.isEmpty {
                    Text("None")
                        .font(EarthType.body)
                        .foregroundStyle(theme.colors.textTertiary)
                        .padding(.vertical, theme.space.x32)
                }
                ForEach(options) { o in
                    Button { pick(o.validator) } label: {
                        HStack(spacing: theme.space.x12) {
                            ValidatorMark(validator: o.validator, size: 44, muted: !o.enabled)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(model.moniker(of: o.validator))
                                    .font(EarthType.body).fontWeight(.semibold)
                                    .foregroundStyle(o.enabled ? theme.colors.textPrimary : theme.colors.textTertiary)
                                    .lineLimit(1)
                                Text(o.detail)
                                    .font(EarthType.caption)
                                    .foregroundStyle(theme.colors.textTertiary)
                                    .lineLimit(1)
                            }
                            Spacer(minLength: theme.space.x8)
                            if o.validator == selected {
                                Image(systemName: "checkmark.circle.fill")
                                    .font(.system(size: 22))
                                    .foregroundStyle(theme.colors.accentInk)
                            }
                        }
                        .padding(.vertical, theme.space.x12)
                        .contentShape(.rect)
                    }
                    .buttonStyle(.plain)
                    .disabled(!o.enabled)
                }
            }
            .padding(.horizontal, theme.space.gutter)
        }
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .background(theme.colors.bgPrimary)
        .scrollContentBackground(.hidden)
    }
}
