import BigInt
import Foundation
import XCTest
@testable import EarthCore

/// The wallet home's Shield / Unshield limits.
final class ShieldMoveTests: XCTestCase {
    func note(_ value: UInt64, _ pos: UInt64, denom: String = "uerth", spent: Bool = false, pending: Bool = false) -> OwnedNote {
        OwnedNote(position: pos, height: 1, note: NotePlaintext(denom: denom, value: value, rho: .one, rcm: .one), cm: .one, nf: .one,
                  spentHeight: spent ? 2 : nil, pendingAt: pending ? 1 : nil)
    }

    func testMaxSpendableTakesTheLargestSpendableNotes() {
        let notes = [note(5, 1), note(40, 2), note(30, 3), note(20, 4), note(99, 5, spent: true), note(98, 6, pending: true), note(97, 7, denom: "uanml")]
        XCTAssertEqual(90, NoteSelection.maxSpendable(notes, denom: "uerth", maxNotes: 3))
        XCTAssertEqual(95, NoteSelection.maxSpendable(notes, denom: "uerth", maxNotes: 16))
        XCTAssertEqual(70, NoteSelection.maxSpendable(notes, denom: "uerth", maxNotes: 2))
        XCTAssertEqual(97, NoteSelection.maxSpendable(notes, denom: "uanml", maxNotes: 16))
        XCTAssertEqual(0, NoteSelection.maxSpendable([], denom: "uerth", maxNotes: 16))
    }

    /// Max is every note one bundle carries; at Max the fee comes out of the amount.
    func testMaxUnshieldIsEveryNoteABundleCarries() {
        let ns = (1 ... 20).map { note(10, UInt64($0)) }
        XCTAssertEqual(160, ShieldMove.maxUnshield(ns, maxNotes: 16))
        XCTAssertEqual(200, ShieldMove.maxUnshield(ns, maxNotes: 32))
        XCTAssertEqual(0, ShieldMove.maxUnshield([], maxNotes: 16))
        XCTAssertTrue(ShieldMove.feeFromAmount(amount: 160, spendable: 160, fee: 3))
        XCTAssertFalse(ShieldMove.feeFromAmount(amount: 150, spendable: 160, fee: 3))
    }

    func testMaxShieldLeavesTheFee() {
        XCTAssertEqual(BigInt(90), ShieldMove.maxShield(public: 100, fee: 10))
        XCTAssertEqual(BigInt(0), ShieldMove.maxShield(public: 5, fee: 10))
    }
}
