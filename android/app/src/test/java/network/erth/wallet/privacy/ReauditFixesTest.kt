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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
        override fun ballotInputs(proposalId: Long, optionId: Long) =
            PrivacyChainReads.BallotInputs(Privacy.proposalScope(proposalId, 0), Fr.ZERO, Fr.ZERO, chain.now - 3600, 0, 0)
        override fun epochNumber() = chain.epoch
        override fun snapshot(proposalId: Long) = PrivacyChainReads.Snapshot(chain.stakeTree.rootAt(chain.stakeTree.size), chain.stakeTree.size)
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
        chain.now += WalletSync.PENDING_TIMEOUT_S + 1
        a.sync()
        assertEquals(100_000L, bal(a, "uerth"))
        // A new registration replaces it.
        a.register(prep, ByteArray(14_656), sigs, "lean_poa", ByteArray(10))
        a.sync()
        assertEquals(WalletSync.IdentityStatus.LIVE, a.identityStatus())
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
