package network.erth.wallet.privacy

import cosmos.gov.v1.VoteOption as GovVoteOption
import cosmos.gov.v1.WeightedVoteOption
import network.erth.wallet.chain.ChainErrors
import network.erth.wallet.privacy.keys.PrivacyKeys
import network.erth.wallet.privacy.prove.VoteWitness
import network.erth.wallet.privacy.sync.PrivacyStore
import network.erth.wallet.privacy.tx.Assembled
import network.erth.wallet.privacy.tx.PrivateMsgs
import network.erth.wallet.privacy.tx.PrivateTxEngine
import network.erth.wallet.privacy.tx.VoteWitnessSpec
import network.erth.wallet.privacy.zk.Fr
import network.erth.wallet.privacy.zk.Privacy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Clients round 7 (chain 48b631c, ORCHARD_DESIGN 17-18): the registration
 * binding's chain id, undelegations that pay out by themselves, one stake
 * vote per validator (four note slots, ten public inputs), the 5x private gas
 * ceiling, send-disabled denoms at every pool edge, the new personhood
 * errors, and nothing ever sent but by the user.
 */
class Fix7Test {
    private val alice = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private val vB = "earthvaloper1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"
    private val yes = listOf(WeightedVoteOption.newBuilder().setOption(GovVoteOption.VOTE_OPTION_YES).setWeight("1").build())

    private fun reads(chain: FakeChain, due: (Long) -> Long? = { null }) = object : PrivacyChainReads {
        override fun personhoodParams() = PrivacyChainReads.PersonhoodParams(chain.caretakerLease, 3_600, chain.handleLease, chain.handleRenewal)
        override fun leaseBounds() = chain.leaseBounds()
        override fun ballotInputs(proposalId: Long, optionId: Long) =
            PrivacyChainReads.BallotInputs(Privacy.proposalScope(proposalId, 0), Fr.ZERO, Fr.ZERO, Privacy.NO_BOUND, 0, 0, chain.ballotMaxPredecessor())
        override fun epochNumber() = chain.epoch
        override fun snapshot(proposalId: Long) = chain.snapshotRead(proposalId)
        override fun stakeNullifierTree(start: Long, limit: Int) = chain.nfTreeRead(start, limit)
        override fun positions() = chain.positionReads()
        override fun debtTree(start: Long, limit: Int) = chain.debtTreeRead(start, limit)
        override fun validatorBook(valoper: String) = chain.validatorBookRead(valoper)
        override fun minDelegation() = chain.minDelegation
        override fun unbondDueBy(epoch: Long) = due(epoch)
    }

    private fun wallet(chain: FakeChain, due: (Long) -> Long? = { null }) = PrivacyWallet(
        PrivacyKeys.fromMnemonic(alice), PrivacyStore.memory(), chain, chain, reads(chain, due), chain.prover, chain.chainId, chain,
        now = { chain.now },
    )

    private fun funded(chain: FakeChain, w: PrivacyWallet, amount: Long = 2_000_000) {
        val o = w.shieldOutput("uerth", 0)
        chain.shield("uerth", amount, o.pc, o.ciphertext)
    }

    private fun staked(chain: FakeChain, due: (Long) -> Long? = { null }): PrivacyWallet {
        val a = wallet(chain, due)
        repeat(4) { funded(chain, a) }
        a.sync()
        a.delegate(vB, 1_000_000); a.sync()
        return a
    }

    private fun bal(w: PrivacyWallet, d: String = "uerth") = w.balances()[d] ?: 0L

    // ---- 1. the registration binding names the chain -----------------------

    @Test
    fun registrationBindingNamesTheChain() {
        val d = Vectors.json.getJSONObject("derive")
        assertEquals("148b3513a501b6ff9c02314f355cb83fb544e22b2a9df79552fe49c944424159", d.getString("reg_pinned"))
        val one = Privacy.registrationBinding("earth-1", Fr.of(1), Fr.of(2), "anml".toByteArray(), Fr.of(3), "erth".toByteArray(), Fr.ZERO)
        assertEquals(d.getString("reg_pinned"), one.toHex())
        val other = Privacy.registrationBinding("earth-testnet-1", Fr.of(1), Fr.of(2), "anml".toByteArray(), Fr.of(3), "erth".toByteArray(), Fr.ZERO)
        assertTrue(one != other)
        // The wallet binds its own chain's id.
        val chain = FakeChain()
        val prep = wallet(chain).prepareRegistration(null)
        assertEquals(Privacy.registrationBinding(chain.chainId, PrivacyKeys.fromMnemonic(alice).idc, prep.anml.pc, prep.anml.ciphertext,
            prep.erth.pc, prep.erth.ciphertext, Fr.ZERO), prep.binding)
    }

    // ---- 2. personhood errors -------------------------------------------------

    @Test
    fun switchSignerAndDailyCapErrorsArePlain() {
        val mismatch = ChainErrors.explain(1127, "personhood")!!
        assertTrue(mismatch, "same passport" in mismatch)
        // A simulate (or the gas service) answers with the registered text.
        assertEquals(mismatch, ChainErrors.explain("identity switch must be proven under the live registration's document signer"))
        val cap = ChainErrors.explain(1113, "personhood")!!
        assertTrue(cap, "Try again tomorrow" in cap)
        assertEquals(cap, ChainErrors.explain("rpc error: daily registration limit reached for this document signer or country"))
        // The codes mean nothing in another module.
        assertNull(ChainErrors.explain(1127, "dex"))
    }

    // ---- 3. undelegations pay out by themselves -------------------------------

    @Test
    fun undelegateNamesItsPayoutAndMintsNoStakeNote() {
        val chain = FakeChain()
        val a = staked(chain, due = { e -> 1_000L * e })
        val stakeRows = chain.stakeRows.size
        a.undelegate(vB, 400_000)
        val m = chain.lastMsg as network.erth.earth.proto.shieldedstaking.MsgUndelegate
        assertEquals(32, m.pc.size())
        assertEquals(network.erth.wallet.privacy.note.NoteCipher.BLIND_CIPHERTEXT_BYTES, m.ciphertext.size())
        // Chain dff3a9b: lane A's output (the change) with its 201-byte ciphertext; no credit lane.
        assertEquals(network.erth.wallet.privacy.note.NoteCipher.STAKE_CIPHERTEXT_BYTES, m.stake.ciphertext.size())
        assertTrue(Fr.fromBytes(m.stake.creditCommitment.toByteArray()).isZero && m.stake.creditCiphertext.isEmpty)
        // The change is the proof's own output; nothing minted into the stake tree.
        assertEquals(stakeRows + 1, chain.stakeRows.size)
        val u = a.pendingUnbonds.single()
        assertEquals(Fr.fromBytes(m.pc.toByteArray()), u.pc)
        assertEquals(listOf(400_000L * 10 / 9, chain.epoch, 1L, 1_000L * chain.epoch), listOf(u.value, u.epoch, u.payoutId, u.dueBy))
        assertTrue(u.confirmed)
        dumpWitnesses(chain, "fix7Undelegate")
    }

    /** A payout past one note's worth comes as several notes, one ciphertext at several positions: every one is found. */
    @Test
    fun aSplitPayoutIsFoundWhole() {
        val chain = FakeChain()
        val a = staked(chain)
        // The whole note: the proof's output is a zero note.
        val all = a.stakeBalances().getValue(PrivacyWallet.derthDenom(vB))
        a.undelegate(vB, all)
        val pc = Fr.fromBytes((chain.lastMsg as network.erth.earth.proto.shieldedstaking.MsgUndelegate).pc.toByteArray())
        a.sync()
        val before = bal(a)
        chain.payUnbonds { p -> listOf(p.value / 3, p.value / 3, p.value - 2 * (p.value / 3)) }
        val txs = chain.txs.size
        a.sync()
        assertEquals(txs, chain.txs.size)
        assertEquals(all * 10 / 9, bal(a) - before)
        assertEquals(3, a.notes.count { it.note.pc(a.keys.ownerPk) == pc })
        assertTrue(a.stakeNotes.none { it.spendable })
        assertTrue(a.pendingUnbonds.isEmpty())
        // A restored wallet finds the payout by trial decryption alone.
        val restored = wallet(chain)
        restored.sync()
        assertEquals(a.balances(), restored.balances())
        dumpWitnesses(chain, "fix7SplitPayout")
    }

    @Test
    fun aFailedOrRefusedUndelegationIsForgotten() {
        val chain = FakeChain()
        val a = staked(chain)
        chain.rejectNext = 1
        assertThrows(java.io.IOException::class.java) { a.undelegate(vB, 100_000) }
        assertTrue(a.pendingUnbonds.isEmpty())
        chain.failInBlockNext = 1
        assertThrows(java.io.IOException::class.java) { a.undelegate(vB, 100_000) }
        assertEquals(1, a.pendingUnbonds.size)
        a.sync()
        assertTrue(a.pendingUnbonds.isEmpty())
        assertTrue(chain.unbondPayouts.isEmpty())
    }

    @Test
    fun pendingUnbondsSurviveAReset() {
        val chain = FakeChain()
        val a = staked(chain)
        a.undelegate(vB, 100_000)
        val kept = a.pendingUnbonds
        a.store.reset(chain.chainId)
        assertEquals(kept, a.pendingUnbonds)
    }

    // ---- 4. one stake vote per validator ----------------------------------------

    @Test
    fun roundVoteWeightMatchesTheChain() {
        val v = Vectors.json.getJSONObject("round_vote_weight")
        for (k in v.keys()) {
            val w = java.math.BigInteger(k)
            if (w.bitLength() > 63) continue // past what a wallet holds
            assertEquals(k, v.getString(k).toLong(), PrivacyWallet.voteWeight(w.toLong()))
        }
    }

    @Test
    fun aVoteHasTwoSlotsAndNinePublicInputs() {
        val chain = FakeChain()
        val a = staked(chain)
        // A top-up merges into the one note.
        a.delegate(vB, 500_000); a.sync()
        val note = a.stakeNotes.single { it.unspent }
        chain.openProposal(7)
        a.sync()
        a.stakeVote(7, vB, yes)
        val m = chain.lastMsg as network.erth.earth.proto.shieldedstaking.MsgStakeVote
        assertEquals(2, m.voteNullifiersCount)
        val vs = m.voteNullifiersList.map { Fr.fromBytes(it.toByteArray()) }
        assertEquals(listOf(false, true), vs.map { it.isZero })
        assertEquals(chain.debtRoot(), Fr.fromBytes(m.debtRoot.toByteArray()))
        val w = chain.prover.allVotes.last()
        assertEquals(9, w.publicInputs().size)
        assertEquals(vs, w.vnfs)
        val inputs = w.noirInputs()
        for (k in listOf("amount", "rho", "rcm", "pos", "path", "move_key", "move_time", "exposed", "low_value", "low_next_value",
            "low_next_index", "low_index", "low_path", "debt_low_key", "debt_low_next_key", "debt_low_next_index", "debt_low_retained",
            "debt_low_index", "debt_low_path", "vnf")) {
            assertEquals(k, 2, (inputs.getValue(k) as List<*>).size)
        }
        assertEquals(listOf("0x0"), (inputs.getValue("amount") as List<*>).drop(1))
        assertEquals(32, ((inputs.getValue("path") as List<*>)[1] as List<*>).size)
        assertEquals(m.weight, PrivacyWallet.voteWeight(note.amount))
        dumpWitnesses(chain, "fix7VoteSlots")
        // The msg's own checks: two slots, used first, distinct.
        assertThrows(IllegalArgumentException::class.java) {
            PrivateMsgs.withVote(m, vs.take(1), ByteArray(0))
        }
    }

    @Test
    fun aVoteWitnessRefusesWhatTheCircuitWould() {
        val chain = FakeChain()
        val a = staked(chain)
        chain.openProposal(8)
        a.sync()
        a.stakeVote(8, vB, yes)
        val w = chain.prover.allVotes.last()
        // The same note twice; more weight than the notes.
        assertThrows(IllegalArgumentException::class.java) { w.copy(slots = w.slots + w.slots).check() }
        assertThrows(IllegalArgumentException::class.java) { w.copy(weight = w.slots.sumOf { it.amount } + 1).check() }
        assertThrows(IllegalArgumentException::class.java) { w.copy(slots = List(3) { w.slots[0] }) }
        assertTrue(VoteWitness.MAX_NOTES == PrivateMsgs.MAX_VOTE_NOTES)
    }

    /** A 1119 in a block names one note: only that one is final, the vote's others may vote again. */
    @Test
    fun aRefusalInABlockSettlesOnlyTheNamedNote() {
        val vnf = Fr.of(77)
        assertTrue(PrivacyWallet.namesVoteNullifier("this stake note already voted on this proposal: proposal 3, vote nullifier ${vnf.toHex().uppercase()}", vnf))
        assertFalse(PrivacyWallet.namesVoteNullifier("this stake note already voted on this proposal: proposal 3, vote nullifier 00", vnf))
        assertEquals(vnf, PrivacyWallet.usedVoteNullifier(IllegalStateException("x", IllegalArgumentException("vote nullifier ${vnf.toHex().uppercase()}")), listOf(Fr.of(1), vnf)))
    }

    // ---- 5. gas ----------------------------------------------------------------

    @Test
    fun everyPrivateTxDeclaresWithinFiveTimesItsGas() {
        val chain = FakeChain()
        val a = staked(chain)
        a.delegate(vB, 500_000); a.sync()
        // A second note (another device's) merged by a restake.
        chain.plantStake(a.keys, PrivacyWallet.derthDenom(vB), 50_000); a.sync()
        a.restake(vB); a.sync()
        chain.openProposal(9); a.sync()
        a.stakeVote(9, vB, yes); a.sync()
        a.undelegate(vB, 100_000); a.sync()
        assertTrue(chain.gasRatios.isNotEmpty())
        // simulate + 10% (at least the fixed headroom): well inside the chain's 5x.
        assertTrue(chain.gasRatios.toString(), chain.gasRatios.all { it in 1.0..1.2 })
        dumpWitnesses(chain, "fix7Gas")
    }

    @Test
    fun voteGasEstimateCountsItsNotes() {
        fun spec(used: Int) = VoteWitnessSpec(List(2) { if (it < used) Fr.of(it + 1L) else Fr.ZERO }) { error("unused") }
        val msg = network.erth.earth.proto.shieldedstaking.MsgStakeVote.getDefaultInstance()
        val g1 = PrivateTxEngine.estimateGas(msg, Assembled(emptyList(), vote = spec(1)) { _, _, _ -> msg }, 0)
        val g2 = PrivateTxEngine.estimateGas(msg, Assembled(emptyList(), vote = spec(2)) { _, _, _ -> msg }, 0)
        assertEquals(PrivateTxEngine.NOTE_GAS, g2 - g1)
        assertEquals(PrivateTxEngine.BASE_GAS + PrivateTxEngine.BUNDLE_GAS + 250_000 + 2_000_000 + 2 * PrivateTxEngine.NOTE_GAS, g1)
    }

    // ---- 7. send-disabled denoms --------------------------------------------------

    @Test
    fun sendDisabledIsRefusedOnSwapsAndDelegationWithAClearError() {
        val chain = FakeChain()
        val a = wallet(chain)
        repeat(3) { funded(chain, a) }
        a.sync()
        chain.sendDisabled = setOf("uerth")
        val sent = chain.txs.size
        val e = assertThrows(Exception::class.java) { a.delegate(vB, 100_000) }
        val text = ChainErrors.explain(e.message.orEmpty())!!
        assertTrue(text, "private staking" in text)
        assertThrows(Exception::class.java) { a.noteSwap("uerth", 100_000, "uanml", 1) }
        assertEquals(sent, chain.txs.size)
        assertNotNull(ChainErrors.explain(5, "bank", "uerth transfers are currently disabled: send transactions are disabled"))
    }

    // ---- 8. nothing is sent but by the user ----------------------------------------

    /** Sync, payouts, pending votes and undelegations: none of it ever broadcasts. */
    @Test
    fun syncNeverSendsAnything() {
        val chain = FakeChain()
        val a = staked(chain)
        a.undelegate(vB, 100_000)
        chain.openProposal(10)
        val broadcasts = chain.txs.size
        val sims = chain.simulated
        repeat(3) { a.sync() }
        chain.payUnbonds()
        chain.now += 40L * 86_400
        repeat(3) { a.sync() }
        assertEquals(broadcasts, chain.txs.size)
        assertEquals(sims, chain.simulated)
    }
}
