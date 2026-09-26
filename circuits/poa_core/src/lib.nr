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
// MRZ line 1, chars 2..4: the issuing state or organisation.
global ISSUER_OFFSET: u32 = 7;
global ISSUER_LEN: u32 = 3;
// MRZ line 2, char 9: the document number's check digit, and chars 28..41: the
// optional data field. A document number longer than nine characters puts '<'
// in the check digit and continues in the optional data (ICAO 9303), so the
// two together carry whatever the nine-character field could not.
global DOC_CHECK_OFFSET: u32 = 58;
global OPTIONAL_OFFSET: u32 = 77;
global OPTIONAL_LEN: u32 = 14;
// Issuing state + document number + its check digit + DOB + optional data.
global NULLIFIER_PREIMAGE_LEN: u32 = 33;
global EXPIRY_OFFSET: u32 = 70; // MRZ line 2, expiry YYMMDD (6 bytes)

pub fn bytes_to_fields<let N: u32>(b: [u8; N]) -> [Field; N] {
    let mut f: [Field; N] = [0; N];
    for i in 0..N {
        f[i] = b[i] as Field;
    }
    f
}

// A TD3 DG1 is exactly its 5-byte header (tag 61, length 5B, MRZ tag 5F1F, MRZ
// length 58) followed by the 88-character MRZ.
global DG1_LEN: u32 = 93;
global DG1_HEADER: [u8; 5] = [0x61, 0x5B, 0x5F, 0x1F, 0x58];

// The DER immediately ahead of DG1's hash in the LDS security object: a
// DataGroupHash SEQUENCE (30 25) whose dataGroupNumber is INTEGER 1 (02 01 01),
// then the 32-byte OCTET STRING header (04 20).
global DG1_HASH_PREFIX: [u8; 7] = [0x30, 0x25, 0x02, 0x01, 0x01, 0x04, 0x20];

// The DER immediately ahead of eContent's hash in the signed attributes: the
// messageDigest attribute's OID (06 09 2A864886F70D010904), its SET (31 22) and
// the 32-byte OCTET STRING header (04 20).
global MESSAGE_DIGEST_PREFIX: [u8; 15] = [
    0x06, 0x09, 0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x09, 0x04, 0x31, 0x22, 0x04, 0x20,
];

// Asserts `hash` sits at `offset` inside the first `len` bytes of `data` -- the
// bytes that were actually hashed -- directly after `prefix`.
//
// Both bounds are load-bearing. sha256_var reads nothing at or past `len`, so a
// hash placed there is bound to no signature at all: a prover could keep a
// genuine SOD's signed prefix and write the hash of an invented DG1 into the
// unhashed tail, minting a registration per invented document number. The
// prefix pins which structure the hash belongs to, so the hash of some other
// data group, or a run of bytes that merely happens to match, does not qualify.
fn assert_hash_at<let N: u32, let P: u32>(
    data: [u8; N],
    len: u32,
    prefix: [u8; P],
    hash: [u8; 32],
    offset: u32,
) {
    assert(len <= N);
    assert(offset >= P);
    assert(offset + 32 <= len);
    for i in 0..P {
        assert(data[offset - P + i] == prefix[i]);
    }
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
    // DG1 must be a whole TD3 DG1. Every MRZ field read later sits at a fixed
    // offset below DG1_LEN, so each of them is covered by the hash; a shorter
    // length would leave those bytes unhashed and free for the prover to choose.
    assert(dg1_len == DG1_LEN);
    for i in 0..5 {
        assert(dg1[i] == DG1_HEADER[i]);
    }
    let dg1_hash = sha256_var(dg1, dg1_len);
    assert_hash_at(e_content, e_content_len, DG1_HASH_PREFIX, dg1_hash, dg1_hash_offset);
    let econtent_hash = sha256_var(e_content, e_content_len);
    assert_hash_at(
        signed_attrs,
        signed_attrs_len,
        MESSAGE_DIGEST_PREFIX,
        econtent_hash,
        econtent_hash_offset,
    );
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
/// nullifier is Poseidon2 over the issuing state, the whole document number and
/// the date of birth (see nullifier_preimage).
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
    //
    // The preimage also carries the issuing state, and everything a long
    // document number spills into.
    //
    // A document number is unique only within the state that issued it. Without
    // the state, two people from different countries with the same nine-char
    // number and birth date got one nullifier, and the second to register
    // silently took over the first's registration as a "wallet switch". And a
    // number longer than nine characters was cut to its first nine, so two
    // documents sharing that prefix collided the same way. Every byte added is
    // already covered by the SOD hash, so none of it can be chosen.
    let id = nullifier_preimage(dg1);
    (Poseidon2::hash(id, NULLIFIER_PREIMAGE_LEN), dsc_key)
}

/// Issuing state ‖ document number ‖ check digit ‖ DOB ‖ optional data.
fn nullifier_preimage(dg1: [u8; DG1_MAX]) -> [Field; NULLIFIER_PREIMAGE_LEN] {
    let mut id: [Field; NULLIFIER_PREIMAGE_LEN] = [0; NULLIFIER_PREIMAGE_LEN];
    let mut at = 0;
    for i in 0..ISSUER_LEN {
        id[at] = dg1[ISSUER_OFFSET + i] as Field;
        at += 1;
    }
    for i in 0..DOC_NUM_LEN {
        id[at] = dg1[DOC_NUM_OFFSET + i] as Field;
        at += 1;
    }
    id[at] = dg1[DOC_CHECK_OFFSET] as Field;
    at += 1;
    for i in 0..6 {
        id[at] = dg1[DOB_OFFSET + i] as Field;
        at += 1;
    }
    for i in 0..OPTIONAL_LEN {
        id[at] = dg1[OPTIONAL_OFFSET + i] as Field;
        at += 1;
    }
    assert(at == NULLIFIER_PREIMAGE_LEN);
    id
}

// --- hash-binding tests ------------------------------------------------------
//
// Each builds a well-formed DG1 → eContent → signedAttrs chain and then breaks
// exactly one link. The attack cases are the ones that verified before the
// length and prefix checks: a hash parked past the hashed length, and a DG1
// short enough to leave the MRZ fields it is read for unhashed.

#[test]
fn binding_accepts_well_formed_chain() {
    let (dg1, ec, ec_len, dg1_off, sa, sa_len, ec_off) = test_chain(40, 24);
    let _ = hash_and_bind(dg1, DG1_LEN, ec, ec_len, dg1_off, sa, sa_len, ec_off);
}

#[test(should_fail)]
fn binding_rejects_econtent_hash_past_signed_length() {
    let (dg1, ec, ec_len, dg1_off, sa, sa_len, ec_off) = test_chain(40, 24);
    // Move the eContent hash (and its prefix) out of the signed bytes.
    let mut forged = sa;
    let moved = sa_len + MESSAGE_DIGEST_PREFIX.len();
    for i in 0..(MESSAGE_DIGEST_PREFIX.len() + 32) {
        forged[moved - MESSAGE_DIGEST_PREFIX.len() + i] = sa[ec_off - MESSAGE_DIGEST_PREFIX.len() + i];
    }
    let _ = hash_and_bind(dg1, DG1_LEN, ec, ec_len, dg1_off, forged, sa_len, moved);
}

#[test(should_fail)]
fn binding_rejects_dg1_hash_past_econtent_length() {
    let (dg1, ec, ec_len, dg1_off, sa, sa_len, ec_off) = test_chain(40, 24);
    let mut forged = ec;
    let moved = ec_len + DG1_HASH_PREFIX.len();
    for i in 0..(DG1_HASH_PREFIX.len() + 32) {
        forged[moved - DG1_HASH_PREFIX.len() + i] = ec[dg1_off - DG1_HASH_PREFIX.len() + i];
    }
    let _ = hash_and_bind(dg1, DG1_LEN, forged, ec_len, moved, sa, sa_len, ec_off);
}

#[test(should_fail)]
fn binding_rejects_short_dg1() {
    let (dg1, _, _, _, _, _, _) = test_chain(40, 24);
    // Rebuild the chain over a 60-byte DG1: document number onwards unhashed.
    let (ec, ec_len, dg1_off) = test_embed(sha256_var(dg1, 60), DG1_HASH_PREFIX, 40);
    let (sa, sa_len, ec_off) = test_embed(sha256_var(ec, ec_len), MESSAGE_DIGEST_PREFIX, 24);
    let _ = hash_and_bind(dg1, 60, ec, ec_len, dg1_off, sa, sa_len, ec_off);
}

#[test(should_fail)]
fn binding_rejects_hash_without_its_der_prefix() {
    let (dg1, _, _, _, _, _, _) = test_chain(40, 24);
    // The DG1 hash is present and inside the hashed bytes, but not as DG1's
    // DataGroupHash entry.
    let wrong: [u8; 7] = [0x30, 0x25, 0x02, 0x01, 0x0B, 0x04, 0x20];
    let (ec, ec_len, dg1_off) = test_embed(sha256_var(dg1, DG1_LEN), wrong, 40);
    let (sa, sa_len, ec_off) = test_embed(sha256_var(ec, ec_len), MESSAGE_DIGEST_PREFIX, 24);
    let _ = hash_and_bind(dg1, DG1_LEN, ec, ec_len, dg1_off, sa, sa_len, ec_off);
}

#[test(should_fail)]
fn binding_rejects_wrong_dg1_header() {
    let (mut dg1, _, _, _, _, _, _) = test_chain(40, 24);
    dg1[0] = 0x6B; // DG11's tag
    let (ec, ec_len, dg1_off) = test_embed(sha256_var(dg1, DG1_LEN), DG1_HASH_PREFIX, 40);
    let (sa, sa_len, ec_off) = test_embed(sha256_var(ec, ec_len), MESSAGE_DIGEST_PREFIX, 24);
    let _ = hash_and_bind(dg1, DG1_LEN, ec, ec_len, dg1_off, sa, sa_len, ec_off);
}

#[test]
fn test_embed_places_hash_after_prefix() {
    let h: [u8; 32] = [7; 32];
    let (buf, len, off) = test_embed(h, DG1_HASH_PREFIX, 40);
    assert(off == 40);
    assert(len == 40 + 32 + 8);
    assert(buf[39] == 0x20);
    assert(buf[40] == 7);
}

fn test_embed<let P: u32>(hash: [u8; 32], prefix: [u8; P], offset: u32) -> ([u8; 200], u32, u32) {
    let mut buf: [u8; 200] = [0; 200];
    for i in 0..200 {
        buf[i] = (i * 7) as u8;
    }
    for i in 0..P {
        buf[offset - P + i] = prefix[i];
    }
    for i in 0..32 {
        buf[offset + i] = hash[i];
    }
    (buf, offset + 32 + 8, offset)
}

fn test_chain(
    dg1_offset: u32,
    ec_offset: u32,
) -> ([u8; DG1_MAX], [u8; 200], u32, u32, [u8; 200], u32, u32) {
    let mut dg1: [u8; DG1_MAX] = [0x3C; DG1_MAX]; // '<'
    for i in 0..5 {
        dg1[i] = DG1_HEADER[i];
    }
    let (ec, ec_len, dg1_off) = test_embed(sha256_var(dg1, DG1_LEN), DG1_HASH_PREFIX, dg1_offset);
    let (sa, sa_len, ec_off) = test_embed(sha256_var(ec, ec_len), MESSAGE_DIGEST_PREFIX, ec_offset);
    (dg1, ec, ec_len, dg1_off, sa, sa_len, ec_off)
}

// --- nullifier tests ---------------------------------------------------------

fn nullifier_of(dg1: [u8; DG1_MAX]) -> Field {
    Poseidon2::hash(nullifier_preimage(dg1), NULLIFIER_PREIMAGE_LEN)
}

fn sample_dg1() -> [u8; DG1_MAX] {
    let mut dg1: [u8; DG1_MAX] = [0x3C; DG1_MAX]; // '<'
    // Issuing state UTO, document number L898902C3, DOB 740812.
    let state = [0x55, 0x54, 0x4F];
    for i in 0..3 {
        dg1[ISSUER_OFFSET + i] = state[i];
    }
    let doc = [0x4C, 0x38, 0x39, 0x38, 0x39, 0x30, 0x32, 0x43, 0x33];
    for i in 0..9 {
        dg1[DOC_NUM_OFFSET + i] = doc[i];
    }
    let dob = [0x37, 0x34, 0x30, 0x38, 0x31, 0x32];
    for i in 0..6 {
        dg1[DOB_OFFSET + i] = dob[i];
    }
    dg1
}

#[test]
fn nullifier_separates_issuing_states() {
    let a = sample_dg1();
    let mut b = a;
    b[ISSUER_OFFSET] = 0x44; // DTO: same number and birth date, another state
    assert(nullifier_of(a) != nullifier_of(b));
}

#[test]
fn nullifier_covers_a_long_document_number() {
    // Two twelve-character numbers sharing their first nine: the check digit
    // is '<' and the rest continues in the optional data.
    let mut a = sample_dg1();
    a[DOC_CHECK_OFFSET] = 0x3C;
    a[OPTIONAL_OFFSET] = 0x31;
    let mut b = a;
    b[OPTIONAL_OFFSET] = 0x32;
    assert(nullifier_of(a) != nullifier_of(b));
}
