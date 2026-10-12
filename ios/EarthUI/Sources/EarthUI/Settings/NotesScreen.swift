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
                VStack(spacing: 0) {
                    hero
                    if let error = model.privacySyncError {
                        Text("Sync: \(error)")
                            .font(EarthType.bodySmall)
                            .foregroundStyle(theme.colors.textError)
                            .padding(.top, theme.space.x12)
                    }

                    Spacer().frame(height: theme.space.x24)
                    VStack(spacing: 0) {
                        ForEach(model.shielded.filter { $0.value > 0 }.sorted(by: { $0.key < $1.key }), id: \.key) { denom, amount in
                            noteRow(denom: denom, amount: amount)
                            EarthDivider()
                        }
                    }

                    VStack(alignment: .leading, spacing: theme.space.x8) {
                        EarthLabel("Shield ERTH")
                        TextField("0", text: $shieldAmount)
                            .font(EarthType.amountField)
                            .keyboardType(.decimalPad)
                        Text("Available \(Token.erth.format(model.balance(.erth))) ERTH")
                            .font(EarthType.bodySmall)
                            .foregroundStyle(theme.colors.textTertiary)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.top, theme.space.x24)

                    Spacer().frame(height: theme.space.x24)
                    EarthPillButton(title: "Shield") { shield() }
                        .disabled(shieldValue == nil)

                    Spacer().frame(height: theme.space.x24)
                    EarthDetailRow(label: "Shielded address", value: model.shieldedAddress)
                    Spacer().frame(height: theme.space.x32)
                }
                .padding(.horizontal, theme.space.gutter)
            }
            .refreshable { await model.syncPrivacy() }
            .navigationTitle("Shielded notes")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } } }
            .background(theme.colors.bgPrimary)
            .scrollContentBackground(.hidden)
        }
    }

    /// Private ERTH, big.
    private var hero: some View {
        VStack(spacing: 0) {
            SplitAmount(amount: Figures.plain(BigInt(model.shielded[Constants.gasDenom] ?? 0)))
                .lineLimit(1)
                .minimumScaleFactor(0.5)
            Text("Private ERTH")
                .font(EarthType.body)
                .foregroundStyle(theme.colors.textTertiary)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, theme.space.x24)
    }

    /// One asset: its name and balance, and Merge when its notes need it.
    private func noteRow(denom: String, amount: UInt64) -> some View {
        HStack(spacing: theme.space.x12) {
            VStack(alignment: .leading, spacing: theme.space.x2) {
                Text(label(denom))
                    .font(EarthType.body).fontWeight(.semibold)
                    .foregroundStyle(theme.colors.textPrimary)
                    .lineLimit(1)
                if let count = model.mergeable[denom] {
                    Button {
                        tx.requestPrivate(.private(action: "Merge notes", rows: [("Asset", label(denom))]),
                                          onSuccess: { await model.syncPrivacy() }) { w in
                            // Stake notes merge by a restake (owner-locked, two per proof).
                            denom.hasPrefix(PrivacyWallet.derthPrefix) ? try await w.mergeStake(denom: denom) : try await w.merge(denom: denom)
                        }
                    } label: {
                        Text("Merge \(count) notes")
                            .font(EarthType.bodySmall).fontWeight(.semibold)
                            .foregroundStyle(theme.colors.accentInk)
                    }
                    .buttonStyle(.plain)
                }
            }
            Spacer(minLength: theme.space.x8)
            Text(Figures.balance(BigInt(amount)))
                .font(.system(size: 17, weight: .semibold).monospacedDigit())
                .foregroundStyle(theme.colors.textPrimary)
                .lineLimit(1)
        }
        .padding(.vertical, theme.space.x12)
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
        tx.request(.init(action: "Shield ERTH", rows: [("Amount", "\(Token.erth.format(value)) ERTH"), ("To", "your shielded balance")]), onSuccess: { shieldAmount = ""; await model.syncPrivacy() }) { key in
            [MsgShield(sender: key.address, amount: Coin(denom: Constants.gasDenom, amount: String(value)),
                       pc: out.pc.bytes, ciphertext: out.ciphertext).asAny(typeURL: MsgShield.typeURL)]
        }
    }

    private func label(_ denom: String) -> String {
        if denom == "uerth" { return "ERTH" }
        if denom == "uanml" { return "ANML" }
        if denom.hasPrefix("derth/") { return "Staked ERTH · " + String(denom.dropFirst(6).prefix(20)) + "…" }
        if denom.hasPrefix("dexlp/") { return "LP shares · pool " + String(denom.dropFirst(6)) }
        return denom
    }
}
