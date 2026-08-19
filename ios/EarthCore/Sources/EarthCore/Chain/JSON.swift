import Foundation

/// A forgiving reader over `JSONSerialization` output.
///
/// The LCD's shapes vary in ways a `Codable` model handles badly: a field can
/// be absent, or present as a string where the proto says a number, and an
/// account is either a `BaseAccount` or something wrapping one. Kotlin's
/// `optString`/`optJSONObject` absorbed that; this is the equivalent, so a
/// missing field yields an empty value rather than a decoding failure that
/// discards the whole response.
@dynamicMemberLookup
public struct JSON {
    public let raw: Any?

    public init(_ raw: Any?) { self.raw = raw }

    public subscript(dynamicMember key: String) -> JSON {
        JSON((raw as? [String: Any])?[key])
    }

    public subscript(key: String) -> JSON {
        JSON((raw as? [String: Any])?[key])
    }

    public subscript(index: Int) -> JSON {
        guard let array = raw as? [Any], array.indices.contains(index) else { return JSON(nil) }
        return JSON(array[index])
    }

    public var exists: Bool { raw != nil && !(raw is NSNull) }

    public var array: [JSON] { (raw as? [Any])?.map(JSON.init) ?? [] }

    public var string: String? {
        if let s = raw as? String { return s }
        if let n = raw as? NSNumber { return n.stringValue }
        return nil
    }

    /// Chain amounts arrive as strings; heights and counts sometimes as
    /// numbers. Accept either.
    public var int64: Int64? {
        if let n = raw as? NSNumber { return n.int64Value }
        if let s = raw as? String { return Int64(s) }
        return nil
    }

    public var uint64: UInt64? {
        if let n = raw as? NSNumber, n.int64Value >= 0 { return n.uint64Value }
        if let s = raw as? String { return UInt64(s) }
        return nil
    }

    public var double: Double? {
        if let n = raw as? NSNumber { return n.doubleValue }
        if let s = raw as? String { return Double(s) }
        return nil
    }

    public var bool: Bool? {
        if let b = raw as? Bool { return b }
        if let s = raw as? String { return Bool(s) }
        return nil
    }

    public func string(default fallback: String) -> String { string ?? fallback }
    public func int64(default fallback: Int64) -> Int64 { int64 ?? fallback }
    public func uint64(default fallback: UInt64) -> UInt64 { uint64 ?? fallback }
    public func double(default fallback: Double) -> Double { double ?? fallback }
}
