//! action — one spend and one output of an Orchard-style shielded bundle
//! (earth-network-chain ORCHARD_DESIGN.md). A bundle is N >= 2 of these, each
//! proven on its own, plus a public value balance per asset and a binding
//! signature the chain checks against the actions' value commitments.
//!
//! Spend (dummy when s_value == 0: the Merkle path is not enforced, the
//! nullifier is still published and spent):
//!   owner_pk = H(TAG_OWNER, nk); pc = H(TAG_PC, owner_pk, rho, rcm)
//!   cm = H(TAG_CM, s_asset, s_value, pc) in the note tree at s_pos under anchor
//!   nf = H(TAG_NF, nk, rho, s_pos)
//! Output (dummy when o_value == 0, nothing special):
//!   cm_out = H(TAG_CM, o_asset, o_value, o_pc)
//! Both values are at most 2^63 - 1 (privacy_core NOTE_VALUE_BITS, inside
//! note_cm): what every wallet holds and the chain mints (ORCHARD_DESIGN
//! section 16), so no bundle creates a note its owner's wallet cannot see.
//! The spend bound is implied (every note in the tree came from a bounded
//! output or a bounded chain mint) but checked anyway, at no extra size.
//! Net value commitment, each asset under its own canonical base:
//!   cv = s_value*G(s_asset) - o_value*G(o_asset) + rcv*R
//! Binding: sighash, the chain's hash of the whole msg (every action's anchor,
//! nf, cm_out, cv and ciphertext, the balances, the msg's own fields), so a
//! proof cannot be lifted into another tx.
//!
//! The spend and the output may be different assets. The chain sums every
//! action's cv, subtracts each public balance times its base and verifies the
//! binding signature under the result; it balances only if, per asset, spends
//! = outputs + public balance.
//!
//! Soundness hinges on BOTH value bases having the canonical y (value.nr
//! point_at): (x, -y) = -G is the one base with a known relation to G, and
//! v under G plus v under -G balances to nothing. The chain cannot detect a
//! violation; only this circuit refuses it (test_negated_*_base_rejected).
//!
//! Dummies need no flag: a value-0 spend or output adds nothing to cv. The
//! wallet gives a dummy spend a fresh rho (unique nf, recorded like any
//! other) and any anchor in the chain's window; a dummy output a random pc.
//!
//! Size (bb v5.0.0 gates, noir-recursive): 8,098, 94 below the 2^13 = 8,192
//! dyadic size (8,120 before the 2^63 - 1 note bound). Any added constraint risks 2^14 and doubles prove time:
//! re-run `bb gates` after every change (scripts/orchard-bundles.sh prints it).
//!
//! Public input order: anchor, nf, cm_out, cv_x, cv_y, sighash

use privacy_core::{bind, DEPTH, merkle_root, note_cm, note_nf, note_pc, owner_pk};
use privacy_core::value::{hash_to_point_hint, point_at, TAG_GEN, value_commit};

fn main(
    nk: Field,
    s_asset: Field,
    s_value: u64,
    s_rho: Field,
    s_rcm: Field,
    s_pos: u32,
    s_path: [Field; DEPTH],
    o_asset: Field,
    o_value: u64,
    o_pc: Field,
    rcv: Field,
    anchor: pub Field,
    nf: pub Field,
    cm_out: pub Field,
    cv_x: pub Field,
    cv_y: pub Field,
    sighash: pub Field,
) {
    // Safety: the bases' hints are checked by point_at inside action.
    let s_hint = unsafe { hash_to_point_hint(TAG_GEN, s_asset) };
    // Safety: as above.
    let o_hint = unsafe { hash_to_point_hint(TAG_GEN, o_asset) };
    action(
        nk,
        s_asset,
        s_value,
        s_rho,
        s_rcm,
        s_pos,
        s_path,
        o_asset,
        o_value,
        o_pc,
        rcv,
        s_hint,
        o_hint,
        anchor,
        nf,
        cm_out,
        cv_x,
        cv_y,
        sighash,
    );
}

fn action(
    nk: Field,
    s_asset: Field,
    s_value: u64,
    s_rho: Field,
    s_rcm: Field,
    s_pos: u32,
    s_path: [Field; DEPTH],
    o_asset: Field,
    o_value: u64,
    o_pc: Field,
    rcv: Field,
    s_hint: (u32, Field),
    o_hint: (u32, Field),
    anchor: Field,
    nf: Field,
    cm_out: Field,
    cv_x: Field,
    cv_y: Field,
    sighash: Field,
) {
    let cm = note_cm(s_asset, s_value, note_pc(owner_pk(nk), s_rho, s_rcm));
    let r = merkle_root(cm, s_pos, s_path);
    // value != 0  =>  r == anchor
    assert((r - anchor) * (s_value as Field) == 0, "spend not in note tree");
    assert(note_nf(nk, s_rho, s_pos) == nf, "bad nullifier");
    assert(note_cm(o_asset, o_value, o_pc) == cm_out, "bad output commitment");

    let g_s = point_at(TAG_GEN, s_asset, s_hint.0, s_hint.1);
    let g_o = point_at(TAG_GEN, o_asset, o_hint.0, o_hint.1);
    let cv = value_commit(g_s, s_value, g_o, o_value, rcv);
    assert((cv.x == cv_x) & (cv.y == cv_y), "bad value commitment");

    bind(sighash);
}

// ---------------------------------------------------------------- tests

use privacy_core::{ASSET_ERTH, h4, TAG_CM};
use privacy_core::value::{point_hint_at, R, value_base};
use std::embedded_curve_ops::{EmbeddedCurvePoint, EmbeddedCurveScalar, multi_scalar_mul};

global ASSET_A: Field = 0xa55e7;

struct Fx {
    nk: Field,
    s_asset: Field,
    s_value: u64,
    s_rho: Field,
    s_rcm: Field,
    s_pos: u32,
    s_path: [Field; DEPTH],
    o_asset: Field,
    o_value: u64,
    o_pc: Field,
    rcv: Field,
    s_hint: (u32, Field),
    o_hint: (u32, Field),
    anchor: Field,
    nf: Field,
    cm_out: Field,
    cv_x: Field,
    cv_y: Field,
    sighash: Field,
}

fn run(f: Fx) {
    action(
        f.nk,
        f.s_asset,
        f.s_value,
        f.s_rho,
        f.s_rcm,
        f.s_pos,
        f.s_path,
        f.o_asset,
        f.o_value,
        f.o_pc,
        f.rcv,
        f.s_hint,
        f.o_hint,
        f.anchor,
        f.nf,
        f.cm_out,
        f.cv_x,
        f.cv_y,
        f.sighash,
    );
}

/// Through main, as a prover runs it (hints computed in-circuit).
fn run_main(f: Fx) {
    main(
        f.nk,
        f.s_asset,
        f.s_value,
        f.s_rho,
        f.s_rcm,
        f.s_pos,
        f.s_path,
        f.o_asset,
        f.o_value,
        f.o_pc,
        f.rcv,
        f.anchor,
        f.nf,
        f.cm_out,
        f.cv_x,
        f.cv_y,
        f.sighash,
    );
}

fn hint(asset: Field) -> (u32, Field) {
    // Safety: test helper
    unsafe {
        hash_to_point_hint(TAG_GEN, asset)
    }
}

/// The spend note sits at position 5 of an otherwise empty tree (a dummy
/// spend is left out). The public cv is computed independently of
/// value_commit, as a sum of separate scalar multiplications. Commitments
/// are taken with raw_cm, without note_cm's bound, so a fixture can hold a
/// value above 2^63 - 1 that only the circuit refuses.
fn fixture(s_asset: Field, s_value: u64, o_asset: Field, o_value: u64) -> Fx {
    let nk = 0x6e6b;
    let s_rho = 11;
    let s_rcm = 21;
    let s_pos: u32 = 5;
    let z = privacy_core::zero_hashes();
    let mut s_path = [0; DEPTH];
    for i in 0..DEPTH {
        s_path[i] = z[i];
    }
    let leaf = if s_value != 0 {
        raw_cm(s_asset, s_value, note_pc(owner_pk(nk), s_rho, s_rcm))
    } else {
        0
    };
    let anchor = merkle_root(leaf, s_pos, s_path);
    let o_pc = 31;
    let rcv = 0x1234567890abcdef1234567890abcdef1234567890abcdef;
    let cv = expected_cv(s_asset, s_value, o_asset, o_value, rcv);
    Fx {
        nk,
        s_asset,
        s_value,
        s_rho,
        s_rcm,
        s_pos,
        s_path,
        o_asset,
        o_value,
        o_pc,
        rcv,
        s_hint: hint(s_asset),
        o_hint: hint(o_asset),
        anchor,
        nf: note_nf(nk, s_rho, s_pos),
        cm_out: raw_cm(o_asset, o_value, o_pc),
        cv_x: cv.x,
        cv_y: cv.y,
        sighash: 0x5169,
    }
}

/// H(TAG_CM, asset, value, pc) for any u64 value (note_cm without its bound).
fn raw_cm(asset: Field, value: u64, pc: Field) -> Field {
    h4(TAG_CM, asset, value as Field, pc)
}

fn mul(p: EmbeddedCurvePoint, v: Field) -> EmbeddedCurvePoint {
    multi_scalar_mul([p], [EmbeddedCurveScalar::from_field(v)])
}

fn expected_cv(
    s_asset: Field,
    s_value: u64,
    o_asset: Field,
    o_value: u64,
    rcv: Field,
) -> EmbeddedCurvePoint {
    mul(value_base(s_asset), s_value as Field) - mul(value_base(o_asset), o_value as Field)
        + mul(R, rcv)
}

#[test]
fn test_send_same_asset() {
    run_main(fixture(ASSET_A, 100, ASSET_A, 60));
}

#[test]
fn test_spend_only() {
    run_main(fixture(ASSET_ERTH, 100, ASSET_ERTH, 0));
}

#[test]
fn test_output_only_dummy_spend() {
    // Dummy spend (value 0): its path is not checked, so any anchor passes.
    let mut f = fixture(ASSET_A, 0, ASSET_A, 40);
    f.anchor = 0xdead;
    run_main(f);
}

#[test]
fn test_mixed_assets() {
    // Spend ERTH, output asset A: each term under its own base.
    run_main(fixture(ASSET_ERTH, 100, ASSET_A, 7));
}

#[test]
fn test_both_dummy() {
    run_main(fixture(ASSET_A, 0, ASSET_A, 0));
}

#[test]
fn test_max_values() {
    let m: u64 = 0x7fffffffffffffff;
    run_main(fixture(ASSET_A, m, ASSET_ERTH, m));
}

// ---- the 2^63 - 1 note bound: a wallet holds nothing larger

#[test(should_fail_with = "assert_max_bit_size")]
fn test_output_above_note_max() {
    // A well-formed cm_out and cv for an output of 2^63: only the bound fails.
    run_main(fixture(ASSET_A, 0, ASSET_A, 0x8000000000000000));
}

#[test(should_fail_with = "assert_max_bit_size")]
fn test_output_u64_max() {
    run_main(fixture(ASSET_A, 0, ASSET_ERTH, 0xffffffffffffffff));
}

#[test(should_fail_with = "assert_max_bit_size")]
fn test_spend_above_note_max() {
    // A 2^63 note at position 5 under the anchor (none can exist on chain).
    run_main(fixture(ASSET_A, 0x8000000000000000, ASSET_A, 0));
}

#[test]
fn test_rcv_zero_and_max() {
    let mut f = fixture(ASSET_A, 9, ASSET_A, 9);
    f.rcv = 0;
    let cv = expected_cv(ASSET_A, 9, ASSET_A, 9, 0);
    f.cv_x = cv.x;
    f.cv_y = cv.y;
    // 9*G - 9*G + 0*R = infinity, (0, 0)
    assert((cv.x == 0) & (cv.y == 0));
    run_main(f);
    let mut g = fixture(ASSET_A, 9, ASSET_A, 3);
    g.rcv = -1;
    let cv = expected_cv(ASSET_A, 9, ASSET_A, 3, -1);
    g.cv_x = cv.x;
    g.cv_y = cv.y;
    run_main(g);
}

#[test]
fn test_cv_is_additive() {
    // Two actions sending 100 of A as 60 + 40 net out: cv1 + cv2 = (rcv1 + rcv2) R.
    let a = expected_cv(ASSET_A, 100, ASSET_A, 60, 5);
    let b = expected_cv(ASSET_A, 0, ASSET_A, 40, 7);
    assert(a + b == mul(R, 12));
}

// ---- inflation and base attacks

#[test(should_fail_with = "non-canonical value base")]
fn test_negated_output_base_rejected() {
    // Inflation via -G: an output of v under -G_A carries cv = +v*G_A, which
    // a second output of v under G_A cancels, so two outputs of v would
    // balance with no spend. The output base's y must be canonical.
    let mut f = fixture(ASSET_A, 0, ASSET_A, 50);
    let (ctr, y) = f.o_hint;
    f.o_hint = (ctr, -y);
    let neg = expected_cv(ASSET_A, 0, ASSET_A, 0, f.rcv) + mul(value_base(ASSET_A), 50);
    f.cv_x = neg.x;
    f.cv_y = neg.y;
    run(f);
}

#[test(should_fail_with = "non-canonical value base")]
fn test_negated_spend_base_rejected() {
    let mut f = fixture(ASSET_A, 100, ASSET_A, 0);
    let (ctr, y) = f.s_hint;
    f.s_hint = (ctr, -y);
    run(f);
}

#[test]
fn test_negation_attack_would_balance() {
    // Why the check matters: these two outputs (values 50 under G and under
    // -G) have commitments summing to (rcv1 + rcv2) R, i.e. a valid binding
    // signature, 100 minted from nothing.
    let g = value_base(ASSET_A);
    let cv1 = mul(R, 3) - mul(g, 50);
    let cv2 = mul(R, 4) - mul(-g, 50);
    assert(cv1 + cv2 == mul(R, 7));
}

#[test(should_fail_with = "value base off the curve")]
fn test_other_assets_base_rejected() {
    // The output commits to asset A but the prover hands in asset ERTH's base
    // (ctr, y): x is recomputed from A, so ERTH's y is not on the curve there.
    let mut f = fixture(ASSET_A, 0, ASSET_A, 50);
    f.o_hint = hint(ASSET_ERTH);
    let cv = expected_cv(ASSET_A, 0, ASSET_ERTH, 50, f.rcv);
    f.cv_x = cv.x;
    f.cv_y = cv.y;
    run(f);
}

#[test(should_fail_with = "value base off the curve")]
fn test_arbitrary_base_rejected() {
    let mut f = fixture(ASSET_A, 0, ASSET_A, 50);
    f.o_hint = (f.o_hint.0, 12345);
    run(f);
}

#[test(should_fail_with = "bad value commitment")]
fn test_cv_for_other_asset_rejected() {
    // cm_out is an A note, cv claims the output was ERTH.
    let mut f = fixture(ASSET_A, 0, ASSET_A, 50);
    let cv = expected_cv(ASSET_A, 0, ASSET_ERTH, 50, f.rcv);
    f.cv_x = cv.x;
    f.cv_y = cv.y;
    run_main(f);
}

#[test(should_fail_with = "bad output commitment")]
fn test_output_asset_swapped() {
    // cv for an A output, but cm_out commits ERTH.
    let mut f = fixture(ASSET_A, 0, ASSET_A, 50);
    f.cm_out = note_cm(ASSET_ERTH, 50, f.o_pc);
    run_main(f);
}

#[test(should_fail_with = "bad value commitment")]
fn test_cv_understates_output() {
    let mut f = fixture(ASSET_A, 100, ASSET_A, 60);
    let cv = expected_cv(ASSET_A, 100, ASSET_A, 59, f.rcv);
    f.cv_x = cv.x;
    f.cv_y = cv.y;
    run_main(f);
}

#[test(should_fail_with = "bad value commitment")]
fn test_cv_overstates_spend() {
    let mut f = fixture(ASSET_A, 100, ASSET_A, 60);
    let cv = expected_cv(ASSET_A, 101, ASSET_A, 60, f.rcv);
    f.cv_x = cv.x;
    f.cv_y = cv.y;
    run_main(f);
}

#[test(should_fail_with = "bad value commitment")]
fn test_cv_wrong_rcv() {
    let mut f = fixture(ASSET_A, 100, ASSET_A, 60);
    f.rcv = f.rcv + 1;
    run_main(f);
}

#[test(should_fail_with = "spend not in note tree")]
fn test_spend_value_inflated() {
    // Claiming the note holds more changes its commitment.
    let mut f = fixture(ASSET_A, 100, ASSET_A, 60);
    f.s_value = 150;
    let cv = expected_cv(ASSET_A, 150, ASSET_A, 60, f.rcv);
    f.cv_x = cv.x;
    f.cv_y = cv.y;
    run_main(f);
}

#[test(should_fail_with = "spend not in note tree")]
fn test_spend_asset_swapped() {
    // Spending an A note as ERTH.
    let mut f = fixture(ASSET_A, 100, ASSET_A, 60);
    f.s_asset = ASSET_ERTH;
    f.s_hint = hint(ASSET_ERTH);
    let cv = expected_cv(ASSET_ERTH, 100, ASSET_A, 60, f.rcv);
    f.cv_x = cv.x;
    f.cv_y = cv.y;
    run_main(f);
}

#[test(should_fail_with = "spend not in note tree")]
fn test_wrong_anchor() {
    let mut f = fixture(ASSET_A, 100, ASSET_A, 60);
    f.anchor = 1;
    run_main(f);
}

#[test(should_fail_with = "spend not in note tree")]
fn test_wrong_nk() {
    let mut f = fixture(ASSET_A, 100, ASSET_A, 60);
    f.nk = 0x999;
    f.nf = note_nf(f.nk, f.s_rho, f.s_pos);
    run_main(f);
}

#[test(should_fail_with = "bad nullifier")]
fn test_wrong_nullifier() {
    let mut f = fixture(ASSET_A, 100, ASSET_A, 60);
    f.nf = 5;
    run_main(f);
}

#[test(should_fail_with = "bad nullifier")]
fn test_dummy_still_binds_nullifier() {
    let mut f = fixture(ASSET_A, 0, ASSET_A, 60);
    f.nf = 5;
    run_main(f);
}

#[test(should_fail)]
fn test_value_overflow() {
    let mut f = fixture(ASSET_A, 100, ASSET_A, 60);
    f.o_value = 0xffffffffffffffff + 1;
    run_main(f);
}

#[test]
fn test_non_minimal_counter_is_a_different_point() {
    // The circuit accepts any counter whose x lands on the curve. That base
    // is an independent hash point for the same asset: notes of A spent and
    // created under it still balance only against each other (per asset), and
    // it has no known relation to the least-counter base the chain uses for
    // public balances. ASSET_A: find a second counter.
    let (c0, _) = hint(ASSET_A);
    let mut found = false;
    for c in 1..16 {
        if !found {
            // Safety: test
            let ok = unsafe { on_curve_at(ASSET_A, c0 + c) };
            if ok {
                found = true;
            }
        }
    }
    assert(found);
}

unconstrained fn on_curve_at(asset: Field, ctr: u32) -> bool {
    let x = privacy_core::h3(TAG_GEN, asset, ctr as Field);
    let t = x * x * x - 17;
    // Euler's criterion: t^((p-1)/2) == 1.
    let mut r: Field = 1;
    let half = 10944121435919637611123202872628637544274182200208017171849102093287904247808;
    let bits: [bool; 254] = half.to_le_bits();
    for i in 0..254 {
        r = r * r;
        if bits[253 - i] {
            r = r * t;
        }
    }
    r == 1
}

#[test]
fn test_non_minimal_counter_accepted() {
    // A non-least counter with the canonical y is a valid base for the
    // circuit (documented, sound: see test_non_minimal_counter_is_a_different_point).
    let (c0, _) = hint(ASSET_A);
    let mut ctr: u32 = 0;
    let mut y: Field = 0;
    for c in 1..16 {
        if ctr == 0 {
            // Safety: test
            let (ok, yy) = unsafe { point_hint_at(TAG_GEN, ASSET_A, c0 + c) };
            if ok {
                ctr = c0 + c;
                y = yy;
            }
        }
    }
    assert(ctr != 0);
    let mut f = fixture(ASSET_A, 100, ASSET_A, 60);
    f.s_hint = (ctr, y);
    let g = point_at(TAG_GEN, ASSET_A, ctr, y);
    let cv = mul(g, 100) - mul(value_base(ASSET_A), 60) + mul(R, f.rcv);
    f.cv_x = cv.x;
    f.cv_y = cv.y;
    run(f);
}

#[test(should_fail_with = "bad value commitment")]
fn test_cv_negated_rejected() {
    // -cv turns a spend into an output in the balance: refused.
    let mut f = fixture(ASSET_A, 100, ASSET_A, 60);
    let cv = expected_cv(ASSET_A, 100, ASSET_A, 60, f.rcv);
    let neg = -cv;
    f.cv_x = neg.x;
    f.cv_y = neg.y;
    run_main(f);
}

#[test(should_fail_with = "bad value commitment")]
fn test_cv_infinity_claimed() {
    // A nonzero action claiming cv = infinity (0, 0).
    let mut f = fixture(ASSET_A, 100, ASSET_A, 60);
    f.cv_x = 0;
    f.cv_y = 0;
    run_main(f);
}

#[test(should_fail_with = "bad value commitment")]
fn test_cv_assets_swapped() {
    // Mixed action, cv computed with the spend and output assets swapped.
    let mut f = fixture(ASSET_ERTH, 100, ASSET_A, 7);
    let cv = expected_cv(ASSET_A, 100, ASSET_ERTH, 7, f.rcv);
    f.cv_x = cv.x;
    f.cv_y = cv.y;
    run_main(f);
}

#[test(should_fail_with = "bad value commitment")]
fn test_cv_without_rcv() {
    // The value terms alone (rcv dropped): the blinding must be rcv*R.
    let mut f = fixture(ASSET_A, 100, ASSET_A, 60);
    let cv = expected_cv(ASSET_A, 100, ASSET_A, 60, 0);
    f.cv_x = cv.x;
    f.cv_y = cv.y;
    run_main(f);
}

#[test(should_fail_with = "bad output commitment")]
fn test_cm_out_value_mismatch() {
    // cv and o_value say 61, cm_out commits 60.
    let mut f = fixture(ASSET_A, 100, ASSET_A, 60);
    f.o_value = 61;
    let cv = expected_cv(ASSET_A, 100, ASSET_A, 61, f.rcv);
    f.cv_x = cv.x;
    f.cv_y = cv.y;
    run_main(f);
}

#[test(should_fail_with = "spend not in note tree")]
fn test_wrong_position() {
    // The same note claimed at another leaf (a second nullifier for one note).
    let mut f = fixture(ASSET_A, 100, ASSET_A, 60);
    f.s_pos = 6;
    f.nf = note_nf(f.nk, f.s_rho, f.s_pos);
    run_main(f);
}

#[test(should_fail_with = "spend not in note tree")]
fn test_wrong_rcm() {
    let mut f = fixture(ASSET_A, 100, ASSET_A, 60);
    f.s_rcm = f.s_rcm + 1;
    run_main(f);
}

#[test(should_fail)]
fn test_spend_value_overflow() {
    let mut f = fixture(ASSET_A, 100, ASSET_A, 60);
    f.s_value = 0xffffffffffffffff + 1;
    run_main(f);
}

#[test(should_fail)]
fn test_position_overflow() {
    // u32 positions: pos + 2^32 would walk the same path with another nf.
    let mut f = fixture(ASSET_A, 100, ASSET_A, 60);
    f.s_pos = 0xffffffff + 6;
    run_main(f);
}

#[test(should_fail_with = "non-canonical value base")]
fn test_negated_base_on_both_sides_rejected() {
    // Spend and output both under -G (a "consistent" negated action) is still
    // refused: the check is per base.
    let mut f = fixture(ASSET_A, 100, ASSET_A, 100);
    let (cs, ys) = f.s_hint;
    let (co, yo) = f.o_hint;
    f.s_hint = (cs, -ys);
    f.o_hint = (co, -yo);
    run(f);
}

#[test(should_fail_with = "value base off the curve")]
fn test_wrong_counter_rejected() {
    // The right y, another counter: x changes, so y is off the curve there.
    let mut f = fixture(ASSET_A, 100, ASSET_A, 60);
    let (c, y) = f.s_hint;
    f.s_hint = (c + 1, y);
    run(f);
}
