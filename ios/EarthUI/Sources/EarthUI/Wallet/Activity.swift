import EarthCore
import SwiftUI

/// The activity list: day headers, then one quiet row per tx (its mark, a
/// short title, who or when, the amount). Everything else is in the row's
/// detail sheet. The data is `AppModel.activity` (`ActivityEntry`).
struct ActivityList: View {
    @Environment(\.earth) private var theme
    let entries: [ActivityEntry]
    var limit: Int?
    let open: (ActivityEntry) -> Void

    var body: some View {
        if entries.isEmpty {
            Text("No activity yet")
                .font(EarthType.bodySmall)
                .foregroundStyle(theme.colors.textTertiary)
                .frame(maxWidth: .infinity)
                .padding(.vertical, theme.space.x32)
        } else {
            // Re-drawn each minute so "Today" turns into "Yesterday" at midnight.
            TimelineView(.periodic(from: .now, by: 60)) { context in
                let shown = limit.map { Array(entries.prefix($0)) } ?? entries
                ForEach(ActivityEntry.days(shown, now: Int64(context.date.timeIntervalSince1970)), id: \.title) { day in
                    Text(day.title)
                        .font(EarthType.bodySmall).fontWeight(.semibold)
                        .foregroundStyle(theme.colors.textTertiary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 24)
                        .padding(.top, theme.space.x16)
                        .padding(.bottom, theme.space.x4)
                    ForEach(day.entries) { e in ActivityItem(entry: e) { open(e) } }
                }
            }
        }
    }
}

/// One tx: mark, title (a tiny lock when private), the counterparty or the
/// time, and the amount. Pending and failed say so in place of the time.
struct ActivityItem: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    let entry: ActivityEntry
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: theme.space.x12) {
                ActivityMark(entry: entry, size: 44)
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 5) {
                        Text(entry.title)
                            .font(EarthType.body).fontWeight(.semibold)
                            .foregroundStyle(theme.colors.textPrimary)
                            .lineLimit(1)
                        if entry.isPrivate {
                            Image(systemName: "lock.fill")
                                .font(.system(size: 10))
                                .foregroundStyle(theme.colors.textTertiary)
                                .accessibilityLabel("Private")
                        }
                    }
                    second
                }
                .layoutPriority(1)
                Spacer(minLength: theme.space.x8)
                if let c = entry.primary {
                    ActivityAmount(coin: c, status: entry.status, size: 17, visible: model.balancesVisible)
                }
            }
            .padding(.horizontal, 24)
            .padding(.vertical, theme.space.x12)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder private var second: some View {
        switch entry.status {
        case .pending:
            Text("Pending").font(EarthType.bodySmall).foregroundStyle(theme.colors.warnInk)
        case .failed:
            Text("Failed").font(EarthType.bodySmall).foregroundStyle(theme.colors.textError)
        case .completed:
            Text(entry.listParty(name: model.moniker(of:)) ?? ActivityEntry.clock(entry.time, exact: entry.timeExact))
                .font(EarthType.bodySmall)
                .foregroundStyle(theme.colors.textTertiary)
                .lineLimit(1)
        }
    }
}

/// "+3.25 ANML": the figure in weight, the symbol small. Green when it came
/// in; grey while pending or when it failed (nothing moved).
struct ActivityAmount: View {
    @Environment(\.earth) private var theme
    let coin: ActivityCoin
    let status: ActivityEntry.Status
    let size: CGFloat
    let visible: Bool

    var body: some View {
        let ink = status != .completed ? theme.colors.textTertiary : (coin.amount > 0 ? theme.colors.accentInk : theme.colors.textPrimary)
        HStack(alignment: .firstTextBaseline, spacing: size > 30 ? 6 : 3) {
            Text(visible ? (coin.amount > 0 ? "+" : "−") + ActivityEntry.figure(coin.amount) : "••••")
                .font(.system(size: size, weight: .semibold).monospacedDigit())
                .foregroundStyle(ink)
                .lineLimit(1)
                .minimumScaleFactor(0.5)
            Text(PrivateActivity.symbol(coin.denom))
                .font(.system(size: size > 30 ? 17 : 12, weight: .medium))
                .foregroundStyle(theme.colors.textTertiary)
        }
        .accessibilityElement(children: .combine)
    }
}

/// The coin's logo when the row is about moving a coin, else a glyph for what was done.
struct ActivityMark: View {
    @Environment(\.earth) private var theme
    let entry: ActivityEntry
    var size: CGFloat = 44

    var body: some View {
        if entry.showsCoin, let c = entry.primary {
            CoinMark(token: Token.named(c.denom) ?? Token.unknown(denom: c.denom), size: size)
                .opacity(entry.status == .failed ? 0.5 : 1)
        } else {
            Image(systemName: symbol)
                .font(.system(size: size * 0.36, weight: .semibold))
                .foregroundStyle(entry.status == .failed ? theme.colors.textTertiary : theme.colors.accentInk)
                .frame(width: size, height: size)
                .background(entry.status == .failed ? theme.colors.bgTertiary : theme.colors.accentTint, in: .circle)
                .accessibilityHidden(true)
        }
    }

    private var symbol: String {
        switch entry.glyph {
        case .send: "arrow.up"
        case .receive: "arrow.down"
        case .swap: "arrow.left.arrow.right"
        case .stake: "chart.line.uptrend.xyaxis"
        case .register: "person.crop.circle.badge.checkmark"
        case .vote: "checkmark"
        case .handle: "at"
        case .other: "ellipsis"
        }
    }
}

/// A tx in full: the big amount, the title, its status, then every detail
/// the wallet knows as label and value. A public tx can be opened in the
/// explorer; a private one cannot (opening it would tell the explorer which
/// tx is this wallet's, the lookup the list never makes), but its hash can
/// still be copied.
struct ActivityDetailSheet: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    @Environment(\.openURL) private var openURL
    let entry: ActivityEntry
    @State private var copied: String?

    /// Live from the model, so a pending tx that lands while open turns completed.
    private var live: ActivityEntry { model.activity?.first { $0.id == entry.id } ?? entry }

    static let explorer = "https://explorer.erth.network/tx/"

    var body: some View {
        let e = live
        ScrollView {
            VStack(spacing: 0) {
                Spacer().frame(height: theme.space.x32)
                ActivityMark(entry: e, size: 56)
                Spacer().frame(height: theme.space.x16)
                if let c = e.primary {
                    ActivityAmount(coin: c, status: e.status, size: 44, visible: model.balancesVisible)
                    Spacer().frame(height: theme.space.x8)
                }
                HStack(spacing: 6) {
                    Text(e.title)
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(theme.colors.textPrimary)
                    if e.isPrivate {
                        Image(systemName: "lock.fill")
                            .font(.system(size: 12))
                            .foregroundStyle(theme.colors.textTertiary)
                            .accessibilityLabel("Private")
                    }
                }
                Spacer().frame(height: theme.space.x12)
                pill(e)

                Spacer().frame(height: theme.space.x24)
                VStack(spacing: 0) {
                    let lines = e.details(name: model.moniker(of:))
                    ForEach(Array(lines.enumerated()), id: \.offset) { i, d in
                        line(d)
                        if i < lines.count - 1 { EarthDivider().padding(.leading, theme.space.x16) }
                    }
                }
                .background(theme.colors.bgSecondary, in: .rect(cornerRadius: 20))

                if !e.isPrivate, let hash = e.hash {
                    Spacer().frame(height: theme.space.x24)
                    EarthPillButton(title: "View in explorer", role: .secondary) {
                        URL(string: Self.explorer + hash).map { openURL($0) }
                    }
                }
                Spacer().frame(height: theme.space.x32)
            }
            .padding(.horizontal, theme.space.gutter)
        }
        .background(theme.colors.bgPrimary)
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }

    private func pill(_ e: ActivityEntry) -> some View {
        switch e.status {
        case .completed: EarthStatusPill(status: .success, text: "Completed")
        case .pending: EarthStatusPill(status: .pending, text: "Pending")
        case .failed: EarthStatusPill(status: .failed, text: "Failed · " + (e.shortFailure ?? "Refused by the chain"))
        }
    }

    private static let amounts: Set<String> = ["Sent", "Received", "You paid", "You got"]

    @ViewBuilder
    private func line(_ d: ActivityEntry.Detail) -> some View {
        let value = !model.balancesVisible && Self.amounts.contains(d.label) ? "••••" : d.value
        let row = HStack(alignment: .firstTextBaseline, spacing: theme.space.x12) {
            Text(d.label)
                .font(EarthType.body)
                .foregroundStyle(theme.colors.textTertiary)
            Spacer(minLength: theme.space.x12)
            Text(copied == d.label ? "Copied" : value)
                .font(d.label == "Error" ? EarthType.bodySmall : EarthType.amount)
                .foregroundStyle(copied == d.label ? theme.colors.accentInk : theme.colors.textPrimary)
                .multilineTextAlignment(.trailing)
                .lineLimit(d.label == "Error" ? 4 : nil)
            if d.copy != nil {
                Image(systemName: "doc.on.doc")
                    .font(.system(size: 12))
                    .foregroundStyle(theme.colors.textTertiary)
            }
        }
        .padding(.horizontal, theme.space.x16)
        .padding(.vertical, 14)
        .contentShape(.rect)
        if let copy = d.copy {
            Button {
                Clipboard.copy(copy)
                copied = d.label
                Task { try? await Task.sleep(nanoseconds: 1_500_000_000); if copied == d.label { copied = nil } }
            } label: { row }
            .buttonStyle(.plain)
            .accessibilityHint("Copies it")
        } else {
            row
        }
    }
}
