import BigInt
import Foundation

/// An X.509 certificate, read only as far as a passport needs.
///
/// Not a general X.509 implementation and not a validator: the chain checks the
/// Document Signer against its CSCA trust store, so the app's job is to find
/// the key, name its algorithm, and pass the original DER along untouched.
public struct Certificate {

    public enum Error: Swift.Error, Equatable {
        case unsupportedKeyAlgorithm(String)
        case unsupportedCurve(String)
        case malformedECPoint(String)
        case unsupportedModulusSize(Int)
    }

    /// An elliptic curve a Document Signer may use, with the register-circuit
    /// variant that verifies it.
    public struct Curve: Equatable {
        public let oid: String
        public let name: String
        /// The register circuit that verifies a SOD signed under this curve.
        public let algorithm: String
        /// Coordinate width in bytes. Coordinates are padded to it — a
        /// coordinate with leading zeros is shorter, and dropping the padding
        /// would change the commitment for exactly the unlucky keys.
        public let coordinateLength: Int
        /// Group order, for low-s normalisation.
        public let order: BigInt
    }

    /// The curves the register circuits are built for, by named-curve OID.
    ///
    /// P-521 is absent deliberately: those DSCs are rare and the circuit is not
    /// built. A passport carrying one fails here with a clear error rather than
    /// producing a proof nothing can verify.
    public static let curves: [String: Curve] = {
        func curve(_ oid: String, _ name: String, _ algorithm: String, _ length: Int, _ order: String) -> (String, Curve) {
            (oid, Curve(oid: oid, name: name, algorithm: algorithm,
                        coordinateLength: length, order: BigInt(order, radix: 16)!))
        }
        return Dictionary(uniqueKeysWithValues: [
            curve("1.2.840.10045.3.1.7", "P-256", "lean_poa", 32,
                  "FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551"),
            curve("1.3.132.0.34", "P-384", "lean_poa_p384", 48,
                  "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFC7634D81F4372DDF581A0DB248B0A77AECEC196ACCC52973"),
            curve("1.3.36.3.3.2.8.1.1.7", "brainpoolP256r1", "lean_poa_brainpool256", 32,
                  "A9FB57DBA1EEA9BC3E660A909D838D718C397AA3B561A6F7901E0E82974856A7"),
            curve("1.3.36.3.3.2.8.1.1.11", "brainpoolP384r1", "lean_poa_brainpool384", 48,
                  "8CB91E82A3386D280F5D6F7E50E641DF152F7109ED5456B31F166E6CAC0425A7CF3AB6AF6B7FC3103B883202E9046565"),
            curve("1.3.36.3.3.2.8.1.1.13", "brainpoolP512r1", "lean_poa_brainpool512", 64,
                  "AADD9DB8DBE9C48B3FD4E6AE33C9FC07CB308DB3B3C9D20ED6639CCA70330870553E5C414CA92619418661197FAC10471DB1D381085DDADDB58796829CA90069"),
        ])
    }()

    /// The curve a key is on, from either form of `ECParameters`.
    ///
    /// Most certificates name the curve by OID, but explicit domain parameters
    /// are legal and common — every Brainpool CSCA in the chain's own test
    /// corpus uses them, because they predate wide OID support. Refusing that
    /// form would fail real passports, so the parameters are matched instead.
    ///
    /// Matching is on the **group order**, which identifies the curve on its
    /// own: it is a 256-bit-or-larger constant, and two standard curves sharing
    /// one does not happen. Comparing every parameter would mean reimplementing
    /// the curve definitions to compare them against.
    static func curve(from parameters: DER.Element) throws -> Curve {
        if parameters.tag == DER.Tag.objectIdentifier {
            let oid = try parameters.oid
            guard let curve = curves[oid] else { throw Error.unsupportedCurve(oid) }
            return curve
        }

        // ECParameters ::= SEQUENCE { version, fieldID, curve, base, order,
        //                             cofactor OPTIONAL }
        guard parameters.tag == DER.Tag.sequence,
              let fields = try? parameters.children(), fields.count >= 5,
              fields[4].tag == DER.Tag.integer
        else { throw Error.unsupportedCurve("malformed explicit domain parameters") }

        let order = BigInt(sign: .plus, magnitude: BigUInt(fields[4].unsignedInteger))
        guard let curve = curves.values.first(where: { $0.order == order }) else {
            throw Error.unsupportedCurve("explicit parameters, order 0x\(String(order, radix: 16))")
        }
        return curve
    }

    public static let ecPublicKeyOID = "1.2.840.10045.2.1"
    public static let rsaEncryptionOID = "1.2.840.113549.1.1.1"

    public enum PublicKey {
        case ec(curve: Curve, x: Data, y: Data)
        case rsa(modulus: BigInt, exponent: BigInt)
    }

    /// The certificate exactly as it arrived. This is what travels in
    /// `MsgRegister` — the chain checks it against the CSCA trust store, so it
    /// must not be re-encoded on the way.
    public let der: Data
    public let publicKey: PublicKey

    public init(der: Data) throws {
        self.der = der

        // Certificate ::= SEQUENCE { tbsCertificate, signatureAlgorithm, signatureValue }
        // TBSCertificate ::= SEQUENCE { [0] version, serial, signature, issuer,
        //                               validity, subject, subjectPublicKeyInfo, ... }
        let certificate = try DER.parse(der).expect(tag: DER.Tag.sequence)
        let tbs = try certificate.child(0).expect(tag: DER.Tag.sequence)
        let fields = try tbs.children()

        // The version is an optional [0], so everything after it shifts by one
        // when it is absent. v1 certificates are extinct among DSCs but the
        // offset is free to get right.
        let base = fields.first?.tag == DER.Tag.context(0) ? 1 : 0
        guard fields.count > base + 5 else { throw DER.Error.missingElement("subjectPublicKeyInfo") }
        let spki = try fields[base + 5].expect(tag: DER.Tag.sequence)

        self.publicKey = try Certificate.parsePublicKey(spki)
    }

    private static func parsePublicKey(_ spki: DER.Element) throws -> PublicKey {
        let algorithm = try spki.child(0).expect(tag: DER.Tag.sequence)
        let algorithmOID = try algorithm.child(0).expect(tag: DER.Tag.objectIdentifier).oid
        let keyBits = try spki.child(1).expect(tag: DER.Tag.bitString).bitStringBytes

        switch algorithmOID {
        case ecPublicKeyOID:
            let parameters = try algorithm.child(1)
            let curve = try Certificate.curve(from: parameters)

            // 0x04 || X || Y. Compressed points are legal ASN.1 and would need
            // a curve implementation to decompress, which nothing here has.
            guard keyBits.first == 0x04 else {
                throw Error.malformedECPoint("point is not uncompressed")
            }
            let expected = 1 + 2 * curve.coordinateLength
            guard keyBits.count == expected else {
                throw Error.malformedECPoint("\(keyBits.count) bytes, expected \(expected) for \(curve.name)")
            }
            let body = keyBits.dropFirst()
            return .ec(
                curve: curve,
                x: Data(body.prefix(curve.coordinateLength)),
                y: Data(body.suffix(curve.coordinateLength))
            )

        case rsaEncryptionOID:
            // RSAPublicKey ::= SEQUENCE { modulus INTEGER, publicExponent INTEGER }
            let key = try DER.parse(keyBits).expect(tag: DER.Tag.sequence)
            let modulus = try key.child(0).expect(tag: DER.Tag.integer).unsignedInteger
            let exponent = try key.child(1).expect(tag: DER.Tag.integer).unsignedInteger
            return .rsa(
                modulus: BigInt(sign: .plus, magnitude: BigUInt(modulus)),
                exponent: BigInt(sign: .plus, magnitude: BigUInt(exponent))
            )

        default:
            throw Error.unsupportedKeyAlgorithm(algorithmOID)
        }
    }

    /// The bytes the register circuits hash into the DSC commitment, and the
    /// key `x/pki` dedups on: ECDSA -> x‖y at the curve's coordinate width,
    /// RSA -> the modulus big-endian.
    ///
    /// This must agree byte for byte with the chain's
    /// `certs.PublicKey.CanonicalBytes`, or the commitment the circuit returns
    /// will not match the one the chain recomputes from this certificate and
    /// the registration is rejected. `tools/certcheck` asserts they do.
    public var canonicalPublicKey: Data {
        switch publicKey {
        case let .ec(_, x, y):
            return x + y
        case let .rsa(modulus, _):
            return Data(modulus.magnitude.serialize().drop { $0 == 0 })
        }
    }

    /// The register circuit that verifies a SOD signed by this key.
    public var registerAlgorithm: String {
        get throws {
            switch publicKey {
            case let .ec(curve, _, _):
                return curve.algorithm
            case let .rsa(modulus, _):
                let bits = modulus.magnitude.bitWidth
                // Real moduli sit a byte or two under the nominal size, so the
                // match is a range rather than an equality.
                switch bits {
                case 2040 ... 2048: return "lean_poa_rsa2048"
                case 4088 ... 4096: return "lean_poa_rsa4096"
                default: throw Error.unsupportedModulusSize(bits)
                }
            }
        }
    }
}
