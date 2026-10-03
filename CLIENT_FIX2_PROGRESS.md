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

## iOS
(not started)
