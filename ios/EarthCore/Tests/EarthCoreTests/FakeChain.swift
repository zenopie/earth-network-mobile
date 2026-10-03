import BigInt
import secp256k1
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
final class FakeChain: PrivateChain, PrivacyIndexer, ChainRoots, @unchecked Sendable {
    let chainID = "earth-1"
    var now: Int64 = 1_790_000_000
    /// The first block hash's prefix the indexer keys its base by; a relaunch changes it.
    var genesis = "0123456789abcdef"
    /// Set to make the indexer report it has halted.
    var halted: String?
    /// The country the chain records for registrations (the verifying CSCA's).
    var registrationCountry = "DE"
    /// Every note root the chain recorded, with its tree size.
    var noteRootSizes: [Fr: UInt64] = [:]
    /// (height -> tree state) after each block, for queries pinned to a height.
    var identityAt: [UInt64: TreeState] = [:]
    var stakeAt: [UInt64: TreeState] = [:]
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
    var price = Decimal(string: "0.001")!
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
    /// Private withdrawals waiting to mature.
    struct Withdrawal { let shares: BigInt; let erthPC: Fr; let erthCt: Data; let tokenPC: Fr; let tokenCt: Data }
    var withdrawals: [Withdrawal] = []

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
        block()
    }

    struct Refused: Error, CustomStringConvertible { let why: String; var description: String { why } }
    func need(_ ok: Bool, _ why: @autoclosure () -> String) throws { if !ok { throw Refused(why: why()) } }

    /// Ends the block being built: its roots become anchors. Writes land at `height`, the block in progress.
    private func block() {
        noteRoots.insert(noteTree.root()); identityRoots.insert(identityTree.root())
        noteRootSizes[noteTree.root()] = noteTree.size
        if stakeTree.size > 0 { stakeRoots.insert(stakeTree.root()) }
        identityAt[height] = TreeState(size: identityTree.size, root: identityTree.size == 0 ? nil : identityTree.root())
        stakeAt[height] = TreeState(size: stakeTree.size, root: stakeTree.size == 0 ? nil : stakeTree.root())
        blockTimes[height] = UInt64(now)
        height += 1
    }

    /// Every note the chain mints carries a 177-byte amount-blind ciphertext (one note-discovery rule).
    @discardableResult
    func mint(_ denom: String, _ value: UInt64, _ pc: Fr, _ ct: Data) -> UInt64 {
        precondition(ct.count == NoteCipher.blindCiphertextBytes, "a minted note needs its 177-byte blind ciphertext, got \(ct.count)")
        let cm = PrivacyHash.cm(asset: PrivacyHash.assetID(denom), value: value, pc: pc)
        let pos = noteTree.append(cm)
        notes.append(NoteRow(position: pos, height: height, cm: cm, ciphertext: ct, amount: "\(value)\(denom)"))
        return pos
    }

    @discardableResult
    func mintStake(_ denom: String, _ amount: UInt64, _ spc: Fr, _ ct: Data) -> UInt64 {
        precondition(ct.count == NoteCipher.blindCiphertextBytes, "a minted stake note needs its blind stake ciphertext")
        let cm = PrivacyHash.stakeCM(asset: PrivacyHash.assetID(denom), amount: amount, spc: spc)
        let pos = stakeTree.append(cm)
        stakeRows.append(StakeNoteRow(position: pos, height: height, cm: cm, ciphertext: ct, denom: denom, amount: amount, spc: spc))
        return pos
    }

    /// MsgShield (a gas grant, a shield from a transparent account): its ciphertext is required.
    func shield(_ denom: String, _ value: UInt64, _ pc: Fr, _ ct: Data) { mint(denom, value, pc, ct); block() }

    /// Ends a block with no tx in it.
    func emptyBlock() { block() }

    /// The LP unbonding period passes: every private withdrawal pays both legs as notes.
    func matureWithdrawals() {
        for w in withdrawals {
            let sh = w.shares
            let e = sh * poolErth / lpSupply
            let t = sh * poolAnml / lpSupply
            poolErth -= e; poolAnml -= t; lpSupply -= sh
            mint("uerth", UInt64(e), w.erthPC, w.erthCt)
            mint("uanml", UInt64(t), w.tokenPC, w.tokenCt)
        }
        withdrawals.removeAll()
        block()
    }

    // MARK: PrivateChain

    func gasPrice() async throws -> Decimal { price }
    func minFee() async throws -> UInt64 { minFeeValue }
    func maxActionsPerBundle() async throws -> Int { maxActions }
    func tipHeight() async throws -> UInt64 { height - 1 + tipAhead }

    /// Every nullifier (pool, stake) the node saw in a simulated tx.
    var simulatedNullifiers: [Fr] = []
    /// Whether a committed tx must carry a timeout_height (the wallet always sets one).
    var requireTimeout = true
    /// The last checked tx's timeout_height.
    var lastTimeoutHeight: UInt64 = 0
    /// Broadcasts accepted (CheckTx) and then dropped: never in a block.
    var dropNext = 0

    /// The ante charges per bundle and per action, before anything else: gas is the tx's shape.
    func simulate(_ tx: Data) async throws -> UInt64 {
        simulated += 1
        let m = try check(tx, simulate: true).0
        for b in m.bundles { for a in b.actions { simulatedNullifiers.append(try Fr(bytes: a.nullifier)) } }
        for n in m.stakeProof?.nullifiers ?? [] { simulatedNullifiers.append(try Fr(bytes: n)) }
        let actions = m.bundles.reduce(0) { $0 + $1.actions.count }
        return 200_000 + 100_000 * UInt64(m.bundles.count) + 350_000 * UInt64(actions) + (m.stakeProof != nil ? 400_000 : 0)
    }

    /// Broadcasts to refuse (after the wallet proved them): a node down, a tx dropped.
    var rejectNext = 0

    /// Broadcasts accepted and committed whose wait then times out.
    var unconfirmedNext = 0
    /// Broadcasts accepted (CheckTx) that then fail in their block (DeliverTx code 5): nothing changes.
    var failInBlockNext = 0
    /// Every tx by hash, as Query/GetTx answers.
    var txs: [String: TxResult] = [:]

    func tx(_ hash: String) async throws -> TxResult? { txs[hash] }

    func broadcast(_ tx: Data, accepted: @Sendable (String) -> Void) async throws -> TxResult {
        if rejectNext > 0 {
            // The proofs made for it never reach the chain.
            rejectNext -= 1
            prover.actions.removeAll(); prover.stakes.removeAll(); prover.memberships.removeAll()
            throw URLError(.networkConnectionLost)
        }
        let hash = "HASH\(height)"
        if dropNext > 0 {
            // Accepted by CheckTx, then never included (evicted from the mempool).
            dropNext -= 1
            _ = try check(tx, simulate: true)
            accepted(hash)
            prover.actions.removeAll(); prover.stakes.removeAll(); prover.memberships.removeAll()
            throw URLError(.timedOut)
        }
        if failInBlockNext > 0 {
            failInBlockNext -= 1
            _ = try check(tx, simulate: true)
            accepted(hash)
            prover.actions.removeAll(); prover.stakes.removeAll(); prover.memberships.removeAll()
            block()
            txs[hash] = TxResult(hash: hash, height: height - 1, time: now, events: [], code: 5, log: "failed in block (test)")
            throw Refused(why: "tx failed (code 5)")
        }
        _ = try check(tx, simulate: true)
        accepted(hash)
        let (_, events) = try check(tx, simulate: false)
        block()
        let r = TxResult(hash: hash, height: height - 1, time: now, events: events)
        txs[hash] = r
        if unconfirmedNext > 0 {
            unconfirmedNext -= 1
            throw EarthClient.Error.notCommitted(hash: hash)
        }
        return r
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
            // Wave 3 (B/F2): never to a module account.
            if !m.receiver.isEmpty { try need(PrivateMsgs.moduleAccount(of: Data(try Bech32.decode(m.receiver).data)) == nil, "receiver is a module account") }
            try need(!rem.keys.contains { $0.hasPrefix("dexlp/") }, "LP shares cannot be unshielded")
        case let m as MsgShieldedDelegate:
            try only("uerth")
            try need(rem["uerth"] == m.amount && m.amount > 0, "delegate releases amount")
        case let m as MsgNoteSwap:
            try need(rem == [m.denomIn: m.amountIn] && m.denomOut != m.denomIn, "a swap releases exactly amount_in of denom_in: \(rem)")
            try need(m.privateFee > 0, "a swap pays a positive fee from its bundle")
            try need(m.ciphertext.count == NoteCipher.blindCiphertextBytes, "swap ciphertext")
        case let m as MsgAddLiquidityShielded:
            try need(Set(rem.keys) == ["uerth", "uanml"] && m.poolID == 1 && rem["uerth"] == m.erthAmount, "deposit legs")
            try need(m.privateFee > 0, "deposit fee")
            try need(m.shareCiphertext.count == NoteCipher.blindCiphertextBytes && m.refundCiphertext.count == NoteCipher.blindCiphertextBytes,
                     "deposit ciphertexts")
        case let m as MsgRemoveLiquidityShielded:
            try only("dexlp/\(m.poolID)")
            try need(m.erthCiphertext.count == NoteCipher.blindCiphertextBytes && m.tokenCiphertext.count == NoteCipher.blindCiphertextBytes,
                     "withdrawal ciphertexts")
        case let m as MsgClaimUnbonding:
            try only(nil)
            try need(m.ciphertext.count == NoteCipher.blindCiphertextBytes, "claim ciphertext")
            // Exactly one way to pay: the bundle, or from the output.
            try need((m.privateFee == 0) != (m.feeFromOutput == 0), "claim fee")
        case let m as MsgRegisterPrivate:
            try only(nil)
            try need(m.ciphertextAnml.count == NoteCipher.blindCiphertextBytes && m.ciphertextErth.count == NoteCipher.blindCiphertextBytes,
                     "registration ciphertexts")
        case let m as MsgClaimAnmlPrivate:
            try only(nil)
            try need(m.ciphertext.count == NoteCipher.blindCiphertextBytes, "claim ciphertext")
        default: try only(nil)
        }
        try need(m.totalFee > 0, "no fee")
    }

    /// Whether the msg has the chain mint a stake note to spc_mint (its blind stake ciphertext is then required).
    private func mintsStake(_ m: any PrivateMsg) -> Bool {
        m is MsgShieldedDelegate || m is MsgShieldedUndelegate || m is MsgStakeVote || m is MsgUnlockPosition
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
        // Wave 3 (L4/L5): a lease's max_activation is at most now - R - 86400 (R = 30 days here).
        case let m as MsgSetCaretaker: return (PrivacyHash.caretakerScope(), Int64(m.maxActivation) <= now - 31 * 86_400 ? m.maxActivation : UInt64.max)
        case let m as MsgBindReferrer: return (PrivacyHash.referrerScope(), Int64(m.maxActivation) <= now - 31 * 86_400 ? m.maxActivation : UInt64.max)
        case let m as MsgProposeRemoval: return (PrivacyHash.proposeRemovalScope(optionID: m.optionID, day: day), day * 86_400 - 86_400)
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
        case let m as MsgAddLiquidityShielded:
            if !m.minShares.isEmpty { try need(shares(rem["uerth"]!, rem["uanml"]!) >= BigInt(m.minShares)!, "below min_shares") }
        case let m as MsgUpdatePosition:
            try need(positions[m.positionID]?.ownerTag == (try f(m.stake.ownerTag)), "not the position's owner")
        case let m as MsgUnlockPosition:
            try need(positions[m.positionID]?.ownerTag == (try f(m.stake.ownerTag)), "not the position's owner")
        case let m as MsgPositionVote:
            try need(positions[m.positionID]?.ownerTag == (try f(m.stake.ownerTag)), "not the position's owner")
            try need(m.options.allSatisfy { (try? PrivateMsgs.legacyDec($0.weight)) == $0.weight }, "a vote weight is not canonical")
        case let m as MsgStakeVote:
            // Wave 3 (F3): option weights only in their canonical LegacyDec form.
            try need(m.options.allSatisfy { (try? PrivateMsgs.legacyDec($0.weight)) == $0.weight }, "a vote weight is not canonical")
        case let m as MsgBindReferrer:
            // Wave 3 (L6): binding an address needs its owner's consent (cosmos secp256k1 over SHA-256).
            if m.address.isEmpty {
                try need(m.referrerPubKey.isEmpty && m.referrerSignature.isEmpty, "clearing a binding carries no consent")
            } else {
                try need(m.referrerPubKey.count == 33 && m.referrerSignature.count == 64, "no referrer consent")
                try need(try EarthKey.address(fromPublicKey: m.referrerPubKey) == m.address, "consent key is not the address's")
                let msg = try PrivateMsgs.referrerConsentBytes(chainID: chainID, nullifier: m.membership.nullifier, address: Data(try Bech32.decode(m.address).data))
                let key = try secp256k1.Signing.PublicKey(dataRepresentation: m.referrerPubKey, format: .compressed)
                let sig = try secp256k1.Signing.ECDSASignature(compactRepresentation: m.referrerSignature)
                try need(key.isValidSignature(sig, for: msg), "bad referrer consent")
            }
        case let m as MsgVoteRemoval: try need(removalBallots[m.optionID] != nil, "no open ballot")
        case let m as MsgProposeRemoval: try need(removalBallots[m.optionID] == nil, "ballot already open")
        case let m as MsgClaimUnbonding: try need((m.bundle != nil) == (m.feeFromOutput == 0), "claim fee")
        case let m as MsgRegisterPrivate:
            let idc = try f(m.idc)
            try need(!identityRows.contains { $0.leaf != .zero && registeredIdc[$0.index] == idc }, "a switch to the live idc is refused")
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
            try need(a.proof.count == PrivateTxEngine.proofBytes, "a proof is exactly \(PrivateTxEngine.proofBytes) bytes")
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
        // Round 2 (R7): exactly the canonical encoding of what it decodes to.
        try need(UnsignedTx.build(m, tx: tx.txFields) == txBytes, "tx bytes are not canonical")
        // Every action's output ciphertext exactly 217 bytes, dummies included.
        for b in m.bundles { for a in b.actions { try need(a.ciphertext.count == NoteCipher.ciphertextBytes, "action ciphertext \(a.ciphertext.count) bytes") } }
        // Stake proofs: exactly two ciphertext slots, empty iff the commitment is zero, else 153 bytes.
        if let p = m.stakeProof {
            try need(p.ciphertexts.count == 2, "stake proof has \(p.ciphertexts.count) ciphertexts")
            for i in 0 ..< 2 {
                let zero = p.commitments.count > i && p.commitments[i].allSatisfy { $0 == 0 }
                try need(zero ? p.ciphertexts[i].isEmpty : p.ciphertexts[i].count == NoteCipher.stakeCiphertextBytes, "stake ciphertext \(i)")
            }
        }
        // timeout_height: the block being built must not be past it (0: none).
        try need(tx.txFields.timeoutHeight == 0 || height <= tx.txFields.timeoutHeight, "tx timed out")
        if requireTimeout && !simulate { try need(tx.txFields.timeoutHeight > 0, "a private tx without timeout_height") }
        lastTimeoutHeight = tx.txFields.timeoutHeight
        let total = m.totalFee
        try need(tx.feeCoins.count == 1 && tx.feeCoins[0].denom == "uerth" && tx.feeCoins[0].amount == String(total), "declared fee != msg fee")
        try need(total >= minFeeValue, "below min fee")
        if !simulate { try need(Decimal(total) >= price * Decimal(tx.gasLimit), "below min gas price") }
        let bundles = m.bundles
        try need(((m.feeFromOutput > 0 ? 0 : 1) ... 2).contains(bundles.count), "bundle count")
        let rem = try remainders(m)
        try checkRelease(m, rem)
        // The tx fields every private sighash binds (the ante records them).
        let sighash = try m.sighash(chainID: chainID, tx: tx.txFields)
        var seen = Set<Fr>()
        for (i, b) in bundles.enumerated() { try checkBundle(i, b, sighash, simulate: simulate, seen: &seen) }

        let stake = m.stakeProof
        if let stake {
            try need(stake.nullifiers.count == 2 && stake.commitments.count == 2 && stake.ciphertexts.count <= 2, "stake proof shape")
            try need(stake.proof.count == PrivateTxEngine.proofBytes, "a stake proof is exactly \(PrivateTxEngine.proofBytes) bytes")
            if mintsStake(m) {
                try need(stake.spcCiphertext.count == NoteCipher.blindCiphertextBytes, "spc_ciphertext required")
            } else {
                try need(stake.spcCiphertext.isEmpty, "spc_ciphertext only for a msg that mints")
            }
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
            try need(mem.proof.count == PrivateTxEngine.proofBytes, "a membership proof is exactly \(PrivateTxEngine.proofBytes) bytes")
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
            // Round 2 (R1): a landed binding is never used again.
            try need(usedBindings.insert(binding).inserted, "binding already used (ErrBindingUsed)")
            let dsc = try PrivateMsgs.decimalField(m.publicSignals[3])
            let idc = try f(m.idc)
            // A switch: the holder's old leaf is zeroed, the new one appended.
            for (i, c) in registeredIdc where c == idc || passportOf[i] == m.publicSignals[2] { zeroLeaf(i) }
            let idx = identityTree.append(PrivacyHash.identityLeaf(idc: idc, dscKey: dsc, country: PrivacyHash.countryField(registrationCountry),
                                                                   activatedAt: UInt64(now)))
            identityRows.append(IdentityRow(index: idx, height: height, leaf: identityTree.leaf(idx), zeroedHeight: nil, time: UInt64(now)))
            registeredIdc[idx] = idc; passportOf[idx] = m.publicSignals[2]
            mint("uanml", 1_000_000, try f(m.pcAnml), m.ciphertextAnml)
            mint("uerth", 5_000_000, try f(m.pcErth), m.ciphertextErth)
            events.append((type: "register", attributes: ["leaf_index": String(idx)]))
        case let m as MsgClaimAnmlPrivate:
            mint("uanml", 1_000_000, try f(m.pc), m.ciphertext)
        case let m as MsgVoteProposalPrivate:
            votes.append((m.proposalID, m.option.rawValue))
        case let m as MsgShieldedDelegate:
            mintStake(PrivacyWallet.derthDenom(m.validator), m.amount * 9 / 10, spcMint!, stake!.spcCiphertext)
        case is MsgRestake: break
        case let m as MsgShieldedUndelegate:
            mintStake(PrivacyWallet.unbondDenom(m.validator, epoch: epoch), m.amount * 10 / 9, spcMint!, stake!.spcCiphertext)
        case let m as MsgClaimUnbonding:
            claimedUnbonds.append(PrivacyWallet.unbondDenom(m.validator, epoch: m.epoch))
            mint("uerth", m.amount - m.feeFromOutput, try f(m.pc), m.ciphertext)
        case let m as MsgStakeVote:
            stakeVotes.append((m.proposalID, m.validator, m.weight))
            mintStake(PrivacyWallet.derthDenom(m.validator), m.weight, spcMint!, stake!.spcCiphertext)
        case let m as MsgNoteSwap:
            let (denomIn, amountIn) = rem.first!
            let out = try swapOut(denomIn, amountIn, m.denomOut)
            if m.denomOut == "uerth" { poolAnml += BigInt(amountIn); poolErth -= BigInt(out) } else { poolErth += BigInt(amountIn); poolAnml -= BigInt(out) }
            mint(m.denomOut, out, try f(m.pc), m.ciphertext)
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
            withdrawals.append(Withdrawal(shares: BigInt(rem["dexlp/1"]!), erthPC: try f(m.erthPC), erthCt: m.erthCiphertext,
                                          tokenPC: try f(m.tokenPC), tokenCt: m.tokenCiphertext))
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
            mintStake(PrivacyWallet.derthDenom(p.validator), p.derth, spcMint!, stake!.spcCiphertext)
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
                      identityLeaves: UInt64(identityRows.count), halted: halted, genesis: genesis, base: "/privacy/\(chainID)/\(genesis)")
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

    /// Whether the indexer serves each identity row's block time (the fifth column); false: an indexer without it.
    var identityRowTimes = true

    func identity(fromIndex: UInt64, limit: Int?) async throws -> IdentityPage {
        let rows = Array(identityRows.dropFirst(Int(fromIndex))).map { identityRowTimes ? $0 : $0.with(time: nil) }
        return IdentityPage(rows: rows, nextIndex: fromIndex + UInt64(rows.count), size: UInt64(identityRows.count), syncedHeight: height - 1)
    }

    func identityZeroed(fromHeight: UInt64, limit: Int?) async throws -> HeightPage<UInt64> {
        let grouped = Dictionary(grouping: zeroed.filter { $0.height >= fromHeight && $0.height < height }, by: \.height)
        let blocks = grouped.keys.sorted().map { (height: $0, items: grouped[$0]!.map(\.index)) }
        return HeightPage(blocks: blocks, nextHeight: height, complete: false, syncedHeight: height - 1)
    }

    func rootsLatest() async throws -> LatestRoots {
        LatestRoots(note: noteTree.size == 0 ? nil : RootRecord(root: noteTree.root(), treeSize: noteTree.size, height: height - 1, time: now),
                    identity: identityTree.size == 0 ? nil : RootRecord(root: identityTree.root(), treeSize: identityTree.size, height: height - 1, time: now),
                    syncedHeight: height - 1,
                    stake: stakeTree.size == 0 ? nil : RootRecord(root: stakeTree.root(), treeSize: stakeTree.size, height: height - 1, time: now))
    }

    // MARK: the chain's own queries (LCD), for the wallet's root checks

    func noteRoot(_ root: Fr) async throws -> NoteRootRecord? { noteRootSizes[root].map { NoteRootRecord(valid: true, treeSize: $0) } }

    private static func at(_ m: [UInt64: TreeState], _ h: UInt64?) -> TreeState {
        let keys = m.keys.filter { h == nil || $0 <= h! }
        return keys.max().flatMap { m[$0] } ?? TreeState(size: 0, root: nil)
    }

    /// Set to make the node answer a pinned query at another height than asked (echo differs).
    var echoOtherHeight = false

    private func read(_ m: [UInt64: TreeState], _ h: UInt64?) -> TreeState {
        if h != nil && echoOtherHeight { let t = Self.at(m, nil); return TreeState(size: t.size, root: t.root, pinned: false) }
        return Self.at(m, h)
    }

    func identityTree(height: UInt64?) async throws -> TreeState { read(identityAt, height) }
    func stakeTree(height: UInt64?) async throws -> TreeState { read(stakeAt, height) }

    /// Drops every recorded note root but the latest (x/shielded prunes roots past its window).
    func pruneNoteRoots() { let keep = noteTree.root(); noteRootSizes = noteRootSizes.filter { $0.key == keep } }

    func nullifierSpent(_ nf: Fr) async -> Bool? { nullifiers[nf] != nil }
    func stakeNullifierSpent(_ nf: Fr) async -> Bool? { stakeNullifiers[nf] != nil }

    /// The chain's tip as the LCD reports it; tests move it ahead of the indexer.
    var tipAhead: UInt64 = 0
    func latestHeight() async -> UInt64? { height - 1 + tipAhead }

    /// Each block's time, as the LCD serves it (`blockTimesPruned`: the node has none).
    var blockTimes: [UInt64: UInt64] = [:]
    var blockTimesPruned = false
    /// Every height whose block time the LCD was asked for, in order.
    var blockTimeAsks: [UInt64] = []

    func blockTime(_ height: UInt64) async -> UInt64? {
        blockTimeAsks.append(height)
        return blockTimesPruned ? nil : blockTimes[height]
    }

    /// What the LCD says block 1's hash prefix is (nil: the indexer's `genesis`); `lcdBlind`: it cannot say.
    var lcdGenesis: String?
    var lcdBlind = false
    func chainIdentity() async -> ChainIdentity? { lcdBlind ? nil : ChainIdentity(chainID: chainID, genesis: lcdGenesis ?? genesis) }

    // MARK: registrations

    var registeredIdc: [UInt64: Fr] = [:]
    var usedBindings: Set<Fr> = []
    var passportOf: [UInt64: String] = [:]
    /// (height, leaf index) of every zeroing.
    var zeroed: [(height: UInt64, index: UInt64)] = []

    private func zeroLeaf(_ index: UInt64) {
        if identityTree.leaf(index) == .zero { return }
        identityTree.update(index, .zero)
        let r = identityRows[Int(index)]
        identityRows[Int(index)] = IdentityRow(index: r.index, height: r.height, leaf: r.leaf, zeroedHeight: height, time: r.time)
        zeroed.append((height: height, index: index))
        registeredIdc.removeValue(forKey: index)
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
