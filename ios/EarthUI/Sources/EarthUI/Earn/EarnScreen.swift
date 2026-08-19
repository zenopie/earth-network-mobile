import BigInt
import EarthCore
import SwiftUI

/// The two ways to put ERTH to work: bond it to a validator, or provide
/// liquidity to a pool.
struct EarnScreen: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model

    var body: some View {
        EarthScreen {
            summary
            StakingSection()
            LiquiditySection()
        }
    }

    private var summary: some View {
        VStack(alignment: .leading, spacing: 0) {
            EarthLabel("Staked")
            Text(Figures.whole(model.totalStaked))
                .font(EarthType.display)
                .foregroundStyle(theme.colors.textPrimary)
                .lineLimit(1)
                .minimumScaleFactor(0.4)
            if let apr = StakingApr.base(bondedUerth: Int64(model.totalBonded.description) ?? 0) {
                // A rate on a young chain with very little bonded is enormous
                // and means very little, so it reads as an estimate rather
                // than as a promise.
                Text("Network rate \(percent(apr)) · \(Figures.precise(model.rewards)) ERTH claimable")
                    .font(EarthType.bodySmall)
                    .foregroundStyle(theme.colors.textTertiary)
            }
        }
    }
}

struct StakingSection: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    @Environment(TxController.self) private var tx

    @State private var staking: Staking.Validator?

    var body: some View {
        VStack(alignment: .leading, spacing: theme.space.x12) {
            EarthSectionHeader(title: "Staking",
                               trailing: Figures.count(model.validators.count, "validator"))

            if !model.delegations.isEmpty {
                ForEach(Array(model.delegations.enumerated()), id: \.element.validator) { index, delegation in
                    if index > 0 { EarthDivider() }
                    EarthRow(
                        title: moniker(delegation.validator),
                        subtitle: "Delegated",
                        value: Figures.amount(delegation.amount, .erth)
                    )
                }
                EarthDivider()
            }

            if !model.unbondings.isEmpty {
                // Between submitting an unbond and it landing there is nothing
                // in the balance to show for it — the stake has left and the
                // funds have not arrived. Without this the three weeks look
                // like the money went nowhere.
                ForEach(Array(model.unbondings.enumerated()), id: \.offset) { index, entry in
                    if index > 0 { EarthDivider() }
                    EarthRow(
                        title: "Unbonding",
                        subtitle: moniker(entry.validator),
                        value: Figures.amount(entry.balance, .erth),
                        valueMuted: true
                    )
                }
                EarthDivider()
            }

            if model.rewards > 0 {
                EarthButton(title: "Claim \(Figures.precise(model.rewards)) ERTH", role: .secondary) {
                    claimAll()
                }
            }

            if model.validators.isEmpty {
                EarthEmpty(systemName: "shield", title: "No bonded validators")
            } else {
                ForEach(Array(model.validators.prefix(10).enumerated()), id: \.element.operatorAddress) { index, validator in
                    if index > 0 { EarthDivider() }
                    EarthRow(
                        title: validator.moniker.isEmpty ? "Validator" : validator.moniker,
                        subtitle: "\(percent(validator.commission)) commission",
                        value: Figures.amount(validator.tokens, .erth),
                        action: { staking = validator }
                    )
                }
            }
        }
        .sheet(item: $staking) { validator in
            StakeSheet(validator: validator).earthThemed()
        }
    }

    private func moniker(_ operatorAddress: String) -> String {
        model.validators.first { $0.operatorAddress == operatorAddress }?.moniker ?? operatorAddress
    }

    /// One message per validator, in one transaction — x/distribution pays per
    /// delegation, so claiming "everything" is genuinely several claims.
    private func claimAll() {
        let validators = model.delegations.map(\.validator)
        tx.request(.init(
            action: "Claim rewards",
            rows: [
                ("Amount", "\(Token.erth.format(model.rewards)) ERTH"),
                ("From", "\(validators.count) validator\(validators.count == 1 ? "" : "s")"),
            ],
            gasLimit: UInt64(200_000 + 100_000 * validators.count)
        )) { key in
            validators.map { model.client.msgWithdrawReward(delegator: key.address, validator: $0) }
        }
    }
}

struct ValidatorRow: View {
    @Environment(\.earth) private var theme
    let validator: Staking.Validator

    var body: some View {
        HStack(spacing: theme.space.x12) {
            EarthGlyph(systemName: "shield.fill")
            VStack(alignment: .leading, spacing: theme.space.x2) {
                Text(validator.moniker.isEmpty ? "Validator" : validator.moniker)
                    .font(EarthType.title)
                    .foregroundStyle(theme.colors.textPrimary)
                    .lineLimit(1)
                Text("\(percent(validator.commission)) commission")
                    .font(EarthType.bodySmall)
                    .foregroundStyle(theme.colors.textTertiary)
            }
            Spacer()
            Text("\(Token.erth.format(validator.tokens)) ERTH")
                .font(EarthType.amount)
                .foregroundStyle(theme.colors.textSecondary)
            Image(systemName: "chevron.right")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(theme.colors.textDisabled)
        }
        .padding(theme.space.x8)
        .contentShape(.rect)
    }
}

struct DelegationRow: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    let delegation: Staking.Delegation

    var body: some View {
        HStack(spacing: theme.space.x12) {
            EarthGlyph(systemName: "checkmark.shield.fill")
            VStack(alignment: .leading, spacing: theme.space.x2) {
                Text(moniker)
                    .font(EarthType.title)
                    .foregroundStyle(theme.colors.textPrimary)
                    .lineLimit(1)
                Text("Delegated")
                    .font(EarthType.bodySmall)
                    .foregroundStyle(theme.colors.textTertiary)
            }
            Spacer()
            Text("\(Token.erth.format(delegation.amount)) ERTH")
                .font(EarthType.amount)
                .foregroundStyle(theme.colors.textPrimary)
        }
        .padding(theme.space.x8)
    }

    private var moniker: String {
        model.validators.first { $0.operatorAddress == delegation.validator }?.moniker
            ?? delegation.validator
    }
}

struct StakeSheet: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    @Environment(TxController.self) private var tx
    @Environment(\.dismiss) private var dismiss

    let validator: Staking.Validator
    @State private var amount = ""
    @State private var unbonding = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: theme.space.x16) {
                    EarthCard {
                        EarthDetailRow(label: "Validator", value: validator.moniker)
                        EarthDetailRow(label: "Commission", value: percent(validator.commission))
                        if let rate = StakingApr.forValidator(
                            bondedUerth: Int64(model.totalBonded.description) ?? 0,
                            commission: validator.commission
                        ) {
                            EarthDetailRow(label: "Estimated rate", value: percent(rate))
                        }
                    }

                    Picker("Action", selection: $unbonding) {
                        Text("Stake").tag(false)
                        Text("Unstake").tag(true)
                    }
                    .pickerStyle(.segmented)

                    EarthCard {
                        HStack {
                            EarthLabel("Amount")
                            Spacer()
                            Button("Max") { amount = Token.erth.format(available) }
                                .font(EarthType.bodySmall)
                                .foregroundStyle(theme.colors.accentInk)
                        }
                        HStack {
                            TextField("0", text: $amount)
                                .font(EarthType.display)
                                .keyboardType(.decimalPad)
                                .onChange(of: amount) { previous, new in
                                    amount = Amounts.filterAmountInput(new, previous: previous)
                                }
                            Text("ERTH").font(EarthType.title).foregroundStyle(theme.colors.textTertiary)
                        }
                        Text("Available \(Token.erth.format(available)) ERTH")
                            .font(EarthType.bodySmall)
                            .foregroundStyle(theme.colors.textTertiary)
                    }

                    if unbonding {
                        // Worth saying before the tap rather than after: the
                        // stake stops earning immediately and arrives weeks
                        // later, and nothing on screen shows it in between
                        // except the unbonding row above.
                        Text("Unstaked ERTH is locked for the chain's unbonding period and earns nothing while it waits.")
                            .font(EarthType.bodySmall)
                            .foregroundStyle(theme.colors.textTertiary)
                    }

                    EarthButton(title: unbonding ? "Review unstake" : "Review stake") { review() }
                        .disabled(parsed == nil)
                }
                .padding(theme.space.gutter)
            }
            .navigationTitle(validator.moniker.isEmpty ? "Validator" : validator.moniker)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
            .earthBackground()
            .scrollContentBackground(.hidden)
        }
    }

    private var staked: BigInt {
        model.delegations.first { $0.validator == validator.operatorAddress }
            .flatMap { BigInt($0.amount) } ?? 0
    }

    private var available: BigInt {
        if unbonding { return staked }
        // Leave the fee behind, or the delegation cannot be paid for.
        let fee = BigInt(TransactionSigner.defaultFeeUerth) ?? 0
        return max(0, model.balance(.erth) - fee)
    }

    private var parsed: BigInt? {
        guard let value = Token.erth.parse(amount), value > 0, value <= available else { return nil }
        return value
    }

    private func review() {
        guard let value = parsed else { return }
        let target = validator.operatorAddress
        let unstaking = unbonding
        tx.request(.init(
            action: unstaking ? "Unstake" : "Stake",
            rows: [
                ("Amount", "\(Token.erth.format(value)) ERTH"),
                ("Validator", validator.moniker.isEmpty ? target : validator.moniker),
            ]
        )) { key in
            [unstaking
                ? model.client.msgUndelegate(delegator: key.address, validator: target, amountUerth: String(value))
                : model.client.msgDelegate(delegator: key.address, validator: target, amountUerth: String(value))]
        }
        dismiss()
    }
}

struct LiquiditySection: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model

    var body: some View {
        VStack(alignment: .leading, spacing: theme.space.x12) {
            EarthSectionHeader(title: "Liquidity",
                               trailing: Figures.count(model.pools.count, "pool"))

            if model.pools.isEmpty {
                EarthEmpty(systemName: "drop", title: "No pools yet")
            } else {
                ForEach(model.pools, id: \.id) { pool in
                    PoolCard(pool: pool)
                }
            }
        }
    }
}

struct PoolCard: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    let pool: Dex.Pool

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            EarthRow(
                title: "ERTH / \(token.symbol)",
                // Split apart because the two halves behave differently. Fees
                // scale with the pool; emissions do not — a deposit does not
                // change the pool's share of the stream, it splits the same
                // emission across more capital, so that half falls the moment
                // you add to it.
                subtitle: apr.map { "\(percent($0.fee)) fees · \(percent($0.emission)) emissions" }
                    ?? "No liquidity",
                value: apr.map { percent($0.total) }
            )
            Text("\(Figures.amount(pool.erthReserve, .erth)) · \(Figures.amount(pool.tokenReserve, token))")
                .font(EarthType.bodySmall)
                .foregroundStyle(theme.colors.textTertiary)
                .padding(.bottom, theme.space.x12)
        }
    }

    private var token: Token {
        Token.named(pool.tokenDenom) ?? Token.unknown(denom: pool.tokenDenom)
    }

    private var apr: PoolApr? {
        AprMath.apr(
            for: pool,
            allPools: model.pools,
            lpOptionShare: model.lpOptionShare,
            swapFeePercent: model.swapFeePercent
        )
    }
}

/// A fraction as a percentage. Capped in words rather than digits at the top
/// end: a four-figure rate on a nearly-unbonded chain is true and useless.
func percent(_ value: Double) -> String {
    if value >= 10 { return ">1000%" }
    return String(format: "%.2f%%", value * 100)
}
