package network.erth.wallet.privacy.zk

import java.math.BigInteger

/**
 * Poseidon2 over BN254 (t=4, d=5, 8 full + 56 partial rounds), the sponge of
 * noir-lang/poseidon v0.3.0 (`Poseidon2::hash`) and the chain's
 * zk/poseidon2.Hash. A line-for-line port of the Go: the IV is len << 64 in
 * the capacity slot, the rate is 3, and one element is squeezed after a final
 * duplex.
 */
object Poseidon2 {
    private const val WIDTH = 4
    private const val RATE = 3
    private const val ROUNDS_F_BEGIN = 4
    private const val ROUNDS_P = 56
    private const val TOTAL = 64

    private val diag: Array<IntArray> = Array(WIDTH) { Fr.of(BigInteger(Poseidon2Constants.MAT_DIAG4[it])).m }
    private val rc: Array<Array<IntArray>> = Array(TOTAL) { r ->
        Array(WIDTH) { i -> Fr.of(BigInteger(Poseidon2Constants.ROUND_CONSTANTS[r][i])).m }
    }
    private val TWO_64: Fr = Fr.of(BigInteger.ONE.shiftLeft(64))

    fun hash(vararg inputs: Fr): Fr = hash(inputs.asList())

    fun hash(inputs: List<Fr>): Fr {
        val s = Array(WIDTH) { Fr.ZERO.m }
        s[RATE] = (Fr.of(inputs.size.toLong()) * TWO_64).m
        val cache = Array(RATE) { Fr.ZERO.m }
        var n = 0
        fun duplex() {
            for (i in n until RATE) cache[i] = Fr.ZERO.m
            for (i in 0 until RATE) s[i] = Fr.add(s[i], cache[i])
            permute(s)
        }
        for (x in inputs) {
            if (n == RATE) {
                duplex()
                cache[0] = x.m
                n = 1
            } else {
                cache[n++] = x.m
            }
        }
        duplex()
        return Fr(s[0])
    }

    private fun sbox(x: IntArray): IntArray {
        val x2 = Fr.mont(x, x)
        val x4 = Fr.mont(x2, x2)
        return Fr.mont(x4, x)
    }

    private fun external(s: Array<IntArray>) {
        val t0 = Fr.add(s[0], s[1])
        val t1 = Fr.add(s[2], s[3])
        var t2 = Fr.add(s[1], s[1]); t2 = Fr.add(t2, t1)
        var t3 = Fr.add(s[3], s[3]); t3 = Fr.add(t3, t0)
        var t4 = Fr.add(t1, t1); t4 = Fr.add(t4, t4); t4 = Fr.add(t4, t3)
        var t5 = Fr.add(t0, t0); t5 = Fr.add(t5, t5); t5 = Fr.add(t5, t2)
        val t6 = Fr.add(t3, t5)
        val t7 = Fr.add(t2, t4)
        s[0] = t6; s[1] = t5; s[2] = t7; s[3] = t4
    }

    private fun internal(s: Array<IntArray>) {
        var sum = Fr.add(s[0], s[1])
        sum = Fr.add(sum, s[2])
        sum = Fr.add(sum, s[3])
        for (i in 0 until WIDTH) s[i] = Fr.add(Fr.mont(diag[i], s[i]), sum)
    }

    private fun permute(s: Array<IntArray>) {
        external(s)
        for (r in 0 until ROUNDS_F_BEGIN) {
            for (i in 0 until WIDTH) s[i] = sbox(Fr.add(s[i], rc[r][i]))
            external(s)
        }
        val pEnd = ROUNDS_F_BEGIN + ROUNDS_P
        for (r in ROUNDS_F_BEGIN until pEnd) {
            s[0] = sbox(Fr.add(s[0], rc[r][0]))
            internal(s)
        }
        for (r in pEnd until TOTAL) {
            for (i in 0 until WIDTH) s[i] = sbox(Fr.add(s[i], rc[r][i]))
            external(s)
        }
    }
}
