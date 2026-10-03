# Clients round 5: final chain formats (chain-orch privacy/orchard 4a663d5)

Worktree mobile-orch, branch privacy/orchard. Android first, then iOS, same
behavior. Web: app-orch (privacy/orchard). Never pushed.

## Circuits
- [x] membership: leaf commits to predecessor_at; public input max_predecessor (313f9c8).
- [x] membership.json rebuilt (nargo 1.0.0-beta.22) and bundled (Android assets; iOS reads the same folder). bb write_vk of every bundled circuit (action, membership, stake, vote) equals chain-orch networks/genesis.json; make privacy-vks-check passes.

## Android
- [ ] core formats (leaf, membership witness, scopes, affiliate field, protos, sighashes)
- [ ] predecessor bounds per msg
- [ ] handles (bind/move, directory, pay a handle, settings, reminders)
- [ ] registration referral by handle; referrer code paths removed
- [ ] caretaker move; identity switch flow
- [ ] no automatic fee-spending actions (reminders)
- [ ] dex deposit legs rounded up; 1120
- [ ] vectors, tests, assemble, versionCode

## iOS
- [ ] (as Android)

## Web
- [ ] pay a handle, handle lookup page, referrer pages removed
