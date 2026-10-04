//! poa_core — shared passive-authentication logic for the passport register
//! circuits. Each variant binary (one per DSC key type, signature scheme and
//! hash profile; see ../variants.json and PASSPORT_COVERAGE.md) only verifies
//! the SOD signature and names its hash functions; everything else (hash
//! binding, DSC commitment, expiry, nullifier, address binding) lives here so it
//! stays identical across variants.
//!
//! The Document Signer that signed the passport is a PUBLIC input: the circuit
//! proves the SOD was signed by that specific DSC, and the chain independently
//! verifies the DSC chains to a trusted CSCA and is not revoked. An earlier
//! design hid the DSC behind a Merkle-membership proof over a registry; that
//! bought issuing-country anonymity at the cost of being unable to attribute or
//! revoke a compromised signer, which matters more for this system.

use poseidon::poseidon2::Poseidon2;

pub mod hash;

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
pub global DG1_LEN: u32 = 93;
global DG1_HEADER: [u8; 5] = [0x61, 0x5B, 0x5F, 0x1F, 0x58];

/// The DER immediately ahead of DG1's hash in the LDS security object: a
/// DataGroupHash SEQUENCE (30, 5 + H) whose dataGroupNumber is INTEGER 1
/// (02 01 01), then the H-byte OCTET STRING header (04 H). H is the DG hash
/// length (20, 28, 32, 48 or 64), so the prefix also pins which hash the entry
/// holds: every permitted hash has a different length.
pub fn dg1_hash_prefix<let H: u32>() -> [u8; 7] {
    [0x30, (H + 5) as u8, 0x02, 0x01, 0x01, 0x04, H as u8]
}

/// The DER immediately ahead of eContent's hash in the signed attributes: the
/// messageDigest attribute's OID (06 09 2A864886F70D010904), its SET (31, H + 2)
/// and the H-byte OCTET STRING header (04 H).
pub fn message_digest_prefix<let H: u32>() -> [u8; 15] {
    [
        0x06, 0x09, 0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x09, 0x04, 0x31, (H + 2) as u8, 0x04,
        H as u8,
    ]
}

// Asserts `hash` sits at `offset` inside the first `len` bytes of `data` -- the
// bytes that were actually hashed -- directly after `prefix`.
//
// Both bounds are load-bearing. The variable-length hashes read nothing at or
// past `len`, so a hash placed there is bound to no signature at all: a prover
// could keep a genuine SOD's signed prefix and write the hash of an invented DG1
// into the unhashed tail, minting a registration per invented document number.
// The prefix pins which structure the hash belongs to, so the hash of some other
// data group, or a run of bytes that merely happens to match, does not qualify.
fn assert_hash_at<let N: u32, let P: u32, let H: u32>(
    data: [u8; N],
    len: u32,
    prefix: [u8; P],
    hash: [u8; H],
    offset: u32,
) {
    assert(len <= N);
    assert(offset >= P);
    assert(offset + H <= len);
    for i in 0..P {
        assert(data[offset - P + i] == prefix[i]);
    }
    for i in 0..H {
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

/// Binds DG1 → eContent → signed attributes and returns the digest the DSC
/// signature is verified against.
///
/// `dg_hash(dg1)` sits in eContent as DG1's DataGroupHash, `ec_hash(eContent)`
/// sits in the signed attributes as the messageDigest attribute, and the
/// result is `sa_hash(signed_attributes)`. The three hashes are the passport's
/// LDS hashAlgorithm, SignerInfo digestAlgorithm and signature hash; real SODs
/// mix them (PASSPORT_COVERAGE.md section 2), so a variant names each one. The
/// prover gains nothing by naming the wrong ones: a digest under another hash
/// would have to be a preimage of what the issuer signed.
pub fn hash_and_bind<let EC: u32, let SA: u32, let HD: u32, let HE: u32, let HS: u32>(
    dg1: [u8; DG1_MAX],
    dg1_len: u32,
    e_content: [u8; EC],
    e_content_len: u32,
    dg1_hash_offset: u32,
    signed_attrs: [u8; SA],
    signed_attrs_len: u32,
    econtent_hash_offset: u32,
    dg_hash: fn([u8; DG1_MAX], u32) -> [u8; HD],
    ec_hash: fn([u8; EC], u32) -> [u8; HE],
    sa_hash: fn([u8; SA], u32) -> [u8; HS],
) -> [u8; HS] {
    // DG1 must be a whole TD3 DG1. Every MRZ field read later sits at a fixed
    // offset below DG1_LEN, so each of them is covered by the hash; a shorter
    // length would leave those bytes unhashed and free for the prover to choose.
    assert(dg1_len == DG1_LEN);
    for i in 0..5 {
        assert(dg1[i] == DG1_HEADER[i]);
    }
    let dg1_digest = dg_hash(dg1, dg1_len);
    assert_hash_at(
        e_content,
        e_content_len,
        dg1_hash_prefix::<HD>(),
        dg1_digest,
        dg1_hash_offset,
    );
    let econtent_digest = ec_hash(e_content, e_content_len);
    assert_hash_at(
        signed_attrs,
        signed_attrs_len,
        message_digest_prefix::<HE>(),
        econtent_digest,
        econtent_hash_offset,
    );
    sa_hash(signed_attrs, signed_attrs_len)
}

/// Left-pads (or truncates to its leftmost bytes) a digest to `N` bytes: the
/// ECDSA message representative for a curve whose order is N bytes, when the
/// verifier takes exactly N bytes (Noir's std secp256r1). Leftmost-bits
/// truncation is what ECDSA prescribes for a digest longer than the order; a
/// shorter one is the same integer zero-extended.
pub fn ecdsa_digest<let H: u32, let N: u32>(digest: [u8; H]) -> [u8; N] {
    let mut out: [u8; N] = [0; N];
    if H >= N {
        for i in 0..N {
            out[i] = digest[i];
        }
    } else {
        for i in 0..H {
            out[N - H + i] = digest[i];
        }
    }
    out
}

/// Curve tags, absorbed into the DSC commitment ahead of the key bytes.
///
/// These values are consensus. They must match certs.Tag* in
/// earth-network-chain/x/pki/certs/commitment.go exactly and for ever: the chain
/// recomputes the commitment from the submitted certificate and requires it to
/// equal this circuit's public output, so a mismatch on either side breaks every
/// registration. Append only; never renumber. 7 (RSA without its exponent) is
/// retired: RSA keys commit under CURVE_TAG_RSA_E, exponent included.
pub global CURVE_TAG_P256: Field = 1;
pub global CURVE_TAG_P384: Field = 2;
pub global CURVE_TAG_P521: Field = 3;
pub global CURVE_TAG_BRAINPOOL_P256R1: Field = 4;
pub global CURVE_TAG_BRAINPOOL_P384R1: Field = 5;
pub global CURVE_TAG_BRAINPOOL_P512R1: Field = 6;
pub global CURVE_TAG_P224: Field = 8;
pub global CURVE_TAG_BRAINPOOL_P224R1: Field = 9;
pub global CURVE_TAG_RSA_E: Field = 10;

/// Commitment to an ECDSA DSC = Poseidon2 over a curve tag followed by its
/// canonical public-key bytes (x‖y, each the curve's coordinate width). The
/// chain recomputes this from the submitted DSC certificate with the same
/// Poseidon2 parameters, so it identifies exactly which Document Signer
/// produced the proof.
///
/// The tag is what stops two curves of the same coordinate width sharing a
/// commitment. The sponge already absorbs the input length into its capacity
/// slot, so different widths collide with nothing; what length cannot separate
/// is P-256 from brainpoolP256r1 (64 bytes each), P-384 from brainpoolP384r1
/// (96) and P-224 from brainpoolP224r1 (56). Each variant passes its own
/// constant, because a circuit knows statically which curve it verifies against.
pub fn dsc_commitment<let N: u32>(tag: Field, pubkey: [u8; N]) -> Field {
    let key = bytes_to_fields(pubkey);
    let mut input: [Field; N + 1] = [0; N + 1];
    input[0] = tag;
    for i in 0..N {
        input[i + 1] = key[i];
    }
    Poseidon2::hash(input, N + 1)
}

/// Commitment to an RSA DSC = Poseidon2(CURVE_TAG_RSA_E, e, modulus bytes
/// big-endian). The exponent is a witness (real DSCs use 3, 65537 and random
/// odd values), so it is committed: the proof then states which (n, e) signed,
/// and the chain recomputes both from the certificate.
pub fn dsc_commitment_rsa<let NB: u32>(exponent: u32, modulus: [u8; NB]) -> Field {
    let mut input: [Field; NB + 2] = [0; NB + 2];
    input[0] = CURVE_TAG_RSA_E;
    input[1] = exponent as Field;
    for i in 0..NB {
        input[i + 2] = modulus[i] as Field;
    }
    Poseidon2::hash(input, NB + 2)
}

/// The big-endian bytes of a modulus held as noir-bignum's 120-bit
/// little-endian limbs, for the commitment.
///
/// Read straight off the limbs rather than through RuntimeBigNum::to_be_bytes,
/// which requires a value below the modulus and so fails on the modulus itself.
/// Each limb is decomposed into exactly 15 bytes (the top one into what is
/// left), and Field::to_be_bytes asserts the limb fits, so the bytes determine
/// the integer: no oversized limb can carry into its neighbour. BigNumParams::new
/// separately requires the top limb to fill the modulus's bit length, which is
/// why the chain's minimal big-endian encoding is always exactly NB bytes.
pub fn modulus_be_bytes<let L: u32, let NB: u32>(limbs: [u128; L]) -> [u8; NB] {
    let mut out: [u8; NB] = [0; NB];
    let top = NB - (L - 1) * 15;
    let mut at = top;
    for i in 0..L - 1 {
        let b: [u8; 15] = (limbs[L - 2 - i] as Field).to_be_bytes();
        for j in 0..15 {
            out[at + j] = b[j];
        }
        at += 15;
    }
    let t: [u8; 15] = (limbs[L - 1] as Field).to_be_bytes();
    // The top limb holds `top` bytes; the bytes above them must be zero.
    for j in 0..15 - top {
        assert(t[j] == 0);
    }
    for j in 0..top {
        out[j] = t[15 - top + j];
    }
    out
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
    // number and birth date would share one nullifier, and the second to
    // register would silently take over the first's registration as a "wallet
    // switch". The optional data carries the overflow of a number longer than
    // nine characters; without it, two documents sharing the first nine would
    // collide the same way. Every byte is covered by the SOD hash, so none of it
    // can be chosen.
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

mod tests;

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
