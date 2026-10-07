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

/// A voluntary switch of identity: the same passport registered to a fresh
/// identity, another of this phone's wallets' or this wallet's next one
/// (PrivacyKeys generations). The chain zeroes the live leaf and makes a new
/// one. Once the switch has landed, the new identity can bring this one's
/// handle and caretaker vote over (`MoveOfferCard`, on Identity where it
/// registered): a move proves knowledge of both identities' secrets, so it
/// comes after the switch and needs both on this phone (one phrase, for a
/// fresh identity here).
///
/// Another wallet's recovery phrase is shown after a fresh unlock and dropped
/// when the app leaves the foreground or the screen closes; the backup box
/// needs the phrase shown first. A fresh identity here needs no new phrase.
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
    /// One step only: what this identity's predecessor still holds that has not moved here.
    @State private var unmoved: String?
    @State private var strandAccepted = false
    @State private var handleOpen = false

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
            .task {
                model.loadWallets(); await model.refreshPersonal()
                if let o = await model.moveOffer(), o.anything {
                    let what = [o.handle.isEmpty ? nil : "@\(o.handle)", o.voteLive ? "your caretaker vote" : nil].compactMap { $0 }.joined(separator: " and ")
                    let held = what.isEmpty ? "a move still in flight" : what
                    unmoved = o.withinWallet ? "Your previous identity in this wallet still holds \(held)."
                        : "\(o.fromName), the identity this one replaced, still holds \(held)."
                }
            }
            .onChange(of: scenePhase) { if scenePhase != .active { phrase = nil } }
            .onDisappear { phrase = nil }
            .sheet(isPresented: $adding) { AddWalletSheet(mode: .create).earthThemed() }
            .sheet(isPresented: $registering) { RegistrationSheet().earthThemed() }
            .sheet(isPresented: $handleOpen) { HandleScreen().earthThemed() }
        }
    }

    /// What this identity holds whose lease ends soon: renewed first (it still
    /// can), its move after the switch has a full lease to land in.
    private var renewFirst: String? {
        guard model.privacy?.snapshot.identityStatus == .live else { return nil }
        return Self.renewSoonText(handle: model.handle, handleExpiresAt: model.handleEntry?.expiresAt ?? 0,
                                  voteExpiresAt: model.caretakerExpiresAt, now: Int64(Date().timeIntervalSince1970))
    }

    /// Before a switch: what this identity holds whose lease ends within
    /// PrivacyWallet.renewFirstWindowSeconds (0 for none), as a prompt to renew
    /// it first, or nil. As Android's renewSoonText; docs quote it.
    static func renewSoonText(handle: String, handleExpiresAt: Int64, voteExpiresAt: Int64, now: Int64) -> String? {
        let soon = Handles.satAdd(now, PrivacyWallet.renewFirstWindowSeconds)
        let what = [
            !handle.isEmpty && handleExpiresAt > now && handleExpiresAt < soon ? "@\(handle) (its lease ends \(MoveOfferCard.moveTime(handleExpiresAt)))" : nil,
            voteExpiresAt > now && voteExpiresAt < soon ? "your caretaker vote (it ends \(MoveOfferCard.moveTime(voteExpiresAt)))" : nil,
        ].compactMap { $0 }
        guard !what.isEmpty else { return nil }
        return "Renew \(what.joined(separator: " and ")) before you switch. After the switch a move can bring it to your new identity only until its lease ends, and this identity can no longer renew it; renewed now, the move has a full lease."
    }

    /// The target for a fresh identity in this wallet (its next generation).
    static let switchToSelf = -1

    private func pick(_ index: Int) {
        target = index; phrase = nil; backedUp = false
        guard index != Self.switchToSelf, let info = model.switchTargetInfo(ofWallet: index) else { targetWarning = nil; return }
        // What a move after the switch could not bring there, said up front.
        // Any wallet can be a target: it registers its next unused identity.
        if info.handleRefusal != nil || info.voteRefusal != nil {
            targetWarning = [info.handleRefusal.map { "Your handle cannot be brought there: \($0)." },
                             info.voteRefusal.map { "Your caretaker vote cannot be brought there: \($0)." }]
                .compactMap { $0 }.joined(separator: " ")
        } else if info.live {
            targetWarning = "That wallet has a live registration of its own. Switching there replaces it with that wallet's next identity; what its current identity holds stays with it unless moved."
        } else {
            targetWarning = info.registered
                ? "That wallet sent a registration in the last two days that can still land. If it lands first, this switch is refused; try again once it has."
                : nil
        }
    }

    private var form: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: theme.space.x12) {
                Text("Switching moves your personhood to a fresh identity: another wallet's, or this wallet's next one. Your passport is registered again and your current identity stops counting as you. Once the switch lands, open Identity where you registered to bring your handle and caretaker vote over; each is a private move proven with both identities' secrets, which stay on this phone. Do it before their leases end, since only a live handle or vote moves and the old identity can no longer renew them, and before switching again, since a move goes only to the passport's live identity. Anything not moved stays with the old identity until it lapses, and the new identity cannot claim a handle or cast a caretaker vote until then (up to a year).")
                    .font(EarthType.bodySmall).foregroundStyle(theme.colors.textTertiary)
                Text("A switch from a wallet whose recovery phrase is lost cannot move anything: the move needs that wallet's secret. Its handle and caretaker vote wait out their leases. Back up every wallet's recovery phrase.")
                    .font(EarthType.bodySmall).foregroundStyle(theme.colors.textTertiary)
                if let renewFirst {
                    Text(renewFirst).font(EarthType.bodySmall).foregroundStyle(theme.colors.textError)
                    EarthButton(title: "Renew first", role: .secondary) {
                        let handleSoon = Self.renewSoonText(handle: model.handle, handleExpiresAt: model.handleEntry?.expiresAt ?? 0, voteExpiresAt: 0,
                                                            now: Int64(Date().timeIntervalSince1970)) != nil
                        if handleSoon { handleOpen = true } else { model.governLink = .caretaker; model.tab = .govern; dismiss() }
                    }
                }
                if let unmoved {
                    Text("\(unmoved) A move goes only from an identity to the one that replaced it, so after another switch it can never move. Bring it here first, from the Identity screen.")
                        .font(EarthType.bodySmall).foregroundStyle(theme.colors.textError)
                    Toggle("Switch anyway and leave it where it is", isOn: $strandAccepted)
                }
                EarthLabel("Switch to")
                let others = model.wallets.enumerated().filter { $0.offset != model.selected }
                let isSelf = target == Self.switchToSelf
                Button { if !isSelf { pick(Self.switchToSelf) } } label: {
                    HStack {
                        Image(systemName: isSelf ? "checkmark.circle.fill" : "circle").foregroundStyle(theme.colors.accentInk)
                        VStack(alignment: .leading) {
                            Text("A fresh identity in this wallet").font(EarthType.body).foregroundStyle(theme.colors.textPrimary)
                            Text("Same recovery phrase").font(EarthType.bodySmall).foregroundStyle(theme.colors.textTertiary)
                        }
                    }
                }
                .buttonStyle(.plain)
                if isSelf {
                    // Which fresh identity a compromise calls for: the next generation only for a secret
                    // that leaked by itself, outside the phone. The wallet holds the key every generation
                    // derives from in memory, so a memory compromise exposes them all, and so does the
                    // phrase: a new wallet then. As Android's FRESH_IDENTITY_NOTE.
                    Text("Your passport is registered to this wallet's next identity, derived from the same recovery phrase. This helps only if this identity's secret leaked by itself outside this phone, for example in a proof's witness file or a log. It does not help if this phone may be compromised or your recovery phrase may be exposed: the wallet holds the key every identity of this wallet derives from in memory, and anyone with that key or the phrase can derive them all. Then create a new wallet, with a new phrase, and switch to it.")
                        .font(EarthType.bodySmall).foregroundStyle(theme.colors.textTertiary)
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
                if others.isEmpty {
                    Text("You have no other wallet on this phone. Create one to switch to it.").font(EarthType.bodySmall).foregroundStyle(theme.colors.textTertiary)
                }
                if let targetWarning {
                    Text(targetWarning).font(EarthType.bodySmall).foregroundStyle(theme.colors.textTertiary)
                }
                EarthButton(title: "Create a new wallet", role: .secondary) { adding = true }
                // Ticked only once the phrase was shown for this target. A fresh
                // identity here is this wallet's own phrase: nothing new to back up.
                let canTick = target != nil && revealedFor == target
                if !isSelf {
                    if let phrase {
                        SeedGrid(words: phrase.split(separator: " ").map(String.init))
                        Text("Write these words down, in order, and keep them offline. Anyone with them controls that wallet.")
                            .font(EarthType.bodySmall).foregroundStyle(theme.colors.textTertiary)
                    }
                    EarthButton(title: phrase == nil ? "Show the new wallet's recovery phrase" : "Hide the recovery phrase", role: .secondary) {
                        if phrase == nil { confirming = true } else { phrase = nil }
                    }
                    .disabled(target == nil)
                    Toggle(canTick ? "I have backed up the new wallet's recovery phrase" : "Show the new wallet's recovery phrase to confirm you have backed it up",
                           isOn: Binding(get: { backedUp && canTick }, set: { backedUp = $0 }))
                        .disabled(!canTick)
                }
                EarthButton(title: isSelf ? "Switch: register a fresh identity" : "Switch: register there") {
                    guard let t = target else { return }
                    // The target registers the same passport to its next identity; a fresh identity stays in this wallet.
                    if t == Self.switchToSelf { registering = true } else { Task { await model.select(t); registering = true } }
                }
                .disabled(!(target != nil && (isSelf || (backedUp && canTick)) && (unmoved == nil || strandAccepted)))
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
    /// A move asked for before the suggested time, waiting for the user's answer.
    @State private var early: Bool?

    private var handleInFlight: Bool { offer.inFlight.contains { $0.kind == PendingMove.handleKind && !$0.confirmed } }
    private var voteInFlight: Bool { offer.inFlight.contains { $0.kind == PendingMove.caretakerKind && !$0.confirmed } }

    var body: some View {
        VStack(alignment: .leading, spacing: theme.space.x12) {
            EarthLabel("From your previous identity")
            note(offer.withinWallet
                 ? "This identity replaced your previous one in this wallet (a renewal or a fresh identity). You can bring what it holds here with no wait, while this is still the passport's live identity. The fee comes from this wallet's private ERTH."
                 : "This identity replaced the one in \(offer.fromName). You can bring what it holds here with no wait, while this is still the passport's live identity. The fee comes from \(offer.fromName)'s private ERTH.")
            if offer.suggestedAt > 0 {
                note(Self.timingText(offer, now: now))
            }
            if !offer.handle.isEmpty && !handleInFlight {
                if offer.handleLive {
                    EarthButton(title: "Bring your handle @\(offer.handle) to this identity") { move(handle: true) }
                } else {
                    note("@\(offer.handle) is in its renewal period: only a live handle can move, and the old identity can no longer renew it.")
                }
            }
            if offer.voteLive && !voteInFlight {
                EarthButton(title: "Bring your Caretaker split to this identity") { move(handle: false) }
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
        // Before the suggested time a move asks first; it is never sent on its own.
        .alert("Move before the suggested time?", isPresented: Binding(get: { early != nil }, set: { if !$0 { early = nil } })) {
            Button("Move now") { if let h = early { early = nil; bring(handle: h) } }
            Button("Wait", role: .cancel) { early = nil }
        } message: {
            Text("A move soon after your switch can be linked to it by its timing. The wallet suggests waiting until \(Self.moveTime(offer.suggestedAt)). You can still move now.")
        }
    }

    private var now: Int64 { Int64(Date().timeIntervalSince1970) }

    private func move(handle: Bool) {
        if offer.suggestedAt > now { early = handle } else { bring(handle: handle) }
    }

    /// When to move, and the deadline: the earliest lease end of what is
    /// still to move, past which the old identity can neither move nor renew
    /// it. As Android's moveTimingText; docs quote these.
    static func timingText(_ offer: AppModel.MoveOffer, now: Int64) -> String {
        let d = offer.deadline
        let lost = "after that your previous identity can neither move nor renew it, and this identity cannot claim a handle or cast a caretaker vote for up to a year."
        if d > 0 && d - now <= PrivacyWallet.moveDeadlineMarginSeconds {
            return "Move now: \(offer.deadlineWhat) on \(moveTime(d)), too soon to wait after your switch; \(lost)"
        }
        if offer.suggestedAt > now && d > 0 {
            return "Suggested: move after \(moveTime(offer.suggestedAt)). A move right after a switch can be linked to it by its timing, so the wallet picked a random time for you. Move by \(moveTime(d)), when \(offer.deadlineWhat); \(lost) The wallet reminds you; it never moves anything on its own."
        }
        if offer.suggestedAt > now {
            return "Suggested: move after \(moveTime(offer.suggestedAt)). A move right after a switch can be linked to it by its timing, so the wallet picked a random time for you. Move before what your previous identity holds lapses: it can no longer renew it. The wallet reminds you; it never moves anything on its own."
        }
        if d > 0 {
            return "The suggested time to move has come. Move by \(moveTime(d)), when \(offer.deadlineWhat); \(lost) Move before you switch again, too."
        }
        return "The suggested time to move has come. Move before what your previous identity holds lapses (it can no longer renew it), and before you switch again."
    }

    /// A suggested move time, in the phone's time zone.
    static func moveTime(_ at: Int64) -> String {
        Date(timeIntervalSince1970: TimeInterval(at)).formatted(date: .abbreviated, time: .shortened)
    }

    private func note(_ s: String) -> some View { Text(s).font(EarthType.bodySmall).foregroundStyle(theme.colors.textTertiary) }

    /// Proven with both identities' secrets: this wallet's (the successor) and
    /// the previous one's, which builds and pays for the tx; recorded in this
    /// wallet's store before the broadcast.
    private func bring(handle: Bool) {
        let from = offer.fromIndex
        let expected = offer.handle
        let within = offer.withinWallet
        let generation = offer.fromGeneration
        // The identity this one replaced pays: another wallet's, or this one's.
        var d = TxController.Details.private(action: handle ? "Bring @\(offer.handle) to this identity" : "Bring your Caretaker split to this identity",
                                             rows: [("Fee paid by", within ? "This wallet (its private ERTH)" : "\(offer.fromName) (its private ERTH)")])
        d.payerErth = offer.feeErth
        tx.requestPrivate(d, host: .identity, onSuccess: { await model.syncPrivacy(); await refresh() }) { w in
            if within {
                // This wallet's earlier identity: one phrase holds both secrets.
                try await w.sync()
                return handle ? try await w.moveHandleWithin(from: generation, expected: expected) : try await w.moveCaretakerWithin(from: generation)
            }
            let to = try model.selfAsSuccessor()
            let p = try model.predecessorWallet(from)
            try await p.sync()
            let rec = model.moveRecorder(for: to.keys)
            return handle ? try await p.moveHandle(to: to, recorder: rec, expected: expected) : try await p.moveCaretaker(to: to, recorder: rec)
        }
    }
}
