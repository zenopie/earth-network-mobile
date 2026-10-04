import Foundation
import ProverGateCore

// Checks the parts of the port that do not touch Barretenberg: field-element
// base conversion and the witness decoder, against every passport variant's
// shared fixture and its compiled circuit's own ABI.
//
//   cd ios/ProverGateCore && swift run corecheck
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

// The constant the gate asserts on: the fixture's current_date 0x3fad3 is what
// bb should report as public signal 0, in decimal. 260819 is 26-08-19 in the
// circuit's YYMMDD encoding.
check("current_date 0x3fad3 -> decimal", decimalFromHex("0x3fad3"), "260819")

var bytes = [UInt8](repeating: 0, count: 32)
bytes[29] = 0x03; bytes[30] = 0xfa; bytes[31] = 0xd3
check("32-byte BE -> decimal", decimalFromBigEndian(bytes), "260819")
check("decimal -> hex round trip", hexFromDecimal("260819"), "3fad3")
check("zero", decimalFromBigEndian([UInt8](repeating: 0, count: 32)), "0")
check("hex of zero", hexFromDecimal("0"), "0")

// A full-width field element, to catch overflow in the long division.
check("2^256-1", decimalFromBigEndian([UInt8](repeating: 0xff, count: 32)),
      "115792089237316195423570985008687907853269984665640564039457584007913129639935")
check("2^256-1 back to hex",
      hexFromDecimal("115792089237316195423570985008687907853269984665640564039457584007913129639935"),
      String(repeating: "f", count: 64))

// JSONSerialization funnels JSON booleans through NSNumber, so a boolean input
// (none of today's circuits takes one) would reach bb as "0"/"1" without
// NoirWitness's CFBooleanGetTypeID check. A synthetic witness, since the
// fixture has no boolean to exercise it.
do {
    let w = try NoirWitness.decode(Data(#"{"flags":[true,false],"n":5,"x":"0x1"}"#.utf8))
    check("booleans decode as Bool", "\((w["flags"] as? [Any])?.first is Bool)", "true")
    check("numbers decode as decimal strings", "\(w["n"] as? String ?? "nil")", "5")
} catch {
    print("[FAIL] synthetic witness: \(error)")
    failures += 1
}

/// The ABI's shape of one parameter: an integer or field ("u32", "field"), or
/// an array of them ("[u8;95]").
func shape(_ type: [String: Any]) -> String {
    switch type["kind"] as? String {
    case "array": return "[\(shape(type["type"] as? [String: Any] ?? [:]));\(type["length"] as? Int ?? -1)]"
    case "integer": return "\(type["sign"] as? String == "unsigned" ? "u" : "i")\(type["width"] as? Int ?? -1)"
    case let k: return k ?? "?"
    }
}

/// The witness value's shape, read the way the ABI names it: a scalar is a
/// hex string, an array a list of them.
func witnessShape(_ value: Any, like type: [String: Any]) -> String {
    if type["kind"] as? String == "array", let a = value as? [Any] {
        let inner = type["type"] as? [String: Any] ?? [:]
        let shapes = Set(a.map { witnessShape($0, like: inner) })
        return "[\(shapes.count == 1 ? shapes.first! : shapes.sorted().joined(separator: "|"));\(a.count)]"
    }
    guard let s = value as? String, s.hasPrefix("0x"), !s.dropFirst(2).isEmpty,
          s.dropFirst(2).allSatisfy(\.isHexDigit) else { return "not hex" }
    if let width = type["width"] as? Int, s.dropFirst(2).drop(while: { $0 == "0" }).count * 4 > width + 3 { return "too wide" }
    return shape(type)
}

do {
    let root = try RepoLayout.root(from: CommandLine.arguments.count > 1
        ? CommandLine.arguments[1]
        : FileManager.default.currentDirectoryPath)
    let paths = RepoLayout.Paths(root: root)
    let variants = ((try JSONSerialization.jsonObject(with: Data(contentsOf: paths.manifest)) as? [String: Any])?["variants"]
        as? [[String: Any]]) ?? []
    check("variants in the manifest", "\(variants.count)", "33")
    for v in variants {
        guard let id = v["id"] as? String, let bundled = v["bundled"] as? Bool else { continue }
        // Each variant's shared witness against its compiled circuit's ABI:
        // a circuit that gains or loses an input fails this, not the prover.
        let fixture = try JSONSerialization.jsonObject(with: Data(contentsOf:
            paths.fixtures.appendingPathComponent("\(id)/expected.json"))) as? [String: Any]
        let witness = try NoirWitness.decode(JSONSerialization.data(withJSONObject: fixture?["witness"] ?? [:]))
        let circuitData: Data
        if bundled {
            circuitData = try Data(contentsOf: paths.circuits.appendingPathComponent("\(id).json"))
        } else if let gz = paths.downloadable(id), let json = Gzip.inflate(try Data(contentsOf: gz)) {
            circuitData = json
        } else {
            print("[SKIP] \(id): not bundled, and no backend checkout beside this one")
            continue
        }
        let manifest = try JSONSerialization.jsonObject(with: circuitData) as? [String: Any]
        let abi = manifest?["abi"] as? [String: Any]
        let parameters = abi?["parameters"] as? [[String: Any]] ?? []
        let names = parameters.compactMap { $0["name"] as? String }
        check("\(id): witness inputs are the circuit's", witness.keys.sorted().joined(separator: ","), names.sorted().joined(separator: ","))
        for p in parameters {
            guard let name = p["name"] as? String, let type = p["type"] as? [String: Any] else { continue }
            check("\(id): \(name) is \(shape(type))", witness[name].map { witnessShape($0, like: type) } ?? "missing", shape(type))
        }
        // Public inputs: current_date and address, then the two return values
        // (nullifier, dsc_key): the four signals the chain indexes 0...3.
        let publics = parameters.filter { $0["visibility"] as? String == "public" }.compactMap { $0["name"] as? String }
        check("\(id): public inputs in order", publics.joined(separator: ","), "current_date,address")
        let returns = ((abi?["return_type"] as? [String: Any])?["abi_type"] as? [String: Any])?["fields"] as? [Any]
        check("\(id): return values", "\(returns?.count ?? -1)", "2")

        // current_date is a calendar date (the chain refuses one that is not).
        let date = Int(decimalFromHex(witness["current_date"] as? String ?? "0x0")) ?? 0
        let (yy, mm, dd) = (date / 10000, date / 100 % 100, date % 100)
        var c = DateComponents(); c.year = 2000 + yy; c.month = mm; c.day = dd
        let cal = Calendar(identifier: .gregorian)
        let real = cal.date(from: c).map { cal.dateComponents([.year, .month, .day], from: $0) }
        check("\(id): current_date is a YYMMDD calendar date", "\(real?.month == mm && real?.day == dd && (1 ... 12).contains(mm))", "true")
        check("\(id): address is a non-zero field", "\((witness["address"] as? String).map { decimalFromHex($0) != "0" } ?? false)", "true")
    }
} catch {
    print("[FAIL] fixture checks: \(error)")
    failures += 1
}

print(failures == 0 ? "\nall core checks passed" : "\n\(failures) check(s) failed")
exit(failures == 0 ? 0 : 1)
