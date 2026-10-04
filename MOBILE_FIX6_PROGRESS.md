# Mobile audit round 6 fixes (audit6-mobile.md, mobile-orch 9accb7a, chain-orch c0ad1dd)

Worktree mobile-orch, branch privacy/orchard. Feature freeze: fixes only.
Android first, then iOS, same behavior and bytes. Never pushed. Chain not edited.

## Android
- [x] M1 PIN change behind a fresh unlock (ConfirmUnlockDialog, counted by UnlockAttempts); SessionManager.changeSecret(old, new) re-checks the old secret; K2 FLAG_SECURE on the dialog
