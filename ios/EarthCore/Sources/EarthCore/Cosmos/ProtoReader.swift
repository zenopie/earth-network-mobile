import Foundation

/// A minimal protobuf wire-format reader, the inverse of `ProtoWriter`.
///
/// The wallet decodes very little — the private msgs in a TxRaw, for the
/// in-memory chain the privacy tests drive and for reading a tx back — so
/// this collects each field's raw values and leaves typing to the caller.
public struct ProtoFields {
    public enum Value {
        case varint(UInt64)
        case bytes(Data)
        case fixed64(UInt64)
        case fixed32(UInt32)
    }

    public enum Error: Swift.Error { case truncated, badWireType(Int) }

    private var fields: [Int: [Value]] = [:]

    public init(_ data: Data) throws {
        let b = [UInt8](data)
        var i = 0
        func varint() throws -> UInt64 {
            var v: UInt64 = 0, shift: UInt64 = 0
            while true {
                guard i < b.count, shift < 64 else { throw Error.truncated }
                let byte = b[i]; i += 1
                v |= UInt64(byte & 0x7f) << shift
                if byte & 0x80 == 0 { return v }
                shift += 7
            }
        }
        while i < b.count {
            let key = try varint()
            let field = Int(key >> 3)
            let value: Value
            switch key & 7 {
            case 0: value = .varint(try varint())
            case 1:
                guard i + 8 <= b.count else { throw Error.truncated }
                var v: UInt64 = 0
                for k in 0 ..< 8 { v |= UInt64(b[i + k]) << UInt64(8 * k) }
                i += 8; value = .fixed64(v)
            case 2:
                let n = Int(try varint())
                guard n >= 0, i + n <= b.count else { throw Error.truncated }
                value = .bytes(Data(b[i ..< i + n])); i += n
            case 5:
                guard i + 4 <= b.count else { throw Error.truncated }
                var v: UInt32 = 0
                for k in 0 ..< 4 { v |= UInt32(b[i + k]) << UInt32(8 * k) }
                i += 4; value = .fixed32(v)
            default: throw Error.badWireType(Int(key & 7))
            }
            fields[field, default: []].append(value)
        }
    }

    public func uint64(_ f: Int) -> UInt64 {
        if case let .varint(v)? = fields[f]?.last { return v }
        return 0
    }

    public func bytes(_ f: Int) -> Data {
        if case let .bytes(d)? = fields[f]?.last { return d }
        return Data()
    }

    public func string(_ f: Int) -> String { String(decoding: bytes(f), as: UTF8.self) }

    public func has(_ f: Int) -> Bool { fields[f] != nil }

    public func repeatedBytes(_ f: Int) -> [Data] {
        (fields[f] ?? []).compactMap { if case let .bytes(d) = $0 { return d } else { return nil } }
    }

    public func repeatedString(_ f: Int) -> [String] { repeatedBytes(f).map { String(decoding: $0, as: UTF8.self) } }

    public func message<T>(_ f: Int, _ decode: (Data) throws -> T) throws -> T { try decode(bytes(f)) }

    public func repeatedMessage<T>(_ f: Int, _ decode: (Data) throws -> T) throws -> [T] {
        try repeatedBytes(f).map(decode)
    }
}
