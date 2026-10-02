import Foundation

/// The backend's side of "free gas".
///
/// A new human has no ERTH and, more awkwardly, no on-chain account: an address
/// the chain has never seen cannot sign anything at all, because the ante
/// handler rejects an unknown signer before it looks at who is paying. So the
/// fee cannot simply be waived — something has to put coins there.
///
/// What earns them is personhood, judged by the chain's own rules rather than
/// anything about the device. Before registration, the backend runs the
/// chain's checks on the very `MsgRegister` about to be broadcast — proof,
/// address binding, DSC chain — and pays if the chain would accept it, once per
/// passport per month. After registration, it pays a registered human once a
/// day. Either way, nothing the app merely says is trusted.
public enum GasGrant {

    /// Which grant to ask for.
    public enum Request {
        /// Gas for this registration, as a shielded note to `pcGas`. Carries
        /// the message itself (without its fee transfer): the backend judges
        /// the proof that will actually be broadcast, not a second one, and
        /// pays only if the chain would accept it, once per passport per
        /// month. It learns that a passport, public in the registration
        /// anyway, got a gas note; the note's spend is unlinkable to it.
        case register(MsgRegisterPrivate, pcGas: Data, ciphertextGas: Data)
        /// ERTH in a transparent account, for a registered human, proved by
        /// a membership proof (GasTransparent): once a month per person, and
        /// the backend never learns which person.
        case transparent(GasTransparent.Request)

        public var path: String {
            switch self {
            case .register: "/gas/register"
            case .transparent: "/gas/transparent"
            }
        }

        /// The JSON the backend reads.
        ///
        /// For registration, the `MsgRegister` fields as they go on chain:
        /// bytes as *standard* base64 (the backend decodes them into the same
        /// message the chain will see, so url-safe would decode to different
        /// bytes or not at all), and public signals passed through as the same
        /// decimal strings, untouched — they are what the proof was made over.
        public var body: [String: Any] {
            switch self {
            case let .register(msg, pcGas, ciphertextGas):
                [
                    "proof": msg.proof.base64EncodedString(),
                    "public_signals": msg.publicSignals,
                    "signature_algorithm": msg.signatureAlgorithm,
                    "dsc_der": msg.dscDer.base64EncodedString(),
                    "idc": msg.idc.base64EncodedString(),
                    "pc_anml": msg.pcAnml.base64EncodedString(),
                    "pc_erth": msg.pcErth.base64EncodedString(),
                    "ciphertext_anml": msg.ciphertextAnml.base64EncodedString(),
                    "ciphertext_erth": msg.ciphertextErth.base64EncodedString(),
                    "affiliate": msg.affiliate,
                    "pc_gas": pcGas.base64EncodedString(),
                    "ciphertext_gas": ciphertextGas.base64EncodedString(),
                ]
            case let .transparent(r):
                GasTransparent.body(r)
            }
        }
    }

    /// What the backend said, in words it chose for a person to read.
    ///
    /// Every non-2xx carries a `message`, and for registration that includes
    /// the chain's own reason ("passport expired"). It is shown verbatim: the
    /// backend is the only side that knows why it refused.
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

    /// Asks for the grant. Either outcome means the coins are sent or on their
    /// way; the caller watches the chain for them.
    public static func request(_ request: Request) async throws -> Outcome {
        let json: JSON
        do {
            json = try await rest().postJSON(request.path, body: request.body)
        } catch EarthRest.Error.http(let status, let text) {
            let message = (try? JSONSerialization.jsonObject(with: Data(text.utf8)))
                .flatMap { JSON($0).message.string }
            throw Refused(
                status: status,
                message: message ?? "The gas service is unavailable (\(status)). Try again shortly."
            )
        }
        let hash = json.tx_hash.string
        switch json.status.string {
        case "success": return .sent(txHash: hash)
        case "pending": return .pending(txHash: hash)
        default:
            throw Refused(status: 200, message: json.message.string ?? "The gas request was not accepted.")
        }
    }
}
