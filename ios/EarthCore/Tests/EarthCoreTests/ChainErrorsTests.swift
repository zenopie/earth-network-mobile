import BigInt
import Foundation
import XCTest
@testable import EarthCore

/// Chain refusals explained in plain words, matched by code and codespace
/// or by the chain's own text.
final class ChainErrorsTests: XCTestCase {
    func testDexWithdrawalBankAndAnchorErrorsAreExplained() {
        XCTAssertNotNil(ChainErrors.explain(code: 1101, codespace: "dex",
                                            log: "invalid amount: the uanml leg (1) is above 2, the most one withdrawal pays as notes; withdraw in smaller parts"))
        XCTAssertNil(ChainErrors.explain(code: 1101, codespace: "dex", log: "invalid amount: zero"))
        XCTAssertNotNil(ChainErrors.explain(code: 5, codespace: "bank", log: "uanml: send transactions are disabled"))
        XCTAssertNotNil(ChainErrors.explain(text: "\"alice\" is not live (renewal): renew it before moving it: invalid private msg"))
        XCTAssertNotNil(ChainErrors.explain(code: 1103, codespace: "shielded",
                                            log: "01AB expires at 5, within 120s of the last block: pick a newer anchor: root is not a recent note-tree root"))
        XCTAssertNil(ChainErrors.explain(code: 1103, codespace: "shielded", log: "root is not a recent note-tree root"))
    }

    func testSwitchSignerAndDailyCapErrorsArePlain() {
        let mismatch = ChainErrors.explain(code: 1127, codespace: "personhood")!
        XCTAssertTrue(mismatch.contains("same passport"), mismatch)
        // A simulate (or the gas service) answers with the registered text.
        XCTAssertEqual(mismatch, ChainErrors.explain(text: "identity switch must be proven under the live registration's document signer"))
        XCTAssertEqual(mismatch, GasGrant.Refused(status: 403, message: "identity switch must be proven under the live registration's document signer").message)
        let cap = ChainErrors.explain(code: 1113, codespace: "personhood")!
        XCTAssertTrue(cap.contains("Try again tomorrow"), cap)
        XCTAssertEqual(cap, ChainErrors.explain(text: "rpc error: daily registration limit reached for this document signer or country"))
        // The codes mean nothing in another module.
        XCTAssertNil(ChainErrors.explain(code: 1127, codespace: "dex"))
    }

    func testRefusalsThatCostNothingSayTryAgain() {
        for log in [
            "the delegation buys 5 derth at the live rate 1.1, less than the 6 it credits (the rate moved since the proof: re-quote with a margin)",
            "move_time 1 is not within 600s before the block time 9000 (name a recent block's time)",
            "debt root 00 is not the current slash debt root 01 (a slash reached a redelegation since: re-prove)",
        ] {
            let t = ChainErrors.explain(text: log)
            XCTAssertNotNil(t, log)
            XCTAssertTrue(t?.contains("try again") ?? false, log)
        }
        XCTAssertEqual(ChainErrors.explain(text: "x re-quote with a margin"),
                       ChainErrors.explain(code: 1103, codespace: "shieldedstaking", log: "x re-quote with a margin"))
        XCTAssertNil(ChainErrors.explain(code: 1103, codespace: "shieldedstaking", log: "amount converts to nothing"))
    }
}
