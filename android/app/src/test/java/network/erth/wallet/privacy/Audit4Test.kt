package network.erth.wallet.privacy

import cosmos.gov.v1.VoteOption as GovVoteOption
import cosmos.gov.v1.WeightedVoteOption
import kotlinx.coroutines.runBlocking
import network.erth.wallet.privacy.keys.PrivacyKeys
import network.erth.wallet.privacy.note.NoteCipher
import network.erth.wallet.privacy.note.NotePlaintext
import network.erth.wallet.privacy.sync.ChainRoots
import network.erth.wallet.privacy.sync.HeightPage
import network.erth.wallet.privacy.sync.HttpPrivacyIndexer
import network.erth.wallet.privacy.sync.IdentityPage
import network.erth.wallet.privacy.sync.IdentityRow
import network.erth.wallet.privacy.sync.LatestRoots
import network.erth.wallet.privacy.sync.NotesPage
import network.erth.wallet.privacy.sync.PrivacyIndexer
import network.erth.wallet.privacy.sync.PrivacyStore
import network.erth.wallet.privacy.sync.RootRecord
import network.erth.wallet.privacy.sync.StakeNfLeavesPage
import network.erth.wallet.privacy.sync.StakeSnapshotRow
import network.erth.wallet.privacy.sync.StakeSnapshotsPage
import network.erth.wallet.privacy.sync.WalletSync
import network.erth.wallet.privacy.tx.PrivateChain
import network.erth.wallet.privacy.tx.TxResult
import network.erth.wallet.privacy.tx.UnsignedTx
import network.erth.wallet.privacy.zk.Fr
import network.erth.wallet.privacy.zk.IndexedTree
import network.erth.wallet.privacy.zk.MemNodeStore
import network.erth.wallet.privacy.zk.MerkleTree
import network.erth.wallet.privacy.zk.Privacy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * The fourth audit's client findings, the auditor's PoCs
 * (audit4/clients/{android,ios,vote}) ported to assert the safe outcome.
 */
class Audit4Test {
    private val alice = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private val receiver = "earth1qqqsyqcyq5rqwzqfpg9scrgwpugpzysncc2uls"
    private val vB = "earthvaloper1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"
    private val yes = listOf(WeightedVoteOption.newBuilder().setOption(GovVoteOption.VOTE_OPTION_YES).setWeight("1").build())

    private fun reads(chain: FakeChain, snapshot: (Long) -> PrivacyChainReads.Snapshot = chain::snapshotRead,
                      nfTree: (Long, Int) -> PrivacyChainReads.NfTreePage = chain::nfTreeRead) = object : PrivacyChainReads {
        override fun personhoodParams() = PrivacyChainReads.PersonhoodParams(30L * 86_400, 3_600)
        override fun ballotInputs(proposalId: Long, optionId: Long) =
            PrivacyChainReads.BallotInputs(Privacy.proposalScope(proposalId, 0), Fr.ZERO, Fr.ZERO, Privacy.NO_BOUND, 0, 0, chain.ballotMaxPredecessor())
        override fun epochNumber() = chain.epoch
        override fun snapshot(proposalId: Long) = snapshot(proposalId)
        override fun stakeNullifierTree(start: Long, limit: Int) = nfTree(start, limit)
        override fun positions() = chain.positionReads()
    }

    private fun wallet(
        chain: FakeChain,
        indexer: PrivacyIndexer = chain,
        store: PrivacyStore = PrivacyStore.memory(),
        roots: ChainRoots = chain,
        r: PrivacyChainReads = reads(chain),
        pc: PrivateChain = chain,
    ) = PrivacyWallet(PrivacyKeys.fromMnemonic(alice), store, indexer, pc, r, chain.prover, chain.chainId, roots, now = { chain.now })

    private open class Wrapped(val inner: PrivacyIndexer) : PrivacyIndexer by inner

    private fun funded(chain: FakeChain, w: PrivacyWallet, amount: Long = 1_000_000) {
        val o = w.shieldOutput("uerth", 0)
        chain.shield("uerth", amount, o.pc, o.ciphertext)
    }

    private fun register(chain: FakeChain, w: PrivacyWallet) {
        val prep = w.prepareRegistration(null)
        chain.shield("uerth", 100_000, prep.gas.pc, prep.gas.ciphertext)
        w.sync()
        w.register(prep, ByteArray(14_656), listOf("261001", prep.binding.toBigInteger().toString(), "555", Fr.of(77).toBigInteger().toString()), "lean_poa", ByteArray(10))
        w.sync()
        assertEquals(WalletSync.IdentityStatus.LIVE, w.identityStatus())
    }

    // ---- M1: indexer heights bounded by the LCD tip (PoC 1) ----

    /**
     * PoC 1: one hostile response (a notes page naming synced_height 10^9,
     * a nullifier page jumping next_height) used to move the persisted
     * nullifier cursor past every future height, and the wallet stayed
     * "verified". Now any height past the LCD tip is Inconsistent: nothing
     * is persisted from it, and with an honest indexer the spend is seen.
     */
    @Test
    fun poc1_heightsPastTheTipAreRefused() {
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

    // ---- M1: a stale indexer claiming the tip (PoC 2) ----

    /**
     * PoC 2: an indexer frozen at H0 that names the current height used to
     * pass the "behind the tip" check (its own synced_height). The chain's
     * note tree at the claimed height (pinned) now says otherwise.
     */
    @Test
    fun poc2_staleIndexerClaimingTheTipIsUnverified() {
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

    // ---- M2: the restore cover set (PoC 5) ----

    /**
     * PoC 5: the cover set used to be drawn from [1, the indexer's own
     * synced_height]; inflated, every decoy was past the tip and the LCD saw
     * one real height. Inflated now fails the sync before any ask, and an
     * honest restore without row times asks [COVER_SET] heights, every one
     * of them a block the chain has.
     */
    @Test
    fun poc5_coverSetIsNeverPastTheTip() {
        val chain = FakeChain()
        val w = wallet(chain)
        register(chain, w)
        val regHeight = chain.identityRows.single().height
        repeat(40) { chain.emptyBlock() }
        chain.identityRowTimes = false
        chain.blockTimeAsks.clear()
        val inflated = object : Wrapped(chain) {
            override fun notes(fromPos: Long, limit: Int?): NotesPage = inner.notes(fromPos, limit).copy(syncedHeight = 1_000_000_000L)
        }
        assertThrows(WalletSync.Inconsistent::class.java) { wallet(chain, indexer = inflated).sync() }
        assertTrue(chain.blockTimeAsks.isEmpty())

        val r = wallet(chain)
        r.sync()
        val tip = chain.latestHeight()
        assertEquals(WalletSync.IdentityStatus.LIVE, r.identityStatus())
        assertEquals(WalletSync.COVER_SET, chain.blockTimeAsks.size)
        assertTrue(chain.blockTimeAsks.all { it in 1..tip })
        assertTrue(regHeight in chain.blockTimeAsks)
    }

    /** Decoys are drawn from identity row heights first (other registrations' blocks). */
    @Test
    fun coverSetPrefersIdentityRowHeights() {
        val chain = FakeChain()
        // Other people's registrations, each in its own block.
        repeat(20) { i ->
            val o = PrivacyWallet(PrivacyKeys.fromMnemonic(network.erth.wallet.crypto.WalletCrypto.generateMnemonic()), PrivacyStore.memory(), chain, chain,
                reads(chain), chain.prover, chain.chainId, chain, now = { chain.now })
            val prep = o.prepareRegistration(null)
            chain.shield("uerth", 100_000, prep.gas.pc, prep.gas.ciphertext)
            o.sync()
            o.register(prep, ByteArray(14_656), listOf("261001", prep.binding.toBigInteger().toString(), "${1000 + i}", Fr.of(77).toBigInteger().toString()), "lean_poa", ByteArray(10))
        }
        val w = wallet(chain)
        register(chain, w)
        repeat(40) { chain.emptyBlock() }
        chain.identityRowTimes = false
        chain.blockTimeAsks.clear()
        val r = wallet(chain)
        r.sync()
        assertEquals(WalletSync.IdentityStatus.LIVE, r.identityStatus())
        val rows = chain.identityRows.map { it.height }.toSet()
        assertEquals(WalletSync.COVER_SET, chain.blockTimeAsks.size)
        assertTrue(chain.blockTimeAsks.all { it in rows })
    }

    // ---- H1 / M5: forged identity rows (iOS PoCs A4, C) ----

    private fun forgeIdentity(chain: FakeChain, t: Long): Wrapped {
        val keys = PrivacyKeys.fromMnemonic(alice)
        val forge: (IdentityRow) -> IdentityRow = { r ->
            IdentityRow(r.index, r.height, Privacy.identityLeaf(keys.idc, Fr.of(77), Fr.ZERO, t, 0), r.zeroedHeight, t)
        }
        return object : Wrapped(chain) {
            override fun identity(fromIndex: Long, limit: Int?): IdentityPage = inner.identity(fromIndex, limit).let { p -> p.copy(rows = p.rows.map(forge)) }
            override fun rootsLatest(): LatestRoots = inner.rootsLatest().let { r ->
                val tree = MerkleTree(MemNodeStore()).apply { appendAll(chain.identityRows.map { forge(it).leaf }) }
                r.copy(identity = RootRecord(tree.root(), tree.size, r.identity!!.height, 0))
            }
        }
    }

    /** An identity row carrying a block time the chain cannot have (2^63-1, 0 < t < 2025) is Inconsistent. */
    @Test
    fun identityRowTimesAreBounded() {
        val chain = FakeChain()
        register(chain, wallet(chain))
        for (t in listOf(Long.MAX_VALUE, chain.now + WalletSync.TIME_SLACK + 1, 1_000L)) {
            val idx = object : Wrapped(chain) {
                override fun identity(fromIndex: Long, limit: Int?) = inner.identity(fromIndex, limit).let { p -> p.copy(rows = p.rows.map { it.copy(time = t) }) }
            }
            assertThrows(WalletSync.Inconsistent::class.java) { wallet(chain, indexer = idx).sync() }
        }
    }

    /**
     * iOS PoC C (claimOpensAt trap): forged rows naming activated_at = T with
     * a root the LCD cannot pin. The tree is unverified, so no record is
     * matched against it at all: no identity, claimOpensAt null, no crash.
     */
    @Test
    fun forgedIdentityOnAnUnverifiedTreeIsNeverMatched() {
        val chain = FakeChain()
        register(chain, wallet(chain))
        repeat(5) { chain.emptyBlock() }
        chain.echoOtherHeight = true
        val w = wallet(chain, indexer = forgeIdentity(chain, chain.now - 10))
        assertFalse(w.sync().verified)
        assertNull(w.store.state.identity)
        assertNull(w.claimOpensAt())
    }

    /**
     * iOS PoC C (forged activated_at survives a ChainMismatch): pinned, the
     * forged tree is a mismatch and wiped, and no identity was ever matched
     * against it; an honest indexer afterwards finds the real one.
     */
    @Test
    fun forgedIdentityNeverSurvivesAMismatch() {
        val chain = FakeChain()
        register(chain, wallet(chain))
        val realAt = chain.identityRows[0].time!!
        repeat(5) { chain.emptyBlock() }
        val store = PrivacyStore.memory()
        val lying = wallet(chain, indexer = forgeIdentity(chain, chain.now - 10_000), store = store)
        assertThrows(WalletSync.ChainMismatch::class.java) { lying.sync() }
        assertNull(store.state.identity)
        val honest = wallet(chain, store = store)
        honest.sync()
        assertEquals(WalletSync.IdentityStatus.LIVE, honest.identityStatus())
        assertEquals(realAt, store.state.identity!!.activatedAt)
        assertTrue(store.state.identity!!.verified)
    }

    /** A reset keeps only a verified identity (a store from before audit 4 holds an unverified one). */
    @Test
    fun resetDropsAnUnverifiedIdentity() {
        val chain = FakeChain()
        val w = wallet(chain)
        register(chain, w)
        val id = w.store.state.identity!!
        assertTrue(id.verified)
        w.store.reset(chain.chainId)
        assertEquals(id, w.store.state.identity)
        w.store.state.identity = id.copy(verified = false)
        w.store.reset(chain.chainId)
        assertNull(w.store.state.identity)
        // Found again (its record note) by the next sync, verified.
        w.sync()
        assertEquals(WalletSync.IdentityStatus.LIVE, w.identityStatus())
        assertEquals(id.copy(passportNullifier = w.store.state.identity!!.passportNullifier), w.store.state.identity)
    }

    /** claimOpensAt never wraps: an activated_at at the end of time has no answer. */
    @Test
    fun claimOpensAtIsChecked() {
        val chain = FakeChain(now = Long.MAX_VALUE - 1000)
        val w = wallet(chain)
        register(chain, w)
        assertNull(w.claimOpensAt())
    }

    // ---- M3: the snapshot is the chain's (vote PoC) ----

    private class Voting(val chain: FakeChain, val a: PrivacyWallet, val k: network.erth.wallet.privacy.note.OwnedStakeNote)

    /** k delegated before proposal 1's snapshot and restaked (spent) after it: on chain it still votes. */
    private fun voting(): Voting {
        val chain = FakeChain()
        val a = wallet(chain)
        repeat(6) { funded(chain, a, 2_000_000) }
        a.sync()
        a.delegate(vB, 666_666); a.sync()
        val k = a.stakeNotes.single()
        // Something in the nullifier tree before the snapshot.
        a.delegate(vB, 333_333); a.sync()
        val m = a.stakeNotes.maxBy { it.position }
        a.restake(vB, listOf(m), listOf(m.amount / 2, m.amount - m.amount / 2)); a.sync()
        chain.openProposal(1)
        a.restake(vB, listOf(a.stakeNotes.first { it.position == k.position }), listOf(k.amount / 2, k.amount - k.amount / 2)); a.sync()
        return Voting(chain, a, k)
    }

    /**
     * Vote PoC: a hostile indexer forges the snapshot's nf_root (adding k's
     * post-snapshot nullifier). The wallet used to take it, classify k as
     * spent before the snapshot and silently skip the vote. The snapshot now
     * comes from the LCD: k votes.
     */
    @Test
    fun voteForgedIndexerSnapshotDoesNotSuppressTheVote() {
        val v = voting()
        val chain = v.chain
        val forged = object : Wrapped(chain) {
            override fun stakeNullifierLeaves(fromIndex: Long, limit: Int?): StakeNfLeavesPage {
                val p = inner.stakeNullifierLeaves(fromIndex, limit)
                return p.copy(leaves = p.leaves + ((p.leaves.lastOrNull()?.first ?: 0L) + 1 to v.k.nf), size = p.size + 1)
            }
            override fun stakeSnapshots(fromHeight: Long, limit: Int?): StakeSnapshotsPage = inner.stakeSnapshots(fromHeight, limit).let { p ->
                p.copy(rows = p.rows.map { r ->
                    val values = chain.stakeNfValues.take((r.nfSize - 1).toInt()) + v.k.nf
                    StakeSnapshotRow(r.height, r.proposalId, r.root, r.treeSize, IndexedTree.build(values).root(), r.nfSize + 1)
                })
            }
        }
        val a = wallet(chain, indexer = forged)
        a.sync()
        assertTrue(a.stakeVoteItems(1).contains(PrivacyWallet.StakeVoteItem.Note(v.k.position)))
        assertNotNull(a.castStakeVote(1, PrivacyWallet.StakeVoteItem.Note(v.k.position), yes))
        assertEquals(1, chain.stakeVotes.size)
    }

    /**
     * When the snapshot's nullifier tree holds the note's nullifier but sync
     * saw no spend before the snapshot, the two disagree: an error, never a
     * vote silently skipped.
     */
    @Test
    fun spentBeforeSnapshotDisagreeingWithSyncIsAnError() {
        val v = voting()
        val chain = v.chain
        val real = chain.snapshotRead(1)
        val values = chain.stakeNfValues.take((real.nfSize - 1).toInt()) + v.k.nf
        val forgedSnap = real.copy(nfRoot = IndexedTree.build(values).root(), nfSize = real.nfSize + 1)
        val a = wallet(chain, r = reads(chain, snapshot = { forgedSnap }, nfTree = { start, limit ->
            PrivacyChainReads.NfTreePage(values.drop(start.toInt()).take(limit), values.size + 1L)
        }))
        a.sync()
        chain.indexerNfTree = false
        val sims = chain.simulated
        val e = assertThrows(IllegalStateException::class.java) { a.castStakeVote(1, PrivacyWallet.StakeVoteItem.Note(v.k.position), yes) }
        assertTrue(e.message!!, "sync saw no spend" in e.message!!)
        assertEquals(sims, chain.simulated)
    }

    /** A note spent in the snapshot's own block is spent before it: not eligible. */
    @Test
    fun noteSpentInTheSnapshotBlockIsNotEligible() {
        val v = voting()
        val a = v.a
        val snap = a.snapshot(1)
        val i = a.store.state.stakeNotes.indexOfFirst { it.position == v.k.position }
        a.store.state.stakeNotes[i] = a.store.state.stakeNotes[i].copy(spentHeight = snap.height)
        assertFalse(a.stakeVoteItems(1).contains(PrivacyWallet.StakeVoteItem.Note(v.k.position)))
        a.store.state.stakeNotes[i] = a.store.state.stakeNotes[i].copy(spentHeight = snap.height + 1)
        assertTrue(a.stakeVoteItems(1).contains(PrivacyWallet.StakeVoteItem.Note(v.k.position)))
    }

    /** The snapshot's nf_size is the LCD's: an indexer's larger count is never fetched (L4). */
    @Test
    fun nullifierFetchIsBoundedByTheLcdSize() {
        val v = voting()
        val chain = v.chain
        val asked = ArrayList<Pair<Long, Int?>>()
        val idx = object : Wrapped(chain) {
            override fun stakeNullifierLeaves(fromIndex: Long, limit: Int?): StakeNfLeavesPage { asked.add(fromIndex to limit); return inner.stakeNullifierLeaves(fromIndex, limit) }
            override fun stakeSnapshots(fromHeight: Long, limit: Int?) = inner.stakeSnapshots(fromHeight, limit).let { p ->
                p.copy(rows = p.rows.map { it.copy(nfSize = 1_000_000_000L) })
            }
        }
        val a = wallet(chain, indexer = idx)
        a.sync()
        assertNotNull(a.castStakeVote(1, PrivacyWallet.StakeVoteItem.Note(v.k.position), yes))
        // The LCD's nf_size (a handful): one aligned page, nothing past it.
        assertEquals(listOf(0L to PrivacyWallet.NF_PAGE), asked)
    }

    /** L1: the stake votes cast survive a same-chain reset (an inconsistent sync). */
    @Test
    fun stakeVotesSurviveAReset() {
        val v = voting()
        val a = v.a
        a.castStakeVote(1, PrivacyWallet.StakeVoteItem.Note(v.k.position), yes)
        val votes = a.store.state.stakeVotes.toList()
        assertEquals(1, votes.size)
        a.store.reset(v.chain.chainId)
        assertEquals(votes, a.store.state.stakeVotes)
        a.sync()
        assertNull(a.castStakeVote(1, PrivacyWallet.StakeVoteItem.Note(v.k.position), yes))
        assertEquals(1, v.chain.stakeVotes.size)
    }

    /** 1119 counts as "already voted" only in x/shieldedstaking's codespace. */
    @Test
    fun alreadyVotedNeedsTheCodespace() {
        assertTrue(PrivacyWallet.alreadyVotedError(UnsignedTx.TxRejected(1119, "x", "shieldedstaking")))
        assertFalse(PrivacyWallet.alreadyVotedError(UnsignedTx.TxRejected(1119, "x", "wasm")))
        assertFalse(PrivacyWallet.alreadyVotedError(java.io.IOException("tx rejected (code 1119): something else")))
        assertTrue(PrivacyWallet.alreadyVotedError(java.io.IOException("simulate failed (400): this stake note already voted on this proposal")))
    }

    // ---- M1: pending notes, marked before the broadcast, released by the chain's word ----

    /** The answer to the broadcast is lost after the node took it: the spent notes are pending all the same. */
    @Test
    fun spendsArePendingBeforeTheBroadcastAnswers() {
        val chain = FakeChain()
        val lost = object : PrivateChain by chain {
            override fun broadcast(tx: ByteArray, accepted: (hash: String) -> Unit): TxResult {
                chain.broadcast(tx) {}
                throw java.net.SocketTimeoutException("read timed out (test)")
            }
        }
        val a = wallet(chain, pc = lost)
        funded(chain, a)
        a.sync()
        assertThrows(java.net.SocketTimeoutException::class.java) { a.unshield(receiver, "uerth", 1000) }
        val n = a.notes.single { it.note.value == 1_000_000L }
        assertNotNull(n.pendingAt)
        assertEquals(chain.txs.keys.single(), n.pendingTx)
    }

    /** A refusal proving the tx is in no mempool (CheckTx's code) makes the notes spendable again at once. */
    @Test
    fun aRefusedBroadcastUnmarks() {
        val chain = FakeChain()
        val a = wallet(chain)
        funded(chain, a)
        a.sync()
        chain.rejectNext = 1
        assertThrows(UnsignedTx.TxRejected::class.java) { a.unshield(receiver, "uerth", 1000) }
        assertTrue(a.notes.all { it.pendingAt == null })
    }

    /**
     * Past the timeout, a pending note is released only when the chain says
     * the tx is missing; a tx in a block whose spend the indexer never
     * reported keeps it pending and the sync unverified.
     */
    @Test
    fun pendingIsReleasedOnlyOnTheChainsWord() {
        val chain = FakeChain()
        var hide: Fr? = null
        val idx = object : Wrapped(chain) {
            override fun nullifiers(fromHeight: Long, limit: Int?) = inner.nullifiers(fromHeight, limit).let { p ->
                p.copy(blocks = p.blocks.map { (h, nfs) -> h to nfs.filter { it != hide } })
            }
        }
        val a = wallet(chain, indexer = idx)
        funded(chain, a)
        a.sync()
        // Dropped from the mempool: missing once past its timeout, released.
        chain.dropNext = 1
        assertThrows(java.io.IOException::class.java) { a.unshield(receiver, "uerth", 1000) }
        while (chain.tipHeight() <= chain.lastTimeoutHeight) chain.emptyBlock()
        assertTrue(a.sync().verified)
        assertTrue(a.notes.all { it.pendingAt == null })
        // Committed, its spend hidden by the indexer: kept pending, the sync unverified.
        hide = a.notes.single().nf
        chain.unconfirmedNext = 1
        assertThrows(Exception::class.java) { a.unshield(receiver, "uerth", 1000) }
        while (chain.tipHeight() <= chain.lastTimeoutHeight) chain.emptyBlock()
        assertFalse(a.sync().verified)
        assertTrue("did not report its spend" in a.store.state.rootsError!!)
        assertNotNull(a.notes.single { it.nf == hide }.pendingAt)
        // Unknown (the node cannot say): kept.
        chain.txLookupBlind = true
        a.sync()
        assertNotNull(a.notes.single { it.nf == hide }.pendingAt)
    }

    // ---- M7: hostile bodies and Errors ----

    /** PoC 3 (automation): an Error thrown by an action is a failed action, never an escape. */
    @Test
    fun poc3_errorsStayInsideTheAutomation() = runBlocking {
        val failed = ArrayList<Throwable>()
        val due = PrivacyAutomation.Inputs(86_400L * 100 + 50_000, maturedUnbonds = listOf("unbond/v/1"))
        PrivacyAutomation.runPass(sync = {}, inputs = { due }, act = { throw StackOverflowError("org.json recursion") }, pause = {}, onFailure = { _, e -> failed.add(e) })
        assertTrue(failed.single() is StackOverflowError)
    }

    private fun serve(handler: (String) -> Pair<String, ByteArray>): ServerSocket {
        val server = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                s.use { c ->
                    val r = c.getInputStream().bufferedReader()
                    val line = r.readLine() ?: return@use
                    while (!r.readLine().isNullOrEmpty()) { }
                    val (head, body) = handler(line.split(" ")[1])
                    c.getOutputStream().apply { write("$head\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray()); write(body); flush() }
                }
            }
        }
        return server.also { servers.add(it) }
    }

    private val servers = ArrayList<ServerSocket>()
    @After fun stop() = servers.forEach { it.close() }

    private val status = JSONObject().put("chain_id", "earth-1").put("genesis", "0123456789abcdef")
        .put("base", "/privacy/earth-1/0123456789abcdef").put("synced_height", 1).put("halted", JSONObject.NULL).toString().toByteArray()

    /** PoC 3 (parser): a deeply nested body is refused before org.json sees it. */
    @Test
    fun poc3_deepJsonIsRefusedBeforeParsing() {
        val deep = ("{\"notes\":" + "[".repeat(500_000)).toByteArray()
        val s = serve { p -> "HTTP/1.0 200 OK\r\nContent-Type: application/json" to (if (p == "/privacy/status") status else deep) }
        val idx = HttpPrivacyIndexer("http://127.0.0.1:${s.localPort}", "earth-1")
        val e = assertThrows(java.io.IOException::class.java) { idx.notes(0) }
        assertTrue(e.message!!, "nests deeper" in e.message!!)
        // Within the bound, strings with brackets in them included, parses.
        network.erth.wallet.chain.EarthRest.checkJsonDepth("""{"a":"[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[\"]","b":[[1]]}""")
    }

    /** PoC 4: a redirect is never followed; the other origin is never asked. */
    @Test
    fun poc4_redirectsAreNotFollowed() {
        val hits = java.util.Collections.synchronizedList(ArrayList<String>())
        val other = serve { p -> hits.add(p); "HTTP/1.0 200 OK\r\nContent-Type: application/json" to
            """{"notes":[],"next_pos":0,"complete":false,"synced_height":1}""".toByteArray() }
        val s = serve { p ->
            if (p == "/privacy/status") "HTTP/1.0 200 OK\r\nContent-Type: application/json" to status
            else "HTTP/1.0 302 Found\r\nLocation: http://127.0.0.1:${other.localPort}/elsewhere$p" to ByteArray(0)
        }
        val idx = HttpPrivacyIndexer("http://127.0.0.1:${s.localPort}", "earth-1")
        assertThrows(java.io.IOException::class.java) { idx.notes(0) }
        assertTrue(hits.isEmpty())
    }

    // ---- L5: a position's vote is persisted when the node takes it ----

    @Test
    fun positionVoteReportsAcceptanceBeforeItsBlock() {
        val chain = FakeChain()
        val a = wallet(chain)
        repeat(4) { funded(chain, a, 2_000_000) }
        a.sync()
        a.delegate(vB, 1_000_000); a.sync()
        a.lockPosition(vB, 100_000, mapOf(2L to 100L)); a.sync()
        chain.openProposal(12)
        a.sync()
        val pos = a.stakeVoteItems(12).filterIsInstance<PrivacyWallet.StakeVoteItem.Position>().single()
        var acceptedHash: String? = null
        chain.unconfirmedNext = 1
        assertThrows(Exception::class.java) { a.castStakeVote(12, pos, yes) { acceptedHash = it } }
        assertNotNull(acceptedHash)
        assertTrue(acceptedHash in chain.txs)
        assertEquals(1, chain.positionVotes.size)
    }

    /** The stake-vote record a refusal leaves behind is forgotten: the note may vote again. */
    @Test
    fun refusedVoteIsForgotten() {
        val v = voting()
        v.chain.rejectNext = 1
        assertThrows(UnsignedTx.TxRejected::class.java) { v.a.castStakeVote(1, PrivacyWallet.StakeVoteItem.Note(v.k.position), yes) }
        assertTrue(v.a.store.state.stakeVotes.isEmpty())
        assertNotNull(v.a.castStakeVote(1, PrivacyWallet.StakeVoteItem.Note(v.k.position), yes))
        assertEquals(1, v.a.store.state.stakeVotes.size)
    }
    // ---- the backend's paging rule (fixed page sizes, aligned cursors) ----

    private fun others(chain: FakeChain, n: Int) {
        repeat(n) { chain.mint("uerth", 1, NotePlaintext.randomField(), ByteArray(NoteCipher.BLIND_CIPHERTEXT_BYTES)) }
        chain.emptyBlock()
    }

    /**
     * Every position cursor is page-aligned and every limit a served size;
     * a tip page is asked again from its aligned start, the rows already
     * held dropped (and checked), and the trees end up the chain's.
     */
    @Test
    fun pagesAreAlignedAndTheTipPageIsReasked() {
        val chain = FakeChain()
        val a = wallet(chain)
        funded(chain, a)
        others(chain, 250)
        val asked = ArrayList<Long>()
        val idx = object : Wrapped(chain) {
            override fun notes(fromPos: Long, limit: Int?): NotesPage { asked.add(fromPos); return inner.notes(fromPos, limit) }
        }
        val keys = PrivacyKeys.fromMnemonic(alice)
        val store = PrivacyStore.memory()
        WalletSync(idx, store, keys, chain.chainId, chain).sync(100)
        assertEquals(listOf(0L, 100L, 200L), asked)
        assertEquals(chain.noteTree.root(), store.noteTree.root())
        others(chain, 30)
        asked.clear()
        WalletSync(idx, store, keys, chain.chainId, chain).sync(100)
        assertEquals(listOf(200L), asked)
        assertEquals(chain.noteTree.root(), store.noteTree.root())
        assertTrue(chain.misaligned.isEmpty())
        // A held row served differently on the re-asked page is an inconsistency.
        val lying = object : Wrapped(chain) {
            override fun notes(fromPos: Long, limit: Int?) = inner.notes(fromPos, limit).let { p ->
                p.copy(rows = p.rows.mapIndexed { i, r -> if (i == 0) r.copy(cm = Fr.of(5)) else r })
            }
        }
        others(chain, 1)
        assertThrows(WalletSync.Inconsistent::class.java) { WalletSync(lying, store, keys, chain.chainId, chain).sync(100) }
    }

    /** A sync, a vote's nullifier tree: no request off the paging rule. */
    @Test
    fun walletRequestsFollowThePagingRule() {
        val v = voting()
        assertNotNull(v.a.castStakeVote(1, PrivacyWallet.StakeVoteItem.Note(v.k.position), yes))
        assertTrue(v.chain.misaligned.toString(), v.chain.misaligned.isEmpty())
    }

    /** A busy indexer (503 with Retry-After, 429) is waited out, then given up on. */
    @Test
    fun busyIndexerIsBackedOff() {
        var busy = 2
        val s = serve { p ->
            when {
                p == "/privacy/status" -> "HTTP/1.0 200 OK\r\nContent-Type: application/json" to status
                busy-- > 0 -> "HTTP/1.0 503 Service Unavailable\r\nRetry-After: 3" to ByteArray(0)
                else -> "HTTP/1.0 200 OK\r\nContent-Type: application/json" to """{"notes":[],"next_pos":0,"complete":false,"synced_height":1}""".toByteArray()
            }
        }
        val slept = ArrayList<Long>()
        val idx = HttpPrivacyIndexer("http://127.0.0.1:${s.localPort}", "earth-1") { slept.add(it) }
        assertEquals(0, idx.notes(0, 1000).rows.size)
        assertEquals(listOf(3000L, 3000L), slept)
        val always = serve { p ->
            if (p == "/privacy/status") "HTTP/1.0 200 OK\r\nContent-Type: application/json" to status
            else "HTTP/1.0 429 Too Many Requests" to ByteArray(0)
        }
        slept.clear()
        val idx2 = HttpPrivacyIndexer("http://127.0.0.1:${always.localPort}", "earth-1") { slept.add(it) }
        val e = assertThrows(java.io.IOException::class.java) { idx2.notes(0) }
        assertTrue("busy" in e.message!!)
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L), slept)
        assertThrows(IllegalArgumentException::class.java) { idx2.notes(0, 5000) }
    }
    /**
     * Vote audit: 40 values inserted in a non-monotonic order by the chain's
     * zk/indexed Insert (audit4 tools/ixpoc) give the root the wallet's batch build does.
     */
    @Test
    fun indexedTreeRandomOrderMatchesChain() {
        val values = listOf(
            "0000000000000000000000000000000041a027f7785144a55afc45053c7f6819",
            "000000000000000000000000000000000e74f84711b59f587c9c1cad13ef5f24",
            "00000000000000000000000000000000162331bf65d3f2322ba8d991b6ee5024",
            "000000000000000000000000000000002f4f06b98574ded50d2ab81ec4774790",
            "0000000000000000000000000000000020b76d1f02eb1c11d68153f3947cec90",
            "00000000000000000000000000000000785ff57fe5a5217e61e2bad7778919e1",
            "000000000000000000000000000000005d9076f293f65d8143fcd0fd4c2e2f11",
            "000000000000000000000000000000007a2b38172940e200cecf9aff080966a4",
            "000000000000000000000000000000001ef049ca638d87936fc093b0a6030ba9",
            "0000000000000000000000000000000052b8ff4ebf53d5e76f2a209ff350c584",
            "0000000000000000000000000000000006465c2f9e05da1709157c2bd10d4fe9",
            "000000000000000000000000000000002c7855dc3608bdc4935514ef1bb8e139",
            "0000000000000000000000000000000004357e97fabe65184baac1abebd69b39",
            "0000000000000000000000000000000002d8bbe518b172eed7cb954c2b88d044",
            "00000000000000000000000000000000357eed4f7fbb3185aa23414cf6d9b171",
            "00000000000000000000000000000000053fbb00b4ecd04d4137a1ee380771b1",
            "0000000000000000000000000000000024a8473c44be89f91f5059c9579a5040",
            "00000000000000000000000000000000095b37c17798de8b140d01f830394ba9",
            "000000000000000000000000000000001de1b43699d6a00a48127ae035efde11",
            "000000000000000000000000000000003f0d619b304ae65c847be589f24fed21",
            "000000000000000000000000000000000151374cbbc54fa4b844ed100af63199",
            "0000000000000000000000000000000085dd1622c606bac6f477b5635da5aad1",
            "0000000000000000000000000000000001463d903b7df8c388cd319293b1bf64",
            "000000000000000000000000000000003a913350dcde598f2d3bcaea3e79bb44",
            "000000000000000000000000000000000272e77861f6426e6e0aa885142372b9",
            "0000000000000000000000000000000000f3d73053fedbe27ba9328854638e10",
            "000000000000000000000000000000006deed5b02eb9dd0fa0f153a316afde99",
            "00000000000000000000000000000000061c5c8cd37c721240e0267fe6e1ff31",
            "00000000000000000000000000000000230b475f441aff4a08cd5cc9eaa16919",
            "000000000000000000000000000000003bd136463b517a0c26d7e648454cb639",
            "00000000000000000000000000000000031127cf8a83168d011f3db6247d0281",
            "000000000000000000000000000000003e7821f58d76f39d6a5c48f4c2dc34a9",
            "00000000000000000000000000000000537a729d759281172d9c0e487a7da064",
            "00000000000000000000000000000000839fa3b92e3ee21284f6e39bcb0b7c90",
            "000000000000000000000000000000004b2fc9fe6e1e0bdf8b2239c288359a99",
            "0000000000000000000000000000000019e0fad5d4d9973563670ef60b7f5504",
            "00000000000000000000000000000000679e1b9056780fa53c1102de2e208924",
            "000000000000000000000000000000005971378e2fea038626401fe3f95a2b01",
            "00000000000000000000000000000000387ec0ee76642409ce9acf571e276059",
            "00000000000000000000000000000000552fbca30e05fa2e02fc57a6d7db8400",
        )
        assertEquals("2e98e4e0f9c6b5fa34290a608292d4622e2447ac7e42139deb35a53eaf75976b", IndexedTree.build(values.map { Fr.fromHex(it) }).root().toHex())
    }
}
