package network.erth.wallet.privacy.zk

import java.math.BigInteger

/**
 * An element of the BN254 scalar field, the field every privacy circuit and
 * the chain's zk/privacy work in (gnark-crypto fr.Element on the chain).
 *
 * Held in Montgomery form as eight little-endian 32-bit limbs. BigInteger
 * arithmetic would be simpler, but a wallet rebuilds the whole note tree by
 * hashing, and Poseidon2 is ~500 multiplications a hash: limb arithmetic is
 * what keeps a full sync on a phone in seconds rather than minutes.
 *
 * The byte encoding is 32 bytes big-endian of the canonical value, exactly
 * zk/privacy.FieldBytes; [fromBytes] refuses a non-canonical value as
 * zk/privacy.FieldFromBytes does.
 */
class Fr internal constructor(internal val m: IntArray) : Comparable<Fr> {

    fun toBigInteger(): BigInteger {
        val c = mont(m, ONE_RAW)
        val b = ByteArray(32)
        for (i in 0 until 8) {
            val w = c[i]
            val o = 31 - 4 * i
            b[o] = w.toByte(); b[o - 1] = (w ushr 8).toByte()
            b[o - 2] = (w ushr 16).toByte(); b[o - 3] = (w ushr 24).toByte()
        }
        return BigInteger(1, b)
    }

    /** 32 bytes big-endian (zk/privacy.FieldBytes). */
    fun toBytes(): ByteArray {
        val raw = toBigInteger().toByteArray()
        val out = ByteArray(32)
        val n = minOf(raw.size, 32)
        System.arraycopy(raw, raw.size - n, out, 32 - n, n)
        return out
    }

    fun toHex(): String = toBytes().joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** As a Noir input: "0x" + hex, which noir_android requires of scalars. */
    fun toNoir(): String = "0x" + toBigInteger().toString(16)

    val isZero: Boolean get() = m.all { it == 0 }

    operator fun plus(o: Fr): Fr = Fr(add(m, o.m))
    operator fun minus(o: Fr): Fr = Fr(sub(m, o.m))
    operator fun times(o: Fr): Fr = Fr(mont(m, o.m))
    fun square(): Fr = Fr(mont(m, m))

    override fun equals(other: Any?): Boolean = other is Fr && m.contentEquals(other.m)
    override fun hashCode(): Int = m.contentHashCode()
    override fun toString(): String = toHex()
    override fun compareTo(other: Fr): Int = toBigInteger().compareTo(other.toBigInteger())

    companion object {
        val MODULUS: BigInteger = BigInteger("21888242871839275222246405745257275088548364400416034343698204186575808495617")

        private val P: IntArray = limbs(MODULUS)
        private val R2: IntArray = limbs(BigInteger.ONE.shiftLeft(512).mod(MODULUS))
        private val ONE_RAW: IntArray = IntArray(8).also { it[0] = 1 }
        // -p^-1 mod 2^32
        private val NP0: Long = BigInteger.ONE.shiftLeft(32)
            .subtract(MODULUS.modInverse(BigInteger.ONE.shiftLeft(32))).toLong() and MASK

        val ZERO = Fr(IntArray(8))
        val ONE = of(1)

        fun of(v: Long): Fr {
            require(v >= 0) { "negative" }
            return of(BigInteger.valueOf(v))
        }

        /** A u64 lifted into the field (zk/privacy.U64); [v] is read unsigned. */
        fun ofU64(v: Long): Fr = of(BigInteger(java.lang.Long.toUnsignedString(v)))

        /** Reduces [v] mod p. */
        fun of(v: BigInteger): Fr = Fr(mont(limbs(v.mod(MODULUS)), R2))

        /** Exactly 32 big-endian bytes of a value below p; anything else throws. */
        fun fromBytes(b: ByteArray): Fr {
            require(b.size == 32) { "a field element is 32 bytes, got ${b.size}" }
            val v = BigInteger(1, b)
            require(v < MODULUS) { "not a canonical field element" }
            return of(v)
        }

        fun fromHex(h: String): Fr {
            val s = h.removePrefix("0x")
            val v = BigInteger(s, 16)
            require(v < MODULUS) { "not a canonical field element" }
            return of(v)
        }

        /** 64 bytes reduced mod p: a uniform field element from uniform bytes. */
        fun fromWideBytes(b: ByteArray): Fr {
            require(b.size >= 48) { "need at least 48 bytes for a uniform reduction" }
            return of(BigInteger(1, b))
        }

        private const val MASK = 0xffffffffL

        private fun limbs(v: BigInteger): IntArray {
            val out = IntArray(8)
            var x = v
            for (i in 0 until 8) {
                out[i] = x.toInt()
                x = x.shiftRight(32)
            }
            return out
        }

        private fun geP(t: IntArray): Boolean {
            for (i in 7 downTo 0) {
                val a = t[i].toLong() and MASK
                val b = P[i].toLong() and MASK
                if (a != b) return a > b
            }
            return true
        }

        private fun subP(t: IntArray) {
            var borrow = 0L
            for (i in 0 until 8) {
                val d = (t[i].toLong() and MASK) - (P[i].toLong() and MASK) - borrow
                t[i] = d.toInt()
                borrow = if (d < 0) 1 else 0
            }
        }

        internal fun add(a: IntArray, b: IntArray): IntArray {
            val t = IntArray(8)
            var c = 0L
            for (i in 0 until 8) {
                val s = (a[i].toLong() and MASK) + (b[i].toLong() and MASK) + c
                t[i] = s.toInt()
                c = s ushr 32
            }
            // p < 2^254, so a + b < 2^255 never carries out of 256 bits.
            if (geP(t)) subP(t)
            return t
        }

        internal fun sub(a: IntArray, b: IntArray): IntArray {
            val t = IntArray(8)
            var borrow = 0L
            for (i in 0 until 8) {
                val d = (a[i].toLong() and MASK) - (b[i].toLong() and MASK) - borrow
                t[i] = d.toInt()
                borrow = if (d < 0) 1 else 0
            }
            if (borrow != 0L) {
                var c = 0L
                for (i in 0 until 8) {
                    val s = (t[i].toLong() and MASK) + (P[i].toLong() and MASK) + c
                    t[i] = s.toInt()
                    c = s ushr 32
                }
            }
            return t
        }

        /**
         * Montgomery product a*b*2^-256 mod p (CIOS, 32-bit words). Every
         * intermediate is at most (2^32-1)^2 + 2(2^32-1) = 2^64-1, so the
         * unsigned sums fit a Long exactly.
         */
        internal fun mont(a: IntArray, b: IntArray): IntArray {
            val t = LongArray(10)
            for (i in 0 until 8) {
                val bi = b[i].toLong() and MASK
                var c = 0L
                for (j in 0 until 8) {
                    val s = t[j] + (a[j].toLong() and MASK) * bi + c
                    t[j] = s and MASK
                    c = s ushr 32
                }
                var s = t[8] + c
                t[8] = s and MASK
                t[9] = s ushr 32
                val mm = (t[0] * NP0) and MASK
                s = t[0] + mm * (P[0].toLong() and MASK)
                c = s ushr 32
                for (j in 1 until 8) {
                    s = t[j] + mm * (P[j].toLong() and MASK) + c
                    t[j - 1] = s and MASK
                    c = s ushr 32
                }
                s = t[8] + c
                t[7] = s and MASK
                t[8] = t[9] + (s ushr 32)
            }
            val out = IntArray(8) { t[it].toInt() }
            if (t[8] != 0L || geP(out)) subP(out)
            return out
        }
    }
}
