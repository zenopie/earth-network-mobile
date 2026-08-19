// swift-tools-version: 5.9
import PackageDescription

// Phase 1 gate for the iOS port: proves that Barretenberg on Apple platforms
// produces proofs the earth-1 chain accepts, before any app code is written.
//
// VERSION LOCKSTEP (see android/app/build.gradle for the Android half):
// Swoir 1.0.0-beta.22-2 -> Swoirenberg 1.0.0-beta.22-2 -> noir_rs tag
// v1.0.0-beta.22-1 -> barretenberg-rs =5.0.0, which downloads the Aztec
// `v5.0.0` release (final, not a nightly). The Android app resolves through
// noir_android 1.0.0-beta.22-2 to that *same* noir_rs tag, and the chain
// verifier pins `aztec_tag: v5.0.0` in
// earth-network-chain/third_party/barretenberg-go/checksums.json. All three
// sides therefore share one bb build. A nightly bb changes the Fiat-Shamir
// transcript and the v5.0.0 verifier rejects its proofs on large circuits, so
// do not float this pin.
//
// NOT -3: that release strips the recursive-proving stack to shrink the
// framework, which leaves dangling references (ChonkProof, databus,
// MegaCircuitBuilder) that fail to link. -2 resolves to the same noir_rs tag
// and so the same bb build, and its archive is complete.
let package = Package(
    name: "ProverGate",
    platforms: [.macOS(.v13), .iOS(.v15)],
    products: [
        .library(name: "ProverGate", targets: ["ProverGate"]),
        .executable(name: "progate", targets: ["progate"]),
    ],
    dependencies: [
        .package(url: "https://github.com/Swoir/Swoir.git", exact: "1.0.0-beta.22-2"),
        .package(path: "../ProverGateCore"),
    ],
    targets: [
        .target(
            name: "ProverGate",
            dependencies: [
                .product(name: "ProverGateCore", package: "ProverGateCore"),
                .product(name: "Swoir", package: "Swoir"),
            ]),
        // The gate as a runnable command. The checks live in the library so
        // they can run without XCTest, which needs full Xcode. Note that
        // this package needs full Xcode anyway — SwiftPM on a Command Line
        // Tools toolchain hangs on the Swoirenberg binary target (see
        // ios/README.md); ProverGateCore is the part that runs without it.
        .executableTarget(
            name: "progate",
            dependencies: ["ProverGate"]),
        .testTarget(
            name: "ProverGateTests",
            dependencies: ["ProverGate"]),
    ]
)
