import Foundation
import ProverGateCore

// Checks the parts of the port that do not touch Barretenberg: field-element
// base conversion and the witness decoder, against the real Android fixture.
//
//   cd ios/ProverGate && swift run corecheck
//
// Split out from the full gate because linking the Swoirenberg xcframework
// needs a toolchain that a Command Line Tools install does not provide (see
// ios/README.md). These checks run anywhere Swift does, so a blocked prover
// does not also block the rest of the port.

var failures = 0
func check(_ name: String, _ got: String, _ want: String) {
    let ok = got == want
    if !ok { failures += 1 }
    print("[\(ok ? "PASS" : "FAIL")] \(name): got \(got)\(ok ? "" : ", want \(want)")")
}

// The constant the gate asserts on: current_date 0x3d0f5 is what bb should
// report as public signal 0, in decimal. 250101 is 25-01-01 in the circuit's
// YYMMDD encoding.
check("current_date 0x3d0f5 -> decimal", decimalFromHex("0x3d0f5"), "250101")

var bytes = [UInt8](repeating: 0, count: 32)
bytes[29] = 0x03; bytes[30] = 0xd0; bytes[31] = 0xf5
check("32-byte BE -> decimal", decimalFromBigEndian(bytes), "250101")
check("decimal -> hex round trip", hexFromDecimal("250101"), "3d0f5")
check("zero", decimalFromBigEndian([UInt8](repeating: 0, count: 32)), "0")
check("hex of zero", hexFromDecimal("0"), "0")

// A full-width field element, to catch overflow in the long division.
check("2^256-1", decimalFromBigEndian([UInt8](repeating: 0xff, count: 32)),
      "115792089237316195423570985008687907853269984665640564039457584007913129639935")
check("2^256-1 back to hex",
      hexFromDecimal("115792089237316195423570985008687907853269984665640564039457584007913129639935"),
      String(repeating: "f", count: 64))

do {
    let root = try RepoLayout.root(from: CommandLine.arguments.count > 1
        ? CommandLine.arguments[1]
        : FileManager.default.currentDirectoryPath)
    let witness = try NoirWitness.decode(Data(contentsOf: RepoLayout.Paths(root: root).witness))

    check("witness input count", "\(witness.count)", "15")
    check("current_date present", witness["current_date"] as? String ?? "nil", "0x3d0f5")
    // merkle_path_bits is the only boolean array. JSONSerialization funnels JSON
    // booleans through NSNumber, so this is the case NoirWitness's
    // CFBooleanGetTypeID check exists for — without it these would reach bb as
    // "0"/"1" strings.
    check("merkle_path_bits decode as Bool",
          "\((witness["merkle_path_bits"] as? [Any])?.first is Bool)", "true")
    check("dg1 decodes as hex strings",
          "\((witness["dg1"] as? [Any])?.first is String)", "true")
} catch {
    print("[FAIL] fixture checks: \(error)")
    failures += 1
}

print(failures == 0 ? "\nall core checks passed" : "\n\(failures) check(s) failed")
exit(failures == 0 ? 0 : 1)
