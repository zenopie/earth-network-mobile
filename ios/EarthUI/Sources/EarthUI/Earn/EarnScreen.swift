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
/// The stake half is StakeOverview: private stake only, one number, a row
/// per validator, and each validator's actions in its own sheet. There is no
/// claim: private stake compounds into its validator's rate.
struct EarnScreen: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    @Environment(TxController.self) private var tx

    @State private var staking: StakeAction?
    /// What a validator's sheet asked for, run once that sheet is gone: one
    /// sheet cannot present over another closing, and a confirm drawn while
    /// it is up would sit behind it.
    @State private var afterDismiss: (() -> Void)?
    @State private var mode = Mode.stake
    @State private var round = StakeRoundModel()

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
        .refreshable {
            await model.refresh()
            await round.load(rest: model.client.rest)
        }
        .background(theme.colors.bgPrimary)
        .scrollContentBackground(.hidden)
        .sheet(item: $staking, onDismiss: {
            let next = afterDismiss
            afterDismiss = nil
            next?()
        }) { action in
            switch action {
            case .move(let from): MoveStakeSheet(source: from).earthThemed()
            case .stake(let v): StakeSheet(unstaking: false, validator: v).earthThemed()
            case .unstake(let v): StakeSheet(unstaking: true, validator: v).earthThemed()
            case .validator(let v):
                ValidatorStakeSheet(validator: v, round: round, open: { next in
                    afterDismiss = { staking = next }
                    staking = nil
                }, merge: { op in
                    afterDismiss = { merge(op) }
                    staking = nil
                }, withdraw: { denom in
                    afterDismiss = { withdraw(denom) }
                    staking = nil
                })
                .earthThemed()
            }
        }
    }

    private var stakeContent: some View {
        StakeOverview(round: round, open: { staking = $0 }, checkIn: checkIn, withdraw: withdraw)
    }

    private var feeRow: (String, String) {
        ("Fee (estimate)", "\(Token.erth.format(Fees.forGas(PrivacyWallet.privateGasEstimate))) ERTH each, shielded")
    }

    /// Every check-in due, on one confirmation: one tx per validator and
    /// month, sent in turn. The stake moves into this month's vintage and
    /// earns (and votes) for another year; nothing is unbonded.
    private func checkIn() {
        guard let w = model.privacy else { return }
        Task { @MainActor in
            do {
                let items = try await w.checkInItems()
                guard !items.isEmpty else { throw PrivacyError("Nothing can be checked in right now.") }
                let validators = Set(items.map(\.validator))
                let derth = items.reduce(UInt64(0)) { PrivateMsgs.saturatingAdd($0, $1.derth) }
                let until = model.vintages.map { Vintage.retiresAt($0.current) }
                var rows: [(String, String)] = [
                    ("Stake", "\(Figures.balance(BigInt(derth))) derth" + (validators.count == 1 ? " with \(moniker(items[0].validator))" : " with \(validators.count) validators")),
                ]
                if let until { rows.append(("Earns until", StakeOverview.date(until))) }
                rows.append(("Sends", items.count == 1 ? "1 transaction" : "\(items.count) transactions, one per validator and month"))
                rows.append(feeRow)
                tx.requestPrivate(.private(action: "Check in stake", rows: rows)) { w in
                    guard let last = try await w.checkInAll().last else { throw PrivacyError("Nothing can be checked in right now.") }
                    return last
                }
            } catch {
                tx.showFailure("Check in stake", error, model: model)
            }
        }
    }

    /// A retired vintage's withdrawal: what it pays (its share of the value
    /// held when it retired) and when (the held payout's maturity, or now).
    private func withdraw(_ denom: String) {
        guard let w = model.privacy else { return }
        Task { @MainActor in
            do {
                let q = try await w.quoteWithdraw(denom: denom)
                let paid: String
                switch q.paidAt {
                case 0: paid = "Now"
                case let t where t > 0: paid = StakeRoundModel.day(t)
                default: paid = "When the unbonding period ends"
                }
                var rows: [(String, String)] = [
                    ("Amount", "\(Figures.balance(BigInt(q.derth))) derth (\(Figures.balance(BigInt(q.value))) ERTH)"),
                    ("From validator", moniker(q.validator)),
                    ("Paid", paid),
                ]
                // A share past one note's value goes in parts, one transaction each.
                if q.parts > 1 { rows.append(("Sends", "\(q.parts) transactions")) }
                rows.append(("Fee (estimate)", "\(Token.erth.format(Fees.forGas(PrivacyWallet.privateGasEstimate))) ERTH\(q.parts > 1 ? " each" : ""), shielded"))
                let stopped = q.retiredAt > 0 ? q.retiredAt : Vintage.retiresAt(q.month)
                tx.requestPrivate(.private(
                    action: "Withdraw retired stake",
                    rows: rows,
                    notes: ["This stake stopped earning on \(StakeOverview.date(stopped)). Withdrawing pays out its share as private ERTH."]
                )) { w in
                    try await w.withdrawRetired(q)
                }
            } catch {
                tx.showFailure("Withdraw retired stake", error, model: model)
            }
        }
    }

    private func moniker(_ operatorAddress: String) -> String {
        model.moniker(of: operatorAddress)
    }

    /// Merge a validator's notes into one (MsgRestake), on the user's tap only.
    private func merge(_ validator: String) {
        tx.requestPrivate(.private(
            action: "Merge stake notes",
            rows: [
                ("Validator", moniker(validator)),
                ("Fee (estimate)", "\(Token.erth.format(Fees.forGas(PrivacyWallet.privateGasEstimate))) ERTH, shielded"),
            ]
        )) { w in
            try await w.restake(validator: validator)
        }
    }
}
