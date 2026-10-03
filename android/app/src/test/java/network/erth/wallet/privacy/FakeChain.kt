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
import network.erth.earth.proto.personhood.MsgBindReferrer
import network.erth.earth.proto.personhood.MsgClaimAnml
import network.erth.earth.proto.personhood.MsgRegister
import network.erth.earth.proto.personhood.MsgSetCaretaker
import network.erth.earth.proto.shielded.Bundle
import network.erth.earth.proto.shielded.MsgSend
import network.erth.earth.proto.shieldedstaking.MsgClaimUnbonding
import network.erth.earth.proto.shieldedstaking.MsgDelegate
import network.erth.earth.proto.shieldedstaking.MsgLockPosition
import network.erth.earth.proto.shieldedstaking.MsgPositionVote
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
import network.erth.wallet.privacy.sync.RateRow
import network.erth.wallet.privacy.sync.RootRecord
import network.erth.wallet.privacy.sync.StakeNoteRow
import network.erth.wallet.privacy.sync.StakeNotesPage
import network.erth.wallet.privacy.sync.StakeNfLeavesPage
import network.erth.wallet.privacy.sync.StakeSnapshotRow
import network.erth.wallet.privacy.sync.StakeSnapshotsPage
import network.erth.wallet.privacy.prove.VoteWitness
import network.erth.wallet.privacy.zk.IndexedTree
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
    /** The stake nullifier indexed tree's values in insertion order (leaf i + 1), ORCHARD_DESIGN 15. */
    val stakeNfValues = ArrayList<Fr>()
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

    // x/shieldedstaking positions, x/personhood referrers, x/assembly removal ballots.
    data class Pos(val id: Long, val validator: String, val derth: Long, val ownerTag: Fr, var splits: Map<Long, Long>, val createdHeight: Long)
    val positions = LinkedHashMap<Long, Pos>()
    var nextPositionId = 1L
    val positionVotes = ArrayList<Pair<Long, Long>>()
    val referrers = HashMap<Fr, String>()
    val removalBallots = HashMap<Long, Long>()
    val removalVotes = ArrayList<Triple<Long, Fr, Int>>()
    val caretakerVotes = HashMap<Fr, Map<Long, Long>>()
    val claimedUnbonds = ArrayList<String>()
    /** The fake's epoch and derth rate (uerth per derth = 10/9 at delegation: 9/10 minted). */
    val epoch = 4L

    // ---- chain ----

    /** Ends the block being built: its roots become anchors. Writes land at [height], the block in progress. */
    private fun block() {
        noteRoots.add(noteTree.root()); identityRoots.add(identityTree.root())
        noteRootSizes[noteTree.root()] = noteTree.size
        if (stakeTree.size > 0) stakeRoots.add(stakeTree.root())
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

    fun mintStake(denom: String, amount: Long, spc: Fr, ct: ByteArray): Long {
        require(ct.size == NoteCipher.BLIND_CIPHERTEXT_BYTES) { "a minted stake note needs its blind stake ciphertext" }
        val cm = Privacy.stakeCm(Privacy.assetId(denom), amount, spc)
        val pos = stakeTree.append(cm)
        stakeRows.add(StakeNoteRow(pos, height, cm, ct, denom, amount, spc))
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
    override fun tipHeight(): Long = height - 1 + tipAhead

    /** The ante charges per bundle and per action, before anything else: gas is the tx's shape. */
    /** Every nullifier (pool, stake, membership) the node saw in a simulated tx. */
    val simulatedNullifiers = ArrayList<Fr>()

    override fun simulate(tx: ByteArray): Long {
        simulated++
        val m = check(tx, simulate = true).first
        PrivateMsgs.bundles(m).forEach { b -> b.actionsList.forEach { simulatedNullifiers.add(f(it.nullifier)) } }
        PrivateMsgs.stake(m)?.nullifiersList?.forEach { simulatedNullifiers.add(f(it)) }
        val actions = PrivateMsgs.bundles(m).sumOf { it.actionsCount }
        return 200_000L + 100_000L * PrivateMsgs.bundles(m).size + 350_000L * actions + (if (PrivateMsgs.stake(m) != null) 400_000 else 0)
    }

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
    /** Every tx by hash, as Query/GetTx answers. */
    val txs = HashMap<String, TxResult>()

    override fun tx(hash: String): TxResult? = txs[hash]

    override fun broadcast(tx: ByteArray, accepted: (hash: String) -> Unit): TxResult {
        if (rejectNext > 0) {
            // The proofs made for it never reach the chain.
            rejectNext--
            prover.actions.clear(); prover.stakes.clear(); prover.memberships.clear(); prover.votes.clear()
            throw java.io.IOException("broadcast refused (test)")
        }
        val hash = "HASH$height"
        if (dropNext > 0) {
            // Accepted by CheckTx, then never included (evicted from the mempool).
            dropNext--
            check(tx, simulate = true)
            accepted(hash)
            prover.actions.clear(); prover.stakes.clear(); prover.memberships.clear(); prover.votes.clear()
            throw java.io.IOException("tx not committed (test)")
        }
        if (failInBlockNext > 0) {
            failInBlockNext--
            check(tx, simulate = true)
            accepted(hash)
            prover.actions.clear(); prover.stakes.clear(); prover.memberships.clear(); prover.votes.clear()
            block()
            txs[hash] = TxResult(hash, height - 1, now, emptyList(), code = 5, log = "failed in block (test)")
            throw java.io.IOException("tx failed (code 5)")
        }
        check(tx, simulate = true)
        accepted(hash)
        val (_, events) = check(tx, simulate = false)
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
            PrivateMsgs.BIND_REFERRER -> MsgBindReferrer.parseFrom(any.value)
            PrivateMsgs.SET_CARETAKER -> MsgSetCaretaker.parseFrom(any.value)
            PrivateMsgs.PROPOSE_REMOVAL -> MsgProposeRemoval.parseFrom(any.value)
            PrivateMsgs.VOTE_REMOVAL -> MsgVoteRemoval.parseFrom(any.value)
            PrivateMsgs.CLAIM_UNBONDING -> MsgClaimUnbonding.parseFrom(any.value)
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
                // Wave 3 (B/F2): never to a module account.
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
            is MsgClaimUnbonding -> {
                only(null)
                require(m.ciphertext.size() == NoteCipher.BLIND_CIPHERTEXT_BYTES)
                // Exactly one way to pay: the bundle, or from the output.
                require((PrivateMsgs.privateFee(m) == 0L) != (m.feeFromOutput == 0L))
            }
            is MsgRegister -> {
                only(null)
                require(m.ciphertextAnml.size() == NoteCipher.BLIND_CIPHERTEXT_BYTES && m.ciphertextErth.size() == NoteCipher.BLIND_CIPHERTEXT_BYTES)
            }
            is MsgClaimAnml -> { only(null); require(m.ciphertext.size() == NoteCipher.BLIND_CIPHERTEXT_BYTES) }
            else -> only(null)
        }
        require(PrivateMsgs.totalFee(m) > 0)
    }

    /** Whether the msg has the chain mint a stake note to spc_mint (its blind stake ciphertext is then required). */
    private fun mintsStake(m: MessageLite) = m is MsgDelegate || m is MsgUndelegate || m is MsgUnlockPosition

    /** The stake proof's chain-supplied publics: asset, v_out. */
    private fun stakeStatement(m: MessageLite): Pair<String?, Long> = when (m) {
        is MsgDelegate -> PrivacyWallet.derthDenom(m.validator) to 0L
        is MsgRestake -> PrivacyWallet.derthDenom(m.validator) to 0L
        is MsgUndelegate -> PrivacyWallet.derthDenom(m.validator) to m.amount
        is MsgClaimUnbonding -> PrivacyWallet.unbondDenom(m.validator, m.epoch) to m.amount
        is MsgLockPosition -> PrivacyWallet.derthDenom(m.validator) to m.amount
        else -> null to 0L
    }

    private fun spent(p: StakeProof) = p.nullifiersList.map(::f).filter { !it.isZero }
    private fun created(p: StakeProof) = p.commitmentsList.withIndex().filter { !f(it.value).isZero }

    /** Shape per msg: (min spends, may create). */
    private fun stakeShape(m: MessageLite, p: StakeProof) {
        val (minSpends, creates) = when (m) {
            is MsgDelegate, is MsgUpdatePosition, is MsgUnlockPosition, is MsgPositionVote -> 0 to false
            else -> 1 to true
        }
        val n = spent(p).size
        require(if (minSpends == 0) n == 0 else n >= minSpends) { "stake proof spends $n" }
        require(creates || created(p).isEmpty()) { "stake proof creates" }
        if (m is MsgRestake) require(created(p).isNotEmpty())
    }

    /** A membership's expected scope and max_activation, per msg. */
    private fun membershipStatement(m: MessageLite): Pair<Fr, Long>? = when (m) {
        is MsgClaimAnml -> Privacy.claimScope(m.day) to (m.day - 1) * 86_400
        is MsgVoteProposal -> Privacy.proposalScope(m.proposalId, 0) to now - 3600
        // Wave 3 (L4/L5): a lease's max_activation is at most now - R - 86400 (R = 30 days here).
        is MsgSetCaretaker -> Privacy.caretakerScope() to m.maxActivation.also { require(it <= now - 31 * 86_400) { "max_activation past now - R - 1d" } }
        is MsgBindReferrer -> Privacy.referrerScope() to m.maxActivation.also { require(it <= now - 31 * 86_400) { "max_activation past now - R - 1d" } }
        is MsgProposeRemoval -> Privacy.proposeRemovalScope(m.optionId, now / 86_400) to now / 86_400 * 86_400 - 86_400
        is MsgVoteRemoval -> Privacy.removalScope(removalBallots.getValue(m.optionId)) to now - 3600
        else -> null
    }

    private fun membershipOf(m: MessageLite): Membership? = when (m) {
        is MsgClaimAnml -> m.membership
        is MsgVoteProposal -> m.membership
        is MsgSetCaretaker -> m.membership
        is MsgBindReferrer -> m.membership
        is MsgProposeRemoval -> m.membership
        is MsgVoteRemoval -> m.membership
        else -> null
    }

    private fun shares(erth: Long, anml: Long): BigInteger =
        minOf(BigInteger.valueOf(erth) * lpSupply / poolErth, BigInteger.valueOf(anml) * lpSupply / poolAnml)

    /** The action's own checks, before anything is written (atomic with the spend in the ante). */
    private fun precheck(m: MessageLite, rem: Map<String, Long>) {
        when (m) {
            is MsgNoteSwap -> {
                val (denomIn, amountIn) = rem.entries.single()
                val out = swapOut(denomIn, amountIn, m.denomOut)
                require(out >= m.minAmountOut) { "slippage: got $out, want >= ${m.minAmountOut}" }
            }
            is MsgAddLiquidityShielded -> if (m.minShares.isNotEmpty()) {
                require(shares(rem.getValue("uerth"), rem.getValue("uanml")) >= BigInteger(m.minShares)) { "below min_shares" }
            }
            is MsgUpdatePosition -> require(positions.getValue(m.positionId).ownerTag == f(m.stake.ownerTag)) { "not the position's owner" }
            is MsgUnlockPosition -> require(positions.getValue(m.positionId).ownerTag == f(m.stake.ownerTag)) { "not the position's owner" }
            is MsgPositionVote -> {
                require(positions.getValue(m.positionId).ownerTag == f(m.stake.ownerTag)) { "not the position's owner" }
                require(m.optionsList.all { it.weight == PrivateMsgs.legacyDec(it.weight) }) { "a vote weight is not canonical" }
            }
            is MsgVoteRemoval -> require(m.optionId in removalBallots) { "no open ballot" }
            is MsgProposeRemoval -> require(m.optionId !in removalBallots) { "ballot already open" }
            is MsgClaimUnbonding -> require((m.hasBundle()) == (m.feeFromOutput == 0L))
            // Wave 3 (F3): option weights only in their canonical LegacyDec form.
            is MsgStakeVote -> {
                require(m.optionsList.all { it.weight == PrivateMsgs.legacyDec(it.weight) }) { "a vote weight is not canonical" }
                val snap = snapshots[m.proposalId] ?: error("no open snapshot for proposal ${m.proposalId}")
                require(m.weight in 1..Long.MAX_VALUE) { "weight must be positive" }
                require(m.voteNullifier.size() == 32 && !f(m.voteNullifier).isZero) { "vote_nullifier" }
                require((m.proposalId to f(m.voteNullifier)) !in voteNullifiers) { "this stake note already voted on this proposal (code 1119)" }
                require(snap.nfSize >= 0)
            }
            // Wave 3 (L6): binding an address needs its owner's consent (cosmos secp256k1 over SHA-256).
            is MsgBindReferrer -> if (m.address.isEmpty()) {
                require(m.referrerPubKey.isEmpty && m.referrerSignature.isEmpty) { "clearing a binding carries no consent" }
            } else {
                require(m.referrerPubKey.size() == 33 && m.referrerSignature.size() == 64) { "no referrer consent" }
                val pub = m.referrerPubKey.toByteArray()
                require(network.erth.wallet.crypto.WalletCrypto.addressOfPubKey(pub) == m.address) { "consent key is not the address's" }
                val sig = m.referrerSignature.toByteArray()
                val r = java.math.BigInteger(1, sig.copyOfRange(0, 32)); val sv = java.math.BigInteger(1, sig.copyOfRange(32, 64))
                require(sv <= org.bitcoinj.core.ECKey.HALF_CURVE_ORDER) { "high-S consent" }
                val msg = PrivateMsgs.referrerConsentBytes(chainId, m.membership.nullifier.toByteArray(), network.erth.wallet.crypto.Bech32.decode(m.address))
                require(org.bitcoinj.core.ECKey.verify(org.bitcoinj.core.Sha256Hash.hash(msg), org.bitcoinj.core.ECKey.ECDSASignature(r, sv), pub)) { "bad referrer consent" }
            }
            is MsgRegister -> require(identityRows.none { it.leaf != Fr.ZERO && registeredIdc[it.index] == f(m.idc) }) { "a switch to the live idc is refused" }
            else -> {}
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
        // Round 2 (R7): exactly the canonical encoding of what it decodes to (the wallet's AuthInfo proto has no tip field at all).
        require(raw.toByteArray().contentEquals(txBytes)) { "tx bytes are not canonical" }
        require(body.toByteArray().contentEquals(raw.bodyBytes.toByteArray())) { "body bytes are not canonical" }
        require(auth.toByteArray().contentEquals(raw.authInfoBytes.toByteArray())) { "auth info bytes are not canonical" }
        require(m.toByteArray().contentEquals(body.getMessages(0).value.toByteArray())) { "msg bytes are not canonical" }
        // Every action's output ciphertext exactly 217 bytes, dummies included.
        for (b in PrivateMsgs.bundles(m)) for (a in b.actionsList) require(a.ciphertext.size() == NoteCipher.CIPHERTEXT_BYTES) { "action ciphertext ${a.ciphertext.size()} bytes" }
        // Stake proofs: exactly two ciphertext slots, empty iff the commitment is zero, else 153 bytes.
        PrivateMsgs.stake(m)?.let { p ->
            require(p.ciphertextsCount == 2) { "stake proof has ${p.ciphertextsCount} ciphertexts" }
            for (i in 0..1) {
                val n = p.getCiphertexts(i).size()
                require(if (f(p.getCommitments(i)).isZero) n == 0 else n == NoteCipher.STAKE_CIPHERTEXT_BYTES) { "stake ciphertext $i: $n bytes" }
            }
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
        val bundles = PrivateMsgs.bundles(m)
        require(bundles.size in (if (PrivateMsgs.feeFromOutput(m) > 0) 0 else 1)..2) { "bundle count" }
        val rem = remainders(m)
        checkRelease(m, rem)
        val sighash = PrivateMsgs.sighash(m, chainId, txf)
        val seen = HashSet<Fr>()
        bundles.forEachIndexed { i, b -> checkBundle(i, b, sighash, simulate, seen) }

        val stake = PrivateMsgs.stake(m)
        if (stake != null) {
            require(stake.nullifiersCount == 2 && stake.commitmentsCount == 2 && stake.ciphertextsCount <= 2)
            require(stake.proof.size() == PROOF_BYTES) { "a stake proof is exactly $PROOF_BYTES bytes" }
            if (mintsStake(m)) require(stake.spcCiphertext.size() == NoteCipher.BLIND_CIPHERTEXT_BYTES) { "spc_ciphertext required" }
            else require(stake.spcCiphertext.isEmpty) { "spc_ciphertext only for a msg that mints" }
            stakeShape(m, stake)
            val nfs = spent(stake)
            require(nfs.none { it in stakeNullifiers }) { "stake nullifier spent" }
            require(nfs.toSet().size == nfs.size)
            if (nfs.isNotEmpty()) require(f(stake.anchor) in stakeRoots) { "unknown stake anchor" }
            if (!simulate) {
                val w = prover.stakes.removeFirstOrNull() ?: error("no stake proof")
                val (denom, vOut) = stakeStatement(m)
                val expect = listOf(f(stake.anchor), denom?.let(Privacy::assetId) ?: Fr.ZERO) +
                    stake.nullifiersList.map(::f) + stake.commitmentsList.map(::f) +
                    listOf(Privacy.u64(0), Privacy.u64(vOut), f(stake.spcMint), f(stake.ownerTag), sighash)
                require(w.publicInputs() == expect) { "stake proof is for other public inputs" }
            }
        }
        if (m is MsgStakeVote) {
            require(m.proof.size() == PROOF_BYTES) { "a vote proof is exactly $PROOF_BYTES bytes" }
            val snap = snapshots[m.proposalId] ?: error("no open snapshot for proposal ${m.proposalId}")
            if (!simulate) {
                val w = prover.votes.removeFirstOrNull() ?: error("no vote proof")
                val expect = listOf(snap.root, snap.nfRoot, Privacy.assetId(PrivacyWallet.derthDenom(m.validator)),
                    Privacy.u64(m.weight), Privacy.u64(m.proposalId), f(m.voteNullifier), sighash)
                require(w.publicInputs() == expect) { "vote proof is for other public inputs" }
            }
        }
        val membership = membershipOf(m)
        if (membership != null) {
            require(Fr.fromBytes(membership.root.toByteArray()) in identityRoots) { "unknown identity anchor" }
            require(membership.proof.size() == PROOF_BYTES) { "a membership proof is exactly $PROOF_BYTES bytes" }
            if (!simulate) {
                val w = prover.memberships.removeFirstOrNull() ?: error("no membership proof")
                val (scope, maxAct) = membershipStatement(m)!!
                val expect = listOf(Fr.fromBytes(membership.root.toByteArray()), scope, Fr.fromBytes(membership.nullifier.toByteArray()),
                    sighash, Fr.ZERO, Fr.ZERO, Privacy.u64(maxAct))
                require(w.publicInputs() == expect) { "membership proof is for other public inputs" }
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
        if (stake != null) {
            spent(stake).forEach { stakeNullifiers[it] = height; stakeNfValues.add(it) }
            for ((i, cm) in created(stake)) {
                val c = f(cm)
                val pos = stakeTree.append(c)
                stakeRows.add(StakeNoteRow(pos, height, c, stake.getCiphertexts(i).toByteArray(), null, null, null))
            }
        }
        val events = ArrayList<Pair<String, Map<String, String>>>()
        val spcMint = stake?.let { f(it.spcMint) }
        when (m) {
            is MsgSend -> if (rem.isNotEmpty()) rem.forEach { (d, v) -> unshielded.getOrPut(m.receiver) { HashMap() }.merge(d, v, Long::plus) }
            is MsgRegister -> {
                val binding = PrivateMsgs.decimalField(m.publicSignalsList[1])
                require(binding == PrivateMsgs.registrationBinding(m)) { "binding" }
                // Round 2 (R1): a landed binding is never used again.
                require(usedBindings.add(binding)) { "binding already used (ErrBindingUsed)" }
                val dsc = PrivateMsgs.decimalField(m.publicSignalsList[3])
                // A switch: the holder's old leaf is zeroed, the new one appended.
                registeredIdc.entries.filter { it.value == f(m.idc) || passportOf[it.key] == m.publicSignalsList[2] }.forEach { zeroLeaf(it.key) }
                val idx = identityTree.append(Privacy.identityLeaf(f(m.idc), dsc, Privacy.countryField(registrationCountry), now))
                identityRows.add(IdentityRow(idx, height, identityTree.leaf(idx), null, now))
                registeredIdc[idx] = f(m.idc); passportOf[idx] = m.publicSignalsList[2]
                mint("uanml", 1_000_000, f(m.pcAnml), m.ciphertextAnml.toByteArray())
                mint("uerth", 5_000_000, f(m.pcErth), m.ciphertextErth.toByteArray())
                events.add("register" to mapOf("leaf_index" to idx.toString()))
            }
            is MsgClaimAnml -> mint("uanml", 1_000_000, f(m.pc), m.ciphertext.toByteArray())
            is MsgVoteProposal -> votes.add(m.proposalId to m.optionValue)
            is MsgDelegate -> mintStake(PrivacyWallet.derthDenom(m.validator), m.amount * 9 / 10, spcMint!!, stake.spcCiphertext.toByteArray())
            is MsgRestake -> {}
            is MsgUndelegate -> mintStake(PrivacyWallet.unbondDenom(m.validator, epoch), m.amount * 10 / 9, spcMint!!, stake.spcCiphertext.toByteArray())
            is MsgClaimUnbonding -> {
                claimedUnbonds.add(PrivacyWallet.unbondDenom(m.validator, m.epoch))
                mint("uerth", m.amount - m.feeFromOutput, f(m.pc), m.ciphertext.toByteArray())
            }
            is MsgStakeVote -> {
                voteNullifiers.add(m.proposalId to f(m.voteNullifier))
                stakeVotes.add(Triple(m.proposalId, m.validator, m.weight))
                events.add("shieldedstaking_stake_vote" to mapOf("vote_nullifier" to f(m.voteNullifier).toHex()))
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
                positions[id] = Pos(id, m.validator, m.amount, f(m.stake.ownerTag), m.splitsList.associate { it.optionId to it.percent }, height)
            }
            is MsgUpdatePosition -> positions.getValue(m.positionId).splits = m.splitsList.associate { it.optionId to it.percent }
            is MsgUnlockPosition -> {
                val p = positions.remove(m.positionId)!!
                mintStake(PrivacyWallet.derthDenom(p.validator), p.derth, spcMint!!, stake.spcCiphertext.toByteArray())
            }
            is MsgPositionVote -> positionVotes.add(m.positionId to m.proposalId)
            is MsgBindReferrer -> referrers[f(m.membership.nullifier)] = m.address
            is MsgSetCaretaker -> caretakerVotes[f(m.membership.nullifier)] = m.percentagesList.associate { it.optionId to it.percent }
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

    override fun notes(fromPos: Long, limit: Int?): NotesPage {
        val n = limit ?: 1000
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
        val rows = identityRows.drop(fromIndex.toInt()).map { if (identityRowTimes) it else it.copy(time = null) }
        return IdentityPage(rows, fromIndex + rows.size, identityRows.size.toLong(), height - 1)
    }

    override fun identityZeroed(fromHeight: Long, limit: Int?): HeightPage<Long> {
        val blocks = zeroed.filter { it.first >= fromHeight && it.first < height }.groupBy({ it.first }, { it.second }).toSortedMap().map { it.key to it.value }
        return HeightPage(blocks, height, false, height - 1)
    }

    override fun rootsLatest() = LatestRoots(
        if (noteTree.size == 0L) null else RootRecord(noteTree.root(), noteTree.size, height - 1, now),
        if (identityTree.size == 0L) null else RootRecord(identityTree.root(), identityTree.size, height - 1, now), height - 1,
        if (stakeTree.size == 0L) null else RootRecord(stakeTree.root(), stakeTree.size, height - 1, now),
    )

    // ---- the chain's own queries (LCD), for the wallet's root checks ----

    override fun noteRoot(root: Fr): NoteRootRecord? = noteRootSizes[root]?.let { NoteRootRecord(true, it) }

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
    val passportOf = HashMap<Long, String>()
    /** (height, leaf index) of every zeroing. */
    val zeroed = ArrayList<Pair<Long, Long>>()

    private fun zeroLeaf(index: Long) {
        if (identityTree.leaf(index) == Fr.ZERO) return
        identityTree.update(index, Fr.ZERO)
        val r = identityRows[index.toInt()]
        identityRows[index.toInt()] = r.copy(zeroedHeight = height)
        zeroed.add(height to index)
        registeredIdc.remove(index)
    }

    override fun rates(epoch: Long?): List<RateRow> = emptyList()

    override fun stakeNotes(fromPos: Long, limit: Int?): StakeNotesPage {
        val n = limit ?: 1000
        val rows = stakeRows.drop(fromPos.toInt()).take(n)
        return StakeNotesPage(rows, fromPos + rows.size, rows.size == n, height - 1)
    }

    override fun stakeNullifiers(fromHeight: Long, limit: Int?): HeightPage<Fr> = heights(stakeNullifiers, fromHeight)

    override fun stakeNullifierLeaves(fromIndex: Long, limit: Int?): StakeNfLeavesPage {
        if (!indexerNfTree) throw IndexerBaseMoved("no /stake/nullifier-tree (test)")
        val n = limit ?: 1000
        val from = maxOf(fromIndex, 1L)
        val rows = (from until from + n).takeWhile { it <= stakeNfValues.size }.map { it to stakeNfValues[(it - 1).toInt()] }
        return StakeNfLeavesPage(rows, rows.lastOrNull()?.first?.plus(1) ?: from, rows.size == n,
            if (stakeNfValues.isEmpty()) 0 else stakeNfValues.size + 1L, height - 1)
    }

    override fun stakeSnapshots(fromHeight: Long, limit: Int?): StakeSnapshotsPage {
        if (!indexerSnapshots) throw IndexerBaseMoved("no /stake/snapshots (test)")
        val rows = snapshots.values.filter { it.height in fromHeight until height }
            .map { StakeSnapshotRow(it.height, it.proposalId, it.root, it.treeSize, it.nfRoot, it.nfSize) }
        return StakeSnapshotsPage(rows, maxOf(fromHeight, height), false, height - 1)
    }

    fun positionReads(): List<PrivacyChainReads.Position> =
        positions.values.map { PrivacyChainReads.Position(it.id, it.validator, it.derth, it.ownerTag, it.splits, it.createdHeight) }

    fun unshieldedTo(receiver: String, denom: String = "uerth"): Long = unshielded[receiver]?.get(denom) ?: 0
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

    override fun proveVote(w: VoteWitness): ByteArray {
        w.check()
        votes.add(w); allVotes.add(w)
        return ByteArray(14_656) { 4 }
    }
}
