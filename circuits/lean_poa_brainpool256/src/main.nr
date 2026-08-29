//! lean_poa_brainpool256 — passport register circuit for an ECDSA Document Signer on a non-P256
//! curve, via zkpassport/noir-ecdsa (generic in-circuit ECDSA over noir_bigcurve).
//! Shares poa_core; public interface [current_date] -> (nullifier, dsc_key).

use ecdsa::ecdsa::verify_brainpoolp256r1_ecdsa;
use bignum::BigNum;
use bignum::fields::brainpoolP256r1Fq::BrainpoolP256r1_Fq;
use bignum::fields::brainpoolP256r1Fr::BrainpoolP256r1_Fr;

fn main(
    dg1: [u8; 95],
    dg1_len: u32,
    e_content: [u8; 200],
    e_content_len: u32,
    dg1_hash_offset: u32,
    signed_attrs: [u8; 200],
    signed_attrs_len: u32,
    econtent_hash_offset: u32,
    dsc_pubkey_x: [u8; 32],
    dsc_pubkey_y: [u8; 32],
    sod_signature_r: [u8; 32],
    sod_signature_s: [u8; 32],
    current_date: pub u32,
    address: pub Field,
) -> pub (Field, Field) {
    let msg_hash = poa_core::hash_and_bind(
        dg1, dg1_len, e_content, e_content_len, dg1_hash_offset,
        signed_attrs, signed_attrs_len, econtent_hash_offset,
    );
    let x: BrainpoolP256r1_Fq = BrainpoolP256r1_Fq::from_be_bytes(dsc_pubkey_x);
    let y: BrainpoolP256r1_Fq = BrainpoolP256r1_Fq::from_be_bytes(dsc_pubkey_y);
    let r: BrainpoolP256r1_Fr = BrainpoolP256r1_Fr::from_be_bytes(sod_signature_r);
    let s: BrainpoolP256r1_Fr = BrainpoolP256r1_Fr::from_be_bytes(sod_signature_s);
    assert(verify_brainpoolp256r1_ecdsa(x, y, msg_hash, (r, s)));

    let mut key: [u8; 64] = [0; 64];
    for i in 0..32 {
        key[i] = dsc_pubkey_x[i];
        key[32 + i] = dsc_pubkey_y[i];
    }
    let commitment = poa_core::dsc_commitment(poa_core::CURVE_TAG_BRAINPOOL_P256R1, key);
    poa_core::finalize(dg1, commitment, current_date, address)
}
