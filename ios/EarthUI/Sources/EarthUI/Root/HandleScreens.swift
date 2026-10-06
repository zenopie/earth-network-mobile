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
                switch r {
                case let .handleExpiring(h, _, _, _) where h == model.handle: ReminderBanner(text: Reminders.text(r, now: now)) { renew() }
                case .handlePaysElsewhere: ReminderBanner(text: Reminders.text(r, now: now)) { renew() }
                default: EmptyView()
                }
            }
            if let err = model.handleDirectoryError {
                Text("Couldn't read the handle directory: \(err)").font(EarthType.bodySmall).foregroundStyle(theme.colors.textError)
            }
            if movingOut {
                note("A move of @\(model.handle) to the identity that replaced this one was sent and is waiting for the chain. Check it on Identity in the new wallet.")
            }
            if model.incomingMoves.contains(where: { $0.kind == PendingMove.handleKind }) {
                note("@\(model.handle) was moved here and is waiting for the chain to confirm the move.")
            }
            // Entries naming this wallet's address that the store does not hold (a restore
            // loses track of a handle): renewing one is checked by the chain, at no cost if it is not ours.
            // Only while no handle is held (a bind of another would free it), and renew-only.
            ForEach(model.handle.isEmpty ? model.addressedHandles : [], id: \.handle) { a in
                EarthDetailRow(label: "Names this wallet", value: "@\(a.handle)")
                EarthDetailRow(label: "Expires", value: day(a.expiresAt))
                note("If this identity holds it, renew it here. If it does not, the chain refuses and nothing is charged.")
                EarthButton(title: "Renew @\(a.handle)") { bind(a.handle) }
            }
            if !model.handle.isEmpty && !movingOut { held }
            if !movingOut { claim }
        }
    }

    private var movingOut: Bool { model.outgoingMoves.contains { $0.kind == PendingMove.handleKind && !$0.confirmed } }

    private func note(_ t: String) -> some View {
        Text(t).font(EarthType.bodySmall).foregroundStyle(theme.colors.textTertiary)
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
            if st == HandleEntry.renewal {
                // Past expiry, a renewal is bounded like a claim, and the handle cannot be moved.
                let claimFrom = model.predecessorAt > 0
                    ? Handles.satAdd(Handles.satAdd(Int64(clamping: model.predecessorAt), model.handleLeaseSeconds), 86_400 + 3_600) : 0
                note("Past its expiry, renewing counts as a new claim"
                     + (claimFrom > now ? ", which this identity can make from \(day(claimFrom)) (it replaced another); renew before \(day(e.renewalUntil)) or the handle is freed." : ".")
                     + " A handle in this period cannot be moved.")
            }
            EarthDetailRow(label: "Pays", value: e.address == model.shieldedAddress
                ? "this wallet (\(Handles.truncate(e.address)))" : "another address (\(Handles.truncate(e.address)))")
        } else {
            EarthDetailRow(label: "Status", value: "Not in the directory")
        }
        HStack(spacing: theme.space.x8) {
            EarthButton(title: model.handleEntry.map { $0.address != model.shieldedAddress } == true ? "Renew to this wallet" : "Renew for 1 year") { renew() }
            EarthButton(title: copied ? "Copied" : "Copy link", role: .secondary) {
                Clipboard.copy("Pay me privately on Earth: @\(model.handle) · join with https://erth.network/ref/\(model.handle)")
                copied = true
            }
        }
        EarthButton(title: "Release @\(model.handle)", role: .destructive) {
            let h = model.handle
            tx.requestPrivate(.private(action: "Release @\(h)", rows: [], gas: PrivacyWallet.bindHandleGasEstimate), host: .handle, onSuccess: { await refreshed() }) { w in
                try await w.releaseHandle()
            }
        }
    }

    @ViewBuilder
    private var claim: some View {
        // Clamped: a hostile lease param cannot trap here.
        let waitUntil = model.predecessorAt > 0
            ? Handles.satAdd(Handles.satAdd(Int64(clamping: model.predecessorAt), model.handleLeaseSeconds), 86_400 + 3_600) : 0
        if model.handleMovedOut && model.handle.isEmpty {
            Text("This identity moved its handle to another identity, so it cannot claim one again.")
                .font(EarthType.bodySmall).foregroundStyle(theme.colors.textTertiary)
        } else {
            if model.handle.isEmpty && waitUntil > now {
                Text("This identity replaced another on \(day(Int64(model.predecessorAt))), so it can claim a new handle from \(day(waitUntil)) (anything the old one held has lapsed by then). Moving a handle before a switch keeps it with no wait. If this identity already holds a handle (a restored wallet can lose track of it), enter its name to renew it: the chain checks, and charges nothing if it does not.")
                    .font(EarthType.bodySmall).foregroundStyle(theme.colors.textTertiary)
            }
            let parsed = Handles.parse(input)
            EarthLabel(model.handle.isEmpty ? (waitUntil > now ? "Renew a handle this identity holds" : "Claim a handle") : "Change to another handle")
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
                tx.requestPrivate(.private(action: model.handle.isEmpty ? "Claim @\(h)" : "Change handle to @\(h)", rows: [("Pays", pays)],
                                           gas: PrivacyWallet.bindHandleGasEstimate),
                                  host: .handle, onSuccess: { input = ""; await refreshed() }) { w in
                    try await w.bindHandle(h)
                }
            }
            .disabled(parsed == nil || parsed == model.handle)
        }
    }

    /// Renew-only: the wallet refuses a bind that would change
    /// the handle held; the sheet names a change as one all the same.
    private func bind(_ h: String) {
        let changing = !model.handle.isEmpty && model.handle != h
        tx.requestPrivate(.private(action: changing ? "Change handle to @\(h)" : "Renew @\(h) for a year",
                                   rows: [("Pays", "@\(h) · \(Handles.truncate(model.shieldedAddress))")],
                                   gas: PrivacyWallet.bindHandleGasEstimate),
                          host: .handle, onSuccess: { await refreshed() }) { w in
            try await w.bindHandle(h, renewOnly: true)
        }
    }

    private func renew() {
        let h = model.handle
        guard !h.isEmpty else { return }
        tx.requestPrivate(.private(action: "Renew @\(h) for a year", rows: [("Pays", "@\(h) · \(Handles.truncate(model.shieldedAddress))")],
                                   gas: PrivacyWallet.bindHandleGasEstimate),
                          host: .handle, onSuccess: { await refreshed() }) { w in
            try await w.bindHandle(h, renewOnly: true)
        }
    }

    private func refreshed() async {
        await model.invalidateHandles()
        await model.syncPrivacy()
    }
}

/// A voluntary switch of identity: the same passport registered from another
/// of this phone's wallets. The chain zeroes this wallet's leaf and makes a
/// new one there. Once the switch has landed, the new identity can bring this
/// one's handle and caretaker vote over (`MoveOfferCard`, on Identity in the
/// new wallet): a move proves knowledge of both identities' secrets, so it
/// comes after the switch and needs both recovery phrases on this phone.
///
/// The recovery phrase is shown after a fresh unlock and dropped when the app
/// leaves the foreground or the screen closes; the backup box needs the
/// phrase shown first.
struct SwitchIdentityScreen: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @State private var target: Int?
    @State private var backedUp = false
    @State private var confirming = false
    @State private var phrase: String?
    @State private var revealedFor: Int?
    @State private var adding = false
    @State private var registering = false
    @State private var targetWarning: String?

    var body: some View {
        NavigationStack {
            Group {
                if confirming {
                    ConfirmIdentity(reason: "Show the new wallet's recovery phrase") { confirmation in
                        if let t = target, confirmation.wallets.indices.contains(t) { phrase = confirmation.wallets[t].mnemonic; revealedFor = t }
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
            .onChange(of: scenePhase) { if scenePhase != .active { phrase = nil } }
            .onDisappear { phrase = nil }
            .sheet(isPresented: $adding) { AddWalletSheet(mode: .create).earthThemed() }
            .sheet(isPresented: $registering) { RegistrationSheet().earthThemed() }
        }
    }

    private func pick(_ index: Int) {
        target = index; phrase = nil; backedUp = false
        guard let info = model.switchTargetInfo(ofWallet: index) else { targetWarning = nil; return }
        // What a move after the switch could not bring there, said up front.
        if info.handleRefusal != nil || info.voteRefusal != nil {
            targetWarning = [info.handleRefusal.map { "Your handle cannot be brought there: \($0)." },
                             info.voteRefusal.map { "Your caretaker vote cannot be brought there: \($0)." }]
                .compactMap { $0 }.joined(separator: " ")
        } else {
            targetWarning = info.registered
                ? "That wallet has a registration, or sent one in the last two days that can still land. Switching to it replaces this identity with it; anything it holds stays with it."
                : nil
        }
    }

    private var form: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: theme.space.x12) {
                Text("Switching moves your personhood to another wallet: register the same passport there and this wallet stops counting as you. Once the switch lands, open Identity in the new wallet to bring your handle and caretaker vote over; each is a private move proven with both wallets' recovery phrases, which stay on this phone. Do it before switching again: a move goes only to the passport's live identity. Anything not moved stays with this wallet until it lapses, and the new identity cannot claim a handle or cast a caretaker vote until then (up to a year).")
                    .font(EarthType.bodySmall).foregroundStyle(theme.colors.textTertiary)
                Text("A switch from a wallet whose recovery phrase is lost cannot move anything: the move needs that wallet's secret. Its handle and caretaker vote wait out their leases. Back up every wallet's recovery phrase.")
                    .font(EarthType.bodySmall).foregroundStyle(theme.colors.textTertiary)
                EarthLabel("Switch to")
                let others = model.wallets.enumerated().filter { $0.offset != model.selected }
                if others.isEmpty {
                    Text("You have no other wallet on this phone. Create one first.").font(EarthType.bodySmall).foregroundStyle(theme.colors.textTertiary)
                }
                ForEach(others, id: \.offset) { index, w in
                    Button { if target != index { pick(index) } } label: {
                        HStack {
                            Image(systemName: target == index ? "checkmark.circle.fill" : "circle").foregroundStyle(theme.colors.accentInk)
                            VStack(alignment: .leading) {
                                Text(w.name).font(EarthType.body).foregroundStyle(theme.colors.textPrimary)
                                Text(w.address).font(EarthType.bodySmall).foregroundStyle(theme.colors.textTertiary).lineLimit(1).truncationMode(.middle)
                            }
                        }
                    }
                    .buttonStyle(.plain)
                }
                if let targetWarning { Text(targetWarning).font(EarthType.bodySmall).foregroundStyle(theme.colors.textTertiary) }
                EarthButton(title: "Create a new wallet", role: .secondary) { adding = true }
                if let phrase {
                    SeedGrid(words: phrase.split(separator: " ").map(String.init))
                    Text("Write these words down, in order, and keep them offline. Anyone with them controls that wallet.")
                        .font(EarthType.bodySmall).foregroundStyle(theme.colors.textTertiary)
                }
                EarthButton(title: phrase == nil ? "Show the new wallet's recovery phrase" : "Hide the recovery phrase", role: .secondary) {
                    if phrase == nil { confirming = true } else { phrase = nil }
                }
                .disabled(target == nil)
                // Ticked only once the phrase was shown for this target.
                let canTick = target != nil && revealedFor == target
                Toggle(canTick ? "I have backed up the new wallet's recovery phrase" : "Show the new wallet's recovery phrase to confirm you have backed it up",
                       isOn: Binding(get: { backedUp && canTick }, set: { backedUp = $0 }))
                    .disabled(!canTick)
                EarthButton(title: "Switch: register there") {
                    guard let t = target else { return }
                    Task { await model.select(t); registering = true }
                }
                .disabled(!(target != nil && backedUp && canTick))
            }
            .padding(theme.space.gutter)
        }
        .scrollContentBackground(.hidden)
    }
}

/// After a switch has landed: what the identity this one replaced (another
/// wallet on this phone, same passport) still holds, and a move for each.
/// Each move is proven with both identities' secrets and paid from the
/// previous wallet's private ERTH; it shows as done only once confirmed.
struct MoveOfferCard: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    @Environment(TxController.self) private var tx
    let offer: AppModel.MoveOffer
    /// Asked to read the offer again (after a move, or a check).
    let refresh: () async -> Void

    private var handleInFlight: Bool { offer.inFlight.contains { $0.kind == PendingMove.handleKind && !$0.confirmed } }
    private var voteInFlight: Bool { offer.inFlight.contains { $0.kind == PendingMove.caretakerKind && !$0.confirmed } }

    var body: some View {
        VStack(alignment: .leading, spacing: theme.space.x12) {
            EarthLabel("From your previous identity")
            note("This identity replaced the one in \(offer.fromName). You can bring what it holds here with no wait, while this is still the passport's live identity. The fee comes from \(offer.fromName)'s private ERTH.")
            if !offer.handle.isEmpty && !handleInFlight {
                if offer.handleLive {
                    EarthButton(title: "Bring your handle @\(offer.handle) to this identity") { bring(handle: true) }
                } else {
                    note("@\(offer.handle) is in its renewal period: only a live handle can move, and the old identity can no longer renew it.")
                }
            }
            if offer.voteLive && !voteInFlight {
                EarthButton(title: "Bring your Caretaker split to this identity") { bring(handle: false) }
            }
            if !offer.inFlight.isEmpty {
                note(handleInFlight || voteInFlight
                     ? "A move was sent and the chain has not confirmed it yet; this identity already counts it as pending."
                     : "A confirmed move has not been recorded in this wallet yet. It finds it on its own when it syncs; you can also record it now.")
                EarthButton(title: "Check the moves again", role: .secondary) {
                    Task { await model.checkMoves(from: offer.fromIndex); await refresh() }
                }
            }
            note("Once moved, the previous identity can never hold a handle or caretaker vote again.")
        }
    }

    private func note(_ s: String) -> some View { Text(s).font(EarthType.bodySmall).foregroundStyle(theme.colors.textTertiary) }

    /// Proven with both identities' secrets: this wallet's (the successor) and
    /// the previous one's, which builds and pays for the tx; recorded in this
    /// wallet's store before the broadcast.
    private func bring(handle: Bool) {
        let from = offer.fromIndex
        var d = TxController.Details.private(action: handle ? "Bring @\(offer.handle) to this identity" : "Bring your Caretaker split to this identity", rows: [])
        d.payerErth = offer.feeErth
        tx.requestPrivate(d, host: .identity, onSuccess: { await model.syncPrivacy(); await refresh() }) { _ in
            let to = try model.selfAsSuccessor()
            let p = try model.predecessorWallet(from)
            try await p.sync()
            let rec = model.moveRecorder(for: to.keys)
            return handle ? try await p.moveHandle(to: to, recorder: rec) : try await p.moveCaretaker(to: to, recorder: rec)
        }
    }
}
