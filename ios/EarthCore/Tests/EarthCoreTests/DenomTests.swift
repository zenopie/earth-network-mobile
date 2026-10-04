import BigInt
import Foundation
import XCTest
@testable import EarthCore

/// Denoms: only well-formed SDK denoms, learned from our own notes or the
/// chain's asset list, never the wallet's internal "asset/<hex>" name; and a
/// send-disabled denom refused with a clear error.
final class DenomTests: PrivacyTestCase {
    func withAmount(_ r: NoteRow, _ amount: String) -> NoteRow {
        NoteRow(position: r.position, height: r.height, cm: r.cm, ciphertext: r.ciphertext, amount: amount, ownerPK: r.ownerPK, rho: r.rho, rcm: r.rcm)
    }

    func testDenomsFollowTheSdkRuleAndNeverTheInternalName() {
        XCTAssertTrue(Denoms.valid("uerth"))
        XCTAssertTrue(Denoms.valid("derth/earthvaloper1abc"))
        XCTAssertTrue(Denoms.valid("unbond/earthvaloper1abc/42"))
        XCTAssertFalse(Denoms.valid("asset/0a"))
        XCTAssertFalse(Denoms.valid("ab"))
        XCTAssertFalse(Denoms.valid("1abc"))
        XCTAssertFalse(Denoms.valid("a b c"))
        XCTAssertFalse(Denoms.valid(String(repeating: "a", count: 129)))
        var d = AssetDenoms()
        XCTAssertFalse(d.learn("asset/" + PrivacyHash.assetID("uerth").hex))
        // An id the chain states for a denom is learned only if it is the denom's own.
        XCTAssertFalse(d.learn("ufoo", id: PrivacyHash.assetID("ubar")))
        XCTAssertTrue(d.learn("ufoo", id: PrivacyHash.assetID("ufoo")))
        XCTAssertEqual("ufoo", d.resolve(PrivacyHash.assetID("ufoo")))
    }

    func testAnAssetSentinelOrMalformedRowAmountIsRefusedNotStoredAndNeverDuplicates() async throws {
        let chain = FakeChain()
        let w = try wallet(chain, alice)
        let good = try mintTo(chain, w, "uerth", 1_000)
        let relabeled = try mintTo(chain, w, "uerth", 1_000_000)
        let broken = try mintTo(chain, w, "uerth", 2_000)
        // A hostile indexer relabels a mint as the internal "asset/<hex>" name of uerth's own id,
        // and serves another with a non-hex sentinel.
        chain.notes[relabeled] = withAmount(chain.notes[relabeled], "1000000asset/" + PrivacyHash.assetID("uerth").hex)
        chain.notes[broken] = withAmount(chain.notes[broken], "2000asset/zz")
        try await w.sync()
        XCTAssertTrue(w.notes.allSatisfy { !$0.note.denom.hasPrefix(NotePlaintext.unresolvedPrefix) })
        XCTAssertEqual([UInt64(good)], w.notes.map(\.position))
        try await w.sync()
        XCTAssertEqual(1, w.notes.count)
        XCTAssertEqual(UInt64(chain.notes.count), w.store.state.notesNext)
    }

    func testAStoredSentinelNoteIsRenamedBack() async throws {
        let chain = FakeChain()
        let w = try wallet(chain, alice)
        try mintTo(chain, w, "uerth", 5_000)
        try await w.sync()
        // As a store an earlier version let an indexer relabel.
        w.store.mutate { $0.notes[0] = $0.notes[0].withDenom(NotePlaintext.unresolvedPrefix + PrivacyHash.assetID("uerth").hex) }
        try await w.sync()
        XCTAssertEqual("uerth", w.store.state.notes[0].note.denom)
        XCTAssertEqual(5_000, w.balances()["uerth"])
    }

    func testDenomsAreLearnedOnlyFromOwnNotesOrTheChainsAssetList() async throws {
        let chain = FakeChain()
        let a = try wallet(chain, alice)
        let b = try wallet(chain, bob)
        try await register(chain, a, passport: "601")
        // Rows not ours, each with a junk denom in the amount column.
        for i in 0 ..< 40 {
            let n = NotePlaintext.fresh("uerth", 7)
            let pos = chain.notes.count
            chain.shield("uerth", 7, n.pc(ownerPK: Fr(UInt64(1000 + i))), try NoteCipher.encryptBlind(n, to: b.keys.address))
            chain.notes[pos] = withAmount(chain.notes[pos], "7junk\(i)")
        }
        try mintTo(chain, a, "ufoo", 9_000)
        try await a.sync()
        XCTAssertTrue(a.store.state.denoms.allSatisfy { !$0.hasPrefix("junk") })
        XCTAssertTrue(a.store.state.denoms.contains("ufoo"))
        // b learns ufoo from a v1 note only through the chain's asset list (id checked).
        try await b.sync()
        _ = try await a.send(to: b.keys.address, denom: "ufoo", amount: 4_000)
        chain.assetList = [("ufoo", PrivacyHash.assetID("ubar"))]
        try await b.sync()
        XCTAssertEqual([NotePlaintext.unresolvedPrefix + PrivacyHash.assetID("ufoo").hex], b.notes.map(\.note.denom))
        chain.assetList = [("ufoo", PrivacyHash.assetID("ufoo"))]
        try await b.sync()
        XCTAssertEqual(["ufoo"], b.notes.map(\.note.denom))
        XCTAssertEqual(4_000, b.balances()["ufoo"])
    }

    func testSendDisabledIsRefusedOnSwapsAndDelegationWithAClearError() async throws {
        let chain = FakeChain()
        let a = try wallet(chain)
        for _ in 0 ..< 3 { try funded(chain, a, 2_000_000) }
        try await a.sync()
        chain.sendDisabled = ["uerth"]
        let sent = chain.txs.count
        do {
            _ = try await a.delegate(validator: validator, amount: 100_000)
            XCTFail("delegated a send-disabled denom")
        } catch {
            let text = try XCTUnwrap(ChainErrors.explain(error))
            XCTAssertTrue(text.contains("private staking"), text)
        }
        await assertThrowsAsync({ try await a.noteSwap(denomIn: "uerth", amountIn: 100_000, denomOut: "uanml", minOut: 1) })
        XCTAssertEqual(sent, chain.txs.count)
        XCTAssertNotNil(ChainErrors.explain(code: 5, codespace: "bank", log: "uerth transfers are currently disabled: send transactions are disabled"))
    }
}
