# Clients round 8: stake note v2, private redelegation, the slash debt (chain-orch privacy/orchard dff3a9b)

Worktree mobile-orch, branch privacy/orchard. Android first, then iOS, same
behavior and bytes. Feature freeze, except "Move stake" (MsgRedelegate),
which the user approved. No background transactions: every tx is sent on a
user tap. Never pushed. Formats: PRIVACY_FORMATS.md.

Chain sources read: ORCHARD_DESIGN §19-20 (§20 supersedes parts of §19),
CHANGELOG [Unreleased], STAKING_WAVE_PROGRESS.md change 4,
proto/earth/shieldedstaking, zk/debt (Go-Noir parity), x/shieldedstaking
keeper (credit checks, moves, debt root, gas). Circuits f02ec61, 0ec5e4c
(stake 16 inputs, vote 9) already bundled. Backend-orch 74e8f7f (stake rows
format 2, {base}/debt_rows).

Chain wave 7 (b46a4bb, audit 7) adopted on top: every stake proof names
Query/DebtTree's current clear_before and root (chain window: an hour below
ClearBefore(now); 0 only while the block time is below the window, which no
real chain sees); owner-tag salts fresh off positions (a lock's is a new
counter's salt_c, reused by the position's msgs); a move arrives whole only
out of an unbonded source's queue, else u - 1001; MsgRedelegate gas follows
the pair's record (simulated; the fee cap allows the worst). Vectors and
fixtures regenerate byte-identical at b46a4bb.

## Items
- [x] 1 prover public inputs: STAKE 16, VOTE 9 (ProverPublicInputsTest)
- [x] 2 stake note v2: cm with label, 201-byte ciphertext (earth.stake.v2,
  version 0x04), StakeProof fields 9-15, StakeFields order
- [x] 3 one note per validator: delegate merges (padding input on a first
  delegation), quote at the live rate less 10 ppm on the confirm sheet,
  zero note on a full exit, MsgRestake kept for a second note (user tap)
- [x] 4 Move stake (MsgRedelegate dst_derth 6, move_time 7 = latest block
  time); credit merges into an unlabelled note only; labels tracked;
  exposed derth refused up front with its date; debt tree from the
  indexer's /debt_rows or Query/DebtTree (checked against the chain's
  root); haircut on the confirm sheet; Move stake action and sheet
- [x] 5 votes: two slots, spent notes' openings kept (never pruned), a
  labelled note at its post-slash value, debt_root last in the sighash
- [x] 6 events/queries: redelegate event (credited, move_key, move_time) in
  FakeChain; Query/Redelegation never used; no per-id query (Query/Move
  never asked; the debt tree read whole)
- [x] 7 dead code: 11-input stake witness, 4-slot vote, spc_mint/blind stake
  ciphertext, chain-minted stake rows removed

## Android (done)
- [x] formats, wallet, sync, FakeChain, vectors regenerated against dff3a9b
  (debt tree, labels, public input layouts, StakeFields) (14c0cd1)
- [x] Fix8Test + plain errors for no-cost refusals (fc14f02)
- [x] UI: Move stake, holdings with held-in-place stake, quotes and haircuts
  on the sheets (078371b)
- [x] testDebugUnitTest all pass (228); assembleDebug ok; 553 witnesses
  (PRIVACY_TOML_OUT: 403 action, 100 stake, 36 membership, 14 vote) pass
  nargo execute (1.0.0-beta.22); versionCode 46 (1.0.39) (fffa0fe)

- [x] stake selection test; flaky redelegate-event lookup fixed (897b34b)
- [x] b46a4bb: move quote (unbonded queue), FakeChain clear_before window,
  fee cap for the redelegation record, stale clear_before explained
  (fff8136, 09ed831); 230 tests pass, assembleDebug ok

## iOS (done)
- [x] formats, wallet, sync, b46a4bb rules (c3786bf)
- [x] FakeChain, Fix8Tests, vectors (8e27465)
- [x] UI: Move stake sheet, quotes and haircuts, held-in-place stake,
  merge on tap (9f114ef)
- [x] swift test 232 pass; corecheck 149/149; build-ios.sh and xcodebuild
  simulator (arm64, unsigned) succeed; build 19 (228e677)

## Witnesses
- [x] nargo execute (1.0.0-beta.22): Android 553 (403 action, 100 stake,
  36 membership, 14 vote), iOS 529 (385, 100, 30, 14): all pass
- [x] ProverGate PRIVACY_TOML_DIR: every stake and vote witness of both
  platforms proved and verified with the genesis-equal VKs, plus 8 of each
  kind

## Chain notes (for the chain)
- The vote circuit cannot pad an unused slot (its vnf is 0), so a vote's
  slot count is public and a two-slot vote hints at a labelled note or a
  second note. A padding vnf (as lane A's padding nullifier) would make
  every vote alike.
- Quotes ask Query/Validator (and x/staking's status for a move's source)
  by validator id when the user reviews: it ties the asking IP to an
  intent to stake at that validator shortly before a public msg naming it.
  A whole-set read (all validators' books in one query) would remove it.
- The wallet stake ciphertext has no memo, so the unlock record (K11) is a
  value-0 pool note in the unlock's fee bundle: one more action per unlock.
- The stake rows stream carries no denom, so a restored wallet names derth
  by AssetID over the whole validator list (every status); a validator
  removed from x/staking after its last note leaves that note unnamed.
- Spent stake notes are never pruned (votes on older snapshots need their
  openings): storage grows with every staking tx.
- A redelegation's gas depends on the pair's x/staking record, which the
  wallet does not ask about before sending (it would tie the IP to the
  pair); simulation prices it, and the fee cap allows the worst case, so a
  move's cap is about 15M gas higher than it would be.
