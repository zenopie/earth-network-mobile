# CLAUDE.md

Guidance for Claude Code (claude.ai/code) working in this repository.

## What this is

Earth Wallet: a self-custody wallet for **earth-1**, a sovereign Cosmos SDK
chain built on proof-of-personhood. A user proves they are a unique human by
reading their ePassport's NFC chip and generating a zero-knowledge proof
**on device**, which the chain verifies. No custodian, no server sees the
passport. Registered humans claim a daily ANML token and direct the chain's
emissions by vote.

Android ships; iOS is a port in progress.

## Layout

    android/    the shipping app — Kotlin + Jetpack Compose
    ios/        the port — see ios/README.md
    circuits/   Noir circuits (nargo workspace) for the personhood proof
    tools/      registry-builder (DSC trust store), chainverify (proof checking)

Gradle lives in `android/`, so **every `./gradlew` command runs from there**.

## Commands

    cd android
    ./gradlew :app:assembleDebug          # debug APK
    ./gradlew :app:assembleRelease        # release APK (needs keystore.properties)
    ./gradlew :app:bundleRelease          # AAB for Play
    ./gradlew lint                        # configured not to abort on errors
    ./gradlew clean

There are **no JVM unit tests** — `app/src/test` does not exist, so
`./gradlew test` does nothing. The only tests are instrumented and need a real
device (the prover does not run on an emulator usefully):

    ./gradlew :app:connectedDebugAndroidTest \
      -Pandroid.testInstrumentationRunnerArguments.class=network.erth.wallet.LeanPoaDeviceTest

`LeanPoaDeviceTest` proves the real ~130k-gate circuit on the phone and writes
the proof + VK to external files storage so the chain verifier can be run
against genuine device output. `NoirDeviceTest` is the same idea on a toy
circuit.

For iOS commands see `ios/README.md`.

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
with one token), `x/personhood`, `x/allocation`, `x/earth`, `x/pki`.

## Android layout

Source sits under `app/src/main/java/com/example/earthwallet/`, but the
declared package is `network.erth.wallet.*`. **The directory path does not
match the package** — a leftover from an old rename that Kotlin tolerates.
Do not "fix" one to match the other casually; it touches every file.

    chain/              typed clients per module (Dex, Personhood, Staking, …)
    wallet/passport/    headless passport read + proof generation
    wallet/services/    key storage, signing, session, price
    wallet/utils/       Bech32, crypto, biometrics, PIN, referrals
    ui/compose/         all screens (Compose); tabs are Wallet/Earn/Swap/Govern
    ui/compose/registration/  the passport flow, step by step
    app/src/main/proto/ cosmos + earth protobuf definitions

Two files encode chain maths that **must** match the chain exactly:
`ui/compose/PoolApr.kt` and `ui/compose/SwapQuote.kt` (mirrors
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
(~14MB, seven signature-algorithm variants). They carry a `noir_version` that
must match the prover's Noir. Recompiling means `nargo` at that version.

**Where a comment explains something surprising, read it before changing the
approach.** This codebase has few comments and the ones present are load-bearing.

## Conventions

- Kotlin, Compose, JVM 17, minSdk 24 / targetSdk 36.
- Protobuf-lite (`protobuf-javalite`) for Cosmos SDK transaction encoding,
  SIGN_MODE_DIRECT.
- Match the surrounding code's comment density and naming; explain *why*, not
  *what*.
