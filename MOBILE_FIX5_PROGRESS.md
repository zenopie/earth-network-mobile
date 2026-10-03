# Mobile audit round 5 fixes (audit5-mobile.md, mobile-orch a3d6cfd, chain-orch 4a663d5)

Worktree mobile-orch, branch privacy/orchard. Android first, then iOS, same
behavior and bytes. Never pushed. Chain not edited.

## Chain check (M1)
- [x] A no-bound MsgBindHandle / MsgSetCaretaker from a non-holder is refused
  at no fee: x/personhood handleStatement / caretakerStatement run
  checkPredecessorBound inside CheckPrivateAction (handle.go:348-374,
  private_actions.go:128-163), which x/shielded's PrivateMsgDecorator runs
  before VerifyPrivateAction and ExecutePrivateMsg (spend + fee payment)
  (ante.go:335-366; action.go:16-23 "a tx whose action would be refused
  never spends its fee notes"). Simulate runs the same checks, so the wallet
  is refused before proving.

## Android (done)
- [x] M3 fresh unlock (ConfirmUnlockDialog: the unlock gate, checked against the session secret, failures counted by UnlockAttempts) before another wallet's phrase; phrase cleared on pause/stop/leave (c8ec404, UI in 5th commit)
- [x] M5 referral: only https://erth.network/ref/<handle> (exact) and the Play install referrer; earth://ref filter removed; Change/Remove on a linked referrer; a linked referrer that does not resolve is cleared and the field unlocked (caf0798)
- [x] M1 state records "EH"/"EC" (PRIVACY_FORMATS 3b) on every bind/release/cast/clear/move (moved-in record to the new wallet, tagged with its nk); sync applies the newest, cursor kept across resets, failed-in-block heights void; directory scan (chain directory, every wallet): single entry at own address adopted, swept handle dropped, all addressed entries reminded; bound not met -> no-bound attempt, chain refusal -> NotHeld (no fee)
- [x] M2 PendingMove in mover (outgoing) and target (incoming, via MoveRecorder before broadcast); refusal undoes both; resolvePendingMoves (every sync + "Check the moves again"): committed applied, failed/missing-past-timeout undone; state records settle too; failed target write kept, retried (retryMoveRecords); UI marks moved only when confirmed, no chained caretaker move after an unconfirmed handle move, register-there disabled while in doubt
- [x] M4 HandleDirectory.check: 0 < expires_at <= renewal_until <= now + 10y; caretaker expires_at bounded (event, records, adopt); Reminders saturating (Handles.satAdd/satSub); Int64.min/max test
- [x] Lows: L2 chain tip time for predecessor bounds and the removal day; L3/L5 personal standing reads the chain's own directory for every wallet (payment-time fresh fetch unchanged: partial for L5); L4 1M-row cap + page-0 size; L6 lease param validated before broadcast, saturating after; L7 params clamped to 10y, wait date saturating; L8 target frozen by store id, registered/handle-holding target warned; L9 backup box only after the reveal; L10 HandlePaysElsewhere reminder; L11 swept handle dropped (L11 double-pay: an unconfirmed bind's record restores it); L12 release refused locally without a handle
- [x] Audit5Test (10 tests); testDebugUnitTest 175 pass; assembleDebug ok (8ed... see git log)

## iOS
- [ ] same
- [ ] swift test, corecheck, xcodebuild (simulator)

## Not done
- L1 (lease bound from the current param, not the chain's longest-ever): needs a chain query (handle_lease_max, lease_hold); later.
