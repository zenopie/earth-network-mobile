import Foundation

/// x/personhood — registration and the daily ANML claim.
///
/// The one-human-one-vote stream this gates lives in x/allocation
/// (`StreamID.caretaker`). This module only decides who counts as a live human.
public enum Personhood {

    private static let secondsPerDay: Int64 = 86_400

    public struct RegistrationStatus: Sendable, Equatable {
        public let registered: Bool
        public let expired: Bool
        /// Unix seconds; 0 if never claimed.
        public let lastAnmlClaim: Int64

        public static let none = RegistrationStatus(registered: false, expired: false, lastAnmlClaim: 0)
    }

    /// ANML is claimable once per UTC day.
    ///
    /// Compared as day numbers, matching the chain. A rolling
    /// `now - last >= 86400` agrees only while the chain stores a
    /// midnight-truncated timestamp; against a real claim time it under-reports
    /// by up to a day, hiding a claim that is actually available.
    public static func isAnmlClaimable(_ status: RegistrationStatus, now: Date = Date()) -> Bool {
        guard status.registered else { return false }
        let nowSec = Int64(now.timeIntervalSince1970)
        return nowSec / secondsPerDay != status.lastAnmlClaim / secondsPerDay
    }

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

    /// Registration's gas limit and fee, defined beside the message they pay for.
    ///
    /// MsgRegister verifies an UltraHonk proof on-chain and is the most
    /// expensive message the app sends. A fresh account pays more than a used
    /// one — the ante handler stores its public key on the first transaction,
    /// measured at 400324 gas against Android's old 400000 default. That is
    /// precisely the case that matters, since a new human's first transaction
    /// is always this one.
    ///
    /// Generous rather than tuned. Note that headroom is NOT free: the fee is
    /// `ceil(gas x minimum-gas-prices)`, so this limit costs 15,000 uerth at
    /// 0.005uerth whether or not the gas is used. The flat "2000" that used to
    /// sit here was the fee for 400,000 gas, and every registration was
    /// rejected with "insufficient fees; got: 2000uerth required: 15000uerth".
    /// An under-estimate still burns both the fee and the ad view that paid for
    /// it, so the headroom stays — but it is a priced decision, not a free one.
    public static let registerGasLimit: UInt64 = 3_000_000
    public static var registerFeeUerth: String { Fees.forGas(registerGasLimit) }
}

public extension EarthClient {

    func registrationStatus(_ address: String) async -> Personhood.RegistrationStatus {
        guard let json = try? await rest.get(
            "/earth/personhood/v1/registration/\(address)"
        ) else { return .none }
        return Personhood.RegistrationStatus(
            registered: json.registered.bool ?? false,
            expired: json.expired.bool ?? false,
            lastAnmlClaim: json.registration.last_anml_claim.int64(default: 0)
        )
    }

    func isRegistered(_ address: String) async -> Bool {
        await registrationStatus(address).registered
    }

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

    // --- messages ---

    /// Proof-of-personhood registration.
    ///
    /// `proof` is the Barretenberg UltraHonk proof bytes; `publicSignals` are
    /// the circuit's public signals as decimal strings — `[current_date,
    /// nullifier, dsc_key]` for `lean_poa`; `signatureAlgorithm` selects the
    /// on-chain verifying key; `dscDer` is the Document Signer certificate the
    /// chain checks against its CSCA trust store and binds to the proof's
    /// `dsc_key` output.
    func msgRegister(
        creator: String,
        proof: Data,
        publicSignals: [String],
        signatureAlgorithm: String,
        affiliate: String?,
        dscDER: Data
    ) -> ProtoAny {
        Msg.Register(
            creator: creator,
            proof: proof,
            publicSignals: publicSignals,
            affiliate: affiliate ?? "",
            signatureAlgorithm: signatureAlgorithm,
            dscDer: dscDER
        ).asAny(typeURL: Msg.Register.typeURL)
    }

    @discardableResult
    func register(
        key: EarthKey,
        proof: Data,
        publicSignals: [String],
        signatureAlgorithm: String,
        affiliate: String?,
        dscDER: Data
    ) async throws -> String {
        try await broadcast(
            [msgRegister(
                creator: key.address,
                proof: proof,
                publicSignals: publicSignals,
                signatureAlgorithm: signatureAlgorithm,
                affiliate: affiliate,
                dscDER: dscDER
            )],
            key: key,
            gasLimit: Personhood.registerGasLimit,
            feeUerth: Personhood.registerFeeUerth
        )
    }

    func msgClaimAnml(creator: String) -> ProtoAny {
        Msg.ClaimAnml(creator: creator).asAny(typeURL: Msg.ClaimAnml.typeURL)
    }

    @discardableResult
    func claimAnml(key: EarthKey) async throws -> String {
        try await broadcast([msgClaimAnml(creator: key.address)], key: key)
    }

    // No unregister. The chain removed MsgUnregister — retiring a registration
    // freed its nullifier, and Register pays the registration reward to any
    // nullifier that is not already live, so leaving and returning drew on the
    // reward pool once per block. A registration now ends only by expiring, and
    // moves between wallets by registering again from the new one.
}
