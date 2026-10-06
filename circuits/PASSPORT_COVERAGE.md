# Passport signature coverage

Status: **implemented (2026-10-04)** as designed in section 7, with option
(b) for app size: the 16 variants of the 2^18 tier are bundled and the other
17 are fetched on demand. Sources of truth: `variants.json` (the variants),
`tools/variants.py` (their mains, fixtures and placement),
`tools/passportgen/reference.py` (variant selection and witness, which both
wallets match byte for byte). Measured gate counts and tiers are in
`variants.json`.

Goal: registration should accept every signature scheme that real, unexpired
passports use (TD3 documents, valid in October 2026 or later).

## 1. Sources

| # | Source | What it gives |
| --- | --- | --- |
| S1 | ICAO Doc 9303 Part 12, 8th ed. (2021), §4.1.1, §4.1.6 ([pdf](https://www.icao.int/sites/default/files/publications/DocSeries/9303_p12_cons_en.pdf)) | Permitted algorithms: RSA (RFC 4055, PKCS#1 v1.5 and PSS; PSS recommended), DSA (FIPS 186-4), ECDSA (X9.62 / ISO 15946, **explicit domain parameters required**, TR-03111 recommended). Hashes: "SHA-224, SHA-256, SHA-384 and SHA-512 are the only permitted hashing algorithms" (SHA-1 was allowed by earlier editions). DS private-key use up to 3 months, DS certificate valid about 10 years (Table 1). |
| S2 | Self (formerly OpenPassport) coverage map data: `registry/outputs/map_dsc.json` and `map_csca.json` at selfxyz/self@52dba27 (the files [map.self.xyz](https://map.self.xyz) renders). Built from the ICAO PKD DSC list and master list, keeping certificates whose notAfter + 10 y (DSC) or + 20 y (CSCA) is in the future. | 20,206 unexpired DSCs from 56 States, 522 CSCAs from 129 States: key type, size, exponent or curve, and the hash of the certificate's own signature. |
| S3 | Self register circuits, `circuits/circuits/register/instances/` at selfxyz/self HEAD (2026-09). Self adds an instance when a real user's passport needs it (commits 8fb6265, b10667f "missing rsapss instance … for Denmark", 5cea47e, ec732da). | Hash triples seen in real SODs: DG hash, eContent (messageDigest) hash, signed-attributes hash, with signature scheme. |
| S4 | Rarimo `passport-zk-circuits`, `circuits/signatureVerifier/signatureVerification.circom` (HEAD 2026-10) | Signature schemes Rarimo met in production: adds RSA-3072 SHA-1 e=37187, brainpoolP320r1, secp192r1. |
| S5 | zkPassport `circuits` (HEAD 2026-09-15): `src/noir/bin/sig-check`, `data-check/integrity` | Supports RSA/PSS 1024–4096, P-192…P-521, Brainpool 192–512, every SHA-1/224/256/384/512 pairing of DG and signed-attribute hash. eContent max 700 bytes, signed attributes max 256. |
| S6 | Self `common/src/constants/constants.ts` | Padded eContent maxima Self needed (SHA-1 384, SHA-224/256 512, SHA-384 768, SHA-512 896 bytes); signed attributes 128 or 256. |
| S7 | BSI TR-03116-2 ([pdf](https://www.bsi.bund.de/SharedDocs/Downloads/DE/BSI/Publikationen/TechnischeRichtlinien/TR03116/BSI-TR-03116-2.pdf)) | German and EU documents: Brainpool curves (RFC 5639) at 256/384/512 bits. Older German documents used brainpoolP224r1. |

The PKD only lists DSCs that States upload: 56 States, not including India,
Russia, the Netherlands, Poland or Turkey. For those, S2's CSCA list and
S3/S4 (real users) stand in. The S2 "hash" column is the CSCA's signature on
the DSC, not the SOD's. Where the SOD hash differs, S3 shows it.

## 2. What varies in a passport signature

Passive authentication has three hash slots and one signature:

1. **DG hash** (`LDSSecurityObject.hashAlgorithm`): the hash of DG1 stored in eContent.
2. **eContent hash** (`SignerInfo.digestAlgorithm`): the `messageDigest` signed attribute.
3. **Signature hash** (from `SignerInfo.signatureAlgorithm`, or `digestAlgorithm` when the
   algorithm is bare `rsaEncryption` / `id-ecPublicKey`): the hash of the DER signed
   attributes that the DSC signs.
4. **Signature**: the DSC key (RSA modulus size and exponent, or curve) and, for
   RSA, the padding (PKCS#1 v1.5, or PSS with its hash, MGF1 hash and salt length).

S3 shows all three hashes can differ (for example SHA-512 / SHA-512 / SHA-256
with RSA). Below, a **hash profile** is the triple (DG, eContent, signature):

| Profile | DG | eContent | Signature | Seen in |
| --- | --- | --- | --- | --- |
| A | SHA-256 | SHA-256 | SHA-256 | Every family; most passports |
| B | SHA-1 | SHA-1 | SHA-1 | S3 (RSA, P-256, BP224), S4 |
| C | SHA-1 | SHA-256 | SHA-256 | S3 (RSA) |
| D | SHA-256 | SHA-1 | SHA-1 | S3 (RSA) |
| E | SHA-224 | SHA-224 | SHA-224 | S3 (BP224) |
| F | SHA-256 | SHA-224 | SHA-224 | S3 (P-224) |
| G | SHA-256 | SHA-256 | SHA-224 | S3 (P-224) |
| H | SHA-384 | SHA-384 | SHA-384 | S3 (P-384, BP384, BP512, PSS-2048) |
| I | SHA-512 | SHA-512 | SHA-256 | S3 (RSA, PSS-2048) |
| J | SHA-512 | SHA-512 | SHA-512 | S3 (RSA, PSS-2048, BP512, P-521) |

## 3. Coverage table

DSC share is the share of the 20,206 unexpired PKD DSCs (S2). It counts
certificates, not passports, and a State that rotates DSCs often weighs more.
"Now" is the seven variants on the branch today. "Proposed" is section 7.

### 3.1 RSA

| Key | Exponent | Padding | DSC share | States (PKD) | Profiles | Now | Proposed |
| --- | --- | --- | --- | --- | --- | --- | --- |
| RSA-2048 | 65537 | PKCS#1 v1.5 | 74.5% | AR AU BJ CN CO ES FI FR GB HU ID IE IS IT LU MA MD MX NG NO NZ PA QA RO SG SK TH TM UN US UZ | A, B, C, D, I, J | A only | A B C D I J |
| RSA-2048 | 3 | PKCS#1 v1.5 | 2.2% | IE | A | no | yes (any e) |
| RSA-2048 | random odd 33k–65k | PKCS#1 v1.5 | 0.1% | AT | A | no | yes (any e) |
| RSA-2048 | 65537, 3 | PSS (salt 32, 64) | 7.6% + 1.8% | BH CA CN CZ DK EU IT JP KR LU MN MY SE SG TZ | A, H, I, J | no | A H I J |
| RSA-3072 | 65537, random odd | PKCS#1 v1.5 | 0.7% | AT FI IR NP | A, B (S4: SHA-1 e=37187) | no | A B |
| RSA-3072 | 65537, 3 | PSS | 1.3% | BZ CA SE (+ RS, TJ CSCAs: PSS SHA-384) | A, H | no | A H |
| RSA-4096 | 65537, 3, 64321 | PKCS#1 v1.5 | 0.9% | BG CM IS UA | A, B, I, J | A only | A B I J |
| RSA-4096 | 65537 | PSS | <0.1% | S3 | A | no | A |
| RSA-1024 | 65537 | PKCS#1 v1.5 | 0.2% | ZZ (ICAO test) | – | no | **excluded** |

### 3.2 ECDSA

| Curve | DSC share | States | Profiles | Now | Proposed |
| --- | --- | --- | --- | --- | --- |
| P-256 (secp256r1) | 8.2% | BJ GB HU NZ RW (+ IN, RU, BE, SA CSCAs) | A, B | A only | A B |
| brainpoolP256r1 | 1.8% | AE AT CH CN DE TH UA | A | A | A |
| brainpoolP224r1 | 0.4% | AE DE (pre-2017 German passports, valid until about 2027) | E, B | no | E B |
| P-384 (secp384r1) | 0.3% | AE IQ JP | A, H | A only | A H |
| brainpoolP512r1 | 0.1% | BR FI (+ CH, DE, VN CSCAs) | H, J | **no** (the circuit is SHA-256) | H J |
| brainpoolP384r1 | <0.1% | VN (+ 13 States' CSCAs) | A, H | A only | A H |
| P-224 (secp224r1) | not in PKD | S3 users | F, G | no | F G |
| P-521 (secp521r1) | not in PKD | S3 users (+ HU IQ IS TR ZW CSCAs) | J | no | J |
| brainpoolP320r1 | none | S4 only | – | no | **excluded** |
| secp192r1 | none | S4 only (SHA-1) | – | no | **excluded** |
| Brainpool t1 (twisted) curves | none | – | – | no | **excluded** |

### 3.3 Coverage, roughly

- **Now:** about 83% of PKD DSCs by key and scheme (RSA-2048/4096 PKCS#1 e=65537,
  P-256, BP256, P-384 and BP384, all with SHA-256). It is less in practice
  because of the eContent cap (section 6).
- **Proposed:** 99.8% of PKD DSCs. Every key family in the PKD is covered except
  the ICAO RSA-1024 specimen signer (ZZ). Every hash profile Self has needed
  for real users is covered (S3).
- By passports: every large issuer the data covers is fully covered: US, CN,
  GB, FR, DE, JP, CA, KR, IT, ES, AU, MX, BR and UA, and IN and RU through their
  CSCA families and S3. The PKD gives no per-passport counts, so this is not a
  precise share. The excluded rows account for well under 1% of the data.

## 4. Exponents and padding details

- **RSA exponent.** It is not always 65537. Ireland, Japan, Korea and China use
  e=3. Austria and Iran issue DSCs with random odd exponents between 33,225 and
  65,427 (about 60 DSCs). S3 has 64,321, and S2 has CSCAs with 107,903 to 127,485. A
  witness exponent in [2, 2^17) (noir_rsa v0.12.0 range-checks it) covers
  every one seen. It must be bound to the DSC (section 7.3).
- **PSS.** The salt length is 32 for SHA-256 in most cases and 64 for Denmark (S3),
  48 for SHA-384 and 64 for SHA-512. It is taken from `RSASSA-PSS-params` as a
  witness, at most `MAX_SALT_LEN`. The MGF1 hash equals the message hash in
  every certificate and SOD seen; a mismatch is refused (wallet message) rather
  than guessed. Trailer field 1 only.
- **ECDSA domain parameters.** ICAO requires *explicit* parameters (S1 §4.1.6.3),
  and many German, Swiss and Austrian DSCs carry them. Today Android reads
  only a named-curve OID and rejects explicit parameters. iOS matches them by
  group order, and the chain by field prime. All three should match the full
  set (p, a, b, G, n, h) against the supported curves.
- **ECDSA hash width.** The digest is truncated to the order's bit length, or
  zero-extended if it is shorter. noir-ecdsa v0.5.0 does this and accepts a digest
  of n or more (it is reduced later). That case is common on Brainpool, where
  n is about 0.66·2^256. v0.4.1, which the branch pins, parses the digest with
  `from_be_bytes`.

## 5. SHA-1: accepted

**Decision:** accept SHA-1 in all three hash slots (profiles B, C, D), and in the
CSCA→DSC signature the chain already verifies natively.

Evidence that it is needed: unexpired DSCs whose CSCA signature is SHA-1 exist
for AU, CN, DE (BP224), ES, FR, HU, IT and LU (S2). S3 added SHA-1 register
circuits in 2025 for real users (RSA-2048/4096, P-256, BP224). Passports last
10 years, so documents from SHA-1 signers issued in late 2016 to 2017 stay
valid into 2027.

Why it is safe here:

1. **Collisions do not apply.** SHAttered (2^63) and the chosen-prefix "SHA-1 is
   a Shambles" attack (2^63.4) need the victim to sign a message the attacker
   prepared. Every hashed object here is built by the issuer: DG1 (MRZ),
   eContent (a list of DG hashes), signed attributes and the DSC's TBS. The
   applicant influences only the name (MRZ alphabet `A–Z<`, 39 characters
   fixed-width, no room for collision blocks) and the DG2 photo. A DG2 collision
   could at most swap the photo, which the circuit never reads. In eContent the
   applicant controls only hash *outputs*, not bytes, so it cannot place
   collision blocks there either.
2. **A forgery needs a second preimage.** To register an invented MRZ, the prover
   must find DG1′ ≠ DG1 with SHA-1(DG1′) = SHA-1(DG1) (or the same for eContent or
   signed attributes). There is no practical second-preimage attack on SHA-1.
   The generic cost is 2^160, and the best known long-message bound is above
   2^100. The circuit uses the standard IV, so freestart collisions (practical
   for SHA-1) give the prover nothing.
3. **Choosing the variant gives the prover nothing.** A SHA-1 variant applied to a
   SHA-256 passport needs a 20-byte DG hash after the DER prefix
   `30 19 02 01 01 04 14` inside signed eContent, which is a preimage. PKCS#1
   v1.5 signs the hash OID in DigestInfo. For ECDSA, matching a 160-bit digest
   to a signed 256-bit one is a preimage.
4. The remaining risk is a malicious issuer, which SHA-256 does not stop either.

Sunset: SHA-1 variants are ordinary VK entries. Governance can remove them once
the last SHA-1-signed passport expires (estimated 2027–2028). Re-check the
second-preimage literature before relaunches.

## 6. Problems in the branch before this change (all fixed)

1. **The eContent cap of 200 bytes** rejects any SHA-256 SOD with more than four data
   groups (header about 23 bytes plus 39 per group). Many EU passports carry
   DG1, 2, 3 and 14, plus 11, 12 or 15. Self had to raise this limit for real users (S6,
   commit ec732da).
2. **Android's passport SRS is 2^18 + 1 points** (`PassportSrs`, `SRS_SIZE`), but
   `lean_poa_p384` and `lean_poa_brainpool384` (263,952 gates) and
   `lean_poa_brainpool512` (424,579) are dyadic 2^19. On Android they cannot be set
   up from the local SRS. iOS fetches 2^19 + 1.
3. **The pins predate the audits.** noir-bignum v0.10.0 (noir-lang, used through RSA)
   and v0.10.0-1 (zkpassport, used through ECDSA) do not have the 11 external audit
   fixes (noir-bignum#270, July 2026: `cmp` and `udiv_mod` unsound on
   non-canonical input, modulus size not validated, non-canonical
   serialization). zkpassport v0.10.0-2, noir_rsa v0.12.0 (PSS SHA-384
   out-of-bounds fix), noir-ecdsa v0.5.0 and noir_bigcurve v0.14.0-2 carry them.
   The current RSA modulus binding is still sound: `to_be_bytes` range-checks
   each limb to 15 bytes, so the bytes fix the integer. Under v0.10.0-2,
   `RuntimeBigNum::to_be_bytes` of the modulus fails, because n is not less than n. The new
   core must serialize the modulus limbs directly.
4. Only SHA-256, PKCS#1 v1.5 and e=65537 are supported: the gaps in section 3.

## 7. Design (implemented)

### 7.1 Structure

- `poa_core` becomes generic. `hash_and_bind<EC, SA, HD, HE, HS>(sod, dg_hash,
  ec_hash, sa_hash)` takes the three hash functions as `fn` values, which
  monomorphize with no dispatch cost. The DER prefixes derive from the digest
  length:

      DataGroupHash: 30 (HD+5) 02 01 01 04 HD
      messageDigest: 06 09 2A864886F70D010904 31 (HE+2) 04 HE

  The length and bound checks stay exactly as they are today (hash inside
  the hashed length, DG1 exactly 93 bytes with its header). `finalize` (expiry, nullifier, address
  assert) is unchanged.
- `poa_core::hash`: `sha1` (zac-williamson/sha1 v0.11), `sha224` and `sha256`
  (noir-lang/sha256 v0.3.0), `sha384` and `sha512` (zkpassport/sha512 v0.2.0), all `(msg: [u8; N],
  len: u32)`.
- `poa_core::sig`: `rsa_pkcs1<L, BITS>`, `rsa_pss<L, BITS>` (noir_rsa v0.12.0:
  witness exponent, witness salt length) and `ecdsa_<curve>` (noir-ecdsa v0.5.0;
  P-256 keeps the std blackbox, which is half the cost). Each returns the bytes
  for the DSC commitment.
- **Thin mains** (about 25 lines each), generated from one manifest
  `circuits/variants.json`: id, key, scheme, hash profile, `e_content_max`,
  `signed_attrs_max`, `log2_circuit_size`. The same file is bundled for both wallets and read by the
  fixture generators. A check fails if the generated mains differ from the
  committed ones.
- **The public interface is unchanged** for every variant:
  `[current_date, address] → (nullifier, dsc_key)`, with the same indices,
  registration binding, date pin and DSC/country exclusion. The chain keeps
  one path: `params.verifying_keys[signature_algorithm]`.

Buffer sizes are fixed per hash so that each one ends on a block boundary:

| Hash | eContent max (bytes / blocks / data groups) | Signed attributes max |
| --- | --- | --- |
| SHA-1 | 439 / 7 / 14 | 247 (4 blocks) |
| SHA-224, SHA-256 | 695 / 11 / 16 (all) | 256 |
| SHA-384, SHA-512 | 751 / 6 / 9 (SHA-512) to 12 (SHA-384) | 239 (2 blocks) |

zkPassport uses 700 bytes for every hash, and Self 879 for SHA-512 (S5, S6). The SHA-512
limit costs about 21k gates per 128-byte block (zkpassport/sha512), so 9
groups is the trade-off. A larger SOD gets the unsupported message.

### 7.2 Variants (33) and measured cost

Measured on the final circuits (nargo 1.0.0-beta.22, `bb gates`, bb 5.0.0;
written into `variants.json` by `tools/variants.py build`). Ids follow the pattern `lean_poa_<key>[_pss]_<profile>`, for example
`lean_poa_rsa2048_sha256` and `lean_poa_rsa4096_sha512_sha512_sha256`.

| Variant | Gates | Tier | Stripped JSON |
| --- | --- | --- | --- |
| `lean_poa_p256_sha256` | 138,556 | 2^18 (bundled) | 0.1 MB |
| `lean_poa_bp224_sha224` | 148,606 | 2^18 (bundled) | 0.8 MB |
| `lean_poa_p224_sha256_sha224_sha224` | 148,630 | 2^18 (bundled) | 0.8 MB |
| `lean_poa_p224_sha256_sha256_sha224` | 148,646 | 2^18 (bundled) | 0.8 MB |
| `lean_poa_rsa2048_sha256` | 151,014 | 2^18 (bundled) | 0.4 MB |
| `lean_poa_rsa2048_sha1_sha256_sha256` | 154,916 | 2^18 (bundled) | 0.6 MB |
| `lean_poa_bp256_sha256` | 188,206 | 2^18 (bundled) | 1.3 MB |
| `lean_poa_rsa3072_sha256` | 192,485 | 2^18 (bundled) | 0.6 MB |
| `lean_poa_rsa2048_pss_sha256` | 196,169 | 2^18 (bundled) | 0.4 MB |
| `lean_poa_p256_sha1` | 204,092 | 2^18 (bundled) | 1.4 MB |
| `lean_poa_bp224_sha1` | 214,174 | 2^18 (bundled) | 2.1 MB |
| `lean_poa_rsa2048_sha1` | 216,540 | 2^18 (bundled) | 1.7 MB |
| `lean_poa_rsa4096_sha256` | 249,588 | 2^18 (bundled) | 0.9 MB |
| `lean_poa_rsa2048_sha256_sha1_sha1` | 251,020 | 2^18 (bundled) | 1.9 MB |
| `lean_poa_rsa3072_pss_sha256` | 255,457 | 2^18 (bundled) | 0.6 MB |
| `lean_poa_rsa3072_sha1` | 258,016 | 2^18 (bundled) | 1.9 MB |
| `lean_poa_p384_sha256` | 300,920 | 2^19 | 2.1 MB |
| `lean_poa_bp384_sha256` | 300,920 | 2^19 | 2.1 MB |
| `lean_poa_rsa2048_sha512_sha512_sha256` | 306,477 | 2^19 | 2.4 MB |
| `lean_poa_rsa4096_sha1` | 315,119 | 2^19 | 2.2 MB |
| `lean_poa_rsa4096_pss_sha256` | 330,353 | 2^19 | 0.9 MB |
| `lean_poa_rsa2048_sha512` | 338,962 | 2^19 | 3.0 MB |
| `lean_poa_rsa2048_pss_sha512_sha512_sha256` | 351,629 | 2^19 | 2.4 MB |
| `lean_poa_rsa4096_sha512_sha512_sha256` | 405,076 | 2^19 | 2.9 MB |
| `lean_poa_rsa4096_sha512` | 437,569 | 2^19 | 3.4 MB |
| `lean_poa_rsa2048_pss_sha512` | 472,248 | 2^19 | 4.3 MB |
| `lean_poa_p384_sha384` | 488,731 | 2^19 | 4.6 MB |
| `lean_poa_bp384_sha384` | 488,731 | 2^19 | 4.6 MB |
| `lean_poa_rsa2048_pss_sha384` | 521,515 | 2^19 | 4.8 MB |
| `lean_poa_rsa3072_pss_sha384` | 612,633 | 2^20 | 5.5 MB |
| `lean_poa_bp512_sha384` | 649,426 | 2^20 | 5.9 MB |
| `lean_poa_bp512_sha512` | 649,564 | 2^20 | 5.9 MB |
| `lean_poa_p521_sha512` | 659,250 | 2^20 | 5.6 MB |

- **2^18**: 16 variants, nearly every passport (RSA-2048 SHA-256 alone is about 75% of DSCs).
- **2^19**: 13 variants.
- **2^20**: 4 variants. In each, SHA-384/512 dominates (MGF1 for PSS, or eContent)
  together with a 512/521-bit curve.

None fits the bundled 2^15 + 1 privacy SRS, and the current passport variants
don't either (83k–425k gates). The passport SRS stays fetched, but sized to
the selected circuit: 2^18 + 1 (16 MiB), 2^19 + 1 (32 MiB) or 2^20 + 1 (64 MiB), each with
a pinned hash. This also fixes problem 2.

**Proving cost.** Measured with `bb prove` (UltraHonk ZK, noir-recursive) on an Apple M2 with 8 GB RAM,
using the prototypes and synthetic signed SODs. All proofs are 14,656 bytes, so
the chain's length check is unchanged.

| Dyadic | M2 time | Peak RSS | Phone estimate |
| --- | --- | --- | --- |
| 2^18 | 1.7 s | 0.27–0.32 GB | 3–8 s |
| 2^19 | 3.6–4.0 s | 0.55–0.62 GB | 6–16 s |
| 2^20 | 6.4–8.7 s | 1.05–1.15 GB | 13–35 s; about 1.2 GB, too much for 3 GB Android devices |

The phone figures are estimates (about 2× M2 for flagships, 4× for mid-range).
The ProverGate passport gate and `LeanPoaDeviceTest` measure them during
implementation.

**App size.** The 33 circuits are 112 MB of JSON, or 78 MB of bytecode only
(`debug_symbols` and `file_map` are not needed to prove), about 55 MB gzipped.
Today the circuits are 14 MB. Options:

- (a) bundle all of them, stripped: about +55 MB per app.
- (b) **recommended:** bundle the 16 2^18 variants (16 MB stripped, about 11 MB gzipped) and fetch the
  17 long-tail variants on demand, pinned by sha256 in the bundled manifest,
  together with their SRS. MsgRegister already makes the variant public, so the
  fetch leaks nothing new.
- (c) fetch every variant on demand.

Decided: (b).

### 7.3 Chain (x/pki, x/personhood)

- New curve tags, appended: `P224 = 8`, `BrainpoolP224r1 = 9`. Curves in
  `certs/curves.go`. Explicit parameters are matched on (p, a, b, G, n), not on p.
- **The RSA exponent is bound to the DSC.** New tag `RSA_E = 10`, preimage `[10, e,
  modulus bytes…]`. Tag 7 is retired and never reused. Without this, the
  witness exponent would rest on the RSA assumption for an e the DSC never
  uses. That is believed hard for every e in [2, 2^17), but binding makes the
  statement exact.
- CSCA→DSC (native Go): add `sha224WithRSAEncryption`. For PSS, refuse an MGF1
  hash that differs from the message hash and a trailer field other than 1
  (Go's `VerifyPSS` assumes both). SHA-1 stays accepted (section 5). CSCA keys up to
  6144 bits and odd exponents up to 127,485 are already handled by `crypto/rsa`.
- `params.verifying_keys`: 33 entries in genesis, written by `privacy-vks.sh`.
  `signature_algorithm` stays the VK key, with no new msg fields.
- Fixtures: `tools/poafixtures` covers every variant and signs synthetic SODs
  with real algorithm parameters.

### 7.4 Wallets (Android first, iOS byte-identical)

- Parse `LDSSecurityObject.hashAlgorithm`, `SignerInfo.digestAlgorithm`,
  `signatureAlgorithm` (with `RSASSA-PSS-params`: hash, MGF1 hash, salt,
  trailer) and the DSC SPKI (named or explicit curve; RSA n and e).
- Map the 5-tuple to a variant id through `variants.json`. Anything unmapped
  raises `UnsupportedPassport(scheme)` and shows "This passport's signature type
  isn't supported yet (RSA-PSS 3072, SHA-512)", not a generic proving error.
  The same applies to explicit parameters that match no curve, a mismatched MGF1, missing
  signed attributes, or an eContent or signed-attributes size over the maximum.
- Witness: padding to the variant's maxima, RSA limbs, `redc`, exponent, PSS salt
  length, ECDSA low-s and coordinate widths. SRS sized from the manifest.
- Shared fixtures per variant on both platforms.

### 7.5 Backend

`services/dsccommit.py` mirrors the new tags (P-224, BP224, RSA with exponent)
and full-parameter curve matching, so the reserved lane still recognizes
every DSC. The precheck and lane do not depend on the variant
(`signature_algorithm` is passed through).

### 7.6 Tests

Each variant gets nargo tests from generated vectors: one positive case, and negative
cases for a wrong hash (another profile's digest), wrong padding (PKCS#1 vs PSS,
bad salt), a tampered DG1 and a tampered signature. The library pins are
checked against NIST CAVP (SHA) and Wycheproof (RSA PKCS#1, RSA-PSS, ECDSA per
curve and hash) vectors. Brainpool needs a vector whose digest is at least n.

## 8. Excluded

| What | Why |
| --- | --- |
| RSA-1024 | Only the ICAO specimen signer (ZZ) uses it. About 80-bit security is within reach of a well-funded attacker, and accepting it would let that attacker mint registrations. |
| brainpoolP320r1, secp192r1 | No unexpired PKD DSC (S2), no S3 user, and noir_bigcurve has no P320 curve. secp192r1 (about 96-bit security, SHA-1 only) is too weak to trust. |
| Brainpool twisted (t1) curves, P-192, BP192 | Not seen in any source's real data. |
| DSA | Permitted by ICAO, but no DSC in the PKD uses it. |
| RSA-PSS with SHA-1, or with an MGF1 hash that differs from the message hash | Not seen. Refused with the unsupported message. |
| Hash profiles not seen in S3 (for example BP256 with SHA-384) | Not seen in real SODs. Each costs a circuit and bundle space. Added on evidence: one manifest entry, one VK, one genesis change. |
| SODs larger than the maxima in 7.1 (for example SHA-512 with more than 9 data groups) | Gate cost (about 21k per SHA-512 block). Refused with the unsupported message. |
| TD1/TD2 (ID cards) | Out of scope: the circuit requires a 93-byte TD3 DG1. |
| SODs without signed attributes | ICAO SODs always carry them. Refused with a clear message. |
