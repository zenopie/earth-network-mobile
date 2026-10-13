import BigInt
import EarthCore
import SwiftUI

/// What the Stake screen or a validator's sheet asks the screen to open.
enum StakeAction: Identifiable, Equatable {
    case stake(validator: String?)
    /// nil: pick which validator first.
    case unstake(validator: String?)
    case move(from: String)
    /// One validator's sheet: its amount and Add / Move / Unstake.
    case validator(String)

    var id: String {
        switch self {
        case .stake(let v): return "stake:" + (v ?? "")
        case .unstake(let v): return "unstake:" + (v ?? "")
        case .move(let v): return "move:" + v
        case .validator(let v): return "validator:" + v
        }
    }
}

/// One validator's private stake as the Stake screen shows it, from
/// `StakeRound.lines`. A delegation bonds in its own block, so stake earns
/// from then on while its validator does.
struct StakeCard {
    let validator: String
    let holding: PrivacyWallet.StakeHolding?
    /// The stake here, at the live rate.
    let value: UInt64
    let standing: StakeRound.Standing?

    enum Status { case earning, idle }

    /// The row's dot.
    var status: Status {
        if let standing, !standing.earns { return .idle }
        return .earning
    }

    @MainActor
    static func all(_ model: AppModel) -> [StakeCard] {
        let list = PrivacyQueries.cachedValidators
        // Read so the rows redraw when a sync changes the notes.
        _ = model.privateStake
        return model.stakeLines().map { line in
            StakeCard(validator: line.validator, holding: model.stakeHoldings.first { $0.validator == line.validator },
                      value: line.value, standing: list?[line.validator].map(StakeRound.Standing.init))
        }
    }
}

/// Private stake: one big number, Stake and Unstake (and Check in when a
/// vintage is about to stop earning), then the validators it sits with,
/// retired stake to withdraw, and what is on its way back. Everything else
/// is a tap away in a validator's sheet.
struct StakeOverview: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    let round: StakeRoundModel
    let open: (StakeAction) -> Void
    /// Check in everything due (one confirmation), and withdraw one retired vintage.
    var checkIn: () -> Void = {}
    var withdraw: (String) -> Void = { _ in }

    var body: some View {
        // Re-drawn each minute so a payout date that passes updates.
        TimelineView(.periodic(from: .now, by: 60)) { context in
            let now = Int64(context.date.timeIntervalSince1970)
            let cards = StakeCard.all(model)
            let retired = model.stakeHoldings.flatMap(\.retired)
            let empty = cards.isEmpty && model.pendingUnbonds.isEmpty
            VStack(spacing: 0) {
                hero(cards)
                Spacer().frame(height: theme.space.x32)
                buttons(empty: empty)
                checkInButton(now: now)
                Spacer().frame(height: theme.space.x24)
                VStack(spacing: 0) {
                    // Notes not read yet: no rows is not "no stake".
                    if !model.has(.privacy) { ActivityPlaceholder().padding(.horizontal, -24) }
                    ForEach(cards, id: \.validator) { c in
                        StakeRow(validator: c.validator, value: amount(c.value), status: c.status,
                                 detail: c.holding.flatMap { Self.checkInLine($0, now: now) }) {
                            open(.validator(c.validator))
                        }
                    }
                    ForEach(retired, id: \.denom) { r in retiredRow(r) }
                    ForEach(model.pendingUnbonds, id: \.txHash) { unbondRow($0, now: now) }
                }
            }
        }
        .task { await round.load(rest: model.client.rest) }
    }

    // MARK: - hero

    private func hero(_ cards: [StakeCard]) -> some View {
        let total = cards.reduce(UInt64(0)) { PrivateMsgs.saturatingAdd($0, $1.value) }
        return VStack(spacing: 0) {
            if model.balancesVisible {
                SplitAmount(amount: Figures.when(model.stakeValuesLoaded, Figures.plain(BigInt(total))))
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
            } else {
                Text("-----")
                    .font(EarthType.header2).fontWeight(.semibold)
                    .foregroundStyle(theme.colors.textPrimary)
            }
            Text("ERTH")
                .font(EarthType.body)
                .foregroundStyle(theme.colors.textTertiary)
            if model.stakeValuesLoaded, model.has(.bonded), let line = heroLine(cards, total: total) {
                Text(line.text)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(line.color)
                    .padding(.top, theme.space.x12)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.top, theme.space.x24)
    }

    /// The hero's one line: what it earns a day and at what rate. The rate
    /// stays with balances hidden: it says nothing about what is held.
    ///
    /// Staked: the rate is weighted across its validators after each one's
    /// commission. Before any stake: what the network pays before
    /// commission, the most any validator can.
    private func heroLine(_ cards: [StakeCard], total: UInt64) -> (text: String, color: Color)? {
        if total == 0 {
            guard cards.isEmpty && model.pendingUnbonds.isEmpty else { return nil }
            guard let base = StakingApr.base(bondedUerth: bonded) else { return ("Earn by staking ERTH", theme.colors.textTertiary) }
            return ("Earn up to \(Figures.rate(base)) APR", theme.colors.accentInk)
        }
        let daily = perDay(cards)
        guard daily > 0 else { return ("Not earning", theme.colors.textTertiary) }
        let apr = "\(Figures.rate(daily * 365 / Double(total))) APR"
        guard model.balancesVisible else { return (apr, theme.colors.accentInk) }
        return ("+\(dailyText(daily)) ERTH / day · \(apr)", theme.colors.accentInk)
    }

    private var bonded: Int64 { Int64(model.totalBonded.description) ?? 0 }

    /// About what the stake adds in a day at the network rate, after each
    /// validator's commission.
    private func perDay(_ cards: [StakeCard]) -> Double {
        cards.reduce(0) { acc, c in
            guard c.standing?.earns ?? true,
                  let r = StakingApr.forValidator(bondedUerth: bonded, commission: model.commission(of: c.validator) ?? 0)
            else { return acc }
            return acc + Double(c.value) * r / 365
        }
    }

    private func dailyText(_ uerth: Double) -> String {
        let erth = uerth / 1_000_000
        if erth >= 100 { return Figures.grouped(Int64(erth.rounded())) }
        if erth >= 1 { return String(format: "%.1f", erth) }
        if erth >= 0.001 { return String(format: "%.3f", erth) }
        return "<0.001"
    }

    // MARK: - buttons and rows

    @ViewBuilder
    private func buttons(empty: Bool) -> some View {
        let leaving = model.stakeHoldings.filter { $0.maxLeave > 0 }
        HStack(spacing: theme.space.x12) {
            EarthPillButton(title: "Stake") { open(.stake(validator: nil)) }
                .disabled(model.privacy == nil)
            if !empty {
                // One validator: straight to it. Several: pick first.
                EarthPillButton(title: "Unstake", role: .secondary) {
                    open(.unstake(validator: leaving.count == 1 ? leaving[0].validator : nil))
                }
                .disabled(leaving.isEmpty)
            }
        }
    }

    /// One button for every check-in due: shown from 30 days before the
    /// oldest vintage holding stake stops earning. Nothing checks in by itself.
    @ViewBuilder
    private func checkInButton(now: Int64) -> some View {
        let by = model.checkInDueBy(now: now)
        if by > 0 {
            VStack(spacing: theme.space.x8) {
                EarthPillButton(title: "Check in") { checkIn() }
                    .disabled(model.privacy == nil)
                Text("Keeps your stake earning · due \(Self.date(by))")
                    .font(EarthType.bodySmall).fontWeight(.semibold)
                    .foregroundStyle(theme.colors.warnInk)
            }
            .padding(.top, theme.space.x16)
        }
    }

    /// A validator's line: when its oldest live vintage must check in by
    /// ("expiring" within 30 days). Nil before vintages.
    static func checkInLine(_ h: PrivacyWallet.StakeHolding, now: Int64) -> StakeRow.Detail? {
        guard let v = h.vintages.filter({ $0.derth > 0 && $0.retiresAt > 0 }).min(by: { $0.retiresAt < $1.retiresAt }) else { return nil }
        if v.dueSoon(now) { return .init(text: "Expiring · check in by \(date(v.retiresAt))", warn: true) }
        return .init(text: "Earning · check in by \(date(v.retiresAt))", warn: false)
    }

    /// "Oct 1": a vintage's dates fall on the 1st, 00:00 UTC.
    static func date(_ unix: Int64) -> String {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "UTC")!
        var style = Date.FormatStyle.dateTime.month(.abbreviated).day()
        style.timeZone = cal.timeZone
        return Date(timeIntervalSince1970: TimeInterval(unix)).formatted(style)
    }

    /// A retired vintage: not earning, its withdrawal value (Query/Vintages), Withdraw.
    private func retiredRow(_ r: PrivacyWallet.StakeVintage) -> some View {
        let q = model.retiredStake.first { $0.denom == r.denom }
        let validator = (try? PrivacyWallet.parseDerth(r.denom)) ?? ""
        return Button { withdraw(r.denom) } label: {
            HStack(spacing: theme.space.x12) {
                ValidatorMark(validator: validator, size: 44, muted: true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(model.moniker(of: validator))
                        .font(EarthType.body).fontWeight(.semibold)
                        .foregroundStyle(theme.colors.textPrimary)
                        .lineLimit(1)
                    Text("Not earning since \(Self.date(r.retiresAt)) · Withdraw")
                        .font(EarthType.bodySmall)
                        .foregroundStyle(theme.colors.accentInk)
                        .lineLimit(1)
                }
                Spacer(minLength: theme.space.x8)
                FigureText(model.balancesVisible ? Figures.when(q) { Figures.display(BigInt($0.value)) } : "••••")
                    .font(.system(size: 17, weight: .semibold).monospacedDigit())
                    .foregroundStyle(theme.colors.textTertiary)
                    .lineLimit(1)
            }
            .padding(.vertical, theme.space.x12)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .disabled(model.privacy == nil)
    }

    private func amount(_ uerth: UInt64) -> String {
        model.balancesVisible ? Figures.when(model.stakeValuesLoaded, Figures.display(BigInt(uerth))) : "••••"
    }

    /// An undelegation on its way back: the chain pays it, nothing to do.
    private func unbondRow(_ u: PendingUnbond, now: Int64) -> some View {
        let value = u.value ?? model.derthValue(u.derth, validator: u.validator)
        let when: String
        if let due = u.dueBy {
            when = due > now ? Date(timeIntervalSince1970: TimeInterval(due)).formatted(.dateTime.month(.abbreviated).day()) : "Soon"
        } else if let s = round.unbondingSeconds {
            when = "~\(max(1, s / 86_400)) days"
        } else {
            when = Figures.loading
        }
        return HStack(spacing: theme.space.x12) {
            Image(systemName: "clock")
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(theme.colors.textTertiary)
                .frame(width: 44, height: 44)
                .background(theme.colors.bgTertiary, in: .circle)
            FigureText("Unstaking · \(when)")
                .font(EarthType.body).fontWeight(.medium)
                .foregroundStyle(theme.colors.textSecondary)
                .lineLimit(1)
            Spacer(minLength: theme.space.x8)
            FigureText(amount(value))
                .font(.system(size: 17, weight: .semibold).monospacedDigit())
                .foregroundStyle(theme.colors.textTertiary)
                .lineLimit(1)
        }
        .padding(.vertical, theme.space.x12)
    }
}

/// A validator you are staked with: its initial, its name with a status dot,
/// the amount. Tapping opens its sheet.
struct StakeRow: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    let validator: String
    let value: String
    let status: StakeCard.Status
    /// The line under the name: when the stake checks in by.
    var detail: Detail? = nil
    let action: () -> Void

    struct Detail { let text: String; let warn: Bool }

    var body: some View {
        Button(action: action) {
            HStack(spacing: theme.space.x12) {
                ValidatorMark(validator: validator, size: 44, muted: status == .idle)
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Text(model.moniker(of: validator))
                            .font(EarthType.body).fontWeight(.semibold)
                            .foregroundStyle(theme.colors.textPrimary)
                            .lineLimit(1)
                        StakeDot(status: status)
                    }
                    if let detail {
                        Text(detail.text)
                            .font(EarthType.bodySmall)
                            .foregroundStyle(detail.warn ? theme.colors.warnInk : theme.colors.textTertiary)
                            .lineLimit(1)
                    }
                }
                Spacer(minLength: theme.space.x8)
                FigureText(value)
                    .font(.system(size: 17, weight: .semibold).monospacedDigit())
                    .foregroundStyle(theme.colors.textPrimary)
                    .lineLimit(1)
            }
            .padding(.vertical, theme.space.x12)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
    }
}

/// Green earning, grey not earning.
struct StakeDot: View {
    let status: StakeCard.Status
    var size: CGFloat = 8

    var body: some View {
        Circle()
            .fill(color)
            .frame(width: size, height: size)
            .accessibilityLabel(label)
    }

    private var color: Color {
        switch status {
        case .earning: return Palette.Brand.b500
        case .idle: return Palette.Gray.g400
        }
    }

    var label: String {
        switch status {
        case .earning: return "Earning"
        case .idle: return "Not earning"
        }
    }
}

/// A validator's initial on the accent: validators have no logos, and one
/// glyph repeated down a list identifies nothing.
struct ValidatorMark: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    let validator: String?
    var size: CGFloat = 44
    var muted = false

    var body: some View {
        Text(validator.map { String(model.moniker(of: $0).prefix(1)).uppercased() } ?? "?")
            .font(.system(size: size * 0.4, weight: .bold))
            .foregroundStyle(muted ? theme.colors.textTertiary : theme.colors.accentInk)
            .frame(width: size, height: size)
            .background(muted ? theme.colors.bgTertiary : theme.colors.accentTint, in: .circle)
            .accessibilityHidden(true)
    }
}

/// One validator: the big amount and Add / Move / Unstake. A window still
/// closed, or notes to merge get one short line each, and only when they apply.
struct ValidatorStakeSheet: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    let validator: String
    let round: StakeRoundModel
    let open: (StakeAction) -> Void
    let merge: (String) -> Void
    var withdraw: (String) -> Void = { _ in }

    var body: some View {
        let card = StakeCard.all(model).first { $0.validator == validator }
        let holding = model.stakeHoldings.first { $0.validator == validator }
        // One undelegation or move spends one vintage.
        let free = card?.holding?.maxLeave ?? 0
        let now = Int64(Date().timeIntervalSince1970)
        VStack(spacing: 0) {
            Spacer().frame(height: theme.space.x32)
            ValidatorMark(validator: validator, size: 56, muted: card?.status == .idle)
            Spacer().frame(height: theme.space.x12)
            Text(model.moniker(of: validator))
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(theme.colors.textPrimary)
                .lineLimit(1)
            if let card {
                HStack(spacing: 6) {
                    StakeDot(status: card.status)
                    FigureText([statusWord(card), apr(card)].compactMap { $0 }.joined(separator: " · "))
                        .font(EarthType.bodySmall)
                        .foregroundStyle(theme.colors.textTertiary)
                }
                .padding(.top, theme.space.x4)
            }

            Spacer().frame(height: theme.space.x24)
            if model.balancesVisible {
                SplitAmount(amount: Figures.when(model.stakeValuesLoaded, Figures.plain(BigInt(card?.value ?? 0))))
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
            } else {
                Text("-----").font(EarthType.header2).fontWeight(.semibold)
                    .foregroundStyle(theme.colors.textPrimary)
            }
            Text("ERTH")
                .font(EarthType.body)
                .foregroundStyle(theme.colors.textTertiary)

            VStack(spacing: theme.space.x4) {
                if let h = card?.holding, h.locked > 0, free == 0 {
                    shortLine(h.lockedUntil.map { "Can move \(StakeRoundModel.day(Int64(clamping: $0)))" } ?? "Recently moved here")
                }
                if let h = card?.holding, h.notes > 1, h.mergeable {
                    Button { merge(validator) } label: {
                        Text("Merge \(h.notes) notes")
                            .font(EarthType.bodySmall).fontWeight(.semibold)
                            .foregroundStyle(theme.colors.accentInk)
                    }
                    .buttonStyle(.plain)
                }
                // Each vintage held: earning until it checks in, or retired and waiting to be withdrawn.
                ForEach(holding?.vintages.filter { $0.derth > 0 && $0.retiresAt > 0 } ?? [], id: \.denom) { v in
                    Text("\(v.dueSoon(now) ? "Expiring" : "Earning") · check in by \(StakeOverview.date(v.retiresAt))")
                        .font(EarthType.bodySmall)
                        .foregroundStyle(v.dueSoon(now) ? theme.colors.warnInk : theme.colors.textTertiary)
                }
                ForEach(holding?.retired ?? [], id: \.denom) { r in
                    Button { withdraw(r.denom) } label: {
                        Text("Not earning since \(StakeOverview.date(r.retiresAt)) · Withdraw")
                            .font(EarthType.bodySmall).fontWeight(.semibold)
                            .foregroundStyle(theme.colors.accentInk)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.top, theme.space.x12)

            Spacer().frame(height: theme.space.x32)
            HStack(spacing: theme.space.x8) {
                EarthPillButton(title: "Add") { open(.stake(validator: validator)) }
                    .disabled(!model.stakeTargets.contains(validator))
                EarthPillButton(title: "Move", role: .secondary) { open(.move(from: validator)) }
                    .disabled(free == 0)
                EarthPillButton(title: "Unstake", role: .secondary) { open(.unstake(validator: validator)) }
                    .disabled(free == 0)
            }
            Spacer(minLength: theme.space.x16)
        }
        .padding(.horizontal, theme.space.gutter)
        .frame(maxWidth: .infinity)
        .background(theme.colors.bgPrimary)
        .presentationDetents([.height(420 + 24 * CGFloat((holding?.vintages.count ?? 0) + (holding?.retired.count ?? 0)))])
        .presentationDragIndicator(.visible)
    }

    /// This validator's rate after its commission, while stake here earns.
    private func apr(_ c: StakeCard) -> String? {
        guard c.status == .earning else { return nil }
        guard model.has(.bonded) else { return Figures.loading }
        return StakingApr.forValidator(bondedUerth: Int64(model.totalBonded.description) ?? 0,
                                       commission: model.commission(of: c.validator) ?? 0)
            .map { "\(Figures.rate($0)) APR" }
    }

    private func statusWord(_ c: StakeCard) -> String {
        switch c.status {
        case .earning: return "Earning"
        case .idle: return c.standing?.label ?? "Not earning"
        }
    }

    private func shortLine(_ text: String) -> some View {
        Text(text)
            .font(EarthType.bodySmall)
            .foregroundStyle(theme.colors.textTertiary)
    }
}

extension StakeRound.Standing {
    /// In the active set, only closed to new stake: it still earns.
    var isClosed: Bool { if case .closed = self { return true }; return false }
    /// Whether stake here earns now.
    var earns: Bool { self == .active || isClosed }
}
