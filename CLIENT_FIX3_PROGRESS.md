# Clients audit round 3 fixes — progress

Worktree mobile-orch, branch privacy/orchard. Android first, then iOS, kept
byte-identical in formats and behavior. PRIVACY_FORMATS.md updated with each
format or behavior change. Auditor PoCs: /private/tmp/claude-501/audit3/clients/ios.

## Android
- [x] 1 sync generations: syncGeneration bumped, rootsVerified cleared and persisted before the first request; verifiedGeneration set only by verifyRoots of the same sync; requireVerified needs both. PoC A test (forged-then-failing indexer).
- [x] 2 stakeVote: snapshot ahead of the local stake tree -> SyncFirst (PoC C); LCD numbers (min_fee, max_actions bound 64, unbonding_time, tree sizes) parsed without wrapping; maturesBy exact math.
- [x] 3 paging: position pages must end at from+rows and carry rows when "complete"; height pages never move back and move forward when "complete"; 10-minute overall sync limit (SyncTimeout). PoC B test.
- [x] 4 restore: identity rows' optional 5th column (block time) kept on the record; without it, a 16-height LCD cover set (persisted, reused, at most 3 fetches); then the bounded fallback.
- [x] 5 timeout_height = tip + 50 on every private tx; pending notes carry pendingUntil and are released only when the LCD tip is past it and the nullifier stream was read through it.
- [x] 6 fee cap: min(2 ERTH, 2x the wallet's shape estimate); FeeAboveQuote above the sheet's fee (TxController re-shows the sheet); sheet estimate 10M gas.
- [x] 7 automation: runPass (one action, random 30-180 s pause + sync before the next, re-decided; change dependency waits).
- [x] 8 StakeVoteController.suspend on PrivacySession.clear (lock/endSession/switch), run kept; resume from the selected wallet's store only; double start refused.
- [x] 9 PrivacyStore.delete (zero, sync, unlink). Android has no forget-wallet path (WalletsScreen: no delete); API + test only.
- [x] 10 save: fsync + rename checked; corrupt state.json -> CorruptState.
- [x] 11 SRS: assets/srs/bn254_g1_32769.dat (2 MiB, sha256 pinned), all privacy kinds at 2^15; passport still downloads at registration (documented).
- [x] 12 nullifier sample excludes own nullifiers.
- [x] 13 records: tried times kept; a new time (indexer, LCD) is tried even after EXHAUSTED; new leaves reopen; reset finds afresh.
- [x] 14 quote/quoteSend simulate with random nullifiers; automation logs kind only; pre-K6 store keeps identity.
- Audit3Test (22 tests); privacy suite green.
- [x] Addition: /gas/register proof of work (GasPow: SHA-256 hashcash, cancellable, progress; GasGrant: GET /gas/pow, stamp, 428 restamp at pow.bits, stamp kept only after 503/429, new after 403). GasPowTest (fake server checking stamps like services/pow). Registration sheet shows "Preparing request… N%".
- [x] versionCode 41 (1.0.34). testDebugUnitTest (all) + assembleDebug pass.

- [x] Passport SRS (Android 2^18+1 points, iOS 2^19+1) fetched once at launch, hash-pinned; privacy SRS bundled (iOS references the Android asset folder).

## Chain wave 3 (chain-orch 06ea4d6), both platforms
- [x] F3 vote weights canonical LegacyDec (wallet canonicalizes; FakeChain refuses others).
- [x] L6 MsgBindReferrer referrer_pub_key (5) / referrer_signature (6) over the consent bytes, signed by the selected wallet's transparent key (UI and automation refresh); address must be the key's.
- [x] L4/L5 max_activation: proposeRemoval = today 00:00 UTC - 86400; caretaker/referrer <= now - R - 86400 (- 600 s margin, hour-rounded); ballot votes from BallotInputs.
- [x] B/F2 unshield to any module account refused client-side (16 module names, SHA-256(name)[:20]).
- [x] I1 registration current_date must be a calendar date.
- [x] Vectors regenerated (android/tools/orchardvectors/gen.sh, chain 06ea4d6): bind_referrer with consent fields, canonical stake_vote weights, referrer_consent, module_accounts, legacy_dec; iOS copy updated.

## iOS
- [x] EarthCore: all of the above ported (byte-identical vectors); Audit3Tests (PoCs A-C fixed + 1-14 + wave 3), GasPowTests. swift test 121/121.
- [x] EarthUI: shownFee TaskLocal + FeeAboveQuote re-shows the sheet; forget deletes privacy data; save errors shown; gas PoW progress; referrer consent.
- [x] App: srs folder reference (Android asset), PassportSRS prefetch; ProverGate PrivacyProverTests prove from the bundled SRS. build-ios.sh ok; xcodebuild simulator (ARCHS=arm64) BUILD SUCCEEDED. CURRENT_PROJECT_VERSION 14.
