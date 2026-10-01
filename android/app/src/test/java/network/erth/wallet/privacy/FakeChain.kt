package network.erth.wallet.privacy

import com.google.protobuf.MessageLite
import cosmos.tx.v1beta1.Tx
import network.erth.earth.proto.assembly.MsgVoteProposal
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
            else -> error("fake chain does not know ${any.typeUrl}")
        }
    }

    private fun transfersOf(m: MessageLite): List<Transfer> = when (m) {
        is MsgTransfer -> listOf(m.transfer)
        is MsgRegister -> listOf(m.fee)
        is MsgClaimAnml -> listOf(m.fee)
        is MsgVoteProposal -> listOf(m.fee)
        is MsgDelegate -> listOf(m.transfer)
        is MsgUndelegate -> listOf(m.transfer)
        is MsgStakeVote -> listOf(m.transfer, m.feeTransfer)
        else -> error("?")
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
            else -> null
        }
        if (membership != null) {
            require(Fr.fromBytes(membership.root.toByteArray()) in identityRoots) { "unknown identity anchor" }
            if (!simulate) {
                val w = prover.memberships.removeFirstOrNull() ?: error("no membership proof")
                val scope = when (m) {
                    is MsgClaimAnml -> Privacy.claimScope(m.day)
                    is MsgVoteProposal -> Privacy.proposalScope(m.proposalId, 0)
                    else -> error("?")
                }
                val maxAct = when (m) {
                    is MsgClaimAnml -> (m.day - 1) * 86_400
                    else -> now - 3600
                }
                val expect = listOf(Fr.fromBytes(membership.root.toByteArray()), scope, Fr.fromBytes(membership.nullifier.toByteArray()),
                    signal, Fr.ZERO, Fr.ZERO, Privacy.u64(maxAct))
                require(w.publicInputs() == expect) { "membership proof is for other public inputs" }
            }
        }
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
