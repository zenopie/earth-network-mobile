import Foundation

/// One line of the wallet's activity, from either source: a public tx this
/// wallet signed (looked up by hash, `SentTxLog`) or a private row built from
/// the sealed store alone (`PrivateActivity`). Carries everything the wallet
/// knows about it, so the list can show three words of it and the detail
/// sheet all of it. Ports `ui/wallet/ActivityMapper.kt`.
///
/// Anything unrecognised falls through to the message's own name rather than
/// being dropped: a wallet that hides txs it does not understand looks like
/// funds vanished.
public struct ActivityEntry: Identifiable, Equatable, Sendable {
    public enum Status: Equatable, Sendable { case completed, pending, failed }

    /// The list's leading mark when the row is not about one coin.
    public enum Glyph: Equatable, Sendable { case send, receive, swap, stake, register, vote, handle, other }

    public let id: String
    /// "Sent", "Swapped", "Gas from Earth".
    public let title: String
    /// The detail sheet's Type: the private kind's full name, or the message's.
    public let typeName: String
    public let glyph: Glyph
    /// Signed: negative left the wallet, positive came in.
    public let coins: [ActivityCoin]
    /// As recorded: a handle, an address, a validator, "proposal 4".
    public let counterparty: String
    public let status: Status
    public let failure: String?
    /// Unix seconds; `timeExact` false when estimated from the block height.
    public let time: Int64?
    public let timeExact: Bool
    /// What this wallet paid, as positive amounts (empty: none, or unknown).
    public let fee: [ActivityCoin]
    /// Who paid the fee, when the wallet knows.
    public let feePayer: String?
    public let height: UInt64?
    public let isPrivate: Bool
    /// The tx hash, when there is one (a received note has none).
    public let hash: String?
    public let memo: String?
    /// "84,123 of 200,000" (public txs).
    public let gas: String?
    /// The private kind, or nil for a public tx.
    public let kind: PrivateActivityKind?

    public var sortTime: Int64 { time ?? 0 }
    public var outs: [ActivityCoin] { coins.filter { $0.amount < 0 } }
    public var ins: [ActivityCoin] { coins.filter { $0.amount > 0 } }

    // MARK: - from a public tx

    public init?(tx: Explorer.Tx, self address: String) {
        let type = tx.types.first ?? ""
        let m = tx.first
        let mine = (m["from_address"] as? String) == address || (m["sender"] as? String) == address

        var title: String
        var glyph: Glyph
        var sign: Int64 = 0
        var party = ""
        switch type {
        case "MsgSend":
            if (m["from_address"] as? String) == address {
                (title, glyph, sign, party) = ("Sent", .send, -1, m["to_address"] as? String ?? "")
            } else {
                (title, glyph, sign, party) = ("Received", .receive, 1, m["from_address"] as? String ?? "")
            }
        case "MsgShield": (title, glyph, sign) = ("Shielded", .send, mine ? -1 : 0)
        case "MsgDelegate": (title, glyph, sign, party) = ("Staked", .stake, -1, m["validator_address"] as? String ?? "")
        case "MsgUndelegate": (title, glyph, sign, party) = ("Unstaked", .stake, 1, m["validator_address"] as? String ?? "")
        case "MsgBeginRedelegate":
            (title, glyph) = ("Moved stake", .stake)
            party = [m["validator_src_address"] as? String, m["validator_dst_address"] as? String].compactMap { $0 }.joined(separator: " → ")
        case "MsgWithdrawDelegatorReward": (title, glyph, party) = ("Claimed rewards", .receive, m["validator_address"] as? String ?? "")
        case "MsgRegister": (title, glyph) = ("Registered", .register)
        // Distinct from registration: you register once and claim every day.
        case "MsgClaimAnml": (title, glyph) = ("Claimed ANML", .receive)
        case "MsgSwap": (title, glyph) = ("Swapped", .swap)
        case "MsgSetAllocations", "MsgSetAllocation": (title, glyph) = ("Allocated", .vote)
        case "MsgVote":
            (title, glyph) = ("Voted", .vote)
            party = (m["proposal_id"] as? String).map { "proposal \($0)" } ?? ""
        case "MsgAddLiquidity": (title, glyph, sign) = ("Added liquidity", .swap, -1)
        case "MsgRemoveLiquidity": (title, glyph) = ("Removed liquidity", .swap)
        default: (title, glyph) = (Self.readable(type.replacingOccurrences(of: "Msg", with: "")), .other)
        }
        if title.isEmpty { title = "Transaction" }

        id = tx.hash
        self.title = title
        let more = tx.types.count > 1 ? " + \(tx.types.count - 1) more" : ""
        typeName = Self.readable(type.replacingOccurrences(of: "Msg", with: "")) + more
        self.glyph = glyph
        coins = sign == 0 ? [] : Self.messageCoin(m).map { [ActivityCoin($0.denom, $0.amount * sign)] } ?? []
        counterparty = party
        status = tx.success ? (tx.height > 0 ? .completed : .pending) : .failed
        failure = tx.success ? nil : (tx.rawLog.isEmpty ? "code \(tx.code)" : tx.rawLog)
        time = Self.unix(tx.timestamp)
        timeExact = true
        fee = tx.fee.filter { $0.amount > 0 }
        if !tx.feeGranter.isEmpty {
            feePayer = "Fee grant from \(Self.abbreviate(tx.feeGranter))"
        } else if !tx.feePayer.isEmpty, tx.feePayer != address {
            feePayer = Self.abbreviate(tx.feePayer)
        } else {
            feePayer = fee.isEmpty ? nil : "You"
        }
        height = tx.height > 0 ? UInt64(tx.height) : nil
        isPrivate = false
        hash = tx.hash
        memo = tx.memo.isEmpty ? nil : tx.memo
        gas = tx.gasWanted > 0 ? "\(Self.grouped(String(tx.gasUsed))) of \(Self.grouped(String(tx.gasWanted)))" : nil
        kind = nil
    }

    // MARK: - from a private row

    public init(private row: PrivateActivityRow) {
        id = row.id
        kind = row.kind
        title = Self.title(row.kind, counterparty: row.counterparty)
        typeName = row.kind.label
        glyph = Self.glyph(row.kind)
        coins = row.coins
        counterparty = row.counterparty
        status = switch row.status {
        case .pending: .pending
        case .confirmed: .completed
        case .failed: .failed
        }
        failure = row.failure
        time = row.time
        timeExact = row.timeExact
        fee = (row.fee ?? 0) > 0 ? [ActivityCoin(PrivacyWallet.fee, Int64(clamping: row.fee!))] : []
        // A private tx's fee comes out of its own shielded ERTH, never a signer's account.
        feePayer = row.kind.sent ? (row.feeFromGrant ? "Earth (gas grant)" : "You (private ERTH)") : nil
        height = row.height
        isPrivate = true
        hash = row.hash
        memo = nil
        gas = nil
    }

    /// Short, as the list says it.
    static func title(_ k: PrivateActivityKind, counterparty: String) -> String {
        switch k {
        case .register: "Registered"
        case .switchIdentity: "Switched identity"
        case .move: "Moved to new identity"
        case .shield: "Shielded"
        case .unshield: "Unshielded"
        case .send: "Sent"
        case .merge: "Merged notes"
        case .swap: "Swapped"
        case .addLiquidity: "Added liquidity"
        case .removeLiquidity: "Removed liquidity"
        case .stake: "Staked"
        case .unstake: "Unstaked"
        case .redelegate: "Moved stake"
        case .restake: "Merged stake"
        case .vote, .caretaker: "Voted"
        case .position: counterparty.hasPrefix("unlocked") ? "Unlocked Groundworks" : "Groundworks"
        case .claimAnml: "Claimed ANML"
        case .handle: counterparty.hasPrefix("released") ? "Released handle" : "Claimed handle"
        case .gasGrant: "Gas from Earth"
        case .unbondingPayout: "Stake returned"
        case .lpPayout: "Liquidity returned"
        case .registrationReward: "Registration reward"
        case .referral: "Referral reward"
        case .fromEarth: "From Earth"
        case .received: "Received"
        case .inferred: "Private transaction"
        }
    }

    static func glyph(_ k: PrivateActivityKind) -> Glyph {
        switch k {
        case .send, .unshield, .move, .shield: .send
        case .swap, .addLiquidity, .removeLiquidity, .lpPayout: .swap
        case .stake, .unstake, .redelegate, .restake, .position, .unbondingPayout: .stake
        case .register, .switchIdentity: .register
        case .vote, .caretaker: .vote
        case .handle: .handle
        case .claimAnml, .gasGrant, .registrationReward, .referral, .fromEarth, .received: .receive
        case .merge, .inferred: .other
        }
    }

    // MARK: - what the list shows

    /// The one amount the row shows: what came in for a swap, a claim, a
    /// registration or anything received; what left for the rest; the other
    /// side when there is only that one.
    public var primary: ActivityCoin? {
        let prefersIn: Bool = switch kind {
        case .swap, .removeLiquidity, .claimAnml, .register: true
        case .inferred: false
        case let k?: !k.sent
        case nil: glyph == .receive || glyph == .swap
        }
        let (a, b) = prefersIn ? (ins, outs) : (outs, ins)
        return a.first ?? b.first
    }

    /// The row's mark is the coin's logo when the row is about moving it,
    /// the kind's glyph when it is about an action (stake, register, vote).
    public var showsCoin: Bool {
        switch glyph {
        case .send, .receive, .swap: primary != nil
        default: false
        }
    }

    /// "Out of gas", "Expired": the status pill's few words.
    public var shortFailure: String? {
        guard status == .failed else { return nil }
        return Self.shortReason(failure)
    }

    public static func shortReason(_ text: String?) -> String {
        let t = (text ?? "").lowercased()
        if t == PrivateActivity.neverLanded { return "Expired before a block" }
        if t.contains("out of gas") { return "Out of gas" }
        if t.contains("insufficient fee") { return "Fee too low" }
        if t.contains("insufficient funds") || t.contains("insufficient balance") { return "Not enough funds" }
        if t.contains("already spent") || t.contains("nullifier") { return "Notes already spent" }
        if t.contains("slippage") || t.contains("min out") || t.contains("minimum") { return "Price moved" }
        if t.contains("expired") || t.contains("timeout") { return "Expired" }
        return "Refused by the chain"
    }

    /// "Today", "Yesterday", "Oct 5", "Oct 5, 2025"; "Earlier" when the time is unknown.
    public static func dayTitle(_ time: Int64?, now: Int64, calendar: Calendar = .current) -> String {
        guard let time else { return "Earlier" }
        let day = Date(timeIntervalSince1970: TimeInterval(time))
        let today = Date(timeIntervalSince1970: TimeInterval(now))
        if calendar.isDate(day, inSameDayAs: today) { return "Today" }
        if let y = calendar.date(byAdding: .day, value: -1, to: today), calendar.isDate(day, inSameDayAs: y) { return "Yesterday" }
        let f = DateFormatter()
        f.calendar = calendar
        f.timeZone = calendar.timeZone
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = calendar.component(.year, from: day) == calendar.component(.year, from: today) ? "MMM d" : "MMM d, yyyy"
        return f.string(from: day)
    }

    /// The list under day headers, newest day first, rows in the order given.
    public static func days(_ entries: [ActivityEntry], now: Int64, calendar: Calendar = .current) -> [(title: String, entries: [ActivityEntry])] {
        var out: [(title: String, entries: [ActivityEntry])] = []
        for e in entries {
            let t = dayTitle(e.time, now: now, calendar: calendar)
            if let i = out.firstIndex(where: { $0.title == t }) { out[i].entries.append(e) } else { out.append((t, [e])) }
        }
        return out
    }

    /// "9:41 AM", "~9:41 AM" when estimated.
    public static func clock(_ time: Int64?, exact: Bool, calendar: Calendar = .current) -> String {
        guard let time else { return "" }
        let f = DateFormatter()
        f.timeZone = calendar.timeZone
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "h:mm a"
        return (exact ? "" : "~") + f.string(from: Date(timeIntervalSince1970: TimeInterval(time)))
    }

    // MARK: - the detail sheet

    public struct Detail: Equatable, Sendable {
        public let label: String
        public let value: String
        /// The text a tap copies (the full hash, the full address).
        public let copy: String?
        public init(_ label: String, _ value: String, copy: String? = nil) { self.label = label; self.value = value; self.copy = copy }
    }

    /// Every detail the wallet knows, in order, leaving out what it does not.
    /// `name` resolves a validator's operator address to its moniker.
    public func details(name: (String) -> String = { $0 }, calendar: Calendar = .current) -> [Detail] {
        var d: [Detail] = [Detail("Type", typeName)]
        if let time {
            let f = DateFormatter()
            f.timeZone = calendar.timeZone
            f.locale = Locale(identifier: "en_US_POSIX")
            f.dateFormat = "MMM d, yyyy 'at' h:mm a"
            d.append(Detail("Date", (timeExact ? "" : "About ") + f.string(from: Date(timeIntervalSince1970: TimeInterval(time)))))
        }
        if !outs.isEmpty { d.append(Detail(kind == .swap ? "You paid" : "Sent", outs.map { Self.coinText($0) }.joined(separator: "\n"))) }
        if !ins.isEmpty { d.append(Detail(kind == .swap ? "You got" : "Received", ins.map { Self.coinText($0) }.joined(separator: "\n"))) }
        if let (label, value, copy) = party(name: name) { d.append(Detail(label, value, copy: copy)) }
        if !fee.isEmpty { d.append(Detail("Network fee", fee.map { Self.coinText($0, signed: false) }.joined(separator: "\n"))) }
        if let feePayer { d.append(Detail("Fee paid by", feePayer)) }
        if let gas { d.append(Detail("Gas used", gas)) }
        if let height { d.append(Detail("Block", Self.grouped(String(height)))) }
        d.append(Detail("Privacy", isPrivate ? "Private" : "Public"))
        if let memo { d.append(Detail("Memo", memo)) }
        if status == .failed, let failure, !failure.isEmpty { d.append(Detail("Error", failure)) }
        if let hash { d.append(Detail("Transaction", Self.abbreviate(hash, head: 6, tail: 6), copy: hash)) }
        return d
    }

    /// Who or what is on the other side, labelled for the kind.
    func party(name: (String) -> String) -> (String, String, String?)? {
        if counterparty.isEmpty {
            switch kind {
            case .gasGrant, .fromEarth, .registrationReward, .unbondingPayout, .lpPayout: return ("From", "Earth", nil)
            case .referral: return ("From", "A referral", nil)
            default: return nil
            }
        }
        let value = Self.people(counterparty, name: name, short: true)
        let copy = counterparty.split(separator: " ").count == 1 && counterparty.hasPrefix("earth") ? counterparty : nil
        let label: String
        switch kind {
        case .send, .unshield, .move: label = "To"
        case .stake, .unstake, .restake: label = "Validator"
        case .redelegate: label = "Validators"
        case .vote, .caretaker: label = "Vote"
        case .addLiquidity, .removeLiquidity: label = "Pool"
        case .handle: label = "Handle"
        case .position: label = "Position"
        case nil:
            switch glyph {
            case .send: label = "To"
            case .receive where title == "Received": label = "From"
            case .stake, .receive: label = "Validator"
            case .vote: label = "Vote"
            default: label = "With"
            }
        default: label = "With"
        }
        return (label, value, copy)
    }

    /// The list's second line when it names someone: "@alice", "Moss Validator", "Proposal 4".
    public func listParty(name: (String) -> String) -> String? {
        guard !counterparty.isEmpty else { return nil }
        return Self.people(counterparty, name: name, short: true)
    }

    /// Operator addresses as monikers, addresses shortened, the first letter up.
    static func people(_ text: String, name: (String) -> String, short: Bool) -> String {
        let words = text.split(separator: " ").map { w -> String in
            let s = String(w)
            if s.hasPrefix("earthvaloper") { return name(s) }
            if s.hasPrefix("earth1"), short { return abbreviate(s) }
            return s
        }
        let joined = words.joined(separator: " ")
        guard let f = joined.first, f.isLowercase, !text.hasPrefix("earth") else { return joined }
        return f.uppercased() + joined.dropFirst()
    }

    // MARK: - figures

    /// "+1,250.5 ERTH" at full precision.
    public static func coinText(_ c: ActivityCoin, signed: Bool = true) -> String {
        let sign = !signed ? "" : (c.amount < 0 ? "−" : "+")
        return "\(sign)\(figure(c.amount)) \(PrivateActivity.symbol(c.denom))"
    }

    /// Six decimals' base units as "1,250.5": grouped, trailing zeros dropped, unsigned.
    public static func figure(_ units: Int64) -> String {
        let v = units == Int64.min ? UInt64(Int64.max) : UInt64(abs(units))
        let whole = v / 1_000_000, frac = v % 1_000_000
        var f = String(frac).leftPad(6)
        while f.hasSuffix("0") { f.removeLast() }
        return grouped(String(whole)) + (f.isEmpty ? "" : "." + f)
    }

    static func grouped(_ digits: String) -> String {
        var out = ""
        for (i, c) in digits.reversed().enumerated() {
            if i > 0, i % 3 == 0 { out.append(",") }
            out.append(c)
        }
        return String(out.reversed())
    }

    // MARK: - merge and helpers

    /// Public rows (timestamps from the chain) and private rows (the sealed
    /// store's), one list, newest first. A tx in both is shown once, as its
    /// private row (the richer one).
    public static func merge(public pub: [ActivityEntry], private priv: [ActivityEntry]) -> [ActivityEntry] {
        let hashes = Set(priv.compactMap { $0.hash?.uppercased() })
        return (pub.filter { !hashes.contains($0.hash?.uppercased() ?? "") } + priv).sorted { $0.sortTime > $1.sortTime }
    }

    /// The message's own coin field: `amount` as a list or one coin.
    static func messageCoin(_ m: [String: Any]) -> ActivityCoin? {
        let coin = (m["amount"] as? [[String: Any]])?.first ?? m["amount"] as? [String: Any]
        guard let coin, let raw = coin["amount"] as? String, let units = Int64(raw), let denom = coin["denom"] as? String else { return nil }
        return ActivityCoin(denom, units)
    }

    /// "earth1jtc…aar6".
    public static func abbreviate(_ text: String, head: Int = 10, tail: Int = 4) -> String {
        text.count <= head + tail + 2 ? text : "\(text.prefix(head))…\(text.suffix(tail))"
    }

    /// "SetAllocations" -> "Set allocations".
    static func readable(_ text: String) -> String {
        var spaced = ""
        for c in text {
            if c.isUppercase, !spaced.isEmpty { spaced.append(" ") }
            spaced.append(c)
        }
        guard let first = spaced.first else { return spaced }
        return String(first).uppercased() + spaced.dropFirst().lowercased()
    }

    /// An RFC 3339 chain timestamp to unix seconds; nil when it does not parse.
    static func unix(_ iso: String) -> Int64? {
        let trimmed = iso.components(separatedBy: ".").first?.replacingOccurrences(of: "Z", with: "") ?? iso
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd'T'HH:mm:ss"
        f.timeZone = TimeZone(identifier: "UTC")
        f.locale = Locale(identifier: "en_US_POSIX")
        return f.date(from: trimmed).map { Int64($0.timeIntervalSince1970) }
    }
}

private extension String {
    func leftPad(_ n: Int) -> String { count >= n ? self : String(repeating: "0", count: n - count) + self }
}
