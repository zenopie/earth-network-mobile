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

## Open / notes
- Device prove times for action/stake not measured on a phone (Mac: ~150ms
  each through Swoirenberg).
- PrivacyWallet.quote is not exposed (Android has it; no screen uses it on
  either platform).
