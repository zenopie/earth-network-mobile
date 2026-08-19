import Foundation

/// A minimal DER reader that keeps byte ranges.
///
/// Hand-written for the same reason the protobuf writer is, plus one that only
/// applies here: what a passport needs from ASN.1 is mostly *raw slices*, not
/// decoded values. The signed attributes have to be re-encoded byte for byte
/// (the SOD stores them under an implicit `[0]` tag and the signature is over
/// the explicit `SET OF` form), and the DSC certificate travels to the chain as
/// its original DER. A decoding library hands back values and makes getting the
/// original bytes back the awkward part.
///
/// Only what a passport contains: definite-length DER, no BER indefinite
/// lengths, no streaming.
public struct DER {

    public enum Error: Swift.Error, Equatable {
        case truncated
        case indefiniteLength
        case lengthTooLarge
        case unexpectedTag(expected: UInt8, found: UInt8)
        case notConstructed(UInt8)
        case missingElement(String)
        case malformedInteger
        case malformedOID
    }

    /// Common tags, as they appear on the wire.
    public enum Tag {
        public static let integer: UInt8 = 0x02
        public static let bitString: UInt8 = 0x03
        public static let octetString: UInt8 = 0x04
        public static let null: UInt8 = 0x05
        public static let objectIdentifier: UInt8 = 0x06
        public static let sequence: UInt8 = 0x30
        public static let set: UInt8 = 0x31

        /// Context-specific constructed `[n]`.
        public static func context(_ n: UInt8) -> UInt8 { 0xa0 | n }
        /// Application constructed `[APPLICATION n]` — EF.SOD's outer 0x77.
        public static func application(_ n: UInt8) -> UInt8 { 0x60 | n }
    }

    /// One TLV, with the ranges needed to slice it back out of the source.
    public struct Element {
        public let tag: UInt8
        /// The value, without the tag and length.
        public let content: Data
        /// Tag, length, and value together — what to copy when re-encoding.
        public let encoded: Data

        public var isConstructed: Bool { tag & 0x20 != 0 }

        /// The elements inside a constructed value.
        public func children() throws -> [Element] {
            guard isConstructed else { throw Error.notConstructed(tag) }
            return try DER.parseAll(content)
        }

        public func child(_ index: Int) throws -> Element {
            let all = try children()
            guard all.indices.contains(index) else {
                throw Error.missingElement("child \(index) of tag 0x\(String(tag, radix: 16))")
            }
            return all[index]
        }

        /// The first child with this tag, if there is one.
        public func first(tag: UInt8) throws -> Element? {
            try children().first { $0.tag == tag }
        }

        public func expect(tag expected: UInt8) throws -> Element {
            guard tag == expected else { throw Error.unexpectedTag(expected: expected, found: tag) }
            return self
        }

        /// The value as an unsigned big-endian magnitude, with DER's sign byte
        /// removed. Passport integers — moduli, r, s — are all positive.
        public var unsignedInteger: Data {
            var bytes = content
            while bytes.first == 0x00, bytes.count > 1 { bytes = bytes.dropFirst() }
            return Data(bytes)
        }

        /// A BIT STRING's payload, minus the unused-bits count.
        public var bitStringBytes: Data {
            get throws {
                guard let unused = content.first else { throw Error.truncated }
                // Keys and signatures are whole bytes; a passport that used
                // padding bits here would be malformed.
                guard unused == 0 else { throw Error.malformedInteger }
                return Data(content.dropFirst())
            }
        }

        /// A dotted OID string, e.g. "1.2.840.10045.2.1".
        public var oid: String {
            get throws {
                let bytes = [UInt8](content)
                guard let first = bytes.first else { throw Error.malformedOID }
                var parts = ["\(first / 40)", "\(first % 40)"]
                var value = 0
                var started = false
                for byte in bytes.dropFirst() {
                    // Base-128, high bit set on every byte but the last.
                    value = value << 7 | Int(byte & 0x7f)
                    started = true
                    if byte & 0x80 == 0 {
                        parts.append("\(value)")
                        value = 0
                        started = false
                    }
                }
                guard !started else { throw Error.malformedOID }
                return parts.joined(separator: ".")
            }
        }
    }

    /// Parse the single element at the start of `data`.
    public static func parse(_ data: Data) throws -> Element {
        try parse(data, from: data.startIndex).element
    }

    /// Every element in a sequence of concatenated TLVs.
    public static func parseAll(_ data: Data) throws -> [Element] {
        var out = [Element]()
        var index = data.startIndex
        while index < data.endIndex {
            let (element, next) = try parse(data, from: index)
            out.append(element)
            index = next
        }
        return out
    }

    private static func parse(_ data: Data, from start: Data.Index) throws -> (element: Element, next: Data.Index) {
        guard start < data.endIndex else { throw Error.truncated }
        let tag = data[start]

        // Multi-byte tags do not occur in anything a passport carries; treating
        // one as a single byte would misparse silently, so refuse it.
        guard tag & 0x1f != 0x1f else { throw Error.unexpectedTag(expected: 0, found: tag) }

        var index = data.index(after: start)
        guard index < data.endIndex else { throw Error.truncated }

        let firstLengthByte = data[index]
        index = data.index(after: index)
        var length = 0

        if firstLengthByte & 0x80 == 0 {
            length = Int(firstLengthByte)
        } else {
            let count = Int(firstLengthByte & 0x7f)
            // 0x80 is BER's indefinite length. DER forbids it, and a passport
            // that used it would need a different parser entirely.
            guard count > 0 else { throw Error.indefiniteLength }
            guard count <= 4 else { throw Error.lengthTooLarge }
            for _ in 0 ..< count {
                guard index < data.endIndex else { throw Error.truncated }
                length = length << 8 | Int(data[index])
                index = data.index(after: index)
            }
        }

        guard let end = data.index(index, offsetBy: length, limitedBy: data.endIndex) else {
            throw Error.truncated
        }
        return (
            Element(tag: tag, content: Data(data[index ..< end]), encoded: Data(data[start ..< end])),
            end
        )
    }

    /// Wrap content in a tag and DER length. Used to re-tag the signed
    /// attributes from implicit `[0]` to the `SET OF` the signature covers.
    public static func encode(tag: UInt8, content: Data) -> Data {
        var out = Data([tag])
        let length = content.count
        if length < 0x80 {
            out.append(UInt8(length))
        } else {
            var lengthBytes = [UInt8]()
            var remaining = length
            while remaining > 0 {
                lengthBytes.insert(UInt8(remaining & 0xff), at: 0)
                remaining >>= 8
            }
            out.append(UInt8(0x80 | lengthBytes.count))
            out.append(contentsOf: lengthBytes)
        }
        out.append(content)
        return out
    }
}
