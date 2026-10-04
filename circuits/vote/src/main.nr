//! vote — a stake note's private vote on one x/gov proposal, without spending
//! it (earth-network-chain x/shieldedstaking, ORCHARD_DESIGN.md section 15).
//!
//! When a proposal enters voting the chain snapshots two roots: the stake note
//! tree's (`note_root`) and the stake nullifier indexed tree's (`nf_root`). A
//! derth/<valoper> stake note votes on that proposal by proving
//!
//!   1. it is in the stake note tree at the snapshot (path to note_root);
//!   2. it was unspent at the snapshot: its spend nullifier
//!        nf = H(TAG_SNF, nk, rho, position)
//!      is NOT in the nullifier tree at the snapshot (a low leaf under nf_root
//!      with low.value < nf < low.next_value, or low.next_value = 0);
//!   3. vnf = H(TAG_VNF, nk, rho, position, proposal_id), the vote nullifier
//!      the chain refuses a second time for this proposal;
//!   4. 0 < weight <= amount (the vote's public weight; a wallet may vote
//!      less than the note holds, e.g. a rounded weight). amount, and so
//!      weight, is at most 2^63 - 1 (privacy_core NOTE_VALUE_BITS, inside
//!      stake_cm), the most a stake note holds.
//!
//! Nothing is spent and nothing is created: the note stays where it is and can
//! vote on every other open proposal (each with its own vnf) and be spent as
//! usual. nf is never published: votes are unlinkable to one another and to
//! the note's spend, except through their public weight and validator.
//! Binding: the msg's sighash (options, proposal, validator, fee bundle, ...).
//!
//! Public input order:
//!   note_root, nf_root, asset, weight, proposal_id, vnf, sighash

use privacy_core::{
    assert_not_in_indexed, bind, DEPTH, merkle_root, owner_pk, stake_cm, stake_nf, stake_pc, vote_nf,
};

fn main(
    nk: Field,
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
    note_root: pub Field,
    nf_root: pub Field,
    asset: pub Field,
    weight: pub u64,
    proposal_id: pub u64,
    vnf: pub Field,
    sighash: pub Field,
) {
    let cm = stake_cm(asset, amount, stake_pc(owner_pk(nk), rho, rcm));
    assert(merkle_root(cm, pos, path) == note_root, "note not in snapshot stake tree");
    let nf = stake_nf(nk, rho, pos);
    assert_not_in_indexed(nf, low_value, low_next_value, low_next_index, low_index, low_path, nf_root);
    assert(weight != 0, "zero weight");
    assert(weight <= amount, "weight exceeds the note");
    assert(vnf == vote_nf(nk, rho, pos, proposal_id), "bad vote nullifier");
    bind(sighash);
}

// ---------------------------------------------------------------- tests

use privacy_core::{h2, nf_leaf, zero_hashes};

global NK: Field = 0x5eed;
global ASSET: Field = 0xde27;
global SIGHASH: Field = 0x5164;
global PROPOSAL: u64 = 7;

struct V {
    nk: Field,
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
    note_root: Field,
    nf_root: Field,
    asset: Field,
    weight: u64,
    proposal_id: u64,
    vnf: Field,
}

fn prove(v: V) {
    main(
        v.nk,
        v.amount,
        v.rho,
        v.rcm,
        v.pos,
        v.path,
        v.low_value,
        v.low_next_value,
        v.low_next_index,
        v.low_index,
        v.low_path,
        v.note_root,
        v.nf_root,
        v.asset,
        v.weight,
        v.proposal_id,
        v.vnf,
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
    privacy_core::h4(privacy_core::TAG_STAKE, ASSET, amount as Field, stake_pc(owner_pk(nk), rho, rcm))
}

/// Note tree: our note of 500 at position 1 (another note at 0).
fn notes() -> (Field, [Field; DEPTH]) {
    let (root, paths) = tree4([note_cm(0xbad, 9, 0x01, 0x02), note_cm(NK, 500, 0xa1, 0xa2), 0, 0]);
    (root, paths[1])
}

/// The voter's spend nullifier (note at position 1).
fn our_nf() -> Field {
    stake_nf(NK, 0xa1, 1)
}

/// The nullifier tree after inserting lo = nf - 1 then hi = nf + 1 (and, with
/// spent, nf itself): leaves in insertion order, sorted list 0 -> lo -> (nf ->)
/// hi.
fn nullifiers(spent: bool) -> (Field, [Field; 4], [[Field; DEPTH]; 4]) {
    let nf = our_nf();
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

/// An honest vote of the note at position 1, its low leaf lo (index 1).
fn honest() -> V {
    let (note_root, path) = notes();
    let (nf_root, _, nf_paths) = nullifiers(false);
    let nf = our_nf();
    V {
        nk: NK,
        amount: 500,
        rho: 0xa1,
        rcm: 0xa2,
        pos: 1,
        path,
        low_value: nf - 1,
        low_next_value: nf + 1,
        low_next_index: 2,
        low_index: 1,
        low_path: nf_paths[1],
        note_root,
        nf_root,
        asset: ASSET,
        weight: 500,
        proposal_id: PROPOSAL,
        vnf: vote_nf(NK, 0xa1, 1, PROPOSAL),
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
fn test_same_note_two_proposals() {
    // The note votes on proposal 7 and on proposal 8: two different vote
    // nullifiers, neither its spend nullifier.
    let a = honest();
    let mut b = honest();
    b.proposal_id = 8;
    b.vnf = vote_nf(NK, 0xa1, 1, 8);
    prove(a);
    prove(b);
    assert(a.vnf != b.vnf);
    assert(a.vnf != our_nf());
    assert(b.vnf != our_nf());
}

#[test]
fn test_largest_nullifier() {
    // nf above every inserted nullifier: the low leaf is the last one
    // (next_value 0).
    let nf = our_nf();
    let lo = nf - 1;
    let (root, paths) = tree4([nf_leaf(0, lo, 1), nf_leaf(lo, 0, 0), 0, 0]);
    let mut v = honest();
    v.low_next_value = 0;
    v.low_next_index = 0;
    v.low_path = paths[1];
    v.nf_root = root;
    prove(v);
}

#[test]
fn test_empty_nullifier_tree() {
    // Only the sentinel: low leaf (0, 0, 0) at index 0.
    let (root, paths) = tree4([nf_leaf(0, 0, 0), 0, 0, 0]);
    let mut v = honest();
    v.low_value = 0;
    v.low_next_value = 0;
    v.low_next_index = 0;
    v.low_index = 0;
    v.low_path = paths[0];
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

// ---- negative

#[test(should_fail_with = "nullifier is in the nullifier tree")]
fn test_spent_before_snapshot() {
    // nf was inserted before the snapshot: its predecessor's next is nf.
    let (root, _, paths) = nullifiers(true);
    let mut v = honest();
    v.nf_root = root;
    v.low_next_value = our_nf();
    v.low_next_index = 3;
    v.low_path = paths[1];
    prove(v);
}

#[test(should_fail_with = "low leaf not below the nullifier")]
fn test_spent_before_snapshot_own_leaf() {
    // ... nor by its own leaf as the low leaf.
    let (root, _, paths) = nullifiers(true);
    let nf = our_nf();
    let mut v = honest();
    v.nf_root = root;
    v.low_value = nf;
    v.low_next_value = nf + 1;
    v.low_next_index = 2;
    v.low_index = 3;
    v.low_path = paths[3];
    prove(v);
}

#[test(should_fail_with = "low leaf not in nullifier tree")]
fn test_spent_before_snapshot_stale_low_leaf() {
    // ... nor by the low leaf as it was before nf's insertion.
    let (root, _, paths) = nullifiers(true);
    let mut v = honest();
    v.nf_root = root;
    v.low_path = paths[1];
    prove(v);
}

#[test(should_fail_with = "low leaf not in nullifier tree")]
fn test_forged_low_leaf() {
    let mut v = honest();
    v.low_next_value = our_nf() + 5;
    prove(v);
}

#[test(should_fail_with = "low leaf not in nullifier tree")]
fn test_empty_slot_as_low_leaf() {
    // An empty slot (leaf 0) is no leaf: no tagged hash equals 0.
    let (_, _, paths) = nullifiers(false);
    let mut v = honest();
    v.low_value = 0;
    v.low_next_value = 0;
    v.low_next_index = 0;
    v.low_index = 3;
    v.low_path = paths[3];
    prove(v);
}

#[test(should_fail_with = "low leaf not below the nullifier")]
fn test_low_leaf_above() {
    // hi (index 2) is a real leaf, but above nf.
    let (_, _, paths) = nullifiers(false);
    let nf = our_nf();
    let mut v = honest();
    v.low_value = nf + 1;
    v.low_next_value = 0;
    v.low_next_index = 0;
    v.low_index = 2;
    v.low_path = paths[2];
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
    v.vnf = our_nf();
    prove(v);
}

#[test(should_fail_with = "bad vote nullifier")]
fn test_vnf_of_another_position() {
    let mut v = honest();
    v.vnf = vote_nf(NK, 0xa1, 0, PROPOSAL);
    prove(v);
}

#[test(should_fail_with = "weight exceeds the note")]
fn test_weight_above_amount() {
    let mut v = honest();
    v.weight = 501;
    prove(v);
}

#[test]
fn test_note_max() {
    let m: u64 = 0x7fffffffffffffff;
    let (note_root, paths) = tree4([note_cm(0xbad, 9, 0x01, 0x02), note_cm(NK, m, 0xa1, 0xa2), 0, 0]);
    let mut v = honest();
    v.note_root = note_root;
    v.path = paths[1];
    v.amount = m;
    v.weight = m;
    prove(v);
}

#[test(should_fail_with = "assert_max_bit_size")]
fn test_amount_above_note_max() {
    // A 2^63 stake note at position 1 (none can exist on chain).
    let a: u64 = 0x8000000000000000;
    let (note_root, paths) = tree4([note_cm(0xbad, 9, 0x01, 0x02), note_cm(NK, a, 0xa1, 0xa2), 0, 0]);
    let mut v = honest();
    v.note_root = note_root;
    v.path = paths[1];
    v.amount = a;
    v.weight = a;
    prove(v);
}

#[test(should_fail_with = "zero weight")]
fn test_zero_weight() {
    let mut v = honest();
    v.weight = 0;
    prove(v);
}

#[test(should_fail_with = "note not in snapshot stake tree")]
fn test_inflated_amount() {
    let mut v = honest();
    v.amount = 501;
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
    let (note_root, paths) = tree4([note_cm(0xbad, 9, 0x01, 0x02), note_cm(NK, 500, 0xa1, 0xa2), 0, 0]);
    let mut v = honest();
    v.note_root = note_root;
    v.amount = 9;
    v.weight = 9;
    v.rho = 0x01;
    v.rcm = 0x02;
    v.pos = 0;
    v.path = paths[0];
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
    assert(vote_nf(NK, 0xa1, 1, 7) == 0x1ada84dad3e6afde3f370e97edf4df2ee4eeb6b1400d5c5f41882552f578ba2f);
    // indexed.EmptyRoot: the sentinel alone.
    let (empty, _) = tree4([nf_leaf(0, 0, 0), 0, 0, 0]);
    assert(empty == 0x18f5a2d2d3273f584793e90ac9bf77abf0ff2a05a101cd5943eaf7bbd0bd5b10);
    // Go's Insert(nf - 1), Insert(nf + 1) [, Insert(nf)] build these trees.
    let (snap, _, _) = nullifiers(false);
    let (now, _, _) = nullifiers(true);
    assert(snap == 0x154276da4092031ee0cc36041772a8ff6f563b14ae80df0d0f46ffe5b474cb6d);
    assert(now == 0x6a4a58053d288e995f3f876a1702085169ca86c6d739d072b94fc379a327048);
}
