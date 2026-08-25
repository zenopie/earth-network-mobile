import BigInt
import Foundation

/// The fee a transaction must offer, derived from its gas limit.
///
/// A Cosmos node rejects a transaction whose fee is below
/// `ceil(gas_limit * minimum-gas-prices)`, which makes the fee a function of
/// the gas limit rather than a flat amount. Both apps had it as a flat "2000" —
/// the fee for the 400,000-gas default — while `Personhood.registerGasLimit`
/// had been raised to 3,000,000 for headroom. Every registration was rejected
/// before it ran:
///
///     insufficient fees; got: 2000uerth required: 15000uerth
///
/// Gas headroom is not free. It costs fee, proportionally, whether or not the
/// gas is used: the fee is what you offer, and an unused remainder is not
/// refunded. Raising a gas limit without raising the fee breaks the
/// transaction; raising both makes it more expensive.
///
/// The price comes from the node rather than a constant, because
/// `minimum-gas-prices` is per-node configuration and not a chain parameter —
/// an operator changes it on a restart, with no governance vote and no signal
/// to clients.
public enum Fees {

    /// Used until ``prime(rest:)`` succeeds, and if it never does.
    ///
    /// Matches what `deploy/akash/deploy.yaml` sets on the validator
    /// (`MIN_GAS_PRICES=0.005uerth`). Being wrong here is recoverable — the
    /// node names the required amount in its rejection — whereas refusing to
    /// build a transaction at all is not.
    static let fallbackPrice = Decimal(string: "0.005")!

    private static let lock = NSLock()
    private static var cached: Decimal?

    /// The fee, in uerth, for `gas`.
    ///
    /// Synchronous and non-throwing on purpose: it is read while building a
    /// confirmation sheet and while working out a spendable balance, neither of
    /// which can await a network round trip. ``prime(rest:)`` fills the cache;
    /// until then this answers with ``fallbackPrice``.
    public static func forGas(_ gas: UInt64) -> String {
        var raw = (readCache() ?? fallbackPrice) * Decimal(gas)
        var rounded = Decimal()
        // Rounded up: the node compares against a ceiling, so truncating
        // produces a fee one uerth short and a rejection that reads as a
        // rounding mystery.
        NSDecimalRound(&rounded, &raw, 0, .up)
        return NSDecimalNumber(decimal: rounded).stringValue
    }

    /// As ``forGas(_:)``, for callers that want to compare against a balance.
    public static func forGasValue(_ gas: UInt64) -> BigInt {
        BigInt(forGas(gas)) ?? BigInt(0)
    }

    /// Reads `minimum-gas-prices` from the node and caches it for the process.
    ///
    /// Safe to call repeatedly and cheap once primed: the value cannot change
    /// under a running node. Never throws — a node that cannot be reached at
    /// launch must not stop the app from building transactions, and the
    /// fallback is the right answer on this chain anyway.
    public static func prime(rest: EarthRest = EarthRest()) async {
        if readCache() != nil { return }
        guard let price = try? await fetch(rest: rest) else { return }
        writeCache(price)
    }

    // Locking lives in these two, deliberately: NSLock is unavailable from an
    // async context (a hard error in the Swift 6 language mode), and holding a
    // lock across an await is how you deadlock anyway. Neither of these awaits.
    private static func readCache() -> Decimal? {
        lock.lock()
        defer { lock.unlock() }
        return cached
    }

    private static func writeCache(_ price: Decimal) {
        lock.lock()
        defer { lock.unlock() }
        cached = price
    }

    /// The endpoint answers `"0.005000000000000000uerth"` — a decimal with the
    /// denom appended, so the denom comes off before parsing. Only uerth is
    /// accepted: this app pays fees in nothing else, and a node quoting another
    /// denom is a misconfiguration the fallback handles better than a silently
    /// wrong number would.
    private static func fetch(rest: EarthRest) async throws -> Decimal? {
        let json = try await rest.get("/cosmos/base/node/v1beta1/config")
        guard let raw = json["minimum_gas_price"].string, raw.hasSuffix("uerth") else {
            return nil
        }
        let digits = raw.prefix { $0.isNumber || $0 == "." }
        guard !digits.isEmpty, let price = Decimal(string: String(digits)) else { return nil }
        return price
    }
}
