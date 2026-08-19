import Foundation

/// Decodes a Noir witness from the JSON shape the Android tests use, into the
/// `[String: Any]` Swoir expects.
///
/// Mirrors `toInputMap` in android/.../LeanPoaDeviceTest.kt: scalars become hex
/// strings, booleans stay booleans, arrays become lists of the same. Keeping the
/// two decoders in step is what makes the gate test meaningful — both sides must
/// hand bb the same witness, not merely equivalent-looking JSON.
public enum NoirWitness {

    public enum Failure: Error, CustomStringConvertible {
        case notAnObject
        case unsupportedValue(key: String, type: String)

        public var description: String {
            switch self {
            case .notAnObject:
                return "witness JSON must be an object at the top level"
            case .unsupportedValue(let key, let type):
                return "witness key '\(key)' holds an unsupported value of type \(type)"
            }
        }
    }

    public static func decode(_ data: Data) throws -> [String: Any] {
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw Failure.notAnObject
        }
        var out: [String: Any] = [:]
        out.reserveCapacity(object.count)
        for (key, value) in object {
            out[key] = try normalize(value, key: key)
        }
        return out
    }

    private static func normalize(_ value: Any, key: String) throws -> Any {
        switch value {
        case let array as [Any]:
            return try array.map { try normalize($0, key: key) }
        case let number as NSNumber:
            // JSONSerialization funnels JSON booleans through NSNumber too, so
            // check the underlying ObjC type rather than trusting `as? Bool`,
            // which would also match 0 and 1.
            if CFGetTypeID(number) == CFBooleanGetTypeID() { return number.boolValue }
            return number.stringValue
        case let string as String:
            return string
        default:
            throw Failure.unsupportedValue(key: key, type: String(describing: type(of: value)))
        }
    }
}
