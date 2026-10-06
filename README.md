# Earth Wallet

Self-custody wallet for **earth-1**, a Cosmos SDK chain built on
proof-of-personhood. The app reads an ePassport's NFC chip, proves on the
device in zero knowledge that its holder is a unique human, and registers that
proof on-chain. No server sees the passport. Balances, stake and votes are
private: every private transaction is proven on the phone.

    android/    the Android app: Kotlin + Jetpack Compose (behavioural reference)
    ios/        the iOS app: see ios/README.md
    circuits/   Noir circuits: passport registration, action, stake, vote, membership, move
    tools/      vector generator, proof checker, Go ground-truth checks, registry builder

| Document | What it is |
|---|---|
| [PRIVACY_FORMATS.md](PRIVACY_FORMATS.md) | Every private format, tag, size and public-input order |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Android structure and conventions |
| [CLAUDE.md](CLAUDE.md) | Working notes and the version pins that must not float |
| [AUDIT_HISTORY.md](AUDIT_HISTORY.md) | Audit rounds, what changed, what is still open |
| [LICENSES.md](LICENSES.md) | Third-party code |

## Build

Android (Gradle lives in `android/`):

    cd android
    ./gradlew :app:assembleDebug        # debug APK
    ./gradlew :app:bundleRelease        # Play bundle (needs keystore.properties)

iOS (full Xcode; details in [ios/README.md](ios/README.md)):

    xcodebuild -project ios/EarthWallet/EarthWallet.xcodeproj -scheme EarthWallet \
      -destination 'generic/platform=iOS Simulator' \
      CODE_SIGNING_ALLOWED=NO ARCHS=arm64 ONLY_ACTIVE_ARCH=NO build
    # arm64 only: the Swoirenberg simulator slice has no x86_64

Circuits (nargo `1.0.0-beta.22`, bb `5.0.0`; see the lockstep in CLAUDE.md):

    cd circuits && nargo compile --workspace && nargo test --workspace

The app ships the compiled circuits checked in at
`android/app/src/main/assets/circuits/*.json` (iOS bundles the same files).
They are not rebuilt by the app build. After any change under `circuits/`,
recompile and compare `bytecode` and `abi` with the shipped JSON, and the VK
(`bb write_vk -b <circuit>.json -o <dir>`): the chain's genesis pins the VKs.
The `hash`, `debug_symbols` and `file_map` fields follow the source text and
paths, so a comment-only change alters them but not the bytecode.

## Test

    cd android && ./gradlew :app:testDebugUnitTest      # JVM tests
    cd ios/EarthCore  && swift test && swift run corecheck
    cd ios/ProverGate && swift test

Both platforms read the same golden vectors and fixtures
(`android/app/src/test/resources/privacy`, copied byte for byte to
`ios/EarthCore/Tests/EarthCoreTests/Resources/privacy`). They come from the
chain's own Go code:

    tools/privacyvectors/gen.sh /path/to/earth-network-chain [ref]

which writes both copies. Regenerating at the pinned chain commit must leave
both trees unchanged.

## Witness dump

The wallet-flow tests run every private action against an in-memory chain with
a checking prover. With `PRIVACY_TOML_OUT` set, every witness the prover saw
is written as a nargo `Prover.toml`, so it can be run through the real
circuits:

    W=/tmp/w
    (cd android && PRIVACY_TOML_OUT=$W ./gradlew :app:testDebugUnitTest --rerun-tasks)
    (cd ios/EarthCore && PRIVACY_TOML_OUT=$W swift test)      # the iOS suite, same layout

    # $W/{action,stake,membership,vote}/<test>_<i>/Prover.toml
    cd circuits
    for k in action stake membership vote; do
      for d in $W/$k/*/; do
        (cd $k && nargo execute -p "${d}Prover" >/dev/null) || echo "FAIL $d"
      done
    done

Every witness must solve. To go one step further on iOS, prove the dumped
witnesses through Barretenberg and check them with the chain's verifier:

    cd ios/ProverGate && PRIVACY_TOML_DIR=$W swift test --filter PrivacyProverTests
    cd tools/chainverify && go run . ../../ios/ProverGate/.artifacts wallet_stake

`PrivacyProverTests` checks each VK against the chain's genesis VK;
`chainverify` needs a chain checkout for its `replace`.
