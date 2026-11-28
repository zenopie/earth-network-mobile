//! claim — one live registration's nullifiers in up to SLOTS scopes at once:
//! an ANML claim for a run of days, with one proof.
//!
//! Proves: leaf = H(TAG_LEAF, H(TAG_ID, id_secret), dsc_key, country,
//! activated_at, predecessor_at) is in the identity tree at `root`; for each
//! slot i, nullifiers[i] = H(TAG_SN, id_secret, scopes[i]), or 0 when
//! scopes[i] is 0 (an unused slot); dsc_key != 0; activated_at <=
//! max_activation; binds `signal`.
//!
//! dsc_key != 0 is membership's dsc_key != excluded_dsc with the 0 a claim
//! names there: a leaf with no Document Signer claims by neither msg.
//!
//! Which scopes, and which bound, are the chain's choice: it fills the slots
//! with the claim scopes of consecutive days and bounds activation by the
//! first of them, which bounds every later day too.
//!
//! Public input order (the chain must supply them in this order):
//!   root, scopes[0..SLOTS], nullifiers[0..SLOTS], signal, max_activation

use privacy_core::{bind, DEPTH, idc, identity_leaf, merkle_root, scope_nullifier};

pub global SLOTS: u32 = 7;

fn main(
    id_secret: Field,
    dsc_key: Field,
    country: Field,
    activated_at: u64,
    predecessor_at: u64,
    leaf_index: u32,
    siblings: [Field; DEPTH],
    root: pub Field,
    scopes: pub [Field; SLOTS],
    nullifiers: pub [Field; SLOTS],
    signal: pub Field,
    max_activation: pub u64,
) {
    let leaf = identity_leaf(idc(id_secret), dsc_key, country, activated_at, predecessor_at);
    assert(merkle_root(leaf, leaf_index, siblings) == root, "not in identity tree");
    assert(dsc_key != 0, "no DSC");
    for i in 0..SLOTS {
        let want = if scopes[i] == 0 { 0 } else { scope_nullifier(id_secret, scopes[i]) };
        assert(nullifiers[i] == want, "bad nullifier");
    }
    assert(activated_at <= max_activation, "activated too late");
    bind(signal);
}

global DE: Field = 0x4445;

struct W {
    id_secret: Field,
    dsc_key: Field,
    country: Field,
    activated_at: u64,
    predecessor_at: u64,
    index: u32,
    sib: [Field; DEPTH],
    root: Field,
}

/// Scopes 100.. for the first n slots, the rest unused.
fn scopes(n: u32) -> [Field; SLOTS] {
    let mut s = [0; SLOTS];
    for i in 0..SLOTS {
        if i < n {
            s[i] = 100 + i as Field;
        }
    }
    s
}

fn nfs(w: W, s: [Field; SLOTS]) -> [Field; SLOTS] {
    let mut n = [0; SLOTS];
    for i in 0..SLOTS {
        if s[i] != 0 {
            n[i] = scope_nullifier(w.id_secret, s[i]);
        }
    }
    n
}

fn run(w: W, root: Field, s: [Field; SLOTS], n: [Field; SLOTS], max_activation: u64) {
    main(
        w.id_secret,
        w.dsc_key,
        w.country,
        w.activated_at,
        w.predecessor_at,
        w.index,
        w.sib,
        root,
        s,
        n,
        9,
        max_activation,
    );
}

#[test]
fn test_one_day() {
    let w = fixture(5, 1000);
    let s = scopes(1);
    run(w, w.root, s, nfs(w, s), 1000);
}

#[test]
fn test_full_week() {
    let w = fixture(5, 1000);
    let s = scopes(SLOTS);
    run(w, w.root, s, nfs(w, s), 1000);
}

#[test]
fn test_no_slots() {
    // Nothing to claim proves nothing but the leaf; the chain refuses it.
    let w = fixture(5, 1000);
    run(w, w.root, [0; SLOTS], [0; SLOTS], 1000);
}

#[test(should_fail_with = "not in identity tree")]
fn test_wrong_root() {
    let w = fixture(5, 1000);
    let s = scopes(3);
    run(w, 123, s, nfs(w, s), 1000);
}

#[test(should_fail_with = "bad nullifier")]
fn test_nullifier_other_scope() {
    let w = fixture(5, 1000);
    let s = scopes(3);
    let mut n = nfs(w, s);
    n[1] = scope_nullifier(w.id_secret, 999);
    run(w, w.root, s, n, 1000);
}

#[test(should_fail_with = "bad nullifier")]
fn test_nullifiers_swapped() {
    let w = fixture(5, 1000);
    let s = scopes(3);
    let mut n = nfs(w, s);
    let t = n[0];
    n[0] = n[1];
    n[1] = t;
    run(w, w.root, s, n, 1000);
}

#[test(should_fail_with = "bad nullifier")]
fn test_unused_slot_nonzero() {
    // An unused slot cannot carry a nullifier.
    let w = fixture(5, 1000);
    let s = scopes(2);
    let mut n = nfs(w, s);
    n[2] = scope_nullifier(w.id_secret, 0);
    run(w, w.root, s, n, 1000);
}

#[test(should_fail_with = "bad nullifier")]
fn test_other_secret() {
    let w = fixture(5, 1000);
    let s = scopes(2);
    let mut n = nfs(w, s);
    n[0] = scope_nullifier(0x9999, s[0]);
    run(w, w.root, s, n, 1000);
}

#[test(should_fail_with = "activated too late")]
fn test_activation_too_late() {
    let w = fixture(5, 1000);
    let s = scopes(7);
    run(w, w.root, s, nfs(w, s), 999);
}

#[test(should_fail_with = "not in identity tree")]
fn test_lied_activation() {
    // Claiming an earlier activated_at than the leaf commits to changes the leaf.
    let mut w = fixture(5, 1000);
    w.activated_at = 999;
    let s = scopes(7);
    run(w, w.root, s, nfs(w, s), 999);
}

#[test(should_fail_with = "no DSC")]
fn test_no_dsc() {
    let w = fixture_dsc(5, 1000, 0);
    let s = scopes(1);
    run(w, w.root, s, nfs(w, s), 1000);
}

/// A tree whose only non-empty leaf is ours at `index`.
fn fixture(index: u32, activated_at: u64) -> W {
    fixture_dsc(index, activated_at, 0xd5c)
}

fn fixture_dsc(index: u32, activated_at: u64, dsc_key: Field) -> W {
    let id_secret = 0x1234;
    let z = privacy_core::zero_hashes();
    let mut sib = [0; DEPTH];
    for i in 0..DEPTH {
        sib[i] = z[i];
    }
    let leaf = identity_leaf(idc(id_secret), dsc_key, DE, activated_at, 0);
    let root = merkle_root(leaf, index, sib);
    W { id_secret, dsc_key, country: DE, activated_at, predecessor_at: 0, index, sib, root }
}
