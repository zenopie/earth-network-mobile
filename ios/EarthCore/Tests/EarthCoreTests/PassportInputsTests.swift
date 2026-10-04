import Foundation
import XCTest
@testable import EarthCore

/// The passport proof's witness, from the shared fixture (privacy/passport_witness.json:
/// a synthetic P-256 passport and the lean_poa witness that proves with the genesis
/// VK through progate --witness). Android's PassportInputsTest builds the same one
/// from the same DG1 and EF.SOD, so both apps hand bb identical inputs.
final class PassportInputsTests: XCTestCase {
    let fixture = try! JSONSerialization.jsonObject(with: Vectors.resource("passport_witness.json")) as! [String: Any]

    func testBuildsTheFixtureWitness() throws {
        let want = fixture["witness"] as! [String: Any]
        let inputs = try PassportInputs.build(dg1: Vectors.unhex(fixture["dg1_hex"] as! String), efSOD: Vectors.unhex(fixture["sod_hex"] as! String),
                                              currentDateYYMMDD: fixture["current_date"] as! Int, addressField: want["address"] as! String)
        XCTAssertEqual(fixture["algorithm"] as? String, inputs.algorithm)
        XCTAssertEqual(Set(want.keys), Set(inputs.witness.keys))
        for (k, v) in want {
            if let a = v as? [String] { XCTAssertEqual(a, inputs.witness[k] as? [String], k) } else { XCTAssertEqual(v as? String, inputs.witness[k] as? String, k) }
        }
    }

    func testADG1TheSODDoesNotSignIsRefused() {
        var dg1 = Vectors.unhex(fixture["dg1_hex"] as! String)
        dg1[20] ^= 1
        XCTAssertThrowsError(try PassportInputs.build(dg1: dg1, efSOD: Vectors.unhex(fixture["sod_hex"] as! String), currentDateYYMMDD: 260819, addressField: "0x1"))
    }
}
