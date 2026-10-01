package network.erth.wallet.privacy.keys

import network.erth.wallet.privacy.zk.Fr

/**
 * A shielded address: what a sender needs to pay a note to its owner,
 * owner_pk (the note's pc commits to it) and ek_pub (the note's ciphertext is
 * encrypted to it).
 *
 * The chain never sees one (it sees only pcs), so it does not define an
 * encoding; this is the wallet's, in PRIVACY_FORMATS.md:
 *
 *     bech32m( hrp "erth", [version 2] ++ 8-to-5-bit(owner_pk 32 BE || ek_pub 32) )
 *
 * Version 2 is the 5-bit value 'z', so every address reads "erth1z…": the
 * hrp is distinct from the transparent "earth", and the leading z marks it as
 * shielded at a glance. 115 characters, past BIP-173's 90 (the limit exists
 * for error-location guarantees; the checksum still detects errors).
 */
data class ShieldedAddress(val ownerPk: Fr, val ekPub: ByteArray) {
    init { require(ekPub.size == 32) { "ek_pub is 32 bytes" } }

    fun encode(): String {
        val payload = ownerPk.toBytes() + ekPub
        val data = byteArrayOf(VERSION.toByte()) + convertBits(payload, 8, 5, true)
        return Bech32m.encode(HRP, data)
    }

    override fun toString(): String = encode()
    override fun equals(other: Any?): Boolean =
        other is ShieldedAddress && ownerPk == other.ownerPk && ekPub.contentEquals(other.ekPub)
    override fun hashCode(): Int = ownerPk.hashCode() * 31 + ekPub.contentHashCode()

    companion object {
        const val HRP = "erth"
        const val VERSION = 2

        fun decode(s: String): ShieldedAddress {
            val (hrp, data) = Bech32m.decode(s)
            require(hrp == HRP) { "not a shielded earth address" }
            require(data.isNotEmpty() && data[0].toInt() == VERSION) { "unknown shielded address version" }
            val payload = convertBits(data.copyOfRange(1, data.size), 5, 8, false)
            require(payload.size == 64) { "bad shielded address length" }
            return ShieldedAddress(Fr.fromBytes(payload.copyOf(32)), payload.copyOfRange(32, 64))
        }

        fun isShielded(s: String): Boolean = runCatching { decode(s.trim()) }.isSuccess

        internal fun convertBits(data: ByteArray, from: Int, to: Int, pad: Boolean): ByteArray {
            var acc = 0
            var bits = 0
            val out = ArrayList<Byte>()
            val maxv = (1 shl to) - 1
            for (b in data) {
                val v = b.toInt() and 0xff
                require(v ushr from == 0) { "invalid data" }
                acc = (acc shl from) or v
                bits += from
                while (bits >= to) {
                    bits -= to
                    out.add(((acc ushr bits) and maxv).toByte())
                }
            }
            if (pad) {
                if (bits > 0) out.add(((acc shl (to - bits)) and maxv).toByte())
            } else {
                require(bits < from && ((acc shl (to - bits)) and maxv) == 0) { "invalid padding" }
            }
            return out.toByteArray()
        }
    }
}

/** BIP-350 bech32m, lowercase, without BIP-173's 90-character cap. */
internal object Bech32m {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private const val CONST = 0x2bc830a3
    private val GEN = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

    private fun polymod(values: IntArray): Int {
        var chk = 1
        for (v in values) {
            val top = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0 until 5) if ((top ushr i) and 1 == 1) chk = chk xor GEN[i]
        }
        return chk
    }

    private fun hrpExpand(hrp: String): IntArray =
        (hrp.map { it.code ushr 5 } + 0 + hrp.map { it.code and 31 }).toIntArray()

    fun encode(hrp: String, data: ByteArray): String {
        val d = data.map { it.toInt() }.toIntArray()
        val mod = polymod(hrpExpand(hrp) + d + IntArray(6)) xor CONST
        val sb = StringBuilder(hrp).append('1')
        d.forEach { sb.append(CHARSET[it]) }
        for (i in 0 until 6) sb.append(CHARSET[(mod ushr (5 * (5 - i))) and 31])
        return sb.toString()
    }

    fun decode(s: String): Pair<String, ByteArray> {
        require(s == s.lowercase() || s == s.uppercase()) { "mixed case" }
        val str = s.lowercase()
        val pos = str.lastIndexOf('1')
        require(pos >= 1 && pos + 7 <= str.length) { "malformed bech32m" }
        val hrp = str.substring(0, pos)
        val d = str.substring(pos + 1).map { c -> CHARSET.indexOf(c).also { require(it >= 0) { "bad character" } } }.toIntArray()
        require(polymod(hrpExpand(hrp) + d) == CONST) { "bad checksum" }
        return hrp to d.copyOf(d.size - 6).map { it.toByte() }.toByteArray()
    }
}
