import Foundation
import Swoir
import Swoirenberg
import ProverGateCore

/// Barretenberg's SRS, provisioned once per process.
///
/// barretenberg honours only the **first** SRS initialization of a process: a
/// later, larger request is silently not met, and a proof that needs it fails.
/// The passport circuits run to ~425k gates (brainpool512) and the privacy
/// circuits to ~10k (stake, the largest of action, stake and membership), so whichever proves first decides for the rest of the
/// launch. Everything that proves goes through here, so the decision is made
/// once, knowingly.
public enum SRS {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var provisioned = false

    public static var isProvisioned: Bool {
        lock.lock(); defer { lock.unlock() }
        return provisioned
    }

    /// Records that a circuit's `setupSrs` ran (the first one is the one that counts).
    static func markProvisioned() {
        lock.lock(); provisioned = true; lock.unlock()
    }

    /// Provisions the SRS for `manifest` (sized from its own bytecode) if
    /// nothing has yet. A no-op afterwards. `srsPath`: a local `.dat` prefix
    /// of the transcript that covers the circuit (noir_rs aborts on a short
    /// file, so only pass one known to cover it); nil downloads it.
    public static func reserve(forManifest manifest: Data, srsPath: String? = nil) throws {
        guard !isProvisioned else { return }
        let circuit = try Swoir(Swoirenberg.self).createCircuit(manifest: manifest, size: nil)
        try circuit.setupSrs(srs_path: srsPath)
        markProvisioned()
    }

    public enum Failure: Error, CustomStringConvertible {
        case shortFile(String)
        public var description: String {
            switch self { case let .shortFile(p): "the SRS file \((p as NSString).lastPathComponent) is too short" }
        }
    }

    /// Provisions `size` (a power of two: size + 1 points) from a local `.dat`
    /// prefix of the transcript (audit 3: proving a private tx never fetches
    /// the SRS). The file is checked to hold every point first: noir_rs
    /// slices it unchecked, and a short file is a Rust panic.
    public static func reserve(points size: UInt32, datPath: String) throws {
        guard !isProvisioned else { return }
        let have = ((try? FileManager.default.attributesOfItem(atPath: datPath))?[.size] as? NSNumber)?.uint64Value ?? 0
        guard have >= (UInt64(size) + 1) * 64 else { throw Failure.shortFile(datPath) }
        _ = try Swoirenberg.setup_srs(circuit_size: size, srs_path: datPath)
        markProvisioned()
    }

    /// The privacy circuits' SRS: 2^15 (32,769 points), every privacy circuit
    /// fits (stake, the largest, is 9,647 gates).
    public static let privacyPoints: UInt32 = 1 << 15
}

/// On-device proofs of the privacy circuits (circuits/membership,
/// circuits/action, circuits/stake), through the same Swoirenberg build as
/// `LeanPoaProver` — bb v5.0.0, in lockstep with the chain's verifier; never
/// float that pin. Ports `privacy/prove/PrivacyProver.kt`.
///
/// The chain never takes public inputs from the tx: it recomputes them from
/// the msg (zk/orchard Bundle.PublicInputs, StakeProof.PublicInputs,
/// personhood MembershipPublicInputs) and verifies the proof body against
/// them. So the public inputs bb
/// returns ahead of the body are split off and checked against the witness's
/// own, and only the body is sent.
public final class PrivacyCircuitProver: @unchecked Sendable {
    public enum Kind: String, CaseIterable, Sendable {
        case membership, action, stake

        public var publicInputs: Int {
            switch self {
            case .membership: 7
            case .action: 6
            case .stake: 11
            }
        }

        /// The largest privacy circuit (gates: membership 5,645, action
        /// 8,120, stake 9,647): its SRS holds the other two.
        public static let largest: Kind = .stake
    }

    public enum Failure: Error, CustomStringConvertible {
        case proofTooShort(Int)
        case publicInputsDiffer(Kind)
        case wrongBodySize(Int)

        public var description: String {
            switch self {
            case let .proofTooShort(n): "proof too short (\(n) bytes)"
            case let .publicInputsDiffer(k): "the \(k.rawValue) proof's public inputs are not the witness's"
            case let .wrongBodySize(n): "unexpected proof body size \(n)"
            }
        }
    }

    /// Expected body size of a bb v5.0.0 UltraHonk proof, whatever the circuit.
    public static let proofBytes = 14_656

    private let manifests: [Kind: Data]
    private let reserve: () -> (manifest: Data, srsPath: String?)?
    private let privacySRS: String?
    private let lock = NSLock()
    private var loaded: [Kind: (circuit: Circuit, vk: Data)] = [:]

    /// Last prove times in milliseconds, for display.
    public private(set) var lastMillis: [Kind: Int] = [:]

    /// - Parameters:
    ///   - manifests: the compiled circuits (the app bundle's circuits/membership.json, action.json, stake.json).
    ///   - reserve: the manifest whose SRS to provision first when nothing has
    ///     been yet — the largest passport circuit while a registration may
    ///     still follow in this launch (with the local transcript prefix
    ///     that covers it, when it has been fetched), nil when the privacy
    ///     circuits' own small SRS is enough.
    ///   - privacySRS: the bundled `.dat` prefix (32,769 points) every privacy
    ///     circuit is set up from, so a private proof never fetches anything;
    ///     nil (tests on a Mac): sized from the stake circuit and downloaded.
    public init(manifests: [Kind: Data], privacySRS: String? = nil, reserve: @escaping () -> (manifest: Data, srsPath: String?)? = { nil }) {
        self.manifests = manifests
        self.privacySRS = privacySRS
        self.reserve = reserve
    }

    private func load(_ k: Kind) throws -> (circuit: Circuit, vk: Data) {
        lock.lock(); defer { lock.unlock() }
        if let l = loaded[k] { return l }
        // At least the largest privacy circuit's SRS (stake), whichever
        // circuit proves first: a membership- or action-sized SRS (a gas
        // grant, a send) cannot be grown for a stake proof later in the same
        // process (bb: errorSettingUpSRS).
        if !SRS.isProvisioned {
            if let big = reserve() {
                try SRS.reserve(forManifest: big.manifest, srsPath: big.srsPath)
            } else if let p = privacySRS {
                try SRS.reserve(points: SRS.privacyPoints, datPath: p)
            } else if let m = manifests[Kind.largest] {
                try SRS.reserve(forManifest: m)
            }
        }
        guard let manifest = manifests[k] else { throw SwoirError.errorLoadingManifest("no \(k.rawValue) circuit") }
        let circuit = try LeanPoaProver.loadCircuit(manifest: manifest, size: nil)
        let vk = try circuit.getVerificationKey(proof_type: LeanPoaProver.proofType)
        loaded[k] = (circuit, vk)
        return (circuit, vk)
    }

    /// The circuit's verification key (for checking against the chain's genesis VKs).
    public func verificationKey(_ k: Kind) throws -> Data { try load(k).vk }

    /// Proves `inputs` and returns the proof body, after checking bb's public
    /// inputs equal `expected` (32-byte big-endian hex, the witness's own).
    public func prove(_ k: Kind, inputs: [String: Any], expected: [String]) throws -> Data {
        let l = try load(k)
        let t0 = Date()
        let raw = try l.circuit.prove(inputs, proof_type: LeanPoaProver.proofType, vkey: l.vk)
        lastMillis[k] = Int(Date().timeIntervalSince(t0) * 1000)
        let (body, pub) = try Self.split(raw, numPublic: k.publicInputs)
        guard pub == expected.map({ $0.lowercased() }) else { throw Failure.publicInputsDiffer(k) }
        return body
    }

    /// Verifies a body against `publicInputs` with the circuit's own VK.
    public func verify(_ k: Kind, body: Data, publicInputs: [String]) throws -> Bool {
        let l = try load(k)
        var raw = Data()
        let n = UInt32(publicInputs.count)
        raw.append(contentsOf: [UInt8(n >> 24 & 0xff), UInt8(n >> 16 & 0xff), UInt8(n >> 8 & 0xff), UInt8(n & 0xff)])
        for h in publicInputs { raw.append(Data(hexString32: h)) }
        raw.append(body)
        return try l.circuit.verify(raw, vkey: l.vk, proof_type: LeanPoaProver.proofType)
    }

    /// bb's output: a 4-byte count, the public inputs (32 bytes each), then
    /// the proof body (the layout `LeanPoaProver.splitProof` asserts).
    static func split(_ raw: Data, numPublic: Int) throws -> (Data, [String]) {
        let b = [UInt8](raw)
        guard b.count >= 4 + 32 * numPublic else { throw Failure.proofTooShort(b.count) }
        let pub = (0 ..< numPublic).map { i in b[(4 + 32 * i) ..< (4 + 32 * i + 32)].map { String(format: "%02x", $0) }.joined() }
        return (Data(b[(4 + 32 * numPublic)...]), pub)
    }
}

private extension Data {
    init(hexString32 h: String) {
        let s = h.hasPrefix("0x") ? String(h.dropFirst(2)) : h
        let padded = String(repeating: "0", count: Swift.max(0, 64 - s.count)) + s
        var out = [UInt8]()
        var i = padded.startIndex
        while i < padded.endIndex {
            let j = padded.index(i, offsetBy: 2)
            out.append(UInt8(padded[i ..< j], radix: 16) ?? 0)
            i = j
        }
        self.init(out)
    }
}
