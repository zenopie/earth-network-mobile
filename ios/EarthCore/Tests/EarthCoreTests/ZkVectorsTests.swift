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

    /// Chain 203d3b2: the referral note's opening the chain derives
    /// (zk/privacy.ReferralOpening) and the pc / cm a handle owner's wallet
    /// recomputes from the mint row (5 ERTH to OwnerPK(7100+i)).
    func testReferralOpening() throws {
        let a = Vectors.json["referral_opening"] as! [[String: Any]]
        XCTAssertEqual(5, a.count)
        for (i, v) in a.enumerated() {
            let o = PrivacyHash.referralOpening(nullifier: Vectors.fr(v["nullifier"] as! String), leafIndex: (v["leaf_index"] as! NSNumber).uint64Value)
            XCTAssertEqual(v["rho"] as? String, o.rho.hex, "rho \(i)")
            XCTAssertEqual(v["rcm"] as? String, o.rcm.hex, "rcm \(i)")
            let owner = Vectors.fr(v["owner_pk"] as! String)
            let pc = PrivacyHash.pc(ownerPK: owner, rho: o.rho, rcm: o.rcm)
            XCTAssertEqual(v["pc"] as? String, pc.hex, "pc \(i)")
            XCTAssertEqual(v["cm"] as? String, PrivacyHash.cm(asset: PrivacyHash.assetID("uerth"), value: 5_000_000, pc: pc).hex, "cm \(i)")
        }
    }

    func testTagsAssetsBytesCountry() {
        let tags = Vectors.obj("tags")
        let mine: [String: Fr] = [
            "id": PrivacyHash.tagID, "owner": PrivacyHash.tagOwner, "leaf": PrivacyHash.tagLeaf, "sn": PrivacyHash.tagSN,
            "pc": PrivacyHash.tagPC, "cm": PrivacyHash.tagCM, "nf": PrivacyHash.tagNF, "reg": PrivacyHash.tagReg,
            "asset": PrivacyHash.tagAsset, "signal": PrivacyHash.tagSignal, "bytes": PrivacyHash.tagBytes, "scope": PrivacyHash.tagScope, "affiliate": PrivacyHash.tagAffiliate, "referral": PrivacyHash.tagReferral,
            "stake": PrivacyHash.tagStake, "spc": PrivacyHash.tagSPC, "snf": PrivacyHash.tagSNF, "otag": PrivacyHash.tagOTag,
            "snfl": PrivacyHash.tagSNFL, "vnf": PrivacyHash.tagVNF, "slabel": PrivacyHash.tagSLabel, "debtl": PrivacyHash.tagDebtL,
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
        XCTAssertEqual(s("leaf"), PrivacyHash.identityLeaf(idc: idc, dscKey: Vectors.fr(s("leaf_dsc")), country: PrivacyHash.countryField("DE"),
                                                           activatedAt: 1_790_000_000, predecessorAt: 0).hex)
        // A switched or re-entered identity's leaf commits to predecessor_at.
        XCTAssertEqual(s("leaf_pred"), PrivacyHash.identityLeaf(idc: idc, dscKey: Vectors.fr(s("leaf_dsc")), country: PrivacyHash.countryField("DE"),
                                                                activatedAt: 1_790_000_000, predecessorAt: 1_790_000_000).hex)
        XCTAssertEqual(s("sn"), PrivacyHash.scopeNullifier(idSecret: idSecret, scope: PrivacyHash.claimScope(day: 20360)).hex)
        let pc = PrivacyHash.pc(ownerPK: opk, rho: rho, rcm: rcm)
        XCTAssertEqual(s("pc"), pc.hex)
        XCTAssertEqual(s("cm"), PrivacyHash.cm(asset: PrivacyHash.assetID("uanml"), value: 1_000_000, pc: pc).hex)
        XCTAssertEqual(s("nf"), PrivacyHash.nf(nk: nk, rho: rho, position: 4_000_000_000).hex)
        XCTAssertEqual(s("reg_none"), PrivacyHash.registrationBinding(chainID: "earth-1", idc: idc, pcAnml: Vectors.fe(1), ctAnml: Data("anml".utf8),
                                                                      pcErth: Vectors.fe(2), ctErth: Data("erth".utf8), affiliate: .zero).hex)
        // The chain's pinned vector (zk/privacy TestRegistrationBindingPinned; the chain id since audit 6, B6-4).
        XCTAssertEqual("148b3513a501b6ff9c02314f355cb83fb544e22b2a9df79552fe49c944424159", s("reg_pinned"))
        XCTAssertEqual(s("reg_pinned"), PrivacyHash.registrationBinding(chainID: "earth-1", idc: Fr(UInt64(1)), pcAnml: Fr(UInt64(2)),
                                                                        ctAnml: Data("anml".utf8), pcErth: Fr(UInt64(3)), ctErth: Data("erth".utf8),
                                                                        affiliate: .zero).hex)
        // Another network's binding differs: a registration does not replay across chains.
        XCTAssertEqual(s("reg_testnet"), PrivacyHash.registrationBinding(chainID: "earth-testnet-1", idc: Fr(UInt64(1)), pcAnml: Fr(UInt64(2)),
                                                                         ctAnml: Data("anml".utf8), pcErth: Fr(UInt64(3)), ctErth: Data("erth".utf8),
                                                                         affiliate: .zero).hex)
        // The stake tree.
        let spc = PrivacyHash.stakePC(ownerPK: opk, rho: rho, rcm: rcm)
        XCTAssertEqual(s("spc"), spc.hex)
        let derthAsset = PrivacyHash.assetID("derth/earthvaloper1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq")
        XCTAssertEqual(s("stake_cm"), PrivacyHash.stakeCM(asset: derthAsset, amount: 1_800_000, spc: spc, label: .zero).hex)
        // Slash labels and the debt tree's leaves (chain dff3a9b), and zk/debt TestNoirParity's pins (= Noir test_go_parity_debt).
        let label = PrivacyHash.stakeLabel(moveKey: Vectors.fe(1009), moveTime: 1_790_000_000, exposed: 400_000)
        XCTAssertEqual(s("stake_label"), label.hex)
        XCTAssertEqual(s("stake_cm_labelled"), PrivacyHash.stakeCM(asset: derthAsset, amount: 1_800_000, spc: spc, label: label).hex)
        XCTAssertEqual(s("debt_leaf"), PrivacyHash.debtLeaf(key: Vectors.fe(1010), nextKey: Vectors.fe(1011), nextIndex: 4_000_000_000, retained: 123_456).hex)
        XCTAssertEqual(s("debt_leaf_1_2_3_4"), PrivacyHash.debtLeaf(key: Fr(UInt64(1)), nextKey: Fr(UInt64(2)), nextIndex: 3, retained: 4).hex)
        XCTAssertEqual("0b28cc858d976ddad0ede75ca9538f9b5ab36538964f6e241b8e89be2711e82a", s("debt_leaf_1_2_3_4"))
        XCTAssertEqual("2dfbc154973d1d3ec6e03137ba41b5c2cf5119f66cc69e3e58c033a77c80a881",
                       PrivacyHash.stakeLabel(moveKey: Fr(UInt64(0x4d4b)), moveTime: 1000, exposed: 200).hex)
        XCTAssertEqual(s("stake_label_4d4b"), PrivacyHash.stakeLabel(moveKey: Fr(UInt64(0x4d4b)), moveTime: 1000, exposed: 200).hex)
        XCTAssertEqual("0ffc538b4162732774bd5026e7a07bd00d2fe406af55231fa0c255c321ad4232",
                       PrivacyHash.stakeCM(asset: Fr(UInt64(1)), amount: 2, spc: Fr(UInt64(3)), label: Fr(UInt64(4))).hex)
        XCTAssertEqual(s("stake_cm_1_2_3_4"), PrivacyHash.stakeCM(asset: Fr(UInt64(1)), amount: 2, spc: Fr(UInt64(3)), label: Fr(UInt64(4))).hex)
        XCTAssertEqual(s("stake_nf"), PrivacyHash.stakeNF(nk: nk, rho: rho, position: 4_000_000_000).hex)
        XCTAssertEqual(Vectors.fe(1006), Vectors.fr(s("otag_salt")))
        XCTAssertEqual(s("otag"), PrivacyHash.ownerTag(ownerPK: opk, salt: Vectors.fe(1006)).hex)
        // Stake votes (ORCHARD_DESIGN 15), and the design's golden values (= Noir test_go_parity).
        XCTAssertEqual(s("nf_leaf_1_2_3"), PrivacyHash.nfLeaf(value: Fr(UInt64(1)), nextValue: Fr(UInt64(2)), nextIndex: 3).hex)
        XCTAssertEqual("0cdc3a81748c6389efaa3a6c29b7f4609a8e9f860230b70413e8bef512978276", s("nf_leaf_1_2_3"))
        XCTAssertEqual(s("nf_leaf"), PrivacyHash.nfLeaf(value: Vectors.fe(1007), nextValue: Vectors.fe(1008), nextIndex: 4_000_000_000).hex)
        XCTAssertEqual(s("vote_nf"), PrivacyHash.voteNF(nk: nk, rho: rho, position: 4_000_000_000, proposalID: 5).hex)
        XCTAssertEqual(s("vote_nf_5eed"), PrivacyHash.voteNF(nk: Fr(UInt64(0x5eed)), rho: Fr(UInt64(0xa1)), position: 1, proposalID: 7).hex)
        XCTAssertEqual("1ada84dad3e6afde3f370e97edf4df2ee4eeb6b1400d5c5f41882552f578ba2f", s("vote_nf_5eed"))
    }

    /// The slash debt tree against zk/debt: roots by write (a row rewritten in place), witnesses for rows and absent moves.
    func testDebtTree() throws {
        let dj = Vectors.obj("debt")
        XCTAssertEqual(dj["empty_root"] as? String, DebtTree.emptyRoot.hex)
        XCTAssertEqual("0cea3d3e26cd2710109d7cbff5bf48570ba54332f812d538893f0958007f6903", dj["empty_root"] as? String)
        let rows = (dj["rows"] as! [[String: Any]]).map { (key: Vectors.fr($0["key"] as! String), retained: ($0["retained"] as! NSNumber).uint64Value) }
        let t = try DebtTree(rows)
        XCTAssertEqual(dj["root"] as? String, t.root().hex)
        // Every write's root: the rows inserted so far, each at its latest retained then.
        let writes = (0 ..< 9).map { (key: Vectors.fe(9000 + UInt64($0)), retained: 1_000 * UInt64($0 + 1)) } + [(key: Vectors.fe(9002), retained: UInt64(7))]
        for (k, root) in dj["roots_by_write"] as! [String: String] {
            var keys: [Fr] = [], latest: [Fr: UInt64] = [:]
            for w in writes.prefix(Int(k)!) {
                if latest[w.key] == nil { keys.append(w.key) }
                latest[w.key] = w.retained
            }
            XCTAssertEqual(root, try DebtTree(keys.map { ($0, latest[$0]!) }).root().hex, "root after \(k) writes")
        }
        for w in dj["witnesses"] as! [[String: Any]] {
            let key = Vectors.fr(w["key"] as! String)
            let mine = try XCTUnwrap(t.witness(key))
            XCTAssertEqual(w["low_key"] as? String, mine.lowKey.hex)
            XCTAssertEqual(w["low_next_key"] as? String, mine.lowNextKey.hex)
            XCTAssertEqual((w["low_next_index"] as! NSNumber).uint64Value, mine.lowNextIndex)
            XCTAssertEqual((w["low_retained"] as! NSNumber).uint64Value, mine.lowRetained)
            XCTAssertEqual((w["low_index"] as! NSNumber).uint64Value, mine.lowIndex)
            XCTAssertEqual(w["low_path"] as? [String], mine.lowPath.map(\.hex))
            XCTAssertEqual((w["retained"] as! NSNumber).uint64Value, mine.retained(key: key, exposed: (w["exposed"] as! NSNumber).uint64Value, root: t.root()))
        }
        // A row's own key read through its low leaf, or against another root, proves nothing.
        let slashed = Vectors.fe(9002)
        XCTAssertEqual(7, t.retainedOf(slashed))
        XCTAssertNil(t.witness(Fr(UInt64(1)))!.retained(key: slashed, exposed: 50_000, root: t.root()))
        XCTAssertNil(t.witness(slashed)!.retained(key: slashed, exposed: 50_000, root: DebtTree.emptyRoot))
        XCTAssertThrowsError(try DebtTree([(slashed, 1), (slashed, 2)]))
        XCTAssertThrowsError(try DebtTree([(Fr.zero, 1)]))
    }

    /// The stake nullifier indexed tree against zk/indexed: roots by insert count, non-membership witnesses.
    func testIndexedTree() throws {
        let ix = Vectors.obj("indexed")
        XCTAssertEqual(ix["empty_root"] as? String, IndexedTree.emptyRoot.hex)
        XCTAssertEqual("18f5a2d2d3273f584793e90ac9bf77abf0ff2a05a101cd5943eaf7bbd0bd5b10", ix["empty_root"] as? String)
        let vs = (ix["values"] as! [String]).map(Vectors.fr)
        for (k, want) in ix["roots"] as! [String: String] {
            XCTAssertEqual(want, try IndexedTree(Array(vs.prefix(Int(k)!))).root().hex, k)
        }
        let t = try IndexedTree(vs)
        XCTAssertTrue(vs.allSatisfy { t.contains($0) && t.nonMembership($0) == nil })
        XCTAssertNil(t.nonMembership(.zero))
        for w in ix["witnesses"] as! [[String: Any]] {
            let v = Vectors.fr(w["value"] as! String)
            let mine = try XCTUnwrap(t.nonMembership(v))
            XCTAssertEqual(w["low_value"] as? String, mine.lowValue.hex)
            XCTAssertEqual(w["low_next_value"] as? String, mine.lowNextValue.hex)
            XCTAssertEqual((w["low_next_index"] as! NSNumber).uint64Value, mine.lowNextIndex)
            XCTAssertEqual((w["low_index"] as! NSNumber).uint64Value, mine.lowIndex)
            XCTAssertEqual(w["low_path"] as! [String], mine.lowPath.map(\.hex))
            XCTAssertTrue(mine.proves(v, root: t.root()))
        }
        // The chain refuses what it never inserts.
        XCTAssertThrowsError(try IndexedTree([vs[0], vs[0]]))
        XCTAssertThrowsError(try IndexedTree([.zero]))
    }

    func testScopes() {
        let s = Vectors.obj("scopes")
        XCTAssertEqual(s["claim_20360"] as? String, PrivacyHash.claimScope(day: 20360).hex)
        XCTAssertEqual(s["caretaker"] as? String, PrivacyHash.caretakerScope().hex)
        XCTAssertEqual(s["handle"] as? String, PrivacyHash.handleScope().hex)
        XCTAssertEqual(s["proposal_5_0"] as? String, PrivacyHash.proposalScope(proposalID: 5, round: 0).hex)
        XCTAssertEqual(s["proposal_5_1"] as? String, PrivacyHash.proposalScope(proposalID: 5, round: 1).hex)
        XCTAssertEqual(s["removal_3"] as? String, PrivacyHash.removalScope(ballotID: 3).hex)
        XCTAssertEqual(s["propose_removal_2_100"] as? String, PrivacyHash.proposeRemovalScope(optionID: 2, day: 100).hex)
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
