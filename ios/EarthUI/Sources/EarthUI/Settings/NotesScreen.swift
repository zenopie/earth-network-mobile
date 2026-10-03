import BigInt
import EarthCore
import SwiftUI

/// Shielded notes, per asset.
///
/// A private payment spends any number of notes, up to
/// max_actions_per_bundle in one transaction, so merging only matters for a
/// balance spread over more notes than that: it joins the smallest notes one
/// transaction carries (ERTH pays its fee from them). Stake notes move two to
/// a proof, so a stake balance spread over many merges by a restake.
/// Shielding moves transparent ERTH into a note.
struct NotesScreen: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    @Environment(TxController.self) private var tx
    @Environment(\.dismiss) private var dismiss
    @State private var shieldAmount = ""

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: theme.space.x16) {
                    Text("A private payment can spend many notes at once, of any assets. Merge only when a balance is spread over more notes than one transaction carries, or to tidy staked ERTH into fewer notes.")
                        .font(EarthType.bodySmall)
                        .foregroundStyle(theme.colors.textTertiary)

                    EarthDetailRow(label: "Shielded address", value: model.shieldedAddress)
                    if let error = model.privacySyncError {
                        Text("Sync: \(error)")
                            .font(EarthType.bodySmall)
                            .foregroundStyle(theme.colors.textError)
                    }

                    ForEach(model.shielded.filter { $0.value > 0 }.sorted(by: { $0.key < $1.key }), id: \.key) { denom, amount in
                        EarthCard {
                            Text(label(denom))
                                .font(EarthType.body).fontWeight(.semibold)
                                .foregroundStyle(theme.colors.textPrimary)
                            EarthDetailRow(label: "Balance", value: Figures.balance(BigInt(amount)))
                            if let count = model.mergeable[denom] {
                                EarthDetailRow(label: "Notes", value: "\(count)")
                                EarthButton(title: "Merge smallest notes", role: .secondary) {
                                    tx.requestPrivate(.private(action: "Merge notes", rows: [("Asset", label(denom))]), host: .notes,
                                                      onSuccess: { await model.syncPrivacy() }) { w in
                                        // Stake notes merge by a restake (owner-locked, two per proof).
                                        denom.hasPrefix(PrivacyWallet.derthPrefix) ? try await w.mergeStake(denom: denom) : try await w.merge(denom: denom)
                                    }
                                }
                            }
                        }
                    }

                    VStack(alignment: .leading, spacing: theme.space.x8) {
                        EarthLabel("Shield ERTH")
                        Text("Moves ERTH from your account into a private note. The amount is public as it enters; after that, what you do with it is not.")
                            .font(EarthType.bodySmall)
                            .foregroundStyle(theme.colors.textTertiary)
                        TextField("0", text: $shieldAmount)
                            .font(EarthType.amountField)
                            .keyboardType(.decimalPad)
                        Text("Account balance \(Token.erth.format(model.balance(.erth))) ERTH")
                            .font(EarthType.bodySmall)
                            .foregroundStyle(theme.colors.textTertiary)
                        EarthButton(title: "Shield") { shield() }
                            .disabled(shieldValue == nil)
                    }
                }
                .padding(theme.space.gutter)
            }
            .refreshable { await model.syncPrivacy() }
            .navigationTitle("Shielded notes")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } } }
            .background(theme.colors.bgPrimary)
            .scrollContentBackground(.hidden)
            .overlay { TxOverlay(host: .notes) }
        }
    }

    private var shieldValue: BigInt? {
        guard let v = Token.erth.parse(shieldAmount), v > 0,
              v + (BigInt(TransactionSigner.defaultFeeUerth) ?? 0) <= model.balance(.erth) else { return nil }
        return v
    }

    /// MsgShield is signed by the account (the coins are its): the note goes
    /// to this wallet's own shielded address, its ciphertext v1.
    private func shield() {
        guard let value = shieldValue, let w = model.privacy, let out = try? w.shieldOutput(denom: Constants.gasDenom, amount: UInt64(value)) else { return }
        tx.request(.init(action: "Shield ERTH", rows: [("Amount", "\(Token.erth.format(value)) ERTH"), ("To", "your shielded balance")]),
                   host: .notes, onSuccess: { shieldAmount = ""; await model.syncPrivacy() }) { key in
            [MsgShield(sender: key.address, amount: Coin(denom: Constants.gasDenom, amount: String(value)),
                       pc: out.pc.bytes, ciphertext: out.ciphertext).asAny(typeURL: MsgShield.typeURL)]
        }
    }

    private func label(_ denom: String) -> String {
        if denom == "uerth" { return "ERTH" }
        if denom == "uanml" { return "ANML" }
        if denom.hasPrefix("derth/") { return "Staked ERTH · " + String(denom.dropFirst(6).prefix(20)) + "…" }
        if denom.hasPrefix("unbond/") { return "Unbonding · epoch " + (denom.split(separator: "/").last.map(String.init) ?? "") }
        if denom.hasPrefix("dexlp/") { return "LP shares · pool " + String(denom.dropFirst(6)) }
        return denom
    }
}
