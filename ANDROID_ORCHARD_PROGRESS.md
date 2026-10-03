# Android Orchard port progress (mobile privacy/orchard)

Port of the Android wallet to the Orchard-style chain (chain worktree
privacy/orchard d083cc5: ORCHARD_DESIGN.md sections 12-13). Formats the
wallet defines are in PRIVACY_FORMATS.md.

## Done (committed)
- Circuits: action.json + stake.json shipped (nargo 1.0.0-beta.22),
  transfer.json removed. PrivacyProver: ACTION (SRS 2^14, 6 publics), STAKE
  (2^15, 11 publics), MEMBERSHIP.
- Crypto (privacy/zk/Grumpkin.kt): Jacobian point ops, try-and-increment
  hash-to-curve with canonical y (Tonelli-Shanks), R, value bases (memoized),
  value commitments, Schnorr binding signer/verifier (bsk = sum rcv mod n,
  k = SHA-512(bsk||sighash||rnd) mod n). Byte-exact vs zk/orchard.
- Golden vectors: android/tools/orchardvectors (main.go + gen.sh, run
  against chain d083cc5): Poseidon2, zk/privacy incl. stake hashes, merkle,
  h2c, bases, point add/mul, cv, binding sig (rnd 0 and 1), digest, every
  private msg's proto + sighash + total fee, StakeFields, unsigned tx;
  fixtures: membership + tools/orchardfixtures 3-action bundle.
- Planner (privacy/tx/BundlePlan.kt): NoteOut (with denom), ActionSpend,
  BundlePlan (balances from actions, cvs, digest inputs, prove + sign),
  BundleBuilder.plan (any number of notes, multi-asset, change per denom,
  pad to 2, max_actions_per_bundle), NoteSelection.cover, StakeSelection.
- StakePlan + StakeWitness; ActionWitness; PrivateMsgs (bundles, digest,
  bindingKey/checkBalance, StakeFields, sighash per msg, fees); engine on
  sighashes (re-simulates when the action count changes).
- Protos mirrored: Bundle/Action/ValueBalance, MsgSend, fee bundles,
  staking msgs with StakeProof (+ MsgRestake), dex MsgNoteSwap,
  MsgAddLiquidityShielded (share_pc), MsgRemoveLiquidityShielded.
- PrivacyWallet: send, unshield (fee from amount at Max), merge, register,
  claim, caretaker, referrer, votes, delegate, restake/mergeStake,
  consolidateStake, undelegate, claimUnbonding (no bundle), stake votes
  (two notes a tx), positions by owner tag, note swaps, private LP add/remove.
- Stake tree sync (WalletSync): stake notes (minted by spc, created by stake
  ciphertext), stake nullifiers, stake root check; client matches the
  backend's /privacy/stake/* API.
- UI: no 3-note messaging (Max = every note one bundle carries), stake
  sheet notes owner-lock, Notes screen merges stake by restake, LP screens
  count share notes, private withdrawal of share notes, positions copy.
- Tests: OrchardVectorsTest, PrivateMsgsTest, FixtureWitnessTest (action
  bundle), BundlePlannerTest (1..20 notes, multi-asset, cap), FakeChain on
  bundles + stake tree with real binding-sig checks, WalletFlowTest (3
  flows, two wallets), all 111 dumped witnesses pass `nargo execute`; an
  action and a stake witness bb prove + verify against the chain's VKs.
- versionCode 38 (1.0.31). testDebugUnitTest + assembleDebug pass.

## Open / notes
- Top-level tools/privacyvectors (transfer era) is stale; outside android/,
  left untouched. CLAUDE.md still names it.
- Phone prove times for action/stake unmeasured (PrivacyProverDeviceTest).
