// --- hash-binding tests ------------------------------------------------------
//
// Each builds a well-formed DG1 → eContent → signedAttrs chain and then breaks
// exactly one link. The attack cases are the ones the length and prefix
// checks exist for: a hash parked past the hashed length, and a DG1 short
// enough to leave the MRZ fields it is read for unhashed.

use crate::{
    DG1_HEADER, DG1_LEN, DG1_MAX, dg1_hash_prefix, ecdsa_digest, hash, hash_and_bind,
    message_digest_prefix, modulus_be_bytes,
};

#[test]
fn binding_accepts_well_formed_chain() {
    let (dg1, ec, ec_len, dg1_off, sa, sa_len, ec_off) = test_chain(40, 24);
    let _ = bind256(dg1, DG1_LEN, ec, ec_len, dg1_off, sa, sa_len, ec_off);
}

#[test]
fn binding_accepts_mixed_hashes() {
    // SHA-512 data-group hashes and messageDigest, SHA-1 signature hash: the
    // prefixes follow each digest's length.
    let dg1 = test_dg1();
    let (ec, ec_len, dg1_off) = test_embed(hash::sha512(dg1, DG1_LEN), dg1_hash_prefix::<64>(), 40);
    let (sa, sa_len, ec_off) =
        test_embed(hash::sha512(ec, ec_len), message_digest_prefix::<64>(), 24);
    let d = hash_and_bind(
        dg1,
        DG1_LEN,
        ec,
        ec_len,
        dg1_off,
        sa,
        sa_len,
        ec_off,
        hash::sha512,
        hash::sha512,
        hash::sha1,
    );
    assert(d == hash::sha1(sa, sa_len));
}

#[test(should_fail)]
fn binding_rejects_a_digest_under_another_hash() {
    // SHA-256 entries, read as SHA-224: the prefix length byte differs.
    let dg1 = test_dg1();
    let (ec, ec_len, dg1_off) = test_embed(hash::sha256(dg1, DG1_LEN), dg1_hash_prefix::<32>(), 40);
    let (sa, sa_len, ec_off) =
        test_embed(hash::sha256(ec, ec_len), message_digest_prefix::<32>(), 24);
    let _ = hash_and_bind(
        dg1,
        DG1_LEN,
        ec,
        ec_len,
        dg1_off,
        sa,
        sa_len,
        ec_off,
        hash::sha224,
        hash::sha256,
        hash::sha256,
    );
}

#[test(should_fail)]
fn binding_rejects_econtent_hash_past_signed_length() {
    let (dg1, ec, ec_len, dg1_off, sa, sa_len, ec_off) = test_chain(40, 24);
    // Move the eContent hash (and its prefix) out of the signed bytes.
    let mut forged = sa;
    let moved = sa_len + 15;
    for i in 0..(15 + 32) {
        forged[moved - 15 + i] = sa[ec_off - 15 + i];
    }
    let _ = bind256(dg1, DG1_LEN, ec, ec_len, dg1_off, forged, sa_len, moved);
}

#[test(should_fail)]
fn binding_rejects_dg1_hash_past_econtent_length() {
    let (dg1, ec, ec_len, dg1_off, sa, sa_len, ec_off) = test_chain(40, 24);
    let mut forged = ec;
    let moved = ec_len + 7;
    for i in 0..(7 + 32) {
        forged[moved - 7 + i] = ec[dg1_off - 7 + i];
    }
    let _ = bind256(dg1, DG1_LEN, forged, ec_len, moved, sa, sa_len, ec_off);
}

#[test(should_fail)]
fn binding_rejects_short_dg1() {
    let dg1 = test_dg1();
    // Rebuild the chain over a 60-byte DG1: document number onwards unhashed.
    let (ec, ec_len, dg1_off) = test_embed(hash::sha256(dg1, 60), dg1_hash_prefix::<32>(), 40);
    let (sa, sa_len, ec_off) =
        test_embed(hash::sha256(ec, ec_len), message_digest_prefix::<32>(), 24);
    let _ = bind256(dg1, 60, ec, ec_len, dg1_off, sa, sa_len, ec_off);
}

#[test(should_fail)]
fn binding_rejects_hash_without_its_der_prefix() {
    let dg1 = test_dg1();
    // The DG1 hash is present and inside the hashed bytes, but not as DG1's
    // DataGroupHash entry.
    let wrong: [u8; 7] = [0x30, 0x25, 0x02, 0x01, 0x0B, 0x04, 0x20];
    let (ec, ec_len, dg1_off) = test_embed(hash::sha256(dg1, DG1_LEN), wrong, 40);
    let (sa, sa_len, ec_off) =
        test_embed(hash::sha256(ec, ec_len), message_digest_prefix::<32>(), 24);
    let _ = bind256(dg1, DG1_LEN, ec, ec_len, dg1_off, sa, sa_len, ec_off);
}

#[test(should_fail)]
fn binding_rejects_wrong_dg1_header() {
    let mut dg1 = test_dg1();
    dg1[0] = 0x6B; // DG11's tag
    let (ec, ec_len, dg1_off) = test_embed(hash::sha256(dg1, DG1_LEN), dg1_hash_prefix::<32>(), 40);
    let (sa, sa_len, ec_off) =
        test_embed(hash::sha256(ec, ec_len), message_digest_prefix::<32>(), 24);
    let _ = bind256(dg1, DG1_LEN, ec, ec_len, dg1_off, sa, sa_len, ec_off);
}

#[test]
fn prefixes_follow_the_digest_length() {
    assert(dg1_hash_prefix::<32>() == [0x30, 0x25, 0x02, 0x01, 0x01, 0x04, 0x20]);
    assert(dg1_hash_prefix::<20>() == [0x30, 0x19, 0x02, 0x01, 0x01, 0x04, 0x14]);
    assert(dg1_hash_prefix::<64>() == [0x30, 0x45, 0x02, 0x01, 0x01, 0x04, 0x40]);
    let md = message_digest_prefix::<48>();
    assert((md[12] == 0x32) & (md[13] == 0x04) & (md[14] == 0x30));
}

#[test]
fn ecdsa_digest_truncates_and_extends() {
    let d: [u8; 3] = [1, 2, 3];
    assert(ecdsa_digest::<3, 5>(d) == [0, 0, 1, 2, 3]);
    assert(ecdsa_digest::<3, 2>(d) == [1, 2]);
}

#[test]
fn modulus_bytes_from_limbs() {
    // 0x0102..1f (31 bytes) in 120-bit limbs: low limb holds the last 15.
    let mut be: [u8; 31] = [0; 31];
    for i in 0..31 {
        be[i] = (i + 1) as u8;
    }
    let mut lo: u128 = 0;
    for i in 16..31 {
        lo = lo * 256 + be[i] as u128;
    }
    let mut mid: u128 = 0;
    for i in 1..16 {
        mid = mid * 256 + be[i] as u128;
    }
    let limbs: [u128; 3] = [lo, mid, be[0] as u128];
    assert(modulus_be_bytes::<3, 31>(limbs) == be);
}

#[test(should_fail)]
fn modulus_bytes_reject_an_oversized_limb() {
    let limbs: [u128; 3] = [1 << 120, 0, 1];
    let _ = modulus_be_bytes::<3, 31>(limbs);
}

#[test(should_fail)]
fn modulus_bytes_reject_an_oversized_top_limb() {
    let limbs: [u128; 3] = [0, 0, 256];
    let _ = modulus_be_bytes::<3, 31>(limbs);
}

fn bind256(
    dg1: [u8; DG1_MAX],
    dg1_len: u32,
    ec: [u8; 200],
    ec_len: u32,
    dg1_off: u32,
    sa: [u8; 200],
    sa_len: u32,
    ec_off: u32,
) -> [u8; 32] {
    hash_and_bind(
        dg1,
        dg1_len,
        ec,
        ec_len,
        dg1_off,
        sa,
        sa_len,
        ec_off,
        hash::sha256,
        hash::sha256,
        hash::sha256,
    )
}

#[test]
fn test_embed_places_hash_after_prefix() {
    let h: [u8; 32] = [7; 32];
    let (buf, len, off) = test_embed(h, dg1_hash_prefix::<32>(), 40);
    assert(off == 40);
    assert(len == 40 + 32 + 8);
    assert(buf[39] == 0x20);
    assert(buf[40] == 7);
}

fn test_embed<let P: u32, let H: u32>(
    hash: [u8; H],
    prefix: [u8; P],
    offset: u32,
) -> ([u8; 200], u32, u32) {
    let mut buf: [u8; 200] = [0; 200];
    for i in 0..200 {
        buf[i] = (i * 7) as u8;
    }
    for i in 0..P {
        buf[offset - P + i] = prefix[i];
    }
    for i in 0..H {
        buf[offset + i] = hash[i];
    }
    (buf, offset + H + 8, offset)
}

fn test_dg1() -> [u8; DG1_MAX] {
    let mut dg1: [u8; DG1_MAX] = [0x3C; DG1_MAX]; // '<'
    for i in 0..5 {
        dg1[i] = DG1_HEADER[i];
    }
    dg1
}

fn test_chain(
    dg1_offset: u32,
    ec_offset: u32,
) -> ([u8; DG1_MAX], [u8; 200], u32, u32, [u8; 200], u32, u32) {
    let dg1 = test_dg1();
    let (ec, ec_len, dg1_off) =
        test_embed(hash::sha256(dg1, DG1_LEN), dg1_hash_prefix::<32>(), dg1_offset);
    let (sa, sa_len, ec_off) =
        test_embed(hash::sha256(ec, ec_len), message_digest_prefix::<32>(), ec_offset);
    (dg1, ec, ec_len, dg1_off, sa, sa_len, ec_off)
}

