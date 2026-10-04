import Foundation

/// RFC 1952 gzip, inflated with Foundation's raw-DEFLATE decoder (Apple's
/// `.zlib` algorithm is raw DEFLATE, with no zlib or gzip framing): the
/// header is skipped by hand, and the trailer's size checks the result.
public enum Gzip {
    public static func inflate(_ gz: Data) -> Data? {
        let b = [UInt8](gz)
        guard b.count >= 18, b[0] == 0x1f, b[1] == 0x8b, b[2] == 8 else { return nil }
        let flags = b[3]
        var i = 10
        if flags & 0x04 != 0 { // FEXTRA
            guard i + 2 <= b.count else { return nil }
            i += 2 + Int(b[i]) | Int(b[i + 1]) << 8
        }
        if flags & 0x08 != 0 { while i < b.count, b[i] != 0 { i += 1 }; i += 1 } // FNAME
        if flags & 0x10 != 0 { while i < b.count, b[i] != 0 { i += 1 }; i += 1 } // FCOMMENT
        if flags & 0x02 != 0 { i += 2 } // FHCRC
        guard i < b.count - 8 else { return nil }
        let isize = UInt32(b[b.count - 4]) | UInt32(b[b.count - 3]) << 8 | UInt32(b[b.count - 2]) << 16 | UInt32(b[b.count - 1]) << 24
        guard let out = try? (Data(b[i ..< b.count - 8]) as NSData).decompressed(using: .zlib) as Data,
              UInt32(truncatingIfNeeded: out.count) == isize
        else { return nil }
        return out
    }
}
