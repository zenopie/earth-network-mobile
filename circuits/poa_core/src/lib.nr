//! poa_core — shared passive-authentication logic for the passport register
//! circuits. Each per-algorithm binary (P-256, RSA-2048, RSA-4096, …) does only
//! the SOD→DSC signature verification and DSC key encoding that differ by key
//! type; everything else (hash binding, DSC binding, expiry, nullifier) lives
//! here so it stays identical across variants.
//!
//! The Document Signer that signed the passport is a PUBLIC input: the circuit
//! proves the SOD was signed by that specific DSC, and the chain independently
//! verifies the DSC chains to a trusted CSCA and is not revoked. An earlier
//! design hid the DSC behind a Merkle-membership proof over a registry; that
//! bought issuing-country anonymity at the cost of being unable to attribute or
//! revoke a compromised signer, which matters more for this system.

use poseidon::poseidon2::Poseidon2;
use sha256::sha256_var;

pub global DG1_MAX: u32 = 95;

// TD3 MRZ field offsets within DG1 (5-byte DG1 header + 88-char MRZ).
global NAME_OFFSET: u32 = 10; // MRZ line 1, chars 5..44 (39 bytes)
global NAME_LEN: u32 = 39;
global DOB_OFFSET: u32 = 62; // MRZ line 2, DOB YYMMDD (6 bytes)
global EXPIRY_OFFSET: u32 = 70; // MRZ line 2, expiry YYMMDD (6 bytes)

pub fn bytes_to_fields<let N: u32>(b: [u8; N]) -> [Field; N] {
    let mut f: [Field; N] = [0; N];
    for i in 0..N {
        f[i] = b[i] as Field;
    }
    f
}

fn assert_hash_at<let N: u32>(data: [u8; N], hash: [u8; 32], offset: u32) {
    for i in 0..32 {
        assert(data[offset + i] == hash[i]);
    }
}

// Reads a 6-ASCII-digit YYMMDD from `data` at `offset` as a comparable number.
fn yymmdd(data: [u8; DG1_MAX], offset: u32) -> u32 {
    let mut n: u32 = 0;
    for i in 0..6 {
        let d = (data[offset + i] as u32) - 48; // ASCII '0'..'9'
        assert(d < 10);
        n = n * 10 + d;
    }
    n
}

/// Binds DG1 → eContent → signed attributes and returns the message hash the DSC
/// signature is verified against: sha256(dg1) sits in eContent, sha256(eContent)
/// sits in the signed attributes, and msg_hash = sha256(signed_attributes).
pub fn hash_and_bind(
    dg1: [u8; DG1_MAX],
    dg1_len: u32,
    e_content: [u8; 200],
    e_content_len: u32,
    dg1_hash_offset: u32,
    signed_attrs: [u8; 200],
    signed_attrs_len: u32,
    econtent_hash_offset: u32,
) -> [u8; 32] {
    let dg1_hash = sha256_var(dg1, dg1_len);
    assert_hash_at(e_content, dg1_hash, dg1_hash_offset);
    let econtent_hash = sha256_var(e_content, e_content_len);
    assert_hash_at(signed_attrs, econtent_hash, econtent_hash_offset);
    sha256_var(signed_attrs, signed_attrs_len)
}

/// Commitment to a DSC = Poseidon2 over its canonical public-key bytes (ECDSA:
/// x‖y; RSA: modulus big-endian). The chain recomputes this from the submitted
/// DSC certificate with the same Poseidon2 parameters, so it identifies exactly
/// which Document Signer produced the proof.
pub fn dsc_commitment<let N: u32>(pubkey: [u8; N]) -> Field {
    Poseidon2::hash(bytes_to_fields(pubkey), N)
}

/// Expiry + nullifier + DSC commitment, shared by every variant.
///
/// Returns `(nullifier, dsc_key)`, both public. `dsc_key` is *returned* rather
/// than accepted as an input so the prover never has to compute Poseidon2
/// itself — the circuit derives it from the key the signature was actually
/// verified against, and the chain recomputes it from the DSC certificate in the
/// transaction. A prover therefore cannot sign with one key and name another.
///
/// Also asserts the passport is unexpired against `current_date` and that the
/// nullifier Poseidon2(name‖DOB) is renewal-stable.
pub fn finalize(
    dg1: [u8; DG1_MAX],
    dsc_key: Field,
    current_date: u32,
) -> (Field, Field) {
    assert(yymmdd(dg1, EXPIRY_OFFSET) >= current_date);

    let mut id: [Field; 45] = [0; 45];
    for i in 0..NAME_LEN {
        id[i] = dg1[NAME_OFFSET + i] as Field;
    }
    for i in 0..6 {
        id[NAME_LEN + i] = dg1[DOB_OFFSET + i] as Field;
    }
    (Poseidon2::hash(id, 45), dsc_key)
}
