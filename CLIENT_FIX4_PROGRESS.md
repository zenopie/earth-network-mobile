# Clients audit round 4 fixes — progress

Worktree mobile-orch, branch privacy/orchard. Android first, then iOS, same
behavior. Web: app-orch (its own CLIENT_FIX4_PROGRESS.md). Auditor PoCs:
/private/tmp/claude-501/audit4/clients/{vote,ios/A,ios/B,ios/C,android}.

## Android
- [x] H1 identity row times bounded (>= 2025-01-01, <= LCD tip time + 1h; else Inconsistent); search candidates checked (addExact, timeOk); claimOpensAt checked arithmetic.
- [x] M1 every indexer height (rows, cursors, synced_height, roots) <= LCD tip + 10 (re-read once) else Inconsistent; note root height == LCD RootRecord.height; note tree at the claimed synced height (pinned Query/Tree) must be the served one; behind-tip from the LCD tip; pending released only when txStatus(hash) is MISSING/FAILED past timeout_height (COMMITTED but unseen -> unverified).
- [x] M2 cover set decoys from a persisted sample of identity row heights (<= tip), then uniform <= tip.
- [x] M3 snapshot from LCD Query/Snapshot only; nf fetch bounded by the LCD nf_size; SpentBeforeSnapshot only when sync agrees, else an error.
- [x] M5 records matched only after verifyRoots of the same sync; IdentityRecord.verified; reset keeps only a verified identity; a match at the same index replaces.
- [x] M7 JSON nesting <= 64 refused before parsing (LCD + indexer); automation/vote run catch Throwable.
- [x] L1 stakeVotes kept across a same-chain reset; L2 caches dropped on genesis change, the vote run stops when its persisted run is gone; L3 rates from the LCD snapshot; L4 (M3); L5 controller joins the suspended job, a position's vote persisted at acceptance.
- [x] Redirects off (EarthRest, indexer). PoW bits cap 24 (refused above). Registration fee reconfirm (withShownFee, FeeAboveQuote re-shows). consolidateStake <= 2 merges per confirmation, 15-45 s apart. Spends marked pending before broadcast (local SHA-256 hash; TxRejected/ConnectException unmark). Proof timing logs debug only. Claim offset persisted per day. 1119 needs codespace shieldedstaking. eligible excludes spends in the snapshot block. Settings: Forget private data (PrivacyStore.delete).
- [x] Audit4Test (25): PoCs 1-5 (android), vote PoC, iOS A4/C equivalents.
- [x] Backend paging rule: limit 1000 always (100/1000 only), aligned from_pos/from_index, held rows dropped (notes/stake notes checked against the held leaf), nullifier-tree from 0, height pages <= 5000; 503/429 backoff (Retry-After or 1,2,4,8 s, max 30, 4 retries). FakeChain enforces alignment. Audit4Test 28.
- [x] PRIVACY_FORMATS.md (3a restore, 4 pending, 4e snapshot/nf tree/1119/eligible, 4a paging/heights/redirects/depth, 4b note root height + claimed height, 4c forget, new 4f). versionCode 43 (1.0.36). testDebugUnitTest + assembleDebug pass.

## iOS
- [ ] all of the above
