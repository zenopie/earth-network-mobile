// swift-tools-version:5.9
import PackageDescription

// Phase 2: the headless domain layer. No Barretenberg, no UI, no Apple SDK —
// so it builds and its checks run under plain SwiftPM on a Command Line Tools
// toolchain, the same reason ProverGateCore is its own package.
let package = Package(
    name: "EarthCore",
    platforms: [.macOS(.v13), .iOS(.v16)],
    products: [
        .library(name: "EarthCore", targets: ["EarthCore"]),
    ],
    dependencies: [
        // 0.17.x is the last line whose manifest a Swift 5.10 toolchain will
        // read; 0.18+ declares tools 6.1. Revisit once Xcode is installed.
        .package(url: "https://github.com/21-DOT-DEV/swift-secp256k1", "0.17.0" ..< "0.18.0"),
        // Pool reserves times an input amount overflows 64 bits, and the chain
        // does this arithmetic in unbounded integers. Matching it needs the same.
        .package(url: "https://github.com/attaswift/BigInt", from: "5.3.0"),
    ],
    targets: [
        .target(
            name: "EarthCore",
            dependencies: [
                .product(name: "secp256k1", package: "swift-secp256k1"),
                .product(name: "BigInt", package: "BigInt"),
            ]
        ),
        // Known-answer checks. An executable rather than XCTest because a
        // Command Line Tools install ships no XCTest platform — `swift test`
        // cannot run here at all. Same pattern as ProverGateCore's corecheck.
        .executableTarget(name: "corecheck", dependencies: ["EarthCore"]),
    ]
)
