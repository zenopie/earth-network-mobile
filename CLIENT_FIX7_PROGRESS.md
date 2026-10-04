# Clients round 7: audit 6 + staking wave formats (chain-orch privacy/orchard 48b631c)

Worktree mobile-orch, branch privacy/orchard. Android first, then iOS, same
behavior and bytes. Feature freeze: adopt formats, remove features, add
nothing. User rule: no background transactions, no automatic fee spending;
every tx comes from a user tap. Never pushed. Formats: PRIVACY_FORMATS.md.

Chain sources read: ORCHARD_DESIGN §17-18, CHANGELOG [Unreleased],
STAKING_WAVE_PROGRESS.md, FIX_ROUND6_PROGRESS.md, proto/earth/shieldedstaking.
Backend (read only, being updated by another agent): backend-orch README
(handles row[5] owner, notes format 2).

## Items
- [x] 1 registration binding names the chain id; reg_pinned 148b3513...4159
- [x] 2 errors 1127 (switch under another DSC), 1113 (signer's daily cap)
- [x] 3 undelegate pays out by itself (pc 6, ciphertext 7); claim flow removed
- [x] 4 one stake vote per validator (4 slots, 10 public inputs); vote run removed
- [x] 5 gas: every private path simulate + 10% (<= 5x); vote gas estimate
- [x] 6 handle owner end to end (chain query field 6, backend row[5])
- [x] 7 send-disabled denoms on dex note swaps and private delegation
- [x] 8 audit: no background tx, no automatic fee spend

## Android (done)
- [x] 2 ChainErrors 1127 / 1113 / bank 5 at every pool edge; GasGrant maps
  the chain's text (cfef344)
- [x] 1, 3, 4, 5 formats: binding chain id; MsgUndelegate pc/ciphertext;
  MsgClaimUnbonding and fee_from_output removed; MsgStakeVote
  vote_nullifiers x4; VoteWitness 4 slots; PrivacyProver VOTE 10; vote gas
  estimate; orchardvectors regenerated against 48b631c (f36260b)
- [x] 3, 4, 8 wallet: undelegate pays out by itself, pending_unbonds (due
  time from chain-wide timing, no per-id query), automation deleted;
  stakeVote per validator (<= 4 notes, RoundVoteWeight(sum), 1119 retry
  within the action), StakeVoteController and consolidateStake deleted (4ff34c1)
- [x] UI: one sheet per validator/position, parts-or-merge choice, pending
  unstaking holdings, no automation loop (7e459ad)
- [x] 5 gas: every private path simulates (+10%, >= 20,000); 12.5M/10M are
  sheet fee estimates only. FakeChain now refuses gas_limit > 5x used on
  every committed tx (all suites pass, ratios 1.0-1.2)
- [x] 6 handle owner: chain query field 6 and backend row[5] parsed since
  round 6 (Audit6Test); adoption by owner; FakeChain serves the bind
  nullifier as owner end to end
- [x] 7 send-disabled: FakeChain refuses note swaps / delegation / unshield
  of a disabled denom; the error is explained
- [x] 8 audit: every broadcast is a TxController sheet's run; sync,
  reminders and payout bookkeeping send nothing (Fix7Test.syncNeverSendsAnything)
- [x] testDebugUnitTest all pass; assembleDebug ok; 505 witnesses
  (PRIVACY_TOML_OUT) pass nargo execute (1.0.0-beta.22), 12 of them
  four-slot vote witnesses; versionCode 45 (1.0.38)

- [x] UI: the parts-or-merge choice is asked before the first vote of a
  run (0a6b3f9)

## iOS (done, as Android)
- [x] errors (1b66ac0), formats + ProverGate vote 10 / largest = vote
  (7f82e6e), wallet (58643de), UI (e17393a), tests (d067219)
- [x] swift test 212/212; corecheck 149/149; build-ios.sh ok; xcodebuild
  simulator (ARCHS=arm64, no signing) BUILD SUCCEEDED; 481 witnesses
  (PRIVACY_TOML_OUT) pass nargo execute; build 18
- [x] ProverGate (PRIVACY_TOML_DIR): all 481 iOS and 505 Android wallet
  witnesses prove and verify (vote kind 10 inputs); every VK equals chain
  genesis (unchanged from 48b631c to 0c60692)
- [x] audit 8: every tx is a TxController sheet's run (claim, register,
  sends, staking, dex, handles, moves, votes); the automation task and the
  vote controller are gone

## Chain notes (for the chain)
- "Merge notes first (one vote)" cannot make one vote on a proposal
  already in voting: a merge after the snapshot leaves the spent notes
  eligible and the merged note outside the snapshot root. The wallet offers
  the merge for later proposals and says so; the chain could only change
  this with a different snapshot rule.
- A restored wallet learns its earlier votes only from 1119 refusals, and
  the chain names one vote nullifier per refusal: up to four simulations to
  re-lay one part (free, within the user's one action). Naming every used
  vnf of the msg in the error would make it one.
- Query/UnbondPayout is per id, and the id is on the undelegate tx's event:
  asking for it (let alone polling) ties the asking IP to that
  undelegation. Wallets keep their own record instead (due time from
  Query/Epoch + params at confirmation). If a pending view from the chain is
  wanted, a whole-list stream (indexer) would be the private shape.
- MsgUndelegate still proves and binds spc_mint though nothing is minted to
  it (wallets pass a throwaway pc). Harmless; noted only because it is a
  public value with no use.
- Chain HEAD moved past 48b631c to 0c60692 (private redelegation,
  MsgRedelegate): not adopted here (feature freeze); VKs unchanged.
