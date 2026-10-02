import Foundation

/// Transparent ERTH for fees, for a registered human. Ports
/// `privacy/GasTransparent.kt`.
///
/// Private txs pay from notes; a transparent account (an LP, IBC, a contract
/// call) needs ERTH in the account. The gas backend sends a little, once a
/// month per person, to an address a live registered human names — proved
/// with a membership proof, so it never learns which human:
///
///   scope            = gasScope(YYYYMM)                          one grant per month
///   signal           = gasTransparentSignal(chain_id, address)   the grant cannot be redirected
///   excluded_dsc     = excluded_country = 0
///   max_activation   = now less a margin, rounded down to the hour
///
/// The backend checks it with `earthd gas-check membership` and pays the
/// address by bank send.
public enum GasTransparent {
    /// What the backend takes: the proof, its root and nullifier, and the statement's free values.
    public struct Request: Sendable {
        public let address: String
        public let proof: Data
        public let root: Fr
        public let nullifier: Fr
        public let maxActivation: UInt64
        public let month: UInt64
    }

    /// `now`'s UTC month as YYYYMM.
    public static func month(_ now: Int64) -> UInt64 {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "UTC")!
        let c = cal.dateComponents([.year, .month], from: Date(timeIntervalSince1970: TimeInterval(now)))
        return UInt64(c.year! * 100 + c.month!)
    }

    /// max_activation: before the backend's view of the chain (the last block
    /// may trail this clock), rounded to the hour so it says nothing about
    /// when the proof was made beyond that hour.
    public static func maxActivation(_ now: Int64) -> UInt64 {
        UInt64(max(0, (now - PrivacyWallet.clockMargin) / 3600 * 3600))
    }

    /// The membership witness for a grant to `address` (bech32), against the wallet's synced identity tree.
    public static func witness(wallet: PrivacyWallet, address: String, now: Int64) async throws -> MembershipWitness {
        try await wallet.gasWitness(
            scope: PrivacyHash.gasScope(yyyymm: month(now)),
            signal: PrivacyHash.gasTransparentSignal(chainID: wallet.chainID, address: try PrivateMsgs.addressBytes(address)),
            maxActivation: maxActivation(now)
        )
    }

    /// Syncs, proves and returns the request for a grant to `address`.
    public static func request(
        wallet: PrivacyWallet, address: String, prove: (MembershipWitness) async throws -> Data,
        now: Int64 = Int64(Date().timeIntervalSince1970)
    ) async throws -> Request {
        try await wallet.sync()
        let w = try await witness(wallet: wallet, address: address, now: now)
        return Request(address: address, proof: try await prove(w), root: w.root, nullifier: w.nullifier,
                       maxActivation: w.maxActivation, month: month(now))
    }

    /// The /gas/transparent body: bytes as standard base64.
    public static func body(_ r: Request) -> [String: Any] {
        [
            "address": r.address,
            "proof": r.proof.base64EncodedString(),
            "root": r.root.bytes.base64EncodedString(),
            "nullifier": r.nullifier.bytes.base64EncodedString(),
            "max_activation": r.maxActivation,
            "month": r.month,
        ]
    }
}
