import BigInt
import EarthCore
import SwiftUI

/// Earn: the two ways to put capital to work — staking, and pools.
///
/// The daily ANML claim sits on the wallet screen's action row, where it
/// belongs: claiming ANML is a one-tap action on a balance, not a position to
/// manage.
///
/// Pools live here, not off the swap tab, for two reasons. Hung off swap, the
/// pool list would be a sheet deep and the deposit sheet a second sheet deep,
/// and a transaction raised from there would have its confirmation drawn
/// *behind* both — the overlay is hosted on a view, and a view cannot draw
/// over what is presented on top of it. And the question "where do I earn on
/// what I hold" has one answer, not two places to look. Staking and pools sit
/// one selector apart, and the deposit sheet is a single presentation from a
/// tab like every other.
///
/// Staked leads because the common question is how much rather than with whom.
/// There is no claim: private stake compounds into its validator's rate, and a
/// validator's self-bond rewards compound into the self-bond at the epoch end.
struct EarnScreen: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    @Environment(TxController.self) private var tx

    @State private var staking: StakeIntent?
    @State private var mode = Mode.stake

    enum StakeIntent: String, Identifiable { case stake, unstake, move; var id: String { rawValue } }

    enum Mode: Hashable { case stake, liquidity }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Spacer().frame(height: theme.space.x16)
                Picker("", selection: $mode) {
                    Text("Stake").tag(Mode.stake)
                    Text("Liquidity").tag(Mode.liquidity)
                }
                .pickerStyle(.segmented)
                Spacer().frame(height: theme.space.x16)

                if mode == .liquidity {
                    PoolList()
                } else {
                    stakeContent
                }

                Spacer().frame(height: theme.space.x32)
            }
            .padding(.horizontal, theme.space.gutter)
        }
        .refreshable { await model.refresh() }
        .background(theme.colors.bgPrimary)
        .scrollContentBackground(.hidden)
        .sheet(item: $staking) { intent in
            if intent == .move {
                MoveStakeSheet().earthThemed()
            } else {
                StakeSheet(unstaking: intent == .unstake).earthThemed()
            }
        }
    }

    private var stakeContent: some View {
        VStack(alignment: .leading, spacing: 0) {
                figures
                Spacer().frame(height: theme.space.x16)

                HStack(spacing: theme.space.x12) {
                    EarthButton(title: "Stake", role: .secondary) { staking = .stake }
                    EarthButton(title: "Unstake", role: .secondary) { staking = .unstake }
                        .disabled(model.privateStake.isEmpty)
                }
                Spacer().frame(height: theme.space.x8)
                EarthButton(title: "Move stake", role: .secondary) { staking = .move }
                    .disabled(!model.stakeHoldings.contains { $0.free > 0 })
                Text("Staking is private: shielded ERTH becomes staked ERTH (derth) whose value rises each epoch as rewards compound. Nothing links it to you.")
                    .font(EarthType.caption)
                    .foregroundStyle(theme.colors.textTertiary)
                    .padding(.top, theme.space.x8)

                if !model.privateStake.isEmpty {
                    Spacer().frame(height: theme.space.x24)
                    EarthLabel("Your private stake")
                    ForEach(model.privateStake.sorted { $0.key < $1.key }, id: \.key) { denom, amount in
                        let op = String(denom.dropFirst("derth/".count))
                        let h = model.stakeHoldings.first { $0.validator == op }
                        // Moved-in stake stays where it is until its window
                        // closes (a slash of the validator it left can still
                        // reach it), and a second note here (beside such
                        // stake, or from another device) merges on a tap.
                        let parts = [
                            h?.lockedUntil.map { "\(Figures.balance(BigInt(h!.locked))) derth can move again after \(PrivacyWallet.dateText($0))" },
                            (h?.notes ?? 0) > 1 ? (h!.mergeable ? "\(h!.notes) notes · tap to merge" : "\(h!.notes) notes") : nil,
                        ].compactMap { $0 }
                        EarthListRow(
                            initial: String(moniker(op).prefix(1)).uppercased(),
                            name: moniker(op),
                            subtitle: parts.isEmpty ? "\(Figures.balance(BigInt(amount))) derth · " + subtitle(commission: commission(op)) : parts.joined(separator: " · "),
                            value: "\(Figures.balance(BigInt(model.derthValue(amount, validator: op)))) ERTH",
                            badgeBackground: theme.colors.accentTint,
                            badgeForeground: theme.colors.accentInk,
                            action: h?.mergeable == true ? { merge(op) } : nil
                        )
                    }
                }

                if !model.pendingUnbonds.isEmpty {
                    // Paid out by the chain itself once the unbonding period
                    // ends: nothing to claim. From this wallet's own record.
                    Spacer().frame(height: theme.space.x24)
                    EarthLabel("Unstaking (private)")
                    ForEach(model.pendingUnbonds, id: \.txHash) { u in
                        EarthListRow(
                            initial: String(moniker(u.validator).prefix(1)).uppercased(),
                            name: moniker(u.validator),
                            subtitle: u.dueBy.map { "Arrives by about " + Date(timeIntervalSince1970: TimeInterval($0)).formatted(date: .abbreviated, time: .shortened) }
                                ?? "Arrives once the unbonding period ends",
                            value: Figures.balance(BigInt(u.value ?? model.derthValue(u.derth, validator: u.validator))),
                            badgeBackground: theme.colors.bgSecondary,
                            badgeForeground: theme.colors.textTertiary
                        )
                    }
                }

                if !model.delegations.isEmpty {
                    Spacer().frame(height: theme.space.x24)
                    EarthLabel("Your validators")
                    ForEach(model.delegations) { delegation in
                        let commission = self.commission(delegation.validator)
                        EarthListRow(
                            initial: String(moniker(delegation.validator).prefix(1)).uppercased(),
                            name: moniker(delegation.validator),
                            // Commission and the rate it leaves, together: the
                            // commission alone is only half the comparison
                            // anyone is making between validators.
                            subtitle: subtitle(commission: commission),
                            value: Figures.balance(delegation.amount),
                            badgeBackground: theme.colors.accentTint,
                            badgeForeground: theme.colors.accentInk
                        )
                    }
                }

                if !model.unbondings.isEmpty {
                    Spacer().frame(height: theme.space.x24)
                    EarthLabel("Unbonding")
                    // Unbonding stake is neither spendable nor earning, and it
                    // returns on its own — so it is listed apart from the
                    // delegations rather than mixed in, with the date rather
                    // than a commission.
                    ForEach(Array(model.unbondings.enumerated()), id: \.offset) { _, entry in
                        EarthListRow(
                            initial: String(moniker(entry.validator).prefix(1)).uppercased(),
                            name: moniker(entry.validator),
                            subtitle: "Returns \(entry.completionTime.prefix(10))",
                            value: Figures.balance(entry.balance),
                            badgeBackground: theme.colors.bgSecondary,
                            badgeForeground: theme.colors.textTertiary
                        )
                    }
                }

        }
    }

    /// The figures, on the accent tint. There is no Zcash equivalent to borrow
    /// here, so this takes the shape of their address panel: a large-radius
    /// card carrying the numbers, with the actions beneath it.
    private var figures: some View {
        VStack(alignment: .leading, spacing: 0) {
            EarthLabel("Staked")
            // `display` rather than `balance`: six decimals at headline size
            // overflows the panel. `minimumScaleFactor` is the backstop for the
            // figure that is long anyway — a shrunk number is readable, a
            // truncated one is wrong.
            // Private stake at its validators' live rates: derth is a claim on
            // ERTH that grows each epoch, so its face value under-reports it.
            Text("\(Figures.display(model.totalStaked + BigInt(model.privateStakeValue))) ERTH")
                .font(EarthType.headline)
                .foregroundStyle(theme.colors.textPrimary)
                .lineLimit(1)
                .minimumScaleFactor(0.6)

            Spacer().frame(height: theme.space.x12)
            EarthLabel("Pending rewards")
            Text("\(Figures.display(model.rewards)) ERTH")
                .font(EarthType.headline)
                .foregroundStyle(theme.colors.accentInk)
                .lineLimit(1)
                .minimumScaleFactor(0.6)

            if let rate = StakingApr.base(bondedUerth: bonded) {
                Spacer().frame(height: theme.space.x12)
                EarthDivider()
                Spacer().frame(height: theme.space.x12)
                HStack(alignment: .center) {
                    VStack(alignment: .leading, spacing: 0) {
                        Text("Estimated APR")
                            .font(EarthType.bodySmall)
                            .foregroundStyle(theme.colors.textSecondary)
                        // The single most useful thing to say about this
                        // number: it is not a policy the chain is aiming at,
                        // it is a fixed stream divided by however much stake
                        // is competing for it.
                        Text("1 ERTH/sec across \(Figures.whole(model.totalBonded)) ERTH staked")
                            .font(EarthType.caption)
                            .foregroundStyle(theme.colors.textTertiary)
                    }
                    Spacer(minLength: theme.space.x8)
                    Text(Figures.rate(rate))
                        .font(EarthType.body).fontWeight(.semibold)
                        .foregroundStyle(theme.colors.accentInk)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(theme.space.x16)
        .background(theme.colors.accentTint, in: .rect(cornerRadius: 20))
    }

    private var bonded: Int64 { Int64(model.totalBonded.description) ?? 0 }

    private func moniker(_ operatorAddress: String) -> String {
        // Joined against the validator list (every status). A validator the
        // list lacks still holds the delegation, so a missing join falls back
        // to the operator address rather than dropping the row — stake that
        // does not appear is worse than stake with an ugly label.
        model.moniker(of: operatorAddress)
    }

    private func commission(_ operatorAddress: String) -> Double? {
        model.commission(of: operatorAddress)
    }

    private func subtitle(commission: Double?) -> String {
        guard let commission else { return "" }
        let percent = String(format: "%.0f%%", commission * 100)
        guard let net = StakingApr.forValidator(bondedUerth: bonded, commission: commission) else {
            return "\(percent) commission"
        }
        return "\(percent) commission · \(Figures.rate(net)) APR"
    }

    /// Merge a validator's notes into one (MsgRestake), on the user's tap only.
    private func merge(_ validator: String) {
        tx.requestPrivate(.private(
            action: "Merge stake notes",
            rows: [
                ("Validator", moniker(validator)),
                ("Fee (estimate)", "\(Token.erth.format(Fees.forGas(PrivacyWallet.privateGasEstimate))) ERTH, shielded"),
            ]
        ), onSuccess: { await model.refresh() }) { w in
            try await w.restake(validator: validator)
        }
    }
}
