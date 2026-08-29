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
// MRZ line 2 begins at DG1[49] (5-byte header + 44-char line 1); the document
// number is its first nine characters.
global DOC_NUM_OFFSET: u32 = 49;
global DOC_NUM_LEN: u32 = 9;
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

/// Curve tags, absorbed into the DSC commitment ahead of the key bytes.
///
/// These values are consensus. They must match certs.Tag* in
/// earth-network-chain/x/pki/certs/commitment.go exactly and for ever: the chain
/// recomputes the commitment from the submitted certificate and requires it to
/// equal this circuit's public output, so a mismatch on either side breaks every
/// registration. Append only; never renumber.
pub global CURVE_TAG_P256: Field = 1;
pub global CURVE_TAG_P384: Field = 2;
pub global CURVE_TAG_P521: Field = 3;
pub global CURVE_TAG_BRAINPOOL_P256R1: Field = 4;
pub global CURVE_TAG_BRAINPOOL_P384R1: Field = 5;
pub global CURVE_TAG_BRAINPOOL_P512R1: Field = 6;
pub global CURVE_TAG_RSA: Field = 7;

/// Commitment to a DSC = Poseidon2 over a curve tag followed by its canonical
/// public-key bytes (ECDSA: x‖y; RSA: modulus big-endian). The chain recomputes
/// this from the submitted DSC certificate with the same Poseidon2 parameters,
/// so it identifies exactly which Document Signer produced the proof.
///
/// The tag is what stops two curves of the same coordinate width sharing a
/// commitment. The sponge already absorbs the input length into its capacity
/// slot, so RSA — 256 or 512 bytes — collides with nothing; what length cannot
/// separate is P-256 from brainpoolP256r1 (64 bytes each) and P-384 from
/// brainpoolP384r1 (96 each). Each variant passes its own constant, because a
/// circuit knows statically which curve it verifies against.
pub fn dsc_commitment<let N: u32>(tag: Field, pubkey: [u8; N]) -> Field {
    let key = bytes_to_fields(pubkey);
    let mut input: [Field; N + 1] = [0; N + 1];
    input[0] = tag;
    for i in 0..N {
        input[i + 1] = key[i];
    }
    Poseidon2::hash(input, N + 1)
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
    address: Field,
) -> (Field, Field) {
    assert(yymmdd(dg1, EXPIRY_OFFSET) >= current_date);

    // Bind the proof to the account it was proved for.
    //
    // Without this, a proof is a bearer token. It travels in the clear inside
    // MsgRegister, so anyone who reads a block has the bytes; the only thing
    // that made replaying them pointless was the chain refusing a nullifier
    // that was already live. The moment re-registering is allowed to MOVE a
    // registration -- which is what a person who lost their wallet needs -- that
    // refusal is gone, and a replay becomes theft of somebody's personhood.
    //
    // Being a public input is what binds it: the verifier checks the proof
    // against the public input vector it is handed, so a proof made for one
    // address simply does not verify against another. The assert is here so the
    // parameter cannot be optimised out of the circuit for being otherwise
    // unread, and it costs a constraint to also reject the zero address.
    assert(address != 0);

    // The nullifier is Poseidon2(document number ‖ DOB), NOT name ‖ DOB.
    //
    // This is a deliberate reversal of SECURITY.md finding #1 -- see the
    // reopened entry there. Name ‖ DOB is stable across a passport renewal,
    // which is why it was chosen; the cost is that it is guessable. A nullifier
    // is public, and a name and birth date are not secrets, so anyone holding a
    // candidate pair could confirm which on-chain address belongs to that
    // person and read their balance. That is a confirmation attack that scales
    // to anyone with a phone book.
    //
    // A document number carries far more entropy and is not published anywhere.
    // The price is that it changes on renewal, so one human can end up with two
    // live registrations across a ten-year cycle. That is bounded by the cost
    // and cadence of actually renewing a passport, and it was judged the better
    // side of the trade: linking every wallet to a named person is a permanent
    // harm to every user, a duplicate registration is a rate-limited one.
    let mut id: [Field; 15] = [0; 15];
    for i in 0..DOC_NUM_LEN {
        id[i] = dg1[DOC_NUM_OFFSET + i] as Field;
    }
    for i in 0..6 {
        id[DOC_NUM_LEN + i] = dg1[DOB_OFFSET + i] as Field;
    }
    (Poseidon2::hash(id, 15), dsc_key)
}
