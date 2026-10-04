import Foundation

/// Native x/staking and x/distribution.
public enum Staking {

    public struct Validator: Sendable, Equatable, Identifiable {
        /// The operator address. A validator can change its moniker, so the
        /// list would reorder under a name-keyed identity.
        public var id: String { operatorAddress }

        public let operatorAddress: String
        public let moniker: String
        public let tokens: String
        /// Commission as a fraction — 0.10 is 10%.
        public let commission: Double

        public init(operatorAddress: String, moniker: String, tokens: String, commission: Double) {
            self.operatorAddress = operatorAddress; self.moniker = moniker; self.tokens = tokens; self.commission = commission
        }
    }

    public struct Delegation: Sendable, Equatable, Identifiable {
        public var id: String { validator }

        public let validator: String
        public let amount: String
    }

    /// One unbonding entry. Funds return to the balance on their own at
    /// `completionTime`.
    public struct UnbondingEntry: Sendable, Equatable {
        public let validator: String
        public let balance: String
        public let completionTime: String
    }
}

public extension EarthClient {

    func bondedValidators() async -> [Staking.Validator] {
        guard let json = try? await rest.get(
            "/cosmos/staking/v1beta1/validators?status=BOND_STATUS_BONDED&pagination.limit=200"
        ) else { return [] }
        return json.validators.array.compactMap { v in
            guard let op = v.operator_address.string else { return nil }
            return Staking.Validator(
                operatorAddress: op,
                moniker: v.description.moniker.string(default: ""),
                tokens: v.tokens.string(default: "0"),
                commission: v.commission.commission_rates.rate.double(default: 0)
            )
        }
    }

    func delegations(_ delegator: String) async -> [Staking.Delegation] {
        guard let json = try? await rest.get("/cosmos/staking/v1beta1/delegations/\(delegator)")
        else { return [] }
        return json.delegation_responses.array.compactMap { d in
            guard let validator = d.delegation.validator_address.string else { return nil }
            return Staking.Delegation(validator: validator, amount: d.balance.amount.string(default: "0"))
        }
    }

    /// Total uerth bonded across the network.
    func totalBonded() async -> String {
        guard let json = try? await rest.get("/cosmos/staking/v1beta1/pool") else { return "0" }
        return json.pool.bonded_tokens.string(default: "0")
    }

    func unbondingDelegations(_ delegator: String) async -> [Staking.UnbondingEntry] {
        guard let json = try? await rest.get(
            "/cosmos/staking/v1beta1/delegators/\(delegator)/unbonding_delegations"
        ) else { return [] }
        return json.unbonding_responses.array.flatMap { response -> [Staking.UnbondingEntry] in
            guard let validator = response.validator_address.string else { return [] }
            return response.entries.array.map { e in
                Staking.UnbondingEntry(
                    validator: validator,
                    balance: e.balance.string(default: "0"),
                    completionTime: e.completion_time.string(default: "")
                )
            }
        }
    }

    /// Total pending uerth rewards across all validators.
    ///
    /// Rewards are DecCoins and may be fractional, so the fraction is dropped
    /// rather than rounded — the chain pays out the truncated amount.
    func totalRewards(_ delegator: String) async -> String {
        guard let json = try? await rest.get(
            "/cosmos/distribution/v1beta1/delegators/\(delegator)/rewards"
        ) else { return "0" }
        for coin in json.total.array where coin.denom.string == Constants.gasDenom {
            let amount = coin.amount.string(default: "0")
            return String(amount.prefix(while: { $0 != "." }))
        }
        return "0"
    }

    // --- messages ---

    func msgWithdrawReward(delegator: String, validator: String) -> ProtoAny {
        Msg.WithdrawDelegatorReward(delegator: delegator, validator: validator)
            .asAny(typeURL: Msg.WithdrawDelegatorReward.typeURL)
    }
}
