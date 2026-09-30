# EarthWallet

The app shell, and almost nothing else. Everything is in `../EarthUI` and
`../EarthCore` so the domain layer stays runnable on a Mac and the UI stays
checkable without an app build.

    open EarthWallet.xcodeproj

## Building it needs one more download

Xcode 26 ships the iOS SDK but downloads **platform support** per iOS version
separately, and without it `xcodebuild` reports no destinations at all — for the
simulator *and* for a connected device:

    iPhoneOS.platform/DeviceSupport: 15.0 … 16.4     (no 26.x)
    zeno's iPhone → ineligible: "iOS 26.5 is not installed"

Fix it from Xcode > Settings > Components, or:

    xcodebuild -downloadPlatform iOS

Installing on a device also needs a signing identity, and a fresh Xcode has
none. Signing in with any Apple ID creates a free Personal Team, which is enough
for your own phone — profiles expire every 7 days, and up to 3 devices. It is
*not* enough for the passport chip read: that needs the Near Field Communication
Tag Reading capability on the App ID, which only a paid account can enable.
Enable it on `network.erth.wallet` in the developer portal (Identifiers → the
App ID → Near Field Communication Tag Reading → Save), or add the capability
once in Xcode's Signing & Capabilities tab, which registers it for you. Until
then the build stops at provisioning, before compiling anything.

Meanwhile the whole UI typechecks against the iOS SDK without any of the above:

    cd ../EarthUI && ./Scripts/build-ios.sh

## What only this target can carry

Three things, each with a comment saying why it is not in `EarthUI`:

- `EarthWallet.entitlements` — `com.apple.developer.nfc.readersession.formats
  = [TAG]`, which is what permits raw APDU exchange. `NFCReaderUsageDescription`
  and the ICAO eMRTD application identifier (`A0000002471001`) are in
  `Info.plist` next to it; iOS refuses the session if any of the three is
  missing.
- `ChipReader.swift` — the passport dialogue, over `NFCPassportReader`.
- `DeviceProver.swift` — Barretenberg, over `ProverGate`, against the seven
  circuits referenced in from the Android tree.

The last two are installed into `EarthUI`'s seams in `EarthWalletApp.init`.
See `../README.md` for the packaging reasons and the SRS behaviour.

## Free gas (App Attest)

An underfunded account gets its fee from the backend, which only pays out
against an App Attest attestation (`EarthUI/Gas/AppAttestGas.swift`, HTTP in
`EarthCore/Backend/GasGrant.swift`). No entitlement is needed. App Attest is
unavailable on the Simulator, so the gas button there always reports that the
device can't verify the app — test it on a phone. A development build attests
against Apple's development environment, and the backend has to accept that
environment for the grant to go through.
