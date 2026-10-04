import BigInt
import CryptoKit
import Foundation
import XCTest
@testable import EarthCore

/// The chain's own witness fixtures (tools/privacyfixtures membership,
/// tools/orchardfixtures action), rebuilt with the wallet's trees,
/// derivations and Grumpkin. nargo execute accepting the chain's tomls is the
/// Go<->Noir parity check; these tests are the Go<->Swift one. Also the
/// bundled privacy SRS the prover reads.
final class FixtureWitnessTests: XCTestCase {
    func det(_ label: String, _ i: UInt64) -> Fr { PrivacyHash.h(PrivacyHash.assetID("fixture/\(label)"), PrivacyHash.u64(i)) }

    func publicInputs(_ name: String) -> [String] {
        let raw = Vectors.resource("\(name)/public_inputs.expected")
        return (0 ..< raw.count / 32).map { Vectors.hex(raw.subdata(in: 32 * $0 ..< 32 * $0 + 32)) }
    }

    /// Minimal TOML: key = "scalar" | [ ... ], values normalised to integers.
    func parseToml(_ text: String) -> [String: String] {
        var out: [String: String] = [:]
        for line in text.split(separator: "\n") where !line.hasPrefix("#") && line.contains(" = ") {
            let parts = line.components(separatedBy: " = ")
            let normalized = parts[1].replacingOccurrences(of: "\"", with: "")
                .components(separatedBy: CharacterSet(charactersIn: "[], "))
                .filter { !$0.isEmpty }
                .map { v -> String in v.hasPrefix("0x") ? BigUInt(String(v.dropFirst(2)), radix: 16)!.description : v }
            out[parts[0]] = normalized.joined(separator: ",")
        }
        return out
    }

    static func txFields(_ t: [String: Any]) -> [Fr] {
        [PrivacyHash.bytes(Data((t["memo"] as! String).utf8)), PrivacyHash.u64((t["timeout_height"] as! NSNumber).uint64Value),
         PrivacyHash.u64((t["gas_limit"] as! NSNumber).uint64Value)]
    }

    func testMembershipFixture() throws {
        let t = MerkleTree(store: MemNodeStore())
        let ours: UInt64 = 13
        let idSecret = det("id_secret", 0), dscKey = det("dsc", 0)
        let country = PrivacyHash.countryField("DE")
        let activatedAt: UInt64 = 1_790_000_000
        // A switched identity: its leaf commits to the switch's time (4a663d5).
        let predecessorAt: UInt64 = 1_789_000_000
        for i in 0 ..< 21 as Range<UInt64> {
            let leaf: Fr
            if i == ours {
                leaf = PrivacyHash.identityLeaf(idc: PrivacyHash.idc(idSecret), dscKey: dscKey, country: country, activatedAt: activatedAt,
                                                predecessorAt: predecessorAt)
            } else {
                let c = PrivacyHash.countryField(["DE", "FR", ""][Int(i % 3)])
                leaf = PrivacyHash.identityLeaf(idc: PrivacyHash.idc(det("other", i)), dscKey: det("dsc", i % 3), country: c,
                                                activatedAt: 1_780_000_000 + i, predecessorAt: 0)
            }
            t.append(leaf)
        }
        t.update(3, .zero)
        let w = try MembershipWitness(idSecret: idSecret, dscKey: dscKey, country: country, activatedAt: activatedAt, predecessorAt: predecessorAt,
                                      leafIndex: ours, siblings: t.path(ours), root: t.root(), scope: PrivacyHash.assetID("claim:20360"),
                                      signal: det("signal", 0), excludedDsc: det("dsc", 99), excludedCountry: PrivacyHash.countryField("FR"),
                                      maxActivation: activatedAt + 86_400, maxPredecessor: predecessorAt + 3_600)
        try w.check()
        XCTAssertEqual(publicInputs("fixture_membership"), w.publicInputs().map(\.hex))
        XCTAssertEqual(parseToml(String(decoding: Vectors.resource("fixture_membership/Prover.toml"), as: UTF8.self)), parseToml(w.proverToml()))
    }

    /// tools/orchardfixtures' 3-action mixed-asset bundle: each action's
    /// witness rebuilt from its private inputs reproduces the chain's public
    /// inputs (cv included) and Prover.toml, and the bundle's digest, sighash
    /// and binding signature check under the wallet's own code.
    func testActionFixture() throws {
        let bj = try JSONSerialization.jsonObject(with: Vectors.resource("fixture_action/bundle.json")) as! [String: Any]
        let acts = bj["actions"] as! [[String: Any]]
        var actions: [ShieldedAction] = []
        for (i, a) in acts.enumerated() {
            let text = String(decoding: Vectors.resource("fixture_action/action_\(i)/Prover.toml"), as: UTF8.self)
            let t = parseToml(text)
            func f(_ k: String) -> Fr { Fr(BigUInt(t[k]!, radix: 10)!) }
            func u(_ k: String) -> UInt64 { UInt64(t[k]!)! }
            let w = try ActionWitness(nk: f("nk"), sAsset: f("s_asset"), sValue: u("s_value"), sRho: f("s_rho"), sRcm: f("s_rcm"),
                                      sPos: u("s_pos"), sPath: t["s_path"]!.split(separator: ",").map { Fr(BigUInt(String($0), radix: 10)!) },
                                      oAsset: f("o_asset"), oValue: u("o_value"), oPc: f("o_pc"), rcv: f("rcv"), anchor: f("anchor"),
                                      sighash: f("sighash"))
            try w.check()
            XCTAssertEqual(publicInputs("fixture_action/action_\(i)"), w.publicInputs().map(\.hex), "action \(i)")
            XCTAssertEqual(t, parseToml(w.proverToml()), "action \(i) toml")
            XCTAssertEqual(a["cv"] as? String, Vectors.hex(w.cv.bytes))
            actions.append(ShieldedAction(anchor: w.anchor.bytes, nullifier: w.nf.bytes, commitment: w.cmOut.bytes, cv: w.cv.bytes,
                                  ciphertext: Vectors.unhex(a["ct"] as! String), proof: Data()))
        }
        let balances = (bj["balances"] as! [[String: Any]]).map { ValueBalance(denom: $0["denom"] as! String, amount: ($0["value"] as! NSNumber).uint64Value) }
        let b = ShieldedBundle(actions: actions, balances: balances, bindingSig: Vectors.unhex(bj["binding_sig"] as! String))
        let sighash = PrivacyHash.signal(msgType: bj["msg_type"] as! String, chainID: bj["chain_id"] as! String,
                                         fields: [PrivacyHash.u64(1), try PrivateMsgs.digest(b)] + Self.txFields(bj["tx"] as! [String: Any]))
        XCTAssertEqual(String((bj["sighash"] as! String).dropFirst(2)), sighash.hex)
        XCTAssertTrue(PrivateMsgs.checkBalance(b, sighash: sighash))
    }

    func testTheBundledSrsIsTheTranscriptPrefix() throws {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
            .appendingPathComponent("../../../../android/app/src/main/assets/srs/bn254_g1_32769.dat").standardized
        let bytes = try Data(contentsOf: url)
        XCTAssertEqual(32_769 * 64, bytes.count)
        XCTAssertEqual("d769ac6c98f8fab858a7e9967f2b7f181d8ad9fdcdf55438c915696febf0e99c",
                       SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined())
        XCTAssertEqual(1, bytes[31]); XCTAssertEqual(2, bytes[63])
    }
}
