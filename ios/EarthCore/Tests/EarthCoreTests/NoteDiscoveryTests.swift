import BigInt
import Foundation
import XCTest
@testable import EarthCore

/// Notes the wallet finds and holds: an open note only by its owner_pk and
/// cm, a split payout's chunks each its own note, and amounts near the top
/// of u64 that saturate rather than trap.
final class NoteDiscoveryTests: PrivacyTestCase {
    func testAnOpenNoteIsOursOnlyByOwnerPKAndCm() async throws {
        let chain = FakeChain()
        let a = try wallet(chain, alice), b = try wallet(chain, bob)
        let o = PrivacyHash.referralOpening(nullifier: Fr(UInt64(4242)), leafIndex: 3)
        let pos = chain.mintOpen("uerth", 5_000_000, ownerPK: a.keys.ownerPK, rho: o.rho, rcm: o.rcm)
        chain.emptyBlock()
        // A row naming alice's owner_pk but a cm of another amount is not a note of hers.
        let cmOther = PrivacyHash.cm(asset: PrivacyHash.assetID("uerth"), value: 6_000_000, pc: PrivacyHash.pc(ownerPK: a.keys.ownerPK, rho: o.rho, rcm: o.rcm))
        let lie = chain.noteTree.append(cmOther)
        chain.notes.append(NoteRow(position: lie, height: chain.height, cm: cmOther, ciphertext: Data(), amount: "5000000uerth",
                                   ownerPK: a.keys.ownerPK, rho: o.rho, rcm: o.rcm))
        chain.emptyBlock()
        try await a.sync(); try await b.sync()
        XCTAssertEqual([pos], a.notes.map(\.position))
        XCTAssertEqual(5_000_000, bal(a))
        XCTAssertTrue(b.notes.isEmpty)
        // Spendable: alice sends from it.
        _ = try await a.send(to: b.address, denom: "uerth", amount: 1_000_000)
        try await b.sync()
        XCTAssertEqual(1_000_000, bal(b))
        dump(chain, "openNote")
    }

    func testASplitPayoutIsOneCiphertextAtSeveralPositions() async throws {
        let chain = FakeChain()
        let a = try wallet(chain, alice)
        // MintNoteSplit: one pc and one ciphertext, several notes, each its own amount and position
        // (the test's chunks are ones this wallet holds; the chain cuts at 2^64-1).
        let out = try await a.withdrawalNote()
        let values: [UInt64] = [3_000_000, 3_000_000, 1_234]
        let ps = chain.mintSplit("uanml", values, out.pc, out.ciphertext)
        chain.emptyBlock()
        try await a.sync()
        let got = a.notes.filter { ps.contains($0.position) }.sorted { $0.position < $1.position }
        XCTAssertEqual(values, got.map(\.note.value))
        // One rho and rcm, a nullifier per position: every chunk spends on its own.
        XCTAssertEqual(1, Set(got.map { $0.note.rho.hex + $0.note.rcm.hex }).count)
        XCTAssertEqual(3, Set(got.map(\.nf)).count)
        for n in got { XCTAssertEqual(PrivacyHash.nf(nk: a.keys.nk, rho: n.note.rho, position: n.position), n.nf) }
        XCTAssertEqual(6_001_234, bal(a, "uanml"))
        XCTAssertEqual(got[0].note, got[1].note)
        XCTAssertNotEqual(got[0], got[1])
    }

    /// Amounts the chain (or an indexer) publishes near u64's top saturate in sums, never trap.
    func testHugeAmountsNeverTrap() async throws {
        let chain = FakeChain()
        let a = try wallet(chain)
        // Past 2^63-1 a value is not one the wallet holds (as Android): ignored, never wrapped.
        let o0 = try a.shieldOutput(denom: "uerth", amount: 0)
        chain.mint("uerth", UInt64.max - 5, o0.pc, o0.ciphertext)
        for _ in 0 ..< 3 {
            let o = try a.shieldOutput(denom: "uerth", amount: 0)
            chain.mint("uerth", UInt64(Int64.max) - 5, o.pc, o.ciphertext)
        }
        chain.emptyBlock()
        try await a.sync()
        XCTAssertEqual(3, a.notes.count)
        // Balance totals saturate at 2^63-1, as Android.
        XCTAssertEqual(UInt64(Int64.max), bal(a, "uerth"))
        XCTAssertEqual(UInt64.max, a.snapshot.unshieldableErth)
        XCTAssertEqual(UInt64.max, PrivateMsgs.saturatingAdd(UInt64.max, 1))
        // Two notes that must both be spent sum past u64: refused, not a trap.
        XCTAssertThrowsError(try BundleBuilder.plan(keys: a.keys, tree: a.store.noteTree, notes: a.store.state.notes, outputs: [],
                                                    release: ["uerth": UInt64.max - 2], maxActions: 16,
                                                    forced: Array(a.store.state.notes.prefix(2))))
        // A msg whose bundles' uerth sums past u64 has a saturated balance.
        let b = ShieldedBundle(balances: [ValueBalance(denom: "uerth", amount: UInt64.max)])
        let m = MsgRemoveLiquidityShielded(bundle: b, poolID: 1, erthPC: Data(), tokenPC: Data())
        XCTAssertEqual(UInt64.max, m.privateFee)
        let d = MsgShieldedDelegate(bundle: ShieldedBundle(balances: [ValueBalance(denom: "uerth", amount: 5)]), validator: validator, amount: 9,
                                    derth: 8, stake: StakeProof(proof: Data(), anchor: Data(), nullifiers: [], ownerTag: Data(), commitment: Data(),
                                                                ciphertext: Data(), creditNullifier: Data(), creditCommitment: Data(),
                                                                creditCiphertext: Data(), clearBefore: 0, debtRoot: Data()))
        XCTAssertEqual(0, d.privateFee)
    }
}
