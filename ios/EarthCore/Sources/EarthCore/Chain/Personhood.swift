import Foundation

/// x/personhood — registration and the daily ANML claim.
///
/// The one-human-one-vote stream this gates lives in x/allocation
/// (`StreamID.caretaker`). This module only decides who counts as a live human.
public enum Personhood {

    private static let secondsPerDay: Int64 = 86_400

    /// Unix seconds at the next UTC midnight, when the window reopens.
    public static func nextClaimOpensAt(now: Date = Date()) -> Int64 {
        (Int64(now.timeIntervalSince1970) / secondsPerDay + 1) * secondsPerDay
    }

    /// One issuing country's registration total. `country` is ISO 3166-1
    /// alpha-2, or "" when the Document Signer's certificate carries no country.
    public struct CountryCount: Sendable, Equatable {
        public let country: String
        public let count: Int64
    }
}

public extension EarthClient {

    /// How many humans are registered — the denominator of the human emission
    /// stream, since every registration carries the same weight.
    func registrationCount() async -> Int64 {
        guard let json = try? await rest.get(
            "/earth/personhood/v1/registration_count"
        ) else { return 0 }
        return json.count.int64(default: 0)
    }

    /// Registrations per issuing country, largest first.
    func registrationCountries() async -> [Personhood.CountryCount] {
        guard let json = try? await rest.get(
            "/earth/personhood/v1/registration_countries"
        ) else { return [] }
        return json.countries.array
            .map {
                Personhood.CountryCount(
                    country: $0.country.string(default: ""),
                    count: $0.count.int64(default: 0)
                )
            }
            .sorted { $0.count > $1.count }
    }

    /// How many humans registered with a given Document Signer (hex dsc_key).
    func registrations(byDSC dscKeyHex: String) async -> Int64 {
        let key = dscKeyHex.hasPrefix("0x") ? String(dscKeyHex.dropFirst(2)) : dscKeyHex
        guard let json = try? await rest.get(
            "/earth/personhood/v1/registrations_by_dsc/\(key)"
        ) else { return 0 }
        return json.count.int64(default: 0)
    }

    // No messages: registration and the ANML claim are private
    // (PrivacyWallet.register / claimAnml), unlinkable to any address, so
    // "am I registered" comes from the wallet's own synced identity tree.
    //
    // No unregister. The chain removed MsgUnregister — retiring a registration
    // freed its nullifier, and Register pays the registration reward to any
    // nullifier that is not already live, so leaving and returning drew on the
    // reward pool once per block. A registration now ends only by expiring, and
    // moves between wallets by registering again from the new one.
}
