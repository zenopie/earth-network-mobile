import EarthCore
import Foundation

// Known-answer checks for the headless domain layer. Run:
//
//     cd ios/EarthCore && swift run corecheck
//
// Where a value came from an outside authority the comment says which; the
// point of this file is that nothing here is self-referential.

// Artifacts land beside the package so tools/txcheck has a fixed path to read,
// the same arrangement ProverGate uses for its proof and VK.
let artifacts = URL(fileURLWithPath: #filePath)
    .deletingLastPathComponent()   // corecheck
    .deletingLastPathComponent()   // Sources
    .deletingLastPathComponent()   // EarthCore
    .appendingPathComponent(".artifacts")

checkCrypto()
checkTransactions(writingTo: artifacts)
checkMath()

// `swift run corecheck --live` also asks the real chain. Left out of the
// default run so the offline checks stay the thing that has to pass.
if CommandLine.arguments.contains("--live") {
    await checkLive()
}

Check.finish()
