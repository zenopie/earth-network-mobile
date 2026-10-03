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

## Open
- ProverGate (action/stake/membership via Swoir), EarthUI, app build 11.
