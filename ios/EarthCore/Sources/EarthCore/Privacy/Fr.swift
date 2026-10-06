import BigInt
import Foundation

/// An element of the BN254 scalar field, the field every privacy circuit and
/// the chain's zk/privacy work in (gnark-crypto fr.Element on the chain).
///
/// Ports `privacy/zk/Fr.kt`. Held in Montgomery form as four little-endian
/// 64-bit limbs in a value type: a wallet rebuilds the whole note tree by
/// hashing, and Poseidon2 is ~500 multiplications a hash, so the product is
/// unrolled straight-line code with no heap traffic. BigUInt is only for the
/// edges (parsing, printing).
///
/// The byte encoding is 32 bytes big-endian of the canonical value, exactly
/// zk/privacy.FieldBytes; `init(bytes:)` refuses a non-canonical value as
/// zk/privacy.FieldFromBytes does.
public struct Fr: Hashable, Comparable, Sendable, CustomStringConvertible {
    typealias Limbs = (UInt64, UInt64, UInt64, UInt64)

    var m0: UInt64, m1: UInt64, m2: UInt64, m3: UInt64

    var limbs: Limbs { (m0, m1, m2, m3) }

    init(mont l: Limbs) { (m0, m1, m2, m3) = l }

    public enum Error: Swift.Error, Equatable {
        case notCanonical
        case badLength(Int)
        case badHex(String)
        case tooShortForReduction
    }

    // MARK: - constants

    public static let modulus = BigUInt("21888242871839275222246405745257275088548364400416034343698204186575808495617", radix: 10)!

    static let p: Limbs = (0x43e1_f593_f000_0001, 0x2833_e848_79b9_7091, 0xb850_45b6_8181_585d, 0x3064_4e72_e131_a029)
    /// -p^-1 mod 2^64.
    static let inv: UInt64 = 0xc2e1_f593_efff_ffff
    static let r2: Limbs = limbsOf((BigUInt(1) << 512) % modulus)

    public static let zero = Fr(mont: (0, 0, 0, 0))
    public static let one = Fr(UInt64(1))

    // MARK: - construction

    /// A u64 lifted into the field (zk/privacy.U64).
    public init(_ v: UInt64) {
        self.init(mont: Fr.mont((v, 0, 0, 0), Fr.r2))
    }

    /// Reduces `v` mod p.
    public init(_ v: BigUInt) {
        self.init(mont: Fr.mont(Fr.limbsOf(v % Fr.modulus), Fr.r2))
    }

    /// Exactly 32 big-endian bytes of a value below p; anything else throws.
    public init(bytes: Data) throws {
        guard bytes.count == 32 else { throw Error.badLength(bytes.count) }
        let v = BigUInt(bytes)
        guard v < Fr.modulus else { throw Error.notCanonical }
        self.init(v)
    }

    /// Hex (optionally `0x`), canonical.
    public init(hex: String) throws {
        let s = hex.hasPrefix("0x") ? String(hex.dropFirst(2)) : hex
        guard !s.isEmpty, let v = BigUInt(s, radix: 16) else { throw Error.badHex(hex) }
        guard v < Fr.modulus else { throw Error.notCanonical }
        self.init(v)
    }

    /// At least 48 uniform bytes reduced mod p: a uniform field element.
    public static func fromWideBytes(_ b: Data) throws -> Fr {
        guard b.count >= 48 else { throw Error.tooShortForReduction }
        return Fr(BigUInt(b))
    }

    static func limbsOf(_ v: BigUInt) -> Limbs {
        var out: [UInt64] = [0, 0, 0, 0]
        var x = v
        for i in 0 ..< 4 {
            out[i] = UInt64(truncatingIfNeeded: x & BigUInt(UInt64.max))
            x >>= 64
        }
        return (out[0], out[1], out[2], out[3])
    }

    // MARK: - reading

    /// The canonical value as four little-endian limbs.
    var canonical: Limbs { Fr.mont(limbs, (1, 0, 0, 0)) }

    public var bigUInt: BigUInt {
        let c = canonical
        var v = BigUInt(c.3)
        v = (v << 64) | BigUInt(c.2)
        v = (v << 64) | BigUInt(c.1)
        v = (v << 64) | BigUInt(c.0)
        return v
    }

    /// 32 bytes big-endian (zk/privacy.FieldBytes).
    public var bytes: Data {
        let c = canonical
        var out = Data(count: 32)
        for (k, w) in [c.3, c.2, c.1, c.0].enumerated() {
            for i in 0 ..< 8 { out[8 * k + i] = UInt8(truncatingIfNeeded: w >> UInt64(56 - 8 * i)) }
        }
        return out
    }

    public var hex: String { bytes.hexString }

    /// As a Noir input: "0x" + minimal hex, the form the prover takes scalars in.
    public var noir: String { "0x" + String(bigUInt, radix: 16) }

    public var isZero: Bool { m0 | m1 | m2 | m3 == 0 }

    public var description: String { hex }

    public static func == (a: Fr, b: Fr) -> Bool {
        a.m0 == b.m0 && a.m1 == b.m1 && a.m2 == b.m2 && a.m3 == b.m3
    }

    public func hash(into h: inout Hasher) {
        h.combine(m0); h.combine(m1); h.combine(m2); h.combine(m3)
    }

    public static func < (a: Fr, b: Fr) -> Bool { a.bigUInt < b.bigUInt }

    // MARK: - arithmetic

    public static func + (a: Fr, b: Fr) -> Fr { Fr(mont: add(a.limbs, b.limbs)) }
    public static func - (a: Fr, b: Fr) -> Fr { Fr(mont: sub(a.limbs, b.limbs)) }
    public static func * (a: Fr, b: Fr) -> Fr { Fr(mont: mont(a.limbs, b.limbs)) }
    public func square() -> Fr { Fr(mont: Fr.mont(limbs, limbs)) }

    @inline(__always)
    static func mac(_ a: UInt64, _ b: UInt64, _ c: UInt64, _ carry: UInt64) -> (UInt64, UInt64) {
        let (hi, lo) = a.multipliedFullWidth(by: b)
        let (l1, o1) = lo.addingReportingOverflow(c)
        let (l2, o2) = l1.addingReportingOverflow(carry)
        return (hi &+ (o1 ? 1 : 0) &+ (o2 ? 1 : 0), l2)
    }

    /// (x + y, carry).
    @inline(__always)
    static func adc(_ x: UInt64, _ y: UInt64) -> (UInt64, UInt64) {
        let (s, o) = x.addingReportingOverflow(y)
        return (s, o ? 1 : 0)
    }

    @inline(__always)
    static func geP(_ t: Limbs) -> Bool {
        if t.3 != p.3 { return t.3 > p.3 }
        if t.2 != p.2 { return t.2 > p.2 }
        if t.1 != p.1 { return t.1 > p.1 }
        return t.0 >= p.0
    }

    @inline(__always)
    static func subP(_ t: Limbs) -> Limbs {
        let (r0, b0) = t.0.subtractingReportingOverflow(p.0)
        let (x1, c1) = t.1.subtractingReportingOverflow(p.1)
        let (r1, d1) = x1.subtractingReportingOverflow(b0 ? 1 : 0)
        let (x2, c2) = t.2.subtractingReportingOverflow(p.2)
        let (r2, d2) = x2.subtractingReportingOverflow((c1 || d1) ? 1 : 0)
        let (x3, _) = t.3.subtractingReportingOverflow(p.3)
        let r3 = x3 &- ((c2 || d2) ? 1 : 0)
        return (r0, r1, r2, r3)
    }

    @inline(__always)
    static func reduceOnce(_ t: Limbs, _ top: UInt64) -> Limbs {
        (top != 0 || geP(t)) ? subP(t) : t
    }

    /// p < 2^254, so a + b < 2^255 never carries out of 256 bits.
    @inline(__always)
    static func add(_ a: Limbs, _ b: Limbs) -> Limbs {
        let (s0, c0) = adc(a.0, b.0)
        let (x1, c1) = adc(a.1, b.1); let (s1, d1) = adc(x1, c0)
        let (x2, c2) = adc(a.2, b.2); let (s2, d2) = adc(x2, c1 | d1)
        let s3 = a.3 &+ b.3 &+ (c2 | d2)
        return reduceOnce((s0, s1, s2, s3), 0)
    }

    @inline(__always)
    static func sub(_ a: Limbs, _ b: Limbs) -> Limbs {
        let (r0, b0) = a.0.subtractingReportingOverflow(b.0)
        let (x1, c1) = a.1.subtractingReportingOverflow(b.1)
        let (r1, d1) = x1.subtractingReportingOverflow(b0 ? 1 : 0)
        let (x2, c2) = a.2.subtractingReportingOverflow(b.2)
        let (r2, d2) = x2.subtractingReportingOverflow((c1 || d1) ? 1 : 0)
        let (x3, c3) = a.3.subtractingReportingOverflow(b.3)
        let (r3, d3) = x3.subtractingReportingOverflow((c2 || d2) ? 1 : 0)
        guard c3 || d3 else { return (r0, r1, r2, r3) }
        let (s0, e0) = adc(r0, p.0)
        let (y1, e1) = adc(r1, p.1); let (s1, f1) = adc(y1, e0)
        let (y2, e2) = adc(r2, p.2); let (s2, f2) = adc(y2, e1 | f1)
        let s3 = r3 &+ p.3 &+ (e2 | f2)
        return (s0, s1, s2, s3)
    }

    /// Montgomery product a*b*2^-256 mod p (CIOS), unrolled.
    @inline(__always)
    static func mont(_ a: Limbs, _ b: Limbs) -> Limbs {
        var t0: UInt64 = 0, t1: UInt64 = 0, t2: UInt64 = 0, t3: UInt64 = 0, t4: UInt64 = 0, t5: UInt64 = 0
        var c: UInt64 = 0
        var m: UInt64 = 0
        _ = t5
        // word 0
        c = 0
        (c, t0) = mac(a.0, b.0, t0, c)
        (c, t1) = mac(a.1, b.0, t1, c)
        (c, t2) = mac(a.2, b.0, t2, c)
        (c, t3) = mac(a.3, b.0, t3, c)
        (t4, t5) = adc(t4, c)
        m = t0 &* inv
        (c, _) = mac(m, p.0, t0, 0)
        (c, t0) = mac(m, p.1, t1, c)
        (c, t1) = mac(m, p.2, t2, c)
        (c, t2) = mac(m, p.3, t3, c)
        (t3, c) = adc(t4, c)
        t4 = t5 &+ c
        // word 1
        c = 0
        (c, t0) = mac(a.0, b.1, t0, c)
        (c, t1) = mac(a.1, b.1, t1, c)
        (c, t2) = mac(a.2, b.1, t2, c)
        (c, t3) = mac(a.3, b.1, t3, c)
        (t4, t5) = adc(t4, c)
        m = t0 &* inv
        (c, _) = mac(m, p.0, t0, 0)
        (c, t0) = mac(m, p.1, t1, c)
        (c, t1) = mac(m, p.2, t2, c)
        (c, t2) = mac(m, p.3, t3, c)
        (t3, c) = adc(t4, c)
        t4 = t5 &+ c
        // word 2
        c = 0
        (c, t0) = mac(a.0, b.2, t0, c)
        (c, t1) = mac(a.1, b.2, t1, c)
        (c, t2) = mac(a.2, b.2, t2, c)
        (c, t3) = mac(a.3, b.2, t3, c)
        (t4, t5) = adc(t4, c)
        m = t0 &* inv
        (c, _) = mac(m, p.0, t0, 0)
        (c, t0) = mac(m, p.1, t1, c)
        (c, t1) = mac(m, p.2, t2, c)
        (c, t2) = mac(m, p.3, t3, c)
        (t3, c) = adc(t4, c)
        t4 = t5 &+ c
        // word 3
        c = 0
        (c, t0) = mac(a.0, b.3, t0, c)
        (c, t1) = mac(a.1, b.3, t1, c)
        (c, t2) = mac(a.2, b.3, t2, c)
        (c, t3) = mac(a.3, b.3, t3, c)
        (t4, t5) = adc(t4, c)
        m = t0 &* inv
        (c, _) = mac(m, p.0, t0, 0)
        (c, t0) = mac(m, p.1, t1, c)
        (c, t1) = mac(m, p.2, t2, c)
        (c, t2) = mac(m, p.3, t3, c)
        (t3, c) = adc(t4, c)
        t4 = t5 &+ c
        return reduceOnce((t0, t1, t2, t3), t4)
    }
}
