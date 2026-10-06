import BigInt
import Foundation

/// Picks the register circuit a scanned passport needs and builds its witness
/// from EF.DG1 and EF.SOD. Ports `passport/PassportInputs.kt`.
///
/// Real passports differ in four things (PASSPORT_COVERAGE.md): the Document
/// Signer's key (RSA size and exponent, or ECDSA curve), the signature padding
/// (PKCS#1 v1.5 or PSS), and three hashes that need not agree: the data-group
/// hashes in the security object, the security object's own hash in the
/// signed attributes, and the hash the signature is over. Those select one
/// `PassportVariants.Variant`; a combination with none is
/// `Error.unsupported(scheme)`, shown as "This passport's signature type
/// isn't supported yet (scheme)", never a proving error.
///
/// The rules and every message are circuits/tools/passportgen/reference.py's;
/// the shared fixtures (circuits/fixtures) hold that reference's output, which
/// this file and Android's PassportInputs.kt reproduce byte for byte.
///
/// Everything is derived from the passport itself. The circuit computes the
/// nullifier and the DSC commitment and returns them, so no Poseidon2 is
/// needed here; the Document Signer certificate travels with `MsgRegister`
/// for the chain to check against its CSCA trust store.
public enum PassportInputs {

    public enum Error: Swift.Error, Equatable, LocalizedError {
        /// The passport is fine but no circuit covers its scheme yet.
        case unsupported(String)
        /// The passport's data does not hold together; the code names how.
        case data(String)
        /// The account address did not decode to twenty bytes.
        case badAddress(Int)
        /// No variant manifest is installed (the app installs it at launch).
        case noManifest

        public var errorDescription: String? {
            switch self {
            case let .unsupported(scheme): return "This passport's signature type isn't supported yet (\(scheme))"
            case .data: return "This passport's data does not match its signature, so it cannot be proved."
            case let .badAddress(n): return "The account address decodes to \(n) bytes, not 20."
            case .noManifest: return "This build has no passport circuit manifest."
            }
        }
    }

    public static let dg1Length = 93
    static let dg1Max = 95
    static let maxSalt = 64
    static let maxExponent = BigInt(1) << 17

    static let hashByOID = [
        "1.3.14.3.2.26": "sha1",
        "2.16.840.1.101.3.4.2.4": "sha224",
        "2.16.840.1.101.3.4.2.1": "sha256",
        "2.16.840.1.101.3.4.2.2": "sha384",
        "2.16.840.1.101.3.4.2.3": "sha512",
    ]
    static let hashName = ["sha1": "SHA-1", "sha224": "SHA-224", "sha256": "SHA-256", "sha384": "SHA-384", "sha512": "SHA-512"]
    static let hashLength = ["sha1": 20, "sha224": 28, "sha256": 32, "sha384": 48, "sha512": 64]
    static let rsaWith = [
        "1.2.840.113549.1.1.5": "sha1", "1.2.840.113549.1.1.14": "sha224",
        "1.2.840.113549.1.1.11": "sha256", "1.2.840.113549.1.1.12": "sha384",
        "1.2.840.113549.1.1.13": "sha512",
    ]
    static let ecdsaWith = [
        "1.2.840.10045.4.1": "sha1", "1.2.840.10045.4.3.1": "sha224",
        "1.2.840.10045.4.3.2": "sha256", "1.2.840.10045.4.3.3": "sha384",
        "1.2.840.10045.4.3.4": "sha512",
    ]
    static let rsaPSSOID = "1.2.840.113549.1.1.10"
    static let mgf1OID = "1.2.840.113549.1.1.8"

    /// The circuit inputs, and which circuit they are for.
    public struct Inputs {
        public let variant: PassportVariants.Variant
        /// The scheme in words, e.g. "RSA-2048 PKCS#1 v1.5, SHA-256".
        public let scheme: String
        /// The witness map, in the form the Noir binding takes: every value a
        /// hex string, or a list of per-byte hex strings.
        public let witness: [String: Any]
        /// `lean_poa_rsa2048_sha256`, … — selects both the compiled circuit
        /// to prove with and the chain's verifying key.
        public var algorithm: String { variant.id }
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

    /// The privacy chain's form: the circuit's `address` input carries the
    /// registration binding H(TAG_REG, idc, pc_anml, pc_erth, affiliate)
    /// (PrivacyWallet.prepareRegistration), which the chain checks against
    /// the msg's fields.
    public static func build(
        dg1: Data,
        efSOD: Data,
        currentDateYYMMDD: Int,
        binding: Fr,
        variants: PassportVariants? = PassportVariants.installed
    ) throws -> Inputs {
        guard let variants else { throw Error.noManifest }
        return try build(dg1: dg1, efSOD: efSOD, currentDateYYMMDD: currentDateYYMMDD,
                         addressField: binding.noir, variants: variants)
    }

    /// - Parameters:
    ///   - dg1: raw EF.DG1 as read from the chip.
    ///   - efSOD: raw EF.SOD.
    ///   - currentDateYYMMDD: today as a YYMMDD integer. The chain pins this to
    ///     block time within `current_date_max_skew_seconds`, so it must be
    ///     roughly now.
    ///   - addressField: the circuit's `address` input, a "0x" field element.
    public static func build(
        dg1: Data,
        efSOD: Data,
        currentDateYYMMDD: Int,
        addressField: String,
        variants: PassportVariants
    ) throws -> Inputs {
        guard dg1.count == dg1Length else { throw Error.unsupported("\(dg1.count)-byte DG1 (not a TD3 passport)") }

        let sod: SOD
        do {
            sod = try SOD(efSOD: efSOD)
        } catch let e as Error {
            throw e
        } catch {
            throw Error.data("malformed_sod")
        }
        let eContent = sod.eContent
        let signedAttributes = sod.signedAttributes
        let key = sod.certificate.publicKey

        let hd = try hashOf(sod.dataGroupHashOID)
        let he = try hashOf(sod.digestAlgorithmOID)
        var salt = 0
        var mgf: String?
        let scheme: String
        let hs: String
        let sigOID = sod.signatureAlgorithmOID
        if let h = rsaWith[sigOID] {
            (scheme, hs) = ("pkcs1", h)
        } else if sigOID == Certificate.rsaEncryptionOID {
            (scheme, hs) = ("pkcs1", he)
        } else if sigOID == rsaPSSOID {
            scheme = "pss"
            let p = try pssParameters(sod.signatureParameters)
            hs = p.hash
            if p.mgf != hs { mgf = p.mgf }
            salt = p.salt
            if p.trailer != 1 {
                throw Error.unsupported(describe(key, scheme, [hd, he, hs], mgf: nil) + ", trailer \(p.trailer)")
            }
        } else if let h = ecdsaWith[sigOID] {
            (scheme, hs) = ("ecdsa", h)
        } else if sigOID == Certificate.ecPublicKeyOID {
            (scheme, hs) = ("ecdsa", he)
        } else {
            throw Error.unsupported("signature \(sigOID)")
        }
        let hashes = [hd, he, hs]
        let desc = describe(key, scheme, hashes, mgf: mgf)
        let isRSA: Bool
        if case .rsa = key { isRSA = true } else { isRSA = false }
        guard isRSA == (scheme != "ecdsa") else { throw Error.data("malformed_sod") }
        if mgf != nil || salt > maxSalt { throw Error.unsupported(desc + (salt > maxSalt ? ", salt \(salt)" : "")) }
        if case let .rsa(_, e) = key, !(e % 2 == 1 && e >= 3 && e < maxExponent) {
            throw Error.unsupported("\(desc), exponent \(e)")
        }
        let id = variantID(key: keyName(key), scheme: scheme, hashes: hashes)
        guard let variant = variants.variant(id: id) else { throw Error.unsupported(desc) }
        guard eContent.count <= variant.eContentMax else {
            throw Error.unsupported("\(desc), \(eContent.count)-byte security object")
        }
        guard signedAttributes.count <= variant.signedAttrsMax else {
            throw Error.unsupported("\(desc), \(signedAttributes.count)-byte signed attributes")
        }

        // The two hash bindings the circuit re-checks: H_dg(DG1) sits inside
        // eContent as DG1's DataGroupHash, and H_ec(eContent) inside the
        // signed attributes as messageDigest. The circuit is told where.
        guard let dg1Offset = locate(eContent, dg1HashPrefix(hashLength[hd]!), Hashes.passport(hd, dg1)!) else {
            throw Error.data("dg1_hash_not_in_econtent")
        }
        guard let ecOffset = locate(signedAttributes, messageDigestPrefix(hashLength[he]!), Hashes.passport(he, eContent)!) else {
            throw Error.data("econtent_hash_not_in_signed_attrs")
        }

        var witness: [String: Any] = [
            "dg1": byteArray(dg1, dg1Max),
            "dg1_len": scalar(dg1.count),
            "e_content": byteArray(eContent, variant.eContentMax),
            "e_content_len": scalar(eContent.count),
            "dg1_hash_offset": scalar(dg1Offset),
            "signed_attrs": byteArray(signedAttributes, variant.signedAttrsMax),
            "signed_attrs_len": scalar(signedAttributes.count),
            "econtent_hash_offset": scalar(ecOffset),
            "current_date": scalar(currentDateYYMMDD),
            "address": addressField,
        ]

        switch key {
        case let .rsa(modulus, exponent):
            let bits = modulus.magnitude.bitWidth
            // noir-bignum stores a big integer as 120-bit little-endian limbs.
            let limbs = bits / 120 + 1
            // Barrett reduction parameter, as noir-bignum defines it.
            let redc = (BigInt(1) << (2 * bits + 6)) / modulus
            let signature = BigInt(sign: .plus, magnitude: BigUInt(sod.signature))
            witness["dsc_modulus"] = limbArray(modulus, count: limbs)
            witness["dsc_redc"] = limbArray(redc, count: limbs)
            witness["sod_signature"] = limbArray(signature, count: limbs)
            witness["dsc_exponent"] = "0x" + String(exponent, radix: 16)
            if scheme == "pss" { witness["pss_salt_len"] = scalar(salt) }

        case let .ec(curve, x, y):
            let (r, s) = try decodeECDSASignature(sod.signature)
            let n = curve.order
            guard r > 0, r < n, s > 0, s < n else { throw Error.data("malformed_sod") }
            // Noir's std ECDSA and noir-ecdsa both reject s > n/2 as malleable,
            // so s is normalised here. ICAO does not require the low form, and
            // roughly half of real signatures arrive in the high one.
            let lowS = s > n >> 1 ? n - s : s
            let width = curve.coordinateLength
            witness["dsc_pubkey_x"] = byteArray(x, width)
            witness["dsc_pubkey_y"] = byteArray(y, width)
            if curve.key == "p256" {
                // P-256 goes through Noir's std secp256r1, which takes r‖s as
                // one 64-byte input; the other curves take them apart.
                witness["sod_signature"] = byteArray(leftPad(r, to: 32) + leftPad(lowS, to: 32), 64)
            } else {
                witness["sod_signature_r"] = byteArray(leftPad(r, to: width), width)
                witness["sod_signature_s"] = byteArray(leftPad(lowS, to: width), width)
            }
        }
        return Inputs(variant: variant, scheme: desc, witness: witness)
    }

    /// The variant id: reference.py variant_id.
    public static func variantID(key: String, scheme: String, hashes: [String]) -> String {
        let hs = Set(hashes).count == 1 ? hashes[0] : hashes.joined(separator: "_")
        return "lean_poa_\(key)\(scheme == "pss" ? "_pss" : "")_\(hs)"
    }

    static func keyName(_ key: Certificate.PublicKey) -> String {
        switch key {
        case let .rsa(modulus, _): return "rsa\(modulus.magnitude.bitWidth)"
        case let .ec(curve, _, _): return curve.key
        }
    }

    static func describe(_ key: Certificate.PublicKey, _ scheme: String, _ hashes: [String], mgf: String?) -> String {
        let k: String
        switch key {
        case let .rsa(modulus, _): k = "RSA-\(modulus.magnitude.bitWidth)"
        case let .ec(curve, _, _): k = "ECDSA \(curve.name)"
        }
        var sch = ["pkcs1": " PKCS#1 v1.5", "pss": " PSS"][scheme] ?? ""
        if let mgf { sch += " (MGF1 \(hashName[mgf]!))" }
        let hs = Set(hashes).count == 1
            ? hashName[hashes[0]]!
            : "DG \(hashName[hashes[0]]!), eContent \(hashName[hashes[1]]!), signature \(hashName[hashes[2]]!)"
        return "\(k)\(sch), \(hs)"
    }

    static func hashOf(_ oid: String) throws -> String {
        guard let h = hashByOID[oid] else { throw Error.unsupported("hash \(oid)") }
        return h
    }

    /// RSASSA-PSS-params with RFC 4055's defaults (SHA-1, MGF1-SHA-1, salt 20, trailer 1).
    static func pssParameters(_ der: Data?) throws -> (hash: String, mgf: String, salt: Int, trailer: Int) {
        var hash = "sha1", mgf = "sha1", salt = 20, trailer = 1
        guard let der else { return (hash, mgf, salt, trailer) }
        func algorithmOID(_ e: DER.Element) throws -> String { try e.expect(tag: DER.Tag.sequence).child(0).oid }
        func small(_ e: DER.Element) -> Int {
            let v = BigInt(sign: .plus, magnitude: BigUInt(e.unsignedInteger))
            return v > 1 << 20 ? 1 << 20 : Int(v)
        }
        for field in try DER.parse(der).expect(tag: DER.Tag.sequence).children() {
            let inner = try field.child(0)
            switch field.tag {
            case DER.Tag.context(0): hash = try hashOf(try algorithmOID(inner))
            case DER.Tag.context(1):
                let maskOID = try inner.child(0).oid
                guard maskOID == mgf1OID else { throw Error.unsupported("PSS mask \(maskOID)") }
                mgf = try hashOf(try algorithmOID(try inner.child(1)))
            case DER.Tag.context(2): salt = small(inner)
            case DER.Tag.context(3): trailer = small(inner)
            default: break
            }
        }
        return (hash, mgf, salt, trailer)
    }

    static func dg1HashPrefix(_ n: Int) -> Data { Data([0x30, UInt8(n + 5), 0x02, 0x01, 0x01, 0x04, UInt8(n)]) }

    static func messageDigestPrefix(_ n: Int) -> Data {
        Data([0x06, 0x09, 0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x09, 0x04, 0x31, UInt8(n + 2), 0x04, UInt8(n)])
    }

    /// Offset of `digest` in `haystack` directly after `prefix`, or nil.
    static func locate(_ haystack: Data, _ prefix: Data, _ digest: Data) -> Int? {
        index(of: prefix + digest, in: haystack).map { $0 + prefix.count }
    }

    // MARK: - helpers

    /// The Noir binding takes every `[u8; N]` as a list of per-byte hex
    /// strings, zero-padded to N.
    static func byteArray(_ data: Data, _ length: Int) -> [String] {
        (data + Data(repeating: 0, count: max(0, length - data.count))).map { String(format: "0x%02x", $0) }
    }

    /// Scalars are hex strings too, and the binding enforces it: a decimal
    /// string is not parsed loosely, it is rejected outright.
    static func scalar(_ value: Int) -> String { "0x" + String(value, radix: 16) }

    /// The twenty address bytes big-endian as one field element.
    public static func addressField(_ bech32: String) throws -> String {
        let (_, payload) = try Bech32.decode(bech32)
        guard payload.count == 20 else { throw Error.badAddress(payload.count) }
        return "0x" + Data(payload).hexString
    }

    /// A big integer as `count` 120-bit little-endian limbs.
    static func limbArray(_ value: BigInt, count: Int) -> [String] {
        let mask = (BigInt(1) << 120) - 1
        return (0 ..< count).map { "0x" + String((value >> (120 * $0)) & mask, radix: 16) }
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
        else { throw Error.data("malformed_sod") }
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
