# Privacy formats the wallet defines

The privacy chain (x/shielded, x/personhood, x/assembly, x/shieldedstaking,
x/dex) pins every hash, tag, sighash, bundle and proof layout in `zk/privacy`
and `zk/orchard` (Orchard-style bundles: ORCHARD_DESIGN.md sections 12-14 of
the chain), and the Android code reproduces those byte for byte
(`android/.../privacy/zk`, `privacy/tx`, tested against vectors generated from
the chain by `android/tools/orchardvectors/gen.sh <chain checkout> [ref]`,
last run against chain privacy/orchard **203d3b2**, regenerated
byte-identically at **c0ad1dd** by audit 6; `tools/privacyvectors` is
the retired transfer-circuit generator, whose `dexamm_test.go.in` still
writes the dex vectors).

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

**Changes for the clients re-audit (2026-10-02), summary.** The record
note is version 2 with an nk tag (§3a; version 1 ignored), restored by the
registration block's chain time with a bounded, persisted fallback search;
an unlock's re-minted stake note names the closed owner-tag counter (§1);
amounts are bounded to 2^63 − 1 (§3); the pending registration is recorded
at broadcast acceptance (§3a); a genesis switch is confirmed by the LCD and
keeps the registration (§4a); the indexer base is validated (§4a); root
checks distinguish unverified from mismatch, pin heights by the echoed
header, sample nullifiers and flag an indexer behind (§4b); stake votes
are cast through one spaced, resumable path (§4); the chain's round-2 tx
rules (canonical bytes, ciphertext slots, one use per binding) are
followed (§4). Android and iOS implement all of it identically; the record
memo golden is pinned on both.

**Handles, predecessor-aware activation and no automatic fees (chain
4a663d5, clients round 5), summary.** The identity leaf commits to
`predecessor_at` and every membership proof carries `max_predecessor` after
`max_activation` (8 public inputs); the wallet names each msg's bounds
(§4g). Public referrer addresses, MsgBindReferrer and its consent are gone:
a registered human claims a **handle** (MsgBindHandle) naming their shielded
address; wallets pay a handle after reading the **whole** directory (the
backend's `/handles` stream, checked against the chain's `Query/Handles`
pages before money moves), and a registration names its referrer by handle
with a referral note to its address (§3a). An identity switch can first
move the handle (MsgMoveHandle) and the caretaker vote (MsgMoveCaretaker)
to the new identity. Nothing that spends a fee happens unasked any more:
the daily claim, the caretaker refresh and handle renewals are reminders;
the only automatic tx completes an undelegation the user started (§4c).
Deposits pull each leg rounded up (§4g). Android and iOS identical.

**Mobile audit round 5 (2026-10-03), summary.** A handle or caretaker
split is restorable: every bind, release, cast, clear and move carries a
tagged value-0 **state record** note (§3b), so a wallet restored from the
mnemonic knows what its identity holds or moved away; it also takes as held
a single non-free directory entry naming its own address, and drops a
handle the chain swept. A renewal or refresh whose bound the identity does
not meet goes out with no bound: the chain refuses it in its ante, before
any fee, unless the identity holds one (§4g). A move is recorded in both
wallets before its broadcast and counts as done only once the chain
confirms it (§4g). Directory entries and caretaker expiries outside
0 < expires_at ≤ renewal_until ≤ now + 10 years are refused or clamped; all
reminder arithmetic saturates. Referrals come only from the verified
`https://erth.network/ref/<handle>` link (App Link, universal link) or the
Play install referrer, and the registrant can remove or replace one (one
that does not resolve is cleared). Android and iOS identical.

**Audit round 5 chain rules (chain 203d3b2, ORCHARD_DESIGN 16; clients
round 6), summary.** MsgRegister names its referrer by handle alone (fields
11/12 gone): the affiliate field is H("earth.affiliate", Bytes(handle)) and
the **chain** mints the referral note to the handle's address with a public
opening; the handle owner's wallet finds it in the notes stream (format 2:
owner_pk, rho, rcm columns) by its own owner_pk, locally (§3a, §4h). An LP
payout leg above 2^64 − 1 arrives as several notes sharing one ciphertext,
each a note of its own. Every handle-claim and caretaker-cast bound comes
from `Query/LeaseBounds`, never Params; a handle in its renewal period or a
lapsed split is bounded like a claim, and only a live handle moves. Anchors
are kept at least 30 minutes from lapsing; the swap fee rounds up; a
withdrawal's note leg is bounded when it starts; a handle bind is priced as
nine note writes (§4h). Android and iOS identical.

**Stake votes without spending (chain 9b29f5d, ORCHARD_DESIGN 15),
summary.** MsgStakeVote no longer carries a stake proof: one derth note
proves (circuits/vote) it was in the stake tree and unspent at the
proposal's snapshot and publishes a per-proposal vote nullifier; nothing is
spent or re-minted, so a note votes on every concurrently open proposal.
The wallet rebuilds the snapshot's stake nullifier tree, rounds the weight
down to three significant figures and remembers (proposal, vote nullifier)
(§4e). Android and iOS identical.

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
undelegation's claim, unlocked position; a stake vote mints nothing) is named by a
`spc_mint` of fresh random rho and rcm and carries the blind stake ciphertext
of them (`StakeProof.spc_ciphertext`). Nothing about them is derived from a
counter, so failed or abandoned attempts can never open a gap that a restore
would not cross. (`mint-rho`, `mint-rcm`, `stake-rho`, `stake-rcm` must not
be used any more.)

Groundworks owner tags (a position stores `otag`; its owner proves it again
to update, unlock or vote it; positions carry no ciphertext), counter c a u32:

    salt_c = HMAC-SHA512("earth.privacy.v1", "otag-salt" || nk (32 BE) || c (u32 BE)) mod p
    otag_c = H(TAG_OTAG, owner_pk, salt_c)

A lock takes counter max(next_otag_counter, highest owned counter found + 1,
highest closed counter + 1) and advances next_otag_counter. Sync matches the
public positions against counters 0 … that + 1024 (OTAG_GAP = 1024: a closed
position disappears from the chain, so the window must cross a run of closed
positions and failed locks). A stake proof whose msg stores no tag
(everything but a position msg) uses a fresh random salt.

**Closed tags are never reused (K11; chosen over a random high counter,
which a restore could not find again).** A position disappears from the
chain when it is unlocked, and the chain's events do not carry its tag, so
a wallet restored from the mnemonic would otherwise start at the highest
*live* counter + 1 and lock again under a closed position's public tag,
linking the two. So MsgUnlockPosition's re-minted stake note carries, in
its blind stake ciphertext's memo, the counter it closed:

    memo = "EU" (0x45 0x55) || 0x01 || counter (u32 BE)
           || first 16 bytes of BE32( H(Tag("earth.unlocktag"), nk, U64(counter)) ) || zero padding

Sync opens every minted stake note anyway; an unlock memo whose tag
recomputes raises `closed_otag_max` (a gift of stake with someone else's
memo is ignored, so it cannot stretch the scan). Positions only ever
disappear by unlock (x/shieldedstaking removes them nowhere else). Left
open: a lock that failed in its block published its tag in the failed tx
without creating a position; a restored wallet may reuse that counter.

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
MsgUndelegate and MsgUnlockPosition (empty in every other staking msg;
MsgStakeVote has no stake proof at all):

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

**Open notes (chain 203d3b2).** A note the chain mints with an opening it
chose (today only the referral note, §3a) has no ciphertext at all: its
notes-stream row carries `owner_pk`, `rho` and `rcm` instead, and it is
ours iff owner_pk is our own and H(TAG_CM, AssetID(denom), amount,
H(TAG_PC, owner_pk, rho, rcm)) is the row's cm (§4h).

**Amounts (K12).** Every note value and stake amount is a u64 on chain.
Both apps parse public amounts as unsigned decimal u64 (no sign, ASCII
digits only) and take only values up to 2^63 − 1: a row, decrypted note or
stake note above that is ignored (never wrapped to a negative; no supply
reaches it). Totals shown to the user saturate; amounts a tx is built from
are checked (an overflow is an error, never a wrong change); derth × rate
saturates at 2^63 − 1 (a negative rate is 0).
The circuits enforce the same bound (privacy_core `NOTE_VALUE_BITS = 63`,
inside `note_cm` and `stake_cm`): an action's spend and output value, a
stake proof's input and output amounts and a vote's note amount are each at
most 2^63 − 1, so no proof can create a note a wallet ignores (the action
circuit had allowed outputs up to 2^64 − 1; chain ORCHARD_DESIGN §16). A
witness above it fails `nargo execute` and cannot be proven. The witness
format is unchanged; the action, stake and vote circuits (and their chain
verifying keys) are new. Public amounts (`v_in`, `v_out`, a bundle's
balance) are not notes and stay u64.

## 3a. Registration (binding, gas grant, record note, restore)

**Binding.** The passport proof's `address` public input is

    address = H(TAG_REG, idc, pc_anml, Bytes(ct_anml), pc_erth, Bytes(ct_erth), affiliate)

with affiliate = 0 for no referrer, and for a referrer named by handle
(chain 203d3b2)

    affiliate = H(Tag("earth.affiliate"), Bytes(affiliate_handle))

MsgRegister carries `affiliate_handle` (15) only, "" for none (11, 12, 13,
14 are reserved; 11/12 were `affiliate_pc` / `affiliate_ciphertext`, the
referral note the registrant's wallet used to make, which let a registrant
pay the referral half to itself). The chain mints the referrer's half
itself, at execution, to the address the handle resolves to then:

    pc  = H(TAG_PC, handle owner_pk, rho, rcm)
    rho = H(Tag("earth.referral"), passport nullifier, U64(leaf_index), U64(0))
    rcm = H(Tag("earth.referral"), passport nullifier, U64(leaf_index), U64(1))

(zk/privacy.ReferralOpening; leaf_index is the new identity leaf). The note
has no ciphertext; its `shielded_mint` event carries owner_pk, rho and rcm
(hex) with amount and position, and the handle owner's wallet takes it from
the notes stream by its own owner_pk (§4h). Vectors: vectors.json
`referral_opening` (five (nullifier, leaf_index) pairs with rho, rcm, and
the pc and cm of 5 ERTH to OwnerPK(7100+i)). The handle is resolved from
the whole directory when the registrant confirms the passport details (§4g:
live in a fresh copy and in the chain's own directory), and a wallet never
names its own address. The wallet picks fresh rho/rcm for its own two notes
and writes their ciphertexts **before** proving the passport, and sends
exactly those ciphertexts in MsgRegister and to /gas/register. A referral link
(`https://erth.network/ref/<handle>`, `earth://ref/<handle>`, the Play
install referrer `referrer=<handle>`) prefills the handle. Chain pinned vector: idc=1,
pc_anml=2, ct_anml="anml", pc_erth=3, ct_erth="erth", affiliate=0 →
`20ce5fccf5e6e20a8a7b80f7565e41a7c73dbb16ac5e53746e7234ba8b305b0c`.

**Gas grant.** `POST /gas/register` takes MsgRegister's fields (no fee
bundle; the referral as `affiliate_handle`, "" for none; never
`affiliate_pc` / `affiliate_ciphertext`, which the backend refuses with a
400 since chain 203d3b2; no `affiliate`)
plus `pc_gas` and `ciphertext_gas`, a fresh v2 ciphertext to self
(177 bytes, required). It is the only grant: `/gas/transparent`,
`/gas/android`, `/gas/challenge`, `/gas/ios`, `/gas/human` are gone.
**Proof of work (backend services/pow.py).** The request may carry
`"pow": {"ts": <int>, "nonce": "<str>"}`, a hashcash stamp:
SHA-256(`"earth-gas-pow/v1:" + ts + ":" + binding + ":" + nullifier + ":" + nonce`,
ASCII) with at least `bits` leading zero bits, ts unix seconds (±600 s),
binding and nullifier `public_signals[1]` and `[2]` exactly as sent, nonce a
lowercase hex counter. The wallet asks `GET /gas/pow` for `bits`, stamps at
it (off the main thread, cancellable, with progress; 22 bits is a few
seconds), posts; on 428 it makes a fresh stamp at the answer's `pow.bits`
and posts again (at most 4 rounds); it keeps a stamp for the next try only
after a 503 or 429 (the server gave it back, reused within 300 s), never
after a 403.

**Registration record note (version 2, K1).** MsgRegister's fee bundle
always carries, as one of its outputs, a value-0 uerth note to the wallet's
own address (v1 ciphertext) whose 64-byte memo is the registration record:

    memo = "ER" (0x45 0x52) || 0x02 || country (2 ASCII bytes A-Z, 0x0000 unknown)
           || built_at (u64 BE unix seconds, the wallet's clock when the bundle was laid out)
           || dsc_key (32 BE) || tag (16) || zero padding (3)          (61 bytes used)
    tag  = first 16 bytes of BE32( H(Tag("earth.rectag"), nk, dsc_key, U64(built_at)) )

H is Poseidon2 as everywhere (Tag(s) = the ASCII bytes of s as a field
element, U64 as in the chain's zk/privacy). Only the holder of nk can make
a tag, so a record anyone else sends (anyone can send this wallet a value-0
note with any memo) is ignored. A memo is a record only if the magic and
version match, the country is 0x0000 or two A-Z letters, dsc_key is
canonical, the padding is zero and the tag recomputes (compared before
anything else is done with it). Version 1 records (untagged, written by
builds before this format) are ignored: such a registration cannot be
restored from the mnemonic (register again). `country` is the wallet's
guess at the verifying CSCA's ISO alpha-2 (the DSC's issuer C=, else the
passport's issuing state). Sync keeps the 32 newest tagged records (they
are value 0, never spent). Golden (mnemonic `abandon ×11 about`, dsc_key =
77, country "FR", built_at = 1790000000), pinned in `ReauditFixesTest` and
`ReauditFixesTests`:

    4552024652000000006ab13b8000000000000000000000000000000000000000000000000000000000000000
    4d1d3756b83dfd918fa510770bc257079b000000
    (tag 1d3756b83dfd918fa510770bc257079b)

**Recording (C2, K7).** The moment the node accepts the registration
(broadcast returns code 0, before waiting for its block) the wallet persists
a pending registration {tx hash, dsc_key, passport nullifier, public
signals, country hint} and marks the notes the fee bundle spends (the gas
grant's note) pending; every private tx marks its spends at that moment
too. The leaf index (the tx's `register` event) and activated_at (its block
time) are filled in when the wait for the block returns, or else by the
next sync, which looks the tx up by hash (`GET /cosmos/tx/v1beta1/txs/{hash}`);
a tx that failed in its block is kept as a failure for the UI (a new
registration replaces it), and its spent notes are released once the chain
is past its timeout_height (§4). Every sync then tries to resolve it: once the local identity
tree has the leaf, the country is found by recomputing
`H(TAG_LEAF, idc, dsc_key, country, activated_at, predecessor_at)` over the
hint, unknown (0) and every A..Z pair, each with predecessor_at 0 (a
passport never registered before) and activated_at (a switch or a re-entry:
the chain sets it to the registration block's time); the match fixes the
identity's predecessor_at (every restore search below tries both too, so
its hash budgets doubled); the identity record is written and the pending one
dropped. It is never dropped unresolved (a leaf that does not match after
the tree has it is an error shown to the user, the record kept).

**Restore from the mnemonic (L8, K1).** No query names the registration.
Every tagged record found keeps, as the identity stream passes them, the
identity leaves appended at its block height h (identity leaves are synced
no higher than the notes, so the record is always seen first; at most 64).
Then, once a sync, newest record first, stopping at the newest that
matched:

1. **Block time from the indexer (audit 3).** activated_at is exactly the
   registration block's time. The indexer's identity rows may carry it as a
   fifth column (`[index, height, leaf, zeroed_height, time]`); the record
   keeps the time of its height's rows as they pass, and every country (the
   hint, unknown, then every A..Z pair: at most 677 hashes a leaf) is tried
   at exactly that time. The LCD is never asked about the registration's
   block alone. Audit 4 (H1): a row time is a block time of this chain or
   the page is inconsistent: at least 1,735,689,600 (2025-01-01) and at
   most the LCD tip's block time + 3600 s; a fallback candidate outside the
   same range, or one that would overflow, is skipped.
2. **Block time from the LCD, with a cover set.** When the rows carry no
   time (or it did not match), the LCD is asked for 16 block times
   (`GET /cosmos/base/tendermint/v1beta1/blocks/{h}`, `block.header.time`,
   the header's height must be h): h and 15 other heights, in a shuffled
   order. Audit 4: the decoys are drawn first from a persisted uniform
   sample (256) of the identity rows' heights (other registrations' blocks,
   the blocks a restore asks about), then uniformly from [1, min(the synced
   height, the LCD's tip)]; never a height past the tip (the synced height
   itself is bounded by the tip, §4a). Residual: an LCD that also runs the
   indexer sees 16 registration blocks asked together and knows the wallet
   is one of their registrants; the indexer's row `time` (preferred, step
   1) avoids the LCD altogether. The set is chosen once
   and persisted with the record (a retry asks the same set; at most 3
   fetches; once answered, never again). Its time is tried the same way;
   known and unmatched, the record is given up (EXHAUSTED). The device
   clock's built_at plays no part in steps 1-2.
3. **Fallback (no block time at all).** built_at is searched outward
   (0, +1, -1, +2, ...), the hint and unknown over [built_at − 3600,
   built_at + 86400], then every other country over [built_at − 600,
   built_at + 3600]. The search is resumable and bounded: the cursor and
   the hashes spent are persisted with the record (a killed app or a later
   sync continues, never repeats), each sync spends at most 50,000 leaf
   hashes over all records (a few seconds on a phone, so the wallet lock is
   never held long), and a record that spent 4,000,000 is given up. A
   device clock off by more than the windows (a day slow, an hour fast) is
   only found by steps 1-2.

Every exact time tried is recorded with the record; a time not tried
before (the indexer's, the LCD's) is still tried after the record was given
up, more leaves at its height reopen it, and a store reset finds every
record afresh (K13): an indexer serving a wrong time cannot block a
restore for good.

A match gives leaf_index, dsc_key, country and activated_at: the identity
record (passport nullifier left empty; nothing needs it). A registration
whose record note is missing (made by an older app) cannot be restored and
must register again (a switch to the same passport is allowed, a switch to
the same idc is refused by the chain only while the old leaf is live; the
app tells the user).

## 3b. State records: handle and caretaker split (audit 5, M1)

A handle and a caretaker split are held by a scope nullifier no query
names, so nothing on chain tells a wallet restored from its mnemonic what
its identity holds. Every MsgBindHandle (claim, renew, change, release),
MsgSetCaretaker (cast, refresh, clear), MsgMoveHandle and MsgMoveCaretaker
therefore carries, as outputs of its fee bundle, value-0 uerth notes (v1
ciphertext, 217 bytes) whose 64-byte memo is a **state record**, tagged
like the registration record (only nk makes one):

    handle:    "EH" (0x45 0x48) || 0x01 || kind (u8)
               || handle (32 ASCII bytes, zero padded; zero unless kind 1)
               || zero (12) || tag (16)
    caretaker: "EC" (0x45 0x43) || 0x01 || kind (u8)
               || expires_at (u32 BE unix seconds; 0 unless kind 1)
               || split (40 bytes: (option_id uvarint LEB128, percent u8)…,
                  options ascending, zero padded) || tag (16)
    kind:      1 holds, 2 released / cleared, 3 moved out;
               caretaker 0x81: holds, the split not recorded (it did not fit
               40 bytes: options are u64; at most 20), split bytes zero
    tag:       first 16 bytes of BE32( H(Tag("earth.statetag"), nk, Bytes(memo[0..48))) )

A record is accepted only if the magic, version and kind are known, the
tag recomputes (checked first), every padding byte is zero, a held handle
is a valid handle, and a recorded split has 1-20 distinct options of 1-100
percent summing to 100. Sync applies them in note order: the newest record
of each kind sets the store's handle (or none, or moved out) and split
(with its expiry, at most now + 10 years; a split the wallet already holds
keeps the chain's own later expiry), unless a newer one was already applied
(a reset keeps that cursor, so a resync never rolls back what the wallet
did since) or the record's height is one where the wallet saw its own tx
fail in its block. Who writes what:

| tx | to this identity's address | to the new identity's address |
| --- | --- | --- |
| MsgBindHandle claim/renew/change | holds handle | |
| MsgBindHandle release | released | |
| MsgSetCaretaker | holds (split, now + R as estimated when built) / cleared | |
| MsgMoveHandle | moved out | holds handle (tagged with the new nk) |
| MsgMoveCaretaker | moved out | holds (split, the chain's expiry) |

The mover has the new wallet's keys on the phone, so it can address and tag
that wallet's record. A record lands with its fee bundle, so one whose
msg then fails in its block (the ante's writes stay) still lands; the
wallet voids it when it sees the failure, and otherwise the chain refuses
what follows from it at no cost. Records cost nothing extra in the usual
case (a fee bundle has two actions anyway); a move's two records add one
action.

**Directory scan.** After a sync (on Home and the handle screens) every
wallet reads the chain's whole directory (`Query/Handles`, not the
indexer's stream) and squares its handle with it, unless a move is in
flight or the read predates the store's last change: a handle the chain
swept (absent or free) is dropped; with none held and not moved out, the
single non-free entry naming the wallet's own shielded address is taken
as held. Every such entry is reminded on.

## 4. Private tx assembly (follows x/shielded/ante)

- TxRaw with one Any, AuthInfo with no signer infos, fee = exactly the msg's
  total fee in uerth (the bundles' fee + fee_from_output), no payer or
  granter, no signatures, no timeout_timestamp (refused for private txs).
- **Canonical bytes (chain round 2, R7).** The tx is exactly
  TxRaw{body_bytes, auth_info_bytes}, each part and the msg inside the body
  the canonical protobuf encoding of what it decodes to (fields in number
  order, minimal varints, no default scalars, no unknown or extension
  fields), AuthInfo.tip unset. Both apps encode with protobuf builders
  (javalite, SwiftProtobuf), which produce exactly this; FakeChain checks
  the round trip.
- **Ciphertext slots (round 2).** Every action's output ciphertext is
  exactly 217 bytes, dummy outputs included; `StakeProof.ciphertexts` has
  exactly two entries, entry i empty iff `commitments[i]` is zero, a
  non-empty one exactly 153 bytes. Both apps check this before broadcast.
- **One use per binding (round 2, R1).** A registration's binding (the
  passport proof's address input) is refused once a registration with it
  has landed: the wallet prepares fresh notes (and so a fresh binding) for
  every registration; a proof whose registration landed is never resent.
- **Sighash** (every private msg):

      sighash = H(TAG_SIGNAL, Bytes(type_url), Bytes(chain_id), K, digest(bundle_0..K-1),
                  Bytes(memo), U64(timeout_height), U64(gas_limit), msg fields…)

  `Bytes(memo)` over the memo's UTF-8 bytes (Bytes("") for none). The wallet
  sets memo "" (an unshield may carry a user memo, e.g. an exchange deposit
  tag) and timeout_height = the LCD's latest height + 50 (audit 3), and
  fixes the gas limit (from simulation) before proving; the tx carries
  exactly those values (the simulated tx carries the same timeout).
- **Pending spends (K7, audit 3, audit 4).** The notes a tx spends are
  marked pending *before* it is sent, with its timeout_height and its hash
  (computed locally: uppercase hex SHA-256 of the raw tx bytes, the node's
  own; a node naming another hash is an error). A refusal that proves the
  tx is in no mempool (CheckTx's non-zero code, no connection at all)
  unmarks them at once; any other failure (a timeout, a lost answer) keeps
  them. They are released (spendable again) only when the LCD's latest
  height is past that timeout_height, the wallet has read the nullifier
  stream through it without seeing their nullifiers, and the LCD says the
  tx is missing (`GET /cosmos/tx/v1beta1/txs/{hash}` 404) or failed in its
  block (code ≠ 0): never by the wall clock. A tx the LCD says is in a
  block whose spend the indexer never reported keeps them pending and the
  sync unverified; an LCD that cannot say keeps them. (Marks made by older
  builds keep their old rule: no hash, the timeout alone; no timeout, 15
  minutes.)
- Fee = max(x/shielded min_fee, ceil(node min gas price × gas limit)); gas
  limit = simulated gas + max(10%, 20,000). Simulation runs on the real
  anchors, nullifiers, commitments, value commitments and ciphertexts with
  14,656-byte placeholder proofs and a zero binding signature. The tx is
  re-laid at the simulated fee; if that changes the action count it is
  simulated again (at most 4 rounds, the fee only rising after the first),
  then the sighash is computed (with that gas limit) and every action, the
  stake proof and the membership proven over it, every bundle signed. Every
  proof must be exactly 14,656 bytes (checked before broadcast).
- **Fee cap (audit 3).** Before anything is proven the fee must be at most
  min(2 ERTH, 2 × the wallet's own estimate): the estimate prices, at the
  node's gas price (and at least min_fee), the gas of the tx's shape at the
  chain's default schedule: 100,000 + 10 per tx byte + per bundle 100,000 +
  2,300,000 per action, + 2,600,000 for a stake proof, + 2,150,000 for a
  membership, + 3,600,000 for MsgRegister. A node asking more is refused
  (nothing proven or sent); the automation has no other limit. A confirm
  sheet's fee bounds the tx it confirms: a simulated fee above what the
  sheet showed throws before proving and the sheet is shown again at the
  new fee (Android's sheet shows the fee of 10,000,000 gas).
- **Quotes (audit 3).** A quote for a sheet (simulate without proving)
  carries random nullifiers in place of the wallet's (pool, stake, and the
  membership's), so the node learns nothing about the notes before the
  user confirms; only the confirmed run simulates the real ones.
- **SRS (audit 3).** Both apps bundle the first 32,769 G1 points of Aztec's
  bn254 transcript (`crs.aztec.network/g1.dat` bytes 0..2,097,215, sha256
  `d769ac6c98f8fab858a7e9967f2b7f181d8ad9fdcdf55438c915696febf0e99c`),
  enough for every privacy circuit (all set up at 2^15), passed to bb as a
  `.dat` path: proving a private tx never touches the network. The passport
  circuits' SRS (too large to bundle) is fetched once at launch, not when a
  proof needs it: a byte range of the same file, hash-pinned (Android 2^18+1
  points, sha256 `8f5cd75519c2e995fa47aa7ecd7b213b9ae13824bc4b0f15025a63acd8c139eb`;
  iOS 2^19+1 points, `1df37a2ce1da3713c7300691a65ffe84de144ea64899d5cfbf0061aaaeb6ad31`),
  kept out of backups; until it is there a passport proof downloads its own
  (registration is public anyway). iOS reserves the passport size for a
  private proof only from that local file, never from the network.
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
  | MsgSetCaretaker | per entry option_id, percent (max_predecessor = 5 is not a sighash field) |
  | MsgMoveCaretaker | new_owner |
  | MsgBindHandle | Bytes(handle), owner_pk, Bytes(ek_pub) of the address (release: Bytes(""), 0, Bytes("")) |
  | MsgMoveHandle | Bytes(handle), new_owner |
  | MsgDelegate | StakeFields, Bytes(validator), amount |
  | MsgRestake | StakeFields, Bytes(validator) |
  | MsgUndelegate | StakeFields, Bytes(validator), amount |
  | MsgClaimUnbonding | StakeFields, Bytes(validator), epoch, amount, pc, Bytes(ct), fee_from_output |
  | MsgStakeVote | proposal_id, Bytes(validator), Bytes(OptionsBytes), weight, vote_nullifier (no StakeFields) |
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
  = 11, MsgStakeVote.proof = 8 / vote_nullifier = 9 (stake 7 reserved).
  Addresses are lowercase canonical bech32.
- **Stake proofs.** A staking msg's stake proof spends at most two stake
  notes of the msg's denom (the smallest single covering, else the
  smallest sufficient pair; a balance spread over more is merged first by
  MsgRestake, two into one) and creates at most one change note (two for a
  restake split) with the wallet stake ciphertext; unused input and output
  slots get random rho/rcm (amount 0: nf 0, cm 0) and a zero path. Anchor:
  the wallet's latest (chain-verified) stake root (zero when the stake tree
  is empty; not checked when nothing is spent). `spc_mint` and
  `spc_ciphertext` as in §3; `otag` a position's owner tag (lock: a new
  counter; update/unlock/vote: the position's) or random. A stake vote is
  not a stake proof (§4e). **Casting a stake vote (K5)** is one path
  in the app (StakeVoteController, what the proposal screen's confirm
  runs): every eligible derth note (one vote each, §4e) and every position of ours created
  before the snapshot's block, in a shuffled order, one cast at a time, a
  full sync and a random 20-120 s pause between casts (none before the
  first; one before the first after a resume), so a vote's fee never spends
  the previous vote's change unseen and the casts are not one burst that
  times them together. It runs off the screen (the app-level view model;
  never holding the wallet lock while it waits), shows its progress and the
  next cast's time, and can be stopped. The plan is persisted (proposal,
  options, positions voted, casts done) and a run the process lost resumes
  on the next unlock (positions already voted are skipped; notes already
  voted are known by their recorded vote nullifiers, §4e).
- **Groundworks positions** are still per-user msgs with splits; the chain
  weighs them per validator and no longer stores a position's weight. The
  wallet shows a position's weight as derth × its validator's current rate
  (0 without a live split).

## 4e. Stake votes without spending (chain 9b29f5d, ORCHARD_DESIGN 15)

One MsgStakeVote per derth note and proposal:

    bundle (fee), proposal_id, validator, options, weight, proof (8), vote_nullifier (9)

1. **Snapshot.** The LCD `Query/Snapshot`
   (`/earth/shieldedstaking/v1/snapshots/{proposal_id}`), never the
   indexer (audit 4, M3: a forged nf_root made a note look spent before the
   snapshot and the vote was skipped; the proposal id is public, so asking
   names nothing of the wallet): root, tree_size, height, nf_root, nf_size
   (the nullifier tree's leaf count, sentinel included; 0 = nothing
   inserted), and the validators' rates (legacy snapshots; the weight shown
   uses them). A snapshot without nf_root takes no stake vote. Cached per
   proposal, dropped with every other per-chain cache when the store's
   genesis changes.
2. **Note.** Its path in the wallet's stake tree of the first tree_size
   leaves; that tree's root must be the snapshot root (a snapshot past the
   local tree is "sync first"). A note at position >= tree_size cannot vote.
3. **Nullifier tree.** The first nf_size − 1 stake nullifiers in insertion
   order (nf_size the LCD's, so the fetch is bounded by the chain's own
   count), from `{base}/stake/nullifier-tree?from_index=0&limit=1000` and
   the aligned pages after it (§4a paging rule: page k holds leaf indexes
   [1000k, 1000(k+1)), leaf 0 the sentinel never a row, so page 0 holds
   1..999; rows [index, nullifier, height], indexes contiguous; a leaf
   already held must be served identically; more than 1000 rows, or
   anything else, is inconsistent) with the LCD
   `Query/StakeNullifierTree{start, limit}` (1000 a page) for whatever the
   indexer lacks. Inserted in order into the
   indexed tree (leaf = H(TAG_SNFL, value, next_value, next_index), leaf 0
   the sentinel; the wallet writes the final leaves in one batch, the same
   root as replaying the inserts), its root must equal nf_root; otherwise
   everything fetched is dropped and the tree is rebuilt from the LCD alone
   once, else the vote is refused. The values are kept in memory (a prefix
   of an append-only list) and the last two trees by nf_root.
4. **Low leaf** of the note's spend nullifier H(TAG_SNF, nk, rho, pos): the
   predecessor (the sentinel if none) with next = the successor (0, 0 if
   none), its index and path. If the nullifier is in the tree the note was
   spent before the snapshot: refused locally (`SpentBeforeSnapshot`),
   nothing is simulated or broadcast, but only when sync, too, saw the spend
   at or before the snapshot's height; otherwise the two disagree and the
   cast fails with an error (audit 4: never a vote silently skipped). A note spent after the snapshot still
   votes; its outputs cannot (not under the root).
5. **vote_nullifier** = H(TAG_VNF, nk, rho, pos, proposal_id): one per note
   and proposal, unlinkable to the note's other votes and its spend.
6. **Weight (wallet rule).** The note's amount (uderth) rounded DOWN to
   three significant decimal digits, whole below 1000:

       unit = 1; while amount / unit >= 1000: unit *= 10
       weight = amount / unit * unit

   999 → 999; 1,000 → 1,000; 1,009 → 1,000; 999,999 → 999,000;
   1,234,567 → 1,230,000; 123,456,789 → 123,000,000. The published weight
   names a bucket, not the exact amount (a delegation's minted amount is
   public), and gives up less than 1% of the note's voice. The confirm
   sheet's weight is the sum of the rounded weights at the snapshot rate.
7. **Prove** circuits/vote (public inputs note_root, nf_root,
   AssetID(derth/<validator>), weight, proposal_id, vnf, sighash; Prover.toml
   names nk, amount, rho, rcm, pos, path, low_value, low_next_value,
   low_next_index, low_index, low_path), fill the fee bundle; the sighash
   binds vote_nullifier (fields in §4). A quote (confirm sheet) simulates
   with a random vote nullifier. Gas estimate: 250,000 + proof + one note
   write (2,400,000); every stake proof now prices two more note writes per
   nullifier slot (3,200,000).
8. **Remember** (proposal, vnf) the moment the node accepts the tx
   (`stake_votes`: proposal_id, vnf, tx_hash, until = timeout_height,
   confirmed); confirmed once committed. A vote that failed in its block,
   or is unknown once the chain is past its timeout_height, is forgotten
   and the note may vote again. A vote the chain refuses as already cast
   (code 1119 in codespace `shieldedstaking`, or at simulate its registered
   text "this stake note already voted on this proposal": no fee)
   is recorded as confirmed: that is how a wallet restored from the
   mnemonic, which does not know its votes, learns them. One vote per note
   per proposal; the same note votes on every other open proposal.

Eligible notes for a proposal (cast list, weight shown): derth, amount > 0,
position < tree_size, not spent at or before the snapshot's height as far
as sync knows (a spend in the snapshot's own block is before it: the
snapshot is the trees at that block's end), not already voted on it. The
recorded votes survive a same-chain reset (an inconsistent sync, a root
mismatch; audit 4, L1). A running vote (StakeVoteController) waits for a
suspended run's last cast to end before casting (audit 4, L5), records a
position's vote the moment the node takes it, and stops when its persisted
run is gone (a chain switch).

## 4d. Chain wave 3 wallet rules (chain 06ea4d6)

- **Vote weights.** Every option weight in MsgStakeVote / MsgPositionVote
  is the canonical LegacyDec string, 18 decimals ("1.000000000000000000",
  "0.500000000000000000"); the wallet canonicalizes whatever it is given
  before laying the msg out (the sighash already bound the canonical form).
- **Referrer consent** (MsgBindReferrer): removed with the msg at 4a663d5
  (§4g).
- **Activation bounds:** superseded by the predecessor bounds (§4g).
- **No unshield to a module account.** The wallet refuses, before
  proving, an unshield whose receiver is any module account the chain
  declares (fee_collector, distribution, mint, bonded_tokens_pool,
  not_bonded_tokens_pool, gov, nft, transfer, interchainaccounts, shielded,
  shieldedstaking, dex, allocation, personhood, earth, wasm: address =
  SHA-256(name)[:20]; vectors.json `module_accounts`).
- **current_date** of a registration's passport proof must be a calendar
  date (YYMMDD; 250231 is refused); the wallet checks before broadcast.

## 4g. Handles, predecessor bounds, moves, reminders (chain 4a663d5)

- **Identity leaf and membership.** leaf = H(Tag("earth.leaf"), idc,
  dsc_key, country, activated_at, predecessor_at) (`Registration.
  predecessor_at` = 8). Membership public inputs, in order: root, scope,
  nullifier, signal, excluded_dsc, excluded_country, max_activation,
  max_predecessor (the circuit checks activated_at <= max_activation and
  predecessor_at <= max_predecessor). "No bound" is 2^63 − 1
  (`Privacy.NO_BOUND`). Bundled `membership.json` was rebuilt with nargo
  1.0.0-beta.22; `bb write_vk` of all four bundled privacy circuits equals
  the chain genesis's verifying keys. Golden: vectors.json `leaf`,
  `leaf_pred`, `membership_public_inputs`.
- **Bounds the wallet names, per msg** (it refuses locally, `NotYet`, when
  its own identity does not meet them; audit 5: a set-caretaker or bind
  whose L is not met goes out with no bound, since a restore can lose what
  the identity holds; x/personhood checks the bound in its ante, before
  the fee bundle is spent, so a holder of none is refused at no cost and
  the wallet says so, `NotHeld`, with the wait; release needs a held
  handle, since the chain refuses that only after the fee):

  | msg | max_activation | max_predecessor |
  | --- | --- | --- |
  | MsgClaimAnml | start of yesterday (day − 1) × 86400 | no bound |
  | MsgSetCaretaker, a new split | no bound | L(R) |
  | MsgSetCaretaker, refresh/change/clear of one held | no bound | L(R) if met, else no bound |
  | MsgBindHandle, a claim (holding none) | no bound | L(handle_lease_seconds) |
  | MsgBindHandle, renew/change of the one held | no bound | L(lease) if met, else no bound |
  | either, bound not met and nothing held as far as the wallet knows | no bound | no bound (audit 5) |
  | MsgBindHandle, release | no bound | no bound |
  | MsgMoveHandle, MsgMoveCaretaker | no bound | no bound |
  | MsgVoteProposal, MsgVoteRemoval | BallotInputs.max_activation (no bound) | BallotInputs.max_predecessor (7) |
  | MsgProposeRemoval | no bound | start of today (UTC) − 86400 |

  L(lease) = floor_hour(now − lease − 86400 − 600): strictly below the
  chain's now − lease − 86400 with 600 s of clock margin, rounded to the
  hour so it says nothing about when the tx was made. Every wallet names
  the same L, fresh registrants (predecessor_at 0) included: they meet it,
  so a fresh identity acts at once and its proof does not tell it from an
  old one (proving max_predecessor = 0 would). R is caretaker_vote_seconds
  (default 365 days); handle_lease_seconds is param 27 (default 365 days;
  the chain bounds a claim by the longest lease ever set).
- **Handles.** Lowercase a-z, 0-9, -; 3-32; no dash at either end; no case
  folding (the wallet lowercases what is typed and drops a leading @).
  MsgBindHandle {fee 1, membership 2 (scope Scope("handle")), handle 3,
  address 4 (canonical lowercase "erthz1..."), max_predecessor 6}. Holding
  a handle: the same handle renews it (lease now + handle_lease_seconds,
  the address may change), another changes to it (the old one is freed at
  once); holding none: a claim; both empty: release at once. MsgMoveHandle
  {fee 1, membership 2, handle 3, new_owner 4 = H(TAG_SN, new_id_secret,
  Scope("handle"))}. Lifecycle: live until expires_at; then until
  renewal_until (= expires_at + handle_renewal_seconds, param 26, default
  30 days) reserved to its owner and not resolving; then free. Errors:
  1121 (not a live handle, as a registration's referrer), 1122 (taken),
  1125 (this identity moved its handle away), 1126 (its caretaker split);
  codespace personhood. The wallet records the handle it holds in its store
  (`handle`, `handle_moved_out`); status, expiry and renewal window come
  from the directory.
- **The directory, never one handle.** Every lookup reads the whole
  directory: the backend's `GET {base}/handles?from_index=&limit=1000`
  (rows [handle, address, status, expires_at, renewal_until, owner]; owner
  optional, see §4i; a snapshot at
  `height`, read from index 0 in aligned pages until `last_page`, started
  over (at most 3 times) when `height` changes between pages; refused when
  out of handle order, a status other than live/renewal/free, a malformed
  handle, or a row count other than `size`), falling back to the chain's
  `GET /earth/personhood/v1/handles?start=&limit=1000` from the first page
  (`next` must be the page's last handle). Cached 10 minutes. There is no
  per-handle query anywhere in the wallet (`Query/Handle` is never used).
  An entry whose expires_at has passed by the wallet's clock is treated as
  in its renewal period (not payable).
  Audit 5 (M4, L4, L7): a directory with an entry whose times no lease has
  (not 0 < expires_at ≤ renewal_until ≤ now + 10 years) or with more than
  250,000 rows (audit 6, H2: was 1,000,000; also checked against page 0's
  `size`), or an address longer than 256 characters,
  is refused whole; the lease params are taken at most 10 years; a
  set_caretaker expires_at outside (0, now + 10 years] is replaced by the
  block time + R; reminder and countdown arithmetic saturates.
- **Paying a handle.** Send accepts "@handle" or a bare handle. Before the
  confirm the wallet reads a fresh copy (at most 60 s old) and the chain's
  own directory (also whole, also at most 60 s old): the handle must be
  live in both with the same address, else nothing is paid. The confirm
  shows "@handle · erthz1xxxxxxxx…yyyyyyyy". Funding: from notes when the
  shielded balance of the asset covers it (a private MsgSend to the
  address), else from the public balance as MsgShield whose note is minted
  to the handle's address (pc of its owner_pk, 177-byte blind ciphertext to
  its ek_pub; the amount is public, the recipient is not).
- **Moves and switching identity.** A switch is the same passport
  registered from another wallet on the phone. Before it, the old wallet
  may move its handle and its live caretaker split to the new identity's
  nullifiers in those scopes, computed from the other wallet's keys
  (`H(TAG_SN, id_secret', Scope(...))`), and records them in the other
  wallet's store (handle, split, expiry; kept by its first sync), so its
  renewal and refresh take no bound. The mover records `*_moved_out` and
  never casts or claims again. Without moves, the new identity waits until
  everything its predecessor could hold has lapsed (lease + 1 day). The
  switch screen requires the new wallet's recovery phrase be backed up and
  explains that a lost wallet's handle and vote cannot be moved.
  **Audit 5 (M2, L8, L9).** A move is written to both stores at the
  moment its hash is known, before the broadcast: the mover's as an
  outgoing pending move (it still holds what it is moving), the new
  wallet's as incoming (it holds it, pending). A refusal (CheckTx, no
  connection) undoes both; a tx that failed in its block, or that the
  chain does not know once past its timeout_height, is undone by each
  wallet on its own when it settles its pending moves by hash (every sync,
  and "Check the moves again"); a committed one is applied (the mover
  records moved out), and its state records (§3b) settle it too. The UI
  shows a move as done only once confirmed and does not offer "register
  there" while one is in doubt; a failed write to the new wallet's store
  is kept for a retry (that wallet also finds the move in its own notes).
  The first confirmed move fixes the target wallet (by store id; audit 6
  M5: a move in flight holds it only while in flight, and a refused,
  failed or expired one frees it); a target whose identity moved a handle
  or split away, or that holds one of its own, is refused for that move
  before anything is sent; one with a registration is warned about. The recovery
  phrase is shown only after a fresh PIN or biometric unlock (counted
  against the unlock backoff) and dropped when the screen is paused or
  left; the backup box can be ticked only once it was shown.
- **Dex deposits** (audit 4, C2). x/dex pulls each leg rounded up,
  ceil(shares × R / S) with shares = min(⌊in_e × S / R_e⌋, ⌊in_t × S / R_t⌋);
  the wallet derives the other leg of a deposit as ceil(amount × R_other /
  R_typed), so the typed side is the binding one and at most one unit comes
  back as a refund. min_shares is the shares at the current reserves less
  1 %. Pinned against the chain's own maths (dex_amm.json `deposits`).
  ErrPoolCap (dex 1120, past 2^120) is explained to the user.
- **timeout_height** stays the LCD tip + 50 (above the last committed
  height, as CheckTx now requires).

## 4h. Audit round 5 chain rules (chain 203d3b2, ORCHARD_DESIGN 16)

- **Notes stream format 2** (backend README "Note stream format 2"). Every
  `/notes` page has `"format": 2` and `"fields": ["position", "height",
  "cm", "ciphertext", "amount", "owner_pk", "rho", "rcm"]`; the wallet reads
  columns by name and refuses a page of any other format (an old backend)
  or one missing a column. Three kinds of row: a bundle output (`amount`
  null, `ciphertext` set): trial-decrypt (§3); a minted or shielded note
  (`amount` and `ciphertext` set): blind v2 against the row's amount; an
  **open note** (`ciphertext` null; `amount`, `owner_pk`, `rho`, `rcm` set,
  hex): ours iff owner_pk is our own and the opening with the amount
  recomputes the row's cm. A row with part of an opening, an opening beside
  a ciphertext, or an open row without an amount is refused (the page is
  inconsistent). Matching is local over the whole stream the wallet reads
  anyway; no request ever names an owner_pk. The note is then spent like
  any other (nf = H(TAG_NF, nk, rho, position); the published opening
  links nothing without nk).
- **Split payouts.** x/dex pays a private LP withdrawal leg above 2^64 − 1
  as ceil(v / (2^63 − 1)) notes (MintNoteSplit, at most 128; chain
  8ed1278): consecutive rows with the **same** pc and ciphertext, each its
  own amount (2^63 − 1, …, the remainder) and position. The blind v2
  ciphertext binds no cm or value, so each row decrypts to the same
  (rho, rcm) and its own amount gives its cm; the nullifier includes the
  position, so every chunk is a separate, separately spendable note. The
  wallet never dedupes by ciphertext, pc or opening. Every chunk fits the
  2^63 − 1 every client holds (§3 Amounts). x/dex refuses at start a leg
  above 32 × (2^63 − 1) (a quarter of what a payout can carry; dex 1101,
  "the most one withdrawal pays as notes"), and every client refuses the
  same bound before proving; the app explains it.
- **Lease bounds** (`GET /earth/personhood/v1/lease_bounds`, int64s as
  strings): block_time, activation_margin_seconds, handle_lease_seconds
  (the longest ever in force), handle_claim_bound, caretaker_lease_seconds
  (a held longer lease after a cut included), caretaker_cast_bound,
  caretaker_lease_hold_until. Checked before use: leases in 1..10 years, the
  margin in 0..10 years, and handle_claim_bound = block_time −
  handle_lease_seconds − margin (likewise the caretaker bound), else
  refused. L(lease) of §4g is now floor_hour(block_time − lease − margin −
  600) with lease and margin from here (closes the mobile audit 5 L1: the
  bound used Params); the UI's claim-wait date uses the same lease.
- **Held but not live** (audit 5 P2). MsgBindHandle is unbounded only for a
  prover holding a **live** handle: a renewal or change of a handle in its
  renewal period is bounded like a claim. The wallet keeps the held handle's
  expires_at (`handle_expires_at` for `handle_expires_for`, from its bind's
  `handle_bound` event, else the block time + the lease, and refreshed from
  the chain's directory on every scan); past it, an identity that does not
  meet L gets `HandleNotLive` (iOS NotYet.lapsed .handle) before anything is
  sent, with the date it may claim; with no expiry known it tries no bound
  and the chain's no-fee refusal reads as NotHeld. MsgMoveHandle refuses a
  handle that is not live: the wallet refuses it first (`HandleNotMovable`)
  and the switch screen does not offer it, saying why; the chain's text
  ("renew it before moving it", personhood 1116) is explained. A caretaker
  split past the chain's own expiry is not held: refreshing it is a new
  split, bounded (`CaretakerLapsed`, iOS NotYet.lapsed .caretaker).

  | msg (replaces the §4g rows) | max_predecessor |
  | --- | --- |
  | MsgBindHandle, renew/change of a live handle held | L(handle lease) if met, else no bound |
  | MsgBindHandle, renew/change of a handle in its renewal period | L(handle lease); not met: refused locally |
  | MsgSetCaretaker, refresh of a live split | L(caretaker lease) if met, else no bound |
  | MsgSetCaretaker, refresh of a lapsed split | L(caretaker lease); not met: refused locally |

- **Anchors.** CheckTx/ReCheckTx refuse an anchor lapsing within 120 s of
  the last block, and a proposer leaves out a tx whose anchor lapsed by its
  block's time. Before laying out a private tx the wallet reads its local
  root's record (`/earth/shielded/v1/roots/{root}`: `valid`, `expires_at`, 0
  for the latest root); one lapsing within 1,800 s of the LCD tip's time
  (the CheckTx margin, the 50-block timeout_height and phone proving) makes
  it sync first (a newer root), and if that is still too old the tx is
  refused before anything is proven (Android SyncFirst, iOS AnchorTooOld).
  A node that does not say `expires_at` leaves the chain's own check. The
  chain's "pick a newer anchor" refusal (shielded 1103) is explained.
- **Gas.** A handle bind (claim, renew, change, release) is priced as nine
  note writes: the fee cap's estimate adds 8 × 150,000 to the membership's
  one write, and the confirm sheet estimates 12,500,000 gas (other private
  txs 10,000,000). The fee itself is still simulated (gas + 10 %, at least
  20,000). x/shielded's gas prices are capped (proof 10M, note 1M, bundle
  1M); the wallet's fee cap stays twice its estimate at the defaults and
  2 ERTH absolute.
- **MsgShield** of a send-disabled denom is refused (bank 5, "send
  transactions are disabled"); its simulate fails before signing and the
  app says the token's transfers are switched off.
- **Assembly.** An expedited proposal the chamber ratified and x/gov
  demoted votes again in round 1, a new nullifier scope; BallotInputs
  reports `round`, and the wallet recomputes the scope as
  Scope("proposal", U64(id), U64(round)) and refuses a node's scope that
  differs (unchanged code; vectors `proposal_5_0`, `proposal_5_1`).
- **Dex.** The swap fee is LegacyDec(amount) × fee / 100 rounded half-even
  at 18 places, then **up** to an integer (was truncated); quotes and
  min-out use it (dex_amm.json and iOS corecheck re-derived from x/dex at
  203d3b2; deposit vectors unchanged).

## 4i. Audit round 6 wallet rules (chain c0ad1dd)

- **Indexer denoms (M2, M3).** A row's public amount is `<digits><denom>`
  with the denom matching the SDK rule `[a-zA-Z][a-zA-Z0-9/:._-]{2,127}`
  and never starting `asset/` (the wallet's own name for an asset id it
  cannot resolve); anything else and the row is not opened against it. The
  same holds for a stake row's denom. A denom is learned (asset id → denom
  for v1 and wallet-stake ciphertexts) only from a note of this wallet's
  whose cm it reproduces, or from the chain's asset list (`GET
  /earth/shielded/v1/assets`, every page, at most 4,096 entries, each
  learned only if its `asset_id` is `AssetID(denom)`), read at most once a
  sync and only when a note of ours carries an id the wallet cannot
  resolve. The persisted `denoms` are the denoms of the notes held (at
  most 4,096); the lookup is built once a sync. A held note named
  `asset/<hex>` whose id becomes known is renamed (same asset, same cm).
  A row that cannot be opened is skipped, never thrown on: a page's rows
  are opened before the tree grows.
- **The send tip (M4).** `verified_height` (store) is the indexer height
  of the last verified sync (checked against the chain's tree and tip),
  kept across resets. A tx's tip (LCD latest height) more than 1,000
  blocks past it is refused (the wallet syncs once and tries again, then
  refuses) before anything is laid out. A pending mark (note, stake note,
  vote, move) whose timeout_height is more than 1,050 blocks past the
  current `verified_height` came from an inflated tip: it is settled by
  the tx's status alone (missing or failed: released; notes after the
  15-minute mempool grace).
- **Handle owners (M6).** The chain adds `owner` to every handle entry
  (Query/Handle, Query/Handles): the handle-scope nullifier that holds it,
  64 hex digits (lowercase; any case accepted), the same value as
  MsgBindHandle's membership nullifier and MsgMoveHandle's new_owner. The
  backend stream carries it as the row's sixth element. Absent or
  malformed, an entry has no owner. The wallet adopts a directory entry as
  its handle only if `owner` equals its own `H(TAG_SN, id_secret,
  Scope("handle"))` (hex); an entry merely naming its address is never
  adopted (anyone may bind any address). A held handle whose entry names
  another owner is dropped. While no handle is held, entries naming the
  wallet's address whose owner is absent are shown as unverified, with a
  renew-only bind; entries with another owner are not shown.
- **Renew-only binds (M7).** Renew (the held handle, reminders, the
  address cards) binds only the handle held, or, holding none, the one
  named; a bind that would change the held handle (freeing it) is refused
  locally. Address cards are hidden while a handle is held.
- **One store per wallet (M8).** The app keeps one wallet object and one
  store per wallet per process, across lock and unlock; a stake-vote run
  re-reads the voted positions from the store before each cast and merges
  its progress into the stored run.
- **Public add-liquidity (M9).** MsgAddLiquidity carries `min_shares`
  (field 5) = min(⌊e·S/R_e⌋, ⌊t·S/R_t⌋) less 1 % from fresh pool and share
  supply reads, as the shielded deposit; "" only for an empty pool; a read
  that fails refuses the deposit (D7). Golden (both platforms):
  creator "earth1creator", pool 2, 1000uerth, 300uusd, min_shares "148" =
  `0a0d65617274683163726561746f7210021a0d0a057565727468120431303030220b0a047575736412033330302a03313438`.
- **PIN change (M1, Android).** Changing the unlock secret needs a fresh
  unlock with the current one (counted against the unlock backoff).

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
   old chain's bookkeeping (claimed days, caretaker split, handle); it
   keeps the identity record (with its passport nullifier), the pending
   registration and the owner-tag counters, and the identity's leaf is
   re-verified against the resynced tree (shown as not live if it does not
   match; the record is never dropped). An unconfirmed switch (the LCD says
   otherwise, or cannot say: block 1 pruned, LCD down) wipes nothing, syncs
   nothing and is shown as unverified. A first sync (nothing stored) goes
   ahead when the LCD cannot say, never when it contradicts the status.
3. Every stream is read under `base`:

       GET {base}/notes?from_pos=&limit=               format 2: [position, height, cm, ciphertext, amount, owner_pk, rho, rcm] (§4h)
       GET {base}/nullifiers?from_height=&limit=       [[height, [nf, ...]], ...]
       GET {base}/identity?from_index=&limit=          [index, height, leaf, zeroed_height]
       GET {base}/identity/zeroed?from_height=&limit=  [[height, [index, ...]], ...]
       GET {base}/roots/latest                         {note, identity, stake: {root, tree_size, height, time}}
       GET {base}/rates?epoch=                         [validator, rate, supply, epoch, height]
       GET {base}/stake/notes?from_pos=&limit=         [position, height, cm, ciphertext, denom, amount, spc]
       GET {base}/stake/nullifiers?from_height=&limit= [[height, [nf, ...]], ...]
       GET {base}/stake/roots?from_height=&limit=      [height, root, tree_size, time]
       GET {base}/stake/nullifier-tree?from_index=&limit=  [index, nullifier, height]   (from 0; leaf 0 never a row; size, next_index)
       GET {base}/stake/snapshots?from_height=&limit=  [height, proposal_id, root, tree_size, nf_root, nf_size]

   A 404 means the base moved: re-read the status (step 1) and retry once.
   Response bodies are capped (8 MiB decompressed) and nested at most 64
   arrays/objects deep, checked before parsing (audit 4, M7: Android's
   org.json recurses without a cap); a redirect is never followed (both
   platforms; a 3xx is an error), for the LCD as for the indexer. A 503
   (the indexer's in-flight cap) or 429 (a client's rate) is retried after
   Retry-After, or 1, 2, 4, 8 s (at most 30 s), four times, then the sync
   fails like any other.
   **Paging rule (backend audit 4, B3).** `limit` is 100 or 1000 (the
   wallet always asks 1000); a position or index cursor (`notes`,
   `identity`, `stake/notes`, `stake/nullifier-tree`) is a multiple of the
   limit and page k is exactly [k·limit, (k+1)·limit). The wallet asks
   for the page holding its cursor, `from = next − next % limit`, and
   drops the rows it holds (a held note or stake note row must be the
   leaf held, else inconsistent); a full page is followed by the next, a
   short one is the tip. A position page carries at most `limit` rows. A
   height page (nullifiers, identity/zeroed, stake/nullifiers,
   stake/snapshots) keeps a free `from_height` and never splits a block,
   so it may exceed the limit by one block (at most 5000 rows).
   **Heights bounded by the chain (audit 4, M1).** Every height an indexer
   page names (a row's height or zeroed_height, `synced_height`,
   `next_height` − 1, every `/roots/latest` height) must be at most the
   LCD's latest height + 10 (read again once when exceeded: the chain
   moved); past it the page is inconsistent and nothing from it is kept, so
   no persisted cursor can be pushed past the chain.
   **Paging (audit 3).** A position page (notes, stake notes) must name
   `next_pos` = from + rows, and one marked complete (more follows) must
   carry rows; a height page never names a `next_height` below its
   `from_height` (nor equal to it when complete) and holds no earlier
   height. Anything else is inconsistent (the wallet starts over once,
   then stops). One sync, its retries included, gives up after 10 minutes.
   The identity stream's optional fifth column `time` is the leaf's block
   time (§3a restore).

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

**Sync generations (audit 3).** Before a sync's first request the wallet
bumps its sync generation, clears "verified" and persists both; only the
root checks at the end of that same sync mark that generation verified. A
sync that fails part way (an indexer that serves forged notes and then
breaks a later stream) leaves the wallet unverified, and every private tx
needs the latest generation verified.

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
  own nullifiers (that would name its notes): they are left out of the
  sample.
- **Note root height (audit 4).** The record's `height` (the block that
  produced the root) must be the indexer's `roots/latest.note.height`;
  otherwise unverified.
- **The indexer's claimed height (audit 4, M1).** `GET
  /earth/shielded/v1/tree` at the indexer's `roots/latest.synced_height`
  (pinned as above) must hold exactly the local note tree's size: a stale
  indexer naming the current height is caught (every spend appends
  notes). Unverified otherwise; an unpinned read is not used.
- **Indexer behind the tip (K9).** If the LCD's latest block
  (`/cosmos/base/tendermint/v1beta1/blocks/latest`) is more than 30 blocks
  past the indexer's synced height (now checked as above), the roots are
  unverified ("the indexer is N blocks behind"). An LCD that cannot say
  its height leaves them unverified.

A local tree larger than the indexer's latest, or one of the same size with
another root, is inconsistent: the wallet starts over from an empty store.
A local tree that differs from the indexer's latest is resynced once; a
mismatch wipes the synced data and is shown. Unverified roots block every
private tx (no proof is built on them) and are shown as such next to the
private balances, stake and registration until a later sync verifies them.

## 4c. Wallet behaviors (audit 3)

- **Automation** (round 5: only matured unbonding claims, the completion
  of an undelegation the user started, paid from its own output) takes one
  claim at a time, chosen at random among those due; before the next, a
  random 30-180 s pause and a full sync, and a fresh decision. Logs name the
  kind of action only, never a denom. Nothing else spends a fee unasked:
  the day's ANML claim, the caretaker vote and the handle are **reminders**
  (Home banners and the Handle screen): "ANML ready to claim" when today's
  claim is open and not made; the caretaker vote from 30 days before its
  expires_at until 30 days after; the handle from 30 days before
  expires_at through its renewal period. A stake vote run continues only a
  vote the user started.
- **Stake vote run** (K5) stops with the session: lock, session end and a
  wallet switch suspend it (the wallet's keys are dropped, the persisted
  run kept); the next unlock resumes it from that wallet's own store only.
  A second start while one runs is refused.
- **Saved state.** state.json is written to a temp file, fsynced and
  renamed over (iOS: atomic write); a failed save is an error, never
  silent. An unreadable state.json is an error shown to the user, never
  replaced by an empty wallet.
- **Forgetting a wallet** deletes its `privacy/<id>/` directory (notes,
  identity, records, trees): every file overwritten with zeros, synced,
  then unlinked. Android (no wallet removal) offers it as Settings →
  "Forget private data" (audit 4); iOS on forgetting the wallet.

## 4f. Wallet behaviors (audit 4)

- **Restore matching only on verified trees (M5).** Registration records
  are matched to identity leaves only after the same sync's root checks
  verified the identity tree; an unverified sync keeps the leaves for a
  later one. The identity record carries `verified` (matched on a verified
  tree, resolved from its own committed tx, or found live in one); a
  same-chain reset keeps only a verified one (an older store's is dropped
  and found again from its record note). A match at the identity's own
  index replaces it.
- **claimOpensAt** and every time sum are checked (no wrap, no trap): an
  activated_at with no answer gives none.
- **Automation.** Errors (not only exceptions) fail the action, never the
  app. (The claim offset went with the automatic claim in round 5.)
- **Gas grant proof of work.** The wallet works for at most 24 bits; a
  server asking more is refused (iOS: the work stops when the request is
  cancelled).
- **Fees.** The registration's fee is bounded by its confirm sheet's like
  every private tx (a higher one re-shows the sheet). A stake undelegation
  that needs its notes merged first merges at most twice per confirmation,
  15-45 s apart and before the action.
- **Logs.** Proof timings are logged in debug builds only.
- **SRS.** The bundled privacy SRS (srs/bn254_g1_32769.dat, 32,769 points)
  is checked by SHA-256 (d769ac6c…e99c) before use on both platforms; iOS
  no longer downloads a privacy SRS when the bundled one is missing (a
  private proof fails with a clear error instead). The passport SRS
  prefetch (iOS) is streamed to a staged file, hashed as it comes and cut
  off at the range's 524,289 × 64 bytes; no redirect is followed.
- **Secrets (iOS).** Every SecRandomCopyBytes status is checked; the
  phrase's bytes and the BIP-39 seed are zeroed once the keys are derived
  (the phrase as a String cannot be).
- **Vote run session (iOS, M4).** A lock, wallet switch or forget that
  lands while a stake vote is starting or resuming stops it (a session
  counter checked under the controller's lock before launch and at every
  step); a suspended run reports no more progress.
- **A store from before K6** (same chain id, no genesis recorded) keeps its
  identity record when the genesis is first recorded (as a confirmed
  switch: synced data goes, the registration stays).
- **Untrusted numbers** from the LCD or indexer (tree sizes, params,
  durations, epochs, a stake snapshot ahead of the local tree) are parsed
  bounded and refused, never trapped on or wrapped: a snapshot past the
  local stake tree is "sync first".

## 5. Off-device parity

`WalletFlowTest` drives two wallets against an in-memory chain (bundles with
real binding-signature checks, the stake tree, the fced976 fee and
ciphertext rules); with `PRIVACY_TOML_OUT=<dir>` it writes every witness as
`<dir>/{action,stake,membership,vote}/<test>_<i>/Prover.toml`. `nargo execute` on
circuits/action, circuits/stake, circuits/membership and circuits/vote accepts
all of them. `StakeVoteFlowTest` ports the chain's TestStakeVoteConcurrentProposals
(one note on two open proposals, a second vote refused locally and by the
chain for a restored wallet, a note spent before the snapshot refused
locally, one restaked after it still voting while its outputs cannot), the
LCD fallback, a forged nullifier stream and the weight rule; one vote
witness was proven with bb v5.0.0 and verified against the chain's vote VK.
`HandlesTest` (round 5) drives handles (claim, taken, renew, change, release,
lapse), paying a handle from the whole directory (a forged indexer entry
refused), a registration referred by a handle (the referral note found by
the referrer), a switch that moves the handle and caretaker vote, and every
predecessor bound; its membership witnesses (switched identities, no-bound
inputs) pass `nargo execute` with the rest (231 witnesses).
`Fix6Test` / `Fix6Tests` (chain 203d3b2) drive the open referral note (found
by owner_pk and cm, a forged cm refused, spent), notes format 2 (by name,
format 1 and partial openings refused), a split payout's shared ciphertext
at three positions, a handle in its renewal period and a lapsed split
(bounded or refused locally, nothing sent), lease bounds after a lease cut,
inconsistent lease bounds, an anchor about to lapse, the rounded-up fee and
the new errors. At 203d3b2 every witness of the Android suite (440) and of
the iOS suite (416) passes `nargo execute`.
