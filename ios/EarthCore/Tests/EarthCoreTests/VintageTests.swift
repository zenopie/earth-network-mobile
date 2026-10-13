import Foundation
import XCTest
@testable import EarthCore

/// Stake vintages (chain v1.3.0) against the chain's own output
/// (Resources/vintages, Scripts/vintagevectors/gen.sh): the month maths,
/// both denom forms, every stake msg's vintage field in its encoding and
/// sighash (0 hashing as before), MsgCheckIn's and a retired withdrawal's
/// shape and sighash, the public inputs the chain supplies each under a
/// VintageCtx, and the check-in reminder's dates.
final class VintageTests: XCTestCase {
    static let json: [String: Any] = {
        let url = Bundle.module.url(forResource: "vintages", withExtension: nil)!.appendingPathComponent("vectors.json")
        return try! JSONSerialization.jsonObject(with: try! Data(contentsOf: url)) as! [String: Any]
    }()

    var json: [String: Any] { Self.json }
    var validator: String { json["validator"] as! String }
    var validator2: String { json["validator2"] as! String }
    var base: UInt32 { UInt32((json["base"] as! NSNumber).uint32Value) }
    var current: UInt32 { UInt32((json["current"] as! NSNumber).uint32Value) }
    var ctx: VintageCtx { VintageCtx(base: base, current: current) }

    func testMonthsMatchTheChain() {
        for m in json["months"] as! [[String: Any]] {
            let month = (m["month"] as! NSNumber).uint32Value
            XCTAssertEqual(m["valid"] as? Bool, Vintage.valid(month), "\(month)")
            XCTAssertEqual((m["start"] as! NSNumber).int64Value, Vintage.start(month), "\(month) start")
            XCTAssertEqual((m["retires_at"] as! NSNumber).int64Value, Vintage.retiresAt(month), "\(month) retires")
            XCTAssertEqual(m["live"] as? Bool, Vintage.live(month, current: current), "\(month) live")
            XCTAssertEqual(m["retired"] as? Bool, Vintage.retired(month, current: current), "\(month) retired")
            XCTAssertEqual(m["denom"] as? String, Vintage.denom(validator, month, base: base), "\(month) denom")
            XCTAssertEqual(m["asset"] as? String, PrivacyHash.assetID(m["denom"] as! String).hex, "\(month) asset")
            // The month that starts it, and the second before.
            XCTAssertEqual(month, Vintage.monthOf(Vintage.start(month)))
            XCTAssertEqual(Vintage.addMonths(month, -1), Vintage.monthOf(Vintage.start(month) - 1))
        }
        for m in json["invalid_months"] as! [NSNumber] { XCTAssertFalse(Vintage.valid(m.uint32Value), "\(m)") }
        for (t, m) in json["month_of"] as! [String: NSNumber] {
            XCTAssertEqual(m.uint32Value, Vintage.monthOf(Int64(t)!), t)
        }
        XCTAssertEqual(202701, Vintage.addMonths(202612, 1))
        XCTAssertEqual(202512, Vintage.addMonths(202601, -1))
        XCTAssertEqual(202711, Vintage.addMonths(202610, 13))
    }

    /// Both forms parse: the unsuffixed one names the base vintage (0), a suffix that is no YYYYMM month nothing.
    func testDenomsParseAsTheChainDoes() {
        for p in json["parse"] as! [[String: Any]] {
            let d = p["denom"] as! String
            let got = Vintage.parse(d)
            XCTAssertEqual(p["ok"] as? Bool, got != nil, d)
            if let got {
                XCTAssertEqual(p["valoper"] as? String, got.valoper, d)
                XCTAssertEqual((p["month"] as! NSNumber).uint32Value, got.month, d)
            }
        }
        // The legacy denom is the base vintage's, and resolves to its month.
        XCTAssertEqual(base, ctx.month(of: PrivacyWallet.derthDenom(validator)))
        XCTAssertEqual(202612, ctx.month(of: "derth/\(validator)/202612"))
        XCTAssertEqual(PrivacyWallet.derthDenom(validator), ctx.denom(validator, 0))
        XCTAssertEqual(PrivacyWallet.derthDenom(validator), ctx.denom(validator, base))
        XCTAssertEqual("derth/\(validator)/202703", ctx.denom(validator, current))
        // Its validator either way.
        XCTAssertEqual(validator, try PrivacyWallet.parseDerth("derth/\(validator)/202612"))
        XCTAssertEqual(validator, try PrivacyWallet.parseDerth(PrivacyWallet.derthDenom(validator)))
        XCTAssertThrowsError(try PrivacyWallet.parseDerth("uerth"))
        // A held denom's msg vintage: the explicit month, never 0, on a v1.3.0 chain; 0 before it.
        XCTAssertEqual(base, PrivacyWallet.msgVintage(PrivacyWallet.derthDenom(validator), ctx))
        XCTAssertEqual(0, PrivacyWallet.msgVintage(PrivacyWallet.derthDenom(validator), nil))
        XCTAssertTrue(PrivacyWallet.isRetired("derth/\(validator)/202602", ctx))
        XCTAssertTrue(PrivacyWallet.isLive("derth/\(validator)/202603", ctx))
        XCTAssertTrue(PrivacyWallet.isLive("derth/\(validator)/202602", nil))
    }

    // MARK: msgs (the same helpers as tools/privacyvectors and Scripts/vintagevectors)

    func fb(_ i: UInt64) -> Data { Vectors.fe(i).bytes }
    let zero32 = Data(count: 32)
    func bct(_ seed: Int) -> Data { Data((0 ..< 177).map { UInt8(truncatingIfNeeded: seed + $0) }) }
    func sct(_ seed: Int) -> Data { Data((0 ..< 201).map { UInt8(truncatingIfNeeded: seed ^ $0) }) }

    func fee(_ seed: UInt64, _ amount: UInt64) -> ShieldedBundle {
        var actions: [ShieldedAction] = []
        for i in 0 ..< 2 as Range<UInt64> {
            let x = seed * 10 + i
            let cv = Grumpkin.valueCommit(assetSpend: PrivacyHash.assetID("uerth"), vSpend: 1000 + x, assetOut: PrivacyHash.assetID("uanml"),
                                          vOut: x, rcv: Vectors.fe(x + 500))
            actions.append(ShieldedAction(anchor: fb(x + 1), nullifier: fb(x + 2), commitment: fb(x + 3), cv: cv.bytes, ciphertext: Data("ct-\(x)".utf8),
                                          proof: Data([0xde, 0xad, UInt8(truncatingIfNeeded: x)])))
        }
        return ShieldedBundle(actions: actions, balances: [ValueBalance(denom: "uerth", amount: amount)],
                              bindingSig: Data(repeating: UInt8(truncatingIfNeeded: seed), count: 96))
    }

    func stake(_ seed: UInt64, _ spends: Int, credits: Bool = false) -> StakeProof {
        StakeProof(proof: Data([0x5e, UInt8(truncatingIfNeeded: seed)]), anchor: fb(seed),
                   nullifiers: (0 ..< 2).map { $0 < spends ? fb(seed + 1 + UInt64($0)) : zero32 },
                   commitment: fb(seed + 3), ciphertext: sct(Int(seed)),
                   creditNullifier: credits ? fb(seed + 4) : zero32, creditCommitment: credits ? fb(seed + 5) : zero32,
                   creditCiphertext: credits ? sct(Int(seed) + 1) : Data(),
                   clearBefore: 1_790_000_000 + seed, debtRoot: fb(seed + 6),
                   groundworksTags: [fb(seed + 8), fb(seed + 9)], creditGroundworksTag: credits ? fb(seed + 10) : zero32,
                   voteTag: zero32, voteWeight: 0, creditVoteTag: zero32, creditVoteWeight: 0, pendingKey: zero32)
    }

    func voting(_ p: StakeProof, _ seed: UInt64, _ w: UInt64, _ cw: UInt64) -> StakeProof {
        var p = p
        p.voteTag = fb(seed + 11); p.voteWeight = w
        p.creditVoteTag = fb(seed + 12); p.creditVoteWeight = cw
        return p
    }

    let opts = [WeightedVoteOption(option: WeightedVoteOption.yes, weight: "0.700000000000000000"),
                WeightedVoteOption(option: WeightedVoteOption.no, weight: "0.300000000000000000")]

    var checkIn: MsgCheckIn {
        MsgCheckIn(bundle: fee(180, 2000), validator: validator, fromVintage: base, amount: 400_000, stake: stake(180, 1, credits: true))
    }

    var checkInVote: MsgCheckIn {
        MsgCheckIn(bundle: fee(185, 2000), validator: validator, fromVintage: 202612, amount: 400_000,
                   stake: voting(stake(185, 2, credits: true), 185, 500_000, 1_400_000), groundworksSplit: [Msg.AllocationWeight(optionID: 2, percent: 100)])
    }

    var withdraw: MsgShieldedUndelegate {
        MsgShieldedUndelegate(bundle: fee(190, 2000), validator: validator, amount: 400_000, stake: stake(190, 1), pc: fb(191), ciphertext: bct(191),
                              vintage: 202602)
    }

    func msgs() -> [String: any PrivateMsg] {
        var out: [String: any PrivateMsg] = ["check_in": checkIn, "check_in_vote": checkInVote, "withdraw_retired": withdraw]
        for v in [UInt32(0), base, current] {
            out["delegate_\(v)"] = MsgShieldedDelegate(bundle: fee(90, 502_000), validator: validator, amount: 500_000, derth: 449_995, stake: stake(90, 1),
                                                       vintage: v)
            out["restake_\(v)"] = MsgRestake(bundle: fee(95, 2000), validator: validator, stake: stake(95, 2), vintage: v)
            out["undelegate_\(v)"] = MsgShieldedUndelegate(bundle: fee(100, 2000), validator: validator, amount: 400_000, stake: stake(100, 1),
                                                           pc: fb(101), ciphertext: bct(101), vintage: v)
            out["redelegate_\(v)"] = MsgRedelegate(bundle: fee(175, 2000), srcValidator: validator, dstValidator: validator2, amount: 400_000,
                                                   stake: stake(175, 1, credits: true), dstDerth: 380_000, moveTime: 1_790_000_123, vintage: v)
            out["stake_vote_\(v)"] = MsgStakeVote(bundle: fee(127, 2000), proposalID: 6, validator: validator, options: opts, weight: 999,
                                                  proof: Data([0x70, 0x7e]), voteNullifiers: [fb(128), fb(129)], debtRoot: fb(130), vintage: v)
        }
        return out
    }

    func testMsgsMatchTheChain() throws {
        let want = json["msgs"] as! [String: [String: Any]]
        let got = msgs()
        XCTAssertEqual(Set(want.keys), Set(got.keys))
        for (name, m) in got {
            let v = want[name]!
            XCTAssertEqual(v["type_url"] as? String, m.typeURL, name)
            XCTAssertEqual(v["proto"] as? String, Vectors.hex(m.encoded()), "\(name) proto")
            let tx = PrivateMsgs.TxFields(gasLimit: (v["gas_limit"] as! NSNumber).uint64Value)
            XCTAssertEqual(v["sighash"] as? String, try m.sighash(chainID: "earth-1", tx: tx).hex, "\(name) sighash")
            let back = try PrivateMsgs.decode(typeURL: m.typeURL, value: m.encoded())
            XCTAssertEqual(Vectors.hex(back.encoded()), Vectors.hex(m.encoded()), "\(name) round trip")
        }
    }

    /// vintage 0 hashes byte for byte as before v1.3.0 (no field appended);
    /// the explicit base month signs differently, so a relayer cannot swap them.
    func testVintageZeroKeepsTheOldSighash() throws {
        let m = msgs()
        for kind in ["delegate", "restake", "undelegate", "redelegate", "stake_vote"] {
            let zero = m["\(kind)_0"]!, explicit = m["\(kind)_\(base)"]!
            XCTAssertEqual(try zero.sighashFields().count + 1, try explicit.sighashFields().count, kind)
            XCTAssertEqual(Array(try explicit.sighashFields().dropLast()), try zero.sighashFields(), kind)
            XCTAssertEqual(PrivacyHash.u64(UInt64(base)), try explicit.sighashFields().last, kind)
            XCTAssertNotEqual(try zero.sighash(chainID: "earth-1", tx: .init()), try explicit.sighash(chainID: "earth-1", tx: .init()), kind)
        }
        // A vintage 0 msg encodes as before: the field is absent.
        XCTAssertFalse(try ProtoFields(m["delegate_0"]!.encoded()).has(8))
        XCTAssertTrue(try ProtoFields(m["delegate_\(base)"]!.encoded()).has(8))
    }

    /// MsgCheckIn: StakeFields, Bytes(validator), U64(from_vintage),
    /// U64(amount), Bytes(SplitsBytes(split)); no vintage appended (it names
    /// from_vintage itself, never 0).
    func testCheckInSighashFields() throws {
        let m = checkInVote
        let f = try m.sighashFields()
        XCTAssertEqual(20 + 4, f.count)
        XCTAssertEqual(try PrivateMsgs.stakeFields(m.stake), Array(f.prefix(20)))
        XCTAssertEqual([PrivacyHash.bytes(validator), PrivacyHash.u64(202612), PrivacyHash.u64(400_000),
                        PrivacyHash.bytes(PrivateMsgs.splitsBytes([Msg.AllocationWeight(optionID: 2, percent: 100)]))], Array(f.suffix(4)))
        var zero = m; zero.fromVintage = 0
        XCTAssertThrowsError(try zero.sighashFields())
        XCTAssertEqual(0, m.movedUerth)
        XCTAssertEqual(2000, m.privateFee)
    }

    /// The circuits' public inputs as the chain lays them out under a
    /// VintageCtx: a check-in's lane A from_vintage's asset (v_out = amount)
    /// and its credit the current vintage's (cr_v_in = amount, cr_move_time
    /// 0); a withdrawal the retired asset; a move's credit dst's current
    /// vintage; a vote its notes' vintage's asset.
    func testPublicInputsUnderVintagesMatchTheChain() throws {
        let want = json["public_inputs"] as! [String: [String]]
        let cases: [String: any PrivateMsg] = [
            "check_in": checkIn, "check_in_vote": checkInVote, "withdraw_retired": withdraw,
            "delegate_current": MsgShieldedDelegate(bundle: fee(90, 502_000), validator: validator, amount: 500_000, derth: 449_995, stake: stake(90, 1),
                                                    vintage: current),
            "redelegate_base": MsgRedelegate(bundle: fee(175, 2000), srcValidator: validator, dstValidator: validator2, amount: 400_000,
                                             stake: stake(175, 1, credits: true), dstDerth: 380_000, moveTime: 1_790_000_123, vintage: base),
        ]
        for (name, m) in cases {
            let got = try ChainLayout.stakePublicInputs(m.stakeProof!, ChainLayout.lanes(m, ctx), Vectors.fe(77))
            XCTAssertEqual(want[name], got.map(\.hex), name)
        }
        let l = ChainLayout.lanes(checkIn, ctx)
        XCTAssertEqual(PrivacyWallet.derthDenom(validator), l.denom)
        XCTAssertEqual("derth/\(validator)/\(current)", l.crDenom)
        XCTAssertEqual(0, l.crMoveTime)
        for v in [base, UInt32(202612)] {
            let vm = MsgStakeVote(bundle: fee(127, 2000), proposalID: 6, validator: validator, options: opts, weight: 999,
                                  voteNullifiers: [fb(128), fb(129)], debtRoot: fb(130), vintage: v)
            let got = try ChainLayout.votePublicInputs(vm, noteRoot: Vectors.fe(131), nfRoot: Vectors.fe(132), sighash: Vectors.fe(77), ctx)
            XCTAssertEqual(want["stake_vote_\(v)"], got.map(\.hex), "vote \(v)")
        }
    }

    /// A check-in's credit lane: unlabelled (cr_move_time 0), the padding
    /// input's own nullifier, merged into our current note when one is held.
    func testCheckInCreditIsUnlabelled() throws {
        let keys = try PrivacyKeys.fromMnemonic("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about")
        let denom = ctx.denom(validator, current)
        let c = try StakePlan.checkInCredit(keys, denom: denom, spend: nil, path: nil, vIn: 400_000)
        XCTAssertNil(c.out.label)
        XCTAssertEqual(0, c.moveTime)
        XCTAssertEqual(400_000, c.out.amount)
        XCTAssertEqual(PrivacyHash.stakeNF(nk: keys.nk, rho: c.padRho, position: 0), c.nullifier)
        let held = OwnedStakeNote(position: 5, height: 1, denom: denom, amount: 1_000, rho: .one, rcm: .one, cm: .one,
                                  nf: PrivacyHash.stakeNF(nk: keys.nk, rho: .one, position: 5))
        let merged = try StakePlan.checkInCredit(keys, denom: denom, spend: held, path: [Fr](repeating: .zero, count: Merkle.depth), vIn: 400_000)
        XCTAssertEqual(401_000, merged.out.amount)
        XCTAssertEqual(held.nf, merged.nullifier)
        // Another vintage's note never takes the credit.
        let old = OwnedStakeNote(position: 6, height: 1, denom: PrivacyWallet.derthDenom(validator), amount: 1_000, rho: .one, rcm: .one, cm: .one, nf: .one)
        XCTAssertThrowsError(try StakePlan.checkInCredit(keys, denom: denom, spend: old, path: [], vIn: 1))
        // A move's credit is labelled.
        XCTAssertNotNil(try StakePlan.credit(keys, denom: denom, spend: nil, path: nil, vIn: 1, moveTime: 7).out.label)
    }

    // MARK: reminders

    func vintage(_ month: UInt32, derth: UInt64 = 1_000, retired: Bool = false) -> PrivacyWallet.StakeVintage {
        PrivacyWallet.StakeVintage(denom: "derth/v/\(month)", month: month, derth: derth, free: derth, locked: 0, lockedUntil: nil, notes: 1,
                                   mergeable: false, retiresAt: Vintage.retiresAt(month), retired: retired)
    }

    /// Due from 30 days before the oldest live vintage holding stake retires,
    /// until it does; an empty vintage, a retired one or none: nothing.
    func testCheckInReminderDates() {
        let holdings = [
            PrivacyWallet.StakeHolding(validator: "a", derth: 2_000, free: 2_000, locked: 0, lockedUntil: nil, notes: 2, mergeable: false,
                                       vintages: [vintage(202611), vintage(202703)], retired: [vintage(202602, retired: true)]),
            PrivacyWallet.StakeHolding(validator: "b", derth: 0, free: 0, locked: 0, lockedUntil: nil, notes: 0, mergeable: false,
                                       vintages: [vintage(202610, derth: 0)]),
        ]
        let by = PrivacyWallet.checkInBy(holdings)
        XCTAssertEqual(Vintage.retiresAt(202611), by)
        XCTAssertEqual(Vintage.start(202712), by)
        XCTAssertEqual(0, PrivacyWallet.checkInBy([]))
        let q = Reminders.Inputs(now: 0, identityLive: false, claimOpensAt: nil, claimedToday: true, caretakerExpiresAt: 0, handle: "", handleEntry: nil)
        func due(_ now: Int64, _ r: Int64) -> [Reminders.Reminder] { var i = q; i.now = now; i.stakeRetiresAt = r; return Reminders.due(i) }
        XCTAssertTrue(due(by - Vintage.checkInLeadSeconds - 1, by).isEmpty)
        XCTAssertEqual([.checkIn(retiresAt: by)], due(by - Vintage.checkInLeadSeconds, by))
        XCTAssertEqual([.checkIn(retiresAt: by)], due(by - 1, by))
        XCTAssertTrue(due(by, by).isEmpty)
        XCTAssertTrue(due(by - 86_400, 0).isEmpty)
        // Not tied to a live identity, never an action; hostile times saturate.
        _ = due(.min, .max); _ = Reminders.text(.checkIn(retiresAt: .max), now: .min)
        XCTAssertEqual("Some of your stake stops earning in 7 days. Check in to keep it earning.",
                       Reminders.text(.checkIn(retiresAt: by), now: by - 7 * 86_400))
        // The vintage's own flag.
        XCTAssertTrue(vintage(202611).dueSoon(by - 86_400))
        XCTAssertFalse(vintage(202611).dueSoon(by - Vintage.checkInLeadSeconds - 1))
        XCTAssertFalse(vintage(202602, retired: true).dueSoon(by))
    }

    /// The wallet's local context: the base it read, the later of the read month and the device clock's.
    func testLocalContext() {
        XCTAssertNil(PrivacyWallet.localCtx(base: 0, current: 0, now: 1_790_000_000))
        XCTAssertEqual(VintageCtx(base: 202610, current: 202611), PrivacyWallet.localCtx(base: 202610, current: 202610, now: Vintage.start(202611)))
        XCTAssertEqual(VintageCtx(base: 202610, current: 202612), PrivacyWallet.localCtx(base: 202610, current: 202612, now: Vintage.start(202611)))
        XCTAssertEqual("Oct 2026", Vintage.monthText(202610))
    }

    /// Sync names a suffixed note by trying each month after the base, newest first.
    func testSyncNamesVintagedDenoms() {
        let ops = [validator, validator2]
        let want = ["derth/\(validator2)/202612", "derth/\(validator)/202611"]
        let ids = Set(want.map(PrivacyHash.assetID) + [PrivacyHash.assetID("uerth")])
        XCTAssertEqual(Set(want), Set(WalletSync.vintageDenoms(operators: ops, base: 202610, through: 202701, matching: ids)))
        // The base month's denom is the unsuffixed one: never tried as a suffix.
        XCTAssertTrue(WalletSync.vintageDenoms(operators: ops, base: 202610, through: 202701,
                                               matching: [PrivacyHash.assetID("derth/\(validator)/202610")]).isEmpty)
    }
}
