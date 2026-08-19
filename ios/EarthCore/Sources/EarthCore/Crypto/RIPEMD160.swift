import Foundation

/// RIPEMD-160, needed for the Cosmos address hash and absent from CryptoKit.
///
/// A Cosmos address is `RIPEMD160(SHA256(compressed pubkey))`, so there is no
/// way around implementing this; the alternative is dragging in a whole
/// crypto library for one 160-bit digest.
public enum RIPEMD160 {

    private static let zl: [UInt32] = [
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
        7, 4, 13, 1, 10, 6, 15, 3, 12, 0, 9, 5, 2, 14, 11, 8,
        3, 10, 14, 4, 9, 15, 8, 1, 2, 7, 0, 6, 13, 11, 5, 12,
        1, 9, 11, 10, 0, 8, 12, 4, 13, 3, 7, 15, 14, 5, 6, 2,
        4, 0, 5, 9, 7, 12, 2, 10, 14, 1, 3, 8, 11, 6, 15, 13,
    ]
    private static let zr: [UInt32] = [
        5, 14, 7, 0, 9, 2, 11, 4, 13, 6, 15, 8, 1, 10, 3, 12,
        6, 11, 3, 7, 0, 13, 5, 10, 14, 15, 8, 12, 4, 9, 1, 2,
        15, 5, 1, 3, 7, 14, 6, 9, 11, 8, 12, 2, 10, 0, 4, 13,
        8, 6, 4, 1, 3, 11, 15, 0, 5, 12, 2, 13, 9, 7, 10, 14,
        12, 15, 10, 4, 1, 5, 8, 7, 6, 2, 13, 14, 0, 3, 9, 11,
    ]
    private static let sl: [UInt32] = [
        11, 14, 15, 12, 5, 8, 7, 9, 11, 13, 14, 15, 6, 7, 9, 8,
        7, 6, 8, 13, 11, 9, 7, 15, 7, 12, 15, 9, 11, 7, 13, 12,
        11, 13, 6, 7, 14, 9, 13, 15, 14, 8, 13, 6, 5, 12, 7, 5,
        11, 12, 14, 15, 14, 15, 9, 8, 9, 14, 5, 6, 8, 6, 5, 12,
        9, 15, 5, 11, 6, 8, 13, 12, 5, 12, 13, 14, 11, 8, 5, 6,
    ]
    private static let sr: [UInt32] = [
        8, 9, 9, 11, 13, 15, 15, 5, 7, 7, 8, 11, 14, 14, 12, 6,
        9, 13, 15, 7, 12, 8, 9, 11, 7, 7, 12, 7, 6, 15, 13, 11,
        9, 7, 15, 11, 8, 6, 6, 14, 12, 13, 5, 14, 13, 13, 7, 5,
        15, 5, 8, 11, 14, 14, 6, 14, 6, 9, 12, 9, 12, 5, 15, 8,
        8, 5, 12, 9, 12, 5, 14, 6, 8, 13, 6, 5, 15, 13, 11, 11,
    ]
    private static let kl: [UInt32] = [0x0000_0000, 0x5a82_7999, 0x6ed9_eba1, 0x8f1b_bcdc, 0xa953_fd4e]
    private static let kr: [UInt32] = [0x50a2_8be6, 0x5c4d_d124, 0x6d70_3ef3, 0x7a6d_76e9, 0x0000_0000]

    private static func f(_ round: Int, _ x: UInt32, _ y: UInt32, _ z: UInt32) -> UInt32 {
        switch round {
        case 0: return x ^ y ^ z
        case 1: return (x & y) | (~x & z)
        case 2: return (x | ~y) ^ z
        case 3: return (x & z) | (y & ~z)
        default: return x ^ (y | ~z)
        }
    }

    public static func hash(_ message: Data) -> Data {
        var h: [UInt32] = [0x6745_2301, 0xefcd_ab89, 0x98ba_dcfe, 0x1032_5476, 0xc3d2_e1f0]

        // Merkle–Damgård padding, little-endian length — the same shape as MD4.
        var padded = message
        let bitLength = UInt64(message.count) * 8
        padded.append(0x80)
        while padded.count % 64 != 56 { padded.append(0x00) }
        for i in 0 ..< 8 { padded.append(UInt8((bitLength >> (8 * UInt64(i))) & 0xff)) }

        padded.withUnsafeBytes { raw in
            for blockStart in stride(from: 0, to: raw.count, by: 64) {
                var x = [UInt32](repeating: 0, count: 16)
                for i in 0 ..< 16 {
                    let o = blockStart + i * 4
                    x[i] = UInt32(raw[o]) | UInt32(raw[o + 1]) << 8
                        | UInt32(raw[o + 2]) << 16 | UInt32(raw[o + 3]) << 24
                }

                var (al, bl, cl, dl, el) = (h[0], h[1], h[2], h[3], h[4])
                var (ar, br, cr, dr, er) = (h[0], h[1], h[2], h[3], h[4])

                for i in 0 ..< 80 {
                    let round = i / 16

                    var t = al &+ f(round, bl, cl, dl) &+ x[Int(zl[i])] &+ kl[round]
                    t = rotl(t, sl[i]) &+ el
                    al = el; el = dl; dl = rotl(cl, 10); cl = bl; bl = t

                    t = ar &+ f(4 - round, br, cr, dr) &+ x[Int(zr[i])] &+ kr[round]
                    t = rotl(t, sr[i]) &+ er
                    ar = er; er = dr; dr = rotl(cr, 10); cr = br; br = t
                }

                let t = h[1] &+ cl &+ dr
                h[1] = h[2] &+ dl &+ er
                h[2] = h[3] &+ el &+ ar
                h[3] = h[4] &+ al &+ br
                h[4] = h[0] &+ bl &+ cr
                h[0] = t
            }
        }

        var out = Data(capacity: 20)
        for word in h {
            for i in 0 ..< 4 { out.append(UInt8((word >> (8 * UInt32(i))) & 0xff)) }
        }
        return out
    }

    private static func rotl(_ v: UInt32, _ n: UInt32) -> UInt32 {
        (v << n) | (v >> (32 - n))
    }
}
