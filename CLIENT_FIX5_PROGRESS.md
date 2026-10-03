# Clients round 5: final chain formats (chain-orch privacy/orchard 4a663d5)

Worktree mobile-orch, branch privacy/orchard. Android first, then iOS, same
behavior. Web: app-orch (privacy/orchard). Never pushed.

## Circuits
- [x] membership: leaf commits to predecessor_at; public input max_predecessor (313f9c8).
- [x] membership.json rebuilt (nargo 1.0.0-beta.22) and bundled (Android assets; iOS reads the same folder). bb write_vk of every bundled circuit (action, membership, stake, vote) equals chain-orch networks/genesis.json; make privacy-vks-check passes.

## Android (done)
- [x] core formats: leaf predecessor_at (restore tries 0 and activated_at), MembershipWitness max_predecessor (8 inputs), handle scope, TAG_AFFILIATE/affiliateField, tx.proto (MsgRegister 11/12/15, MsgSetCaretaker 5, MsgBindHandle, MsgMoveHandle, MsgMoveCaretaker; MsgBindReferrer gone), sighashes (c98228d)
- [x] bounds per msg (PRIVACY_FORMATS 4g): L(lease) = floor_hour(now - lease - 1d - 600) for everyone (fresh registrants meet it; not 0, which would mark them fresh); no bound for renewals/changes of what is held, moves, releases; ballots BallotInputs.max_predecessor; propose removal today - 1d; claims unchanged
- [x] handles: bind/renew/change/release/move; HandleDirectory reads the whole directory (backend /handles stream, restart on height change; chain Query/Handles pages as fallback and as the check before money moves); never a per-handle query
- [x] pay a handle in Send (private send from notes, else MsgShield to the handle's address); confirm shows @handle + truncated address
- [x] Settings > Handle (status, expiry, renewal window, claim/renew/change/release, share link, reminder banner)
- [x] registration: "Referred by @handle" resolved at Continue, referral note bound (affiliate field); /gas/register body affiliate_handle/pc/ciphertext; referral links carry handles; referrer section, consent signer, WalletCrypto.signConsent removed
- [x] Switch identity (Identity screen): move handle and caretaker vote to another wallet's nullifiers, record them in its store (kept by its first sync), recovery phrase reveal + backup checkbox, then register there; intro text explains switching
- [x] automation: only matured unbonding claims; Reminders (ANML ready, caretaker expiring/lapsed, handle expiring/renewal) on Home; audit: no other background tx (stake-vote run continues a user's vote)
- [x] dex: deposit legs derived rounded up (SwapMath.depositLeg/deposit, pinned to x/dex mulDiv/mulDivUp vectors); ChainErrors explains 1120 dex, 1121/1122/1125/1126 personhood
- [x] orchardvectors regenerated against 4a663d5; FakeChain models handles/leases/moves/predecessors; HandlesTest + AutomationTest + SwapMathTest; testDebugUnitTest (all) and assembleDebug pass; 231 witnesses pass nargo execute; versionCode 44 (1.0.37)

## iOS
- [ ] (as Android)

## Docs
- [x] PRIVACY_FORMATS.md: 4a663d5 summary, 3a (affiliate field, gas body, leaf match), msg table, 4d superseded, new 4g, 4c/4f automation, 5 parity

## Web
- [ ] pay a handle, handle lookup page, referrer pages removed
