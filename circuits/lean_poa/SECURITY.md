# lean_poa — security review & input spec

Status: circuit complete + validated — accepts a valid witness, rejects a
tampered signature, and rejects an expired passport; `bb verify` passes. This is a
self-review — a **professional ZK/crypto audit is still required before it guards
real value.**

**Variants (workspace):** the shared logic (hash binding, registry membership,
expiry, name‖DOB nullifier) lives in `../poa_core`; each binary differs only in the
SOD→DSC signature verify + DSC-leaf encoding, and all share the public interface
`[registry_root, current_date] → nullifier`:
| circuit | DSC algorithm | gates | leaf |
|---|---|---|---|
| `lean_poa` | ECDSA-P256 (std secp256r1) | 108k | Poseidon2(x‖y) |
| `lean_poa_rsa2048` | RSA-2048 PKCS#1v1.5 SHA-256 | 90k | Poseidon2(modulus BE) |
| `lean_poa_rsa4096` | RSA-4096 PKCS#1v1.5 SHA-256 | 131k | Poseidon2(modulus BE) |
| `lean_poa_p384` | ECDSA-P384 | 270k | Poseidon2(x‖y) |
| `lean_poa_brainpool256` | ECDSA-brainpoolP256r1 | 158k | Poseidon2(x‖y) |
| `lean_poa_brainpool384` | ECDSA-brainpoolP384r1 | 270k | Poseidon2(x‖y) |
| `lean_poa_brainpool512` | ECDSA-brainpoolP512r1 | 431k | Poseidon2(x‖y) |
All seven `bb verify` and verify through the chain's UltraHonk verifier
(`zk/ultrahonk`, `TestRegisterVariantProofs`). The RSA leaf
(`RuntimeBigNum.to_be_bytes`) and the EC leaf (`x‖y`) match the chain's canonical
pubkey (`certs.CanonicalBytes`), so on-chain-built inclusion proofs are valid for
the matching circuit. The caretaker selects the circuit by `signature_algorithm`.
The non-P256 ECDSA curves use `zkpassport/noir-ecdsa` (over `noir_bigcurve`); like
Noir's std ECDSA they enforce **low-s**, so the input-gen normalizes `s = min(s,
n−s)` per curve. (P-521 DSCs are rare and not yet built; same pattern.)

## What the circuit proves
Given a passport's DG1 + SOD, it proves in zero knowledge:
1. `sha256(dg1)` is embedded in `e_content` (the LDS security object),
2. `sha256(e_content)` is embedded in the signed attributes,
3. those signed attributes are ECDSA-P256 signed by a Document Signer key,
4. that DSC key is a Poseidon2-Merkle leaf under the public `registry_root`,
5. the passport's MRZ expiry date is `>= current_date` (public input),
and outputs `nullifier = Poseidon2(name ‖ DOB)`.

Trust reduces to: **the registry contains only genuine government DSC keys**
(inherited ICAO PKI trust), the holder can't forge a DSC signature, and the
chain pins `current_date` to ~today (see finding #7).

## Findings
1. **Nullifier stability — RESOLVED.** The nullifier is now
   `Poseidon2(name ‖ DOB)` over the MRZ name field (DG1[10..49], 39 bytes) and
   date of birth (DG1[62..68], YYMMDD). It excludes the passport number, so a
   renewed passport yields the **same** nullifier — one person, one registration.
2. **Expiry check — RESOLVED.** `main` takes a `current_date: pub u32` (YYMMDD)
   and asserts the MRZ expiry (DG1[70..76], parsed YYMMDD) `>= current_date`.
   Expired passports fail to prove (validated: a future `current_date` is
   rejected). See finding #7 for how the chain must constrain `current_date`.
7. **`current_date` must be chain-pinned — IMPLEMENTED (chain side).** The prover
   supplies `current_date`; nothing in-circuit forces it to be today. The caretaker
   verifier (`verifyRegistrationProof`) now rejects a proof whose public
   `current_date` isn't within `params.current_date_max_skew_seconds` of the block
   time (default 2 days; `current_date_index=1` for this circuit). Without it a
   holder backdates `current_date` to pass an expired passport. (Same pattern as
   `registry_root == params.dsc_root`.)
8. **ECDSA low-s is mandatory (input-gen).** Noir's `std::ecdsa_secp256r1`
   `verify_signature` **enforces low-s** (rejects `s > n/2` as malleable). ICAO
   DSC signatures are NOT required to be low-s, so ~half of real passports would
   fail unless normalized. The input-gen MUST emit `s' = min(s, n - s)`; `(r, n-s)`
   is an equally valid signature for the same message/key. (Validated: high-s sigs
   fail, normalized sigs pass. This was the root cause of intermittent
   `Cannot satisfy constraint` at the verify step.)
3. **Registry freshness / revocation.** `registry_root` must be kept current and
   support revocation, or a revoked DSC stays valid. Needs a registry update path
   + the chain pinning a recent root.
4. **eContent structure.** The circuit checks `dg1_hash` sits *at an offset* in
   `e_content` but doesn't fully validate the LDS ASN.1 structure. Safe only
   because a valid DSC signature over the chain is unforgeable — but an auditor
   should confirm no offset/aliasing attack lets a crafted `e_content`/
   `signed_attrs` bind an unintended DG1.
5. **Under-constrained checks.** Standard ZK audit: confirm every `assert` and the
   Merkle/hash-at-offset logic is fully constrained (no free witness values).
6. **One algorithm.** Covers ECDSA-P256 only; RSA/other-curve passports need
   sibling circuits (same structure, different `verify_signature`).

## Input spec (what the Kotlin input-gen must emit — validated against the circuit)
| field | type | source |
|---|---|---|
| `dg1` | `[u8;95]` | jMRTD EF.DG1 bytes, zero-padded to 95 |
| `dg1_len` | u32 | real EF.DG1 length (TD3 = 93); only these bytes are hashed |
| `e_content` | `[u8;200]` | SOD encapContentInfo (LDS security object), zero-padded |
| `e_content_len` | u32 | its real length |
| `dg1_hash_offset` | u32 | index of `sha256(dg1)` within `e_content` |
| `signed_attrs` | `[u8;200]` | SOD SignerInfo signedAttrs DER (SET form), padded |
| `signed_attrs_len` | u32 | its real length |
| `econtent_hash_offset` | u32 | index of `sha256(e_content)` within `signed_attrs` |
| `dsc_pubkey_x/y` | `[u8;32]` | DSC EC public key coords (BouncyCastle) |
| `sod_signature` | `[u8;64]` | SOD signature r‖s (from DER, big-endian), **s low-s normalized** |
| `registry_root` | Field (pub) | published DSC-registry root (chain checks == dsc_root) |
| `merkle_path_bits` | `[bool;16]` | DSC leaf's path in the registry tree |
| `merkle_siblings` | `[Field;16]` | sibling nodes from the registry data |
| `current_date` | u32 (pub) | YYMMDD "today"; **chain must pin to ~block time** (finding #7) |

Public outputs (in order): `registry_root`, `current_date`, then the returned
`nullifier`.

Registry leaf = `Poseidon2(dsc_pubkey_x‖dsc_pubkey_y as 64 field bytes)`; tree is a
binary Poseidon2 Merkle tree, depth 16. The registry builder (offline) and the
circuit use the same Poseidon2 (validated: `@zkpassport/poseidon2` == Noir's).
The app fetches siblings from registry data — **no Poseidon2 needed in Kotlin.**
ECDSA notes (validated): sign the digest directly and pass that same digest;
signature is compact `r‖s` (not DER), coords big-endian; **`s` must be
normalized to low-s** (`s' = min(s, n − s)`) or Noir rejects it (finding #8).
MRZ offsets in DG1 (TD3, 5-byte header): name = `[10..49]`, DOB = `[62..68]`,
expiry = `[70..76]`.
