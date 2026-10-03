import Foundation

/// What the wallet reminds its owner to do, in place of doing it unasked:
/// the day's ANML claim, the caretaker vote's refresh before it lapses, and
/// the handle's renewal before (and through) its renewal period. Each costs a
/// fee, so each is the owner's decision; nothing here broadcasts anything.
/// Ports `privacy/Reminders.kt`; `due` is pure (unit-tested).
public enum Reminders {
    /// How long before a caretaker vote or handle lapses the reminder starts.
    public static let leadSeconds: Int64 = Handles.reminderLeadSeconds
    /// How long after a caretaker vote lapsed the reminder to cast it again stays.
    public static let lapsedSeconds: Int64 = 30 * 86400

    public enum Reminder: Equatable, Sendable {
        /// Today's ANML can be claimed.
        case anmlReady
        /// The caretaker vote lapses at `expiresAt` (lapsed when in the past): cast it again to keep it counted.
        case caretakerExpiring(expiresAt: Int64, lapsed: Bool)
        /// The handle's lease ends at `expiresAt`; until `renewalUntil` only its owner may renew it (`inRenewal`: it no longer resolves).
        case handleExpiring(handle: String, expiresAt: Int64, renewalUntil: Int64, inRenewal: Bool)
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

        public init(now: Int64, identityLive: Bool, claimOpensAt: Int64?, claimedToday: Bool, caretakerExpiresAt: Int64,
                    handle: String, handleEntry: HandleEntry?) {
            self.now = now; self.identityLive = identityLive; self.claimOpensAt = claimOpensAt; self.claimedToday = claimedToday
            self.caretakerExpiresAt = caretakerExpiresAt; self.handle = handle; self.handleEntry = handleEntry
        }
    }

    public static func due(_ i: Inputs) -> [Reminder] {
        var out: [Reminder] = []
        if i.identityLive, !i.claimedToday, i.claimOpensAt == 0 { out.append(.anmlReady) }
        let c = i.caretakerExpiresAt
        if i.identityLive, c > 0, i.now >= c - leadSeconds, i.now < c + lapsedSeconds {
            out.append(.caretakerExpiring(expiresAt: c, lapsed: i.now >= c))
        }
        if i.identityLive, !i.handle.isEmpty, let e = i.handleEntry, e.handle == i.handle {
            let st = e.status(at: i.now)
            if st != HandleEntry.free, i.now >= e.expiresAt - leadSeconds, i.now < e.renewalUntil {
                out.append(.handleExpiring(handle: e.handle, expiresAt: e.expiresAt, renewalUntil: e.renewalUntil, inRenewal: st == HandleEntry.renewal))
            }
        }
        return out
    }

    /// The reminder's line for a banner.
    public static func text(_ r: Reminder, now: Int64) -> String {
        switch r {
        case .anmlReady:
            return "Your ANML is ready to claim today."
        case let .caretakerExpiring(exp, lapsed):
            return lapsed ? "Your caretaker vote has lapsed and no longer counts. Cast it again to keep directing emissions."
                : "Your caretaker vote expires in \(days(exp - now)). Renew it to keep it counted."
        case let .handleExpiring(h, exp, until, inRenewal):
            return inRenewal ? "@\(h) has expired and no longer receives payments. Renew it within \(days(until - now)) or anyone may claim it."
                : "@\(h) expires in \(days(exp - now)). Renew it to keep it."
        }
    }

    private static func days(_ seconds: Int64) -> String {
        let d = max(0, (seconds + 86399) / 86400)
        return d == 1 ? "1 day" : "\(d) days"
    }
}
