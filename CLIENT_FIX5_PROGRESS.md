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

## iOS (done, as Android)
- [x] EarthCore: PrivacyHash (leaf predecessor_at, handle scope, affiliate field, noBound), PrivateMsgs (MsgRegister 11/12/15, MsgSetCaretaker 5, MsgBindHandle/MsgMoveHandle/MsgMoveCaretaker; referrer + consent removed), MembershipWitness (8 inputs), WalletSync (match with predecessor 0 or activated_at, budgets doubled), PrivacyStore (handle, moves, caretaker expiry; never-synced store keeps what a switch moved in), PrivacyWallet (bounds, bindHandle/releaseHandle/moveHandle/moveCaretaker/newOwner/adoptMoved, prepareRegistration(referrer:)), HandleDirectory actor (backend stream + chain pages + check before money moves), Reminders, PrivacyAutomation (only unbonding claims), GasGrant body, SwapMath.depositLeg/deposit, ChainErrors (e661979)
- [x] tests: FakeChain ported, HandlesTests, reminders/no-automation, deposit vectors; swift test 171 pass; corecheck 149/149; 389 witnesses (Android + iOS) pass nargo execute (f49cefc)
- [x] EarthUI: Send pays @handle (private from notes, MsgShield from the account), Handle screen (Settings and Identity), Switch identity (moves, recovery phrase behind ConfirmIdentity, then register there), referral by @handle at registration, reminder banners on Home, deposit legs rounded up, chain errors explained; automation without referrer consent; build 17; build-ios.sh ok; xcodebuild simulator (ARCHS=arm64) BUILD SUCCEEDED (a1ceef6)

## Web
- [x] app-orch privacy/orchard 05a0271..4fa9191 (see app-orch/CLIENT_FIX5_PROGRESS.md): referrer page removed, pay a handle (Shield), Handles page, legs rounded up, 1120, ballot max_predecessor; build + check:privacy/tx/forms/handles pass. Backend /handles needs CORS for the browser to use the stream (LCD fallback meanwhile).

## Notes for other repos
- backend-orch: /gas/register takes affiliate_handle/affiliate_pc/affiliate_ciphertext (matches); its /handles stream needs CORS for the web app.
- Bound choice: every wallet proves the hour-rounded lease bound L (fresh registrants meet it) rather than max_predecessor = 0, so a proof does not mark a fresh identity (PRIVACY_FORMATS 4g).

## Docs
- [x] PRIVACY_FORMATS.md: 4a663d5 summary, 3a (affiliate field, gas body, leaf match), msg table, 4d superseded, new 4g, 4c/4f automation, 5 parity

## Web
- [ ] pay a handle, handle lookup page, referrer pages removed
