# CLAUDE.md

Guidance for Claude Code (claude.ai/code) working in this repository.

## What this is

Earth Wallet: a self-custody wallet for **earth-1**, a sovereign Cosmos SDK
chain built on proof-of-personhood. A user proves they are a unique human by
reading their ePassport's NFC chip and generating a zero-knowledge proof
**on device**, which the chain verifies. No custodian, no server sees the
passport. Registered humans claim a daily ANML token and direct the chain's
emissions by vote. Balances, stake and votes are private: ERTH and ANML are
shielded notes, stake is stake notes, and every private tx is proven on the
phone (formats: `PRIVACY_FORMATS.md`; audit rounds: `AUDIT_HISTORY.md`).

Android is the behavioural reference; iOS is a port of it with the same
privacy core, checked against the same vectors.

## Layout

    android/    the shipping app — Kotlin + Jetpack Compose
    ios/        the port — see ios/README.md
    circuits/   Noir circuits (nargo workspace): the passport register-circuit
                variants and the privacy circuits (action, stake, vote, membership, move)
    tools/      chainverify (proof checking), keycheck + txcheck + certcheck
                (Go ground truth for the iOS port), privacyvectors (golden
                vectors from the chain's own code)
                genesishash (the genesis sha256 the own-node check pins: set at the
                ceremony from its genesis.json)

Gradle lives in `android/`, so **every `./gradlew` command runs from there**.

## Commands

    cd android
    ./gradlew :app:assembleDebug          # debug APK
    ./gradlew :app:assembleRelease        # release APK (needs keystore.properties)
    ./gradlew :app:bundleRelease          # AAB for Play
    ./gradlew lint                        # configured not to abort on errors
    ./gradlew clean

JVM unit tests (`./gradlew :app:testDebugUnitTest`) cover the gas-grant
request body and the privacy core (`privacy/`): golden vectors from the
chain's own Go code (`tools/privacyvectors/gen.sh <chain checkout> [ref]`,
which writes the Android copy and the identical iOS copy), the
chain's witness fixtures, every private msg's encoding and signal, and an
end-to-end wallet flow against an in-memory chain. The formats the chain
does not pin (keys, shielded address, note ciphertext) are in
`PRIVACY_FORMATS.md`. The prover tests are instrumented and need a real
device (the prover does not run on an emulator usefully):

    ./gradlew :app:connectedDebugAndroidTest \
      -Pandroid.testInstrumentationRunnerArguments.class=network.erth.wallet.LeanPoaDeviceTest

`LeanPoaDeviceTest` proves the real P-256 and RSA-2048 register circuits on the phone and writes
the proof + VK to external files storage so the chain verifier can be run
against genuine device output. `NoirDeviceTest` is the same idea on a toy
circuit.

For iOS commands see `ios/README.md`. The short version:

    cd ios/EarthCore  && swift test                  # privacy core, shared vectors
    cd ios/EarthCore  && swift run corecheck         # domain layer + passport
    cd tools/txcheck   && go run . ../../ios/EarthCore/.artifacts
    cd ios/ProverGate  && swift run progate --witness ../EarthCore/.artifacts/passport_witness.json

## Chain facts

    chain id     earth-1
    LCD          https://lcd.erth.network
    RPC          https://rpc.erth.network
    backend      https://api.erth.network
    bech32       earth
    coin type    118          (Cosmos default — NOT a custom type)
    denoms       uerth (gas/staking), uanml (personhood), both 6dp
    register     /earth.personhood.v1.MsgRegister

Canonical source is `android/.../Constants.kt` — read it rather than copying
these. Custom chain modules: `x/dex` (hub-and-spoke AMM, every pool pairs ERTH
with one token), `x/personhood`, `x/allocation`, `x/assembly` (the human
chamber), `x/shielded` (the note pool), `x/shieldedstaking` (stake notes),
`x/earth`, `x/pki` (the CSCA trust store).

## Android layout

Source is under `app/src/main/java/network/erth/wallet/`, package
`network.erth.wallet.*`.

    chain/              typed clients per module (Dex, Personhood, Staking, …)
    chain/math/         APR and AMM maths that mirror the chain
    backend/            the gas-grant client (api.erth.network)
    crypto/             BIP-39/44 keys, Bech32, signing
    wallet/             key storage, session, unlock (PIN, biometric), attestation
    passport/           headless passport read + proof generation
    privacy/            the shielded wallet: keys, notes, sync, proving, private txs
    referral/           referrer handle from the App Link and the install referrer
    ui/                 MainActivity, UpdateCheckActivity (launcher), EarthApp root
    ui/<feature>/       home, wallet, earn, swap, govern, explore, personhood,
                        privacy, settings, onboarding, unlock, registration
    ui/tx/              the one confirm → broadcast → result path
    ui/navigation/      routes, tab bar, top bars
    ui/components/      shared Earth composables
    ui/theme/           EarthTheme, dimensions, accent
    ui/designsystem/    vendored Zodl design library (see ../LICENSES.md)
    app/src/main/proto/ cosmos + earth protobuf definitions

`UpdateCheckActivity` is the launcher. Keep its class name and package: a
launcher shortcut on a user's home screen points at that component.

Two files encode chain maths that **must** match the chain exactly:
`chain/math/PoolApr.kt` and `chain/math/SwapQuote.kt` (mirrors
`x/dex/keeper/amm.go`). Changing either without checking the chain is a bug.

## Things that will bite you

**Barretenberg version lockstep.** On-device proofs verify on-chain only when
the prover's bb matches the chain verifier's. All sides are pinned to bb
**v5.0.0 final**: Android via `com.github.madztheo:noir_android:1.0.0-beta.22-2`,
iOS via Swoirenberg, the chain via `aztec_tag: v5.0.0` in
`earth-network-chain/third_party/barretenberg-go/checksums.json`. A *nightly*
bb changes the Fiat-Shamir transcript and the v5.0.0 verifier rejects its
proofs on large circuits. Never float these pins, and never move the chain to
a nightly to compensate — that breaks the shipping app.

**BouncyCastle is delicately balanced.** JMRTD and the crypto stack disagree
about versions; `app/build.gradle` carries deliberate excludes and pins.
Changing a crypto or passport dependency usually means re-doing that work.

**Compiled circuits are checked in** at `app/src/main/assets/circuits/*.json`
(the 16 passport register-circuit variants of the 2^18 tier, ~16MB, stripped
to bytecode and ABI, plus action, stake, vote, membership and move; the other 17
variants are fetched on demand from the backend, pinned by sha256 in
`passport_variants.json`). The passport variants are generated:
`circuits/tools/variants.py gen` writes their Noir mains and shared fixtures
from `circuits/variants.json`, `build` compiles and places them. Their bytecode fixes the VKs the chain's genesis pins, so
they are never regenerated casually: a comment-only change to `circuits/`
must recompile to identical bytecode and ABI (the `hash`, `debug_symbols` and
`file_map` fields follow the source text and paths). They carry a `noir_version` that
must match the prover's Noir. Recompiling means `nargo` at that version.

**Where a comment explains something surprising, read it before changing the
approach.** This codebase has few comments and the ones present are load-bearing.

## Conventions

- Kotlin, Compose, JVM 17, minSdk 24 / targetSdk 36.
- Protobuf-lite (`protobuf-javalite`) for Cosmos SDK transaction encoding,
  SIGN_MODE_DIRECT.
- Match the surrounding code's comment density and naming; explain *why*, not
  *what*.
