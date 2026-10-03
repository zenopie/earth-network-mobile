import Foundation

/// Everything between a chip read and a registration on chain.
///
/// The NFC dialogue is not here, and neither is Barretenberg. Both need things
/// this package deliberately does not have — CoreNFC needs a device and an
/// entitlement, the prover needs the 140MB Swoirenberg framework — so the proof
/// arrives through `Prover`, injected. What is left is the part with the
/// decisions in it, and it runs and is tested on any Mac.
///
/// Ports the half of `passport/PassportSession.kt` that is not Android's
/// `IsoDep`.
public struct PassportRegistration {

    /// What the chip gave up, before anything is proved from it.
    public struct Scan {
        /// Raw EF.DG1 as read from the chip.
        public let dg1: Data
        /// Raw EF.SOD.
        public let efSOD: Data

        public init(dg1: Data, efSOD: Data) {
            self.dg1 = dg1
            self.efSOD = efSOD
        }

        /// The MRZ the chip holds, which is the authoritative copy — what the
        /// user typed only had to be right enough to unlock it.
        public var mrz: MRZ? {
            // DG1 is [APPLICATION 1] { 5F1F <88 bytes> }; the MRZ is the last
            // 88 bytes either way, which avoids parsing a two-byte tag for a
            // field at a fixed offset.
            guard dg1.count >= 88 else { return nil }
            return try? MRZ(td3: String(decoding: dg1.suffix(88), as: UTF8.self))
        }
    }

    /// A proof of personhood over a scan. Produced by `ProverGate` on a device;
    /// stubbed in tests.
    public struct Proof {
        public let proof: Data
        /// `[current_date, address, nullifier, dsc_key]` as decimal strings.
        /// address is the registration binding the proof was made for
        /// (H(TAG_REG, idc, pc_anml, pc_erth, affiliate)); the chain requires it
        /// to match the msg's fields.
        public let publicSignals: [String]
        /// The circuit variant that produced it — the chain uses it to pick a
        /// verifying key, so it must be the one `PassportInputs` selected.
        public let signatureAlgorithm: String

        public init(proof: Data, publicSignals: [String], signatureAlgorithm: String) {
            self.proof = proof
            self.publicSignals = publicSignals
            self.signatureAlgorithm = signatureAlgorithm
        }

        /// The registration binding the proof carries, as a decimal field element.
        public var address: String? {
            publicSignals.count > 1 ? publicSignals[1] : nil
        }

        /// The nullifier: one per human per passport.
        ///
        /// It commits to the document number and date of birth, so it is NOT
        /// stable across a renewal — a renewed passport yields a new nullifier
        /// and can register again. That is a deliberate trade for making the
        /// nullifier unguessable from a name and birth date; see finding #1 in
        /// circuits/lean_poa/SECURITY.md.
        public var nullifier: String? {
            publicSignals.count > 2 ? publicSignals[2] : nil
        }
    }

    /// Builds a witness and proves it. Separated from everything else here
    /// because it is the slow, failure-prone step, and it needs the passport
    /// held against the phone throughout.
    public typealias Prover = (PassportInputs.Inputs) async throws -> Proof

    public enum Error: Swift.Error, Equatable {
        /// The proof was produced against a different circuit than the
        /// certificate selects, so the chain would look up the wrong verifying
        /// key. Only reachable if a prover ignores the inputs it was given.
        case algorithmMismatch(expected: String, got: String)
        case malformedReferrer(String)
    }

    /// Today as YYMMDD in UTC, for the circuit's `current_date`.
    ///
    /// UTC rather than local: the chain compares it to block time within a
    /// skew window, and a phone in UTC+13 would otherwise be a day ahead of
    /// every validator.
    public static func todayYYMMDD(now: Date = Date()) -> Int {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        let parts = calendar.dateComponents([.year, .month, .day], from: now)
        return (parts.year! % 100) * 10000 + parts.month! * 100 + parts.day!
    }

    /// Prove personhood from a scan, without broadcasting anything.
    ///
    /// Broadcasting waits until the passport is back in a pocket — and a new
    /// human's fee is itself paid against this proof (`GasGrant`), so the
    /// proof has to exist before there is any gas to ask for.
    /// - Parameter binding: the registration binding from
    ///   `PrivacyWallet.prepareRegistration`. The circuit takes it as its
    ///   `address` public input, so the proof registers only the identity
    ///   commitment and notes it names.
    public static func prove(
        scan: Scan,
        binding: Fr,
        now: Date = Date(),
        using prover: Prover
    ) async throws -> Proof {
        let inputs = try PassportInputs.build(
            dg1: scan.dg1,
            efSOD: scan.efSOD,
            currentDateYYMMDD: todayYYMMDD(now: now),
            binding: binding
        )
        let proof = try await prover(inputs)
        guard proof.signatureAlgorithm == inputs.algorithm else {
            throw Error.algorithmMismatch(expected: inputs.algorithm, got: proof.signatureAlgorithm)
        }
        return proof
    }

    /// The optional referrer as typed ("@alice", "alice"): its handle, or ""
    /// for none. Refused here rather than on chain, where a malformed one
    /// costs the gas grant that paid for the attempt. Whether it is live is
    /// looked up in the whole handle directory before the passport is read.
    public static func normalizeReferrer(_ referrer: String?) throws -> String {
        guard let referrer = referrer?.trimmingCharacters(in: .whitespacesAndNewlines),
              !referrer.isEmpty
        else { return "" }
        guard let h = Handles.parse(referrer) else { throw Error.malformedReferrer(referrer) }
        return h
    }

    /// The Document Signer certificate the registration carries.
    public static func dscDER(scan: Scan) throws -> Data {
        try PassportInputs.scannedDSC(efSOD: scan.efSOD).certificateDER
    }
}
