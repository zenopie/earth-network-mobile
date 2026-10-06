# Audit history (mobile, privacy/orchard)

How the Android and iOS wallets reached the formats in PRIVACY_FORMATS.md:
one entry per round, oldest first, with the chain commit each round's
vectors were generated against (`tools/privacyvectors/gen.sh`) and
what it found and changed. Android went first each round, then iOS with the
same bytes; the web app (app-orch) kept its own log. Nothing was pushed.
This file replaces the per-round `*_PROGRESS.md` files and
`IOS_PORT_PROMPT.md` (see git history for them).

## Open and deferred (still relevant)

- **Deferred Lows from mobile audit 6** (not cheap, or need chain/UX work):
  S3 (buffer state records until roots verify), S4 (nullifier
  completeness), S5 (rescan), P3, P4, I3, I4, I5, I6 (bind pending guard),
  I7, H1 (stream omission vs the chain directory), H3, V1 (cross-check
  ballot inputs against the proposal), V2, V4, V5, V6, D2, D3, D5, K1 (iOS
  PIN device binding), B1, B2, B4.
- **External setup.** erth.network must serve
  `/.well-known/assetlinks.json` (Android App Link) and
  `/.well-known/apple-app-site-association` (universal link) for `/ref/*`;
  until then referral links open the browser (no custom-scheme fallback, on
  purpose). The iOS App ID needs the Associated Domains capability before a
  device/TestFlight build signs with the entitlement. The backend `/handles`
  stream needs CORS for the web app.
- **Residuals, documented.** A state record lands with its fee bundle even
  if its msg then fails in the block (the wallet voids it when it sees the
  failure). A caretaker split that does not fit 40 bytes (about 18+ options,
  or option ids ≥ 2^14) is restored as held with its expiry, options
  unknown. Every wallet reads the chain directory on the same schedule, but
  the fresh read right before paying a handle remains (mobile audit 5 L5,
  partial). A lock that failed in its block published its owner tag without
  a position; a restored wallet may reuse that counter. Registrations made
  before the record note cannot be restored from the mnemonic.
- **Privacy notes for the chain** (raised, not changed): a restored
  wallet learns earlier votes only from 1119 refusals, one vote nullifier
  per refusal; Query/UnbondPayout is per id (never asked; the wallet keeps
  its own record); stake rows carry no denom, so a note at a validator
  whose book the chain has dropped (the validator list carries a removed
  validator's book only while it lasts) stays unnamed; spent stake notes
  are never pruned (storage grows); a redelegation's fee cap still allows
  the pair's record at its worst (the list's entry counts size only the
  merge headroom); the unlock record costs one more action (the stake
  ciphertext has no memo); "merge notes" after a snapshot cannot reduce
  that proposal's vote parts.
- **Unmeasured.** Phone prove times for action and stake (Mac through
  Swoirenberg: about 150 ms each), and an on-device measurement of the
  2^19 and 2^20 passport tiers. A restore whose record country hint
  misses scans about 90k timestamps before the full country search (about
  30 s in a debug build).

## Before the Orchard port

- **iOS port brief** (2026-08, `IOS_PORT_PROMPT.md`, now removed). Set the
  ground rules that still hold: the Android app is the reference
  implementation; the prover's Barretenberg must be **v5.0.0 final** on
  every side (a nightly's proofs fail the chain's verifier on large
  circuits; never move the chain to a nightly), confirmed first via
  Swoirenberg/noir_rs; passport reading needs the NFC `TAG` reader format
  and the ICAO eMRTD AID `A0000002471001` in
  `com.apple.developer.nfc.readersession.iso7816.select-identifiers`, plus
  `NFCReaderUsageDescription` (iOS 13+, foreground-only sheet); chip
  dialogue via NFCPassportReader; coin type 118, SIGN_MODE_DIRECT; Apple
  requires an organization account for crypto wallets. Its gas gate
  (rewarded ads), referrer-address deep links and `earth://ref` are gone.
- Chain d083cc5: circuits action and stake, Grumpkin and binding
  signatures, BundlePlan/StakePlan, every private msg, stake-tree sync,
  FakeChain, WalletFlowTest, nargo/bb parity (git history before d52b564).

## Orchard port (chain fced976, 2026-10-02)

Both platforms ported to Orchard-style bundles (ORCHARD_DESIGN 12-14).

- Formats: staking/dex `fee` fields reserved; the sighash binds memo,
  timeout_height and gas_limit; one fee rule; the registration binding
  covers both v2 note ciphertexts, written before proving; every
  chain-minted note carries a ciphertext and the self-mint counters were
  removed (one note-discovery rule); indexer base `/privacy/<chain_id>/
  <genesis>` with relaunch wipe, 404 retry, halt, 8 MiB bodies, 5000-row
  pages; transparent gas grant removed.
- Audit: C1 (no counters: restores after abandoned preps, failed broadcasts
  and locks), C2 (pending registration persisted at broadcast), C3 (every
  root checked against the LCD; unverified roots block private txs), C4
  (iOS privacy data excluded from backup), L1 (saturating sums), L5 (Earn
  rates from one `/rates` read), L6/L7 (bounded responses, positions past
  u32 inconsistent), L8 (registration record note; restore from the
  mnemonic).
- Android versionCode 39 (1.0.32); iOS build 12. 281 witnesses pass
  `nargo execute` on each platform; ProverGate proofs accepted by the
  chain's Go verifier.
- Backend events.py would have halted on minted stake rows; moot since
  chain dff3a9b mints no stake notes.

## Clients re-audit, round 2 (2026-10-02)

K1 tagged record memo v2 (`earth.rectag`), block time first, bounded
resumable fallback (then 4M hashes a record); K6 genesis switch confirmed by
the LCD before any wipe, keeping identity, pending registration and
owner-tag counters; K7 spends and registration recorded at CheckTx
acceptance by tx hash; K8 a pruned note root is unverified, not a mismatch;
K9 pinned reads need the echoed height, 4+4 nullifier sample, indexer > 30
blocks behind is unverified; K10 strict indexer base, host-pinned requests;
K11 unlock memo `"EU"` (`earth.unlocktag`) and `closed_otag_max`; K12
amounts bounded to 2^63 − 1. Chain round 2 rules: canonical tx bytes,
ciphertext slots, one use per binding. The (since removed) spaced stake-vote
controller (K5) was introduced here. Android 1.0.33; iOS build 13.

## Clients audit, round 3 + chain wave 3 (chain 06ea4d6, 2026-10-03)

Sync generations; snapshot ahead of the local tree is "sync first"; paging
consistency and a 10-minute sync limit; identity row time column and the
16-height LCD cover set; timeout_height = tip + 50 and pending release past
it; fee cap min(2 ERTH, 2× estimate) and FeeAboveQuote; save fsync+rename,
corrupt state is an error; `PrivacyStore.delete`; bundled 2^15 privacy SRS
(sha256 pinned), passport SRS fetched once at launch; nullifier sample
excludes own; tried record times kept; quotes with random nullifiers;
`/gas/register` proof of work (`earth-gas-pow/v1`, 428 restamp). Wave 3:
canonical LegacyDec vote weights, unshield to a module account refused,
calendar current_date, activation bounds. Android 1.0.34; iOS build 14.

## Stake votes without spending (chain 9b29f5d, 2026-10-03)

MsgStakeVote gained circuits/vote (no stake proof, nothing spent): snapshot
stake root and nullifier tree, per-proposal vote nullifiers
(`earth.vnf`, `earth.snfl`), the three-significant-figure weight rule,
1119 refusals recorded. 329 witnesses each platform. Android 1.0.35; iOS
build 15.

## Clients audit, round 4 (2026-10-03)

H1 identity row times bounded; M1 every indexer height ≤ LCD tip + 10, note
root height and the claimed synced height checked, pending released only on
MISSING/FAILED past timeout; M2 cover-set decoys from a persisted sample;
M3 snapshot from the LCD only, SpentBeforeSnapshot only when sync agrees;
M5 records matched only on verified trees; M7 JSON depth ≤ 64; redirects
off; PoW capped at 24 bits; spends marked pending before broadcast with a
local hash; 1119 needs codespace shieldedstaking; backend paging rule
(aligned pages, 100/1000, 503/429 backoff); iOS privacy SRS never
downloaded at proving time, passport SRS streamed with a cap; secrets
zeroed. Android 1.0.36; iOS build 16.

## Clients round 5: handles and predecessor bounds (chain 4a663d5, 2026-10-03)

Identity leaf commits to `predecessor_at`; membership gained
`max_predecessor` (8 public inputs); circuits rebuilt with nargo
1.0.0-beta.22, every bundled VK equal to genesis. Referrer addresses,
MsgBindReferrer and its consent removed: handles (MsgBindHandle,
MsgMoveHandle, MsgMoveCaretaker), referral by handle (`earth.affiliate`),
the whole-directory rule, pay a handle, switch identity with moves,
L(lease) hour-rounded bounds for every wallet, reminders instead of
automatic fee spending (only matured unbonding claims stayed automatic,
removed in round 7), dex deposit legs rounded up. Android 1.0.37; iOS
build 17.

## Mobile audit 5 (chain 4a663d5, 2026-10-03)

M1 state records `"EH"`/`"EC"` (`earth.statetag`) on every bind, release,
cast, clear and move, applied newest first with a kept cursor; no-bound
attempts refused at no fee become NotHeld; M2 pending moves in both stores
before broadcast, settled by hash; M3 recovery phrase behind a fresh unlock;
M4 lease times bounded to now + 10 years, saturating reminders; M5 referrals
only from `https://erth.network/ref/<handle>` and the Play install referrer
(`earth://ref` removed); Lows L2-L12. L1 (bound from Params) was closed in
round 6 by LeaseBounds.

## Clients round 6: audit 5 chain rules (chain 203d3b2; circuit bound d9d2366, 2026-10-03)

MsgRegister names its referrer by handle alone (11/12 reserved; the chain
mints an open referral note, `earth.referral`); notes stream format 2 with
open notes; split payouts (same pc and ciphertext per chunk; the chain now
splits at 2^63 − 1, chain 8ed1278, and refuses a leg above 32 × (2^63 − 1));
LeaseBounds for every bound; HandleNotLive / CaretakerLapsed /
HandleNotMovable; anchor freshness (1,800 s); swap fee rounded up; bind gas
+8 note writes. Then privacy_core `NOTE_VALUE_BITS = 63` in note_cm and
stake_cm (gates: action 8,098, stake 9,672, vote 9,072). The prover's
membership kind still split 7 public inputs at the time; fixed since (8).
440 Android / 416 iOS witnesses pass.

## Mobile audit 6 (chain c0ad1dd, 2026-10-03)

M1 PIN change behind a fresh unlock; M2/M3 denoms learned only from own
cm-bound notes or the chain's asset list (SDK regex, never `asset/`, cap
4096); M4 `verified_height` and the 1,000-block tip bound, outsized
timeouts settled by tx status; M5 switch target fixed only by a confirmed
move; M6 handle `owner` adoption (needed the chain's field 6, served from
round 7); M7 renew-only binds; M8 one wallet and store per process; M9
public MsgAddLiquidity `min_shares` (5); H2 directory cap 250,000 rows,
address ≤ 256. Deferred Lows: see the open list above.

## Clients round 7: audit 6 + staking wave (chain 48b631c, 2026-10-03)

Registration binding names the chain id (`148b3513…4159`); errors 1127 and
1113; MsgUndelegate names its own payout note (pc 6, ciphertext 7) and the
chain pays at maturity, so MsgClaimUnbonding, unbond claim notes and the
automatic claim were removed; one stake vote per validator (then four
slots); the stake-vote controller, consolidateStake and all automation
removed: every tx comes from a confirm sheet; every private gas limit from
simulation (+10 %, ≤ 5× used); send-disabled denoms refused at every pool
edge; handle owner end to end. 505 Android / 481 iOS witnesses pass and
ProverGate proves them all. Android 1.0.38; iOS build 18.

## Clients round 8: stake note v2, Move stake, slash debt (chain dff3a9b; audit 7 b46a4bb, 2026-10-04)

Stake commitments carry a slash label (`earth.slabel`), stake ciphertext v2
(201 bytes, `earth.stake.v2`, version 0x04); the chain mints no stake note
(blind stake ciphertext and spc_mint removed); stake proof v2 (16 public
inputs, StakeProof fields 9-15, two lanes); one note per validator with
delegation quotes at the live rate less 10 ppm; Move stake (MsgRedelegate,
the one feature the freeze allowed); the slash debt tree (`earth.debtl`,
indexer `/debt_rows`); votes with two slots (9 public inputs), labelled
notes at their post-slash value; the unlock record moved to a value-0 pool
note. Audit 7 (b46a4bb): every stake proof names the current clear_before
and debt root; owner-tag salts fresh off positions; a move arrives whole
only out of an unbonded source's queue, else u − 1,001; the redelegation
record in the fee cap. Dead code removed: the 11-input stake witness, the
four-slot vote, chain-minted stake rows. 553 Android (403 action, 100
stake, 36 membership, 14 vote) / 529 iOS witnesses pass; vectors identical
at dff3a9b and b46a4bb. Android 1.0.39 (versionCode 46); iOS build 19.

## Pre-audit cleanup (2026-10-04)

No behaviour change. PRIVACY_FORMATS.md rewritten as a current-state spec
(code wins where the old layered doc disagreed; differences listed in the
commit). Progress logs folded into this file. Dead code of removed features
and unused helpers, resources and transparent msg builders removed on both
platforms; comments describe current behaviour instead of audit rounds;
tests reorganised by feature (Android 230, iOS EarthCore 232, unchanged
counts). The pre-Orchard vector generator was retired: the current one moved
from `android/tools/orchardvectors` to `tools/privacyvectors` and now writes
both platforms' copies (regenerated at b46a4bb: byte-identical). Circuit
comments tidied with identical bytecode, ABI and VKs.

## Final pre-audit pass (chain c3bf5ef, 2026-10-04)

Fixes only; Android first, then iOS with the same behaviour.

- **Validator list (chain b7e77f8).** Every per-validator read is gone:
  Query/Validator and x/staking's `validators/{addr}` (asked for the
  validators of each quote at review time), Earn's per-validator rate
  fallback and the indexer's `/rates`. **iOS bug found:** AppModel's rate
  fallback asked Query/Validator for every held validator by id, bonded or
  not, telling the node which validators the wallet holds (Android's
  fallback asked only about the bonded set). Both apps now read
  `Query/Validators` whole: every page pinned to the first page's height
  (`x-cosmos-block-height`), a page from another height or not served at
  it restarting the read (4 tries); kept in memory; read again on every
  sync, every quote and every Earn refresh. Quotes take S, B, P, U, D, W,
  status, delegatable and refusal from it: delegate and move destinations
  that are not delegatable are refused up front with the chain's reason;
  a move arrives whole wherever the value leaves the queue first
  (unbonded, removed, or D − U ≤ 0) and P + W covers it (previously only
  an unbonded source, and P alone); an undelegation's sheet shows its value
  at the live rate; a move whose pair is within 51 counted entries of the
  1,024 cap declares the merge's gas on top of its simulation. Stake
  pickers offer bonded validators that are delegatable; names, commissions
  and rates come from the list for every validator; sync names derth
  denoms from it (books of removed validators included). Grep of both
  apps: no per-id validator, delegation-pair or redelegation query left.
- **Android tx sheet gas (bug).** Every tx sheet offered "Get free gas",
  but `TxController.requestGas` only set a message and `requestingGas` /
  `awaitingGas` were never set. The one grant (`/gas/register`) belongs to
  the registration's sheet, which keeps it; every other sheet now says
  where its fee comes from, as iOS already did (iOS offers the grant only
  on the registration sheet; its text was aligned and its unreachable
  non-registration branches removed).
- **ProverGateCore corecheck.** It checked a stale 15-input fixture; the
  committed `lean_inputs.json` already matched the 13-input lean_poa. It
  now checks the fixture against the compiled circuit's ABI (names,
  shapes, widths, public order, return values, calendar date, non-zero
  address). The gate and `progate --witness` compare their VK with the
  chain's genesis key. The apps' passport path was correct: both build the
  identical witness from a shared synthetic passport fixture (new Android
  and iOS tests; the synthetic DSC now names an issuer, which BouncyCastle
  requires), it proves and verifies with the genesis lean_poa VK, and
  `bb write_vk` of every bundled circuit (seven passport variants, action,
  stake, membership, vote) equals genesis.
- **Vote padding (ba4d295)** verified end to end: fresh dumps, Android 553
  witnesses (403 action, 100 stake, 36 membership, 14 vote) and iOS 529
  (385, 100, 30, 14), all solve with `nargo execute`; ProverGate
  (PRIVACY_TOML_DIR) proves and verifies all 1,082 with the genesis VKs.
  The two passport witnesses (`lean_inputs.json`, the shared fixture)
  solve lean_poa. Tests: Android 241, iOS EarthCore 243, corecheck 149/149,
  ProverGateCore corecheck all pass; assembleDebug and the arm64 simulator
  build succeed.

Android 1.0.40 (versionCode 47); iOS build 20.

## Passport signature coverage (chain e2f3e18, 2026-10-04)

Every signature scheme unexpired passports use (circuits/PASSPORT_COVERAGE.md,
with sources and the SHA-1 decision). Android first, iOS byte-identical.

| Change | Commits |
| --- | --- |
| Research: coverage table, SHA-1 decision, design | 6442c5e |
| Circuits: generic poa_core (hash functions as parameters, DER prefixes by digest length), 33 generated variants with nargo tests over shared synthetic passports, audited library pins, per-hash buffer maxima, RSA exponent committed | 4d38b31 |
| The 16 bundled 2^18-tier circuits and passport_variants.json (the other 17 served by the backend, pinned by sha256) | dbaa03b |
| Android: variant selection from the SOD's key, padding and three hashes; "This passport's signature type isn't supported yet (<scheme>)"; fetched circuits; tiered SRS | d2bdc4f |
| iOS EarthCore: the same selection and witness, byte for byte | 8c6532d |
| iOS: tiered SRS, fetched circuits, the passport gate on every variant | 050fb6e |

Found and fixed: the 200-byte eContent cap (more than four SHA-256 data
groups refused); Android's 2^18 SRS could not serve the 2^19 circuits;
Android rejected explicit ECParameters and iOS matched them by group order
only (now every parameter, as the chain); the BP512 circuit's SHA-256-only
hashing; pre-audit noir-bignum; BoundedVec tails read by SHA-1/384/512.
Tests: nargo 165 variant tests + 21 poa_core; Android 245; iOS EarthCore
245 and corecheck 336; ProverGate gate on all 33 variants (VK = genesis).
Not done: an on-device measurement of the 2^19 and 2^20 tiers; the
androidTest PrivacyProverDeviceTest was already out of date and does not
compile (unrelated).
