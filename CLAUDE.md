# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

### Build Commands
- `./gradlew build` - Build the Android app
- `./gradlew assembleDebug` - Build debug APK
- `./gradlew assembleRelease` - Build release APK

### Testing Commands
- `./gradlew test` - Run unit tests
- `./gradlew connectedAndroidTest` - Run instrumented tests (requires connected device/emulator)

### Development Commands
- `./gradlew clean` - Clean build artifacts
- `./gradlew lint` - Run lint checks (note: configured to not abort on errors)

### SecretJS Commands (in secretjs-source directory)
- `npm run build` - Build the SecretJS library
- `npm test` or `./test.sh` - Run SecretJS tests

## Architecture

This is an Android application (EarthWallet) for scanning ePassports using NFC technology with integrated Secret Network blockchain functionality:

### Core Components
1. **EarthWallet**: Android app using JMRTD library for reading ePassports via NFC
2. **SecretJS Integration**: Full SecretJS source embedded for Secret Network blockchain interactions
3. **Bridge Layer**: Java classes that bridge Android and Secret Network operations

### Key Directories
- `app/src/main/java/com/example/earthwallet/` - Main Android application code
  - `bridge/` - Secret Network integration classes
  - `wallet/` - Wallet management functionality
- `secretjs-source/` - Complete SecretJS library source code
- `app/src/main/proto/` - Protocol buffer definitions for Cosmos SDK transactions

### Technology Stack
- **Android**: Java with gradle build system, minimum SDK 23
- **Cryptography**: BouncyCastle (carefully managed to avoid conflicts)
- **NFC**: JMRTD library for ePassport reading
- **Blockchain**: SecretJS for Secret Network integration with protobuf transaction encoding
- **Dependencies**: Complex dependency resolution to handle BouncyCastle version conflicts

### Architecture Notes
- The app combines passport scanning with blockchain wallet functionality
- Uses protobuf-lite for Android compatibility with Cosmos SDK transactions
- Implements careful BouncyCastle dependency management to avoid conflicts between JMRTD and crypto libraries
- SecretJS is included as source rather than npm dependency for Android integration
- Supports both debug and release builds with protobuf source generation