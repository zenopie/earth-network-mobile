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
        /// The live handle this identity holds pays another address (a handle moved here keeps the old
        /// wallet's): renewing it from this wallet points it here.
        case handlePaysElsewhere(handle: String)
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
        /// Audit 5 (M1): directory entries naming this wallet's own address (a handle held but not in
        /// the store, after a restore): reminded like the held one.
        public var addressed: [HandleEntry]
        /// This wallet's shielded address ("" unknown): a held handle paying another is pointed out.
        public var ownAddress: String

        public init(now: Int64, identityLive: Bool, claimOpensAt: Int64?, claimedToday: Bool, caretakerExpiresAt: Int64,
                    handle: String, handleEntry: HandleEntry?, addressed: [HandleEntry] = [], ownAddress: String = "") {
            self.now = now; self.identityLive = identityLive; self.claimOpensAt = claimOpensAt; self.claimedToday = claimedToday
            self.caretakerExpiresAt = caretakerExpiresAt; self.handle = handle; self.handleEntry = handleEntry
            self.addressed = addressed; self.ownAddress = ownAddress
        }
    }

    public static func due(_ i: Inputs) -> [Reminder] {
        var out: [Reminder] = []
        if i.identityLive, !i.claimedToday, i.claimOpensAt == 0 { out.append(.anmlReady) }
        // Clamped throughout (audit 5, M4): a hostile time saturates, never traps.
        let c = i.caretakerExpiresAt
        if i.identityLive, c > 0, i.now >= Handles.satSub(c, leadSeconds), i.now < Handles.satAdd(c, lapsedSeconds) {
            out.append(.caretakerExpiring(expiresAt: c, lapsed: i.now >= c))
        }
        guard i.identityLive else { return out }
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

    /// The reminder's line for a banner.
    public static func text(_ r: Reminder, now: Int64) -> String {
        switch r {
        case .anmlReady:
            return "Your ANML is ready to claim today."
        case let .caretakerExpiring(exp, lapsed):
            return lapsed ? "Your caretaker vote has lapsed and no longer counts. Cast it again to keep directing emissions."
                : "Your caretaker vote expires in \(days(Handles.satSub(exp, now))). Renew it to keep it counted."
        case let .handleExpiring(h, exp, until, inRenewal):
            return inRenewal ? "@\(h) has expired and no longer receives payments. Renew it within \(days(Handles.satSub(until, now))) or anyone may claim it."
                : "@\(h) expires in \(days(Handles.satSub(exp, now))). Renew it to keep it."
        case let .handlePaysElsewhere(h):
            return "@\(h) still pays the wallet it moved from. Renew it here to point it at this wallet."
        }
    }

    private static func days(_ seconds: Int64) -> String {
        let d = max(0, Handles.satAdd(seconds, 86399) / 86400)
        return d == 1 ? "1 day" : "\(d) days"
    }
}
