# Privacy formats the wallet defines

The privacy chain (x/shielded, x/personhood, x/assembly, x/shieldedstaking)
pins every hash, tag, signal and proof layout in `zk/privacy`, and the
Android code reproduces those byte for byte (`android/.../privacy/zk`, tested
against vectors generated from the chain by `tools/privacyvectors/gen.sh`).

What the chain never sees, and so does not pin, is defined here. Every item
is implemented in `android/app/src/main/java/network/erth/wallet/privacy/`
and pinned by a golden test; the iOS port and the web app must match.

## 1. Keys (wallet-only)

From the BIP-39 seed (empty passphrase), BIP-32 hardened derivation:

    m/2026'/118'/0'/0'   id_secret
    m/2026'/118'/0'/1'   nk
    m/2026'/118'/0'/2'   ek (x25519)
    m/2026'/118'/1'/i'   Groundworks position key i (secp256k1, the child key itself)

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

Pinned in `KeysAndNotesTest` (cross-checked with an independent Python
derivation) for the mnemonic `abandon ×11 about`:

    id_secret 059b96926ae7a563f2ddeb6fe425a6ccdd1c267f1d06d92457f1b6dba741fcba
    nk        0a67906d75dbdf06237678494da622b51aab4bcefea0134f5ead80d7c9440b81
    ek_pub    c6327c6004804dce1fd6a876c9c983204cb251507a5da8ae845e52cde8585773

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
to itself (MsgRegister's pc_erth, MsgDelegate/MsgUndelegate/
MsgClaimUnbonding pcs, the /gas/register pc_gas, a note swap's output, a
shielded deposit's refunds, a pool-1 withdrawal's ANML leg) names a
self-mint pc (§1) and leaves the ciphertext empty; sync recognises it by
its public mint amount and the self-mint pcs counter 0 … last+20. A
shielded deposit's ERTH and token refunds share one self-mint pc (two
notes, different assets). Recovery needs only the mnemonic. Notes of known
value (claims, sends, change, stake-vote re-mints, position unlocks, the
registration's ANML) carry the v1 ciphertext.

## 4. Private tx assembly (follows x/shielded/ante)

- TxRaw with one Any, AuthInfo with no signer infos, fee = exactly the msg's
  total fee in uerth (every transfer's fee + fee_from_output), no payer or
  granter, no signatures.
- Fee = max(x/shielded min_fee, ceil(node min gas price × gas limit)); gas
  limit = simulated gas + max(10%, 20,000). Simulation runs on the real
  roots, nullifiers, commitments and ciphertexts with 14,656-byte
  placeholder proofs (the ante charges proof gas but skips verification in
  simulate mode); the tx is re-laid at the final fee (the fee note's change
  changes), then the signal is computed and the proofs made over it.
- Transfer layout: slots 0–1 the hidden asset (dummies of value 0 with a fresh
  rho where unused), slot 2 ERTH paying the fee (dummy when the msg pays fee
  from output or the transfer pays none). Change goes to the first free
  slot. Fee note = the smallest ERTH note covering the fee; inputs = the
  smallest single note covering the amount, else the smallest sufficient
  pair.

## 5. Off-device parity

`WalletFlowTest` drives the wallet against an in-memory chain; with
`PRIVACY_TOML_OUT=<dir>` it writes every witness as a nargo Prover.toml.
`nargo execute` on the real circuits accepts all of them, and
`bb prove`/`bb verify -t noir-recursive` against the chain's verifying keys
(zk/ultrahonk/testdata) succeed.
