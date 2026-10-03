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
