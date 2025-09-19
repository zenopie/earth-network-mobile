package com.example.earthwallet.wallet.utils

/**
 * Minimal Bech32 encoder with convertBits helper (BIP-0173).
 * Supports lowercase HRP only. Decoding is not implemented.
 */
object Bech32 {

    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val GENERATORS = intArrayOf(
        0x3b6a57b2,
        0x26508e6d,
        0x1ea119fa,
        0x3d4233dd,
        0x2a1462b3
    )

    @JvmStatic
    fun encode(hrp: String?, data: ByteArray?): String {
        require(!hrp.isNullOrEmpty()) { "hrp required" }
        requireNotNull(data) { "data required" }

        val hrpLower = hrp.lowercase()
        val checksum = createChecksum(hrpLower, data)
        val combined = ByteArray(data.size + checksum.size)
        System.arraycopy(data, 0, combined, 0, data.size)
        System.arraycopy(checksum, 0, combined, data.size, checksum.size)

        val sb = StringBuilder(hrpLower.length + 1 + combined.size)
        sb.append(hrpLower)
        sb.append('1')
        for (b in combined) {
            require((b.toInt() and 0xFF) < CHARSET.length) { "data value out of range" }
            sb.append(CHARSET[b.toInt()])
        }
        return sb.toString()
    }

    private fun hrpExpand(hrp: String): ByteArray {
        val len = hrp.length
        val ret = ByteArray(len * 2 + 1)
        for (i in 0 until len) {
            ret[i] = (hrp[i].code shr 5).toByte()
        }
        ret[len] = 0
        for (i in 0 until len) {
            ret[len + 1 + i] = (hrp[i].code and 0x1f).toByte()
        }
        return ret
    }

    private fun polymod(values: ByteArray): Int {
        var chk = 1
        for (v in values) {
            val top = chk ushr 25
            chk = (chk and 0x1ffffff) shl 5 xor (v.toInt() and 0xff)
            for (i in 0 until 5) {
                if (((top shr i) and 1) == 1) {
                    chk = chk xor GENERATORS[i]
                }
            }
        }
        return chk
    }

    private fun createChecksum(hrp: String, data: ByteArray): ByteArray {
        val hrpExp = hrpExpand(hrp)
        val values = ByteArray(hrpExp.size + data.size + 6)
        System.arraycopy(hrpExp, 0, values, 0, hrpExp.size)
        System.arraycopy(data, 0, values, hrpExp.size, data.size)
        values.fill(0, hrpExp.size + data.size, values.size)
        val mod = polymod(values) xor 1
        val ret = ByteArray(6)
        for (i in 0 until 6) {
            ret[i] = ((mod shr (5 * (5 - i))) and 31).toByte()
        }
        return ret
    }

    /**
     * Convert groups of bits. For Bech32, fromBits=8, toBits=5 with padding.
     * Ported from reference implementation.
     */
    @JvmStatic
    fun convertBits(data: ByteArray, fromBits: Int, toBits: Int, pad: Boolean): ByteArray {
        var acc = 0
        var bits = 0
        val maxv = (1 shl toBits) - 1
        val maxAcc = (1 shl (fromBits + toBits - 1)) - 1

        val ret = mutableListOf<Byte>()
        for (value in data) {
            val b = value.toInt() and 0xFF
            require((b ushr fromBits) == 0) { "input value out of range" }
            acc = ((acc shl fromBits) or b) and maxAcc
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                ret.add(((acc shr bits) and maxv).toByte())
            }
        }
        if (pad) {
            if (bits > 0) {
                ret.add(((acc shl (toBits - bits)) and maxv).toByte())
            }
        } else if (bits >= fromBits || ((acc shl (toBits - bits)) and maxv) != 0) {
            throw IllegalArgumentException("Could not convert bits without padding")
        }

        return ret.toByteArray()
    }
}