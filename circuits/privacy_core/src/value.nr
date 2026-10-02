//! Value commitments on Grumpkin (Noir's embedded curve) for the action
//! circuit. Must match earth-network-chain zk/orchard bit for bit.
//!
//!   G_a = hash_to_point(TAG_GEN, asset)       canonical value base of asset a
//!   R   = hash_to_point(TAG_CV_R, 0)          randomness / binding-signature base
//!   cv  = v_spend*G_{a_spend} - v_out*G_{a_out} + rcv*R
//!
//! hash_to_point(tag, input): x = H(tag, input, ctr), y^2 = x^3 - 17, y the
//! root with y <= (p-1)/2. The chain uses the least ctr; the circuit accepts
//! any ctr, which is sound (each ctr gives an independent point bound to the
//! same input). What must be canonical is the SIGN of y: (x, -y) = -G_a, and
//! v under G_a plus v under -G_a balance to nothing (inflation).

use crate::h3;
use std::embedded_curve_ops::{EmbeddedCurvePoint, EmbeddedCurveScalar, multi_scalar_mul};

pub global TAG_GEN: Field = 0x65617274682e67656e; // "earth.gen"
pub global TAG_CV_R: Field = 0x65617274682e63762e72; // "earth.cv.r"

/// (p-1)/2: y is canonical iff y <= HALF.
global HALF: Field = 10944121435919637611123202872628637544274182200208017171849102093287904247808;

/// R = hash_to_point(TAG_CV_R, 0) (ctr 0), pinned; test_r_derivation re-derives it.
pub global R: EmbeddedCurvePoint = EmbeddedCurvePoint {
    x: 0x07fc551d28471de4eb62cf956996e963f8a0877bb97afd963ad5f296973401ce,
    y: 0x17bbea25d2f097a980174168aaffe0d61e97da2edeb3eec90c3a5e8a3b9328a4,
};

/// The canonical point for (tag, input), constrained: x is the hash at the
/// prover's counter, y is on the curve and canonical.
pub fn hash_to_point(tag: Field, input: Field) -> EmbeddedCurvePoint {
    // Safety: (ctr, y) are only a hint; point_at recomputes x and constrains
    // y, so a bad hint fails the proof.
    let (ctr, y) = unsafe { hash_to_point_hint(tag, input) };
    point_at(tag, input, ctr, y)
}

/// The constraints of hash_to_point for a given hint (ctr, y).
pub fn point_at(tag: Field, input: Field, ctr: u32, y: Field) -> EmbeddedCurvePoint {
    let x = h3(tag, input, ctr as Field);
    assert(y * y == x * x * x - 17, "value base off the curve");
    // y <= (p-1)/2, i.e. y < HALF + 1. Without this, (x, -y) = -G passes.
    assert(!std::field::bn254::lt(HALF, y), "non-canonical value base");
    EmbeddedCurvePoint { x, y }
}

/// The value base of an asset id.
pub fn value_base(asset: Field) -> EmbeddedCurvePoint {
    hash_to_point(TAG_GEN, asset)
}

/// cv = v_spend*G_spend - v_out*G_out + rcv*R, one MSM.
pub fn value_commit(
    g_spend: EmbeddedCurvePoint,
    v_spend: u64,
    g_out: EmbeddedCurvePoint,
    v_out: u64,
    rcv: Field,
) -> EmbeddedCurvePoint {
    multi_scalar_mul(
        [g_spend, -g_out, R],
        [
            EmbeddedCurveScalar { lo: v_spend as Field, hi: 0 },
            EmbeddedCurveScalar { lo: v_out as Field, hi: 0 },
            EmbeddedCurveScalar::from_field(rcv),
        ],
    )
}

// ---------------------------------------------------------------- hints

unconstrained fn pow(b: Field, e: Field) -> Field {
    let bits: [bool; 254] = e.to_le_bits();
    let mut r: Field = 1;
    for i in 0..254 {
        r = r * r;
        if bits[253 - i] {
            r = r * b;
        }
    }
    r
}

/// p - 1 = 2^28 * Q.
global Q: Field = 81540058820840996586704275553141814055101440848469862132140264610111;
/// 5^Q, 5 being a non-residue.
global Z: Field = 19103219067921713944291392827692070036145651957329286315305642004821462161904;

unconstrained fn is_square(t: Field) -> bool {
    (t == 0) | (pow(t, HALF) == 1)
}

/// Tonelli-Shanks; t must be a nonzero square.
unconstrained fn sqrt(t: Field) -> Field {
    let mut m: u32 = 28;
    let mut c = Z;
    let mut tt = pow(t, Q);
    let mut r = pow(t, (Q + 1) / 2);
    for _ in 0..28 {
        if tt != 1 {
            // least i with tt^(2^i) == 1 (0 < i < m)
            let mut i: u32 = 0;
            let mut t2 = tt;
            for _ in 0..28 {
                if t2 != 1 {
                    t2 = t2 * t2;
                    i += 1;
                }
            }
            let mut b = c;
            let mut k = m - i - 1;
            for _ in 0..28 {
                if k > 0 {
                    b = b * b;
                    k -= 1;
                }
            }
            m = i;
            c = b * b;
            tt = tt * c;
            r = r * b;
        }
    }
    assert(r * r == t);
    r
}

pub unconstrained fn hash_to_point_hint(tag: Field, input: Field) -> (u32, Field) {
    let mut ctr: u32 = 0;
    let mut out: (u32, Field) = (0, 0);
    let mut found = false;
    for _ in 0..256 {
        if !found {
            let x = h3(tag, input, ctr as Field);
            let t = x * x * x - 17;
            if is_square(t) {
                let mut y = sqrt(t);
                if std::field::bn254::lt(HALF, y) {
                    y = -y;
                }
                out = (ctr, y);
                found = true;
            } else {
                ctr += 1;
            }
        }
    }
    assert(found);
    out
}

// ---------------------------------------------------------------- tests

#[test]
fn test_r_derivation() {
    assert(hash_to_point(TAG_CV_R, 0) == R);
}

#[test]
fn test_value_bases_match_go() {
    // Go parity: zk/orchard.HashToPoint(TagGen, AssetID("uerth"|"uanml")).
    let erth = value_base(crate::ASSET_ERTH);
    assert(erth.x == 0x16cbb75ad1d0a4a6d9237c82bdc3c63ec0fc5d5f3c847c8c4832836c091eae68);
    assert(erth.y == 0x0cc11824c96520b6268b50038a557c8d213b15ddd3bb586f61ea0ef2747a0c5c);
    // Safety: test
    let (ctr, _) = unsafe { hash_to_point_hint(TAG_GEN, asset_anml()) };
    assert(ctr == 3);
    let anml = value_base(asset_anml());
    assert(anml.x == 0x18a0d714f8b0fc0cac3f1e90dda89ab60f20b1867a1e327e5b76fb704cf587a5);
    assert(anml.y == 0x1356a023e45ee34fc5d1cb2a02eb12dd38f7ed5afe0b25b30adbce930e443bd1);
}

/// AssetID("uanml") = H(TAG_ASSET, 5, "uanml").
fn asset_anml() -> Field {
    crate::h3(crate::TAG_ASSET, 5, 0x75616e6d6c)
}

#[test]
fn test_bases_independent_of_r() {
    assert(value_base(crate::ASSET_ERTH) != R);
}

#[test]
fn test_point_arithmetic_matches_go() {
    // Go parity: zk/orchard TestNoirVectors (gnark-crypto ecc/grumpkin).
    let erth = value_base(crate::ASSET_ERTH);
    let anml = value_base(asset_anml());
    let v1 = value_commit(erth, 1000000, anml, 500, 7);
    assert(v1.x == 0x0eeeab2eff032b7092fcb4671d7118f79ac8d4bbf024c02e66350bdaf5a8fc9d);
    assert(v1.y == 0x2a03b86e5fb0e98a9227c939b215da80bea36ef51204d60b95b627932563db60);
    let v2 = R + erth;
    assert(v2.x == 0x044778190901765d4f027e2382ba88063fdfdb74e273c11f439440f8dfa4ee16);
    assert(v2.y == 0x206474e0f7353228d22ad7c896c38e67dac395992d9c9b0372f2e7601e563dc1);
    // rcv = -1 (= r - 1 as a scalar), u64 max spend
    let v3 = value_commit(anml, 0xffffffffffffffff, anml, 1, -1);
    assert(v3.x == 0x0cc7252fc1726a374708a6a298521ad29730dfd14ca5c39fa0ce56b84c0efb41);
    assert(v3.y == 0x269a187559ea4f5c2082d61e3cef850b84f37f100cae309a0291000ff80f0607);
}
