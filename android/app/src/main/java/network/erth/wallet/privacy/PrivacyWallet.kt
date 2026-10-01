package network.erth.wallet.privacy

import com.google.protobuf.ByteString
import cosmos.gov.v1.WeightedVoteOption
import network.erth.earth.proto.allocation.AllocationWeight
import network.erth.earth.proto.assembly.MsgProposeRemoval
import network.erth.earth.proto.assembly.MsgVoteProposal
import network.erth.earth.proto.assembly.MsgVoteRemoval
import network.erth.earth.proto.assembly.VoteOption
import network.erth.earth.proto.personhood.MsgBindReferrer
import network.erth.earth.proto.personhood.MsgClaimAnml
import network.erth.earth.proto.personhood.MsgRegister
import network.erth.earth.proto.personhood.MsgSetCaretaker
import network.erth.earth.proto.shielded.MsgTransfer
import network.erth.earth.proto.shieldedstaking.MsgClaimUnbonding
import network.erth.earth.proto.shieldedstaking.MsgDelegate
import network.erth.earth.proto.shieldedstaking.MsgLockPosition
import network.erth.earth.proto.shieldedstaking.MsgPositionVote
import network.erth.earth.proto.shieldedstaking.MsgStakeVote
import network.erth.earth.proto.shieldedstaking.MsgUndelegate
import network.erth.earth.proto.shieldedstaking.MsgUnlockPosition
import network.erth.earth.proto.shieldedstaking.MsgUpdatePosition
import network.erth.wallet.privacy.keys.PrivacyKeys
import network.erth.wallet.privacy.keys.ShieldedAddress
import network.erth.wallet.privacy.note.OwnedNote
import network.erth.wallet.privacy.prove.MembershipWitness
import network.erth.wallet.privacy.sync.IdentityRecord
import network.erth.wallet.privacy.sync.PrivacyIndexer
import network.erth.wallet.privacy.sync.PrivacyStore
import network.erth.wallet.privacy.sync.WalletSync
import network.erth.wallet.privacy.tx.Assembled
import network.erth.wallet.privacy.tx.MembershipWitnessSpec
import network.erth.wallet.privacy.tx.NoteOut
import network.erth.wallet.privacy.tx.NoteSelection
import network.erth.wallet.privacy.tx.PrivateChain
import network.erth.wallet.privacy.tx.PrivateMsgs
import network.erth.wallet.privacy.tx.PrivateTxEngine
import network.erth.wallet.privacy.tx.Prover
import network.erth.wallet.privacy.tx.TransferPlan
import network.erth.wallet.privacy.tx.TxResult
import network.erth.wallet.privacy.zk.Fr
import network.erth.wallet.privacy.zk.Privacy
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.Sha256Hash

/** The chain reads the wallet's private msgs are built from. */
interface PrivacyChainReads {
    data class PersonhoodParams(val caretakerVoteSeconds: Long, val identityRootWindowSeconds: Long)
    data class BallotInputs(val scope: Fr, val excludedDsc: Fr, val excludedCountry: Fr, val maxActivation: Long, val round: Long, val ballotId: Long)
    data class Snapshot(val root: Fr, val treeSize: Long)
    data class Position(val id: Long, val validator: String, val derth: Long, val pubkey: ByteArray, val nonce: Long)

    fun personhoodParams(): PersonhoodParams
    fun ballotInputs(proposalId: Long = 0, optionId: Long = 0): BallotInputs
    fun epochNumber(): Long
    fun snapshot(proposalId: Long): Snapshot
    fun positions(): List<Position>
}

/**
 * One wallet's private side: its notes and registration, kept in sync with
 * the chain through the indexer, and every private action the chain offers,
 * built, proven and broadcast as an unsigned tx whose fee comes out of a
 * shielded ERTH note.
 *
 * Blocking throughout: call from an IO thread.
 */
class PrivacyWallet(
    val keys: PrivacyKeys,
    val store: PrivacyStore,
    private val indexer: PrivacyIndexer,
    private val chain: PrivateChain,
    private val reads: PrivacyChainReads,
    prover: Prover,
    val chainId: String,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val engine = PrivateTxEngine(chainId, chain, prover)

    val address: ShieldedAddress get() = keys.address

    @Synchronized
    fun sync(): WalletSync.Result = WalletSync(indexer, store, keys, chainId).sync()

    fun identityStatus(): WalletSync.IdentityStatus = WalletSync(indexer, store, keys, chainId).identityStatus()

    val notes: List<OwnedNote> get() = store.state.notes.toList()

    /** Spendable balance per denom (pending spends excluded). */
    fun balances(): Map<String, Long> =
        store.state.notes.filter { it.unspent && it.pendingAt == null }
            .groupBy { it.note.denom }.mapValues { (_, ns) -> ns.sumOf { it.note.value } }

    /** Allocates the next self-mint pc (see PrivacyKeys.mintSecrets). */
    @Synchronized
    private fun mint(denom: String): NoteOut {
        val c = store.state.nextMintCounter++
        store.save()
        return NoteOut.mintToSelf(keys, denom, c)
    }

    private fun today(): Long = now() / SECONDS_PER_DAY

    // ---- running ------------------------------------------------------------

    @Synchronized
    private fun run(assemble: (fee: Long) -> Assembled): TxResult {
        val (result, a) = engine.run(assemble)
        markPending(a.transfers.flatMap { it.spends })
        return result
    }

    /** What [run] would charge, without proving: for the confirm sheet. */
    fun quote(assemble: (fee: Long) -> Assembled): PrivateTxEngine.Quote = engine.quote(assemble)

    private fun markPending(spent: List<OwnedNote>) {
        val positions = spent.map { it.position }.toSet()
        val s = store.state
        for (i in s.notes.indices) if (s.notes[i].position in positions) s.notes[i] = s.notes[i].copy(pendingAt = now())
        store.save()
    }

    private fun feeOnly(fee: Long, exclude: Set<Long> = emptySet()): TransferPlan =
        TransferPlan.feeOnly(keys, store.noteTree, NoteSelection.feeNote(store.state.notes, fee, exclude), fee)

    /**
     * A transfer of [denom] whose fee is paid by a separate ERTH note: the fee
     * note is chosen first (the smallest that covers it), the inputs from the
     * rest.
     */
    private fun spendWithFee(denom: String, amount: Long, fee: Long, outputs: List<NoteOut>, vPubOut: Long): TransferPlan {
        val notes = store.state.notes
        val feeNote = NoteSelection.feeNote(notes, fee)
        val inputs = NoteSelection.inputs(notes, denom, amount, setOf(feeNote.position))
        return TransferPlan.build(keys, store.noteTree, denom, inputs, outputs, vPubOut, feeNote, fee)
    }

    // ---- membership ---------------------------------------------------------

    private fun identity(): IdentityRecord {
        val id = store.state.identity ?: throw IllegalStateException("this wallet has no registration")
        check(identityStatus() == WalletSync.IdentityStatus.LIVE) { "this wallet's registration is no longer live; register again" }
        return id
    }

    private fun membership(scope: Fr, excludedDsc: Fr, excludedCountry: Fr, maxActivation: Long): MembershipWitnessSpec {
        val id = identity()
        if (id.activatedAt > maxActivation) {
            throw NotYet(maxOf(0, id.activatedAt - maxActivation))
        }
        val tree = store.identityTree
        val path = tree.path(id.leafIndex)
        val root = tree.root()
        return MembershipWitnessSpec { signal ->
            MembershipWitness(
                idSecret = keys.idSecret, dscKey = id.dscKey, country = id.country, activatedAt = id.activatedAt,
                leafIndex = id.leafIndex, siblings = path, root = root, scope = scope, signal = signal,
                excludedDsc = excludedDsc, excludedCountry = excludedCountry, maxActivation = maxActivation,
            )
        }
    }

    /** The identity is too recent for this action; it opens [waitSeconds] from now. */
    class NotYet(val waitSeconds: Long) : Exception("this registration is too recent for this action; try again in ${waitSeconds / 3600 + 1}h")

    // ---- pool ---------------------------------------------------------------

    /** A private send of [amount] [denom] to [to]. */
    fun send(to: ShieldedAddress, denom: String, amount: Long, memo: ByteArray = ByteArray(0)): TxResult = run { fee ->
        val plan = spendWithFee(denom, amount, fee, listOf(NoteOut.to(to, denom, amount, memo)), 0)
        Assembled(listOf(plan), null) { ts, _ -> MsgTransfer.newBuilder().setTransfer(ts[0]).build() }
    }

    /**
     * Unshields so that [receiver] gets exactly [amount] [denom]. Its fee
     * comes from an ERTH note when there is one; an ERTH unshield with no
     * spare note pays it out of the unshield (fee_from_output).
     */
    fun unshield(receiver: String, denom: String, amount: Long): TxResult = run { fee ->
        val notes = store.state.notes
        val feeNote = runCatching { NoteSelection.feeNote(notes, fee) }.getOrNull()
            ?.takeIf { fn -> denom != "uerth" || runCatching { NoteSelection.inputs(notes, denom, amount, setOf(fn.position)) }.isSuccess }
        if (feeNote != null) {
            val inputs = NoteSelection.inputs(notes, denom, amount, setOf(feeNote.position))
            val plan = TransferPlan.build(keys, store.noteTree, denom, inputs, emptyList(), amount, feeNote, fee)
            Assembled(listOf(plan), null) { ts, _ -> MsgTransfer.newBuilder().setTransfer(ts[0]).setReceiver(receiver).build() }
        } else {
            require(denom == "uerth") { "no ERTH note to pay the fee of a $denom unshield" }
            val out = amount + fee
            val plan = TransferPlan.build(keys, store.noteTree, denom, NoteSelection.inputs(notes, denom, out), emptyList(), out, null, 0)
            Assembled(listOf(plan), null) { ts, _ ->
                MsgTransfer.newBuilder().setTransfer(ts[0]).setReceiver(receiver).setFeeFromOutput(fee).build()
            }
        }
    }

    /** Merges the two largest [denom] notes into one (a transfer has only two input slots for its asset). */
    fun merge(denom: String): TxResult = run { fee ->
        val feeNote = NoteSelection.feeNote(store.state.notes, fee)
        val two = NoteSelection.spendable(store.state.notes, denom, setOf(feeNote.position)).sortedByDescending { it.note.value }.take(2)
        require(two.size == 2) { "nothing to merge" }
        val plan = TransferPlan.build(keys, store.noteTree, denom, two, emptyList(), 0, feeNote, fee)
        Assembled(listOf(plan), null) { ts, _ -> MsgTransfer.newBuilder().setTransfer(ts[0]).build() }
    }

    /** A note to self for MsgShield (transparent coins into the pool; signed, so built by the caller's key). */
    fun shieldOutput(denom: String, amount: Long): NoteOut = NoteOut.toSelf(keys, denom, amount)

    // ---- personhood ---------------------------------------------------------

    /** The notes a registration pays, and the binding its passport proof carries. Hold until [register]. */
    class RegistrationPrep(val anml: NoteOut, val erth: NoteOut, val gas: NoteOut, val affiliate: String, val binding: Fr, val idc: Fr)

    fun prepareRegistration(affiliate: String?): RegistrationPrep {
        val aff = affiliate?.trim().orEmpty()
        val anml = NoteOut.toSelf(keys, "uanml", ANML_PER_CLAIM)
        // The reward's value is the chain's to decide: minted at its public amount.
        val erth = mint("uerth")
        val gas = mint("uerth")
        val binding = Privacy.registrationBinding(keys.idc, anml.pc, erth.pc, PrivateMsgs.affiliateField(aff))
        return RegistrationPrep(anml, erth, gas, aff, binding, keys.idc)
    }

    /** MsgRegister without its fee transfer: what /gas/register checks. */
    fun registerMsg(prep: RegistrationPrep, proof: ByteArray, publicSignals: List<String>, signatureAlgorithm: String, dscDer: ByteArray): MsgRegister =
        MsgRegister.newBuilder()
            .setProof(ByteString.copyFrom(proof))
            .addAllPublicSignals(publicSignals)
            .setSignatureAlgorithm(signatureAlgorithm)
            .setDscDer(ByteString.copyFrom(dscDer))
            .setIdc(ByteString.copyFrom(prep.idc.toBytes()))
            .setPcAnml(ByteString.copyFrom(prep.anml.pc.toBytes()))
            .setCiphertextAnml(ByteString.copyFrom(prep.anml.ciphertext))
            .setPcErth(ByteString.copyFrom(prep.erth.pc.toBytes()))
            .setCiphertextErth(ByteString.copyFrom(prep.erth.ciphertext))
            .setAffiliate(prep.affiliate)
            .build()

    /**
     * Broadcasts the registration, its fee paid from a shielded ERTH note (the
     * gas grant's, on a first registration), and records the identity leaf it
     * wrote. [publicSignals] are the passport proof's: [current_date, address,
     * nullifier, dsc_key].
     */
    fun register(prep: RegistrationPrep, proof: ByteArray, publicSignals: List<String>, signatureAlgorithm: String, dscDer: ByteArray): TxResult {
        require(PrivateMsgs.decimalField(publicSignals[1]) == prep.binding) { "the passport proof is bound to other notes" }
        val base = registerMsg(prep, proof, publicSignals, signatureAlgorithm, dscDer)
        val result = run { fee ->
            val plan = feeOnly(fee)
            Assembled(listOf(plan), null) { ts, _ -> base.toBuilder().setFee(ts[0]).build() }
        }
        recordRegistration(result, PrivateMsgs.decimalField(publicSignals[3]), publicSignals[2])
        return result
    }

    /**
     * The leaf the registration appended: its index from the tx's register
     * event, activated_at the block time. The country (the verifying CSCA's,
     * which the chain records) is found by recomputing the leaf for every
     * ISO alpha-2 code and unknown, against the leaf the indexer serves at
     * that index: no query names this registration.
     */
    fun recordRegistration(result: TxResult, dscKey: Fr, passportNullifier: String) {
        val index = result.attr("register", "leaf_index")?.toLongOrNull()
            ?: throw IllegalStateException("registration tx ${result.hash} has no leaf_index")
        sync()
        require(index < store.identityTree.size) { "the indexer has not seen leaf $index yet" }
        val leaf = store.identityTree.leaf(index)
        val country = countryFor(leaf, dscKey, result.time)
            ?: throw IllegalStateException("leaf $index does not match this registration")
        store.state.identity = IdentityRecord(index, dscKey, country, result.time, passportNullifier)
        store.save()
    }

    private fun countryFor(leaf: Fr, dscKey: Fr, activatedAt: Long): Fr? {
        val candidates = sequenceOf(Fr.ZERO) + ('A'..'Z').asSequence().flatMap { a -> ('A'..'Z').asSequence().map { b -> Privacy.countryField("$a$b") } }
        return candidates.firstOrNull { Privacy.identityLeaf(keys.idc, dscKey, it, activatedAt) == leaf }
    }

    /** Today's ANML. Opens once the identity was activated before yesterday began. */
    fun claimAnml(day: Long = today()): TxResult {
        val anml = NoteOut.toSelf(keys, "uanml", ANML_PER_CLAIM)
        val m = membership(Privacy.claimScope(day), Fr.ZERO, Fr.ZERO, (day - 1) * SECONDS_PER_DAY)
        val r = run { fee ->
            Assembled(listOf(feeOnly(fee)), m) { ts, mem ->
                MsgClaimAnml.newBuilder().setFee(ts[0]).setMembership(mem).setDay(day)
                    .setPc(ByteString.copyFrom(anml.pc.toBytes())).setCiphertext(ByteString.copyFrom(anml.ciphertext)).build()
            }
        }
        store.state.claimedDays.add(day); store.save()
        return r
    }

    fun claimedToday(): Boolean = today() in store.state.claimedDays

    /**
     * When ANML can next be claimed: 0 for now, null without a live
     * registration. A claim for day d needs activated_at <= (d - 1) * 86400,
     * so a fresh registration first claims on the day after next.
     */
    fun claimOpensAt(): Long? {
        if (identityStatus() != WalletSync.IdentityStatus.LIVE) return null
        val id = store.state.identity ?: return null
        val firstDay = id.activatedAt / SECONDS_PER_DAY + 1 + if (id.activatedAt % SECONDS_PER_DAY == 0L) 0 else 1
        val day = maxOf(today() + if (claimedToday()) 1 else 0, firstDay)
        return if (day == today()) 0L else day * SECONDS_PER_DAY
    }

    /**
     * The max_activation a caretaker split or referrer binding names: at most
     * now - R - root window (the chain's bound), rounded down to the hour so it
     * says nothing about when the tx was made, less a margin for clock skew.
     */
    private fun leaseBound(): Long {
        val p = reads.personhoodParams()
        val bound = now() - p.caretakerVoteSeconds - p.identityRootWindowSeconds - CLOCK_MARGIN
        return bound / 3600 * 3600
    }

    /** Casts, refreshes or (empty) clears the caretaker split, option id -> percent. */
    fun setCaretaker(split: Map<Long, Long>): TxResult {
        val maxAct = leaseBound()
        val m = membership(Privacy.caretakerScope(), Fr.ZERO, Fr.ZERO, maxAct)
        val weights = split.entries.sortedBy { it.key }.map { AllocationWeight.newBuilder().setOptionId(it.key).setPercent(it.value).build() }
        val r = run { fee ->
            Assembled(listOf(feeOnly(fee)), m) { ts, mem ->
                MsgSetCaretaker.newBuilder().setFee(ts[0]).setMembership(mem).addAllPercentages(weights).setMaxActivation(maxAct).build()
            }
        }
        store.state.caretakerCastAt = now(); store.state.caretakerSplit = split; store.save()
        return r
    }

    /** Whether the split needs refreshing to stay counted: past half of R, or never cast. */
    fun caretakerDue(): Boolean {
        if (store.state.caretakerSplit.isEmpty()) return false
        val r = reads.personhoodParams().caretakerVoteSeconds
        return now() - store.state.caretakerCastAt > r / 2
    }

    /** Binds (or, empty, clears) the address referral rewards are paid to. */
    fun bindReferrer(address: String): TxResult {
        val maxAct = leaseBound()
        val m = membership(Privacy.referrerScope(), Fr.ZERO, Fr.ZERO, maxAct)
        return run { fee ->
            Assembled(listOf(feeOnly(fee)), m) { ts, mem ->
                MsgBindReferrer.newBuilder().setFee(ts[0]).setMembership(mem).setAddress(address).setMaxActivation(maxAct).build()
            }
        }
    }

    // ---- assembly -----------------------------------------------------------

    /** A human vote on an x/gov proposal. The scope is recomputed here rather than taken from the node. */
    fun voteProposal(proposalId: Long, yes: Boolean): TxResult {
        val b = reads.ballotInputs(proposalId = proposalId)
        check(b.scope == Privacy.proposalScope(proposalId, b.round)) { "the node's ballot scope is not this proposal's" }
        val m = membership(b.scope, b.excludedDsc, b.excludedCountry, b.maxActivation)
        val option = if (yes) VoteOption.VOTE_OPTION_YES else VoteOption.VOTE_OPTION_NO
        return run { fee ->
            Assembled(listOf(feeOnly(fee)), m) { ts, mem ->
                MsgVoteProposal.newBuilder().setFee(ts[0]).setMembership(mem).setProposalId(proposalId).setOption(option).build()
            }
        }
    }

    fun proposeRemoval(optionId: Long): TxResult {
        val day = today()
        val window = reads.personhoodParams().identityRootWindowSeconds
        val m = membership(Privacy.proposeRemovalScope(optionId, day), Fr.ZERO, Fr.ZERO, day * SECONDS_PER_DAY - window)
        return run { fee ->
            Assembled(listOf(feeOnly(fee)), m) { ts, mem ->
                MsgProposeRemoval.newBuilder().setFee(ts[0]).setMembership(mem).setOptionId(optionId).build()
            }
        }
    }

    fun voteRemoval(optionId: Long, yes: Boolean): TxResult {
        val b = reads.ballotInputs(optionId = optionId)
        check(b.scope == Privacy.removalScope(b.ballotId)) { "the node's ballot scope is not this ballot's" }
        val m = membership(b.scope, b.excludedDsc, b.excludedCountry, b.maxActivation)
        val option = if (yes) VoteOption.VOTE_OPTION_YES else VoteOption.VOTE_OPTION_NO
        return run { fee ->
            Assembled(listOf(feeOnly(fee)), m) { ts, mem ->
                MsgVoteRemoval.newBuilder().setFee(ts[0]).setMembership(mem).setOptionId(optionId).setOption(option).build()
            }
        }
    }

    // ---- private staking ----------------------------------------------------

    /** Stakes [amount] uerth with [validator]: derth minted to us at the live rate, delegated at the epoch's end. */
    fun delegate(validator: String, amount: Long): TxResult {
        val derth = mint(derthDenom(validator))
        return run { fee ->
            val plan = spendWithFee("uerth", amount, fee, emptyList(), amount)
            Assembled(listOf(plan), null) { ts, _ ->
                MsgDelegate.newBuilder().setTransfer(ts[0]).setValidator(validator)
                    .setPc(ByteString.copyFrom(derth.pc.toBytes())).setCiphertext(ByteString.copyFrom(derth.ciphertext)).build()
            }
        }
    }

    /** Turns [amount] derth/[validator] into an unbonding claim, claimable once its epoch's undelegation matures. */
    fun undelegate(validator: String, amount: Long): TxResult {
        val unbond = mint(unbondDenom(validator, reads.epochNumber()))
        return run { fee ->
            val plan = spendWithFee(derthDenom(validator), amount, fee, emptyList(), amount)
            Assembled(listOf(plan), null) { ts, _ ->
                MsgUndelegate.newBuilder().setTransfer(ts[0]).setValidator(validator)
                    .setPc(ByteString.copyFrom(unbond.pc.toBytes())).setCiphertext(ByteString.copyFrom(unbond.ciphertext)).build()
            }
        }
    }

    /** Claims a matured unbond note; the fee comes out of the ERTH it pays. */
    fun claimUnbonding(note: OwnedNote): TxResult {
        val (validator, epoch) = parseUnbond(note.note.denom)
        val erth = mint("uerth")
        return run { fee ->
            val plan = TransferPlan.build(keys, store.noteTree, note.note.denom, listOf(note), emptyList(), note.note.value, null, 0)
            Assembled(listOf(plan), null) { ts, _ ->
                MsgClaimUnbonding.newBuilder().setTransfer(ts[0]).setValidator(validator).setEpoch(epoch)
                    .setPc(ByteString.copyFrom(erth.pc.toBytes())).setCiphertext(ByteString.copyFrom(erth.ciphertext))
                    .setFeeFromOutput(fee).build()
            }
        }
    }

    /**
     * Spend-to-vote: [note] (derth, created before the proposal's snapshot)
     * is spent whole against the snapshot root, its value is the vote's
     * weight, and the chain mints the same value back to us. The fee is a
     * second transfer against the current root.
     */
    fun stakeVote(proposalId: Long, note: OwnedNote, options: List<WeightedVoteOption>): TxResult {
        val validator = parseDerth(note.note.denom)
        val snap = reads.snapshot(proposalId)
        require(note.position < snap.treeSize) { "this stake arrived after the proposal's snapshot and cannot vote on it" }
        val tree = store.noteTree
        check(tree.rootAt(snap.treeSize) == snap.root) { "the local note tree disagrees with the proposal's snapshot root" }
        val path = tree.pathAt(note.position, snap.treeSize)
        val back = NoteOut.toSelf(keys, note.note.denom, note.note.value)
        return run { fee ->
            val vote = TransferPlan.build(keys, tree, note.note.denom, listOf(note), emptyList(), note.note.value, null, 0,
                root = snap.root, paths = mapOf(note.position to path))
            val feePlan = feeOnly(fee, setOf(note.position))
            Assembled(listOf(vote, feePlan), null) { ts, _ ->
                MsgStakeVote.newBuilder().setTransfer(ts[0]).setProposalId(proposalId).setValidator(validator)
                    .addAllOptions(options).setPc(ByteString.copyFrom(back.pc.toBytes()))
                    .setCiphertext(ByteString.copyFrom(back.ciphertext)).setFeeTransfer(ts[1]).build()
            }
        }
    }

    /** This wallet's Groundworks positions: the public positions whose key is one of ours. */
    fun positions(): List<Pair<PrivacyChainReads.Position, Int>> {
        val mine = (0 until store.state.nextPositionKey).associateBy { ByteString.copyFrom(keys.positionKey(it).pubKeyPoint.getEncoded(true)) }
        return reads.positions().mapNotNull { p -> mine[ByteString.copyFrom(p.pubkey)]?.let { p to it } }
    }

    fun lockPosition(validator: String, amount: Long, splits: Map<Long, Long>): TxResult {
        val keyIndex = store.state.nextPositionKey
        val pub = keys.positionKey(keyIndex).pubKeyPoint.getEncoded(true)
        val r = run { fee ->
            val plan = spendWithFee(derthDenom(validator), amount, fee, emptyList(), amount)
            Assembled(listOf(plan), null) { ts, _ ->
                MsgLockPosition.newBuilder().setTransfer(ts[0]).setValidator(validator)
                    .addAllSplits(weights(splits)).setPubkey(ByteString.copyFrom(pub)).build()
            }
        }
        store.state.nextPositionKey = keyIndex + 1; store.save()
        return r
    }

    fun updatePosition(position: PrivacyChainReads.Position, keyIndex: Int, splits: Map<Long, Long>): TxResult {
        val w = weights(splits)
        val sig = positionSign(keyIndex, "update", position, PrivateMsgs.splitsBytes(w))
        return run { fee ->
            Assembled(listOf(feeOnly(fee)), null) { ts, _ ->
                MsgUpdatePosition.newBuilder().setTransfer(ts[0]).setPositionId(position.id).addAllSplits(w)
                    .setSignature(ByteString.copyFrom(sig)).build()
            }
        }
    }

    fun unlockPosition(position: PrivacyChainReads.Position, keyIndex: Int): TxResult {
        val back = NoteOut.toSelf(keys, derthDenom(position.validator), position.derth)
        val sig = positionSign(keyIndex, "unlock", position, back.pc.toBytes() + back.ciphertext)
        return run { fee ->
            Assembled(listOf(feeOnly(fee)), null) { ts, _ ->
                MsgUnlockPosition.newBuilder().setTransfer(ts[0]).setPositionId(position.id)
                    .setPc(ByteString.copyFrom(back.pc.toBytes())).setCiphertext(ByteString.copyFrom(back.ciphertext))
                    .setSignature(ByteString.copyFrom(sig)).build()
            }
        }
    }

    fun positionVote(position: PrivacyChainReads.Position, keyIndex: Int, proposalId: Long, options: List<WeightedVoteOption>): TxResult {
        val sig = positionSign(keyIndex, "vote", position, PrivateMsgs.positionVotePayload(proposalId, options))
        return run { fee ->
            Assembled(listOf(feeOnly(fee)), null) { ts, _ ->
                MsgPositionVote.newBuilder().setTransfer(ts[0]).setPositionId(position.id).setProposalId(proposalId)
                    .addAllOptions(options).setSignature(ByteString.copyFrom(sig)).build()
            }
        }
    }

    /** secp256k1 over sha256(PositionSignBytes), low-S, 64-byte r||s. */
    private fun positionSign(keyIndex: Int, action: String, p: PrivacyChainReads.Position, payload: ByteArray): ByteArray {
        val key: ECKey = keys.positionKey(keyIndex)
        check(key.pubKeyPoint.getEncoded(true).contentEquals(p.pubkey)) { "position ${p.id} is not held by key $keyIndex" }
        val sig = key.sign(Sha256Hash.of(PrivateMsgs.positionSignBytes(chainId, action, p.id, p.nonce, payload)))
        fun b32(x: java.math.BigInteger): ByteArray {
            val b = x.toByteArray()
            return ByteArray(32).also { o -> val n = minOf(32, b.size); System.arraycopy(b, b.size - n, o, 32 - n, n) }
        }
        return b32(sig.r) + b32(sig.s)
    }

    private fun weights(splits: Map<Long, Long>): List<AllocationWeight> =
        splits.entries.sortedBy { it.key }.map { AllocationWeight.newBuilder().setOptionId(it.key).setPercent(it.value).build() }

    companion object {
        const val SECONDS_PER_DAY = 86_400L
        const val ANML_PER_CLAIM = 1_000_000L
        /**
         * MsgRegister's gas, for the fee estimate before simulating: the
         * passport proof (3M) and DSC chain (300k), the fee transfer's proof
         * (2M) and note writes, and the tx's bytes.
         */
        const val REGISTER_GAS_ESTIMATE = 7_000_000L
        /** Slack against the chain's clock for bounds the wallet must stay under. */
        const val CLOCK_MARGIN = 600L

        fun derthDenom(valoper: String) = "derth/$valoper"
        fun unbondDenom(valoper: String, epoch: Long) = "unbond/$valoper/$epoch"

        fun parseDerth(denom: String): String {
            require(denom.startsWith("derth/")) { "not a derth note" }
            return denom.removePrefix("derth/")
        }

        fun parseUnbond(denom: String): Pair<String, Long> {
            val parts = denom.split("/")
            require(parts.size == 3 && parts[0] == "unbond") { "not an unbond note" }
            return parts[1] to parts[2].toLong()
        }
    }
}
