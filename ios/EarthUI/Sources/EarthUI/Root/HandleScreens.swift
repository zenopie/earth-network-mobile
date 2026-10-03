import EarthCore
import SwiftUI

/// A reminder, as a tappable banner: what is due and where to do it. Nothing
/// happens until the owner acts.
struct ReminderBanner: View {
    @Environment(\.earth) private var theme
    let text: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(text)
                .font(EarthType.bodySmall)
                .foregroundStyle(theme.colors.textPrimary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(theme.space.x16)
                .background(theme.colors.bgSecondary, in: .rect(cornerRadius: theme.space.radiusMd))
        }
        .buttonStyle(.plain)
    }
}

private func day(_ t: Int64) -> String {
    Date(timeIntervalSince1970: TimeInterval(t)).formatted(date: .abbreviated, time: .omitted)
}

/// The handle: a name in the chain's public directory for this wallet's
/// shielded address, so others can pay "@name" privately. Claimed, renewed,
/// changed or released with a membership proof (one per person; who holds it
/// is not public). A lease lasts a year; nothing renews it on its own, so the
/// app reminds the owner from 30 days before it ends and through the 30-day
/// renewal period after. Ports HandleScreen (Android).
struct HandleScreen: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    @Environment(TxController.self) private var tx
    @Environment(\.dismiss) private var dismiss
    @State private var input = ""
    @State private var copied = false

    private var now: Int64 { Int64(Date().timeIntervalSince1970) }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: theme.space.x12) {
                    Text("A handle lets anyone pay you by name: their wallet looks it up in the chain's public directory and sends to your shielded address. Who holds a handle is not public. A handle lasts a year from each renewal and never renews on its own; the app reminds you before it ends.")
                        .font(EarthType.bodySmall)
                        .foregroundStyle(theme.colors.textTertiary)
                    content
                }
                .padding(theme.space.gutter)
            }
            .navigationTitle("Handle")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } } }
            .background(theme.colors.bgPrimary)
            .scrollContentBackground(.hidden)
            .task { await model.refreshPersonal() }
            .overlay { TxOverlay(host: .handle) }
        }
    }

    @ViewBuilder
    private var content: some View {
        if !model.isRegistered {
            Text("Register this wallet (Identity) to claim a handle.")
                .font(EarthType.bodySmall).foregroundStyle(theme.colors.textTertiary)
        } else {
            ForEach(Array(model.reminders.enumerated()), id: \.offset) { _, r in
                if case .handleExpiring = r { ReminderBanner(text: Reminders.text(r, now: now)) { renew() } }
            }
            if let err = model.handleDirectoryError {
                Text("Couldn't read the handle directory: \(err)").font(EarthType.bodySmall).foregroundStyle(theme.colors.textError)
            }
            if !model.handle.isEmpty { held }
            claim
        }
    }

    @ViewBuilder
    private var held: some View {
        EarthDetailRow(label: "Handle", value: "@\(model.handle)")
        if let e = model.handleEntry {
            let st = e.status(at: now)
            EarthDetailRow(label: "Status", value: st == HandleEntry.live ? "Live"
                : st == HandleEntry.renewal ? "Expired: renewal period (only you may renew it)" : "Released")
            EarthDetailRow(label: "Expires", value: day(e.expiresAt))
            EarthDetailRow(label: "Renewable until", value: day(e.renewalUntil))
            EarthDetailRow(label: "Pays", value: e.address == model.shieldedAddress
                ? "this wallet (\(Handles.truncate(e.address)))" : "another address (\(Handles.truncate(e.address)))")
        } else {
            EarthDetailRow(label: "Status", value: "Not in the directory")
        }
        HStack(spacing: theme.space.x8) {
            EarthButton(title: model.handleEntry.map { $0.address != model.shieldedAddress } == true ? "Renew to this wallet" : "Renew for 1 year") { renew() }
            EarthButton(title: copied ? "Copied" : "Copy link", role: .secondary) {
                UIPasteboard.general.string = "Pay me privately on Earth: @\(model.handle) · join with https://erth.network/ref/\(model.handle)"
                copied = true
            }
        }
        EarthButton(title: "Release @\(model.handle)", role: .destructive) {
            let h = model.handle
            tx.requestPrivate(.private(action: "Release @\(h)", rows: []), host: .handle, onSuccess: { await refreshed() }) { w in
                try await w.releaseHandle()
            }
        }
    }

    @ViewBuilder
    private var claim: some View {
        let waitUntil = model.predecessorAt > 0 ? Int64(model.predecessorAt) + model.handleLeaseSeconds + 86_400 + 3_600 : 0
        if model.handleMovedOut && model.handle.isEmpty {
            Text("This identity moved its handle to another identity, so it cannot claim one again.")
                .font(EarthType.bodySmall).foregroundStyle(theme.colors.textTertiary)
        } else if model.handle.isEmpty && waitUntil > now {
            Text("This identity replaced another on \(day(Int64(model.predecessorAt))), so it can claim a handle from \(day(waitUntil)) (anything the old one held has lapsed by then). Moving a handle before a switch keeps it with no wait.")
                .font(EarthType.bodySmall).foregroundStyle(theme.colors.textTertiary)
        } else {
            let parsed = Handles.parse(input)
            EarthLabel(model.handle.isEmpty ? "Claim a handle" : "Change to another handle")
            TextField("@name", text: $input)
                .font(EarthType.mono)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .padding(theme.space.x12)
                .background(theme.colors.bgPrimary, in: .rect(cornerRadius: theme.space.radiusMd))
                .overlay { RoundedRectangle(cornerRadius: theme.space.radiusMd).strokeBorder(theme.colors.strokePrimary, lineWidth: theme.space.stroke) }
            if !input.isEmpty, parsed == nil {
                Text("3-32 of a-z, 0-9 and -, no dash at either end").font(EarthType.bodySmall).foregroundStyle(theme.colors.textError)
            }
            Text(model.handle.isEmpty ? "One handle per person. If it is taken the chain refuses the claim and nothing but the fee is spent."
                 : "Changing frees @\(model.handle) at once: anyone may claim it.")
                .font(EarthType.bodySmall).foregroundStyle(theme.colors.textTertiary)
            EarthButton(title: model.handle.isEmpty ? "Claim \(parsed.map { "@\($0)" } ?? "handle")" : "Change to \(parsed.map { "@\($0)" } ?? "…")") {
                guard let h = parsed else { return }
                let pays = "@\(h) · \(Handles.truncate(model.shieldedAddress))"
                tx.requestPrivate(.private(action: model.handle.isEmpty ? "Claim @\(h)" : "Change handle to @\(h)", rows: [("Pays", pays)]),
                                  host: .handle, onSuccess: { input = ""; await refreshed() }) { w in
                    try await w.bindHandle(h)
                }
            }
            .disabled(parsed == nil || parsed == model.handle)
        }
    }

    private func renew() {
        let h = model.handle
        guard !h.isEmpty else { return }
        tx.requestPrivate(.private(action: "Renew @\(h) for a year", rows: [("Pays", "@\(h) · \(Handles.truncate(model.shieldedAddress))")]),
                          host: .handle, onSuccess: { await refreshed() }) { w in
            try await w.bindHandle(h)
        }
    }

    private func refreshed() async {
        await model.invalidateHandles()
        await model.syncPrivacy()
    }
}

/// A voluntary switch of identity: the same passport registered from another
/// of this phone's wallets. The chain zeroes this wallet's leaf and makes a
/// new one there. What this identity holds (its handle, its caretaker vote)
/// can be moved to the new identity first, so it keeps them with no wait;
/// otherwise the new identity waits until they lapse (up to a year).
struct SwitchIdentityScreen: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    @Environment(TxController.self) private var tx
    @Environment(\.dismiss) private var dismiss
    @State private var target: Int?
    @State private var moveHandle = true
    @State private var moveVote = true
    @State private var backedUp = false
    @State private var confirming = false
    @State private var phrase: String?
    @State private var moved: Set<String> = []
    @State private var adding = false
    @State private var registering = false

    private var now: Int64 { Int64(Date().timeIntervalSince1970) }

    var body: some View {
        NavigationStack {
            Group {
                if confirming {
                    ConfirmIdentity(reason: "Show the new wallet's recovery phrase") { confirmation in
                        if let t = target, confirmation.wallets.indices.contains(t) { phrase = confirmation.wallets[t].mnemonic }
                        confirming = false
                    }
                } else {
                    form
                }
            }
            .navigationTitle("Switch identity")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(confirming ? "Cancel" : "Done") { if confirming { confirming = false } else { dismiss() } } } }
            .background(theme.colors.bgPrimary)
            .task { model.loadWallets(); await model.refreshPersonal() }
            .sheet(isPresented: $adding) { AddWalletSheet(mode: .create).earthThemed() }
            .sheet(isPresented: $registering) { RegistrationSheet().earthThemed() }
            .overlay { TxOverlay(host: .switchIdentity) }
        }
    }

    private var holdsHandle: Bool { !model.handle.isEmpty }
    private var holdsVote: Bool { model.caretakerExpiresAt > now && !(model.privacy?.snapshot.caretakerSplit.isEmpty ?? true) }

    private var form: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: theme.space.x12) {
                Text("Switching moves your personhood to another wallet: register the same passport there and this wallet stops counting as you. Your handle and caretaker vote can move with you first, so the new identity keeps them at once. Anything not moved stays with this wallet until it lapses, and the new identity cannot claim a handle or cast a caretaker vote until then (up to a year).")
                    .font(EarthType.bodySmall).foregroundStyle(theme.colors.textTertiary)
                Text("If you ever lose a wallet, nothing it holds can be moved: a new identity waits out its handle and caretaker vote. Back up every wallet's recovery phrase.")
                    .font(EarthType.bodySmall).foregroundStyle(theme.colors.textTertiary)
                EarthLabel("Switch to")
                let others = model.wallets.enumerated().filter { $0.offset != model.selected }
                if others.isEmpty {
                    Text("You have no other wallet on this phone. Create one first.").font(EarthType.bodySmall).foregroundStyle(theme.colors.textTertiary)
                }
                ForEach(others, id: \.offset) { index, w in
                    Button { target = index; phrase = nil } label: {
                        HStack {
                            Image(systemName: target == index ? "checkmark.circle.fill" : "circle").foregroundStyle(theme.colors.accentInk)
                            VStack(alignment: .leading) {
                                Text(w.name).font(EarthType.body).foregroundStyle(theme.colors.textPrimary)
                                Text(w.address).font(EarthType.bodySmall).foregroundStyle(theme.colors.textTertiary).lineLimit(1).truncationMode(.middle)
                            }
                        }
                    }.buttonStyle(.plain)
                }
                EarthButton(title: "Create a new wallet", role: .secondary) { adding = true }
                if holdsHandle || holdsVote || !moved.isEmpty {
                    EarthLabel("Move first")
                    if holdsHandle || moved.contains("handle") {
                        Toggle("Move @\(model.handle.isEmpty ? "handle" : model.handle)" + (moved.contains("handle") ? " (moved)" : ""), isOn: $moveHandle)
                            .disabled(moved.contains("handle"))
                    }
                    if holdsVote || moved.contains("caretaker") {
                        Toggle("Move my caretaker vote" + (moved.contains("caretaker") ? " (moved)" : ""), isOn: $moveVote)
                            .disabled(moved.contains("caretaker"))
                    }
                    Text("Each move is a private transaction with its own fee. Once moved, this identity can never hold one again.")
                        .font(EarthType.bodySmall).foregroundStyle(theme.colors.textTertiary)
                }
                if let phrase {
                    SeedGrid(words: phrase.split(separator: " ").map(String.init))
                    Text("Write these words down, in order, and keep them offline. Anyone with them controls that wallet.")
                        .font(EarthType.bodySmall).foregroundStyle(theme.colors.textTertiary)
                }
                EarthButton(title: phrase == nil ? "Show the new wallet's recovery phrase" : "Hide the recovery phrase", role: .secondary) {
                    if phrase == nil { confirming = true } else { phrase = nil }
                }
                .disabled(target == nil)
                Toggle("I have backed up the new wallet's recovery phrase", isOn: $backedUp)
                let pending = (holdsHandle && moveHandle && !moved.contains("handle")) || (holdsVote && moveVote && !moved.contains("caretaker"))
                if pending {
                    EarthButton(title: "Move to the new wallet") { move() }
                        .disabled(target == nil || !backedUp)
                } else {
                    EarthButton(title: "Switch: register there") {
                        guard let t = target else { return }
                        Task { await model.select(t); registering = true }
                    }
                    .disabled(target == nil || !backedUp)
                }
            }
            .padding(theme.space.gutter)
        }
        .scrollContentBackground(.hidden)
    }

    /// Each move names the new wallet's nullifier in that scope, derived from
    /// its keys on this phone, then records in its store what it now holds.
    private func move() {
        guard let t = target, let keys = try? model.privacyKeys(ofWallet: t) else { return }
        let handle = model.handle
        let split = model.privacy?.snapshot.caretakerSplit ?? [:]
        let expires = model.caretakerExpiresAt
        let doVote = holdsVote && moveVote && !moved.contains("caretaker")
        func moveVoteTx() {
            tx.requestPrivate(.private(action: "Move caretaker vote to the new wallet", rows: []), host: .switchIdentity,
                              onSuccess: { moved.insert("caretaker"); await model.syncPrivacy() }) { w in
                let r = try await w.moveCaretaker(newOwner: PrivacyWallet.newOwner(keys, scope: PrivacyHash.caretakerScope()))
                try await model.adoptMoved(intoWallet: t, handle: nil, split: split, splitExpiresAt: expires)
                return r
            }
        }
        if holdsHandle && moveHandle && !moved.contains("handle") {
            tx.requestPrivate(.private(action: "Move @\(handle) to the new wallet", rows: []), host: .switchIdentity,
                              onSuccess: {
                                  moved.insert("handle")
                                  await model.syncPrivacy()
                                  if doVote { moveVoteTx() }
                              }) { w in
                let r = try await w.moveHandle(newOwner: PrivacyWallet.newOwner(keys, scope: PrivacyHash.handleScope()))
                try await model.adoptMoved(intoWallet: t, handle: handle, split: nil, splitExpiresAt: 0)
                return r
            }
        } else if doVote {
            moveVoteTx()
        }
    }
}
