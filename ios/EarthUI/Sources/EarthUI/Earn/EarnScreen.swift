import BigInt
import EarthCore
import SwiftUI

/// Earn: staking and its rewards, and nothing else.
///
/// The daily ANML claim sits on the wallet screen's action row, where it
/// belongs — claiming ANML is a one-tap action on a balance, not a position to
/// manage. Pools are not here either; they hang off the swap tab, because
/// providing liquidity is adjacent to swapping rather than to staking.
///
/// Staked and claimable lead because the common question is how much rather
/// than with whom. Claim is disabled at zero rather than hidden: a button that
/// comes and goes as rewards accrue is harder to find than one always in the
/// same place, and its disabled state answers "is there anything to claim"
/// without being pressed.
struct EarnScreen: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    @Environment(TxController.self) private var tx

    @State private var staking: StakeIntent?

    enum StakeIntent: String, Identifiable { case stake, unstake; var id: String { rawValue } }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Spacer().frame(height: theme.space.x16)
                figures
                Spacer().frame(height: theme.space.x16)

                EarthButton(title: "Claim rewards") { claimAll() }
                    .disabled(model.rewards <= 0)
                Spacer().frame(height: theme.space.x8)
                HStack(spacing: theme.space.x12) {
                    EarthButton(title: "Stake", role: .secondary) { staking = .stake }
                    EarthButton(title: "Unstake", role: .secondary) { staking = .unstake }
                        .disabled(model.delegations.isEmpty)
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
                            value: Figures.whole(delegation.amount),
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
                            value: Figures.whole(entry.balance),
                            badgeBackground: theme.colors.bgSecondary,
                            badgeForeground: theme.colors.textTertiary
                        )
                    }
                }

                Spacer().frame(height: theme.space.x32)
            }
            .padding(.horizontal, theme.space.gutter)
        }
        .refreshable { await model.refresh() }
        .background(theme.colors.bgPrimary)
        .scrollContentBackground(.hidden)
        .sheet(item: $staking) { intent in
            StakeSheet(unstaking: intent == .unstake).earthThemed()
        }
    }

    /// The figures, on the accent tint. There is no Zcash equivalent to borrow
    /// here, so this takes the shape of their address panel: a large-radius
    /// card carrying the numbers, with the actions beneath it.
    private var figures: some View {
        VStack(alignment: .leading, spacing: 0) {
            EarthLabel("Staked")
            Text("\(Figures.whole(model.totalStaked)) ERTH")
                .font(EarthType.headline)
                .foregroundStyle(theme.colors.textPrimary)

            Spacer().frame(height: theme.space.x12)
            EarthLabel("Claimable rewards")
            Text("\(Figures.whole(model.rewards)) ERTH")
                .font(EarthType.headline)
                .foregroundStyle(theme.colors.accentInk)

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
        let named = model.validators.first { $0.operatorAddress == operatorAddress }?.moniker
        // A validator that has left the bonded set still holds the delegation,
        // so a missing join falls back to the operator address rather than
        // dropping the row — stake that does not appear is worse than stake
        // with an ugly label.
        return (named?.isEmpty == false ? named : nil) ?? operatorAddress
    }

    private func commission(_ operatorAddress: String) -> Double? {
        model.validators.first { $0.operatorAddress == operatorAddress }?.commission
    }

    private func subtitle(commission: Double?) -> String {
        guard let commission else { return "" }
        let percent = String(format: "%.0f%%", commission * 100)
        guard let net = StakingApr.forValidator(bondedUerth: bonded, commission: commission) else {
            return "\(percent) commission"
        }
        return "\(percent) commission · \(Figures.rate(net)) APR"
    }

    /// One withdraw per validator, so the gas scales with how many you
    /// delegate to.
    private func claimAll() {
        let validators = model.delegations.map(\.validator)
        tx.request(.init(
            action: "Claim rewards",
            rows: [
                ("Rewards", "\(Figures.whole(model.rewards)) ERTH"),
                ("From", Figures.count(validators.count, "validator")),
            ],
            gasLimit: TransactionSigner.defaultGasLimit + UInt64(150_000 * validators.count)
        )) { key in
            validators.map { model.client.msgWithdrawReward(delegator: key.address, validator: $0) }
        }
    }
}
