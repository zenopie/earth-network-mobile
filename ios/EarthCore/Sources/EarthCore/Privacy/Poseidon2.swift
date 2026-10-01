import BigInt
import Foundation

/// Poseidon2 over BN254 (t=4, d=5, 8 full + 56 partial rounds), the sponge of
/// noir-lang/poseidon v0.3.0 (`Poseidon2::hash`) and the chain's
/// zk/poseidon2.Hash. Ports `privacy/zk/Poseidon2.kt` line for line: the IV is
/// len << 64 in the capacity slot, the rate is 3, and one element is squeezed
/// after a final duplex.
public enum Poseidon2 {
    private static let rate = 3
    private static let roundsFBegin = 4
    private static let roundsP = 56
    private static let total = 64

    private static let diag: [Fr] = Poseidon2Constants.matDiag4.map { Fr(BigUInt($0)!) }
    private static let rc: [[Fr]] = Poseidon2Constants.roundConstants.map { $0.map { Fr(BigUInt($0)!) } }
    private static let two64 = Fr(BigUInt(1) << 64)

    public static func hash(_ inputs: Fr...) -> Fr { hash(inputs) }

    public static func hash(_ inputs: [Fr]) -> Fr {
        var s0 = Fr.zero, s1 = Fr.zero, s2 = Fr.zero
        var s3 = Fr(UInt64(inputs.count)) * two64
        var cache = (Fr.zero, Fr.zero, Fr.zero)
        var n = 0
        func duplex() {
            if n < 1 { cache.0 = .zero }
            if n < 2 { cache.1 = .zero }
            if n < 3 { cache.2 = .zero }
            s0 = s0 + cache.0; s1 = s1 + cache.1; s2 = s2 + cache.2
            permute(&s0, &s1, &s2, &s3)
        }
        for x in inputs {
            if n == rate {
                duplex()
                cache.0 = x
                n = 1
            } else {
                switch n {
                case 0: cache.0 = x
                case 1: cache.1 = x
                default: cache.2 = x
                }
                n += 1
            }
        }
        duplex()
        return s0
    }

    @inline(__always)
    private static func sbox(_ x: Fr) -> Fr {
        let x2 = x.square()
        let x4 = x2.square()
        return x4 * x
    }

    @inline(__always)
    private static func external(_ s0: inout Fr, _ s1: inout Fr, _ s2: inout Fr, _ s3: inout Fr) {
        let t0 = s0 + s1
        let t1 = s2 + s3
        let t2 = s1 + s1 + t1
        let t3 = s3 + s3 + t0
        var t4 = t1 + t1; t4 = t4 + t4; t4 = t4 + t3
        var t5 = t0 + t0; t5 = t5 + t5; t5 = t5 + t2
        let t6 = t3 + t5
        let t7 = t2 + t4
        s0 = t6; s1 = t5; s2 = t7; s3 = t4
    }

    @inline(__always)
    private static func internalRound(_ s0: inout Fr, _ s1: inout Fr, _ s2: inout Fr, _ s3: inout Fr) {
        let sum = s0 + s1 + s2 + s3
        s0 = diag[0] * s0 + sum
        s1 = diag[1] * s1 + sum
        s2 = diag[2] * s2 + sum
        s3 = diag[3] * s3 + sum
    }

    private static func permute(_ s0: inout Fr, _ s1: inout Fr, _ s2: inout Fr, _ s3: inout Fr) {
        external(&s0, &s1, &s2, &s3)
        for r in 0 ..< roundsFBegin {
            let c = rc[r]
            s0 = sbox(s0 + c[0]); s1 = sbox(s1 + c[1]); s2 = sbox(s2 + c[2]); s3 = sbox(s3 + c[3])
            external(&s0, &s1, &s2, &s3)
        }
        let pEnd = roundsFBegin + roundsP
        for r in roundsFBegin ..< pEnd {
            s0 = sbox(s0 + rc[r][0])
            internalRound(&s0, &s1, &s2, &s3)
        }
        for r in pEnd ..< total {
            let c = rc[r]
            s0 = sbox(s0 + c[0]); s1 = sbox(s1 + c[1]); s2 = sbox(s2 + c[2]); s3 = sbox(s3 + c[3])
            external(&s0, &s1, &s2, &s3)
        }
    }
}
