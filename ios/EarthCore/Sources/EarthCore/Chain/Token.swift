import BigInt
import Foundation

/// The token registry. Ports `wallet/constants/Tokens.kt`.
///
/// Every token here is a native bank denom — this chain has no contract tokens,
/// no viewing keys, and nothing to approve before a swap.
public struct Token: Sendable, Hashable, Identifiable {
    public let denom: String
    public let symbol: String
    public let decimals: Int

    public var id: String { denom }

    public init(denom: String, symbol: String, decimals: Int = Constants.denomExponent) {
        self.denom = denom
        self.symbol = symbol
        self.decimals = decimals
    }

    public static let erth = Token(denom: "uerth", symbol: "ERTH")
    public static let anml = Token(denom: "uanml", symbol: "ANML")
    public static let usdc = Token(denom: "uusdc", symbol: "USDC")
    public static let atom = Token(denom: "uatom", symbol: "ATOM")

    public static let all: [Token] = [.erth, .anml, .usdc, .atom]

    /// By symbol or by base denom, either case.
    public static func named(_ identifier: String) -> Token? {
        let lower = identifier.lowercased()
        return all.first { $0.symbol.lowercased() == lower || $0.denom == identifier }
    }

    /// A denom the registry does not know — an LP share, or a token added to
    /// the chain after this build. Shown by its denom rather than hidden: a
    /// balance the wallet will not name is worse than an ugly one.
    public static func unknown(denom: String) -> Token {
        Token(denom: denom, symbol: denom.hasPrefix("dexlp/")
            ? "LP \(denom.dropFirst("dexlp/".count))"
            : denom.uppercased())
    }

    public func format(_ baseUnits: BigInt) -> String {
        Amounts.fromBaseUnits(baseUnits, exponent: decimals)
    }

    public func format(_ baseUnits: String) -> String {
        BigInt(baseUnits).map(format) ?? baseUnits
    }

    public func parse(_ text: String) -> BigInt? {
        Amounts.toBaseUnits(text, exponent: decimals)
    }
}
