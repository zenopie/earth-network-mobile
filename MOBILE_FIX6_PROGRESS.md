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

## iOS
- [ ] M2/M3, M4, M5, M6, M7 (+ confirm wording), M8, M9, Lows
