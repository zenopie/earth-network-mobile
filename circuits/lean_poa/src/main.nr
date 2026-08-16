//! lean_poa — passport register circuit for an ECDSA-P256 Document Signer.
//! Verifies the SOD signature (secp256r1) and binds the signing DSC publicly;
//! all shared logic lives in poa_core. Public input: current_date;
//! outputs: (name‖DOB nullifier, dsc_key commitment).

use std::ecdsa_secp256r1::verify_signature;

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
    sod_signature: [u8; 64],
    current_date: pub u32,
) -> pub (Field, Field) {
    let msg_hash = poa_core::hash_and_bind(
        dg1,
        dg1_len,
        e_content,
        e_content_len,
        dg1_hash_offset,
        signed_attrs,
        signed_attrs_len,
        econtent_hash_offset,
    );
    assert(verify_signature(dsc_pubkey_x, dsc_pubkey_y, sod_signature, msg_hash));

    let mut key: [u8; 64] = [0; 64];
    for i in 0..32 {
        key[i] = dsc_pubkey_x[i];
        key[32 + i] = dsc_pubkey_y[i];
    }
    let commitment = poa_core::dsc_commitment(key);
    poa_core::finalize(dg1, commitment, current_date)
}
