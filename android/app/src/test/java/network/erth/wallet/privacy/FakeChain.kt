package network.erth.wallet.privacy

import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite
import cosmos.tx.v1beta1.Tx
import network.erth.earth.proto.assembly.MsgProposeRemoval
import network.erth.earth.proto.assembly.MsgVoteProposal
import network.erth.earth.proto.assembly.MsgVoteRemoval
import network.erth.earth.proto.dex.MsgAddLiquidityShielded
import network.erth.earth.proto.dex.MsgNoteSwap
import network.erth.earth.proto.dex.MsgRemoveLiquidityShielded
import network.erth.earth.proto.personhood.Membership
import network.erth.earth.proto.personhood.MsgBindHandle
import network.erth.earth.proto.personhood.MsgMoveCaretaker
import network.erth.earth.proto.personhood.MsgMoveHandle
import network.erth.earth.proto.personhood.MoveProof
import network.erth.wallet.privacy.prove.MoveWitness
import network.erth.earth.proto.personhood.MsgClaimAnml
import network.erth.earth.proto.personhood.MsgRegister
import network.erth.earth.proto.personhood.MsgSetCaretaker
import network.erth.earth.proto.shielded.Bundle
import network.erth.earth.proto.shielded.MsgSend
import network.erth.earth.proto.shieldedstaking.MsgDelegate
import network.erth.earth.proto.shieldedstaking.MsgLockPosition
import network.erth.earth.proto.shieldedstaking.MsgPositionVote
import network.erth.earth.proto.shieldedstaking.MsgRedelegate
import network.erth.earth.proto.shieldedstaking.MsgRestake
import network.erth.earth.proto.shieldedstaking.MsgStakeVote
import network.erth.earth.proto.shieldedstaking.MsgUndelegate
import network.erth.earth.proto.shieldedstaking.MsgUnlockPosition
import network.erth.earth.proto.shieldedstaking.MsgUpdatePosition
import network.erth.earth.proto.shieldedstaking.StakeProof
import network.erth.wallet.chain.math.SwapMath
import network.erth.wallet.privacy.prove.ActionWitness
import network.erth.wallet.privacy.prove.MembershipWitness
import network.erth.wallet.privacy.prove.StakeWitness
import network.erth.wallet.privacy.note.NoteCipher
import network.erth.wallet.privacy.sync.ChainIdentity
import network.erth.wallet.privacy.sync.ChainRoots
import network.erth.wallet.privacy.sync.HeightPage
import network.erth.wallet.privacy.sync.NoteRootRecord
import network.erth.wallet.privacy.sync.TreeState
import network.erth.wallet.privacy.sync.IdentityPage
import network.erth.wallet.privacy.sync.IdentityRow
import network.erth.wallet.privacy.sync.IndexerBaseMoved
import network.erth.wallet.privacy.sync.IndexerStatus
import network.erth.wallet.privacy.sync.LatestRoots
import network.erth.wallet.privacy.sync.NoteRow
import network.erth.wallet.privacy.sync.NotesPage
import network.erth.wallet.privacy.sync.PrivacyIndexer
import network.erth.wallet.privacy.sync.RootRecord
import network.erth.wallet.privacy.sync.StakeNoteRow
import network.erth.wallet.privacy.sync.StakeNotesPage
import network.erth.wallet.privacy.sync.StakeNfLeavesPage
import network.erth.wallet.privacy.sync.StakeSnapshotRow
import network.erth.wallet.privacy.sync.StakeSnapshotsPage
import network.erth.wallet.privacy.prove.VoteWitness
import network.erth.wallet.privacy.zk.IndexedTree
import network.erth.wallet.privacy.zk.DebtTree
import network.erth.wallet.privacy.sync.DebtRowsPage
import network.erth.wallet.privacy.tx.PrivateChain
import network.erth.wallet.privacy.tx.PrivateMsgs
import network.erth.wallet.privacy.tx.Prover
import network.erth.wallet.privacy.tx.TxResult
import network.erth.wallet.privacy.zk.Fr
import network.erth.wallet.privacy.zk.Grumpkin
import network.erth.wallet.privacy.zk.MemNodeStore
import network.erth.wallet.privacy.zk.MerkleTree
import network.erth.wallet.privacy.zk.Privacy
import java.math.BigDecimal
import java.math.BigInteger

/**
 * An in-memory model of the chain's private side, for driving a wallet end
 * to end without a node: the note, identity and stake trees, both nullifier
 * sets, the ante's checks a private tx must pass (shape, the fee, anchors,
 * nullifiers, the release map, every binding signature for real, and every
 * proof's public inputs), and the mints each msg makes. Its indexer serves
 * the same streams the backend does. Proofs are not real: [CheckingProver]
 * checks each witness against its circuit's constraints instead and keeps it,
 * so the chain can match it to the tx and a test can hand it to nargo.
 */
class FakeChain(val chainId: String = "earth-1", var now: Long = 1_790_000_000L) : PrivateChain, PrivacyIndexer, ChainRoots {
    /** The first block hash's prefix the indexer keys its base by; a relaunch changes it. */
    var genesis = "0123456789abcdef"
    /** Set to make the indexer report it has halted. */
    var halted: String? = null
    /** The country the chain records for registrations (the verifying CSCA's). */
    var registrationCountry = "DE"
    /** Every note root the chain recorded, with its tree size. */
    val noteRootSizes = HashMap<Fr, Long>()
    /** (height -> tree state) after each block, for queries pinned to a height. */
    val identityAt = java.util.TreeMap<Long, TreeState>()
    val stakeAt = java.util.TreeMap<Long, TreeState>()
    val noteAt = java.util.TreeMap<Long, TreeState>()
    /** The block each note root was first recorded in (x/shielded RootRecord.height). */
    val noteRootHeights = HashMap<Fr, Long>()
    /** Each block's time, as the LCD serves it ([blockTimesPruned]: the node has none). */
    val blockTimes = HashMap<Long, Long>()
    val notes = ArrayList<NoteRow>()
    val noteTree = MerkleTree(MemNodeStore())
    val identityTree = MerkleTree(MemNodeStore())
    val identityRows = ArrayList<IdentityRow>()
    val nullifiers = LinkedHashMap<Fr, Long>()
    val noteRoots = HashSet<Fr>().apply { add(noteTree.root()) }
    val identityRoots = HashSet<Fr>().apply { add(identityTree.root()) }
    // x/shieldedstaking's stake tree.
    val stakeRows = ArrayList<StakeNoteRow>()
    val stakeTree = MerkleTree(MemNodeStore())
    val stakeNullifiers = LinkedHashMap<Fr, Long>()
    /** The stake nullifier indexed tree's values in insertion order (leaf i + 1), ORCHARD_DESIGN 3.4. */
    val stakeNfValues = ArrayList<Fr>()
    /** Every stake root the chain recorded (the empty tree's at the first block). */
    val stakeRoots = HashSet<Fr>()
    /** Proposal snapshots: note root and size, nullifier tree root and size (sentinel included), block. */
    data class Snap(val proposalId: Long, val root: Fr, val treeSize: Long, val nfRoot: Fr, val nfSize: Long, val height: Long)
    val snapshots = LinkedHashMap<Long, Snap>()
    /** (proposal, vote nullifier) of every stake vote. */
    val voteNullifiers = HashSet<Pair<Long, Fr>>()
    /** Whether the indexer serves the nullifier tree and snapshot streams (false: an older indexer; the wallet uses the LCD). */
    var indexerNfTree = true
    var indexerSnapshots = true
    /** Every LCD StakeNullifierTree page asked (start). */
    val nfTreeAsks = ArrayList<Long>()
    var height = 1L
    val minFee = 1000L
    var price = BigDecimal("0.001")
    var maxActions = 16
    val prover = CheckingProver()
    /** receiver -> denom -> amount unshielded. */
    val unshielded = HashMap<String, HashMap<String, Long>>()
    val votes = ArrayList<Pair<Long, Int>>()
    /** (proposal, validator, weight) of every stake vote. */
    val stakeVotes = ArrayList<Triple<Long, String, Long>>()
    var simulated = 0
    val actionCounts = ArrayList<Int>()

    // x/dex pool 1 (uanml/uerth) and its LP shares.
    var poolErth = BigInteger.valueOf(1_000_000_000_000)
    var poolAnml = BigInteger.valueOf(500_000_000_000)
    var lpSupply = BigInteger.valueOf(700_000_000_000)
    val swapFee = BigDecimal("0.3")
    /** Private withdrawals waiting to mature: shares, erth pc, token pc. */
    data class Withdrawal(val shares: BigInteger, val erthPc: Fr, val erthCt: ByteArray, val tokenPc: Fr, val tokenCt: ByteArray)
    val withdrawals = ArrayList<Withdrawal>()

    // x/shieldedstaking positions, x/personhood handles and caretaker splits, x/assembly removal ballots.
    data class Pos(val id: Long, val validator: String, val derth: Long, val ownerTag: Fr, var splits: Map<Long, Long>, val createdHeight: Long, var splitExpiresAt: Long = 0)
    /** x/allocation groundworks_lease_seconds: a split counts this long after it was cast or renewed. */
    var groundworksLease = 365L * 86_400

    /** The chain's lapse at [now]: every split whose lease has ended is cleared (splits empty, split_expires_at 0). */
    fun lapseSplits() {
        for (p in positions.values) if (p.splits.isNotEmpty() && p.splitExpiresAt in 1..now) { p.splits = emptyMap(); p.splitExpiresAt = 0 }
    }
    val positions = LinkedHashMap<Long, Pos>()
    var nextPositionId = 1L
    val positionVotes = ArrayList<Pair<Long, Long>>()
    val removalBallots = HashMap<Long, Long>()
    val removalVotes = ArrayList<Triple<Long, Fr, Int>>()
    val caretakerVotes = HashMap<Fr, Map<Long, Long>>()
    /** Caretaker leases (nullifier -> expires_at), and nullifiers that moved theirs away (1126). */
    val caretakerExpiry = HashMap<Fr, Long>()
    val caretakerMovedOut = HashSet<Fr>()
    /** caretaker_vote_seconds (R) and handle_lease_seconds / handle_renewal_seconds, as the tests' reads name them. */
    var caretakerLease = 30L * 86_400
    var handleLease = 365L * 86_400
    var handleRenewal = 30L * 86_400
    /** current_date_max_skew_seconds (the default 48 h unless a test sets it). */
    var currentDateMaxSkew = 172_800L
    /** The handle directory: handle -> (holder's nullifier, address, expires_at). */
    data class HandleRec(val handle: String, val nullifier: Fr, val address: String, val expiresAt: Long)
    val handles = java.util.TreeMap<String, HandleRec>()
    val handleMovedOut = HashSet<Fr>()
    /** What a served entry's owner says: the holder's nullifier unless a test overrides it ("" = a directory without owners). */
    var ownerHex: ((HandleRec) -> String)? = null
    /** Every Query/Handles (start) and backend /handles (from_index) page asked: never one handle. */
    val handleAsks = ArrayList<String>()
    /** Passports ever registered: a re-registration's leaf has a predecessor (x/personhood PassportsSeen). */
    val passportsSeen = HashSet<String>()
    /** Each passport's last registered idc (PassportsSeen's value): a registration to another appends a succession leaf. */
    val passportLastIdc = HashMap<String, Fr>()
    /** Succession leaves: index -> (idc_old, idc_new). */
    val successions = LinkedHashMap<Long, Pair<Fr, Fr>>()
    /** Each leaf's predecessor_at. */
    val predecessorOf = HashMap<Long, Long>()
    /** Referral notes minted (handle, pc). */
    val referralNotes = ArrayList<Pair<String, Fr>>()
    val referralPositions = ArrayList<Long>()
    /** Undelegations waiting for their payout: id, validator, value, pc, ciphertext. */
    data class Payout(val id: Long, val validator: String, val value: Long, val pc: Fr, val ct: ByteArray)
    val unbondPayouts = ArrayList<Payout>()
    var nextPayoutId = 1L
    /** Used slots of every stake vote, in order. */
    val stakeVoteSlots = ArrayList<Int>()
    /** The fake's epoch and derth rate (uerth per derth = 10/9 at delegation: 9/10 minted). */
    val epoch = 4L

    // ---- x/shieldedstaking books, moves and the slash debt ----

    /** Each validator's live book (backing, supply): rate 10/9 uerth per derth unless a test sets one. */
    val books = HashMap<String, Pair<BigInteger, BigInteger>>()
    fun book(v: String): Pair<BigInteger, BigInteger> = books[v] ?: (BigInteger.valueOf(10_000_000_000_000) to BigInteger.valueOf(9_000_000_000_000))
    /** ERTH queued for delegation per validator (what a move leaves first): 0 unless a test sets it. */
    val queues = HashMap<String, BigInteger>()
    /** Validators x/staking has unbonded: a move from one leaves its queue first. */
    val unbonded = HashSet<String>()
    /** The module's x/staking delegation D per validator (default: the backing less the queue, all bonded). */
    val delegated = HashMap<String, BigInteger>()
    /** U: undelegated privately, not yet from x/staking (0 unless a test sets it). */
    val undelegating = HashMap<String, BigInteger>()
    /** W: the module's unwithdrawn rewards (0 unless a test sets it); a move withdraws them into the queue first. */
    val rewards = HashMap<String, BigInteger>()
    /** Validators taking no delegation, with the chain's reason (1102). */
    val refusals = HashMap<String, String>()
    /** The module's (src, dst) x/staking redelegation records: (entries, counted entries). */
    val redelegationLoads = HashMap<Pair<String, String>, Pair<Long, Long>>()
    /** Every Query/Validators read (page reads included). */
    var validatorsReads = 0
    var minDelegation = 1L
    /** Applied to every book's backing just before a tx's credit check: the rate moving between quote and block. */
    var rateDriftPpm = 0L
    /** x/staking's longest unbonding time (MaxUnbonding); the label window adds 600 s. */
    var maxUnbondingSeconds = 21L * 86_400
    val labelWindow: Long get() = maxUnbondingSeconds + 600
    /** types.ClearBeforeSlackSeconds: how far below ClearBefore(now) a proof's clear_before may be. */
    val CLEAR_BEFORE_SLACK = 3_600L

    fun clearBefore(): Long = if (now <= labelWindow) 0 else now - labelWindow
    /** Open moves: key -> (src, dst, move_time, credited). */
    data class Move(val key: Fr, val src: String, val dst: String, val moveTime: Long, val credited: Long)
    val moves = LinkedHashMap<Fr, Move>()
    /** The slash debt tree's rows in insertion order, each with its latest retained. */
    val debtRows = LinkedHashMap<Fr, Long>()
    fun debtTree(): DebtTree = DebtTree.build(debtRows.entries.map { it.key to it.value })
    fun debtRoot(): Fr = debtTree().root()
    /** A slash of the move's source reaches it: its exposure now worth [retained] (a row written in the next block's BeginBlock). */
    fun slashMove(key: Fr, retained: Long) {
        val m = moves.getValue(key)
        require(retained in 0..m.credited)
        debtRows[key] = minOf(retained, debtRows[key] ?: Long.MAX_VALUE)
        block()
    }
    /** Every Query/DebtTree page asked (start), and every /debt_rows page (from_index). */
    val debtAsks = ArrayList<String>()
    /** Whether the indexer serves /debt_rows (false: an older indexer; the wallet reads the chain's pages). */
    var indexerDebtRows = true
    /** Set to make the indexer's debt stream lie about a retained (the root check must catch it). */
    var forgeDebtRetained: Long? = null

    /** Query/DebtTree. */
    fun debtTreeRead(start: Long, limit: Int): PrivacyChainReads.DebtTreePage {
        debtAsks.add("chain:$start")
        val rows = debtRows.entries.drop(start.toInt()).take(minOf(limit, 1000)).map { it.key to it.value }
        return PrivacyChainReads.DebtTreePage(rows, if (debtRows.isEmpty()) 0 else debtRows.size + 1L, debtRoot(), labelWindow, clearBefore())
    }

    /** x/staking's validators: the tests' own, and any a private delegation or move named. */
    val stakingValidators = linkedSetOf(
        "earthvaloper1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq",
        "earthvaloper1qypqxpq9qcrsszg2pvxq6rs0zqg3yyc5pmxnhd",
        "earthvaloper1qyqszqgpqyqszqgpqyqszqgpqyqszqgpjnp7du",
        "earthvaloper1zyqszqgpqyqszqgpqyqszqgpqyqszqgpqyqszq",
    )

    /** Query/Validators: x/staking's validators, and any a test gave a book, a queue or a refusal. */
    fun validatorsRead(): PrivacyChainReads.ValidatorList {
        validatorsReads++
        val ops = LinkedHashSet<String>(stakingValidators).apply { addAll(validators); addAll(books.keys); addAll(refusals.keys); addAll(queues.keys); addAll(unbonded) }
        return PrivacyChainReads.ValidatorList(height, ops.map(::quoteOf))
    }

    fun quoteOf(v: String): PrivacyChainReads.ValidatorQuote {
        val (b, sup) = book(v)
        val p = queues[v] ?: BigInteger.ZERO
        return PrivacyChainReads.ValidatorQuote(
            validator = v, backing = b, supply = sup, pendingDelegation = p, pendingUndelegation = undelegating[v] ?: BigInteger.ZERO,
            delegation = delegated[v] ?: (b - p).max(BigInteger.ZERO), rewards = rewards[v] ?: BigInteger.ZERO,
            status = if (v in unbonded) PrivacyChainReads.BOND_STATUS_UNBONDED else PrivacyChainReads.BOND_STATUS_BONDED,
            delegatable = v !in refusals, refusal = refusals[v].orEmpty(), moniker = "moniker-$v",
            redelegations = redelegationLoads.filterKeys { it.first == v }.map { (k, e) -> k.second to PrivacyChainReads.RedelegationLoad(e.first, e.second) }.toMap(),
        )
    }

    override fun debtRows(fromIndex: Long, limit: Int): DebtRowsPage {
        if (!indexerDebtRows) throw IndexerBaseMoved("no /debt_rows (test)")
        debtAsks.add("indexer:$fromIndex")
        val n = aligned("debt_rows", fromIndex, limit)
        val all = debtRows.entries.toList()
        val rows = (maxOf(fromIndex, 1L) until fromIndex + n).takeWhile { it <= all.size }.map {
            val e = all[(it - 1).toInt()]
            Triple(it, e.key, forgeDebtRetained ?: e.value)
        }
        val full = fromIndex + n <= all.size + 1L
        return DebtRowsPage(rows, if (full) fromIndex + n else (rows.lastOrNull()?.first?.plus(1) ?: maxOf(fromIndex, 1L)), full,
            if (all.isEmpty()) 0 else all.size + 1L, debtRoot())
    }

    /** floor(value x S / B) at [v]'s book (with the drift a test set), the chain's derthFor. */
    private fun buys(v: String, value: BigInteger): BigInteger {
        val (b0, sup) = book(v)
        val b = b0 + b0 * BigInteger.valueOf(rateDriftPpm) / BigInteger.valueOf(1_000_000)
        return if (sup.signum() == 0) value else value * sup / b
    }

    private fun checkCredit(v: String, value: BigInteger, credit: Long) {
        val buys = buys(v, value)
        require(buys >= BigInteger.valueOf(minDelegation)) { "the value buys $buys derth, less than the minimum (code 1103)" }
        require(credit >= minDelegation) { "credits less than the minimum (code 1103)" }
        require(BigInteger.valueOf(credit) <= buys) { "the delegation buys $buys derth at the live rate, less than the $credit it credits (the rate moved since the proof: re-quote with a margin)" }
    }

    // ---- chain ----

    /** Ends the block being built: its roots become anchors. Writes land at [height], the block in progress. */
    private fun block() {
        noteRoots.add(noteTree.root()); identityRoots.add(identityTree.root())
        noteRootSizes[noteTree.root()] = noteTree.size
        noteRootHeights.putIfAbsent(noteTree.root(), height)
        noteAt[height] = TreeState(noteTree.size, if (noteTree.size == 0L) null else noteTree.root())
        stakeRoots.add(stakeTree.root())
        identityAt[height] = TreeState(identityTree.size, if (identityTree.size == 0L) null else identityTree.root())
        stakeAt[height] = TreeState(stakeTree.size, if (stakeTree.size == 0L) null else stakeTree.root())
        blockTimes[height] = now
        height++
    }

    init { block() }

    /** Every note the chain mints carries a 177-byte amount-blind ciphertext (one note-discovery rule). */
    fun mint(denom: String, value: Long, pc: Fr, ct: ByteArray): Long {
        require(ct.size == NoteCipher.BLIND_CIPHERTEXT_BYTES) { "a minted note needs its 177-byte blind ciphertext, got ${ct.size}" }
        val cm = Privacy.cm(Privacy.assetId(denom), value, pc)
        val pos = noteTree.append(cm)
        notes.add(NoteRow(pos, height, cm, ct, "$value$denom"))
        return pos
    }

    /**
     * MintOpenNote: a note whose opening the chain chose, no ciphertext; the
     * row carries owner_pk, rho and rcm (the shielded_mint event's).
     */
    fun mintOpen(denom: String, value: Long, ownerPk: Fr, rho: Fr, rcm: Fr): Long {
        val cm = Privacy.cm(Privacy.assetId(denom), value, Privacy.pc(ownerPk, rho, rcm))
        val pos = noteTree.append(cm)
        notes.add(NoteRow(pos, height, cm, ByteArray(0), "$value$denom", ownerPk, rho, rcm))
        return pos
    }

    /**
     * MintNoteSplit: a value past a note's u64 as ceil(v / (2^64-1)) notes,
     * all to one pc with one ciphertext, each its own amount and position.
     * [values] are the chunks as the chain cuts them (the test chooses ones a
     * wallet can hold).
     */
    fun mintSplit(denom: String, values: List<Long>, pc: Fr, ct: ByteArray): List<Long> = values.map { mint(denom, it, pc, ct) }

    /**
     * A stake note of [keys]'s owner made elsewhere (another device's proof,
     * another owner's): its commitment and wallet stake ciphertext appended
     * as a proof output would, and the block ended.
     */
    fun plantStake(keys: network.erth.wallet.privacy.keys.PrivacyKeys, denom: String, amount: Long, label: network.erth.wallet.privacy.note.StakeLabel? = null): Long {
        val o = NoteCipher.StakeOpening(Privacy.assetId(denom), amount, network.erth.wallet.privacy.note.NotePlaintext.randomField(), network.erth.wallet.privacy.note.NotePlaintext.randomField(), label)
        val cm = o.cm(keys.ownerPk)
        val pos = stakeTree.append(cm)
        stakeRows.add(StakeNoteRow(pos, height, cm, NoteCipher.encryptStake(o, keys.ekPub, cm)))
        validators.add(PrivacyWallet.parseDerth(denom))
        block()
        return pos
    }

    /** The stake nullifier tree's root now (indexed.EmptyRoot before the first insert). */
    fun stakeNfRoot(): Fr = IndexedTree.build(stakeNfValues).root()

    /** A proposal enters voting: its snapshot is the trees as the last block left them; the block it is taken in ends. */
    fun openProposal(id: Long): Snap {
        val s = Snap(id, if (stakeTree.size == 0L) Fr.ZERO else stakeTree.root(), stakeTree.size, stakeNfRoot(),
            if (stakeNfValues.isEmpty()) 0 else stakeNfValues.size + 1L, height)
        snapshots[id] = s
        block()
        return s
    }

    /** Query/Snapshot. */
    fun snapshotRead(id: Long): PrivacyChainReads.Snapshot {
        val s = snapshots[id] ?: error("no snapshot for proposal $id")
        return PrivacyChainReads.Snapshot(s.root, s.treeSize, s.height, emptyMap(), s.nfRoot, s.nfSize)
    }

    /** Query/StakeNullifierTree. */
    fun nfTreeRead(start: Long, limit: Int): PrivacyChainReads.NfTreePage {
        nfTreeAsks.add(start)
        val vs = stakeNfValues.drop(start.toInt()).take(minOf(limit, 1000))
        return PrivacyChainReads.NfTreePage(vs, if (stakeNfValues.isEmpty()) 0 else stakeNfValues.size + 1L)
    }

    /** MsgShield (a gas grant, a shield from a transparent account): its ciphertext is required. */
    fun shield(denom: String, value: Long, pc: Fr, ct: ByteArray) { mint(denom, value, pc, ct); block() }

    /** Ends a block with no tx in it. */
    fun emptyBlock() = block()

    /**
     * The unbonding period passes: the chain pays every queued undelegation
     * by itself (MintNoteSplit to its pc and ciphertext; [split] cuts a
     * payout into chunks as the chain would past 2^63-1). Nothing is sent.
     */
    fun payUnbonds(split: (Payout) -> List<Long> = { listOf(it.value) }) {
        for (p in unbondPayouts) {
            require(split(p).sum() == p.value)
            mintSplit("uerth", split(p), p.pc, p.ct)
        }
        unbondPayouts.clear()
        block()
    }

    /** The LP unbonding period passes: every private withdrawal pays both legs as notes. */
    fun matureWithdrawals() {
        for (w in withdrawals) {
            val sh = w.shares
            val e = sh * poolErth / lpSupply
            val t = sh * poolAnml / lpSupply
            poolErth -= e; poolAnml -= t; lpSupply -= sh
            mint("uerth", e.toLong(), w.erthPc, w.erthCt)
            mint("uanml", t.toLong(), w.tokenPc, w.tokenCt)
        }
        withdrawals.clear()
        block()
    }

    override fun gasPrice(): BigDecimal = price
    override fun minFee(): Long = minFee
    override fun maxActionsPerBundle(): Int = maxActions
    override fun tipHeight(): Long = height - 1 + tipAhead + sendTipAhead

    /** How far the tip a tx's timeout is set from runs ahead (a node inflating it at send only). */
    var sendTipAhead = 0L

    /** x/shielded Query/Assets as the node serves it: null says nothing. */
    var assetList: List<Pair<String, Fr>>? = null

    override fun assets(): List<Pair<String, Fr>>? = assetList

    /** The ante charges per bundle and per action, before anything else: gas is the tx's shape. */
    /** Every nullifier (pool, stake, membership) the node saw in a simulated tx. */
    val simulatedNullifiers = ArrayList<Fr>()

    override fun simulate(tx: ByteArray): Long {
        simulated++
        val m = check(tx, simulate = true).first
        PrivateMsgs.bundles(m).forEach { b -> b.actionsList.forEach { simulatedNullifiers.add(f(it.nullifier)) } }
        PrivateMsgs.stake(m)?.let { p -> (p.nullifiersList + listOf(p.creditNullifier)).forEach { simulatedNullifiers.add(f(it)) } }
        return gasOf(m)
    }

    /** What the fake's ante charges [m]: its shape alone (a vote: gasVote + proof + (1 + 2) x note). */
    fun gasOf(m: MessageLite): Long {
        val actions = PrivateMsgs.bundles(m).sumOf { it.actionsCount }
        val vote = if (m is MsgStakeVote) 2_250_000L + (1L + m.voteNullifiersCount) * 150_000L else 0L
        return 200_000L + 100_000L * PrivateMsgs.bundles(m).size + 350_000L * actions + (if (PrivateMsgs.stake(m) != null) 400_000 else 0) + vote +
            (if (m is MsgRedelegate) redelegateGas(m.srcValidator, m.dstValidator) else 0L)
    }

    /** The chain's redelegateGas beyond the 400,000 above: 700,000 base, 2,500 an entry, the merge at the cap. */
    fun redelegateGas(src: String, dst: String): Long {
        val (n, counted) = redelegationLoads[src to dst] ?: (0L to 0L)
        return 300_000L + n * 2_500L + if (counted >= 1_024) n * 2_500L + 128L * 20_000L else 0L
    }

    /** The last committed tx's gas_limit. */
    var lastGasLimit = 0L

    /** Every committed private tx's gas_limit over the gas it uses (the chain allows at most 5). */
    val gasRatios = ArrayList<Double>()

    /** Whether a committed tx must carry a timeout_height (the wallet always sets one). */
    var requireTimeout = true
    /** The last checked tx's timeout_height. */
    var lastTimeoutHeight = 0L

    /** Broadcasts to refuse (after the wallet proved them): a node down, a tx dropped. */
    var rejectNext = 0

    /** Broadcasts accepted and committed whose wait then times out (the app sees TxUnconfirmedException). */
    var unconfirmedNext = 0
    /** Broadcasts accepted (CheckTx) and then dropped: never in a block. */
    var dropNext = 0
    /** Broadcasts accepted (CheckTx) that then fail in their block (DeliverTx code 5): nothing changes. */
    var failInBlockNext = 0
    /** The last committed msg. */
    var lastMsg: MessageLite? = null
    /** Every tx by hash, as Query/GetTx answers. */
    val txs = HashMap<String, TxResult>()

    override fun tx(hash: String): TxResult? = txs[hash]

    /** An idc that registers elsewhere between the simulate and the broadcast (CheckTx then refuses it: 1130). */
    var usedOnBroadcast: Fr? = null

    /** The LCD a CheckTx refusal names as having answered it. */
    var answeringLcd = ""

    override fun broadcast(tx: ByteArray, accepted: (hash: String) -> Unit): TxResult {
        usedOnBroadcast?.let { usedIdcs.add(it); usedOnBroadcast = null }
        // CheckTx answers with the code and codespace (a simulate with the text alone).
        try { check(tx, simulate = true) } catch (e: IllegalArgumentException) {
            if (e.message?.endsWith("(code 1130)") == true) {
                prover.actions.clear(); prover.stakes.clear(); prover.memberships.clear(); prover.votes.clear(); prover.moves.clear()
                throw network.erth.wallet.privacy.tx.UnsignedTx.TxRejected(1130, e.message!!, "personhood", answeringLcd)
            }
        }
        if (rejectNext > 0) {
            // The proofs made for it never reach the chain.
            rejectNext--
            prover.actions.clear(); prover.stakes.clear(); prover.memberships.clear(); prover.votes.clear(); prover.moves.clear()
            throw network.erth.wallet.privacy.tx.UnsignedTx.TxRejected(19, "broadcast refused (test)")
        }
        val hash = network.erth.wallet.privacy.tx.UnsignedTx.hash(tx)
        if (dropNext > 0) {
            // Accepted by CheckTx, then never included (evicted from the mempool).
            dropNext--
            check(tx, simulate = true)
            accepted(hash)
            prover.actions.clear(); prover.stakes.clear(); prover.memberships.clear(); prover.votes.clear(); prover.moves.clear()
            throw java.io.IOException("tx not committed (test)")
        }
        if (failInBlockNext > 0) {
            failInBlockNext--
            check(tx, simulate = true)
            accepted(hash)
            prover.actions.clear(); prover.stakes.clear(); prover.memberships.clear(); prover.votes.clear(); prover.moves.clear()
            block()
            txs[hash] = TxResult(hash, height - 1, now, emptyList(), code = 5, log = "failed in block (test)")
            throw java.io.IOException("tx failed (code 5)")
        }
        check(tx, simulate = true)
        accepted(hash)
        val (m, events) = check(tx, simulate = false)
        lastMsg = m
        block()
        val r = TxResult(hash, height - 1, now, events)
        txs[hash] = r
        if (unconfirmedNext > 0) {
            unconfirmedNext--
            throw network.erth.wallet.chain.TxUnconfirmedException(hash)
        }
        return r
    }

    private fun decode(raw: Tx.TxRaw): MessageLite {
        val body = Tx.TxBody.parseFrom(raw.bodyBytes)
        require(body.messagesCount == 1)
        val any = body.getMessages(0)
        return when (any.typeUrl) {
            PrivateMsgs.SEND -> MsgSend.parseFrom(any.value)
            PrivateMsgs.REGISTER -> MsgRegister.parseFrom(any.value)
            PrivateMsgs.CLAIM_ANML -> MsgClaimAnml.parseFrom(any.value)
            PrivateMsgs.VOTE_PROPOSAL -> MsgVoteProposal.parseFrom(any.value)
            PrivateMsgs.DELEGATE -> MsgDelegate.parseFrom(any.value)
            PrivateMsgs.RESTAKE -> MsgRestake.parseFrom(any.value)
            PrivateMsgs.UNDELEGATE -> MsgUndelegate.parseFrom(any.value)
            PrivateMsgs.STAKE_VOTE -> MsgStakeVote.parseFrom(any.value)
            PrivateMsgs.NOTE_SWAP -> MsgNoteSwap.parseFrom(any.value)
            PrivateMsgs.ADD_LIQUIDITY_SHIELDED -> MsgAddLiquidityShielded.parseFrom(any.value)
            PrivateMsgs.REMOVE_LIQUIDITY_SHIELDED -> MsgRemoveLiquidityShielded.parseFrom(any.value)
            PrivateMsgs.LOCK_POSITION -> MsgLockPosition.parseFrom(any.value)
            PrivateMsgs.UPDATE_POSITION -> MsgUpdatePosition.parseFrom(any.value)
            PrivateMsgs.UNLOCK_POSITION -> MsgUnlockPosition.parseFrom(any.value)
            PrivateMsgs.POSITION_VOTE -> MsgPositionVote.parseFrom(any.value)
            PrivateMsgs.REDELEGATE -> MsgRedelegate.parseFrom(any.value)
            PrivateMsgs.BIND_HANDLE -> MsgBindHandle.parseFrom(any.value)
            PrivateMsgs.MOVE_HANDLE -> MsgMoveHandle.parseFrom(any.value)
            PrivateMsgs.MOVE_CARETAKER -> MsgMoveCaretaker.parseFrom(any.value)
            PrivateMsgs.SET_CARETAKER -> MsgSetCaretaker.parseFrom(any.value)
            PrivateMsgs.PROPOSE_REMOVAL -> MsgProposeRemoval.parseFrom(any.value)
            PrivateMsgs.VOTE_REMOVAL -> MsgVoteRemoval.parseFrom(any.value)
            else -> error("fake chain does not know ${any.typeUrl}")
        }
    }

    /** What the swap pays, as x/dex prices it (the wallet's SwapMath is pinned to amm.go separately). */
    private fun swapOut(denomIn: String, amountIn: Long, denomOut: String): Long =
        SwapMath.route(mapOf("uanml" to SwapMath.Reserves(poolErth, poolAnml)), "uerth", denomIn, BigInteger.valueOf(amountIn), denomOut, swapFee)
            ?.amountOut?.toLong() ?: error("no route")

    private fun f(b: ByteString): Fr = Fr.fromBytes(b.toByteArray())

    /** types.Remainders: per denom, the bundles' balances less the fee in uerth. */
    private fun remainders(m: MessageLite): Map<String, Long> {
        val sum = sortedMapOf<String, Long>()
        for (b in PrivateMsgs.bundles(m)) for (bal in b.balancesList) sum.merge(bal.denom, bal.amount, Math::addExact)
        val fee = PrivateMsgs.privateFee(m)
        require((sum["uerth"] ?: 0) >= fee) { "fee exceeds the uerth balance" }
        if (fee > 0) sum["uerth"] = sum.getValue("uerth") - fee
        return sum.filterValues { it > 0 }
    }

    /** The release map each msg allows (the msgs' ValidateBasic). */
    private fun checkRelease(m: MessageLite, rem: Map<String, Long>) {
        fun only(denom: String?) = require(if (denom == null) rem.isEmpty() else rem.keys == setOf(denom)) { "release map: $rem" }
        when (m) {
            is MsgSend -> {
                require(m.fee > 0)
                require(rem.isEmpty() == m.receiver.isEmpty()) { "receiver exactly when something is left" }
                // Never to a module account.
                if (m.receiver.isNotEmpty()) require(PrivateMsgs.moduleAccountOf(network.erth.wallet.crypto.Bech32.decode(m.receiver)) == null) { "receiver is a module account" }
                require(rem.keys.none { it.startsWith("dexlp/") }) { "LP shares cannot be unshielded" }
            }
            is MsgDelegate -> { only("uerth"); require(rem.getValue("uerth") == m.amount && m.amount > 0) { "delegate releases amount" } }
            is MsgNoteSwap -> {
                require(rem == mapOf(m.denomIn to m.amountIn) && m.denomOut != m.denomIn) { "a swap releases exactly amount_in of denom_in: $rem" }
                require(PrivateMsgs.privateFee(m) > 0) { "a swap pays a positive fee from its bundle" }
                require(m.ciphertext.size() == NoteCipher.BLIND_CIPHERTEXT_BYTES)
            }
            is MsgAddLiquidityShielded -> {
                require(rem.keys == setOf("uerth", "uanml") && m.poolId == 1L && rem.getValue("uerth") == m.erthAmount)
                require(PrivateMsgs.privateFee(m) > 0)
                require(m.shareCiphertext.size() == NoteCipher.BLIND_CIPHERTEXT_BYTES && m.refundCiphertext.size() == NoteCipher.BLIND_CIPHERTEXT_BYTES)
            }
            is MsgRemoveLiquidityShielded -> {
                only("dexlp/${m.poolId}")
                require(m.erthCiphertext.size() == NoteCipher.BLIND_CIPHERTEXT_BYTES && m.tokenCiphertext.size() == NoteCipher.BLIND_CIPHERTEXT_BYTES)
            }
            is MsgUndelegate -> {
                only(null)
                // The payout's pc and ciphertext: checked like any mint.
                require(m.pc.size() == 32 && !f(m.pc).isZero) { "pc" }
                require(m.ciphertext.size() == NoteCipher.BLIND_CIPHERTEXT_BYTES) { "the payout needs its 177-byte blind ciphertext" }
            }
            is MsgRegister -> {
                only(null)
                require(m.ciphertextAnml.size() == NoteCipher.BLIND_CIPHERTEXT_BYTES && m.ciphertextErth.size() == NoteCipher.BLIND_CIPHERTEXT_BYTES)
                // The referral is the handle alone; the chain makes its note.
                if (m.affiliateHandle.isNotEmpty()) require(network.erth.wallet.privacy.handles.Handles.valid(m.affiliateHandle)) { "affiliate_handle" }
                // An idc registers once, ever (ErrIdcUsed, in the ante before the proof).
                require(f(m.idc) !in usedIdcs) { "identity commitment has been registered before; register a fresh identity (code 1130)" }
                // The proof's idc output is the msg's (params.idc_index 4).
                require(m.publicSignalsCount == 5 && PrivateMsgs.decimalField(m.publicSignalsList[4]) == f(m.idc)) { "proof's identity commitment is not this msg's idc (code 1103)" }
            }
            is MsgBindHandle -> {
                only(null)
                require(m.handle.isEmpty() == m.address.isEmpty()) { "a bind names a handle and an address; a release neither" }
                if (m.address.isNotEmpty()) require(network.erth.wallet.privacy.keys.ShieldedAddress.decode(m.address).encode() == m.address) { "address not canonical" }
            }
            is MsgMoveHandle -> { only(null); moveShape(m.move); require(network.erth.wallet.privacy.handles.Handles.valid(m.handle)) { "handle" } }
            is MsgMoveCaretaker -> { only(null); moveShape(m.move) }
            is MsgClaimAnml -> { only(null); require(m.ciphertext.size() == NoteCipher.BLIND_CIPHERTEXT_BYTES) }
            else -> only(null)
        }
        require(PrivateMsgs.totalFee(m) > 0)
    }

    /** MoveProof.ValidateBasic: the proof's length, canonical fields, two distinct nullifiers. */
    private fun moveShape(p: MoveProof) {
        require(p.proof.size() == PROOF_BYTES) { "a move proof is exactly $PROOF_BYTES bytes" }
        require(p.root.size() == 32 && p.oldNullifier.size() == 32 && p.newNullifier.size() == 32) { "a move field is 32 bytes" }
        require(p.oldNullifier != p.newNullifier) { "old and new nullifier are the same" }
    }

    private fun lanes(m: MessageLite): ChainLayout.Lanes = ChainLayout.lanes(m) { id -> positions.getValue(id).let { it.validator to it.derth } }


    private fun spent(p: StakeProof) = (p.nullifiersList + listOf(p.creditNullifier)).map(::f).filter { !it.isZero }

    /** The chain's shape rule: a note-moving msg spends (or pads) in both slots and creates; a crediting one uses its credit lane; the rest are zero. */
    private fun stakeShape(m: MessageLite, p: StakeProof) {
        val notes = m !is MsgUpdatePosition && m !is MsgPositionVote
        val credit = m is MsgRedelegate
        if (notes) {
            require(!f(p.getNullifiers(0)).isZero && !f(p.getNullifiers(1)).isZero) { "the stake proof spends a note (or pads with its own nullifier) in both slots" }
            require(!f(p.commitment).isZero) { "the stake proof creates a note (the merged note, the change or a zero note)" }
        } else {
            require(f(p.getNullifiers(0)).isZero && f(p.getNullifiers(1)).isZero && f(p.commitment).isZero) { "the stake proof spends and creates nothing for this msg" }
        }
        if (credit) require(!f(p.creditNullifier).isZero && !f(p.creditCommitment).isZero) { "the credit lane spends (or pads) and creates" }
        else require(f(p.creditNullifier).isZero && f(p.creditCommitment).isZero) { "this msg credits no second asset" }
    }

    /** A ballot's max_predecessor (x/assembly: opened - 86400; the fake opens ballots as it is asked). */
    fun ballotMaxPredecessor(): Long = now - 86_400

    /** The handle claim bound: now - the longest lease - 86400. */
    /**
     * The longest handle lease ever in force (x/personhood handle_lease_max),
     * and a caretaker lease held after a cut (lease_hold): what LeaseBounds
     * reports and the bounds use, never the current param alone.
     */
    var handleLeaseMax = 0L
    var caretakerLeaseHold = 0L
    private fun effectiveHandleLease(): Long = maxOf(handleLease, handleLeaseMax)
    private fun effectiveCaretakerLease(): Long = maxOf(caretakerLease, caretakerLeaseHold)
    private fun handleClaimBound(): Long = now - effectiveHandleLease() - 86_400

    /** Query/LeaseBounds at the chain's last block time. */
    fun leaseBounds() = network.erth.wallet.privacy.PrivacyChainReads.LeaseBounds(
        now, 86_400, effectiveHandleLease(), handleClaimBound(), effectiveCaretakerLease(), now - effectiveCaretakerLease() - 86_400,
    ).also { leaseBoundsReads++ }

    /** Every LeaseBounds read (the wallet must use it for every bound). */
    var leaseBoundsReads = 0

    private fun nf(m: Membership): Fr = Fr.fromBytes(m.nullifier.toByteArray())

    private fun caretakerHolds(n: Fr): Boolean = n in caretakerVotes

    /** A lapsed split the sweep has not reached is not held. */
    private fun caretakerHoldsLive(n: Fr): Boolean = caretakerHolds(n) && (caretakerExpiry[n] ?: 0L) > now

    /** A handle held and live (not in its renewal period). */
    private fun holdsLiveHandle(n: Fr): Boolean = handleOf(n)?.let { now < it.expiresAt } == true

    private fun handleOf(n: Fr): HandleRec? = handles.values.firstOrNull { it.nullifier == n }

    /** The chain's refusal of a too-recent predecessor (strictly before the bound). */
    private fun predecessorBound(maxPredecessor: Long, bound: Long) =
        require(bound > 0 && maxPredecessor in 0 until bound) { "max_predecessor $maxPredecessor is not before $bound (now - lease length - activation margin)" }

    /**
     * A membership's statement per msg: scope, max_activation,
     * max_predecessor (x/personhood and x/assembly at 4a663d5), and the
     * checks each makes of the prover's standing first.
     */
    private fun membershipStatement(m: MessageLite): Triple<Fr, Long, Long>? = when (m) {
        is MsgClaimAnml -> Triple(Privacy.claimScope(m.day), (m.day - 1) * 86_400, Privacy.NO_BOUND)
        is MsgVoteProposal -> Triple(Privacy.proposalScope(m.proposalId, 0), Privacy.NO_BOUND, ballotMaxPredecessor())
        is MsgSetCaretaker -> {
            val n = nf(m.membership)
            if (!caretakerHoldsLive(n) && m.percentagesCount > 0) {
                require(n !in caretakerMovedOut) { "this identity moved its caretaker split away (code 1126)" }
                predecessorBound(m.maxPredecessor, now - effectiveCaretakerLease() - 86_400)
            }
            Triple(Privacy.caretakerScope(), Privacy.NO_BOUND, m.maxPredecessor)
        }
        is MsgBindHandle -> {
            val n = nf(m.membership)
            val holds = handleOf(n) != null
            if (m.handle.isNotEmpty()) {
                require(network.erth.wallet.privacy.handles.Handles.valid(m.handle)) { "not a handle" }
                handles[m.handle]?.let { h -> require(h.nullifier == n || now >= h.expiresAt + handleRenewal) { "handle is held by another human (code 1122)" } }
                // Only a live handle renews or changes unbounded.
                if (!holdsLiveHandle(n)) {
                    require(n !in handleMovedOut) { "this identity moved its handle away (code 1125)" }
                    predecessorBound(m.maxPredecessor, handleClaimBound())
                }
            } else require(holds) { "the prover holds no handle to release" }
            Triple(Privacy.handleScope(), Privacy.NO_BOUND, m.maxPredecessor)
        }
        is MsgProposeRemoval -> Triple(Privacy.proposeRemovalScope(m.optionId, now / 86_400), Privacy.NO_BOUND, now / 86_400 * 86_400 - 86_400)
        is MsgVoteRemoval -> Triple(Privacy.removalScope(removalBallots.getValue(m.optionId)), Privacy.NO_BOUND, ballotMaxPredecessor())
        else -> null
    }

    private fun membershipOf(m: MessageLite): Membership? = when (m) {
        is MsgClaimAnml -> m.membership
        is MsgVoteProposal -> m.membership
        is MsgSetCaretaker -> m.membership
        is MsgBindHandle -> m.membership
        is MsgProposeRemoval -> m.membership
        is MsgVoteRemoval -> m.membership
        else -> null
    }

    /**
     * A move's statement (x/personhood checkMoveHandle / checkMoveCaretaker):
     * the scope its msg fixes, after the holder and recipient checks.
     */
    private fun moveStatement(m: MessageLite): Fr? = when (m) {
        is MsgMoveCaretaker -> {
            val n = f(m.move.oldNullifier); val o = f(m.move.newNullifier)
            val exp = caretakerExpiry[n] ?: error("old_nullifier holds no caretaker split")
            require(exp > now) { "old_nullifier's caretaker split has lapsed" }
            require(!caretakerHolds(o)) { "new_nullifier already holds a caretaker split" }
            require(o !in caretakerMovedOut) { "this identity moved its caretaker split away (code 1126)" }
            Privacy.caretakerScope()
        }
        is MsgMoveHandle -> {
            val n = f(m.move.oldNullifier); val o = f(m.move.newNullifier)
            require(handles[m.handle]?.nullifier == n) { "old_nullifier does not hold ${m.handle}" }
            require(now < handles.getValue(m.handle).expiresAt) { "\"${m.handle}\" is not live (renewal): renew it before moving it" }
            require(handleOf(o) == null) { "new_nullifier already holds a handle" }
            require(o !in handleMovedOut) { "this identity moved its handle away (code 1125)" }
            Privacy.handleScope()
        }
        else -> null
    }

    private fun shares(erth: Long, anml: Long): BigInteger =
        minOf(BigInteger.valueOf(erth) * lpSupply / poolErth, BigInteger.valueOf(anml) * lpSupply / poolAnml)

    /** Denoms governance send-disabled (bank SendEnabled false): refused at every pool edge. */
    var sendDisabled: Set<String> = emptySet()

    /** The action's own checks, before anything is written (atomic with the spend in the ante). */
    private fun precheck(m: MessageLite, rem: Map<String, Long>) {
        // The ante's release-map check and the module mint: a
        // dex note swap and a private delegation release out of the pool.
        val edges = when (m) {
            is MsgNoteSwap -> rem.keys + m.denomOut
            is MsgDelegate -> rem.keys
            is MsgSend -> rem.keys
            else -> emptySet()
        }
        edges.firstOrNull { it in sendDisabled }?.let { throw IllegalArgumentException("$it transfers are currently disabled: send transactions are disabled") }
        when (m) {
            is MsgNoteSwap -> {
                val (denomIn, amountIn) = rem.entries.single()
                val out = swapOut(denomIn, amountIn, m.denomOut)
                require(out >= m.minAmountOut) { "slippage: got $out, want >= ${m.minAmountOut}" }
            }
            is MsgAddLiquidityShielded -> if (m.minShares.isNotEmpty()) {
                require(shares(rem.getValue("uerth"), rem.getValue("uanml")) >= BigInteger(m.minShares)) { "below min_shares" }
            }
            is MsgDelegate -> {
                refusals[m.validator]?.let { throw IllegalArgumentException("$it (code 1102)") }
                require(m.amount >= minDelegation) { "a delegation is at least ${minDelegation}uerth" }
                checkCredit(m.validator, BigInteger.valueOf(m.amount), m.derth)
            }
            is MsgRedelegate -> {
                require(m.srcValidator != m.dstValidator) { "source and destination are the same validator (code 1120)" }
                refusals[m.dstValidator]?.let { throw IllegalArgumentException("$it (code 1102)") }
                require(m.moveTime in 1..now && now - m.moveTime <= 600) { "move_time ${m.moveTime} is not within 600s before the block time $now (name a recent block's time)" }
                val (bA, sA) = book(m.srcValidator)
                val u = BigInteger.valueOf(m.amount) * bA / sA
                require(u >= BigInteger.valueOf(minDelegation)) { "the redelegation is worth less than the minimum (code 1103)" }
                // What arrives: pro rata out of the queue and the
                // bonded stake, up to bondedDust + a truncated uerth short of u
                // (the least it can be); all of it only when the value leaves the
                // queue first (src unbonded, or its bonded part D - U nothing)
                // and the queue, its rewards withdrawn into it, covers u.
                val src = quoteOf(m.srcValidator)
                val queueFirst = m.srcValidator in unbonded || src.delegation <= src.pendingUndelegation
                val arrived = if (queueFirst && u <= src.pendingDelegation + src.rewards) u else (u - BigInteger.valueOf(1001)).max(BigInteger.ZERO)
                checkCredit(m.dstValidator, arrived, m.dstDerth)
            }
            is MsgUpdatePosition -> require(positions.getValue(m.positionId).ownerTag == f(m.stake.ownerTag)) { "not the position's owner" }
            is MsgUnlockPosition -> require(positions.getValue(m.positionId).ownerTag == f(m.stake.ownerTag)) { "not the position's owner" }
            is MsgPositionVote -> {
                require(positions.getValue(m.positionId).ownerTag == f(m.stake.ownerTag)) { "not the position's owner" }
                require(m.optionsList.all { it.weight == PrivateMsgs.legacyDec(it.weight) }) { "a vote weight is not canonical" }
            }
            is MsgVoteRemoval -> require(m.optionId in removalBallots) { "no open ballot" }
            is MsgProposeRemoval -> require(m.optionId !in removalBallots) { "ballot already open" }
            // Option weights only in their canonical LegacyDec form.
            is MsgStakeVote -> {
                require(m.optionsList.all { it.weight == PrivateMsgs.legacyDec(it.weight) }) { "a vote weight is not canonical" }
                val snap = snapshots[m.proposalId] ?: error("no open snapshot for proposal ${m.proposalId}")
                require(m.weight in 1..Long.MAX_VALUE) { "weight must be positive" }
                // At most three significant digits.
                require(PrivacyWallet.voteWeight(m.weight) == m.weight) { "weight has more than 3 significant digits" }
                // Exactly two, non-zero (an unused slot carries a padding nullifier), distinct.
                require(m.voteNullifiersCount == PrivateMsgs.MAX_VOTE_NOTES) { "a stake vote carries exactly 2 vote nullifiers" }
                require(m.voteNullifiersList.all { it.size() == 32 }) { "vote_nullifiers" }
                val vs = m.voteNullifiersList.map(::f)
                require(vs.none { it.isZero }) { "a zero vote nullifier: an unused slot carries a padding nullifier" }
                require(vs.toSet().size == vs.size) { "repeated vote nullifier" }
                vs.firstOrNull { (m.proposalId to it) in voteNullifiers }?.let {
                    throw IllegalArgumentException("this stake note already voted on this proposal (code 1119): proposal ${m.proposalId}, vote nullifier ${it.toHex().uppercase()}")
                }
                require(snap.nfSize >= 0)
            }
            is MsgRegister -> if (m.affiliateHandle.isNotEmpty()) {
                val h = handles[m.affiliateHandle]
                require(h != null && now < h.expiresAt) { "affiliate_handle is not a live handle (code 1121)" }
            }
            else -> {}
        }
        if (m is MsgRegister) {
            require(identityRows.none { it.leaf != Fr.ZERO && registeredIdc[it.index] == f(m.idc) }) { "a switch to the live idc is refused" }
        }
    }

    private fun checkBundle(i: Int, b: Bundle, sighash: Fr, simulate: Boolean, seen: MutableSet<Fr>) {
        require(b.actionsCount in 2..maxActions) { "bundle $i: ${b.actionsCount} actions" }
        require(b.balancesList.all { it.amount > 0 } && b.balancesList.map { it.denom }.toSet().size == b.balancesCount) { "balances" }
        require(b.balancesCount <= 2 * b.actionsCount)
        for (a in b.actionsList) {
            require(f(a.anchor) in noteRoots) { "unknown anchor" }
            val nf = f(a.nullifier)
            require(nf !in nullifiers) { "nullifier spent" }
            require(seen.add(nf)) { "duplicate nullifier" }
            Grumpkin.Point.fromBytes(a.cv.toByteArray())
            require(a.proof.size() == PROOF_BYTES) { "a proof is exactly $PROOF_BYTES bytes" }
        }
        require(b.bindingSig.size() == Grumpkin.BINDING_SIG_BYTES)
        if (simulate) return
        require(PrivateMsgs.checkBalance(b, sighash)) { "bundle $i: binding signature" }
        b.actionsList.forEachIndexed { j, a ->
            val w = prover.actions.removeFirstOrNull() ?: error("no proof for bundle $i action $j")
            val cv = a.cv.toByteArray()
            val expect = listOf(f(a.anchor), f(a.nullifier), f(a.commitment),
                Fr.fromBytes(cv.copyOfRange(0, 32)), Fr.fromBytes(cv.copyOfRange(32, 64)), sighash)
            require(w.publicInputs() == expect) { "bundle $i action $j proof is for other public inputs" }
        }
    }

    /** The ante, then the handler. */
    private fun check(txBytes: ByteArray, simulate: Boolean): Pair<MessageLite, List<Pair<String, Map<String, String>>>> {
        val raw = Tx.TxRaw.parseFrom(txBytes)
        require(raw.signaturesCount == 0) { "private txs are unsigned" }
        val auth = Tx.AuthInfo.parseFrom(raw.authInfoBytes)
        require(auth.signerInfosCount == 0)
        val m = decode(raw)
        val body = Tx.TxBody.parseFrom(raw.bodyBytes)
        // Exactly the canonical encoding of what it decodes to (the wallet's AuthInfo proto has no tip field at all).
        require(raw.toByteArray().contentEquals(txBytes)) { "tx bytes are not canonical" }
        require(body.toByteArray().contentEquals(raw.bodyBytes.toByteArray())) { "body bytes are not canonical" }
        require(auth.toByteArray().contentEquals(raw.authInfoBytes.toByteArray())) { "auth info bytes are not canonical" }
        require(m.toByteArray().contentEquals(body.getMessages(0).value.toByteArray())) { "msg bytes are not canonical" }
        // Every action's output ciphertext exactly 217 bytes, dummies included.
        for (b in PrivateMsgs.bundles(m)) for (a in b.actionsList) require(a.ciphertext.size() == NoteCipher.CIPHERTEXT_BYTES) { "action ciphertext ${a.ciphertext.size()} bytes" }
        // Stake proofs: every field 32 bytes, a 201-byte ciphertext exactly for a non-zero commitment.
        PrivateMsgs.stake(m)?.let { p ->
            require(p.nullifiersCount == 2) { "a stake proof carries exactly two nullifiers" }
            for (b in p.nullifiersList + listOf(p.anchor, p.ownerTag, p.commitment, p.creditNullifier, p.creditCommitment, p.debtRoot)) require(b.size() == 32) { "a stake field of ${b.size()} bytes" }
            for ((cm, ct) in listOf(p.commitment to p.ciphertext, p.creditCommitment to p.creditCiphertext)) {
                require(if (f(cm).isZero) ct.isEmpty else ct.size() == NoteCipher.STAKE_CIPHERTEXT_BYTES) { "stake ciphertext: ${ct.size()} bytes" }
            }
            require((p.clearBefore == 0L) == f(p.debtRoot).isZero) { "debt_root is zero when clear_before is 0 (the proof clears no label)" }
        }
        // The tx fields every private sighash binds (the ante records them).
        val txf = PrivateMsgs.TxFields(body.memo, body.timeoutHeight, auth.fee.gasLimit)
        // timeout_height: the block being built must not be past it (0: none).
        require(body.timeoutHeight == 0L || height <= body.timeoutHeight) { "tx timed out (timeout_height ${body.timeoutHeight}, block $height)" }
        if (requireTimeout && !simulate) require(body.timeoutHeight > 0) { "a private tx without timeout_height" }
        lastTimeoutHeight = body.timeoutHeight
        val total = PrivateMsgs.totalFee(m)
        require(auth.fee.amountCount == 1 && auth.fee.getAmount(0).denom == "uerth" && auth.fee.getAmount(0).amount == total.toString()) { "declared fee != msg fee" }
        require(total >= minFee) { "below min fee" }
        if (!simulate) require(BigDecimal(total) >= price.multiply(BigDecimal(auth.fee.gasLimit))) { "below min gas price" }
        // A private tx's gas_limit is at most 5x the gas it uses.
        if (!simulate) {
            val used = gasOf(m)
            require(auth.fee.gasLimit <= 5 * used) { "gas limit ${auth.fee.gasLimit} exceeds what this private tx uses (${used})" }
            gasRatios.add(auth.fee.gasLimit.toDouble() / used)
            lastGasLimit = auth.fee.gasLimit
        }
        val bundles = PrivateMsgs.bundles(m)
        require(bundles.size in 1..2) { "bundle count" }
        val rem = remainders(m)
        checkRelease(m, rem)
        val sighash = PrivateMsgs.sighash(m, chainId, txf)
        val seen = HashSet<Fr>()
        bundles.forEachIndexed { i, b -> checkBundle(i, b, sighash, simulate, seen) }

        val stake = PrivateMsgs.stake(m)
        if (stake != null) {
            require(stake.proof.size() == PROOF_BYTES) { "a stake proof is exactly $PROOF_BYTES bytes" }
            stakeShape(m, stake)
            val nfs = spent(stake)
            require(nfs.none { it in stakeNullifiers }) { "stake nullifier spent" }
            require(nfs.toSet().size == nfs.size) { "duplicate stake nullifier" }
            if (nfs.isNotEmpty()) require(f(stake.anchor) in stakeRoots) { "unknown stake anchor" }
            // Every stake proof names the current clear_before (within an hour below it) and debt root (the chain's checkStakeClear).
            val cb = clearBefore()
            if (cb == 0L) {
                require(stake.clearBefore == 0L) { "clear_before must be 0 while the block time is within the label window" }
            } else {
                val lo = if (cb > CLEAR_BEFORE_SLACK) cb - CLEAR_BEFORE_SLACK else 1L
                require(stake.clearBefore in lo..cb) { "clear_before ${stake.clearBefore} is not within [$lo, $cb] (code 1113)" }
                require(f(stake.debtRoot) == debtRoot()) { "debt root is not the current slash debt root (a slash reached a redelegation since: re-prove)" }
            }
            if (!simulate) {
                val w = prover.stakes.removeFirstOrNull() ?: error("no stake proof")
                require(w.publicInputs() == ChainLayout.stakePublicInputs(stake, lanes(m), sighash)) { "stake proof is for other public inputs" }
            }
        }
        if (m is MsgStakeVote) {
            require(m.proof.size() == PROOF_BYTES) { "a vote proof is exactly $PROOF_BYTES bytes" }
            val snap = snapshots[m.proposalId] ?: error("no open snapshot for proposal ${m.proposalId}")
            require(m.debtRoot.size() == 32 && f(m.debtRoot) == debtRoot()) { "debt root is not the current slash debt root (a slash reached a redelegation since: re-prove)" }
            if (!simulate) {
                val w = prover.votes.removeFirstOrNull() ?: error("no vote proof")
                require(w.publicInputs() == ChainLayout.votePublicInputs(m, snap.root, snap.nfRoot, sighash)) { "vote proof is for other public inputs" }
            }
        }
        val membership = membershipOf(m)
        if (membership != null) {
            require(Fr.fromBytes(membership.root.toByteArray()) in identityRoots) { "unknown identity anchor" }
            membershipStatement(m)
            require(membership.proof.size() == PROOF_BYTES) { "a membership proof is exactly $PROOF_BYTES bytes" }
            if (!simulate) {
                val w = prover.memberships.removeFirstOrNull() ?: error("no membership proof")
                val (scope, maxAct, maxPred) = membershipStatement(m)!!
                val expect = listOf(Fr.fromBytes(membership.root.toByteArray()), scope, Fr.fromBytes(membership.nullifier.toByteArray()),
                    sighash, Fr.ZERO, Fr.ZERO, Privacy.u64(maxAct), Privacy.u64(maxPred))
                require(w.publicInputs() == expect) { "membership proof is for other public inputs" }
            }
        }
        PrivateMsgs.move(m)?.let { mv ->
            require(f(mv.root) in identityRoots) { "unknown identity anchor" }
            val scope = moveStatement(m)!!
            if (!simulate) {
                val w = prover.moves.removeFirstOrNull() ?: error("no move proof")
                val expect = listOf(f(mv.root), scope, f(mv.oldNullifier), f(mv.newNullifier), sighash)
                require(w.publicInputs() == expect) { "invalid move proof (code 1129): it is for other public inputs" }
                // What the circuit proves of the tree: a succession leaf and the successor's live leaf at that root.
                require(successions.values.any { (a, b) -> a == Privacy.idc(w.oldSecret) && b == Privacy.idc(w.newSecret) }) { "invalid move proof (code 1129): not a succession" }
            }
        }
        precheck(m, rem)
        if (simulate) return m to emptyList()
        actionCounts.add(bundles.sumOf { it.actionsCount })
        // Execute: spend, append, pay the fee and any unshield, then the action.
        for (b in bundles) for (a in b.actionsList) {
            nullifiers[f(a.nullifier)] = height
            val cm = f(a.commitment)
            val pos = noteTree.append(cm)
            notes.add(NoteRow(pos, height, cm, a.ciphertext.toByteArray(), null))
        }
        val stakePositions = ArrayList<Long>()
        if (stake != null) {
            spent(stake).forEach { stakeNullifiers[it] = height; stakeNfValues.add(it) }
            for ((cm, ct) in listOf(stake.commitment to stake.ciphertext, stake.creditCommitment to stake.creditCiphertext)) {
                val c = f(cm)
                if (c.isZero) continue
                val pos = stakeTree.append(c)
                stakeRows.add(StakeNoteRow(pos, height, c, ct.toByteArray()))
                stakePositions.add(pos)
            }
        }
        val events = ArrayList<Pair<String, Map<String, String>>>()
        when (m) {
            is MsgSend -> if (rem.isNotEmpty()) rem.forEach { (d, v) -> unshielded.getOrPut(m.receiver) { HashMap() }.merge(d, v, Long::plus) }
            is MsgRegister -> {
                val binding = PrivateMsgs.decimalField(m.publicSignalsList[1])
                require(binding == PrivateMsgs.registrationBinding(m, chainId)) { "binding" }
                // A landed binding is never used again.
                require(usedBindings.add(binding)) { "binding already used (ErrBindingUsed)" }
                usedIdcs.add(f(m.idc))
                val dsc = PrivateMsgs.decimalField(m.publicSignalsList[3])
                // A switch: the holder's old leaf is zeroed, the new one appended.
                val live = registeredIdc.entries.filter { it.value == f(m.idc) || passportOf[it.key] == m.publicSignalsList[2] }
                val switched = live.isNotEmpty()
                live.forEach { zeroLeaf(it.key) }
                // predecessor_at: the switch or re-entry that made this leaf, 0 for a passport never seen.
                val pred = if (switched || m.publicSignalsList[2] in passportsSeen) now else 0L
                passportsSeen.add(m.publicSignalsList[2])
                val idx = identityTree.append(Privacy.identityLeaf(f(m.idc), dsc, Privacy.countryField(registrationCountry), now, pred))
                predecessorOf[idx] = pred
                identityRows.add(IdentityRow(idx, height, identityTree.leaf(idx), null, now))
                registeredIdc[idx] = f(m.idc); passportOf[idx] = m.publicSignalsList[2]
                // The succession from the passport's last identity, right after the new leaf (never zeroed).
                passportLastIdc.put(m.publicSignalsList[2], f(m.idc))?.takeIf { it != f(m.idc) }?.let { old ->
                    val si = identityTree.append(Privacy.successionLeaf(old, f(m.idc)))
                    identityRows.add(IdentityRow(si, height, identityTree.leaf(si), null, now))
                    successions[si] = old to f(m.idc)
                }
                mint("uanml", 1_000_000, f(m.pcAnml), m.ciphertextAnml.toByteArray())
                if (!switched) {
                    mint("uerth", 5_000_000, f(m.pcErth), m.ciphertextErth.toByteArray())
                    // The referrer's half: the chain's own note to the handle's address, its
                    // opening derived from the passport nullifier and the leaf (ReferralOpening).
                    if (m.affiliateHandle.isNotEmpty()) {
                        val to = network.erth.wallet.privacy.keys.ShieldedAddress.decode(handles.getValue(m.affiliateHandle).address)
                        val (rho, rcm) = Privacy.referralOpening(Fr.of(java.math.BigInteger(m.publicSignalsList[2])), idx)
                        val pos = mintOpen("uerth", 5_000_000, to.ownerPk, rho, rcm)
                        referralNotes.add(m.affiliateHandle to Privacy.pc(to.ownerPk, rho, rcm))
                        referralPositions.add(pos)
                    }
                }
                events.add("register" to mapOf("leaf_index" to idx.toString(), "switched" to switched.toString()))
            }
            is MsgClaimAnml -> mint("uanml", 1_000_000, f(m.pc), m.ciphertext.toByteArray())
            is MsgVoteProposal -> votes.add(m.proposalId to m.optionValue)
            is MsgDelegate -> {
                validators.add(m.validator)
                events.add("shieldedstaking_delegate" to mapOf("validator" to m.validator, "amount" to m.amount.toString(), "derth" to m.derth.toString()))
            }
            is MsgRestake -> {}
            is MsgRedelegate -> {
                val key = f(m.stake.creditNullifier)
                validators.add(m.srcValidator); validators.add(m.dstValidator)
                moves[key] = Move(key, m.srcValidator, m.dstValidator, m.moveTime, m.dstDerth)
                events.add("shieldedstaking_redelegate" to mapOf("src_validator" to m.srcValidator, "dst_validator" to m.dstValidator,
                    "derth" to m.amount.toString(), "credited" to m.dstDerth.toString(), "move_key" to key.toHex(), "move_time" to m.moveTime.toString()))
            }
            is MsgUndelegate -> {
                // Booked at the live rate and queued: the chain pays it at maturity by itself.
                val id = nextPayoutId++
                val value = m.amount * 10 / 9
                unbondPayouts.add(Payout(id, m.validator, value, f(m.pc), m.ciphertext.toByteArray()))
                events.add("shieldedstaking_undelegate" to mapOf("validator" to m.validator, "derth" to m.amount.toString(),
                    "value" to value.toString(), "epoch" to epoch.toString(), "payout_id" to id.toString()))
            }
            is MsgStakeVote -> {
                val vs = m.voteNullifiersList.map(::f)
                vs.forEach { voteNullifiers.add(m.proposalId to it) }
                stakeVotes.add(Triple(m.proposalId, m.validator, m.weight))
                // How many notes it voted is not on chain: the proof's witness says.
                stakeVoteSlots.add(prover.allVotes.last().slots.size)
                events.add("shieldedstaking_stake_vote" to mapOf("vote_nullifiers" to m.voteNullifiersList.joinToString(",") { f(it).toHex() }))
            }
            is MsgNoteSwap -> {
                val (denomIn, amountIn) = rem.entries.single()
                val out = swapOut(denomIn, amountIn, m.denomOut)
                if (m.denomOut == "uerth") { poolAnml += BigInteger.valueOf(amountIn); poolErth -= BigInteger.valueOf(out) }
                else { poolErth += BigInteger.valueOf(amountIn); poolAnml -= BigInteger.valueOf(out) }
                mint(m.denomOut, out, f(m.pc), m.ciphertext.toByteArray())
            }
            is MsgAddLiquidityShielded -> {
                val e = rem.getValue("uerth"); val t = rem.getValue("uanml")
                val sh = shares(e, t)
                val depE = sh * poolErth / lpSupply
                val depT = sh * poolAnml / lpSupply
                poolErth += depE; poolAnml += depT; lpSupply += sh
                mint("dexlp/1", sh.toLong(), f(m.sharePc), m.shareCiphertext.toByteArray())
                val rE = e - depE.toLong()
                val rT = t - depT.toLong()
                if (rE > 0) mint("uerth", rE, f(m.refundPc), m.refundCiphertext.toByteArray())
                if (rT > 0) mint("uanml", rT, f(m.refundPc), m.refundCiphertext.toByteArray())
            }
            is MsgRemoveLiquidityShielded -> withdrawals.add(
                Withdrawal(BigInteger.valueOf(rem.getValue("dexlp/1")), f(m.erthPc), m.erthCiphertext.toByteArray(), f(m.tokenPc), m.tokenCiphertext.toByteArray()),
            )
            is MsgLockPosition -> {
                val id = nextPositionId++
                positions[id] = Pos(id, m.validator, m.amount, f(m.stake.ownerTag), m.splitsList.associate { it.optionId to it.percent }, height,
                    if (m.splitsCount > 0) now + groundworksLease else 0)
            }
            is MsgUpdatePosition -> positions.getValue(m.positionId).let { p ->
                p.splits = m.splitsList.associate { it.optionId to it.percent }
                p.splitExpiresAt = if (m.splitsCount > 0) now + groundworksLease else 0
            }
            is MsgUnlockPosition -> positions.remove(m.positionId)!!
            is MsgPositionVote -> positionVotes.add(m.positionId to m.proposalId)
            is MsgSetCaretaker -> {
                val n = f(m.membership.nullifier)
                if (m.percentagesCount == 0) { caretakerVotes.remove(n); caretakerExpiry.remove(n) }
                else { caretakerVotes[n] = m.percentagesList.associate { it.optionId to it.percent }; caretakerExpiry[n] = now + caretakerLease }
                events.add("set_caretaker" to mapOf("expires_at" to (forgeCaretakerExpiry ?: caretakerExpiry[n] ?: 0L).toString()))
            }
            is MsgMoveCaretaker -> {
                val n = f(m.move.oldNullifier); val o = f(m.move.newNullifier)
                caretakerVotes[o] = caretakerVotes.remove(n)!!; caretakerExpiry[o] = caretakerExpiry.remove(n)!!
                caretakerMovedOut.add(n)
                events.add("move_caretaker" to mapOf("nullifier" to o.toHex(), "previous_nullifier" to n.toHex(), "expires_at" to caretakerExpiry.getValue(o).toString()))
            }
            is MsgBindHandle -> {
                val n = f(m.membership.nullifier)
                val cur = handleOf(n)
                if (m.handle.isEmpty()) { handles.remove(cur!!.handle) }
                else {
                    // A change frees the old handle at once; a renewal (or a claim) leases now + handle_lease_seconds.
                    if (cur != null && cur.handle != m.handle) handles.remove(cur.handle)
                    handles[m.handle] = HandleRec(m.handle, n, m.address, now + handleLease)
                    events.add("handle_bound" to mapOf("handle" to m.handle, "expires_at" to (now + handleLease).toString()))
                }
            }
            is MsgMoveHandle -> {
                val n = f(m.move.oldNullifier); val o = f(m.move.newNullifier)
                handles[m.handle] = handles.getValue(m.handle).copy(nullifier = o)
                handleMovedOut.add(n)
                events.add("handle_moved" to mapOf("handle" to m.handle, "nullifier" to o.toHex(), "owner" to o.toHex(), "previous_owner" to n.toHex()))
            }
            is MsgProposeRemoval -> removalBallots[m.optionId] = 100L + m.optionId
            is MsgVoteRemoval -> removalVotes.add(Triple(m.optionId, f(m.membership.nullifier), m.optionValue))
            else -> {}
        }
        return m to events
    }

    // ---- indexer ----

    override fun status() = IndexerStatus(
        chainId, height - 1, now, notes.size.toLong(), identityRows.size.toLong(), halted, genesis, "/privacy/$chainId/$genesis",
    )

    /** Every position/index cursor asked, as the backend's paging rule checks it (a misaligned one is a 400). */
    val misaligned = ArrayList<String>()

    private fun aligned(name: String, from: Long, limit: Int?): Int {
        val n = limit ?: 1000
        if (n !in setOf(100, 1000) && !anyPageSize) { misaligned.add("$name limit $n"); throw java.io.IOException("indexer /$name: 400 limit") }
        if (from % n != 0L) { misaligned.add("$name $from/$n"); throw java.io.IOException("indexer /$name: 400 from not aligned") }
        return n
    }

    /** Lets tests page with small sizes (the alignment rule still holds). */
    var anyPageSize = false

    override fun notes(fromPos: Long, limit: Int?): NotesPage {
        val n = aligned("notes", fromPos, limit)
        val rows = notes.drop(fromPos.toInt()).take(n)
        return NotesPage(rows, fromPos + rows.size, rows.size == n, height - 1)
    }

    override fun nullifiers(fromHeight: Long, limit: Int?): HeightPage<Fr> = heights(nullifiers, fromHeight)

    private fun heights(set: Map<Fr, Long>, fromHeight: Long): HeightPage<Fr> {
        val blocks = set.entries.filter { it.value >= fromHeight }.groupBy({ it.value }, { it.key }).toSortedMap().map { it.key to it.value }
        return HeightPage(blocks.filter { it.first < height }, height, false, height - 1)
    }

    /** Whether the indexer serves each identity row's block time (the fifth column); false: an indexer without it. */
    var identityRowTimes = true

    override fun identity(fromIndex: Long, limit: Int?): IdentityPage {
        val n = aligned("identity", fromIndex, limit)
        val rows = identityRows.drop(fromIndex.toInt()).take(n).map { if (identityRowTimes) it else it.copy(time = null) }
        return IdentityPage(rows, fromIndex + rows.size, identityRows.size.toLong(), height - 1)
    }

    override fun identityZeroed(fromHeight: Long, limit: Int?): HeightPage<Long> {
        val blocks = zeroed.filter { it.first >= fromHeight && it.first < height }.groupBy({ it.first }, { it.second }).toSortedMap().map { it.key to it.value }
        return HeightPage(blocks, height, false, height - 1)
    }

    override fun rootsLatest() = LatestRoots(
        if (noteTree.size == 0L) null else RootRecord(noteTree.root(), noteTree.size, noteRootHeights[noteTree.root()] ?: (height - 1), now),
        if (identityTree.size == 0L) null else RootRecord(identityTree.root(), identityTree.size, height - 1, now), height - 1,
        if (stakeTree.size == 0L) null else RootRecord(stakeTree.root(), stakeTree.size, height - 1, now),
    )

    // ---- the chain's own queries (LCD), for the wallet's root checks ----

    override fun noteRoot(root: Fr): NoteRootRecord? = noteRootSizes[root]?.let { NoteRootRecord(true, it, noteRootHeights[root], rootExpiresAt?.invoke(root)) }

    /** Query/Root's expires_at per root (null: not said, as before 203d3b2's wallets read it). */
    var rootExpiresAt: ((Fr) -> Long?)? = null

    override fun noteTree(height: Long?): TreeState = at(noteAt, height)

    /** Set to make the LCD say nothing of txs by hash. */
    var txLookupBlind = false

    override fun txStatus(hash: String): network.erth.wallet.privacy.sync.TxStatus? = when {
        txLookupBlind -> null
        txs[hash] == null -> network.erth.wallet.privacy.sync.TxStatus.MISSING
        txs[hash]!!.code != 0 -> network.erth.wallet.privacy.sync.TxStatus.FAILED
        else -> network.erth.wallet.privacy.sync.TxStatus.COMMITTED
    }

    override fun latestBlock(): network.erth.wallet.privacy.sync.ChainTip = network.erth.wallet.privacy.sync.ChainTip(latestHeight(), now)

    /** Drops every recorded note root but the latest (x/shielded prunes roots past its window). */
    fun pruneNoteRoots() {
        val keep = noteTree.root()
        noteRootSizes.keys.retainAll(setOf(keep))
    }

    /** Set to make the node answer a pinned query at another height than asked (echo differs). */
    var echoOtherHeight = false

    private fun at(m: java.util.TreeMap<Long, TreeState>, height: Long?): TreeState {
        if (height != null && echoOtherHeight) return (m.lastEntry()?.value ?: TreeState(0, null)).copy(pinned = false)
        return (if (height == null) m.lastEntry() else m.floorEntry(height))?.value ?: TreeState(0, null)
    }

    override fun identityTree(height: Long?): TreeState = at(identityAt, height)

    override fun stakeTree(height: Long?): TreeState = at(stakeAt, height)

    override fun nullifierSpent(nf: Fr): Boolean = nf in nullifiers

    override fun stakeNullifierSpent(nf: Fr): Boolean = nf in stakeNullifiers

    /** Every validator a private delegation or move named (x/staking's validators, as the LCD lists them). */
    val validators = LinkedHashSet<String>()

    override fun validatorOperators(): List<String> = validators.toList()

    /** The chain's tip as the LCD reports it; tests move it ahead of the indexer. */
    var tipAhead = 0L

    override fun latestHeight(): Long = height - 1 + tipAhead

    var blockTimesPruned = false

    /** Every height whose block time the LCD was asked for, in order. */
    val blockTimeAsks = ArrayList<Long>()

    override fun blockTime(height: Long): Long? {
        blockTimeAsks.add(height)
        return if (blockTimesPruned) null else blockTimes[height]
    }

    /** What the LCD says block 1's hash prefix is (null: the indexer's [genesis]); [lcdBlind]: it cannot say. */
    var lcdGenesis: String? = null
    var lcdBlind = false

    override fun chainIdentity(): ChainIdentity? = if (lcdBlind) null else ChainIdentity(chainId, lcdGenesis ?: genesis)

    // ---- registrations ----

    val registeredIdc = HashMap<Long, Fr>()
    val usedBindings = HashSet<Fr>()
    /** Every idc ever registered (genesis used_idcs). */
    val usedIdcs = HashSet<Fr>()
    val passportOf = HashMap<Long, String>()
    /** (height, leaf index) of every zeroing. */
    val zeroed = ArrayList<Pair<Long, Long>>()

    /** [idc]'s registration ends (its lease lapsed): the chain's sweep zeroes its leaf, in the next block. */
    fun lapse(idc: Fr) {
        registeredIdc.entries.filter { it.value == idc }.map { it.key }.forEach { zeroLeaf(it) }
    }

    private fun zeroLeaf(index: Long) {
        if (identityTree.leaf(index) == Fr.ZERO) return
        identityTree.update(index, Fr.ZERO)
        val r = identityRows[index.toInt()]
        identityRows[index.toInt()] = r.copy(zeroedHeight = height)
        zeroed.add(height to index)
        registeredIdc.remove(index)
    }


    override fun stakeNotes(fromPos: Long, limit: Int?): StakeNotesPage {
        val n = aligned("stake/notes", fromPos, limit)
        val rows = stakeRows.drop(fromPos.toInt()).take(n)
        return StakeNotesPage(rows, fromPos + rows.size, rows.size == n, height - 1)
    }

    override fun stakeNullifiers(fromHeight: Long, limit: Int?): HeightPage<Fr> = heights(stakeNullifiers, fromHeight)

    override fun stakeNullifierLeaves(fromIndex: Long, limit: Int?): StakeNfLeavesPage {
        if (!indexerNfTree) throw IndexerBaseMoved("no /stake/nullifier-tree (test)")
        val n = aligned("stake/nullifier-tree", fromIndex, limit)
        // Page k is leaf indexes [k*n, (k+1)*n); leaf 0 (the sentinel) is never a row.
        val rows = (maxOf(fromIndex, 1L) until fromIndex + n).takeWhile { it <= stakeNfValues.size }.map { it to stakeNfValues[(it - 1).toInt()] }
        val full = fromIndex + n <= stakeNfValues.size + 1L
        return StakeNfLeavesPage(rows, if (full) fromIndex + n else (rows.lastOrNull()?.first?.plus(1) ?: maxOf(fromIndex, 1L)), full,
            if (stakeNfValues.isEmpty()) 0 else stakeNfValues.size + 1L, height - 1)
    }

    override fun stakeSnapshots(fromHeight: Long, limit: Int?): StakeSnapshotsPage {
        if (!indexerSnapshots) throw IndexerBaseMoved("no /stake/snapshots (test)")
        val rows = snapshots.values.filter { it.height in fromHeight until height }
            .map { StakeSnapshotRow(it.height, it.proposalId, it.root, it.treeSize, it.nfRoot, it.nfSize) }
        return StakeSnapshotsPage(rows, maxOf(fromHeight, height), false, height - 1)
    }

    /** A directory entry as Query/Handles serves it now (released ones are gone: swept). */
    private fun entry(h: HandleRec): network.erth.wallet.privacy.handles.HandleEntry? {
        val renewalUntil = h.expiresAt + handleRenewal
        val status = when { now < h.expiresAt -> "live"; now < renewalUntil -> "renewal"; else -> return null }
        return network.erth.wallet.privacy.handles.HandleEntry(h.handle, h.address, status, h.expiresAt, renewalUntil, owner = ownerHex?.invoke(h) ?: h.nullifier.toHex())
    }

    private fun directory() = handles.values.mapNotNull(::entry)

    /** Query/Handles: handles after [start], at most [limit]. */
    fun handlesPage(start: String, limit: Int): network.erth.wallet.privacy.handles.HandleDirectory.Page {
        handleAsks.add("chain:$start")
        val page = directory().filter { it.handle > start }.take(minOf(limit, 1000))
        val more = directory().count { it.handle > start } > page.size
        return network.erth.wallet.privacy.handles.HandleDirectory.Page(page, if (more) page.last().handle else "")
    }

    /** Set to make a set_caretaker event report this expires_at (a hostile node). */
    var forgeCaretakerExpiry: Long? = null

    /** Set to make the backend's handle stream lie about an address (the chain check must catch it). */
    var forgeHandleAddress: String? = null

    override fun handles(fromIndex: Long, limit: Int): network.erth.wallet.privacy.handles.HandleDirectory.StreamPage {
        val n = aligned("handles", fromIndex, limit)
        handleAsks.add("indexer:$fromIndex")
        val all = directory().map { e -> forgeHandleAddress?.let { e.copy(address = it) } ?: e }
        val rows = all.drop(fromIndex.toInt()).take(n)
        return network.erth.wallet.privacy.handles.HandleDirectory.StreamPage(rows, height - 1, all.size.toLong(), fromIndex, fromIndex + n >= all.size)
    }

    /** The app's directory over this chain: the indexer's stream first, the chain's pages to check against. */
    fun handleDirectory() = network.erth.wallet.privacy.handles.HandleDirectory(::handlesPage, { f, l -> handles(f, l) }, now = { now })

    fun positionReads(): List<PrivacyChainReads.Position> =
        positions.values.map { PrivacyChainReads.Position(it.id, it.validator, it.derth, it.ownerTag, it.splits, it.createdHeight, it.splitExpiresAt) }

    fun unshieldedTo(receiver: String, denom: String = "uerth"): Long = unshielded[receiver]?.get(denom) ?: 0
}

/**
 * The chain's layout of the stake and vote circuits' public inputs
 * (x/shieldedstaking StakeProof.PublicInputs with each msg's StakeLanes, and
 * MsgStakeVote.VotePublicInputs), pinned to the chain's own output by
 * PrivateMsgsTest; FakeChain checks every witness against it.
 */
object ChainLayout {
    /** What the chain supplies a stake proof: lane A's denom, v_in, v_out; the credit lane's denom, cr_v_in, cr_move_time. */
    data class Lanes(val denom: String?, val vIn: Long, val vOut: Long, val crDenom: String? = null, val crVIn: Long = 0, val crMoveTime: Long = 0)

    /** [position] names an unlocked position's validator and derth (the keeper fills an unlock's lanes in). */
    fun lanes(m: MessageLite, position: (Long) -> Pair<String, Long> = { error("no position") }): Lanes = when (m) {
        is MsgDelegate -> Lanes(PrivacyWallet.derthDenom(m.validator), m.derth, 0)
        is MsgRestake -> Lanes(PrivacyWallet.derthDenom(m.validator), 0, 0)
        is MsgUndelegate -> Lanes(PrivacyWallet.derthDenom(m.validator), 0, m.amount)
        is MsgLockPosition -> Lanes(PrivacyWallet.derthDenom(m.validator), 0, m.amount)
        is MsgUnlockPosition -> position(m.positionId).let { (v, d) -> Lanes(PrivacyWallet.derthDenom(v), d, 0) }
        is MsgRedelegate -> Lanes(PrivacyWallet.derthDenom(m.srcValidator), 0, m.amount, PrivacyWallet.derthDenom(m.dstValidator), m.dstDerth, m.moveTime)
        else -> Lanes(null, 0, 0)
    }

    private fun f(b: ByteString): Fr = Fr.fromBytes(b.toByteArray())

    fun stakePublicInputs(p: StakeProof, l: Lanes, sighash: Fr): List<Fr> = listOf(
        f(p.anchor), l.denom?.let(Privacy::assetId) ?: Fr.ZERO, f(p.getNullifiers(0)), f(p.getNullifiers(1)),
        f(p.commitment), Privacy.u64(l.vIn), Privacy.u64(l.vOut), Privacy.u64(p.clearBefore), f(p.debtRoot),
        l.crDenom?.let(Privacy::assetId) ?: Fr.ZERO, f(p.creditNullifier), f(p.creditCommitment), Privacy.u64(l.crVIn),
        Privacy.u64(l.crMoveTime), f(p.ownerTag), sighash,
    )

    fun votePublicInputs(m: MsgStakeVote, noteRoot: Fr, nfRoot: Fr, sighash: Fr): List<Fr> =
        listOf(noteRoot, nfRoot, f(m.debtRoot), Privacy.assetId(PrivacyWallet.derthDenom(m.validator)), Privacy.u64(m.weight), Privacy.u64(m.proposalId)) +
            m.voteNullifiersList.map(::f) + listOf(sighash)
}

/**
 * With PRIVACY_TOML_OUT set, writes every witness [chain]'s prover saw as a
 * nargo Prover.toml (<dir>/{action,stake,membership,vote}/<test>_<i>/), for
 * `nargo execute` against the real circuits.
 */
fun dumpWitnesses(chain: FakeChain, test: String) {
    val out = System.getenv("PRIVACY_TOML_OUT") ?: return
    fun write(kind: String, i: Int, toml: String) =
        java.io.File(out, "$kind/${test}_$i/Prover.toml").apply { parentFile.mkdirs() }.writeText(toml)
    chain.prover.allActions.forEachIndexed { i, w -> write("action", i, w.proverToml()) }
    chain.prover.allStakes.forEachIndexed { i, w -> write("stake", i, w.proverToml()) }
    chain.prover.allMemberships.forEachIndexed { i, w -> write("membership", i, w.proverToml()) }
    chain.prover.allMoves.forEachIndexed { i, w -> write("move", i, w.proverToml()) }
    chain.prover.allVotes.forEachIndexed { i, w -> write("vote", i, w.proverToml()) }
}

/**
 * Checks each witness against its circuit's constraints (the Kotlin twin of
 * circuits/{action,stake,membership}/src/main.nr) and keeps it for the chain
 * to match against the tx and for a test to dump as Prover.toml.
 */
const val PROOF_BYTES = 14_656

class CheckingProver : Prover {
    val actions = ArrayDeque<ActionWitness>()
    val stakes = ArrayDeque<StakeWitness>()
    val memberships = ArrayDeque<MembershipWitness>()
    val votes = ArrayDeque<VoteWitness>()
    val allVotes = ArrayList<VoteWitness>()
    val allActions = ArrayList<ActionWitness>()
    val allStakes = ArrayList<StakeWitness>()
    val allMemberships = ArrayList<MembershipWitness>()
    val moves = ArrayDeque<MoveWitness>()
    val allMoves = ArrayList<MoveWitness>()

    override fun proveAction(w: ActionWitness): ByteArray {
        w.check()
        // The circuit's base-canonicality: both value bases' y <= (p-1)/2.
        require(Grumpkin.valueBase(w.sAsset).y.toBigInteger() <= Grumpkin.HALF_P)
        actions.add(w); allActions.add(w)
        return ByteArray(14_656) { 1 }
    }

    override fun proveStake(w: StakeWitness): ByteArray {
        w.check()
        stakes.add(w); allStakes.add(w)
        return ByteArray(14_656) { 3 }
    }

    override fun proveMembership(w: MembershipWitness): ByteArray {
        w.check()
        memberships.add(w); allMemberships.add(w)
        return ByteArray(14_656) { 2 }
    }

    override fun proveMove(w: MoveWitness): ByteArray {
        w.check()
        moves.add(w); allMoves.add(w)
        return ByteArray(14_656) { 5 }
    }

    override fun proveVote(w: VoteWitness): ByteArray {
        w.check()
        votes.add(w); allVotes.add(w)
        return ByteArray(14_656) { 4 }
    }
}
