# Clients re-audit fixes (K1, K5-K12, info) — progress

Worktree mobile-orch, branch privacy/orchard. Android first, then iOS, kept
byte-identical in formats and behavior. PRIVACY_FORMATS.md updated with each
format or behavior change.

## Android
- [x] K12 amounts: u64 parsed unsigned and bounded to 2^63-1 (Amounts.kt);
      display sums saturate, tx sums checked; AmountsTest.

- [x] K10 indexer base: exactly /privacy/<chain_id>/<genesis> (strict charset), requests pinned to the host, null chain id refused; IndexerBaseTest (local HTTP server, hostile bases).

- [x] K8 a note root the chain no longer holds is unverified (no wipe); only positive contradictions wipe; local tree larger than the indexer's latest is inconsistent.
- [x] K9 LCD trust documented (4b); pinned reads need the echoed x-cosmos-block-height; unpinned reads verify only equal trees; 4+4 sampled nullifiers checked via Query/Nullifier; indexer >30 blocks behind the tip is unverified; home screen shows the reason next to the private side. ReauditFixesTest; AuditFixesTest forged-notes now unverified, forged identity tree a mismatch.

- [x] K6 genesis switch confirmed by the LCD (node_info network + block 1 hash) before any wipe; confirmed switch keeps identity record, passport nullifier, pending registration, otag counters and re-verifies the leaf; unconfirmed claim wipes nothing (GenesisUnverified). ReauditFixesTest.

- [x] K7 PrivateChain.broadcast(accepted:) runs at CheckTx code 0: spends marked pending and the registration recorded by tx hash before awaitCommit; leaf/activated_at filled from the result or looked up by hash (PrivateChain.tx) on later syncs; failed-in-block kept as failure. Also: WalletSync stamps/releases pending with the wallet clock. ReauditFixesTest (timeout + killed app; failed in block).

- [x] K1 record memo v2 with 16-byte tag H(Tag("earth.rectag"), nk, dsc_key, U64(built_at)) checked before any search (v1 ignored); records keep their block's leaves, status, cursor and work (persisted); match by the LCD's block time first (<=677 hashes a leaf), else a resumable outward search capped at 50k hashes a sync and 4M a record, once a sync after all passes. Golden vector pinned; forged-spam, bounded-resume tests (ReauditFixesTest).

- [x] K11 unlock re-mint memo "EU"|1|counter|tag(earth.unlocktag); sync raises closed_otag_max; scans and new locks start past closed counters (documented choice). ReauditFixesTest.

- [x] K5 StakeVoteController is the only path: notes + positions shuffled, sync + 20-120 s random pause between, off-screen (activity VM, coroutine delay, no lock held), progress + Stop on the proposal, persisted run resumed on unlock; stakeVoteAll/stakeVoteNotes and the inline UI loop deleted; tests drive the controller the UI calls (spacing, cancel, resume).

- [x] Chain round 2 rules: engine checks 217-byte action ciphertexts and the two stake ciphertext slots before broadcast; FakeChain enforces canonical tx/body/auth/msg bytes, those slots, and one use per registration binding.

- [x] Info: claimAnml refuses day 0 (parity with the iOS underflow fix). versionCode 40 (1.0.33). testDebugUnitTest (all) + assembleDebug pass.

## iOS (EarthCore, byte-identical formats; golden record memo matches Android)
- [x] K1 tagged record memo v2, chain block time first, bounded resumable fallback (searchBudget, persisted RegRecord state).
- [x] K6 switchChain via LCDChainRoots.chainIdentity; switchGenesis keeps identity/pending/otag.
- [x] K7 PrivateChain.broadcast(accepted:) + tx(hash); pending by hash at acceptance; fillPendingRegistration.
- [x] K8/K9 verifyRoots as Android; EarthRest.getEcho (echoed height, no silent latest-as-pinned); nullifier sample; staleBlocks.
- [x] K10 HTTPPrivacyIndexer.validBase, no force unwrap on paths, host pinned; nil chain id refused.
- [x] K11 unlock memo, closedOtagMax.
- [x] K12 values > Int64.max ignored (as Android); derthValue saturates at Int64.max.
- [x] K5 StakeVoteController (Task, cancellable, progress, persisted resume); stakeVoteAll/stakeVoteNotes and the wallet pause removed.
- [x] Round 2: engine checkShape; FakeChain canonical re-encode, slots, single-use binding.
- [x] Info: claimAnml(day: 0) refused.
- swift test: 88/88 (ReauditFixesTests 19).
- [x] EarthUI: proposal stake vote goes through AppModel.stakeVotes (StakeVoteController): first cast is the sheet's result, progress + "Stop voting" on the proposal, resume on unlock, suspend (kept for resume) on lock; wallet home shows the private-side notice (unverified/indexer behind). build-ios.sh typechecks; xcodebuild simulator (ARCHS=arm64) BUILD SUCCEEDED. CURRENT_PROJECT_VERSION 13.
