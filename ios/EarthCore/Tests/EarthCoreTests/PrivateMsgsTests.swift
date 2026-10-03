import XCTest
@testable import EarthCore

/// Every private msg built by the wallet, against the chain's gogoproto
/// Marshal of the same msg and the chain's own Sighash of it
/// (android/tools/orchardvectors). Ports PrivateMsgsTest.kt.
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

    func stake(_ seed: UInt64, _ spends: Int, _ creates: Int, mints: Bool = false) -> StakeProof {
        StakeProof(proof: Data([0x5e, UInt8(truncatingIfNeeded: seed)]), anchor: fb(seed),
                   nullifiers: (0 ..< 2).map { $0 < spends ? fb(seed + 1 + UInt64($0)) : zero32 },
                   commitments: (0 ..< 2).map { $0 < creates ? fb(seed + 3 + UInt64($0)) : zero32 },
                   ciphertexts: (0 ..< 2).map { $0 < creates ? bs("sct-\(seed)-\($0)") : Data() },
                   spcMint: fb(seed + 7), ownerTag: fb(seed + 8), spcCiphertext: mints ? bct(Int(seed)) : Data())
    }

    func membership(_ seed: UInt64) -> Membership {
        Membership(proof: Data([0xbe, 0xef, UInt8(truncatingIfNeeded: seed)]), root: fb(seed + 100), nullifier: fb(seed + 101))
    }

    let validator = "earthvaloper1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"
    let opts = [WeightedVoteOption(option: WeightedVoteOption.yes, weight: "0.7"),
                WeightedVoteOption(option: WeightedVoteOption.no, weight: "0.300000000000000000")]
    func w(_ o: UInt64, _ p: UInt64) -> Msg.AllocationWeight { Msg.AllocationWeight(optionID: o, percent: p) }

    func register(_ affiliate: String) -> MsgRegisterPrivate {
        MsgRegisterPrivate(
            fee: fee(40, 2000), proof: Data([1, 2, 3]), publicSignals: ["250930", "12345", "678", "9"],
            signatureAlgorithm: "lean_poa", dscDer: Data([0x30, 0x03, 1, 2, 3]), idc: fb(41), pcAnml: fb(42),
            ciphertextAnml: bct(42), pcErth: fb(43), ciphertextErth: bct(43), affiliate: affiliate
        )
    }

    lazy var msgs: [String: any ProtoMessage] = [
        "send": MsgSend(bundle: fee(10, 1500), fee: 1500),
        "send_tx_fields": MsgSend(bundle: fee(11, 1500), fee: 1500),
        "send_no_gas": MsgSend(bundle: fee(12, 1500), fee: 1500),
        "unshield": MsgSend(bundle: bundle(20, ("uanml", 5000), ("uerth", 2000)), receiver: addr(1), fee: 2000),
        "shield": MsgShield(sender: addr(30), amount: Coin(denom: "uerth", amount: "100000"), pc: fb(31), ciphertext: bct(31)),
        "register": register(addr(50)),
        "register_no_affiliate": register(""),
        "claim_anml": MsgClaimAnmlPrivate(fee: fee(50, 2000), membership: membership(50), day: 20360, pc: fb(51), ciphertext: bct(51)),
        "set_caretaker": MsgSetCaretaker(fee: fee(60, 2000), membership: membership(60), percentages: [w(1, 60), w(7, 40)], maxActivation: 1_780_000_000),
        "bind_referrer": MsgBindReferrer(fee: fee(70, 2000), membership: membership(70), address: addr(50), maxActivation: 1_780_000_000),
        "bind_referrer_clear": MsgBindReferrer(fee: fee(71, 2000), membership: membership(71), address: "", maxActivation: 1_780_000_000),
        "vote_proposal": MsgVoteProposalPrivate(fee: fee(80, 2000), membership: membership(80), proposalID: 5, option: .yes),
        "propose_removal": MsgProposeRemoval(fee: fee(81, 2000), membership: membership(81), optionID: 3),
        "vote_removal": MsgVoteRemoval(fee: fee(82, 2000), membership: membership(82), optionID: 3, option: .no),
        "delegate": MsgShieldedDelegate(bundle: fee(90, 502_000), validator: validator, amount: 500_000, stake: stake(90, 0, 0, mints: true)),
        "restake": MsgRestake(bundle: fee(95, 2000), validator: validator, stake: stake(95, 2, 1)),
        "undelegate": MsgShieldedUndelegate(bundle: fee(100, 2000), validator: validator, amount: 400_000, stake: stake(100, 1, 1, mints: true)),
        "claim_unbonding": MsgClaimUnbonding(validator: validator, epoch: 17, amount: 400_000, pc: fb(111), ciphertext: bct(111), feeFromOutput: 2000,
                                             stake: stake(110, 2, 0)),
        "claim_unbonding_fee_bundle": MsgClaimUnbonding(bundle: fee(115, 2000), validator: validator, epoch: 17, amount: 400_000, pc: fb(116),
                                                        ciphertext: bct(116), stake: stake(117, 1, 1)),
        "stake_vote": MsgStakeVote(bundle: fee(120, 2000), proposalID: 5, validator: validator, options: opts, weight: 400_000,
                                   stake: stake(120, 2, 0, mints: true)),
        "lock_position": MsgLockPosition(bundle: fee(140, 2000), validator: validator, amount: 400_000, splits: [w(2, 100)], stake: stake(140, 1, 1)),
        "update_position": MsgUpdatePosition(bundle: fee(150, 2000), positionID: 9, splits: [w(2, 100)], stake: stake(150, 0, 0)),
        "unlock_position": MsgUnlockPosition(bundle: fee(160, 2000), positionID: 9, stake: stake(160, 0, 0, mints: true)),
        "position_vote": MsgPositionVote(bundle: fee(170, 2000), positionID: 9, proposalID: 5, options: opts, stake: stake(170, 0, 0)),
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
        let want = Vectors.json["stake_fields_undelegate"] as! [String]
        let got = try PrivateMsgs.stakeFields(stake(100, 1, 1, mints: true))
        XCTAssertEqual(want, got.map(\.hex))
    }

    func testBindingAndFieldEncodings() throws {
        let b = Vectors.obj("registration_binding")
        XCTAssertEqual(b["with_affiliate"] as? String, try (msgs["register"] as! MsgRegisterPrivate).binding().hex)
        XCTAssertEqual(b["none"] as? String, try (msgs["register_no_affiliate"] as! MsgRegisterPrivate).binding().hex)
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
}
