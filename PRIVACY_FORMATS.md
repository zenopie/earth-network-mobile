# Privacy formats

The current byte-level and behavioural spec of the Earth Wallet's private
side (Android, iOS; the web app must match the formats). It describes what
is true now, against chain privacy/orchard **20a91c6** (genesis 34fe7441). How it got here is in AUDIT_HISTORY.md.

**Who defines what.** The chain (x/shielded, x/personhood, x/assembly,
x/shieldedstaking, x/dex; `zk/privacy`, `zk/orchard`, `zk/indexed`,
`zk/debt`; ORCHARD_DESIGN.md) pins every hash, tag, commitment, nullifier,
sighash, bundle and proof layout and the public ciphertext lengths; the
clients reproduce those byte for byte. What the chain never sees (keys,
note plaintexts, memo records, owner-tag salts, the stake ciphertext's
contents, the vote weight rule) is defined by the wallet, here. Every
section is marked **[chain]**, **[wallet]** or **[shared]** (a canonical
format the chain and the web app also implement).

**Ground truth.** Chain-pinned values are tested against vectors generated
from the chain's own Go code: `tools/privacyvectors/gen.sh <chain
checkout> [ref]` writes `android/app/src/test/resources/privacy/vectors.json`
(and `dex_amm.json`, the action/membership fixtures) and the identical iOS
copy.
Wallet-defined formats are pinned by golden tests on both platforms
(Android `KeysAndNotesTest`, `BlindNoteTest`, `RestoreTest`,
`HandleOwnershipTest`, `DenomsTest`, `DexTest`; iOS `KeysAndNotesTests`,
`BlindNoteTests`, `RestoreTests`, `HandlesTests`, `DenomTests`, `DexTests`). The circuits are in
`circuits/{action,stake,vote,membership,lean_poa_*}`; a circuit `main()`'s
`pub` parameters, in order, are its public inputs.

## Contents

1. Primitives and domain tags
2. Keys
3. Shielded address
4. Pool notes
5. Note ciphertexts
6. Memo records (value-0 pool notes)
7. Stake notes, labels, positions
8. Trees
9. Circuits: public-input order and witnesses
10. Bundles
11. Private transactions
12. Messages
13. Registration
14. Handles, caretaker split, identity moves
15. Staking
16. Assembly and dex
17. Indexer
18. Verification against the chain
19. Sync and restore
20. Wallet behaviour
21. Off-device parity

## 1. Primitives and domain tags

**[chain]** p is the BN254 scalar modulus. A field element on the wire is
exactly 32 bytes big-endian and below p (`Fr.fromBytes` refuses anything
else, as the chain's `FieldFromBytes` does).

- **H** is Poseidon2 over BN254 (t = 4, d = 5, 8 full + 56 partial rounds),
  the sponge of noir-lang/poseidon v0.3.0 (`Poseidon2::hash`) = the chain's
  `zk/poseidon2.Hash`: rate 3, IV = len << 64 in the capacity slot, one
  element squeezed after a final duplex. The length is absorbed, so hashes
  of different arity cannot collide.
- **Tag(s)** = the ASCII bytes of s read as one big-endian integer.
- **U64(v)** = v as a field element (unsigned).
- **Bytes(b)** = H(TAG_BYTES, U64(len b), b in 31-byte big-endian chunks…);
  Bytes of nothing is H(TAG_BYTES, 0).
- **AssetID(denom)** = H(TAG_ASSET, U64(len), 31-byte chunks of the UTF-8
  denom…). AssetID("uerth") = `0ad44a14c7205c61e39db2c64b79005ffb14a2a927977e8cda2208be2e3fe54c`
  (`privacy_core::ASSET_ERTH`); vectors `asset_ids`.
- **Scope(kind, args…)** = H(TAG_SCOPE, Bytes(kind), args…). Scopes in use:
  `Scope("claim", U64(day))`, `Scope("caretaker")`, `Scope("handle")`,
  `Scope("proposal", U64(id), U64(round))`, `Scope("removal", U64(ballot_id))`,
  `Scope("propose_removal", U64(option_id), U64(day))` (vectors `scopes`).
- **Signal(type_url, chain_id, fields…)** = H(TAG_SIGNAL, Bytes(type_url),
  Bytes(chain_id), fields…).
- **Country** = the ISO 3166-1 alpha-2's two ASCII bytes big-endian ("DE" =
  0x4445); 0 = unknown.
- Merkle trees are depth 32, node = H(left, right), empty leaf 0 (§8).

**Domain tags.** Chain tags (all in `privacy_core` or `zk/orchard`, vectors
`tags`; `earth.vote` is retired and never reused):

| tag | string | use |
| --- | --- | --- |
| TAG_ID | `earth.id` | idc |
| TAG_OWNER | `earth.owner` | owner_pk |
| TAG_LEAF | `earth.leaf` | identity leaf |
| TAG_SN | `earth.sn` | scope nullifier |
| TAG_PC | `earth.pc` | pool note owner commitment |
| TAG_CM | `earth.cm` | pool note commitment |
| TAG_NF | `earth.nf` | pool note nullifier |
| TAG_REG | `earth.reg` | registration binding |
| TAG_ASSET | `earth.asset` | AssetID |
| TAG_BYTES | `earth.bytes` | Bytes |
| TAG_SCOPE | `earth.scope` | Scope |
| TAG_SIGNAL | `earth.signal` | sighash / Signal |
| TAG_AFFILIATE | `earth.affiliate` | registration affiliate field |
| TAG_REFERRAL | `earth.referral` | referral note opening |
| TAG_STAKE | `earth.stake` | stake note commitment |
| TAG_SPC | `earth.spc` | stake note owner commitment |
| TAG_SNF | `earth.snf` | stake note nullifier |
| TAG_OTAG | `earth.otag` | position owner tag |
| TAG_SNFL | `earth.snfl` | stake nullifier tree leaf |
| TAG_VNF | `earth.vnf` | stake vote nullifier |
| TAG_VPAD | `earth.vpad` | padding vote nullifier of an unused vote slot |
| TAG_SLABEL | `earth.slabel` | stake note slash label |
| TAG_DEBTL | `earth.debtl` | slash debt tree leaf |
| TAG_GEN | `earth.gen` | Grumpkin value base |
| TAG_CV_R | `earth.cv.r` | value-commitment randomness base R |
| TAG_BUNDLE | `earth.bundle` | bundle digest |
| TAG_BSIG | `earth.bsig` | binding-signature challenge |

Wallet tags and labels (**[wallet]**):

| string | kind | use |
| --- | --- | --- |
| `earth.privacy.v1` | HMAC-SHA512 key | key derivation, owner-tag salts (§2, §7) |
| `id_secret`, `nk`, `ek` | HMAC labels | key derivation (§2) |
| `otag-salt` | HMAC label | owner-tag salt (§7) |
| `earth.note.v1` | HKDF salt | v1 note ciphertext (§5) |
| `earth.note.v2` | HKDF salt | v2 blind ciphertext (§5) **[shared]** |
| `earth.stake.v2` | HKDF salt | wallet stake ciphertext (§5) |
| `earth.rectag` | field tag | registration record tag (§6) |
| `earth.statetag` | field tag | state record tag (§6) |
| `earth.unlocktag` | field tag | unlock record tag (§6) |
| `earth-gas-pow/v1` | ASCII prefix | gas grant proof of work (§13) |

## 2. Keys

**[wallet]** From the BIP-39 seed (empty passphrase), BIP-32 hardened
derivation on a purpose of its own (no Cosmos account key is reused):

    m/2026'/118'/0'/0'   id_secret
    m/2026'/118'/0'/1'   nk
    m/2026'/118'/0'/2'   ek (x25519)

For each, with k the child's 32-byte private key:

    s = HMAC-SHA512(key = "earth.privacy.v1", data = label || k)
    id_secret = s mod p      (label "id_secret")
    nk        = s mod p      (label "nk")
    ek        = s[0..32]     (label "ek"; X25519 clamps it)

**[chain]** `idc = H(TAG_ID, id_secret)`, `owner_pk = H(TAG_OWNER, nk)`.

Nothing else is derived from the mnemonic by a counter except owner-tag
salts (§7): every note the chain mints to the wallet and every stake note
uses fresh random rho and rcm and is found by trial decryption (§19), so a
failed or abandoned attempt never leaves a gap a restore cannot cross.
The seed and the phrase's bytes are zeroed once the keys are derived (the
phrase as an iOS String cannot be); iOS checks every SecRandomCopyBytes
status.

Golden (`KeysAndNotesTest`, cross-checked with an independent Python
derivation), mnemonic `abandon ×11 about`:

    id_secret 059b96926ae7a563f2ddeb6fe425a6ccdd1c267f1d06d92457f1b6dba741fcba
    nk        0a67906d75dbdf06237678494da622b51aab4bcefea0134f5ead80d7c9440b81
    ek_pub    c6327c6004804dce1fd6a876c9c983204cb251507a5da8ae845e52cde8585773
    salt_0    0685f54037389aaceee42288ed8c8c996a884e297ffee771c73370ca885e1618
    salt_1    2e52e73b7af259664a34df8bcee1c0476009a37e0ab2e52b285bae497845a9ba

## 3. Shielded address

**[shared]** (chain zk/privacy and the web app):

    bech32m( hrp "erthz", 8→5 bits of: 0x01 || owner_pk (32, BE, < p) || ek_pub (32) )

65 payload bytes, 116 characters; BIP-173's 90-character cap is not applied
(as with Zcash unified addresses). Decoders refuse other hrps, versions,
lengths, mixed case and a non-canonical owner_pk. Golden (the mnemonic
above):

    erthz1qyh7prm54w0lu9ymzm3dtpm3r3juewetjuu8675hw0gpu5hywx4ll33j03sqfqzdec0ad2rke8ycxgzvkfg4q7ja4zhgghjjeh59s4mn9gwhg2

A bech32 transparent address (`earth1…`) in a msg is its raw bytes from the
address codec; addresses are lowercase canonical bech32.

## 4. Pool notes

**[chain]**

    pc = H(TAG_PC, owner_pk, rho, rcm)
    cm = H(TAG_CM, AssetID(denom), value, pc)
    nf = H(TAG_NF, nk, rho, position)          position a u32 (the leaf index)

**Amounts.** Every note value and stake amount is a u64 on chain, and the
circuits bound it to 2^63 − 1 (`privacy_core NOTE_VALUE_BITS = 63`, inside
`note_cm` and `stake_cm`): an action's spend and output value, a stake
proof's input and output amounts and a vote's note amounts are each at most
2^63 − 1, so no proof can create a note a wallet ignores; x/shielded's
MaxNoteValue is the same. A witness above it fails `nargo execute`. Public
amounts (`v_in`, `v_out`, a bundle's balance) are not notes and stay u64.

**[wallet]** Both apps parse public amounts as unsigned decimal u64 (no
sign, ASCII digits only) and take only values up to 2^63 − 1: a row,
decrypted note or stake note above that is ignored (never wrapped). Totals
shown to the user saturate; amounts a tx is built from are checked (an
overflow is an error, never a wrong change); derth × rate saturates at
2^63 − 1 (a negative rate is 0).

A note's asset is carried as its id. The wallet resolves ids to denoms from
`uerth`, `uanml` and what it learns (§19); an id it cannot resolve is kept
as `asset/<hex>`, spendable inside the pool.

## 5. Note ciphertexts

Three formats, told apart by length:

| format | bytes | plaintext | used for |
| --- | --- | --- | --- |
| v1 note **[shared]** | 217 | 169 | a bundle's outputs: sends, change, memo records, dummies |
| v2 blind note **[shared]** | 177 | 129 | every pool note the chain mints |
| wallet stake note v2 **[wallet]** (length pinned by the chain) | 201 | 153 | every stake note |

The AEAD is ChaCha20-Poly1305 with a zero 12-byte nonce and empty aad; esk
is fresh per note, so the nonce never repeats under a key. A shared secret
of all zeros (low-order epk) is refused.

**v1 note.**

    ct  = epk (32) || ChaCha20-Poly1305(key, pt)                                   217 bytes
    key = HKDF-SHA256(ikm = X25519(esk, ek_pub), salt = "earth.note.v1", info = epk || cm)
    pt  = 0x01 || asset_id (32) || value (u64 BE) || rho (32) || rcm (32) || memo (64, zero padded)   169 bytes

The recipient decrypts with the note's public cm and accepts only if
`H(TAG_CM, asset_id, value, H(TAG_PC, owner_pk, rho, rcm)) == cm`. Memo
trailing zeros are dropped on read. A dummy output is a value-0 uerth note
encrypted to a throwaway key. Golden: esk = 01 02 … 20, note (uanml,
1000000, rho = Poseidon2([7]), rcm = Poseidon2([8]), memo "memo") to the
address of §3:

    07a37cbc142093c8b755dc1b10e86cb426374ad16aa853ed0bdfc0b2b86d1c7c27a4ccf6eb32e22c660248ac5cfc37d11c
    e870fdb324e97e1e94176876b187056319e704577b33ba5fa53a834ac83f419c51cbea350859acf194fd7c69dc74074d50
    3714b4783349656cd5427e678cfec273deb29a786db6ca40b1b95cf5a5356bab620aa7657442f8d130c415aa7c9bfdbc70
    094c927ecc8448cd159c76fb0d937b4af129915b6eee36c1066dfd4e66ccbd1fa244c0ce319a0cc12095a58d894477a819
    4318471c9d1365d3eb99e330944de6893064b50614

(cm = 05e80ddba92b607efc03967707d42b7cbc814f5767040a9066902fb53b3ff5a3)

**v2 blind note (chain `EncryptBlindNote`).** Required, exactly 177 bytes,
on every note the chain mints to a hidden owner: MsgShield (the gas grant
included), MsgRegister (ciphertext_anml and ciphertext_erth, both v2),
MsgClaimAnml, MsgBuyAnml, MsgNoteSwap, MsgAddLiquidityShielded (share;
refund: one ciphertext for both refund notes, same pc),
MsgRemoveLiquidityShielded (both legs), MsgRemoveLiquidity (ANML leg),
MsgUndelegate (the payout), and `pc_gas` of `/gas/register`.

    ct  = epk (32) || ChaCha20-Poly1305(key, pt)                                   177 bytes
    key = HKDF-SHA256(ikm = X25519(esk, ek_pub), salt "earth.note.v2", info = epk)
    pt  = 0x02 || rho (32) || rcm (32) || memo (64, zero padded)                   129 bytes

info cannot bind cm (the sender does not know the value), so the binding is
the recipient's check: it opens ct and accepts only if `CM(AssetID(denom),
value, PC(owner_pk, rho, rcm)) == cm` with the denom and value the chain
published for that position (the indexer's note row `amount`,
`<n><denom>`). The wallet uses fresh random rho, rcm and esk for each and
an empty memo for its own. Golden (chain formats_test.go goldenKeys: ek =
01..20, esk = 40..5f, owner_pk = OwnerPK(7), rho 11, rcm 13, memo "golden
memo"; vectors `blind.note_ct`), pinned in `BlindNoteTest`:

    79a631eede1bf9c98f12032cdeadd0e7a079398fc786b88cc846ec89af85a51a8b8d4fe44e9fcb771cba93975cb4507ff1d20e44
    6a6a4cd8336f9a50186a7de58a5b4570c62bfd9cd347f5921103700103da6af3ce492bbd1f936a4310b3b01a1d583847125f76
    32547dfb2ea23c438f21cd4a419f9ef66d92660af42686e93c890bc37f68cf282f46ca2550ab2df0ce7191a11e7721ce736e0d
    1bdd62af8be221017ee455ab79e7b2ea0e756a86c39910

A split payout (§12, x/dex MintNoteSplit, an undelegation payout) mints
several notes with the same pc and ciphertext, each its own amount and
position: each row decrypts to the same (rho, rcm) and its own amount gives
its cm; the wallet never dedupes by ciphertext, pc or opening.

**Wallet stake note v2 ("earth stake note v2"; the chain pins only its
length, WalletStakeCiphertextBytes = 201).** For every stake note (each is a
stake proof's output), always to the wallet's own address:

    ct  = epk (32) || ChaCha20-Poly1305(key, pt)                                   201 bytes
    key = HKDF-SHA256(ikm = X25519(esk, ek_pub), salt "earth.stake.v2", info = epk || cm)
    pt  = 0x04 || asset_id (32) || amount (u64 BE) || rho (32) || rcm (32)
          || move_key (32) || move_time (u64 BE) || exposed (u64 BE)              153 bytes

Accepted only if `H(TAG_STAKE, asset_id, amount, H(TAG_SPC, owner_pk, rho,
rcm), label) == cm` (§7). The three label fields are all zero for an
unlabelled note (label 0; same length, so the ciphertext does not tell);
otherwise move_key ≠ 0, move_time ≠ 0 and 0 < exposed ≤ amount, else the
note is refused. Golden (esk = 40..5f, the v2 golden keys, asset
AssetID(vectors `derth_denom`), amount 1,800,000, rho 11, rcm 13), pinned
in `BlindNoteTest` on both platforms:

    unlabelled (cm vectors blind.stake_cm_derth_1800000):
    79a631eede1bf9c98f12032cdeadd0e7a079398fc786b88cc846ec89af85a51a4acda0bd0608c8007ac2122efac709dab693cd96e83cab5ab4603f747f3c136214b4f26db58f5c9a523c7936ed9eded5dcca94061e0f2cde9565e931dbbcde137eb70d7626ff5a5f7f87ca16bf918c833c0e751a006159e638180e36d8c9390721e533f265d6c082c3b39282802bdef58ce478ee525812f7a2409ea2d7582e980be9bd239897e0a849e9bb197f09bdd3bd37309625b149d5c16b7a883c2e3573786ab371c01d82e218
    labelled move_key 0x4d4b, move_time 1000, exposed 200 (cm blind.stake_cm_derth_1800000_labelled):
    79a631eede1bf9c98f12032cdeadd0e7a079398fc786b88cc846ec89af85a51a524088d6a8c8bfb2c4c18834e3dca9e08f49563fb5d05b22c0fa65732779254918403cfc2bb8d36aa9c8839639732209620e6666fa89c49fcda050c3d5d4324a3e316bcad126dba984ba60ca4edde3f2e3e4dd565e9d4bd8b85195072085bef5f2454561786101b50164958e27585a23ebbbafff57047de9813082e6a96f0034a597ea4639cad38084f5cf40e3808eee9eb85a4eb63fef8621553805d590d6d5101e93f8be0b7e60e3

The stake ciphertext has no memo.

**Open notes [chain].** A note the chain mints with an opening it chose
(only the referral note, §13) has no ciphertext: its notes-stream row
carries `owner_pk`, `rho` and `rcm` instead, and it is ours iff owner_pk is
our own and `H(TAG_CM, AssetID(denom), amount, H(TAG_PC, owner_pk, rho,
rcm))` is the row's cm. It is spent like any other note (nf needs nk; the
published opening links nothing without it).

## 6. Memo records (value-0 pool notes)

**[wallet]** Restoring from the mnemonic must recover state no chain query
names. The wallet records it in value-0 uerth pool notes to the wallet's
own address (v1 ciphertext, 217 bytes), carried as outputs of a fee bundle,
whose 64-byte memo is a record. Every record is tagged so only the holder
of nk can make one (anyone can send this wallet a value-0 note with any
memo); the tag is the first 16 bytes of a field element's 32-byte BE form,
and is compared before anything else is done with the record. Sync opens
every pool note anyway; a value-0 note is read for its record, then
dropped.

**Registration record (version 2).** One output of MsgRegister's fee
bundle:

    memo = "ER" (0x45 0x52) || 0x02 || country (2 ASCII bytes A-Z, 0x0000 unknown)
           || built_at (u64 BE unix seconds, the wallet's clock when the bundle was laid out)
           || dsc_key (32 BE) || tag (16) || zero padding (3)                   (61 bytes used)
    tag  = first 16 bytes of BE32( H(Tag("earth.rectag"), nk, dsc_key, U64(built_at)) )

A memo is a record only if the magic and version match, the country is
0x0000 or two A-Z letters, dsc_key is canonical, the padding is zero and the
tag recomputes. Version 1 records (untagged) are ignored: such a
registration cannot be restored from the mnemonic (register again).
`country` is the wallet's guess at the verifying CSCA's ISO alpha-2: the
DSC's issuer C= (unknown when unparsable). Sync keeps the 32 newest tagged
records (value 0, never spent). Golden (mnemonic `abandon ×11 about`,
dsc_key = 77, country "FR", built_at = 1790000000), pinned in
`RestoreTest` and `RestoreTests`:

    4552024652000000006ab13b8000000000000000000000000000000000000000000000000000000000000000
    4d1d3756b83dfd918fa510770bc257079b000000
    (tag 1d3756b83dfd918fa510770bc257079b)

**State records (handle and caretaker split).** A handle and a caretaker
split are held by a scope nullifier no query names. Every MsgBindHandle
(claim, renew, change, release), MsgSetCaretaker (cast, refresh, clear),
MsgMoveHandle and MsgMoveCaretaker carries state records:

    handle:    "EH" (0x45 0x48) || 0x01 || kind (u8)
               || handle (32 ASCII bytes, zero padded; zero unless kind 1)
               || zero (12) || tag (16)
    caretaker: "EC" (0x45 0x43) || 0x01 || kind (u8)
               || expires_at (u32 BE unix seconds; 0 unless kind 1)
               || split (40 bytes: (option_id uvarint LEB128, percent u8)…,
                  options ascending, zero padded) || tag (16)
    kind:      1 holds, 2 released / cleared, 3 moved out;
               caretaker 0x81: holds, the split not recorded (it did not fit
               40 bytes, or has more than 20 options), split bytes zero
    tag:       first 16 bytes of BE32( H(Tag("earth.statetag"), nk, Bytes(memo[0..48))) )

The tag sits at memo[48..64) and covers every byte before it. A record is
accepted only if the magic, version and kind are known, the tag recomputes
(checked first), every padding byte is zero, a held handle is a valid
handle (§14), a kind-1 caretaker record has expires_at ≠ 0, and a recorded
split has 1-20 distinct options of 1-100 percent summing to 100. A written
expires_at is clamped to [1, 2^32 − 1]. Goldens are pinned in `RestoreTest`
and `RestoreTests` (`stateRecordsRoundTripAndRefuseForgeries`). Who writes what:

| tx | to this identity's address | to the new identity's address |
| --- | --- | --- |
| MsgBindHandle claim/renew/change | holds handle | |
| MsgBindHandle release | released | |
| MsgSetCaretaker | holds (split, now + R as estimated when built) / cleared | |
| MsgMoveHandle | moved out | holds handle (tagged with the new nk) |
| MsgMoveCaretaker | moved out | holds (split, the chain's expiry) |

The mover has the new wallet's keys on the phone, so it addresses and tags
that wallet's record. How sync applies them: §19.

**Unlock record.** MsgUnlockPosition's fee bundle carries a value-0 record
naming the owner-tag counter it closed (§7):

    memo = "EU" (0x45 0x55) || 0x01 || counter (u32 BE)
           || first 16 bytes of BE32( H(Tag("earth.unlocktag"), nk, U64(counter)) ) || zero padding

An unlock memo whose tag recomputes raises `closed_otag_max` (a gift with
someone else's memo is ignored, so it cannot stretch the owner-tag scan).

## 7. Stake notes, labels, positions

**[chain]** Delegated stake (`derth/<valoper>`) lives in its own
append-only tree, owner-locked: it can be merged, undelegated, redelegated,
voted or locked by its owner, never sent.

    spc   = H(TAG_SPC, owner_pk, rho, rcm)
    cm    = H(TAG_STAKE, AssetID(derth/<valoper>), amount, spc, label)
    label = H(Tag("earth.slabel"), move_key, move_time, exposed)    (0: unlabelled)
    nf    = H(TAG_SNF, nk, rho, position)                            position a u32
    otag  = H(TAG_OTAG, owner_pk, salt)

The chain mints no stake note: every one is a stake proof's output. A label
marks derth that arrived by a move (§15): `exposed` of it (the move's
credit), `move_key` the move's credit nullifier, `move_time` the time the
move named. It stays until the label window (`window_seconds`,
Query/DebtTree) after move_time has passed; a slash of the source in that
window owes through the move's debt row (§8). A note holds at most one
label. Vectors: `derive.stake_label`, `stake_cm`, `stake_cm_labelled`.

**One note per validator [wallet].** Every delegation, unlock and credit
merges into the wallet's note at the validator, so a wallet holds one note
per validator; a second appears only beside a labelled note (a move into a
validator where ours is labelled) or from another device, and merges by
MsgRestake on the user's tap (at most one of the two labelled).

**Owner tags [wallet].** A Groundworks position stores `otag`; its owner
proves it again to update, unlock or vote it. Positions carry no
ciphertext. For counter c (a u32):

    salt_c = HMAC-SHA512("earth.privacy.v1", "otag-salt" || nk (32 BE) || c (u32 BE)) mod p
    otag_c = H(TAG_OTAG, owner_pk, salt_c)

A lock takes counter max(next_otag_counter, highest owned counter found + 1,
closed_otag_max + 1) and advances next_otag_counter; update, vote and unlock
reuse the position's salt. Every other stake proof (delegate, undelegate,
restake, redelegate) uses a fresh random salt. Sync
matches the public positions against counters 0 … max(next, closed + 1) +
1024, extended past every match (OTAG_GAP = 1024: a closed position
disappears from the chain, so the window must cross a run of closed
positions and failed locks). Closed counters are never reused: a position
disappears only by unlock, and the unlock record (§6) lets a restored wallet
start past every closed tag. Residual: a lock that failed in its block
published its tag without creating a position, and a restored wallet may
reuse that counter.

A position's weight is shown as derth × its validator's current rate (0
without a live split).

## 8. Trees

All are depth-32 Poseidon2 Merkle trees (**[chain]** `zk/merkle`):

    node = H(left, right)    empty leaf = 0    zero[i+1] = H(zero[i], zero[i])
    bit i of the index = 1  ⇔  the running node is the right child at level i

zero[32] = `0b59baa35b9dc267744f0ccb4e3b0255c1fc512460d91130c6bc19fb2668568d`.

- **Note tree** (x/shielded, append-only): pool note cms.
- **Identity tree** (x/personhood, updatable: a switched or removed leaf is
  zeroed):

      leaf = H(TAG_LEAF, idc, dsc_key, country, activated_at, predecessor_at)

  activated_at is the registration block's time; predecessor_at the time of
  the switch or re-entry that made the leaf (its activated_at), 0 for a
  passport never registered before. Vectors `derive.leaf`, `leaf_pred`.

  **Succession leaves** share the tree: when a passport whose last
  registration (live or lapsed) was to idc_old registers to idc_new ≠ idc_old
  (a switch, or a re-entry), the chain appends, right after the new identity
  leaf,

      succession = H(TAG_SUCC, idc_old, idc_new)      TAG_SUCC = Tag("earth.succ")

  It is never zeroed. The wallet takes it from the indexer's stream like any
  other leaf (no membership proof can use it: another tag and arity). The
  move circuit proves a move along one (§9, §14).
- **Stake tree** (x/shieldedstaking, append-only): stake note cms.
- **Stake nullifier tree** (`zk/indexed`): an indexed (sorted) tree; leaf i
  = H(TAG_SNFL, value, next_value, next_index), leaf 0 the sentinel (value
  0), each leaf pointing at the next larger value (0, 0 for the largest).
  The tree after n inserts is fixed by the final sorted order, so the
  wallet writes the final leaves in one batch (the same root as replaying
  the inserts). nf_size counts leaves, sentinel included. Vectors `indexed`.
- **Slash debt tree** (`zk/debt`): an indexed tree with one row per slashed
  redelegation; leaf i = H(TAG_DEBTL, key, next_key, next_index, retained),
  leaf 0 the sentinel (0, smallest key, its index, 0); the empty tree is
  the sentinel alone (root `0cea3d3e26cd2710109d7cbff5bf48570ba54332f812d538893f0958007f6903`).
  A move with a row is worth its row's `retained`; a move absent (a low
  leaf below it whose successor is above it, or none) is worth its whole
  exposure. Vectors `debt`.

The wallet builds the note, identity and stake trees from the indexer's full
streams and takes its own paths locally: nothing it asks names a leaf of its
own. The nullifier and debt trees are read whole when needed (§15) and
checked against the chain's root.

## 9. Circuits: public-input order and witnesses

**[chain]** Every privacy circuit is proven with the bundled 2^15 + 1 point
SRS (circuit sizes 2^13 to 2^15) and compiled with nargo 1.0.0-beta.22; `bb write_vk` of
every bundled circuit equals the chain genesis's verifying key. Every proof
is exactly 14,656 bytes. Prover kinds split these public-input counts:
action 6, stake 16, membership 8, vote 9, move 5.

**action** (one spend and one output of a bundle):

    private: nk, s_asset, s_value, s_rho, s_rcm, s_pos, s_path[32], o_asset, o_value, o_pc, rcv
    public:  anchor, nf, cm_out, cv_x, cv_y, sighash

Spend: cm = H(TAG_CM, s_asset, s_value, H(TAG_PC, owner_pk, s_rho, s_rcm))
at s_pos under anchor (not enforced for a dummy, s_value = 0), nf =
H(TAG_NF, nk, s_rho, s_pos). Output: cm_out = H(TAG_CM, o_asset, o_value,
o_pc). cv = s_value·G(s_asset) − o_value·G(o_asset) + rcv·R (§10).

**stake** (one owner-locked operation; ORCHARD_DESIGN 4.1, 8.2):

    private: nk, in_amount[2], in_rho[2], in_rcm[2], in_pos[2], in_path[2][32],
             in_move_key[2], in_move_time[2], in_exposed[2],
             out_amount, out_rho, out_rcm, clear,
             debt_low_key, debt_low_next_key, debt_low_next_index, debt_low_retained, debt_low_index, debt_low_path[32],
             cr_in_amount, cr_in_rho, cr_in_rcm, cr_in_pos, cr_in_path[32], cr_out_rho, cr_out_rcm,
             tag_salt
    public:  anchor, asset, nf_0, nf_1, cm_out, v_in, v_out, clear_before, debt_root,
             cr_asset, cr_nf, cr_cm, cr_v_in, cr_move_time, otag, sighash

Two lanes under `anchor`. Lane A (`asset`) spends up to two notes, at most
one labelled, and creates one; v_in is credited (delegation, unlock), v_out
leaves (undelegation, lock, a move's source). It either keeps the label
(the output carries the same label, and in_0 + in_1 − exposed + v_in = out
− exposed + v_out) or clears it once `move_time < clear_before`, reading the
move's row in the debt tree at `debt_root` (in_0 + in_1 − exposed +
retained + v_in = out + v_out, output unlabelled). Lane B (`cr_asset`, the
credit lane) merges `cr_v_in` credited by a move into at most one
unlabelled note: cr_in + cr_v_in = cr_out, the output labelled (move_key =
cr_nf, move_time = cr_move_time, exposed = cr_v_in). Padding: an input of
amount 0 publishes 0 or its own would-be nullifier; an output of amount 0
publishes 0 or a zero note's commitment. Vectors `public_inputs.delegate`,
`undelegate`, `redelegate`.

**Stake padding [chain, ORCHARD_DESIGN 8.3].** A msg that moves notes
(Delegate, Undelegate, Redelegate, Restake, Lock, Unlock) spends in both
lane-A slots: nf_0 and nf_1 are both non-zero, else the chain refuses the
proof's shape. A slot holding none of the wallet's notes is a padding
input (amount 0, a fresh random rho and rcm, pos 0, path zero) publishing
its own would-be nullifier H(TAG_SNF, nk, rho, 0), so a merge of two notes
looks like a spend of one (a zero nf_1 used to show a single spend, and a
second note at a validator mostly sits beside a labelled redelegation
credit). Position updates and votes still use neither slot (nf_0 = nf_1 =
0). The circuit is unchanged.

**vote** (MAX_NOTES = 2 stake notes of one validator on one proposal,
nothing spent; ORCHARD_DESIGN 4.2, 8.5):

    private: nk, amount[2], rho[2], rcm[2], pos[2], path[2][32],
             move_key[2], move_time[2], exposed[2],
             low_value[2], low_next_value[2], low_next_index[2], low_index[2], low_path[2][32],
             debt_low_key[2], debt_low_next_key[2], debt_low_next_index[2], debt_low_retained[2],
             debt_low_index[2], debt_low_path[2][32]
    public:  note_root, nf_root, debt_root, asset, weight, proposal_id, vnf[0], vnf[1], sighash

For each used slot (amount ≠ 0): the note is under note_root (the
snapshot's stake root); its spend nullifier H(TAG_SNF, nk, rho, pos) is not
in the nullifier tree at nf_root (a low leaf with low.value < nf <
low.next_value, or low.next_value = 0); vnf = H(TAG_VNF, nk, rho, pos,
proposal_id); its value is its amount, or for a labelled note amount −
exposed + retained against the current `debt_root`. An unused slot (amount
0, rho = r, every other field zero) publishes the padding nullifier
vnf = H(TAG_VPAD, nk, r, proposal_id), r fresh random per vote: it looks
like a note's vnf and never equals one (tag and arity differ), so the
number of notes voted is hidden. 0 < weight ≤ the sum of the values. Vector `public_inputs.stake_vote`.

**membership** (anonymous proof of a live registration):

    private: id_secret, dsc_key, country, activated_at, predecessor_at, leaf_index, siblings[32]
    public:  root, scope, nullifier, signal, excluded_dsc, excluded_country, max_activation, max_predecessor

Proves the leaf is in the identity tree at root, nullifier = H(TAG_SN,
id_secret, scope), dsc_key ≠ excluded_dsc, country ≠ excluded_country
unless that is 0, activated_at ≤ max_activation, predecessor_at ≤
max_predecessor, and binds signal (the msg's sighash). "No bound" is 2^63 −
1 (`Privacy.NO_BOUND`). Vector `membership_public_inputs`.

**move** (a handle or caretaker split passes to the same passport's next
identity; MsgMoveHandle, MsgMoveCaretaker):

    private: old_secret, new_secret, succession_index (u32), succession_siblings[32],
             dsc_key, country, activated_at (u64), predecessor_at (u64),
             leaf_index (u32), siblings[32]
    public:  root, scope, old_nullifier, new_nullifier, signal

Proves H(TAG_SUCC, H(TAG_ID, old_secret), H(TAG_ID, new_secret)) is in the
identity tree at root (at succession_index), the successor's identity leaf
H(TAG_LEAF, H(TAG_ID, new_secret), dsc_key, country, activated_at,
predecessor_at) is too (at leaf_index; the successor's own registration
record gives those fields), old_nullifier = H(TAG_SN, old_secret, scope),
new_nullifier = H(TAG_SN, new_secret, scope), and binds signal (the msg's
sighash). scope is Scope("handle") for MsgMoveHandle and Scope("caretaker")
for MsgMoveCaretaker; root is a recent identity root (as a membership's).
The prover needs both identity secrets: the old wallet's and the new
wallet's, both on the phone. 8,362 gates (2^14). Bundled as
`assets/circuits/move.json` (Android and iOS share the folder);
`bb write_vk` of it equals genesis 01298d6b's `move` key. The engine sets
the msg's MoveProof (root, both nullifiers, proof) once proven; a quote lays
out random nullifiers, as for a membership. `fixture_move` (test resources)
is a wallet witness of a post-switch handle move: nargo executes it, bb and
Swoirenberg prove it, and the chain's verifier (tools/chainverify) accepts
the proof under the genesis key.

**passport** (`lean_poa_*`, 33 variants; registration only): one circuit per
DSC key type (RSA-2048/3072/4096; ECDSA P-224/256/384/521, brainpoolP224r1/
256r1/384r1/512r1), signature padding (PKCS#1 v1.5, PSS) and hash profile
(data-group hash, eContent hash, signature hash), listed in
`circuits/variants.json` and bundled as
`assets/circuits/passport_variants.json` (PASSPORT_COVERAGE.md). Every
variant has the same public inputs `current_date` (u32 YYMMDD), `address`
(the registration binding, §13), then the returned `nullifier`, `dsc_key`
commitment and `idc`. The wallet sends them as MsgRegister.public_signals,
decimal, in that order: [current_date, address, nullifier, dsc_key, idc].
`idc` = H(TAG_ID, id_secret) is computed in the circuit from the private
witness `id_secret`; the chain (params.idc_index = 4) requires it to equal
MsgRegister.idc, so a passport is registered only to an identity whose
secret its prover holds (audit R2-B1).
`signature_algorithm` is the variant id.

**Proof date [chain + wallet].** The wallet always proves on today's UTC
date. The chain accepts current_date within current_date_max_skew_seconds
(48 h) of the block time and records it as Registration.proof_date (field
9, unix seconds at midnight UTC). A switch (the passport live under another
idc) must be proven on a strictly later date than the live registration's
proof_date, else error 1128 (ErrSwitchProofStale): a passport switches at
most once per UTC day, and the wallet says so ("try again tomorrow"). The
check is in the ante, so a refused switch costs nothing.

**Keeping a sent registration's identity [wallet].** A registration's bytes
are public once broadcast, and one that failed or was refused may still
land while its current_date is within the skew. From just before the
broadcast the wallet records `registration_keep_until` (iOS
`registrationKeepUntil`) = current_date (unix) + the skew in the wallet's
state, the skew being the chain's current_date_max_skew_seconds (x/personhood
params, field 7, read from `/earth/personhood/v1/params` before the
broadcast) taken in [172,800, 366 days]: never below the default 48 h (a
node that says nothing, or 0, gets 48 h; a shorter window would only mean
keeping longer than needed), never above the year governance may set plus a
day; until then that wallet counts as possibly registered (a switch
target's "already has a registration" warning), and its identity, so its
recovery phrase, must be kept. A reset or genesis switch of the synced
data carries it.

**Variant selection [wallet].** From EF.SOD: the LDS security object's
hashAlgorithm (H_dg), SignerInfo digestAlgorithm (H_ec), signatureAlgorithm
(PKCS#1: `shaXWithRSAEncryption`, or `rsaEncryption` with H_ec; PSS:
`RSASSA-PSS` params, RFC 4055 defaults; ECDSA: `ecdsa-with-SHAx`, or
`id-ecPublicKey` with H_ec) and the DSC key (RSA bit length and exponent;
the curve by named OID, or by explicit ECParameters matching p, a, b, G, n
and cofactor 1). The id is `lean_poa_<key>[_pss]_<hash>` when the three
hashes agree, else `lean_poa_<key>[_pss]_<H_dg>_<H_ec>_<H_sig>`. Refused as
unsupported, with the scheme named ("This passport's signature type isn't
supported yet (RSA-2048 PSS, SHA-1)"): a DG1 that is not 93 bytes, absent
signed attributes, an unknown hash, curve, key or signature OID, an MGF1
hash other than the message hash, a trailer other than 1, a PSS salt over
64, an RSA exponent that is even or outside [3, 2^17), no variant for the
combination, or an eContent or signed-attributes encoding longer than the
variant's maximum (by H_dg: SHA-1 439, SHA-224/256 695, SHA-384/512 751
bytes; by H_sig: 247, 256, 239). The scheme strings and every rule are
`circuits/tools/passportgen/reference.py`'s; the shared fixtures
`circuits/fixtures/<variant>` and `fixtures/unsupported/*` hold its output,
which both apps reproduce byte for byte (Android PassportInputsTest, iOS
PassportInputsTests and corecheck).

**Witness.** dg1 (95, zero-padded), dg1_len, e_content and signed_attrs
zero-padded to the variant's maxima with their lengths, dg1_hash_offset and
econtent_hash_offset (the index of the digest right after its DER prefix:
`30 (H+5) 02 01 01 04 H` and `06 09 2A864886F70D010904 31 (H+2) 04 H`),
`id_secret` (the identity's secret, "0x" hex: the one whose idc
MsgRegister names), current_date, address, then by key: RSA `dsc_modulus`, `dsc_redc`
(floor(2^(2·bits+6) / n)), `sod_signature` as 120-bit little-endian limbs
(18, 26, 35), `dsc_exponent`, and for PSS `pss_salt_len`; P-256
`dsc_pubkey_x`, `dsc_pubkey_y`, `sod_signature` r‖s (low s); other curves
the coordinates and `sod_signature_r`, `sod_signature_s` (low s), each at
the curve's coordinate width. Bytes as "0x%02x" strings, scalars and limbs
as "0x" lowercase hex.

**Circuits.** The 16 variants of the 2^18 tier ship in the app (stripped to
bytecode and ABI); the other 17 are fetched once, on demand, from
`download_base` + `<id>.json.gz` (the backend's `/circuits`), inflated, and
kept only if the JSON hashes to the variant's pinned `sha256`.

**SRS.** Both apps bundle the first 32,769 G1 points of Aztec's bn254
transcript (`crs.aztec.network/g1.dat` bytes 0..2,097,215, file
`srs/bn254_g1_32769.dat`, sha256
`d769ac6c98f8fab858a7e9967f2b7f181d8ad9fdcdf55438c915696febf0e99c`), checked
before use and passed to bb as a `.dat` path: proving a private tx never
touches the network, and a missing file is an error (never a download). The
passport circuits' SRS (too large to bundle) comes in three tiers, each a
byte range of the same file, hash-pinned in the manifest: 2^18 + 1 points
(`8f5cd75519c2e995fa47aa7ecd7b213b9ae13824bc4b0f15025a63acd8c139eb`, fetched
at launch), 2^19 + 1
(`1df37a2ce1da3713c7300691a65ffe84de144ea64899d5cfbf0061aaaeb6ad31`) and
2^20 + 1 (`0f238856e55722f15a4d64ef0de12b4260e218245590bd3a6c900aee188de8e5`),
fetched before proving a variant of that tier; streamed to a staged file,
hashed as it comes, cut off at the range's length, no redirect followed,
kept out of backups. A larger tier's file serves a smaller circuit. iOS
reserves the passport size for a private proof only from a local file (the
largest bundled circuit, the 2^18 tier).

**Witness dumps.** With `PRIVACY_TOML_OUT=<dir>` the wallet tests write every
witness as `<dir>/{action,stake,membership,vote}/<test>_<i>/Prover.toml`
(§21).

## 10. Bundles

**[chain]** (zk/orchard, ORCHARD_DESIGN 2.3-2.5). A bundle is N ≥ 2 actions,
each proven on its own, a public value balance per denom, and a binding
signature.

- **Value commitments** on Grumpkin (Noir's embedded curve, y² = x³ − 17):

      G_a = hash_to_point(TAG_GEN, AssetID(denom))
      R   = hash_to_point(TAG_CV_R, 0)
      hash_to_point(tag, input): x = H(tag, input, ctr), least ctr with x³ − 17 a square, y its root ≤ (p − 1)/2
      cv  = v_spend·G_spend − v_out·G_out + rcv·R

  R is pinned (`privacy_core::value::R`, ctr 0; vectors `orchard.r`). A
  point on the wire is x (32) || y (32).
- **Digest:**

      digest = H(TAG_BUNDLE, U64(N), [anchor_i, nf_i, cm_i, cv_x_i, cv_y_i, Bytes(ct_i)]…, U64(M), [AssetID(denom_j), U64(amount_j)]…)

- **Binding signature** (Schnorr over Grumpkin, base R; 96 bytes):

      bvk = Σ cv_i − Σ amount_j·G_j        bsk = Σ rcv_i mod n
      k   = SHA-512(BE32(bsk) || sighash || rnd (32 fresh random bytes)) mod n     [wallet: the nonce]
      Rn  = k·R
      e   = H(TAG_BSIG, Rn.x, Rn.y, bvk.x, bvk.y, sighash)
      s   = k + e·bsk mod n
      sig = Rn.x || Rn.y || s

  Verify: s·R = Rn + e·bvk, Rn on the curve and not infinity, s < n, bvk not
  infinity. n is Grumpkin's group order (vectors `orchard.n`).
- **Balances** are computed from the actions (spends − outputs per denom,
  positive only, sorted by denom); a negative one is refused.

**Layout [wallet].** The msg's public release per denom (a fee in uerth, an
unshield, what a module takes) plus every payment output is what must leave
each denom; notes per denom: the smallest single note covering it, else the
fewest notes largest first with the last swapped for the smallest that still
covers. Each denom's surplus returns as one v1 change note to self. Spends
and outputs are shuffled independently and paired into max(#spends,
#outputs, 2) actions (an action's spend and output may be different
assets), dummies filling either side: a dummy spend has value 0, asset
uerth, a fresh random rho and rcm, position 0 and a zero path (its
nullifier is still published and spent); a dummy output value 0, asset
uerth, a random pc and a ciphertext to a throwaway key. Every action's
anchor is the wallet's current chain-verified note root. Every rcv is a
fresh random field element. A layout above x/shielded's
max_actions_per_bundle (chain default 16; zk/orchard MaxActions 32 bounds
it) is refused ("merge first"; the user merges a denom's smallest notes in
one MsgSend to self).

## 11. Private transactions

**Shape [chain].**

- TxRaw with one Any, AuthInfo with no signer infos, fee = exactly the msg's
  private fee in uerth, no payer or granter, no signatures, no
  timeout_timestamp (refused for private txs), AuthInfo.tip unset.
- **Canonical bytes.** The tx is exactly TxRaw{body_bytes, auth_info_bytes},
  each part and the msg inside the body the canonical protobuf encoding of
  what it decodes to (fields in number order, minimal varints, no default
  scalars, no unknown or extension fields). Both apps encode with protobuf
  builders (javalite, SwiftProtobuf), which produce exactly this.
- **Ciphertext slots.** Every action's output ciphertext is exactly 217
  bytes, dummies included. A StakeProof carries exactly two lane-A
  nullifiers; anchor, owner_tag, commitment, credit_nullifier,
  credit_commitment and debt_root are each 32 bytes; `ciphertext` is
  exactly 201 bytes iff `commitment` is non-zero (else empty), likewise
  `credit_ciphertext` and `credit_commitment`; debt_root is zero exactly
  when clear_before is 0. Both apps check all of this before broadcast.
- **One use per binding.** A registration's binding is refused once a
  registration with it has landed (§13).
- **gas_limit** at most 5× what the tx uses.
- **Sighash** (every private msg; vectors `binding.sighash_tx`, `msgs`):

      sighash = H(TAG_SIGNAL, Bytes(type_url), Bytes(chain_id), U64(K), digest(bundle_0..K-1),
                  Bytes(memo), U64(timeout_height), U64(gas_limit), msg fields…)

  `Bytes(memo)` over the memo's UTF-8 bytes (Bytes("") for none); 0 for no
  timeout. The msg fields are in §12. Every action proof, the stake proof,
  the vote proof and the membership (as its signal) are made over it, and
  every bundle's binding signature signs it.

**Fee rule [chain].** fee = the bundles' uerth balance less the uerth the
msg moves itself. Moves: MsgDelegate.amount; MsgNoteSwap.amount_in when
denom_in is uerth; MsgAddLiquidityShielded.erth_amount; nothing for every
other staking, dex, personhood and assembly msg (their whole uerth balance
is the fee). MsgSend names its fee (uerth beyond it is unshielded to its
receiver). No msg pays from its output: a swap of ANML into ERTH needs an
ERTH note for its fee.

**Assembly [wallet]** (`PrivateTxEngine`):

1. timeout_height = the LCD's latest height + 50. The tip must be within
   1,000 blocks of the store's `verified_height` (the indexer height of the
   last verified sync), else the wallet syncs once and retries, then
   refuses (TipOutOfRange).
2. Lay the tx out at a guessed fee (3,000,000 gas) with 14,656-byte
   placeholder proofs and a zero binding signature over the real anchors,
   commitments, value commitments and ciphertexts, and simulate. gas limit
   = simulated gas + max(10 %, 20,000); fee = max(x/shielded min_fee,
   ceil(node min gas price × gas limit)). Re-lay at that fee; if the action
   count changes, simulate again (at most 4 rounds, the fee only rising
   after the first).
3. **Fee cap.** Before anything is proven the fee must be at most min(2 ERTH,
   2 × the wallet's own estimate), the estimate pricing (at the node's gas
   price, at least min_fee) the gas of the tx's shape at the chain's
   default schedule: 100,000 + 10 per tx byte + per bundle 100,000 +
   2,300,000 per action; + 3,150,000 for a stake proof (+ 750,000 for a
   credit lane and 1,024 × 5,000 + 128 × 20,000 for MsgRedelegate's
   x/staking record at its worst); + 2,150,000 for a membership or a move
   proof (+ 3 × 150,000 for MsgMoveHandle, + 5 × 150,000 for
   MsgMoveCaretaker: four and six note writes); + 3,300,000 + 6 × 150,000
   for MsgRegister (six note writes, the sixth the succession leaf a switch
   or re-entry appends); + 2,250,000 + (1 + 2) × 150,000 (both slots, padding included) for
   a stake vote; + 8 × 150,000 for MsgBindHandle (a bind is priced as nine
   note writes). x/shielded's gas prices are capped (proof 10M, note 1M,
   bundle 1M). A node asking more is refused (FeeAboveCap; nothing proven
   or sent).
4. **Confirm sheets.** Every tx comes from a confirm sheet whose fee bounds
   it: a fee above the sheet's throws before proving (FeeAboveQuote) and
   the sheet is shown again at the new fee. A sheet's fee estimate is
   10,000,000 gas for a private tx, 12,500,000 for a handle bind, 7,000,000
   for a registration; these are never declared as a gas limit.
5. **Quotes** (simulate without proving, for a sheet) carry random
   nullifiers in place of the wallet's (pool, stake lane A and credit, the
   membership's, both of a vote's vote nullifiers; other zeros stay zero), so the node learns
   nothing about the notes before the user confirms.
6. Fix memo, timeout_height and gas limit; compute the sighash; prove every
   action, the stake proof, the membership and the vote over it; sign every
   bundle; check every proof is 14,656 bytes, the sighash recomputes, the
   fee is exactly the quote and the slots above; broadcast.

The wallet sets memo "" except on an unshield, which may carry a user memo
(an exchange deposit tag).

**Anchors.** CheckTx/ReCheckTx refuse an anchor lapsing within 120 s of the
last block, and a proposer leaves out a tx whose anchor lapsed by its
block's time. Before laying out a private tx the wallet reads its local
root's record (`/earth/shielded/v1/roots/{root}`: `valid`, `expires_at`, 0
for the latest root); the chain computes `expires_at` as the root's
`superseded_at` (RootRecord field 5: the time of the block whose root
replaced it, 0 while it is the latest) + root_window_seconds, so a root
stays an anchor for the full window after it stops being the latest,
however long it was the latest; `time` (when it was recorded) no longer
bounds it. The stake tree's StakeRoot has the same `superseded_at` (field
5) and window rule; the wallet anchors stake proofs to its synced stake
tree's root, which is the chain's latest or was superseded within the
window. The wallet never computes an expiry from `time`. One lapsing within 1,800 s of the LCD tip's time
makes it sync first (a newer root), and if that is still too old the tx is
refused before anything is proven (Android SyncFirst, iOS AnchorTooOld). A
node that does not say `expires_at` leaves the chain's own check.

**Pending spends [wallet].** The notes (and stake notes) a tx spends are
marked pending before it is sent, with its timeout_height and its hash
(computed locally: uppercase hex SHA-256 of the raw tx bytes; a node naming
another hash is an error). A refusal that proves the tx is in no mempool
(CheckTx's non-zero code, no connection at all) unmarks them at once; any
other failure keeps them. They are released only when the LCD's latest
height is past that timeout_height, the wallet has read the nullifier
stream through it without seeing their nullifiers, and the LCD says the tx
is missing (`GET /cosmos/tx/v1beta1/txs/{hash}` 404) or failed in its block
(code ≠ 0): never by the wall clock. A tx the LCD says is in a block whose
spend the indexer never reported keeps them pending and the sync
unverified; an LCD that cannot say keeps them. A mark (note, stake note,
vote, move) whose timeout_height is more than 1,050 blocks past the current
`verified_height` came from an inflated tip and is settled by the tx's
status alone (missing or failed: released; notes after a 15-minute mempool
grace). Marks from builds without a hash keep the timeout alone, and without
a timeout, 15 minutes.

## 12. Messages

**Per msg [chain]:** type URL, bundle field, fields bound after the
digests (each msg's Go `SighashFields`), and the membership scope / stake
proof it carries. `StakeFields` (every stake-proof msg, first):

    StakeFields = anchor, nf_0, nf_1, cm, Bytes(ciphertext), credit_nf, credit_cm, Bytes(credit_ciphertext),
                  owner_tag, U64(clear_before), debt_root          (absent field: 0; absent bytes: Bytes of nothing)

| msg | sighash fields after the digests |
| --- | --- |
| `/earth.shielded.v1.MsgSend` | Bytes(receiver address bytes, nothing for none), U64(fee) |
| `/earth.personhood.v1.MsgRegister` | idc, pc_anml, Bytes(ct_anml), pc_erth, Bytes(ct_erth), affiliate, Bytes(signature_algorithm), each public signal as a field |
| `/earth.personhood.v1.MsgClaimAnml` | U64(day), pc, Bytes(ct) |
| `/earth.personhood.v1.MsgSetCaretaker` | per entry U64(option_id), U64(percent) (max_predecessor is not a sighash field) |
| `/earth.personhood.v1.MsgMoveCaretaker` | (none) |
| `/earth.personhood.v1.MsgBindHandle` | Bytes(handle), owner_pk, Bytes(ek_pub) of the address (release: Bytes(""), 0, Bytes("")) |
| `/earth.personhood.v1.MsgMoveHandle` | Bytes(handle) |
| `/earth.assembly.v1.MsgVoteProposal` | U64(proposal_id), U64(option) |
| `/earth.assembly.v1.MsgProposeRemoval` | U64(option_id) |
| `/earth.assembly.v1.MsgVoteRemoval` | U64(option_id), U64(option) |
| `/earth.shieldedstaking.v1.MsgDelegate` | StakeFields, Bytes(validator), U64(amount), U64(derth) |
| `/earth.shieldedstaking.v1.MsgRestake` | StakeFields, Bytes(validator) |
| `/earth.shieldedstaking.v1.MsgUndelegate` | StakeFields, Bytes(validator), U64(amount), pc, Bytes(ciphertext) |
| `/earth.shieldedstaking.v1.MsgRedelegate` | StakeFields, Bytes(src_validator), Bytes(dst_validator), U64(amount), U64(dst_derth), U64(move_time) |
| `/earth.shieldedstaking.v1.MsgLockPosition` | StakeFields, Bytes(validator), U64(amount), Bytes(SplitsBytes) |
| `/earth.shieldedstaking.v1.MsgUpdatePosition` | StakeFields, U64(position_id), Bytes(SplitsBytes) |
| `/earth.shieldedstaking.v1.MsgUnlockPosition` | StakeFields, U64(position_id) |
| `/earth.shieldedstaking.v1.MsgPositionVote` | StakeFields, U64(position_id), U64(proposal_id), Bytes(OptionsBytes) |
| `/earth.shieldedstaking.v1.MsgStakeVote` | U64(proposal_id), Bytes(validator), Bytes(OptionsBytes), U64(weight), vnf_0, vnf_1, debt_root (no StakeFields) |
| `/earth.dex.v1.MsgNoteSwap` | Bytes(denom_in), U64(amount_in), Bytes(denom_out), U64(min_amount_out), pc, Bytes(ct) |
| `/earth.dex.v1.MsgAddLiquidityShielded` | U64(pool_id), Bytes(min_shares), share_pc, Bytes(share_ct), refund_pc, Bytes(refund_ct), U64(erth_amount) |
| `/earth.dex.v1.MsgRemoveLiquidityShielded` | U64(pool_id), erth_pc, Bytes(erth_ct), token_pc, Bytes(token_ct) |

Strings (validator, denom, handle, min_shares, signature_algorithm) are
Bytes of their bytes. Public signals are canonical decimals below p.

- **SplitsBytes** = per entry, option_id (u64 BE) || percent (u64 BE)
  (vectors `splits_bytes`). Splits are sent sorted by option_id.
- **OptionsBytes** = per option, option (u64 BE) || len (u32 BE) || the
  weight's LegacyDec string (vectors `options_bytes`). Every option weight
  in MsgStakeVote / MsgPositionVote is the canonical LegacyDec string, 18
  decimals ("1.000000000000000000", "0.500000000000000000"; vectors
  `legacy_dec`); the wallet canonicalizes before laying the msg out and
  refuses a weight outside (0, 1].

**Proto field numbers [chain]** (`android/app/src/main/proto/earth/…`;
reserved numbers are never written):

    shielded.Action          anchor 1, nullifier 2, commitment 3, cv 4, ciphertext 5, proof 6
    shielded.ValueBalance    denom 1, amount 2
    shielded.Bundle          actions 1, balances 2, binding_sig 3
    MsgShield                sender 1, amount 2, pc 3, ciphertext 4
    MsgSend                  bundle 1, receiver 2, fee 3
    personhood.Membership    proof 1, root 2, nullifier 3
    MsgRegister              fee 1, proof 2, public_signals 3, signature_algorithm 4, dsc_der 5, idc 6,
                             pc_anml 7, ciphertext_anml 8, pc_erth 9, ciphertext_erth 10, affiliate_handle 15;
                             reserved 11-14
    MsgClaimAnml             fee 1, membership 2, day 3, pc 4, ciphertext 5
    MsgSetCaretaker          fee 1, membership 2, percentages 3, max_predecessor 5; reserved 4
    MsgMoveCaretaker         fee 1, move 2
    MoveProof                proof 1, root 2, old_nullifier 3, new_nullifier 4
    MsgBindHandle            fee 1, membership 2, handle 3, address 4, max_predecessor 6; reserved 5
    MsgMoveHandle            fee 1, move 2, handle 3
    MsgVoteProposal          fee 1, membership 2, proposal_id 3, option 4   (YES 1, NO 2)
    MsgProposeRemoval        fee 1, membership 2, option_id 3
    MsgVoteRemoval           fee 1, membership 2, option_id 3, option 4
    StakeProof               proof 1, anchor 2, nullifiers 3 (two), owner_tag 7, commitment 9, ciphertext 10,
                             credit_nullifier 11, credit_commitment 12, credit_ciphertext 13,
                             clear_before 14, debt_root 15; reserved 4, 5, 6, 8
    MsgDelegate              bundle 1, validator 2, stake 4, amount 5, derth 6; reserved 3
    MsgRestake               bundle 1, validator 2, stake 4; reserved 3
    MsgUndelegate            bundle 1, validator 2, amount 3, stake 5, pc 6, ciphertext 7; reserved 4
    MsgRedelegate            bundle 1, src_validator 2, dst_validator 3, amount 4, stake 5, dst_derth 6, move_time 7
    MsgLockPosition          bundle 1, validator 2, amount 3, splits 4, stake 6; reserved 5
    MsgUpdatePosition        bundle 1, position_id 2, splits 3, stake 5; reserved 4
    MsgUnlockPosition        bundle 1, position_id 2, stake 4; reserved 3
    MsgPositionVote          bundle 1, position_id 2, proposal_id 3, options 4, stake 6; reserved 5
    MsgStakeVote             bundle 1, proposal_id 2, validator 3, options 4, weight 5, proof 8,
                             vote_nullifiers 10 (exactly two), debt_root 11; reserved 6, 7, 9
    MsgNoteSwap              bundle 1, denom_out 2, min_amount_out 3, pc 4, ciphertext 5, denom_in 8, amount_in 9;
                             reserved 6, 7
    MsgAddLiquidityShielded  bundle 1, pool_id 3, min_shares 5, refund_pc 6, refund_ciphertext 7, share_pc 9,
                             share_ciphertext 10, erth_amount 11; reserved 2, 4, 8
    MsgRemoveLiquidityShielded bundle 1, pool_id 2, erth_pc 4, erth_ciphertext 5, token_pc 6, token_ciphertext 7;
                             reserved 3
    MsgAddLiquidity (public) creator 1, pool_id 2, amount_a 3, amount_b 4, min_shares 5
    MsgRemoveLiquidity       creator 1, pool_id 2, shares 3, pc 4, ciphertext 5
    MsgBuyAnml               creator 1, token_in 2, min_amount_out 3, pc 4, ciphertext 5

**Membership statements per msg [chain + wallet].** signal = the sighash;
root = the wallet's verified identity root. Scopes (§1): MsgClaimAnml
`claim(day)`, MsgSetCaretaker / MsgMoveCaretaker `caretaker`,
MsgBindHandle / MsgMoveHandle `handle`, MsgVoteProposal `proposal(id,
round)` (recomputed by the wallet from BallotInputs' `round`; a node's
scope that differs is refused), MsgVoteRemoval `removal(ballot_id)`,
MsgProposeRemoval `propose_removal(option_id, day)` (day = the chain tip
time's UTC day). excluded_dsc and excluded_country are 0 except on ballot
votes, which take them from BallotInputs. Bounds: §14.

**Other rules [chain].**

- An unshield (MsgSend with a receiver) to any module account is refused;
  the wallet refuses it before proving. Module accounts: fee_collector,
  distribution, mint, bonded_tokens_pool, not_bonded_tokens_pool, gov, nft,
  transfer, interchainaccounts, shielded, shieldedstaking, dex, allocation,
  personhood, earth, wasm; address = SHA-256(name)[:20] (vectors
  `module_accounts`).
- Send-disabled denoms (bank 5, "send transactions are disabled") are
  refused at shield, unshield, a dex note swap (either side), a private
  delegation's ERTH and any module mint into the pool; the wallet explains
  it ("Transfers of this token are switched off on the chain … no
  shielding, unshielding, note swaps or private staking with it"); notes
  already held still move privately.
- derth is owner-locked: never sent or unshielded; LP shares leave the pool
  only by a withdrawal.

## 13. Registration

**Binding [chain].** The passport proof's `address` public input:

    address   = H(TAG_REG, Bytes(chain_id), idc, pc_anml, Bytes(ct_anml), pc_erth, Bytes(ct_erth), affiliate)
    affiliate = 0 for no referrer, else H(Tag("earth.affiliate"), Bytes(affiliate_handle))

The chain id first keeps a registration seen on one network from being
replayed onto another; the circuit takes `address` as opaque. Pinned
(zk/privacy TestRegistrationBindingPinned; vectors `derive.reg_pinned`):
chain_id "earth-1", idc = 1, pc_anml = 2, ct_anml = "anml", pc_erth = 3,
ct_erth = "erth", affiliate = 0 →
`148b3513a501b6ff9c02314f355cb83fb544e22b2a9df79552fe49c944424159`; the
same with "earth-testnet-1" (`derive.reg_testnet`) →
`122b90a7dc7460e7a9fedc31ab0ee17602b744124eab9fb9b9a7e4770485523a`.

The wallet picks fresh rho/rcm for its ANML and ERTH notes (both v2 to its
own address) and writes their ciphertexts **before** proving the passport,
and sends exactly those ciphertexts in MsgRegister and to /gas/register. A
binding is single-use: a registration whose binding has landed is never
resent; every registration prepares fresh notes. current_date must be a
calendar date (YYMMDD; 250231 is refused); the wallet checks before
broadcast. MsgRegister's fee bundle carries the registration record (§6).

**Referral [chain].** MsgRegister names its referrer by `affiliate_handle`
(15) alone, "" for none. The chain mints the referrer's half at execution
to the address the handle resolves to then, as an open note (§5):

    pc  = H(TAG_PC, handle owner_pk, rho, rcm)
    rho = H(Tag("earth.referral"), passport nullifier, U64(leaf_index), U64(0))
    rcm = H(Tag("earth.referral"), passport nullifier, U64(leaf_index), U64(1))

(zk/privacy.ReferralOpening; leaf_index the new identity leaf). Its
`shielded_mint` event carries owner_pk, rho and rcm (hex) with amount and
position; the handle owner's wallet takes it from the notes stream by its
own owner_pk (§19). Vectors `referral_opening` (five (nullifier,
leaf_index) pairs with rho, rcm, and the pc and cm of 5 ERTH to
OwnerPK(7100+i)).

**Referral capture [wallet].** A referrer comes only from the verified link
`https://erth.network/ref/<handle>` (Android App Link, iOS universal link;
exactly that host, path and a valid handle) or the Play install referrer
(`referrer=<handle>`). There is no custom scheme. First capture wins; the
registrant sees it and may remove or replace it. The handle is resolved
from the whole directory when the registrant confirms the passport details
(live in a fresh copy and in the chain's own directory, §14); one that does
not resolve is cleared, and a wallet never names its own address.

**Gas grant [wallet + backend].** `POST /gas/register` is the only grant.
Body (bytes as standard base64): `proof`, `public_signals`,
`signature_algorithm`, `dsc_der`, `idc`, `pc_anml`, `pc_erth`,
`ciphertext_anml`, `ciphertext_erth`, `affiliate_handle` ("" for none;
never `affiliate_pc` / `affiliate_ciphertext`, which the backend refuses
with a 400), `pc_gas` and `ciphertext_gas` (a fresh v2 note to self, 177
bytes, required), and optionally `pow`. The backend rebuilds MsgRegister
from it and checks it as the chain would. Only the registration's confirm
sheet offers it; every other sheet whose fee the balance cannot cover says
where that fee comes from (shielded ERTH for a private action: shield some
or receive privately; the public account for a signed one: unshield or
receive) and offers no grant.

**Proof of work** (backend services/pow.py): `"pow": {"ts": <int>,
"nonce": "<str>"}`, a hashcash stamp:

    SHA-256( "earth-gas-pow/v1:" + ts + ":" + binding + ":" + nullifier + ":" + nonce )   (ASCII)
    with at least `bits` leading zero bits

ts unix seconds (±600 s), binding and nullifier `public_signals[1]` and
`[2]` exactly as sent, nonce a lowercase hex counter. The wallet asks `GET
/gas/pow` for `bits` (its `version` must be `earth-gas-pow/v1`), stamps at
it (off the main thread, cancellable, with progress; 22 bits is a few
seconds), posts; on 428 it makes a fresh stamp at the answer's `pow.bits`
and posts again (at most 4 rounds); it keeps a stamp for the next try only
after a 503 or 429 (the server gave it back; reused within 300 s), never
after a 403. It works for at most 24 bits: a server asking more is refused.
The gas service's refusals carrying the chain's text get the chain errors'
sentences.

**Recording [wallet].** The moment the node accepts the registration
(CheckTx code 0, before waiting for its block) the wallet persists a pending
registration {tx hash, dsc_key, passport nullifier, public signals, country
hint} and marks the fee bundle's spends pending. The leaf index (the tx's
`register` event `leaf_index`) and activated_at (its block time) are filled
in when the wait returns, or by the next sync, which looks the tx up by
hash; a tx that failed in its block is kept as a failure for the UI (a new
registration replaces it). Every sync then tries to resolve it: once the
local identity tree has the leaf, the country is found by recomputing the
leaf over the hint, unknown (0) and every A..Z pair, each with
predecessor_at 0 and activated_at (a switch or re-entry); the match fixes
the identity's predecessor_at; the identity record is written and the
pending one dropped. It is never dropped unresolved (a leaf that does not
match is an error shown to the user, the record kept). Restore from the
mnemonic: §19.

**Fresh identity [chain + wallet].** The chain refuses a registration to
any idc registered before, by any passport (error 1130, ErrIdcUsed, in the
ante before the proof; genesis `used_idcs`, field 23). Every first
registration, switch and re-entry is to a fresh identity secret; a wallet
never re-registers a retired identity (a switch back to an earlier wallet is
refused: that wallet's identity is spent), and a switch target is always a
wallet whose identity was never registered. The register proof outputs the
idc of the witness `id_secret` (§9), so the wallet proves with the target
identity's own secret.

**Chain errors explained.** personhood 1130 ("This identity has been
registered before. Switch to a new wallet."), 1127 (a switch proven under another
Document Signer than the live registration's: "This switch was refused. A
switch must be proven with the same passport you registered with …"), 1113
(a signer's daily cap, switches included: "Today's limit for passports
from this issuer has been reached. Try again tomorrow.").

## 14. Handles, caretaker split, identity moves

**Handles [chain].** Lowercase a-z, 0-9, -; 3-32 characters; no dash at
either end; no case folding (the wallet lowercases what is typed and drops
a leading @). MsgBindHandle: holding a handle, the same handle renews it
(lease now + handle_lease_seconds, the address may change), another changes
to it (the old one is freed at once); holding none, a claim; handle and
address both empty: release at once. The address is canonical lowercase
`erthz1…`. MsgMoveHandle hands a live handle to the successor identity's
`new_nullifier = H(TAG_SN, new_id_secret, Scope("handle"))` with a move
proof (§9; genesis 01298d6b): only along the chain's succession leaf from
the holder's identity to the same passport's next one, while that successor
is live. A failed proof is error 1129 (invalid move proof). Without a move,
after a switch the old identity's handle and split persist, unchangeable,
until their leases end, and the new identity claims or casts under the
predecessor bound. Lifecycle: live
until expires_at; then until renewal_until (= expires_at +
handle_renewal_seconds, param 26, default 30 days) reserved to its owner and
not resolving; then free. handle_lease_seconds is param 27 (default 365
days). Errors (codespace personhood): 1121 (not a live handle, as a
registration's referrer), 1122 (taken), 1125 (this identity moved its
handle away), 1126 (its caretaker split), 1116 ("renew it before moving
it"). Each directory entry carries `owner` (field 6): the handle-scope
nullifier that holds it, 64 hex digits.

**Caretaker split [chain].** MsgSetCaretaker casts, refreshes or (empty)
clears the split (1-20 options, percent summing to 100); it lapses at
expires_at (the `set_caretaker` event; R = caretaker_vote_seconds, default
365 days) unless cast again. MsgMoveCaretaker hands the live split and its
expiry to the successor's `H(TAG_SN, new_id_secret, Scope("caretaker"))`,
with a move proof in the caretaker scope.

**Lease bounds [chain].** `GET /earth/personhood/v1/lease_bounds` (int64s as
strings): block_time, activation_margin_seconds, handle_lease_seconds (the
longest ever in force), handle_claim_bound, caretaker_lease_seconds (a held
longer lease after a cut included), caretaker_cast_bound,
caretaker_lease_hold_until. **[wallet]** Checked before use: leases in
1..10 years, the margin in 0..10 years, handle_claim_bound = block_time −
handle_lease_seconds − margin and caretaker_cast_bound = block_time −
caretaker_lease_seconds − margin, else refused. Every bound below comes from
here, never from Params.

    L(lease) = max(0, floor_hour(block_time − lease − (activation_margin + 600)))   (saturating)

Strictly below the chain's bound with 600 s of clock margin, rounded to the
hour so it says nothing about when the tx was made. Every wallet names the
same L, fresh registrants (predecessor_at 0) included: they meet it, so a
proof does not tell a fresh identity from an old one.

**Bounds the wallet names [wallet; the chain checks them in its ante,
before the fee bundle is spent].** It refuses locally (`NotYet`, with the
wait) when its own identity does not meet them:

| msg | max_activation | max_predecessor |
| --- | --- | --- |
| MsgClaimAnml | (day − 1) × 86400 | no bound |
| MsgSetCaretaker, non-empty split | no bound | L(caretaker_lease_seconds) if met; else no bound (see below) |
| MsgSetCaretaker, clear (empty split) | no bound | no bound |
| MsgBindHandle claim, renew, change | no bound | L(handle_lease_seconds) if met; else no bound (see below) |
| MsgBindHandle release (needs a held handle) | no bound | no bound |
| MsgMoveHandle, MsgMoveCaretaker | no bound | no bound |
| MsgVoteProposal, MsgVoteRemoval | BallotInputs.max_activation | BallotInputs.max_predecessor (field 7) |
| MsgProposeRemoval | no bound | day × 86400 − 86400 (day: the chain tip's UTC day) |

When the identity does not meet L: if the wallet knows what it holds there
has lapsed (a handle past its expires_at, in its renewal period; a split
past the chain's own expiry) the tx is refused locally (`HandleNotLive`,
`CaretakerLapsed`; iOS `NotYet.lapsed`) with the date it may act; otherwise
(it holds a live one, or cannot tell after a restore) it goes out with no
bound, and the chain refuses a holder of nothing live in its ante at no
cost, which the wallet reports as `NotHeld` with the wait. A claim for day
d opens once activated_at ≤ (d − 1) × 86400, so a fresh registration first
claims on the day after next; day 0 is refused.

**The directory [wallet].** Every lookup reads the whole directory: the
backend's `GET {base}/handles?from_index=&limit=1000` (rows [handle,
address, status, expires_at, renewal_until, owner]; owner optional, 64 hex,
any case accepted; a snapshot at `height`, read from index 0 in aligned
pages until `last_page`, started over (at most 3 times) when `height`
changes between pages), falling back to the chain's `GET
/earth/personhood/v1/handles?start=&limit=1000` from the first page (`next`
must be the page's last handle). There is no per-handle query anywhere
(`Query/Handle` is never used). A directory is refused whole when out of
handle order, a status other than live/renewal/free, a malformed handle, a
row count other than `size`, more than 250,000 rows (also checked against
page 0's `size`), an address longer than 256 characters, or an entry whose
times no lease has (not 0 < expires_at ≤ renewal_until ≤ now + 10 years).
Cached 10 minutes. An entry whose expires_at has passed by the wallet's
clock is treated as in its renewal period (not payable). Lease params are
taken at most 10 years; a set_caretaker expires_at outside (0, now + 10
years] is replaced by the block time + R; reminder and countdown arithmetic
saturates.

**Paying a handle.** Send accepts "@handle" or a bare handle. Before the
confirm the wallet reads a fresh copy (at most 60 s old) and the chain's
own directory (also whole, also at most 60 s old): the handle must be live
in both with the same address, else nothing is paid. The confirm shows
"@handle · erthz1xxxxxxxx…yyyyyyyy". Funding: a private MsgSend to the
address when the shielded balance of the asset covers it, else MsgShield
from the public balance whose note is minted to the handle's address (pc of
its owner_pk, 177-byte v2 ciphertext to its ek_pub; the amount is public,
the recipient is not).

**Squaring the store with the chain [wallet].** After a sync (Home and the
handle screens) every wallet reads the chain's whole directory (not the
indexer's stream) and, unless a handle move is in flight or the read
predates the store's last change: drops a held handle the chain swept
(absent or free) or whose entry names another owner; with none held and
not moved out, adopts the single non-free entry whose `owner` equals its
own `H(TAG_SN, id_secret, Scope("handle"))` (an entry merely naming its
address is never adopted: anyone may bind any address); and refreshes the
held handle's expires_at (`handle_expires_at` for `handle_expires_for`,
from its bind's `handle_bound` event, else the block time + the lease). While
no handle is held, entries naming the wallet's address whose owner is
absent are shown as unverified, with a renew-only bind; entries with
another owner are not shown. Renew (the held handle, reminders, the address
cards) binds only the handle held, or, holding none, the one named; a bind
that would change the held handle is refused locally, and address cards are
hidden while a handle is held.

**Moves and switching identity [wallet].** A switch is the same passport
registered from another wallet on the phone. **A move comes after the
switch:** the move proof needs the succession leaf the switch appends and
the new identity's live leaf, so once the new registration has landed (and
its block's identity root is recorded), the phone proves the move with both
wallets' identity secrets and moves the handle (only a live one;
`HandleNotMovable` otherwise) and the live caretaker split to the new
identity's nullifiers, with state records to both (§6). It must do so while
the new identity is still the passport's live one (before any further
switch).

- **Who proves and pays.** The old identity's wallet builds the move: it
  holds what moves, and a switched registration mints nothing to the new
  wallet, so the fee comes from the old wallet's private ERTH. It syncs
  first, then takes, from its own identity tree (verified against the chain
  at that sync), the new identity's leaf at the index the new wallet's
  registration record names (`Successor`: the new wallet's keys and its
  IdentityRecord) and the succession leaf. A zeroed successor leaf (it
  switched again or lapsed), a leaf that does not match the record, or no
  succession from this identity to that one is refused before anything is
  laid out (`MoveNotPossible`).
- **succession_index.** The chain appends the succession leaf in the same
  tx, right after the new identity leaf, so the wallet looks at the new
  leaf's index + 1 first and searches the rest of its local tree only if it
  is not there. Nothing asked names either leaf.
- **The offer.** Identity in the new wallet looks among this phone's other
  wallets for the one whose idc forms a succession leaf with its own in its
  local tree (at most one: the passport's previous identity), syncs that
  wallet, and offers "Bring your handle @… to this identity" and "Bring your
  Caretaker split to this identity" for what it still holds, with the fee
  shown against that wallet's ERTH. A switch from a wallet whose phrase is
  lost finds nothing to offer: the move proof needs its secret. The switch
  screen itself moves nothing and says so.
- **When to move.** Nothing hurries a move while the new identity stays
  live (until the passport's next switch), and a move landing right after
  the switch links the handle, its owner_pk and the split to the passport's
  public registration record by timing (ORCHARD_DESIGN 6.6). The offer
  suggests waiting a random delay (hours to days; the wallet draws one and
  shows it) and lets the user choose: move now, or be reminded at the
  suggested time. The wallet never sends a move on its own; a move spends a
  fee and only the user starts it.
- **One step only.** A move goes from an identity to its immediate
  successor while that successor is live. After a further switch whatever
  is still on the older identity can never move. The switch screen warns,
  and asks the user to move first, when the current identity's predecessor
  (on this phone) still holds a handle or split. Without moves, the new identity waits until
everything its predecessor could hold has lapsed (lease + 1 day). The mover
records `*_moved_out` and never casts or claims again.

- A move is written to both stores at the moment its hash is known, before
  the broadcast: the mover's as an outgoing pending move (it still holds
  what it moves), the new wallet's as incoming (it holds it, pending). A
  refusal (CheckTx, no connection) undoes both; a tx that failed in its
  block, or that the chain does not know once past its timeout_height, is
  undone by each wallet when it settles its pending moves by hash (every
  sync, and "Check the moves again"); a committed one is applied, and its
  state records settle it too. A failed write to the new wallet's store is
  kept for a retry.
- The UI shows a move as done only once confirmed; a move in doubt is
  shown as pending on the offer, with "Check the moves again".
- The first confirmed move fixes the target wallet (by store id); a move in
  flight holds it only while in flight, and a refused, failed or expired
  one frees it. A target whose identity moved a handle or split away, or
  that holds one of its own, is refused before anything is sent; one with a
  registration is warned about.
- The switch screen requires the new wallet's recovery phrase be backed up;
  the phrase is shown only after a fresh PIN or biometric unlock (counted
  against the unlock backoff), dropped when the screen is paused or left,
  and the backup box can be ticked only once it was shown. It explains that
  a lost wallet's handle and vote cannot be moved, and that a target whose
  identity already moved one away, or holds one, cannot take a move.

## 15. Staking

All staking msgs carry a fee bundle; every stake proof (Delegate, Restake,
Undelegate, Redelegate, LockPosition, UpdatePosition, UnlockPosition,
PositionVote) names Query/DebtTree's current `clear_before` and `root`
(read when the action starts), whether or not it clears a label (a proof
naming them only to clear would be linkable to the
public redelegation into that validator). **[chain]** The chain takes
clear_before within [ClearBefore(now) − 3600, ClearBefore(now)] and the
current root (a slash changing it between the read and the block refuses
the tx at no cost: "try again" re-reads and re-proves). Both are 0 only
while the block time is below the window (no real chain), as the chain then
requires.

**Lane A [wallet].** Every note-moving msg spends at most two notes of the
msg's denom (at most one labelled) and creates exactly one note back (the
merged note, the change, or a zero note on a full exit: amount 0, a real
commitment). With nothing of ours to spend (a first delegation) slot 0 is a
padding input: amount 0, fresh rho and rcm, nullifier H(TAG_SNF, nk, rho,
0), so nf_0 is never zero on a note-moving msg. A position's msg (update,
vote) moves nothing: lane A all zero, asset 0. Anchor: the wallet's
chain-verified stake root (the empty tree's root before the first note).

**Clearing.** A labelled input whose `move_time < clear_before` clears at
amount − exposed + retained (retained = the debt row's, or all of exposed
when absent), the output unlabelled. While the window is open the labelled
part cannot leave: undelegate, lock and move take only amount − exposed, and
the wallet refuses more up front with "moved stake can move again after
<date>" (date = move_time + window, UTC). Once clearable, the haircut
(exposed − retained) is shown on the confirm sheet and sent as shown (a
larger one by the time of sending is refused as QuoteChanged, before
proving).

**Debt tree reads.** Only when a label is cleared or voted, and whole: the
indexer's `{base}/debt_rows?from_index=` (aligned pages of 1000 from leaf 0,
rows [index, key hex, retained, height, updated_height], `size`, `root`;
indexes contiguous from 1), else Query/DebtTree pages
(`/earth/shieldedstaking/v1/debt_tree?start=&limit=`, at most 1000), built in
insertion order with the latest retained and checked against the LCD's
root (a mismatch drops the indexer's rows and rebuilds from the chain). The
last two trees are kept by root.

**Validator list.** Every staking quote, picker, stake value and validator
name comes from x/shieldedstaking `Query/Validators`
(`/earth/shieldedstaking/v1/validators?pagination.limit=200&pagination.key=`),
read whole: the first page's `height` (int64 as a string) fixes the
height, every later page is asked with `x-cosmos-block-height: <height>`
and must answer that same `height`; a page from another height, or one the
node cannot serve at it, starts the read over (at most 4 reads, then an
error, never a mix of two states). An entry: `validator`; `staking`
(x/staking's Validator: `operator_address` "" for a book whose validator
x/staking removed, which comes on the last page; `status`, `jailed`,
`tokens`, `description.moniker`, `commission.commission_rates.rate`);
`tombstoned`; `delegatable` and `refusal`; `book` (`pending_delegation` P,
`pending_undelegation` U, …); `backing` B, `supply` S, `rate`;
`delegation` D, `rewards` W; `redelegations` [{`dst_validator`, `entries`,
`counted_entries`}]. Integers are decimal strings of at most 80 digits; a
duplicate validator, a looping next_key, an entry whose
`staking.operator_address` is another's or more than 10,000 entries refuse
the read. The list is read again on every sync and every quote (a quote
reads it when the user reviews it), kept in memory for pickers and names;
the stake pickers offer bonded validators with `delegatable` true. The
wallet never asks about one validator (Query/Validator, x/staking's
`validators/{addr}`, `Query/Redelegation`): asked just before a public msg,
that ties the asking IP to the intent.

**Delegate.** MsgDelegate merges `amount` uerth (released by the bundle)
into the validator's note (v_in = derth) and names `derth`, the derth the
chain credits:

    derth = floor(value × S / B) − ceil(floor(value × S / B) × 10 / 1e6)     (S = 0: value, B = 0 required)

from the list's backing B and supply S, read when the user reviews (the
confirm sheet shows the derth). A validator whose entry is not
`delegatable` is refused up front with its `refusal`. Both amount and derth
must be at least min_delegation. A rate that moved past the margin is
refused in the ante (1103, no cost).

**Restake.** MsgRestake merges two notes of a validator into one, only on
the user's tap (Earn "N notes · tap to merge"; offered when at most one of
the two is labelled).

**Undelegate.** The confirm sheet shows the value floor(amount × B / S)
from the list, read when the user reviews. The stake proof spends `amount`
derth (v_out = amount, change back). `pc` is a fresh pool note opening of the wallet's own and
`ciphertext` its 177-byte v2 ciphertext to its own address. At maturity the
chain mints `value × payout / requested` uerth to pc in the EndBlocker (at
most 2^63 − 1 a note: a larger payout is several `shielded_mint` rows with
the same ciphertext, each its own position and amount); sync finds every
one by trial decryption. Nothing is sent to claim it. The
`shieldedstaking_undelegate` event carries validator, derth, value, epoch
and payout_id.

**Pending undelegations [wallet, local only].** The wallet records each
undelegation (`pending_unbonds`: tx_hash, validator, derth, pc, started_at,
until = timeout_height, confirmed, epoch, value, payout_id, due_by) when the
node takes the tx, fills epoch, value and payout_id from its committed
event, and drops it when a synced note of its own carries that pc (paid),
or when the tx was refused, failed in its block or is missing past its
timeout_height. `due_by` is computed once, at confirmation, from chain-wide
timing alone (the current epoch's start and end, epoch_seconds, x/staking
unbonding_time): an epoch e not ended yet ends (e − current) epochs after
the current one's end (at least its start + epoch_seconds); an ended one at
start(current) − (current − 1 − e) × epoch_seconds; then + unbonding_time +
15 minutes; no answer on overflow. The wallet shows "Unstaking (private),
arrives by about <due_by>" until paid. The record survives a same-chain
reset; a restored wallet has none (the payout is still found). The chain's
per-id `Query/UnbondPayout` is never asked: the id is on the undelegate
tx's event, so a query for it ties the asking IP to that undelegation.

**Move stake (MsgRedelegate).** Lane A spends `amount` derth of src (free
value only) with the change back. The credit lane merges what arrives into
the wallet's largest unlabelled note at dst (cr_in; none: a padding input,
so a second note beside a labelled one) and creates a labelled note:
move_key = the credit nullifier (cr_nf), move_time = the LCD's latest block
time (the chain takes it within 600 s before its block, 1120 otherwise, no
cost), exposed = cr_v_in = dst_derth. Quote, from one read of the list: dst
must be `delegatable` (else refused up front with its `refusal`); u =
floor(amount × B_src / S_src) (at least min_delegation); what arrives is u
when the value leaves src's queue first (src's `staking.status`
BOND_STATUS_UNBONDED, or removed, or its bonded part D − U ≤ 0) and the
queue P + W covers u (the move withdraws W into it first), else u − 1,001
(the chain splits u pro rata between the queue and the bonded stake; a
bonded part of at most 1,000 stays, and x/staking may truncate a uerth);
dst_derth = the delegation formula on what arrives at dst's book (at least
min_delegation). Gas is simulated, the limit the simulation + 10% (§11)
plus, when src's `redelegations` entry for dst has `counted_entries` < 1,024
≤ `counted_entries` + 51 (the pair may reach the cap within the tx's
timeout), the merge it would then pay: (`entries` (at most 1,024) + 51) ×
2,500 + 128 × 20,000. The fee cap allows the pair's x/staking record at
its worst (§11). The confirm sheet shows the derth that arrives,
the window and any haircut. The `shieldedstaking_redelegate` event carries
credited, move_key, move_time; the wallet never queries a move
(Query/Redelegation and Query/Move are never asked).

**Positions (Groundworks).** MsgLockPosition: lane A releases `amount` with
a new owner-tag counter's salt (§7), refused up front (before a counter is
taken) when open-window stake would have to leave. MsgUpdatePosition and
MsgPositionVote: lane A all zero, the position's salt. MsgUnlockPosition:
lane A merges the position's derth (v_in) into the wallet's note at its
validator (or pads), the position's salt, and the fee bundle carries the
unlock record (§6). The chain weighs positions per validator. Positions are
read from `/earth/shieldedstaking/v1/positions`, the whole listing paged by
its own key (never `Query/Position` for an id: that would tell the node
which positions are whose); the wallet picks its own by owner tag. One votes
on a proposal only if created before the snapshot's block.

**Split leases (Groundworks).** A position's split counts until
`split_expires_at` (Position field 11, unix seconds): cast or renewed
(MsgLockPosition, MsgUpdatePosition) + x/allocation
`groundworks_lease_seconds` (0 = 365 days). MsgUpdatePosition with the same
split renews it. At the lease end the chain clears the split (`splits`
empty, `split_expires_at` 0; a `shieldedstaking_position` event with action
`split_lapsed`) and the position directs nothing until a split is cast
again. The wallet reads `split_expires_at` from that same whole listing and
keeps, per position of its own, the last split and lease end it saw
(Android state.json `position_leases`: `[{id, expires_at, split}]`; iOS
`positionLeases`), clamped to now + 10 years, kept through a same-chain
reset, dropped when the position is gone. A position is **lapsed** when its
split is cleared, or held past its lease end (the chain clears it in the
next block); the card then reads "Lapsed — choose a split again" and the
split sheet opens on the last split seen, removed options left out. From 30
days before the lease end the card offers **Renew**: MsgUpdatePosition with
the held split, on the user's confirmation; a split naming an option the
fund no longer has is not offered for renewal (the chain would refuse it).

**Stake votes (MsgStakeVote, no stake proof).** One msg votes up to two
eligible derth notes of one validator with one weight; nothing is spent or
re-minted, so a note votes on every concurrently open proposal.

1. **Snapshot** from the LCD `Query/Snapshot`
   (`/earth/shieldedstaking/v1/snapshots/{proposal_id}`), never the indexer
   (the proposal id is public, so asking names nothing of the wallet): root,
   tree_size, height, nf_root, nf_size (the nullifier tree's leaf count,
   sentinel included; 0 = nothing inserted), and the validators' rates (the
   weight shown uses them). A snapshot without nf_root takes no stake vote.
   Cached per proposal, dropped with every other per-chain cache when the
   store's genesis changes. A snapshot past the local stake tree is "sync
   first".
2. **Note paths** in the wallet's stake tree of the first tree_size leaves;
   that tree's root must be the snapshot root. A note at position ≥
   tree_size cannot vote. Spent notes' openings are kept (never pruned), so
   a note spent after a snapshot still votes on it; its outputs cannot.
3. **Nullifier tree.** The first nf_size − 1 stake nullifiers in insertion
   order (nf_size the LCD's, so the fetch is bounded by the chain's own
   count), from `{base}/stake/nullifier-tree?from_index=&limit=1000` in
   aligned pages (page k holds leaf indexes [1000k, 1000(k+1)); leaf 0, the
   sentinel, is never a row; rows [index, nullifier, height], indexes
   contiguous; a leaf already held must be served identically; more than
   1000 rows is inconsistent), with the LCD
   `Query/StakeNullifierTree{start, limit}` (1000 a page) for whatever the
   indexer lacks. Its root must equal nf_root; otherwise everything fetched
   is dropped and the tree is rebuilt from the LCD alone once, else the vote
   is refused. The values are kept in memory and the last two trees by
   nf_root.
4. **Low leaf** of each note's spend nullifier: the predecessor (the
   sentinel if none) with next = the successor (0, 0 if none). A nullifier
   in the tree means the note was spent before the snapshot: skipped, but
   only when sync, too, saw the spend at or before the snapshot's height
   (a spend in the snapshot's own block is before it); otherwise the cast
   fails with an error (never a vote silently skipped).
5. **Notes.** Eligible: derth, amount > 0, position < tree_size, not spent
   at or before the snapshot's height as far as sync knows, not already
   voted on the proposal. Taken largest first, then by position, two a
   msg; a note slashed to nothing is skipped. A labelled note votes at
   amount − exposed + retained under the current debt root.
6. **vote_nullifiers** is exactly two, non-zero and distinct, in the
   proof's slot order: each note's H(TAG_VNF, nk, rho, pos, proposal_id)
   and, for a one-note vote, a padding nullifier H(TAG_VPAD, nk, r,
   proposal_id) with r a fresh CSPRNG field element (never reused). The
   padding goes in a random slot (`VoteLayout.random`). Only the notes'
   vnfs are remembered as voted; the chain records both.
   `debt_root` (11) is the current root on every vote.
7. **Weight [wallet].** RoundVoteWeight of the sum of the notes' values
   (the sum saturates at 2^63 − 1): rounded DOWN to three significant
   decimal digits, whole below 1000:

       unit = 1; while amount / unit >= 1000: unit *= 10
       weight = amount / unit * unit

   999 → 999; 1,000 → 1,000; 1,009 → 1,000; 123,456 → 123,000; 999,999 →
   999,000; 1,234,567 → 1,230,000; 123,456,789 → 123,000,000; 399,999,999
   → 399,000,000; 2^63 − 1 →
   9,220,000,000,000,000,000 (vectors `round_vote_weight`). The published
   weight names a bucket, not the exact amount, and gives up less than 1 %
   of the notes' voice. The sheet shows the weight at the snapshot rate.
8. **Prove** circuits/vote (§9); a quote simulates with random vote
   nullifiers in both slots. Gas (estimate, fee cap): 250,000 + proof
   (2,000,000) + (1 + 2) × note_gas (150,000), the same for every vote.
9. **Remember** each used slot's (proposal, vnf) the moment the node
   accepts the tx (`stake_votes`: proposal_id, vnf, tx_hash, until =
   timeout_height, confirmed); confirmed once committed. A vote that failed
   in its block, or is unknown once the chain is past its timeout_height,
   is forgotten and the note may vote again. The records survive a
   same-chain reset.
10. **Already voted (1119, codespace `shieldedstaking`).** The chain refuses
    the whole msg if any used vnf was already used on the proposal and
    names it ("vote nullifier <HEX>"; at simulate, its registered text "this
    stake note already voted on this proposal"). Refused before any mempool
    (simulate, CheckTx): the named note is recorded as voted and the vote
    laid out again without it, within the same confirmed action (nothing
    was paid; at most two re-layouts). Refused in a block: only the named
    note's record becomes final, the msg's other notes are forgotten and
    vote again. This is how a wallet restored from the mnemonic learns its
    votes.

**Casting [wallet].** The proposal screen shows one confirm sheet per
validator and per position, in order, the next raised only after the last
tx went through and each sent only on its own tap. A validator with more
eligible notes than one vote holds first asks: "Vote in parts" (one sheet
per part; each part publishes its own weight and the parts can be linked by
validator and timing) or "Merge notes" (one MsgRestake sheet). A merge
after a proposal's snapshot does not change that proposal's vote (the
merged note is not under its root, and the spent ones still vote); the
sheet says so.

**Stake note discovery.** The stake rows stream carries no denom: a stake
ciphertext names only the asset id, so the wallet names `derth/<valoper>`
by AssetID over the validator list (every status, and books whose
validator x/staking removed; the list the sync read, each candidate
learned only as the hash of its own name). A stake
note of an asset it cannot name is not used.

## 16. Assembly and dex

**Assembly.** MsgVoteProposal and MsgVoteRemoval take their statement from
`/earth/assembly/v1/ballot_inputs?proposal_id=` / `?option_id=` (scope,
excluded_dsc, excluded_country, max_activation, max_predecessor (7), round,
ballot_id); the wallet recomputes the scope and refuses a node's that
differs. An expedited proposal
the chamber ratified and x/gov demoted votes again in round 1, a new
nullifier scope (vectors `proposal_5_0`, `proposal_5_1`).

**Dex [chain maths, wallet mirrors].**

- **Swap fee.** LegacyDec(amount) × fee / 100 rounded half-even at 18
  places, then **up** to an integer; quotes and min-out use it
  (`dex_amm.json`; iOS corecheck re-derives from x/dex).
- **Deposits.** x/dex pulls each leg rounded up, ceil(shares × R / S) with
  shares = min(⌊in_e × S / R_e⌋, ⌊in_t × S / R_t⌋); the wallet derives the
  other leg of a deposit as ceil(amount × R_other / R_typed), so the typed
  side is the binding one and at most one unit comes back as a refund.
  min_shares is the shares at the current reserves less 1 % (`dex_amm.json`
  `deposits`). ErrPoolCap (dex 1120, past 2^120) is explained.
- **Public add-liquidity.** MsgAddLiquidity carries `min_shares` (5) =
  min(⌊e·S/R_e⌋, ⌊t·S/R_t⌋) less 1 % from fresh pool and share supply
  reads; "" only for an empty pool; a read that fails refuses the deposit.
  Golden (both platforms): creator "earth1creator", pool 2, 1000uerth,
  300uusd, min_shares "148" =
  `0a0d65617274683163726561746f7210021a0d0a057565727468120431303030220b0a047575736412033330302a03313438`.
- **Private deposits** (MsgAddLiquidityShielded): one bundle releases the
  token leg and erth_amount + fee; LP shares are minted as a `dexlp/<pool>`
  note to a pc of ours; what the ratio does not take is minted back to one
  refund pc (a note per asset, both opened by the one v2 refund
  ciphertext).
- **Withdrawals** (MsgRemoveLiquidityShielded; the public MsgRemoveLiquidity
  names a note for its ANML leg): escrowed for the LP unbonding period,
  then both legs minted to pcs of ours (v2). A leg above 2^63 − 1 is paid as
  ceil(v / (2^63 − 1)) notes (MintNoteSplit, at most 128) sharing one pc
  and ciphertext (§5). x/dex refuses at start a leg above 32 × (2^63 − 1)
  (dex 1101, "the most one withdrawal pays as notes"), and every client
  refuses the same bound, floor(shares × reserve / total) at the current
  reserves, before proving.
- **Note swaps** (MsgNoteSwap) through the ERTH hub; the output is minted
  to us (or to another address) with a v2 ciphertext.

## 17. Indexer

**[backend; wallet rules]** (backend README "URL scheme for wallets").

1. `GET /privacy/status` → `chain_id`, `genesis` (16 hex), `base`
   (`/privacy/<chain_id>/<genesis>`, null until the indexer met its
   chain), `halted` (non-null: the indexer stopped; the wallet refuses to
   sync). A non-null `base` is accepted only if it is byte for byte
   `/privacy/` + chain_id + `/` + genesis, with chain_id the wallet's own
   (`[A-Za-z0-9][A-Za-z0-9._-]{0,63}`) and genesis `[0-9a-f]{16}`, both as
   the same status names them; anything else (a host, `//`, `@`, a scheme,
   `..`, a query, another chain) is refused before any stream request, and
   every request URL keeps the indexer's own scheme, host and port. A
   status whose `chain_id` is null or another chain's is refused.
2. The store records (chain_id, genesis). If the status names another
   genesis for the same chain id (a relaunch), the wallet first asks the
   LCD: `GET /cosmos/base/tendermint/v1beta1/node_info`
   (`default_node_info.network` must be the chain id) and `GET
   /cosmos/base/tendermint/v1beta1/blocks/1` (the first 16 lowercase hex
   digits of `block_id.hash` must be the status's genesis). Only a
   confirmed switch wipes the local trees, notes, cursors, records and the
   old chain's bookkeeping (claimed days, caretaker split, handle); it
   keeps the identity record (with its passport nullifier), the pending
   registration and the owner-tag counters, and the identity's leaf is
   re-verified against the resynced tree (shown as not live if it does not
   match; the record is never dropped). An unconfirmed switch (the LCD says
   otherwise, or cannot say) wipes nothing, syncs nothing and is shown as
   unverified. A first sync goes ahead when the LCD cannot say, never when
   it contradicts the status. A store with no genesis recorded keeps its
   identity record when the genesis is first recorded.
3. Streams the wallet reads, under `base`:

       GET {base}/notes?from_pos=&limit=               format 2 (below)
       GET {base}/nullifiers?from_height=&limit=       blocks [[height, [nf, …]], …]
       GET {base}/identity?from_index=&limit=          leaves [index, height, leaf, zeroed_height, time?]; size
       GET {base}/identity/zeroed?from_height=&limit=  blocks [[height, [index, …]], …]
       GET {base}/roots/latest                         {note, identity, stake: {root, tree_size, height, time}}, synced_height
       GET {base}/stake/notes?from_pos=&limit=         notes [position, height, cm, ciphertext]
       GET {base}/stake/nullifiers?from_height=&limit= blocks [[height, [nf, …]], …]
       GET {base}/stake/nullifier-tree?from_index=&limit=  nullifiers [index, nullifier, height]; size, next_index
       GET {base}/debt_rows?from_index=&limit=         rows [index, key, retained, height, updated_height]; size, root
       GET {base}/handles?from_index=&limit=           handles [handle, address, status, expires_at, renewal_until, owner]; height, size, last_page

   Position and height pages carry `next_pos` / `next_index` /
   `next_height`, `complete` and `synced_height`.

   **Notes format 2** (backend README "Note stream format 2"). Every
   `/notes` page has `"format": 2` and `"fields": ["position", "height",
   "cm", "ciphertext", "amount", "owner_pk", "rho", "rcm"]`; the wallet
   reads columns by name and refuses a page of any other format or one
   missing a column. Three kinds of row: a bundle output (`amount` null,
   `ciphertext` set); a minted or shielded note (`amount` and `ciphertext`
   set); an open note (`ciphertext` null; `amount`, `owner_pk`, `rho`, `rcm`
   set, hex). A row with part of an opening, an opening beside a
   ciphertext, or an open row without an amount is refused (the page is
   inconsistent). Matching is local over the whole stream; no request ever
   names an owner_pk. A public amount is `<digits><denom>` with the denom
   matching the SDK rule `[a-zA-Z][a-zA-Z0-9/:._-]{2,127}` and never
   starting `asset/`; anything else and the row is not opened against it.

4. **Transport.** A 404 means the base moved: re-read the status and retry
   once. Response bodies are capped (8 MiB decompressed) and nested at most
   64 arrays/objects deep, checked before parsing; a redirect is never
   followed (a 3xx is an error), for the LCD as for the indexer. A 503 (the
   indexer's in-flight cap) or 429 is retried after max(Retry-After,
   2^(attempt − 1) s), at most 30 s, four times, then the sync fails.
5. **Paging rule.** `limit` is 100 or 1000 (the wallet always asks 1000); a
   position or index cursor (`notes`, `identity`, `stake/notes`,
   `stake/nullifier-tree`, `debt_rows`, `handles`) is a multiple of the
   limit and page k is exactly [k·limit, (k+1)·limit). The wallet asks for
   the page holding its cursor, `from = next − next % limit`, and drops the
   rows it holds (a held note or stake note row must be the leaf held, else
   inconsistent); a full page is followed by the next, a short one is the
   tip. A position page carries at most `limit` rows, must name `next_pos` =
   from + rows, and one marked complete must carry rows. A height page
   (nullifiers, identity/zeroed, stake/nullifiers) keeps a free
   `from_height`, never splits a block (so it may exceed the limit by one
   block; at most 5000 rows), never names a `next_height` below its
   `from_height` (nor equal to it when complete), and holds no earlier
   height. Anything else is inconsistent (the wallet starts over once, then
   stops). One sync, its retries included, gives up after 10 minutes.
6. **Heights bounded by the chain.** Every height an indexer page names (a
   row's height or zeroed_height, `synced_height`, `next_height` − 1, every
   `/roots/latest` height) must be at most the LCD's latest height + 10
   (read again once when exceeded); past it the page is inconsistent and
   nothing from it is kept. An identity row's `time` is a block time of
   this chain or the page is inconsistent: at least 1,735,689,600
   (2025-01-01) and at most the LCD tip's block time + 3600 s.

## 18. Verification against the chain

**What is trusted.** The operator runs both the indexer (api.erth.network)
and the LCD (lcd.erth.network); the wallet has no light client and does not
check consensus signatures, so the LCD is trusted for chain state. The
checks below make sure a compromised or broken *indexer* alone cannot make
the wallet build on, or show as verified, trees the chain does not have; an
operator controlling both could still lie consistently (it could not forge
spends: every proof is checked by the validators).

**Who sees the wallet's requests.** The wallet's chain reads include its own
transparent address (balances, a validator operator's self-bond, its tx
search), and its private txs are simulated and broadcast through the same
node, from the same IP, in the same session. Whoever runs that node, or
holds its access logs, could tie the two. The defence is operational, not in
the request pattern: Earth's node keeps no request logs, and Settings →
Network points an install at the user's own node (`NodeConfig.kt` /
`NodeSettings.swift`), through which every chain query and broadcast then
goes. The backend (indexer, handle directory, gas grant, fetched circuits)
is not a node and stays at api.erth.network; it never learns the
transparent address (the indexer streams are whole-chain, the gas grant
body carries neither address).

**Sync generations.** Before a sync's first request the wallet bumps its
sync generation, clears "verified" and persists both; only the root checks
at the end of that same sync mark that generation verified. Every private
tx needs the latest generation verified.

After each sync the wallet checks every local root against the LCD:

- **Note tree:** `GET /earth/shielded/v1/roots/{root hex}`. A record whose
  `tree_size` differs from the local size is a mismatch. No record, or
  `valid` false, is **unverified, not a mismatch** (x/shielded prunes roots
  after its window, 14 days). The record's `height` must be the indexer's
  `roots/latest.note.height`, else unverified.
- **Identity tree:** `GET /earth/personhood/v1/identity_tree` at height H
  (header `x-cosmos-block-height`, H = the indexer's
  `roots/latest.identity.height`) must give `size` = local size and
  `latest_root` = local root.
- **Stake tree:** `GET /earth/shieldedstaking/v1/stake_tree` at the
  indexer's stake root height likewise (an empty tree: size 0, root empty).
- **Pinned heights.** A tree read counts as pinned only if the response's
  `x-cosmos-block-height` echoes exactly H. A pinned read that differs is a
  mismatch. If H is unavailable or another height is echoed, the latest
  state is read instead (marked unpinned); an unpinned read verifies equal
  trees and otherwise leaves the roots unverified, never a mismatch.
- **The indexer's claimed height.** `GET /earth/shielded/v1/tree` at the
  indexer's `roots/latest.synced_height` (pinned) must hold exactly the
  local note tree's size; unverified otherwise; an unpinned read is not used.
- **Nullifier sample.** Up to 4 pool and 4 stake nullifiers, drawn
  uniformly (reservoir) from everything the nullifier streams delivered in
  this sync, are asked of `GET /earth/shielded/v1/nullifiers/{hex}` and
  `GET /earth/shieldedstaking/v1/stake_nullifiers/{hex}`; one the chain says
  is not spent leaves the roots unverified. The wallet's own nullifiers are
  never in the sample.
- **Indexer behind the tip.** If the LCD's latest block
  (`/cosmos/base/tendermint/v1beta1/blocks/latest`) is more than 30 blocks
  past the indexer's synced height, the roots are unverified ("the indexer
  is N blocks behind"); an LCD that cannot say its height leaves them
  unverified.

A local tree larger than the indexer's latest, or one of the same size with
another root, is inconsistent: the wallet starts over from an empty store.
A local tree that differs from the indexer's latest is resynced once; a
mismatch wipes the synced data and is shown. Unverified roots block every
private tx and are shown next to the private balances, stake and
registration until a later sync verifies them. `verified_height` (the
indexer height of the last verified sync) is kept across resets.

Untrusted numbers from the LCD or indexer (tree sizes, params, durations,
epochs, a snapshot ahead of the local tree) are parsed bounded and refused,
never trapped on or wrapped.

## 19. Sync and restore

**One note-discovery rule.** A pool note row is ours iff: a 217-byte
ciphertext opens as v1 (cm-bound); a 177-byte one opens as v2 against the
row's public amount (rows without an amount are skipped); or it is an open
note with our owner_pk whose opening recomputes its cm. A stake note row is
ours iff its 201-byte ciphertext opens as the wallet stake note v2.
Value-0 notes are read for their memo record (§6) and dropped; a zero stake
note is dropped. Nothing else: a restore from the mnemonic alone finds
every note. A row that cannot be opened is skipped, never thrown on; a
page's rows are opened before the tree grows. Nullifiers are read up to the
height the notes reached; identity leaves are synced no higher than the
notes.

**Denoms.** A denom is learned (asset id → denom, for v1 and stake
ciphertexts) only from a note of this wallet's whose cm it reproduces, or
from the chain's asset list (`GET /earth/shielded/v1/assets`, every page, at
most 4,096 entries, each learned only if its `asset_id` is
`AssetID(denom)`), read at most once a sync and only when a note of ours
carries an id the wallet cannot resolve. The persisted `denoms` are the
denoms of the notes held (at most 4,096). A held note named `asset/<hex>`
whose id becomes known is renamed (same asset, same cm).

**State records.** Applied in note order: the newest record of each kind
sets the store's handle (or none, or moved out) and split (with its expiry,
at most now + 10 years; a split the wallet already holds keeps the chain's
own later expiry), unless a newer one was already applied (a reset keeps
that cursor, so a resync never rolls back what the wallet did since) or the
record's height is one where the wallet saw its own tx fail in its block. A
record lands with its fee bundle, so one whose msg then fails in its block
still lands; the wallet voids it when it sees the failure, and otherwise the
chain refuses what follows from it at no cost. A HOLDS record settles an
incoming move, a MOVED_OUT one an outgoing move.

**Restore of the registration.** No query names it. Every tagged
registration record found keeps, as the identity stream passes them, the
identity leaves appended at its block height h (at most 64). Records are
matched only after the same sync's root checks verified the identity tree
(an unverified sync keeps the leaves for a later one). Then, once a sync,
newest record first, stopping at the newest that matched, each candidate
time is tried with every country (the hint, unknown, then every A..Z pair:
677 countries) and both predecessor_at candidates (0, or the time itself):
at most 1,354 hashes a leaf.

1. **Block time from the indexer.** activated_at is exactly the
   registration block's time; the identity rows' optional fifth column
   `time` gives it. The LCD is never asked about the registration's block
   alone.
2. **Block time from the LCD, with a cover set.** When the rows carry no
   time (or it did not match), the LCD is asked for 16 block times (`GET
   /cosmos/base/tendermint/v1beta1/blocks/{h}`, `block.header.time`, the
   header's height must be h): h and 15 other heights, in a shuffled order.
   The decoys are drawn first from a persisted uniform sample (256) of the
   identity rows' heights, then uniformly from [1, min(the synced height,
   the LCD's tip)]; never a height past the tip. The set is chosen once and
   persisted with the record (a retry asks the same set; at most 3 fetches;
   once answered, never again). Known and unmatched, the record is given up
   (EXHAUSTED). Residual: an LCD that also runs the indexer sees 16
   registration blocks asked together.
3. **Fallback (no block time at all).** built_at is searched outward (0,
   +1, −1, +2, …), the hint and unknown over [built_at − 3600, built_at +
   86400], then every other country over [built_at − 600, built_at + 3600].
   The search is resumable and bounded: the cursor and the hashes spent are
   persisted with the record, each sync spends at most 50,000 leaf hashes
   over all records, and a record that spent 8,000,000 is given up. A
   device clock off by more than the windows is only found by steps 1-2.

Every exact time tried is recorded; a time not tried before is still tried
after the record was given up, more leaves at its height reopen it, and a
store reset finds every record afresh. Times and sums are checked (no wrap,
no trap); a candidate outside the block-time range is skipped. A match gives
leaf_index, dsc_key, country, activated_at and predecessor_at: the identity
record (passport nullifier left empty; nothing needs it), marked
`verified`. A same-chain reset keeps only a verified identity record; a
match at the identity's own index replaces it. A registration whose record
note is missing (an older app) cannot be restored and must register again
(a switch to the same passport is allowed; a switch to the same idc is
refused by the chain only while the old leaf is live).

## 20. Wallet behaviour

- **Nothing unasked.** The wallet broadcasts only a tx the user confirmed on
  its sheet: no automation, no background run, no follow-up tx added to a
  confirmed one (a stake that needs merging first is refused; a merge is
  its own sheet). Sync, the reminders and the payout bookkeeping send
  nothing. A chain of sheets (a switch's handle and caretaker moves, a
  stake vote's validators and positions) raises the next sheet only after
  the last tx went through, and each is sent only on its own tap.
- **Reminders** (Home banners and the Handle screen): "ANML ready to claim"
  when today's claim is open and not made; the caretaker vote from 30 days
  before its expires_at until 30 days after; the handle from 30 days before
  expires_at through its renewal period; a handle naming this wallet's
  address that it does not hold; each Groundworks position's split from 30
  days before its lease end until 30 days after (§15), whether or not the
  identity is live, and never while the lease end is unknown (a lapse the
  wallet never saw). The caretaker and Groundworks banners open their Govern
  screen (Groundworks on Positions); Renew there is one tap and its confirm
  sheet. All reminder arithmetic saturates.
- **Saved state.** state.json is written to a temp file, fsynced and renamed
  over (iOS: atomic write); a failed save is an error, never silent. An
  unreadable state.json is an error shown to the user, never replaced by an
  empty wallet. iOS marks the privacy directory excluded from backup.
- **State at rest.** state.json is sealed (`StateSeal`, both platforms, one
  format): `{"sealed": 1, "alg": "AES-256-GCM", "kid", "nonce", "ct"}`, all
  hex, `ct` the ciphertext with its 16-byte tag appended, AAD
  `"earth/privacy-state/v1|" + walletId`, `kid` the first 8 bytes of
  SHA-256(`"earth/privacy-store/kid"` ‖ key). The key is a random 32-byte
  data key kept inside the sealed wallet vault (Android: the storage JSON's
  `data_key`; iOS: the vault payload's `dataKey`), so it opens exactly when
  the wallet does, whatever the unlock method, and survives a change of
  method. A state sealed under another `kid` (an earlier install's vault) is
  dropped, trees included, and resynced from the mnemonic; a failed tag
  under the current key is an error. Plaintext from before sealing is read
  once and sealed. The trees (the chain's public leaves) are not sealed.
- **Vault key derivation.** The wallet vault records how its key was
  derived (Android format 2: `kdf` = `pbkdf2-hmac-sha256`, `kdf_iterations`
  600,000; iOS format 2: `kdf` = `pbkdf2-hmac-sha512`, `rounds` 200,000).
  A vault without them is format 1, sealed at those same values; counts
  outside [100,000, 10,000,000] and newer formats are refused; a vault at
  other than the current parameters is re-sealed at them on unlock.
- **One store per wallet.** The app keeps one wallet object and one store
  per wallet per process, across lock and unlock.
- **Forgetting a wallet** deletes its `privacy/<id>/` directory (notes,
  identity, records, trees): every file overwritten with zeros, synced, then
  unlinked. Android offers it as Settings → "Forget private data"; iOS on
  forgetting the wallet.
- **PIN change (Android)** needs a fresh unlock with the current secret
  (counted against the unlock backoff).
- **Logs.** Proof timings are logged in debug builds only.
- **Chain errors** are explained in plain words (`ChainErrors`, matched by
  codespace and code), among them the no-cost ante refusals:
  shieldedstaking 1103 (a delegation or move quote the rate outran), 1120
  (a move_time too old), 1113 (a stale clear_before), shielded 1103 ("pick a
  newer anchor"), and the predecessor bound (§14).

## 21. Off-device parity

`WalletFlowTest` / `WalletFlowTests` drive two wallets against an in-memory
chain (FakeChain: real binding-signature checks, the stake and debt trees,
the fee, ciphertext, canonical-bytes, gas-limit (≤ 5× used) and
send-disabled rules, handles, leases, moves, predecessors). With
`PRIVACY_TOML_OUT=<dir>` every witness is written as Prover.toml, and
`nargo execute` (1.0.0-beta.22) on circuits/action, stake, membership and
vote accepts all of them. ProverGate
(`PRIVACY_TOML_DIR`) proves and verifies every stake and vote witness of both
platforms with VKs equal to the chain's genesis keys. Suites: `StakeVoteFlowTest`
(concurrent proposals, refusals, restored wallets learning votes, the
snapshot nullifier tree, the weight rule), `StakeTest` / `StakeNoteTests`
(stake note v2, moves, debt tree, clear_before), `HandlesTest`,
`LeaseBoundsTest`, `NoteDiscoveryTest`, `UnstakeTest`, `SyncTest`,
`RestoreTest`, `WalletFlowTest` and their iOS counterparts.
