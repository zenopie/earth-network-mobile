//! lean_poa_rsa4096 — passport register circuit for an RSA-4096 (PKCS#1 v1.5,
//! SHA-256, e=65537) Document Signer. Identical to lean_poa except the SOD
//! signature is RSA and the DSC leaf is Poseidon2 over the modulus big-endian
//! bytes (matching the chain's canonical RSA pubkey). Shared logic in poa_core.

use bignum::params::BigNumParams;
use bignum::RuntimeBigNum;
use rsa::rsa::verify_sha256_pkcs1v15;

fn main(
    dg1: [u8; 95],
    dg1_len: u32,
    e_content: [u8; 200],
    e_content_len: u32,
    dg1_hash_offset: u32,
    signed_attrs: [u8; 200],
    signed_attrs_len: u32,
    econtent_hash_offset: u32,
    dsc_modulus: [u128; 35], // RSA modulus limbs (noir-bignum layout)
    dsc_redc: [u128; 35],    // Barrett reduction parameter
    sod_signature: [u128; 35],
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

    let params: BigNumParams<35, 4096> = BigNumParams::new(false, dsc_modulus, dsc_redc);
    let sig: RuntimeBigNum<35, 4096> = RuntimeBigNum::from_array(params, sod_signature);
    assert(verify_sha256_pkcs1v15(msg_hash, sig, 65537));

    // Canonical big-endian modulus bytes = chain's RSA canonical pubkey.
    let modulus: RuntimeBigNum<35, 4096> = RuntimeBigNum::from_array(params, dsc_modulus);
    let commitment = poa_core::dsc_commitment(modulus.to_be_bytes());
    poa_core::finalize(dg1, commitment, current_date)
}
