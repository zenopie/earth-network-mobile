import BigInt
import XCTest
@testable import EarthCore

/// Poseidon2, zk/privacy and zk/merkle against the chain's own outputs
/// (ports ZkVectorsTest.kt).
final class ZkVectorsTests: XCTestCase {
    func testFieldArithmetic() {
        let p = Fr.modulus
        var rng = SystemRandomNumberGenerator()
        for _ in 0 ..< 300 {
            let a = BigUInt.randomInteger(withMaximumWidth: 256, using: &rng) % p
            let b = BigUInt.randomInteger(withMaximumWidth: 256, using: &rng) % p
            XCTAssertEqual((a * b) % p, (Fr(a) * Fr(b)).bigUInt)
            XCTAssertEqual((a + b) % p, (Fr(a) + Fr(b)).bigUInt)
            XCTAssertEqual((a + p - b) % p, (Fr(a) - Fr(b)).bigUInt)
            XCTAssertEqual(a, try Fr(bytes: Fr(a).bytes).bigUInt)
        }
        let pm1 = p - 1
        XCTAssertEqual(BigUInt(1), (Fr(pm1) * Fr(pm1)).bigUInt)
        XCTAssertThrowsError(try Fr(bytes: p.serialize()))
        XCTAssertEqual(BigUInt(UInt64.max), Fr(UInt64.max).bigUInt)
        XCTAssertEqual("0x0", Fr.zero.noir)
        XCTAssertEqual("0xff", Fr(UInt64(255)).noir)
    }

    func testPoseidon2() {
        for v in Vectors.json["poseidon2"] as! [[String: Any]] {
            let inputs = (v["in"] as! [String]).map(Vectors.fr)
            XCTAssertEqual(v["out"] as? String, Poseidon2.hash(inputs).hex, "arity \(inputs.count)")
        }
    }

    func testTagsAssetsBytesCountry() {
        let tags = Vectors.obj("tags")
        let mine: [String: Fr] = [
            "id": PrivacyHash.tagID, "owner": PrivacyHash.tagOwner, "leaf": PrivacyHash.tagLeaf, "sn": PrivacyHash.tagSN,
            "pc": PrivacyHash.tagPC, "cm": PrivacyHash.tagCM, "nf": PrivacyHash.tagNF, "reg": PrivacyHash.tagReg,
            "asset": PrivacyHash.tagAsset, "signal": PrivacyHash.tagSignal, "bytes": PrivacyHash.tagBytes, "scope": PrivacyHash.tagScope,
            "stake": PrivacyHash.tagStake, "spc": PrivacyHash.tagSPC, "snf": PrivacyHash.tagSNF, "otag": PrivacyHash.tagOTag,
            "gen": Grumpkin.tagGen, "cv_r": Grumpkin.tagCvR, "bsig": Grumpkin.tagBsig, "bundle": PrivateMsgs.tagBundle,
        ]
        XCTAssertEqual(tags.count, mine.count)
        for (k, v) in mine { XCTAssertEqual(tags[k] as? String, v.hex, k) }

        let assets = Vectors.obj("asset_ids")
        for (d, h) in assets { XCTAssertEqual(h as? String, PrivacyHash.assetID(d).hex, d) }
        XCTAssertEqual(assets["uerth"] as? String, PrivacyHash.assetErth.hex)

        for (h, want) in Vectors.obj("bytes") { XCTAssertEqual(want as? String, PrivacyHash.bytes(Vectors.unhex(h)).hex, h) }
        for (k, want) in Vectors.obj("country") { XCTAssertEqual(want as? String, PrivacyHash.countryField(k).hex, k) }
    }

    func testDerivations() {
        let d = Vectors.obj("derive")
        func s(_ k: String) -> String { d[k] as! String }
        let idSecret = Vectors.fr(s("id_secret"))
        let nk = Vectors.fr(s("nk"))
        let rho = Vectors.fr(s("rho"))
        let rcm = Vectors.fr(s("rcm"))
        XCTAssertEqual(Vectors.fe(1001), idSecret)
        let idc = PrivacyHash.idc(idSecret)
        XCTAssertEqual(s("idc"), idc.hex)
        let opk = PrivacyHash.ownerPK(nk)
        XCTAssertEqual(s("owner_pk"), opk.hex)
        XCTAssertEqual(s("leaf"), PrivacyHash.identityLeaf(idc: idc, dscKey: Vectors.fr(s("leaf_dsc")), country: PrivacyHash.countryField("DE"), activatedAt: 1_790_000_000).hex)
        XCTAssertEqual(s("sn"), PrivacyHash.scopeNullifier(idSecret: idSecret, scope: PrivacyHash.claimScope(day: 20360)).hex)
        let pc = PrivacyHash.pc(ownerPK: opk, rho: rho, rcm: rcm)
        XCTAssertEqual(s("pc"), pc.hex)
        XCTAssertEqual(s("cm"), PrivacyHash.cm(asset: PrivacyHash.assetID("uanml"), value: 1_000_000, pc: pc).hex)
        XCTAssertEqual(s("nf"), PrivacyHash.nf(nk: nk, rho: rho, position: 4_000_000_000).hex)
        XCTAssertEqual(s("reg_none"), PrivacyHash.registrationBinding(idc: idc, pcAnml: Vectors.fe(1), pcErth: Vectors.fe(2), affiliate: .zero).hex)
        // The stake tree.
        let spc = PrivacyHash.stakePC(ownerPK: opk, rho: rho, rcm: rcm)
        XCTAssertEqual(s("spc"), spc.hex)
        XCTAssertEqual(s("stake_cm"), PrivacyHash.stakeCM(asset: PrivacyHash.assetID("derth/earthvaloper1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"),
                                                         amount: 1_800_000, spc: spc).hex)
        XCTAssertEqual(s("stake_nf"), PrivacyHash.stakeNF(nk: nk, rho: rho, position: 4_000_000_000).hex)
        XCTAssertEqual(Vectors.fe(1006), Vectors.fr(s("otag_salt")))
        XCTAssertEqual(s("otag"), PrivacyHash.ownerTag(ownerPK: opk, salt: Vectors.fe(1006)).hex)
    }

    func testScopes() {
        let s = Vectors.obj("scopes")
        XCTAssertEqual(s["claim_20360"] as? String, PrivacyHash.claimScope(day: 20360).hex)
        XCTAssertEqual(s["caretaker"] as? String, PrivacyHash.caretakerScope().hex)
        XCTAssertEqual(s["referrer"] as? String, PrivacyHash.referrerScope().hex)
        XCTAssertEqual(s["proposal_5_0"] as? String, PrivacyHash.proposalScope(proposalID: 5, round: 0).hex)
        XCTAssertEqual(s["proposal_5_1"] as? String, PrivacyHash.proposalScope(proposalID: 5, round: 1).hex)
        XCTAssertEqual(s["removal_3"] as? String, PrivacyHash.removalScope(ballotID: 3).hex)
        XCTAssertEqual(s["propose_removal_2_100"] as? String, PrivacyHash.proposeRemovalScope(optionID: 2, day: 100).hex)
        XCTAssertEqual(s["gas_202610"] as? String, PrivacyHash.gasScope(yyyymm: 202610).hex)
    }

    private func checkTree(_ t: MerkleTree, oneByOne: Bool) {
        let m = Vectors.obj("merkle")
        let zeros = m["zeros"] as! [String]
        for i in 0 ... Merkle.depth { XCTAssertEqual(zeros[i], Merkle.zero[i].hex) }
        let roots = m["roots"] as! [String: String]
        let seed = (m["leaf_seed"] as! NSNumber).uint64Value
        var n: UInt64 = 0
        for cp in roots.keys.compactMap(UInt64.init).sorted() {
            let batch = (n ..< cp).map { Vectors.fe(seed + $0) }
            if oneByOne { batch.forEach { t.append($0) } } else { t.appendAll(batch) }
            n = cp
            XCTAssertEqual(roots["\(cp)"], t.root().hex, "root at \(cp)")
        }
        for (k, want) in m["paths"] as! [String: [String]] {
            let idx = UInt64(k)!
            let got = t.path(idx)
            XCTAssertEqual(want, got.map(\.hex), "path \(k)")
            XCTAssertEqual(t.root(), Merkle.rootFromPath(leaf: t.leaf(idx), index: idx, siblings: got))
        }
        t.update(5, .zero)
        XCTAssertEqual(m["root_37_zeroed_5"] as? String, t.root().hex)
    }

    func testMerkleIncremental() { checkTree(MerkleTree(store: MemNodeStore()), oneByOne: true) }

    func testMerkleBatched() { checkTree(MerkleTree(store: MemNodeStore()), oneByOne: false) }

    func testMerkleOnDisk() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("tree-\(UUID())")
        let store = FileNodeStore(directory: dir)
        checkTree(MerkleTree(store: store), oneByOne: false)
        let root = MerkleTree(store: store, size: 37).root()
        store.close()
        XCTAssertEqual(root, MerkleTree(store: FileNodeStore(directory: dir), size: 37).root())
        XCTAssertFalse(root.isZero)
    }

    func testRootAtMatchesAPrefixTree() {
        let full = MerkleTree(store: MemNodeStore())
        let prefix = MerkleTree(store: MemNodeStore())
        for i in 0 ..< 23 as Range<UInt64> {
            full.append(Vectors.fe(500 + i))
            if i < 13 { prefix.append(Vectors.fe(500 + i)) }
        }
        XCTAssertEqual(prefix.root(), full.rootAt(13))
        XCTAssertEqual(prefix.path(7), full.pathAt(7, size: 13))
    }

    func testHashThroughput() {
        let n = 2000
        let t0 = Date()
        var x = Fr.one
        for _ in 0 ..< n { x = Poseidon2.hash([x, x]) }
        print("poseidon2 arity-2: \(Int(Date().timeIntervalSince(t0) * 1e6) / n)us/hash")
    }
}
