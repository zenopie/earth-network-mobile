import BigInt
import Foundation

/// x/shieldedstaking Query/Validators (`/earth/shieldedstaking/v1/validators`,
/// ORCHARD_DESIGN 12.2): every validator's quote inputs as one paged list,
/// read whole so that no query names the validator a staking msg is about to
/// act on. Every page is read at the first page's height (the later ones
/// pinned with `x-cosmos-block-height`); a page from another height, or one
/// the node cannot serve at it, starts the read over. Ports ValidatorPages.kt.
public enum ValidatorPages {
    /// One page: its entries, next_key ("" on the last) and the height it was read at.
    public struct Page: Sendable {
        public let validators: [PrivacyReads.ValidatorQuote]
        public let nextKey: String
        public let height: Int64
        public init(validators: [PrivacyReads.ValidatorQuote], nextKey: String, height: Int64) {
            self.validators = validators; self.nextKey = nextKey; self.height = height
        }
    }

    /// The chain's largest page (MaxValidatorsPage).
    public static let pageLimit = 200

    /// The most entries the wallet takes (far above any validator set).
    public static let maxValidators = 10_000

    /// Reads started over before giving up, each on a height change.
    public static let maxReads = 4

    /// Every page at one height, or an error: never a mix of two states.
    /// `fetch` gives the page after `key` ("" the first), pinned to `height`
    /// (nil: the latest); nil when not served at that height.
    public static func readAll(_ fetch: (_ key: String, _ height: Int64?) async throws -> Page?) async throws -> PrivacyReads.ValidatorList {
        for _ in 0 ..< maxReads {
            if let list = try await readOnce(fetch) { return list }
        }
        throw PrivacyError("the validator list changed height on every read; try again")
    }

    private static func readOnce(_ fetch: (_ key: String, _ height: Int64?) async throws -> Page?) async throws -> PrivacyReads.ValidatorList? {
        guard var page = try await fetch("", nil) else { return nil }
        let height = page.height
        guard height > 0 else { throw PrivacyError("the validator list names no height") }
        var out: [PrivacyReads.ValidatorQuote] = []
        var seen = Set<String>()
        var keys = Set<String>()
        while true {
            if page.height != height { return nil }
            for v in page.validators {
                guard seen.insert(v.validator).inserted else { throw PrivacyError("the validator list carries \(v.validator) twice") }
                out.append(v)
                if out.count > maxValidators { throw PrivacyError("the validator list is longer than \(maxValidators)") }
            }
            if page.nextKey.isEmpty { return PrivacyReads.ValidatorList(height: height, validators: out) }
            guard keys.insert(page.nextKey).inserted else { throw PrivacyError("the validator list's pages loop") }
            guard let next = try await fetch(page.nextKey, height) else { return nil }
            page = next
        }
    }

    /// A Query/Validators response body.
    public static func parse(_ j: JSON) throws -> Page {
        let h = str(j.height)
        guard let height = h.isEmpty ? 0 : Int64(h) else { throw PrivacyError("the validator list's height is not an int64") }
        return Page(validators: try j.validators.array.map(quote), nextKey: str(j.pagination.next_key), height: height)
    }

    /// One ValidatorQuote.
    public static func quote(_ v: JSON) throws -> PrivacyReads.ValidatorQuote {
        let op = str(v.validator)
        guard !op.isEmpty else { throw PrivacyError("a validator list entry names no validator") }
        let st = v.staking
        let stOp = str(st.operator_address)
        guard stOp.isEmpty || stOp == op else { throw PrivacyError("\(op)'s x/staking record is \(stOp)'s") }
        func int(_ x: JSON, _ name: String) throws -> BigUInt {
            let s = str(x).isEmpty ? "0" : str(x)
            guard s.count <= 80, s.allSatisfy({ $0.isASCII && $0.isNumber }), let b = BigUInt(s, radix: 10) else {
                throw PrivacyError("\(op)'s \(name) is not a non-negative integer")
            }
            return b
        }
        var loads: [String: PrivacyReads.RedelegationLoad] = [:]
        for r in v.redelegations.array {
            let dst = str(r.dst_validator)
            if dst.isEmpty { continue }
            let u32 = BigUInt(UInt32.max)
            loads[dst] = PrivacyReads.RedelegationLoad(entries: UInt64(Swift.min(try int(r.entries, "entries"), u32)),
                                                       countedEntries: UInt64(Swift.min(try int(r.counted_entries, "counted_entries"), u32)))
        }
        let supply = try int(v.supply, "supply")
        let rate: Decimal
        if let r = Decimal(string: str(v.rate), locale: Locale(identifier: "en_US_POSIX")), !r.isNaN, r >= 0 {
            rate = r
        } else if supply == 0 {
            rate = 1
        } else {
            throw PrivacyError("\(op)'s rate is not a non-negative decimal")
        }
        let commission = Double(str(st.commission.commission_rates.rate)).flatMap { (0 ... 1).contains($0) ? $0 : nil } ?? 0
        return PrivacyReads.ValidatorQuote(
            validator: op,
            backing: try int(v.backing, "backing"),
            supply: supply,
            pendingDelegation: try int(v.book.pending_delegation, "pending_delegation"),
            pendingUndelegation: try int(v.book.pending_undelegation, "pending_undelegation"),
            delegation: try int(v.delegation, "delegation"),
            rewards: try int(v.rewards, "rewards"),
            rate: rate,
            // A book whose validator x/staking removed: no status.
            status: stOp.isEmpty ? "" : str(st.status),
            jailed: !stOp.isEmpty && st.jailed.bool(default: false),
            tombstoned: v.tombstoned.bool(default: false),
            delegatable: v.delegatable.bool(default: false),
            refusal: String(str(v.refusal).prefix(300)),
            moniker: String(str(st.description.moniker).prefix(70)),
            commission: commission,
            tokens: try int(st.tokens, "tokens"),
            redelegations: loads)
    }

    /// `j`'s string, "" when absent or null.
    private static func str(_ j: JSON) -> String { j.string ?? "" }

    /// The last list read, shared by every reader.
    static let cache = Cache()

    final class Cache: @unchecked Sendable {
        private let lock = NSLock()
        private var list: PrivacyReads.ValidatorList?
        func get() -> PrivacyReads.ValidatorList? { lock.lock(); defer { lock.unlock() }; return list }
        func set(_ l: PrivacyReads.ValidatorList) { lock.lock(); defer { lock.unlock() }; list = l }
    }
}
