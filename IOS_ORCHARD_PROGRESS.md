# iOS Orchard port progress (mobile privacy/orchard)

Port of the iOS wallet to the Orchard-style chain, matching the Android port
(ANDROID_ORCHARD_PROGRESS.md). Formats the wallet defines are in
PRIVACY_FORMATS.md.

## Done (committed)
- EarthCore: Grumpkin (Jacobian ops, try-and-increment h2c with canonical y,
  R, memoized value bases, value commitments, Schnorr binding signer and
  verifier), stake hashes, stake self-mints, owner tags, stake ciphertext v3,
  OwnedStakeNote; ShieldedBundle/ShieldedAction/ValueBalance/StakeProof and
  every private msg on bundles (digest, bindingKey, checkBalance,
  stakeFields, sighash, fees); BundlePlan/BundleBuilder/NoteSelection/
  StakeSelection; StakePlan; ActionWitness/StakeWitness; engine on sighashes
  (re-lays and re-simulates up to 4 rounds); stake-tree sync (indexer
  /privacy/stake/*, store, WalletSync); PrivacyWallet ported method for
  method (sends, unshield with fee-from-amount, merge, fee bundles,
  delegate/restake/mergeStake/consolidateStake/undelegate/claimUnbonding,
  stake votes, positions by owner tag, note swaps, private LP add/remove);
  automation on stake notes.
- Tests (swift test, EarthCore): OrchardVectors, PrivateMsgs (every msg's
  proto + sighash + fee byte-exact), BundlePlanner, action + membership
  fixtures, keys (stake mints, otag salts), stake ciphertext golden (from an
  independent Python encryption), FakeChain with real binding-sig checks and
  the stake tree, WalletFlowTests (3 flows, two wallets). All 111 dumped
  witnesses (PRIVACY_TOML_OUT) pass `nargo execute` on circuits/action,
  stake, membership.

- ProverGate: PrivacyCircuitProver kinds action (6 publics), stake (11),
  membership (7); SRS reserved for stake (the largest privacy circuit) when
  nothing is provisioned, the largest passport circuit while a registration
  may follow. Tests prove the chain's action/membership fixtures and all 111
  dumped wallet witnesses (stake first), VKs byte-equal to chain-orch's
  genesis VKs; Swift action, stake and membership proofs ACCEPTED by the
  chain's Go verifier (tools/chainverify against chain-orch's
  barretenberg-go).
- EarthWallet DeviceProver and the EarthUI seam on proveAction/proveStake.
- EarthUI: no 3-note copy (Max unshield releases every note one bundle
  carries, fee from the amount), merges by restake for stake notes, staked
  ERTH owner-locked copy, private LP shares counted and withdrawn privately
  (removeLiquidityShielded), stake votes two notes a tx, positions by owner
  tag, consolidateStake before undelegate and lock.
- CURRENT_PROJECT_VERSION 11. EarthUI typechecks (build-ios.sh); the app
  builds for the simulator (xcodebuild, ARCHS=arm64, unsigned) with
  action.json and stake.json bundled.

## Chain fced976 + client audit (matching Android d52b564..af336e1)
- Protos: staking/dex `fee` fields gone (reserved numbers not written);
  MsgDelegate.amount (5), StakeProof.spc_ciphertext (8), MsgNoteSwap
  denom_in/amount_in (8/9), MsgAddLiquidityShielded.erth_amount (11).
- Sighash binds Bytes(memo), timeout_height, gas_limit after the digests
  (PrivateMsgs.TxFields); the engine fixes the gas limit (and memo; an
  unshield may carry one) before proving; UnsignedTx writes/decodes them;
  every proof checked to be exactly 14,656 bytes before broadcast.
- One fee rule (PrivateMsg.privateFee = bundles' uerth balance − movedUerth,
  saturating); only MsgClaimUnbonding pays fee_from_output; ANML swaps pay
  from an ERTH note (SwapScreen says so and disables without one).
- Registration binding over Bytes(ct_anml), Bytes(ct_erth) (both v2, written
  in prepareRegistration before the passport proof); /gas/register gets
  ciphertext_gas.
- Every chain mint carries v2 (NoteOut.mintToSelf) or the blind stake
  ciphertext (StakePlan.selfMint → spc_ciphertext); mint/stake-mint counters
  removed from PrivacyKeys and PrivacyState. Sync opens 217 (v1), 177 (v2
  with the row's amount), 153 (wallet stake), 177 stake (blind, row's
  denom/amount). Owner tags scanned to highest + 1024, extended past every
  match; a lock rescans first.
- Indexer: /privacy/status base `/privacy/<chain_id>/<genesis>`, (chain_id,
  genesis) change wipes the store, 404 → status re-read and retry once,
  halted → IndexerHalted; bodies capped at 8 MiB while streaming
  (EarthRest.boundedData, indexer and LCD); pages > 5000 rows and positions
  past u32 are WalletSync.Inconsistent (L7).
- C2 PendingRegistration persisted right after the broadcast, before any
  sync; every sync resolves it; a failure is kept and shown.
- C3 LCDChainRoots: note root by value (/earth/shielded/v1/roots/{hex}),
  identity/stake trees at the indexer's root height (x-cosmos-block-height),
  latest as fallback; mismatch wipes and throws ChainMismatch; unverified
  roots block every private tx (requireVerified) and show as the sync error.
- C4 PrivacyStore.open marks <root>/privacy and the wallet's directory
  isExcludedFromBackup (tested).
- L1 note/stake sums saturating or checked (addingReportingOverflow)
  throughout EarthCore and the EarthUI stake totals.
- L4 stakeVoteAll: one vote at a time, sync + random 20–120 s pause between
  (pause injectable). L5 Earn rates from one indexer /rates read, fallback
  to every bonded validator. L8 registration record note (value-0 v1 note in
  MsgRegister's fee bundle, memo "ER"|1|country|built_at|dsc_key; country from
  the DSC issuer C=) and restore from the mnemonic alone.
- Removed: GasTransparent, the transparent-gas UI and GasGrant `.transparent`
  (a note points to Unshield).
- Tests: vectors/fixtures copied from android (fced976), PrivateMsgs (all
  msgs + private fee + tx fields), Orchard sighash_tx, blind stake golden,
  pinned registration binding, FakeChain on the new rules, WalletFlowTests,
  AuditFixesTests (C1 restore after 30 abandoned preps, failed broadcasts
  and 40 failed locks; C2; C3; C4; L1; halted/relaunch; moved base; L4; L6;
  L7; L8). CURRENT_PROJECT_VERSION 12.
- Verified: swift test EarthCore 69/69; 281 dumped witnesses (211 action,
  61 stake, 9 membership) pass `nargo execute` and are proven by ProverGate
  (PRIVACY_TOML_DIR) with genesis-equal VKs; ProverGate swift test passes;
  EarthUI typechecks (build-ios.sh); xcodebuild simulator build (ARCHS=arm64,
  unsigned) succeeds.

## Open / notes
- Device prove times for action/stake not measured on a phone (Mac: ~150ms
  each through Swoirenberg).
- PrivacyWallet.quote is not exposed (Android has it; no screen uses it on
  either platform).
- A restore whose record-note country hint misses scans ~90k timestamps
  before the full country search (as on Android): ~30 s in a debug build.
- Backend events.py stake-note issue noted in ANDROID_ORCHARD_PROGRESS.md
  applies to iOS too (the wallet already reads the minted row's ciphertext).
