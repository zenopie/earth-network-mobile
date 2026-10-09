import BigInt
import EarthCore
import SwiftUI

/// Home.
///
/// A close port of the Android screen, because the composition is the thing
/// worth having: the balance centred with nothing beside it, four equal
/// actions on a row of squarish cards, and the activity list rising to tuck
/// under those cards so the screen reads as one surface rather than three
/// stacked panels.
struct WalletScreen: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    @Environment(TxController.self) private var tx

    @State private var sending = false
    @State private var receiving = false
    @State private var registering = false
    @State private var moving = false
    @State private var moveDirection = MoveSheet.Direction.shield
    /// The coin whose sheet is open, and the move it asked for: opened once
    /// its sheet is gone, as one sheet cannot present over another closing.
    @State private var coin: CoinPick?
    @State private var pendingMove: MoveSheet.Direction?
    /// Which list is under the cards. The third action toggles it.
    @State private var panel = Panel.activity
    @State private var handleOpen = false
    @State private var identityOpen = false
    @State private var networkOpen = false

    enum Panel { case activity, portfolio }

    struct CoinPick: Identifiable {
        let denom: String
        var id: String { denom }
    }

    var body: some View {
        VStack(spacing: 0) {
            Spacer().frame(height: 8)
            BalanceWidget()
            // A node that failed its recheck (or awaits it) leads: Earth's node is in use meanwhile.
            if let notice = model.nodeNotice {
                ReminderBanner(text: notice) { networkOpen = true }
                    .padding(.horizontal, 24)
                    .padding(.top, 6)
            }
            // What is due, never done unasked: each reminder opens where it is done.
            ForEach(Array(model.reminders.enumerated()), id: \.offset) { _, r in
                ReminderBanner(text: Reminders.text(r, now: Int64(Date().timeIntervalSince1970))) { open(r) }
                    .padding(.horizontal, 24)
                    .padding(.top, 6)
            }
            Spacer().frame(height: 16)
            HomeActions(
                panel: $panel,
                onReceive: { receiving = true },
                onSend: { sending = true },
                onRegister: { registering = true }
            )
            .padding(.horizontal, 24)
            .offset(y: 8)
            .zIndex(1)
            Spacer().frame(height: 2)
            HomePanel(panel: panel) { coin = CoinPick(denom: $0) }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(theme.colors.bgPrimary)
        .sheet(isPresented: $sending) { SendSheet().earthThemed() }
        .sheet(isPresented: $receiving) { ReceiveSheet().earthThemed() }
        .sheet(isPresented: $registering) { RegistrationSheet().earthThemed() }
        .sheet(item: $coin, onDismiss: {
            guard let d = pendingMove else { return }
            pendingMove = nil
            moveDirection = d
            moving = true
        }) { pick in
            CoinSheet(denom: pick.denom) { pendingMove = $0 }.earthThemed()
        }
        .sheet(isPresented: $moving) { MoveSheet(direction: moveDirection).earthThemed() }
        .sheet(isPresented: $handleOpen) { HandleScreen().earthThemed() }
        .sheet(isPresented: $identityOpen) { IdentityScreen().earthThemed() }
        .sheet(isPresented: $networkOpen) { NetworkScreen().earthThemed() }
    }

    private func open(_ r: Reminders.Reminder) {
        switch r {
        case .anmlReady:
            tx.requestPrivate(.private(action: "Claim", rows: [("Token", "ANML"), ("Amount", "1 ANML"), ("Paid as", "a private note")]),
                              onSuccess: { await model.syncPrivacy() }) { w in try await w.claimAnml() }
        case .caretakerExpiring: model.governLink = .caretaker; model.tab = .govern
        case .groundworksExpiring: model.governLink = .groundworks; model.tab = .govern
        case .handleExpiring, .handlePaysElsewhere: handleOpen = true
        case .moveSuggested: identityOpen = true
        // The handle first when it is one of them; the caretaker vote is renewed where it is cast.
        case let .renewBeforeLapse(_, h, _, _):
            if h.isEmpty { model.governLink = .caretaker; model.tab = .govern } else { handleOpen = true }
        }
    }
}

/// The balance: ERTH large, ANML beneath it.
///
/// The fractional part is set smaller than the whole. It keeps a six-decimal
/// micro-denomination from dominating a glance without truncating it away,
/// which matters when the fee is measured in the digits being shrunk.
///
/// ANML sits under ERTH rather than beside it because they are not peers: ERTH
/// is what the wallet spends and what the fee comes out of, ANML is what
/// personhood accrues. Two equal-sized numbers side by side would invite
/// adding them together.
struct BalanceWidget: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model

    var body: some View {
        VStack(spacing: 0) {
            HStack(alignment: .center, spacing: 6) {
                EarthAsset.erthLogo?
                    .resizable().scaledToFit()
                    .frame(width: 28, height: 28)
                if model.balancesVisible {
                    // Transparent and shielded ERTH together: both are the
                    // owner's. Private actions pay their fees from the shielded part.
                    SplitAmount(amount: Figures.plain(model.balance(.erth) + BigInt(model.shieldedErth)))
                } else {
                    Text("-----")
                        .font(EarthType.header2).fontWeight(.semibold)
                        .foregroundStyle(theme.colors.textPrimary)
                }
            }

            Spacer().frame(height: 6)

            HStack(alignment: .center, spacing: 4) {
                EarthAsset.anml?
                    .resizable().scaledToFit()
                    .frame(width: 16, height: 16)
                Text(model.balancesVisible
                     // ANML exists only shielded.
                     ? "\(Figures.plain(model.balance(.anml) + BigInt(model.shielded["uanml"] ?? 0))) ANML"
                     : "---")
                    .font(EarthType.body)
                    .foregroundStyle(theme.colors.textTertiary)
            }

            // Why the private side (notes, stake, registration) is not
            // verified against the chain: shown, never hidden.
            if let notice = model.privacySyncError {
                Spacer().frame(height: 6)
                Text("Private balances, stake and registration: \(notice)")
                    .font(EarthType.bodySmall)
                    .foregroundStyle(theme.colors.textSecondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 24)
            }
        }
    }
}

/// The whole in display size, the fraction one step down.
struct SplitAmount: View {
    @Environment(\.earth) private var theme
    let amount: String

    var body: some View {
        let parts = amount.split(separator: ".", maxSplits: 1)
        HStack(alignment: .top, spacing: 0) {
            Text(parts.first.map(String.init) ?? amount)
                .font(EarthType.header2).fontWeight(.semibold)
                .foregroundStyle(theme.colors.textPrimary)
            if parts.count > 1 {
                Text(".\(parts[1])")
                    .font(EarthType.caption).fontWeight(.semibold)
                    .foregroundStyle(theme.colors.textPrimary)
                    .padding(.top, 8)
            }
        }
    }
}

/// Four equal actions.
///
/// Filled as a primary, not outlined: these are the app's headline actions,
/// the first thing on the first screen, and there is nothing above them to
/// rank against. Making them the quieter of the two ranks would say the main
/// thing is somewhere else.
struct HomeActions: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    @Environment(TxController.self) private var tx

    @Binding var panel: WalletScreen.Panel

    let onReceive: () -> Void
    let onSend: () -> Void
    let onRegister: () -> Void

    /// Recomputed once a second only while a claim is actually pending, so the
    /// label counts down without the row ticking the rest of the time.
    @State private var now = Date()

    var body: some View {
        HStack(spacing: 9) {
            BigIconButton(label: "Receive", symbol: "arrow.down", action: onReceive)
            BigIconButton(label: "Send", symbol: "arrow.up", action: onSend)
            // Not a second way to the Earn tab — that is one tap away in the
            // bar already. This swaps the list below, and names what it will
            // switch to rather than what is showing, so the card is always an
            // action.
            BigIconButton(
                label: panel == .activity ? "Portfolio" : "Activity",
                symbol: panel == .activity ? "chart.pie" : "list.bullet"
            ) {
                panel = panel == .activity ? .portfolio : .activity
            }
            // The ANML coin in its own colour: this is the one action here
            // about a specific token rather than about the balance, and the
            // mark says which token faster than the word does.
            BigIconButton(
                label: claimLabel,
                image: EarthAsset.anml,
                // Greyed out when the day's claim is already taken, and again
                // when there is no registration to claim against — both are
                // "nothing to collect", and both should look it.
                enabled: notRegistered || model.canClaimAnml,
                action: notRegistered ? onRegister : claim
            )
        }
        .task {
            // Ticks only while something is counting down.
            while !Task.isCancelled, !notRegistered, !model.canClaimAnml {
                try? await Task.sleep(for: .seconds(1))
                now = Date()
            }
        }
    }

    private var notRegistered: Bool { !model.isRegistered }

    /// Three states, and the button is a different action in the first.
    ///
    /// An unregistered wallet has nothing to claim, so the slot offers the
    /// thing that would give it something instead of a disabled button
    /// explaining why it cannot be pressed.
    private var claimLabel: String {
        if notRegistered { return "Verify" }
        if model.canClaimAnml { return "Claim" }
        let opens = model.claimOpensAt ?? Personhood.nextClaimOpensAt()
        let seconds = opens - Int64(now.timeIntervalSince1970)
        return countdown(max(0, seconds))
    }

    /// "5h 12m", or "48s" in the last minute. Minutes are dropped past an hour
    /// and seconds past a minute: at that distance the extra unit is noise.
    private func countdown(_ seconds: Int64) -> String {
        let hours = seconds / 3600
        let minutes = (seconds % 3600) / 60
        if hours > 0 { return "\(hours)h \(minutes)m" }
        if minutes > 0 { return "\(minutes)m" }
        return "\(seconds)s"
    }

    /// Private: a membership proof in the day's claim scope, its fee paid
    /// from a shielded ERTH note, the ANML minted as a note. Nothing links it
    /// to the registration or to any other claim. Nothing claims on its own:
    /// a reminder says when the day's claim is open.
    private func claim() {
        tx.requestPrivate(.private(action: "Claim", rows: [("Token", "ANML"), ("Amount", "1 ANML"), ("Paid as", "a private note")]),
                          onSuccess: { await model.syncPrivacy() }) { w in
            try await w.claimAnml()
        }
    }
}

/// One action card: icon over label, on brand.
///
/// The proportion is fixed rather than left to the content. With four labels
/// of different lengths, letting height follow content makes one card a few
/// points taller than its neighbours, which reads as a mistake.
struct BigIconButton: View {
    @Environment(\.earth) private var theme
    let label: String
    /// A system glyph, tinted with the card's ink.
    var symbol: String?
    /// Artwork, drawn as-is. Only the ANML coin: it names a specific token,
    /// and a monochrome outline of a coin identifies nothing.
    var image: Image?
    var enabled = true
    let action: () -> Void

    private static let ratio: CGFloat = 106.0 / 100.0

    var body: some View {
        Button(action: action) {
            VStack(spacing: 4) {
                Spacer(minLength: 0)
                Group {
                    if let image {
                        // Untinted art keeps its colour even when disabled.
                        // The ANML coin is the one thing on the card that says
                        // which token it claims, and greying it out costs that
                        // to restate what the fill and the label already say.
                        image.resizable().scaledToFit()
                    } else if let symbol {
                        Image(systemName: symbol)
                            .font(.system(size: 22, weight: .semibold))
                            .foregroundStyle(ink)
                    }
                }
                .frame(width: 24, height: 24)
                Text(label)
                    .font(EarthType.caption).fontWeight(.medium)
                    .foregroundStyle(ink)
                    .lineLimit(1)
                Spacer(minLength: 0)
            }
            .frame(maxWidth: .infinity)
            .aspectRatio(Self.ratio, contentMode: .fit)
            .background(fill, in: .rect(cornerRadius: 22))
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }

    private var fill: Color {
        enabled ? theme.colors.brandButtonBg : theme.colors.buttonDisabledBg
    }

    private var ink: Color {
        enabled ? theme.colors.brandButtonFg : theme.colors.buttonDisabledFg
    }
}

/// The list under the action cards: what has happened, or what is held.
struct HomePanel: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model

    let panel: WalletScreen.Panel
    /// A coin row was tapped: its denom.
    let onCoin: (String) -> Void
    /// The activity row whose detail sheet is open.
    @State private var opened: ActivityEntry?

    var body: some View {
        ScrollView {
            LazyVStack(spacing: 0) {
                HStack {
                    Text(panel == .activity ? "Activity" : "Portfolio")
                        .font(EarthType.body).fontWeight(.semibold)
                        .foregroundStyle(theme.colors.textPrimary)
                    Spacer()
                }
                .padding(.horizontal, 24)
                .padding(.vertical, 8)

                if panel == .portfolio {
                    portfolio
                } else if let activity = model.activity {
                    ActivityList(entries: activity) { opened = $0 }
                } else {
                    // Placeholder rows rather than a spinner: the list keeps
                    // its shape, so nothing jumps when the real rows land.
                    ForEach(0 ..< 3, id: \.self) { _ in ActivityPlaceholder() }
                }
            }
            // The cards sit over the top of this, so the content starts below
            // them rather than behind them.
            .padding(.top, 24)
            .padding(.bottom, 24)
        }
        .refreshable { await model.refresh() }
        .scrollContentBackground(.hidden)
        .sheet(item: $opened) { ActivityDetailSheet(entry: $0).earthThemed() }
    }

    /// Every coin, each with its private and public amount (a tap opens its
    /// Shield / Unshield), then what is held but not spendable: stake,
    /// positions, rewards, unbonding. Those are named apart because a wallet
    /// that shows only the spendable figure looks to its owner like it lost
    /// the rest.
    @ViewBuilder
    private var portfolio: some View {
        ForEach(ShieldMove.coins(public: model.balances, shielded: model.shielded), id: \.denom) { coin in
            CoinRow(coin: coin) { onCoin(coin.denom) }
        }
        let hasPosition = model.totalStaked > 0 || model.rewards > 0 || model.unbondingTotal > 0 ||
            model.privateStakeTotal > 0 || !model.pendingUnbonds.isEmpty

        if hasPosition {
            HStack {
                Text("Positions")
                    .font(EarthType.bodySmall).fontWeight(.semibold)
                    .foregroundStyle(theme.colors.textSecondary)
                Spacer()
            }
            .padding(.horizontal, 24)
            .padding(.top, 16)
            .padding(.bottom, 4)
            if model.totalStaked > 0 {
                positionRow("Staked", "Delegated · earning", "shield.fill",
                            Figures.whole(model.totalStaked) + " ERTH")
            }
            // Private stake: derth notes, worth more ERTH each epoch as rewards
            // compound into the validator's rate.
            ForEach(model.privateStake.sorted(by: { $0.key < $1.key }), id: \.key) { denom, amount in
                let op = String(denom.dropFirst("derth/".count))
                positionRow("Staked (private)", Figures.balance(BigInt(amount)) + " derth · " + op, "shield.lefthalf.filled",
                            Figures.balance(BigInt(model.derthValue(amount, validator: op))) + " ERTH")
            }
            // An undelegation waiting for its payout, from this wallet's own
            // record until the chain mints the payout to it.
            ForEach(model.pendingUnbonds, id: \.txHash) { u in
                positionRow("Unstaking (private)",
                            u.dueBy.map { "Arrives by about " + Date(timeIntervalSince1970: TimeInterval($0)).formatted(date: .abbreviated, time: .shortened) }
                                ?? "Arrives once the unbonding period ends",
                            "clock.arrow.circlepath",
                            Figures.balance(BigInt(u.value ?? model.derthValue(u.derth, validator: u.validator))) + " ERTH")
            }
            if model.rewards > 0 {
                positionRow("Rewards", "Pending", "sparkles",
                            Figures.precise(model.rewards) + " ERTH")
            }
            if model.unbondingTotal > 0 {
                // Neither spendable nor earning, and it returns on its own —
                // so it is named apart rather than folded into staked, where
                // it would look like it was still working.
                positionRow("Unbonding", "Returns when the period ends", "clock.arrow.circlepath",
                            Figures.balance(model.unbondingTotal) + " ERTH")
            }
        }
    }

    private func positionRow(_ title: String, _ subtitle: String, _ symbol: String, _ value: String) -> some View {
        HStack(spacing: 12) {
            Image(systemName: symbol)
                .font(.system(size: 14))
                .foregroundStyle(theme.colors.accentInk)
                .frame(width: 32, height: 32)
                .background(theme.colors.accentTint, in: .rect(cornerRadius: 12))
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(EarthType.body)
                    .foregroundStyle(theme.colors.textPrimary)
                Text(subtitle)
                    .font(EarthType.bodySmall)
                    .foregroundStyle(theme.colors.textTertiary)
                    .lineLimit(1)
            }
            Spacer(minLength: 8)
            Text(model.balancesVisible ? value : "••••")
                .font(EarthType.amount)
                .foregroundStyle(theme.colors.textPrimary)
        }
        .padding(.horizontal, 24)
        .padding(.vertical, 12)
    }
}

struct ActivityPlaceholder: View {
    @Environment(\.earth) private var theme

    var body: some View {
        HStack(spacing: 12) {
            Circle().fill(theme.colors.bgTertiary).frame(width: 44, height: 44)
            VStack(alignment: .leading, spacing: 6) {
                RoundedRectangle(cornerRadius: 4).fill(theme.colors.bgTertiary)
                    .frame(width: 96, height: 14)
                RoundedRectangle(cornerRadius: 4).fill(theme.colors.bgTertiary)
                    .frame(width: 140, height: 12)
            }
            Spacer()
        }
        .padding(.horizontal, 24)
        .padding(.vertical, 12)
    }
}
