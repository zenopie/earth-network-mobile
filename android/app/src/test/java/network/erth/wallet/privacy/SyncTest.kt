package network.erth.wallet.privacy

import java.io.File
import network.erth.wallet.privacy.keys.PrivacyKeys
import network.erth.wallet.privacy.note.NoteCipher
import network.erth.wallet.privacy.note.NotePlaintext
import network.erth.wallet.privacy.sync.ChainRoots
import network.erth.wallet.privacy.sync.HeightPage
import network.erth.wallet.privacy.sync.IdentityPage
import network.erth.wallet.privacy.sync.LatestRoots
import network.erth.wallet.privacy.sync.NoteRow
import network.erth.wallet.privacy.sync.NotesPage
import network.erth.wallet.privacy.sync.PrivacyIndexer
import network.erth.wallet.privacy.sync.PrivacyStore
import network.erth.wallet.privacy.sync.RootRecord
import network.erth.wallet.privacy.sync.WalletSync
import network.erth.wallet.privacy.zk.Fr
import network.erth.wallet.privacy.zk.MemNodeStore
import network.erth.wallet.privacy.zk.MerkleTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a sync trusts: every tree the indexer serves is checked against the
 * chain's own roots and tip; a sync that fails part-way leaves nothing
 * verified; nullifiers are spot-checked without naming ours; and a sync never
 * sends anything.
 */
class SyncTest : WalletTest() {
    private fun tmp(): File = java.nio.file.Files.createTempDirectory("privacy-store").toFile()

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

    private fun staked(chain: FakeChain, due: (Long) -> Long? = { null }): PrivacyWallet {
        val a = wallet(chain, r = reads(chain, due = due))
        repeat(4) { funded(chain, a, 2_000_000) }
        a.sync()
        a.delegate(vB, 1_000_000); a.sync()
        return a
    }

    private val vB = "earthvaloper1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"

    /**
     * An indexer appends a forged 50 ERTH note and then fails a later
     * stream of the same sync. The roots were marked unverified (and
     * persisted) before the first request, so nothing is labelled verified
     * and no tx is built on the forged tree.
     */
    @Test
    fun failedSyncLeavesNothingVerified() {
        val chain = FakeChain()
        var forge = false
        val idx = object : Wrapped(chain) {
            override fun notes(fromPos: Long, limit: Int?): NotesPage {
                if (!forge) return inner.notes(fromPos, limit)
                val w = PrivacyKeys.fromMnemonic(alice)
                val fake = NotePlaintext.fresh("uerth", 50_000_000)
                val cm = fake.cm(w.ownerPk)
                val rows = chain.notes + NoteRow(chain.notes.size.toLong(), chain.height - 1, cm, NoteCipher.encrypt(fake, w.address), null)
                val page = rows.drop(fromPos.toInt())
                return NotesPage(page, fromPos + page.size, false, chain.height - 1)
            }
            override fun identity(fromIndex: Long, limit: Int?): IdentityPage =
                if (forge) throw java.io.IOException("identity stream broke (test)") else inner.identity(fromIndex, limit)
        }
        val dir = tmp()
        val a = wallet(chain, indexer = idx, store = PrivacyStore.open(dir, "w"))
        funded(chain, a)
        a.sync()
        assertTrue(a.store.state.rootsVerified)
        assertEquals(a.store.state.syncGeneration, a.store.state.verifiedGeneration)
        forge = true
        assertThrows(java.io.IOException::class.java) { a.sync() }
        assertFalse(a.store.state.rootsVerified)
        assertEquals(WalletSync.SYNC_UNFINISHED, a.store.state.rootsError)
        // Persisted before the first request: a restarted app reads it back unverified.
        assertFalse(PrivacyStore.open(dir, "w").state.rootsVerified)
        val before = chain.height
        val e = assertThrows(IllegalStateException::class.java) { a.unshield(receiver, "uerth", 40_000_000) }
        assertEquals(WalletSync.SYNC_UNFINISHED, e.message)
        assertEquals(before, chain.height)
        assertEquals(0, chain.simulated)
        // The indexer recovers: the next sync starts over (the forged row is inconsistent) and verifies again.
        forge = false
        assertTrue(a.sync().verified)
        assertEquals(1_000_000L, bal(a, "uerth"))
    }

    /** A verified flag left from an older generation is not enough for a tx. */
    @Test
    fun verifiedFlagOfAnotherGenerationIsRefused() {
        val chain = FakeChain()
        val a = wallet(chain)
        funded(chain, a)
        a.sync()
        a.store.state.syncGeneration++
        assertThrows(IllegalStateException::class.java) { a.unshield(receiver, "uerth", 1000) }
        a.sync()
        a.unshield(receiver, "uerth", 1000)
    }

    /** An indexer that keeps serving well-formed pages forever is cut off by the sync's time limit. */
    @Test
    fun syncHasAnOverallTimeLimit() {
        val chain = FakeChain()
        val idx = object : Wrapped(chain) {
            override fun notes(fromPos: Long, limit: Int?): NotesPage {
                val rows = (0 until 4).map { i -> NoteRow(fromPos + i, 1, Fr.of(fromPos + i + 1), ByteArray(0), null) }
                return NotesPage(rows, fromPos + rows.size, true, chain.height - 1)
            }
        }
        var t = 0L
        val sync = WalletSync(idx, PrivacyStore.memory(), PrivacyKeys.fromMnemonic(alice), chain.chainId, chain, { chain.now },
            syncTimeoutMs = WalletSync.SYNC_TIMEOUT_MS, monoMs = { t += 60_000; t })
        assertThrows(WalletSync.SyncTimeout::class.java) { sync.sync() }
    }

    @Test
    fun nullifierSampleExcludesOurOwn() {
        val chain = FakeChain()
        val asked = ArrayList<Fr>()
        val roots = object : ChainRoots by chain {
            override fun nullifierSpent(nf: Fr): Boolean? { asked.add(nf); return chain.nullifierSpent(nf) }
        }
        val a = wallet(chain, roots = roots)
        funded(chain, a)
        a.sync()
        a.unshield(receiver, "uerth", 1000)
        val other = wallet(FakeChain())
        a.sync()
        val ours = a.notes.map { it.nf }.toSet()
        assertTrue(ours.isNotEmpty())
        assertTrue(asked.none { it in ours })
        assertNotNull(other)
    }

    /**
     * One hostile response (a notes page naming synced_height 10^9, a
     * nullifier page jumping next_height) must not move the persisted
     * nullifier cursor past every future height: any height past the LCD tip
     * is Inconsistent, nothing is persisted from it, and with an honest
     * indexer the spend is seen.
     */
    @Test
    fun heightsPastTheTipAreRefused() {
        val chain = FakeChain()
        var poison = false
        val far = 1_000_000_000L
        val idx = object : Wrapped(chain) {
            override fun notes(fromPos: Long, limit: Int?): NotesPage =
                inner.notes(fromPos, limit).let { if (poison) it.copy(syncedHeight = far) else it }
            override fun nullifiers(fromHeight: Long, limit: Int?): HeightPage<Fr> =
                if (poison) HeightPage(emptyList(), far + 1, false, far) else inner.nullifiers(fromHeight, limit)
        }
        val a = wallet(chain, indexer = idx)
        funded(chain, a)
        assertTrue(a.sync().verified)
        poison = true
        assertThrows(WalletSync.Inconsistent::class.java) { a.sync() }
        assertFalse(a.store.state.rootsVerified)
        assertTrue(a.store.state.nullifiersNext <= chain.latestHeight() + WalletSync.TIP_SLACK + 1)
        poison = false
        assertTrue(a.sync().verified)
        a.unshield(receiver, "uerth", 1000)
        while (chain.tipHeight() <= chain.lastTimeoutHeight) chain.emptyBlock()
        assertTrue(a.sync().verified)
        // The spent note is spent; only its change is spendable.
        assertTrue(a.notes.none { it.unspent && it.note.value == 1_000_000L })
        assertTrue((a.poolBalances()["uerth"] ?: 0L) < 1_000_000L)
    }

    /** Every height a page can carry is bounded, a stake page's and /roots/latest's too. */
    @Test
    fun everyIndexerHeightIsBounded() {
        val chain = FakeChain()
        val far = chain.latestHeight() + WalletSync.TIP_SLACK + 50
        val cases: List<PrivacyIndexer> = listOf(
            object : Wrapped(chain) { override fun rootsLatest() = inner.rootsLatest().copy(syncedHeight = far) },
            object : Wrapped(chain) { override fun stakeNullifiers(fromHeight: Long, limit: Int?) = HeightPage(listOf(far to listOf(Fr.of(9))), far + 1, false, far) },
            object : Wrapped(chain) { override fun identityZeroed(fromHeight: Long, limit: Int?) = HeightPage<Long>(emptyList(), far + 1, false, 1) },
        )
        for ((i, idx) in cases.withIndex()) {
            val a = wallet(chain, indexer = idx)
            funded(chain, a)
            assertThrows("case $i", WalletSync.Inconsistent::class.java) { a.sync() }
        }
    }

    /**
     * An indexer frozen at H0 that names the current height passes the
     * "behind the tip" check (its own synced_height); the chain's note tree
     * at the claimed height (pinned) says otherwise.
     */
    @Test
    fun staleIndexerClaimingTheTipIsUnverified() {
        val chain = FakeChain()
        val a = wallet(chain)
        funded(chain, a)
        a.sync()
        val n0 = chain.notes.size
        val h0 = chain.height
        val frozenTree = MerkleTree(MemNodeStore()).apply { appendAll(chain.notes.take(n0).map { it.cm }) }
        val rootHeight = chain.noteRootHeights[frozenTree.root()]!!
        val frozen = object : Wrapped(chain) {
            override fun notes(fromPos: Long, limit: Int?): NotesPage {
                val rows = chain.notes.take(n0).drop(fromPos.toInt())
                return NotesPage(rows, fromPos + rows.size, false, chain.height - 1)
            }
            override fun nullifiers(fromHeight: Long, limit: Int?): HeightPage<Fr> {
                val p = inner.nullifiers(fromHeight, limit)
                return HeightPage(p.blocks.filter { it.first < h0 }, p.nextHeight, false, p.syncedHeight)
            }
            override fun rootsLatest(): LatestRoots =
                LatestRoots(RootRecord(frozenTree.root(), frozenTree.size, rootHeight, chain.now), null, chain.height - 1, null)
        }
        val other = wallet(chain)
        other.sync()
        other.unshield(receiver, "uerth", 1000)
        repeat(100) { chain.emptyBlock() }
        val lied = wallet(chain, indexer = frozen)
        assertFalse(lied.sync().verified)
        assertTrue(lied.store.state.rootsError!!, "note tree" in lied.store.state.rootsError!!)
        assertThrows(IllegalStateException::class.java) { lied.unshield(receiver, "uerth", 1000) }
    }

    /** The indexer must date its note root as the chain recorded it (RootRecord.height). */
    @Test
    fun noteRootHeightMustBeTheChains() {
        val chain = FakeChain()
        val idx = object : Wrapped(chain) {
            override fun rootsLatest() = inner.rootsLatest().let { r -> r.copy(note = r.note!!.copy(height = r.note!!.height - 1)) }
        }
        val a = wallet(chain, indexer = idx)
        funded(chain, a)
        chain.emptyBlock()
        assertFalse(a.sync().verified)
        assertTrue("dates its note root" in a.store.state.rootsError!!)
    }

    /**
     * An indexer serving a note the chain never had (encrypted to
     * us, with roots to match) leaves the wallet unverified: nothing is
     * built on it, and the honest indexer's stream replaces it.
     */
    @Test
    fun forgedIndexerTreesAreRefused() {
        val chain = FakeChain()
        val a = wallet(chain)
        val o = a.shieldOutput("uerth", 0)
        chain.shield("uerth", 1_000_000, o.pc, o.ciphertext)
        val fake = NotePlaintext.fresh("uerth", 50_000_000)
        val fakeCm = fake.cm(a.keys.ownerPk)
        var forge = true
        val idx = object : Wrapped(chain) {
            private fun tree(): MerkleTree = MerkleTree(MemNodeStore()).apply { appendAll(chain.notes.map { it.cm } + fakeCm) }
            override fun notes(fromPos: Long, limit: Int?): NotesPage {
                if (!forge) return inner.notes(fromPos, limit)
                val rows = chain.notes + NoteRow(chain.notes.size.toLong(), chain.height - 1, fakeCm, NoteCipher.encrypt(fake, a.address), null)
                val page = rows.drop(fromPos.toInt())
                return NotesPage(page, fromPos + page.size, false, chain.height - 1)
            }
            override fun rootsLatest(): LatestRoots {
                val r = inner.rootsLatest()
                if (!forge) return r
                val t = tree()
                return r.copy(note = RootRecord(t.root(), t.size, chain.height - 1, chain.now))
            }
        }
        val w = wallet(chain, indexer = idx)
        assertEquals(false, w.sync().verified)
        // Nothing synced from it is spendable.
        assertTrue(w.store.state.rootsError!!.contains("no longer holds"))
        assertThrows(IllegalStateException::class.java) { w.unshield("earth1qqqsyqcyq5rqwzqfpg9scrgwpugpzysncc2uls", "uerth", 1) }
        forge = false
        w.sync()
        assertEquals(1_000_000L, bal(w, "uerth"))
        assertTrue(w.store.state.rootsVerified)
    }

    /** An identity tree the chain contradicts at the indexer's own height wipes what was synced. */
    @Test
    fun forgedIdentityTreeIsAMismatch() {
        val chain = FakeChain()
        val a = wallet(chain)
        val o = a.shieldOutput("uerth", 0)
        chain.shield("uerth", 1_000_000, o.pc, o.ciphertext)
        val idx = object : Wrapped(chain) {
            val leaf = Fr.of(99)
            override fun identity(fromIndex: Long, limit: Int?) =
                network.erth.wallet.privacy.sync.IdentityPage(
                    if (fromIndex == 0L) listOf(network.erth.wallet.privacy.sync.IdentityRow(0, chain.height - 1, leaf, null)) else emptyList(),
                    1, 1, chain.height - 1,
                )
            override fun rootsLatest(): LatestRoots {
                val t = MerkleTree(MemNodeStore()).apply { append(leaf) }
                return inner.rootsLatest().copy(identity = RootRecord(t.root(), 1, chain.height - 1, chain.now))
            }
        }
        val w = wallet(chain, indexer = idx)
        assertThrows(WalletSync.ChainMismatch::class.java) { w.sync() }
        assertTrue(w.balances().isEmpty())
        assertTrue(w.store.state.rootsError!!.contains("identity tree"))
    }

    /** The indexer is two weeks behind and the chain pruned its root: unverified, nothing wiped. */
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

    /** A node answering a pinned query at another height cannot condemn the trees; equal ones still verify. */
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

    /** A spend the chain does not hold, slipped into the nullifier stream, is caught by the sample. */
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

    /** An indexer trailing the chain's tip is labelled unverified. */
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
