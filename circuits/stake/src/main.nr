//! stake — one owner-locked operation on the stake note tree
//! (earth-network-chain x/shieldedstaking, ORCHARD_DESIGN.md sections 13 and
//! 20).
//!
//! Delegated stake (derth/<valoper>) lives in its own append-only tree, never
//! in the shielded pool, and can never change owner: every note this circuit
//! spends or creates belongs to the one owner_pk = H(TAG_OWNER, nk) the prover
//! knows nk for.
//!
//!   spc      = H(TAG_SPC, owner_pk, rho, rcm)
//!   cm       = H(TAG_STAKE, asset, amount, spc)
//!   nf       = H(TAG_SNF, nk, rho, position)
//!   otag     = H(TAG_OTAG, owner_pk, salt)
//!
//! One stake note per (owner, asset): the chain never mints a stake note.
//! Every note is an output of this circuit, and value the chain adds (a
//! delegation's derth, an unlocked position, a redelegation's arrival) is a
//! public v_in merged into the owner's existing note in the same proof.
//!
//! Two lanes, each of one asset, all under `anchor`:
//!
//!   lane A (`asset`):    in_0 + in_1 + v_in == out + v_out
//!   lane B (`cr_asset`): cr_in + cr_v_in   == cr_out
//!
//! Lane A spends up to two notes and creates one; v_in is value the chain
//! credits (a delegation's derth, an unlocked position's), v_out value leaving
//! to the chain (undelegated, locked, redelegated). Lane B, the credit lane,
//! receives value of a second asset into one note, merging at most one
//! existing note of that asset (a redelegation's derth/<dst>); its asset is 0
//! and everything in it 0 when the msg has no second asset.
//!
//! Inputs. An input of amount != 0 is a real note: in the stake tree under
//! `anchor`, its nullifier published. An input of amount 0 is a dummy: no
//! membership, and its published nullifier is either 0 (no input) or its
//! would-be nullifier H(TAG_SNF, nk, rho, position) (a padding spend, which
//! the chain cannot tell from a real one: a first delegation looks like a
//! top-up). A dummy's nullifier is still derived from nk, so nobody can
//! publish another owner's nullifier; an owner who pads with the rho and
//! position of one of its own notes only burns that note.
//!
//! Outputs. An output of amount != 0 is a note: cm = H(TAG_STAKE, asset,
//! amount, spc) of the prover's owner_pk. An output of amount 0 publishes
//! either 0 (no output) or the commitment of a zero note of the same owner
//! (padding: a full undelegation looks like one with change). Zero notes are
//! inert: spending one is a dummy spend, voting one an unused slot.
//!
//! Every note amount is at most 2^63 - 1 (privacy_core NOTE_VALUE_BITS,
//! inside stake_cm): what every wallet holds. v_in, v_out and cr_v_in are
//! public u64 amounts, not notes.
//!
//! otag is a commitment to the same owner the chain may store (a Groundworks
//! position) or compare (a position's later update, unlock or vote).
//! Binding: the msg's sighash. The chain fixes, per msg, the assets, v_in,
//! v_out, cr_v_in and which of the rest must be zero.
//!
//! Public input order:
//!   anchor, asset, nf_0, nf_1, cm_out, v_in, v_out,
//!   cr_asset, cr_nf, cr_cm, cr_v_in, otag, sighash

use privacy_core::{bind, DEPTH, merkle_root, owner_pk, owner_tag, stake_cm, stake_nf, stake_pc};
use privacy_core::{h4, TAG_STAKE};

/// One input of `asset`: a real note under anchor (amount != 0) with its
/// nullifier, or a dummy (amount 0) publishing 0 or its own would-be
/// nullifier.
fn spend(
    nk: Field,
    opk: Field,
    asset: Field,
    anchor: Field,
    amount: u64,
    rho: Field,
    rcm: Field,
    pos: u32,
    path: [Field; DEPTH],
    nf: Field,
) {
    let real = (amount != 0) as Field;
    let cm = stake_cm(asset, amount, stake_pc(opk, rho, rcm));
    let r = merkle_root(cm, pos, path);
    assert((r - anchor) * real == 0, "input not in stake tree");
    // real: nf == want. dummy: nf == want or nf == 0.
    let want = stake_nf(nk, rho, pos);
    assert((nf - want) * (real + (1 - real) * nf) == 0, "bad nullifier");
}

/// One output of `asset` to the prover: cm of the note (amount != 0), or, for
/// amount 0, 0 or the commitment of the zero note.
fn create(opk: Field, asset: Field, amount: u64, rho: Field, rcm: Field, cm: Field) {
    let made = (amount != 0) as Field;
    let c = stake_cm(asset, amount, stake_pc(opk, rho, rcm));
    assert((cm - c) * (made + (1 - made) * cm) == 0, "bad output commitment");
}

fn main(
    nk: Field,
    in_amount: [u64; 2],
    in_rho: [Field; 2],
    in_rcm: [Field; 2],
    in_pos: [u32; 2],
    in_path: [[Field; DEPTH]; 2],
    out_amount: u64,
    out_rho: Field,
    out_rcm: Field,
    cr_in_amount: u64,
    cr_in_rho: Field,
    cr_in_rcm: Field,
    cr_in_pos: u32,
    cr_in_path: [Field; DEPTH],
    cr_out_amount: u64,
    cr_out_rho: Field,
    cr_out_rcm: Field,
    tag_salt: Field,
    anchor: pub Field,
    asset: pub Field,
    nf_0: pub Field,
    nf_1: pub Field,
    cm_out: pub Field,
    v_in: pub u64,
    v_out: pub u64,
    cr_asset: pub Field,
    cr_nf: pub Field,
    cr_cm: pub Field,
    cr_v_in: pub u64,
    otag: pub Field,
    sighash: pub Field,
) {
    let opk = owner_pk(nk);
    let nf = [nf_0, nf_1];
    let mut total_in = v_in as Field;
    for i in 0..2 {
        spend(nk, opk, asset, anchor, in_amount[i], in_rho[i], in_rcm[i], in_pos[i], in_path[i], nf[i]);
        total_in += in_amount[i] as Field;
    }
    create(opk, asset, out_amount, out_rho, out_rcm, cm_out);
    // Each side is at most 2 * (2^63 - 1) + (2^64 - 1): no wrap in the field.
    assert(total_in == out_amount as Field + v_out as Field, "amounts do not balance");

    spend(nk, opk, cr_asset, anchor, cr_in_amount, cr_in_rho, cr_in_rcm, cr_in_pos, cr_in_path, cr_nf);
    create(opk, cr_asset, cr_out_amount, cr_out_rho, cr_out_rcm, cr_cm);
    assert(
        cr_in_amount as Field + cr_v_in as Field == cr_out_amount as Field,
        "credit lane does not balance",
    );

    assert(otag == owner_tag(opk, tag_salt), "bad owner tag");
    bind(sighash);
}

// ---------------------------------------------------------------- tests

global NK: Field = 0x5eed;
global ASSET: Field = 0xde27;
global CR_ASSET: Field = 0xde28;
global SIGHASH: Field = 0x5164;
global MAX: u64 = 0x7fffffffffffffff;

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
    out: Out,
    cr_in: In,
    cr_out: Out,
    salt: Field,
    anchor: Field,
    asset: Field,
    nf: [Field; 2],
    cm_out: Field,
    v_in: u64,
    v_out: u64,
    cr_asset: Field,
    cr_nf: Field,
    cr_cm: Field,
    cr_v_in: u64,
    otag: Field,
}

/// No input: amount 0, publishes nf 0.
fn none_in() -> In {
    In { amount: 0, rho: 0x77, rcm: 0x78, pos: 0, path: [0; DEPTH] }
}

/// A padding input: amount 0, publishes its would-be nullifier.
fn dummy_in(rho: Field) -> In {
    In { amount: 0, rho, rcm: 0x79, pos: 0, path: [0; DEPTH] }
}

fn none_out() -> Out {
    Out { amount: 0, rho: 0x99, rcm: 0x9a }
}

fn out(amount: u64, rho: Field) -> Out {
    Out { amount, rho, rcm: rho + 1 }
}

/// H(TAG_STAKE, asset, amount, spc) for any u64 amount (stake_cm without its
/// bound), so a test can hold an amount above 2^63 - 1 that only the circuit
/// refuses.
fn cm_of(nk: Field, asset: Field, amount: u64, rho: Field, rcm: Field) -> Field {
    h4(TAG_STAKE, asset, amount as Field, stake_pc(owner_pk(nk), rho, rcm))
}

/// A tree of four leaves under NK: notes a and b of ASSET (positions 0, 1),
/// note c of CR_ASSET (position 2), position 3 empty. Returns the three
/// inputs with their paths, and the root.
fn tree(a: u64, b: u64, c: u64) -> ([In; 3], Field) {
    let z = privacy_core::zero_hashes();
    let l = [cm_of(NK, ASSET, a, 0xa1, 0xa2), cm_of(NK, ASSET, b, 0xb1, 0xb2), cm_of(NK, CR_ASSET, c, 0xc1, 0xc2), 0];
    let n01 = privacy_core::h2(l[0], l[1]);
    let n23 = privacy_core::h2(l[2], l[3]);
    let mut paths = [[0; DEPTH]; 3];
    for k in 0..3 {
        for i in 2..DEPTH {
            paths[k][i] = z[i];
        }
    }
    paths[0][0] = l[1];
    paths[0][1] = n23;
    paths[1][0] = l[0];
    paths[1][1] = n23;
    paths[2][0] = l[3];
    paths[2][1] = n01;
    let root = merkle_root(l[0], 0, paths[0]);
    assert(root == merkle_root(l[1], 1, paths[1]));
    assert(root == merkle_root(l[2], 2, paths[2]));
    (
        [
            In { amount: a, rho: 0xa1, rcm: 0xa2, pos: 0, path: paths[0] },
            In { amount: b, rho: 0xb1, rcm: 0xb2, pos: 1, path: paths[1] },
            In { amount: c, rho: 0xc1, rcm: 0xc2, pos: 2, path: paths[2] },
        ],
        root,
    )
}

/// The published nullifier of an input as an honest prover chooses it:
/// a real note's, a padding input's would-be one, or 0 for none.
fn nf_of(nk: Field, i: In, pad: bool) -> Field {
    if (i.amount != 0) | pad {
        stake_nf(nk, i.rho, i.pos)
    } else {
        0
    }
}

/// An output's published commitment: the note's, the zero note's when
/// padding, or 0.
fn cm_pub(nk: Field, asset: Field, o: Out, pad: bool) -> Field {
    if (o.amount != 0) | pad {
        cm_of(nk, asset, o.amount, o.rho, o.rcm)
    } else {
        0
    }
}

/// Lane A only, no padding: every public value as an honest prover fills it.
fn honest(ins: [In; 2], o: Out, anchor: Field, v_in: u64, v_out: u64) -> St {
    St {
        nk: NK,
        ins,
        out: o,
        cr_in: none_in(),
        cr_out: none_out(),
        salt: 0x5a17,
        anchor,
        asset: ASSET,
        nf: [nf_of(NK, ins[0], false), nf_of(NK, ins[1], false)],
        cm_out: cm_pub(NK, ASSET, o, false),
        v_in,
        v_out,
        cr_asset: 0,
        cr_nf: 0,
        cr_cm: 0,
        cr_v_in: 0,
        otag: owner_tag(owner_pk(NK), 0x5a17),
    }
}

/// s with a credit lane: cr_in (a note of CR_ASSET, or a padding input)
/// merged with cr_v_in into cr_out.
fn with_credit(s: St, cr_in: In, cr_out: Out, cr_v_in: u64) -> St {
    let mut t = s;
    t.cr_in = cr_in;
    t.cr_out = cr_out;
    t.cr_asset = CR_ASSET;
    t.cr_nf = nf_of(NK, cr_in, true);
    t.cr_cm = cm_pub(NK, CR_ASSET, cr_out, false);
    t.cr_v_in = cr_v_in;
    t
}

fn prove(s: St) {
    main(
        s.nk,
        [s.ins[0].amount, s.ins[1].amount],
        [s.ins[0].rho, s.ins[1].rho],
        [s.ins[0].rcm, s.ins[1].rcm],
        [s.ins[0].pos, s.ins[1].pos],
        [s.ins[0].path, s.ins[1].path],
        s.out.amount,
        s.out.rho,
        s.out.rcm,
        s.cr_in.amount,
        s.cr_in.rho,
        s.cr_in.rcm,
        s.cr_in.pos,
        s.cr_in.path,
        s.cr_out.amount,
        s.cr_out.rho,
        s.cr_out.rcm,
        s.salt,
        s.anchor,
        s.asset,
        s.nf[0],
        s.nf[1],
        s.cm_out,
        s.v_in,
        s.v_out,
        s.cr_asset,
        s.cr_nf,
        s.cr_cm,
        s.cr_v_in,
        s.otag,
        SIGHASH,
    );
}

// ---- positive: every op the chain uses

#[test]
fn test_first_delegation_padding_input() {
    // No note yet: a padding input publishes a nullifier like a top-up's.
    let (_, root) = tree(300, 200, 100);
    let mut s = honest([dummy_in(0xd1), none_in()], out(1000, 0xe1), root, 1000, 0);
    s.nf[0] = stake_nf(NK, 0xd1, 0);
    prove(s);
}

#[test]
fn test_first_delegation_no_input() {
    prove(honest([none_in(), none_in()], out(1000, 0xe1), 0, 1000, 0));
}

#[test]
fn test_top_up_merges() {
    // The existing note and the credited derth become one note.
    let (n, root) = tree(300, 200, 100);
    prove(honest([n[0], none_in()], out(500, 0xe1), root, 200, 0));
}

#[test]
fn test_top_up_merges_two_notes() {
    let (n, root) = tree(300, 200, 100);
    prove(honest([n[0], n[1]], out(700, 0xe1), root, 200, 0));
}

#[test]
fn test_top_up_to_note_max() {
    let (n, root) = tree(MAX - 5, 200, 100);
    prove(honest([n[0], none_in()], out(MAX, 0xe1), root, 5, 0));
}

#[test]
fn test_restake_merge() {
    let (n, root) = tree(300, 200, 100);
    prove(honest([n[0], n[1]], out(500, 0xe1), root, 0, 0));
}

#[test]
fn test_undelegate_with_change() {
    let (n, root) = tree(300, 200, 100);
    prove(honest([n[1], none_in()], out(50, 0xe1), root, 0, 150));
}

#[test]
fn test_undelegate_all_padding_output() {
    // No change: a zero note pads the output, so a full exit looks like a
    // partial one.
    let (n, root) = tree(300, 200, 100);
    let mut s = honest([n[1], none_in()], out(0, 0xe1), root, 0, 200);
    s.cm_out = cm_of(NK, ASSET, 0, 0xe1, 0xe2);
    prove(s);
}

#[test]
fn test_undelegate_all_no_output() {
    let (n, root) = tree(300, 200, 100);
    prove(honest([n[1], none_in()], none_out(), root, 0, 200));
}

#[test]
fn test_unlock_merges_position() {
    let (n, root) = tree(300, 200, 100);
    prove(honest([n[0], none_in()], out(1300, 0xe1), root, 1000, 0));
}

#[test]
fn test_redelegate_merges_into_existing() {
    // 250 of A's 300 + 200 leave; the chain credits 240 of B, merged into
    // the owner's B note of 100.
    let (n, root) = tree(300, 200, 100);
    let s = honest([n[0], n[1]], out(250, 0xe1), root, 0, 250);
    prove(with_credit(s, n[2], out(340, 0xf1), 240));
}

#[test]
fn test_redelegate_padding_credit_input() {
    // No B note yet: a padding input on the credit lane.
    let (n, root) = tree(300, 200, 100);
    let s = honest([n[0], none_in()], out(0, 0xe1), root, 0, 300);
    let mut s = with_credit(s, dummy_in(0xd2), out(290, 0xf1), 290);
    s.cm_out = cm_of(NK, ASSET, 0, 0xe1, 0xe2);
    prove(s);
}

#[test]
fn test_position_owner_proof() {
    // Update, unlock's owner check, position vote: nothing spent or created.
    prove(honest([none_in(), none_in()], none_out(), 0, 0, 0));
}

#[test]
fn test_public_v_out_above_note_max() {
    // v_out is a public amount leaving the notes, not a note.
    let (n, root) = tree(MAX, MAX, 100);
    prove(honest([n[0], n[1]], none_out(), root, 0, 0xfffffffffffffffe));
}

// ---- the 2^63 - 1 note bound: a wallet holds nothing larger

#[test(should_fail_with = "assert_max_bit_size")]
fn test_top_up_past_note_max() {
    // A top-up that would take the merged note past 2^63 - 1: balanced, only
    // the bound fails. The wallet keeps the excess as a second note.
    let (n, root) = tree(MAX, 200, 100);
    prove(honest([n[0], none_in()], out(0x8000000000000000, 0xe1), root, 1, 0));
}

#[test(should_fail_with = "assert_max_bit_size")]
fn test_merge_past_note_max() {
    let h: u64 = 0x4000000000000000;
    let (n, root) = tree(h, h, 100);
    prove(honest([n[0], n[1]], out(0x8000000000000000, 0xe1), root, 0, 0));
}

#[test(should_fail_with = "assert_max_bit_size")]
fn test_v_in_above_note_max() {
    prove(honest([none_in(), none_in()], out(0xffffffffffffffff, 0xe1), 0, 0xffffffffffffffff, 0));
}

#[test(should_fail_with = "assert_max_bit_size")]
fn test_credit_past_note_max() {
    let (n, root) = tree(300, 200, MAX);
    let s = honest([n[0], none_in()], out(0, 0xe1), root, 0, 300);
    prove(with_credit(s, n[2], out(0x8000000000000000, 0xf1), 1));
}

#[test(should_fail_with = "assert_max_bit_size")]
fn test_input_above_note_max() {
    let (n, root) = tree(0x8000000000000000, 1, 100);
    prove(honest([n[0], none_in()], out(MAX, 0xe1), root, 0, 1));
}

// ---- negative: ownership

#[test(should_fail_with = "bad output commitment")]
fn test_output_to_another_owner() {
    let (n, root) = tree(300, 200, 100);
    let mut s = honest([n[0], none_in()], out(500, 0xe1), root, 200, 0);
    s.cm_out = cm_of(0xbad, ASSET, 500, 0xe1, 0xe2);
    prove(s);
}

#[test(should_fail_with = "bad output commitment")]
fn test_credit_to_another_owner() {
    let (n, root) = tree(300, 200, 100);
    let s = honest([n[0], none_in()], out(0, 0xe1), root, 0, 300);
    let mut s = with_credit(s, n[2], out(400, 0xf1), 300);
    s.cr_cm = cm_of(0xbad, CR_ASSET, 400, 0xf1, 0xf2);
    prove(s);
}

#[test(should_fail_with = "bad output commitment")]
fn test_padding_output_of_another_owner() {
    let (n, root) = tree(300, 200, 100);
    let mut s = honest([n[1], none_in()], out(0, 0xe1), root, 0, 200);
    s.cm_out = cm_of(0xbad, ASSET, 0, 0xe1, 0xe2);
    prove(s);
}

#[test(should_fail_with = "bad output commitment")]
fn test_padding_output_arbitrary() {
    let (n, root) = tree(300, 200, 100);
    let mut s = honest([n[1], none_in()], out(0, 0xe1), root, 0, 200);
    s.cm_out = 5;
    prove(s);
}

#[test(should_fail_with = "bad owner tag")]
fn test_owner_tag_of_another_owner() {
    let mut s = honest([none_in(), none_in()], none_out(), 0, 0, 0);
    s.otag = owner_tag(owner_pk(0xbad), 0x5a17);
    prove(s);
}

#[test(should_fail_with = "input not in stake tree")]
fn test_spend_another_owners_note() {
    let (n, root) = tree(300, 200, 100);
    let mut s = honest([n[0], none_in()], none_out(), root, 0, 300);
    s.nk = 0xbad;
    s.nf[0] = stake_nf(0xbad, n[0].rho, n[0].pos);
    s.otag = owner_tag(owner_pk(0xbad), 0x5a17);
    prove(s);
}

#[test(should_fail_with = "input not in stake tree")]
fn test_merge_another_owners_credit_note() {
    // Lane A pads (nothing of the prover's spent); the credit lane names a
    // note of NK while the prover knows only 0xbad.
    let (n, root) = tree(300, 200, 100);
    let mut s = honest([dummy_in(0xd1), none_in()], none_out(), root, 0, 0);
    s = with_credit(s, n[2], out(400, 0xf1), 300);
    s.nk = 0xbad;
    s.nf[0] = stake_nf(0xbad, 0xd1, 0);
    s.cr_nf = stake_nf(0xbad, n[2].rho, n[2].pos);
    s.cr_cm = cm_of(0xbad, CR_ASSET, 400, 0xf1, 0xf2);
    s.otag = owner_tag(owner_pk(0xbad), 0x5a17);
    prove(s);
}

// ---- negative: nullifiers

#[test(should_fail_with = "bad nullifier")]
fn test_wrong_nullifier() {
    let (n, root) = tree(300, 200, 100);
    let mut s = honest([n[0], none_in()], none_out(), root, 0, 300);
    s.nf[0] = stake_nf(NK, n[0].rho, 7);
    prove(s);
}

#[test(should_fail_with = "bad nullifier")]
fn test_real_input_hides_its_nullifier() {
    let (n, root) = tree(300, 200, 100);
    let mut s = honest([n[0], none_in()], none_out(), root, 0, 300);
    s.nf[0] = 0;
    prove(s);
}

#[test(should_fail_with = "bad nullifier")]
fn test_padding_nullifier_arbitrary() {
    // A padding input cannot publish just any value (another owner's
    // nullifier seen in the mempool, say).
    let mut s = honest([dummy_in(0xd1), none_in()], out(1000, 0xe1), 0, 1000, 0);
    s.nf[0] = 5;
    prove(s);
}

#[test(should_fail_with = "bad nullifier")]
fn test_padding_nullifier_of_another_owner() {
    let (n, root) = tree(300, 200, 100);
    let mut s = honest([dummy_in(0xd1), none_in()], out(1000, 0xe1), root, 1000, 0);
    s.nf[0] = stake_nf(0xbad, n[0].rho, n[0].pos);
    prove(s);
}

#[test(should_fail_with = "bad nullifier")]
fn test_credit_wrong_nullifier() {
    let (n, root) = tree(300, 200, 100);
    let s = honest([n[0], none_in()], out(0, 0xe1), root, 0, 300);
    let mut s = with_credit(s, n[2], out(400, 0xf1), 300);
    s.cr_nf = stake_nf(NK, n[2].rho, 9);
    prove(s);
}

// ---- negative: membership, assets

#[test(should_fail_with = "input not in stake tree")]
fn test_wrong_anchor() {
    let (n, root) = tree(300, 200, 100);
    prove(honest([n[0], none_in()], none_out(), root + 1, 0, 300));
}

#[test(should_fail_with = "input not in stake tree")]
fn test_inflated_input() {
    let (n, root) = tree(300, 200, 100);
    let mut i0 = n[0];
    i0.amount = 301;
    prove(honest([i0, none_in()], none_out(), root, 0, 301));
}

#[test(should_fail_with = "input not in stake tree")]
fn test_other_asset() {
    // A note of one validator spent as another's.
    let (n, root) = tree(300, 200, 100);
    let mut s = honest([n[0], none_in()], none_out(), root, 0, 300);
    s.asset = ASSET + 1;
    prove(s);
}

#[test(should_fail_with = "input not in stake tree")]
fn test_credit_lane_note_of_the_spent_asset() {
    // A derth/<src> note cannot be merged as the derth/<dst> note.
    let (n, root) = tree(300, 200, 100);
    let s = honest([n[1], none_in()], out(0, 0xe1), root, 0, 200);
    prove(with_credit(s, n[0], out(500, 0xf1), 200));
}

#[test(should_fail_with = "input not in stake tree")]
fn test_lane_a_note_of_the_credit_asset() {
    let (n, root) = tree(300, 200, 100);
    prove(honest([n[2], none_in()], none_out(), root, 0, 100));
}

#[test(should_fail_with = "bad output commitment")]
fn test_output_of_another_asset() {
    let (n, root) = tree(300, 200, 100);
    let mut s = honest([n[0], none_in()], out(500, 0xe1), root, 200, 0);
    s.cm_out = cm_of(NK, CR_ASSET, 500, 0xe1, 0xe2);
    prove(s);
}

#[test(should_fail_with = "bad output commitment")]
fn test_credit_output_of_the_spent_asset() {
    let (n, root) = tree(300, 200, 100);
    let s = honest([n[0], none_in()], out(0, 0xe1), root, 0, 300);
    let mut s = with_credit(s, n[2], out(400, 0xf1), 300);
    s.cr_cm = cm_of(NK, ASSET, 400, 0xf1, 0xf2);
    prove(s);
}

// ---- negative: balance

#[test(should_fail_with = "amounts do not balance")]
fn test_creates_value() {
    let (n, root) = tree(300, 200, 100);
    prove(honest([n[0], none_in()], out(301, 0xe1), root, 0, 0));
}

#[test(should_fail_with = "amounts do not balance")]
fn test_overstated_v_out() {
    let (n, root) = tree(300, 200, 100);
    prove(honest([n[0], none_in()], none_out(), root, 0, 301));
}

#[test(should_fail_with = "amounts do not balance")]
fn test_top_up_claims_more_than_credited() {
    // The merged note must be exactly the old note plus what the chain
    // credits (v_in, checked against the price by the chain).
    let (n, root) = tree(300, 200, 100);
    prove(honest([n[0], none_in()], out(501, 0xe1), root, 200, 0));
}

#[test(should_fail_with = "amounts do not balance")]
fn test_padding_input_carries_no_value() {
    let mut s = honest([dummy_in(0xd1), none_in()], out(1001, 0xe1), 0, 1000, 0);
    s.ins[0].amount = 0;
    s.nf[0] = stake_nf(NK, 0xd1, 0);
    prove(s);
}

#[test(should_fail_with = "credit lane does not balance")]
fn test_credit_creates_value() {
    let (n, root) = tree(300, 200, 100);
    let s = honest([n[0], none_in()], out(0, 0xe1), root, 0, 300);
    prove(with_credit(s, n[2], out(401, 0xf1), 300));
}

#[test(should_fail_with = "credit lane does not balance")]
fn test_credit_lane_takes_lane_a_value() {
    // v_out leaving lane A does not reach the credit lane: only cr_v_in does.
    let (n, root) = tree(300, 200, 100);
    let s = honest([n[0], none_in()], out(0, 0xe1), root, 0, 300);
    prove(with_credit(s, n[2], out(400, 0xf1), 0));
}
