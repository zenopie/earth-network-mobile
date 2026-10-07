import Foundation

/// What the wallet reminds its owner to do, in place of doing it unasked:
/// the day's ANML claim, the caretaker vote's refresh before it lapses, each
/// Groundworks position's split before its lease ends, and the handle's
/// renewal before (and through) its renewal period. Each costs a fee, so each
/// is the owner's decision; nothing here broadcasts anything.
/// Ports `privacy/Reminders.kt`; `due` is pure (unit-tested).
public enum Reminders {
    /// How long before a caretaker vote or handle lapses the reminder starts.
    public static let leadSeconds: Int64 = Handles.reminderLeadSeconds
    /// How long after a caretaker vote or a position's split lapsed the reminder to cast it again stays.
    public static let lapsedSeconds: Int64 = 30 * 86400

    public enum Reminder: Equatable, Sendable {
        /// Today's ANML can be claimed.
        case anmlReady
        /// The caretaker vote lapses at `expiresAt` (lapsed when in the past): cast it again to keep it counted.
        case caretakerExpiring(expiresAt: Int64, lapsed: Bool)
        /// Position `positionID`'s Groundworks split lapses at `expiresAt` (`lapsed`: it has): renew or re-cast it.
        case groundworksExpiring(positionID: UInt64, expiresAt: Int64, lapsed: Bool)
        /// The handle's lease ends at `expiresAt`; until `renewalUntil` only its owner may renew it (`inRenewal`: it no longer resolves).
        case handleExpiring(handle: String, expiresAt: Int64, renewalUntil: Int64, inRenewal: Bool)
        /// The live handle this identity holds pays another address (a handle moved here keeps the old
        /// wallet's): renewing it from this wallet points it here.
        case handlePaysElsewhere(handle: String)
        /// After a switch: the time the wallet suggested for bringing the predecessor's handle and
        /// caretaker vote over (`at`) has come. `deadline` (0: unknown) is the earliest lease end of
        /// what is to move: past it the old identity can neither move nor renew it, so the reminder
        /// grows urgent as it nears. The move itself is the user's to make.
        case moveSuggested(at: Int64, deadline: Int64 = 0)
        /// The registration ends at `registrationEndsAt` and this identity's handle (`handle`, ""
        /// none) or caretaker vote ends soon after (`handleExpiresAt`, `voteExpiresAt`; 0 when not
        /// at stake): once the registration lapses this identity can renew neither, and after a
        /// renewal they can be moved only until their leases end. Renewing them now gives that
        /// move a full lease.
        case renewBeforeLapse(registrationEndsAt: Int64, handle: String, handleExpiresAt: Int64, voteExpiresAt: Int64)
    }

    /// One of this wallet's positions as its Groundworks split's lease stands, from the wallet's own
    /// position read and what it last saw (PrivacyWallet.groundworksLeases).
    public struct GroundworksLease: Equatable, Sendable {
        public let positionID: UInt64
        /// When the split stops (or stopped) counting; 0 unknown (a node before leases, a lapse never seen).
        public let expiresAt: Int64
        /// The chain still holds the split (it clears it at the lapse).
        public let held: Bool
        /// The split a renewal casts again: the chain's, else the last one seen (empty: unknown).
        public let split: [UInt64: UInt64]
        public init(positionID: UInt64, expiresAt: Int64, held: Bool, split: [UInt64: UInt64]) {
            self.positionID = positionID; self.expiresAt = expiresAt; self.held = held; self.split = split
        }
        /// The split no longer counts: cleared, or past its lease end and not yet cleared.
        public func lapsed(_ now: Int64) -> Bool { !held || (expiresAt > 0 && expiresAt <= now) }
        /// The reminder window before the lease end has opened, and the split still counts.
        public func renewalDue(_ now: Int64) -> Bool { !lapsed(now) && expiresAt > 0 && now >= Handles.satSub(expiresAt, leadSeconds) }
    }

    public struct Inputs: Sendable {
        public var now: Int64
        public var identityLive: Bool
        /// PrivacyWallet.claimOpensAt: 0 for now, nil without a live registration.
        public var claimOpensAt: Int64?
        public var claimedToday: Bool
        /// When the caretaker vote lapses (0: none cast).
        public var caretakerExpiresAt: Int64
        /// This identity's handle ("" for none) and its directory entry (nil: not found).
        public var handle: String
        public var handleEntry: HandleEntry?
        /// Directory entries naming this wallet's own address (a handle held but not in
        /// the store, after a restore): reminded like the held one.
        public var addressed: [HandleEntry]
        /// This wallet's shielded address ("" unknown): a held handle paying another is pointed out.
        public var ownAddress: String
        /// This wallet's Groundworks positions' leases (stake, so not tied to a live identity).
        public var groundworks: [GroundworksLease]
        /// PrivacyWallet.moveSuggestionDue: the suggested move time once it has come (0: none).
        public var moveSuggestedAt: Int64
        /// PrivacyWallet.moveDeadline: when what is to move stops being movable (0: unknown).
        public var moveDeadline: Int64
        /// When the live registration ends: its activated_at plus registration_validity_seconds (0: unknown).
        public var registrationEndsAt: Int64
        /// The held handle's lease end (0: unknown).
        public var handleExpiresAt: Int64

        public init(now: Int64, identityLive: Bool, claimOpensAt: Int64?, claimedToday: Bool, caretakerExpiresAt: Int64,
                    handle: String, handleEntry: HandleEntry?, addressed: [HandleEntry] = [], ownAddress: String = "",
                    groundworks: [GroundworksLease] = [], moveSuggestedAt: Int64 = 0, moveDeadline: Int64 = 0,
                    registrationEndsAt: Int64 = 0, handleExpiresAt: Int64 = 0) {
            self.now = now; self.identityLive = identityLive; self.claimOpensAt = claimOpensAt; self.claimedToday = claimedToday
            self.caretakerExpiresAt = caretakerExpiresAt; self.handle = handle; self.handleEntry = handleEntry
            self.addressed = addressed; self.ownAddress = ownAddress; self.groundworks = groundworks
            self.moveSuggestedAt = moveSuggestedAt; self.moveDeadline = moveDeadline
            self.registrationEndsAt = registrationEndsAt; self.handleExpiresAt = handleExpiresAt
        }
    }

    public static func due(_ i: Inputs) -> [Reminder] {
        var out: [Reminder] = []
        if i.identityLive, !i.claimedToday, i.claimOpensAt == 0 { out.append(.anmlReady) }
        // Clamped throughout: a hostile time saturates, never traps.
        let c = i.caretakerExpiresAt
        if i.identityLive, c > 0, i.now >= Handles.satSub(c, leadSeconds), i.now < Handles.satAdd(c, lapsedSeconds) {
            out.append(.caretakerExpiring(expiresAt: c, lapsed: i.now >= c))
        }
        out += i.groundworks.compactMap { groundworks($0, now: i.now) }
        guard i.identityLive else { return out }
        // Until what is to move stops being movable; past that a move cannot bring it.
        if i.moveSuggestedAt > 0, i.moveSuggestedAt <= i.now, i.moveDeadline <= 0 || i.now < i.moveDeadline {
            out.append(.moveSuggested(at: i.moveSuggestedAt, deadline: i.moveDeadline))
        }
        if let r = renewBeforeLapse(i) { out.append(r) }
        let held = i.handleEntry.flatMap { (!i.handle.isEmpty && $0.handle == i.handle) ? $0 : nil }
        var seen = Set<String>()
        for e in ([held].compactMap { $0 } + i.addressed) where seen.insert(e.handle).inserted {
            let st = e.status(at: i.now)
            if st != HandleEntry.free, i.now >= Handles.satSub(e.expiresAt, leadSeconds), i.now < e.renewalUntil {
                out.append(.handleExpiring(handle: e.handle, expiresAt: e.expiresAt, renewalUntil: e.renewalUntil, inRenewal: st == HandleEntry.renewal))
            }
        }
        if let held, !i.ownAddress.isEmpty, held.address != i.ownAddress, held.status(at: i.now) == HandleEntry.live {
            out.append(.handlePaysElsewhere(handle: held.handle))
        }
        return out
    }

    /// From `leadSeconds` before the registration ends: the handle or caretaker vote whose lease
    /// ends within PrivacyWallet.renewFirstWindowSeconds after it. As Android.
    public static func renewBeforeLapse(_ i: Inputs) -> Reminder? {
        let end = i.registrationEndsAt
        guard i.identityLive, end > 0, i.now >= Handles.satSub(end, leadSeconds), i.now < end else { return nil }
        let soon = Handles.satAdd(end, PrivacyWallet.renewFirstWindowSeconds)
        let h: Int64 = !i.handle.isEmpty && i.handleExpiresAt > 0 && i.handleExpiresAt < soon ? i.handleExpiresAt : 0
        let v: Int64 = i.caretakerExpiresAt > 0 && i.caretakerExpiresAt < soon ? i.caretakerExpiresAt : 0
        guard h > 0 || v > 0 else { return nil }
        return .renewBeforeLapse(registrationEndsAt: end, handle: h > 0 ? i.handle : "", handleExpiresAt: h, voteExpiresAt: v)
    }

    /// `g`'s reminder, as the caretaker vote's: from `leadSeconds` before the lease end until
    /// `lapsedSeconds` after it. None while the lease end is unknown.
    public static func groundworks(_ g: GroundworksLease, now: Int64) -> Reminder? {
        let e = g.expiresAt
        guard e > 0 else { return nil }
        let lapsed = g.lapsed(now)
        let due = lapsed ? now < Handles.satAdd(e, lapsedSeconds) : g.renewalDue(now)
        return due ? .groundworksExpiring(positionID: g.positionID, expiresAt: e, lapsed: lapsed) : nil
    }

    /// The reminder's line for a banner.
    public static func text(_ r: Reminder, now: Int64) -> String {
        switch r {
        case .anmlReady:
            return "Your ANML is ready to claim today."
        case let .caretakerExpiring(exp, lapsed):
            return lapsed ? "Your caretaker vote has lapsed and no longer counts. Cast it again to keep directing emissions."
                : "Your caretaker vote expires in \(days(Handles.satSub(exp, now))). Renew it to keep it counted."
        case let .groundworksExpiring(id, exp, lapsed):
            return lapsed ? "Your Groundworks split on position #\(id) has lapsed and no longer counts. Choose a split again to keep directing emissions."
                : "Your Groundworks split on position #\(id) expires in \(days(Handles.satSub(exp, now))). Renew it to keep it counted."
        case let .handleExpiring(h, exp, until, inRenewal):
            return inRenewal ? "@\(h) has expired and no longer receives payments. Renew it within \(days(Handles.satSub(until, now))) or anyone may claim it."
                : "@\(h) expires in \(days(Handles.satSub(exp, now))). Renew it to keep it."
        case let .handlePaysElsewhere(h):
            return "@\(h) still pays the wallet it moved from. Renew it here to point it at this wallet."
        case let .moveSuggested(_, deadline):
            if deadline <= 0 {
                return "The suggested time to bring your handle and caretaker vote from your previous identity has come. Open Identity to move them."
            }
            let left = Handles.satSub(deadline, now)
            if left <= PrivacyWallet.moveDeadlineMarginSeconds {
                return "Move your handle and caretaker vote from your previous identity now: in \(days(left)) they can no longer be moved, and your previous identity cannot renew them. Open Identity to move them."
            }
            return "Bring your handle and caretaker vote from your previous identity within \(days(left)): after that they can no longer be moved. Open Identity to move them."
        case let .renewBeforeLapse(end, h, _, v):
            let what = [h.isEmpty ? nil : "@\(h)", v > 0 ? "your caretaker vote" : nil].compactMap { $0 }.joined(separator: " and ")
            return "Your registration ends in \(days(Handles.satSub(end, now))). Renew \(what) before then: once it lapses, this identity can no longer renew them, and after you renew your registration they can be moved only until their leases end."
        }
    }

    private static func days(_ seconds: Int64) -> String {
        let d = max(0, Handles.satAdd(seconds, 86399) / 86400)
        return d == 1 ? "1 day" : "\(d) days"
    }
}
