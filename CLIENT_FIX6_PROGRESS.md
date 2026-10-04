# Clients round 6: audit round 5 chain rules (chain-orch privacy/orchard 203d3b2)

Worktree mobile-orch, branch privacy/orchard. Android first, then iOS, same
behavior and bytes. Web: app-orch (privacy/orchard, its own
CLIENT_FIX6_PROGRESS.md). Backend (read only): backend-orch 14bd53b/13eb232
(notes stream format 2), c17c8b6 (/gas/register affiliate_handle only).
Never pushed. Formats: PRIVACY_FORMATS.md §3, §3a, §4a, §4h.

## Chain check (item 3: split payouts)
- [x] MintNoteSplit (x/shielded/keeper/notes.go) mints each chunk with
  MintNote: cm = H(TAG_CM, asset, chunk value, pc), its own position and
  `shielded_mint` event (amount, position, the one ciphertext). The blind v2
  ciphertext (zk/privacy EncryptBlindNote) carries rho, rcm and a memo and
  binds neither cm nor value (KDF over the shared secret and epk only), so
  each row decrypts to the same opening and its own amount gives its cm;
  nf = H(TAG_NF, nk, rho, position) differs per position. Not a chain bug:
  every chunk is found and spendable.
- [!] Interaction found: full chunks are 2^64 − 1, above the 2^63 − 1 every
  client holds (PRIVACY_FORMATS §3 Amounts), so a wallet would ignore them.
  Wallet-side guard: a withdrawal is refused at start when a note leg is
  above (2^63 − 1) / 4 at the current reserves (only ERTH/ANML, 6 dp, use
  note legs; unreachable in practice). A chain decision is needed if it
  should split at 2^63 − 1 instead (or the clients widen to u64).

## Android (done)
- [x] items 1, 2, 3, 4, 5, 6, 7, 8, 9 in core: MsgRegister 15 only,
  Privacy.affiliateField(handle), Privacy.referralOpening, GasGrant body;
  Indexer.parseNotes format 2 by name (refuses format 1, partial openings);
  WalletSync.open takes open notes by owner_pk + cm; LeaseBounds read and
  checked (adds up, ranges), predecessorBound from it, HandleNotLive /
  CaretakerLapsed / HandleNotMovable, handle_expires_at kept (bind event,
  directory); anchor freshness (roots expires_at, 1,800 s, sync then
  SyncFirst); SwapMath.feeOf ceiling; checkWithdrawalNoteLegs; bind gas
  +8 note writes in the fee cap estimate; ChainErrors (dex 1101 text, bank
  5, personhood 1116 text, shielded 1103 text); assembly round already used
  BallotInputs.round (scope vectors proposal_5_1) (f028405)
- [x] UI: renewal-period note on the handle screen, switch screen refuses
  to move a handle in its renewal period and says why, claim wait from
  LeaseBounds, bind sheets at 12.5M gas, LP withdrawals (private and the
  account's ANML-note leg) checked before signing (30b2d9d, 6cf6413)
- [x] orchardvectors (main.go: affiliate field, referral_opening, tag
  referral) regenerated against 203d3b2: vectors.json, dex_amm.json;
  fixtures unchanged (no circuit change)
- [x] Fix6Test (13), HandlesTest referral rewritten, FakeChain models
  203d3b2 (open referral mint, live-hold rules, LeaseBounds with a longest
  lease and a held caretaker lease, root expiry, MintNoteSplit)
- [x] testDebugUnitTest 189/189; assembleDebug ok; 440 witnesses
  (PRIVACY_TOML_OUT) pass nargo execute (1.0.0-beta.22)

## iOS (done, as Android)
- [x] EarthCore: same items (PrivateMsgs, PrivacyHash, GasGrant, Indexer,
  WalletSync, PrivacyChain LeaseBounds, PrivacyStore handleExpiresAt/For,
  PrivacyWallet NotYet.lapsed / HandleNotMovable / AnchorTooOld /
  checkWithdrawalNoteLegs / bindHandleGasEstimate, PrivateTxEngine,
  SwapMath.feeOf half-even at 18 places then ceiling, ChainErrors);
  vectors copied from Android; corecheck swap vectors re-derived from x/dex
  at 203d3b2 (239d7db)
- [x] EarthUI: as Android (8881074)
- [x] swift test 195/195; corecheck 149/149; build-ios.sh ok; xcodebuild
  simulator (ARCHS=arm64, no signing) BUILD SUCCEEDED; 416 witnesses pass
  nargo execute

## Web (app-orch, see its CLIENT_FIX6_PROGRESS.md)
- [x] e235ba9 protos, 0cb88fb fee ceiling + dex-swaps fixture, c57ae54
  LeaseBounds + governance round note, 51595c5 shield send-disabled and
  withdrawal note legs, 5fffd5b progress. build ok; check:privacy 164,
  check:tx 28, check:forms 20, check:handles 49, check:explorer 13,
  check:staking 8 pass; check:dex 4 fail on the live LCD (Cloudflare 1033).

## Docs
- [x] PRIVACY_FORMATS.md: 203d3b2 summary, §3 open notes, §3a affiliate
  field / referral opening / gas body, §4a notes format 2, new §4h, §5

## Circuit note bound (chain d9d2366)
- [x] privacy_core NOTE_VALUE_BITS = 63 in note_cm/stake_cm: action (spend,
  output), stake (inputs, outputs), vote (amount) refuse a value above
  2^63-1 (810c38d). Gates: action 8,120 -> 8,098, stake 9,647 -> 9,672,
  vote 9,046 -> 9,072, membership 5,659 unchanged. ABI unchanged: no
  witness builder changes.
- [x] action/stake/vote.json rebuilt and bundled (b7b2094); every bundled
  circuit's VK equals chain-orch genesis (genesis sha256 77af7586...652d).
  orchardvectors regenerated against chain d9d2366: unchanged.
- [x] testDebugUnitTest 189/189; assembleDebug ok; 440 Android witnesses
  pass nargo execute. swift test 195/195; corecheck 149/149; xcodebuild
  simulator BUILD SUCCEEDED; 416 iOS witnesses pass nargo execute;
  ProverGate proves iOS stake, action and vote witnesses and the action
  fixture with the new circuits.
- [!] Pre-existing, not from this change: ProverGate testMembership fails,
  and so would on-device membership proving: Kind.membership (iOS
  PrivacyCircuitProver) and Kind.MEMBERSHIP (Android PrivacyProver) split 7
  public inputs, but membership has 8 since max_predecessor (313f9c8).
