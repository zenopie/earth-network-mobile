import Foundation

/// x/bank queries and messages.
public extension EarthClient {

    /// Every balance as denom -> base-unit amount. Empty when the account does
    /// not exist yet, which is the normal state for a user before their first
    /// receipt.
    func balances(_ address: String) async -> [String: String] {
        guard let json = try? await rest.get("/cosmos/bank/v1beta1/balances/\(address)") else {
            return [:]
        }
        var out = [String: String]()
        for coin in json.balances.array {
            guard let denom = coin.denom.string else { continue }
            out[denom] = coin.amount.string(default: "0")
        }
        return out
    }

    func balance(_ address: String, denom: String) async -> String {
        await balances(address)[denom] ?? "0"
    }

    /// Total supply of one denom. The query takes the denom as a parameter
    /// rather than a path segment, which is what makes a slashed denom such as
    /// `dexlp/1` work at all.
    func supply(denom: String) async -> String {
        let escaped = denom.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? denom
        guard let json = try? await rest.get("/cosmos/bank/v1beta1/supply/by_denom?denom=\(escaped)")
        else { return "0" }
        return json.amount.amount.string(default: "0")
    }

    func msgSend(from: String, to: String, denom: String, amount: String) -> ProtoAny {
        Msg.Send(from: from, to: to, amount: [Coin(denom: denom, amount: amount)])
            .asAny(typeURL: Msg.Send.typeURL)
    }
}
