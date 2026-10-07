import Foundation
import XCTest
@testable import EarthCore

/// The confirm sheet's single-flight rule (build 21: a registration's sheet
/// came back over "Sending" and was confirmed twice), the duplicate
/// registration refusals, and the registration estimate that made the sheet
/// come back.
final class TxGateTests: XCTestCase {
    func testAConfirmedRequestsSheetNeverComesBack() {
        var g = TxGate()
        let a = UUID()
        XCTAssertTrue(g.present(a))
        XCTAssertTrue(g.confirm(a))
        // A second tap, or any stale path putting the sheet back.
        XCTAssertFalse(g.confirm(a))
        XCTAssertFalse(g.present(a))
        g.finish(a)
        XCTAssertFalse(g.present(a))
        XCTAssertFalse(g.confirm(a))
    }

    func testNoSheetDrawsOverASend() {
        var g = TxGate()
        let a = UUID(), b = UUID()
        XCTAssertTrue(g.present(a))
        XCTAssertTrue(g.confirm(a))
        XCTAssertFalse(g.present(b))
        XCTAssertFalse(g.confirm(b))
        // Cancel has no sheet to close while sending, and does not end the send.
        g.cancel()
        XCTAssertTrue(g.sending)
        g.finish(a)
        // A chained request after the send (the caretaker move after a handle move).
        XCTAssertTrue(g.present(b))
        XCTAssertTrue(g.confirm(b))
    }

    func testAReaskShowsTheSameRequestOnceMore() {
        var g = TxGate()
        let a = UUID()
        XCTAssertTrue(g.present(a))
        XCTAssertTrue(g.confirm(a))
        XCTAssertTrue(g.reask(a))
        XCTAssertTrue(g.showing(a))
        XCTAssertTrue(g.confirm(a))
        g.finish(a)
        XCTAssertFalse(g.reask(a))
        XCTAssertFalse(g.present(a))
    }

    func testAGasWaitStopsOnceItsSheetIsGone() {
        var g = TxGate()
        let a = UUID(), b = UUID()
        XCTAssertTrue(g.present(a))
        XCTAssertTrue(g.showing(a))
        XCTAssertTrue(g.confirm(a))
        XCTAssertFalse(g.showing(a))
        g.finish(a)
        XCTAssertTrue(g.present(b))
        g.cancel()
        XCTAssertFalse(g.showing(b))
        // Replaced by another request's sheet.
        let c = UUID(), d = UUID()
        XCTAssertTrue(g.present(c))
        XCTAssertTrue(g.present(d))
        XCTAssertFalse(g.showing(c))
    }

    func testDuplicateRegistrationRefusals() {
        for code in [1123, 1124, 1130] {
            XCTAssertTrue(TxGate.duplicateRegistration(UnsignedTx.TxRejected(code: code, log: "", codespace: "personhood")))
        }
        XCTAssertTrue(TxGate.duplicateRegistration(PrivacyWallet.IdentityUsed()))
        XCTAssertFalse(TxGate.duplicateRegistration(UnsignedTx.TxRejected(code: 1102, log: "", codespace: "personhood")))
        XCTAssertFalse(TxGate.duplicateRegistration(UnsignedTx.TxRejected(code: 1130, log: "", codespace: "sdk")))
        XCTAssertFalse(TxGate.duplicateRegistration(EarthClient.Error.notCommitted(hash: "AB")))
    }

    /// The sheet's registration fee covers the chain's default schedule
    /// with the quote's headroom (else every registration is re-asked), and
    /// fits inside the backend's 100,000 uerth gas grant at the validator's
    /// price (else the grant cannot pay it).
    func testTheRegistrationEstimateCoversItsQuote() {
        typealias E = PrivateTxEngine
        // Three proofs (the passport's, two actions'), the DSC and ciphertexts.
        let bytes = UInt64(3 * E.proofBytes + 4_096)
        let gas = E.baseGas + E.txByteGas * bytes + E.bundleGas + 2 * E.actionGas + E.registerGas
        let quoted = gas + max(gas / 10, E.minHeadroom)
        XCTAssertLessThanOrEqual(quoted, PrivacyWallet.registerGasEstimate)
        XCTAssertLessThanOrEqual(E.feeFor(price: Decimal(string: "0.005")!, gas: PrivacyWallet.registerGasEstimate), 100_000)
    }

    func testTheReaskNoteNamesBothFees() {
        let n = TxGate.reaskNote(fee: 52_000, shown: 35_000)
        XCTAssertTrue(n.hasPrefix("Nothing was sent."))
        XCTAssertTrue(n.contains(Token.erth.format("52000")))
        XCTAssertTrue(n.contains(Token.erth.format("35000")))
    }
}
