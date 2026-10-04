import Foundation

/// The backend's side of "free gas".
///
/// A new human holds no ERTH, and a registration's fee is paid from a
/// shielded note, so something has to put one there. What earns it is
/// personhood, judged by the chain's own rules rather than anything about the
/// device: the backend runs the chain's checks on the very `MsgRegister` about
/// to be broadcast — proof, address binding, DSC chain — and pays a gas note
/// if the chain would accept it, once per passport per month. Nothing the app
/// merely says is trusted.
public enum GasGrant {

    /// Which grant to ask for.
    public enum Request {
        /// Gas for this registration, as a shielded note to `pcGas` with
        /// `ciphertextGas`, its required 177-byte v2 ciphertext (the app finds
        /// the note by it). It is the only grant. Carries
        /// the message itself (without its fee bundle): the backend judges
        /// the proof that will actually be broadcast, not a second one, and
        /// pays only if the chain would accept it, once per passport per
        /// month. It learns that a passport, public in the registration
        /// anyway, got a gas note; the note's spend is unlinkable to it.
        case register(MsgRegisterPrivate, pcGas: Data, ciphertextGas: Data)

        public var path: String {
            switch self {
            case .register: "/gas/register"
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
                    // The referrer by handle alone ("" for none); the backend refuses
                    // affiliate_pc / affiliate_ciphertext, which MsgRegister lost.
                    "affiliate_handle": msg.affiliateHandle,
                    "pc_gas": pcGas.base64EncodedString(),
                    "ciphertext_gas": ciphertextGas.base64EncodedString(),
                ]
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

        /// The chain's own reason, in plain words where the app has them (1127, 1113).
        public init(status: Int, message: String) { self.status = status; self.message = ChainErrors.explain(text: message) ?? message }
    }

    public enum Outcome: Sendable, Equatable {
        /// The send is in a block.
        case sent(txHash: String?)
        /// The send was broadcast but not yet seen in a block.
        case pending(txHash: String?)
    }

    /// What talks to the backend: (status, body) for a GET or a JSON POST. Tests pass a fake server.
    public struct Transport: Sendable {
        public let get: @Sendable (String) async throws -> (Int, Data)
        public let post: @Sendable (String, [String: Any]) async throws -> (Int, Data)
        public init(get: @escaping @Sendable (String) async throws -> (Int, Data),
                    post: @escaping @Sendable (String, [String: Any]) async throws -> (Int, Data)) {
            self.get = get; self.post = post
        }

        /// The backend over HTTP, bounded like every other response.
        public static let backend = Transport(
            get: { path in try await send(URLRequest(url: try url(path))) },
            post: { path, body in
                var r = URLRequest(url: try url(path))
                r.httpMethod = "POST"
                r.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
                r.httpBody = try JSONSerialization.data(withJSONObject: body)
                return try await send(r)
            })

        private static let session: URLSession = {
            let c = URLSessionConfiguration.ephemeral
            c.timeoutIntervalForRequest = 30
            c.timeoutIntervalForResource = 60
            return EarthRest.session(c)
        }()

        private static func url(_ path: String) throws -> URL {
            guard let u = URL(string: Constants.backendBaseURL.absoluteString.replacingOccurrences(of: "/$", with: "", options: .regularExpression) + path) else {
                throw EarthRest.Error.missing(path)
            }
            return u
        }

        private static func send(_ r: URLRequest) async throws -> (Int, Data) {
            let (d, status) = try await EarthRest.boundedData(session, r)
            return (status, d)
        }
    }

    /// A stamp the server gave back (503, 429), for the next try of the same registration.
    private static let keptLock = NSLock()
    nonisolated(unsafe) private static var kept: (key: String, stamp: GasPow.Stamp)?
    private static let maxPowRounds = 4
    /// How long a kept stamp is reused (the server takes ts within 600 s).
    private static let stampReuseSeconds: Int64 = 300

    private static func takeKept(_ key: String, now: Int64) -> GasPow.Stamp? {
        keptLock.lock(); defer { keptLock.unlock() }
        defer { kept = nil }
        guard let k = kept, k.key == key, now - k.stamp.ts < stampReuseSeconds else { return nil }
        return k.stamp
    }

    private static func keep(_ key: String, _ s: GasPow.Stamp?) { keptLock.lock(); kept = s.map { (key, $0) }; keptLock.unlock() }

    /// Asks for the grant. Either outcome means the coins are sent or on their
    /// way; the caller watches the chain for them.
    ///
    /// The request may need a proof of work (`GasPow`): GET /gas/pow says
    /// how many bits admit a request now; a stamp at that difficulty goes with
    /// the request; a 428 names the bits that request needs and a fresh stamp
    /// is made at them. A stamp is kept for the next try only after a 503 or a
    /// 429 (the server gave it back); a 403 or anything else drops it. The work
    /// runs off the caller's thread, cancellably, reporting `progress` (0...1).
    public static func request(_ request: Request, transport: Transport = .backend,
                               clock: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970) },
                               progress: @escaping @Sendable (Double) -> Void = { _ in }) async throws -> Outcome {
        var body = request.body
        guard case let .register(msg, _, _) = request, msg.publicSignals.count > 2 else {
            throw Refused(status: 0, message: "The registration is incomplete.")
        }
        let binding = msg.publicSignals[1], nullifier = msg.publicSignals[2]
        let key = "\(binding):\(nullifier)"
        func stamp(_ bits: Int) async throws -> GasPow.Stamp? {
            guard bits > 0 else { return nil }
            let ts = clock()
            // Off the caller's thread, but cancelled with it: a
            // detached task does not inherit the caller's cancellation.
            let work = Task.detached(priority: .userInitiated) {
                try GasPow.solve(ts: ts, binding: binding, nullifier: nullifier, bits: bits, progress: progress)
            }
            return try await withTaskCancellationHandler { try await work.value } onCancel: { work.cancel() }
        }
        var current = takeKept(key, now: clock())
        if current == nil { current = try await stamp(await powBits(transport)) }
        for _ in 0 ..< maxPowRounds {
            if let s = current { body["pow"] = ["ts": s.ts, "nonce": s.nonce] } else { body["pow"] = nil }
            let (status, data) = try await transport.post(request.path, body)
            let json = (try? JSONSerialization.jsonObject(with: data)).map(JSON.init)
            switch status {
            case 200 ... 299:
                let hash = json?.tx_hash.string
                switch json?.status.string {
                case "success": return .sent(txHash: hash)
                case "pending": return .pending(txHash: hash)
                default: throw Refused(status: status, message: json?.message.string ?? "The gas request was not accepted.")
                }
            case 428:
                // A stamp is needed, or this one was stale or used: a fresh one at the bits asked.
                guard let bits = json?.pow.bits.int64.map(Int.init), (1 ... GasPow.maxBits).contains(bits) else {
                    throw Refused(status: status, message: json?.message.string ?? "The gas service asked for work this app cannot do.")
                }
                current = try await stamp(bits)
            default:
                // 503, 429: the server gave the stamp back; the next try may use it.
                if status == 503 || status == 429 { keep(key, current) }
                throw Refused(status: status, message: json?.message.string ?? "The gas service is unavailable (\(status)). Try again shortly.")
            }
        }
        throw Refused(status: 428, message: "The gas service kept asking for more work. Try again shortly.")
    }

    /// GET /gas/pow's bits: what admits a request now (0 when it cannot say: the POST's 428 will).
    static func powBits(_ t: Transport) async -> Int {
        guard let (status, data) = try? await t.get("/gas/pow"), (200 ... 299).contains(status),
              let o = try? JSONSerialization.jsonObject(with: data) else { return 0 }
        let j = JSON(o)
        guard j.version.string == GasPow.version, let b = j.bits.int64 else { return 0 }
        // More than the wallet works for: no stamp now, and the POST's 428 is refused.
        return (0 ... Int64(GasPow.maxBits)).contains(b) ? Int(b) : 0
    }
}
