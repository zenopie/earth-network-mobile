import Foundation

/// A minimal protobuf wire-format writer.
///
/// Hand-written rather than generated. The alternative is swift-protobuf plus
/// `protoc` and `protoc-gen-swift` in the build, for the dozen small messages
/// a wallet actually sends — none of which will change shape without the chain
/// changing first. What that costs is compile-time checking, so the encoding
/// is verified instead: `tools/txcheck` decodes what this produces with the
/// chain's own cosmos-sdk types and checks the signature.
///
/// Proto3 default-value elision is deliberate: a zero, an empty string, or
/// empty bytes is *absent* on the wire. Emitting it would change the encoded
/// bytes, and since SIGN_MODE_DIRECT signs those bytes, the chain would
/// verify a signature over something other than what it re-encodes.
public struct ProtoWriter {
    public private(set) var data = Data()

    public init() {}

    private mutating func tag(_ field: Int, _ wireType: UInt8) {
        varint(UInt64(field) << 3 | UInt64(wireType))
    }

    private mutating func varint(_ value: UInt64) {
        var v = value
        while v >= 0x80 {
            data.append(UInt8(v & 0x7f) | 0x80)
            v >>= 7
        }
        data.append(UInt8(v))
    }

    public mutating func string(_ field: Int, _ value: String) {
        guard !value.isEmpty else { return }
        bytes(field, Data(value.utf8))
    }

    public mutating func bytes(_ field: Int, _ value: Data) {
        guard !value.isEmpty else { return }
        tag(field, 2)
        varint(UInt64(value.count))
        data.append(value)
    }

    public mutating func uint64(_ field: Int, _ value: UInt64) {
        guard value != 0 else { return }
        tag(field, 0)
        varint(value)
    }

    public mutating func int64(_ field: Int, _ value: Int64) {
        guard value != 0 else { return }
        tag(field, 0)
        varint(UInt64(bitPattern: value))
    }

    /// Enums are varints and elide their zero value like any other scalar.
    public mutating func enumValue(_ field: Int, _ value: Int) {
        uint64(field, UInt64(value))
    }

    /// A nested message. Always emitted once asked for, even when its encoding
    /// is empty: a present-but-default submessage is distinguishable from an
    /// absent one, and the chain's gogoproto types treat non-nullable members
    /// as always present.
    public mutating func message(_ field: Int, _ value: ProtoMessage) {
        let encoded = value.encoded()
        tag(field, 2)
        varint(UInt64(encoded.count))
        data.append(encoded)
    }

    /// Repeated string. Unlike a singular one, an empty element is still an
    /// element and must be written.
    public mutating func repeatedString(_ field: Int, _ values: [String]) {
        for value in values {
            tag(field, 2)
            let encoded = Data(value.utf8)
            varint(UInt64(encoded.count))
            data.append(encoded)
        }
    }

    public mutating func repeatedBytes(_ field: Int, _ values: [Data]) {
        for value in values {
            tag(field, 2)
            varint(UInt64(value.count))
            data.append(value)
        }
    }

    public mutating func repeatedMessage(_ field: Int, _ values: [ProtoMessage]) {
        for value in values { message(field, value) }
    }
}

public protocol ProtoMessage {
    func encoded() -> Data
}

public extension ProtoMessage {
    /// The message wrapped in a `google.protobuf.Any`, which is how every
    /// Cosmos transaction carries its payload.
    func asAny(typeURL: String) -> ProtoAny {
        ProtoAny(typeURL: typeURL, value: encoded())
    }
}
