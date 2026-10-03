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
- [x] Audit5Test (10 tests, state-record goldens shared with iOS); testDebugUnitTest 175 pass; assembleDebug ok (c8ec404, caf0798, 5b092fb, 96d5a48)

## iOS (done, as Android)
- [x] core: Handles (timesOk, maxRows, satAdd/satSub, chainDirectoryRead), Reminders (saturating, addressed entries, handlePaysElsewhere), params clamped, tagStateTag, WalletSync state records (same bytes: goldens pinned on both), PrivacyStore (PendingMove, cursors, void heights, switch target; kept across resets), PrivacyWallet (records on every bind/release/cast/clear/move, NotYet.notHeld no-bound attempts, MoveRecorder, resolvePendingMoves, reconcileHandle, chain tip time, release needs a handle), ReferralLink (0b30d91)
- [x] UI: chain directory for every wallet + reconcile in refreshPersonal; Handle screen (addressed entries, in-flight moves, renew-what-you-hold note); Switch identity (moves confirmed only, Check the moves again + record retry, frozen/warned target, phrase cleared when backgrounded/closed, backup toggle after reveal); SendSheet shield-note failure shown (L12); PrivacySession.handles uses the same Constants LCD/indexer as everything else (no user-selected node on iOS: L12 confirmed) (56e994c)
- [x] M5: ReferralStore (first wins, clear), universal link capture (onOpenURL + NSUserActivityTypeBrowsingWeb), associated-domains applinks:erth.network, Change/Remove, cleared when unresolved (09c7bff)
- [x] M4: the hostile Int64.min/max entry that trapped now saturates (Audit5Tests.testHostileHandleAndCaretakerTimesNeitherWrapNorTrap)
- [x] swift test 181 pass; corecheck 149/149; build-ios.sh ok; xcodebuild simulator (ARCHS=arm64, no signing) BUILD SUCCEEDED

## External (not code)
- erth.network must serve /.well-known/assetlinks.json (Android App Link verification) and /.well-known/apple-app-site-association (universal link) for /ref/*; until then links open the browser, not the app (no custom-scheme fallback any more, on purpose).
- iOS App ID needs the Associated Domains capability before a device/TestFlight build signs with the new entitlement.

## Residuals (documented in PRIVACY_FORMATS 3b/4g)
- A state record lands with its fee bundle even if its msg then fails in the block (ante writes stay): the wallet voids it when it sees the failure itself; otherwise the chain refuses what follows from it at no cost (a wrongly-applied "moved out" would hide a renew button until the user restores differently; very unlikely: needs out-of-gas in the msg after a simulated gas limit + 10%).
- A caretaker split of more than ~18 options (or ids >= 2^14) does not fit the record: restored as held with its expiry, options unknown (recast to choose them).
- L5 partially: every wallet now reads the chain directory on the same schedule (holder or not); the fresh read right before paying a handle remains.


## Not done
- L1 (lease bound from the current param, not the chain's longest-ever): needs a chain query (handle_lease_max, lease_hold); later.
