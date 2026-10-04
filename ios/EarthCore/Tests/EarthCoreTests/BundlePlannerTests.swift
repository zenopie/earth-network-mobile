import BigInt
import XCTest
@testable import EarthCore

/// The bundle planner: notes chosen per denom (any number), change per denom,
/// spends and outputs paired into actions (mixed assets allowed), padded to
/// two, capped at max_actions_per_bundle, and every bundle balancing under
/// its own binding signature with every action's witness satisfying the
/// circuit. Ports BundlePlannerTest.kt.
final class BundlePlannerTests: XCTestCase {
    let keys = try! PrivacyKeys.fromMnemonic("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about")
    let bob = try! PrivacyKeys.fromMnemonic("legal winner thank year wave sausage worth useful legal winner thank yellow")

    struct Wallet { let tree: MerkleTree; let notes: [OwnedNote] }

    /// A tree holding `values` as our notes, among strangers' notes.
    func wallet(_ values: [(String, [UInt64])]) -> Wallet {
        let tree = MerkleTree(store: MemNodeStore())
        var notes: [OwnedNote] = []
        for (denom, vs) in values {
            for v in vs {
                tree.append(PrivacyHash.cm(asset: PrivacyHash.assetID("uerth"), value: 1, pc: NotePlaintext.randomField()))
                let n = NotePlaintext.fresh(denom, v)
                let cm = n.cm(ownerPK: keys.ownerPK)
                let pos = tree.append(cm)
                notes.append(OwnedNote(position: pos, height: 1, note: n, cm: cm, nf: PrivacyHash.nf(nk: keys.nk, rho: n.rho, position: pos)))
            }
        }
        return Wallet(tree: tree, notes: notes)
    }

    /// Proves nothing; checks every action against the circuit and the bundle's binding signature.
    func verify(_ p: BundlePlan) async throws {
        let sighash = NotePlaintext.randomField()
        let bundle = try await p.prove(sighash: sighash) { w in
            try w.check()
            XCTAssertEqual(Grumpkin.valueCommit(assetSpend: w.sAsset, vSpend: w.sValue, assetOut: w.oAsset, vOut: w.oValue, rcv: w.rcv), w.cv)
            return Data([0])
        }
        XCTAssertTrue(PrivateMsgs.checkBalance(bundle, sighash: sighash))
        XCTAssertEqual(p.actions.count, Set(p.nullifiers).count)
        XCTAssertGreaterThanOrEqual(p.actions.count, BundlePlan.minActions)
        for b in bundle.balances { XCTAssertGreaterThan(b.amount, 0) }
    }

    func balances(_ p: BundlePlan) -> [String: UInt64] { Dictionary(uniqueKeysWithValues: p.balances.map { ($0.denom, $0.amount) }) }

    func testErthPaymentFrom1To20Notes() async throws {
        for n in 1 ... 20 {
            let w = wallet([("uerth", Array(repeating: 1_000, count: n))])
            let amount = UInt64(n) * 1_000 - 500 - 300
            let out = try NoteOut.to(bob.address, denom: "uerth", value: amount)
            let p = try BundleBuilder.plan(keys: keys, tree: w.tree, notes: w.notes, outputs: [out], release: ["uerth": 500], maxActions: 32)
            XCTAssertEqual(n, p.spends.count, "n=\(n) spends")
            XCTAssertEqual(["uerth": 500], balances(p))
            XCTAssertEqual(max(n, 2), p.actions.count)
            let change = p.actions.map(\.out).filter { $0.note != nil && $0.pc == $0.note!.pc(ownerPK: keys.ownerPK) }
            XCTAssertEqual([300], change.map(\.value))
            try await verify(p)
        }
    }

    func testMultiAssetBundlePairsAcrossAssets() async throws {
        let w = wallet([("uerth", [5_000, 7_000]), ("uanml", [100, 200, 300])])
        let outs = [try NoteOut.to(bob.address, denom: "uanml", value: 550), try NoteOut.to(bob.address, denom: "uerth", value: 4_000)]
        let p = try BundleBuilder.plan(keys: keys, tree: w.tree, notes: w.notes, outputs: outs, release: ["uerth": 1_000, "uanml": 20], maxActions: 16)
        XCTAssertEqual(["uanml": 20, "uerth": 1_000], balances(p))
        XCTAssertEqual(["uanml", "uerth"], p.balances.map(\.denom))
        // ANML: 570 needs all three (600, change 30); ERTH: 5,000 is one note exactly.
        XCTAssertEqual(4, p.spends.count)
        XCTAssertEqual(4, p.actions.count)
        try await verify(p)
    }

    func testFeeOnlyAndPadding() async throws {
        let w = wallet([("uerth", [10_000])])
        let p = try BundleBuilder.plan(keys: keys, tree: w.tree, notes: w.notes, outputs: [], release: ["uerth": 10_000], maxActions: 16)
        // One spend, no output at all (the fee is the whole note): padded to two actions.
        XCTAssertEqual(2, p.actions.count)
        XCTAssertEqual(1, p.spends.count)
        XCTAssertTrue(p.actions.allSatisfy { $0.out.value == 0 })
        try await verify(p)
        XCTAssertEqual(2, Set(p.nullifiers).count)
    }

    func testCapsAtMaxActions() async throws {
        let w = wallet([("uerth", Array(repeating: 100, count: 10))])
        let out = [try NoteOut.to(bob.address, denom: "uerth", value: 700)]
        XCTAssertThrowsError(try BundleBuilder.plan(keys: keys, tree: w.tree, notes: w.notes, outputs: out, release: ["uerth": 100], maxActions: 4)) {
            XCTAssertTrue($0 is NoteSelection.Insufficient)
        }
        let p = try BundleBuilder.plan(keys: keys, tree: w.tree, notes: w.notes, outputs: out, release: ["uerth": 100], maxActions: 8)
        XCTAssertEqual(8, p.spends.count)
        try await verify(p)
        XCTAssertThrowsError(try BundleBuilder.plan(keys: keys, tree: w.tree, notes: w.notes, outputs: out, release: ["uerth": 400], maxActions: 32)) {
            XCTAssertTrue($0 is NoteSelection.Insufficient)
        }
    }

    func testSelectionPrefersOneNoteThenFewest() throws {
        let w = wallet([("uerth", [50, 400, 300, 1_000, 20])])
        XCTAssertEqual([400], try NoteSelection.cover(w.notes, denom: "uerth", amount: 350).map(\.note.value))
        // 1,000 + 400 covers 1,300; the 400 is swapped for the smallest that still covers (300).
        XCTAssertEqual([1_000, 300], try NoteSelection.cover(w.notes, denom: "uerth", amount: 1_300).map(\.note.value))
        XCTAssertEqual(5, try NoteSelection.cover(w.notes, denom: "uerth", amount: 1_770).count)
    }

    func testValueCommitmentsSumToTheBalance() throws {
        // bvk = sum cv - sum value*G = (sum rcv)*R for any honest plan.
        let w = wallet([("uerth", [3_000, 4_000]), ("uanml", [9])])
        let p = try BundleBuilder.plan(keys: keys, tree: w.tree, notes: w.notes, outputs: [try NoteOut.to(bob.address, denom: "uanml", value: 9)],
                                       release: ["uerth": 6_000], maxActions: 16)
        var bvk = p.cvs.reduce(Grumpkin.Point.infinity, +)
        for b in p.balances { bvk = bvk - Grumpkin.valueBase(PrivacyHash.assetID(b.denom)) * BigUInt(b.amount) }
        XCTAssertEqual(Grumpkin.r * p.bindingKey(), bvk)
        XCTAssertEqual(Merkle.depth, try p.witness(0, sighash: .one).sPath.count)
    }

    func testStakeSelectionSpendsAtMostTwo() throws {
        func s(_ pos: UInt64, _ amount: UInt64, _ label: StakeLabel? = nil) -> OwnedStakeNote {
            OwnedStakeNote(position: pos, height: 1, denom: "derth/v", amount: amount, rho: .one, rcm: .one, cm: .one, nf: .one, label: label)
        }
        let amount: (OwnedStakeNote) -> UInt64 = { $0.amount }
        let ns = [s(1, 50), s(2, 400), s(3, 300), s(4, 1_000)]
        XCTAssertEqual([400], try StakeSelection.cover(ns, amount: 350, free: amount).map(\.amount))
        XCTAssertEqual([400, 1_000], try StakeSelection.cover(ns, amount: 1_350, free: amount).map(\.amount))
        XCTAssertThrowsError(try StakeSelection.cover(ns, amount: 1_500, free: amount)) {
            XCTAssertEqual("this stake is spread over more notes than one transaction spends; merge them first (one fee each), then try again",
                           ($0 as? NoteSelection.Insufficient)?.message)
        }
        XCTAssertThrowsError(try StakeSelection.cover(ns, amount: 2_000, free: amount)) {
            XCTAssertEqual("insufficient stake", ($0 as? NoteSelection.Insufficient)?.message)
        }
        XCTAssertEqual([1_000, 400], StakeSelection.merge(ns, free: amount).map(\.amount))
        // At most one labelled note a proof; a labelled note gives up only its unexposed part while its window is open.
        let ls = [s(1, 500, StakeLabel(moveKey: .one, moveTime: 1, exposed: 100)), s(2, 600, StakeLabel(moveKey: Fr(UInt64(2)), moveTime: 1, exposed: 100)),
                  s(3, 50)]
        let free: (OwnedStakeNote) -> UInt64 = { $0.amount - ($0.label?.exposed ?? 0) }
        XCTAssertEqual([50, 600], try StakeSelection.cover(ls, amount: 520, free: free).map(\.amount))
        XCTAssertThrowsError(try StakeSelection.cover(ls, amount: 700, free: free))
        XCTAssertThrowsError(try StakeSelection.cover(ls, amount: 1_000, free: free, locked: { "held" })) {
            XCTAssertEqual("held", ($0 as? NoteSelection.Insufficient)?.message)
        }
        XCTAssertEqual([600, 50], StakeSelection.merge(ls, free: free).map(\.amount))
    }
}
