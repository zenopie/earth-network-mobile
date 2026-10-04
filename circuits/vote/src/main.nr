//! vote — one owner's private vote with up to MAX_NOTES stake notes at one
//! validator on one x/gov proposal, without spending them, revealing one
//! weight (earth-network-chain x/shieldedstaking, ORCHARD_DESIGN.md sections
//! 15 and 18).
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
//!
//! an unused slot (amount_i = 0) has vnf_i = 0 and proves nothing; and
//!
//!   4. 0 < weight <= sum of the amounts (the vote's one public weight; the
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
//!   note_root, nf_root, asset, weight, proposal_id, vnf[0..MAX_NOTES], sighash

use privacy_core::{
    assert_not_in_indexed, bind, DEPTH, merkle_root, owner_pk, stake_cm, stake_nf, stake_pc,
    vote_nf,
};

/// The most notes one vote proof carries. 4 keeps the circuit under 2^15
/// gates (27,6xx), the bundled SRS's 2^15 + 1 points; 5 would not fit.
global MAX_NOTES: u32 = 4;

fn main(
    nk: Field,
    amount: [u64; MAX_NOTES],
    rho: [Field; MAX_NOTES],
    rcm: [Field; MAX_NOTES],
    pos: [u32; MAX_NOTES],
    path: [[Field; DEPTH]; MAX_NOTES],
    low_value: [Field; MAX_NOTES],
    low_next_value: [Field; MAX_NOTES],
    low_next_index: [u32; MAX_NOTES],
    low_index: [u32; MAX_NOTES],
    low_path: [[Field; DEPTH]; MAX_NOTES],
    note_root: pub Field,
    nf_root: pub Field,
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
            let cm = stake_cm(asset, amount[i], stake_pc(opk, rho[i], rcm[i]));
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
        } else {
            assert(vnf[i] == 0, "unused slot has a vote nullifier");
        }
        total += amount[i] as u128;
    }
    assert(weight != 0, "zero weight");
    assert(weight as u128 <= total, "weight exceeds the notes");
    bind(sighash);
}

// ---------------------------------------------------------------- tests

use privacy_core::{h2, nf_leaf, zero_hashes};

global NK: Field = 0x5eed;
global ASSET: Field = 0xde27;
global SIGHASH: Field = 0x5164;
global PROPOSAL: u64 = 7;

/// One slot's witness.
struct Slot {
    amount: u64,
    rho: Field,
    rcm: Field,
    pos: u32,
    path: [Field; DEPTH],
    low_value: Field,
    low_next_value: Field,
    low_next_index: u32,
    low_index: u32,
    low_path: [Field; DEPTH],
    vnf: Field,
}

struct V {
    nk: Field,
    slots: [Slot; MAX_NOTES],
    note_root: Field,
    nf_root: Field,
    asset: Field,
    weight: u64,
    proposal_id: u64,
}

fn unused() -> Slot {
    Slot {
        amount: 0,
        rho: 0x77,
        rcm: 0x78,
        pos: 0,
        path: [0; DEPTH],
        low_value: 0,
        low_next_value: 0,
        low_next_index: 0,
        low_index: 0,
        low_path: [0; DEPTH],
        vnf: 0,
    }
}

fn prove(v: V) {
    let mut amount = [0; MAX_NOTES];
    let mut rho = [0; MAX_NOTES];
    let mut rcm = [0; MAX_NOTES];
    let mut pos = [0; MAX_NOTES];
    let mut path = [[0; DEPTH]; MAX_NOTES];
    let mut low_value = [0; MAX_NOTES];
    let mut low_next_value = [0; MAX_NOTES];
    let mut low_next_index = [0; MAX_NOTES];
    let mut low_index = [0; MAX_NOTES];
    let mut low_path = [[0; DEPTH]; MAX_NOTES];
    let mut vnf = [0; MAX_NOTES];
    for i in 0..MAX_NOTES {
        let s = v.slots[i];
        amount[i] = s.amount;
        rho[i] = s.rho;
        rcm[i] = s.rcm;
        pos[i] = s.pos;
        path[i] = s.path;
        low_value[i] = s.low_value;
        low_next_value[i] = s.low_next_value;
        low_next_index[i] = s.low_next_index;
        low_index[i] = s.low_index;
        low_path[i] = s.low_path;
        vnf[i] = s.vnf;
    }
    main(
        v.nk,
        amount,
        rho,
        rcm,
        pos,
        path,
        low_value,
        low_next_value,
        low_next_index,
        low_index,
        low_path,
        v.note_root,
        v.nf_root,
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

/// H(TAG_STAKE, ASSET, amount, spc) for any u64 amount (stake_cm without its
/// bound), so a test can hold an amount above 2^63 - 1 that only the circuit
/// refuses.
fn note_cm(nk: Field, amount: u64, rho: Field, rcm: Field) -> Field {
    privacy_core::h4(
        privacy_core::TAG_STAKE,
        ASSET,
        amount as Field,
        stake_pc(owner_pk(nk), rho, rcm),
    )
}

/// Note tree: another owner's note of 9 at position 0, ours of 500, 300 and
/// 200 at positions 1, 2 and 3 (rho 0xa1, 0xb1, 0xc1; rcm 0xa2, 0xb2, 0xc2).
global AMOUNTS: [u64; 4] = [9, 500, 300, 200];
global RHOS: [Field; 4] = [0x01, 0xa1, 0xb1, 0xc1];
global RCMS: [Field; 4] = [0x02, 0xa2, 0xb2, 0xc2];

fn notes() -> (Field, [[Field; DEPTH]; 4]) {
    tree4([
        note_cm(0xbad, AMOUNTS[0], RHOS[0], RCMS[0]),
        note_cm(NK, AMOUNTS[1], RHOS[1], RCMS[1]),
        note_cm(NK, AMOUNTS[2], RHOS[2], RCMS[2]),
        note_cm(NK, AMOUNTS[3], RHOS[3], RCMS[3]),
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

/// Our note at position p, its low leaf the sentinel of the empty tree.
fn slot_empty_tree(p: u32) -> Slot {
    let (_, paths) = notes();
    let (_, low_path) = empty_nullifiers();
    Slot {
        amount: AMOUNTS[p],
        rho: RHOS[p],
        rcm: RCMS[p],
        pos: p,
        path: paths[p],
        low_value: 0,
        low_next_value: 0,
        low_next_index: 0,
        low_index: 0,
        low_path,
        vnf: vote_nf(NK, RHOS[p], p, PROPOSAL),
    }
}

/// Our three notes (positions 1, 2, 3) in slots 0..2, slot 3 unused, against
/// the empty nullifier tree: weight 1,000, their sum.
fn three() -> V {
    let (note_root, _) = notes();
    let (nf_root, _) = empty_nullifiers();
    V {
        nk: NK,
        slots: [slot_empty_tree(1), slot_empty_tree(2), slot_empty_tree(3), unused()],
        note_root,
        nf_root,
        asset: ASSET,
        weight: 1000,
        proposal_id: PROPOSAL,
    }
}

/// An honest vote of the note at position 1 alone (slot 0), its low leaf lo
/// (index 1) in the nullifier tree of lo and hi.
fn honest() -> V {
    let (note_root, paths) = notes();
    let (nf_root, _, nf_paths) = nullifiers(false);
    let nf = nf_at(1);
    V {
        nk: NK,
        slots: [
            Slot {
                amount: 500,
                rho: 0xa1,
                rcm: 0xa2,
                pos: 1,
                path: paths[1],
                low_value: nf - 1,
                low_next_value: nf + 1,
                low_next_index: 2,
                low_index: 1,
                low_path: nf_paths[1],
                vnf: vote_nf(NK, 0xa1, 1, PROPOSAL),
            },
            unused(),
            unused(),
            unused(),
        ],
        note_root,
        nf_root,
        asset: ASSET,
        weight: 500,
        proposal_id: PROPOSAL,
    }
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
fn test_three_notes_one_weight() {
    // 500 + 300 + 200: one weight for all three.
    prove(three());
}

#[test]
fn test_three_notes_rounded_weight() {
    // The wallet's rounding down to three digits (999 here) is below the sum.
    let mut v = three();
    v.weight = 999;
    prove(v);
}

#[test]
fn test_used_slot_anywhere() {
    // Unused slots may sit between used ones (the chain asks for a prefix,
    // the circuit does not care).
    let mut v = three();
    v.slots[3] = v.slots[1];
    v.slots[1] = unused();
    prove(v);
}

#[test]
fn test_four_notes() {
    // Four used slots: our three notes and, in a second tree, a fourth.
    let z = zero_hashes();
    let cms = [
        note_cm(NK, 100, 0xd1, 0xd2),
        note_cm(NK, AMOUNTS[1], RHOS[1], RCMS[1]),
        note_cm(NK, AMOUNTS[2], RHOS[2], RCMS[2]),
        note_cm(NK, AMOUNTS[3], RHOS[3], RCMS[3]),
    ];
    let (root, paths) = tree4(cms);
    let _ = z;
    let mut v = three();
    v.note_root = root;
    for i in 0..3 {
        v.slots[i].path = paths[i + 1];
    }
    let (_, low_path) = empty_nullifiers();
    v.slots[3] = Slot {
        amount: 100,
        rho: 0xd1,
        rcm: 0xd2,
        pos: 0,
        path: paths[0],
        low_value: 0,
        low_next_value: 0,
        low_next_index: 0,
        low_index: 0,
        low_path,
        vnf: vote_nf(NK, 0xd1, 0, PROPOSAL),
    };
    v.weight = 1100;
    prove(v);
}

#[test]
fn test_same_note_twice_same_vnf() {
    // A note in two slots proves, but both slots carry the same vote
    // nullifier: the chain refuses repeated vnfs, so it counts once.
    let mut v = three();
    v.slots[1] = v.slots[0];
    v.weight = 1000; // 500 + 500 + 200 >= 1000
    prove(v);
    assert(v.slots[0].vnf == v.slots[1].vnf);
}

#[test]
fn test_same_notes_two_proposals() {
    // The notes vote on proposal 7 and on proposal 8: different vote
    // nullifiers, none a spend nullifier.
    let a = three();
    let mut b = three();
    b.proposal_id = 8;
    for i in 0..3 {
        b.slots[i].vnf = vote_nf(NK, b.slots[i].rho, b.slots[i].pos, 8);
    }
    prove(a);
    prove(b);
    for i in 0..3 {
        assert(a.slots[i].vnf != b.slots[i].vnf);
        assert(a.slots[i].vnf != nf_at(a.slots[i].pos));
    }
}

#[test]
fn test_largest_nullifier() {
    // nf above every inserted nullifier: the low leaf is the last one
    // (next_value 0).
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
fn test_restaked_after_snapshot_still_votes() {
    // Spent after the snapshot: nf is in the CURRENT nullifier tree but not in
    // the snapshot's, which the vote proves against.
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
    let (note_root, paths) = tree4([note_cm(0xbad, 9, 0x01, 0x02), note_cm(NK, m, 0xa1, 0xa2), 0, 0]);
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
        note_cm(0xbad, 9, 0x01, 0x02),
        note_cm(NK, m, 0xa1, 0xa2),
        note_cm(NK, m, 0xb1, 0xb2),
        0,
    ]);
    let (nf_root, low_path) = empty_nullifiers();
    let mut v = three();
    v.note_root = note_root;
    v.nf_root = nf_root;
    v.slots[0].amount = m;
    v.slots[0].path = paths[1];
    v.slots[0].low_path = low_path;
    v.slots[1].amount = m;
    v.slots[1].path = paths[2];
    v.slots[2] = unused();
    v.weight = 0xfffffffffffffffe; // 2 x (2^63 - 1) = 2^64 - 2
    prove(v);
}

// ---- negative

#[test(should_fail_with = "weight exceeds the notes")]
fn test_weight_above_sum() {
    let mut v = three();
    v.weight = 1001;
    prove(v);
}

#[test(should_fail_with = "unused slot has a vote nullifier")]
fn test_unused_slot_with_vnf() {
    // A vote nullifier in an unused slot would burn another note's vnf.
    let mut v = three();
    v.slots[3].vnf = vote_nf(NK, 0xd1, 0, PROPOSAL);
    prove(v);
}

#[test(should_fail_with = "note not in snapshot stake tree")]
fn test_slot_of_another_owner() {
    // Slot 2 holds the other owner's note (position 0), not provable under NK.
    let (_, paths) = notes();
    let mut v = three();
    v.slots[2].amount = 9;
    v.slots[2].rho = 0x01;
    v.slots[2].rcm = 0x02;
    v.slots[2].pos = 0;
    v.slots[2].path = paths[0];
    v.slots[2].vnf = vote_nf(NK, 0x01, 0, PROPOSAL);
    v.weight = 800;
    prove(v);
}

#[test(should_fail_with = "note not in snapshot stake tree")]
fn test_inflated_amount_in_one_slot() {
    let mut v = three();
    v.slots[1].amount = 301;
    v.weight = 1001;
    prove(v);
}

#[test(should_fail_with = "bad vote nullifier")]
fn test_wrong_vnf_in_one_slot() {
    let mut v = three();
    v.slots[2].vnf = vote_nf(NK, RHOS[3], 3, PROPOSAL + 1);
    prove(v);
}

#[test(should_fail_with = "nullifier is in the nullifier tree")]
fn test_spent_before_snapshot() {
    // nf was inserted before the snapshot: its predecessor's next is nf.
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
    // ... nor by its own leaf as the low leaf.
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
    // ... nor by the low leaf as it was before nf's insertion.
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
    // An empty slot (leaf 0) is no leaf: no tagged hash equals 0.
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
    // hi (index 2) is a real leaf, but above nf.
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
    // A 2^63 stake note at position 1 (none can exist on chain).
    let a: u64 = 0x8000000000000000;
    let (note_root, paths) = tree4([note_cm(0xbad, 9, 0x01, 0x02), note_cm(NK, a, 0xa1, 0xa2), 0, 0]);
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
    // Every slot unused: nothing to vote with.
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
    // The other note (position 0) belongs to 0xbad: not provable under NK.
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
    // A note appended after the snapshot is not under the snapshot root.
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
