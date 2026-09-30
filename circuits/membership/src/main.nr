//! membership — anonymous proof of a live registration.
//!
//! Proves: leaf = H(TAG_LEAF, H(TAG_ID, id_secret), dsc_key, activated_at) is in
//! the identity tree at `root`; nullifier = H(TAG_SN, id_secret, scope);
//! dsc_key != excluded_dsc; activated_at <= max_activation; binds `signal`.
//!
//! Public input order (the chain must supply them in this order):
//!   root, scope, nullifier, signal, excluded_dsc, max_activation

use privacy_core::{bind, DEPTH, idc, identity_leaf, merkle_root, scope_nullifier};

fn main(
    id_secret: Field,
    dsc_key: Field,
    activated_at: u64,
    leaf_index: u32,
    siblings: [Field; DEPTH],
    root: pub Field,
    scope: pub Field,
    nullifier: pub Field,
    signal: pub Field,
    excluded_dsc: pub Field,
    max_activation: pub u64,
) {
    let leaf = identity_leaf(idc(id_secret), dsc_key, activated_at);
    assert(merkle_root(leaf, leaf_index, siblings) == root, "not in identity tree");
    assert(scope_nullifier(id_secret, scope) == nullifier, "bad nullifier");
    assert(dsc_key != excluded_dsc, "excluded DSC");
    assert(activated_at <= max_activation, "activated too late");
    bind(signal);
}

#[test]
fn test_ok() {
    let (w, root) = fixture(5, 1000);
    main(
        w.0,
        w.1,
        w.2,
        w.3,
        w.4,
        root,
        42,
        privacy_core::scope_nullifier(w.0, 42),
        9,
        0,
        1000,
    );
}

#[test(should_fail_with = "not in identity tree")]
fn test_wrong_root() {
    let (w, _) = fixture(5, 1000);
    main(
        w.0,
        w.1,
        w.2,
        w.3,
        w.4,
        123,
        42,
        privacy_core::scope_nullifier(w.0, 42),
        9,
        0,
        1000,
    );
}

#[test(should_fail_with = "not in identity tree")]
fn test_wrong_index() {
    let (w, root) = fixture(5, 1000);
    main(
        w.0,
        w.1,
        w.2,
        4,
        w.4,
        root,
        42,
        privacy_core::scope_nullifier(w.0, 42),
        9,
        0,
        1000,
    );
}

#[test(should_fail_with = "bad nullifier")]
fn test_nullifier_other_scope() {
    let (w, root) = fixture(5, 1000);
    main(
        w.0,
        w.1,
        w.2,
        w.3,
        w.4,
        root,
        42,
        privacy_core::scope_nullifier(w.0, 43),
        9,
        0,
        1000,
    );
}

#[test(should_fail_with = "excluded DSC")]
fn test_excluded_dsc() {
    let (w, root) = fixture(5, 1000);
    main(
        w.0,
        w.1,
        w.2,
        w.3,
        w.4,
        root,
        42,
        privacy_core::scope_nullifier(w.0, 42),
        9,
        w.1,
        1000,
    );
}

#[test(should_fail_with = "activated too late")]
fn test_activation_too_late() {
    let (w, root) = fixture(5, 1000);
    main(
        w.0,
        w.1,
        w.2,
        w.3,
        w.4,
        root,
        42,
        privacy_core::scope_nullifier(w.0, 42),
        9,
        0,
        999,
    );
}

#[test(should_fail_with = "not in identity tree")]
fn test_lied_activation() {
    // Claiming an earlier activated_at than the leaf commits to changes the leaf.
    let (w, root) = fixture(5, 1000);
    main(
        w.0,
        w.1,
        999,
        w.3,
        w.4,
        root,
        42,
        privacy_core::scope_nullifier(w.0, 42),
        9,
        0,
        999,
    );
}

/// A tree whose only non-empty leaf is ours at `index`.
fn fixture(index: u32, activated_at: u64) -> ((Field, Field, u64, u32, [Field; DEPTH]), Field) {
    let id_secret = 0x1234;
    let dsc_key = 0xd5c;
    let z = privacy_core::zero_hashes();
    let mut sib = [0; DEPTH];
    for i in 0..DEPTH {
        sib[i] = z[i];
    }
    let leaf = identity_leaf(idc(id_secret), dsc_key, activated_at);
    let root = merkle_root(leaf, index, sib);
    ((id_secret, dsc_key, activated_at, index, sib), root)
}
