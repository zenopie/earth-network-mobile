import CryptoKit
import EarthCore
import Foundation

/// Builds a passport that does not exist: a DG1, a Document Signer key, and a
/// real EF.SOD signed by it.
///
/// There is no captured DG1/SOD pair in this repo to test against — the
/// Android fixture is the *already-built* circuit inputs, which cannot be run
/// backwards into a SOD. So one is constructed here, and the check is that
/// `PassportInputs` finds exactly what was put in. That covers the ASN.1
/// walking, the hash bindings, the implicit-to-explicit re-tagging of the
/// signed attributes, and low-s normalisation.
///
/// It is a fixture, not a forgery: nothing about it would pass the chain, whose
/// trust comes from the CSCA store this certificate is not in.
enum SyntheticPassport {

    struct Passport {
        let dg1: Data
        let efSOD: Data
        let signingKey: P256.Signing.PrivateKey
        /// x‖y, the canonical form the circuit commits to.
        let canonicalPublicKey: Data
        let eContent: Data
        let signedAttributes: Data
        let mrz: String
    }

    /// ICAO's own BAC worked example, so the document number, birth date and
    /// expiry — and therefore the key seed — are externally anchored. Only the
    /// composite check digit is computed, because the widely-copied specimen
    /// string has an inconsistent one.
    static let line1 = "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<"
    static let line2 = "L898902C<3UTO6908061F9406236ZE184226B<<<<<14"

    static func make(expiry: String = "940623") throws -> Passport {
        let mrz = line1 + line2
        let dg1 = encodeDG1(mrz: mrz)

        let key = P256.Signing.PrivateKey()
        // CryptoKit hands back 0x04‖x‖y for an uncompressed point.
        let point = key.publicKey.x963Representation
        let canonical = Data(point.dropFirst())

        let eContent = ldsSecurityObject(dg1Hash: sha256(dg1))
        let signedAttributes = signedAttributes(eContentHash: sha256(eContent))

        // The signature covers the explicit SET OF form, which is why the SOD
        // stores the attributes under [0] and something has to re-tag them.
        let signature = try key.signature(for: signedAttributes).derRepresentation

        let certificate = certificate(publicKeyPoint: point)
        let efSOD = efSOD(
            eContent: eContent,
            certificate: certificate,
            signedAttributes: signedAttributes,
            signature: Data(signature)
        )

        return Passport(
            dg1: dg1,
            efSOD: efSOD,
            signingKey: key,
            canonicalPublicKey: canonical,
            eContent: eContent,
            signedAttributes: signedAttributes,
            mrz: mrz
        )
    }

    // MARK: - the passport's parts

    /// EF.DG1 ::= [APPLICATION 1] { [APPLICATION 0x1F] MRZ }
    static func encodeDG1(mrz: String) -> Data {
        // 5F1F is a two-byte tag. Nothing parses DG1 — it goes to the circuit
        // as opaque bytes, and the circuit indexes into the MRZ by offset — so
        // it is written literally rather than through the encoder.
        var body = Data([0x5f, 0x1f])
        let mrzBytes = Data(mrz.utf8)
        body.append(UInt8(mrzBytes.count))
        body.append(mrzBytes)
        return DER.encode(tag: 0x61, content: body)
    }

    /// The LDS Security Object: the DG hash list the Document Signer signs.
    static func ldsSecurityObject(dg1Hash: Data) -> Data {
        let dataGroupHash = DER.encode(tag: DER.Tag.sequence, content:
            DER.encode(tag: DER.Tag.integer, content: Data([0x01]))
                + DER.encode(tag: DER.Tag.octetString, content: dg1Hash))
        return DER.encode(tag: DER.Tag.sequence, content:
            DER.encode(tag: DER.Tag.integer, content: Data([0x00]))
                + algorithmIdentifier(oid: sha256OID)
                + DER.encode(tag: DER.Tag.sequence, content: dataGroupHash))
    }

    /// The signed attributes, in the SET OF form the signature is over.
    static func signedAttributes(eContentHash: Data) -> Data {
        let contentType = attribute(
            oid: contentTypeAttributeOID,
            value: DER.encode(tag: DER.Tag.objectIdentifier, content: encodeOID(ldsSecurityObjectOID))
        )
        let messageDigest = attribute(
            oid: messageDigestAttributeOID,
            value: DER.encode(tag: DER.Tag.octetString, content: eContentHash)
        )
        return DER.encode(tag: DER.Tag.set, content: contentType + messageDigest)
    }

    /// A certificate carrying the signer's key. Only the SubjectPublicKeyInfo
    /// is ever read — of it or of a real one — so the rest is the minimum that
    /// keeps the structure well formed.
    static func certificate(publicKeyPoint: Data) -> Data {
        let spki = DER.encode(tag: DER.Tag.sequence, content:
            DER.encode(tag: DER.Tag.sequence, content:
                DER.encode(tag: DER.Tag.objectIdentifier, content: encodeOID(ecPublicKeyOID))
                    + DER.encode(tag: DER.Tag.objectIdentifier, content: encodeOID(p256OID)))
                + DER.encode(tag: DER.Tag.bitString, content: Data([0x00]) + publicKeyPoint))

        let name = DER.encode(tag: DER.Tag.sequence, content: Data())
        let validity = DER.encode(tag: DER.Tag.sequence, content:
            utcTime("690806000000Z") + utcTime("940623000000Z"))

        let tbs = DER.encode(tag: DER.Tag.sequence, content:
            DER.encode(tag: DER.Tag.context(0), content:
                DER.encode(tag: DER.Tag.integer, content: Data([0x02])))
                + DER.encode(tag: DER.Tag.integer, content: Data([0x01]))
                + algorithmIdentifier(oid: ecdsaWithSHA256OID, omitParameters: true)
                + name + validity + name + spki)

        return DER.encode(tag: DER.Tag.sequence, content:
            tbs
                + algorithmIdentifier(oid: ecdsaWithSHA256OID, omitParameters: true)
                // Self-signature: never checked here, and the chain checks the
                // real one against its CSCA store rather than this.
                + DER.encode(tag: DER.Tag.bitString, content: Data([0x00])))
    }

    static func efSOD(eContent: Data, certificate: Data, signedAttributes: Data, signature: Data) -> Data {
        let encapContentInfo = DER.encode(tag: DER.Tag.sequence, content:
            DER.encode(tag: DER.Tag.objectIdentifier, content: encodeOID(ldsSecurityObjectOID))
                + DER.encode(tag: DER.Tag.context(0), content:
                    DER.encode(tag: DER.Tag.octetString, content: eContent)))

        // The attributes go back under the implicit [0] the SOD stores them
        // under — the whole point of the re-tagging the parser has to undo.
        let implicitAttributes = DER.encode(
            tag: DER.Tag.context(0),
            content: try! DER.parse(signedAttributes).content
        )

        let signerInfo = DER.encode(tag: DER.Tag.sequence, content:
            DER.encode(tag: DER.Tag.integer, content: Data([0x01]))
                + DER.encode(tag: DER.Tag.sequence, content:
                    DER.encode(tag: DER.Tag.sequence, content: Data())
                        + DER.encode(tag: DER.Tag.integer, content: Data([0x01])))
                + algorithmIdentifier(oid: sha256OID)
                + implicitAttributes
                + algorithmIdentifier(oid: ecdsaWithSHA256OID, omitParameters: true)
                + DER.encode(tag: DER.Tag.octetString, content: signature))

        let signedData = DER.encode(tag: DER.Tag.sequence, content:
            DER.encode(tag: DER.Tag.integer, content: Data([0x03]))
                + DER.encode(tag: DER.Tag.set, content: algorithmIdentifier(oid: sha256OID))
                + encapContentInfo
                + DER.encode(tag: DER.Tag.context(0), content: certificate)
                + DER.encode(tag: DER.Tag.set, content: signerInfo))

        let contentInfo = DER.encode(tag: DER.Tag.sequence, content:
            DER.encode(tag: DER.Tag.objectIdentifier, content: encodeOID(signedDataOID))
                + DER.encode(tag: DER.Tag.context(0), content: signedData))

        // How it comes off the chip.
        return DER.encode(tag: DER.Tag.application(23), content: contentInfo)
    }

    // MARK: - DER odds and ends

    static let sha256OID = "2.16.840.1.101.3.4.2.1"
    static let ecPublicKeyOID = "1.2.840.10045.2.1"
    static let p256OID = "1.2.840.10045.3.1.7"
    static let ecdsaWithSHA256OID = "1.2.840.10045.4.3.2"
    static let signedDataOID = "1.2.840.113549.1.7.2"
    static let ldsSecurityObjectOID = "2.23.136.1.1.1"
    static let contentTypeAttributeOID = "1.2.840.113549.1.9.3"
    static let messageDigestAttributeOID = "1.2.840.113549.1.9.4"

    static func sha256(_ data: Data) -> Data { Data(CryptoKit.SHA256.hash(data: data)) }

    static func algorithmIdentifier(oid: String, omitParameters: Bool = false) -> Data {
        var content = DER.encode(tag: DER.Tag.objectIdentifier, content: encodeOID(oid))
        if !omitParameters { content += DER.encode(tag: DER.Tag.null, content: Data()) }
        return DER.encode(tag: DER.Tag.sequence, content: content)
    }

    static func attribute(oid: String, value: Data) -> Data {
        DER.encode(tag: DER.Tag.sequence, content:
            DER.encode(tag: DER.Tag.objectIdentifier, content: encodeOID(oid))
                + DER.encode(tag: DER.Tag.set, content: value))
    }

    static func utcTime(_ value: String) -> Data {
        DER.encode(tag: 0x17, content: Data(value.utf8))
    }

    /// The reverse of `DER.Element.oid`: first two arcs in one byte, the rest
    /// base-128 with the high bit set on every byte but the last.
    static func encodeOID(_ oid: String) -> Data {
        let arcs = oid.split(separator: ".").compactMap { Int($0) }
        var out = Data([UInt8(arcs[0] * 40 + arcs[1])])
        for arc in arcs.dropFirst(2) {
            var bytes = [UInt8(arc & 0x7f)]
            var remaining = arc >> 7
            while remaining > 0 {
                bytes.insert(UInt8(remaining & 0x7f) | 0x80, at: 0)
                remaining >>= 7
            }
            out.append(contentsOf: bytes)
        }
        return out
    }
}
