# Earth Wallet — Architecture

A native Android wallet for the **earth** chain: a transparent Cosmos SDK chain with
native bank denoms, native staking, and custom modules — `x/dex` (a
spoke-and-wheel AMM hubbed on ERTH), `x/allocation` (both vote-directed emission
streams over one engine: the stake-weighted `capital` stream = the Deflation
Fund, and the one-human-one-vote `human` stream = the Caretaker Fund), and
`x/personhood` (proof-of-personhood registration and ANML, which gates who may
vote in the `human` stream).
There are **no** SNIP-20 tokens,
viewing keys, permits, or CosmWasm contract queries — every asset is a bank denom
and every read is a plain LCD/REST query.

## Layers

```
ui/pages/**            Fragments (screens). No chain/crypto logic beyond calling chain/**.
        │
chain/**               Typed service layer over the earth LCD (the only place that
        │              builds tx messages / parses chain JSON).
        │
wallet/services/**     Key management + signing (TransactionSigner, SecureWalletManager,
        │              EarthWallet, WalletCrypto) and app services (session, prices, contacts).
        │
app/src/main/proto/**  Clean proto3 message defs (javalite) for the tx messages we sign.
```

### `chain/` — the service layer (start here)

| File | Responsibility |
|------|----------------|
| `EarthRest.kt` | `get(path)` / `postJson(path, body)` over `Constants.EARTH_LCD_URL` (HttpURLConnection). |
| `EarthTx.kt` | Generic tx pipeline: account lookup → `TxBody`/`AuthInfo`/`SignDoc` → sign (`TransactionSigner`) → POST `TxRaw`. `broadcast(key, msgs)` + `anyOf(typeUrl, msg)`. |
| `Bank.kt` | `balances` / `balance` / `supply`; `msgSend`. |
| `Dex.kt` | `pools` / `poolForToken` / `swapFeePercent`; `msgSwap`, `msgAddLiquidity`, `msgRemoveLiquidity`. |
| `Allocation.kt` | Both allocation streams: `allocationOptions` / `voterAllocations`; `msgSetAllocations`, `msgClaimAllocation`. Every call takes a `StreamId` — `STREAM_ID_HUMAN` (Caretaker Fund) or `STREAM_ID_CAPITAL` (Deflation Fund). Ids and totals are per stream. |
| `Staking.kt` | `bondedValidators` / `delegations` / `totalBonded` / `totalRewards` / `unbondingDelegations`; `msgDelegate`, `msgUndelegate`, `msgWithdrawReward`. |
| `Personhood.kt` | Registration + ANML: `isRegistered`, `registrationStatus`, `register`, `claimAnml`, `registrationCount` / `registrationCountries` / `registrationsByDsc`. The Caretaker Fund's votes live in `Allocation.kt`. |

**The one pattern every write follows** (query on `Dispatchers.IO`, sign inside the
session-scoped mnemonic block):

```kotlin
val txHash = withContext(Dispatchers.IO) {
    SecureWalletManager.executeWithMnemonic(requireContext()) { mnemonic ->
        val key = EarthWallet.deriveKey(mnemonic)
        val creator = EarthWallet.address(key)
        EarthTx.broadcast(key, listOf(Dex.msgSwap(creator, tokenInDenom, amtIn, denomOut, minOut)))
    }
}
```

Reads are just `chain/**` query calls (also on `Dispatchers.IO`). Fragments never
build protobufs or parse chain JSON directly — add new chain interactions to
`chain/**`, not to a screen.

### Wallet / keys

- BIP-44 coin type **118**, bech32 prefix **`earth`** (`WalletCrypto`).
- `SecureWalletManager.executeWithMnemonic` decrypts the mnemonic from the active
  session only for the duration of a signing block.
- `TransactionSigner` signs a `SignDoc` with secp256k1 (SIGN_MODE_DIRECT) and
  assembles the `TxRaw`.

### Protos

Hand-written, javalite-clean proto3 mirrors of the messages we sign live under
`app/src/main/proto/` (`earth/dex`, `earth/democracy`, `cosmos/bank|staking|distribution|base`).
They are wire-compatible with the chain; regenerate/extend here when adding a message.

## Screen map

- **wallet**: balances (`Bank.balances`), send (`Bank.msgSend`), receive, contacts, settings.
- **anml**: passport scan/NFC → zk proof (`anmlprover.aar`) → `Personhood.register`; claim → `Personhood.claimAnml`.
- **swap**: `Dex` pool-reserve pricing + `Dex.msgSwap`.
- **managelp**: pool overview + Info/Add/Remove tabs → `Dex` add/remove liquidity. LP shares are the bank denom `dexlp/{poolId}`; rewards auto-compound (no bonding, no manual claim).
- **staking**: Rewards/Stake/Withdraw/Unbonding → native `x/staking` + `x/distribution`.
- **governance**: two funds, each an Actual/Preferred allocation viewer + editor. Both go through `Allocation.allocationOptions` / `voterAllocations` / `msgSetAllocations`, differing only in the `StreamId` passed: **Caretaker Fund** = `STREAM_ID_HUMAN` (one-human-one-vote), **Deflation Fund** = `STREAM_ID_CAPITAL` (stake-weighted).

## Configuration

`Constants.kt`:
- `EARTH_LCD_URL` — LCD/REST base. Defaults to `http://10.0.2.2:1317` (Android emulator → host running `ignite chain serve`). Point at your node/LAN IP for a device.
- `EARTH_CHAIN_ID`, `EARTH_PREFIX`, `EARTH_COIN_TYPE`, `UERTH_DENOM`.
- `BACKEND_BASE_URL` — passport proof verification + app update metadata only (not chain state).

## Build

```
JAVA_HOME="<Android Studio JBR / JDK 21>" ./gradlew :app:assembleDebug
```
