//! privacy_core — shared hashes, derivations and Merkle path logic for the
//! privacy circuits (membership, action, stake, vote).
//!
//! Must match earth-network-chain zk/privacy and zk/merkle bit for bit.
//!
//! Every derivation is Poseidon2 (noir-lang/poseidon v0.3.0 sponge, == the
//! chain's zk/poseidon2.Hash) with a domain tag as its first input. Tags are
//! ASCII strings read as big-endian integers, distinct from each other and from
//! the passport circuits' small curve tags. The sponge also absorbs the input
//! length, so hashes of different arity cannot collide. Append only.

use poseidon::poseidon2::Poseidon2;

pub mod value;

pub global DEPTH: u32 = 32;

/// Every note value and stake note amount is at most 2^63 - 1: what every
/// wallet holds (Android Long, iOS Int64, web; PRIVACY_FORMATS section 4,
/// Amounts) and the most the chain mints (x/shielded MaxNoteValue,
/// ORCHARD_DESIGN section 16). note_cm and stake_cm enforce it, so no circuit
/// opens or creates a commitment to a larger value: a bundle cannot make a
/// note no wallet can see.
pub global NOTE_VALUE_BITS: u32 = 63;

/// value < 2^NOTE_VALUE_BITS: one range constraint on the value's own
/// witness, no new witness.
pub fn assert_note_value(value: u64) {
    (value as Field).assert_max_bit_size::<NOTE_VALUE_BITS>();
}

pub global TAG_ID: Field = 0x65617274682e6964; // "earth.id"
pub global TAG_OWNER: Field = 0x65617274682e6f776e6572; // "earth.owner"
pub global TAG_LEAF: Field = 0x65617274682e6c656166; // "earth.leaf"
pub global TAG_SN: Field = 0x65617274682e736e; // "earth.sn"
pub global TAG_PC: Field = 0x65617274682e7063; // "earth.pc"
pub global TAG_CM: Field = 0x65617274682e636d; // "earth.cm"
pub global TAG_NF: Field = 0x65617274682e6e66; // "earth.nf"
pub global TAG_VOTE: Field = 0x65617274682e766f7465; // "earth.vote": retired (note_vote), never reuse
pub global TAG_REG: Field = 0x65617274682e726567; // "earth.reg"
pub global TAG_ASSET: Field = 0x65617274682e6173736574; // "earth.asset"
// Stake note tree (circuits/stake; chain x/shieldedstaking).
pub global TAG_STAKE: Field = 0x65617274682e7374616b65; // "earth.stake"
pub global TAG_SPC: Field = 0x65617274682e737063; // "earth.spc"
pub global TAG_SNF: Field = 0x65617274682e736e66; // "earth.snf"
pub global TAG_OTAG: Field = 0x65617274682e6f746167; // "earth.otag"
// Stake nullifier indexed tree leaves and stake vote nullifiers (circuits/vote).
pub global TAG_SNFL: Field = 0x65617274682e736e666c; // "earth.snfl"
pub global TAG_VNF: Field = 0x65617274682e766e66; // "earth.vnf"
pub global TAG_VPAD: Field = 0x65617274682e76706164; // "earth.vpad"
// Stake note slash labels and the slash debt tree (circuits/stake, circuits/vote;
// ORCHARD_DESIGN.md section 20).
pub global TAG_SLABEL: Field = 0x65617274682e736c6162656c; // "earth.slabel"
pub global TAG_DEBTL: Field = 0x65617274682e646562746c; // "earth.debtl"

/// Asset id of `uerth` = H(TAG_ASSET, 5, 0x7565727468) (zk/privacy.AssetID).
pub global ASSET_ERTH: Field = 0xad44a14c7205c61e39db2c64b79005ffb14a2a927977e8cda2208be2e3fe54c;

pub fn h2(a: Field, b: Field) -> Field {
    Poseidon2::hash([a, b], 2)
}

pub fn h3(a: Field, b: Field, c: Field) -> Field {
    Poseidon2::hash([a, b, c], 3)
}

pub fn h4(a: Field, b: Field, c: Field, d: Field) -> Field {
    Poseidon2::hash([a, b, c, d], 4)
}

pub fn h5(a: Field, b: Field, c: Field, d: Field, e: Field) -> Field {
    Poseidon2::hash([a, b, c, d, e], 5)
}

pub fn h6(a: Field, b: Field, c: Field, d: Field, e: Field, f: Field) -> Field {
    Poseidon2::hash([a, b, c, d, e, f], 6)
}

/// idc = H(TAG_ID, id_secret)
pub fn idc(id_secret: Field) -> Field {
    h2(TAG_ID, id_secret)
}

/// owner_pk = H(TAG_OWNER, nk)
pub fn owner_pk(nk: Field) -> Field {
    h2(TAG_OWNER, nk)
}

/// leaf = H(TAG_LEAF, idc, dsc_key, country, activated_at, predecessor_at)
///
/// `country` is the issuing country as the chain records it (the CSCA's ISO
/// 3166-1 alpha-2), encoded by country_code; 0 when unknown.
/// `predecessor_at` is the time of the switch or re-entry that created the
/// leaf (unix seconds), 0 for a passport never registered before.
pub fn identity_leaf(idc: Field, dsc_key: Field, country: Field, activated_at: u64, predecessor_at: u64) -> Field {
    h6(TAG_LEAF, idc, dsc_key, country, activated_at as Field, predecessor_at as Field)
}

/// Field encoding of an ISO 3166-1 alpha-2 code: its two ASCII bytes read
/// big-endian ("DE" = 0x4445). Never 0 for a real code; 0 means unknown.
/// Wallets and tests only; the circuits take the encoded value. Matches
/// zk/privacy.CountryField.
pub fn country_code(a: u8, b: u8) -> Field {
    (a as Field) * 256 + (b as Field)
}

/// Per-scope nullifier = H(TAG_SN, id_secret, scope)
pub fn scope_nullifier(id_secret: Field, scope: Field) -> Field {
    h3(TAG_SN, id_secret, scope)
}

/// pc = H(TAG_PC, owner_pk, rho, rcm)
pub fn note_pc(owner_pk: Field, rho: Field, rcm: Field) -> Field {
    h4(TAG_PC, owner_pk, rho, rcm)
}

/// cm = H(TAG_CM, asset, value, pc), value at most 2^63 - 1 (NOTE_VALUE_BITS).
pub fn note_cm(asset: Field, value: u64, pc: Field) -> Field {
    assert_note_value(value);
    h4(TAG_CM, asset, value as Field, pc)
}

/// nf = H(TAG_NF, nk, rho, position). `position` is a u32, so it is range
/// checked: a Field position would let `pos + k*2^32` walk the same path with a
/// different nullifier (a double spend).
pub fn note_nf(nk: Field, rho: Field, position: u32) -> Field {
    h4(TAG_NF, nk, rho, position as Field)
}

/// Stake pc = H(TAG_SPC, owner_pk, rho, rcm): a stake note's hidden owner.
/// The stake tree's notes are owner-locked: the stake circuit only ever
/// outputs (or lets the chain mint to) a stake pc of the spender's own
/// owner_pk.
pub fn stake_pc(owner_pk: Field, rho: Field, rcm: Field) -> Field {
    h4(TAG_SPC, owner_pk, rho, rcm)
}

/// Stake note commitment = H(TAG_STAKE, asset, amount, spc, label). asset is
/// the stake asset's id: zk/privacy.AssetID("derth/<valoper>"). amount at
/// most 2^63 - 1 (NOTE_VALUE_BITS). label is 0 for an ordinary note, or
/// stake_label(move_key, move_time, exposed) for a note holding derth a
/// redelegation credited while that redelegation can still be slashed.
pub fn stake_cm(asset: Field, amount: u64, spc: Field, label: Field) -> Field {
    assert_note_value(amount);
    h5(TAG_STAKE, asset, amount as Field, spc, label)
}

/// Slash label = H(TAG_SLABEL, move_key, move_time, exposed): the note holds
/// `exposed` derth credited by the redelegation `move_key` (its credit lane's
/// nullifier) made at `move_time` (unix seconds), which a slash of its source
/// for an infraction before the move may still cut (the slash debt tree).
pub fn stake_label(move_key: Field, move_time: u64, exposed: u64) -> Field {
    h4(TAG_SLABEL, move_key, move_time as Field, exposed as Field)
}

/// Leaf of the slash debt indexed tree = H(TAG_DEBTL, key, next_key,
/// next_index, retained): one slashed redelegation `key` (its move key), its
/// successor in numeric order (next_key = 0 for the largest), and `retained`,
/// the derth its credited `exposed` is still worth after the slashes so far.
/// Leaf 0 is the sentinel (0, smallest key, its index, 0). Matches
/// zk/privacy.DebtLeaf.
pub fn debt_leaf(key: Field, next_key: Field, next_index: u32, retained: u64) -> Field {
    h5(TAG_DEBTL, key, next_key, next_index as Field, retained as Field)
}

/// What `exposed` derth of the redelegation `key` is worth under the slash
/// debt tree `root`: the row's `retained` when the redelegation was slashed
/// (a leaf with that key), else all of it (a low leaf below the key whose
/// successor is above it, or the largest). One path either way.
pub fn debt_retained(
    key: Field,
    exposed: u64,
    low_key: Field,
    low_next_key: Field,
    low_next_index: u32,
    low_retained: u64,
    low_index: u32,
    low_path: [Field; DEPTH],
    root: Field,
) -> u64 {
    let leaf = debt_leaf(low_key, low_next_key, low_next_index, low_retained);
    assert(merkle_root(leaf, low_index, low_path) == root, "debt leaf not in debt tree");
    if low_key == key {
        assert(low_retained <= exposed, "debt row above the exposure");
        low_retained
    } else {
        assert(low_key.lt(key), "debt leaf not below the move");
        if low_next_key != 0 {
            assert(key.lt(low_next_key), "move is in the debt tree");
        }
        exposed
    }
}

/// Stake nullifier = H(TAG_SNF, nk, rho, position) (the stake tree's own
/// nullifier set; position u32 as for note_nf).
pub fn stake_nf(nk: Field, rho: Field, position: u32) -> Field {
    h4(TAG_SNF, nk, rho, position as Field)
}

/// Owner tag = H(TAG_OTAG, owner_pk, salt): a commitment to an owner that the
/// chain stores (a Groundworks position) and the owner later proves again.
pub fn owner_tag(owner_pk: Field, salt: Field) -> Field {
    h3(TAG_OTAG, owner_pk, salt)
}

/// Leaf of the stake nullifier indexed tree = H(TAG_SNFL, value, next_value,
/// next_index): one inserted stake nullifier `value` and its successor in
/// numeric order (`next_value`, at leaf `next_index`), next_value = 0 for the
/// largest. Leaf 0 is the sentinel (0, smallest nullifier, its index). Empty
/// slots are 0, which no tagged hash equals, so an empty slot is never a leaf.
/// Matches zk/indexed.Leaf.Hash.
pub fn nf_leaf(value: Field, next_value: Field, next_index: u32) -> Field {
    h4(TAG_SNFL, value, next_value, next_index as Field)
}

/// Stake vote nullifier = H(TAG_VNF, nk, rho, position, proposal_id): one per
/// stake note and proposal. Unlinkable to the note's spend nullifier and to the
/// same note's vote on any other proposal without nk.
pub fn vote_nf(nk: Field, rho: Field, position: u32, proposal_id: u64) -> Field {
    h5(TAG_VNF, nk, rho, position as Field, proposal_id as Field)
}

/// Padding vote nullifier = H(TAG_VPAD, nk, r, proposal_id): what an unused
/// vote slot publishes, r a fresh random field element per vote. A Poseidon2
/// output like a real vote nullifier (indistinguishable without nk and r);
/// another tag and arity (4 inputs, a real one 5), so it never equals any
/// note's vote nullifier: it can neither take nor block one.
pub fn vote_pad_nf(nk: Field, r: Field, proposal_id: u64) -> Field {
    h4(TAG_VPAD, nk, r, proposal_id as Field)
}

/// Proves `value` is NOT in the indexed tree with root `root`: a low leaf
/// (low_value, low_next_value, low_next_index) at low_index under root with
/// low_value < value and (value < low_next_value, or low_next_value = 0: the
/// low leaf holds the largest value). The leaves form a sorted linked list
/// from the sentinel 0, so a value strictly between a leaf and its successor
/// was never inserted. value = 0 is never provable (0 is the sentinel).
pub fn assert_not_in_indexed(
    value: Field,
    low_value: Field,
    low_next_value: Field,
    low_next_index: u32,
    low_index: u32,
    low_path: [Field; DEPTH],
    root: Field,
) {
    let leaf = nf_leaf(low_value, low_next_value, low_next_index);
    assert(merkle_root(leaf, low_index, low_path) == root, "low leaf not in nullifier tree");
    assert(low_value.lt(value), "low leaf not below the nullifier");
    if low_next_value != 0 {
        assert(value.lt(low_next_value), "nullifier is in the nullifier tree");
    }
}

/// Root of the depth-32 tree from a leaf, its index and siblings (leaf level
/// first). node = Poseidon2([left, right]); bit i of index set => the running
/// node is the right child at level i.
pub fn merkle_root(leaf: Field, index: u32, siblings: [Field; DEPTH]) -> Field {
    let bits: [bool; DEPTH] = (index as Field).to_le_bits();
    let mut cur = leaf;
    for i in 0..DEPTH {
        let b = bits[i] as Field;
        let s = siblings[i];
        // b = 0: (cur, s); b = 1: (s, cur)
        let left = cur + b * (s - cur);
        let right = s + b * (cur - s);
        cur = h2(left, right);
    }
    cur
}

/// Ties a public input into the constraint system with a trivial gate. The
/// proof already commits to every public input, so this is belt and braces.
pub fn bind(x: Field) {
    let sq = x * x;
    std::as_witness(sq);
    assert(sq == x * x);
}

// ---------------------------------------------------------------- tests

/// Empty-subtree roots, zero[0] = 0.
pub fn zero_hashes() -> [Field; DEPTH + 1] {
    let mut z = [0; DEPTH + 1];
    for i in 0..DEPTH {
        z[i + 1] = h2(z[i], z[i]);
    }
    z
}

#[test]
fn test_asset_erth_matches_derivation() {
    // "uerth" = 0x7565727468, 5 bytes, one chunk.
    assert(Poseidon2::hash([TAG_ASSET, 5, 0x7565727468], 3) == ASSET_ERTH);
}

#[test]
fn test_country_code() {
    // "DE" = 0x4445; Go parity: zk/privacy.CountryField("DE").
    assert(country_code(0x44, 0x45) == 0x4445);
}

#[test]
fn test_tags_distinct() {
    let t = [
        TAG_ID,
        TAG_OWNER,
        TAG_LEAF,
        TAG_SN,
        TAG_PC,
        TAG_CM,
        TAG_NF,
        TAG_VOTE,
        TAG_REG,
        TAG_ASSET,
        value::TAG_GEN,
        value::TAG_CV_R,
        TAG_STAKE,
        TAG_SPC,
        TAG_SNF,
        TAG_OTAG,
        TAG_SNFL,
        TAG_VNF,
        TAG_SLABEL,
        TAG_DEBTL,
        TAG_VPAD,
    ];
    for i in 0..21 {
        for j in 0..21 {
            if i != j {
                assert(t[i] != t[j]);
            }
        }
    }
}

#[test]
fn test_empty_tree_root() {
    let z = zero_hashes();
    let mut sib = [0; DEPTH];
    for i in 0..DEPTH {
        sib[i] = z[i];
    }
    assert(merkle_root(0, 0, sib) == z[DEPTH]);
    assert(merkle_root(0, 0xffffffff, sib) == z[DEPTH]);
    // Go parity: zk/merkle.Zero[32]
    assert(z[DEPTH] == 0x0b59baa35b9dc267744f0ccb4e3b0255c1fc512460d91130c6bc19fb2668568d);
}

#[test]
fn test_merkle_index_direction() {
    let z = zero_hashes();
    let mut sib = [0; DEPTH];
    for i in 0..DEPTH {
        sib[i] = z[i];
    }
    // leaf at index 1 is the right child of level 0.
    let r1 = merkle_root(7, 1, sib);
    let mut expect = h2(0, 7);
    for i in 1..DEPTH {
        expect = h2(expect, z[i]);
    }
    assert(r1 == expect);
    assert(merkle_root(7, 0, sib) != r1);
}

#[test]
fn test_note_value_bound() {
    let max: u64 = 0x7fffffffffffffff;
    let _ = note_cm(ASSET_ERTH, max, 1);
    let _ = stake_cm(ASSET_ERTH, max, 1, 0);
}

#[test(should_fail_with = "assert_max_bit_size")]
fn test_note_cm_above_bound() {
    let _ = note_cm(ASSET_ERTH, 0x8000000000000000, 1);
}

#[test(should_fail_with = "assert_max_bit_size")]
fn test_stake_cm_above_bound() {
    let _ = stake_cm(ASSET_ERTH, 0xffffffffffffffff, 1, 0);
}

#[test]
fn test_debt_retained() {
    // A tree of the sentinel and one row (key 10, retained 7).
    let z = zero_hashes();
    let sentinel = debt_leaf(0, 10, 1, 0);
    let row = debt_leaf(10, 0, 0, 7);
    let mut p0 = [0; DEPTH];
    let mut p1 = [0; DEPTH];
    for i in 1..DEPTH {
        p0[i] = z[i];
        p1[i] = z[i];
    }
    p0[0] = row;
    p1[0] = sentinel;
    let root = merkle_root(sentinel, 0, p0);
    assert(root == merkle_root(row, 1, p1));
    // The slashed move: its row.
    assert(debt_retained(10, 9, 10, 0, 0, 7, 1, p1, root) == 7);
    // A move below it, and one above it: untouched.
    assert(debt_retained(4, 9, 0, 10, 1, 0, 0, p0, root) == 9);
    assert(debt_retained(12, 9, 10, 0, 0, 7, 1, p1, root) == 9);
}

#[test(should_fail_with = "move is in the debt tree")]
fn test_debt_retained_skips_its_row() {
    // The slashed move cannot prove itself absent with the sentinel.
    let z = zero_hashes();
    let sentinel = debt_leaf(0, 10, 1, 0);
    let row = debt_leaf(10, 0, 0, 7);
    let mut p0 = [0; DEPTH];
    for i in 1..DEPTH {
        p0[i] = z[i];
    }
    p0[0] = row;
    let root = merkle_root(sentinel, 0, p0);
    let _ = debt_retained(10, 9, 0, 10, 1, 0, 0, p0, root);
}

#[test(should_fail_with = "debt leaf not in debt tree")]
fn test_debt_retained_stale_root() {
    let z = zero_hashes();
    let mut p = [0; DEPTH];
    for i in 0..DEPTH {
        p[i] = z[i];
    }
    // The empty tree's sentinel against a root that holds a row.
    let sentinel = debt_leaf(0, 10, 1, 0);
    let row = debt_leaf(10, 0, 0, 7);
    let mut p0 = p;
    p0[0] = row;
    let root = merkle_root(sentinel, 0, p0);
    let _ = debt_retained(10, 9, 0, 0, 0, 0, 0, p, root);
}

// ---- Go parity (earth-network-chain zk/debt TestNoirParity)

#[test]
fn test_go_parity_debt() {
    assert(debt_leaf(1, 2, 3, 4) == 0x0b28cc858d976ddad0ede75ca9538f9b5ab36538964f6e241b8e89be2711e82a);
    assert(stake_label(0x4d4b, 1000, 200) == 0x2dfbc154973d1d3ec6e03137ba41b5c2cf5119f66cc69e3e58c033a77c80a881);
    assert(stake_cm(1, 2, 3, 4) == 0x0ffc538b4162732774bd5026e7a07bd00d2fe406af55231fa0c255c321ad4232);
    // debt.EmptyRoot: the sentinel alone; and the tree of one row (10, 7).
    let z = zero_hashes();
    let mut p = [0; DEPTH];
    for i in 0..DEPTH {
        p[i] = z[i];
    }
    assert(merkle_root(debt_leaf(0, 0, 0, 0), 0, p) == 0x0cea3d3e26cd2710109d7cbff5bf48570ba54332f812d538893f0958007f6903);
    p[0] = debt_leaf(10, 0, 0, 7);
    assert(merkle_root(debt_leaf(0, 10, 1, 0), 0, p) == 0x2a7c0afbf11f7ecd5cf533fbb282bf8eb0bf5df39d697c0b3ac04699a80b15b7);
}
