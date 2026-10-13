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
}

public extension EarthClient {

    /// How many humans are registered — the denominator of the human emission
    /// stream, since every registration carries the same weight.
    func registrationCount() async -> Int64 {
        (try? await fetchRegistrationCount()) ?? 0
    }

    /// Throws when the node could not be asked, where `registrationCount`
    /// answers 0: a screen must not show a failed read as nobody registered.
    func fetchRegistrationCount() async throws -> Int64 {
        try await rest.get("/earth/personhood/v1/registration_count").count.int64(default: 0)
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
