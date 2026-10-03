# Privacy formats the wallet defines

The privacy chain (x/shielded, x/personhood, x/assembly, x/shieldedstaking,
x/dex) pins every hash, tag, sighash, bundle and proof layout in `zk/privacy`
and `zk/orchard` (Orchard-style bundles: ORCHARD_DESIGN.md sections 12-14 of
the chain), and the Android code reproduces those byte for byte
(`android/.../privacy/zk`, `privacy/tx`, tested against vectors generated from
the chain by `android/tools/orchardvectors/gen.sh <chain checkout> [ref]`,
last run against chain privacy/orchard **fced976**; `tools/privacyvectors` is
the retired transfer-circuit generator).

What the chain never sees, and so does not pin, is defined here. Every item
is implemented in `android/app/src/main/java/network/erth/wallet/privacy/`
and pinned by a golden test; the iOS port and the web app must match.

**Changes for chain fced976 (2026-10-02), summary.** Every chain-minted note
now carries a 177-byte amount-blind ciphertext, so the self-mint counters
(`mint-rho/rcm`, `stake-rho/rcm`) are gone: every note is found by trial
decryption. The sighash binds the tx's memo, timeout_height and gas_limit.
The registration binding covers both note ciphertexts. Staking and dex msgs
lost their `fee` fields (one fee rule, §4). New: the registration record
note (§3a), indexer URL scheme and root verification against the chain
(§4a, §4b).

## 1. Keys (wallet-only)

From the BIP-39 seed (empty passphrase), BIP-32 hardened derivation:

    m/2026'/118'/0'/0'   id_secret
    m/2026'/118'/0'/1'   nk
    m/2026'/118'/0'/2'   ek (x25519)

For each, with k the child's 32-byte private key:

    s = HMAC-SHA512(key = "earth.privacy.v1", data = label || k)
    id_secret = s mod p      (label "id_secret")
    nk        = s mod p      (label "nk")
    ek        = s[0..32]     (label "ek"; X25519 clamps it)

p is the BN254 scalar modulus. `idc = H(TAG_ID, id_secret)` and
`owner_pk = H(TAG_OWNER, nk)` are the chain's.

**No self-mint counters (removed for fced976).** A note the chain mints to
this wallet (registration ANML and reward, ANML claim, gas grant, shield,
swap output, LP shares/refunds/withdrawal legs, unbonding payout) is named by
a pc of fresh random rho and rcm and carries a v2 ciphertext of them to the
wallet's own address; a stake note the chain mints (delegation's derth,
undelegation's claim, stake vote's re-mint, unlocked position) is named by a
`spc_mint` of fresh random rho and rcm and carries the blind stake ciphertext
of them (`StakeProof.spc_ciphertext`). Nothing about them is derived from a
counter, so failed or abandoned attempts can never open a gap that a restore
would not cross. (`mint-rho`, `mint-rcm`, `stake-rho`, `stake-rcm` must not
be used any more.)

Groundworks owner tags (a position stores `otag`; its owner proves it again
to update, unlock or vote it; positions carry no ciphertext), counter c a u32:

    salt_c = HMAC-SHA512("earth.privacy.v1", "otag-salt" || nk (32 BE) || c (u32 BE)) mod p
    otag_c = H(TAG_OTAG, owner_pk, salt_c)

A lock takes counter max(next_otag_counter, highest owned counter found + 1)
and advances next_otag_counter. Sync matches the public positions against
counters 0 … max(next_otag_counter, highest found + 1) + 1024 (OTAG_GAP =
1024: a closed position disappears from the chain, so the window must cross
a run of closed positions and failed locks). A stake proof whose msg stores
no tag (everything but a position msg) uses a fresh random salt.

Pinned in `KeysAndNotesTest` (cross-checked with an independent Python
derivation) for the mnemonic `abandon ×11 about`:

    id_secret 059b96926ae7a563f2ddeb6fe425a6ccdd1c267f1d06d92457f1b6dba741fcba
    nk        0a67906d75dbdf06237678494da622b51aab4bcefea0134f5ead80d7c9440b81
    ek_pub    c6327c6004804dce1fd6a876c9c983204cb251507a5da8ae845e52cde8585773
    salt_0    0685f54037389aaceee42288ed8c8c996a884e297ffee771c73370ca885e1618
    salt_1    2e52e73b7af259664a34df8bcee1c0476009a37e0ab2e52b285bae497845a9ba

## 2. Shielded address (canonical; also in chain zk/privacy and the web app)

    bech32m( hrp "erthz", 8→5 bits of: 0x01 || owner_pk (32, BE, < p) || ek_pub (32) )

65 payload bytes, 116 characters; BIP-173's 90-character cap is not applied
(as with Zcash unified addresses). Decoders refuse other hrps, versions,
lengths and a non-canonical owner_pk. Golden (the mnemonic above):

    erthz1qyh7prm54w0lu9ymzm3dtpm3r3juewetjuu8675hw0gpu5hywx4ll33j03sqfqzdec0ad2rke8ycxgzvkfg4q7ja4zhgghjjeh59s4mn9gwhg2

## 3. Note ciphertexts

Four kinds, told apart by length (and, for 177 bytes, by which stream the
row is in):

| kind | len | used for |
| --- | --- | --- |
| v1 note (canonical) | 217 | a bundle's outputs: sends, change, the registration record note |
| v2 blind note (canonical, chain zk/privacy) | 177 | every pool note the chain mints, to self or to anyone |
| blind stake (canonical, chain zk/privacy) | 177 | `StakeProof.spc_ciphertext`: every stake note the chain mints |
| wallet stake note (wallet-defined) | 153 | a stake proof's own outputs (restake, change) |

**v1 note.**

    ct  = epk (32) || ChaCha20-Poly1305(key, nonce = 0^12, aad = empty, pt)     217 bytes
    key = HKDF-SHA256(ikm = X25519(esk, ek_pub), salt = "earth.note.v1", info = epk || cm)
    pt  = 0x01 || asset_id (32) || value (u64 BE) || rho (32) || rcm (32) || memo (64, zero padded)   169 bytes

esk is fresh per note, so the zero nonce never repeats under a key. The
recipient decrypts with the note's public cm and accepts only if
`H(TAG_CM, asset_id, value, H(TAG_PC, owner_pk, rho, rcm)) == cm`. Memo
trailing zeros are dropped on read. A dummy output is encrypted to a
throwaway key. Golden: esk = 01 02 … 20, note (uanml, 1000000,
rho = Poseidon2([7]), rcm = Poseidon2([8]), memo "memo") to the address
above:

    07a37cbc142093c8b755dc1b10e86cb426374ad16aa853ed0bdfc0b2b86d1c7c27a4ccf6eb32e22c660248ac5cfc37d11c
    e870fdb324e97e1e94176876b187056319e704577b33ba5fa53a834ac83f419c51cbea350859acf194fd7c69dc74074d50
    3714b4783349656cd5427e678cfec273deb29a786db6ca40b1b95cf5a5356bab620aa7657442f8d130c415aa7c9bfdbc70
    094c927ecc8448cd159c76fb0d937b4af129915b6eee36c1066dfd4e66ccbd1fa244c0ce319a0cc12095a58d894477a819
    4318471c9d1365d3eb99e330944de6893064b50614

(cm = 05e80ddba92b607efc03967707d42b7cbc814f5767040a9066902fb53b3ff5a3)

**v2 blind note (chain `EncryptBlindNote`).** Required, exactly 177 bytes,
on every note the chain mints to a hidden owner: MsgShield (the gas grant
included), MsgRegister (ciphertext_anml and ciphertext_erth: both v2, even
the ANML whose value is known), MsgClaimAnml, MsgBuyAnml, MsgNoteSwap,
MsgAddLiquidityShielded (share, refund: one ciphertext for both refund
notes, same pc), MsgRemoveLiquidityShielded (both legs), MsgRemoveLiquidity
(ANML leg), MsgClaimUnbonding.

    ct  = epk (32) || ChaCha20-Poly1305(key, nonce 0^12, aad empty, pt)      177 bytes
    key = HKDF-SHA256(ikm = X25519(esk, ek_pub), salt "earth.note.v2", info = epk)
    pt  = 0x02 || rho (32) || rcm (32) || memo (64, zero padded)            129 bytes

info cannot bind cm, so the binding is the recipient's check: it opens ct
and accepts only if `CM(AssetID(denom), value, PC(owner_pk, rho, rcm)) ==
cm` with the denom and value the chain published for that position (the
indexer's note row `amount`, "<n><denom>", set for every shield and mint).
The wallet uses fresh random rho, rcm and esk for each, and an empty memo
for its own. Golden (chain formats_test.go goldenKeys: ek = 01..20, esk =
40..5f, owner_pk = OwnerPK(7), rho 11, rcm 13, memo "golden memo"), pinned
in `BlindNoteTest`:

    79a631eede1bf9c98f12032cdeadd0e7a079398fc786b88cc846ec89af85a51a8b8d4fe44e9fcb771cba93975cb4507ff1d20e44
    6a6a4cd8336f9a50186a7de58a5b4570c62bfd9cd347f5921103700103da6af3ce492bbd1f936a4310b3b01a1d583847125f76
    32547dfb2ea23c438f21cd4a419f9ef66d92660af42686e93c890bc37f68cf282f46ca2550ab2df0ce7191a11e7721ce736e0d
    1bdd62af8be221017ee455ab79e7b2ea0e756a86c39910

**Blind stake ciphertext (chain `EncryptBlindStakeNote`).** Required,
exactly 177 bytes, in `StakeProof.spc_ciphertext` of MsgDelegate,
MsgUndelegate, MsgStakeVote and MsgUnlockPosition (empty in every other
staking msg):

    ct  = epk || ChaCha20-Poly1305(HKDF-SHA256(X25519(esk, ek_pub), salt "earth.stake.v1", info epk), nonce 0, pt)
    pt  = 0x03 || rho (32) || rcm (32) || memo (64)                          129 bytes

The owner opens it, recomputes `spc = H(TAG_SPC, owner_pk, rho, rcm)` and
`cm = H(TAG_STAKE, AssetID(denom), amount, spc)` from the stake row's
published denom and amount, and accepts only a matching cm. `spc_mint` is
that spc (fresh rho, rcm per msg). Golden: chain `goldenBlindStakeCT`
(zk/privacy notecipher_test.go), pinned in `BlindNoteTest`.

**Wallet stake note ciphertext ("earth stake note v1", wallet-defined).**
For a stake note a stake proof creates (a restake's outputs, an
undelegation's or a lock's change), always to the wallet's own address:

    ct  = epk (32) || ChaCha20-Poly1305(key, nonce 0^12, aad empty, pt)      153 bytes
    key = HKDF-SHA256(ikm = X25519(esk, ek_pub), salt "earth.stake.v1", info = epk || cm)
    pt  = 0x03 || asset_id (32) || amount (u64 BE) || rho (32) || rcm (32)  105 bytes

accepted only if `H(TAG_STAKE, asset_id, amount, H(TAG_SPC, owner_pk, rho,
rcm)) == cm`. Same salt and version byte as the blind stake ciphertext, but
a different length, info and plaintext, so neither opens as the other.

**Sync rule (one note-discovery rule).** A pool note row is ours iff its
ciphertext opens: 217 bytes as v1 (cm-bound), or 177 bytes as v2 with the
row's public amount (rows without an amount are skipped). A stake note row
is ours iff its ciphertext opens: 153 bytes as the wallet stake note, or 177
bytes as a blind stake note with the row's denom and amount. Value-0 notes
are dropped, except the registration record note (§3a). Nothing else; a
restore from the mnemonic alone finds every note.

## 3a. Registration (binding, gas grant, record note, restore)

**Binding.** The passport proof's `address` public input is

    address = H(TAG_REG, idc, pc_anml, Bytes(ct_anml), pc_erth, Bytes(ct_erth), affiliate)

(affiliate = Bytes(address bytes of the lowercase bech32 referrer), 0 for
none). So the wallet picks fresh rho/rcm for both notes and writes both v2
ciphertexts **before** proving the passport, and sends exactly those
ciphertexts in MsgRegister and to /gas/register. Chain pinned vector: idc=1,
pc_anml=2, ct_anml="anml", pc_erth=3, ct_erth="erth", affiliate=0 →
`20ce5fccf5e6e20a8a7b80f7565e41a7c73dbb16ac5e53746e7234ba8b305b0c`.

**Gas grant.** `POST /gas/register` takes MsgRegister's fields (no fee
bundle) plus `pc_gas` and `ciphertext_gas`, a fresh v2 ciphertext to self
(177 bytes, required). It is the only grant: `/gas/transparent`,
`/gas/android`, `/gas/challenge`, `/gas/ios`, `/gas/human` are gone.

**Registration record note.** MsgRegister's fee bundle always carries, as
one of its outputs, a value-0 uerth note to the wallet's own address (v1
ciphertext) whose 64-byte memo is the registration record:

    memo = "ER" (0x45 0x52) || 0x01 || country (2 ASCII bytes, 0x0000 unknown)
           || built_at (u64 BE unix seconds, the wallet's clock when the bundle was laid out)
           || dsc_key (32 BE) || zero padding                                   (45 bytes used)

`country` is the wallet's guess at the verifying CSCA's ISO alpha-2 (the
DSC's issuer C=, else the passport's issuing state). Sync keeps every record
note it finds (they are value 0, never spent).

**Recording (C2).** Right after the broadcast returns (before any sync) the
wallet persists a pending registration {leaf_index from the tx's `register`
event, dsc_key, passport nullifier, public signals, block time, tx hash,
country hint}. Every sync then tries to resolve it: once the local identity
tree has the leaf, the country is found by recomputing
`H(TAG_LEAF, idc, dsc_key, country, activated_at)` over the hint, unknown
(0) and every A..Z pair; the identity record is written and the pending one
dropped. It is never dropped unresolved (a leaf that does not match after
the tree has it is an error shown to the user, the record kept).

**Restore from the mnemonic (L8).** No query names the registration. For
every record note found (newest first), with h its block height: over every
identity leaf appended at height h (identity leaves are synced no higher
than the notes, so the record is always seen first), try `activated_at`
outward from built_at over [built_at − 3600, built_at + 86400] with the
hinted country and unknown (0); only if no leaf matches, every other A..Z
pair over [built_at − 600, built_at + 3600]. A match gives leaf_index, dsc_key, country and
activated_at: the identity record (passport nullifier left empty; nothing
needs it). A registration whose record note is missing (made by an older
app) cannot be restored and must register again (a switch to the same
passport is allowed, a switch to the same idc is refused by the chain only
while the old leaf is live; the app tells the user).

## 4. Private tx assembly (follows x/shielded/ante)

- TxRaw with one Any, AuthInfo with no signer infos, fee = exactly the msg's
  total fee in uerth (the bundles' fee + fee_from_output), no payer or
  granter, no signatures, no timeout_timestamp (refused for private txs).
- **Sighash** (every private msg):

      sighash = H(TAG_SIGNAL, Bytes(type_url), Bytes(chain_id), K, digest(bundle_0..K-1),
                  Bytes(memo), U64(timeout_height), U64(gas_limit), msg fields…)

  `Bytes(memo)` over the memo's UTF-8 bytes (Bytes("") for none). The wallet
  sets memo "" (an unshield may carry a user memo, e.g. an exchange deposit
  tag) and timeout_height 0, and fixes the gas limit (from simulation)
  before proving; the tx carries exactly those values.
- Fee = max(x/shielded min_fee, ceil(node min gas price × gas limit)); gas
  limit = simulated gas + max(10%, 20,000). Simulation runs on the real
  anchors, nullifiers, commitments, value commitments and ciphertexts with
  14,656-byte placeholder proofs and a zero binding signature. The tx is
  re-laid at the simulated fee; if that changes the action count it is
  simulated again (at most 4 rounds, the fee only rising after the first),
  then the sighash is computed (with that gas limit) and every action, the
  stake proof and the membership proven over it, every bundle signed. Every
  proof must be exactly 14,656 bytes (checked before broadcast).
- **One fee rule.** fee = the bundles' uerth balance less the uerth the msg
  moves itself. Moves: MsgDelegate.amount; MsgNoteSwap.amount_in when
  denom_in is uerth; MsgAddLiquidityShielded.erth_amount; nothing for every
  other staking, dex, personhood and assembly msg (their whole uerth balance
  is the fee). Exceptions: MsgSend names its fee (uerth beyond it is
  unshielded to its receiver); MsgClaimUnbonding pays `fee_from_output`
  with no bundle (the wallet always does). No other msg pays from output: a
  swap of ANML into ERTH needs an ERTH note for its fee.
- **Bundle layout (any notes, any assets).** The msg's public release per
  denom (a fee in uerth, an unshield, what a module takes) plus every
  payment output is what must leave each denom; notes per denom: the
  smallest single note covering it, else the fewest notes largest first
  with the last swapped for the smallest that still covers. Each denom's
  surplus returns as one v1 change note to self. Spends and outputs are
  shuffled independently and paired into max(#spends, #outputs, 2)
  actions (an action's spend and output may be different assets), dummies
  filling either side: a dummy spend has value 0, asset uerth, a fresh
  random rho and rcm, position 0 and a zero path; a dummy output value 0,
  asset uerth, a random pc and a ciphertext to a throwaway key. Every
  action's anchor is the wallet's current (chain-verified) note root.
  Every rcv is a fresh random field element; bsk = Σ rcv mod n; the binding
  signature's 32 random bytes are fresh. Balances are computed from the
  actions (spends − outputs per denom, positive only, sorted by denom). A
  layout above max_actions_per_bundle (x/shielded param, default 16) is
  refused ("merge first").
- **Per msg (fields beyond the bundle; sighash order).**

  | msg | fields bound after the digests |
  | --- | --- |
  | MsgSend | Bytes(receiver bytes), fee |
  | MsgRegister | idc, pc_anml, Bytes(ct_anml), pc_erth, Bytes(ct_erth), affiliate, Bytes(signature_algorithm), signals… |
  | MsgClaimAnml | day, pc, Bytes(ct) |
  | MsgDelegate | StakeFields, Bytes(validator), amount |
  | MsgRestake | StakeFields, Bytes(validator) |
  | MsgUndelegate | StakeFields, Bytes(validator), amount |
  | MsgClaimUnbonding | StakeFields, Bytes(validator), epoch, amount, pc, Bytes(ct), fee_from_output |
  | MsgStakeVote | StakeFields, proposal_id, Bytes(validator), Bytes(OptionsBytes), weight |
  | MsgLockPosition | StakeFields, Bytes(validator), amount, Bytes(SplitsBytes) |
  | MsgUpdatePosition | StakeFields, position_id, Bytes(SplitsBytes) |
  | MsgUnlockPosition | StakeFields, position_id |
  | MsgPositionVote | StakeFields, position_id, proposal_id, Bytes(OptionsBytes) |
  | MsgNoteSwap | Bytes(denom_in), amount_in, Bytes(denom_out), min_amount_out, pc, Bytes(ct) |
  | MsgAddLiquidityShielded | pool_id, Bytes(min_shares), share_pc, Bytes(share_ct), refund_pc, Bytes(refund_ct), erth_amount |
  | MsgRemoveLiquidityShielded | pool_id, erth_pc, Bytes(erth_ct), token_pc, Bytes(token_ct) |

  StakeFields = anchor, nf_0, nf_1, cm_0, cm_1, Bytes(ct_0), Bytes(ct_1),
  spc_mint, owner_tag, Bytes(spc_ciphertext). Proto field numbers: the
  removed `fee` fields are reserved; MsgDelegate.amount = 5,
  StakeProof.spc_ciphertext = 8, MsgNoteSwap.denom_in = 8 / amount_in = 9
  (fee_from_output 6 and fee 7 reserved), MsgAddLiquidityShielded.erth_amount
  = 11. Addresses are lowercase canonical bech32.
- **Stake proofs.** A staking msg's stake proof spends at most two stake
  notes of the msg's denom (the smallest single covering, else the
  smallest sufficient pair; a balance spread over more is merged first by
  MsgRestake, two into one) and creates at most one change note (two for a
  restake split) with the wallet stake ciphertext; unused input and output
  slots get random rho/rcm (amount 0: nf 0, cm 0) and a zero path. Anchor:
  the wallet's latest (chain-verified) stake root (zero when the stake tree
  is empty; not checked when nothing is spent), for a stake vote the
  proposal's snapshot root, the paths taken against the tree at the
  snapshot's size. `spc_mint` and `spc_ciphertext` as in §3; `otag` a
  position's owner tag (lock: a new counter; update/unlock/vote: the
  position's) or random. A stake vote spends one or two derth notes of one
  validator (weight = their sum). Voting several (stakeVoteAll): one vote at
  a time, a full sync and a random 20-120 s pause between votes, so a vote's
  fee never spends the previous vote's change unseen and the votes are not
  one burst.
- **Groundworks positions** are still per-user msgs with splits; the chain
  weighs them per validator and no longer stores a position's weight. The
  wallet shows a position's weight as derth × its validator's current rate
  (0 without a live split).

## 4a. Indexer URL scheme (backend README "URL scheme for wallets")

1. `GET /privacy/status` → `chain_id`, `genesis` (16 hex), `base`
   (`/privacy/<chain_id>/<genesis>`, null until the indexer met its chain),
   `halted` (non-null: the indexer stopped; the wallet refuses to sync).
   **Base validation (K10).** A non-null `base` is accepted only if it is
   byte for byte `/privacy/` + chain_id + `/` + genesis, with chain_id the
   wallet's own (`[A-Za-z0-9][A-Za-z0-9._-]{0,63}`) and genesis
   `[0-9a-f]{16}`, both as the same status names them; anything else (a
   host, `//`, `@`, a scheme, `..`, a query, another chain) is refused before
   any stream request, and every request URL must keep the indexer's own
   scheme, host and port. A status whose `chain_id` is null or another
   chain's is refused (no sync).
2. The wallet's store records (chain_id, genesis). If the status names
   another genesis for the same chain id (a relaunch), the wallet first asks
   the LCD (K6): `GET /cosmos/base/tendermint/v1beta1/node_info`
   (`default_node_info.network` must be the chain id) and
   `GET /cosmos/base/tendermint/v1beta1/blocks/1` (the first 16 lowercase
   hex digits of `block_id.hash` must be the status's genesis). Only a
   confirmed switch wipes the local trees, notes, cursors, records and the
   old chain's bookkeeping (claimed days, caretaker split, referrer); it
   keeps the identity record (with its passport nullifier), the pending
   registration and the owner-tag counters, and the identity's leaf is
   re-verified against the resynced tree (shown as not live if it does not
   match; the record is never dropped). An unconfirmed switch (the LCD says
   otherwise, or cannot say: block 1 pruned, LCD down) wipes nothing, syncs
   nothing and is shown as unverified. A first sync (nothing stored) goes
   ahead when the LCD cannot say, never when it contradicts the status.
3. Every stream is read under `base`:

       GET {base}/notes?from_pos=&limit=               [position, height, cm, ciphertext, amount]
       GET {base}/nullifiers?from_height=&limit=       [[height, [nf, ...]], ...]
       GET {base}/identity?from_index=&limit=          [index, height, leaf, zeroed_height]
       GET {base}/identity/zeroed?from_height=&limit=  [[height, [index, ...]], ...]
       GET {base}/roots/latest                         {note, identity, stake: {root, tree_size, height, time}}
       GET {base}/rates?epoch=                         [validator, rate, supply, epoch, height]
       GET {base}/stake/notes?from_pos=&limit=         [position, height, cm, ciphertext, denom, amount, spc]
       GET {base}/stake/nullifiers?from_height=&limit= [[height, [nf, ...]], ...]
       GET {base}/stake/roots?from_height=&limit=      [height, root, tree_size, time]

   A 404 means the base moved: re-read the status (step 1) and retry once.
   Response bodies are capped (8 MiB decompressed) and pages to 5000 rows.

A minted stake note row has denom, amount and spc and (fced976 on) its blind
stake ciphertext; a created one its wallet stake ciphertext and nulls.

## 4b. Root verification against the chain (C3, K8, K9)

**What is trusted.** The operator runs both the indexer (api.erth.network)
and the LCD (lcd.erth.network); the wallet has no light client and does not
check consensus signatures, so the LCD is trusted for chain state. What the
checks below buy is that a compromised or broken *indexer* alone cannot make
the wallet build on, or show as verified, trees the chain does not have; an
operator controlling both the indexer and the LCD could still lie
consistently (it could not forge spends: every proof is checked by the
validators, so a forged tree only yields proofs the chain refuses).

After each sync the wallet checks every local root against the LCD:

- note tree: `GET /earth/shielded/v1/roots/{root hex}`. A record whose
  `tree_size` differs from the local size is a mismatch. No record is
  **unverified, not a mismatch** (K8): x/shielded prunes roots after its
  window (14 days), so an indexer far behind and a forged root look the
  same; `valid` false (no longer an anchor) is unverified too.
- identity tree: `GET /earth/personhood/v1/identity_tree` at height H (header
  `x-cosmos-block-height`, H = the indexer's `roots/latest.identity.height`)
  must give `size` = local size and `latest_root` = local root;
- stake tree: `GET /earth/shieldedstaking/v1/stake_tree` at the indexer's
  stake root height likewise (an empty tree: size 0, root empty).
- **Pinned heights (K9).** A tree read counts as pinned only if the
  response's `x-cosmos-block-height` header echoes exactly H. A pinned read
  that differs is a mismatch. If H is unavailable (pruned) or another height
  is echoed, the latest state is read instead (both platforms, never
  silently: it is marked unpinned); an unpinned read verifies equal trees and
  otherwise leaves the roots **unverified** (a zeroing after H changes the
  identity root at the same size), never a mismatch.
- **Nullifier sample (K9).** Up to 4 pool and 4 stake nullifiers, drawn
  uniformly (reservoir) from everything the nullifier streams delivered in
  this sync, are asked of `GET /earth/shielded/v1/nullifiers/{hex}` and
  `GET /earth/shieldedstaking/v1/stake_nullifiers/{hex}`; one the chain says
  is not spent leaves the roots unverified. The wallet never asks about its
  own nullifiers (that would name its notes).
- **Indexer behind the tip (K9).** If the LCD's latest block
  (`/cosmos/base/tendermint/v1beta1/blocks/latest`) is more than 30 blocks
  past the indexer's synced height, the roots are unverified ("the indexer
  is N blocks behind").

A local tree larger than the indexer's latest, or one of the same size with
another root, is inconsistent: the wallet starts over from an empty store.
A local tree that differs from the indexer's latest is resynced once; a
mismatch wipes the synced data and is shown. Unverified roots block every
private tx (no proof is built on them) and are shown as such next to the
private balances, stake and registration until a later sync verifies them.

## 5. Off-device parity

`WalletFlowTest` drives two wallets against an in-memory chain (bundles with
real binding-signature checks, the stake tree, the fced976 fee and
ciphertext rules); with `PRIVACY_TOML_OUT=<dir>` it writes every witness as
`<dir>/{action,stake,membership}/<test>_<i>/Prover.toml`. `nargo execute` on
circuits/action, circuits/stake and circuits/membership accepts all of them.
