# Clients re-audit fixes (K1, K5-K12, info) — progress

Worktree mobile-orch, branch privacy/orchard. Android first, then iOS, kept
byte-identical in formats and behavior. PRIVACY_FORMATS.md updated with each
format or behavior change.

## Android
- [x] K12 amounts: u64 parsed unsigned and bounded to 2^63-1 (Amounts.kt);
      display sums saturate, tx sums checked; AmountsTest.

## iOS
(not started)
