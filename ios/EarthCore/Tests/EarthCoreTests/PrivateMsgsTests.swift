import XCTest
@testable import EarthCore

/// Every private msg built by the wallet, against the chain's gogoproto
/// Marshal of the same msg and the chain's own Signal() of it
/// (tools/privacyvectors). Ports PrivateMsgsTest.kt.
final class PrivateMsgsTests: XCTestCase {
    let chainID = "earth-1"
    func fb(_ i: UInt64) -> Data { Vectors.fe(i).bytes }
    func bs(_ s: String) -> Data { Data(s.utf8) }
    func addr(_ b: Int) -> String {
        try! Bech32.encode(hrp: "earth", data: Bech32.convertBits((0 ..< 20).map { UInt8(b + $0) }, from: 8, to: 5, pad: true))
    }

    func transfer(_ seed: UInt64, _ fee: UInt64, _ valueOut: UInt64, _ denomOut: String) -> ShieldedTransfer {
        ShieldedTransfer(
            proof: Data([0xde, 0xad, UInt8(truncatingIfNeeded: seed)]), root: fb(seed),
            nullifiers: [fb(seed + 1), fb(seed + 2), fb(seed + 3)], commitments: [fb(seed + 4), fb(seed + 5), fb(seed + 6)],
            ciphertexts: [bs("ct-\(seed)-0"), bs("ct-\(seed)-1"), Data()], fee: fee, valueOut: valueOut, denomOut: denomOut
        )
    }

    func membership(_ seed: UInt64) -> Membership {
        Membership(proof: Data([0xbe, 0xef, UInt8(truncatingIfNeeded: seed)]), root: fb(seed + 100), nullifier: fb(seed + 101))
    }

    let validator = "earthvaloper1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"
    var derth: String { "derth/\(validator)" }
    let opts = [WeightedVoteOption(option: WeightedVoteOption.yes, weight: "0.7"),
                WeightedVoteOption(option: WeightedVoteOption.no, weight: "0.300000000000000000")]
    func w(_ o: UInt64, _ p: UInt64) -> Msg.AllocationWeight { Msg.AllocationWeight(optionID: o, percent: p) }
    let sig = Data((0 ..< 64).map { UInt8($0) })

    func register(_ affiliate: String) -> MsgRegisterPrivate {
        MsgRegisterPrivate(
            fee: transfer(40, 2000, 0, ""), proof: Data([1, 2, 3]), publicSignals: ["250930", "12345", "678", "9"],
            signatureAlgorithm: "lean_poa", dscDer: Data([0x30, 0x03, 1, 2, 3]), idc: fb(41), pcAnml: fb(42),
            ciphertextAnml: bs("anml"), pcErth: fb(43), ciphertextErth: bs("erth"), affiliate: affiliate
        )
    }

    lazy var msgs: [String: any ProtoMessage] = [
        "transfer_send": MsgShieldedTransfer(transfer: transfer(10, 1500, 0, "")),
        "transfer_unshield": MsgShieldedTransfer(transfer: transfer(20, 0, 5000, "uerth"), receiver: addr(1), feeFromOutput: 1000),
        "shield": MsgShield(sender: addr(30), amount: Coin(denom: "uerth", amount: "100000"), pc: fb(31), ciphertext: bs("gas")),
        "register": register(addr(50)),
        "register_no_affiliate": register(""),
        "claim_anml": MsgClaimAnmlPrivate(fee: transfer(50, 2000, 0, ""), membership: membership(50), day: 20360, pc: fb(51), ciphertext: bs("claim")),
        "set_caretaker": MsgSetCaretaker(fee: transfer(60, 2000, 0, ""), membership: membership(60), percentages: [w(1, 60), w(7, 40)], maxActivation: 1_780_000_000),
        "bind_referrer": MsgBindReferrer(fee: transfer(70, 2000, 0, ""), membership: membership(70), address: addr(50), maxActivation: 1_780_000_000),
        "bind_referrer_clear": MsgBindReferrer(fee: transfer(71, 2000, 0, ""), membership: membership(71), address: "", maxActivation: 1_780_000_000),
        "vote_proposal": MsgVoteProposalPrivate(fee: transfer(80, 2000, 0, ""), membership: membership(80), proposalID: 5, option: .yes),
        "propose_removal": MsgProposeRemoval(fee: transfer(81, 2000, 0, ""), membership: membership(81), optionID: 3),
        "vote_removal": MsgVoteRemoval(fee: transfer(82, 2000, 0, ""), membership: membership(82), optionID: 3, option: .no),
        "delegate": MsgShieldedDelegate(transfer: transfer(90, 2000, 500_000, "uerth"), validator: validator, pc: fb(91), ciphertext: bs("d")),
        "undelegate": MsgShieldedUndelegate(transfer: transfer(100, 2000, 400_000, derth), validator: validator, pc: fb(101), ciphertext: bs("u")),
        "claim_unbonding": MsgClaimUnbonding(transfer: transfer(110, 0, 400_000, "unbond/\(validator)/17"), validator: validator, epoch: 17,
                                             pc: fb(111), ciphertext: bs("c"), feeFromOutput: 2000),
        "stake_vote": MsgStakeVote(transfer: transfer(120, 0, 400_000, derth), proposalID: 5, validator: validator, options: opts,
                                   pc: fb(121), ciphertext: bs("v"), feeTransfer: transfer(130, 2000, 0, "")),
        "lock_position": MsgLockPosition(transfer: transfer(140, 0, 400_000, derth), validator: validator, splits: [w(2, 100)],
                                         pubkey: Data([2]) + Vectors.fe(141).bytes),
        "update_position": MsgUpdatePosition(transfer: transfer(150, 2000, 0, ""), positionID: 9, splits: [w(2, 100)], signature: sig),
        "unlock_position": MsgUnlockPosition(transfer: transfer(160, 2000, 0, ""), positionID: 9, pc: fb(161), ciphertext: bs("x"), signature: sig),
        "position_vote": MsgPositionVote(transfer: transfer(170, 2000, 0, ""), positionID: 9, proposalID: 5, options: opts, signature: sig),
        "note_swap": MsgNoteSwap(transfer: transfer(180, 2000, 300_000, "uanml"), denomOut: "uerth", minAmountOut: 123_456, pc: fb(181)),
        "note_swap_fee_from_output": MsgNoteSwap(transfer: transfer(190, 0, 300_000, "uanml"), denomOut: "uerth", minAmountOut: 123_456,
                                                 pc: fb(191), ciphertext: bs("s"), feeFromOutput: 3000),
        "note_swap_to_anml": MsgNoteSwap(transfer: transfer(200, 2000, 300_000, "uerth"), denomOut: "uanml", minAmountOut: 1, pc: fb(201)),
        "add_liquidity_shielded": MsgAddLiquidityShielded(transfer: transfer(210, 0, 700_000, "uanml"), erthTransfer: transfer(220, 2500, 900_000, "uerth"),
                                                          poolID: 1, provider: addr(30), minShares: "777", refundPC: fb(211)),
        "add_liquidity_shielded_no_min": MsgAddLiquidityShielded(transfer: transfer(230, 2500, 700_000, "uanml"), erthTransfer: transfer(240, 0, 900_000, "uerth"),
                                                                 poolID: 1, provider: addr(50), minShares: "", refundPC: fb(231), refundCiphertext: bs("r")),
        "remove_liquidity_pc": Msg.RemoveLiquidity(creator: addr(30), poolID: 1, shares: Coin(denom: "dexlp/1", amount: "4242"), pc: fb(250)),
        "buy_anml": MsgBuyAnml(creator: addr(30), tokenIn: Coin(denom: "uerth", amount: "5000000"), minAmountOut: "99", pc: fb(260)),
    ]

    func testEncodingsAndSignalsMatchTheChain() throws {
        let want = Vectors.obj("msgs")
        XCTAssertEqual(want.count, msgs.count)
        for (name, m) in msgs {
            let v = want[name] as! [String: Any]
            XCTAssertEqual(v["proto"] as? String, Vectors.hex(m.encoded()), "\(name) proto")
            if let signal = v["signal"] as? String {
                let pm = try XCTUnwrap(m as? any PrivateMsg, name)
                XCTAssertEqual(v["type_url"] as? String, pm.typeURL, "\(name) type")
                XCTAssertEqual(signal, try pm.signal(chainID: chainID).hex, "\(name) signal")
                // And it decodes back to itself.
                let back = try PrivateMsgs.decode(typeURL: pm.typeURL, value: pm.encoded())
                XCTAssertEqual(Vectors.hex(back.encoded()), Vectors.hex(pm.encoded()), "\(name) round trip")
            }
        }
    }

    func testTotalFeesMatchTheChain() {
        for (name, fee) in Vectors.obj("total_fees") {
            XCTAssertEqual(UInt64(fee as! String), (msgs[name] as! any PrivateMsg).totalFee, name)
        }
    }

    func testBindingAndFieldEncodings() throws {
        let b = Vectors.obj("registration_binding")
        XCTAssertEqual(b["with_affiliate"] as? String, try (msgs["register"] as! MsgRegisterPrivate).binding().hex)
        XCTAssertEqual(b["none"] as? String, try (msgs["register_no_affiliate"] as! MsgRegisterPrivate).binding().hex)
        XCTAssertEqual(Vectors.json["options_bytes"] as? String, Vectors.hex(try PrivateMsgs.optionsBytes(opts)))
        XCTAssertEqual(Vectors.json["splits_bytes"] as? String, Vectors.hex(PrivateMsgs.splitsBytes([w(1, 60), w(7, 40)])))
        XCTAssertEqual(
            Vectors.json["position_sign_bytes"] as? String,
            Vectors.hex(PrivateMsgs.positionSignBytes(chainID: chainID, action: "vote", positionID: 9, nonce: 3,
                                                       payload: try PrivateMsgs.positionVotePayload(proposalID: 5, options: opts)))
        )
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
        let raw = UnsignedTx.build(msgs["claim_anml"] as! any PrivateMsg, gasLimit: (v["gas_limit"] as! NSNumber).uint64Value)
        XCTAssertEqual(v["tx_raw"] as? String, Vectors.hex(raw))
        let back = try UnsignedTx.decode(raw)
        XCTAssertEqual(0, back.signatures)
        XCTAssertEqual(0, back.signerInfos)
        XCTAssertEqual("2000", back.feeCoins.first?.amount)
    }
}
