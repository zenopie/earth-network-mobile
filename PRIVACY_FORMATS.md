# Privacy formats the wallet defines

The privacy chain (x/shielded, x/personhood, x/assembly, x/shieldedstaking,
x/dex) pins every hash, tag, sighash, bundle and proof layout in `zk/privacy`
and `zk/orchard` (Orchard-style bundles: ORCHARD_DESIGN.md sections 12-13 of
the chain), and the Android code reproduces those byte for byte
(`android/.../privacy/zk`, `privacy/tx`, tested against vectors generated from
the chain by `android/tools/orchardvectors/gen.sh <chain checkout> [ref]`,
last run against chain privacy/orchard d083cc5; `tools/privacyvectors` is
the retired transfer-circuit generator).

What the chain never sees, and so does not pin, is defined here. Every item
is implemented in `android/app/src/main/java/network/erth/wallet/privacy/`
and pinned by a golden test; the iOS port and the web app must match.

## 1. Keys (wallet-only)

From the BIP-39 seed (empty passphrase), BIP-32 hardened derivation:

    m/2026'/118'/0'/0'   id_secret
    m/2026'/118'/0'/1'   nk
    m/2026'/118'/0'/2'   ek (x25519)

(The retired `m/2026'/118'/1'/i'` Groundworks position keys are gone:
positions are owned by owner tags, below.)

For each of the first three, with k the child's 32-byte private key:

    s = HMAC-SHA512(key = "earth.privacy.v1", data = label || k)
    id_secret = s mod p      (label "id_secret")
    nk        = s mod p      (label "nk")
    ek        = s[0..32]     (label "ek"; X25519 clamps it)

p is the BN254 scalar modulus. `idc = H(TAG_ID, id_secret)` and
`owner_pk = H(TAG_OWNER, nk)` are the chain's.

Self-mint secrets (notes the chain mints at a value the wallet cannot know
when naming the pc: the registration reward, derth at the live rate, an
unbonding payout, the gas grant), counter c a u32:

    rho_c = HMAC-SHA512("earth.privacy.v1", "mint-rho" || nk (32 BE) || c (u32 BE)) mod p
    rcm_c = HMAC-SHA512("earth.privacy.v1", "mint-rcm" || nk (32 BE) || c (u32 BE)) mod p
    pc_c  = H(TAG_PC, owner_pk, rho_c, rcm_c)

Stake self-mints (stake notes x/shieldedstaking mints to a stake pc the
wallet names as a stake proof's `spc_mint`: a delegation's derth, an
undelegation's claim, a stake vote's re-mint, an unlocked position), counter
c a u32, a counter space of its own:

    srho_c = HMAC-SHA512("earth.privacy.v1", "stake-rho" || nk (32 BE) || c (u32 BE)) mod p
    srcm_c = HMAC-SHA512("earth.privacy.v1", "stake-rcm" || nk (32 BE) || c (u32 BE)) mod p
    spc_c  = H(TAG_SPC, owner_pk, srho_c, srcm_c)

Groundworks owner tags (a position stores `otag`; its owner proves it again
to update, unlock or vote it), counter c a u32:

    salt_c = HMAC-SHA512("earth.privacy.v1", "otag-salt" || nk (32 BE) || c (u32 BE)) mod p
    otag_c = H(TAG_OTAG, owner_pk, salt_c)

Sync tries counters 0 … last+20 for every family (self-mint pcs against
pool mints, stake pcs against stake mints, owner tags against the public
positions), so a wallet restored from the mnemonic finds everything. A stake
proof whose msg mints nothing or stores no tag (restake, update, vote) uses
fresh random secrets for `spc_mint` / `otag` so the public values link to
nothing.

Pinned in `KeysAndNotesTest` (cross-checked with an independent Python
derivation) for the mnemonic `abandon ×11 about`:

    id_secret 059b96926ae7a563f2ddeb6fe425a6ccdd1c267f1d06d92457f1b6dba741fcba
    nk        0a67906d75dbdf06237678494da622b51aab4bcefea0134f5ead80d7c9440b81
    ek_pub    c6327c6004804dce1fd6a876c9c983204cb251507a5da8ae845e52cde8585773
    srho_0    2e169030a56d7e472fc9f342ef18783bc65662f82edf58e00b9fa23b7131fbb3
    srcm_0    16443a6dc3a8058eaafa1b6feb6d1804cf71794815a830e782756c2ccf759bab
    srho_1    05708bcf1c37660a1859a735e21a57c9d802f78ab8274364eb9199583b095c32
    srcm_1    2e4cb7ef401c2041af61f2e4a7593f5afed0d85ab28cefc6cde17aa9c28b1b22
    salt_0    0685f54037389aaceee42288ed8c8c996a884e297ffee771c73370ca885e1618
    salt_1    2e52e73b7af259664a34df8bcee1c0476009a37e0ab2e52b285bae497845a9ba

## 2. Shielded address (canonical; also in chain zk/privacy and the web app)

    bech32m( hrp "erthz", 8→5 bits of: 0x01 || owner_pk (32, BE, < p) || ek_pub (32) )

65 payload bytes, 116 characters; BIP-173's 90-character cap is not applied
(as with Zcash unified addresses). Decoders refuse other hrps, versions,
lengths and a non-canonical owner_pk. Golden (the mnemonic above):

    erthz1qyh7prm54w0lu9ymzm3dtpm3r3juewetjuu8675hw0gpu5hywx4ll33j03sqfqzdec0ad2rke8ycxgzvkfg4q7ja4zhgghjjeh59s4mn9gwhg2

## 3. Note ciphertext (canonical)

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

**Value-blind ciphertext (v2, canonical; chain zk/privacy notecipher.go).**
For a note whose asset and value the chain decides when the msg runs (a
swap's output, an LP withdrawal priced at maturity), paid to *another*
address:

    ct  = epk (32) || ChaCha20-Poly1305(key, nonce 0^12, aad empty, pt)      177 bytes
    key = HKDF-SHA256(ikm = X25519(esk, ek_pub), salt "earth.note.v2", info = epk)
    pt  = 0x02 || rho (32) || rcm (32) || memo (64, zero padded)            129 bytes

The length tells v1 (217) from v2 (177). info cannot bind cm, so the
binding is the recipient's check: it opens ct and accepts only if
`CM(AssetID(denom), value, PC(owner_pk, rho, rcm)) == cm` with the denom and
value the chain published for that position (the `shielded_mint` event's
`amount`, which the indexer serves on the note row; every MintNote emits
it, EndBlock payouts included). Golden (chain formats_test.go goldenKeys:
ek = 01..20, esk = 40..5f, owner_pk = OwnerPK(7), rho 11, rcm 13, memo
"golden memo"), pinned in `BlindNoteTest`:

    79a631eede1bf9c98f12032cdeadd0e7a079398fc786b88cc846ec89af85a51a8b8d4fe44e9fcb771cba93975cb4507ff1d20e44
    6a6a4cd8336f9a50186a7de58a5b4570c62bfd9cd347f5921103700103da6af3ce492bbd1f936a4310b3b01a1d583847125f76
    32547dfb2ea23c438f21cd4a419f9ef66d92660af42686e93c890bc37f68cf282f46ca2550ab2df0ce7191a11e7721ce736e0d
    1bdd62af8be221017ee455ab79e7b2ea0e756a86c39910

**Self-mints carry no ciphertext.** A chain-decided note the wallet pays
to itself (MsgRegister's pc_erth, MsgClaimUnbonding's pc, the /gas/register
pc_gas, a note swap's output, a shielded deposit's share note (`dexlp/<pool>`)
and refunds, a private withdrawal's two legs, a pool-1 MsgRemoveLiquidity's
ANML leg) names a self-mint pc (§1) and leaves the ciphertext empty; sync
recognises it by its public mint amount and the self-mint pcs counter
0 … last+20. A shielded deposit's ERTH and token refunds share one
self-mint pc (two notes, different assets). Recovery needs only the
mnemonic. Notes of known value (claims, sends, change, the registration's
ANML) carry the v1 ciphertext.

**Stake note ciphertext (wallet-defined, "earth stake note v1").** For a
stake note a stake proof creates (a restake's outputs, an undelegation's or
a lock's change). Stake notes are owner-locked, so it is always encrypted to
the wallet's own address:

    ct  = epk (32) || ChaCha20-Poly1305(key, nonce 0^12, aad empty, pt)      153 bytes
    key = HKDF-SHA256(ikm = X25519(esk, ek_pub), salt "earth.stake.v1", info = epk || cm)
    pt  = 0x03 || asset_id (32) || amount (u64 BE) || rho (32) || rcm (32)  105 bytes

accepted only if `H(TAG_STAKE, asset_id, amount, H(TAG_SPC, owner_pk, rho,
rcm)) == cm`. Stake notes the chain mints carry no ciphertext: the
indexer's stake stream gives their denom, amount and spc, matched against
the stake self-mint pcs (§1).

## 4. Private tx assembly (follows x/shielded/ante)

- TxRaw with one Any, AuthInfo with no signer infos, fee = exactly the msg's
  total fee in uerth (the bundles' fee + fee_from_output), no payer or
  granter, no signatures.
- Fee = max(x/shielded min_fee, ceil(node min gas price × gas limit)); gas
  limit = simulated gas + max(10%, 20,000). Simulation runs on the real
  anchors, nullifiers, commitments, value commitments and ciphertexts with
  14,656-byte placeholder proofs and a zero binding signature (the ante
  charges per bundle and per action, and skips verification in simulate
  mode). The tx is re-laid at the simulated fee; if that changes the action
  count it is simulated again (at most 4 rounds, the fee only rising after
  the first), then the sighash is computed and every action, the stake
  proof and the membership proven over it, every bundle signed.
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
  action's anchor is the wallet's current note root (dummies too). Every
  rcv is a fresh random field element; bsk = Σ rcv mod n; the binding
  signature's 32 random bytes are fresh. Balances are computed from the
  actions (spends − outputs per denom, positive only, sorted by denom). A
  layout above max_actions_per_bundle (x/shielded param, default 16) is
  refused ("merge first"); merging spends the smallest notes one bundle
  carries into one.
- Fee-only msgs (personhood, assembly, staking msgs other than delegate)
  carry a bundle whose only balance is the uerth fee. MsgSend's uerth
  balance is fee (+ an ERTH unshield); at Max an ERTH unshield releases
  exactly the notes' sum and the receiver gets it less the fee. MsgNoteSwap
  into uerth pays fee_from_output (the bundle releases only the asset in);
  any other swap pays from the bundle. MsgClaimUnbonding always pays
  fee_from_output and carries no bundle. MsgAddLiquidityShielded is one
  bundle (token leg + uerth leg + fee).
- **Stake proofs.** A staking msg's stake proof spends at most two stake
  notes of the msg's denom (the smallest single covering, else the
  smallest sufficient pair; a balance spread over more is merged first by
  MsgRestake, two into one) and creates at most one change note (two for a
  restake split) with the stake ciphertext above; unused input and output
  slots get random rho/rcm (amount 0: nf 0, cm 0) and a zero path. Anchor:
  the wallet's latest stake root (zero when the stake tree is empty; not
  checked when nothing is spent), for a stake vote the proposal's snapshot
  root, the paths taken against the tree at the snapshot's size.
  `spc_mint` is a stake self-mint (delegate, undelegate, stake vote,
  unlock) or random; `otag` is a position's owner tag (lock: a new counter;
  update/unlock/vote: the position's) or random. A stake vote spends one or
  two derth notes of one validator (weight = their sum); the wallet votes
  each validator's eligible notes two at a time.

## 4a. Indexer stake streams (backend /privacy, chain x/shieldedstaking events)

    GET /privacy/stake/notes?from_pos=&limit=          [position, height, cm, ciphertext, denom, amount, spc]
    GET /privacy/stake/nullifiers?from_height=&limit=  [[height, [nf, ...]], ...]
    GET /privacy/stake/roots?from_height=&limit=       [height, root, tree_size, time]
    GET /privacy/roots/latest                          {note, identity, stake: {root, tree_size, height, time}}

A minted stake note has denom, amount (decimal string) and spc (hex) and a
null ciphertext; a created one a base64 ciphertext and null denom, amount,
spc (events `shieldedstaking_stake_note`, `_stake_nullifier`,
`_stake_root`). The wallet rebuilds the stake tree from the full stream as
it does the note tree, and checks its root against `roots/latest.stake`.

## 5. Off-device parity

`WalletFlowTest` drives two wallets against an in-memory chain (bundles with
real binding-signature checks, the stake tree); with `PRIVACY_TOML_OUT=<dir>`
it writes every witness as `<dir>/{action,stake,membership}/<test>_<i>/
Prover.toml`. `nargo execute` on circuits/action, circuits/stake and
circuits/membership accepts all of them (111 at the last run), and
`bb prove`/`bb verify -t noir-recursive` of an action and a stake witness
against the chain's verifying keys (x/shielded/testdata/action.vk,
x/shieldedstaking/testdata/stake.vk) succeed.
