import BigInt
import Foundation
import secp256k1
@testable import EarthCore

/// An in-memory model of the chain's private side, for driving a wallet end to
/// end without a node (ports FakeChain.kt): the note and identity trees, the
/// nullifier set, the ante's checks a private tx must pass (shape, fee,
/// anchors, nullifiers, the signal and every proof's statement), and the mints
/// each msg makes. Its indexer serves the same streams the backend does.
/// Proofs are not real: `CheckingProver` checks each witness against the
/// circuit's constraints instead, and keeps it so a test can hand it to nargo.
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
    var height: UInt64 = 1
    let minFeeValue: UInt64 = 1000
    let price = Decimal(string: "0.001")!
    lazy var prover = CheckingProver()
    var unshielded: [String: UInt64] = [:]
    var votes: [(UInt64, Int)] = []
    var simulated = 0

    // x/dex pool 1 (uanml/uerth) and its LP shares.
    var poolErth = BigInt(1_000_000_000_000)
    var poolAnml = BigInt(500_000_000_000)
    var lpSupply = BigInt(700_000_000_000)
    let swapFee = Decimal(string: "0.3")!
    var lpShares: [String: BigInt] = [:]

    final class Pos {
        let id: UInt64, validator: String, derth: UInt64, pubkey: Data
        var nonce: UInt64, splits: [UInt64: UInt64]
        init(id: UInt64, validator: String, derth: UInt64, pubkey: Data, nonce: UInt64, splits: [UInt64: UInt64]) {
            self.id = id; self.validator = validator; self.derth = derth; self.pubkey = pubkey; self.nonce = nonce; self.splits = splits
        }
    }
    var positions: [UInt64: Pos] = [:]
    var positionOrder: [UInt64] = []
    var positionVotes: [(UInt64, UInt64)] = []
    var referrers: [Fr: String] = [:]
    var removalBallots: [UInt64: UInt64] = [:]
    var removalVotes: [(UInt64, Fr, Int)] = []
    var caretakerVotes: [Fr: [UInt64: UInt64]] = [:]

    init() {
        noteRoots.insert(noteTree.root())
        identityRoots.insert(identityTree.root())
    }

    struct Refused: Error { let why: String }
    func need(_ ok: Bool, _ why: String) throws { if !ok { throw Refused(why: why) } }

    /// Ends the block being built: its roots become anchors.
    private func block() { noteRoots.insert(noteTree.root()); identityRoots.insert(identityTree.root()); height += 1 }

    @discardableResult
    func mint(_ denom: String, _ value: UInt64, _ pc: Fr, _ ct: Data = Data()) -> UInt64 {
        let cm = PrivacyHash.cm(asset: PrivacyHash.assetID(denom), value: value, pc: pc)
        let pos = noteTree.append(cm)
        notes.append(NoteRow(position: pos, height: height, cm: cm, ciphertext: ct, amount: "\(value)\(denom)"))
        return pos
    }

    func shield(_ denom: String, _ value: UInt64, _ pc: Fr, _ ct: Data = Data()) { mint(denom, value, pc, ct); block() }

    // MARK: PrivateChain

    func gasPrice() async throws -> Decimal { price }
    func minFee() async throws -> UInt64 { minFeeValue }

    func simulate(_ tx: Data) async throws -> UInt64 { simulated += 1; _ = try check(tx, simulate: true); return 2_100_000 }

    func broadcast(_ tx: Data) async throws -> TxResult {
        let events = try check(tx, simulate: false)
        block()
        return TxResult(hash: "HASH\(height - 1)", height: height - 1, time: now, events: events)
    }

    func swapOut(_ denomIn: String, _ amountIn: UInt64, _ denomOut: String) throws -> UInt64 {
        guard let q = SwapMath.route(pools: ["uanml": .init(erth: poolErth, token: poolAnml)], hub: "uerth", denomIn: denomIn,
                                     amountIn: BigInt(amountIn), denomOut: denomOut, feePercent: swapFee) else { throw Refused(why: "no route") }
        return UInt64(q.amountOut)
    }

    private func positionSigOK(_ p: Pos, _ action: String, _ payload: Data, _ sig: Data) -> Bool {
        let msg = PrivateMsgs.positionSignBytes(chainID: chainID, action: action, positionID: p.id, nonce: p.nonce, payload: payload)
        guard let pub = try? secp256k1.Signing.PublicKey(dataRepresentation: p.pubkey, format: .compressed),
              let s = try? secp256k1.Signing.ECDSASignature(compactRepresentation: sig) else { return false }
        // libsecp256k1 verification refuses a high-S signature, as the chain does.
        return pub.isValidSignature(s, for: msg)
    }

    private func shares(_ m: MsgAddLiquidityShielded) -> BigInt {
        min(BigInt(m.erthTransfer.valueOut) * lpSupply / poolErth, BigInt(m.transfer.valueOut) * lpSupply / poolAnml)
    }

    /// The action's own checks, before anything is written.
    private func precheck(_ m: any PrivateMsg) throws {
        switch m {
        case let m as MsgNoteSwap:
            let out = try swapOut(m.transfer.denomOut, m.transfer.valueOut, m.denomOut)
            try need(out >= m.minAmountOut, "slippage: got \(out), want >= \(m.minAmountOut)")
            try need(m.feeFromOutput == 0 || (m.denomOut == "uerth" && m.minAmountOut > m.feeFromOutput && m.transfer.fee == 0), "fee from output")
        case let m as MsgAddLiquidityShielded:
            try need(m.poolID == 1 && m.transfer.denomOut == "uanml" && m.erthTransfer.denomOut == "uerth", "pool 1 legs")
            if !m.minShares.isEmpty { try need(shares(m) >= BigInt(m.minShares)!, "below min_shares") }
        case let m as MsgUpdatePosition:
            try need(positionSigOK(positions[m.positionID]!, "update", PrivateMsgs.splitsBytes(m.splits), m.signature), "bad signature")
        case let m as MsgUnlockPosition:
            try need(positionSigOK(positions[m.positionID]!, "unlock", m.pc + m.ciphertext, m.signature), "bad signature")
        case let m as MsgPositionVote:
            try need(positionSigOK(positions[m.positionID]!, "vote", try PrivateMsgs.positionVotePayload(proposalID: m.proposalID, options: m.options), m.signature), "bad signature")
        case let m as MsgVoteRemoval:
            try need(removalBallots[m.optionID] != nil, "no open ballot")
        case let m as MsgProposeRemoval:
            try need(removalBallots[m.optionID] == nil, "ballot already open")
        default: break
        }
    }

    /// A membership's expected scope and max_activation, per msg.
    private func membershipStatement(_ m: any PrivateMsg) -> (Membership, Fr, UInt64)? {
        let day = UInt64(now / 86_400)
        switch m {
        case let m as MsgClaimAnmlPrivate: return (m.membership, PrivacyHash.claimScope(day: m.day), (m.day - 1) * 86_400)
        case let m as MsgVoteProposalPrivate: return (m.membership, PrivacyHash.proposalScope(proposalID: m.proposalID, round: 0), UInt64(now - 3600))
        case let m as MsgSetCaretaker: return (m.membership, PrivacyHash.caretakerScope(), m.maxActivation)
        case let m as MsgBindReferrer: return (m.membership, PrivacyHash.referrerScope(), m.maxActivation)
        case let m as MsgProposeRemoval: return (m.membership, PrivacyHash.proposeRemovalScope(optionID: m.optionID, day: day), day * 86_400 - 3600)
        case let m as MsgVoteRemoval: return (m.membership, PrivacyHash.removalScope(ballotID: removalBallots[m.optionID] ?? 0), UInt64(now - 3600))
        default: return nil
        }
    }

    /// The ante, then the handler.
    private func check(_ txBytes: Data, simulate: Bool) throws -> [(type: String, attributes: [String: String])] {
        let tx = try UnsignedTx.decode(txBytes)
        try need(tx.signatures == 0, "private txs are unsigned")
        try need(tx.signerInfos == 0, "no signer infos")
        let m = tx.msg
        let ts = m.transfers
        let total = m.totalFee
        try need(tx.feeCoins.count == 1 && tx.feeCoins[0].denom == "uerth" && tx.feeCoins[0].amount == String(total), "declared fee != msg fee")
        try need(total >= minFeeValue, "below min fee")
        if !simulate { try need(Decimal(total) >= price * Decimal(tx.gasLimit), "below min gas price") }
        let signal = try m.signal(chainID: chainID)
        for (i, t) in ts.enumerated() {
            let root = try Fr(bytes: t.root)
            try need(noteRoots.contains(root), "unknown anchor")
            for nf in t.nullifiers { try need(nullifiers[try Fr(bytes: nf)] == nil, "nullifier spent") }
            try need((t.valueOut == 0) == t.denomOut.isEmpty, "value out and denom out")
            if !simulate {
                guard !prover.transfers.isEmpty else { throw Refused(why: "no proof for transfer \(i)") }
                let w = prover.transfers.removeFirst()
                let assetPub = t.valueOut > 0 ? PrivacyHash.assetID(t.denomOut) : .zero
                let expect = [root] + (try t.nullifiers.map { try Fr(bytes: $0) }) + (try t.commitments.map { try Fr(bytes: $0) }) +
                    [PrivacyHash.u64(t.fee), PrivacyHash.u64(t.valueOut), assetPub, signal]
                try need(w.publicInputs() == expect, "transfer \(i) proof is for other public inputs")
            }
        }
        if let (mem, scope, maxAct) = membershipStatement(m) {
            try need(identityRoots.contains(try Fr(bytes: mem.root)), "unknown identity anchor")
            if !simulate {
                guard !prover.memberships.isEmpty else { throw Refused(why: "no membership proof") }
                let w = prover.memberships.removeFirst()
                let expect = [try Fr(bytes: mem.root), scope, try Fr(bytes: mem.nullifier), signal, .zero, .zero, PrivacyHash.u64(maxAct)]
                try need(w.publicInputs() == expect, "membership proof is for other public inputs")
            }
        }
        try precheck(m)
        if simulate { return [] }
        // Execute: spend, append, then the action.
        for t in ts {
            for nf in t.nullifiers { nullifiers[try Fr(bytes: nf)] = height }
            for (i, cmb) in t.commitments.enumerated() {
                let cm = try Fr(bytes: cmb)
                let pos = noteTree.append(cm)
                notes.append(NoteRow(position: pos, height: height, cm: cm, ciphertext: t.ciphertexts[i], amount: nil))
            }
        }
        var events: [(type: String, attributes: [String: String])] = []
        func f(_ d: Data) throws -> Fr { try Fr(bytes: d) }
        switch m {
        case let m as MsgShieldedTransfer:
            if m.transfer.valueOut > 0 { unshielded[m.receiver, default: 0] += m.transfer.valueOut - m.feeFromOutput }
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
            mint("derth/\(m.validator)", m.transfer.valueOut * 9 / 10, try f(m.pc), m.ciphertext)
        case let m as MsgStakeVote:
            votes.append((m.proposalID, -1))
            mint(m.transfer.denomOut, m.transfer.valueOut, try f(m.pc), m.ciphertext)
        case let m as MsgShieldedUndelegate:
            mint(PrivacyWallet.unbondDenom(m.validator, epoch: 4), m.transfer.valueOut, try f(m.pc), m.ciphertext)
        case let m as MsgClaimUnbonding:
            mint("uerth", m.transfer.valueOut - m.feeFromOutput, try f(m.pc), m.ciphertext)
        case let m as MsgNoteSwap:
            let inAmt = BigInt(m.transfer.valueOut)
            let out = try swapOut(m.transfer.denomOut, m.transfer.valueOut, m.denomOut)
            if m.denomOut == "uerth" { poolAnml += inAmt; poolErth -= BigInt(out) } else { poolErth += inAmt; poolAnml -= BigInt(out) }
            mint(m.denomOut, out - m.feeFromOutput, try f(m.pc), m.ciphertext)
        case let m as MsgAddLiquidityShielded:
            let sh = shares(m)
            let depE = sh * poolErth / lpSupply
            let depT = sh * poolAnml / lpSupply
            poolErth += depE; poolAnml += depT; lpSupply += sh
            lpShares[m.provider, default: 0] += sh
            let pc = try f(m.refundPC)
            let rE = m.erthTransfer.valueOut - UInt64(depE)
            let rT = m.transfer.valueOut - UInt64(depT)
            if rE > 0 { mint("uerth", rE, pc, m.refundCiphertext) }
            if rT > 0 { mint("uanml", rT, pc, m.refundCiphertext) }
        case let m as MsgLockPosition:
            let id = UInt64(positionOrder.count + 1)
            positions[id] = Pos(id: id, validator: m.validator, derth: m.transfer.valueOut, pubkey: m.pubkey, nonce: 0,
                                splits: Dictionary(uniqueKeysWithValues: m.splits.map { ($0.optionID, $0.percent) }))
            positionOrder.append(id)
        case let m as MsgUpdatePosition:
            let p = positions[m.positionID]!
            p.splits = Dictionary(uniqueKeysWithValues: m.splits.map { ($0.optionID, $0.percent) }); p.nonce += 1
        case let m as MsgUnlockPosition:
            let p = positions.removeValue(forKey: m.positionID)!
            mint(PrivacyWallet.derthDenom(p.validator), p.derth, try f(m.pc), m.ciphertext)
        case let m as MsgPositionVote:
            positions[m.positionID]!.nonce += 1
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
        return events
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

    func nullifiers(fromHeight: UInt64, limit: Int?) async throws -> HeightPage<Fr> {
        let grouped = Dictionary(grouping: nullifiers.filter { $0.value >= fromHeight && $0.value < height }, by: { $0.value })
        let blocks = grouped.keys.sorted().map { (height: $0, items: grouped[$0]!.map(\.key)) }
        return HeightPage(blocks: blocks, nextHeight: height, complete: false, syncedHeight: height - 1)
    }

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
                    syncedHeight: height - 1)
    }

    func rates(epoch: UInt64?) async throws -> [RateRow] { [] }

    func positionReads() -> [PrivacyReads.Position] {
        positionOrder.compactMap { positions[$0] }.map {
            PrivacyReads.Position(id: $0.id, validator: $0.validator, derth: $0.derth, pubkey: $0.pubkey, nonce: $0.nonce, splits: $0.splits)
        }
    }
}

/// Checks each witness against the circuits' constraints (the Swift twin of
/// circuits/{transfer,membership}/src/main.nr) and keeps it for the chain to
/// match against the tx and for a test to dump as Prover.toml.
final class CheckingProver: PrivacyProver, @unchecked Sendable {
    var transfers: [TransferWitness] = []
    var memberships: [MembershipWitness] = []
    var allTransfers: [TransferWitness] = []
    var allMemberships: [MembershipWitness] = []

    func proveTransfer(_ w: TransferWitness) async throws -> Data {
        let opk = PrivacyHash.ownerPK(w.nk)
        for (i, inp) in w.inputs.enumerated() where inp.value != 0 {
            let cm = PrivacyHash.cm(asset: w.assets[i], value: inp.value, pc: PrivacyHash.pc(ownerPK: opk, rho: inp.rho, rcm: inp.rcm))
            guard Merkle.rootFromPath(leaf: cm, index: inp.position, siblings: inp.path) == w.root else { throw PrivacyError("input \(i) not in note tree") }
        }
        guard Set(w.nullifiers).count == 3 else { throw PrivacyError("duplicate nullifier") }
        guard w.vPubOut == 0 || w.assetPub == w.asset else { throw PrivacyError("asset_pub") }
        transfers.append(w); allTransfers.append(w)
        return Data(repeating: 1, count: PrivateTxEngine.proofBytes)
    }

    func proveMembership(_ w: MembershipWitness) async throws -> Data {
        try w.check()
        memberships.append(w); allMemberships.append(w)
        return Data(repeating: 2, count: PrivateTxEngine.proofBytes)
    }
}

/// PrivacyChainReads over a FakeChain.
struct FakeReads: PrivacyChainReads, @unchecked Sendable {
    let chain: FakeChain
    var snapshotSize: () -> UInt64

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

    func epochNumber() async throws -> UInt64 { 4 }

    func snapshot(proposalID: UInt64) async throws -> PrivacyReads.Snapshot {
        let s = snapshotSize()
        return .init(root: chain.noteTree.rootAt(s), treeSize: s)
    }

    func positions() async throws -> [PrivacyReads.Position] { chain.positionReads() }
}
