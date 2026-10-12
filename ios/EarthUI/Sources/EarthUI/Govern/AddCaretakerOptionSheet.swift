import BigInt
import EarthCore
import SwiftUI

/// List a new Caretaker option: a description and the address it pays.
///
/// Permissionless on chain (MsgAddAddressOption, caretaker stream): any
/// account may list one by burning params.address_option_fee from its public
/// balance, the open path's spam brake. No registration is asked for, since
/// the chain asks for none: whoever runs a public good lists it, and people
/// decide by vote whether it is paid. The payout is claimable by anyone and
/// goes to the address regardless (empty claimer).
struct AddCaretakerOptionSheet: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    @Environment(TxController.self) private var tx
    @Environment(\.dismiss) private var dismiss

    let onAdded: () -> Void

    @State private var name = ""
    @State private var recipient = ""
    @State private var fee: UInt64?
    @State private var scanning = false

    /// x/allocation's MaxDescriptionLen, in bytes.
    private static let maxNameBytes = 256

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 0) {
                    Text("New option")
                        .font(.system(size: 34, weight: .bold))
                        .foregroundStyle(theme.colors.textPrimary)
                        .padding(.top, theme.space.x24)
                    Text(fee.map { "\(Token.erth.format(BigInt($0))) ERTH to list · burned" } ?? "Anyone can list one")
                        .font(EarthType.body)
                        .foregroundStyle(theme.colors.textTertiary)
                        .padding(.top, theme.space.x4)
                    // x/allocation OptionIdleGrace: thirty days at zero weight and
                    // the option is swept, its unclaimed balance burned.
                    Text("Dropped after 30 days with no votes")
                        .font(EarthType.bodySmall)
                        .foregroundStyle(theme.colors.textTertiary)
                        .padding(.top, theme.space.x2)

                    VStack(alignment: .leading, spacing: theme.space.x16) {
                        // The chain's one text field for an option: what lists show as its name.
                        field("Description") {
                            TextField("What it funds, and who runs it", text: $name, axis: .vertical)
                                .lineLimit(2 ... 5)
                        }
                        if name.utf8.count > Self.maxNameBytes {
                            Text("Too long").font(EarthType.bodySmall).foregroundStyle(theme.colors.textError)
                        }
                        field("Pays to") {
                            HStack {
                                TextField("earth1…", text: $recipient)
                                    .textInputAutocapitalization(.never)
                                    .autocorrectionDisabled()
                                    .font(EarthType.body.monospaced())
                                Button { scanning = true } label: { Image(systemName: "qrcode.viewfinder") }
                                    .foregroundStyle(theme.colors.accentInk)
                            }
                        }
                        if !recipient.isEmpty, !EarthKey.isValidAddress(recipient) {
                            Text("Not an earth1 address").font(EarthType.bodySmall).foregroundStyle(theme.colors.textError)
                        }
                        if let short = shortfall {
                            Text("Needs \(Token.erth.format(short)) more public ERTH")
                                .font(EarthType.bodySmall).foregroundStyle(theme.colors.textError)
                        }
                    }
                    .padding(.top, theme.space.x32)

                    EarthPillButton(title: "Review") { review() }
                        .disabled(!valid)
                        .padding(.top, theme.space.x24)
                }
                .padding(.horizontal, theme.space.gutter)
                .padding(.bottom, theme.space.x32)
            }
            .scrollContentBackground(.hidden)
            .navigationTitle("Caretaker Fund")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
            .background(theme.colors.bgPrimary)
            .sheet(isPresented: $scanning) { QRScanSheet { recipient = $0 }.earthThemed() }
            .task {
                // This wallet's own address to start with: the usual case is
                // listing what you run yourself.
                if recipient.isEmpty { recipient = model.address }
                fee = await model.client.addressOptionFee()
            }
        }
    }

    private func field<Content: View>(_ label: String, @ViewBuilder _ content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: theme.space.x8) {
            EarthLabel(label)
            content()
                .padding(theme.space.x12)
                .background(theme.colors.bgSecondary, in: .rect(cornerRadius: theme.space.radiusMd))
        }
    }

    private var trimmedName: String { name.trimmingCharacters(in: .whitespacesAndNewlines) }

    /// What the public balance lacks for the burn and the network fee.
    private var shortfall: BigInt? {
        guard let fee else { return nil }
        let needed = BigInt(fee) + (BigInt(TransactionSigner.defaultFeeUerth) ?? 0)
        let have = model.balance(.erth)
        return have < needed ? needed - have : nil
    }

    private var valid: Bool {
        fee != nil && !trimmedName.isEmpty && trimmedName.utf8.count <= Self.maxNameBytes
            && EarthKey.isValidAddress(recipient) && shortfall == nil
    }

    private func review() {
        guard valid, let fee else { return }
        let name = trimmedName, to = recipient
        tx.request(.init(action: "Add Caretaker option", rows: [
            ("Description", name),
            ("Pays to", to),
            ("Burned", "\(Token.erth.format(BigInt(fee))) ERTH, public"),
        ]), onSuccess: { onAdded() }) { key in
            [Msg.AddAddressOption(submitter: key.address, stream: .caretaker, description: name, recipient: to)
                .asAny(typeURL: Msg.AddAddressOption.typeURL)]
        }
        dismiss()
    }
}
