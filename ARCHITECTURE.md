# Earth Wallet: Android architecture

A native Android wallet for **earth-1**, a Cosmos SDK chain with native bank
denoms, native staking, and custom modules:

- `x/dex`: hub-and-spoke AMM. Every pool pairs ERTH with one token.
- `x/allocation`: vote-directed emission streams. The stake-weighted `capital`
  stream is the Deflation Fund; the one-human-one-vote `human` stream is the
  Caretaker Fund.
- `x/personhood`: passport proof-of-personhood registration and ANML.
- `x/assembly`: the human chamber of bicameral governance.

There are no CosmWasm or SNIP-20 tokens in the wallet's path: every asset is a
bank denom and every read is a plain LCD query.

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
passport/        NFC read (JMRTD) and on-device Noir proving.
backend/         Gas grants from api.erth.network, for accounts that cannot pay.
referral/        Referrer capture from deep links and the Play install referrer.
```

Protobuf definitions for the messages the wallet signs are in
`app/src/main/proto/` (cosmos bank/staking/distribution/gov/tx, earth
dex/allocation/personhood/assembly), compiled to javalite.

## chain/

| File | Responsibility |
|------|----------------|
| `EarthRest.kt` | `get` / `postJson` over the LCD, `getRpc` for CometBFT RPC. |
| `EarthTx.kt` | Account lookup → `TxBody` / `AuthInfo` / `SignDoc` → sign → broadcast `TxRaw`. |
| `Fees.kt` | Gas price and fee for a gas limit. |
| `Bank.kt` | Balances, supply, `msgSend`. |
| `Dex.kt` | Pools, LP unbondings, swap fee; swap and liquidity messages. |
| `Staking.kt` | Validators, delegations, unbondings, rewards; delegate, undelegate, withdraw. |
| `Allocation.kt` | Both allocation streams, selected by `StreamId`; `msgSetAllocations`. |
| `Personhood.kt` | Registration status and count, `register`, ANML claims. |
| `Gov.kt`, `Assembly.kt` | Proposals, tallies, and votes in both chambers. |
| `Explorer.kt` | Blocks, transactions and validators for the explorer screens. |
| `Tokens.kt` | Token registry: denom, decimals, symbol, logo. |
| `math/PoolApr.kt`, `math/SwapQuote.kt` | Must match `x/dex` and the chain's reward maths. |

## Every write goes through TxController

A screen raises intent, and its view model turns that into messages and passes
them to `TxController`. The confirmation and result sheets are driven by that
state, so a caller cannot skip them. The same path offers a gas grant to an
account that cannot pay. Signing happens inside the session-scoped mnemonic
block:

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
| `tx/` | Confirm, pending and result sheets, and `TxController`. |
| `components/`, `theme/` | Shared Earth composables and the Earth theme. |
| `designsystem/` | Vendored Zodl design library; see [LICENSES.md](LICENSES.md). |

## Configuration

Endpoints, chain id and denoms are in `Constants.kt`. The LCD is
`https://lcd.erth.network`; the file's comments explain how to point it at a
local chain.
