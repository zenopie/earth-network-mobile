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
    /// The round's end (unix), for the line on when new stake joins its validator.
    let roundEnds: Int64?
    let unbondingSeconds: Int64?

    @State private var validator: String?
    @State private var amount = ""
    /// The whole validator list (Query/Validators, every page): never a read about one validator.
    @State private var list: PrivacyReads.ValidatorList? = PrivacyQueries.cachedValidators

    init(unstaking: Bool, validator: String? = nil, roundEnds: Int64? = nil, unbondingSeconds: Int64? = nil) {
        self.unstaking = unstaking
        self.roundEnds = roundEnds
        self.unbondingSeconds = unbondingSeconds
        _validator = State(initialValue: validator)
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: theme.space.x16) {
                    EarthLabel(unstaking ? "Take back from" : "Stake with")
                    VStack(spacing: theme.space.x8) {
                        if unstaking ? choices.isEmpty : rows.isEmpty {
                            Text(unstaking ? "No private stake that can leave now." : "No validators to stake with.")
                                .font(EarthType.bodySmall)
                                .foregroundStyle(theme.colors.textTertiary)
                        }
                        if unstaking {
                            ForEach(choices, id: \.self) { option in
                                ValidatorPickRow(validator: option, detail: subtitle(option), standing: nil,
                                                 selected: validator == option, enabled: true) { pick(option) }
                            }
                        } else {
                            ForEach(rows, id: \.validator) { v in
                                let standing = StakeRound.Standing(v)
                                let open = model.stakeTargets.contains(v.validator) && standing == .active
                                ValidatorPickRow(validator: v.validator, detail: open ? subtitle(v.validator) : (standing.reason ?? ""),
                                                 standing: standing, selected: validator == v.validator, enabled: open) { pick(v.validator) }
                            }
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
                        HStack(spacing: 5) {
                            Image(systemName: "lock.fill").font(.system(size: 10))
                                .foregroundStyle(theme.colors.accentInk)
                            Text(unstaking
                                 ? "Staked \(Figures.balance(available)) derth, worth \(Figures.balance(BigInt(model.derthValue(UInt64(available.description) ?? 0, validator: validator ?? "")))) ERTH"
                                 : "From private ERTH · \(Figures.balance(available)) available")
                                .font(EarthType.bodySmall)
                                .foregroundStyle(theme.colors.textTertiary)
                        }

                        Text(unstaking ? unstakeNote : stakeNote)
                            .font(EarthType.bodySmall)
                            .foregroundStyle(theme.colors.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
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
            .task {
                guard !unstaking else { return }
                if let fresh = try? await PrivacyQueries(rest: model.client.rest).validators() { list = fresh }
            }
        }
    }

    private func pick(_ option: String) {
        if validator != option { amount = "" }
        validator = option
    }

    /// Every validator the chain lists, active first, the chain's order
    /// within each standing: unsorted by stake, which would only concentrate it.
    private var rows: [PrivacyReads.ValidatorQuote] {
        let all = list?.validators ?? []
        return all.enumerated().sorted { a, b in
            let (ra, rb) = (StakeRound.Standing(a.element).rank, StakeRound.Standing(b.element).rank)
            return ra != rb ? ra < rb : a.offset < b.offset
        }.map(\.element)
    }

    private var stakeNote: String {
        let name = validator.map(moniker) ?? "the validator"
        let when = roundEnds.map { " at \(StakeRoundModel.clock($0))" } ?? " when today's round ends"
        // Queued ERTH earns nothing until the round's end delegates it
        // (rewards accrue only on what is delegated). derth is not a coin: a
        // stake note only its owner can merge, vote, lock or unstake.
        return "It starts earning when it joins \(name)\(when). Staked ERTH stays private and locked to this wallet: it can't be sent or traded, only unstaked or moved."
    }

    /// Worth saying before the tap rather than after: the stake stops earning
    /// at once and arrives later, with nothing on screen in between but its card.
    private var unstakeNote: String {
        let days = unbondingSeconds.map { " (about \(max(1, $0 / 86_400)) days)" } ?? ""
        return "It stops earning now and is paid to your private ERTH automatically after the unbonding period\(days). Nothing more to do or pay."
            + (model.stakeHoldings.contains { $0.locked > 0 } ? " Stake moved here recently can be unstaked once its window closes." : "")
    }

    /// Unstaking can only come from somewhere private stake may leave now
    /// (moved-in stake whose window is open stays where it is).
    private var choices: [String] {
        guard unstaking else { return model.stakeTargets }
        return model.stakeHoldings.filter { $0.free > 0 }.map(\.validator)
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
        let c = model.commission(of: option) ?? 0
        let commission = String(format: "%.0f%% commission", c * 100)
        guard let rate = StakingApr.forValidator(bondedUerth: Int64(model.totalBonded.description) ?? 0, commission: c) else { return commission }
        let held = model.privateStake[PrivacyWallet.derthDenom(option)].map { " · you have \(Figures.whole(BigInt(model.derthValue($0, validator: option)))) ERTH" } ?? ""
        return commission + " · " + Figures.rate(rate) + " APR" + held
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

/// A validator in a picker: its initial, name, one line on its terms or why it
/// cannot be picked, and its standing.
struct ValidatorPickRow: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    let validator: String
    let detail: String
    let standing: StakeRound.Standing?
    let selected: Bool
    let enabled: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Text(String(model.moniker(of: validator).prefix(1)).uppercased())
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(enabled ? theme.colors.accentInk : theme.colors.textTertiary)
                    .frame(width: 36, height: 36)
                    .background(enabled ? theme.colors.accentTint : theme.colors.bgTertiary, in: .circle)
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Text(model.moniker(of: validator))
                            .font(EarthType.title)
                            .foregroundStyle(enabled ? theme.colors.textPrimary : theme.colors.textSecondary)
                            .lineLimit(1)
                        if let standing {
                            Text(standing.label)
                                .font(EarthType.caption).fontWeight(.medium)
                                .foregroundStyle(standing == .active ? theme.colors.accentInk : theme.colors.textTertiary)
                                .padding(.horizontal, 6).padding(.vertical, 2)
                                .background(standing == .active ? theme.colors.accentTint : theme.colors.bgTertiary, in: .capsule)
                        }
                    }
                    Text(detail)
                        .font(EarthType.caption)
                        .foregroundStyle(enabled ? theme.colors.textTertiary : theme.colors.textSecondary)
                        .lineLimit(2)
                        .multilineTextAlignment(.leading)
                }
                Spacer(minLength: 8)
                Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 20))
                    .foregroundStyle(selected ? theme.colors.accentInk : theme.colors.strokePrimary)
                    .opacity(enabled ? 1 : 0)
            }
            .padding(12)
            .background(selected ? theme.colors.accentTint.opacity(0.5) : theme.colors.bgSecondary,
                        in: .rect(cornerRadius: theme.space.radiusMd))
            .overlay {
                RoundedRectangle(cornerRadius: theme.space.radiusMd)
                    .strokeBorder(selected ? theme.colors.accentInk : .clear, lineWidth: 1.5)
            }
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }
}
