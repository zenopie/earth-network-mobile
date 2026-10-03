//! membership — anonymous proof of a live registration.
//!
//! Proves: leaf = H(TAG_LEAF, H(TAG_ID, id_secret), dsc_key, country,
//! activated_at, predecessor_at) is in the identity tree at `root`;
//! nullifier = H(TAG_SN, id_secret, scope); dsc_key != excluded_dsc;
//! country != excluded_country unless excluded_country is 0 (none);
//! activated_at <= max_activation; predecessor_at <= max_predecessor; binds
//! `signal`.
//!
//! Public input order (the chain must supply them in this order):
//!   root, scope, nullifier, signal, excluded_dsc, excluded_country,
//!   max_activation, max_predecessor

use privacy_core::{bind, DEPTH, idc, identity_leaf, merkle_root, scope_nullifier};

fn main(
    id_secret: Field,
    dsc_key: Field,
    country: Field,
    activated_at: u64,
    predecessor_at: u64,
    leaf_index: u32,
    siblings: [Field; DEPTH],
    root: pub Field,
    scope: pub Field,
    nullifier: pub Field,
    signal: pub Field,
    excluded_dsc: pub Field,
    excluded_country: pub Field,
    max_activation: pub u64,
    max_predecessor: pub u64,
) {
    let leaf = identity_leaf(idc(id_secret), dsc_key, country, activated_at, predecessor_at);
    assert(merkle_root(leaf, leaf_index, siblings) == root, "not in identity tree");
    assert(scope_nullifier(id_secret, scope) == nullifier, "bad nullifier");
    assert(dsc_key != excluded_dsc, "excluded DSC");
    // excluded_country 0 excludes nobody, so a leaf of unknown country (0) is
    // never excluded by country; only by its DSC.
    if excluded_country != 0 {
        assert(country != excluded_country, "excluded country");
    }
    assert(activated_at <= max_activation, "activated too late");
    assert(predecessor_at <= max_predecessor, "predecessor too recent");
    bind(signal);
}

global DE: Field = 0x4445;
global FR: Field = 0x4652;

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

/// Proves w with the given statement (scope 42, signal 9).
fn run(w: W, root: Field, nullifier: Field, excluded_dsc: Field, excluded_country: Field, max_activation: u64) {
    run_pred(w, root, nullifier, excluded_dsc, excluded_country, max_activation, 0xffffffffffffffff);
}

fn run_pred(
    w: W,
    root: Field,
    nullifier: Field,
    excluded_dsc: Field,
    excluded_country: Field,
    max_activation: u64,
    max_predecessor: u64,
) {
    main(
        w.id_secret,
        w.dsc_key,
        w.country,
        w.activated_at,
        w.predecessor_at,
        w.index,
        w.sib,
        root,
        42,
        nullifier,
        9,
        excluded_dsc,
        excluded_country,
        max_activation,
        max_predecessor,
    );
}

fn nf(w: W) -> Field {
    privacy_core::scope_nullifier(w.id_secret, 42)
}

#[test]
fn test_ok() {
    let w = fixture(5, DE, 1000);
    run(w, w.root, nf(w), 0, 0, 1000);
}

#[test]
fn test_other_country_excluded_ok() {
    let w = fixture(5, DE, 1000);
    run(w, w.root, nf(w), 0, FR, 1000);
}

#[test]
fn test_unknown_country_not_excluded_by_country() {
    let w = fixture(5, 0, 1000);
    run(w, w.root, nf(w), 0, FR, 1000);
}

#[test(should_fail_with = "not in identity tree")]
fn test_wrong_root() {
    let w = fixture(5, DE, 1000);
    run(w, 123, nf(w), 0, 0, 1000);
}

#[test(should_fail_with = "not in identity tree")]
fn test_wrong_index() {
    let mut w = fixture(5, DE, 1000);
    w.index = 4;
    run(w, w.root, nf(w), 0, 0, 1000);
}

#[test(should_fail_with = "bad nullifier")]
fn test_nullifier_other_scope() {
    let w = fixture(5, DE, 1000);
    run(w, w.root, privacy_core::scope_nullifier(w.id_secret, 43), 0, 0, 1000);
}

#[test(should_fail_with = "excluded DSC")]
fn test_excluded_dsc() {
    let w = fixture(5, DE, 1000);
    run(w, w.root, nf(w), w.dsc_key, 0, 1000);
}

#[test(should_fail_with = "excluded country")]
fn test_excluded_country() {
    let w = fixture(5, DE, 1000);
    run(w, w.root, nf(w), 0, DE, 1000);
}

#[test(should_fail_with = "not in identity tree")]
fn test_lied_country() {
    // Claiming another country than the leaf commits to changes the leaf.
    let mut w = fixture(5, DE, 1000);
    w.country = FR;
    run(w, w.root, nf(w), 0, DE, 1000);
}

#[test(should_fail_with = "activated too late")]
fn test_activation_too_late() {
    let w = fixture(5, DE, 1000);
    run(w, w.root, nf(w), 0, 0, 999);
}

#[test(should_fail_with = "not in identity tree")]
fn test_lied_activation() {
    // Claiming an earlier activated_at than the leaf commits to changes the leaf.
    let mut w = fixture(5, DE, 1000);
    w.activated_at = 999;
    run(w, w.root, nf(w), 0, 0, 999);
}

#[test]
fn test_predecessor_ok() {
    let w = fixture_pred(5, DE, 1000, 500);
    run_pred(w, w.root, nf(w), 0, 0, 1000, 500);
}

#[test]
fn test_fresh_registrant_any_bound() {
    let w = fixture(5, DE, 1000);
    run_pred(w, w.root, nf(w), 0, 0, 1000, 0);
}

#[test(should_fail_with = "predecessor too recent")]
fn test_predecessor_too_recent() {
    let w = fixture_pred(5, DE, 1000, 500);
    run_pred(w, w.root, nf(w), 0, 0, 1000, 499);
}

#[test(should_fail_with = "not in identity tree")]
fn test_lied_predecessor() {
    // Claiming an earlier predecessor_at than the leaf commits to changes the leaf.
    let mut w = fixture_pred(5, DE, 1000, 500);
    w.predecessor_at = 0;
    run_pred(w, w.root, nf(w), 0, 0, 1000, 0);
}

/// A tree whose only non-empty leaf is ours at `index`.
fn fixture(index: u32, country: Field, activated_at: u64) -> W {
    fixture_pred(index, country, activated_at, 0)
}

fn fixture_pred(index: u32, country: Field, activated_at: u64, predecessor_at: u64) -> W {
    let id_secret = 0x1234;
    let dsc_key = 0xd5c;
    let z = privacy_core::zero_hashes();
    let mut sib = [0; DEPTH];
    for i in 0..DEPTH {
        sib[i] = z[i];
    }
    let leaf = identity_leaf(idc(id_secret), dsc_key, country, activated_at, predecessor_at);
    let root = merkle_root(leaf, index, sib);
    W { id_secret, dsc_key, country, activated_at, predecessor_at, index, sib, root }
}
