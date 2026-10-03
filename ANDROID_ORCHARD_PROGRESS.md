# Android Orchard port progress (mobile privacy/orchard)

Port of the Android wallet to the Orchard-style chain. Now on chain
privacy/orchard **fced976** (ORCHARD_DESIGN.md sections 12-14, CHANGELOG
Unreleased, FIX_{SHIELDED,PERSON,STAKING}_PROGRESS.md). Formats the wallet
defines are in PRIVACY_FORMATS.md (updated first, for the iOS port).

## Done (committed)
- Earlier (chain d083cc5): circuits action + stake, Grumpkin/binding sigs,
  BundlePlan/StakePlan, every private msg, stake tree sync, UI, FakeChain,
  WalletFlowTest, nargo/bb parity. See git history before d52b564.
- fced976 formats:
  - Protos: staking/dex `fee` fields reserved; MsgDelegate.amount (5),
    StakeProof.spc_ciphertext (8), MsgNoteSwap.denom_in/amount_in (8/9;
    fee_from_output 6 reserved), MsgAddLiquidityShielded.erth_amount (11).
  - Sighash binds Bytes(memo), timeout_height, gas_limit after the digests
    (PrivateMsgs.TxFields); the engine fixes the gas limit (and memo; an
    unshield may carry one) before proving; UnsignedTx carries them; every
    proof checked to be exactly 14,656 bytes.
  - One fee rule (PrivateMsgs.privateFee = uerth balance − what the msg
    moves); only MsgClaimUnbonding pays fee_from_output; swaps of ANML pay
    from an ERTH note (SwapScreen says so, disables without ERTH).
  - Registration binding over Bytes(ct_anml), Bytes(ct_erth); both notes v2,
    written before proving; /gas/register gets ciphertext_gas (v2).
  - One note-discovery rule: every chain-minted note carries v2 (pool) or
    the blind stake ciphertext (spc_ciphertext); self-mint and stake
    self-mint counters removed (PrivacyKeys, PrivacyState). Sync opens 217
    (v1), 177 (v2 with the row's amount), 153 (wallet stake ct), 177 stake
    (blind stake with the row's denom/amount).
  - Removed: GasTransparent, the "Get ERTH for transparent fees" UI (a note
    pointing to Unshield instead), gasScope/gasTransparentSignal. No
    /gas/android or /gas/challenge code existed.
  - Indexer: /privacy/status → base `/privacy/<chain_id>/<genesis>`;
    (chain_id, genesis) change wipes the store; 404 → status re-read and
    retry (IndexerBaseMoved); halted → IndexerHalted; bodies capped 8 MiB
    (indexer and LCD), pages capped at 5000 rows.
  - Vectors regenerated (android/tools/orchardvectors/gen.sh, fced976):
    tx-field sighash, every msg's proto/sighash/total and private fee,
    pinned registration binding, blind note + blind stake goldens,
    unsigned tx with memo/timeout; fixtures (orchard 3-action bundle with tx
    fields, membership).
- Audit fixes:
  - C1: no counters; AuditFixesTest restores after 30 abandoned
    registration preps, failed broadcasts and 40 failed locks.
  - C2: PendingRegistration persisted right after the broadcast, before any
    sync; resolved by every later sync; failure kept and shown.
  - C3: WalletSync.verifyRoots against ChainRoots (LcdChainRoots: note
    root by value via /earth/shielded/v1/roots/{root}; identity and stake
    trees at the indexer's root height via x-cosmos-block-height, latest as
    fallback); mismatch wipes and throws ChainMismatch; unverified roots
    block every private tx (PrivacyWallet.requireVerified) and surface in
    the wallet's sync error.
  - L4: stakeVoteAll one vote at a time, sync + random 20-120 s pause
    between (pause injectable).
  - L5: Earn rates from one indexer /rates read (all validators), falling
    back to every bonded validator alike.
  - L6: response size bounds (above). L7: positions past u32 and oversized
    pages are WalletSync.Inconsistent, not crashes.
  - L8: registration record note (value-0 v1 note in MsgRegister's fee
    bundle, memo "ER"|1|country|built_at|dsc_key); sync matches it to the
    identity leaves at its height; restore finds the identity from the
    mnemonic alone (AuditFixesTest).
  - Groundworks: position weight shown as derth × rate (already so).
- Tests: OrchardVectorsTest, PrivateMsgsTest (all fced976 msgs + private
  fee), FixtureWitnessTest, BlindNoteTest (blind stake golden), FakeChain on
  the new rules (required 177-byte cts, spc_ciphertext rule, fee rule, tx
  fields in the sighash, exact proof length, history for pinned queries,
  switches zero the old leaf), WalletFlowTest, AuditFixesTest; 281 dumped
  witnesses pass `nargo execute` (action/stake/membership).
- versionCode 39 (1.0.32). testDebugUnitTest + assembleDebug pass.

## Open / notes
- Backend (backend-orch HEAD ae92a00) services/privacy/events.py refuses a
  shieldedstaking_stake_note with both spc/denom/amount and a ciphertext,
  which fced976 emits for every minted stake note (stake_tree.go:155):
  the indexer would halt on the first delegation. Needs a backend fix
  (store the ciphertext with the minted row); the wallet already reads it.
- Identity restore needs the record note: registrations made by older apps
  cannot be restored from the mnemonic (register again).
- Top-level tools/privacyvectors (transfer era) is stale; CLAUDE.md names it.
- Phone prove times for action/stake unmeasured (PrivacyProverDeviceTest).
