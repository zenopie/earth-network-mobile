import Foundation

/// EF.SOD — the passport's Document Security Object.
///
/// A CMS `SignedData` whose encapsulated content is the LDS Security Object
/// (the list of data-group hashes), signed by the Document Signer. Three things
/// come out of it, and each is a slice of the original bytes rather than
/// anything re-derived:
///
///  - `eContent`, which holds `sha256(DG1)`,
///  - `signedAttrs`, which holds `sha256(eContent)`,
///  - the signature over `signedAttrs`, and the certificate that verifies it.
///
/// The circuit re-walks that same chain in zero knowledge, which is why the
/// bytes must be exactly what the Document Signer signed.
public struct SOD {

    public enum Error: Swift.Error, Equatable {
        case notSignedData(String)
        case noSigner
        case noCertificate
        case noContent
        case noSignedAttributes
    }

    public static let signedDataOID = "1.2.840.113549.1.7.2"

    /// The LDS Security Object, as signed. Holds `sha256(DG1)`.
    public let eContent: Data
    /// The signed attributes in the `SET OF` form the signature covers.
    public let signedAttributes: Data
    /// The Document Signer's signature over `signedAttributes`.
    public let signature: Data
    public let certificate: Certificate

    public init(efSOD: Data) throws {
        // ContentInfo ::= SEQUENCE { contentType OID, [0] EXPLICIT content }
        let contentInfo = try DER.parse(SOD.stripApplicationTag(efSOD)).expect(tag: DER.Tag.sequence)
        let contentType = try contentInfo.child(0).expect(tag: DER.Tag.objectIdentifier).oid
        guard contentType == SOD.signedDataOID else { throw Error.notSignedData(contentType) }

        let signedData = try contentInfo.child(1)
            .expect(tag: DER.Tag.context(0))
            .child(0)
            .expect(tag: DER.Tag.sequence)

        // SignedData ::= SEQUENCE { version, digestAlgorithms SET,
        //                           encapContentInfo, [0] certificates,
        //                           [1] crls, signerInfos SET }
        let members = try signedData.children()

        guard let encap = members.first(where: { $0.tag == DER.Tag.sequence }) else {
            throw Error.noContent
        }
        // EncapsulatedContentInfo ::= SEQUENCE { eContentType, [0] EXPLICIT eContent }
        guard let wrapper = try encap.first(tag: DER.Tag.context(0)) else { throw Error.noContent }
        self.eContent = try wrapper.child(0).expect(tag: DER.Tag.octetString).content

        guard let certificates = members.first(where: { $0.tag == DER.Tag.context(0) }) else {
            throw Error.noCertificate
        }
        // The Document Signer is the first certificate. A SOD may carry the
        // CSCA alongside it, but the chain does its own trust-store lookup, so
        // only the signer matters here.
        guard let dsc = try certificates.children().first(where: { $0.tag == DER.Tag.sequence }) else {
            throw Error.noCertificate
        }
        self.certificate = try Certificate(der: dsc.encoded)

        guard let signerInfos = members.last(where: { $0.tag == DER.Tag.set }),
              let signer = try signerInfos.children().first(where: { $0.tag == DER.Tag.sequence })
        else { throw Error.noSigner }

        let signerFields = try signer.children()

        // The signed attributes are stored under an implicit [0], but the
        // signature is computed over the explicit `SET OF` encoding. Re-tagging
        // is not cosmetic: sign or verify against the stored bytes and the
        // digest is over a different first byte, so nothing matches.
        guard let attributes = signerFields.first(where: { $0.tag == DER.Tag.context(0) }) else {
            throw Error.noSignedAttributes
        }
        self.signedAttributes = DER.encode(tag: DER.Tag.set, content: attributes.content)

        // signature OCTET STRING, after digestAlgorithm and signatureAlgorithm.
        guard let signature = signerFields.last(where: { $0.tag == DER.Tag.octetString }) else {
            throw Error.noSigner
        }
        self.signature = signature.content
    }

    /// EF.SOD wraps the ContentInfo in `[APPLICATION 23]` (0x77) when read off
    /// the chip. Some tooling hands it over already unwrapped, so this accepts
    /// either.
    static func stripApplicationTag(_ sod: Data) -> Data {
        guard sod.first == DER.Tag.application(23) else { return sod }
        return (try? DER.parse(sod).content) ?? sod
    }
}
