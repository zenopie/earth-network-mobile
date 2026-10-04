//! vote — one owner's private vote with up to MAX_NOTES stake notes at one
//! validator on one x/gov proposal, without spending them, revealing one
//! weight (earth-network-chain x/shieldedstaking, ORCHARD_DESIGN.md sections
//! 15, 18 and 20).
//!
//! When a proposal enters voting the chain snapshots two roots: the stake note
//! tree's (`note_root`) and the stake nullifier indexed tree's (`nf_root`).
//! An owner (one nk) votes up to MAX_NOTES of its derth/<valoper> stake notes
//! (one `asset`) on that proposal by proving, for every used slot i
//! (amount_i != 0):
//!
//!   1. note i is in the stake note tree at the snapshot (path to note_root);
//!   2. it was unspent at the snapshot: its spend nullifier
//!        nf_i = H(TAG_SNF, nk, rho_i, position_i)
//!      is NOT in the nullifier tree at the snapshot (a low leaf under nf_root
//!      with low.value < nf_i < low.next_value, or low.next_value = 0);
//!   3. vnf_i = H(TAG_VNF, nk, rho_i, position_i, proposal_id), its vote
//!      nullifier, which the chain refuses a second time for this proposal;
//!   4. its value: its amount, or, for a note holding a redelegation's
//!      exposure (label (move_key, move_time, exposed)), its amount less what
//!      slashes have cut from the exposure so far: amount - exposed +
//!      debt_retained(move_key, exposed) against `debt_root`, the CURRENT slash
//!      debt tree (a slash after the snapshot still counts);
//!
//! an unused slot (amount_i = 0) has vnf_i = 0 and proves nothing; and
//!
//!   5. 0 < weight <= sum of the values (the vote's one public weight; the
//!      wallet publishes the sum rounded down to three significant digits).
//!      Each amount is at most 2^63 - 1 (privacy_core NOTE_VALUE_BITS,
//!      inside stake_cm), the most a stake note holds.
//!
//! The circuit does not make the used slots' notes distinct: the same note in
//! two slots has the same vnf, and the chain refuses a msg whose vote
//! nullifiers repeat (and any already used on the proposal), so each note
//! counts once.
//!
//! Nothing is spent and nothing is created: the notes stay where they are and
//! can vote on every other open proposal (each with its own vnfs) and be
//! spent as usual. No nf_i is published: votes are unlinkable to one another
//! and to the notes' spends, except through their public weight, validator
//! and the number of slots used. Binding: the msg's sighash (options,
//! proposal, validator, fee bundle, ...).
//!
//! Public input order:
//!   note_root, nf_root, debt_root, asset, weight, proposal_id,
//!   vnf[0..MAX_NOTES], sighash

use privacy_core::{
    assert_not_in_indexed, bind, debt_retained, DEPTH, merkle_root, owner_pk, stake_cm, stake_label,
    stake_nf, stake_pc, vote_nf,
};

/// The most notes one vote proof carries. With one stake note per validator
/// (ORCHARD_DESIGN.md section 20) a vote uses one; the second covers a note
/// a redelegation into a validator with a labelled note made beside it. 2
/// slots with a debt lookup each keep the circuit within 2^15 gates, the
/// bundled SRS's 2^15 + 1 points.
global MAX_NOTES: u32 = 2;

fn main(
    nk: Field,
    amount: [u64; MAX_NOTES],
    rho: [Field; MAX_NOTES],
    rcm: [Field; MAX_NOTES],
    pos: [u32; MAX_NOTES],
    path: [[Field; DEPTH]; MAX_NOTES],
    move_key: [Field; MAX_NOTES],
    move_time: [u64; MAX_NOTES],
    exposed: [u64; MAX_NOTES],
    low_value: [Field; MAX_NOTES],
    low_next_value: [Field; MAX_NOTES],
    low_next_index: [u32; MAX_NOTES],
    low_index: [u32; MAX_NOTES],
    low_path: [[Field; DEPTH]; MAX_NOTES],
    debt_low_key: [Field; MAX_NOTES],
    debt_low_next_key: [Field; MAX_NOTES],
    debt_low_next_index: [u32; MAX_NOTES],
    debt_low_retained: [u64; MAX_NOTES],
    debt_low_index: [u32; MAX_NOTES],
    debt_low_path: [[Field; DEPTH]; MAX_NOTES],
    note_root: pub Field,
    nf_root: pub Field,
    debt_root: pub Field,
    asset: pub Field,
    weight: pub u64,
    proposal_id: pub u64,
    vnf: pub [Field; MAX_NOTES],
    sighash: pub Field,
) {
    let opk = owner_pk(nk);
    let mut total: u128 = 0;
    for i in 0..MAX_NOTES {
        if amount[i] != 0 {
            let lab = move_key[i] != 0;
            let mut label: Field = 0;
            let mut value: u64 = amount[i];
            if lab {
                assert((exposed[i] != 0) & (exposed[i] <= amount[i]), "bad exposure");
                label = stake_label(move_key[i], move_time[i], exposed[i]);
                let retained = debt_retained(
                    move_key[i],
                    exposed[i],
                    debt_low_key[i],
                    debt_low_next_key[i],
                    debt_low_next_index[i],
                    debt_low_retained[i],
                    debt_low_index[i],
                    debt_low_path[i],
                    debt_root,
                );
                value = amount[i] - exposed[i] + retained;
            }
            let cm = stake_cm(asset, amount[i], stake_pc(opk, rho[i], rcm[i]), label);
            assert(
                merkle_root(cm, pos[i], path[i]) == note_root,
                "note not in snapshot stake tree",
            );
            let nf = stake_nf(nk, rho[i], pos[i]);
            assert_not_in_indexed(
                nf,
                low_value[i],
                low_next_value[i],
                low_next_index[i],
                low_index[i],
                low_path[i],
                nf_root,
            );
            assert(vnf[i] == vote_nf(nk, rho[i], pos[i], proposal_id), "bad vote nullifier");
            total += value as u128;
        } else {
            assert(vnf[i] == 0, "unused slot has a vote nullifier");
        }
    }
    assert(weight != 0, "zero weight");
    assert(weight as u128 <= total, "weight exceeds the notes");
    bind(sighash);
}

// ---------------------------------------------------------------- tests

use privacy_core::{debt_leaf, h2, nf_leaf, zero_hashes};

global NK: Field = 0x5eed;
global ASSET: Field = 0xde27;
global SIGHASH: Field = 0x5164;
global PROPOSAL: u64 = 7;
/// The labelled note's move (position 3): key, time, exposure.
global MK: Field = 0x4d4b;
global MT: u64 = 1000;
global EX: u64 = 100;

/// One slot's witness.
struct Slot {
    amount: u64,
    rho: Field,
    rcm: Field,
    pos: u32,
    path: [Field; DEPTH],
    mk: Field,
    mt: u64,
    ex: u64,
    low_value: Field,
    low_next_value: Field,
    low_next_index: u32,
    low_index: u32,
    low_path: [Field; DEPTH],
    debt: Debt,
    vnf: Field,
}

struct Debt {
    low_key: Field,
    low_next_key: Field,
    low_next_index: u32,
    low_retained: u64,
    low_index: u32,
    low_path: [Field; DEPTH],
}

struct V {
    nk: Field,
    slots: [Slot; MAX_NOTES],
    note_root: Field,
    nf_root: Field,
    debt_root: Field,
    asset: Field,
    weight: u64,
    proposal_id: u64,
}

fn no_debt() -> Debt {
    Debt { low_key: 0, low_next_key: 0, low_next_index: 0, low_retained: 0, low_index: 0, low_path: [0; DEPTH] }
}

fn unused() -> Slot {
    Slot {
        amount: 0,
        rho: 0x77,
        rcm: 0x78,
        pos: 0,
        path: [0; DEPTH],
        mk: 0,
        mt: 0,
        ex: 0,
        low_value: 0,
        low_next_value: 0,
        low_next_index: 0,
        low_index: 0,
        low_path: [0; DEPTH],
        debt: no_debt(),
        vnf: 0,
    }
}

fn prove(v: V) {
    let mut amount = [0; MAX_NOTES];
    let mut rho = [0; MAX_NOTES];
    let mut rcm = [0; MAX_NOTES];
    let mut pos = [0; MAX_NOTES];
    let mut path = [[0; DEPTH]; MAX_NOTES];
    let mut mk = [0; MAX_NOTES];
    let mut mt = [0; MAX_NOTES];
    let mut ex = [0; MAX_NOTES];
    let mut low_value = [0; MAX_NOTES];
    let mut low_next_value = [0; MAX_NOTES];
    let mut low_next_index = [0; MAX_NOTES];
    let mut low_index = [0; MAX_NOTES];
    let mut low_path = [[0; DEPTH]; MAX_NOTES];
    let mut d_low_key = [0; MAX_NOTES];
    let mut d_low_next_key = [0; MAX_NOTES];
    let mut d_low_next_index = [0; MAX_NOTES];
    let mut d_low_retained = [0; MAX_NOTES];
    let mut d_low_index = [0; MAX_NOTES];
    let mut d_low_path = [[0; DEPTH]; MAX_NOTES];
    let mut vnf = [0; MAX_NOTES];
    for i in 0..MAX_NOTES {
        let s = v.slots[i];
        amount[i] = s.amount;
        rho[i] = s.rho;
        rcm[i] = s.rcm;
        pos[i] = s.pos;
        path[i] = s.path;
        mk[i] = s.mk;
        mt[i] = s.mt;
        ex[i] = s.ex;
        low_value[i] = s.low_value;
        low_next_value[i] = s.low_next_value;
        low_next_index[i] = s.low_next_index;
        low_index[i] = s.low_index;
        low_path[i] = s.low_path;
        d_low_key[i] = s.debt.low_key;
        d_low_next_key[i] = s.debt.low_next_key;
        d_low_next_index[i] = s.debt.low_next_index;
        d_low_retained[i] = s.debt.low_retained;
        d_low_index[i] = s.debt.low_index;
        d_low_path[i] = s.debt.low_path;
        vnf[i] = s.vnf;
    }
    main(
        v.nk,
        amount,
        rho,
        rcm,
        pos,
        path,
        mk,
        mt,
        ex,
        low_value,
        low_next_value,
        low_next_index,
        low_index,
        low_path,
        d_low_key,
        d_low_next_key,
        d_low_next_index,
        d_low_retained,
        d_low_index,
        d_low_path,
        v.note_root,
        v.nf_root,
        v.debt_root,
        v.asset,
        v.weight,
        v.proposal_id,
        vnf,
        SIGHASH,
    );
}

/// A depth-32 tree of four leaves at indexes 0..3 (the rest empty): its root
/// and each leaf's path.
fn tree4(leaves: [Field; 4]) -> (Field, [[Field; DEPTH]; 4]) {
    let z = zero_hashes();
    let n01 = h2(leaves[0], leaves[1]);
    let n23 = h2(leaves[2], leaves[3]);
    let mut paths = [[0; DEPTH]; 4];
    for i in 0..4 {
        for l in 2..DEPTH {
            paths[i][l] = z[l];
        }
    }
    paths[0][0] = leaves[1];
    paths[1][0] = leaves[0];
    paths[2][0] = leaves[3];
    paths[3][0] = leaves[2];
    paths[0][1] = n23;
    paths[1][1] = n23;
    paths[2][1] = n01;
    paths[3][1] = n01;
    let root = merkle_root(leaves[0], 0, paths[0]);
    for i in 0..4 {
        assert(merkle_root(leaves[i], i as u32, paths[i]) == root);
    }
    (root, paths)
}

/// H(TAG_STAKE, ASSET, amount, spc, label) for any u64 amount (stake_cm
/// without its bound).
fn note_cm(nk: Field, amount: u64, rho: Field, rcm: Field, label: Field) -> Field {
    privacy_core::h5(
        privacy_core::TAG_STAKE,
        ASSET,
        amount as Field,
        stake_pc(owner_pk(nk), rho, rcm),
        label,
    )
}

/// Note tree: another owner's note of 9 at position 0, ours of 500, 300 and
/// 200 at positions 1, 2 and 3 (rho 0xa1, 0xb1, 0xc1; rcm 0xa2, 0xb2, 0xc2).
/// The note at 3 holds a redelegation's exposure of 100 (MK, MT).
global AMOUNTS: [u64; 4] = [9, 500, 300, 200];
global RHOS: [Field; 4] = [0x01, 0xa1, 0xb1, 0xc1];
global RCMS: [Field; 4] = [0x02, 0xa2, 0xb2, 0xc2];

fn notes() -> (Field, [[Field; DEPTH]; 4]) {
    tree4([
        note_cm(0xbad, AMOUNTS[0], RHOS[0], RCMS[0], 0),
        note_cm(NK, AMOUNTS[1], RHOS[1], RCMS[1], 0),
        note_cm(NK, AMOUNTS[2], RHOS[2], RCMS[2], 0),
        note_cm(NK, AMOUNTS[3], RHOS[3], RCMS[3], stake_label(MK, MT, EX)),
    ])
}

/// The spend nullifier of our note at position p (1..3).
fn nf_at(p: u32) -> Field {
    stake_nf(NK, RHOS[p], p)
}

/// The nullifier tree after inserting lo = nf - 1 then hi = nf + 1 (and, with
/// spent, nf itself) for nf = nf_at(1): leaves in insertion order, sorted list
/// 0 -> lo -> (nf ->) hi.
fn nullifiers(spent: bool) -> (Field, [Field; 4], [[Field; DEPTH]; 4]) {
    let nf = nf_at(1);
    let lo = nf - 1;
    let hi = nf + 1;
    let mut l = [nf_leaf(0, lo, 1), nf_leaf(lo, hi, 2), nf_leaf(hi, 0, 0), 0];
    if spent {
        l[1] = nf_leaf(lo, nf, 3);
        l[3] = nf_leaf(nf, hi, 2);
    }
    let (root, paths) = tree4(l);
    (root, l, paths)
}

/// The nullifier tree holding only its sentinel (nothing spent): every
/// nullifier's low leaf is (0, 0, 0) at index 0.
fn empty_nullifiers() -> (Field, [Field; DEPTH]) {
    let (root, paths) = tree4([nf_leaf(0, 0, 0), 0, 0, 0]);
    (root, paths[0])
}

/// The slash debt tree holding one row (key, retained): its root and the
/// witness for `lookup` (the row if lookup == key, else its low leaf).
fn debt(key: Field, retained: u64, lookup: Field) -> (Field, Debt) {
    let (root, paths) = tree4([debt_leaf(0, key, 1, 0), debt_leaf(key, 0, 0, retained), 0, 0]);
    if lookup.lt(key) {
        (root, Debt { low_key: 0, low_next_key: key, low_next_index: 1, low_retained: 0, low_index: 0, low_path: paths[0] })
    } else {
        (root, Debt { low_key: key, low_next_key: 0, low_next_index: 0, low_retained: retained, low_index: 1, low_path: paths[1] })
    }
}

/// Our note at position p, its low leaf the sentinel of the empty tree.
fn slot_empty_tree(p: u32) -> Slot {
    let (_, paths) = notes();
    let (_, low_path) = empty_nullifiers();
    let mut s = unused();
    s.amount = AMOUNTS[p];
    s.rho = RHOS[p];
    s.rcm = RCMS[p];
    s.pos = p;
    s.path = paths[p];
    s.low_path = low_path;
    s.vnf = vote_nf(NK, RHOS[p], p, PROPOSAL);
    s
}

/// Our notes at positions 1 and 2 in the two slots, against the empty
/// nullifier tree: weight 800, their sum.
fn two() -> V {
    let (note_root, _) = notes();
    let (nf_root, _) = empty_nullifiers();
    let (debt_root, _) = debt(0x9999, 1, 0);
    V {
        nk: NK,
        slots: [slot_empty_tree(1), slot_empty_tree(2)],
        note_root,
        nf_root,
        debt_root,
        asset: ASSET,
        weight: 800,
        proposal_id: PROPOSAL,
    }
}

/// The labelled note (position 3) in slot 0 against the debt tree holding
/// (key, retained), alone.
fn labelled(key: Field, retained: u64) -> V {
    let mut v = two();
    let (debt_root, d) = debt(key, retained, MK);
    v.debt_root = debt_root;
    v.slots[0] = slot_empty_tree(3);
    v.slots[0].mk = MK;
    v.slots[0].mt = MT;
    v.slots[0].ex = EX;
    v.slots[0].debt = d;
    v.slots[1] = unused();
    v.weight = 200;
    v
}

/// An honest vote of the note at position 1 alone (slot 0), its low leaf lo
/// (index 1) in the nullifier tree of lo and hi.
fn honest() -> V {
    let (note_root, paths) = notes();
    let (nf_root, _, nf_paths) = nullifiers(false);
    let (debt_root, _) = debt(0x9999, 1, 0);
    let nf = nf_at(1);
    let mut s = unused();
    s.amount = 500;
    s.rho = 0xa1;
    s.rcm = 0xa2;
    s.pos = 1;
    s.path = paths[1];
    s.low_value = nf - 1;
    s.low_next_value = nf + 1;
    s.low_next_index = 2;
    s.low_index = 1;
    s.low_path = nf_paths[1];
    s.vnf = vote_nf(NK, 0xa1, 1, PROPOSAL);
    V { nk: NK, slots: [s, unused()], note_root, nf_root, debt_root, asset: ASSET, weight: 500, proposal_id: PROPOSAL }
}

// ---- positive

#[test]
fn test_vote() {
    prove(honest());
}

#[test]
fn test_vote_partial_weight() {
    let mut v = honest();
    v.weight = 1;
    prove(v);
}

#[test]
fn test_two_notes_one_weight() {
    prove(two());
}

#[test]
fn test_two_notes_rounded_weight() {
    let mut v = two();
    v.weight = 799;
    prove(v);
}

#[test]
fn test_used_slot_second() {
    // An unused slot may come first (the chain asks for a prefix, the circuit
    // does not care).
    let mut v = two();
    v.slots[0] = unused();
    v.weight = 300;
    prove(v);
}

#[test]
fn test_labelled_unslashed_votes_its_amount() {
    // The move was never slashed: absent from the debt tree, the note votes
    // all 200.
    prove(labelled(0x9999, 1));
}

#[test]
fn test_labelled_unslashed_below_the_row() {
    prove(labelled(0x1111, 1));
}

#[test]
fn test_labelled_slashed_votes_its_haircut_value() {
    // Slashed: the exposure of 100 is worth 40, the note 140.
    let mut v = labelled(MK, 40);
    v.weight = 140;
    prove(v);
}

#[test]
fn test_labelled_beside_an_unlabelled_note() {
    let mut v = labelled(MK, 40);
    v.slots[1] = slot_empty_tree(1);
    v.weight = 640;
    prove(v);
}

#[test]
fn test_same_note_twice_same_vnf() {
    let mut v = two();
    v.slots[1] = v.slots[0];
    v.weight = 1000;
    prove(v);
    assert(v.slots[0].vnf == v.slots[1].vnf);
}

#[test]
fn test_same_notes_two_proposals() {
    let a = two();
    let mut b = two();
    b.proposal_id = 8;
    for i in 0..2 {
        b.slots[i].vnf = vote_nf(NK, b.slots[i].rho, b.slots[i].pos, 8);
    }
    prove(a);
    prove(b);
    for i in 0..2 {
        assert(a.slots[i].vnf != b.slots[i].vnf);
        assert(a.slots[i].vnf != nf_at(a.slots[i].pos));
    }
}

#[test]
fn test_largest_nullifier() {
    let nf = nf_at(1);
    let lo = nf - 1;
    let (root, paths) = tree4([nf_leaf(0, lo, 1), nf_leaf(lo, 0, 0), 0, 0]);
    let mut v = honest();
    v.slots[0].low_next_value = 0;
    v.slots[0].low_next_index = 0;
    v.slots[0].low_path = paths[1];
    v.nf_root = root;
    prove(v);
}

#[test]
fn test_empty_nullifier_tree() {
    let (root, low_path) = empty_nullifiers();
    let mut v = honest();
    v.slots[0].low_value = 0;
    v.slots[0].low_next_value = 0;
    v.slots[0].low_next_index = 0;
    v.slots[0].low_index = 0;
    v.slots[0].low_path = low_path;
    v.nf_root = root;
    prove(v);
}

#[test]
fn test_merged_after_snapshot_still_votes() {
    // Spent (merged into a newer note) after the snapshot: nf is in the
    // CURRENT nullifier tree but not in the snapshot's, which the vote proves
    // against. The pre-existing value keeps its vote.
    let (snap_root, _, _) = nullifiers(false);
    let (now_root, _, _) = nullifiers(true);
    let v = honest();
    assert(v.nf_root == snap_root);
    assert(snap_root != now_root);
    prove(v);
}

#[test]
fn test_note_max() {
    let m: u64 = 0x7fffffffffffffff;
    let (note_root, paths) = tree4([note_cm(0xbad, 9, 0x01, 0x02, 0), note_cm(NK, m, 0xa1, 0xa2, 0), 0, 0]);
    let mut v = honest();
    v.note_root = note_root;
    v.slots[0].path = paths[1];
    v.slots[0].amount = m;
    v.weight = m;
    prove(v);
}

#[test]
fn test_weight_u64_max_over_big_notes() {
    // Two maximal notes sum to 2^64 - 2: the sum is held in a u128.
    let m: u64 = 0x7fffffffffffffff;
    let (note_root, paths) = tree4([
        note_cm(0xbad, 9, 0x01, 0x02, 0),
        note_cm(NK, m, 0xa1, 0xa2, 0),
        note_cm(NK, m, 0xb1, 0xb2, 0),
        0,
    ]);
    let mut v = two();
    v.note_root = note_root;
    v.slots[0].amount = m;
    v.slots[0].path = paths[1];
    v.slots[1].amount = m;
    v.slots[1].path = paths[2];
    v.weight = 0xfffffffffffffffe;
    prove(v);
}

// ---- negative

#[test(should_fail_with = "weight exceeds the notes")]
fn test_weight_above_sum() {
    let mut v = two();
    v.weight = 801;
    prove(v);
}

#[test(should_fail_with = "weight exceeds the notes")]
fn test_labelled_slashed_cannot_vote_its_nominal() {
    // The haircut counts: 141 > 140.
    let mut v = labelled(MK, 40);
    v.weight = 141;
    prove(v);
}

#[test(should_fail_with = "move is in the debt tree")]
fn test_labelled_skips_its_row() {
    // The slashed move proven absent with the sentinel as its low leaf.
    let (debt_root, _) = debt(MK, 40, MK);
    let (_, below) = debt(MK, 40, 0);
    let mut v = labelled(MK, 40);
    v.debt_root = debt_root;
    v.slots[0].debt = below;
    prove(v);
}

#[test(should_fail_with = "debt leaf not in debt tree")]
fn test_labelled_stale_debt_root() {
    // The witness of the tree before the slash, against the current root.
    let (_, before) = debt(0x9999, 1, MK);
    let mut v = labelled(MK, 40);
    v.slots[0].debt = before;
    prove(v);
}

#[test(should_fail_with = "note not in snapshot stake tree")]
fn test_labelled_note_without_its_label() {
    // Dropping the label to vote the exposure unhaircut.
    let mut v = labelled(MK, 40);
    v.slots[0].mk = 0;
    v.slots[0].mt = 0;
    v.slots[0].ex = 0;
    prove(v);
}

#[test(should_fail_with = "note not in snapshot stake tree")]
fn test_labelled_understated_exposure() {
    let mut v = labelled(0x9999, 1);
    v.slots[0].ex = 1;
    prove(v);
}

#[test(should_fail_with = "unused slot has a vote nullifier")]
fn test_unused_slot_with_vnf() {
    let mut v = honest();
    v.slots[1].vnf = vote_nf(NK, 0xd1, 0, PROPOSAL);
    prove(v);
}

#[test(should_fail_with = "note not in snapshot stake tree")]
fn test_slot_of_another_owner() {
    let (_, paths) = notes();
    let mut v = two();
    v.slots[1].amount = 9;
    v.slots[1].rho = 0x01;
    v.slots[1].rcm = 0x02;
    v.slots[1].pos = 0;
    v.slots[1].path = paths[0];
    v.slots[1].vnf = vote_nf(NK, 0x01, 0, PROPOSAL);
    v.weight = 509;
    prove(v);
}

#[test(should_fail_with = "note not in snapshot stake tree")]
fn test_inflated_amount_in_one_slot() {
    let mut v = two();
    v.slots[1].amount = 301;
    v.weight = 801;
    prove(v);
}

#[test(should_fail_with = "bad vote nullifier")]
fn test_wrong_vnf_in_one_slot() {
    let mut v = two();
    v.slots[1].vnf = vote_nf(NK, RHOS[2], 2, PROPOSAL + 1);
    prove(v);
}

#[test(should_fail_with = "nullifier is in the nullifier tree")]
fn test_spent_before_snapshot() {
    let (root, _, paths) = nullifiers(true);
    let mut v = honest();
    v.nf_root = root;
    v.slots[0].low_next_value = nf_at(1);
    v.slots[0].low_next_index = 3;
    v.slots[0].low_path = paths[1];
    prove(v);
}

#[test(should_fail_with = "low leaf not below the nullifier")]
fn test_spent_before_snapshot_own_leaf() {
    let (root, _, paths) = nullifiers(true);
    let nf = nf_at(1);
    let mut v = honest();
    v.nf_root = root;
    v.slots[0].low_value = nf;
    v.slots[0].low_next_value = nf + 1;
    v.slots[0].low_next_index = 2;
    v.slots[0].low_index = 3;
    v.slots[0].low_path = paths[3];
    prove(v);
}

#[test(should_fail_with = "low leaf not in nullifier tree")]
fn test_spent_before_snapshot_stale_low_leaf() {
    let (root, _, paths) = nullifiers(true);
    let mut v = honest();
    v.nf_root = root;
    v.slots[0].low_path = paths[1];
    prove(v);
}

#[test(should_fail_with = "low leaf not in nullifier tree")]
fn test_forged_low_leaf() {
    let mut v = honest();
    v.slots[0].low_next_value = nf_at(1) + 5;
    prove(v);
}

#[test(should_fail_with = "low leaf not in nullifier tree")]
fn test_empty_slot_as_low_leaf() {
    let (_, _, paths) = nullifiers(false);
    let mut v = honest();
    v.slots[0].low_value = 0;
    v.slots[0].low_next_value = 0;
    v.slots[0].low_next_index = 0;
    v.slots[0].low_index = 3;
    v.slots[0].low_path = paths[3];
    prove(v);
}

#[test(should_fail_with = "low leaf not below the nullifier")]
fn test_low_leaf_above() {
    let (_, _, paths) = nullifiers(false);
    let nf = nf_at(1);
    let mut v = honest();
    v.slots[0].low_value = nf + 1;
    v.slots[0].low_next_value = 0;
    v.slots[0].low_next_index = 0;
    v.slots[0].low_index = 2;
    v.slots[0].low_path = paths[2];
    prove(v);
}

#[test(should_fail_with = "bad vote nullifier")]
fn test_wrong_proposal_id() {
    let mut v = honest();
    v.proposal_id = PROPOSAL + 1;
    prove(v);
}

#[test(should_fail_with = "bad vote nullifier")]
fn test_spend_nullifier_as_vnf() {
    let mut v = honest();
    v.slots[0].vnf = nf_at(1);
    prove(v);
}

#[test(should_fail_with = "bad vote nullifier")]
fn test_vnf_of_another_position() {
    let mut v = honest();
    v.slots[0].vnf = vote_nf(NK, 0xa1, 0, PROPOSAL);
    prove(v);
}

#[test(should_fail_with = "weight exceeds the notes")]
fn test_weight_above_amount() {
    let mut v = honest();
    v.weight = 501;
    prove(v);
}

#[test(should_fail_with = "assert_max_bit_size")]
fn test_amount_above_note_max() {
    let a: u64 = 0x8000000000000000;
    let (note_root, paths) = tree4([note_cm(0xbad, 9, 0x01, 0x02, 0), note_cm(NK, a, 0xa1, 0xa2, 0), 0, 0]);
    let mut v = honest();
    v.note_root = note_root;
    v.slots[0].path = paths[1];
    v.slots[0].amount = a;
    v.weight = a;
    prove(v);
}

#[test(should_fail_with = "zero weight")]
fn test_zero_weight() {
    let mut v = honest();
    v.weight = 0;
    prove(v);
}

#[test(should_fail_with = "zero weight")]
fn test_no_notes() {
    let mut v = honest();
    v.slots[0] = unused();
    v.weight = 0;
    prove(v);
}

#[test(should_fail_with = "note not in snapshot stake tree")]
fn test_inflated_amount() {
    let mut v = honest();
    v.slots[0].amount = 501;
    v.weight = 501;
    prove(v);
}

#[test(should_fail_with = "note not in snapshot stake tree")]
fn test_other_validator() {
    let mut v = honest();
    v.asset = ASSET + 1;
    prove(v);
}

#[test(should_fail_with = "note not in snapshot stake tree")]
fn test_another_owners_note() {
    let (_, paths) = notes();
    let mut v = honest();
    v.slots[0].amount = 9;
    v.weight = 9;
    v.slots[0].rho = 0x01;
    v.slots[0].rcm = 0x02;
    v.slots[0].pos = 0;
    v.slots[0].path = paths[0];
    prove(v);
}

#[test(should_fail_with = "note not in snapshot stake tree")]
fn test_note_after_snapshot() {
    let mut v = honest();
    v.note_root = v.note_root + 1;
    prove(v);
}

#[test(should_fail_with = "low leaf not in nullifier tree")]
fn test_wrong_nullifier_root() {
    let mut v = honest();
    v.nf_root = v.nf_root + 1;
    prove(v);
}

// ---- Go parity (earth-network-chain zk/indexed TestNoirParity)

#[test]
fn test_go_parity() {
    assert(nf_leaf(1, 2, 3) == 0xcdc3a81748c6389efaa3a6c29b7f4609a8e9f860230b70413e8bef512978276);
    assert(
        vote_nf(NK, 0xa1, 1, 7)
            == 0x1ada84dad3e6afde3f370e97edf4df2ee4eeb6b1400d5c5f41882552f578ba2f,
    );
    // indexed.EmptyRoot: the sentinel alone.
    let (empty, _) = empty_nullifiers();
    assert(empty == 0x18f5a2d2d3273f584793e90ac9bf77abf0ff2a05a101cd5943eaf7bbd0bd5b10);
    // Go's Insert(nf - 1), Insert(nf + 1) [, Insert(nf)] build these trees.
    let (snap, _, _) = nullifiers(false);
    let (now, _, _) = nullifiers(true);
    assert(snap == 0x154276da4092031ee0cc36041772a8ff6f563b14ae80df0d0f46ffe5b474cb6d);
    assert(now == 0x6a4a58053d288e995f3f876a1702085169ca86c6d739d072b94fc379a327048);
}
