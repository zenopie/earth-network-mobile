import BigInt
import CryptoKit
import Foundation

/// Grumpkin, Noir's embedded curve, as the chain's zk/orchard uses it for
/// value commitments and the binding signature (ORCHARD_DESIGN.md sections 2
/// and 4). Ports `privacy/zk/Grumpkin.kt`:
///
///     y^2 = x^3 - 17 over the BN254 scalar field p (coordinates are Fr)
///     prime order n = the BN254 base-field modulus, cofactor 1
///     infinity is (0, 0), as in Noir and gnark
///
/// Scalars are BigUInts mod n. Nothing here is constant time: the only
/// secrets it touches are value-commitment randomness and the binding key,
/// both fresh per tx, on the user's own phone.
public enum Grumpkin {
    public static let p: BigUInt = Fr.modulus
    public static let n = BigUInt("21888242871839275222246405745257275088696311157297823662689037894645226208583", radix: 10)!

    /// (p-1)/2: a y is canonical iff y <= halfP.
    public static let halfP: BigUInt = (p - 1) >> 1

    private static let b: Fr = .zero - Fr(UInt64(17))

    public struct Point: Hashable, Sendable {
        public let x: Fr
        public let y: Fr

        public init(x: Fr, y: Fr) { self.x = x; self.y = y }

        public static let infinity = Point(x: .zero, y: .zero)

        public var isInfinity: Bool { x.isZero && y.isZero }

        public func isOnCurve() -> Bool { isInfinity || y.square() == x.square() * x + Grumpkin.b }

        /// x || y, 64 bytes (zk/orchard.PointBytes).
        public var bytes: Data { x.bytes + y.bytes }

        public init(bytes: Data) throws {
            guard bytes.count == 64 else { throw PrivacyError("a point is 64 bytes") }
            let b = Data(bytes)
            self.init(x: try Fr(bytes: b.prefix(32)), y: try Fr(bytes: b.suffix(32)))
            guard isOnCurve() else { throw PrivacyError("not a Grumpkin point") }
        }

        public static func + (a: Point, b: Point) -> Point { Grumpkin.add(a, b) }
        public static func - (a: Point, b: Point) -> Point { Grumpkin.add(a, Grumpkin.neg(b)) }
        public static func * (a: Point, k: BigUInt) -> Point { Grumpkin.mul(a, k) }
        public static prefix func - (a: Point) -> Point { Grumpkin.neg(a) }
    }

    public static func neg(_ q: Point) -> Point { q.isInfinity ? q : Point(x: q.x, y: .zero - q.y) }

    // Jacobian (X, Y, Z): x = X/Z^2, y = Y/Z^3; Z = 0 is infinity.
    private struct J { var x: Fr; var y: Fr; var z: Fr }

    private static let jInf = J(x: .one, y: .one, z: .zero)

    private static func toJ(_ q: Point) -> J { q.isInfinity ? jInf : J(x: q.x, y: q.y, z: .one) }

    static func inv(_ a: Fr) -> Fr { Fr(a.bigUInt.inverse(p)!) }

    private static func toAffine(_ j: J) -> Point {
        if j.z.isZero { return .infinity }
        let zi = inv(j.z)
        let zi2 = zi.square()
        return Point(x: j.x * zi2, y: j.y * zi2 * zi)
    }

    /// dbl-2009-l (a = 0).
    private static func dbl(_ q: J) -> J {
        if q.z.isZero || q.y.isZero { return jInf }
        let a = q.x.square()
        let b = q.y.square()
        let c = b.square()
        var d = (q.x + b).square() - a - c
        d = d + d
        let e = a + a + a
        let f = e.square()
        let x3 = f - d - d
        var c8 = c + c; c8 = c8 + c8; c8 = c8 + c8
        let y3 = e * (d - x3) - c8
        let yz = q.y * q.z
        return J(x: x3, y: y3, z: yz + yz)
    }

    /// add-2007-bl, with the doubling and inverse cases.
    private static func addJ(_ a: J, _ b: J) -> J {
        if a.z.isZero { return b }
        if b.z.isZero { return a }
        let z1z1 = a.z.square()
        let z2z2 = b.z.square()
        let u1 = a.x * z2z2
        let u2 = b.x * z1z1
        let s1 = a.y * b.z * z2z2
        let s2 = b.y * a.z * z1z1
        if u1 == u2 { return s1 == s2 ? dbl(a) : jInf }
        let h = u2 - u1
        let i = (h + h).square()
        let j = h * i
        let r0 = s2 - s1
        let r = r0 + r0
        let v = u1 * i
        let x3 = r.square() - j - v - v
        let s1j = s1 * j
        let y3 = r * (v - x3) - s1j - s1j
        let z3 = ((a.z + b.z).square() - z1z1 - z2z2) * h
        return J(x: x3, y: y3, z: z3)
    }

    public static func add(_ a: Point, _ b: Point) -> Point { toAffine(addJ(toJ(a), toJ(b))) }

    /// k*P for any k (reduced mod n).
    public static func mul(_ q: Point, _ k: BigUInt) -> Point {
        let s = k % n
        if s.isZero || q.isInfinity { return .infinity }
        let base = toJ(q)
        var acc = jInf
        for i in stride(from: s.bitWidth - 1, through: 0, by: -1) {
            acc = dbl(acc)
            if s[bitAt: i] { acc = addJ(acc, base) }
        }
        return toAffine(acc)
    }

    // MARK: - hash to curve

    public static let tagGen = tag("earth.gen")
    public static let tagCvR = tag("earth.cv.r")
    public static let tagBsig = tag("earth.bsig")

    private static func tag(_ s: String) -> Fr { Fr(BigUInt(Data(s.utf8))) }

    /// a^e in the field, by square and multiply over Fr's Montgomery arithmetic.
    static func pow(_ a: Fr, _ e: BigUInt) -> Fr {
        var r = Fr.one
        for i in stride(from: e.bitWidth - 1, through: 0, by: -1) {
            r = r.square()
            if e[bitAt: i] { r = r * a }
        }
        return r
    }

    /// x^3 - 17's canonical root, or nil when it is not a square.
    private static func canonicalY(_ x: Fr) -> Fr? {
        let rhs = x.square() * x + b
        if rhs.isZero { return .zero }
        if pow(rhs, halfP) != .one { return nil }
        var y = sqrt(rhs)
        if y.bigUInt > halfP { y = .zero - y }
        return y
    }

    /// p - 1 = 2^28 * q.
    private static let s2 = 28
    private static let q: BigUInt = (p - 1) >> 28
    /// 5 is a non-residue mod p.
    private static let z: Fr = pow(Fr(UInt64(5)), q)

    /// Tonelli-Shanks; t must be a nonzero square.
    private static func sqrt(_ t: Fr) -> Fr {
        var m = s2
        var c = z
        var tt = pow(t, q)
        var r = pow(t, (q + 1) >> 1)
        while tt != .one {
            var i = 0
            var t2 = tt
            while t2 != .one { t2 = t2.square(); i += 1 }
            var bb = c
            for _ in 0 ..< (m - i - 1) { bb = bb.square() }
            m = i
            c = bb.square()
            tt = tt * c
            r = r * bb
        }
        return r
    }

    /// hash_to_point's step at `ctr`: x = H(tag, input, ctr), nil when x^3 - 17 is not a square.
    public static func hashToPointAt(tag: Fr, input: Fr, ctr: Int) -> Point? {
        let x = Poseidon2.hash([tag, input, Fr(UInt64(ctr))])
        return canonicalY(x).map { Point(x: x, y: $0) }
    }

    /// try-and-increment with the least counter (zk/orchard.HashToPoint).
    public static func hashToPoint(tag: Fr, input: Fr) -> (Point, Int) {
        var ctr = 0
        while true {
            if let pt = hashToPointAt(tag: tag, input: input, ctr: ctr) { return (pt, ctr) }
            ctr += 1
        }
    }

    /// The value-commitment randomness base and binding-signature base.
    public static let r: Point = hashToPoint(tag: tagCvR, input: .zero).0

    private static let basesLock = NSLock()
    nonisolated(unsafe) private static var bases: [Fr: Point] = [:]

    /// G_a = hash_to_point(TAG_GEN, asset_id), memoized.
    public static func valueBase(_ asset: Fr) -> Point {
        basesLock.lock()
        if let b = bases[asset] { basesLock.unlock(); return b }
        basesLock.unlock()
        let b = hashToPoint(tag: tagGen, input: asset).0
        basesLock.lock(); bases[asset] = b; basesLock.unlock()
        return b
    }

    // MARK: - value commitments and the binding signature

    /// cv = v_s*G(a_s) - v_o*G(a_o) + rcv*R (zk/orchard.ValueCommit).
    public static func valueCommit(assetSpend: Fr, vSpend: UInt64, assetOut: Fr, vOut: UInt64, rcv: Fr) -> Point {
        valueBase(assetSpend) * BigUInt(vSpend) - valueBase(assetOut) * BigUInt(vOut) + r * rcv.bigUInt
    }

    /// bsk = sum of every action's rcv, mod n.
    public static func bindingKey(_ rcvs: [Fr]) -> BigUInt { rcvs.reduce(BigUInt(0)) { $0 + $1.bigUInt } % n }

    public static let bindingSigBytes = 96

    static func be32(_ v: BigUInt) -> Data {
        let raw = v.serialize()
        return Data(count: 32 - min(raw.count, 32)) + raw.suffix(32)
    }

    private static func challenge(_ rn: Point, _ bvk: Point, _ sighash: Fr) -> BigUInt {
        Poseidon2.hash([tagBsig, rn.x, rn.y, bvk.x, bvk.y, sighash]).bigUInt
    }

    /// Schnorr over Grumpkin with base R (zk/orchard.SignBinding):
    /// k = SHA-512(bsk || sighash || rnd) mod n, Rn = k*R,
    /// e = H(TAG_BSIG, Rn.x, Rn.y, bvk.x, bvk.y, sighash), s = k + e*bsk mod n;
    /// Rn.x || Rn.y || s. `rnd` is 32 bytes; a broken rnd still never repeats
    /// a nonce across messages.
    public static func signBinding(bsk: BigUInt, sighash: Fr, rnd: Data) -> Data {
        precondition(rnd.count == 32)
        var h = SHA512()
        h.update(data: be32(bsk))
        h.update(data: sighash.bytes)
        h.update(data: rnd)
        let k = BigUInt(Data(h.finalize())) % n
        precondition(!k.isZero, "zero nonce")
        let rn = r * k
        let bvk = r * bsk
        let e = challenge(rn, bvk, sighash)
        let s = (k + e * bsk) % n
        return rn.bytes + be32(s)
    }

    /// s*R == Rn + e*bvk, Rn on the curve and not infinity, s < n, bvk not infinity.
    public static func verifyBinding(bvk: Point, sighash: Fr, sig: Data) -> Bool {
        guard sig.count == bindingSigBytes, !bvk.isInfinity else { return false }
        let sg = Data(sig)
        guard let rn = try? Point(bytes: sg.prefix(64)), !rn.isInfinity else { return false }
        let s = BigUInt(sg.suffix(32))
        guard s < n else { return false }
        return r * s == rn + bvk * challenge(rn, bvk, sighash)
    }
}
