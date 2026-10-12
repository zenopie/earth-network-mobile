import BigInt
import EarthCore
import Foundation

/// An option as people are shown it.
///
/// Caretaker options are permissionless: any account lists one for a fee, so
/// every description is untrusted text from a stranger. Two things follow.
/// The text is cleaned before display, so it cannot hide characters, reverse
/// itself or stack marks over its neighbours. And a listed option always
/// carries where it pays next to its name: a copy of a built-in's name is
/// then visibly not the built-in, because the built-in says so and the copy
/// shows an address.
extension Allocation.OptionInfo {
    /// The description, cleaned; "Option N" when there is none.
    var displayName: String {
        let clean = OptionText.clean(description)
        return clean.isEmpty ? "Option \(id)" : clean
    }

    /// uerth it would be paid if claimed now: its settled balance plus what it
    /// has earned since, computed the way x/allocation's settleOption does
    /// (weight x index delta / 1e18, floored), since the claim settles first.
    func claimable(rewardIndex: String) -> BigInt {
        let settled = BigInt(accumulated) ?? 0
        guard let weight = BigInt(amountAllocated), weight > 0,
              let now = BigInt(rewardIndex), let last = BigInt(lastRewardIndex), now > last else { return settled }
        return settled + weight * (now - last) / BigInt(10).power(18)
    }

    /// Built into the chain, or the short address a listed option pays.
    var source: String {
        if integrated { return "Built in" }
        return recipient.isEmpty ? "Listed" : "Pays " + OptionText.short(recipient)
    }
}

enum OptionText {
    /// Drops format and control characters (direction overrides, zero-width
    /// joiners and spaces, the like), folds line breaks to spaces, and keeps
    /// at most two combining marks on a character.
    static func clean(_ text: String) -> String {
        var out = String.UnicodeScalarView()
        var marks = 0
        for scalar in text.unicodeScalars {
            switch scalar.properties.generalCategory {
            case .format, .control, .privateUse, .surrogate, .unassigned:
                if scalar == "\n" || scalar == "\t" || scalar == "\r" { out.append(" ") }
                marks = 0
            case .nonspacingMark, .enclosingMark, .spacingMark:
                marks += 1
                if marks <= 2 { out.append(scalar) }
            default:
                marks = 0
                out.append(scalar)
            }
        }
        return String(out).split(whereSeparator: \.isWhitespace).joined(separator: " ")
    }

    /// "earth1abcd…wxyz".
    static func short(_ address: String) -> String {
        address.count > 16 ? "\(address.prefix(10))…\(address.suffix(4))" : address
    }
}
