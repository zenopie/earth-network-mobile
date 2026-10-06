import XCTest
@testable import EarthCore

/// Every private msg built by the wallet, against the chain's gogoproto
/// Marshal of the same msg and the chain's own Sighash of it
/// (tools/privacyvectors). Ports PrivateMsgsTest.kt.
final class PrivateMsgsTests: XCTestCase {
    let chainID = "earth-1"
    func fb(_ i: UInt64) -> Data { Vectors.fe(i).bytes }
    func bs(_ s: String) -> Data { Data(s.utf8) }
    func addr(_ b: Int) -> String {
        try! Bech32.encode(hrp: "earth", data: Bech32.convertBits((0 ..< 20).map { UInt8(b + $0) }, from: 8, to: 5, pad: true))
    }

    func bundle(_ seed: UInt64, _ bal: (String, UInt64)...) -> ShieldedBundle {
        var actions: [ShieldedAction] = []
        for i in 0 ..< 2 as Range<UInt64> {
            let x = seed * 10 + i
            let cv = Grumpkin.valueCommit(assetSpend: PrivacyHash.assetID("uerth"), vSpend: 1000 + x, assetOut: PrivacyHash.assetID("uanml"),
                                          vOut: x, rcv: Vectors.fe(x + 500))
            actions.append(ShieldedAction(anchor: fb(x + 1), nullifier: fb(x + 2), commitment: fb(x + 3), cv: cv.bytes, ciphertext: bs("ct-\(x)"),
                                  proof: Data([0xde, 0xad, UInt8(truncatingIfNeeded: x)])))
        }
        return ShieldedBundle(actions: actions, balances: bal.map { ValueBalance(denom: $0.0, amount: $0.1) },
                      bindingSig: Data(repeating: UInt8(truncatingIfNeeded: seed), count: 96))
    }

    func fee(_ seed: UInt64, _ amount: UInt64) -> ShieldedBundle { bundle(seed, ("uerth", amount)) }

    let zero32 = Data(count: 32)

    /// A deterministic 177-byte stand-in for an amount-blind ciphertext (main.go bct).
    func bct(_ seed: Int) -> Data { Data((0 ..< 177).map { UInt8(truncatingIfNeeded: seed + $0) }) }

    /// A deterministic 201-byte stand-in for a wallet stake ciphertext (main.go sct).
    func sct(_ seed: Int) -> Data { Data((0 ..< 201).map { UInt8(truncatingIfNeeded: seed ^ $0) }) }

    /// main.go stakeProof: lane A nullifiers, its output, the credit lane, a clear_before and debt root.
    func stake(_ seed: UInt64, _ spends: Int, _ creates: Bool, credits: Bool = false, clears: Bool = true) -> StakeProof {
        StakeProof(proof: Data([0x5e, UInt8(truncatingIfNeeded: seed)]), anchor: fb(seed),
                   nullifiers: (0 ..< 2).map { $0 < spends ? fb(seed + 1 + UInt64($0)) : zero32 }, ownerTag: fb(seed + 8),
                   commitment: creates ? fb(seed + 3) : zero32, ciphertext: creates ? sct(Int(seed)) : Data(),
                   creditNullifier: credits ? fb(seed + 4) : zero32, creditCommitment: credits ? fb(seed + 5) : zero32,
                   creditCiphertext: credits ? sct(Int(seed) + 1) : Data(),
                   clearBefore: clears ? 1_790_000_000 + seed : 0, debtRoot: clears ? fb(seed + 6) : zero32)
    }

    func membership(_ seed: UInt64) -> Membership {
        Membership(proof: Data([0xbe, 0xef, UInt8(truncatingIfNeeded: seed)]), root: fb(seed + 100), nullifier: fb(seed + 101))
    }

    /// main.go moveProof.
    func moveProof(_ seed: UInt64) -> MoveProof {
        MoveProof(proof: Data([0x30, 0x7e, UInt8(truncatingIfNeeded: seed)]), root: fb(seed + 100), oldNullifier: fb(seed + 101), newNullifier: fb(seed + 102))
    }

    let validator = "earthvaloper1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"
    let opts = [WeightedVoteOption(option: WeightedVoteOption.yes, weight: "0.700000000000000000"),
                WeightedVoteOption(option: WeightedVoteOption.no, weight: "0.300000000000000000")]
    func w(_ o: UInt64, _ p: UInt64) -> Msg.AllocationWeight { Msg.AllocationWeight(optionID: o, percent: p) }

    func register(_ handle: String) -> MsgRegisterPrivate {
        MsgRegisterPrivate(
            fee: fee(40, 2000), proof: Data([1, 2, 3]), publicSignals: ["250930", "12345", "678", "9"],
            signatureAlgorithm: "lean_poa", dscDer: Data([0x30, 0x03, 1, 2, 3]), idc: fb(41), pcAnml: fb(42),
            ciphertextAnml: bct(42), pcErth: fb(43), ciphertextErth: bct(43),
            affiliateHandle: handle
        )
    }

    /// The handle vectors' shielded address (owner_pk OwnerPK(7), ek_pub of ek 01..20).
    var zaddr: String { Vectors.json["handle_address"] as! String }

    lazy var msgs: [String: any ProtoMessage] = [
        "send": MsgSend(bundle: fee(10, 1500), fee: 1500),
        "send_tx_fields": MsgSend(bundle: fee(11, 1500), fee: 1500),
        "send_no_gas": MsgSend(bundle: fee(12, 1500), fee: 1500),
        "unshield": MsgSend(bundle: bundle(20, ("uanml", 5000), ("uerth", 2000)), receiver: addr(1), fee: 2000),
        "shield": MsgShield(sender: addr(30), amount: Coin(denom: "uerth", amount: "100000"), pc: fb(31), ciphertext: bct(31)),
        "register": register("alice-01"),
        "register_no_affiliate": register(""),
        "claim_anml": MsgClaimAnmlPrivate(fee: fee(50, 2000), membership: membership(50), day: 20360, pc: fb(51), ciphertext: bct(51)),
        "set_caretaker": MsgSetCaretaker(fee: fee(60, 2000), membership: membership(60), percentages: [w(1, 60), w(7, 40)], maxPredecessor: 1_750_000_000),
        "set_caretaker_no_bound": MsgSetCaretaker(fee: fee(61, 2000), membership: membership(61), percentages: [w(2, 100)], maxPredecessor: PrivacyHash.noBound),
        "move_caretaker": MsgMoveCaretaker(fee: fee(62, 2000), move: moveProof(62)),
        "bind_handle": MsgBindHandle(fee: fee(70, 2000), membership: membership(70), handle: "alice-01", address: zaddr, maxPredecessor: 1_750_000_000),
        "bind_handle_release": MsgBindHandle(fee: fee(71, 2000), membership: membership(71), handle: "", address: "", maxPredecessor: PrivacyHash.noBound),
        "move_handle": MsgMoveHandle(fee: fee(72, 2000), move: moveProof(72), handle: "alice-01"),
        "vote_proposal": MsgVoteProposalPrivate(fee: fee(80, 2000), membership: membership(80), proposalID: 5, option: .yes),
        "propose_removal": MsgProposeRemoval(fee: fee(81, 2000), membership: membership(81), optionID: 3),
        "vote_removal": MsgVoteRemoval(fee: fee(82, 2000), membership: membership(82), optionID: 3, option: .no),
        "delegate": MsgShieldedDelegate(bundle: fee(90, 502_000), validator: validator, amount: 500_000, derth: 449_995, stake: stake(90, 1, true)),
        "delegate_young": MsgShieldedDelegate(bundle: fee(91, 502_000), validator: validator, amount: 500_000, derth: 449_995,
                                              stake: stake(91, 1, true, clears: false)),
        "restake": MsgRestake(bundle: fee(95, 2000), validator: validator, stake: stake(95, 2, true)),
        "undelegate": MsgShieldedUndelegate(bundle: fee(100, 2000), validator: validator, amount: 400_000, stake: stake(100, 1, true),
                                            pc: fb(101), ciphertext: bct(101)),
        "undelegate_whole": MsgShieldedUndelegate(bundle: fee(102, 2000), validator: validator, amount: 400_000, stake: stake(102, 2, true),
                                                  pc: fb(103), ciphertext: bct(103)),
        "stake_vote": MsgStakeVote(bundle: fee(120, 2000), proposalID: 5, validator: validator, options: opts, weight: 400_000,
                                   proof: Data([0x70, 0x7e]), voteNullifiers: [fb(121), fb(122)], debtRoot: DebtTree.emptyRoot.bytes),
        "stake_vote_two": MsgStakeVote(bundle: fee(127, 2000), proposalID: 6, validator: validator, options: opts, weight: 999,
                                       proof: Data([0x70, 0x7e]), voteNullifiers: [fb(128), fb(129)], debtRoot: fb(130)),
        "lock_position": MsgLockPosition(bundle: fee(140, 2000), validator: validator, amount: 400_000, splits: [w(2, 100)], stake: stake(140, 1, true)),
        "update_position": MsgUpdatePosition(bundle: fee(150, 2000), positionID: 9, splits: [w(2, 100)], stake: stake(150, 0, false)),
        "unlock_position": MsgUnlockPosition(bundle: fee(160, 2000), positionID: 9, stake: stake(160, 1, true)),
        "position_vote": MsgPositionVote(bundle: fee(170, 2000), positionID: 9, proposalID: 5, options: opts, stake: stake(170, 0, false)),
        "redelegate": MsgRedelegate(bundle: fee(175, 2000), srcValidator: validator, dstValidator: Vectors.json["validator2"] as! String,
                                    amount: 400_000, stake: stake(175, 1, true, credits: true), dstDerth: 380_000, moveTime: 1_790_000_123),
        "note_swap": MsgNoteSwap(bundle: bundle(180, ("uanml", 300_000), ("uerth", 2000)), denomIn: "uanml", amountIn: 300_000, denomOut: "uerth",
                                 minAmountOut: 123_456, pc: fb(181), ciphertext: bct(181)),
        "note_swap_to_anml": MsgNoteSwap(bundle: fee(200, 302_000), denomIn: "uerth", amountIn: 300_000, denomOut: "uanml", minAmountOut: 1,
                                         pc: fb(201), ciphertext: bct(201)),
        "add_liquidity_shielded": MsgAddLiquidityShielded(bundle: bundle(210, ("uanml", 700_000), ("uerth", 902_500)), poolID: 1, minShares: "777",
                                                          refundPC: fb(211), refundCiphertext: bct(211), sharePC: fb(212),
                                                          shareCiphertext: bct(212), erthAmount: 900_000),
        "add_liquidity_shielded_no_min": MsgAddLiquidityShielded(bundle: bundle(230, ("uanml", 700_000), ("uerth", 902_500)), poolID: 1, minShares: "",
                                                                 refundPC: fb(231), refundCiphertext: bct(231), sharePC: fb(232),
                                                                 shareCiphertext: bct(232), erthAmount: 900_000),
        "remove_liquidity_shielded": MsgRemoveLiquidityShielded(bundle: bundle(240, ("dexlp/1", 4242), ("uerth", 2000)), poolID: 1,
                                                                erthPC: fb(241), erthCiphertext: bct(241), tokenPC: fb(242), tokenCiphertext: bct(242)),
        "remove_liquidity_pc": Msg.RemoveLiquidity(creator: addr(30), poolID: 1, shares: Coin(denom: "dexlp/1", amount: "4242"), pc: fb(250),
                                                   ciphertext: bct(250)),
        "buy_anml": MsgBuyAnml(creator: addr(30), tokenIn: Coin(denom: "uerth", amount: "5000000"), minAmountOut: "99", pc: fb(260), ciphertext: bct(4)),
    ]

    func testEncodingsAndSighashesMatchTheChain() throws {
        let want = Vectors.obj("msgs")
        XCTAssertEqual(want.count, msgs.count)
        for (name, m) in msgs {
            let v = want[name] as! [String: Any]
            XCTAssertEqual(v["proto"] as? String, Vectors.hex(m.encoded()), "\(name) proto")
            if let sighash = v["sighash"] as? String {
                let pm = try XCTUnwrap(m as? any PrivateMsg, name)
                XCTAssertEqual(v["type_url"] as? String, pm.typeURL, "\(name) type")
                let tx = PrivateMsgs.TxFields(memo: v["memo"] as! String, timeoutHeight: (v["timeout_height"] as! NSNumber).uint64Value,
                                              gasLimit: (v["gas_limit"] as! NSNumber).uint64Value)
                XCTAssertEqual(sighash, try pm.sighash(chainID: chainID, tx: tx).hex, "\(name) sighash")
                XCTAssertEqual(UInt64(v["total_fee"] as! String), pm.totalFee, "\(name) total fee")
                XCTAssertEqual(UInt64(v["private_fee"] as! String), pm.privateFee, "\(name) private fee")
                // And it decodes back to itself.
                let back = try PrivateMsgs.decode(typeURL: pm.typeURL, value: pm.encoded())
                XCTAssertEqual(Vectors.hex(back.encoded()), Vectors.hex(pm.encoded()), "\(name) round trip")
            }
        }
    }

    func testStakeFieldsMatchTheChain() throws {
        let want = Vectors.json["stake_fields_redelegate"] as! [String]
        let got = try PrivateMsgs.stakeFields(stake(175, 1, true, credits: true))
        XCTAssertEqual(want, got.map(\.hex))
    }

    /// The stake and vote circuits' public inputs, as the chain lays them out per msg (FakeChain checks every witness against this layout).
    func testPublicInputLayoutsMatchTheChain() throws {
        let want = Vectors.obj("public_inputs")
        for name in ["delegate", "undelegate", "redelegate"] {
            let m = msgs[name] as! any PrivateMsg
            let got = try ChainLayout.stakePublicInputs(m.stakeProof!, ChainLayout.lanes(m), Vectors.fe(77))
            XCTAssertEqual(want[name] as? [String], got.map(\.hex), name)
            XCTAssertEqual(16, got.count)
        }
        let vote = msgs["stake_vote_two"] as! MsgStakeVote
        let got = try ChainLayout.votePublicInputs(vote, noteRoot: Vectors.fe(131), nfRoot: Vectors.fe(132), sighash: Vectors.fe(77))
        XCTAssertEqual(want["stake_vote"] as? [String], got.map(\.hex))
        XCTAssertEqual(9, got.count)
    }

    func testBindingAndFieldEncodings() throws {
        let b = Vectors.obj("registration_binding")
        XCTAssertEqual(b["with_affiliate"] as? String, try (msgs["register"] as! MsgRegisterPrivate).binding(chainID: chainID).hex)
        XCTAssertEqual(b["none"] as? String, try (msgs["register_no_affiliate"] as! MsgRegisterPrivate).binding(chainID: chainID).hex)
        XCTAssertEqual(Vectors.json["options_bytes"] as? String, Vectors.hex(try PrivateMsgs.optionsBytes(opts)))
        XCTAssertEqual(Vectors.json["splits_bytes"] as? String, Vectors.hex(PrivateMsgs.splitsBytes([w(1, 60), w(7, 40)])))
    }

    func testLegacyDec() throws {
        XCTAssertEqual("1.000000000000000000", try PrivateMsgs.legacyDec("1"))
        XCTAssertEqual("0.700000000000000000", try PrivateMsgs.legacyDec("0.7"))
        XCTAssertEqual("0.300000000000000000", try PrivateMsgs.legacyDec("0.3000000000000000000"))
        XCTAssertThrowsError(try PrivateMsgs.legacyDec("0"))
        XCTAssertThrowsError(try PrivateMsgs.legacyDec("1.1"))
        XCTAssertThrowsError(try PrivateMsgs.legacyDec("0.1234567890123456789"))
        XCTAssertThrowsError(try PrivateMsgs.legacyDec("abc"))
    }

    func testUnsignedTxMatchesTheChain() throws {
        let v = Vectors.obj("unsigned_tx")
        let raw = UnsignedTx.build(msgs["claim_anml"] as! any PrivateMsg, gasLimit: (v["gas_limit"] as! NSNumber).uint64Value,
                                   memo: v["memo"] as! String, timeoutHeight: (v["timeout_height"] as! NSNumber).uint64Value)
        XCTAssertEqual(v["tx_raw"] as? String, Vectors.hex(raw))
        let back = try UnsignedTx.decode(raw)
        XCTAssertEqual(v["memo"] as? String, back.memo)
        XCTAssertEqual((v["timeout_height"] as! NSNumber).uint64Value, back.timeoutHeight)
        XCTAssertEqual(0, back.signatures)
        XCTAssertEqual(0, back.signerInfos)
        XCTAssertEqual("2000", back.feeCoins.first?.amount)
    }

    /// The membership proof's public inputs (4a663d5): max_predecessor after max_activation.
    func testMembershipPublicInputsMatchTheChain() throws {
        let v = Vectors.obj("membership_public_inputs")
        let ins = v["inputs"] as! [String]
        func h(_ k: String) -> Fr { Vectors.fr(v[k] as! String) }
        let maxAct = (v["max_activation"] as! NSNumber).uint64Value, maxPred = (v["max_predecessor"] as! NSNumber).uint64Value
        let mine = [h("root"), h("scope"), h("nullifier"), h("signal"), h("excluded_dsc"), h("excluded_country"),
                    PrivacyHash.u64(maxAct), PrivacyHash.u64(maxPred)]
        XCTAssertEqual(8, ins.count)
        XCTAssertEqual(PrivacyHash.noBound, maxAct)
        XCTAssertEqual(ins, mine.map(\.hex))
    }

    /// The move proof's public inputs (8acf58f): root, scope, old_nullifier, new_nullifier, signal.
    func testMovePublicInputsMatchTheChain() throws {
        let v = Vectors.obj("move_public_inputs")
        let ins = v["inputs"] as! [String]
        func h(_ k: String) -> Fr { Vectors.fr(v[k] as! String) }
        XCTAssertEqual(PrivacyHash.handleScope(), h("scope"))
        let m = moveProof(72)
        XCTAssertEqual(h("root").bytes, m.root)
        XCTAssertEqual(h("old_nullifier").bytes, m.oldNullifier)
        XCTAssertEqual(h("new_nullifier").bytes, m.newNullifier)
        XCTAssertEqual(5, ins.count)
        XCTAssertEqual(ins, [h("root"), h("scope"), h("old_nullifier"), h("new_nullifier"), h("signal")].map(\.hex))
        // The witness lays them out the same way.
        let z = [Fr](repeating: .zero, count: Merkle.depth)
        let w = try MoveWitness(oldSecret: Vectors.fe(1), newSecret: Vectors.fe(2), successionIndex: 0, successionSiblings: z, dscKey: .zero, country: .zero,
                                activatedAt: 0, predecessorAt: 0, leafIndex: 1, siblings: z, root: h("root"), scope: h("scope"), signal: h("signal"))
        XCTAssertEqual([h("root"), h("scope"), PrivacyHash.scopeNullifier(idSecret: Vectors.fe(1), scope: h("scope")),
                        PrivacyHash.scopeNullifier(idSecret: Vectors.fe(2), scope: h("scope")), h("signal")], w.publicInputs())
    }

    /// A bind's address must be canonical; a release binds Bytes(""), 0, Bytes("").
    func testBindHandleFields() throws {
        let rel = MsgBindHandle(fee: fee(71, 2000), membership: membership(71), handle: "", address: "", maxPredecessor: 0)
        XCTAssertEqual([PrivacyHash.bytes(Data()), .zero, PrivacyHash.bytes(Data())], try rel.sighashFields())
        XCTAssertThrowsError(try MsgBindHandle(fee: fee(70, 2000), membership: membership(70), handle: "alice-01", address: zaddr.uppercased(), maxPredecessor: 0).sighashFields())
        XCTAssertThrowsError(try MsgBindHandle(fee: fee(70, 2000), membership: membership(70), handle: "Alice", address: zaddr, maxPredecessor: 0).sighashFields())
        XCTAssertThrowsError(try MsgBindHandle(fee: fee(70, 2000), membership: membership(70), handle: "alice", address: "", maxPredecessor: 0).sighashFields())
        let reg = register("alice-01")
        let b = Vectors.obj("registration_binding")
        XCTAssertEqual(b["affiliate_field"] as? String, try reg.affiliateField().hex)
        XCTAssertEqual(b["affiliate_field"] as? String, PrivacyHash.affiliateField(handle: "alice-01").hex)
    }

    /// The module accounts, canonical weights, calendar dates.
    func testModuleAccountsCanonicalWeightsAndCalendarDates() throws {
        let mods = Vectors.json["module_accounts"] as! [String: String]
        XCTAssertEqual(Set(PrivateMsgs.moduleAccounts), Set(mods.keys))
        for name in PrivateMsgs.moduleAccounts {
            XCTAssertEqual(name, PrivateMsgs.moduleAccount(of: Data(try Bech32.decode(mods[name]!).data)))
        }
        let dec = Vectors.json["legacy_dec"] as! [String: String]
        XCTAssertEqual(dec["1"], try PrivateMsgs.legacyDec("1"))
        XCTAssertEqual(dec["0.5"], try PrivateMsgs.legacyDec("0.5"))
        XCTAssertEqual(["1.000000000000000000"], try PrivateMsgs.canonicalOptions([WeightedVoteOption(option: 1, weight: "1")]).map(\.weight))
        XCTAssertTrue(PrivateMsgs.isCalendarDate("261001"))
        XCTAssertFalse(PrivateMsgs.isCalendarDate("250231"))
        XCTAssertTrue(PrivateMsgs.isCalendarDate("240229"))
        XCTAssertFalse(PrivateMsgs.isCalendarDate("250229"))
        // Midnight UTC, as the chain's yymmddToUnix.
        XCTAssertEqual(1_790_812_800, PrivateMsgs.calendarDateUnix("261001"))
        XCTAssertEqual(1_709_164_800, PrivateMsgs.calendarDateUnix("240229"))
        XCTAssertEqual(951_868_800, PrivateMsgs.calendarDateUnix("000301"))
        XCTAssertEqual(4_102_358_400, PrivateMsgs.calendarDateUnix("991231"))
        XCTAssertNil(PrivateMsgs.calendarDateUnix("250229"))
    }

    /// legacyDec: a non-ASCII digit is refused, never a trap.
    func testLegacyDecRefusesNonAsciiDigits() {
        XCTAssertThrowsError(try PrivateMsgs.legacyDec("0.\u{0665}"))
        XCTAssertThrowsError(try PrivateMsgs.legacyDec("\u{0661}"))
        XCTAssertEqual("0.500000000000000000", try PrivateMsgs.legacyDec("0.5"))
    }
}
