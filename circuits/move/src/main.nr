//! move — hands a handle or a caretaker split from an identity to the
//! identity that replaced it, under the same passport.
//!
//! The chain appends succession = H(TAG_SUCC, idc_old, idc_new) to the
//! identity tree when a passport registers to idc_new and its last
//! registration was to idc_old (a switch, or a re-entry). Only the chain
//! writes the tree, and it writes a succession only from one passport's own
//! registration records, so a succession leaf is the chain's statement that
//! idc_new is the passport's next identity after idc_old.
//!
//! Proves, revealing neither identity nor any passport data:
//!   - the prover knows old_secret and new_secret (idc = H(TAG_ID, secret));
//!   - H(TAG_SUCC, idc(old_secret), idc(new_secret)) is in the identity tree
//!     at `root`: the new identity directly succeeded the old one;
//!   - the new identity's leaf H(TAG_LEAF, idc(new_secret), dsc_key, country,
//!     activated_at, predecessor_at) is in the tree at the same `root`: it is
//!     live (a switch or expiry zeroes it, and roots age out);
//!   - old_nullifier = H(TAG_SN, old_secret, scope) and new_nullifier =
//!     H(TAG_SN, new_secret, scope): what moves, from whom, to whom, in the
//!     scope the chain names (handle or caretaker);
//!   - binds `signal` (the msg's sighash: chain id, tx fields, fee bundle,
//!     handle), so the proof moves nothing but the msg it was made for.
//!
//! Public input order (the chain must supply them in this order):
//!   root, scope, old_nullifier, new_nullifier, signal

use privacy_core::{bind, DEPTH, idc, identity_leaf, merkle_root, scope_nullifier, succession_leaf};

fn main(
    old_secret: Field,
    new_secret: Field,
    succession_index: u32,
    succession_siblings: [Field; DEPTH],
    dsc_key: Field,
    country: Field,
    activated_at: u64,
    predecessor_at: u64,
    leaf_index: u32,
    siblings: [Field; DEPTH],
    root: pub Field,
    scope: pub Field,
    old_nullifier: pub Field,
    new_nullifier: pub Field,
    signal: pub Field,
) {
    let old_idc = idc(old_secret);
    let new_idc = idc(new_secret);
    assert(
        merkle_root(succession_leaf(old_idc, new_idc), succession_index, succession_siblings) == root,
        "not a succession",
    );
    let leaf = identity_leaf(new_idc, dsc_key, country, activated_at, predecessor_at);
    assert(merkle_root(leaf, leaf_index, siblings) == root, "successor not live");
    assert(scope_nullifier(old_secret, scope) == old_nullifier, "bad old nullifier");
    assert(scope_nullifier(new_secret, scope) == new_nullifier, "bad new nullifier");
    bind(signal);
}

// ---- tests --------------------------------------------------------------------

global A: Field = 0xa11ce; // passport P's first identity secret
global B: Field = 0xb0b; // P's second (a switch from A)
global C: Field = 0xc0ffee; // another passport Q's identity
global SCOPE: Field = 0x5c09e0;
global DSC: Field = 0xd5c;
global DE: Field = 0x4445;

/// A depth-DEPTH tree whose first four leaves are `leaves`: its root and the
/// path of each.
fn tree4(leaves: [Field; 4]) -> (Field, [[Field; DEPTH]; 4]) {
    let z = privacy_core::zero_hashes();
    let n01 = privacy_core::h2(leaves[0], leaves[1]);
    let n23 = privacy_core::h2(leaves[2], leaves[3]);
    let mut paths = [[0; DEPTH]; 4];
    for j in 0..4 {
        paths[j][0] = leaves[j ^ 1];
        paths[j][1] = if j < 2 { n23 } else { n01 };
        for k in 2..DEPTH {
            paths[j][k] = z[k];
        }
    }
    (merkle_root(leaves[0], 0, paths[0]), paths)
}

fn leaf_of(secret: Field) -> Field {
    identity_leaf(idc(secret), DSC, DE, 1000, 900)
}

/// P registered to A (leaf 0), then switched to B: A's leaf zeroed (slot 0
/// is 0), B's leaf at 1, succession (A, B) at 2; Q's identity C at 3.
fn switched() -> (Field, [[Field; DEPTH]; 4]) {
    tree4([0, leaf_of(B), succession_leaf(idc(A), idc(B)), leaf_of(C)])
}

fn run(old: Field, new: Field, si: u32, li: u32, t: (Field, [[Field; DEPTH]; 4]), scope: Field, old_nf: Field, new_nf: Field) {
    let (root, paths) = t;
    main(old, new, si, paths[si], DSC, DE, 1000, 900, li, paths[li], root, scope, old_nf, new_nf, 7);
}

fn nf(secret: Field) -> Field {
    scope_nullifier(secret, SCOPE)
}

#[test]
fn test_move_to_successor() {
    run(A, B, 2, 1, switched(), SCOPE, nf(A), nf(B));
}

#[test(should_fail_with = "not a succession")]
fn test_move_to_another_passport() {
    // C is live but no succession links A to C.
    run(A, C, 2, 3, switched(), SCOPE, nf(A), nf(C));
}

#[test(should_fail_with = "not a succession")]
fn test_move_backwards() {
    // The succession is (A, B), not (B, A).
    let t = tree4([leaf_of(A), leaf_of(B), succession_leaf(idc(A), idc(B)), 0]);
    run(B, A, 2, 0, t, SCOPE, nf(B), nf(A));
}

#[test(should_fail_with = "not a succession")]
fn test_skipping_a_successor() {
    // A -> B -> D: (A, B) and (B, D) exist, (A, D) does not.
    let d = 0xd00d;
    let t = tree4([succession_leaf(idc(A), idc(B)), 0, succession_leaf(idc(B), idc(d)), leaf_of(d)]);
    run(A, d, 2, 3, t, SCOPE, nf(A), nf(d));
}

#[test]
fn test_successor_chain_step() {
    let d = 0xd00d;
    let t = tree4([succession_leaf(idc(A), idc(B)), 0, succession_leaf(idc(B), idc(d)), leaf_of(d)]);
    run(B, d, 2, 3, t, SCOPE, nf(B), nf(d));
}

#[test(should_fail_with = "successor not live")]
fn test_successor_zeroed() {
    // B switched away again: its leaf is zeroed.
    let t = tree4([0, 0, succession_leaf(idc(A), idc(B)), leaf_of(C)]);
    run(A, B, 2, 1, t, SCOPE, nf(A), nf(B));
}

#[test(should_fail_with = "successor not live")]
fn test_successor_lied_leaf() {
    // Claiming the succession leaf as B's identity leaf.
    run(A, B, 2, 2, switched(), SCOPE, nf(A), nf(B));
}

#[test(should_fail_with = "not a succession")]
fn test_wrong_old_secret() {
    run(A + 1, B, 2, 1, switched(), SCOPE, scope_nullifier(A + 1, SCOPE), nf(B));
}

#[test(should_fail_with = "not a succession")]
fn test_wrong_new_secret() {
    run(A, B + 1, 2, 1, switched(), SCOPE, nf(A), scope_nullifier(B + 1, SCOPE));
}

#[test(should_fail_with = "bad old nullifier")]
fn test_old_nullifier_of_another_scope() {
    // A replay into another scope (the split, with the handle's nullifiers).
    run(A, B, 2, 1, switched(), SCOPE + 1, nf(A), scope_nullifier(B, SCOPE + 1));
}

#[test(should_fail_with = "bad new nullifier")]
fn test_new_nullifier_not_the_successors() {
    // The recipient must be the successor's own nullifier, not one of the
    // prover's choosing (another person's).
    run(A, B, 2, 1, switched(), SCOPE, nf(A), nf(C));
}

#[test(should_fail_with = "not a succession")]
fn test_wrong_root() {
    let (_, paths) = switched();
    main(A, B, 2, paths[2], DSC, DE, 1000, 900, 1, paths[1], 123, SCOPE, nf(A), nf(B), 7);
}
