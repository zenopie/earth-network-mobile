//! stake — one owner-locked operation on the stake note tree
//! (earth-network-chain x/shieldedstaking, ORCHARD_DESIGN.md sections 13 and
//! 20).
//!
//! Delegated stake (derth/<valoper>) lives in its own append-only tree, never
//! in the shielded pool, and can never change owner: every note this circuit
//! spends or creates belongs to the one owner_pk = H(TAG_OWNER, nk) the prover
//! knows nk for.
//!
//!   spc    = H(TAG_SPC, owner_pk, rho, rcm)
//!   cm     = H(TAG_STAKE, asset, amount, spc, label)
//!   label  = 0, or H(TAG_SLABEL, move_key, move_time, exposed)
//!   nf     = H(TAG_SNF, nk, rho, position)
//!   gw     = H(TAG_GW, nk, rho)
//!
//! One stake note per (owner, asset): the chain never mints a stake note.
//! Value the chain credits (a delegation's derth, a redelegation's arrival) is a public amount merged into the owner's
//! existing note by this proof.
//!
//! Two lanes, all under `anchor`:
//!
//!   lane A (`asset`): spends up to two notes, creates one;
//!                     v_in credited (delegation), v_out leaving
//!                     (undelegation, redelegation's source)
//!   lane B (`cr_asset`, the credit lane): merges `cr_v_in` credited by a
//!                     redelegation into at most one existing unlabeled note
//!
//! Slash labels. A redelegation's credit (lane B, cr_move_time != 0) is
//! labelled (move_key = cr_nf, the credit lane's own nullifier; move_time;
//! exposed = cr_v_in): a slash of the source for an infraction before the move
//! may still cut it until the move's x/staking entry matures. A note holds at
//! most one label, and its exposed derth never leaves it: lane A spends at
//! most one labelled note, and either
//!
//!   keeps the label: the output carries the same label and exposure, and
//!     in_0 + in_1 - exposed + v_in == out - exposed + v_out
//!     (only the unexposed part moves), or
//!   clears it, once the move's window has closed (move_time < clear_before,
//!     the chain checks clear_before against its clock): the exposure is worth
//!     what the slash debt tree says (debt_retained against `debt_root`: the
//!     row's retained derth if the move was slashed, else all of it), and the
//!     output is unlabelled:
//!     in_0 + in_1 - exposed + retained + v_in == out + v_out.
//!
//! Lane B balance: cr_in + cr_v_in == cr_out; its input must be unlabelled.
//!
//! Padding. An input of amount 0 publishes 0 or its own would-be nullifier
//! (a first delegation looks like a top-up); an output of amount 0 publishes
//! 0 or a zero note's commitment (a full exit looks like a partial one). A
//! padding nullifier is still derived from nk.
//!
//! Groundworks votes. A note's vote is stored by the chain under its tag gw,
//! which needs no tree position. Every input publishes its gw (a padding
//! input: 0 or its own, as for nullifiers), so any spend of a voting note
//! cancels its vote. Each output may vote: gw_out (cr_gw_out) is its tag and
//! w_out (cr_w_out) its unexposed amount, the vote's weight; both 0 when it
//! does not. An output of amount 0 cannot vote.
//!
//! Every note amount is at most 2^63 - 1 (stake_cm); v_in, v_out, cr_v_in are
//! public u64 amounts. Binding: the msg's sighash. The chain fixes, per msg,
//! the assets, v_in, v_out, cr_v_in, cr_move_time, debt_root and which of the
//! rest must be zero.
//!
//! Public input order:
//!   anchor, asset, nf_0, nf_1, cm_out, v_in, v_out, clear_before, debt_root,
//!   cr_asset, cr_nf, cr_cm, cr_v_in, cr_move_time,
//!   gw_0, gw_1, cr_gw, gw_out, w_out, cr_gw_out, cr_w_out, sighash

use privacy_core::{bind, debt_retained, DEPTH, merkle_root, owner_pk};
use privacy_core::{stake_cm, stake_gw, stake_label, stake_nf, stake_pc};
use privacy_core::{debt_leaf, h5, TAG_STAKE};

/// One input of `asset` with commitment label `label`: a real note under
/// anchor (amount != 0) with its nullifier and Groundworks tag, or a padding
/// input (amount 0) publishing 0 or its own would-be nullifier and tag.
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
    label: Field,
    nf: Field,
    gw: Field,
) {
    let real = (amount != 0) as Field;
    let cm = stake_cm(asset, amount, stake_pc(opk, rho, rcm), label);
    let r = merkle_root(cm, pos, path);
    assert((r - anchor) * real == 0, "input not in stake tree");
    // real: nf == want. padding: nf == want or nf == 0.
    let want = stake_nf(nk, rho, pos);
    assert((nf - want) * (real + (1 - real) * nf) == 0, "bad nullifier");
    let want_gw = stake_gw(nk, rho);
    assert((gw - want_gw) * (real + (1 - real) * gw) == 0, "bad groundworks tag");
}

/// An output's Groundworks vote: none (gw == 0, w == 0), or the note's own
/// tag with its unexposed amount as the weight. A padding output cannot vote.
fn vote(nk: Field, amount: u64, unexposed: u64, rho: Field, gw: Field, w: u64) {
    if gw == 0 {
        assert(w == 0, "a weight without a vote");
    } else {
        assert(amount != 0, "a padding output cannot vote");
        assert(gw == stake_gw(nk, rho), "bad vote tag");
        assert(w == unexposed, "the vote's weight is the unexposed amount");
    }
}

/// One output of `asset` to the prover: cm of the note (amount != 0), or, for
/// amount 0, 0 or the commitment of the zero note.
fn create(opk: Field, asset: Field, amount: u64, rho: Field, rcm: Field, label: Field, cm: Field) {
    let made = (amount != 0) as Field;
    let c = stake_cm(asset, amount, stake_pc(opk, rho, rcm), label);
    assert((cm - c) * (made + (1 - made) * cm) == 0, "bad output commitment");
}

fn main(
    nk: Field,
    in_amount: [u64; 2],
    in_rho: [Field; 2],
    in_rcm: [Field; 2],
    in_pos: [u32; 2],
    in_path: [[Field; DEPTH]; 2],
    in_move_key: [Field; 2],
    in_move_time: [u64; 2],
    in_exposed: [u64; 2],
    out_amount: u64,
    out_rho: Field,
    out_rcm: Field,
    clear: bool,
    debt_low_key: Field,
    debt_low_next_key: Field,
    debt_low_next_index: u32,
    debt_low_retained: u64,
    debt_low_index: u32,
    debt_low_path: [Field; DEPTH],
    cr_in_amount: u64,
    cr_in_rho: Field,
    cr_in_rcm: Field,
    cr_in_pos: u32,
    cr_in_path: [Field; DEPTH],
    cr_out_rho: Field,
    cr_out_rcm: Field,
    anchor: pub Field,
    asset: pub Field,
    nf_0: pub Field,
    nf_1: pub Field,
    cm_out: pub Field,
    v_in: pub u64,
    v_out: pub u64,
    clear_before: pub u64,
    debt_root: pub Field,
    cr_asset: pub Field,
    cr_nf: pub Field,
    cr_cm: pub Field,
    cr_v_in: pub u64,
    cr_move_time: pub u64,
    gw_0: pub Field,
    gw_1: pub Field,
    cr_gw: pub Field,
    gw_out: pub Field,
    w_out: pub u64,
    cr_gw_out: pub Field,
    cr_w_out: pub u64,
    sighash: pub Field,
) {
    let opk = owner_pk(nk);
    let nf = [nf_0, nf_1];
    let gw = [gw_0, gw_1];

    // ---- lane A: at most one labelled input.
    let mut total: Field = 0;
    let mut labelled = false;
    let mut mk: Field = 0;
    let mut mt: u64 = 0;
    let mut ex: u64 = 0;
    for i in 0..2 {
        let lab = in_move_key[i] != 0;
        let mut label: Field = 0;
        if lab {
            assert(!labelled, "two labelled inputs");
            assert(in_amount[i] != 0, "a padding input carries no label");
            assert((in_exposed[i] != 0) & (in_exposed[i] <= in_amount[i]), "bad exposure");
            label = stake_label(in_move_key[i], in_move_time[i], in_exposed[i]);
            labelled = true;
            mk = in_move_key[i];
            mt = in_move_time[i];
            ex = in_exposed[i];
        }
        spend(nk, opk, asset, anchor, in_amount[i], in_rho[i], in_rcm[i], in_pos[i], in_path[i], label, nf[i], gw[i]);
        total += in_amount[i] as Field;
    }
    // The exposure is kept as it is, or cleared at what the debt tree says.
    let mut retained: Field = 0;
    if clear {
        assert(labelled, "nothing to clear");
        assert(mt < clear_before, "the move's window is still open");
        retained = debt_retained(
            mk,
            ex,
            debt_low_key,
            debt_low_next_key,
            debt_low_next_index,
            debt_low_retained,
            debt_low_index,
            debt_low_path,
            debt_root,
        ) as Field;
    }
    let keep = labelled & !clear;
    let mut out_label: Field = 0;
    let mut out_ex: u64 = 0;
    if keep {
        out_label = stake_label(mk, mt, ex);
        out_ex = ex;
    }
    assert(out_amount >= out_ex, "the exposure stays in the note");
    create(opk, asset, out_amount, out_rho, out_rcm, out_label, cm_out);
    vote(nk, out_amount, out_amount - out_ex, out_rho, gw_out, w_out);
    // Unexposed value in == unexposed value out. Each side is at most
    // 2 * (2^63 - 1) + 2 * (2^64 - 1): no wrap in the field.
    let unexposed_in = total - ex as Field + retained;
    let unexposed_out = (out_amount - out_ex) as Field;
    assert(unexposed_in + v_in as Field == unexposed_out + v_out as Field, "amounts do not balance");

    // ---- lane B: an unlabelled input, the credit merged, the label applied.
    spend(nk, opk, cr_asset, anchor, cr_in_amount, cr_in_rho, cr_in_rcm, cr_in_pos, cr_in_path, 0, cr_nf, cr_gw);
    let cr_out_amount = cr_in_amount + cr_v_in;
    let mut cr_label: Field = 0;
    if cr_move_time != 0 {
        assert((cr_nf != 0) & (cr_v_in != 0), "a labelled credit names its move and exposure");
        cr_label = stake_label(cr_nf, cr_move_time, cr_v_in);
    }
    create(opk, cr_asset, cr_out_amount, cr_out_rho, cr_out_rcm, cr_label, cr_cm);
    // The credit's exposure is cr_v_in when labelled; the rest is the input's.
    let cr_unexposed = if cr_move_time != 0 { cr_in_amount } else { cr_out_amount };
    vote(nk, cr_out_amount, cr_unexposed, cr_out_rho, cr_gw_out, cr_w_out);

    bind(sighash);
}

// ---------------------------------------------------------------- tests

global NK: Field = 0x5eed;
global ASSET: Field = 0xde27;
global CR_ASSET: Field = 0xde28;
global SIGHASH: Field = 0x5164;
global MAX: u64 = 0x7fffffffffffffff;
/// The labelled note's move: key, time, exposure.
global MK: Field = 0x4d4b;
global MT: u64 = 1000;
global EX: u64 = 200;
/// A clear_before the move's window has closed by, and one it has not.
global CLOSED: u64 = 5000;
global OPEN: u64 = 1000;

struct In {
    amount: u64,
    rho: Field,
    rcm: Field,
    pos: u32,
    path: [Field; DEPTH],
    mk: Field,
    mt: u64,
    ex: u64,
}

struct Out {
    amount: u64,
    rho: Field,
    rcm: Field,
}

struct Debt {
    root: Field,
    low_key: Field,
    low_next_key: Field,
    low_next_index: u32,
    low_retained: u64,
    low_index: u32,
    low_path: [Field; DEPTH],
}

struct St {
    nk: Field,
    ins: [In; 2],
    out: Out,
    clear: bool,
    debt: Debt,
    cr_in: In,
    cr_out: Out,
    anchor: Field,
    asset: Field,
    nf: [Field; 2],
    cm_out: Field,
    v_in: u64,
    v_out: u64,
    clear_before: u64,
    cr_asset: Field,
    cr_nf: Field,
    cr_cm: Field,
    cr_v_in: u64,
    cr_move_time: u64,
    gw: [Field; 2],
    cr_gw: Field,
    gw_out: Field,
    w_out: u64,
    cr_gw_out: Field,
    cr_w_out: u64,
}

fn none_in() -> In {
    In { amount: 0, rho: 0x77, rcm: 0x78, pos: 0, path: [0; DEPTH], mk: 0, mt: 0, ex: 0 }
}

fn dummy_in(rho: Field) -> In {
    In { amount: 0, rho, rcm: 0x79, pos: 0, path: [0; DEPTH], mk: 0, mt: 0, ex: 0 }
}

fn none_out() -> Out {
    Out { amount: 0, rho: 0x99, rcm: 0x9a }
}

fn out(amount: u64, rho: Field) -> Out {
    Out { amount, rho, rcm: rho + 1 }
}

fn label_of(mk: Field, mt: u64, ex: u64) -> Field {
    if mk != 0 {
        stake_label(mk, mt, ex)
    } else {
        0
    }
}

/// H(TAG_STAKE, asset, amount, spc, label) for any u64 amount (stake_cm
/// without its bound).
fn cm_of(nk: Field, asset: Field, amount: u64, rho: Field, rcm: Field, label: Field) -> Field {
    h5(TAG_STAKE, asset, amount as Field, stake_pc(owner_pk(nk), rho, rcm), label)
}

fn leaf(asset: Field, i: In) -> Field {
    cm_of(NK, asset, i.amount, i.rho, i.rcm, label_of(i.mk, i.mt, i.ex))
}

/// A stake tree of four notes of NK: a (ASSET, unlabelled, position 0),
/// b (ASSET, amount b, labelled MK/MT/EX, position 1), c (CR_ASSET,
/// unlabelled, position 2), d (CR_ASSET, labelled, position 3).
fn tree(a: u64, b: u64, c: u64) -> ([In; 4], Field) {
    let z = privacy_core::zero_hashes();
    let mut n = [
        In { amount: a, rho: 0xa1, rcm: 0xa2, pos: 0, path: [0; DEPTH], mk: 0, mt: 0, ex: 0 },
        In { amount: b, rho: 0xb1, rcm: 0xb2, pos: 1, path: [0; DEPTH], mk: MK, mt: MT, ex: EX },
        In { amount: c, rho: 0xc1, rcm: 0xc2, pos: 2, path: [0; DEPTH], mk: 0, mt: 0, ex: 0 },
        In { amount: 300, rho: 0xd1, rcm: 0xd2, pos: 3, path: [0; DEPTH], mk: 0x4d4c, mt: MT, ex: 100 },
    ];
    let l = [leaf(ASSET, n[0]), leaf(ASSET, n[1]), leaf(CR_ASSET, n[2]), leaf(CR_ASSET, n[3])];
    let n01 = privacy_core::h2(l[0], l[1]);
    let n23 = privacy_core::h2(l[2], l[3]);
    for k in 0..4 {
        for i in 2..DEPTH {
            n[k].path[i] = z[i];
        }
    }
    n[0].path[0] = l[1];
    n[0].path[1] = n23;
    n[1].path[0] = l[0];
    n[1].path[1] = n23;
    n[2].path[0] = l[3];
    n[2].path[1] = n01;
    n[3].path[0] = l[2];
    n[3].path[1] = n01;
    let root = merkle_root(l[0], 0, n[0].path);
    for k in 1..4 {
        assert(root == merkle_root(l[k], n[k].pos, n[k].path));
    }
    (n, root)
}

/// The slash debt tree holding one row (key, retained), and the witness
/// for `lookup`: the row itself if lookup == key, else its low leaf.
fn debt(key: Field, retained: u64, lookup: Field) -> Debt {
    let z = privacy_core::zero_hashes();
    let sentinel = debt_leaf(0, key, 1, 0);
    let row = debt_leaf(key, 0, 0, retained);
    let mut p0 = [0; DEPTH];
    let mut p1 = [0; DEPTH];
    for i in 1..DEPTH {
        p0[i] = z[i];
        p1[i] = z[i];
    }
    p0[0] = row;
    p1[0] = sentinel;
    let root = merkle_root(sentinel, 0, p0);
    if lookup.lt(key) {
        Debt { root, low_key: 0, low_next_key: key, low_next_index: 1, low_retained: 0, low_index: 0, low_path: p0 }
    } else {
        Debt { root, low_key: key, low_next_key: 0, low_next_index: 0, low_retained: retained, low_index: 1, low_path: p1 }
    }
}

fn no_debt() -> Debt {
    debt(0x7777, 1, 0x7777)
}

fn nf_of(nk: Field, i: In, pad: bool) -> Field {
    if (i.amount != 0) | pad {
        stake_nf(nk, i.rho, i.pos)
    } else {
        0
    }
}

fn gw_of(nk: Field, i: In, pad: bool) -> Field {
    if (i.amount != 0) | pad {
        stake_gw(nk, i.rho)
    } else {
        0
    }
}

/// Lane A only, nothing cleared, the output unlabelled unless keep: every
/// public value as an honest prover fills it.
fn honest(ins: [In; 2], o: Out, anchor: Field, v_in: u64, v_out: u64) -> St {
    let mut out_label = 0;
    for i in 0..2 {
        if ins[i].mk != 0 {
            out_label = label_of(ins[i].mk, ins[i].mt, ins[i].ex);
        }
    }
    let cm_out = if o.amount != 0 {
        cm_of(NK, ASSET, o.amount, o.rho, o.rcm, out_label)
    } else {
        0
    };
    St {
        nk: NK,
        ins,
        out: o,
        clear: false,
        debt: no_debt(),
        cr_in: none_in(),
        cr_out: none_out(),
        anchor,
        asset: ASSET,
        nf: [nf_of(NK, ins[0], false), nf_of(NK, ins[1], false)],
        cm_out,
        v_in,
        v_out,
        clear_before: OPEN,
        cr_asset: 0,
        cr_nf: 0,
        cr_cm: 0,
        cr_v_in: 0,
        cr_move_time: 0,
        gw: [gw_of(NK, ins[0], false), gw_of(NK, ins[1], false)],
        cr_gw: 0,
        gw_out: 0,
        w_out: 0,
        cr_gw_out: 0,
        cr_w_out: 0,
    }
}

/// s clearing its labelled input against d, its output unlabelled.
fn clearing(s: St, d: Debt) -> St {
    let mut t = s;
    t.clear = true;
    t.debt = d;
    t.clear_before = CLOSED;
    t.cm_out = if t.out.amount != 0 {
        cm_of(NK, ASSET, t.out.amount, t.out.rho, t.out.rcm, 0)
    } else {
        0
    };
    t
}

/// s with a credit lane: cr_in (an unlabelled note of CR_ASSET, or padding)
/// merged with cr_v_in, labelled by the move made at move_time (0: none).
fn with_credit(s: St, cr_in: In, cr_out: Out, cr_v_in: u64, move_time: u64) -> St {
    let mut t = s;
    t.cr_in = cr_in;
    t.cr_out = cr_out;
    t.cr_asset = CR_ASSET;
    t.cr_nf = nf_of(NK, cr_in, true);
    t.cr_gw = gw_of(NK, cr_in, true);
    t.cr_v_in = cr_v_in;
    t.cr_move_time = move_time;
    t.cr_cm = cm_of(NK, CR_ASSET, cr_out.amount, cr_out.rho, cr_out.rcm, label_of(t.cr_nf, move_time, cr_v_in));
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
        [s.ins[0].mk, s.ins[1].mk],
        [s.ins[0].mt, s.ins[1].mt],
        [s.ins[0].ex, s.ins[1].ex],
        s.out.amount,
        s.out.rho,
        s.out.rcm,
        s.clear,
        s.debt.low_key,
        s.debt.low_next_key,
        s.debt.low_next_index,
        s.debt.low_retained,
        s.debt.low_index,
        s.debt.low_path,
        s.cr_in.amount,
        s.cr_in.rho,
        s.cr_in.rcm,
        s.cr_in.pos,
        s.cr_in.path,
        s.cr_out.rho,
        s.cr_out.rcm,
        s.anchor,
        s.asset,
        s.nf[0],
        s.nf[1],
        s.cm_out,
        s.v_in,
        s.v_out,
        s.clear_before,
        s.debt.root,
        s.cr_asset,
        s.cr_nf,
        s.cr_cm,
        s.cr_v_in,
        s.cr_move_time,
        s.gw[0],
        s.gw[1],
        s.cr_gw,
        s.gw_out,
        s.w_out,
        s.cr_gw_out,
        s.cr_w_out,
        SIGHASH,
    );
}

// ---- positive: every op the chain uses

#[test]
fn test_first_delegation_padding_input() {
    let (_, root) = tree(300, 500, 100);
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
    let (n, root) = tree(300, 500, 100);
    prove(honest([n[0], none_in()], out(500, 0xe1), root, 200, 0));
}

#[test]
fn test_top_up_to_note_max() {
    let (n, root) = tree(MAX - 5, 500, 100);
    prove(honest([n[0], none_in()], out(MAX, 0xe1), root, 5, 0));
}

#[test]
fn test_restake_merges_two_notes() {
    // The labelled note and an unlabelled one: the label and its exposure
    // carry to the merged note.
    let (n, root) = tree(300, 500, 100);
    prove(honest([n[0], n[1]], out(800, 0xe1), root, 0, 0));
}

#[test]
fn test_top_up_keeps_the_label() {
    // A delegation into a note holding a live exposure: the unexposed part
    // grows, the exposure is untouched.
    let (n, root) = tree(300, 500, 100);
    prove(honest([n[1], none_in()], out(1500, 0xe1), root, 1000, 0));
}

#[test]
fn test_unexposed_part_leaves_a_labelled_note() {
    // 300 of the 500 are unexposed: they may leave while the window is open.
    let (n, root) = tree(300, 500, 100);
    prove(honest([n[1], none_in()], out(200, 0xe1), root, 0, 300));
}

#[test]
fn test_clear_unslashed_move() {
    // The window closed and the move was never slashed (absent from the debt
    // tree): the whole exposure is worth its derth, and leaves.
    let (n, root) = tree(300, 500, 100);
    let s = honest([n[1], none_in()], out(0, 0xe1), root, 0, 500);
    let mut s = clearing(s, debt(0x9999, 1, MK));
    s.cm_out = cm_of(NK, ASSET, 0, 0xe1, 0xe2, 0);
    prove(s);
}

#[test]
fn test_clear_unslashed_move_above_the_row() {
    let (n, root) = tree(300, 500, 100);
    let s = honest([n[1], none_in()], out(500, 0xe1), root, 0, 0);
    prove(clearing(s, debt(0x1111, 1, MK)));
}

#[test]
fn test_clear_slashed_move_pays_the_haircut() {
    // Slashed: the 200 exposed are worth 190. The note of 500 becomes 490.
    let (n, root) = tree(300, 500, 100);
    let s = honest([n[1], none_in()], out(490, 0xe1), root, 0, 0);
    prove(clearing(s, debt(MK, 190, MK)));
}

#[test]
fn test_clear_and_undelegate_all() {
    let (n, root) = tree(300, 500, 100);
    let s = honest([n[0], n[1]], none_out(), root, 0, 790);
    prove(clearing(s, debt(MK, 190, MK)));
}

#[test]
fn test_undelegate_with_change() {
    let (n, root) = tree(300, 500, 100);
    prove(honest([n[0], none_in()], out(50, 0xe1), root, 0, 250));
}

#[test]
fn test_undelegate_all_padding_output() {
    let (n, root) = tree(300, 500, 100);
    let mut s = honest([n[0], none_in()], out(0, 0xe1), root, 0, 300);
    s.cm_out = cm_of(NK, ASSET, 0, 0xe1, 0xe2, 0);
    prove(s);
}

#[test]
fn test_redelegate_merges_into_existing() {
    // 250 of A's 300 leave; the chain credits 240 of B, merged into the
    // owner's unlabelled B note of 100 and labelled by this move.
    let (n, root) = tree(300, 500, 100);
    let s = honest([n[0], none_in()], out(50, 0xe1), root, 0, 250);
    prove(with_credit(s, n[2], out(340, 0xf1), 240, 7000));
}

#[test]
fn test_redelegate_padding_credit_input() {
    let (n, root) = tree(300, 500, 100);
    let mut s = honest([n[0], none_in()], out(0, 0xe1), root, 0, 300);
    s.cm_out = cm_of(NK, ASSET, 0, 0xe1, 0xe2, 0);
    prove(with_credit(s, dummy_in(0xd2), out(290, 0xf1), 290, 7000));
}

#[test]
fn test_redelegate_clears_its_source_first() {
    // A note whose window closed moves on, cleared at its haircut, in the
    // same proof.
    let (n, root) = tree(300, 500, 100);
    let s = honest([n[1], none_in()], none_out(), root, 0, 490);
    let s = clearing(s, debt(MK, 190, MK));
    prove(with_credit(s, n[2], out(580, 0xf1), 480, 7000));
}

#[test]
fn test_public_v_out_above_note_max() {
    // v_out is a public amount, not a note: two maximal notes (one labelled,
    // cleared unslashed) leave as 2^64 - 2.
    let (n, root) = tree(MAX, MAX, 100);
    prove(clearing(honest([n[0], n[1]], none_out(), root, 0, 0xfffffffffffffffe), debt(0x9999, 1, MK)));
}

// ---- the 2^63 - 1 note bound

#[test(should_fail_with = "assert_max_bit_size")]
fn test_top_up_past_note_max() {
    let (n, root) = tree(MAX, 500, 100);
    prove(honest([n[0], none_in()], out(0x8000000000000000, 0xe1), root, 1, 0));
}

#[test(should_fail_with = "assert_max_bit_size")]
fn test_credit_past_note_max() {
    let (n, root) = tree(300, 500, MAX);
    let mut s = honest([n[0], none_in()], out(0, 0xe1), root, 0, 300);
    s.cm_out = cm_of(NK, ASSET, 0, 0xe1, 0xe2, 0);
    prove(with_credit(s, n[2], out(0x8000000000000000, 0xf1), 1, 7000));
}

#[test(should_fail_with = "assert_max_bit_size")]
fn test_input_above_note_max() {
    let (n, root) = tree(0x8000000000000000, 500, 100);
    prove(honest([n[0], none_in()], out(MAX, 0xe1), root, 0, 1));
}

// ---- negative: the exposure never leaves while the window is open

#[test(should_fail_with = "the exposure stays in the note")]
fn test_exposed_part_cannot_leave() {
    // 301 of 500 leaving takes exposed derth.
    let (n, root) = tree(300, 500, 100);
    prove(honest([n[1], none_in()], out(199, 0xe1), root, 0, 301));
}

#[test(should_fail_with = "the move's window is still open")]
fn test_cannot_clear_an_open_window() {
    let (n, root) = tree(300, 500, 100);
    let s = honest([n[1], none_in()], out(0, 0xe1), root, 0, 500);
    let mut s = clearing(s, debt(0x9999, 1, MK));
    s.cm_out = cm_of(NK, ASSET, 0, 0xe1, 0xe2, 0);
    s.clear_before = MT;
    prove(s);
}

#[test(should_fail_with = "bad output commitment")]
fn test_keep_drops_the_label() {
    // Keeping the exposure but publishing an unlabelled output.
    let (n, root) = tree(300, 500, 100);
    let mut s = honest([n[1], none_in()], out(1500, 0xe1), root, 1000, 0);
    s.cm_out = cm_of(NK, ASSET, 1500, 0xe1, 0xe2, 0);
    prove(s);
}

#[test(should_fail_with = "bad output commitment")]
fn test_keep_shrinks_the_exposure() {
    let (n, root) = tree(300, 500, 100);
    let mut s = honest([n[1], none_in()], out(1500, 0xe1), root, 1000, 0);
    s.cm_out = cm_of(NK, ASSET, 1500, 0xe1, 0xe2, stake_label(MK, MT, 1));
    prove(s);
}

#[test(should_fail_with = "two labelled inputs")]
fn test_two_labels_do_not_merge() {
    let (n, root) = tree(300, 500, 100);
    let mut b2 = n[1];
    b2.rho = 0xb9;
    prove(honest([n[1], b2], out(1000, 0xe1), root, 0, 0));
}

#[test(should_fail_with = "input not in stake tree")]
fn test_label_cannot_be_dropped_from_an_input() {
    // Spending the labelled note as if it were unlabelled.
    let (n, root) = tree(300, 500, 100);
    let mut b = n[1];
    b.mk = 0;
    b.mt = 0;
    b.ex = 0;
    prove(honest([b, none_in()], none_out(), root, 0, 500));
}

#[test(should_fail_with = "input not in stake tree")]
fn test_exposure_cannot_be_understated() {
    let (n, root) = tree(300, 500, 100);
    let mut b = n[1];
    b.ex = 1;
    prove(honest([b, none_in()], out(1, 0xe1), root, 0, 499));
}

#[test(should_fail_with = "input not in stake tree")]
fn test_move_time_cannot_be_backdated() {
    // An earlier move time would open the window early.
    let (n, root) = tree(300, 500, 100);
    let mut b = n[1];
    b.mt = 1;
    let s = honest([b, none_in()], none_out(), root, 0, 500);
    prove(clearing(s, debt(0x9999, 1, MK)));
}

// ---- negative: the debt row cannot be skipped

#[test(should_fail_with = "amounts do not balance")]
fn test_clear_ignores_the_haircut() {
    let (n, root) = tree(300, 500, 100);
    let s = honest([n[1], none_in()], out(500, 0xe1), root, 0, 0);
    prove(clearing(s, debt(MK, 190, MK)));
}

#[test(should_fail_with = "move is in the debt tree")]
fn test_clear_skips_its_row() {
    // Proving the slashed move absent with the sentinel as its low leaf.
    let (n, root) = tree(300, 500, 100);
    let s = honest([n[1], none_in()], out(500, 0xe1), root, 0, 0);
    let mut d = debt(MK, 190, MK);
    let z = privacy_core::zero_hashes();
    let mut p0 = [0; DEPTH];
    for i in 1..DEPTH {
        p0[i] = z[i];
    }
    p0[0] = debt_leaf(MK, 0, 0, 190);
    d.low_key = 0;
    d.low_next_key = MK;
    d.low_next_index = 1;
    d.low_retained = 0;
    d.low_index = 0;
    d.low_path = p0;
    prove(clearing(s, d));
}

#[test(should_fail_with = "debt leaf not in debt tree")]
fn test_clear_with_a_stale_debt_root() {
    // The witness of a tree without the row, against the tree with it.
    let (n, root) = tree(300, 500, 100);
    let s = honest([n[1], none_in()], out(500, 0xe1), root, 0, 0);
    let stale = debt(0x9999, 1, MK);
    let mut d = debt(MK, 190, MK);
    d.low_key = stale.low_key;
    d.low_next_key = stale.low_next_key;
    d.low_next_index = stale.low_next_index;
    d.low_retained = stale.low_retained;
    d.low_index = stale.low_index;
    d.low_path = stale.low_path;
    prove(clearing(s, d));
}

#[test(should_fail_with = "nothing to clear")]
fn test_clear_without_a_label() {
    let (n, root) = tree(300, 500, 100);
    let s = honest([n[0], none_in()], out(300, 0xe1), root, 0, 0);
    prove(clearing(s, debt(0x9999, 1, MK)));
}

// ---- negative: the credit lane

#[test(should_fail_with = "input not in stake tree")]
fn test_credit_into_a_labelled_note() {
    // The credit lane merges only into an unlabelled note.
    let (n, root) = tree(300, 500, 100);
    let mut s = honest([n[0], none_in()], out(0, 0xe1), root, 0, 300);
    s.cm_out = cm_of(NK, ASSET, 0, 0xe1, 0xe2, 0);
    prove(with_credit(s, n[3], out(590, 0xf1), 290, 7000));
}

#[test(should_fail_with = "bad output commitment")]
fn test_credit_without_its_label() {
    let (n, root) = tree(300, 500, 100);
    let s = honest([n[0], none_in()], out(50, 0xe1), root, 0, 250);
    let mut s = with_credit(s, n[2], out(340, 0xf1), 240, 7000);
    s.cr_cm = cm_of(NK, CR_ASSET, 340, 0xf1, 0xf2, 0);
    prove(s);
}

#[test(should_fail_with = "bad output commitment")]
fn test_credit_labels_less_exposure() {
    let (n, root) = tree(300, 500, 100);
    let s = honest([n[0], none_in()], out(50, 0xe1), root, 0, 250);
    let mut s = with_credit(s, n[2], out(340, 0xf1), 240, 7000);
    s.cr_cm = cm_of(NK, CR_ASSET, 340, 0xf1, 0xf2, stake_label(s.cr_nf, 7000, 1));
    prove(s);
}

#[test(should_fail_with = "bad output commitment")]
fn test_credit_creates_value() {
    let (n, root) = tree(300, 500, 100);
    let s = honest([n[0], none_in()], out(50, 0xe1), root, 0, 250);
    let mut s = with_credit(s, n[2], out(341, 0xf1), 240, 7000);
    s.cr_cm = cm_of(NK, CR_ASSET, 341, 0xf1, 0xf2, stake_label(s.cr_nf, 7000, 240));
    prove(s);
}

// ---- negative: ownership, nullifiers, assets, balance

#[test(should_fail_with = "bad output commitment")]
fn test_output_to_another_owner() {
    let (n, root) = tree(300, 500, 100);
    let mut s = honest([n[0], none_in()], out(500, 0xe1), root, 200, 0);
    s.cm_out = cm_of(0xbad, ASSET, 500, 0xe1, 0xe2, 0);
    prove(s);
}

#[test(should_fail_with = "bad output commitment")]
fn test_credit_to_another_owner() {
    let (n, root) = tree(300, 500, 100);
    let s = honest([n[0], none_in()], out(50, 0xe1), root, 0, 250);
    let mut s = with_credit(s, n[2], out(340, 0xf1), 240, 7000);
    s.cr_cm = cm_of(0xbad, CR_ASSET, 340, 0xf1, 0xf2, stake_label(s.cr_nf, 7000, 240));
    prove(s);
}

#[test(should_fail_with = "bad output commitment")]
fn test_padding_output_arbitrary() {
    let (n, root) = tree(300, 500, 100);
    let mut s = honest([n[0], none_in()], out(0, 0xe1), root, 0, 300);
    s.cm_out = 5;
    prove(s);
}

#[test(should_fail_with = "input not in stake tree")]
fn test_spend_another_owners_note() {
    let (n, root) = tree(300, 500, 100);
    let mut s = honest([n[0], none_in()], none_out(), root, 0, 300);
    s.nk = 0xbad;
    s.nf[0] = stake_nf(0xbad, n[0].rho, n[0].pos);
    s.gw[0] = stake_gw(0xbad, n[0].rho);
    prove(s);
}

#[test(should_fail_with = "bad nullifier")]
fn test_wrong_nullifier() {
    let (n, root) = tree(300, 500, 100);
    let mut s = honest([n[0], none_in()], none_out(), root, 0, 300);
    s.nf[0] = stake_nf(NK, n[0].rho, 7);
    prove(s);
}

#[test(should_fail_with = "bad nullifier")]
fn test_real_input_hides_its_nullifier() {
    let (n, root) = tree(300, 500, 100);
    let mut s = honest([n[0], none_in()], none_out(), root, 0, 300);
    s.nf[0] = 0;
    prove(s);
}

#[test(should_fail_with = "bad nullifier")]
fn test_padding_nullifier_arbitrary() {
    let mut s = honest([dummy_in(0xd1), none_in()], out(1000, 0xe1), 0, 1000, 0);
    s.nf[0] = 5;
    prove(s);
}

#[test(should_fail_with = "a padding input carries no label")]
fn test_padding_input_with_a_label() {
    let mut d = dummy_in(0xd1);
    d.mk = MK;
    d.ex = 1;
    let mut s = honest([d, none_in()], out(1000, 0xe1), 0, 1000, 0);
    s.nf[0] = stake_nf(NK, 0xd1, 0);
    prove(s);
}

#[test(should_fail_with = "input not in stake tree")]
fn test_wrong_anchor() {
    let (n, root) = tree(300, 500, 100);
    prove(honest([n[0], none_in()], none_out(), root + 1, 0, 300));
}

#[test(should_fail_with = "input not in stake tree")]
fn test_other_asset() {
    let (n, root) = tree(300, 500, 100);
    let mut s = honest([n[0], none_in()], none_out(), root, 0, 300);
    s.asset = ASSET + 1;
    prove(s);
}

#[test(should_fail_with = "input not in stake tree")]
fn test_lane_a_note_of_the_credit_asset() {
    let (n, root) = tree(300, 500, 100);
    prove(honest([n[2], none_in()], none_out(), root, 0, 100));
}

#[test(should_fail_with = "amounts do not balance")]
fn test_creates_value() {
    let (n, root) = tree(300, 500, 100);
    prove(honest([n[0], none_in()], out(301, 0xe1), root, 0, 0));
}

#[test(should_fail_with = "amounts do not balance")]
fn test_top_up_claims_more_than_credited() {
    let (n, root) = tree(300, 500, 100);
    prove(honest([n[0], none_in()], out(501, 0xe1), root, 200, 0));
}

#[test(should_fail_with = "amounts do not balance")]
fn test_padding_input_carries_no_value() {
    let mut s = honest([dummy_in(0xd1), none_in()], out(1001, 0xe1), 0, 1000, 0);
    s.nf[0] = stake_nf(NK, 0xd1, 0);
    prove(s);
}

// ---- Groundworks votes

/// s with lane A's output voting.
fn voting(s: St) -> St {
    let mut t = s;
    t.gw_out = stake_gw(NK, t.out.rho);
    let mut ex = 0;
    if !t.clear {
        for i in 0..2 {
            if t.ins[i].mk != 0 {
                ex = t.ins[i].ex;
            }
        }
    }
    t.w_out = t.out.amount - ex;
    t
}

#[test]
fn test_first_delegation_votes() {
    prove(voting(honest([none_in(), none_in()], out(1000, 0xe1), 0, 1000, 0)));
}

#[test]
fn test_top_up_carries_the_vote() {
    // The old note's tag is published (the chain cancels its vote) and the
    // merged note votes its whole amount.
    let (n, root) = tree(300, 500, 100);
    prove(voting(honest([n[0], none_in()], out(500, 0xe1), root, 200, 0)));
}

#[test]
fn test_restake_onto_itself_votes() {
    // Voting, renewing or changing the split alone: the note respent as is.
    let (n, root) = tree(300, 500, 100);
    prove(voting(honest([n[0], none_in()], out(300, 0xe9), root, 0, 0)));
}

#[test]
fn test_labelled_note_votes_its_unexposed_part() {
    let (n, root) = tree(300, 500, 100);
    let s = voting(honest([n[1], none_in()], out(1500, 0xe1), root, 1000, 0));
    assert(s.w_out == 1300);
    prove(s);
}

#[test]
fn test_cleared_note_votes_all() {
    let (n, root) = tree(300, 500, 100);
    let s = clearing(honest([n[1], none_in()], out(490, 0xe1), root, 0, 0), debt(MK, 190, MK));
    let s = voting(s);
    assert(s.w_out == 490);
    prove(s);
}

#[test]
fn test_undelegate_with_change_votes() {
    let (n, root) = tree(300, 500, 100);
    prove(voting(honest([n[0], none_in()], out(50, 0xe1), root, 0, 250)));
}

#[test]
fn test_credit_votes_its_unexposed_part() {
    // A move: the source's change and the destination's merged note both
    // vote; the arrival (240, labelled) is exposed, the 100 already there is not.
    let (n, root) = tree(300, 500, 100);
    let s = voting(honest([n[0], none_in()], out(50, 0xe1), root, 0, 250));
    let mut s = with_credit(s, n[2], out(340, 0xf1), 240, 7000);
    s.cr_gw_out = stake_gw(NK, 0xf1);
    s.cr_w_out = 100;
    prove(s);
}

#[test(should_fail_with = "the vote's weight is the unexposed amount")]
fn test_credit_vote_counts_its_exposure() {
    let (n, root) = tree(300, 500, 100);
    let s = honest([n[0], none_in()], out(50, 0xe1), root, 0, 250);
    let mut s = with_credit(s, n[2], out(340, 0xf1), 240, 7000);
    s.cr_gw_out = stake_gw(NK, 0xf1);
    s.cr_w_out = 340;
    prove(s);
}

#[test(should_fail_with = "the vote's weight is the unexposed amount")]
fn test_vote_counts_the_exposure() {
    let (n, root) = tree(300, 500, 100);
    let mut s = voting(honest([n[1], none_in()], out(1500, 0xe1), root, 1000, 0));
    s.w_out = 1500;
    prove(s);
}

#[test(should_fail_with = "the vote's weight is the unexposed amount")]
fn test_vote_overstates_its_weight() {
    let (n, root) = tree(300, 500, 100);
    let mut s = voting(honest([n[0], none_in()], out(500, 0xe1), root, 200, 0));
    s.w_out = 501;
    prove(s);
}

#[test(should_fail_with = "the vote's weight is the unexposed amount")]
fn test_vote_understates_its_weight() {
    let (n, root) = tree(300, 500, 100);
    let mut s = voting(honest([n[0], none_in()], out(500, 0xe1), root, 200, 0));
    s.w_out = 499;
    prove(s);
}

#[test(should_fail_with = "bad vote tag")]
fn test_vote_under_another_notes_tag() {
    let (n, root) = tree(300, 500, 100);
    let mut s = voting(honest([n[0], none_in()], out(500, 0xe1), root, 200, 0));
    s.gw_out = stake_gw(NK, 0xa1);
    prove(s);
}

#[test(should_fail_with = "bad vote tag")]
fn test_vote_under_another_owners_tag() {
    let (n, root) = tree(300, 500, 100);
    let mut s = voting(honest([n[0], none_in()], out(500, 0xe1), root, 200, 0));
    s.gw_out = stake_gw(0xbad, 0xe1);
    prove(s);
}

#[test(should_fail_with = "a weight without a vote")]
fn test_weight_without_a_vote() {
    let (n, root) = tree(300, 500, 100);
    let mut s = honest([n[0], none_in()], out(500, 0xe1), root, 200, 0);
    s.w_out = 500;
    prove(s);
}

#[test(should_fail_with = "a padding output cannot vote")]
fn test_padding_output_votes() {
    let (n, root) = tree(300, 500, 100);
    let mut s = honest([n[0], none_in()], out(0, 0xe1), root, 0, 300);
    s.cm_out = cm_of(NK, ASSET, 0, 0xe1, 0xe2, 0);
    s.gw_out = stake_gw(NK, 0xe1);
    prove(s);
}

#[test(should_fail_with = "a padding output cannot vote")]
fn test_padding_credit_output_votes() {
    let mut s = honest([none_in(), none_in()], out(1000, 0xe1), 0, 1000, 0);
    s.cr_gw_out = stake_gw(NK, 0x99);
    prove(s);
}

#[test(should_fail_with = "bad groundworks tag")]
fn test_real_input_hides_its_tag() {
    // A voting note spent without its tag would keep its vote.
    let (n, root) = tree(300, 500, 100);
    let mut s = honest([n[0], none_in()], none_out(), root, 0, 300);
    s.gw[0] = 0;
    prove(s);
}

#[test(should_fail_with = "bad groundworks tag")]
fn test_real_input_publishes_another_tag() {
    let (n, root) = tree(300, 500, 100);
    let mut s = honest([n[0], none_in()], none_out(), root, 0, 300);
    s.gw[0] = stake_gw(NK, 0xb1);
    prove(s);
}

#[test(should_fail_with = "bad groundworks tag")]
fn test_credit_input_hides_its_tag() {
    let (n, root) = tree(300, 500, 100);
    let s = honest([n[0], none_in()], out(50, 0xe1), root, 0, 250);
    let mut s = with_credit(s, n[2], out(340, 0xf1), 240, 7000);
    s.cr_gw = 0;
    prove(s);
}

#[test(should_fail_with = "bad groundworks tag")]
fn test_padding_tag_arbitrary() {
    let mut s = honest([dummy_in(0xd1), none_in()], out(1000, 0xe1), 0, 1000, 0);
    s.gw[0] = 5;
    prove(s);
}

#[test]
fn test_padding_input_may_publish_its_tag() {
    let mut s = honest([dummy_in(0xd1), none_in()], out(1000, 0xe1), 0, 1000, 0);
    s.gw[0] = stake_gw(NK, 0xd1);
    prove(s);
}
