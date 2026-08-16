# Earth-chain registration (no backend)

Registration no longer POSTs the passport to `api.erth.network/verify`. It now proves
proof-of-personhood **on-device** and submits `MsgRegister` **directly to the earth chain**.

## Flow
1. `PassportScannerFragment` reads DG1/SOD from the passport chip over NFC (unchanged).
2. `PassportProver.prove(dg1)` → the bundled gnark prover (`app/libs/anmlprover.aar`,
   `network.erth.anmlprover.Anmlprover`) returns `{proof, public_witness, nullifier}`.
3. `EarthWallet.deriveKey(mnemonic)` derives the `earth1…` key (BIP-44 coin type **118**,
   distinct from the Secret coin type 529).
4. `EarthClient.broadcastRegister(...)` builds `MsgRegister` (cosmos protobuf), signs it with the
   existing `TransactionSigner` (secp256k1, SIGN_MODE_DIRECT), and POSTs the `TxRaw` to
   `EARTH_LCD_URL/cosmos/tx/v1beta1/txs`.

New/changed files:
- `Constants.kt` — `EARTH_LCD_URL`, `EARTH_CHAIN_ID`, `EARTH_PREFIX`, `EARTH_COIN_TYPE`,
  `UERTH_DENOM`, `MSG_REGISTER_TYPE_URL`.
- `wallet/services/EarthWallet.kt`, `wallet/services/EarthClient.kt`,
  `ui/pages/anml/PassportProver.kt`.
- `src/main/proto/earth/democracy/v1/register.proto` (clean proto3 `MsgRegister`).
- `ui/pages/anml/PassportScannerFragment.kt` — the `sendToBackend(...)` call is replaced by the
  prove + broadcast path.

## Rebuilding the prover AAR
The AAR is produced from the chain repo's `mobile/anmlprover` package (gnark BN254 Groth16):
```
# one-time: gomobile + NDK
go install golang.org/x/mobile/cmd/gomobile@latest
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "ndk;29.0.14206865"

# build the AAR into this app
cd ../earth-network-chain
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/29.0.14206865"
gomobile bind -target=android -androidapi 24 -javapkg network.erth \
  -o ../earth-network-mobile/app/libs/anmlprover.aar ./mobile/anmlprover
```
The embedded demo proving key matches the demo verifying key seeded in the chain's genesis
(`config.yml` → `app_state.democracy.params.verifying_key`). Swap in the real passport circuit's
keys later — the `prove(dg1)` API and on-chain verifier are unchanged.

## Run
- Start a node: `PATH="$HOME/.local/go-shim:$PATH" ignite chain serve` (LCD on :1317).
- Point `Constants.EARTH_LCD_URL` at it (`http://10.0.2.2:1317` from the Android emulator).
- Fund the derived `earth1…` address with `uerth`, then scan a passport. Verify:
  `earthd q democracy registration <earth1…>` → `registered: true`; re-scanning the same passport is
  rejected (nullifier dedup).

## TODO (later cleanup)
- Pass the affiliate/referrer address from the register screen into `broadcastRegister` (currently
  `null` → registree gets 100% of the 10 bps payout).
- Remove the now-dead backend code (`sendToBackend`, `backendUrl`, `parseAndAttachVerification`).
- Migrate claim / dex / staking screens to the earth chain and retire SecretK.
