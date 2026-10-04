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
    var noteAt: [UInt64: TreeState] = [:]
    /// The block each note root was first recorded in (x/shielded RootRecord.height).
    var noteRootHeights: [Fr: UInt64] = [:]
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
    /// The stake nullifier indexed tree's values in insertion order (leaf i + 1), ORCHARD_DESIGN 15.
    var stakeNfValues: [Fr] = []
    var stakeRoots: Set<Fr> = []
    /// Proposal snapshots: note root and size, nullifier tree root and size (sentinel included), block.
    struct Snap { let proposalID: UInt64, root: Fr, treeSize: UInt64, nfRoot: Fr, nfSize: UInt64, height: UInt64 }
    var snapshots: [UInt64: Snap] = [:]
    /// (proposal, vote nullifier) of every stake vote.
    var voteNullifiers: Set<[Fr]> = []
    /// Whether the indexer serves the nullifier tree and snapshot streams (false: an older indexer; the wallet uses the LCD).
    var indexerNfTree = true
    var indexerSnapshots = true
    /// Every LCD StakeNullifierTree page asked (start).
    var nfTreeAsks: [UInt64] = []
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
    var removalBallots: [UInt64: UInt64] = [:]
    var removalVotes: [(UInt64, Fr, Int)] = []
    var caretakerVotes: [Fr: [UInt64: UInt64]] = [:]
    /// Caretaker leases (nullifier -> expires_at), and nullifiers that moved theirs away (1126).
    var caretakerExpiry: [Fr: Int64] = [:]
    var caretakerMovedOut: Set<Fr> = []
    /// caretaker_vote_seconds (R) and handle_lease_seconds / handle_renewal_seconds, as FakeReads names them.
    var caretakerLease: Int64 = 30 * 86_400
    var handleLease: Int64 = 365 * 86_400
    var handleRenewal: Int64 = 30 * 86_400
    /// The handle directory: handle -> (holder's nullifier, address, expires_at).
    struct HandleRec { let handle: String; var nullifier: Fr; let address: String; let expiresAt: Int64 }
    var handles: [String: HandleRec] = [:]
    var handleMovedOut: Set<Fr> = []
    /// Every Query/Handles (start) and backend /handles (from_index) page asked: never one handle.
    var handleAsks: [String] = []
    /// Passports ever registered: a re-registration's leaf has a predecessor (x/personhood PassportsSeen).
    var passportsSeen: Set<String> = []
    /// Each leaf's predecessor_at.
    var predecessorOf: [UInt64: Int64] = [:]
    /// Referral notes minted (handle, pc).
    var referralNotes: [(String, Fr)] = []
    var referralPositions: [UInt64] = []
    /// The longest handle lease ever in force (handle_lease_max), and a caretaker
    /// lease held after a cut (lease_hold): what LeaseBounds reports and the bounds use.
    var handleLeaseMax: Int64 = 0
    var caretakerLeaseHold: Int64 = 0
    var effectiveHandleLease: Int64 { max(handleLease, handleLeaseMax) }
    var effectiveCaretakerLease: Int64 { max(caretakerLease, caretakerLeaseHold) }
    /// Every LeaseBounds read (the wallet must use it for every bound).
    var leaseBoundsReads = 0
    /// Query/Root's expires_at per root (nil: not said).
    var rootExpiresAt: ((Fr) -> Int64?)?

    /// Query/LeaseBounds at the chain's last block time.
    func leaseBounds() -> PrivacyReads.LeaseBounds {
        leaseBoundsReads += 1
        return PrivacyReads.LeaseBounds(blockTime: now, activationMarginSeconds: 86_400, handleLeaseSeconds: effectiveHandleLease,
                                        handleClaimBound: now - effectiveHandleLease - 86_400, caretakerLeaseSeconds: effectiveCaretakerLease,
                                        caretakerCastBound: now - effectiveCaretakerLease - 86_400)
    }
    /// Set to make the backend's handle stream lie about an address (the chain check must catch it).
    var forgeHandleAddress: String?
    /// Set to make a set_caretaker event report this expires_at (a hostile node).
    var forgeCaretakerExpiry: Int64?
    /// Undelegations waiting for their payout: id, validator, value, pc, ciphertext.
    struct Payout { let id: UInt64; let validator: String; let value: UInt64; let pc: Fr; let ct: Data }
    var unbondPayouts: [Payout] = []
    var nextPayoutID: UInt64 = 1
    /// Used slots of every stake vote, in order.
    var stakeVoteSlots: [Int] = []
    /// The fake's epoch (9/10 derth minted per uerth at delegation).
    let epoch: UInt64 = 4

    // MARK: x/shieldedstaking books, moves and the slash debt

    /// Each validator's live book (backing, supply): rate 10/9 uerth per derth unless a test sets one.
    var books: [String: (backing: BigUInt, supply: BigUInt)] = [:]
    func book(_ v: String) -> (backing: BigUInt, supply: BigUInt) { books[v] ?? (BigUInt(10_000_000_000_000), BigUInt(9_000_000_000_000)) }
    /// ERTH queued for delegation per validator (what a move leaves first): 0 unless a test sets it.
    var queues: [String: BigUInt] = [:]
    /// Validators x/staking has unbonded: a move from one leaves its queue first.
    var unbonded: Set<String> = []
    var minDelegation: UInt64 = 1
    /// Applied to every book's backing just before a tx's credit check: the rate moving between quote and block.
    var rateDriftPPM: UInt64 = 0
    /// x/staking's longest unbonding time (MaxUnbonding); the label window adds 600 s.
    var maxUnbondingSeconds: UInt64 = 21 * 86_400
    var labelWindow: UInt64 { maxUnbondingSeconds + 600 }
    /// types.ClearBeforeSlackSeconds: how far below ClearBefore(now) a proof's clear_before may be.
    static let clearBeforeSlack: UInt64 = 3_600

    func clearBefore() -> UInt64 { UInt64(now) <= labelWindow ? 0 : UInt64(now) - labelWindow }
    /// Open moves: key -> (src, dst, move_time, credited).
    struct Move { let key: Fr; let src: String; let dst: String; let moveTime: UInt64; let credited: UInt64 }
    var moves: [Fr: Move] = [:]
    /// The slash debt tree's rows in insertion order, each with its latest retained.
    var debtKeys: [Fr] = []
    var debtRetained: [Fr: UInt64] = [:]
    func debtTree() -> DebtTree { try! DebtTree(debtKeys.map { ($0, debtRetained[$0]!) }) }
    func debtRoot() -> Fr { debtTree().root() }
    /// A slash of the move's source reaches it: its exposure now worth `retained` (a row written in the next block's BeginBlock).
    func slashMove(_ key: Fr, retained: UInt64) {
        let m = moves[key]!
        precondition(retained <= m.credited)
        if debtRetained[key] == nil { debtKeys.append(key) }
        debtRetained[key] = min(retained, debtRetained[key] ?? .max)
        block()
    }
    /// Every Query/DebtTree page asked (start), and every /debt_rows page (from_index).
    var debtAsks: [String] = []
    /// Whether the indexer serves /debt_rows (false: an older indexer; the wallet reads the chain's pages).
    var indexerDebtRows = true
    /// Set to make the indexer's debt stream lie about a retained (the root check must catch it).
    var forgeDebtRetained: UInt64?

    /// Query/DebtTree.
    func debtTreeRead(start: UInt64, limit: Int) -> PrivacyReads.DebtTreePage {
        debtAsks.append("chain:\(start)")
        let rows = debtKeys.dropFirst(Int(start)).prefix(min(limit, 1000)).map { (key: $0, retained: debtRetained[$0]!) }
        return PrivacyReads.DebtTreePage(rows: Array(rows), size: debtKeys.isEmpty ? 0 : UInt64(debtKeys.count) + 1, root: debtRoot(),
                                         windowSeconds: labelWindow, clearBefore: clearBefore())
    }

    func validatorBookRead(_ v: String) -> PrivacyReads.Book {
        let b = book(v)
        return PrivacyReads.Book(backing: b.backing, supply: b.supply, pendingDelegation: queues[v] ?? 0, unbonded: unbonded.contains(v))
    }

    func debtRows(fromIndex: UInt64, limit: Int) async throws -> DebtRowsPage {
        guard indexerDebtRows else { throw IndexerBaseMoved("no /debt_rows (test)") }
        debtAsks.append("indexer:\(fromIndex)")
        let n = UInt64(try aligned("debt_rows", fromIndex, limit))
        let all = debtKeys
        var rows: [(index: UInt64, key: Fr, retained: UInt64)] = []
        var i = max(fromIndex, 1)
        while i < fromIndex + n && i <= UInt64(all.count) {
            let k = all[Int(i - 1)]
            rows.append((i, k, forgeDebtRetained ?? debtRetained[k]!))
            i += 1
        }
        let full = fromIndex + n <= UInt64(all.count) + 1
        return DebtRowsPage(rows: rows, nextIndex: full ? fromIndex + n : (rows.last.map { $0.index + 1 } ?? max(fromIndex, 1)), complete: full,
                            size: all.isEmpty ? 0 : UInt64(all.count) + 1, root: debtRoot())
    }

    /// floor(value x S / B) at `v`'s book (with the drift a test set), the chain's derthFor.
    private func buys(_ v: String, _ value: BigUInt) -> BigUInt {
        let (b0, sup) = book(v)
        let b = b0 + b0 * BigUInt(rateDriftPPM) / 1_000_000
        return sup == 0 ? value : value * sup / b
    }

    private func checkCredit(_ v: String, _ value: BigUInt, _ credit: UInt64) throws {
        let buys = buys(v, value)
        try need(buys >= BigUInt(minDelegation), "the value buys \(buys) derth, less than the minimum (code 1103)")
        try need(credit >= minDelegation, "credits less than the minimum (code 1103)")
        try need(BigUInt(credit) <= buys,
                 "the delegation buys \(buys) derth at the live rate, less than the \(credit) it credits (the rate moved since the proof: re-quote with a margin)")
    }

    /// Every validator a private delegation or move named (x/staking's validators, as the LCD lists them).
    var validators: [String] = []
    private func noteValidator(_ v: String) { if !validators.contains(v) { validators.append(v) } }

    func validatorOperators() async -> [String]? { validators }

    /// A stake note of `keys`'s owner made elsewhere (another device's proof,
    /// another owner's): its commitment and wallet stake ciphertext appended
    /// as a proof output would, and the block ended.
    @discardableResult
    func plantStake(_ keys: PrivacyKeys, _ denom: String, _ amount: UInt64, label: StakeLabel? = nil) -> UInt64 {
        let o = NoteCipher.StakeOpening(asset: PrivacyHash.assetID(denom), amount: amount, rho: NotePlaintext.randomField(), rcm: NotePlaintext.randomField(), label: label)
        let cm = o.cm(ownerPK: keys.ownerPK)
        let pos = stakeTree.append(cm)
        stakeRows.append(StakeNoteRow(position: pos, height: height, cm: cm, ciphertext: try! NoteCipher.encryptStake(o, ekPub: keys.ekPub, cm: cm)))
        noteValidator(String(denom.dropFirst(PrivacyWallet.derthPrefix.count)))
        block()
        return pos
    }

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
        if noteRootHeights[noteTree.root()] == nil { noteRootHeights[noteTree.root()] = height }
        noteAt[height] = TreeState(size: noteTree.size, root: noteTree.size == 0 ? nil : noteTree.root())
        // The empty tree's root too: the chain records it at the first block.
        stakeRoots.insert(stakeTree.root())
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

    /// MintOpenNote: a note whose opening the chain chose, no ciphertext; the
    /// row carries owner_pk, rho and rcm (the shielded_mint event's).
    @discardableResult
    func mintOpen(_ denom: String, _ value: UInt64, ownerPK: Fr, rho: Fr, rcm: Fr) -> UInt64 {
        let cm = PrivacyHash.cm(asset: PrivacyHash.assetID(denom), value: value, pc: PrivacyHash.pc(ownerPK: ownerPK, rho: rho, rcm: rcm))
        let pos = noteTree.append(cm)
        notes.append(NoteRow(position: pos, height: height, cm: cm, ciphertext: Data(), amount: "\(value)\(denom)", ownerPK: ownerPK, rho: rho, rcm: rcm))
        return pos
    }

    /// MintNoteSplit: one pc and one ciphertext, several notes, each its own amount and position.
    func mintSplit(_ denom: String, _ values: [UInt64], _ pc: Fr, _ ct: Data) -> [UInt64] { values.map { mint(denom, $0, pc, ct) } }


    /// The stake nullifier tree's root now (indexed.EmptyRoot before the first insert).
    func stakeNfRoot() -> Fr { try! IndexedTree(stakeNfValues).root() }

    /// A proposal enters voting: its snapshot is the trees as the last block left them; the block it is taken in ends.
    @discardableResult
    func openProposal(_ id: UInt64) -> Snap {
        let s = Snap(proposalID: id, root: stakeTree.size == 0 ? .zero : stakeTree.root(), treeSize: stakeTree.size, nfRoot: stakeNfRoot(),
                     nfSize: stakeNfValues.isEmpty ? 0 : UInt64(stakeNfValues.count) + 1, height: height)
        snapshots[id] = s
        block()
        return s
    }

    /// Query/Snapshot.
    func snapshotRead(_ id: UInt64) throws -> PrivacyReads.Snapshot {
        guard let s = snapshots[id] else { throw Refused(why: "no snapshot for proposal \(id)") }
        return PrivacyReads.Snapshot(root: s.root, treeSize: s.treeSize, height: Int64(s.height), nfRoot: s.nfRoot, nfSize: s.nfSize)
    }

    /// Query/StakeNullifierTree.
    func nfTreeRead(start: UInt64, limit: Int) -> PrivacyReads.NfTreePage {
        nfTreeAsks.append(start)
        let vs = Array(stakeNfValues.dropFirst(Int(start)).prefix(min(limit, 1000)))
        return PrivacyReads.NfTreePage(values: vs, size: stakeNfValues.isEmpty ? 0 : UInt64(stakeNfValues.count) + 1)
    }

    /// MsgShield (a gas grant, a shield from a transparent account): its ciphertext is required.
    func shield(_ denom: String, _ value: UInt64, _ pc: Fr, _ ct: Data) { mint(denom, value, pc, ct); block() }

    /// Ends a block with no tx in it.
    func emptyBlock() { block() }

    /// The unbonding period passes: the chain pays every queued undelegation
    /// by itself (MintNoteSplit to its pc and ciphertext; `split` cuts a
    /// payout into chunks as the chain would past 2^63-1). Nothing is sent.
    func payUnbonds(split: (Payout) -> [UInt64] = { [$0.value] }) {
        for p in unbondPayouts {
            precondition(split(p).reduce(0, +) == p.value)
            _ = mintSplit("uerth", split(p), p.pc, p.ct)
        }
        unbondPayouts.removeAll()
        block()
    }

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
    func tipHeight() async throws -> UInt64 { height - 1 + tipAhead + sendTipAhead }

    /// How far the tip a tx's timeout is set from runs ahead (a node inflating it at send only).
    var sendTipAhead: UInt64 = 0

    /// x/shielded Query/Assets as the node serves it: nil says nothing.
    var assetList: [(denom: String, id: Fr)]?

    func assets() async -> [(denom: String, id: Fr)]? { assetList }

    /// What a served entry's owner says: the holder's nullifier unless a test overrides it ("" = a directory without owners).
    var ownerHex: ((HandleRec) -> String)?

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
        if let p = m.stakeProof { for n in p.nullifiers + [p.creditNullifier] { simulatedNullifiers.append(try Fr(bytes: n)) } }
        return gasOf(m)
    }

    /// What the fake's ante charges `m`: its shape alone (a vote: gasVote + proof + (1 + used) x note).
    func gasOf(_ m: any PrivateMsg) -> UInt64 {
        let actions = m.bundles.reduce(0) { $0 + $1.actions.count }
        var vote: UInt64 = 0
        if let v = m as? MsgStakeVote { vote = 2_250_000 + (1 + UInt64(v.voteNullifiers.count)) * 150_000 }
        return 200_000 + 100_000 * UInt64(m.bundles.count) + 350_000 * UInt64(actions) + (m.stakeProof != nil ? 400_000 : 0) + vote
    }

    /// Every committed private tx's gas_limit over the gas it uses (the chain allows at most 5).
    var gasRatios: [Double] = []
    /// The last committed msg.
    var lastMsg: (any PrivateMsg)?

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
            prover.actions.removeAll(); prover.stakes.removeAll(); prover.memberships.removeAll(); prover.votes.removeAll()
            throw UnsignedTx.TxRejected(code: 19, log: "broadcast refused (test)")
        }
        let hash = UnsignedTx.hash(tx)
        if dropNext > 0 {
            // Accepted by CheckTx, then never included (evicted from the mempool).
            dropNext -= 1
            _ = try check(tx, simulate: true)
            accepted(hash)
            prover.actions.removeAll(); prover.stakes.removeAll(); prover.memberships.removeAll(); prover.votes.removeAll()
            throw URLError(.timedOut)
        }
        if failInBlockNext > 0 {
            failInBlockNext -= 1
            _ = try check(tx, simulate: true)
            accepted(hash)
            prover.actions.removeAll(); prover.stakes.removeAll(); prover.memberships.removeAll(); prover.votes.removeAll()
            block()
            txs[hash] = TxResult(hash: hash, height: height - 1, time: now, events: [], code: 5, log: "failed in block (test)")
            throw Refused(why: "tx failed (code 5)")
        }
        _ = try check(tx, simulate: true)
        accepted(hash)
        let (m, events) = try check(tx, simulate: false)
        lastMsg = m
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
            // Never to a module account.
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
        case let m as MsgShieldedUndelegate:
            try only(nil)
            // The payout's pc and ciphertext: checked like any mint.
            try need(m.pc.count == 32 && !(try f(m.pc)).isZero, "pc")
            try need(m.ciphertext.count == NoteCipher.blindCiphertextBytes, "the payout needs its 177-byte blind ciphertext")
        case let m as MsgRegisterPrivate:
            try only(nil)
            try need(m.ciphertextAnml.count == NoteCipher.blindCiphertextBytes && m.ciphertextErth.count == NoteCipher.blindCiphertextBytes,
                     "registration ciphertexts")
            // The referral is the handle alone; the chain makes its note.
            if !m.affiliateHandle.isEmpty {
                try need(Handles.valid(m.affiliateHandle), "affiliate_handle")
            }
        case let m as MsgBindHandle:
            try only(nil)
            try need(m.handle.isEmpty == m.address.isEmpty, "a bind names a handle and an address; a release neither")
            if !m.address.isEmpty { try need((try ShieldedAddress.decode(m.address)).encode() == m.address, "address not canonical") }
        case let m as MsgMoveHandle:
            try only(nil); try need(m.newOwner != m.membership.nullifier, "new_owner is the prover")
        case let m as MsgMoveCaretaker:
            try only(nil); try need(m.newOwner != m.membership.nullifier, "new_owner is the prover")
        case let m as MsgClaimAnmlPrivate:
            try only(nil)
            try need(m.ciphertext.count == NoteCipher.blindCiphertextBytes, "claim ciphertext")
        default: try only(nil)
        }
        try need(m.totalFee > 0, "no fee")
    }

    private func lanes(_ m: any PrivateMsg) -> ChainLayout.Lanes {
        ChainLayout.lanes(m) { id in self.positions[id].map { ($0.validator, $0.derth) } ?? ("", 0) }
    }

    private func spent(_ p: StakeProof) throws -> [Fr] { try (p.nullifiers + [p.creditNullifier]).map(f).filter { !$0.isZero } }

    /// The chain's shape rule: a note-moving msg spends in its first slot and creates; a crediting one uses its credit lane; the rest are zero.
    private func stakeShape(_ m: any PrivateMsg, _ p: StakeProof) throws {
        let notes = !(m is MsgUpdatePosition) && !(m is MsgPositionVote)
        let credit = m is MsgRedelegate
        if notes {
            try need(!(try f(p.nullifiers[0])).isZero, "the stake proof spends a note (or pads with its own nullifier) in its first slot")
            try need(!(try f(p.commitment)).isZero, "the stake proof creates a note (the merged note, the change or a zero note)")
        } else {
            try need((try f(p.nullifiers[0])).isZero && (try f(p.nullifiers[1])).isZero && (try f(p.commitment)).isZero,
                     "the stake proof spends and creates nothing for this msg")
        }
        if credit {
            try need(!(try f(p.creditNullifier)).isZero && !(try f(p.creditCommitment)).isZero, "the credit lane spends (or pads) and creates")
        } else {
            try need((try f(p.creditNullifier)).isZero && (try f(p.creditCommitment)).isZero, "this msg credits no second asset")
        }
    }

    /// A ballot's max_predecessor (x/assembly: opened - 86400; the fake opens ballots as it is asked).
    func ballotMaxPredecessor() -> UInt64 { UInt64(now - 86_400) }

    private func handleOf(_ n: Fr) -> HandleRec? { handles.values.first { $0.nullifier == n } }

    /// A lapsed split the sweep has not reached is not held; only a live handle renews unbounded.
    private func caretakerHoldsLive(_ n: Fr) -> Bool { caretakerVotes[n] != nil && (caretakerExpiry[n] ?? 0) > now }
    private func holdsLiveHandle(_ n: Fr) -> Bool { handleOf(n).map { now < $0.expiresAt } ?? false }

    /// The chain's refusal of a too-recent predecessor (strictly before the bound).
    private func predecessorBound(_ maxPredecessor: UInt64, _ bound: Int64) throws {
        try need(bound > 0 && maxPredecessor < UInt64(bound), "max_predecessor \(maxPredecessor) is not before \(bound) (now - lease length - activation margin)")
    }

    /// A membership's statement per msg: scope, max_activation,
    /// max_predecessor (x/personhood and x/assembly at 4a663d5), and the
    /// checks each makes of the prover's standing first.
    private func membershipStatement(_ m: any PrivateMsg) throws -> (Fr, UInt64, UInt64)? {
        let day = UInt64(now / 86_400)
        let none = PrivacyHash.noBound
        switch m {
        case let m as MsgClaimAnmlPrivate: return (PrivacyHash.claimScope(day: m.day), (m.day - 1) * 86_400, none)
        case let m as MsgVoteProposalPrivate: return (PrivacyHash.proposalScope(proposalID: m.proposalID, round: 0), none, ballotMaxPredecessor())
        case let m as MsgSetCaretaker:
            let n = try f(m.membership.nullifier)
            if !caretakerHoldsLive(n), !m.percentages.isEmpty {
                try need(!caretakerMovedOut.contains(n), "this identity moved its caretaker split away (code 1126)")
                try predecessorBound(m.maxPredecessor, now - effectiveCaretakerLease - 86_400)
            }
            return (PrivacyHash.caretakerScope(), none, m.maxPredecessor)
        case let m as MsgMoveCaretaker:
            let n = try f(m.membership.nullifier)
            guard let exp = caretakerExpiry[n] else { throw Refused(why: "the prover holds no caretaker split") }
            try need(exp > now, "the prover's caretaker split has lapsed")
            let o = try f(m.newOwner)
            try need(caretakerVotes[o] == nil, "new_owner already holds a caretaker split")
            try need(!caretakerMovedOut.contains(o), "this identity moved its caretaker split away (code 1126)")
            return (PrivacyHash.caretakerScope(), none, none)
        case let m as MsgBindHandle:
            let n = try f(m.membership.nullifier)
            let holds = handleOf(n) != nil
            if !m.handle.isEmpty {
                try need(Handles.valid(m.handle), "not a handle")
                if let h = handles[m.handle] { try need(h.nullifier == n || now >= h.expiresAt + handleRenewal, "handle is held by another human (code 1122)") }
                if !holdsLiveHandle(n) {
                    try need(!handleMovedOut.contains(n), "this identity moved its handle away (code 1125)")
                    try predecessorBound(m.maxPredecessor, now - effectiveHandleLease - 86_400)
                }
            } else {
                try need(holds, "the prover holds no handle to release")
            }
            return (PrivacyHash.handleScope(), none, m.maxPredecessor)
        case let m as MsgMoveHandle:
            let n = try f(m.membership.nullifier)
            try need(handles[m.handle]?.nullifier == n, "the prover does not hold \(m.handle)")
            try need(now < handles[m.handle]!.expiresAt, "\"\(m.handle)\" is not live (renewal): renew it before moving it")
            let o = try f(m.newOwner)
            try need(handleOf(o) == nil, "new_owner already holds a handle")
            try need(!handleMovedOut.contains(o), "this identity moved its handle away (code 1125)")
            return (PrivacyHash.handleScope(), none, none)
        case let m as MsgProposeRemoval: return (PrivacyHash.proposeRemovalScope(optionID: m.optionID, day: day), none, day * 86_400 - 86_400)
        case let m as MsgVoteRemoval: return (PrivacyHash.removalScope(ballotID: removalBallots[m.optionID] ?? 0), none, ballotMaxPredecessor())
        default: return nil
        }
    }

    private func shares(_ erth: UInt64, _ anml: UInt64) -> BigInt {
        min(BigInt(erth) * lpSupply / poolErth, BigInt(anml) * lpSupply / poolAnml)
    }

    /// Denoms governance send-disabled (bank SendEnabled false): refused at every pool edge.
    var sendDisabled: Set<String> = []

    /// The action's own checks, before anything is written (atomic with the spend in the ante).
    private func precheck(_ m: any PrivateMsg, _ rem: [String: UInt64]) throws {
        // The ante's release-map check and the module mint: a
        // dex note swap and a private delegation release out of the pool.
        var edges = Set<String>()
        switch m {
        case let m as MsgNoteSwap: edges = Set(rem.keys).union([m.denomOut])
        case is MsgShieldedDelegate, is MsgSend: edges = Set(rem.keys)
        default: break
        }
        if let d = edges.sorted().first(where: { sendDisabled.contains($0) }) {
            throw Refused(why: "\(d) transfers are currently disabled: send transactions are disabled")
        }
        switch m {
        case let m as MsgNoteSwap:
            let (denomIn, amountIn) = rem.first!
            let out = try swapOut(denomIn, amountIn, m.denomOut)
            try need(out >= m.minAmountOut, "slippage: got \(out), want >= \(m.minAmountOut)")
        case let m as MsgAddLiquidityShielded:
            if !m.minShares.isEmpty { try need(shares(rem["uerth"]!, rem["uanml"]!) >= BigInt(m.minShares)!, "below min_shares") }
        case let m as MsgShieldedDelegate:
            try need(m.amount >= minDelegation, "a delegation is at least \(minDelegation)uerth")
            try checkCredit(m.validator, BigUInt(m.amount), m.derth)
        case let m as MsgRedelegate:
            try need(m.srcValidator != m.dstValidator, "source and destination are the same validator (code 1120)")
            try need(m.moveTime >= 1 && m.moveTime <= UInt64(now) && UInt64(now) - m.moveTime <= 600,
                     "move_time \(m.moveTime) is not within 600s before the block time \(now) (name a recent block's time)")
            let (bA, sA) = book(m.srcValidator)
            let u = BigUInt(m.amount) * bA / sA
            try need(u >= BigUInt(minDelegation), "the redelegation is worth less than the minimum (code 1103)")
            // What arrives: pro rata out of the queue and the
            // bonded stake, up to bondedDust + a truncated uerth short of u; all
            // of it only out of an unbonded source's queue.
            let q = queues[m.srcValidator] ?? 0
            let arrived = unbonded.contains(m.srcValidator) && u <= q ? u : (u > 1001 ? u - 1001 : 0)
            try checkCredit(m.dstValidator, arrived, m.dstDerth)
        case let m as MsgUpdatePosition:
            try need(positions[m.positionID]?.ownerTag == (try f(m.stake.ownerTag)), "not the position's owner")
        case let m as MsgUnlockPosition:
            try need(positions[m.positionID]?.ownerTag == (try f(m.stake.ownerTag)), "not the position's owner")
        case let m as MsgPositionVote:
            try need(positions[m.positionID]?.ownerTag == (try f(m.stake.ownerTag)), "not the position's owner")
            try need(m.options.allSatisfy { (try? PrivateMsgs.legacyDec($0.weight)) == $0.weight }, "a vote weight is not canonical")
        case let m as MsgStakeVote:
            // Option weights only in their canonical LegacyDec form.
            try need(m.options.allSatisfy { (try? PrivateMsgs.legacyDec($0.weight)) == $0.weight }, "a vote weight is not canonical")
            try need(snapshots[m.proposalID] != nil, "no open snapshot for proposal \(m.proposalID)")
            try need(m.weight > 0, "weight must be positive")
            // At most three significant digits.
            try need((try? PrivacyWallet.voteWeight(m.weight)) == m.weight, "weight has more than 3 significant digits")
            // Exactly two, non-zero (an unused slot carries a padding nullifier), distinct.
            try need(m.voteNullifiers.count == MsgStakeVote.maxVoteNotes, "a stake vote carries exactly 2 vote nullifiers")
            try need(m.voteNullifiers.allSatisfy { $0.count == 32 }, "vote_nullifiers")
            let vs = try m.voteNullifiers.map(f)
            try need(!vs.contains(where: \.isZero), "a zero vote nullifier: an unused slot carries a padding nullifier")
            try need(Set(vs).count == vs.count, "repeated vote nullifier")
            if let v = vs.first(where: { voteNullifiers.contains([PrivacyHash.u64(m.proposalID), $0]) }) {
                throw Refused(why: "this stake note already voted on this proposal (code 1119): proposal \(m.proposalID), vote nullifier \(v.hex.uppercased())")
            }
        case let m as MsgVoteRemoval: try need(removalBallots[m.optionID] != nil, "no open ballot")
        case let m as MsgProposeRemoval: try need(removalBallots[m.optionID] == nil, "ballot already open")
        case let m as MsgRegisterPrivate:
            let idc = try f(m.idc)
            try need(!identityRows.contains { $0.leaf != .zero && registeredIdc[$0.index] == idc }, "a switch to the live idc is refused")
            if !m.affiliateHandle.isEmpty {
                let h = handles[m.affiliateHandle]
                try need(h != nil && now < h!.expiresAt, "affiliate_handle is not a live handle (code 1121)")
            }
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
        // Exactly the canonical encoding of what it decodes to.
        try need(UnsignedTx.build(m, tx: tx.txFields) == txBytes, "tx bytes are not canonical")
        // Every action's output ciphertext exactly 217 bytes, dummies included.
        for b in m.bundles { for a in b.actions { try need(a.ciphertext.count == NoteCipher.ciphertextBytes, "action ciphertext \(a.ciphertext.count) bytes") } }
        // Stake proofs: every field 32 bytes, a 201-byte ciphertext exactly for a non-zero commitment.
        if let p = m.stakeProof {
            try need(p.nullifiers.count == 2, "a stake proof carries exactly two nullifiers")
            for b in p.nullifiers + [p.anchor, p.ownerTag, p.commitment, p.creditNullifier, p.creditCommitment, p.debtRoot] {
                try need(b.count == 32, "a stake field of \(b.count) bytes")
            }
            for (cm, ct) in [(p.commitment, p.ciphertext), (p.creditCommitment, p.creditCiphertext)] {
                let zero = cm.allSatisfy { $0 == 0 }
                try need(zero ? ct.isEmpty : ct.count == NoteCipher.stakeCiphertextBytes, "stake ciphertext: \(ct.count) bytes")
            }
            try need((p.clearBefore == 0) == p.debtRoot.allSatisfy { $0 == 0 }, "debt_root is zero when clear_before is 0 (the proof clears no label)")
        }
        // timeout_height: the block being built must not be past it (0: none).
        try need(tx.txFields.timeoutHeight == 0 || height <= tx.txFields.timeoutHeight, "tx timed out")
        if requireTimeout && !simulate { try need(tx.txFields.timeoutHeight > 0, "a private tx without timeout_height") }
        lastTimeoutHeight = tx.txFields.timeoutHeight
        let total = m.totalFee
        try need(tx.feeCoins.count == 1 && tx.feeCoins[0].denom == "uerth" && tx.feeCoins[0].amount == String(total), "declared fee != msg fee")
        try need(total >= minFeeValue, "below min fee")
        if !simulate { try need(Decimal(total) >= price * Decimal(tx.gasLimit), "below min gas price") }
        // A private tx's gas_limit is at most 5x the gas it uses.
        if !simulate {
            let used = gasOf(m)
            try need(tx.gasLimit <= 5 * used, "gas limit \(tx.gasLimit) exceeds what this private tx uses (\(used))")
            gasRatios.append(Double(tx.gasLimit) / Double(used))
        }
        let bundles = m.bundles
        try need((1 ... 2).contains(bundles.count), "bundle count")
        let rem = try remainders(m)
        try checkRelease(m, rem)
        // The tx fields every private sighash binds (the ante records them).
        let sighash = try m.sighash(chainID: chainID, tx: tx.txFields)
        var seen = Set<Fr>()
        for (i, b) in bundles.enumerated() { try checkBundle(i, b, sighash, simulate: simulate, seen: &seen) }

        let stake = m.stakeProof
        if let stake {
            try need(stake.proof.count == PrivateTxEngine.proofBytes, "a stake proof is exactly \(PrivateTxEngine.proofBytes) bytes")
            try stakeShape(m, stake)
            let nfs = try spent(stake)
            try need(!nfs.contains { stakeNullifiers[$0] != nil }, "stake nullifier spent")
            try need(Set(nfs).count == nfs.count, "duplicate stake nullifier")
            if !nfs.isEmpty { try need(stakeRoots.contains(try f(stake.anchor)), "unknown stake anchor") }
            // Every stake proof names the current clear_before (within an hour below it) and debt root (the chain's checkStakeClear).
            let cb = clearBefore()
            if cb == 0 {
                try need(stake.clearBefore == 0, "clear_before must be 0 while the block time is within the label window")
            } else {
                let lo: UInt64 = cb > Self.clearBeforeSlack ? cb - Self.clearBeforeSlack : 1
                try need(stake.clearBefore >= lo && stake.clearBefore <= cb, "clear_before \(stake.clearBefore) is not within [\(lo), \(cb)] (code 1113)")
                try need((try f(stake.debtRoot)) == debtRoot(), "debt root is not the current slash debt root (a slash reached a redelegation since: re-prove)")
            }
            if !simulate {
                guard !prover.stakes.isEmpty else { throw Refused(why: "no stake proof") }
                let w = prover.stakes.removeFirst()
                try need(w.publicInputs() == (try ChainLayout.stakePublicInputs(stake, lanes(m), sighash)), "stake proof is for other public inputs")
            }
        }
        if let m = m as? MsgStakeVote {
            try need(m.proof.count == PrivateTxEngine.proofBytes, "a vote proof is exactly \(PrivateTxEngine.proofBytes) bytes")
            guard let snap = snapshots[m.proposalID] else { throw Refused(why: "no open snapshot for proposal \(m.proposalID)") }
            try need(m.debtRoot.count == 32 && (try f(m.debtRoot)) == debtRoot(),
                     "debt root is not the current slash debt root (a slash reached a redelegation since: re-prove)")
            if !simulate {
                guard !prover.votes.isEmpty else { throw Refused(why: "no vote proof") }
                let w = prover.votes.removeFirst()
                try need(w.publicInputs() == (try ChainLayout.votePublicInputs(m, noteRoot: snap.root, nfRoot: snap.nfRoot, sighash: sighash)),
                         "vote proof is for other public inputs")
            }
        }
        if let mm = m as? any MembershipMsg {
            let mem = mm.membership
            try need(identityRoots.contains(try f(mem.root)), "unknown identity anchor")
            try need(mem.proof.count == PrivateTxEngine.proofBytes, "a membership proof is exactly \(PrivateTxEngine.proofBytes) bytes")
            _ = try membershipStatement(m)
            if !simulate {
                guard !prover.memberships.isEmpty else { throw Refused(why: "no membership proof") }
                let w = prover.memberships.removeFirst()
                let (scope, maxAct, maxPred) = try membershipStatement(m)!
                let expect = [try f(mem.root), scope, try f(mem.nullifier), sighash, .zero, .zero, PrivacyHash.u64(maxAct), PrivacyHash.u64(maxPred)]
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
            for nf in try spent(stake) { stakeNullifiers[nf] = height; stakeNfValues.append(nf) }
            for (cm, ct) in [(stake.commitment, stake.ciphertext), (stake.creditCommitment, stake.creditCiphertext)] {
                let c = try f(cm)
                if c.isZero { continue }
                let pos = stakeTree.append(c)
                stakeRows.append(StakeNoteRow(position: pos, height: height, cm: c, ciphertext: ct))
            }
        }
        var events: [(type: String, attributes: [String: String])] = []
        switch m {
        case let m as MsgSend:
            for (d, v) in rem { unshielded[m.receiver, default: [:]][d, default: 0] += v }
        case let m as MsgRegisterPrivate:
            let binding = try PrivateMsgs.decimalField(m.publicSignals[1])
            try need(binding == (try m.binding(chainID: chainID)), "binding")
            // A landed binding is never used again.
            try need(usedBindings.insert(binding).inserted, "binding already used (ErrBindingUsed)")
            let dsc = try PrivateMsgs.decimalField(m.publicSignals[3])
            let idc = try f(m.idc)
            // A switch: the holder's old leaf is zeroed, the new one appended.
            let live = registeredIdc.filter { $0.value == idc || passportOf[$0.key] == m.publicSignals[2] }
            let switched = !live.isEmpty
            for i in live.keys { zeroLeaf(i) }
            // predecessor_at: the switch or re-entry that made this leaf, 0 for a passport never seen.
            let pred: Int64 = switched || passportsSeen.contains(m.publicSignals[2]) ? now : 0
            passportsSeen.insert(m.publicSignals[2])
            let idx = identityTree.append(PrivacyHash.identityLeaf(idc: idc, dscKey: dsc, country: PrivacyHash.countryField(registrationCountry),
                                                                   activatedAt: UInt64(now), predecessorAt: UInt64(pred)))
            predecessorOf[idx] = pred
            identityRows.append(IdentityRow(index: idx, height: height, leaf: identityTree.leaf(idx), zeroedHeight: nil, time: UInt64(now)))
            registeredIdc[idx] = idc; passportOf[idx] = m.publicSignals[2]
            mint("uanml", 1_000_000, try f(m.pcAnml), m.ciphertextAnml)
            if !switched {
                mint("uerth", 5_000_000, try f(m.pcErth), m.ciphertextErth)
                // The referrer's half: the chain's own note to the handle's address, its
                // opening derived from the passport nullifier and the leaf (ReferralOpening).
                if !m.affiliateHandle.isEmpty {
                    let to = try ShieldedAddress.decode(handles[m.affiliateHandle]!.address)
                    let o = PrivacyHash.referralOpening(nullifier: try PrivateMsgs.decimalField(m.publicSignals[2]), leafIndex: idx)
                    let pos = mintOpen("uerth", 5_000_000, ownerPK: to.ownerPK, rho: o.rho, rcm: o.rcm)
                    referralNotes.append((m.affiliateHandle, PrivacyHash.pc(ownerPK: to.ownerPK, rho: o.rho, rcm: o.rcm)))
                    referralPositions.append(pos)
                }
            }
            events.append((type: "register", attributes: ["leaf_index": String(idx), "switched": String(switched)]))
        case let m as MsgClaimAnmlPrivate:
            mint("uanml", 1_000_000, try f(m.pc), m.ciphertext)
        case let m as MsgVoteProposalPrivate:
            votes.append((m.proposalID, m.option.rawValue))
        case let m as MsgShieldedDelegate:
            noteValidator(m.validator)
            events.append((type: "shieldedstaking_delegate", attributes: ["validator": m.validator, "amount": String(m.amount), "derth": String(m.derth)]))
        case is MsgRestake: break
        case let m as MsgRedelegate:
            let key = try f(m.stake.creditNullifier)
            noteValidator(m.srcValidator); noteValidator(m.dstValidator)
            moves[key] = Move(key: key, src: m.srcValidator, dst: m.dstValidator, moveTime: m.moveTime, credited: m.dstDerth)
            events.append((type: "shieldedstaking_redelegate", attributes: ["src_validator": m.srcValidator, "dst_validator": m.dstValidator,
                                                                             "derth": String(m.amount), "credited": String(m.dstDerth),
                                                                             "move_key": key.hex, "move_time": String(m.moveTime)]))
        case let m as MsgShieldedUndelegate:
            // Booked at the live rate and queued: the chain pays it at maturity by itself.
            let id = nextPayoutID
            nextPayoutID += 1
            let value = m.amount * 10 / 9
            unbondPayouts.append(Payout(id: id, validator: m.validator, value: value, pc: try f(m.pc), ct: m.ciphertext))
            events.append((type: "shieldedstaking_undelegate", attributes: ["validator": m.validator, "derth": String(m.amount),
                                                                            "value": String(value), "epoch": String(epoch), "payout_id": String(id)]))
        case let m as MsgStakeVote:
            let vs = try m.voteNullifiers.map(f)
            for v in vs { voteNullifiers.insert([PrivacyHash.u64(m.proposalID), v]) }
            stakeVotes.append((m.proposalID, m.validator, m.weight))
            // How many notes it voted is not on chain: the proof's witness says.
            stakeVoteSlots.append(prover.allVotes.last!.slots.count)
            events.append((type: "shieldedstaking_stake_vote", attributes: ["vote_nullifiers": try m.voteNullifiers.map { try f($0).hex }.joined(separator: ",")]))
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
            _ = positions.removeValue(forKey: m.positionID)!
            positionOrder.removeAll { $0 == m.positionID }
        case let m as MsgPositionVote:
            positionVotes.append((m.positionID, m.proposalID))
        case let m as MsgSetCaretaker:
            let n = try f(m.membership.nullifier)
            if m.percentages.isEmpty { caretakerVotes[n] = nil; caretakerExpiry[n] = nil } else {
                caretakerVotes[n] = Dictionary(uniqueKeysWithValues: m.percentages.map { ($0.optionID, $0.percent) })
                caretakerExpiry[n] = now + caretakerLease
            }
            events.append((type: "set_caretaker", attributes: ["expires_at": String(forgeCaretakerExpiry ?? caretakerExpiry[n] ?? 0)]))
        case let m as MsgMoveCaretaker:
            let n = try f(m.membership.nullifier), o = try f(m.newOwner)
            caretakerVotes[o] = caretakerVotes.removeValue(forKey: n); caretakerExpiry[o] = caretakerExpiry.removeValue(forKey: n)
            caretakerMovedOut.insert(n)
            events.append((type: "move_caretaker", attributes: ["expires_at": String(caretakerExpiry[o] ?? 0)]))
        case let m as MsgBindHandle:
            let n = try f(m.membership.nullifier)
            let cur = handleOf(n)
            if m.handle.isEmpty { handles[cur!.handle] = nil } else {
                // A change frees the old handle at once; a renewal (or a claim) leases now + handle_lease_seconds.
                if let cur, cur.handle != m.handle { handles[cur.handle] = nil }
                handles[m.handle] = HandleRec(handle: m.handle, nullifier: n, address: m.address, expiresAt: now + handleLease)
                events.append((type: "handle_bound", attributes: ["handle": m.handle, "expires_at": String(now + handleLease)]))
            }
        case let m as MsgMoveHandle:
            handles[m.handle]!.nullifier = try f(m.newOwner)
            handleMovedOut.insert(try f(m.membership.nullifier))
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

    /// Every position/index cursor asked off the backend's paging rule (each a 400).
    var misaligned: [String] = []
    /// Lets tests page with other sizes (the alignment rule still holds).
    var anyPageSize = false

    private func aligned(_ name: String, _ from: UInt64, _ limit: Int?) throws -> Int {
        let n = limit ?? 1000
        if ![100, 1000].contains(n) && !anyPageSize { misaligned.append("\(name) limit \(n)"); throw Refused(why: "indexer /\(name): 400 limit") }
        if from % UInt64(n) != 0 { misaligned.append("\(name) \(from)/\(n)"); throw Refused(why: "indexer /\(name): 400 from not aligned") }
        return n
    }

    func notes(fromPos: UInt64, limit: Int?) async throws -> NotesPage {
        let n = try aligned("notes", fromPos, limit)
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
        let n = try aligned("identity", fromIndex, limit)
        let rows = Array(identityRows.dropFirst(Int(fromIndex)).prefix(n)).map { identityRowTimes ? $0 : $0.with(time: nil) }
        return IdentityPage(rows: rows, nextIndex: fromIndex + UInt64(rows.count), size: UInt64(identityRows.count), syncedHeight: height - 1)
    }

    func identityZeroed(fromHeight: UInt64, limit: Int?) async throws -> HeightPage<UInt64> {
        let grouped = Dictionary(grouping: zeroed.filter { $0.height >= fromHeight && $0.height < height }, by: \.height)
        let blocks = grouped.keys.sorted().map { (height: $0, items: grouped[$0]!.map(\.index)) }
        return HeightPage(blocks: blocks, nextHeight: height, complete: false, syncedHeight: height - 1)
    }

    func rootsLatest() async throws -> LatestRoots {
        LatestRoots(note: noteTree.size == 0 ? nil : RootRecord(root: noteTree.root(), treeSize: noteTree.size,
                                                               height: noteRootHeights[noteTree.root()] ?? height - 1, time: now),
                    identity: identityTree.size == 0 ? nil : RootRecord(root: identityTree.root(), treeSize: identityTree.size, height: height - 1, time: now),
                    syncedHeight: height - 1,
                    stake: stakeTree.size == 0 ? nil : RootRecord(root: stakeTree.root(), treeSize: stakeTree.size, height: height - 1, time: now))
    }

    // MARK: the chain's own queries (LCD), for the wallet's root checks

    func noteRoot(_ root: Fr) async throws -> NoteRootRecord? {
        noteRootSizes[root].map { NoteRootRecord(valid: true, treeSize: $0, height: noteRootHeights[root], expiresAt: rootExpiresAt?(root)) }
    }

    func noteTree(height: UInt64?) async -> TreeState? { read(noteAt, height) }

    /// Set to make the LCD say nothing of txs by hash.
    var txLookupBlind = false

    func txStatus(_ hash: String) async -> TxStatus? {
        if txLookupBlind { return nil }
        guard let r = txs[hash] else { return .missing }
        return r.code == 0 ? .committed : .failed
    }

    func latestBlock() async -> ChainTip? { ChainTip(height: height - 1 + tipAhead, time: UInt64(now)) }

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
        let n = try aligned("stake/notes", fromPos, limit)
        let rows = Array(stakeRows.dropFirst(Int(fromPos)).prefix(n))
        return StakeNotesPage(rows: rows, nextPos: fromPos + UInt64(rows.count), complete: rows.count == n, syncedHeight: height - 1)
    }

    func stakeNullifiers(fromHeight: UInt64, limit: Int?) async throws -> HeightPage<Fr> { heights(stakeNullifiers, fromHeight) }

    func stakeNullifierLeaves(fromIndex: UInt64, limit: Int?) async throws -> StakeNfLeavesPage {
        guard indexerNfTree else { throw IndexerBaseMoved("no /stake/nullifier-tree (test)") }
        let n = UInt64(try aligned("stake/nullifier-tree", fromIndex, limit))
        // Page k is leaf indexes [k*n, (k+1)*n); leaf 0 (the sentinel) is never a row.
        let from = max(fromIndex, 1)
        let to = min(fromIndex + n, UInt64(stakeNfValues.count) + 1)
        let rows = from < to ? (from ..< to).map { (index: $0, value: stakeNfValues[Int($0 - 1)]) } : []
        let full = fromIndex + n <= UInt64(stakeNfValues.count) + 1
        return StakeNfLeavesPage(leaves: rows, nextIndex: full ? fromIndex + n : (rows.last.map { $0.index + 1 } ?? from), complete: full,
                                 size: stakeNfValues.isEmpty ? 0 : UInt64(stakeNfValues.count) + 1, syncedHeight: height - 1)
    }

    func stakeSnapshots(fromHeight: UInt64, limit: Int?) async throws -> StakeSnapshotsPage {
        guard indexerSnapshots else { throw IndexerBaseMoved("no /stake/snapshots (test)") }
        let rows = snapshots.values.filter { $0.height >= fromHeight && $0.height < height }.sorted { $0.height < $1.height }
            .map { StakeSnapshotRow(height: $0.height, proposalID: $0.proposalID, root: $0.root, treeSize: $0.treeSize, nfRoot: $0.nfRoot, nfSize: $0.nfSize) }
        return StakeSnapshotsPage(rows: rows, nextHeight: max(fromHeight, height), complete: false, syncedHeight: height - 1)
    }

    /// A directory entry as Query/Handles serves it now (released ones are gone: swept).
    private func entry(_ h: HandleRec) -> HandleEntry? {
        let until = h.expiresAt + handleRenewal
        let status: String
        if now < h.expiresAt { status = HandleEntry.live } else if now < until { status = HandleEntry.renewal } else { return nil }
        return HandleEntry(handle: h.handle, address: h.address, status: status, expiresAt: h.expiresAt, renewalUntil: until,
                           owner: ownerHex?(h) ?? h.nullifier.hex)
    }

    private func directory() -> [HandleEntry] { handles.keys.sorted().compactMap { entry(handles[$0]!) } }

    /// Query/Handles: handles after `start`, at most `limit`.
    func handlesPage(start: String, limit: Int) -> HandleDirectory.Page {
        handleAsks.append("chain:" + start)
        let after = directory().filter { $0.handle > start }
        let page = Array(after.prefix(min(limit, 1000)))
        return HandleDirectory.Page(handles: page, next: after.count > page.count ? page.last!.handle : "")
    }

    func handles(fromIndex: Int64, limit: Int) async throws -> HandleDirectory.StreamPage {
        let n = try aligned("handles", UInt64(fromIndex), limit)
        handleAsks.append("indexer:\(fromIndex)")
        let all = directory().map { e in forgeHandleAddress.map { HandleEntry(handle: e.handle, address: $0, status: e.status, expiresAt: e.expiresAt, renewalUntil: e.renewalUntil, owner: e.owner) } ?? e }
        let rows = Array(all.dropFirst(Int(fromIndex)).prefix(n))
        return HandleDirectory.StreamPage(handles: rows, height: Int64(height - 1), size: Int64(all.count), fromIndex: fromIndex,
                                          lastPage: Int(fromIndex) + n >= all.count)
    }

    /// The app's directory over this chain: the indexer's stream first, the chain's pages to check against.
    func handleDirectory() -> HandleDirectory {
        HandleDirectory(fetchChainPage: { [self] s, l in handlesPage(start: s, limit: l) },
                        fetchStream: { [self] f, l in try await handles(fromIndex: f, limit: l) }, now: { [self] in now })
    }

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
    var votes: [VoteWitness] = []
    var allVotes: [VoteWitness] = []
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

    func proveVote(_ w: VoteWitness) async throws -> Data {
        try w.check()
        votes.append(w); allVotes.append(w)
        return Data(repeating: 4, count: PrivateTxEngine.proofBytes)
    }
}

/// PrivacyChainReads over a FakeChain; snapshots are the chain's (FakeChain.openProposal).
struct FakeReads: PrivacyChainReads, @unchecked Sendable {
    let chain: FakeChain
    /// What Query/Epoch and the timing say an undelegation's payout time is (nil: unknown).
    var due: (UInt64) -> Int64? = { _ in nil }

    func unbondDueBy(epoch: UInt64) async -> Int64? { due(epoch) }

    func personhoodParams() async throws -> PrivacyReads.PersonhoodParams {
        .init(caretakerVoteSeconds: chain.caretakerLease, identityRootWindowSeconds: 3_600, handleLeaseSeconds: chain.handleLease,
              handleRenewalSeconds: chain.handleRenewal)
    }

    func leaseBounds() async throws -> PrivacyReads.LeaseBounds { chain.leaseBounds() }

    func ballotInputs(proposalID: UInt64, optionID: UInt64) async throws -> PrivacyReads.BallotInputs {
        if proposalID != 0 {
            return .init(scope: PrivacyHash.proposalScope(proposalID: proposalID, round: 0), excludedDsc: .zero, excludedCountry: .zero,
                         maxActivation: PrivacyHash.noBound, round: 0, ballotID: 0, maxPredecessor: chain.ballotMaxPredecessor())
        }
        let id = chain.removalBallots[optionID]!
        return .init(scope: PrivacyHash.removalScope(ballotID: id), excludedDsc: .zero, excludedCountry: .zero,
                     maxActivation: PrivacyHash.noBound, round: 0, ballotID: id, maxPredecessor: chain.ballotMaxPredecessor())
    }

    func epochNumber() async throws -> UInt64 { chain.epoch }

    func snapshot(proposalID: UInt64) async throws -> PrivacyReads.Snapshot { try chain.snapshotRead(proposalID) }

    func stakeNullifierTree(start: UInt64, limit: Int) async throws -> PrivacyReads.NfTreePage { chain.nfTreeRead(start: start, limit: limit) }

    func positions() async throws -> [PrivacyReads.Position] { chain.positionReads() }

    func debtTree(start: UInt64, limit: Int) async throws -> PrivacyReads.DebtTreePage { chain.debtTreeRead(start: start, limit: limit) }

    func validatorBook(_ valoper: String) async throws -> PrivacyReads.Book { chain.validatorBookRead(valoper) }

    func minDelegation() async throws -> UInt64 { chain.minDelegation }
}

/// The chain's layout of the stake and vote circuits' public inputs
/// (x/shieldedstaking StakeProof.PublicInputs with each msg's StakeLanes, and
/// MsgStakeVote.VotePublicInputs), pinned to the chain's own output by
/// PrivateMsgsTests; FakeChain checks every witness against it (as Android's).
enum ChainLayout {
    /// What the chain supplies a stake proof: lane A's denom, v_in, v_out; the credit lane's denom, cr_v_in, cr_move_time.
    struct Lanes {
        var denom: String?
        var vIn: UInt64
        var vOut: UInt64
        var crDenom: String? = nil
        var crVIn: UInt64 = 0
        var crMoveTime: UInt64 = 0
    }

    /// `position` names an unlocked position's validator and derth (the keeper fills an unlock's lanes in).
    static func lanes(_ m: any PrivateMsg, position: (UInt64) -> (String, UInt64) = { _ in ("", 0) }) -> Lanes {
        switch m {
        case let m as MsgShieldedDelegate: return Lanes(denom: PrivacyWallet.derthDenom(m.validator), vIn: m.derth, vOut: 0)
        case let m as MsgRestake: return Lanes(denom: PrivacyWallet.derthDenom(m.validator), vIn: 0, vOut: 0)
        case let m as MsgShieldedUndelegate: return Lanes(denom: PrivacyWallet.derthDenom(m.validator), vIn: 0, vOut: m.amount)
        case let m as MsgLockPosition: return Lanes(denom: PrivacyWallet.derthDenom(m.validator), vIn: 0, vOut: m.amount)
        case let m as MsgUnlockPosition:
            let (v, d) = position(m.positionID)
            return Lanes(denom: PrivacyWallet.derthDenom(v), vIn: d, vOut: 0)
        case let m as MsgRedelegate:
            return Lanes(denom: PrivacyWallet.derthDenom(m.srcValidator), vIn: 0, vOut: m.amount, crDenom: PrivacyWallet.derthDenom(m.dstValidator),
                         crVIn: m.dstDerth, crMoveTime: m.moveTime)
        default: return Lanes(denom: nil, vIn: 0, vOut: 0)
        }
    }

    static func stakePublicInputs(_ p: StakeProof, _ l: Lanes, _ sighash: Fr) throws -> [Fr] {
        func f(_ b: Data) throws -> Fr { try Fr(bytes: b) }
        return [try f(p.anchor), l.denom.map(PrivacyHash.assetID) ?? .zero, try f(p.nullifiers[0]), try f(p.nullifiers[1]),
                try f(p.commitment), PrivacyHash.u64(l.vIn), PrivacyHash.u64(l.vOut), PrivacyHash.u64(p.clearBefore), try f(p.debtRoot),
                l.crDenom.map(PrivacyHash.assetID) ?? .zero, try f(p.creditNullifier), try f(p.creditCommitment), PrivacyHash.u64(l.crVIn),
                PrivacyHash.u64(l.crMoveTime), try f(p.ownerTag), sighash]
    }

    static func votePublicInputs(_ m: MsgStakeVote, noteRoot: Fr, nfRoot: Fr, sighash: Fr) throws -> [Fr] {
        [noteRoot, nfRoot, try Fr(bytes: m.debtRoot), PrivacyHash.assetID(PrivacyWallet.derthDenom(m.validator)), PrivacyHash.u64(m.weight),
         PrivacyHash.u64(m.proposalID)] + (try m.voteNullifiers.map { try Fr(bytes: $0) }) + [sighash]
    }
}
