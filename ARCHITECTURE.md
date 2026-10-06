# Earth Wallet: Android architecture

A native Android wallet for **earth-1**, a Cosmos SDK chain with native bank
denoms, native staking, and custom modules:

- `x/dex`: hub-and-spoke AMM. Every pool pairs ERTH with one token.
- `x/allocation`: vote-directed emission streams. The stake-weighted
  `groundworks` stream is the Deflation Fund; the one-human-one-vote
  `caretaker` stream is the Caretaker Fund.
- `x/personhood`: passport proof-of-personhood registration and ANML.
- `x/assembly`: the human chamber of bicameral governance.
- `x/shielded`, `x/shieldedstaking`: the note pool and stake notes.

There are no CosmWasm or SNIP-20 tokens in the wallet's path: every asset is a
bank denom. ERTH and ANML are held as shielded notes (x/shielded, Orchard-style
bundles) and delegated stake as stake notes (x/shieldedstaking); allocations
stay public. Private transactions are unsigned and proven on the phone; their
formats are in [PRIVACY_FORMATS.md](PRIVACY_FORMATS.md).

## Layers

All code is under `app/src/main/java/network/erth/wallet/`.

```
ui/**            Compose screens and view models. No protobufs, no chain JSON.
   │
ui/tx/           TxController: the one confirm → broadcast → result path.
   │
chain/**         Typed clients over the LCD. The only place that builds tx
   │             messages or parses chain responses. chain/math/ mirrors the
   │             chain's APR and AMM maths exactly.
   │
crypto/          Key derivation (BIP-39/44, coin type 118), Bech32, SIGN_MODE_DIRECT.
wallet/          Encrypted wallet storage, session, PIN/biometric unlock, attestation.
privacy/         The shielded wallet: keys, notes, sync, proving, private txs.
passport/        NFC read (JMRTD) and on-device Noir proving.
backend/         The registration gas grant from api.erth.network (proof-backed).
referral/        Referrer handle capture from the App Link and the Play install referrer.
```

Protobuf definitions for the messages the wallet sends are in
`app/src/main/proto/` (cosmos bank/tx and gov's vote options; earth
dex/allocation/personhood/assembly/shielded/shieldedstaking), compiled to
javalite.

## chain/

| File | Responsibility |
|------|----------------|
| `EarthRest.kt` | `get` / `postJson` over the LCD, `getRpc` for CometBFT RPC, to the node `NodeConfig` names. |
| `NodeConfig.kt` | The node in use: Earth's, or the user's own (Settings → Network), checked to be the live earth-1 (chain id, block 1 hash, a recent tip) before it is saved. https only, except the user's own node at a local address. |
| `EarthTx.kt` | Account lookup → `TxBody` / `AuthInfo` / `SignDoc` → sign → broadcast `TxRaw`. |
| `Fees.kt` | Gas price and fee for a gas limit. |
| `Bank.kt` | Balances, supply, `msgSend`. |
| `Dex.kt` | Pools, LP unbondings, swap fee and simulation; transparent liquidity messages. |
| `Staking.kt` | Bonded validators (explorer), network bonded total, and a validator's own transparent self-bond (delegations, unbondings, pending rewards). Staking itself is private. |
| `Allocation.kt` | Both allocation streams, selected by `StreamId` (read only). |
| `Personhood.kt` | Network-wide registration count. Everything per-person is private (`privacy/`). |
| `Gov.kt`, `Assembly.kt` | Proposals and tallies in both chambers. Votes are private msgs. |
| `ChainErrors.kt` | Chain error codes explained in plain language. |
| `Explorer.kt` | Blocks and transactions for the explorer and the activity list. |
| `math/PoolApr.kt`, `math/SwapQuote.kt` | Must match `x/dex` and the chain's reward maths. |

## privacy/

| Package | Responsibility |
|---------|----------------|
| `PrivacyWallet.kt` | Every private action: send, swap, liquidity, stake, unstake, move, votes, registration, claims, handles, and the handle and caretaker moves a switch's old identity makes to its successor (move proofs). |
| `PrivacySession.kt` | The selected wallet's `PrivacyWallet`, built from its mnemonic and kept for the session; another wallet's on demand (a switch's predecessor), and the move offer it finds by the succession leaf. |
| `Reminders.kt` | Recurring actions (ANML claim, caretaker refresh, handle renewal) are reminded, never run unasked. |
| `keys/` | Shielded key derivation and the shielded address. |
| `note/` | Note plaintexts and their ciphertexts. |
| `zk/` | Field, Poseidon2, Grumpkin, Merkle, indexed and debt trees; the chain's derivations. |
| `sync/` | Indexer client, local store, wallet sync, restore, root verification against the LCD. |
| `tx/` | Bundle and stake planning, private msg encoding, the unsigned tx and its engine. |
| `prove/` | Witnesses and the on-device prover for action, stake, vote, membership and move. |
| `chain/`, `handles/` | Private-chain queries and the chain roots sync checks against; handles and their directory. |

## Every write goes through TxController

A screen raises intent, and its view model turns that into messages and passes
them to `TxController`. The confirmation and result sheets are driven by that
state, so a caller cannot skip them. A private action goes through
`requestPrivate`: it is proven and broadcast by `PrivacyWallet`, its fee paid
from shielded ERTH and capped at what the sheet showed. A transparent one is
signed inside the session-scoped mnemonic block:

```kotlin
SecureWalletManager.executeWithMnemonic(context) { mnemonic ->
    val key = EarthWallet.deriveKey(mnemonic)
    EarthTx.broadcast(key, msgs, gasLimit, feeUerth)
}
```

## ui/

| Package | Contents |
|---------|----------|
| `ui/` | `UpdateCheckActivity` (launcher), `MainActivity`, `EarthApp` (root composable). |
| `navigation/` | Routes, tab bar, top bars. Tabs are Wallet, Earn, Swap, Govern. |
| `home/`, `wallet/` | Home, balances, send, receive, activity, wallet management. |
| `earn/`, `swap/` | Staking; swapping and liquidity. |
| `govern/`, `personhood/`, `explore/` | Proposals and allocations, registration status, the explorer. |
| `onboarding/`, `unlock/`, `settings/` | First wallet, PIN and biometric unlock, settings. |
| `registration/` | The passport flow: MRZ camera → confirm → NFC → prove → register. |
| `privacy/` | Shielded balances and actions, handle screens, the identity switch and the post-switch move offer. |
| `tx/` | Confirm, pending and result sheets, and `TxController`. |
| `components/`, `theme/` | Shared Earth composables and the Earth theme. |
| `designsystem/` | Vendored Zodl design library; see [LICENSES.md](LICENSES.md). |

## Configuration

Endpoints, chain id and denoms are in `Constants.kt`. The LCD
`https://lcd.erth.network` and its RPC are defaults: Settings → Network
(`chain/NodeConfig.kt`, iOS `NodeSettings.swift`) points an install at the
user's own node, and every chain query and broadcast then goes there. That,
with a no-logs policy on Earth's node, is the answer to a node operator
seeing the wallet's transparent-address reads beside its private broadcasts
(PRIVACY_FORMATS §18). The backend (`https://api.erth.network`: privacy
indexer, handle directory, gas grant, fetched circuits) is not configurable.
Cleartext is refused in code (EarthRest), not by the network security
config, which cannot name a LAN range: http:// is allowed only to the user's
own node at a loopback or private address.
