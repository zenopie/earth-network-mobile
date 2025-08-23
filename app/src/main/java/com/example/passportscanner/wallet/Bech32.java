package com.example.passportscanner.wallet;

import java.util.Arrays;

/**
 * Minimal Bech32 encoder with convertBits helper (BIP-0173).
 * Supports lowercase HRP only. Decoding is not implemented.
 */
public final class Bech32 {

    private static final String CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";
    private static final int[] GENERATORS = new int[] {
            0x3b6a57b2,
            0x26508e6d,
            0x1ea119fa,
            0x3d4233dd,
            0x2a1462b3
    };

    private Bech32() {}

    public static String encode(String hrp, byte[] data) {
        if (hrp == null || hrp.isEmpty()) throw new IllegalArgumentException("hrp required");
        if (data == null) throw new IllegalArgumentException("data required");
        String hrpLower = hrp.toLowerCase();
        byte[] checksum = createChecksum(hrpLower, data);
        byte[] combined = new byte[data.length + checksum.length];
        System.arraycopy(data, 0, combined, 0, data.length);
        System.arraycopy(checksum, 0, combined, data.length, checksum.length);

        StringBuilder sb = new StringBuilder(hrpLower.length() + 1 + combined.length);
        sb.append(hrpLower);
        sb.append('1');
        for (byte b : combined) {
            if ((b & 0xFF) >= CHARSET.length()) throw new IllegalArgumentException("data value out of range");
            sb.append(CHARSET.charAt(b));
        }
        return sb.toString();
    }

    private static byte[] hrpExpand(String hrp) {
        int len = hrp.length();
        byte[] ret = new byte[len * 2 + 1];
        for (int i = 0; i < len; ++i) {
            ret[i] = (byte) (hrp.charAt(i) >> 5);
        }
        ret[len] = 0;
        for (int i = 0; i < len; ++i) {
            ret[len + 1 + i] = (byte) (hrp.charAt(i) & 0x1f);
        }
        return ret;
    }

    private static int polymod(byte[] values) {
        int chk = 1;
        for (byte v : values) {
            int top = chk >>> 25;
            chk = (chk & 0x1ffffff) << 5 ^ (v & 0xff);
            for (int i = 0; i < 5; i++) {
                if (((top >> i) & 1) == 1) {
                    chk ^= GENERATORS[i];
                }
            }
        }
        return chk;
    }

    private static byte[] createChecksum(String hrp, byte[] data) {
        byte[] hrpExp = hrpExpand(hrp);
        byte[] values = new byte[hrpExp.length + data.length + 6];
        System.arraycopy(hrpExp, 0, values, 0, hrpExp.length);
        System.arraycopy(data, 0, values, hrpExp.length, data.length);
        Arrays.fill(values, hrpExp.length + data.length, values.length, (byte) 0);
        int mod = polymod(values) ^ 1;
        byte[] ret = new byte[6];
        for (int i = 0; i < 6; ++i) {
            ret[i] = (byte) ((mod >> (5 * (5 - i))) & 31);
        }
        return ret;
    }

    /**
     * Convert groups of bits. For Bech32, fromBits=8, toBits=5 with padding.
     * Ported from reference implementation.
     */
    public static byte[] convertBits(byte[] data, int fromBits, int toBits, boolean pad) {
        int acc = 0;
        int bits = 0;
        int maxv = (1 << toBits) - 1;
        int maxAcc = (1 << (fromBits + toBits - 1)) - 1;

        java.util.ArrayList<Byte> ret = new java.util.ArrayList<>();
        for (byte value : data) {
            int b = value & 0xFF;
            if ((b >>> fromBits) != 0) {
                throw new IllegalArgumentException("input value out of range");
            }
            acc = ((acc << fromBits) | b) & maxAcc;
            bits += fromBits;
            while (bits >= toBits) {
                bits -= toBits;
                ret.add((byte) ((acc >> bits) & maxv));
            }
        }
        if (pad) {
            if (bits > 0) {
                ret.add((byte) ((acc << (toBits - bits)) & maxv));
            }
        } else if (bits >= fromBits || ((acc << (toBits - bits)) & maxv) != 0) {
            throw new IllegalArgumentException("Could not convert bits without padding");
        }

        byte[] out = new byte[ret.size()];
        for (int i = 0; i < ret.size(); i++) out[i] = ret.get(i);
        return out;
    }
}