//! note_vote — a stake-weighted vote from a shielded note.
//!
//! Proves, for a note the prover owns (knows nk):
//!   pc = H(TAG_PC, H(TAG_OWNER, nk), rho, rcm)
//!   cm = H(TAG_CM, asset, value, pc) is at `position` in the note tree at `root`
//!   vote_nf = H(TAG_VOTE, nk, rho, position, proposal_id)
//!   nf      = H(TAG_NF, nk, rho, position)
//! and binds `signal` (the chain's hash of the enclosing msg: option, fee
//! transfer, chain id).
//!
//! The chain uses `root` = the note-tree root recorded when the proposal
//! entered voting, so only notes that existed then can vote, and `asset`,
//! `value` are the vote's public weight (a derth/<valoper> note).
//!
//! vote_nf makes it one vote per note per proposal (a later vote with the same
//! vote_nf replaces the earlier one); proposal_id is public so vote_nf cannot
//! be computed for a made-up proposal to vote twice.
//!
//! nf is the note's own spend nullifier. Tree membership alone does not say a
//! note is unspent: the tree is append-only, so a note spent before the
//! proposal started (or moved into a Groundworks position) is still in the tree
//! at `root`, and could otherwise vote alongside the notes it was spent into.
//! The chain refuses a vote whose nf is already spent. The cost: once the note
//! is later spent, that spend is linkable to this vote.
//!
//! Public input order (the chain must supply them in this order):
//!   root, asset, value, proposal_id, vote_nf, nf, signal

use privacy_core::{bind, DEPTH, merkle_root, note_cm, note_nf, note_pc, owner_pk, vote_nullifier};

fn main(
    nk: Field,
    rho: Field,
    rcm: Field,
    position: u32,
    path: [Field; DEPTH],
    root: pub Field,
    asset: pub Field,
    value: pub u64,
    proposal_id: pub u64,
    vote_nf: pub Field,
    nf: pub Field,
    signal: pub Field,
) {
    let pc = note_pc(owner_pk(nk), rho, rcm);
    let cm = note_cm(asset, value, pc);
    assert(merkle_root(cm, position, path) == root, "note not in tree");
    assert(note_nf(nk, rho, position) == nf, "bad nullifier");
    assert(vote_nullifier(nk, rho, position, proposal_id) == vote_nf, "bad vote nullifier");
    bind(signal);
}

// ---------------------------------------------------------------- tests

struct Fx {
    nk: Field,
    rho: Field,
    rcm: Field,
    position: u32,
    path: [Field; DEPTH],
    root: Field,
    asset: Field,
    value: u64,
    proposal_id: u64,
    vote_nf: Field,
    nf: Field,
    signal: Field,
}

fn run(f: Fx) {
    main(
        f.nk,
        f.rho,
        f.rcm,
        f.position,
        f.path,
        f.root,
        f.asset,
        f.value,
        f.proposal_id,
        f.vote_nf,
        f.nf,
        f.signal,
    );
}

global ASSET_D: Field = 0xde27;

/// The voting note at position 1 of a tree holding [other, ours, other2].
fn fixture(value: u64, proposal_id: u64) -> Fx {
    let nk = 0x6e6b;
    let rho = 0x72686f;
    let rcm = 0x72636d;
    let position: u32 = 1;
    let ours = note_cm(ASSET_D, value, note_pc(owner_pk(nk), rho, rcm));
    let other = note_cm(ASSET_D, 5, 0x77);
    let other2 = note_cm(ASSET_D, 9, 0x78);
    let z = privacy_core::zero_hashes();
    let mut path = [0; DEPTH];
    for i in 2..DEPTH {
        path[i] = z[i];
    }
    path[0] = other;
    path[1] = privacy_core::h2(other2, 0);
    let root = merkle_root(ours, position, path);
    Fx {
        nk,
        rho,
        rcm,
        position,
        path,
        root,
        asset: ASSET_D,
        value,
        proposal_id,
        vote_nf: vote_nullifier(nk, rho, position, proposal_id),
        nf: note_nf(nk, rho, position),
        signal: 0x5169,
    }
}

#[test]
fn test_vote() {
    run(fixture(1_000_000, 7));
}

#[test]
fn test_max_value() {
    run(fixture(0xffffffffffffffff, 0xffffffffffffffff));
}

#[test]
fn test_vote_nf_differs_per_proposal_and_from_nf() {
    let a = fixture(10, 1);
    let b = fixture(10, 2);
    assert(a.vote_nf != b.vote_nf);
    assert(a.vote_nf != a.nf);
    assert(a.nf == b.nf);
}

#[test(should_fail_with = "note not in tree")]
fn test_wrong_root() {
    let mut f = fixture(10, 1);
    f.root = 1;
    run(f);
}

#[test(should_fail_with = "note not in tree")]
fn test_claim_more_value() {
    let mut f = fixture(10, 1);
    f.value = 11;
    run(f);
}

#[test(should_fail_with = "note not in tree")]
fn test_wrong_asset() {
    let mut f = fixture(10, 1);
    f.asset = 0xde28;
    run(f);
}

#[test(should_fail_with = "note not in tree")]
fn test_wrong_owner() {
    let mut f = fixture(10, 1);
    f.nk = 0x999;
    f.nf = note_nf(f.nk, f.rho, f.position);
    f.vote_nf = vote_nullifier(f.nk, f.rho, f.position, f.proposal_id);
    run(f);
}

#[test(should_fail_with = "note not in tree")]
fn test_wrong_position() {
    let mut f = fixture(10, 1);
    f.position = 3;
    f.nf = note_nf(f.nk, f.rho, f.position);
    f.vote_nf = vote_nullifier(f.nk, f.rho, f.position, f.proposal_id);
    run(f);
}

#[test(should_fail_with = "bad nullifier")]
fn test_fake_spend_nullifier() {
    let mut f = fixture(10, 1);
    f.nf = 0x1234;
    run(f);
}

#[test(should_fail_with = "bad vote nullifier")]
fn test_vote_nf_for_another_proposal() {
    // A vote_nf computed for proposal 2 cannot be passed off as proposal 1's:
    // that would give one note a second vote on proposal 1.
    let mut f = fixture(10, 1);
    f.vote_nf = vote_nullifier(f.nk, f.rho, f.position, 2);
    run(f);
}

#[test(should_fail)]
fn test_value_overflow() {
    let mut f = fixture(10, 1);
    f.value = 0xffffffffffffffff + 1;
    run(f);
}
