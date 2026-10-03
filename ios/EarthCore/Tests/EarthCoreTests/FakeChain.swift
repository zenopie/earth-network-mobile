import BigInt
import Foundation
@testable import EarthCore

/// An in-memory model of the chain's private side, for driving a wallet end
/// to end without a node (ports FakeChain.kt): the note, identity and stake
/// trees, both nullifier sets, the ante's checks a private tx must pass
/// (shape, the fee, anchors, nullifiers, the release map, every binding
/// signature for real, and every proof's public inputs), and the mints each
/// msg makes. Its indexer serves the same streams the backend does. Proofs
/// are not real: `CheckingProver` checks each witness against its circuit's
/// constraints instead and keeps it, so the chain can match it to the tx and
/// a test can hand it to nargo.
final class FakeChain: PrivateChain, PrivacyIndexer, @unchecked Sendable {
    let chainID = "earth-1"
    var now: Int64 = 1_790_000_000
    var notes: [NoteRow] = []
    let noteTree = MerkleTree(store: MemNodeStore())
    let identityTree = MerkleTree(store: MemNodeStore())
    var identityRows: [IdentityRow] = []
    var nullifiers: [Fr: UInt64] = [:]
    var noteRoots: Set<Fr> = []
    var identityRoots: Set<Fr> = []
    // x/shieldedstaking's stake tree.
    var stakeRows: [StakeNoteRow] = []
    let stakeTree = MerkleTree(store: MemNodeStore())
    var stakeNullifiers: [Fr: UInt64] = [:]
    var stakeRoots: Set<Fr> = []
    var height: UInt64 = 1
    let minFeeValue: UInt64 = 1000
    let price = Decimal(string: "0.001")!
    var maxActions = 16
    let prover = CheckingProver()
    /// receiver -> denom -> amount unshielded.
    var unshielded: [String: [String: UInt64]] = [:]
    var votes: [(UInt64, Int)] = []
    /// (proposal, validator, weight) of every stake vote.
    var stakeVotes: [(UInt64, String, UInt64)] = []
    var simulated = 0
    var actionCounts: [Int] = []

    // x/dex pool 1 (uanml/uerth) and its LP shares.
    var poolErth = BigInt(1_000_000_000_000)
    var poolAnml = BigInt(500_000_000_000)
    var lpSupply = BigInt(700_000_000_000)
    let swapFee = Decimal(string: "0.3")!
    /// Private withdrawals waiting to mature: shares, erth pc, token pc.
    var withdrawals: [(BigInt, Fr, Fr)] = []

    final class Pos {
        let id: UInt64, validator: String, derth: UInt64, ownerTag: Fr, createdHeight: UInt64
        var splits: [UInt64: UInt64]
        init(id: UInt64, validator: String, derth: UInt64, ownerTag: Fr, splits: [UInt64: UInt64], createdHeight: UInt64) {
            self.id = id; self.validator = validator; self.derth = derth; self.ownerTag = ownerTag; self.splits = splits
            self.createdHeight = createdHeight
        }
    }
    var positions: [UInt64: Pos] = [:]
    var positionOrder: [UInt64] = []
    var nextPositionID: UInt64 = 1
    var positionVotes: [(UInt64, UInt64)] = []
    var referrers: [Fr: String] = [:]
    var removalBallots: [UInt64: UInt64] = [:]
    var removalVotes: [(UInt64, Fr, Int)] = []
    var caretakerVotes: [Fr: [UInt64: UInt64]] = [:]
    var claimedUnbonds: [String] = []
    /// The fake's epoch (9/10 derth minted per uerth at delegation).
    let epoch: UInt64 = 4

    init() {
        noteRoots.insert(noteTree.root())
        identityRoots.insert(identityTree.root())
    }

    struct Refused: Error, CustomStringConvertible { let why: String; var description: String { why } }
    func need(_ ok: Bool, _ why: @autoclosure () -> String) throws { if !ok { throw Refused(why: why()) } }

    /// Ends the block being built: its roots become anchors. Writes land at `height`, the block in progress.
    private func block() {
        noteRoots.insert(noteTree.root()); identityRoots.insert(identityTree.root())
        if stakeTree.size > 0 { stakeRoots.insert(stakeTree.root()) }
        height += 1
    }

    @discardableResult
    func mint(_ denom: String, _ value: UInt64, _ pc: Fr, _ ct: Data = Data()) -> UInt64 {
        let cm = PrivacyHash.cm(asset: PrivacyHash.assetID(denom), value: value, pc: pc)
        let pos = noteTree.append(cm)
        notes.append(NoteRow(position: pos, height: height, cm: cm, ciphertext: ct, amount: "\(value)\(denom)"))
        return pos
    }

    @discardableResult
    func mintStake(_ denom: String, _ amount: UInt64, _ spc: Fr) -> UInt64 {
        let cm = PrivacyHash.stakeCM(asset: PrivacyHash.assetID(denom), amount: amount, spc: spc)
        let pos = stakeTree.append(cm)
        stakeRows.append(StakeNoteRow(position: pos, height: height, cm: cm, ciphertext: Data(), denom: denom, amount: amount, spc: spc))
        return pos
    }

    func shield(_ denom: String, _ value: UInt64, _ pc: Fr, _ ct: Data = Data()) { mint(denom, value, pc, ct); block() }

    /// Ends a block with no tx in it.
    func emptyBlock() { block() }

    /// The LP unbonding period passes: every private withdrawal pays both legs as notes.
    func matureWithdrawals() {
        for (sh, ePC, tPC) in withdrawals {
            let e = sh * poolErth / lpSupply
            let t = sh * poolAnml / lpSupply
            poolErth -= e; poolAnml -= t; lpSupply -= sh
            mint("uerth", UInt64(e), ePC)
            mint("uanml", UInt64(t), tPC)
        }
        withdrawals.removeAll()
        block()
    }

    // MARK: PrivateChain

    func gasPrice() async throws -> Decimal { price }
    func minFee() async throws -> UInt64 { minFeeValue }
    func maxActionsPerBundle() async throws -> Int { maxActions }

    /// The ante charges per bundle and per action, before anything else: gas is the tx's shape.
    func simulate(_ tx: Data) async throws -> UInt64 {
        simulated += 1
        let m = try check(tx, simulate: true).0
        let actions = m.bundles.reduce(0) { $0 + $1.actions.count }
        return 200_000 + 100_000 * UInt64(m.bundles.count) + 350_000 * UInt64(actions) + (m.stakeProof != nil ? 400_000 : 0)
    }

    func broadcast(_ tx: Data) async throws -> TxResult {
        let (_, events) = try check(tx, simulate: false)
        block()
        return TxResult(hash: "HASH\(height - 1)", height: height - 1, time: now, events: events)
    }

    /// What the swap pays, as x/dex prices it.
    func swapOut(_ denomIn: String, _ amountIn: UInt64, _ denomOut: String) throws -> UInt64 {
        guard let q = SwapMath.route(pools: ["uanml": .init(erth: poolErth, token: poolAnml)], hub: "uerth", denomIn: denomIn,
                                     amountIn: BigInt(amountIn), denomOut: denomOut, feePercent: swapFee) else { throw Refused(why: "no route") }
        return UInt64(q.amountOut)
    }

    private func f(_ d: Data) throws -> Fr { try Fr(bytes: d) }

    /// types.Remainders: per denom, the bundles' balances less the fee in uerth.
    private func remainders(_ m: any PrivateMsg) throws -> [String: UInt64] {
        var sum: [String: UInt64] = [:]
        for b in m.bundles { for bal in b.balances { sum[bal.denom, default: 0] += bal.amount } }
        let fee = m.privateFee
        try need((sum["uerth"] ?? 0) >= fee, "fee exceeds the uerth balance")
        if fee > 0 { sum["uerth"]! -= fee }
        return sum.filter { $0.value > 0 }
    }

    /// The release map each msg allows (the msgs' ValidateBasic).
    private func checkRelease(_ m: any PrivateMsg, _ rem: [String: UInt64]) throws {
        func only(_ denom: String?) throws {
            try need(denom == nil ? rem.isEmpty : Set(rem.keys) == [denom!], "release map: \(rem)")
        }
        switch m {
        case let m as MsgSend:
            try need(m.fee > 0, "send fee")
            try need(rem.isEmpty == m.receiver.isEmpty, "receiver exactly when something is left")
            try need(!rem.keys.contains { $0.hasPrefix("dexlp/") }, "LP shares cannot be unshielded")
        case is MsgShieldedDelegate: try only("uerth")
        case let m as MsgNoteSwap:
            try need(rem.count == 1 && rem[m.denomOut] == nil, "swap release")
            try need((m.fee == 0) != (m.feeFromOutput == 0), "swap fee")
        case let m as MsgAddLiquidityShielded: try need(Set(rem.keys) == ["uerth", "uanml"] && m.poolID == 1, "deposit legs")
        case let m as MsgRemoveLiquidityShielded: try only("dexlp/\(m.poolID)")
        default: try only(nil)
        }
        try need(m.totalFee > 0, "no fee")
    }

    /// The stake proof's chain-supplied publics: asset, v_out.
    private func stakeStatement(_ m: any PrivateMsg) -> (String?, UInt64) {
        switch m {
        case let m as MsgShieldedDelegate: return (PrivacyWallet.derthDenom(m.validator), 0)
        case let m as MsgRestake: return (PrivacyWallet.derthDenom(m.validator), 0)
        case let m as MsgShieldedUndelegate: return (PrivacyWallet.derthDenom(m.validator), m.amount)
        case let m as MsgClaimUnbonding: return (PrivacyWallet.unbondDenom(m.validator, epoch: m.epoch), m.amount)
        case let m as MsgStakeVote: return (PrivacyWallet.derthDenom(m.validator), m.weight)
        case let m as MsgLockPosition: return (PrivacyWallet.derthDenom(m.validator), m.amount)
        default: return (nil, 0)
        }
    }

    private func spent(_ p: StakeProof) throws -> [Fr] { try p.nullifiers.map(f).filter { !$0.isZero } }
    private func created(_ p: StakeProof) throws -> [(Int, Fr)] {
        try p.commitments.enumerated().map { ($0.offset, try f($0.element)) }.filter { !$0.1.isZero }
    }

    /// Shape per msg: (min spends, may create).
    private func stakeShape(_ m: any PrivateMsg, _ p: StakeProof) throws {
        let (minSpends, creates): (Int, Bool)
        switch m {
        case is MsgShieldedDelegate, is MsgUpdatePosition, is MsgUnlockPosition, is MsgPositionVote: (minSpends, creates) = (0, false)
        case is MsgStakeVote: (minSpends, creates) = (1, false)
        default: (minSpends, creates) = (1, true)
        }
        let n = try spent(p).count
        try need(minSpends == 0 ? n == 0 : n >= minSpends, "stake proof spends \(n)")
        try need(creates || (try created(p)).isEmpty, "stake proof creates")
        if m is MsgRestake { try need(!(try created(p)).isEmpty, "restake creates nothing") }
    }

    /// A membership's expected scope and max_activation, per msg.
    private func membershipStatement(_ m: any PrivateMsg) -> (Fr, UInt64)? {
        let day = UInt64(now / 86_400)
        switch m {
        case let m as MsgClaimAnmlPrivate: return (PrivacyHash.claimScope(day: m.day), (m.day - 1) * 86_400)
        case let m as MsgVoteProposalPrivate: return (PrivacyHash.proposalScope(proposalID: m.proposalID, round: 0), UInt64(now - 3600))
        case let m as MsgSetCaretaker: return (PrivacyHash.caretakerScope(), m.maxActivation)
        case let m as MsgBindReferrer: return (PrivacyHash.referrerScope(), m.maxActivation)
        case let m as MsgProposeRemoval: return (PrivacyHash.proposeRemovalScope(optionID: m.optionID, day: day), day * 86_400 - 3600)
        case let m as MsgVoteRemoval: return (PrivacyHash.removalScope(ballotID: removalBallots[m.optionID] ?? 0), UInt64(now - 3600))
        default: return nil
        }
    }

    private func shares(_ erth: UInt64, _ anml: UInt64) -> BigInt {
        min(BigInt(erth) * lpSupply / poolErth, BigInt(anml) * lpSupply / poolAnml)
    }

    /// The action's own checks, before anything is written (atomic with the spend in the ante).
    private func precheck(_ m: any PrivateMsg, _ rem: [String: UInt64]) throws {
        switch m {
        case let m as MsgNoteSwap:
            let (denomIn, amountIn) = rem.first!
            let out = try swapOut(denomIn, amountIn, m.denomOut)
            try need(out >= m.minAmountOut, "slippage: got \(out), want >= \(m.minAmountOut)")
            try need(m.feeFromOutput == 0 || (m.denomOut == "uerth" && m.minAmountOut > m.feeFromOutput), "fee from output")
        case let m as MsgAddLiquidityShielded:
            if !m.minShares.isEmpty { try need(shares(rem["uerth"]!, rem["uanml"]!) >= BigInt(m.minShares)!, "below min_shares") }
        case let m as MsgUpdatePosition:
            try need(positions[m.positionID]?.ownerTag == (try f(m.stake.ownerTag)), "not the position's owner")
        case let m as MsgUnlockPosition:
            try need(positions[m.positionID]?.ownerTag == (try f(m.stake.ownerTag)), "not the position's owner")
        case let m as MsgPositionVote:
            try need(positions[m.positionID]?.ownerTag == (try f(m.stake.ownerTag)), "not the position's owner")
        case let m as MsgVoteRemoval: try need(removalBallots[m.optionID] != nil, "no open ballot")
        case let m as MsgProposeRemoval: try need(removalBallots[m.optionID] == nil, "ballot already open")
        case let m as MsgClaimUnbonding: try need((m.bundle != nil) == (m.feeFromOutput == 0), "claim fee")
        default: break
        }
    }

    private func checkBundle(_ i: Int, _ b: ShieldedBundle, _ sighash: Fr, simulate: Bool, seen: inout Set<Fr>) throws {
        try need((2 ... maxActions).contains(b.actions.count), "bundle \(i): \(b.actions.count) actions")
        try need(b.balances.allSatisfy { $0.amount > 0 } && Set(b.balances.map(\.denom)).count == b.balances.count, "balances")
        try need(b.balances.count <= 2 * b.actions.count, "too many balances")
        for a in b.actions {
            try need(noteRoots.contains(try f(a.anchor)), "unknown anchor")
            let nf = try f(a.nullifier)
            try need(nullifiers[nf] == nil, "nullifier spent")
            try need(seen.insert(nf).inserted, "duplicate nullifier")
            _ = try Grumpkin.Point(bytes: a.cv)
        }
        try need(b.bindingSig.count == Grumpkin.bindingSigBytes, "binding sig size")
        if simulate { return }
        try need(PrivateMsgs.checkBalance(b, sighash: sighash), "bundle \(i): binding signature")
        for (j, a) in b.actions.enumerated() {
            guard !prover.actions.isEmpty else { throw Refused(why: "no proof for bundle \(i) action \(j)") }
            let w = prover.actions.removeFirst()
            let cv = Data(a.cv)
            let expect = [try f(a.anchor), try f(a.nullifier), try f(a.commitment), try f(cv.prefix(32)), try f(cv.suffix(32)), sighash]
            try need(w.publicInputs() == expect, "bundle \(i) action \(j) proof is for other public inputs")
        }
    }

    /// The ante, then the handler.
    private func check(_ txBytes: Data, simulate: Bool) throws -> (any PrivateMsg, [(type: String, attributes: [String: String])]) {
        let tx = try UnsignedTx.decode(txBytes)
        try need(tx.signatures == 0, "private txs are unsigned")
        try need(tx.signerInfos == 0, "no signer infos")
        let m = tx.msg
        let total = m.totalFee
        try need(tx.feeCoins.count == 1 && tx.feeCoins[0].denom == "uerth" && tx.feeCoins[0].amount == String(total), "declared fee != msg fee")
        try need(total >= minFeeValue, "below min fee")
        if !simulate { try need(Decimal(total) >= price * Decimal(tx.gasLimit), "below min gas price") }
        let bundles = m.bundles
        try need(((m.feeFromOutput > 0 ? 0 : 1) ... 2).contains(bundles.count), "bundle count")
        let rem = try remainders(m)
        try checkRelease(m, rem)
        let sighash = try m.sighash(chainID: chainID)
        var seen = Set<Fr>()
        for (i, b) in bundles.enumerated() { try checkBundle(i, b, sighash, simulate: simulate, seen: &seen) }

        let stake = m.stakeProof
        if let stake {
            try need(stake.nullifiers.count == 2 && stake.commitments.count == 2 && stake.ciphertexts.count <= 2, "stake proof shape")
            try stakeShape(m, stake)
            let nfs = try spent(stake)
            try need(!nfs.contains { stakeNullifiers[$0] != nil }, "stake nullifier spent")
            try need(Set(nfs).count == nfs.count, "duplicate stake nullifier")
            if !nfs.isEmpty { try need(stakeRoots.contains(try f(stake.anchor)), "unknown stake anchor") }
            if !simulate {
                guard !prover.stakes.isEmpty else { throw Refused(why: "no stake proof") }
                let w = prover.stakes.removeFirst()
                let (denom, vOut) = stakeStatement(m)
                let expect = [try f(stake.anchor), denom.map(PrivacyHash.assetID) ?? .zero] + (try stake.nullifiers.map(f)) +
                    (try stake.commitments.map(f)) + [PrivacyHash.u64(0), PrivacyHash.u64(vOut), try f(stake.spcMint), try f(stake.ownerTag), sighash]
                try need(w.publicInputs() == expect, "stake proof is for other public inputs")
            }
        }
        if let mm = m as? any MembershipMsg {
            let mem = mm.membership
            try need(identityRoots.contains(try f(mem.root)), "unknown identity anchor")
            if !simulate {
                guard !prover.memberships.isEmpty else { throw Refused(why: "no membership proof") }
                let w = prover.memberships.removeFirst()
                let (scope, maxAct) = membershipStatement(m)!
                let expect = [try f(mem.root), scope, try f(mem.nullifier), sighash, .zero, .zero, PrivacyHash.u64(maxAct)]
                try need(w.publicInputs() == expect, "membership proof is for other public inputs")
            }
        }
        try precheck(m, rem)
        if simulate { return (m, []) }
        actionCounts.append(bundles.reduce(0) { $0 + $1.actions.count })
        // Execute: spend, append, pay the fee and any unshield, then the action.
        for b in bundles {
            for a in b.actions {
                nullifiers[try f(a.nullifier)] = height
                let cm = try f(a.commitment)
                let pos = noteTree.append(cm)
                notes.append(NoteRow(position: pos, height: height, cm: cm, ciphertext: a.ciphertext, amount: nil))
            }
        }
        if let stake {
            for nf in try spent(stake) { stakeNullifiers[nf] = height }
            for (i, c) in try created(stake) {
                let pos = stakeTree.append(c)
                stakeRows.append(StakeNoteRow(position: pos, height: height, cm: c, ciphertext: stake.ciphertexts[i], denom: nil, amount: nil, spc: nil))
            }
        }
        var events: [(type: String, attributes: [String: String])] = []
        let spcMint = try stake.map { try f($0.spcMint) }
        switch m {
        case let m as MsgSend:
            for (d, v) in rem { unshielded[m.receiver, default: [:]][d, default: 0] += v }
        case let m as MsgRegisterPrivate:
            let binding = try PrivateMsgs.decimalField(m.publicSignals[1])
            try need(binding == (try m.binding()), "binding")
            let dsc = try PrivateMsgs.decimalField(m.publicSignals[3])
            let idx = identityTree.append(PrivacyHash.identityLeaf(idc: try f(m.idc), dscKey: dsc, country: PrivacyHash.countryField("DE"), activatedAt: UInt64(now)))
            identityRows.append(IdentityRow(index: idx, height: height, leaf: identityTree.leaf(idx), zeroedHeight: nil))
            mint("uanml", 1_000_000, try f(m.pcAnml), m.ciphertextAnml)
            mint("uerth", 5_000_000, try f(m.pcErth), m.ciphertextErth)
            events.append((type: "register", attributes: ["leaf_index": String(idx)]))
        case let m as MsgClaimAnmlPrivate:
            mint("uanml", 1_000_000, try f(m.pc), m.ciphertext)
        case let m as MsgVoteProposalPrivate:
            votes.append((m.proposalID, m.option.rawValue))
        case let m as MsgShieldedDelegate:
            mintStake(PrivacyWallet.derthDenom(m.validator), rem["uerth"]! * 9 / 10, spcMint!)
        case is MsgRestake: break
        case let m as MsgShieldedUndelegate:
            mintStake(PrivacyWallet.unbondDenom(m.validator, epoch: epoch), m.amount * 10 / 9, spcMint!)
        case let m as MsgClaimUnbonding:
            claimedUnbonds.append(PrivacyWallet.unbondDenom(m.validator, epoch: m.epoch))
            mint("uerth", m.amount - m.feeFromOutput, try f(m.pc), m.ciphertext)
        case let m as MsgStakeVote:
            stakeVotes.append((m.proposalID, m.validator, m.weight))
            mintStake(PrivacyWallet.derthDenom(m.validator), m.weight, spcMint!)
        case let m as MsgNoteSwap:
            let (denomIn, amountIn) = rem.first!
            let out = try swapOut(denomIn, amountIn, m.denomOut)
            if m.denomOut == "uerth" { poolAnml += BigInt(amountIn); poolErth -= BigInt(out) } else { poolErth += BigInt(amountIn); poolAnml -= BigInt(out) }
            mint(m.denomOut, out - m.feeFromOutput, try f(m.pc), m.ciphertext)
        case let m as MsgAddLiquidityShielded:
            let e = rem["uerth"]!, t = rem["uanml"]!
            let sh = shares(e, t)
            let depE = sh * poolErth / lpSupply
            let depT = sh * poolAnml / lpSupply
            poolErth += depE; poolAnml += depT; lpSupply += sh
            mint("dexlp/1", UInt64(sh), try f(m.sharePC), m.shareCiphertext)
            let rE = e - UInt64(depE), rT = t - UInt64(depT)
            if rE > 0 { mint("uerth", rE, try f(m.refundPC), m.refundCiphertext) }
            if rT > 0 { mint("uanml", rT, try f(m.refundPC), m.refundCiphertext) }
        case let m as MsgRemoveLiquidityShielded:
            withdrawals.append((BigInt(rem["dexlp/1"]!), try f(m.erthPC), try f(m.tokenPC)))
        case let m as MsgLockPosition:
            let id = nextPositionID
            nextPositionID += 1
            positions[id] = Pos(id: id, validator: m.validator, derth: m.amount, ownerTag: try f(m.stake.ownerTag),
                                splits: Dictionary(uniqueKeysWithValues: m.splits.map { ($0.optionID, $0.percent) }), createdHeight: height)
            positionOrder.append(id)
        case let m as MsgUpdatePosition:
            positions[m.positionID]!.splits = Dictionary(uniqueKeysWithValues: m.splits.map { ($0.optionID, $0.percent) })
        case let m as MsgUnlockPosition:
            let p = positions.removeValue(forKey: m.positionID)!
            positionOrder.removeAll { $0 == m.positionID }
            mintStake(PrivacyWallet.derthDenom(p.validator), p.derth, spcMint!)
        case let m as MsgPositionVote:
            positionVotes.append((m.positionID, m.proposalID))
        case let m as MsgBindReferrer:
            referrers[try f(m.membership.nullifier)] = m.address
        case let m as MsgSetCaretaker:
            caretakerVotes[try f(m.membership.nullifier)] = Dictionary(uniqueKeysWithValues: m.percentages.map { ($0.optionID, $0.percent) })
        case let m as MsgProposeRemoval:
            removalBallots[m.optionID] = 100 + m.optionID
        case let m as MsgVoteRemoval:
            removalVotes.append((m.optionID, try f(m.membership.nullifier), m.option.rawValue))
        default: break
        }
        return (m, events)
    }

    // MARK: PrivacyIndexer

    func status() async throws -> IndexerStatus {
        IndexerStatus(chainID: chainID, syncedHeight: height - 1, syncedTime: now, notes: UInt64(notes.count),
                      identityLeaves: UInt64(identityRows.count), halted: nil)
    }

    func notes(fromPos: UInt64, limit: Int?) async throws -> NotesPage {
        let n = limit ?? 1000
        let rows = Array(notes.dropFirst(Int(fromPos)).prefix(n))
        return NotesPage(rows: rows, nextPos: fromPos + UInt64(rows.count), complete: rows.count == n, syncedHeight: height - 1)
    }

    private func heights(_ set: [Fr: UInt64], _ fromHeight: UInt64) -> HeightPage<Fr> {
        let grouped = Dictionary(grouping: set.filter { $0.value >= fromHeight && $0.value < height }, by: { $0.value })
        let blocks = grouped.keys.sorted().map { (height: $0, items: grouped[$0]!.map(\.key)) }
        return HeightPage(blocks: blocks, nextHeight: height, complete: false, syncedHeight: height - 1)
    }

    func nullifiers(fromHeight: UInt64, limit: Int?) async throws -> HeightPage<Fr> { heights(nullifiers, fromHeight) }

    func identity(fromIndex: UInt64, limit: Int?) async throws -> IdentityPage {
        let rows = Array(identityRows.dropFirst(Int(fromIndex)))
        return IdentityPage(rows: rows, nextIndex: fromIndex + UInt64(rows.count), size: UInt64(identityRows.count), syncedHeight: height - 1)
    }

    func identityZeroed(fromHeight: UInt64, limit: Int?) async throws -> HeightPage<UInt64> {
        HeightPage(blocks: [], nextHeight: height, complete: false, syncedHeight: height - 1)
    }

    func rootsLatest() async throws -> LatestRoots {
        LatestRoots(note: RootRecord(root: noteTree.root(), treeSize: noteTree.size, height: height, time: now),
                    identity: RootRecord(root: identityTree.root(), treeSize: identityTree.size, height: height, time: now),
                    syncedHeight: height - 1,
                    stake: stakeTree.size == 0 ? nil : RootRecord(root: stakeTree.root(), treeSize: stakeTree.size, height: height, time: now))
    }

    func rates(epoch: UInt64?) async throws -> [RateRow] { [] }

    func stakeNotes(fromPos: UInt64, limit: Int?) async throws -> StakeNotesPage {
        let n = limit ?? 1000
        let rows = Array(stakeRows.dropFirst(Int(fromPos)).prefix(n))
        return StakeNotesPage(rows: rows, nextPos: fromPos + UInt64(rows.count), complete: rows.count == n, syncedHeight: height - 1)
    }

    func stakeNullifiers(fromHeight: UInt64, limit: Int?) async throws -> HeightPage<Fr> { heights(stakeNullifiers, fromHeight) }

    func positionReads() -> [PrivacyReads.Position] {
        positionOrder.compactMap { positions[$0] }.map {
            PrivacyReads.Position(id: $0.id, validator: $0.validator, derth: $0.derth, ownerTag: $0.ownerTag, splits: $0.splits,
                                  createdHeight: $0.createdHeight)
        }
    }

    func unshieldedTo(_ receiver: String, _ denom: String = "uerth") -> UInt64 { unshielded[receiver]?[denom] ?? 0 }
}

/// Checks each witness against its circuit's constraints (the Swift twin of
/// circuits/{action,stake,membership}/src/main.nr) and keeps it for the chain
/// to match against the tx and for a test to dump as Prover.toml.
final class CheckingProver: PrivacyProver, @unchecked Sendable {
    var actions: [ActionWitness] = []
    var stakes: [StakeWitness] = []
    var memberships: [MembershipWitness] = []
    var allActions: [ActionWitness] = []
    var allStakes: [StakeWitness] = []
    var allMemberships: [MembershipWitness] = []

    func proveAction(_ w: ActionWitness) async throws -> Data {
        try w.check()
        // The circuit's base-canonicality: the value bases' y <= (p-1)/2.
        guard Grumpkin.valueBase(w.sAsset).y.bigUInt <= Grumpkin.halfP else { throw PrivacyError("non-canonical base") }
        actions.append(w); allActions.append(w)
        return Data(repeating: 1, count: PrivateTxEngine.proofBytes)
    }

    func proveStake(_ w: StakeWitness) async throws -> Data {
        try w.check()
        stakes.append(w); allStakes.append(w)
        return Data(repeating: 3, count: PrivateTxEngine.proofBytes)
    }

    func proveMembership(_ w: MembershipWitness) async throws -> Data {
        try w.check()
        memberships.append(w); allMemberships.append(w)
        return Data(repeating: 2, count: PrivateTxEngine.proofBytes)
    }
}

/// PrivacyChainReads over a FakeChain; snapshots are of the stake tree.
struct FakeReads: PrivacyChainReads, @unchecked Sendable {
    let chain: FakeChain
    var snapshotSize: () -> UInt64
    var snapshotHeight: () -> Int64 = { 0 }

    func personhoodParams() async throws -> PrivacyReads.PersonhoodParams { .init(caretakerVoteSeconds: 30 * 86_400, identityRootWindowSeconds: 3_600) }

    func ballotInputs(proposalID: UInt64, optionID: UInt64) async throws -> PrivacyReads.BallotInputs {
        if proposalID != 0 {
            return .init(scope: PrivacyHash.proposalScope(proposalID: proposalID, round: 0), excludedDsc: .zero, excludedCountry: .zero,
                         maxActivation: UInt64(chain.now - 3600), round: 0, ballotID: 0)
        }
        let id = chain.removalBallots[optionID]!
        return .init(scope: PrivacyHash.removalScope(ballotID: id), excludedDsc: .zero, excludedCountry: .zero,
                     maxActivation: UInt64(chain.now - 3600), round: 0, ballotID: id)
    }

    func epochNumber() async throws -> UInt64 { chain.epoch }

    func snapshot(proposalID: UInt64) async throws -> PrivacyReads.Snapshot {
        let s = snapshotSize()
        return .init(root: chain.stakeTree.rootAt(s), treeSize: s, height: snapshotHeight())
    }

    func positions() async throws -> [PrivacyReads.Position] { chain.positionReads() }
}
