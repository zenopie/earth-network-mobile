# Android Orchard port progress (mobile privacy/orchard)

Port of the Android wallet to the Orchard-style chain (chain worktree
privacy/orchard d083cc5: ORCHARD_DESIGN.md sections 12-13). Formats the
wallet defines are in PRIVACY_FORMATS.md.

## Plan
1. Crypto: Grumpkin, hash-to-curve, value commitments, binding signer;
   golden vectors from chain zk/orchard (android/tools/orchardvectors).
2. Bundle planner + action/stake witnesses + engine; every private msg.
3. Stake tree sync (indexer client defined against chain events).
4. UI: existing screens; no 3-note Max; LP share notes; stake non-transferable.
5. Tests: vectors, planner, two-wallet flow on FakeChain, nargo execute.

## Done
- circuits: action.json + stake.json shipped, transfer.json removed.

## Next
- Grumpkin + vectors.
