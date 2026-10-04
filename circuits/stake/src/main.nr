//! stake — one owner-locked operation on the stake note tree
//! (earth-network-chain x/shieldedstaking, ORCHARD_DESIGN.md section 13).
//!
//! Delegated stake (derth/<valoper>) lives in its own append-only tree, never
//! in the shielded pool, and can never change owner: every note this circuit spends
//! or creates, and every note it lets the chain mint, belongs to the one
//! owner_pk = H(TAG_OWNER, nk) the prover knows nk for.
//!
//!   spc      = H(TAG_SPC, owner_pk, rho, rcm)
//!   cm       = H(TAG_STAKE, asset, amount, spc)
//!   nf       = H(TAG_SNF, nk, rho, position)
//!   otag     = H(TAG_OTAG, owner_pk, salt)
//!
//! Up to two inputs (amount 0 = none: no path, nf = 0) under `anchor`, up to
//! two outputs (amount 0 = none: cm_out = 0), all of one `asset`, and
//!
//!   in_0 + in_1 + v_in == out_0 + out_1 + v_out
//!
//! Every note amount (in_i, out_i) is at most 2^63 - 1 (privacy_core
//! NOTE_VALUE_BITS, inside stake_cm): what every wallet holds and the chain
//! mints. v_in and v_out are public u64 amounts, not notes.
//!
//! v_in is value the chain adds (none today), v_out value leaving the notes
//! to the chain (undelegated, locked). spc_mint is a stake pc of the same
//! owner the chain may mint a note to (a delegation's derth, an unlocked
//! position; an undelegation pays out to a pool pc instead); otag a
//! commitment to the same owner the chain may store (a position) or compare
//! (a position's later update, unlock or vote). Binding: the msg's sighash.
//! (Unbonding claims, unbond/<valoper>/<epoch>, and spend-to-vote are
//! retired: the asset is always a derth asset, or 0 for a position msg.)
//!
//! The chain fixes, per msg, which of these must be zero.
//!
//! Public input order:
//!   anchor, asset, nf_0, nf_1, cm_out_0, cm_out_1, v_in, v_out, spc_mint,
//!   otag, sighash

use privacy_core::{bind, DEPTH, merkle_root, owner_pk, owner_tag, stake_cm, stake_nf, stake_pc};
use privacy_core::{h4, TAG_STAKE};

fn main(
    nk: Field,
    in_amount: [u64; 2],
    in_rho: [Field; 2],
    in_rcm: [Field; 2],
    in_pos: [u32; 2],
    in_path: [[Field; DEPTH]; 2],
    out_amount: [u64; 2],
    out_rho: [Field; 2],
    out_rcm: [Field; 2],
    mint_rho: Field,
    mint_rcm: Field,
    tag_salt: Field,
    anchor: pub Field,
    asset: pub Field,
    nf_0: pub Field,
    nf_1: pub Field,
    cm_out_0: pub Field,
    cm_out_1: pub Field,
    v_in: pub u64,
    v_out: pub u64,
    spc_mint: pub Field,
    otag: pub Field,
    sighash: pub Field,
) {
    let opk = owner_pk(nk);
    let nf = [nf_0, nf_1];
    let cm_out = [cm_out_0, cm_out_1];
    let mut total_in = v_in as Field;
    let mut total_out = v_out as Field;
    for i in 0..2 {
        let real = (in_amount[i] != 0) as Field;
        let cm = stake_cm(asset, in_amount[i], stake_pc(opk, in_rho[i], in_rcm[i]));
        let r = merkle_root(cm, in_pos[i], in_path[i]);
        assert((r - anchor) * real == 0, "input not in stake tree");
        assert(nf[i] == stake_nf(nk, in_rho[i], in_pos[i]) * real, "bad nullifier");
        total_in += in_amount[i] as Field;

        let made = (out_amount[i] != 0) as Field;
        let c = stake_cm(asset, out_amount[i], stake_pc(opk, out_rho[i], out_rcm[i]));
        assert(cm_out[i] == c * made, "bad output commitment");
        total_out += out_amount[i] as Field;
    }
    // Each side is at most 2 * (2^63 - 1) + (2^64 - 1): no wrap in the field.
    assert(total_in == total_out, "amounts do not balance");
    assert(spc_mint == stake_pc(opk, mint_rho, mint_rcm), "bad mint pc");
    assert(otag == owner_tag(opk, tag_salt), "bad owner tag");
    bind(sighash);
}

// ---------------------------------------------------------------- tests

global NK: Field = 0x5eed;
global ASSET: Field = 0xde27;
global SIGHASH: Field = 0x5164;

struct In {
    amount: u64,
    rho: Field,
    rcm: Field,
    pos: u32,
    path: [Field; DEPTH],
}

struct Out {
    amount: u64,
    rho: Field,
    rcm: Field,
}

struct St {
    nk: Field,
    ins: [In; 2],
    outs: [Out; 2],
    mint_rho: Field,
    mint_rcm: Field,
    salt: Field,
    anchor: Field,
    asset: Field,
    nf: [Field; 2],
    cm_out: [Field; 2],
    v_in: u64,
    v_out: u64,
    spc_mint: Field,
    otag: Field,
}

fn none_in() -> In {
    In { amount: 0, rho: 0x77, rcm: 0x78, pos: 0, path: [0; DEPTH] }
}

fn none_out() -> Out {
    Out { amount: 0, rho: 0x99, rcm: 0x9a }
}

/// H(TAG_STAKE, asset, amount, spc) for any u64 amount (stake_cm without its
/// bound), so a test can hold an amount above 2^63 - 1 that only the circuit
/// refuses.
fn cm_of(nk: Field, asset: Field, amount: u64, rho: Field, rcm: Field) -> Field {
    h4(TAG_STAKE, asset, amount as Field, stake_pc(owner_pk(nk), rho, rcm))
}

/// A tree holding notes a (position 0) and b (position 1) of NK, asset ASSET:
/// the two inputs with their paths, and the root.
fn two_notes(a: u64, b: u64) -> ([In; 2], Field) {
    let z = privacy_core::zero_hashes();
    let la = cm_of(NK, ASSET, a, 0xa1, 0xa2);
    let lb = cm_of(NK, ASSET, b, 0xb1, 0xb2);
    let mut pa = [0; DEPTH];
    let mut pb = [0; DEPTH];
    for i in 1..DEPTH {
        pa[i] = z[i];
        pb[i] = z[i];
    }
    pa[0] = lb;
    pb[0] = la;
    let root = merkle_root(la, 0, pa);
    assert(root == merkle_root(lb, 1, pb));
    ([In { amount: a, rho: 0xa1, rcm: 0xa2, pos: 0, path: pa }, In { amount: b, rho: 0xb1, rcm: 0xb2, pos: 1, path: pb }], root)
}

/// Fills every public value as an honest prover would.
fn honest(nk: Field, ins: [In; 2], outs: [Out; 2], anchor: Field, v_in: u64, v_out: u64) -> St {
    let mut nf = [0; 2];
    let mut cm_out = [0; 2];
    for i in 0..2 {
        if ins[i].amount != 0 {
            nf[i] = stake_nf(nk, ins[i].rho, ins[i].pos);
        }
        if outs[i].amount != 0 {
            cm_out[i] = cm_of(nk, ASSET, outs[i].amount, outs[i].rho, outs[i].rcm);
        }
    }
    St {
        nk,
        ins,
        outs,
        mint_rho: 0x31,
        mint_rcm: 0x32,
        salt: 0x5a17,
        anchor,
        asset: ASSET,
        nf,
        cm_out,
        v_in,
        v_out,
        spc_mint: stake_pc(owner_pk(nk), 0x31, 0x32),
        otag: owner_tag(owner_pk(nk), 0x5a17),
    }
}

fn prove(s: St) {
    main(
        s.nk,
        [s.ins[0].amount, s.ins[1].amount],
        [s.ins[0].rho, s.ins[1].rho],
        [s.ins[0].rcm, s.ins[1].rcm],
        [s.ins[0].pos, s.ins[1].pos],
        [s.ins[0].path, s.ins[1].path],
        [s.outs[0].amount, s.outs[1].amount],
        [s.outs[0].rho, s.outs[1].rho],
        [s.outs[0].rcm, s.outs[1].rcm],
        s.mint_rho,
        s.mint_rcm,
        s.salt,
        s.anchor,
        s.asset,
        s.nf[0],
        s.nf[1],
        s.cm_out[0],
        s.cm_out[1],
        s.v_in,
        s.v_out,
        s.spc_mint,
        s.otag,
        SIGHASH,
    );
}

// ---- positive: every op the chain uses

#[test]
fn test_delegate_no_inputs() {
    // No inputs, no outputs: proves only the owner of spc_mint (and otag).
    prove(honest(NK, [none_in(), none_in()], [none_out(), none_out()], 0, 0, 0));
}

#[test]
fn test_merge() {
    let (ins, root) = two_notes(300, 200);
    let outs = [Out { amount: 500, rho: 0xc1, rcm: 0xc2 }, none_out()];
    prove(honest(NK, ins, outs, root, 0, 0));
}

#[test]
fn test_split() {
    let (ins, root) = two_notes(300, 200);
    let outs = [Out { amount: 120, rho: 0xc1, rcm: 0xc2 }, Out { amount: 180, rho: 0xd1, rcm: 0xd2 }];
    prove(honest(NK, [ins[0], none_in()], outs, root, 0, 0));
}

#[test]
fn test_undelegate_with_change() {
    let (ins, root) = two_notes(300, 200);
    let outs = [Out { amount: 50, rho: 0xc1, rcm: 0xc2 }, none_out()];
    prove(honest(NK, [none_in(), ins[1]], outs, root, 0, 150));
}

#[test]
fn test_vote_whole_notes() {
    let (ins, root) = two_notes(300, 200);
    prove(honest(NK, ins, [none_out(), none_out()], root, 0, 500));
}

#[test]
fn test_note_max() {
    let m: u64 = 0x7fffffffffffffff;
    let (ins, root) = two_notes(m, m);
    let outs = [Out { amount: m, rho: 0xc1, rcm: 0xc2 }, Out { amount: m, rho: 0xd1, rcm: 0xd2 }];
    prove(honest(NK, ins, outs, root, 0, 0));
}

#[test]
fn test_public_v_out_above_note_max() {
    // v_out is a public amount leaving the notes, not a note: two full notes
    // leave as 2^64 - 2.
    let m: u64 = 0x7fffffffffffffff;
    let (ins, root) = two_notes(m, m);
    prove(honest(NK, ins, [none_out(), none_out()], root, 0, 0xfffffffffffffffe));
}

// ---- the 2^63 - 1 note bound: a wallet holds nothing larger

#[test(should_fail_with = "assert_max_bit_size")]
fn test_output_above_note_max() {
    // Two 2^62 notes merged into one of 2^63: balanced, only the bound fails.
    let h: u64 = 0x4000000000000000;
    let (ins, root) = two_notes(h, h);
    let outs = [Out { amount: 0x8000000000000000, rho: 0xc1, rcm: 0xc2 }, none_out()];
    prove(honest(NK, ins, outs, root, 0, 0));
}

#[test(should_fail_with = "assert_max_bit_size")]
fn test_output_above_note_max_from_v_in() {
    let outs = [Out { amount: 0xffffffffffffffff, rho: 0xc1, rcm: 0xc2 }, none_out()];
    prove(honest(NK, [none_in(), none_in()], outs, 0, 0xffffffffffffffff, 0));
}

#[test(should_fail_with = "assert_max_bit_size")]
fn test_input_above_note_max() {
    // A 2^63 note in the tree (none can exist on chain), split in two.
    let (ins, root) = two_notes(0x8000000000000000, 1);
    let h: u64 = 0x4000000000000000;
    let outs = [Out { amount: h, rho: 0xc1, rcm: 0xc2 }, Out { amount: h, rho: 0xd1, rcm: 0xd2 }];
    prove(honest(NK, [ins[0], none_in()], outs, root, 0, 0));
}

// ---- negative

#[test(should_fail_with = "bad output commitment")]
fn test_output_to_another_owner() {
    // The transfer attempt: an output note for another key's owner_pk.
    let (ins, root) = two_notes(300, 200);
    let mut s = honest(NK, [ins[0], none_in()], [Out { amount: 300, rho: 0xc1, rcm: 0xc2 }, none_out()], root, 0, 0);
    s.cm_out[0] = cm_of(0xbad, ASSET, 300, 0xc1, 0xc2);
    prove(s);
}

#[test(should_fail_with = "bad mint pc")]
fn test_mint_to_another_owner() {
    let (ins, root) = two_notes(300, 200);
    let mut s = honest(NK, [ins[0], none_in()], [none_out(), none_out()], root, 0, 300);
    s.spc_mint = stake_pc(owner_pk(0xbad), 0x31, 0x32);
    prove(s);
}

#[test(should_fail_with = "bad owner tag")]
fn test_owner_tag_of_another_owner() {
    let mut s = honest(NK, [none_in(), none_in()], [none_out(), none_out()], 0, 0, 0);
    s.otag = owner_tag(owner_pk(0xbad), 0x5a17);
    prove(s);
}

#[test(should_fail_with = "input not in stake tree")]
fn test_spend_another_owners_note() {
    // Notes of another nk are not provable under this nk.
    let (ins, root) = two_notes(300, 200);
    let mut s = honest(NK, [ins[0], none_in()], [none_out(), none_out()], root, 0, 300);
    s.nk = 0xbad;
    s.nf[0] = stake_nf(0xbad, ins[0].rho, ins[0].pos);
    s.spc_mint = stake_pc(owner_pk(0xbad), 0x31, 0x32);
    s.otag = owner_tag(owner_pk(0xbad), 0x5a17);
    prove(s);
}

#[test(should_fail_with = "input not in stake tree")]
fn test_wrong_anchor() {
    let (ins, root) = two_notes(300, 200);
    prove(honest(NK, [ins[0], none_in()], [none_out(), none_out()], root + 1, 0, 300));
}

#[test(should_fail_with = "input not in stake tree")]
fn test_inflated_input() {
    let (ins, root) = two_notes(300, 200);
    let mut i0 = ins[0];
    i0.amount = 301;
    prove(honest(NK, [i0, none_in()], [none_out(), none_out()], root, 0, 301));
}

#[test(should_fail_with = "input not in stake tree")]
fn test_other_asset() {
    // A note of one validator spent as another's.
    let (ins, root) = two_notes(300, 200);
    let mut s = honest(NK, [ins[0], none_in()], [none_out(), none_out()], root, 0, 300);
    s.asset = ASSET + 1;
    prove(s);
}

#[test(should_fail_with = "amounts do not balance")]
fn test_creates_value() {
    let (ins, root) = two_notes(300, 200);
    prove(honest(NK, [ins[0], none_in()], [Out { amount: 301, rho: 0xc1, rcm: 0xc2 }, none_out()], root, 0, 0));
}

#[test(should_fail_with = "amounts do not balance")]
fn test_overstated_v_out() {
    let (ins, root) = two_notes(300, 200);
    prove(honest(NK, [ins[0], none_in()], [none_out(), none_out()], root, 0, 301));
}

#[test(should_fail_with = "bad nullifier")]
fn test_wrong_nullifier() {
    let (ins, root) = two_notes(300, 200);
    let mut s = honest(NK, [ins[0], none_in()], [none_out(), none_out()], root, 0, 300);
    s.nf[0] = stake_nf(NK, ins[0].rho, 7);
    prove(s);
}

#[test(should_fail_with = "bad nullifier")]
fn test_dummy_input_publishes_no_nullifier() {
    let mut s = honest(NK, [none_in(), none_in()], [none_out(), none_out()], 0, 0, 0);
    s.nf[1] = 5;
    prove(s);
}

#[test(should_fail_with = "bad output commitment")]
fn test_dummy_output_publishes_no_commitment() {
    let mut s = honest(NK, [none_in(), none_in()], [none_out(), none_out()], 0, 0, 0);
    s.cm_out[0] = cm_of(NK, ASSET, 0, 0x99, 0x9a);
    prove(s);
}

#[test(should_fail_with = "bad output commitment")]
fn test_output_of_another_asset() {
    let (ins, root) = two_notes(300, 200);
    let mut s = honest(NK, [ins[0], none_in()], [Out { amount: 300, rho: 0xc1, rcm: 0xc2 }, none_out()], root, 0, 0);
    s.cm_out[0] = cm_of(NK, ASSET + 1, 300, 0xc1, 0xc2);
    prove(s);
}
