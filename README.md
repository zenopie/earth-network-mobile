# Earth Wallet

Self-custody wallet for **earth-1**, a Cosmos SDK chain built on
proof-of-personhood. The app reads an ePassport's NFC chip, proves on the
device in zero knowledge that its holder is a unique human, and registers that
proof on-chain. No server sees the passport.

    android/    the shipping app: Kotlin + Jetpack Compose
    ios/        the iOS app: see ios/README.md
    circuits/   Noir circuits for the personhood proof
    tools/      registry builder, proof checker, Go ground-truth checks

## Android

    cd android
    ./gradlew :app:assembleDebug        # debug APK
    ./gradlew :app:bundleRelease        # Play bundle (needs keystore.properties)
    ./gradlew :app:testDebugUnitTest    # JVM tests

Structure and conventions: [ARCHITECTURE.md](ARCHITECTURE.md). Working notes
and the version pins that must not float: [CLAUDE.md](CLAUDE.md).
Third-party code: [LICENSES.md](LICENSES.md).
