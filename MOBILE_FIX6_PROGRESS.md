# Mobile audit round 6 fixes (audit6-mobile.md, mobile-orch 9accb7a, chain-orch c0ad1dd)

Worktree mobile-orch, branch privacy/orchard. Feature freeze: fixes only.
Android first, then iOS, same behavior and bytes. Never pushed. Chain not edited.

## Android (done)
- [x] M1 PIN change behind a fresh unlock (ConfirmUnlockDialog, counted by UnlockAttempts); SessionManager.changeSecret(old, new) re-checks the old secret; K2 FLAG_SECURE on the dialog (2aec23b)
- [x] M2/M3 denoms: SDK regex, never "asset/"; learned only from own notes (cm-bound) or Query/Assets (id checked), once a sync on demand; lookup built once a sync; persisted set = held notes' denoms, cap 4096; stored asset/<hex> notes renamed back; rows opened before the tree grows, bad row skipped (46c8553)
- [x] M4 verified_height in the store; tip > verified + 1000 refused (sync once, retry, refuse); outsized pending timeouts (notes, stake notes, votes, moves) settled by tx status (fc6807d)
- [x] M5 switch target only on a confirmed move (confirmMove / MOVED_OUT record); in-flight move holds it; refused/failed/expired frees it; legacy freeze cleared; target that moved out or holds one refused before anything is sent (also I1) (889c604)
- [x] M8 one PrivacyWallet + PrivacyStore per wallet per process (PrivacyStore.shared, PrivacySession map), kept across lock; run re-reads votedPositions before each cast and merges into the stored run (889c604)
- [x] M6 HandleEntry.owner (chain JSON `owner`, stream row[5], 64 hex, else ""); adopt only owner == own handle-scope nullifier; held handle with another owner dropped; no owner = no adoption (d8780ce)
- [x] M7 bindHandle(renewOnly); Renew / reminders / address cards use it; cards hidden while a handle is held (d8780ce)
- [x] M9 MsgAddLiquidity field 5 in proto; public deposit sends SwapMath.minShares (fresh reads, 1%); D7 fails closed (9dec4f8)
- [x] Lows: P1, D1, D4, D6, K2, K3, K4, K5, H2 (cap 250k, address <= 256) (818f70d)
- [x] Audit6Test (12 tests; M9 golden shared with iOS); testDebugUnitTest 202 pass; assembleDebug ok

## iOS (done, as Android, same bytes)
- [x] M1: not affected (SecurityScreen already behind ConfirmIdentity)
- [x] M2/M3 denoms (c32d174)
- [x] M4 send tip / outsized timeouts (9003a1b)
- [x] M5 switch target + target refusal (I1); M8 one PrivacyWallet + store + StakeVoteController per wallet, runs merge into the stored run (5f90ad3)
- [x] M6 owner adoption; M7 renewOnly, cards hidden while held, confirm says "Change handle to @X" for a change; H2 (dce97b3)
- [x] M9 Msg.AddLiquidity field 5, fresh reads at confirm (async build); D7 fail closed; I2 chained onSuccess kept; K7 comment (eaabf41)
- [x] Lows S2, P2, D1, V3, K6 (adee986)
- [x] Audit6Tests 12 (MsgAddLiquidity golden identical to Android) (498693b)
- [x] swift test 207 pass; corecheck 149/149; xcodebuild simulator (ARCHS=arm64, no signing) BUILD SUCCEEDED; ProverGate swift test 4 (1 skipped) pass. No witness changed: no PRIVACY_TOML_DIR run needed.

## Chain / backend (not done here; chain not edited)
- M6 needs the chain to add `owner` (64 hex handle-scope nullifier) to HandleEntry in Query/Handle and Query/Handles, and the backend /handles stream to carry it as row[5]. Until then no entry is ever adopted from the directory (address cards show as unverified, renew-only).

## Lows not done (deferred: not cheap or need chain/UX work)
- S3 (buffer state records until roots verify), S4 (nullifier completeness), S5 (rescan), P3, P4, I3, I4, I5, I6 (bind pending guard), I7, H1 (stream omission vs chain directory), H3, H4 (= M6 root, addressed by owner), V1 (cross-check ballot inputs vs proposal), V2, V4, V5, V6, D2, D3, D5, K1 (iOS PIN device binding), B1, B2, B4.
