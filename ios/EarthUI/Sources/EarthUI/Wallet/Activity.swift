import EarthCore
import Foundation

/// A chain transaction, resolved into a row a person can read.
///
/// Ports `ui/wallet/ActivityMapper.kt`. Anything unrecognised falls through
/// to the raw message name rather than being dropped — a wallet that silently
/// hides transactions it does not understand is worse than one that shows an
/// unfamiliar word. The second can be searched for; the first looks like funds
/// vanished.
struct ActivityRow: Identifiable, Equatable {
    let txHash: String
    let kind: Kind
    let counterparty: String
    let amount: String
    let timestamp: String
    let failed: Bool
    /// Built from the sealed store (lock), or a public tx looked up by hash (globe).
    var isPrivate = false
    /// What the row calls itself, when the kind's own word is not enough.
    var title: String?
    /// Unix seconds, for one list newest first (0: unknown).
    var sortTime: Int64 = 0
    /// The tx hash, when there is one (a received note has none).
    var hash: String?
    var pending = false

    var id: String { txHash }

    var label: String { title ?? kind.label }

    enum Kind: Equatable {
        case sent, received, staked, unstaked, claimed, claimedAnml
        case registered, swapped, allocated

        var label: String {
            switch self {
            case .sent: "Sent"
            case .received: "Received"
            case .staked: "Staked"
            case .unstaked: "Unstaked"
            case .claimed: "Claimed rewards"
            case .claimedAnml: "Claimed ANML"
            case .registered: "Registered"
            case .swapped: "Swapped"
            case .allocated: "Allocated"
            }
        }

        var glyph: String {
            switch self {
            case .sent: "↑"
            case .received: "↓"
            case .staked: "▲"
            case .unstaked: "▼"
            case .claimed, .claimedAnml: "✦"
            case .registered: "✓"
            case .swapped: "⇄"
            case .allocated: "◴"
            }
        }
    }

    init?(tx: Explorer.Tx, self address: String) {
        let type = tx.types.first ?? ""
        let message = tx.first

        kind = switch type {
        case "MsgSend":
            (message["from_address"] as? String) == address ? .sent : .received
        case "MsgDelegate": .staked
        case "MsgUndelegate": .unstaked
        case "MsgBeginRedelegate": .staked
        case "MsgWithdrawDelegatorReward": .claimed
        case "MsgRegister": .registered
        // Distinct from registration: you register once and claim every day,
        // so folding them together labels the whole history "Registered".
        case "MsgClaimAnml": .claimedAnml
        case "MsgSwap": .swapped
        case "MsgSetAllocations", "MsgSetAllocation": .allocated
        default: .sent
        }

        let named: String = switch kind {
        case .sent: message["to_address"] as? String ?? ""
        case .received: message["from_address"] as? String ?? ""
        case .staked, .unstaked, .claimed: message["validator_address"] as? String ?? ""
        default: ""
        }

        txHash = tx.hash
        counterparty = named.isEmpty
            ? ActivityRow.readable(type.replacingOccurrences(of: "Msg", with: ""))
            : ActivityRow.abbreviate(named)
        amount = ActivityRow.amountLabel(message, kind: kind)
        timestamp = ActivityRow.relative(tx.timestamp)
        failed = !tx.success
        sortTime = ActivityRow.unix(tx.timestamp) ?? 0
        hash = tx.hash
    }

    /// A private row (`PrivateActivity`, built from the sealed store alone)
    /// in the list's terms. Its time is the wallet's own when it sent the tx,
    /// or one estimated from the block height ("~"); a received note names
    /// its block. As Android's PrivateActivityRow.toActivityRow.
    init(private row: PrivateActivityRow, now: Int64 = Int64(Date().timeIntervalSince1970)) {
        kind = switch row.kind {
        case .send, .unshield, .move, .merge, .inferred: .sent
        case .stake, .redelegate, .restake, .position: .staked
        case .unstake: .unstaked
        case .claimAnml: .claimedAnml
        case .register, .switchIdentity, .handle: .registered
        case .swap, .addLiquidity, .removeLiquidity: .swapped
        case .vote, .caretaker: .allocated
        default: .received
        }
        txHash = row.id
        let party = row.counterparty.split(separator: " ").map { w in
            w.hasPrefix("earth") ? ActivityRow.abbreviate(String(w)) : String(w)
        }.joined(separator: " ")
        counterparty = party.isEmpty && !row.kind.sent ? (row.height.map { "block \($0.formatted())" } ?? "") : party
        amount = ActivityRow.coinsLabel(row.coins)
        let when = row.time.map { (row.timeExact ? "" : "~") + ActivityRow.relative(unix: $0, now: now) } ?? ""
        timestamp = row.status == .pending ? (["pending", when].filter { !$0.isEmpty }.joined(separator: " · ")) : when
        failed = row.status == .failed
        isPrivate = true
        title = row.kind.label
        sortTime = row.time ?? 0
        hash = row.hash
        pending = row.status == .pending
    }

    /// "-1.5 ERTH, +2 ANML": every coin a row moved, signed.
    static func coinsLabel(_ coins: [ActivityCoin]) -> String {
        coins.map { c in
            let sign = c.amount < 0 ? "-" : "+"
            let v = c.amount == Int64.min ? Int64.max : abs(c.amount)
            return "\(sign)\(Figures.decimal(Double(v) / 1_000_000)) \(PrivateActivity.symbol(c.denom))"
        }.joined(separator: ", ")
    }

    /// Public rows (timestamps from the chain) and private rows (the sealed
    /// store's), one list, newest first. A tx in both is shown once, as its
    /// private row (the richer one).
    static func merge(public pub: [ActivityRow], private priv: [ActivityRow]) -> [ActivityRow] {
        let hashes = Set(priv.compactMap { $0.hash?.uppercased() })
        return (pub.filter { !hashes.contains($0.hash?.uppercased() ?? "") } + priv).sorted { $0.sortTime > $1.sortTime }
    }

    /// "earth1jtc…aar6" — enough to recognise an address you know, short
    /// enough for a row.
    static func abbreviate(_ text: String) -> String {
        text.count <= 16 ? text : "\(text.prefix(10))…\(text.suffix(4))"
    }

    /// "SetAllocations" -> "Set allocations".
    static func readable(_ text: String) -> String {
        var spaced = ""
        for character in text {
            if character.isUppercase, !spaced.isEmpty { spaced.append(" ") }
            spaced.append(character)
        }
        guard let first = spaced.first else { return spaced }
        return String(first).uppercased() + spaced.dropFirst().lowercased()
    }

    /// The signed amount, from the message's own coin field.
    ///
    /// The sign is which way the balance moved, not which way the transaction
    /// went: staking and sending both leave the spendable balance, so both are
    /// negative, while unstaking and claiming return to it. Anything that does
    /// not move the balance in a way this message can state gets no sign at
    /// all rather than a guessed one.
    static func amountLabel(_ message: [String: Any], kind: Kind) -> String {
        let coin: [String: Any]?
        if let list = message["amount"] as? [[String: Any]] {
            coin = list.first
        } else {
            coin = message["amount"] as? [String: Any]
        }
        guard let coin,
              let raw = coin["amount"] as? String,
              let units = Int64(raw)
        else { return "" }

        let denom = (coin["denom"] as? String ?? "")
        let symbol = denom.hasPrefix("u") ? String(denom.dropFirst()).uppercased() : denom.uppercased()

        let sign = switch kind {
        case .sent, .staked: "-"
        case .received, .unstaked, .claimed: "+"
        default: ""
        }
        let whole = Double(units) / 1_000_000
        return "\(sign)\(Figures.decimal(whole)) \(symbol)"
    }

    /// Relative for the recent past, absolute beyond a week.
    ///
    /// "3 days ago" is easier to place than a date while the memory is fresh,
    /// and useless once it is not — nobody counts back 43 days.
    static func relative(_ iso: String) -> String {
        guard let t = unix(iso) else { return iso }
        return relative(unix: t, now: Int64(Date().timeIntervalSince1970))
    }

    /// An RFC 3339 chain timestamp to unix seconds; nil when it does not parse.
    static func unix(_ iso: String) -> Int64? {
        let trimmed = iso.components(separatedBy: ".").first?
            .replacingOccurrences(of: "Z", with: "") ?? iso
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd'T'HH:mm:ss"
        formatter.timeZone = TimeZone(identifier: "UTC")
        formatter.locale = Locale(identifier: "en_US_POSIX")
        return formatter.date(from: trimmed).map { Int64($0.timeIntervalSince1970) }
    }

    static func relative(unix: Int64, now: Int64) -> String {
        let date = Date(timeIntervalSince1970: TimeInterval(unix))
        let minutes = Int((now - unix) / 60)
        switch minutes {
        case ..<1: return "just now"
        case ..<60: return "\(minutes)m ago"
        case ..<1440: return "\(minutes / 60)h ago"
        case ..<10080: return "\(minutes / 1440)d ago"
        default:
            let absolute = DateFormatter()
            absolute.dateFormat = "d MMM yyyy"
            return absolute.string(from: date)
        }
    }
}
