//! privacy_core — shared hashes, derivations and Merkle path logic for the
//! privacy circuits (membership, transfer).
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

/// idc = H(TAG_ID, id_secret)
pub fn idc(id_secret: Field) -> Field {
    h2(TAG_ID, id_secret)
}

/// owner_pk = H(TAG_OWNER, nk)
pub fn owner_pk(nk: Field) -> Field {
    h2(TAG_OWNER, nk)
}

/// leaf = H(TAG_LEAF, idc, dsc_key, country, activated_at)
///
/// `country` is the issuing country as the chain records it (the CSCA's ISO
/// 3166-1 alpha-2), encoded by country_code; 0 when unknown.
pub fn identity_leaf(idc: Field, dsc_key: Field, country: Field, activated_at: u64) -> Field {
    h5(TAG_LEAF, idc, dsc_key, country, activated_at as Field)
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

/// cm = H(TAG_CM, asset, value, pc)
pub fn note_cm(asset: Field, value: u64, pc: Field) -> Field {
    h4(TAG_CM, asset, value as Field, pc)
}

/// nf = H(TAG_NF, nk, rho, position). `position` is a u32, so it is range
/// checked: a Field position would let `pos + k*2^32` walk the same path with a
/// different nullifier (a double spend).
pub fn note_nf(nk: Field, rho: Field, position: u32) -> Field {
    h4(TAG_NF, nk, rho, position as Field)
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
    ];
    for i in 0..12 {
        for j in 0..12 {
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
