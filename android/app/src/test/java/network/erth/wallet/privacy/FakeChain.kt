package network.erth.wallet.privacy

import com.google.protobuf.MessageLite
import cosmos.tx.v1beta1.Tx
import network.erth.earth.proto.assembly.MsgProposeRemoval
import network.erth.earth.proto.assembly.MsgVoteProposal
import network.erth.earth.proto.assembly.MsgVoteRemoval
import network.erth.earth.proto.dex.MsgAddLiquidityShielded
import network.erth.earth.proto.dex.MsgNoteSwap
import network.erth.earth.proto.personhood.MsgBindReferrer
import network.erth.earth.proto.personhood.MsgSetCaretaker
import network.erth.earth.proto.shieldedstaking.MsgClaimUnbonding
import network.erth.earth.proto.shieldedstaking.MsgLockPosition
import network.erth.earth.proto.shieldedstaking.MsgPositionVote
import network.erth.earth.proto.shieldedstaking.MsgUnlockPosition
import network.erth.earth.proto.shieldedstaking.MsgUpdatePosition
import network.erth.wallet.chain.math.SwapMath
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.Sha256Hash
import java.math.BigInteger
import network.erth.earth.proto.personhood.MsgClaimAnml
import network.erth.earth.proto.personhood.MsgRegister
import network.erth.earth.proto.shielded.MsgTransfer
import network.erth.earth.proto.shielded.Transfer
import network.erth.earth.proto.shieldedstaking.MsgDelegate
import network.erth.earth.proto.shieldedstaking.MsgStakeVote
import network.erth.earth.proto.shieldedstaking.MsgUndelegate
import network.erth.wallet.privacy.prove.MembershipWitness
import network.erth.wallet.privacy.prove.TransferWitness
import network.erth.wallet.privacy.sync.HeightPage
import network.erth.wallet.privacy.sync.IdentityPage
import network.erth.wallet.privacy.sync.IdentityRow
import network.erth.wallet.privacy.sync.IndexerStatus
import network.erth.wallet.privacy.sync.LatestRoots
import network.erth.wallet.privacy.sync.NoteRow
import network.erth.wallet.privacy.sync.NotesPage
import network.erth.wallet.privacy.sync.PrivacyIndexer
import network.erth.wallet.privacy.sync.RateRow
import network.erth.wallet.privacy.sync.RootRecord
import network.erth.wallet.privacy.tx.PrivateChain
import network.erth.wallet.privacy.tx.PrivateMsgs
import network.erth.wallet.privacy.tx.Prover
import network.erth.wallet.privacy.tx.TxResult
import network.erth.wallet.privacy.zk.Fr
import network.erth.wallet.privacy.zk.MemNodeStore
import network.erth.wallet.privacy.zk.Merkle
import network.erth.wallet.privacy.zk.MerkleTree
import network.erth.wallet.privacy.zk.Privacy
import java.math.BigDecimal

/**
 * An in-memory model of the chain's private side, for driving a wallet end
 * to end without a node: the note and identity trees, the nullifier set, the
 * ante's checks a private tx must pass (shape, fee, anchors, nullifiers, the
 * signal and every proof's statement), and the mints each msg makes. Its
 * indexer serves the same streams the backend does. Proofs are not real:
 * [CheckingProver] checks each witness against the circuit's constraints
 * instead, and keeps it so a test can hand it to nargo.
 */
class FakeChain(val chainId: String = "earth-1", var now: Long = 1_790_000_000L) : PrivateChain, PrivacyIndexer {
    val notes = ArrayList<NoteRow>()
    val noteTree = MerkleTree(MemNodeStore())
    val identityTree = MerkleTree(MemNodeStore())
    val identityRows = ArrayList<IdentityRow>()
    val nullifiers = LinkedHashMap<Fr, Long>()
    val noteRoots = HashSet<Fr>().apply { add(noteTree.root()) }
    val identityRoots = HashSet<Fr>().apply { add(identityTree.root()) }
    var height = 1L
    val minFee = 1000L
    val price = BigDecimal("0.001")
    val prover = CheckingProver(this)
    val unshielded = HashMap<String, Long>()
    val votes = ArrayList<Pair<Long, Int>>()
    var simulated = 0

    // x/dex pool 1 (uanml/uerth) and its LP shares.
    var poolErth = BigInteger.valueOf(1_000_000_000_000)
    var poolAnml = BigInteger.valueOf(500_000_000_000)
    var lpSupply = BigInteger.valueOf(700_000_000_000)
    val swapFee = BigDecimal("0.3")
    val lpShares = HashMap<String, BigInteger>()

    // x/shieldedstaking positions, x/personhood referrers, x/assembly removal ballots.
    data class Pos(val id: Long, val validator: String, val derth: Long, val pubkey: ByteArray, var nonce: Long, var splits: Map<Long, Long>)
    val positions = LinkedHashMap<Long, Pos>()
    val positionVotes = ArrayList<Pair<Long, Long>>()
    val referrers = HashMap<Fr, String>()
    val removalBallots = HashMap<Long, Long>()
    val removalVotes = ArrayList<Triple<Long, Fr, Int>>()
    val caretakerVotes = HashMap<Fr, Map<Long, Long>>()
    val claimedUnbonds = ArrayList<String>()

    // ---- chain ----

    /** Ends the block being built: its roots become anchors. Writes land at [height], the block in progress. */
    private fun block() { noteRoots.add(noteTree.root()); identityRoots.add(identityTree.root()); height++ }

    fun mint(denom: String, value: Long, pc: Fr, ct: ByteArray = ByteArray(0)): Long {
        val cm = Privacy.cm(Privacy.assetId(denom), value, pc)
        val pos = noteTree.append(cm)
        notes.add(NoteRow(pos, height, cm, ct, "$value$denom"))
        return pos
    }

    fun shield(denom: String, value: Long, pc: Fr, ct: ByteArray = ByteArray(0)) { mint(denom, value, pc, ct); block() }

    override fun gasPrice(): BigDecimal = price
    override fun minFee(): Long = minFee

    override fun simulate(tx: ByteArray): Long { simulated++; check(tx, simulate = true); return 2_100_000 }

    override fun broadcast(tx: ByteArray): TxResult {
        val events = check(tx, simulate = false)
        block()
        return TxResult("HASH${height - 1}", height - 1, now, events)
    }

    private fun decode(raw: Tx.TxRaw): MessageLite {
        val body = Tx.TxBody.parseFrom(raw.bodyBytes)
        require(body.messagesCount == 1)
        val any = body.getMessages(0)
        return when (any.typeUrl) {
            PrivateMsgs.TRANSFER -> MsgTransfer.parseFrom(any.value)
            PrivateMsgs.REGISTER -> MsgRegister.parseFrom(any.value)
            PrivateMsgs.CLAIM_ANML -> MsgClaimAnml.parseFrom(any.value)
            PrivateMsgs.VOTE_PROPOSAL -> MsgVoteProposal.parseFrom(any.value)
            PrivateMsgs.DELEGATE -> MsgDelegate.parseFrom(any.value)
            PrivateMsgs.UNDELEGATE -> MsgUndelegate.parseFrom(any.value)
            PrivateMsgs.STAKE_VOTE -> MsgStakeVote.parseFrom(any.value)
            PrivateMsgs.NOTE_SWAP -> MsgNoteSwap.parseFrom(any.value)
            PrivateMsgs.ADD_LIQUIDITY_SHIELDED -> MsgAddLiquidityShielded.parseFrom(any.value)
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

    private fun transfersOf(m: MessageLite): List<Transfer> = PrivateMsgs.transfers(m)

    /** What the swap pays, as x/dex prices it (the wallet's SwapMath is pinned to amm.go separately). */
    private fun swapOut(denomIn: String, amountIn: Long, denomOut: String): Long =
        SwapMath.route(mapOf("uanml" to SwapMath.Reserves(poolErth, poolAnml)), "uerth", denomIn, BigInteger.valueOf(amountIn), denomOut, swapFee)
            ?.amountOut?.toLong() ?: error("no route")

    private fun positionSigOk(p: Pos, action: String, payload: ByteArray, sig: ByteArray): Boolean {
        val hash = Sha256Hash.of(PrivateMsgs.positionSignBytes(chainId, action, p.id, p.nonce, payload))
        val es = ECKey.ECDSASignature(BigInteger(1, sig.copyOfRange(0, 32)), BigInteger(1, sig.copyOfRange(32, 64)))
        return es.isCanonical && ECKey.fromPublicOnly(p.pubkey).verify(hash, es)
    }

    /** The action's own checks, before anything is written (the chain runs these in the ante, atomically with the spend). */
    private fun precheck(m: MessageLite) {
        when (m) {
            is MsgNoteSwap -> {
                val out = swapOut(m.transfer.denomOut, m.transfer.valueOut, m.denomOut)
                require(out >= m.minAmountOut) { "slippage: got $out, want >= ${m.minAmountOut}" }
                require(m.feeFromOutput == 0L || (m.denomOut == "uerth" && m.minAmountOut > m.feeFromOutput && m.transfer.fee == 0L))
            }
            is MsgAddLiquidityShielded -> {
                require(m.poolId == 1L && m.transfer.denomOut == "uanml" && m.erthTransfer.denomOut == "uerth")
                if (m.minShares.isNotEmpty()) require(shares(m) >= BigInteger(m.minShares)) { "below min_shares" }
            }
            is MsgUpdatePosition -> require(positionSigOk(positions.getValue(m.positionId), "update", PrivateMsgs.splitsBytes(m.splitsList), m.signature.toByteArray()))
            is MsgUnlockPosition -> require(positionSigOk(positions.getValue(m.positionId), "unlock", m.pc.toByteArray() + m.ciphertext.toByteArray(), m.signature.toByteArray()))
            is MsgPositionVote -> require(positionSigOk(positions.getValue(m.positionId), "vote",
                PrivateMsgs.positionVotePayload(m.proposalId, m.optionsList), m.signature.toByteArray()))
            is MsgVoteRemoval -> require(m.optionId in removalBallots) { "no open ballot" }
            is MsgProposeRemoval -> require(m.optionId !in removalBallots) { "ballot already open" }
            else -> {}
        }
    }

    private fun shares(m: MsgAddLiquidityShielded): BigInteger =
        minOf(BigInteger.valueOf(m.erthTransfer.valueOut) * lpSupply / poolErth, BigInteger.valueOf(m.transfer.valueOut) * lpSupply / poolAnml)

    /** A membership's expected scope and max_activation, per msg. */
    private fun membershipStatement(m: MessageLite): Pair<Fr, Long>? = when (m) {
        is MsgClaimAnml -> Privacy.claimScope(m.day) to (m.day - 1) * 86_400
        is MsgVoteProposal -> Privacy.proposalScope(m.proposalId, 0) to now - 3600
        is MsgSetCaretaker -> Privacy.caretakerScope() to m.maxActivation
        is MsgBindReferrer -> Privacy.referrerScope() to m.maxActivation
        is MsgProposeRemoval -> Privacy.proposeRemovalScope(m.optionId, now / 86_400) to now / 86_400 * 86_400 - 3600
        is MsgVoteRemoval -> Privacy.removalScope(removalBallots.getValue(m.optionId)) to now - 3600
        else -> null
    }

    /** The ante, then the handler. */
    private fun check(txBytes: ByteArray, simulate: Boolean): List<Pair<String, Map<String, String>>> {
        val raw = Tx.TxRaw.parseFrom(txBytes)
        require(raw.signaturesCount == 0) { "private txs are unsigned" }
        val auth = Tx.AuthInfo.parseFrom(raw.authInfoBytes)
        require(auth.signerInfosCount == 0)
        val m = decode(raw)
        val ts = transfersOf(m)
        val total = PrivateMsgs.totalFee(m)
        require(auth.fee.amountCount == 1 && auth.fee.getAmount(0).denom == "uerth" && auth.fee.getAmount(0).amount == total.toString()) { "declared fee != msg fee" }
        require(total >= minFee) { "below min fee" }
        if (!simulate) require(BigDecimal(total) >= price.multiply(BigDecimal(auth.fee.gasLimit))) { "below min gas price" }
        val signal = PrivateMsgs.signal(m, chainId)
        ts.forEachIndexed { i, t ->
            val root = Fr.fromBytes(t.root.toByteArray())
            require(root in noteRoots) { "unknown anchor" }
            for (nf in t.nullifiersList) require(Fr.fromBytes(nf.toByteArray()) !in nullifiers) { "nullifier spent" }
            require((t.valueOut == 0L) == (t.denomOut == ""))
            if (!simulate) {
                val w = prover.transfers.removeFirstOrNull() ?: error("no proof for transfer $i")
                val assetPub = if (t.valueOut > 0) Privacy.assetId(t.denomOut) else Fr.ZERO
                val expect = listOf(root) + t.nullifiersList.map { Fr.fromBytes(it.toByteArray()) } +
                    t.commitmentsList.map { Fr.fromBytes(it.toByteArray()) } +
                    listOf(Privacy.u64(t.fee), Privacy.u64(t.valueOut), assetPub, signal)
                require(w.publicInputs() == expect) { "transfer $i proof is for other public inputs" }
            }
        }
        val membership = when (m) {
            is MsgClaimAnml -> m.membership
            is MsgVoteProposal -> m.membership
            is MsgSetCaretaker -> m.membership
            is MsgBindReferrer -> m.membership
            is MsgProposeRemoval -> m.membership
            is MsgVoteRemoval -> m.membership
            else -> null
        }
        if (membership != null) {
            require(Fr.fromBytes(membership.root.toByteArray()) in identityRoots) { "unknown identity anchor" }
            if (!simulate) {
                val w = prover.memberships.removeFirstOrNull() ?: error("no membership proof")
                val (scope, maxAct) = membershipStatement(m)!!
                val expect = listOf(Fr.fromBytes(membership.root.toByteArray()), scope, Fr.fromBytes(membership.nullifier.toByteArray()),
                    signal, Fr.ZERO, Fr.ZERO, Privacy.u64(maxAct))
                require(w.publicInputs() == expect) { "membership proof is for other public inputs" }
            }
        }
        precheck(m)
        if (simulate) return emptyList()
        // Execute: spend, append, then the action.
        for (t in ts) {
            t.nullifiersList.forEach { nullifiers[Fr.fromBytes(it.toByteArray())] = height }
            t.commitmentsList.forEachIndexed { i, cm ->
                val pos = noteTree.append(Fr.fromBytes(cm.toByteArray()))
                notes.add(NoteRow(pos, height, Fr.fromBytes(cm.toByteArray()), t.getCiphertexts(i).toByteArray(), null))
            }
        }
        val events = ArrayList<Pair<String, Map<String, String>>>()
        when (m) {
            is MsgTransfer -> if (m.transfer.valueOut > 0) unshielded.merge(m.receiver, m.transfer.valueOut - m.feeFromOutput, Long::plus)
            is MsgRegister -> {
                val binding = PrivateMsgs.decimalField(m.publicSignalsList[1])
                require(binding == PrivateMsgs.registrationBinding(m)) { "binding" }
                val dsc = PrivateMsgs.decimalField(m.publicSignalsList[3])
                val idx = identityTree.append(Privacy.identityLeaf(Fr.fromBytes(m.idc.toByteArray()), dsc, Privacy.countryField("DE"), now))
                identityRows.add(IdentityRow(idx, height, identityTree.leaf(idx), null))
                mint("uanml", 1_000_000, Fr.fromBytes(m.pcAnml.toByteArray()), m.ciphertextAnml.toByteArray())
                mint("uerth", 5_000_000, Fr.fromBytes(m.pcErth.toByteArray()), m.ciphertextErth.toByteArray())
                events.add("register" to mapOf("leaf_index" to idx.toString()))
            }
            is MsgClaimAnml -> mint("uanml", 1_000_000, Fr.fromBytes(m.pc.toByteArray()), m.ciphertext.toByteArray())
            is MsgVoteProposal -> votes.add(m.proposalId to m.optionValue)
            is MsgDelegate -> mint("derth/${m.validator}", m.transfer.valueOut * 9 / 10, Fr.fromBytes(m.pc.toByteArray()), m.ciphertext.toByteArray())
            is MsgStakeVote -> { votes.add(m.proposalId to -1); mint(m.transfer.denomOut, m.transfer.valueOut, Fr.fromBytes(m.pc.toByteArray()), m.ciphertext.toByteArray()) }
            is MsgUndelegate -> mint(PrivacyWallet.unbondDenom(m.validator, 4), m.transfer.valueOut, Fr.fromBytes(m.pc.toByteArray()), m.ciphertext.toByteArray())
            is MsgClaimUnbonding -> {
                claimedUnbonds.add(m.transfer.denomOut)
                mint("uerth", m.transfer.valueOut - m.feeFromOutput, Fr.fromBytes(m.pc.toByteArray()), m.ciphertext.toByteArray())
            }
            is MsgNoteSwap -> {
                val inAmt = BigInteger.valueOf(m.transfer.valueOut)
                val out = swapOut(m.transfer.denomOut, m.transfer.valueOut, m.denomOut)
                if (m.denomOut == "uerth") { poolAnml += inAmt; poolErth -= BigInteger.valueOf(out) } else { poolErth += inAmt; poolAnml -= BigInteger.valueOf(out) }
                mint(m.denomOut, out - m.feeFromOutput, Fr.fromBytes(m.pc.toByteArray()), m.ciphertext.toByteArray())
            }
            is MsgAddLiquidityShielded -> {
                val sh = shares(m)
                val depE = sh * poolErth / lpSupply
                val depT = sh * poolAnml / lpSupply
                poolErth += depE; poolAnml += depT; lpSupply += sh
                lpShares.merge(m.provider, sh, BigInteger::add)
                val pc = Fr.fromBytes(m.refundPc.toByteArray())
                val rE = m.erthTransfer.valueOut - depE.toLong()
                val rT = m.transfer.valueOut - depT.toLong()
                if (rE > 0) mint("uerth", rE, pc, m.refundCiphertext.toByteArray())
                if (rT > 0) mint("uanml", rT, pc, m.refundCiphertext.toByteArray())
            }
            is MsgLockPosition -> {
                val id = positions.size + 1L
                positions[id] = Pos(id, m.validator, m.transfer.valueOut, m.pubkey.toByteArray(), 0, m.splitsList.associate { it.optionId to it.percent })
            }
            is MsgUpdatePosition -> positions.getValue(m.positionId).apply { splits = m.splitsList.associate { it.optionId to it.percent }; nonce++ }
            is MsgUnlockPosition -> {
                val p = positions.remove(m.positionId)!!
                mint(PrivacyWallet.derthDenom(p.validator), p.derth, Fr.fromBytes(m.pc.toByteArray()), m.ciphertext.toByteArray())
            }
            is MsgPositionVote -> { positions.getValue(m.positionId).nonce++; positionVotes.add(m.positionId to m.proposalId) }
            is MsgBindReferrer -> referrers[Fr.fromBytes(m.membership.nullifier.toByteArray())] = m.address
            is MsgSetCaretaker -> caretakerVotes[Fr.fromBytes(m.membership.nullifier.toByteArray())] = m.percentagesList.associate { it.optionId to it.percent }
            is MsgProposeRemoval -> removalBallots[m.optionId] = 100L + m.optionId
            is MsgVoteRemoval -> removalVotes.add(Triple(m.optionId, Fr.fromBytes(m.membership.nullifier.toByteArray()), m.optionValue))
            else -> {}
        }
        return events
    }

    // ---- indexer ----

    override fun status() = IndexerStatus(chainId, height - 1, now, notes.size.toLong(), identityRows.size.toLong(), null)

    override fun notes(fromPos: Long, limit: Int?): NotesPage {
        val n = limit ?: 1000
        val rows = notes.drop(fromPos.toInt()).take(n)
        return NotesPage(rows, fromPos + rows.size, rows.size == n, height - 1)
    }

    override fun nullifiers(fromHeight: Long, limit: Int?): HeightPage<Fr> {
        val blocks = nullifiers.entries.filter { it.value >= fromHeight }.groupBy({ it.value }, { it.key }).toSortedMap().map { it.key to it.value }
        return HeightPage(blocks.filter { it.first < height }, height, false, height - 1)
    }

    override fun identity(fromIndex: Long, limit: Int?): IdentityPage {
        val rows = identityRows.drop(fromIndex.toInt())
        return IdentityPage(rows, fromIndex + rows.size, identityRows.size.toLong(), height - 1)
    }

    override fun identityZeroed(fromHeight: Long, limit: Int?): HeightPage<Long> = HeightPage(emptyList(), height, false, height - 1)

    override fun rootsLatest() = LatestRoots(
        RootRecord(noteTree.root(), noteTree.size, height, now), RootRecord(identityTree.root(), identityTree.size, height, now), height - 1,
    )

    override fun rates(epoch: Long?): List<RateRow> = emptyList()

    fun positionReads(): List<PrivacyChainReads.Position> =
        positions.values.map { PrivacyChainReads.Position(it.id, it.validator, it.derth, it.pubkey, it.nonce, it.splits) }
}

/**
 * Checks each witness against the circuits' constraints (the Kotlin twin of
 * circuits/{transfer,membership}/src/main.nr) and keeps it for the chain to
 * match against the tx and for a test to dump as Prover.toml.
 */
class CheckingProver(private val chain: FakeChain) : Prover {
    val transfers = ArrayDeque<TransferWitness>()
    val memberships = ArrayDeque<MembershipWitness>()
    val allTransfers = ArrayList<TransferWitness>()
    val allMemberships = ArrayList<MembershipWitness>()

    override fun proveTransfer(w: TransferWitness): ByteArray {
        val opk = Privacy.ownerPk(w.nk)
        w.inputs.forEachIndexed { i, inp ->
            val cm = Privacy.cm(w.assets[i], inp.value, Privacy.pc(opk, inp.rho, inp.rcm))
            if (inp.value != 0L) require(Merkle.rootFromPath(cm, inp.position, inp.path) == w.root) { "input $i not in note tree" }
        }
        require(w.nullifiers.toSet().size == 3) { "duplicate nullifier" }
        require(w.vPubOut == 0L || w.assetPub == w.asset)
        transfers.add(w); allTransfers.add(w)
        return ByteArray(14_656) { 1 }
    }

    override fun proveMembership(w: MembershipWitness): ByteArray {
        w.check()
        memberships.add(w); allMemberships.add(w)
        return ByteArray(14_656) { 2 }
    }
}
