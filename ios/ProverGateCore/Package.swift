// swift-tools-version: 5.9
import PackageDescription

// The half of the iOS port that does not touch Barretenberg: field-element
// conversion, the Noir witness decoder, repo layout.
//
// Deliberately a separate package with **no dependencies**. The ProverGate
// package next door pulls in Swoirenberg as an xcframework binary target, and
// SwiftPM on a Command Line Tools toolchain hangs resolving that (see
// ios/README.md) — for the whole package graph, not just the target that needs
// it. Keeping this standalone means `swift run corecheck` works on any Swift
// toolchain, so a blocked prover does not block everything downstream of it.
let package = Package(
    name: "ProverGateCore",
    platforms: [.macOS(.v13), .iOS(.v15)],
    products: [
        .library(name: "ProverGateCore", targets: ["ProverGateCore"]),
        .executable(name: "corecheck", targets: ["corecheck"]),
    ],
    targets: [
        .target(name: "ProverGateCore"),
        .executableTarget(name: "corecheck", dependencies: ["ProverGateCore"]),
    ]
)
