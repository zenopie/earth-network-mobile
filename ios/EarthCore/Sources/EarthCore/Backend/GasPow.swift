import CryptoKit
import Foundation

/// The hashcash stamp /gas/register may ask for (backend README "Proof of
/// work", services/pow.py). Ports `backend/GasPow.kt`:
///
///     input = "earth-gas-pow/v1:" + ts + ":" + binding + ":" + nullifier + ":" + nonce   (ASCII)
///     valid = SHA-256(input) has at least `bits` leading zero bits
///
/// ts is unix seconds (the server takes ±600 s), binding and nullifier are
/// public_signals[1] and [2] exactly as the request sends them, the nonce a
/// lowercase hex counter (1-64 of [0-9A-Za-z]). 22 bits, the server's cap,
/// is about four million hashes: a few seconds on a phone.
public enum GasPow {
    public static let version = "earth-gas-pow/v1"
    /// The most bits the wallet will work for: the server's policy
    /// caps POW_MAX_BITS at 24 (it ships at 22); a server asking more is
    /// refused rather than worked for (each bit doubles the phone's work).
    public static let maxBits = 24

    public struct Stamp: Equatable, Sendable {
        public let ts: Int64
        public let nonce: String
        public let bits: Int
    }

    public static func input(ts: Int64, binding: String, nullifier: String, nonce: String) -> Data {
        Data("\(version):\(ts):\(binding):\(nullifier):\(nonce)".utf8)
    }

    public static func leadingZeroBits<S: Sequence>(_ d: S) -> Int where S.Element == UInt8 {
        var n = 0
        for b in d {
            if b == 0 { n += 8; continue }
            return n + b.leadingZeroBitCount
        }
        return n
    }

    /// A stamp at `bits` for this registration, made at `ts`. Checks for
    /// cancellation between batches; `progress` gets the expected fraction
    /// done (tries over 2^bits, capped below 1).
    public static func solve(ts: Int64, binding: String, nullifier: String, bits: Int,
                             progress: (Double) -> Void = { _ in }) throws -> Stamp {
        guard (0 ... maxBits).contains(bits) else { throw GasGrant.Refused(status: 0, message: "The gas service asks too much work (\(bits) bits).") }
        let prefix = Data("\(version):\(ts):\(binding):\(nullifier):".utf8)
        let expected = Double(UInt64(1) << UInt64(bits))
        var i: UInt64 = 0
        while true {
            let nonce = String(i, radix: 16)
            var h = SHA256()
            h.update(data: prefix)
            h.update(data: Data(nonce.utf8))
            if leadingZeroBits(h.finalize()) >= bits {
                progress(1)
                return Stamp(ts: ts, nonce: nonce, bits: bits)
            }
            i += 1
            if i & 0xfff == 0 {
                try Task.checkCancellation()
                progress(min(0.99, Double(i) / expected))
            }
        }
    }
}
