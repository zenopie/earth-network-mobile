import Foundation

/// Handles (x/personhood): a registered human's name in the chain's public
/// directory for a shielded address. Lowercase a-z, 0-9 and -, 3 to 32
/// characters, no leading or trailing dash; one spelling per handle (the
/// chain does no case folding). Ports `privacy/handles/Handles.kt`.
///
/// Lifecycle: live until expires_at (it resolves: payments and referrals may
/// name it); then, until renewal_until, reserved to its owner and not
/// resolving; then free. Nothing renews on its own.
public enum Handles {
    public static let minLen = 3
    public static let maxLen = 32

    /// Params defaults (handle_lease_seconds 27, handle_renewal_seconds 26).
    public static let defaultLeaseSeconds: Int64 = 365 * 86400
    public static let defaultRenewalSeconds: Int64 = 30 * 86400

    /// Seconds before expiry the reminder starts (and it stays through the renewal period).
    public static let reminderLeadSeconds: Int64 = 30 * 86400

    /// The furthest ahead any lease time the wallet takes may lie: a
    /// directory entry's expiry and renewal end, a caretaker split's
    /// expiry, and the lease params themselves. Anything past it is a hostile
    /// or broken answer, refused or clamped before it reaches any arithmetic.
    public static let maxAheadSeconds: Int64 = 10 * 365 * 86400

    /// A directory entry's owner: the handle-scope nullifier
    /// that holds it, as 64 lowercase hex digits (the MsgBindHandle
    /// membership nullifier, MsgMoveHandle new_owner). Anything else, or
    /// none, is "": no owner said, and nothing is adopted on it.
    public static func owner(_ raw: String?) -> String {
        guard let raw else { return "" }
        let h = raw.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return h.utf8.count == 64 && h.utf8.allSatisfy({ ($0 >= 0x30 && $0 <= 0x39) || ($0 >= 0x61 && $0 <= 0x66) }) ? h : ""
    }

    /// The longest address a directory entry may carry: an erthz1 address is far shorter.
    public static let maxAddressLen = 256

    /// a + b, clamped to the Int64 range rather than trapping.
    public static func satAdd(_ a: Int64, _ b: Int64) -> Int64 {
        let (r, o) = a.addingReportingOverflow(b)
        return o ? (a < 0 ? .min : .max) : r
    }

    /// a - b, clamped likewise.
    public static func satSub(_ a: Int64, _ b: Int64) -> Int64 {
        let (r, o) = a.subtractingReportingOverflow(b)
        return o ? (a < 0 ? .min : .max) : r
    }

    /// Whether `h` is a handle as the chain spells one (ValidateHandle).
    public static func valid(_ h: String) -> Bool {
        let b = Array(h.utf8)
        guard (minLen ... maxLen).contains(b.count) else { return false }
        guard b.allSatisfy({ (97 ... 122).contains($0) || (48 ... 57).contains($0) || $0 == 45 }) else { return false }
        return b.first != 45 && b.last != 45
    }

    /// What someone typed ("@Alice", " alice ") as a handle: the leading @
    /// and surrounding space dropped, lowercased; nil when that is not a
    /// handle. Lowercasing is safe: the chain only holds lowercase handles.
    public static func parse(_ input: String) -> String? {
        var t = input.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.hasPrefix("@") { t.removeFirst() }
        let h = t.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return valid(h) ? h : nil
    }

    /// Whether `input` reads as a handle rather than an address ("@x", or a bare handle that is no address).
    public static func looksLikeHandle(_ input: String) -> Bool {
        let t = input.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.hasPrefix("@") { return true }
        if t.hasPrefix("earth1") || t.hasPrefix(ShieldedAddress.hrp + "1") { return false }
        return parse(t) != nil
    }

    /// "erthz1abcd…wxyz": a shielded address shown for confirmation.
    public static func truncate(_ address: String, head: Int = 12, tail: Int = 8) -> String {
        address.count <= head + tail + 1 ? address : String(address.prefix(head)) + "…" + String(address.suffix(tail))
    }
}

/// A directory entry as Query/Handles serves it.
public struct HandleEntry: Equatable, Sendable {
    public static let live = "live"
    public static let renewal = "renewal"
    public static let free = "free"

    public let handle: String
    /// bech32m "erthz1..."
    public let address: String
    /// "live" | "renewal" | "free"
    public let status: String
    public let expiresAt: Int64
    public let renewalUntil: Int64
    /// `Handles.owner`: the holder's handle-scope nullifier (hex), "" when the source does not say.
    public let owner: String

    public init(handle: String, address: String, status: String, expiresAt: Int64, renewalUntil: Int64, owner: String = "") {
        self.handle = handle; self.address = address; self.status = status; self.expiresAt = expiresAt; self.renewalUntil = renewalUntil
        self.owner = owner
    }

    /// The entry's status as of `now`: the served status, demoted when its times have passed since.
    public func status(at now: Int64) -> String {
        if status == Self.free { return Self.free }
        if now < expiresAt { return status }
        if now < renewalUntil { return Self.renewal }
        return Self.free
    }
}

/// The whole handle directory, fetched in full and never asked about one
/// handle: a lookup of the handle a wallet is about to pay would tell the
/// server who pays whom.
///
/// Read from the privacy backend's stream (`fetchStream`: a snapshot of the
/// chain's Handles query at one height, paged by place from 0, restarted
/// when the height moves between pages), or, when the backend cannot serve
/// it, from the chain's own Query/Handles pages (`fetchChainPage`). Kept
/// for `maxAge` seconds. Before money moves on a handle (a payment, a
/// referral) `resolveForPayment` reads a fresh copy and checks the entry
/// against the chain's own directory (also fetched whole), so neither an
/// indexer nor a stale cache can redirect a payment.
///
/// That check is worth something only while the two copies come from
/// different places. `requireBackend` says when the node cannot be the
/// other one (the user's own node over plain http, which anyone on that
/// network can answer for): a payment then needs the backend's copy, read
/// over https, to agree with the node's, and never resolves from the node
/// alone. Mirrors HandleDirectory in Handles.kt.
public actor HandleDirectory {
    /// One page of the chain's Query/Handles: handles after start; next "" when exhausted.
    public struct Page: Sendable {
        public let handles: [HandleEntry]
        public let next: String
        public init(handles: [HandleEntry], next: String) { self.handles = handles; self.next = next }
    }

    /// One page of the backend's stream: the snapshot's rows from fromIndex, its height and size.
    public struct StreamPage: Sendable {
        public let handles: [HandleEntry]
        public let height: Int64?
        public let size: Int64
        public let fromIndex: Int64
        public let lastPage: Bool
        public init(handles: [HandleEntry], height: Int64?, size: Int64, fromIndex: Int64, lastPage: Bool) {
            self.handles = handles; self.height = height; self.size = size; self.fromIndex = fromIndex; self.lastPage = lastPage
        }
    }

    public struct Inconsistent: Error, CustomStringConvertible, LocalizedError {
        public let description: String
        public init(_ d: String) { description = d }
        public var errorDescription: String? { description }
    }

    /// Why a handle cannot be paid now, or its shielded address when it can.
    public enum Resolution: Sendable {
        case payable(HandleEntry, ShieldedAddress)
        case notPayable(String)
    }

    /// Query/Handles' largest page, and the backend stream's page.
    public static let page = 1000
    /// The most rows the wallet holds; more fails closed.
    /// Near the backend's 200k (README), well under what a phone holds.
    public static let maxRows = 250_000
    public static let maxPages = maxRows / page
    public static let freshSeconds: Int64 = 60
    public static let streamRestarts = 3
    private static let statuses: Set<String> = [HandleEntry.live, HandleEntry.renewal, HandleEntry.free]

    private let fetchChainPage: @Sendable (String, Int) async throws -> Page
    private let fetchStream: (@Sendable (Int64, Int) async throws -> StreamPage)?
    private let now: @Sendable () -> Int64
    private let maxAgeSeconds: Int64
    private let requireBackend: @Sendable () -> Bool
    private var entries: [String: HandleEntry]?
    private var fetchedAt: Int64 = 0
    /// Whether `entries` is the backend's copy (not the chain pages it falls back on).
    private var fromBackend = false
    private var chainEntries: [String: HandleEntry]?
    private var chainFetchedAt: Int64 = 0

    public init(fetchChainPage: @escaping @Sendable (String, Int) async throws -> Page,
                fetchStream: (@Sendable (Int64, Int) async throws -> StreamPage)? = nil,
                now: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970) },
                maxAgeSeconds: Int64 = 600,
                requireBackend: @escaping @Sendable () -> Bool = { false }) {
        self.fetchChainPage = fetchChainPage; self.fetchStream = fetchStream; self.now = now; self.maxAgeSeconds = maxAgeSeconds
        self.requireBackend = requireBackend
    }

    /// The directory, from cache when it is younger than `maxAge` seconds.
    public func all(maxAge: Int64? = nil) async throws -> [String: HandleEntry] { try await read(maxAge: maxAge).0 }

    /// The directory and whether it is the backend's copy.
    private func read(maxAge: Int64?) async throws -> ([String: HandleEntry], Bool) {
        let age = maxAge ?? maxAgeSeconds
        if let e = entries, (0 ... age).contains(now() - fetchedAt) { return (e, fromBackend) }
        var out: [String: HandleEntry]? = nil
        if let f = fetchStream { out = try? await readStream(f) }
        let got: [String: HandleEntry]
        if let out { got = out } else { got = try await chainDirectory(maxAge: age) }
        entries = got; fromBackend = out != nil; fetchedAt = now()
        return (got, fromBackend)
    }

    public static let unconfirmedOverHTTP = "Your node is reached over plain http, so a handle is paid only when Earth's directory "
        + "confirms it, and it could not be read. Try again, or pay the address itself."

    /// 0 < expires_at <= renewal_until <= now + `Handles.maxAheadSeconds`.
    public static func timesOk(_ e: HandleEntry, now: Int64) -> Bool {
        e.expiresAt > 0 && e.expiresAt <= e.renewalUntil && e.renewalUntil <= Handles.satAdd(now, Handles.maxAheadSeconds)
    }

    /// The chain's own directory and when it was read (wallet clock): what the wallet squares its own handle with.
    public func chainDirectoryRead(maxAge: Int64? = nil) async throws -> ([String: HandleEntry], Int64) {
        let d = try await chainDirectory(maxAge: maxAge)
        return (d, chainFetchedAt)
    }

    /// The chain's own directory (Query/Handles, every page), from cache when younger than `maxAge`.
    public func chainDirectory(maxAge: Int64? = nil) async throws -> [String: HandleEntry] {
        let age = maxAge ?? maxAgeSeconds
        if let e = chainEntries, (0 ... age).contains(now() - chainFetchedAt) { return e }
        let out = try await readChain()
        chainEntries = out; chainFetchedAt = now()
        return out
    }

    private func check(_ e: HandleEntry, after: String, _ out: [String: HandleEntry]) throws {
        // In handle order, each once, well formed: anything else is not the chain's directory.
        guard Handles.valid(e.handle) else { throw Inconsistent("the directory holds \(e.handle.prefix(40)), not a handle") }
        guard e.handle > after, out[e.handle] == nil else { throw Inconsistent("the directory is out of order at \(e.handle)") }
        guard Self.statuses.contains(e.status) else { throw Inconsistent("handle \(e.handle): status \(e.status.prefix(20))") }
        guard e.address.utf8.count <= Handles.maxAddressLen else { throw Inconsistent("handle \(e.handle): address too long") }
        // Times a lease can have, 0 < expires_at <= renewal_until <= now + 10 years;
        // anything else is refused before any reminder or status does arithmetic on it.
        guard Self.timesOk(e, now: now()) else { throw Inconsistent("handle \(e.handle): times out of range") }
        guard out.count < Self.maxRows else { throw Inconsistent("the directory has more than \(Self.maxRows) handles") }
    }

    private func readChain() async throws -> [String: HandleEntry] {
        var out: [String: HandleEntry] = [:]
        var start = ""
        var pages = 0
        while true {
            let p = try await fetchChainPage(start, Self.page)
            guard p.handles.count <= Self.page else { throw Inconsistent("the node sent \(p.handles.count) handles in a page of \(Self.page)") }
            for e in p.handles { try check(e, after: start, out); out[e.handle] = e; start = e.handle }
            if p.next.isEmpty || p.handles.isEmpty { break }
            guard p.next == start else { throw Inconsistent("the directory's next is not its last handle") }
            pages += 1
            guard pages <= Self.maxPages else { throw Inconsistent("the directory has more than \(Self.maxPages) pages") }
        }
        return out
    }

    /// The backend's snapshot, pages 0, PAGE, 2 PAGE, ... (its paging rule:
    /// fixed, aligned pages) until its last; started over when the
    /// snapshot's height changes between pages.
    private func readStream(_ fetch: @Sendable (Int64, Int) async throws -> StreamPage) async throws -> [String: HandleEntry] {
        for _ in 0 ..< Self.streamRestarts {
            var out: [String: HandleEntry] = [:]
            var from: Int64 = 0
            var height: Int64?
            var last = ""
            var moved = false
            while true {
                let p = try await fetch(from, Self.page)
                guard p.fromIndex == from, p.handles.count <= Self.page else { throw Inconsistent("the indexer's handle page is not the one asked for") }
                if from == 0 {
                    height = p.height
                    guard (0 ... Int64(Self.maxRows)).contains(p.size) else { throw Inconsistent("the indexer's directory claims \(p.size) handles") }
                } else if p.height != height { moved = true; break }
                for e in p.handles { try check(e, after: last, out); out[e.handle] = e; last = e.handle }
                if p.lastPage || p.handles.count < Self.page {
                    guard Int64(out.count) == p.size else { throw Inconsistent("the indexer's directory holds \(out.count) of its \(p.size) handles") }
                    return out
                }
                from += Int64(Self.page)
                guard from / Int64(Self.page) <= Int64(Self.maxPages) else { throw Inconsistent("the directory has more than \(Self.maxPages) pages") }
            }
            if !moved { throw Inconsistent("the indexer's handle stream ended early") }
        }
        throw Inconsistent("the indexer's handle directory kept changing while it was read")
    }

    /// A copy fetched within the last `freshSeconds`: for a payment about to be confirmed.
    public func fresh() async throws -> [String: HandleEntry] { try await all(maxAge: Self.freshSeconds) }

    public func lookup(_ handle: String, maxAge: Int64? = nil) async throws -> HandleEntry? { try await all(maxAge: maxAge)[handle] }

    public func invalidate() { entries = nil; chainEntries = nil }

    /// `input` ("@alice", "alice") resolved for a payment or a referral: live
    /// now in a fresh copy of the directory, and the same (address, live) in
    /// the chain's own, also fetched whole.
    public func resolveForPayment(_ input: String) async throws -> Resolution {
        guard let h = Handles.parse(input) else {
            return .notPayable("\"\(input.trimmingCharacters(in: .whitespaces).prefix(40))\" is not a handle")
        }
        let (dir, backend) = try await read(maxAge: Self.freshSeconds)
        if requireBackend() && !backend {
            invalidate()
            return .notPayable(Self.unconfirmedOverHTTP)
        }
        guard let e = dir[h] else { return .notPayable("@\(h) is not claimed by anyone") }
        guard e.status(at: now()) == HandleEntry.live else { return .notPayable("@\(h) has lapsed and names no address now") }
        let c = try await chainDirectory(maxAge: Self.freshSeconds)[h]
        guard let c, c.address == e.address, c.status(at: now()) == HandleEntry.live else {
            invalidate()
            return .notPayable("@\(h) changed on chain since the directory was read; try again")
        }
        guard let a = try? ShieldedAddress.decode(e.address) else { return .notPayable("@\(h) names an address this wallet cannot read") }
        return .payable(e, a)
    }
}
