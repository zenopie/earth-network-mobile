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
        case malformedECPoint(String)
    }

    /// An elliptic curve the register circuits verify, with every domain
    /// parameter (ICAO 9303-12 requires DSCs to state them explicitly, so a
    /// certificate's curve is matched on all of them).
    public struct Curve: Equatable {
        /// The variant key (p256, bp224, ...).
        public let key: String
        public let name: String
        public let oid: String
        /// Coordinate width in bytes. Coordinates are padded to it — a
        /// coordinate with leading zeros is shorter, and dropping the padding
        /// would change the commitment for exactly the unlucky keys.
        public let coordinateLength: Int
        public let p: BigInt
        public let a: BigInt
        public let b: BigInt
        public let gx: BigInt
        public let gy: BigInt
        /// Group order, for low-s normalisation.
        public let order: BigInt
    }

    /// The curves the register circuits are built for.
    public static let curves: [Curve] = {
        func curve(_ key: String, _ name: String, _ oid: String, _ length: Int,
                   p: String, a: String, b: String, gx: String, gy: String, n: String) -> Curve {
            Curve(key: key, name: name, oid: oid, coordinateLength: length,
                  p: BigInt(p, radix: 16)!, a: BigInt(a, radix: 16)!, b: BigInt(b, radix: 16)!,
                  gx: BigInt(gx, radix: 16)!, gy: BigInt(gy, radix: 16)!, order: BigInt(n, radix: 16)!)
        }
        return [
            curve("p224", "P-224", "1.3.132.0.33", 28,
                  p: "ffffffffffffffffffffffffffffffff000000000000000000000001",
                  a: "fffffffffffffffffffffffffffffffefffffffffffffffffffffffe",
                  b: "b4050a850c04b3abf54132565044b0b7d7bfd8ba270b39432355ffb4",
                  gx: "b70e0cbd6bb4bf7f321390b94a03c1d356c21122343280d6115c1d21",
                  gy: "bd376388b5f723fb4c22dfe6cd4375a05a07476444d5819985007e34",
                  n: "ffffffffffffffffffffffffffff16a2e0b8f03e13dd29455c5c2a3d"),
            curve("p256", "P-256", "1.2.840.10045.3.1.7", 32,
                  p: "ffffffff00000001000000000000000000000000ffffffffffffffffffffffff",
                  a: "ffffffff00000001000000000000000000000000fffffffffffffffffffffffc",
                  b: "5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b",
                  gx: "6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296",
                  gy: "4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5",
                  n: "ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551"),
            curve("p384", "P-384", "1.3.132.0.34", 48,
                  p: "fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffeffffffff0000000000000000ffffffff",
                  a: "fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffeffffffff0000000000000000fffffffc",
                  b: "b3312fa7e23ee7e4988e056be3f82d19181d9c6efe8141120314088f5013875ac656398d8a2ed19d2a85c8edd3ec2aef",
                  gx: "aa87ca22be8b05378eb1c71ef320ad746e1d3b628ba79b9859f741e082542a385502f25dbf55296c3a545e3872760ab7",
                  gy: "3617de4a96262c6f5d9e98bf9292dc29f8f41dbd289a147ce9da3113b5f0b8c00a60b1ce1d7e819d7a431d7c90ea0e5f",
                  n: "ffffffffffffffffffffffffffffffffffffffffffffffffc7634d81f4372ddf581a0db248b0a77aecec196accc52973"),
            curve("p521", "P-521", "1.3.132.0.35", 66,
                  p: "01ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
                  a: "01fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffc",
                  b: "0051953eb9618e1c9a1f929a21a0b68540eea2da725b99b315f3b8b489918ef109e156193951ec7e937b1652c0bd3bb1bf073573df883d2c34f1ef451fd46b503f00",
                  gx: "00c6858e06b70404e9cd9e3ecb662395b4429c648139053fb521f828af606b4d3dbaa14b5e77efe75928fe1dc127a2ffa8de3348b3c1856a429bf97e7e31c2e5bd66",
                  gy: "011839296a789a3bc0045c8a5fb42c7d1bd998f54449579b446817afbd17273e662c97ee72995ef42640c550b9013fad0761353c7086a272c24088be94769fd16650",
                  n: "01fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffa51868783bf2f966b7fcc0148f709a5d03bb5c9b8899c47aebb6fb71e91386409"),
            curve("bp224", "brainpoolP224r1", "1.3.36.3.3.2.8.1.1.5", 28,
                  p: "d7c134aa264366862a18302575d1d787b09f075797da89f57ec8c0ff",
                  a: "68a5e62ca9ce6c1c299803a6c1530b514e182ad8b0042a59cad29f43",
                  b: "2580f63ccfe44138870713b1a92369e33e2135d266dbb372386c400b",
                  gx: "0d9029ad2c7e5cf4340823b2a87dc68c9e4ce3174c1e6efdee12c07d",
                  gy: "58aa56f772c0726f24c6b89e4ecdac24354b9e99caa3f6d3761402cd",
                  n: "d7c134aa264366862a18302575d0fb98d116bc4b6ddebca3a5a7939f"),
            curve("bp256", "brainpoolP256r1", "1.3.36.3.3.2.8.1.1.7", 32,
                  p: "a9fb57dba1eea9bc3e660a909d838d726e3bf623d52620282013481d1f6e5377",
                  a: "7d5a0975fc2c3057eef67530417affe7fb8055c126dc5c6ce94a4b44f330b5d9",
                  b: "26dc5c6ce94a4b44f330b5d9bbd77cbf958416295cf7e1ce6bccdc18ff8c07b6",
                  gx: "8bd2aeb9cb7e57cb2c4b482ffc81b7afb9de27e1e3bd23c23a4453bd9ace3262",
                  gy: "547ef835c3dac4fd97f8461a14611dc9c27745132ded8e545c1d54c72f046997",
                  n: "a9fb57dba1eea9bc3e660a909d838d718c397aa3b561a6f7901e0e82974856a7"),
            curve("bp384", "brainpoolP384r1", "1.3.36.3.3.2.8.1.1.11", 48,
                  p: "8cb91e82a3386d280f5d6f7e50e641df152f7109ed5456b412b1da197fb71123acd3a729901d1a71874700133107ec53",
                  a: "7bc382c63d8c150c3c72080ace05afa0c2bea28e4fb22787139165efba91f90f8aa5814a503ad4eb04a8c7dd22ce2826",
                  b: "04a8c7dd22ce28268b39b55416f0447c2fb77de107dcd2a62e880ea53eeb62d57cb4390295dbc9943ab78696fa504c11",
                  gx: "1d1c64f068cf45ffa2a63a81b7c13f6b8847a3e77ef14fe3db7fcafe0cbd10e8e826e03436d646aaef87b2e247d4af1e",
                  gy: "8abe1d7520f9c2a45cb1eb8e95cfd55262b70b29feec5864e19c054ff99129280e4646217791811142820341263c5315",
                  n: "8cb91e82a3386d280f5d6f7e50e641df152f7109ed5456b31f166e6cac0425a7cf3ab6af6b7fc3103b883202e9046565"),
            curve("bp512", "brainpoolP512r1", "1.3.36.3.3.2.8.1.1.13", 64,
                  p: "aadd9db8dbe9c48b3fd4e6ae33c9fc07cb308db3b3c9d20ed6639cca703308717d4d9b009bc66842aecda12ae6a380e62881ff2f2d82c68528aa6056583a48f3",
                  a: "7830a3318b603b89e2327145ac234cc594cbdd8d3df91610a83441caea9863bc2ded5d5aa8253aa10a2ef1c98b9ac8b57f1117a72bf2c7b9e7c1ac4d77fc94ca",
                  b: "3df91610a83441caea9863bc2ded5d5aa8253aa10a2ef1c98b9ac8b57f1117a72bf2c7b9e7c1ac4d77fc94cadc083e67984050b75ebae5dd2809bd638016f723",
                  gx: "81aee4bdd82ed9645a21322e9c4c6a9385ed9f70b5d916c1b43b62eef4d0098eff3b1f78e2d0d48d50d1687b93b97d5f7c6d5047406a5e688b352209bcb9f822",
                  gy: "7dde385d566332ecc0eabfa9cf7822fdf209f70024a57b1aa000c55b881f8111b2dcde494a5f485e5bca4bd88a2763aed1ca2b2fa8f0540678cd1e0f3ad80892",
                  n: "aadd9db8dbe9c48b3fd4e6ae33c9fc07cb308db3b3c9d20ed6639cca70330870553e5c414ca92619418661197fac10471db1d381085ddaddb58796829ca90069"),
        ]
    }()

    /// The curve a key is on, from either form of `ECParameters`.
    ///
    /// Most certificates name the curve by OID, but explicit domain parameters
    /// are what ICAO 9303-12 requires and what German, Swiss and Austrian
    /// DSCs carry. Those name a supported curve only when every parameter
    /// matches (p, a, b, G, n, cofactor 1), as the chain matches them
    /// (x/pki/certs knownCurve): the same prime with another b is another curve.
    static func curve(from parameters: DER.Element) throws -> Curve {
        if parameters.tag == DER.Tag.objectIdentifier {
            let oid = try parameters.oid
            guard let curve = curves.first(where: { $0.oid == oid }) else {
                throw PassportInputs.Error.unsupported("ECDSA curve \(oid)")
            }
            return curve
        }

        // ECParameters ::= SEQUENCE { version, fieldID SEQUENCE { type, p },
        //     curve SEQUENCE { a, b, seed? }, base, order, cofactor OPTIONAL }
        guard parameters.tag == DER.Tag.sequence,
              let fields = try? parameters.children(), fields.count >= 5,
              let fieldID = try? fields[1].children(), fieldID.count == 2,
              let ab = try? fields[2].children(), ab.count >= 2,
              fields[3].tag == DER.Tag.octetString, fields[4].tag == DER.Tag.integer
        else { throw PassportInputs.Error.data("malformed_dsc") }

        func int(_ d: Data) -> BigInt { BigInt(sign: .plus, magnitude: BigUInt(d)) }
        let p = int(fieldID[1].unsignedInteger)
        let a = int(ab[0].content)
        let b = int(ab[1].content)
        let base = fields[3].content
        let width = (base.count - 1) / 2
        let gx = int(Data(base.dropFirst().prefix(width)))
        let gy = int(Data(base.suffix(width)))
        let n = int(fields[4].unsignedInteger)
        let cofactor = fields.count > 5 ? int(fields[5].unsignedInteger) : 1
        if base.first == 0x04, cofactor == 1,
           let curve = curves.first(where: { ($0.p, $0.a, $0.b, $0.gx, $0.gy, $0.order) == (p, a, b, gx, gy, n) }) {
            return curve
        }
        throw PassportInputs.Error.unsupported("ECDSA explicit curve of \(p.magnitude.bitWidth) bits")
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

    /// The issuer's countryName (C=, OID 2.5.4.6), as written; nil when absent.
    public static func issuerCountry(der: Data) throws -> String? {
        let certificate = try DER.parse(der).expect(tag: DER.Tag.sequence)
        let fields = try certificate.child(0).expect(tag: DER.Tag.sequence).children()
        let base = fields.first?.tag == DER.Tag.context(0) ? 1 : 0
        guard fields.count > base + 2 else { throw DER.Error.missingElement("issuer") }
        // Name ::= SEQUENCE OF RelativeDistinguishedName (SET OF AttributeTypeAndValue)
        for rdn in try fields[base + 2].children() {
            for atv in try rdn.children() where atv.isConstructed {
                let parts = try atv.children()
                if parts.count == 2, parts[0].tag == DER.Tag.objectIdentifier, (try? parts[0].oid) == "2.5.4.6" {
                    return String(data: parts[1].content, encoding: .ascii)
                }
            }
        }
        return nil
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
            throw PassportInputs.Error.unsupported("DSC key \(algorithmOID)")
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
}
