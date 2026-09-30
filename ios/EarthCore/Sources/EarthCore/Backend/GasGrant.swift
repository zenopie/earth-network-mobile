import Foundation

/// The backend's side of "free gas": a challenge, and a grant against an
/// App Attest attestation.
///
/// A new human has no ERTH and, more awkwardly, no on-chain account: an address
/// the chain has never seen cannot sign anything at all, because the ante
/// handler rejects an unknown signer before it looks at who is paying. So the
/// fee cannot simply be waived — something has to put coins there. What buys
/// them is Apple's word that the request came from a genuine copy of this app
/// on a real device; the backend verifies that, rate-limits per device and
/// address, and sends the dust.
///
/// Only the HTTP and the hash live here. The attestation itself is DeviceCheck,
/// which is an app-bundle concern and sits in `EarthUI` — this half stays
/// runnable under plain SwiftPM so `corecheck` can pin the hash.
public enum GasGrant {

    /// What the backend said, in words it chose for a person to read.
    ///
    /// Every non-2xx carries a `message` ("challenge expired", "limit
    /// reached"), and that is shown verbatim: the backend is the only side that
    /// knows why it refused.
    public struct Refused: Swift.Error, Sendable {
        public let status: Int
        public let message: String
    }

    public enum Outcome: Sendable, Equatable {
        /// The send is in a block.
        case sent(txHash: String?)
        /// The send was broadcast but not yet seen in a block.
        case pending(txHash: String?)
    }

    /// The backend, through the same client the LCD uses: one timeout policy
    /// and one non-2xx guard. The parameter is named for the LCD; here it is
    /// just the base URL.
    static func rest() -> EarthRest {
        EarthRest(lcd: Constants.backendBaseURL, rpc: nil)
    }

    /// Asks for a single-use challenge bound to `address`.
    ///
    /// Returned as the backend sent it — base64url, unpadded — because it goes
    /// back verbatim in `submit`, and re-encoding it risks a padding or
    /// alphabet mismatch the backend would read as a different challenge.
    public static func challenge(for address: String) async throws -> String {
        let json = try await call("/gas/challenge", body: ["address": address])
        guard let challenge = json.challenge.string, !challenge.isEmpty else {
            throw EarthRest.Error.missing("challenge")
        }
        return challenge
    }

    /// The `clientDataHash` handed to `attestKey`.
    ///
    /// SHA256 over the challenge's raw bytes followed by the address. The
    /// address is in the hash, not just in the request body, so an attestation
    /// captured in transit cannot be replayed to fund a different address: the
    /// backend recomputes this from the body and Apple's signature covers it.
    ///
    /// Nil if the challenge is not base64url.
    public static func clientDataHash(challenge: String, address: String) -> Data? {
        guard let raw = Data(base64URL: challenge) else { return nil }
        return Hashes.sha256(raw + Data(address.utf8))
    }

    /// Hands the attestation over and reports whether the grant went out.
    ///
    /// `keyId` goes exactly as Apple returned it: it is already base64 of the
    /// key's hash, and the backend checks it against the attested public key.
    /// The attestation is standard base64, not url-safe — it is a CBOR blob,
    /// not a token, and that is the encoding the backend decodes.
    public static func submit(
        address: String,
        challenge: String,
        keyId: String,
        attestation: Data
    ) async throws -> Outcome {
        let json = try await call("/gas/ios", body: [
            "address": address,
            "challenge": challenge,
            "key_id": keyId,
            "attestation": attestation.base64EncodedString(),
        ])
        let hash = json.tx_hash.string
        switch json.status.string {
        case "success": return .sent(txHash: hash)
        case "pending": return .pending(txHash: hash)
        default:
            throw Refused(status: 200, message: json.message.string ?? "The gas request was not accepted.")
        }
    }

    /// Posts, and turns a non-2xx into `Refused` with the backend's message.
    private static func call(_ path: String, body: [String: Any]) async throws -> JSON {
        do {
            return try await rest().postJSON(path, body: body)
        } catch EarthRest.Error.http(let status, let text) {
            let message = (try? JSONSerialization.jsonObject(with: Data(text.utf8)))
                .flatMap { JSON($0).message.string }
            throw Refused(
                status: status,
                message: message ?? "The gas service is unavailable (\(status)). Try again shortly."
            )
        }
    }
}

public extension Data {
    /// RFC 4648 §5 base64url, padding optional.
    init?(base64URL text: String) {
        var standard = text
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        let remainder = standard.count % 4
        if remainder == 1 { return nil }
        if remainder > 0 { standard += String(repeating: "=", count: 4 - remainder) }
        self.init(base64Encoded: standard)
    }
}
