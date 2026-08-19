import EarthCore
import Foundation

/// A chain transaction, resolved into a row a person can read.
///
/// Ports `ui/compose/ActivityMapper.kt`. Anything unrecognised falls through
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

    var id: String { txHash }

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
        let trimmed = iso.components(separatedBy: ".").first?
            .replacingOccurrences(of: "Z", with: "") ?? iso
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd'T'HH:mm:ss"
        formatter.timeZone = TimeZone(identifier: "UTC")
        formatter.locale = Locale(identifier: "en_US_POSIX")
        guard let date = formatter.date(from: trimmed) else { return iso }

        let minutes = Int(Date().timeIntervalSince(date) / 60)
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
