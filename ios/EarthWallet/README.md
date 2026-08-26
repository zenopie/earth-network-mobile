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

## AdMob

`GADApplicationIdentifier` in `Info.plist` and `RewardedAds.adUnitID` in
`EarthUI` are the live iOS app and rewarded unit — not Android's, which will
never fill here, and not Google's demo unit, which fills but breaks the grant
(the SSV callback URL belongs to an ad unit, and the backend checks the
`ad_unit` it is called with). A development phone gets "No fill" from the live
unit until it is registered as an AdMob test device; paste this device's
identifier into `RewardedAds.testDeviceIdentifiers` — the SDK prints it on the
first ad request — and the same unit serves test ads that still drive the
callback. Debug builds only.
