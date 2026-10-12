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

/// A screen's one big line and the short line under it, as the Stake screen's
/// hero: the thing itself, then its state in a few words.
private struct Hero: View {
    @Environment(\.earth) private var theme
    let title: String
    var muted = false
    var line: (text: String, color: Color)?

    var body: some View {
        VStack(spacing: 0) {
            Text(title)
                .font(.system(size: 40, weight: .bold))
                .foregroundStyle(muted ? theme.colors.textTertiary : theme.colors.textPrimary)
                .lineLimit(1)
                .minimumScaleFactor(0.5)
            if let line {
                Text(line.text)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(line.color)
                    .multilineTextAlignment(.center)
                    .padding(.top, theme.space.x12)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.top, theme.space.x24)
    }
}

/// A short caption: a warning at the point of action, or a state in a few words.
private struct Caption: View {
    @Environment(\.earth) private var theme
    let text: String
    var error = false

    init(_ text: String, error: Bool = false) { self.text = text; self.error = error }

    var body: some View {
        Text(text)
            .font(EarthType.bodySmall)
            .foregroundStyle(error ? theme.colors.textError : theme.colors.textTertiary)
    }
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
                VStack(spacing: 0) {
                    Hero(title: shown ? "@\(model.handle)" : "No handle", muted: !shown, line: heroLine)
                    VStack(alignment: .leading, spacing: theme.space.x12) { content }
                        .padding(.top, theme.space.x32)
                }
                .padding(.horizontal, theme.space.gutter)
                .padding(.bottom, theme.space.x32)
            }
            .navigationTitle("Handle")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } } }
            .background(theme.colors.bgPrimary)
            .scrollContentBackground(.hidden)
            .task { await model.refreshPersonal() }
        }
    }

    private var shown: Bool { model.isRegistered && !model.handle.isEmpty }

    /// The hero's one line: where the handle stands. A move in flight wins,
    /// since nothing else here can be done until it lands.
    private var heroLine: (text: String, color: Color)? {
        let quiet = theme.colors.textTertiary
        guard model.isRegistered else { return ("Register to claim one", quiet) }
        if movingOut { return ("Moving to your new identity", quiet) }
        if model.incomingMoves.contains(where: { $0.kind == PendingMove.handleKind }) { return ("Move waiting for the chain", quiet) }
        if model.handle.isEmpty {
            if model.handleMovedOut { return ("Moved to another identity", quiet) }
            if waitUntil > now { return ("New claims from \(day(waitUntil))", quiet) }
            return ("Get paid by name", quiet)
        }
        guard let e = model.handleEntry else { return ("Not in the directory", theme.colors.textError) }
        let st = e.status(at: now)
        if st == HandleEntry.live { return ("Live · until \(day(e.expiresAt))", theme.colors.accentInk) }
        if st == HandleEntry.renewal { return ("Expired · only you can renew", theme.colors.textError) }
        return ("Released", quiet)
    }

    /// When an identity that replaced another may claim: the old one's lease
    /// and a day's margin past its last possible bind. Clamped: a hostile lease
    /// param cannot trap here.
    private var waitUntil: Int64 {
        model.predecessorAt > 0
            ? Handles.satAdd(Handles.satAdd(Int64(clamping: model.predecessorAt), model.handleLeaseSeconds), 86_400 + 3_600) : 0
    }

    @ViewBuilder
    private var content: some View {
        if model.isRegistered {
            ForEach(Array(model.reminders.enumerated()), id: \.offset) { _, r in
                switch r {
                case let .handleExpiring(h, _, _, _) where h == model.handle: ReminderBanner(text: Reminders.text(r, now: now)) { renew() }
                case .handlePaysElsewhere: ReminderBanner(text: Reminders.text(r, now: now)) { renew() }
                default: EmptyView()
                }
            }
            if let err = model.handleDirectoryError {
                Caption("Couldn't read the directory: \(err)", error: true)
            }
            // Entries naming this wallet's address that the store does not hold (a restore
            // loses track of a handle): renewing one is checked by the chain, at no cost if it is not ours.
            // Only while no handle is held (a bind of another would free it), and renew-only.
            ForEach(model.handle.isEmpty ? model.addressedHandles : [], id: \.handle) { a in
                EarthDetailRow(label: "Names this wallet", value: "@\(a.handle)")
                EarthDetailRow(label: "Expires", value: day(a.expiresAt))
                EarthPillButton(title: "Renew @\(a.handle)") { bind(a.handle) }
                Caption("Free if it isn't yours")
            }
            if !model.handle.isEmpty && !movingOut { held }
            if !movingOut { claim }
        }
    }

    private var movingOut: Bool { model.outgoingMoves.contains { $0.kind == PendingMove.handleKind && !$0.confirmed } }

    @ViewBuilder
    private var held: some View {
        HStack(spacing: theme.space.x8) {
            EarthPillButton(title: model.handleEntry.map { $0.address != model.shieldedAddress } == true ? "Renew here" : "Renew 1 year") { renew() }
            EarthPillButton(title: copied ? "Copied" : "Copy link", role: .secondary) {
                Clipboard.copy("Pay me privately on Earth: @\(model.handle) · join with https://erth.network/ref/\(model.handle)")
                copied = true
            }
        }
        if let e = model.handleEntry {
            if e.status(at: now) == HandleEntry.renewal {
                // Past expiry, a renewal is bounded like a claim, and the handle cannot be moved.
                Caption(waitUntil > now
                        ? "Renew \(day(waitUntil))–\(day(e.renewalUntil)) · can't move"
                        : "Renew by \(day(e.renewalUntil)) · can't move", error: true)
            }
            EarthDetailRow(label: "Expires", value: day(e.expiresAt))
            EarthDetailRow(label: "Renewable until", value: day(e.renewalUntil))
            EarthDetailRow(label: "Pays", value: e.address == model.shieldedAddress
                ? "This wallet · \(Handles.truncate(e.address))" : "Another address · \(Handles.truncate(e.address))")
        }
        VStack(spacing: theme.space.x4) {
            Button {
                let h = model.handle
                tx.requestPrivate(.private(action: "Release @\(h)", rows: [], gas: PrivacyWallet.bindHandleGasEstimate), onSuccess: { await refreshed() }) { w in
                    try await w.releaseHandle()
                }
            } label: {
                Text("Release @\(model.handle)")
                    .font(EarthType.body).fontWeight(.semibold)
                    .foregroundStyle(theme.colors.textError)
                    .frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.plain)
            Caption("Anyone can claim it after")
        }
        .frame(maxWidth: .infinity)
        .padding(.top, theme.space.x8)
    }

    @ViewBuilder
    private var claim: some View {
        // Moved out: the hero says so, and there is nothing to claim.
        if !(model.handleMovedOut && model.handle.isEmpty) {
            let parsed = Handles.parse(input)
            EarthLabel(model.handle.isEmpty ? (waitUntil > now ? "Renew a handle this identity holds" : "Claim a handle") : "Change to another handle")
                .padding(.top, theme.space.x12)
            TextField("@name", text: $input)
                .font(EarthType.mono)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .padding(theme.space.x12)
                .background(theme.colors.bgPrimary, in: .rect(cornerRadius: theme.space.radiusMd))
                .overlay { RoundedRectangle(cornerRadius: theme.space.radiusMd).strokeBorder(theme.colors.strokePrimary, lineWidth: theme.space.stroke) }
            if !input.isEmpty, parsed == nil {
                Caption("3-32 of a-z, 0-9, -; no end dash", error: true)
            }
            EarthPillButton(title: model.handle.isEmpty ? "Claim \(parsed.map { "@\($0)" } ?? "handle")" : "Change to \(parsed.map { "@\($0)" } ?? "…")") {
                guard let h = parsed else { return }
                let pays = "@\(h) · \(Handles.truncate(model.shieldedAddress))"
                tx.requestPrivate(.private(action: model.handle.isEmpty ? "Claim @\(h)" : "Change handle to @\(h)", rows: [("Pays", pays)],
                                           gas: PrivacyWallet.bindHandleGasEstimate), onSuccess: { input = ""; await refreshed() }) { w in
                    try await w.bindHandle(h)
                }
            }
            .disabled(parsed == nil || parsed == model.handle)
            Caption(model.handle.isEmpty ? "One per person · fee spent if taken" : "Frees @\(model.handle) for anyone")
                .frame(maxWidth: .infinity)
        }
    }

    /// Renew-only: the wallet refuses a bind that would change
    /// the handle held; the sheet names a change as one all the same.
    private func bind(_ h: String) {
        let changing = !model.handle.isEmpty && model.handle != h
        tx.requestPrivate(.private(action: changing ? "Change handle to @\(h)" : "Renew @\(h) for a year",
                                   rows: [("Pays", "@\(h) · \(Handles.truncate(model.shieldedAddress))")],
                                   gas: PrivacyWallet.bindHandleGasEstimate), onSuccess: { await refreshed() }) { w in
            try await w.bindHandle(h, renewOnly: true)
        }
    }

    private func renew() {
        let h = model.handle
        guard !h.isEmpty else { return }
        tx.requestPrivate(.private(action: "Renew @\(h) for a year", rows: [("Pays", "@\(h) · \(Handles.truncate(model.shieldedAddress))")],
                                   gas: PrivacyWallet.bindHandleGasEstimate), onSuccess: { await refreshed() }) { w in
            try await w.bindHandle(h, renewOnly: true)
        }
    }

    private func refreshed() async {
        await model.invalidateHandles()
        await model.syncPrivacy()
    }
}

/// A voluntary switch of identity: the same passport registered to another
/// of this phone's wallets (its next identity). The chain zeroes the live leaf
/// and makes a new one. Once the switch has landed, the new identity can bring
/// this one's handle and caretaker vote over (`MoveOfferCard`, on Identity
/// where it registered): a move proves knowledge of both identities' secrets,
/// so it comes after the switch and needs both on this phone.
///
/// Not offered: a fresh identity in this same wallet. It derives from the same
/// phrase, so it helps only if an identity secret leaked by itself off the
/// phone, never for a lost phone or an exposed phrase, which is what anyone
/// reaching for this screen is afraid of. Renewal still registers the next
/// identity here; that path is Identity's, not this one.
///
/// Another wallet's recovery phrase is shown after a fresh unlock and dropped
/// when the app leaves the foreground or the screen closes; the backup box
/// needs the phrase shown first.
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
                    let held = what.isEmpty ? "a move in flight" : what
                    unmoved = o.withinWallet ? "Your previous identity still holds \(held)"
                        : "\(o.fromName) still holds \(held)"
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
    /// it first, or nil. Android's renewSoonText's condition, in fewer words:
    /// after the switch a move can bring it over only until its lease ends,
    /// and this identity can no longer renew it.
    static func renewSoonText(handle: String, handleExpiresAt: Int64, voteExpiresAt: Int64, now: Int64) -> String? {
        let soon = Handles.satAdd(now, PrivacyWallet.renewFirstWindowSeconds)
        let what = [
            !handle.isEmpty && handleExpiresAt > now && handleExpiresAt < soon ? "@\(handle)" : nil,
            voteExpiresAt > now && voteExpiresAt < soon ? "your vote" : nil,
        ].compactMap { $0 }
        guard !what.isEmpty else { return nil }
        return "Renew \(what.joined(separator: " and ")) before switching"
    }

    private func pick(_ index: Int) {
        target = index; phrase = nil; backedUp = false
        guard let info = model.switchTargetInfo(ofWallet: index) else { targetWarning = nil; return }
        // What a move after the switch could not bring there, said up front.
        // Any wallet can be a target: it registers its next unused identity.
        if info.handleRefusal != nil || info.voteRefusal != nil {
            targetWarning = [info.handleRefusal.map { "Handle can't move there: \($0)" },
                             info.voteRefusal.map { "Vote can't move there: \($0)" }]
                .compactMap { $0 }.joined(separator: "\n")
        } else if info.live {
            // Its current identity keeps what it holds unless moved.
            targetWarning = "Replaces that wallet's live identity"
        } else {
            // A registration from the last two days that lands first refuses this switch.
            targetWarning = info.registered ? "Its pending registration may block this" : nil
        }
    }

    private var form: some View {
        ScrollView {
            VStack(spacing: 0) {
                Hero(title: "New identity", line: ("Move your passport to another wallet", theme.colors.textTertiary))
                VStack(alignment: .leading, spacing: theme.space.x12) { options }
                    .padding(.top, theme.space.x32)
            }
            .padding(.horizontal, theme.space.gutter)
            .padding(.bottom, theme.space.x32)
        }
        .scrollContentBackground(.hidden)
    }

    @ViewBuilder
    private var options: some View {
        if let renewFirst {
            Caption(renewFirst, error: true)
            EarthPillButton(title: "Renew first", role: .secondary) {
                let handleSoon = Self.renewSoonText(handle: model.handle, handleExpiresAt: model.handleEntry?.expiresAt ?? 0, voteExpiresAt: 0,
                                                    now: Int64(Date().timeIntervalSince1970)) != nil
                if handleSoon { handleOpen = true } else { model.governLink = .caretaker; model.tab = .govern; dismiss() }
            }
        }
        if let unmoved {
            // A move goes only from an identity to the one that replaced it, so
            // after another switch it can never move.
            Caption("\(unmoved). Switching strands it for good.", error: true)
            Toggle("Switch anyway", isOn: $strandAccepted)
        }
        EarthLabel("Switch to")
        let others = model.wallets.enumerated().filter { $0.offset != model.selected }
        ForEach(others, id: \.offset) { index, w in
            choice(title: w.name, subtitle: w.address, selected: target == index) {
                if target != index { pick(index) }
            }
        }
        if others.isEmpty {
            Caption("No other wallets on this phone")
        }
        if let targetWarning {
            Caption(targetWarning)
        }
        EarthPillButton(title: "Create a new wallet", role: .secondary) { adding = true }
        // Ticked only once the phrase was shown for this target.
        let canTick = target != nil && revealedFor == target
        if let phrase {
            SeedGrid(words: phrase.split(separator: " ").map(String.init))
            Caption("Write down in order. Keep offline.")
        }
        EarthPillButton(title: phrase == nil ? "Show recovery phrase" : "Hide recovery phrase", role: .secondary) {
            if phrase == nil { confirming = true } else { phrase = nil }
        }
        .disabled(target == nil)
        Toggle(canTick ? "I backed up the phrase" : "Show the phrase first",
               isOn: Binding(get: { backedUp && canTick }, set: { backedUp = $0 }))
            .disabled(!canTick)
        EarthPillButton(title: "Switch") {
            guard let t = target else { return }
            // The target registers the same passport to its next identity.
            Task { await model.select(t); registering = true }
        }
        .disabled(!(target != nil && backedUp && canTick && (unmoved == nil || strandAccepted)))
        .padding(.top, theme.space.x8)
        // The old identity no longer counts once registered elsewhere; a lost
        // phrase leaves its handle and vote to wait out their leases.
        Caption("This identity stops counting. Move handle and vote after.")
            .frame(maxWidth: .infinity)
    }

    private func choice(title: String, subtitle: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: theme.space.x12) {
                Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 22))
                    .foregroundStyle(theme.colors.accentInk)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(EarthType.body).fontWeight(.semibold).foregroundStyle(theme.colors.textPrimary).lineLimit(1)
                    Text(subtitle).font(EarthType.bodySmall).foregroundStyle(theme.colors.textTertiary).lineLimit(1).truncationMode(.middle)
                }
                Spacer(minLength: 0)
            }
            .padding(.vertical, theme.space.x8)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
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
        let canHandle = !offer.handle.isEmpty && !handleInFlight && offer.handleLive
        let canVote = offer.voteLive && !voteInFlight
        VStack(alignment: .leading, spacing: theme.space.x12) {
            EarthLabel("From your previous identity")
            if offer.suggestedAt > 0 {
                Text(Self.timingText(offer, now: now))
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(urgent ? theme.colors.textError : theme.colors.textPrimary)
            }
            if !offer.handle.isEmpty && !handleInFlight && !offer.handleLive {
                // Only a live handle can move, and the old identity can no longer renew it.
                Caption("@\(offer.handle) expired; it can't move")
            }
            if canHandle || canVote {
                HStack(spacing: theme.space.x8) {
                    if canHandle { EarthPillButton(title: "Bring @\(offer.handle)") { move(handle: true) } }
                    if canVote { EarthPillButton(title: "Bring vote", role: canHandle ? .secondary : .primary) { move(handle: false) } }
                }
                Caption("Fee from \(offer.withinWallet ? "this wallet" : offer.fromName) · old identity can't hold them again")
            }
            if !offer.inFlight.isEmpty {
                // In flight: this identity already counts it as pending. Confirmed
                // but unrecorded: a sync finds it on its own; the check records it now.
                Caption(handleInFlight || voteInFlight ? "Move sent · waiting for the chain" : "Move confirmed · not recorded yet")
                EarthPillButton(title: "Check again", role: .secondary) {
                    Task { await model.checkMoves(from: offer.fromIndex); await refresh() }
                }
            }
        }
        // Before the suggested time a move asks first; it is never sent on its own.
        .alert("Move before the suggested time?", isPresented: Binding(get: { early != nil }, set: { if !$0 { early = nil } })) {
            Button("Move now") { if let h = early { early = nil; bring(handle: h) } }
            Button("Wait", role: .cancel) { early = nil }
        } message: {
            Text("Moving soon after a switch can link the two. Suggested: after \(Self.moveTime(offer.suggestedAt)).")
        }
    }

    private var now: Int64 { Int64(Date().timeIntervalSince1970) }

    private var urgent: Bool { offer.deadline > 0 && offer.deadline - now <= PrivacyWallet.moveDeadlineMarginSeconds }

    private func move(handle: Bool) {
        if offer.suggestedAt > now { early = handle } else { bring(handle: handle) }
    }

    /// When to move, and the deadline: the earliest lease end of what is
    /// still to move, past which the old identity can neither move nor renew
    /// it, and this one cannot claim a handle or vote for up to a year. The
    /// suggested time is random, since a move right after a switch can be
    /// linked to it by its timing. Android's moveTimingText's cases, in fewer words.
    static func timingText(_ offer: AppModel.MoveOffer, now: Int64) -> String {
        let d = offer.deadline
        if d > 0 && d - now <= PrivacyWallet.moveDeadlineMarginSeconds {
            return "Move now · lapses \(moveTime(d))"
        }
        if offer.suggestedAt > now && d > 0 {
            return "Move after \(moveTime(offer.suggestedAt)) · by \(moveTime(d))"
        }
        if offer.suggestedAt > now {
            return "Move after \(moveTime(offer.suggestedAt))"
        }
        if d > 0 {
            return "Ready to move · by \(moveTime(d))"
        }
        return "Ready to move"
    }

    /// A suggested move time, in the phone's time zone.
    static func moveTime(_ at: Int64) -> String {
        Date(timeIntervalSince1970: TimeInterval(at)).formatted(date: .abbreviated, time: .shortened)
    }

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
        tx.requestPrivate(d, onSuccess: { await model.syncPrivacy(); await refresh() }) { w in
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
