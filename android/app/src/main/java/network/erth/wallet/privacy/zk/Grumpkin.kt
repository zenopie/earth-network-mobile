package network.erth.wallet.privacy.zk

import java.math.BigInteger
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Grumpkin, Noir's embedded curve, as the chain's zk/orchard uses it for
 * value commitments and the binding signature (ORCHARD_DESIGN.md sections
 * 2 and 4):
 *
 *     y^2 = x^3 - 17 over the BN254 scalar field p (coordinates are [Fr])
 *     prime order n = the BN254 base-field modulus, cofactor 1
 *     infinity is (0, 0), as in Noir and gnark
 *
 * Scalars are BigIntegers mod n. Nothing here is constant time: the only
 * secrets it touches are value-commitment randomness and the binding key,
 * both fresh per tx, on the user's own phone.
 */
object Grumpkin {
    val P: BigInteger = Fr.MODULUS
    val N: BigInteger = BigInteger("21888242871839275222246405745257275088696311157297823662689037894645226208583")

    /** (p-1)/2: a y is canonical iff y <= HALF_P. */
    val HALF_P: BigInteger = P.subtract(BigInteger.ONE).shiftRight(1)

    private val B: Fr = Fr.ZERO - Fr.of(17)

    data class Point(val x: Fr, val y: Fr) {
        val isInfinity: Boolean get() = x.isZero && y.isZero

        fun isOnCurve(): Boolean = isInfinity || y.square() == x.square() * x + B

        /** x || y, 64 bytes (zk/orchard.PointBytes). */
        fun toBytes(): ByteArray = x.toBytes() + y.toBytes()

        operator fun plus(o: Point): Point = add(this, o)
        operator fun minus(o: Point): Point = add(this, neg(o))
        operator fun times(k: BigInteger): Point = mul(this, k)
        operator fun unaryMinus(): Point = neg(this)

        companion object {
            val INFINITY = Point(Fr.ZERO, Fr.ZERO)

            fun fromBytes(b: ByteArray): Point {
                require(b.size == 64) { "a point is 64 bytes" }
                val p = Point(Fr.fromBytes(b.copyOfRange(0, 32)), Fr.fromBytes(b.copyOfRange(32, 64)))
                require(p.isOnCurve()) { "not a Grumpkin point" }
                return p
            }
        }
    }

    fun neg(p: Point): Point = if (p.isInfinity) p else Point(p.x, Fr.ZERO - p.y)

    // Jacobian (X, Y, Z): x = X/Z^2, y = Y/Z^3; Z = 0 is infinity.
    private class J(val x: Fr, val y: Fr, val z: Fr)

    private val J_INF = J(Fr.ONE, Fr.ONE, Fr.ZERO)

    private fun toJ(p: Point): J = if (p.isInfinity) J_INF else J(p.x, p.y, Fr.ONE)

    private fun inv(a: Fr): Fr = Fr.of(a.toBigInteger().modInverse(P))

    private fun toAffine(j: J): Point {
        if (j.z.isZero) return Point.INFINITY
        val zi = inv(j.z)
        val zi2 = zi.square()
        return Point(j.x * zi2, j.y * zi2 * zi)
    }

    /** dbl-2009-l (a = 0). */
    private fun dbl(p: J): J {
        if (p.z.isZero || p.y.isZero) return J_INF
        val a = p.x.square()
        val b = p.y.square()
        val c = b.square()
        var d = (p.x + b).square() - a - c
        d = d + d
        val e = a + a + a
        val f = e.square()
        val x3 = f - d - d
        var c8 = c + c; c8 = c8 + c8; c8 = c8 + c8
        val y3 = e * (d - x3) - c8
        val yz = p.y * p.z
        return J(x3, y3, yz + yz)
    }

    /** add-2007-bl, with the doubling and inverse cases. */
    private fun addJ(p: J, q: J): J {
        if (p.z.isZero) return q
        if (q.z.isZero) return p
        val z1z1 = p.z.square()
        val z2z2 = q.z.square()
        val u1 = p.x * z2z2
        val u2 = q.x * z1z1
        val s1 = p.y * q.z * z2z2
        val s2 = q.y * p.z * z1z1
        if (u1 == u2) return if (s1 == s2) dbl(p) else J_INF
        val h = u2 - u1
        val i = (h + h).square()
        val j = h * i
        val r0 = s2 - s1
        val r = r0 + r0
        val v = u1 * i
        val x3 = r.square() - j - v - v
        val s1j = s1 * j
        val y3 = r * (v - x3) - s1j - s1j
        val z3 = ((p.z + q.z).square() - z1z1 - z2z2) * h
        return J(x3, y3, z3)
    }

    fun add(p: Point, q: Point): Point = toAffine(addJ(toJ(p), toJ(q)))

    /** k*P for any k (reduced mod n). */
    fun mul(p: Point, k: BigInteger): Point {
        val s = k.mod(N)
        if (s.signum() == 0 || p.isInfinity) return Point.INFINITY
        val base = toJ(p)
        var acc = J_INF
        for (i in s.bitLength() - 1 downTo 0) {
            acc = dbl(acc)
            if (s.testBit(i)) acc = addJ(acc, base)
        }
        return toAffine(acc)
    }

    // ---- hash to curve -------------------------------------------------------

    val TAG_GEN: Fr = tag("earth.gen")
    val TAG_CV_R: Fr = tag("earth.cv.r")
    val TAG_BSIG: Fr = tag("earth.bsig")

    private fun tag(s: String): Fr = Fr.of(BigInteger(1, s.toByteArray(Charsets.US_ASCII)))

    /** x^3 - 17's canonical root, or null when it is not a square. */
    private fun canonicalY(x: Fr): Fr? {
        val rhs = (x.square() * x + B).toBigInteger()
        if (rhs.signum() == 0) return Fr.ZERO
        if (rhs.modPow(HALF_P, P) != BigInteger.ONE) return null
        var y = sqrt(rhs)
        if (y > HALF_P) y = P.subtract(y)
        return Fr.of(y)
    }

    /** p - 1 = 2^28 * Q. */
    private const val S = 28
    private val Q: BigInteger = P.subtract(BigInteger.ONE).shiftRight(S)
    /** 5 is a non-residue mod p. */
    private val Z: BigInteger = BigInteger.valueOf(5).modPow(Q, P)

    /** Tonelli-Shanks; t must be a nonzero square. */
    private fun sqrt(t: BigInteger): BigInteger {
        var m = S
        var c = Z
        var tt = t.modPow(Q, P)
        var r = t.modPow(Q.add(BigInteger.ONE).shiftRight(1), P)
        while (tt != BigInteger.ONE) {
            var i = 0
            var t2 = tt
            while (t2 != BigInteger.ONE) { t2 = t2.multiply(t2).mod(P); i++ }
            var b = c
            repeat(m - i - 1) { b = b.multiply(b).mod(P) }
            m = i
            c = b.multiply(b).mod(P)
            tt = tt.multiply(c).mod(P)
            r = r.multiply(b).mod(P)
        }
        return r
    }

    /** hash_to_point's step at [ctr]: x = H(tag, input, ctr), null when x^3 - 17 is not a square. */
    fun hashToPointAt(tag: Fr, input: Fr, ctr: Int): Point? {
        val x = Poseidon2.hash(tag, input, Fr.of(ctr.toLong()))
        return canonicalY(x)?.let { Point(x, it) }
    }

    /** try-and-increment with the least counter (zk/orchard.HashToPoint). */
    fun hashToPoint(tag: Fr, input: Fr): Pair<Point, Int> {
        var ctr = 0
        while (true) {
            hashToPointAt(tag, input, ctr)?.let { return it to ctr }
            ctr++
        }
    }

    /** The value-commitment randomness base and binding-signature base. */
    val R: Point by lazy { hashToPoint(TAG_CV_R, Fr.ZERO).first }

    private val bases = ConcurrentHashMap<Fr, Point>()

    /** G_a = hash_to_point(TAG_GEN, asset_id), memoized. */
    fun valueBase(asset: Fr): Point = bases.getOrPut(asset) { hashToPoint(TAG_GEN, asset).first }

    // ---- value commitments and the binding signature --------------------------

    /** A u64 (read unsigned) as a scalar. */
    fun u64(v: Long): BigInteger = BigInteger(java.lang.Long.toUnsignedString(v))

    /** cv = v_s*G(a_s) - v_o*G(a_o) + rcv*R (zk/orchard.ValueCommit). */
    fun valueCommit(assetSpend: Fr, vSpend: Long, assetOut: Fr, vOut: Long, rcv: Fr): Point =
        valueBase(assetSpend) * u64(vSpend) - valueBase(assetOut) * u64(vOut) + R * rcv.toBigInteger()

    /** bsk = sum of every action's rcv, mod n. */
    fun bindingKey(rcvs: List<Fr>): BigInteger = rcvs.fold(BigInteger.ZERO) { a, r -> a.add(r.toBigInteger()) }.mod(N)

    const val BINDING_SIG_BYTES = 96

    private fun be32(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        val out = ByteArray(32)
        val n = minOf(raw.size, 32)
        System.arraycopy(raw, raw.size - n, out, 32 - n, n)
        return out
    }

    private fun challenge(rn: Point, bvk: Point, sighash: Fr): BigInteger =
        Poseidon2.hash(TAG_BSIG, rn.x, rn.y, bvk.x, bvk.y, sighash).toBigInteger()

    /**
     * Schnorr over Grumpkin with base R (zk/orchard.SignBinding):
     * k = SHA-512(bsk || sighash || rnd) mod n, Rn = k*R,
     * e = H(TAG_BSIG, Rn.x, Rn.y, bvk.x, bvk.y, sighash), s = k + e*bsk mod n;
     * Rn.x || Rn.y || s. [rnd] is 32 bytes; a broken rnd still never repeats
     * a nonce across messages.
     */
    fun signBinding(bsk: BigInteger, sighash: Fr, rnd: ByteArray): ByteArray {
        require(rnd.size == 32)
        val h = MessageDigest.getInstance("SHA-512")
        h.update(be32(bsk))
        h.update(sighash.toBytes())
        h.update(rnd)
        val k = BigInteger(1, h.digest()).mod(N)
        check(k.signum() != 0) { "zero nonce" }
        val rn = R * k
        val bvk = R * bsk
        val e = challenge(rn, bvk, sighash)
        val s = k.add(e.multiply(bsk)).mod(N)
        return rn.toBytes() + be32(s)
    }

    /** s*R == Rn + e*bvk, Rn on the curve and not infinity, s < n, bvk not infinity. */
    fun verifyBinding(bvk: Point, sighash: Fr, sig: ByteArray): Boolean {
        if (sig.size != BINDING_SIG_BYTES || bvk.isInfinity) return false
        val rn = runCatching { Point.fromBytes(sig.copyOfRange(0, 64)) }.getOrNull() ?: return false
        if (rn.isInfinity) return false
        val s = BigInteger(1, sig.copyOfRange(64, 96))
        if (s >= N) return false
        return R * s == rn + bvk * challenge(rn, bvk, sighash)
    }
}
