//! transfer — 3-in / 3-out shielded transfer.
//!
//! Slots 0-1 (in and out) carry one hidden asset A; slot 2 (in and out) is
//! always ERTH and pays the public fee. Balances:
//!   in0 + in1 == out0 + out1 + v_pub_out      (asset A)
//!   in2       == out2 + fee                   (ERTH)
//! Every value is a u64 (range checked), so neither sum wraps the field.
//! v_pub_out leaves the pool as asset A: `asset_pub == A` whenever
//! v_pub_out > 0 (enforced as v_pub_out * (asset_pub - A) == 0). When
//! v_pub_out == 0 asset_pub is unconstrained; the chain should pin it to 0.
//!
//! Inputs: one spending key nk owns every real input.
//!   owner_pk = H(TAG_OWNER, nk); pc = H(TAG_PC, owner_pk, rho, rcm)
//!   cm = H(TAG_CM, asset, value, pc); nf = H(TAG_NF, nk, rho, position)
//! An input of value 0 is a dummy: its Merkle membership is not enforced, but
//! its nullifier is still published (the prover picks a fresh rho).
//! The three nullifiers must be pairwise distinct, so one note cannot fill two
//! slots (relevant when A == ERTH, where slot 0 and slot 2 hold the same asset).
//!
//! Outputs take the recipient's pc directly (value 0 is allowed); the recipient
//! checks the pc opening when it decrypts the note.
//!
//! Public input order:
//!   root, nf[3], cm_out[3], fee, v_pub_out, asset_pub, signal

use privacy_core::{ASSET_ERTH, bind, DEPTH, merkle_root, note_cm, note_nf, note_pc, owner_pk};

fn main(
    asset: Field,
    nk: Field,
    in_value: [u64; 3],
    in_rho: [Field; 3],
    in_rcm: [Field; 3],
    in_pos: [u32; 3],
    in_path: [[Field; DEPTH]; 3],
    out_value: [u64; 3],
    out_pc: [Field; 3],
    root: pub Field,
    nf: pub [Field; 3],
    cm_out: pub [Field; 3],
    fee: pub u64,
    v_pub_out: pub u64,
    asset_pub: pub Field,
    signal: pub Field,
) {
    let assets = [asset, asset, ASSET_ERTH];
    let opk = owner_pk(nk);

    for i in 0..3 {
        let pc = note_pc(opk, in_rho[i], in_rcm[i]);
        let cm = note_cm(assets[i], in_value[i], pc);
        let r = merkle_root(cm, in_pos[i], in_path[i]);
        // value != 0  =>  r == root
        assert((r - root) * (in_value[i] as Field) == 0, "input not in note tree");
        assert(note_nf(nk, in_rho[i], in_pos[i]) == nf[i], "bad nullifier");
        assert(note_cm(assets[i], out_value[i], out_pc[i]) == cm_out[i], "bad output commitment");
    }

    assert(nf[0] != nf[1], "duplicate nullifier");
    assert(nf[0] != nf[2], "duplicate nullifier");
    assert(nf[1] != nf[2], "duplicate nullifier");

    let in_a = in_value[0] as Field + in_value[1] as Field;
    let out_a = out_value[0] as Field + out_value[1] as Field + v_pub_out as Field;
    assert(in_a == out_a, "asset A unbalanced");
    assert(in_value[2] as Field == out_value[2] as Field + fee as Field, "fee slot unbalanced");

    assert((v_pub_out as Field) * (asset_pub - asset) == 0, "asset_pub != A");

    bind(signal);
}

// ---------------------------------------------------------------- tests

struct Fx {
    asset: Field,
    nk: Field,
    in_value: [u64; 3],
    in_rho: [Field; 3],
    in_rcm: [Field; 3],
    in_pos: [u32; 3],
    in_path: [[Field; DEPTH]; 3],
    out_value: [u64; 3],
    out_pc: [Field; 3],
    root: Field,
    nf: [Field; 3],
    cm_out: [Field; 3],
    fee: u64,
    v_pub_out: u64,
    asset_pub: Field,
    signal: Field,
}

fn run(f: Fx) {
    main(
        f.asset,
        f.nk,
        f.in_value,
        f.in_rho,
        f.in_rcm,
        f.in_pos,
        f.in_path,
        f.out_value,
        f.out_pc,
        f.root,
        f.nf,
        f.cm_out,
        f.fee,
        f.v_pub_out,
        f.asset_pub,
        f.signal,
    );
}

global ASSET_A: Field = 0xa55e7;

/// Tree holding the three input notes at positions 0, 1, 2 (dummies are left
/// out of the tree). Outputs: out_value, public fields filled in consistently.
fn fixture(asset: Field, in_value: [u64; 3], out_value: [u64; 3], fee: u64, v_pub_out: u64) -> Fx {
    let nk = 0x6e6b;
    let opk = owner_pk(nk);
    let in_rho = [11, 12, 13];
    let in_rcm = [21, 22, 23];
    let in_pos: [u32; 3] = [0, 1, 2];
    let assets = [asset, asset, ASSET_ERTH];
    let mut leaves = [0; 3];
    for i in 0..3 {
        if in_value[i] != 0 {
            leaves[i] = note_cm(assets[i], in_value[i], note_pc(opk, in_rho[i], in_rcm[i]));
        }
    }
    let z = privacy_core::zero_hashes();
    let n01 = privacy_core::h2(leaves[0], leaves[1]);
    let n23 = privacy_core::h2(leaves[2], 0);
    let mut paths = [[0; DEPTH]; 3];
    for p in 0..3 {
        for i in 2..DEPTH {
            paths[p][i] = z[i];
        }
    }
    paths[0][0] = leaves[1];
    paths[0][1] = n23;
    paths[1][0] = leaves[0];
    paths[1][1] = n23;
    paths[2][0] = 0;
    paths[2][1] = n01;
    let root = merkle_root(leaves[0], 0, paths[0]);

    let out_pc = [31, 32, 33];
    let mut nf = [0; 3];
    let mut cm_out = [0; 3];
    for i in 0..3 {
        nf[i] = note_nf(nk, in_rho[i], in_pos[i]);
        cm_out[i] = note_cm(assets[i], out_value[i], out_pc[i]);
    }
    let asset_pub = if v_pub_out > 0 { asset } else { 0 };
    Fx {
        asset,
        nk,
        in_value,
        in_rho,
        in_rcm,
        in_pos,
        in_path: paths,
        out_value,
        out_pc,
        root,
        nf,
        cm_out,
        fee,
        v_pub_out,
        asset_pub,
        signal: 0x5169,
    }
}

#[test]
fn test_private_send() {
    run(fixture(ASSET_A, [70, 30, 10], [60, 40, 7], 3, 0));
}

#[test]
fn test_unshield_with_zero_outputs() {
    run(fixture(ASSET_A, [70, 30, 10], [0, 0, 0], 10, 100));
}

#[test]
fn test_dummy_inputs() {
    // Only the fee note is real; A slots are dummies and outputs are zero.
    run(fixture(ASSET_A, [0, 0, 10], [0, 0, 4], 6, 0));
}

#[test]
fn test_asset_a_is_erth() {
    run(fixture(ASSET_ERTH, [5, 0, 10], [5, 0, 0], 10, 0));
}

#[test]
fn test_max_values() {
    let m: u64 = 0xffffffffffffffff;
    run(fixture(ASSET_A, [m, m, m], [m, m - 1, 0], m, 1));
}

#[test(should_fail_with = "asset A unbalanced")]
fn test_inflate_a() {
    run(fixture(ASSET_A, [70, 30, 10], [61, 40, 7], 3, 0));
}

#[test(should_fail_with = "fee slot unbalanced")]
fn test_inflate_fee_slot() {
    run(fixture(ASSET_A, [70, 30, 10], [60, 40, 8], 3, 0));
}

#[test(should_fail_with = "asset A unbalanced")]
fn test_fee_from_a_slot_rejected() {
    // Paying the fee out of asset A instead of the ERTH slot.
    run(fixture(ASSET_A, [70, 30, 0], [57, 40, 0], 3, 0));
}

#[test(should_fail_with = "input not in note tree")]
fn test_wrong_root() {
    let mut f = fixture(ASSET_A, [70, 30, 10], [60, 40, 7], 3, 0);
    f.root = 1;
    run(f);
}

#[test(should_fail_with = "input not in note tree")]
fn test_claim_more_than_note() {
    // Input note is 70; claiming 80 changes cm so membership fails.
    let mut f = fixture(ASSET_A, [70, 30, 10], [70, 40, 7], 3, 0);
    f.in_value[0] = 80;
    run(f);
}

#[test(should_fail_with = "input not in note tree")]
fn test_wrong_nk() {
    let mut f = fixture(ASSET_A, [70, 30, 10], [60, 40, 7], 3, 0);
    f.nk = 0x999;
    for i in 0..3 {
        f.nf[i] = note_nf(f.nk, f.in_rho[i], f.in_pos[i]);
    }
    run(f);
}

#[test(should_fail_with = "input not in note tree")]
fn test_swap_asset() {
    // Spend an asset-A note as if it were asset B.
    let mut f = fixture(ASSET_A, [70, 30, 10], [60, 40, 7], 3, 0);
    f.asset = 0xb;
    for i in 0..2 {
        f.cm_out[i] = note_cm(0xb, f.out_value[i], f.out_pc[i]);
    }
    run(f);
}

#[test(should_fail_with = "bad nullifier")]
fn test_wrong_nullifier() {
    let mut f = fixture(ASSET_A, [70, 30, 10], [60, 40, 7], 3, 0);
    f.nf[1] = 5;
    run(f);
}

#[test(should_fail_with = "bad output commitment")]
fn test_output_asset_mismatch() {
    let mut f = fixture(ASSET_A, [70, 30, 10], [60, 40, 7], 3, 0);
    f.cm_out[0] = note_cm(0xb, 60, f.out_pc[0]);
    run(f);
}

#[test(should_fail_with = "bad output commitment")]
fn test_fee_output_not_erth() {
    let mut f = fixture(ASSET_A, [70, 30, 10], [60, 40, 7], 3, 0);
    f.cm_out[2] = note_cm(ASSET_A, 7, f.out_pc[2]);
    run(f);
}

#[test(should_fail_with = "asset_pub != A")]
fn test_unshield_wrong_public_asset() {
    let mut f = fixture(ASSET_A, [70, 30, 10], [0, 0, 0], 10, 100);
    f.asset_pub = ASSET_ERTH;
    run(f);
}

#[test(should_fail_with = "duplicate nullifier")]
fn test_same_note_twice() {
    // A == ERTH: the ERTH note at position 2 also placed in slot 0.
    let mut f = fixture(ASSET_ERTH, [0, 0, 10], [10, 0, 0], 10, 0);
    f.in_value[0] = 10;
    f.in_rho[0] = f.in_rho[2];
    f.in_rcm[0] = f.in_rcm[2];
    f.in_pos[0] = f.in_pos[2];
    f.in_path[0] = f.in_path[2];
    f.nf[0] = f.nf[2];
    f.out_value[0] = 20;
    f.cm_out[0] = note_cm(ASSET_ERTH, 20, f.out_pc[0]);
    run(f);
}

#[test(should_fail)]
fn test_value_overflow() {
    // A u64 above 2^64 is rejected by the type's range check.
    let mut f = fixture(ASSET_A, [70, 30, 10], [60, 40, 7], 3, 0);
    f.out_value[0] = 0xffffffffffffffff + 1;
    run(f);
}
