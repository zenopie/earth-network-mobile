// swift-tools-version:5.9
import PackageDescription

// Phase 4: the app's screens. A library rather than an app target so the whole
// UI typechecks from the command line — `Scripts/build-ios.sh` — without a
// simulator runtime installed. `ios/EarthWallet` is the thin app shell that
// hosts it.
let package = Package(
    name: "EarthUI",
    platforms: [.iOS(.v17), .macOS(.v13)],
    products: [
        .library(name: "EarthUI", targets: ["EarthUI"]),
    ],
    dependencies: [
        .package(path: "../EarthCore"),
    ],
    targets: [
        .target(name: "EarthUI", dependencies: [
            .product(name: "EarthCore", package: "EarthCore"),
        ]),
    ]
)
