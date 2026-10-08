import BigInt
import EarthCore
import SwiftUI

/// What a stake card or the summary asks the screen to open.
enum StakeAction: Identifiable, Equatable {
    case stake(validator: String?)
    case unstake(validator: String)
    case move(from: String)

    var id: String {
        switch self {
        case .stake(let v): return "stake:" + (v ?? "")
        case .unstake(let v): return "unstake:" + v
        case .move(let v): return "move:" + v
        }
    }
}

/// Private stake at a glance: what it is worth and earning (and, only while
/// some of it is still waiting to join its validator, how much and when), then
/// a card per validator with where its stake stands and what can be done with
/// it, then what is on its way back.
///
/// Private stake is stake notes and Groundworks positions alike: a position
/// is stake locked at its validator, earning the same. A validator operator's
/// public self-bond is not shown here; positions are managed in Govern.
struct StakeOverview: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    let round: StakeRoundModel
    let open: (StakeAction) -> Void
    let merge: (String) -> Void

    var body: some View {
        // Re-drawn each minute so the countdowns move.
        TimelineView(.periodic(from: .now, by: 60)) { context in
            let now = Int64(context.date.timeIntervalSince1970)
            let cards = self.cards
            VStack(alignment: .leading, spacing: 0) {
                summary(cards, now: now)
                Spacer().frame(height: theme.space.x16)
                EarthButton(title: "Stake") { open(.stake(validator: nil)) }
                    .disabled(model.privacy == nil)

                Spacer().frame(height: theme.space.x24)
                sectionTitle("Your stake")
                if cards.isEmpty && model.pendingUnbonds.isEmpty {
                    EarthEmpty(systemName: "leaf", title: "Nothing staked yet",
                               detail: "Stake private ERTH with a validator. Rewards compound into it, and no one can see it's yours.")
                }
                VStack(spacing: theme.space.x12) {
                    ForEach(cards, id: \.validator) { card($0, now: now) }
                    ForEach(model.pendingUnbonds, id: \.txHash) { unbondCard($0, now: now) }
                }
            }
        }
        .task { await round.load(rest: model.client.rest) }
    }

    // MARK: - figures

    struct Card {
        let validator: String
        let holding: PrivacyWallet.StakeHolding?
        /// Notes and positions here, at the live rate (`StakeRound.lines`).
        let value: UInt64
        /// The part locked in Groundworks positions, in ERTH.
        let lockedValue: UInt64
        /// The part still waiting to join its validator (in ERTH), nil when unknown.
        let joiningValue: UInt64?
        let standing: StakeRound.Standing?

        /// What earns now: all of it but what is waiting to join.
        var earningValue: UInt64 { value - min(value, joiningValue ?? 0) }
    }

    private var cards: [Card] {
        let list = PrivacyQueries.cachedValidators
        // Read so the cards redraw when a sync changes the notes or positions.
        _ = (model.privateStake, model.positions)
        return model.stakeLines(after: round.startHeight).map { line in
            Card(validator: line.validator, holding: model.stakeHoldings.first { $0.validator == line.validator },
                 value: line.value, lockedValue: line.lockedValue, joiningValue: line.joiningValue,
                 standing: list?[line.validator].map(StakeRound.Standing.init))
        }
    }

    private var bonded: Int64 { Int64(model.totalBonded.description) ?? 0 }

    /// The validator's rate after its commission (nil: unknown, or it earns nothing now).
    private func apr(_ op: String, _ standing: StakeRound.Standing?) -> Double? {
        if let standing, !standing.earns { return nil }
        return StakingApr.forValidator(bondedUerth: bonded, commission: model.commission(of: op) ?? 0)
    }

    /// About what the stake adds in a day at the network rate, after commission.
    /// Stake still waiting to join earns nothing yet (rewards accrue only on
    /// what is delegated), so it is left out.
    private func perDay(_ cards: [Card]) -> Double {
        cards.reduce(0) { acc, c in acc + Double(c.earningValue) * (apr(c.validator, c.standing) ?? 0) / 365 }
    }

    /// "6:00 AM" when the round's end is known.
    private var joinsAt: String? { round.epoch.map { StakeRoundModel.clock($0.endTime) } }

    private func amount(_ uerth: UInt64) -> String {
        model.balancesVisible ? Figures.balance(BigInt(uerth)) : "••••"
    }

    // MARK: - summary

    private func summary(_ cards: [Card], now: Int64) -> some View {
        let total = cards.reduce(UInt64(0)) { PrivateMsgs.saturatingAdd($0, $1.value) }
        let daily = perDay(cards)
        let waiting = cards.reduce(UInt64(0)) { PrivateMsgs.saturatingAdd($0, $1.joiningValue ?? 0) }
        let line: String
        if total == 0 { line = "Rewards compound into it. Nothing to claim." }
        else if daily > 0 { line = "Earning about \(dailyText(daily)) ERTH a day" }
        else if waiting >= total { line = joinsAt.map { "Starts earning at \($0)" } ?? "Starts earning when it joins its validator" }
        else { line = "Not earning now: see below" }
        return VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 6) {
                Image(systemName: "lock.fill").font(.system(size: 12))
                    .foregroundStyle(theme.colors.accentInk)
                Text("Private stake")
                    .font(EarthType.bodySmall).fontWeight(.semibold)
                    .foregroundStyle(theme.colors.textSecondary)
            }
            Text("\(model.balancesVisible ? Figures.display(BigInt(total)) : "••••") ERTH")
                .font(EarthType.headline)
                .foregroundStyle(theme.colors.textPrimary)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
                .padding(.top, theme.space.x4)
            Text(line)
                .font(EarthType.bodySmall)
                .foregroundStyle(total > 0 && daily > 0 ? theme.colors.accentInk : theme.colors.textSecondary)
                .padding(.top, theme.space.x2)

            Spacer().frame(height: theme.space.x12)
            EarthDivider()
            Spacer().frame(height: theme.space.x12)

            if waiting > 0 {
                // Only while something waits: nothing about rounds otherwise.
                let many = cards.filter { ($0.joiningValue ?? 0) > 0 }.count > 1
                let joins = many ? "joins its validators" : "joins its validator"
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Image(systemName: "clock").font(.system(size: 12))
                        .foregroundStyle(theme.colors.warnInk)
                    Text("\(amount(waiting)) ERTH waiting to start earning: \(joins) \(joinsAt.map { "at " + $0 } ?? "when today's round ends")")
                        .font(EarthType.bodySmall)
                        .foregroundStyle(theme.colors.textPrimary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer().frame(height: theme.space.x12)
            }
            if let rate = StakingApr.base(bondedUerth: bonded) {
                // Not a policy the chain aims at: a fixed stream divided by
                // however much stake is competing for it.
                infoRow("Network rate", Figures.rate(rate) + " a year",
                        "1 ERTH a second shared across \(Figures.whole(model.totalBonded)) ERTH staked, before commission.")
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(theme.space.x16)
        .background(theme.colors.accentTint, in: .rect(cornerRadius: 20))
    }

    private func dailyText(_ uerth: Double) -> String {
        guard model.balancesVisible else { return "••••" }
        let erth = uerth / 1_000_000
        if erth >= 100 { return String(format: "%.0f", erth) }
        if erth >= 1 { return String(format: "%.2f", erth) }
        if erth >= 0.001 { return String(format: "%.3f", erth) }
        return "<0.001"
    }

    private func infoRow(_ title: String, _ value: String, _ detail: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(alignment: .firstTextBaseline) {
                Text(title)
                    .font(EarthType.bodySmall)
                    .foregroundStyle(theme.colors.textSecondary)
                Spacer(minLength: theme.space.x8)
                Text(value)
                    .font(EarthType.bodySmall).fontWeight(.semibold)
                    .foregroundStyle(theme.colors.accentInk)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
            Text(detail)
                .font(EarthType.caption)
                .foregroundStyle(theme.colors.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private func sectionTitle(_ text: String) -> some View {
        Text(text)
            .font(EarthType.bodySmall).fontWeight(.semibold)
            .foregroundStyle(theme.colors.textSecondary)
            .padding(.bottom, theme.space.x8)
    }

    // MARK: - cards

    /// A validator's initial on the accent: validators have no logos, and one
    /// glyph repeated down a list identifies nothing.
    private func mark(_ op: String, muted: Bool = false) -> some View {
        Text(String(model.moniker(of: op).prefix(1)).uppercased())
            .font(.system(size: 16, weight: .semibold))
            .foregroundStyle(muted ? theme.colors.textTertiary : theme.colors.accentInk)
            .frame(width: 40, height: 40)
            .background(muted ? theme.colors.bgTertiary : theme.colors.accentTint, in: .circle)
            .accessibilityHidden(true)
    }

    private func header(_ op: String, _ subtitle: String, value: UInt64, muted: Bool = false) -> some View {
        HStack(spacing: 12) {
            mark(op, muted: muted)
            VStack(alignment: .leading, spacing: 2) {
                Text(model.moniker(of: op))
                    .font(EarthType.title)
                    .foregroundStyle(theme.colors.textPrimary)
                    .lineLimit(1)
                Text(subtitle)
                    .font(EarthType.caption)
                    .foregroundStyle(theme.colors.textTertiary)
                    .lineLimit(1)
            }
            .layoutPriority(1)
            Spacer(minLength: 8)
            VStack(alignment: .trailing, spacing: 2) {
                HStack(spacing: 5) {
                    Text(amount(value))
                        .font(EarthType.amount)
                        .foregroundStyle(theme.colors.textPrimary)
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                    Image(systemName: "lock.fill")
                        .font(.system(size: 10))
                        .foregroundStyle(theme.colors.accentInk)
                }
                Text("ERTH")
                    .font(EarthType.caption)
                    .foregroundStyle(theme.colors.textTertiary)
            }
        }
    }

    private func commissionLine(_ op: String, _ standing: StakeRound.Standing?) -> String {
        let c = String(format: "%.0f%% commission", (model.commission(of: op) ?? 0) * 100)
        guard let r = apr(op, standing) else { return c }
        return c + " · " + Figures.rate(r) + " APR"
    }

    /// The card's standing: a pill and one sentence on what it means.
    private func status(_ c: Card, now: Int64) -> [(EarthStatus, String, String)] {
        var out: [(EarthStatus, String, String)] = []
        let name = model.moniker(of: c.validator)
        let ends = round.epoch.map { "at \(StakeRoundModel.clock($0.endTime)) (in \(StakeRound.countdown($0.endTime - now)))" }
            ?? "when today's round ends"
        if let s = c.standing, !s.earns, let reason = s.reason {
            out.append((.failed, "Not earning", reason + " Move it to an active validator to earn again."))
        } else if let j = c.joiningValue, j > 0, j >= c.value {
            out.append((.pending, joinsAt.map { "Starts earning at \($0)" } ?? "Waiting to join",
                        "Waiting to start earning. It joins \(name) \(ends); from then rewards compound into it, nothing to claim."))
        } else {
            let more = (c.joiningValue ?? 0) > 0 ? " \(amount(c.joiningValue!)) ERTH more is waiting to start earning: it joins \(ends)." : ""
            out.append((.success, "Earning", "Grows with every block: rewards compound into it, nothing to claim." + more))
        }
        if c.lockedValue > 0 {
            out.append((.neutral, "In Groundworks",
                        "\(amount(c.lockedValue)) ERTH is locked in a Groundworks position: still staked here and earning the same. Unlock it in Govern to move or unstake it."))
        }
        if let h = c.holding, h.locked > 0 {
            let until = h.lockedUntil.map { "after " + StakeRoundModel.day(Int64(clamping: $0)) } ?? "once its window closes"
            out.append((.neutral, "Moving",
                        "\(amount(model.derthValue(h.locked, validator: c.validator))) ERTH moved here. It earns here now, and can move or unstake \(until)."))
        }
        return out
    }

    private func card(_ c: Card, now: Int64) -> some View {
        let free = c.holding?.free ?? 0
        let canAdd = model.stakeTargets.contains(c.validator)
        return VStack(alignment: .leading, spacing: theme.space.x12) {
            header(c.validator, commissionLine(c.validator, c.standing), value: c.value)
            ForEach(Array(status(c, now: now).enumerated()), id: \.offset) { _, s in
                VStack(alignment: .leading, spacing: 6) {
                    EarthStatusPill(status: s.0, text: s.1)
                    Text(s.2)
                        .font(EarthType.bodySmall)
                        .foregroundStyle(theme.colors.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            if let h = c.holding, h.notes > 1 {
                Button { merge(c.validator) } label: {
                    Text(h.mergeable ? "Held as \(h.notes) notes · merge them" : "Held as \(h.notes) notes")
                        .font(EarthType.caption)
                        .foregroundStyle(h.mergeable ? theme.colors.accentInk : theme.colors.textTertiary)
                }
                .buttonStyle(.plain)
                .disabled(!h.mergeable)
            }
            HStack(spacing: theme.space.x8) {
                cardButton("Add", "plus", enabled: canAdd) { open(.stake(validator: c.validator)) }
                cardButton("Move", "arrow.left.arrow.right", enabled: free > 0) { open(.move(from: c.validator)) }
                cardButton("Unstake", "arrow.uturn.backward", enabled: free > 0) { open(.unstake(validator: c.validator)) }
            }
        }
        .padding(theme.space.x16)
        .background(theme.colors.bgPrimary, in: .rect(cornerRadius: theme.space.radiusLg))
        .overlay {
            RoundedRectangle(cornerRadius: theme.space.radiusLg)
                .strokeBorder(theme.colors.strokePrimary, lineWidth: theme.space.stroke)
        }
    }

    private func cardButton(_ title: String, _ symbol: String, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 6) {
                Image(systemName: symbol).font(.system(size: 12, weight: .semibold))
                Text(title).font(EarthType.bodySmall).fontWeight(.semibold)
            }
            .foregroundStyle(enabled ? theme.colors.secondaryButtonFg : theme.colors.buttonDisabledFg)
            .frame(maxWidth: .infinity, minHeight: 40)
            .background(enabled ? theme.colors.secondaryButtonBg : theme.colors.buttonDisabledBg,
                        in: .rect(cornerRadius: theme.space.radiusMd))
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }

    /// An undelegation on its way back: the chain pays it, nothing to do.
    private func unbondCard(_ u: PendingUnbond, now: Int64) -> some View {
        let value = u.value ?? model.derthValue(u.derth, validator: u.validator)
        let when: String
        if let due = u.dueBy {
            when = due > now ? "around \(StakeRoundModel.day(due))" : "any time now"
        } else if let s = round.unbondingSeconds {
            when = "about \(max(1, s / 86_400)) days after the round it was asked in ends"
        } else {
            when = "once the unbonding period ends"
        }
        return VStack(alignment: .leading, spacing: theme.space.x12) {
            header(u.validator, "Unstaked", value: value, muted: true)
            VStack(alignment: .leading, spacing: 6) {
                EarthStatusPill(status: .pending, text: "Unstaking")
                Text("Not earning. It's paid to your private ERTH automatically \(when). Nothing to claim.")
                    .font(EarthType.bodySmall)
                    .foregroundStyle(theme.colors.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(theme.space.x16)
        .background(theme.colors.bgPrimary, in: .rect(cornerRadius: theme.space.radiusLg))
        .overlay {
            RoundedRectangle(cornerRadius: theme.space.radiusLg)
                .strokeBorder(theme.colors.strokePrimary, lineWidth: theme.space.stroke)
        }
    }
}

extension StakeRound.Standing {
    /// In the active set, only closed to new stake: it still earns.
    var isClosed: Bool { if case .closed = self { return true }; return false }
    /// Whether stake here earns now.
    var earns: Bool { self == .active || isClosed }
}
