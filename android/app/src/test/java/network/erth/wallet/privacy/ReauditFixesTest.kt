package network.erth.wallet.privacy

import cosmos.gov.v1.VoteOption as GovVoteOption
import cosmos.gov.v1.WeightedVoteOption
import network.erth.wallet.privacy.keys.PrivacyKeys
import network.erth.wallet.privacy.sync.HeightPage
import network.erth.wallet.privacy.sync.LatestRoots
import network.erth.wallet.privacy.sync.NotesPage
import network.erth.wallet.privacy.sync.PrivacyIndexer
import network.erth.wallet.privacy.sync.PrivacyStore
import network.erth.wallet.privacy.sync.WalletSync
import network.erth.wallet.privacy.zk.Fr
import network.erth.wallet.privacy.zk.Privacy
import network.erth.wallet.privacy.tx.PrivateTxEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import kotlinx.coroutines.flow.first
import org.junit.Test

/**
 * The clients re-audit's findings, each against [FakeChain]: K8 (a pruned
 * root is unverified, not a mismatch), K9 (pinned heights, sampled
 * nullifiers, an indexer behind the tip).
 */
class ReauditFixesTest {
    private val alice = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private val yes = listOf(WeightedVoteOption.newBuilder().setOption(GovVoteOption.VOTE_OPTION_YES).setWeight("1").build())

    private fun reads(chain: FakeChain) = object : PrivacyChainReads {
        override fun personhoodParams() = PrivacyChainReads.PersonhoodParams(30L * 86_400, 3_600)
        override fun leaseBounds() = chain.leaseBounds()
        override fun ballotInputs(proposalId: Long, optionId: Long) =
            PrivacyChainReads.BallotInputs(Privacy.proposalScope(proposalId, 0), Fr.ZERO, Fr.ZERO, Privacy.NO_BOUND, 0, 0, chain.ballotMaxPredecessor())
        override fun epochNumber() = chain.epoch
        override fun snapshot(proposalId: Long) = chain.snapshotRead(proposalId)
        override fun stakeNullifierTree(start: Long, limit: Int) = chain.nfTreeRead(start, limit)
        override fun positions() = chain.positionReads()
    }

    private fun wallet(chain: FakeChain, indexer: PrivacyIndexer = chain, store: PrivacyStore = PrivacyStore.memory()) =
        PrivacyWallet(PrivacyKeys.fromMnemonic(alice), store, indexer, chain, reads(chain), chain.prover, chain.chainId, chain, now = { chain.now })

    private open class Wrapped(val inner: PrivacyIndexer) : PrivacyIndexer by inner

    private fun bal(w: PrivacyWallet, d: String) = w.balances()[d] ?: 0L

    private fun funded(chain: FakeChain, w: PrivacyWallet, amount: Long = 1_000_000) {
        val o = w.shieldOutput("uerth", 0)
        chain.shield("uerth", amount, o.pc, o.ciphertext)
    }

    /** An indexer frozen at the chain's state when [freeze] was called. */
    private class Frozen(val chain: FakeChain) : Wrapped(chain) {
        var notes = -1; var height = -1L; var roots: LatestRoots? = null
        fun freeze() { notes = chain.notes.size; height = chain.height - 1; roots = chain.rootsLatest() }
        override fun notes(fromPos: Long, limit: Int?): NotesPage {
            if (notes < 0) return inner.notes(fromPos, limit)
            val rows = chain.notes.take(notes).drop(fromPos.toInt())
            return NotesPage(rows, fromPos + rows.size, false, height)
        }
        override fun nullifiers(fromHeight: Long, limit: Int?): HeightPage<Fr> =
            inner.nullifiers(fromHeight, limit).let { p -> if (notes < 0) p else HeightPage(p.blocks.filter { it.first <= height }, height + 1, false, height) }
        override fun rootsLatest(): LatestRoots = roots ?: inner.rootsLatest()
    }

    /** K8: the indexer is two weeks behind and the chain pruned its root: unverified, nothing wiped. */
    @Test
    fun prunedRootIsUnverifiedNotAMismatch() {
        val chain = FakeChain()
        val idx = Frozen(chain)
        val a = wallet(chain, indexer = idx)
        funded(chain, a)
        a.sync()
        assertTrue(a.store.state.rootsVerified)
        idx.freeze()
        funded(chain, a, 2_000_000)
        chain.pruneNoteRoots()
        val r = a.sync()
        assertFalse(r.verified)
        assertTrue(a.store.state.rootsError!!.contains("no longer holds"))
        // Kept, labelled, and not spent from.
        assertEquals(1_000_000L, bal(a, "uerth"))
        assertThrows(IllegalStateException::class.java) { a.unshield("earth1qqqsyqcyq5rqwzqfpg9scrgwpugpzysncc2uls", "uerth", 1) }
        idx.notes = -1; idx.roots = null
        assertTrue(a.sync().verified)
        assertEquals(3_000_000L, bal(a, "uerth"))
    }

    /** K9: a node answering a pinned query at another height cannot condemn the trees; equal ones still verify. */
    @Test
    fun otherEchoedHeightIsUnverifiedNotAMismatch() {
        val chain = FakeChain()
        val a = wallet(chain)
        funded(chain, a)
        chain.echoOtherHeight = true
        assertTrue(a.sync().verified)
        // The indexer one block behind the tip: its height is not answered, the latest tree differs.
        val idx = Frozen(chain)
        val b = wallet(chain, indexer = idx)
        val prep = b.prepareRegistration(null)
        chain.shield("uerth", 100_000, prep.gas.pc, prep.gas.ciphertext)
        b.sync()
        idx.freeze()
        b.register(prep, ByteArray(14_656), listOf("261001", prep.binding.toBigInteger().toString(), "1", "77"), "lean_poa", ByteArray(10))
        val r = b.sync()
        assertFalse(r.verified)
        assertTrue(b.store.state.rootsError!!, b.store.state.rootsError!!.contains("could not be read at the indexer's height"))
        assertEquals(1_000_000L, bal(a, "uerth"))
    }

    /** K9: a spend the chain does not hold, slipped into the nullifier stream, is caught by the sample. */
    @Test
    fun inventedSpendIsCaughtBySample() {
        val chain = FakeChain()
        val a = wallet(chain)
        funded(chain, a)
        a.sync()
        val idx = object : Wrapped(chain) {
            override fun nullifiers(fromHeight: Long, limit: Int?): HeightPage<Fr> {
                val p = inner.nullifiers(fromHeight, limit)
                return p.copy(blocks = listOf(1L to listOf(Fr.of(424242))) + p.blocks)
            }
        }
        val w = wallet(chain, indexer = idx)
        assertFalse(w.sync().verified)
        assertTrue(w.store.state.rootsError!!.contains("spend the chain does not hold"))
    }

    private fun registered(chain: FakeChain, w: PrivacyWallet, nullifier: String = "555") {
        val prep = w.prepareRegistration(null)
        chain.shield("uerth", 100_000, prep.gas.pc, prep.gas.ciphertext)
        w.sync()
        w.register(prep, ByteArray(14_656), listOf("261001", prep.binding.toBigInteger().toString(), nullifier, Fr.of(77).toBigInteger().toString()), "lean_poa", ByteArray(10))
        w.sync()
        assertEquals(WalletSync.IdentityStatus.LIVE, w.identityStatus())
    }

    /** K6: a relaunch the LCD confirms keeps the registration (record, passport nullifier) and re-verifies its leaf. */
    @Test
    fun genesisSwitchKeepsIdentity() {
        val chain = FakeChain()
        val a = wallet(chain)
        registered(chain, a)
        val id = a.store.state.identity!!
        a.store.state.claimedDays.add(7)
        chain.genesis = "fedcba9876543210"
        a.sync()
        assertEquals("fedcba9876543210", a.store.state.genesis)
        assertEquals(id, a.store.state.identity)
        assertEquals("555", a.store.state.identity!!.passportNullifier)
        assertEquals(WalletSync.IdentityStatus.LIVE, a.identityStatus())
        assertTrue(a.store.state.claimedDays.isEmpty())
        assertTrue(a.store.state.rootsVerified)
    }

    /** K6: an indexer's new genesis the LCD does not confirm (or cannot) wipes nothing and syncs nothing. */
    @Test
    fun unverifiedGenesisSwitchKeepsEverything() {
        val chain = FakeChain()
        val a = wallet(chain)
        registered(chain, a)
        val before = a.balances()
        val id = a.store.state.identity
        chain.lcdGenesis = chain.genesis
        chain.genesis = "fedcba9876543210"
        assertThrows(WalletSync.GenesisUnverified::class.java) { a.sync() }
        chain.lcdGenesis = null
        chain.lcdBlind = true
        assertThrows(WalletSync.GenesisUnverified::class.java) { a.sync() }
        assertEquals("0123456789abcdef", a.store.state.genesis)
        assertEquals(before, a.balances())
        assertEquals(id, a.store.state.identity)
        assertFalse(a.store.state.rootsVerified)
        assertTrue(a.store.state.rootsError!!.contains("does not confirm"))
        // A first sync goes ahead when the LCD cannot say, never when it contradicts.
        wallet(chain).sync()
        chain.lcdBlind = false
        chain.lcdGenesis = "1111111111111111"
        assertThrows(WalletSync.GenesisUnverified::class.java) { wallet(chain).sync() }
    }

    /**
     * K7: the registration is recorded by hash the moment the node accepts
     * it and the gas note marked spent, so a wait that times out followed by
     * a killed app loses nothing: the next wallet finds the tx by hash.
     */
    @Test
    fun registrationRecordedAtAcceptance() {
        val chain = FakeChain()
        val store = PrivacyStore.memory()
        val a = wallet(chain, store = store)
        val prep = a.prepareRegistration(null)
        chain.shield("uerth", 100_000, prep.gas.pc, prep.gas.ciphertext)
        a.sync()
        assertEquals(100_000L, bal(a, "uerth"))
        chain.unconfirmedNext = 1
        val sigs = listOf("261001", prep.binding.toBigInteger().toString(), "31337", Fr.of(77).toBigInteger().toString())
        assertThrows(network.erth.wallet.chain.TxUnconfirmedException::class.java) { a.register(prep, ByteArray(14_656), sigs, "lean_poa", ByteArray(10)) }
        val p = store.state.pendingRegistration!!
        assertEquals(chain.txs.keys.single { chain.txs[it]!!.events.any { e -> e.first == "register" } }, p.txHash)
        assertEquals(null, p.leafIndex)
        assertEquals(0L, bal(a, "uerth"))
        // The app is killed; a new wallet over the same store resolves it by hash.
        val b = wallet(chain, store = store)
        b.sync()
        assertEquals(null, b.pendingRegistration)
        assertEquals(WalletSync.IdentityStatus.LIVE, b.identityStatus())
        assertEquals("31337", store.state.identity!!.passportNullifier)
    }

    /** K7: accepted then failed in its block: the failure is kept for the UI, the gas note released later. */
    @Test
    fun registrationFailedInBlockIsShown() {
        val chain = FakeChain()
        val a = wallet(chain)
        val prep = a.prepareRegistration(null)
        chain.shield("uerth", 100_000, prep.gas.pc, prep.gas.ciphertext)
        a.sync()
        chain.failInBlockNext = 1
        val sigs = listOf("261001", prep.binding.toBigInteger().toString(), "1", Fr.of(77).toBigInteger().toString())
        assertThrows(java.io.IOException::class.java) { a.register(prep, ByteArray(14_656), sigs, "lean_poa", ByteArray(10)) }
        a.sync()
        assertTrue(a.pendingRegistration!!.failure!!.startsWith(PrivacyWallet.TX_FAILED))
        assertEquals(0L, bal(a, "uerth"))
        // Audit 3: released only once the chain is past the tx's timeout_height, not by the clock.
        chain.now += WalletSync.PENDING_TIMEOUT_S + 1
        a.sync()
        assertEquals(0L, bal(a, "uerth"))
        repeat(PrivateTxEngine.TIMEOUT_BLOCKS.toInt() + 1) { chain.emptyBlock() }
        a.sync()
        assertEquals(100_000L, bal(a, "uerth"))
        // A new registration replaces it.
        a.register(prep, ByteArray(14_656), sigs, "lean_poa", ByteArray(10))
        a.sync()
        assertEquals(WalletSync.IdentityStatus.LIVE, a.identityStatus())
    }

    /** K1: the record memo, version 2 with its tag (PRIVACY_FORMATS 3a golden). */
    @Test
    fun recordMemoGolden() {
        val k = PrivacyKeys.fromMnemonic(alice)
        val m = WalletSync.regMemo(k.nk, Fr.of(77), "FR", 1_790_000_000L)
        assertEquals(
            "4552024652000000006ab13b80" + "00".repeat(31) + "4d" + "1d3756b83dfd918fa510770bc257079b" + "000000",
            m.joinToString("") { "%02x".format(it.toInt() and 0xff) },
        )
        assertEquals(Triple(Fr.of(77), "FR", 1_790_000_000L), WalletSync.parseRegMemo(k.nk, m))
        // Another wallet's nk, a flipped tag bit, version 1, or junk past the tag: not a record.
        assertEquals(null, WalletSync.parseRegMemo(Fr.of(5), m))
        assertEquals(null, WalletSync.parseRegMemo(k.nk, m.copyOf().also { it[50] = (it[50].toInt() xor 1).toByte() }))
        assertEquals(null, WalletSync.parseRegMemo(k.nk, m.copyOf().also { it[2] = 1 }))
        assertEquals(null, WalletSync.parseRegMemo(k.nk, m.copyOf().also { it[63] = 1 }))
    }

    /** Appends a note row (a v1 note someone sent) at the block being built. */
    private fun sendNote(chain: FakeChain, to: network.erth.wallet.privacy.keys.ShieldedAddress, memo: ByteArray) {
        val n = network.erth.wallet.privacy.note.NotePlaintext.fresh("uerth", 0, memo)
        val cm = n.cm(to.ownerPk)
        val pos = chain.noteTree.append(cm)
        chain.notes.add(network.erth.wallet.privacy.sync.NoteRow(pos, chain.height, cm, network.erth.wallet.privacy.note.NoteCipher.encrypt(n, to), null))
    }

    /**
     * K1: forged record notes (untagged version 1, or version 2 with a
     * guessed tag) in the registration's block cost the restore nothing: not
     * one is kept or searched, and the real record still finds the identity.
     */
    @Test
    fun forgedRecordSpamIsIgnored() {
        val chain = FakeChain()
        val a = wallet(chain)
        val prep = a.prepareRegistration(null)
        chain.shield("uerth", 100_000, prep.gas.pc, prep.gas.ciphertext)
        a.sync()
        val k = PrivacyKeys.fromMnemonic(alice)
        repeat(16) { i ->
            val forged = WalletSync.regMemo(Fr.of(1000L + i), Fr.of(77), "DE", chain.now + i)
            sendNote(chain, a.address, forged)
            sendNote(chain, a.address, forged.copyOf().also { it[2] = 1 }.copyOf(45).copyOf(64))
        }
        a.register(prep, ByteArray(14_656), listOf("261001", prep.binding.toBigInteger().toString(), "9", Fr.of(77).toBigInteger().toString()), "lean_poa", ByteArray(10))
        a.sync()
        val restored = wallet(chain)
        val t0 = System.nanoTime()
        restored.sync()
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(1, restored.store.state.regRecords.size)
        assertEquals(WalletSync.IdentityStatus.LIVE, restored.identityStatus())
        // The chain's block time: one country pass for one leaf, nothing like the 3M-hash search per forged record.
        assertTrue(restored.store.state.regRecords.single().work <= 2 * 677)
        assertTrue("restore took $ms ms", ms < 20_000)
        assertEquals(k.nk, a.keys.nk)
    }

    /**
     * K1: with no block time from the node, the fallback search is bounded
     * per sync and resumes from its persisted cursor after a kill, never
     * redoing work, and still finds a registration whose device clock was
     * 22 hours behind (83,601 steps of the outward walk).
     */
    @Test
    fun fallbackSearchIsBoundedAndResumes() {
        val chain = FakeChain()
        // The country the record hints (none: the DSC is unparsable here), so the narrow pass finds it.
        chain.registrationCountry = ""
        val skewed = PrivacyWallet(
            PrivacyKeys.fromMnemonic(alice), PrivacyStore.memory(), chain, chain, reads(chain), chain.prover, chain.chainId, chain,
            now = { chain.now - 80_000 },
        )
        val prep = skewed.prepareRegistration(null)
        chain.shield("uerth", 100_000, prep.gas.pc, prep.gas.ciphertext)
        skewed.sync()
        skewed.register(prep, ByteArray(14_656), listOf("261001", prep.binding.toBigInteger().toString(), "9", Fr.of(77).toBigInteger().toString()), "lean_poa", ByteArray(10))
        chain.blockTimesPruned = true
        chain.identityRowTimes = false
        val dir = java.nio.file.Files.createTempDirectory("k1").toFile()
        val keys = PrivacyKeys.fromMnemonic(alice)
        val budget = 30_000L
        var works = ArrayList<Long>()
        var syncs = 0
        while (true) {
            // A fresh store object each time: what a killed and restarted app reads back from disk.
            val store = PrivacyStore.open(dir, "w")
            WalletSync(chain, store, keys, chain.chainId, chain, { chain.now }, searchBudget = budget).sync()
            val rec = store.state.regRecords.single()
            works.add(rec.work)
            syncs++
            if (rec.status != network.erth.wallet.privacy.sync.RecordStatus.OPEN) break
            assertTrue(syncs < 20)
        }
        val store = PrivacyStore.open(dir, "w")
        assertEquals(network.erth.wallet.privacy.sync.RecordStatus.MATCHED, store.state.regRecords.single().status)
        assertTrue(syncs >= 3)
        // Each sync spent at most its budget (plus one step), and none started over.
        works.zipWithNext().forEach { (x, y) -> assertTrue(y - x in 1..budget + 2) }
        assertTrue(works.first() <= budget + 2)
        assertEquals(chain.identityTree.leaf(store.state.identity!!.leafIndex), Privacy.identityLeaf(keys.idc, store.state.identity!!.dscKey, store.state.identity!!.country, store.state.identity!!.activatedAt, store.state.identity!!.predecessorAt))
    }

    /**
     * K11: positions closed before a restore are known from their unlock
     * memos, so the restored wallet's next lock never reuses a tag the chain
     * has already seen (without them it would take counter 1 again).
     */
    @Test
    fun restoredWalletNeverReusesAClosedTag() {
        val chain = FakeChain()
        val v = "earthvaloper1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"
        val a = wallet(chain)
        funded(chain, a, 5_000_000)
        a.sync()
        a.delegate(v, 3_000_000)
        a.sync()
        repeat(3) { a.lockPosition(v, 100_000, mapOf(2L to 100L)); a.sync() }
        val used = chain.positions.values.map { it.ownerTag }.toSet()
        assertEquals(3, used.size)
        for ((p, c) in a.positions().filter { it.second >= 1 }) { a.unlockPosition(p, c); a.sync() }
        assertEquals(listOf(0), a.positions().map { it.second })
        assertEquals(2, a.store.state.closedOtagMax)
        val restored = wallet(chain)
        restored.sync()
        assertEquals(2, restored.store.state.closedOtagMax)
        assertEquals(listOf(0), restored.positions().map { it.second })
        restored.lockPosition(v, 100_000, mapOf(2L to 100L))
        restored.sync()
        val fresh = chain.positions.values.last().ownerTag
        assertFalse(fresh in used)
        assertEquals(listOf(0, 3), restored.positions().map { it.second })
        // A gift of stake carrying someone else's (untagged) unlock memo is ignored.
        assertEquals(null, WalletSync.parseUnlockMemo(a.keys.nk, WalletSync.unlockMemo(Fr.of(9), 1_000_000)))
    }

    /** Two validators' derth and a position, all before proposal 12's snapshot. */
    private fun staked(chain: FakeChain): PrivacyWallet {
        val v1 = "earthvaloper1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"
        val v2 = "earthvaloper1qyqszqgpqyqszqgpqyqszqgpqyqszqgpjnp7du"
        val a = wallet(chain)
        repeat(4) { funded(chain, a, 2_000_000) }
        a.sync()
        a.delegate(v1, 1_000_000); a.sync()
        a.delegate(v2, 1_000_000); a.sync()
        a.lockPosition(v1, 100_000, mapOf(2L to 100L)); a.sync()
        chain.openProposal(12)
        return a
    }

    /**
     * K5, as the chain now takes it (48b631c): one vote per validator and one
     * per position, each its own tx the user confirms; nothing is cast in the
     * background. A validator voted once is not voted again.
     */
    @Test
    fun stakeVotesAreOneTxPerValidatorAndPosition() {
        val chain = FakeChain()
        val a = staked(chain)
        a.sync()
        val items = a.stakeVoteItems(12)
        assertEquals(3, items.size)
        assertEquals(2, items.count { it is PrivacyWallet.StakeVoteItem.Validator })
        val before = chain.height
        for (item in items) {
            org.junit.Assert.assertNotNull(a.castStakeVote(12, item, yes))
            a.sync()
        }
        assertEquals(2, chain.stakeVotes.size)
        assertEquals(1, chain.positionVotes.size)
        assertEquals(before + 3, chain.height)
        // Final: the validators' notes have voted.
        assertEquals(null, a.castStakeVote(12, items.first(), yes))
        assertEquals(2, chain.stakeVotes.size)
    }

    /** Info: a claim for day 0 is refused (iOS trapped on the underflow). */
    @Test
    fun claimDayZeroIsRefused() {
        val chain = FakeChain()
        val a = wallet(chain)
        registered(chain, a)
        assertThrows(IllegalArgumentException::class.java) { a.claimAnml(0) }
    }

    /** K10: a status naming no chain is refused. */
    @Test
    fun nullChainIdIsRefused() {
        val chain = FakeChain()
        val idx = object : Wrapped(chain) {
            override fun status() = inner.status().copy(chainId = null)
        }
        assertThrows(IllegalStateException::class.java) { wallet(chain, indexer = idx).sync() }
    }

    /** K9: an indexer trailing the chain's tip is labelled unverified. */
    @Test
    fun indexerBehindTipIsUnverified() {
        val chain = FakeChain()
        val a = wallet(chain)
        funded(chain, a)
        chain.tipAhead = WalletSync.STALE_BLOCKS + 5
        assertFalse(a.sync().verified)
        assertTrue(a.store.state.rootsError!!.contains("blocks behind"))
        chain.tipAhead = 0
        assertTrue(a.sync().verified)
    }
}
