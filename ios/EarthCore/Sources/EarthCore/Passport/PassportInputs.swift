import BigInt
import Foundation

/// Builds the `lean_poa` circuit inputs from a scanned passport's EF.DG1 and
/// EF.SOD. Ports `wallet/passport/PassportInputs.kt`.
///
/// Everything is derived from the passport itself. The circuit computes the
/// nullifier (Poseidon2 over name‖DOB) and the DSC commitment in-circuit and
/// returns them, so there is no Poseidon2 needed here and no registry round
/// trip before proving — the Document Signer certificate simply travels with
/// `MsgRegister` for the chain to check against its CSCA trust store.
///
/// Circuit input contract (see `circuits/lean_poa/SECURITY.md`):
///
///     dg1[95] + dg1_len
///     e_content[200] + e_content_len + dg1_hash_offset
///     signed_attrs[200] + signed_attrs_len + econtent_hash_offset
///     dsc_pubkey_x[32], dsc_pubkey_y[32], sod_signature[64] (low-s)
///     current_date, address
public enum PassportInputs {

    public enum Error: Swift.Error, Equatable {
        /// The account address did not decode to twenty bytes.
        case badAddress(Int)
        case dg1TooLong(Int)
        case eContentTooLong(Int)
        case signedAttributesTooLong(Int)
        case dg1HashNotInEContent
        case eContentHashNotInSignedAttributes
        case malformedSignature
    }

    static let dg1Max = 95
    static let eContentMax = 200
    static let signedAttributesMax = 200

    /// The circuit inputs, and which circuit they are for.
    public struct Inputs {
        /// `lean_poa`, `lean_poa_rsa2048`, … — selects both the compiled
        /// circuit to prove with and the chain's verifying key.
        public let algorithm: String
        /// The witness map, in the form the Noir binding takes: every value a
        /// hex string, or a list of per-byte hex strings.
        public let witness: [String: Any]
    }

    /// A Document Signer lifted out of a SOD: its DER, to travel with
    /// `MsgRegister`, and its canonical public key.
    public struct ScannedDSC {
        public let certificateDER: Data
        public let publicKey: Data
    }

    public static func scannedDSC(efSOD: Data) throws -> ScannedDSC {
        let sod = try SOD(efSOD: efSOD)
        return ScannedDSC(
            certificateDER: sod.certificate.der,
            publicKey: sod.certificate.canonicalPublicKey
        )
    }

    /// - Parameters:
    ///   - dg1: raw EF.DG1 as read from the chip.
    ///   - efSOD: raw EF.SOD.
    ///   - currentDateYYMMDD: today as a YYMMDD integer. The chain pins this to
    ///     block time within `current_date_max_skew_seconds`, so it must be
    ///     roughly now — see finding 7 in the circuit's SECURITY.md.
    ///   - address: the bech32 account this registration is for. The circuit
    ///     takes it as a public input, so the proof verifies only for this
    ///     account and cannot be lifted out of a block and replayed from another
    ///     wallet — which is what lets the chain treat a re-registration as
    ///     MOVING a registration rather than refusing it. It must be the account
    ///     that will sign MsgRegister; the chain rejects any other.
    public static func build(
        dg1: Data,
        efSOD: Data,
        currentDateYYMMDD: Int,
        address: String
    ) throws -> Inputs {
        guard dg1.count <= dg1Max else { throw Error.dg1TooLong(dg1.count) }

        let sod = try SOD(efSOD: efSOD)
        let eContent = sod.eContent
        let signedAttributes = sod.signedAttributes

        guard eContent.count <= eContentMax else { throw Error.eContentTooLong(eContent.count) }
        guard signedAttributes.count <= signedAttributesMax else {
            throw Error.signedAttributesTooLong(signedAttributes.count)
        }

        // The two hash bindings the circuit re-checks: sha256(dg1) sits inside
        // eContent, and sha256(eContent) sits inside the signed attributes as
        // the messageDigest attribute. The circuit is told where rather than
        // made to search, so the offsets are found here.
        guard let dg1HashOffset = index(of: Hashes.sha256(dg1), in: eContent) else {
            throw Error.dg1HashNotInEContent
        }
        guard let eContentHashOffset = index(of: Hashes.sha256(eContent), in: signedAttributes) else {
            throw Error.eContentHashNotInSignedAttributes
        }

        var witness: [String: Any] = [
            "dg1": byteArray(pad(dg1, to: dg1Max)),
            "dg1_len": scalar(dg1.count),
            "e_content": byteArray(pad(eContent, to: eContentMax)),
            "e_content_len": scalar(eContent.count),
            "dg1_hash_offset": scalar(dg1HashOffset),
            "signed_attrs": byteArray(pad(signedAttributes, to: signedAttributesMax)),
            "signed_attrs_len": scalar(signedAttributes.count),
            "econtent_hash_offset": scalar(eContentHashOffset),
            "current_date": scalar(currentDateYYMMDD),
            "address": try addressField(address),
        ]

        switch sod.certificate.publicKey {
        case let .ec(curve, x, y):
            witness["dsc_pubkey_x"] = byteArray(x)
            witness["dsc_pubkey_y"] = byteArray(y)

            let (r, s) = try decodeECDSASignature(sod.signature)
            // Noir's std ECDSA and noir-ecdsa both reject s > n/2 as malleable,
            // so s is normalised here. ICAO does not require the low form, and
            // roughly half of real signatures arrive in the high one.
            let lowS = s > curve.order >> 1 ? curve.order - s : s
            let width = curve.coordinateLength

            if curve.algorithm == "lean_poa" {
                // P-256 goes through Noir's std secp256r1, which takes r‖s as
                // one 64-byte input; the other curves take them apart.
                witness["sod_signature"] = byteArray(
                    leftPad(r, to: width) + leftPad(lowS, to: width)
                )
            } else {
                witness["sod_signature_r"] = byteArray(leftPad(r, to: width))
                witness["sod_signature_s"] = byteArray(leftPad(lowS, to: width))
            }

        case let .rsa(modulus, _):
            let bits = try rsaCircuitBits(modulus)
            // noir-bignum stores a big integer as 120-bit little-endian limbs.
            let limbs = bits / 120 + 1
            // Barrett reduction parameter, as noir-bignum defines it.
            let redc = (BigInt(1) << (2 * bits + 6)) / modulus
            let signature = BigInt(sign: .plus, magnitude: BigUInt(sod.signature))

            witness["dsc_modulus"] = limbArray(modulus, count: limbs)
            witness["dsc_redc"] = limbArray(redc, count: limbs)
            witness["sod_signature"] = limbArray(signature, count: limbs)
        }

        return Inputs(algorithm: try sod.certificate.registerAlgorithm, witness: witness)
    }

    // MARK: - helpers

    private static func rsaCircuitBits(_ modulus: BigInt) throws -> Int {
        switch modulus.magnitude.bitWidth {
        case 2040 ... 2048: return 2048
        case 4088 ... 4096: return 4096
        case let other: throw Certificate.Error.unsupportedModulusSize(other)
        }
    }

    /// The Noir binding takes every `[u8; N]` as a list of per-byte hex strings.
    static func byteArray(_ data: Data) -> [String] {
        data.map { String(format: "0x%02x", $0) }
    }

    /// Scalars are hex strings too, and the binding enforces it: a decimal
    /// string is not parsed loosely, it is rejected outright.
    static func scalar(_ value: Int) -> String { "0x" + String(value, radix: 16) }

    /// The twenty address bytes big-endian as one field element — the same
    /// encoding the chain compares against in `verifyRegistrationProof`.
    /// 160 bits into BN254's ~254-bit field, so the encoding is injective and
    /// two accounts can never collide onto one element.
    static func addressField(_ bech32: String) throws -> String {
        let (_, payload) = try Bech32.decode(bech32)
        guard payload.count == 20 else { throw Error.badAddress(payload.count) }
        return "0x" + payload.map { String(format: "%02x", $0) }.joined()
    }

    /// A big integer as `count` 120-bit little-endian limbs.
    static func limbArray(_ value: BigInt, count: Int) -> [String] {
        let mask = (BigInt(1) << 120) - 1
        return (0 ..< count).map { "0x" + String((value >> (120 * $0)) & mask, radix: 16) }
    }

    static func pad(_ data: Data, to length: Int) -> Data {
        data.count == length ? data : data + Data(repeating: 0, count: length - data.count)
    }

    /// Big-endian, left-padded to a fixed width. Dropping the padding would
    /// change the value for any coordinate with a leading zero byte.
    static func leftPad(_ value: BigInt, to length: Int) -> Data {
        let bytes = value.magnitude.serialize().drop { $0 == 0 }
        return Data(repeating: 0, count: length - bytes.count) + bytes
    }

    static func decodeECDSASignature(_ der: Data) throws -> (r: BigInt, s: BigInt) {
        guard let sequence = try? DER.parse(der).expect(tag: DER.Tag.sequence),
              let fields = try? sequence.children(), fields.count >= 2,
              fields[0].tag == DER.Tag.integer, fields[1].tag == DER.Tag.integer
        else { throw Error.malformedSignature }
        return (
            BigInt(sign: .plus, magnitude: BigUInt(fields[0].unsignedInteger)),
            BigInt(sign: .plus, magnitude: BigUInt(fields[1].unsignedInteger))
        )
    }

    static func index(of needle: Data, in haystack: Data) -> Int? {
        guard !needle.isEmpty, haystack.count >= needle.count else { return nil }
        let bytes = [UInt8](haystack)
        let target = [UInt8](needle)
        outer: for i in 0 ... (bytes.count - target.count) {
            for j in target.indices where bytes[i + j] != target[j] { continue outer }
            return i
        }
        return nil
    }
}
