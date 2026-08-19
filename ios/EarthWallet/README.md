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
Tag Reading entitlement, which only a paid account can enable.

Meanwhile the whole UI typechecks against the iOS SDK without any of the above:

    cd ../EarthUI && ./Scripts/build-ios.sh

## Info.plist

`NFCReaderUsageDescription` and the ICAO eMRTD application identifier
(`A0000002471001`) are declared already, so the shape is on record. The reader
session itself is not built — see `../README.md`.
