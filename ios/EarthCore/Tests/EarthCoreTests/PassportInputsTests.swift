import Foundation
import XCTest
@testable import EarthCore

/// Every register-circuit variant's shared fixture (circuits/fixtures/<variant>:
/// a synthetic passport from circuits/tools/variants.py, whose witness the
/// circuit's nargo tests and the chain's verifier fixtures prove). Android's
/// PassportInputsTest reads the very same files, so both apps hand bb
/// identical inputs; and every scheme no circuit covers is refused by name.
final class PassportInputsTests: XCTestCase {
    /// Tests/EarthCoreTests -> Tests -> EarthCore -> ios -> the repo root.
    static let root = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent()
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    static let fixtures = root.appendingPathComponent("circuits/fixtures")
    let variants = try! PassportVariants(json: Data(contentsOf: root.appendingPathComponent(
        "android/app/src/main/assets/circuits/passport_variants.json")))

    func file(_ dir: String, _ name: String) throws -> Data {
        try Data(contentsOf: Self.fixtures.appendingPathComponent(dir).appendingPathComponent(name))
    }

    func testEveryVariantBuildsTheReferenceWitness() throws {
        XCTAssertEqual(variants.variants.count, 33)
        for v in variants.variants {
            let expected = try JSONSerialization.jsonObject(with: file(v.id, "expected.json")) as! [String: Any]
            let want = expected["witness"] as! [String: Any]
            let inputs = try PassportInputs.build(dg1: file(v.id, "dg1.bin"), efSOD: file(v.id, "sod.bin"),
                                                  currentDateYYMMDD: expected["current_date"] as! Int,
                                                  addressField: expected["address"] as! String,
                idSecretField: expected["id_secret"] as! String, variants: variants)
            XCTAssertEqual(inputs.algorithm, v.id)
            XCTAssertEqual(inputs.scheme, expected["scheme"] as? String, v.id)
            XCTAssertEqual(Set(want.keys), Set(inputs.witness.keys), v.id)
            for (k, value) in want {
                if let a = value as? [String] {
                    XCTAssertEqual(a, inputs.witness[k] as? [String], "\(v.id) \(k)")
                } else {
                    XCTAssertEqual(value as? String, inputs.witness[k] as? String, "\(v.id) \(k)")
                }
            }
        }
    }

    func testADG1TheSODDoesNotSignIsRefused() throws {
        for v in variants.variants {
            let expected = try JSONSerialization.jsonObject(with: file(v.id, "expected.json")) as! [String: Any]
            XCTAssertThrowsError(try PassportInputs.build(dg1: file(v.id, "dg1_tampered.bin"), efSOD: file(v.id, "sod.bin"),
                                                          currentDateYYMMDD: 250101, addressField: "0x1", idSecretField: "0x2", variants: variants)) {
                XCTAssertEqual($0 as? PassportInputs.Error, .data(expected["dg1_tampered_error"] as! String), v.id)
            }
        }
    }

    func testUnsupportedSchemesAreNamed() throws {
        let dir = Self.fixtures.appendingPathComponent("unsupported")
        let names = try FileManager.default.contentsOfDirectory(atPath: dir.path).sorted()
        XCTAssertGreaterThanOrEqual(names.count, 10)
        for name in names {
            let want = (try JSONSerialization.jsonObject(with: file("unsupported/\(name)", "expected.json")) as! [String: Any])["unsupported"] as! String
            XCTAssertThrowsError(try PassportInputs.build(dg1: file("unsupported/\(name)", "dg1.bin"),
                                                          efSOD: file("unsupported/\(name)", "sod.bin"),
                                                          currentDateYYMMDD: 250101, addressField: "0x1", idSecretField: "0x2", variants: variants)) {
                XCTAssertEqual($0 as? PassportInputs.Error, .unsupported(want), name)
                XCTAssertEqual($0.localizedDescription, "This passport's signature type isn't supported yet (\(want))", name)
            }
        }
    }

    func testANonTD3DG1IsUnsupported() throws {
        let v = variants.variants[0]
        XCTAssertThrowsError(try PassportInputs.build(dg1: Data(count: 60), efSOD: file(v.id, "sod.bin"),
                                                      currentDateYYMMDD: 250101, addressField: "0x1", idSecretField: "0x2", variants: variants)) {
            XCTAssertEqual($0 as? PassportInputs.Error, .unsupported("60-byte DG1 (not a TD3 passport)"))
        }
    }
}
