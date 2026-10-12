import BigInt
import EarthCore
import SwiftUI

/// One option, opened from a stream's key: what it is, its share, and where
/// its emission goes.
///
/// No "listed by": the chain keeps no submitter on the option, and the one
/// place it appears, the listing tx's event, is not searchable through
/// Earth's node (its edge serves tx search by height only). The address it
/// pays is the honest stand-in: whoever listed it chose it.
struct OptionDetailSheet: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    @Environment(TxController.self) private var tx
    @Environment(\.dismiss) private var dismiss
    let option: Allocation.OptionInfo
    /// The stream's reward index now, for what the option is owed.
    let rewardIndex: String
    let onClaimed: () -> Void
    let stream: Msg.StreamID
    /// Its share of the stream as every vote stands, in percent.
    let share: Int
    /// Your split's percent for it, when you gave it one.
    let mine: Int?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 0) {
                    Text("\(share)%")
                        .font(EarthType.header2).fontWeight(.semibold)
                        .foregroundStyle(theme.colors.textPrimary)
                        .padding(.top, theme.space.x24)
                    Text(status)
                        .font(EarthType.body)
                        .foregroundStyle(option.removed ? theme.colors.textError : theme.colors.textTertiary)

                    Text(option.displayName)
                        .font(EarthType.body)
                        .foregroundStyle(theme.colors.textPrimary)
                        .multilineTextAlignment(.center)
                        .textSelection(.enabled)
                        .padding(.top, theme.space.x24)

                    VStack(spacing: 0) {
                        if let mine { EarthDetailRow(label: "Your vote", value: "\(mine)%") }
                        if option.integrated {
                            EarthDetailRow(label: "Pays", value: handlerName)
                        } else {
                            EarthDetailRow(label: "Pays to", value: option.recipient)
                            EarthDetailRow(label: "Claim by", value: option.claimer.isEmpty ? "Anyone" : option.claimer)
                        }
                        EarthDetailRow(label: "Waiting to be paid", value: "\(Figures.balance(owed)) ERTH")
                        EarthDetailRow(label: "Option", value: "#\(option.id)")
                    }
                    .padding(.top, theme.space.x24)

                    // Only where this wallet is what the option pays (or the
                    // one claimer it allows): the payout goes to the recipient
                    // whoever signs, so anyone else claiming is a stranger's
                    // business. Greyed while nothing is owed.
                    if paysThisWallet {
                        EarthPillButton(title: "Claim \(Figures.balance(owed)) ERTH") { claim() }
                            .disabled(owed == 0 || !mayClaim)
                            .padding(.top, theme.space.x24)
                    }
                }
                .padding(.horizontal, theme.space.gutter)
                .padding(.bottom, theme.space.x32)
            }
            .scrollContentBackground(.hidden)
            .navigationTitle(stream == .caretaker ? "Caretaker Fund" : "Groundworks Fund")
            .navigationBarTitleDisplayMode(.inline)
            .background(theme.colors.bgPrimary)
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }

    private var owed: BigInt { option.claimable(rewardIndex: rewardIndex) }

    /// This wallet is the option's payout address or its claimer.
    private var paysThisWallet: Bool {
        !option.integrated && !model.address.isEmpty && (option.recipient == model.address || option.claimer == model.address)
    }

    /// A named claimer is the only one the chain lets claim.
    private var mayClaim: Bool { option.claimer.isEmpty || option.claimer == model.address }

    private func claim() {
        let id = option.id, s = stream, to = option.recipient
        tx.request(.init(action: "Claim option payout", rows: [
            ("Option", option.displayName),
            ("Amount", "About \(Figures.balance(owed)) ERTH"),
            ("To", OptionText.short(to)),
        ]), onSuccess: { onClaimed() }) { key in
            [Msg.ClaimAllocation(creator: key.address, stream: s, optionID: id).asAny(typeURL: Msg.ClaimAllocation.typeURL)]
        }
        dismiss()
    }

    private var status: String {
        if option.removed { return "Removed" }
        // Listed by anyone, for a fee: said, so the name is not taken on trust.
        return option.integrated ? "of the fund · built in" : "of the fund · listed by anyone"
    }

    /// A built-in option's handler, in words.
    private var handlerName: String {
        switch option.handler {
        case "registration_rewards": return "Registration rewards"
        case "lp_rewards": return "Liquidity providers"
        default: return option.handler.replacingOccurrences(of: "_", with: " ").capitalized
        }
    }
}
