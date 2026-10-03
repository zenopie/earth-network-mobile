package network.erth.wallet.privacy

import cosmos.gov.v1.VoteOption as GovVoteOption
import cosmos.gov.v1.WeightedVoteOption
import kotlinx.coroutines.flow.first
import network.erth.wallet.privacy.PrivacyAutomation.Action
import network.erth.wallet.privacy.PrivacyAutomation.Inputs
import network.erth.wallet.privacy.chain.PrivacyQueries
import network.erth.wallet.privacy.keys.PrivacyKeys
import network.erth.wallet.privacy.note.NoteCipher
import network.erth.wallet.privacy.note.NotePlaintext
import network.erth.wallet.privacy.sync.ChainRoots
import network.erth.wallet.privacy.sync.HeightPage
import network.erth.wallet.privacy.sync.IdentityPage
import network.erth.wallet.privacy.sync.NoteRow
import network.erth.wallet.privacy.sync.NotesPage
import network.erth.wallet.privacy.sync.PrivacyIndexer
import network.erth.wallet.privacy.sync.PrivacyStore
import network.erth.wallet.privacy.sync.WalletSync
import network.erth.wallet.privacy.tx.PrivateTxEngine
import network.erth.wallet.privacy.tx.StakePlan
import network.erth.wallet.privacy.zk.Fr
import network.erth.wallet.privacy.zk.Privacy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The third audit's client findings (1-14), each against [FakeChain]; the
 * PoCs A-C of the iOS report are reproduced here first, fixed.
 */
class Audit3Test {
    private val alice = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private val receiver = "earth1qqqsyqcyq5rqwzqfpg9scrgwpugpzysncc2uls"
    private val v1 = "earthvaloper1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"
    private var snapshotSize: Long? = null
    private val yes = listOf(WeightedVoteOption.newBuilder().setOption(GovVoteOption.VOTE_OPTION_YES).setWeight("1").build())

    private fun reads(chain: FakeChain) = object : PrivacyChainReads {
        override fun personhoodParams() = PrivacyChainReads.PersonhoodParams(30L * 86_400, 3_600)
        override fun ballotInputs(proposalId: Long, optionId: Long) =
            PrivacyChainReads.BallotInputs(Privacy.proposalScope(proposalId, 0), Fr.ZERO, Fr.ZERO, chain.now - 3600, 0, 0)
        override fun epochNumber() = chain.epoch
        override fun snapshot(proposalId: Long) = (snapshotSize ?: chain.stakeTree.size).let { PrivacyChainReads.Snapshot(chain.stakeTree.rootAt(it), it) }
        override fun positions() = chain.positionReads()
    }

    private fun wallet(chain: FakeChain, indexer: PrivacyIndexer = chain, store: PrivacyStore = PrivacyStore.memory(), roots: ChainRoots = chain) =
        PrivacyWallet(PrivacyKeys.fromMnemonic(alice), store, indexer, chain, reads(chain), chain.prover, chain.chainId, roots, now = { chain.now })

    private open class Wrapped(val inner: PrivacyIndexer) : PrivacyIndexer by inner

    private fun bal(w: PrivacyWallet, d: String) = w.balances()[d] ?: 0L

    private fun funded(chain: FakeChain, w: PrivacyWallet, amount: Long = 1_000_000) {
        val o = w.shieldOutput("uerth", 0)
        chain.shield("uerth", amount, o.pc, o.ciphertext)
    }

    private fun registered(chain: FakeChain, w: PrivacyWallet) {
        val prep = w.prepareRegistration(null)
        chain.shield("uerth", 100_000, prep.gas.pc, prep.gas.ciphertext)
        w.sync()
        w.register(prep, ByteArray(14_656), listOf("261001", prep.binding.toBigInteger().toString(), "555", Fr.of(77).toBigInteger().toString()), "lean_poa", ByteArray(10))
        w.sync()
        assertEquals(WalletSync.IdentityStatus.LIVE, w.identityStatus())
    }

    private fun tmp(): File = java.nio.file.Files.createTempDirectory("audit3").toFile()

    // ---- 1. sync generations (PoC A) ----

    /**
     * PoC A: an indexer appends a forged 50 ERTH note and then fails a later
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

    // ---- 3. paging (PoC B) and the sync timeout ----

    /** PoC B: empty pages that say more follows are inconsistent at once, not asked forever. */
    @Test
    fun emptyPagesThatSayMoreFollowsAreInconsistent() {
        val chain = FakeChain()
        var calls = 0
        val idx = object : Wrapped(chain) {
            override fun notes(fromPos: Long, limit: Int?): NotesPage { calls++; return NotesPage(emptyList(), fromPos, true, chain.height - 1) }
        }
        assertThrows(WalletSync.Inconsistent::class.java) { wallet(chain, indexer = idx).sync() }
        assertTrue("asked $calls times", calls <= 2)
    }

    @Test
    fun heightPagesThatDoNotAdvanceAreInconsistent() {
        val chain = FakeChain()
        val a0 = wallet(chain)
        funded(chain, a0)
        var calls = 0
        val idx = object : Wrapped(chain) {
            override fun nullifiers(fromHeight: Long, limit: Int?): HeightPage<Fr> { calls++; return HeightPage(emptyList(), fromHeight, true, chain.height - 1) }
        }
        assertThrows(WalletSync.Inconsistent::class.java) { wallet(chain, indexer = idx).sync() }
        assertTrue(calls <= 2)
        val back = object : Wrapped(chain) {
            override fun stakeNullifiers(fromHeight: Long, limit: Int?): HeightPage<Fr> = HeightPage(emptyList(), fromHeight - 1, false, chain.height - 1)
        }
        assertThrows(WalletSync.Inconsistent::class.java) { wallet(chain, indexer = back).sync() }
        val wrongNext = object : Wrapped(chain) {
            override fun notes(fromPos: Long, limit: Int?): NotesPage = inner.notes(fromPos, limit).let { it.copy(nextPos = it.nextPos + 1) }
        }
        assertThrows(WalletSync.Inconsistent::class.java) { wallet(chain, indexer = wrongNext).sync() }
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

    // ---- 2. a snapshot ahead of the local tree (PoC C) and untrusted numbers ----

    /** PoC C: a stake snapshot larger than the local stake tree is "sync first", not a crash. */
    @Test
    fun snapshotAheadOfLocalTreeAsksForASync() {
        val chain = FakeChain()
        val a = wallet(chain)
        repeat(2) { funded(chain, a, 2_000_000) }
        a.sync()
        a.delegate(v1, 1_000_000)
        a.sync()
        val other = StakePlan.selfMint(PrivacyKeys.fromMnemonic("legal winner thank year wave sausage worth useful legal winner thank yellow"))
        chain.mintStake(PrivacyWallet.derthDenom(v1), 7, Privacy.stakePc(Fr.of(5), other.first.first, other.first.second), other.second)
        chain.emptyBlock()
        snapshotSize = chain.stakeTree.size
        val n = a.stakeNotes.first { it.spendable }
        val e = assertThrows(PrivacyWallet.SyncFirst::class.java) { a.stakeVote(1, listOf(n), yes) }
        assertTrue(e.message!!.contains("sync first"))
        a.sync()
        a.stakeVote(1, listOf(a.stakeNotes.first { it.spendable }), yes)
    }

    @Test
    fun chainNumbersNeverWrap() {
        assertNull(PrivacyQueries.durationSeconds("1e30s"))
        assertNull(PrivacyQueries.durationSeconds("-5s"))
        assertNull(PrivacyQueries.durationSeconds("99999999999999999999999s"))
        assertEquals(1_814_400L, PrivacyQueries.durationSeconds("1814400s"))
        assertEquals(0L, PrivacyQueries.durationSeconds("0.5s"))
        assertNull(PrivacyAutomation.maturesBy(0, Long.MAX_VALUE, 0, Long.MAX_VALUE, 0))
        assertNull(PrivacyAutomation.maturesBy(1, 3, Long.MAX_VALUE, 1, Long.MAX_VALUE))
        assertNull(PrivacyAutomation.maturesBy(-1, 3, 0, 1, 1))
        assertTrue(PrivacyAutomation.matured(
            listOf(network.erth.wallet.privacy.note.OwnedStakeNote(0, 1, "unbond/v/99999999999999999999999", 5, Fr.ONE, Fr.ONE, Fr.ONE, Fr.ONE)),
            1_000, 4, 0, 10, 10, emptyMap(),
        ).isEmpty())
    }

    // ---- 4. restore takes the block time from the indexer; 13. retry ----

    private fun restoredLive(chain: FakeChain, indexer: PrivacyIndexer = chain): PrivacyWallet {
        val r = wallet(chain, indexer = indexer)
        r.sync()
        assertEquals(WalletSync.IdentityStatus.LIVE, r.identityStatus())
        return r
    }

    /** The identity rows carry their block's time: a restore never asks the LCD about the registration's block. */
    @Test
    fun restoreTakesTheBlockTimeFromTheIndexer() {
        val chain = FakeChain()
        registered(chain, wallet(chain))
        repeat(40) { chain.emptyBlock() }
        chain.blockTimeAsks.clear()
        val r = restoredLive(chain)
        assertTrue(chain.blockTimeAsks.isEmpty())
        assertTrue(r.store.state.regRecords.single().work <= 677)
    }

    /** No time on the rows: the LCD is asked for a uniform cover set holding the block, once. */
    @Test
    fun restoreWithoutRowTimesAsksACoverSet() {
        val chain = FakeChain()
        registered(chain, wallet(chain))
        val height = chain.identityRows.single().height
        repeat(40) { chain.emptyBlock() }
        chain.identityRowTimes = false
        chain.blockTimeAsks.clear()
        val r = restoredLive(chain)
        assertEquals(WalletSync.COVER_SET, chain.blockTimeAsks.size)
        assertEquals(WalletSync.COVER_SET, chain.blockTimeAsks.toSet().size)
        assertTrue(height in chain.blockTimeAsks)
        assertEquals(r.store.state.regRecords.single().cover.toSet(), chain.blockTimeAsks.toSet())
        r.sync()
        assertEquals(WalletSync.COVER_SET, chain.blockTimeAsks.size)
    }

    /** K13: a wrong indexer time does not give the record up; the LCD's (cover set) still finds it. */
    @Test
    fun wrongIndexerTimeDoesNotBlockRestore() {
        val chain = FakeChain()
        registered(chain, wallet(chain))
        repeat(20) { chain.emptyBlock() }
        val lying = object : Wrapped(chain) {
            override fun identity(fromIndex: Long, limit: Int?): IdentityPage =
                inner.identity(fromIndex, limit).let { p -> p.copy(rows = p.rows.map { it.copy(time = (it.time ?: 0) + 1) }) }
        }
        restoredLive(chain, lying)
        // And with the LCD pruned too, the bounded fallback still runs (the record is not given up), a budget a sync.
        chain.blockTimesPruned = true
        val r = wallet(chain, indexer = lying)
        var syncs = 0
        while (r.identityStatus() != WalletSync.IdentityStatus.LIVE) { r.sync(); assertTrue(++syncs < 10) }
    }

    /** K13: a record given up is tried again after a store reset (it is found afresh). */
    @Test
    fun exhaustedRecordIsRetriedAfterAReset() {
        val chain = FakeChain()
        registered(chain, wallet(chain))
        val lying = object : Wrapped(chain) {
            override fun identity(fromIndex: Long, limit: Int?): IdentityPage =
                inner.identity(fromIndex, limit).let { p -> p.copy(rows = p.rows.map { it.copy(time = (it.time ?: 0) + 1) }) }
        }
        val lcd = object : ChainRoots by chain {
            override fun blockTime(height: Long): Long? = chain.blockTime(height)?.plus(7)
        }
        val r = wallet(chain, indexer = lying, roots = lcd)
        r.sync()
        assertEquals(network.erth.wallet.privacy.sync.RecordStatus.EXHAUSTED, r.store.state.regRecords.single().status)
        assertEquals(WalletSync.IdentityStatus.NONE, r.identityStatus())
        r.store.reset(chain.chainId)
        WalletSync(chain, r.store, r.keys, chain.chainId, chain, { chain.now }).sync()
        assertEquals(WalletSync.IdentityStatus.LIVE, r.identityStatus())
    }

    // ---- 5. timeout_height ----

    @Test
    fun privateTxsCarryATimeoutAndPendingWaitsForIt() {
        val chain = FakeChain()
        val a = wallet(chain)
        funded(chain, a)
        a.sync()
        val tip = chain.tipHeight()
        a.unshield(receiver, "uerth", 1000)
        assertEquals(tip + PrivateTxEngine.TIMEOUT_BLOCKS, chain.lastTimeoutHeight)
        a.sync()
        // A tx the node accepted and then dropped: its note stays pending until the chain is past the timeout.
        chain.dropNext = 1
        val t = chain.tipHeight() + PrivateTxEngine.TIMEOUT_BLOCKS
        assertThrows(java.io.IOException::class.java) { a.unshield(receiver, "uerth", 1000) }
        val held = a.notes.single { it.unspent }
        assertEquals(t, held.pendingUntil)
        chain.now += 24 * 3600
        while (chain.tipHeight() < t) chain.emptyBlock()
        a.sync()
        assertNotNull(a.notes.single { it.unspent }.pendingAt)
        assertThrows(Exception::class.java) { a.unshield(receiver, "uerth", 1000) }
        chain.emptyBlock()
        a.sync()
        assertNull(a.notes.single { it.unspent }.pendingAt)
        a.unshield(receiver, "uerth", 1000)
        // A tx past its timeout_height is refused by the chain.
        assertThrows(IllegalArgumentException::class.java) {
            chain.broadcast(network.erth.wallet.privacy.tx.UnsignedTx.build(network.erth.earth.proto.shielded.MsgSend.getDefaultInstance(), 1, "", 1))
        }
    }

    // ---- 6. fee cap ----

    @Test
    fun feeIsCappedAndReconfirmedAboveTheSheet() {
        val chain = FakeChain()
        val a = wallet(chain)
        funded(chain, a, 50_000_000)
        a.sync()
        chain.price = java.math.BigDecimal("10")
        val e = assertThrows(PrivateTxEngine.FeeAboveCap::class.java) { a.unshield(receiver, "uerth", 1000) }
        assertTrue(e.cap <= PrivateTxEngine.MAX_PRIVATE_FEE)
        assertEquals(0, chain.prover.actions.size)
        chain.price = java.math.BigDecimal("0.001")
        val before = chain.height
        val q = assertThrows(PrivateTxEngine.FeeAboveQuote::class.java) { PrivacyWallet.withShownFee(1) { a.unshield(receiver, "uerth", 1000) } }
        assertEquals(before, chain.height)
        PrivacyWallet.withShownFee(q.fee) { a.unshield(receiver, "uerth", 1000) }
        assertEquals(before + 1, chain.height)
        assertNull(PrivacyWallet.shownFee.get())
    }

    // ---- 14a. quote with placeholder nullifiers ----

    @Test
    fun quoteNeverShowsTheNodeOurNullifiers() {
        val chain = FakeChain()
        val a = wallet(chain)
        funded(chain, a)
        funded(chain, a)
        a.sync()
        val q = a.quoteSend(a.address, "uerth", 1_500_000)
        assertTrue(q.fee > 0)
        val ours = a.notes.map { it.nf }.toSet()
        assertTrue(chain.simulatedNullifiers.isNotEmpty())
        assertTrue(chain.simulatedNullifiers.none { it in ours })
        assertEquals(0, chain.prover.actions.size)
    }

    // ---- 12. the nullifier spot-check never names ours ----

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

    // ---- 7. automation spacing; 14b. logs ----

    @Test
    fun automationSpacesActionsWithASyncBetween() = kotlinx.coroutines.runBlocking {
        val events = ArrayList<String>()
        var claimed = false; var caretaker = true; var fee = true; var landed = false
        val base = Inputs(now = 20_000L * 86_400 + 5 * 3600, identityLive = true, claimOpensAt = 0, claimedToday = false,
            claimOffset = 0, caretakerDue = false, hasFeeErth = true, maturedUnbonds = emptyList())
        val taken = PrivacyAutomation.runPass(
            sync = { events.add("sync"); if (landed) fee = true },
            inputs = { base.copy(claimedToday = claimed, caretakerDue = caretaker, hasFeeErth = fee) },
            act = { a ->
                events.add(PrivacyAutomation.kind(a))
                // One ERTH note: its change is pending until the tx lands and a sync sees it.
                fee = false; landed = true
                if (a is Action.ClaimAnml) claimed = true else caretaker = false
            },
            pause = { ms -> events.add("pause"); assertTrue(ms in PrivacyAutomation.ACTION_PAUSE_MIN_MS..PrivacyAutomation.ACTION_PAUSE_MAX_MS) },
        )
        assertEquals(2, taken.size)
        assertEquals(listOf("sync", PrivacyAutomation.kind(taken[0]), "pause", "sync", PrivacyAutomation.kind(taken[1])), events)
    }

    @Test
    fun automationWaitsForChangeThatHasNotLanded() = kotlinx.coroutines.runBlocking {
        var claimed = false; var fee = true
        val base = Inputs(now = 20_000L * 86_400 + 5 * 3600, identityLive = true, claimOpensAt = 0, claimedToday = false,
            claimOffset = 0, caretakerDue = true, hasFeeErth = true, maturedUnbonds = listOf("unbond/v/1"))
        var pauses = 0
        val taken = PrivacyAutomation.runPass(
            sync = {},
            inputs = { base.copy(claimedToday = claimed, hasFeeErth = fee, caretakerDue = !claimed || fee) },
            act = { a -> if (a !is Action.ClaimUnbonding) { fee = false; claimed = true } },
            pause = { pauses++ },
            random = java.util.Random(1),
        )
        // Never two actions without a pause; a fee action waits while the only fee note is pending.
        assertEquals(taken.size - 1, pauses)
        assertEquals(1, taken.count { it !is Action.ClaimUnbonding })
    }

    @Test
    fun automationLogsNameNoDenom() {
        val k = PrivacyAutomation.kind(Action.ClaimUnbonding("unbond/earthvaloper1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq/3"))
        assertEquals("ClaimUnbonding", k)
        assertEquals("ClaimAnml", PrivacyAutomation.kind(Action.ClaimAnml(5)))
    }

    // ---- 8. the stake vote run stops with the session ----

    private fun staked(chain: FakeChain): PrivacyWallet {
        val v2 = "earthvaloper1qyqszqgpqyqszqgpqyqszqgpqyqszqgpjnp7du"
        val a = wallet(chain)
        repeat(4) { funded(chain, a, 2_000_000) }
        a.sync()
        a.delegate(v1, 1_000_000); a.sync()
        a.delegate(v2, 1_000_000); a.sync()
        a.lockPosition(v1, 100_000, mapOf(2L to 100L)); a.sync()
        snapshotSize = chain.stakeTree.size
        return a
    }

    private fun <T> await(flow: kotlinx.coroutines.flow.StateFlow<T>, cond: (T) -> Boolean): T = kotlinx.coroutines.runBlocking {
        kotlinx.coroutines.withTimeout(60_000) { flow.first { cond(it) } }
    }

    @Test
    fun stakeVoteSuspendsOnLockAndResumes() {
        val chain = FakeChain()
        val a = staked(chain)
        val waiting = kotlinx.coroutines.CompletableDeferred<Unit>()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        val c = StakeVoteController(scope, { a }, pause = { waiting.complete(Unit); kotlinx.coroutines.awaitCancellation() })
        c.startAndAwaitFirst(12, yes)
        kotlinx.coroutines.runBlocking { waiting.await() }
        // A second start while one runs is refused.
        assertThrows(IllegalStateException::class.java) { c.startAndAwaitFirst(12, yes) }
        c.suspend()
        assertNull(c.progress.value)
        Thread.sleep(200)
        assertNull(c.progress.value)
        val kept = a.store.state.stakeVoteRun!!
        assertEquals(1, kept.done)
        // The next unlock (a new controller, as after a restart) picks it up.
        val c2 = StakeVoteController(scope, { a }, pause = {})
        c2.resume()
        val p = await(c2.progress) { it?.finished == true }!!
        assertEquals(3, p.done)
        assertNull(a.store.state.stakeVoteRun)
    }

    /** Resume reads only the selected wallet's own store: another wallet's run is never cast with these keys. */
    @Test
    fun resumeIsPerWallet() {
        val chain = FakeChain()
        val a = staked(chain)
        val b = PrivacyWallet(PrivacyKeys.fromMnemonic("legal winner thank year wave sausage worth useful legal winner thank yellow"),
            PrivacyStore.memory(), chain, chain, reads(chain), chain.prover, chain.chainId, chain, now = { chain.now })
        a.store.state.stakeVoteRun = network.erth.wallet.privacy.sync.StakeVoteRun(12, listOf(1 to "1"), emptySet(), 3, 0)
        val c = StakeVoteController(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default), { b }, pause = {})
        c.resume()
        assertNull(c.progress.value)
        assertEquals(0, chain.stakeVotes.size)
    }

    // ---- 9. forget; 10. saves ----

    @Test
    fun forgettingAWalletDeletesItsPrivateData() {
        val dir = tmp()
        val chain = FakeChain()
        val a = wallet(chain, store = PrivacyStore.open(dir, "w1"))
        funded(chain, a)
        a.sync()
        PrivacyStore.open(dir, "w2").save()
        assertTrue(File(dir, "privacy/w1/state.json").exists())
        PrivacyStore.delete(dir, "w1")
        assertFalse(File(dir, "privacy/w1").exists())
        assertTrue(File(dir, "privacy/w2").exists())
        PrivacyStore.delete(dir)
        assertFalse(File(dir, "privacy").exists())
    }

    @Test
    fun corruptStateIsAnErrorNotAnEmptyWallet() {
        val dir = tmp()
        PrivacyStore.open(dir, "w").save()
        File(dir, "privacy/w/state.json").writeText("{\"notes\": [")
        assertThrows(PrivacyStore.CorruptState::class.java) { PrivacyStore.open(dir, "w") }
    }

    @Test
    fun aSaveThatFailsThrows() {
        val dir = tmp()
        val s = PrivacyStore.open(dir, "w")
        File(dir, "privacy/w/state.json.tmp").mkdirs()
        assertThrows(java.io.IOException::class.java) { s.save() }
    }

    // ---- 11. the bundled SRS ----

    @Test
    fun bundledSrsIsTheTranscriptPrefix() {
        val f = File("src/main/assets/${network.erth.wallet.privacy.prove.PrivacyProver.SRS_ASSET}")
        val bytes = f.readBytes()
        assertEquals(network.erth.wallet.privacy.prove.PrivacyProver.SRS_POINTS * 64, bytes.size)
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals(network.erth.wallet.privacy.prove.PrivacyProver.SRS_SHA256, sha)
        // The first point is the generator (1, 2).
        assertEquals(1, bytes[31].toInt()); assertEquals(2, bytes[63].toInt())
        assertTrue(bytes.copyOf(31).all { it.toInt() == 0 })
        // Every privacy circuit's SRS fits: the size hint's subgroup plus one point.
        assertTrue(network.erth.wallet.privacy.prove.PrivacyProver.SRS_SIZE + 1 <= network.erth.wallet.privacy.prove.PrivacyProver.SRS_POINTS)
    }

    // ---- 14c. a store from before K6 keeps its identity ----

    @Test
    fun preK6StoreKeepsIdentity() {
        val chain = FakeChain()
        val a = wallet(chain)
        registered(chain, a)
        val id = a.store.state.identity
        a.store.state.genesis = null
        a.store.save()
        a.sync()
        assertEquals(id, a.store.state.identity)
        assertEquals(WalletSync.IdentityStatus.LIVE, a.identityStatus())
        assertEquals(chain.genesis, a.store.state.genesis)
    }

    // ---- chain wave 3 (06ea4d6) ----

    /** B/F2: an unshield to any module account is refused before anything is proven. */
    @Test
    fun unshieldToAModuleAccountIsRefused() {
        val chain = FakeChain()
        val a = wallet(chain)
        funded(chain, a)
        a.sync()
        val staking = network.erth.wallet.crypto.Bech32.encode("earth",
            network.erth.wallet.crypto.Bech32.convertBits(network.erth.wallet.privacy.tx.PrivateMsgs.moduleAddress("shieldedstaking"), 8, 5, true))
        val e = assertThrows(IllegalArgumentException::class.java) { a.unshield(staking, "uerth", 1000) }
        assertTrue(e.message!!.contains("shieldedstaking"))
        assertEquals(0, chain.simulated)
        a.unshield(receiver, "uerth", 1000)
    }

    /** I1: a passport proof whose current_date is not a calendar date is refused before broadcast. */
    @Test
    fun registrationNeedsACalendarDate() {
        val chain = FakeChain()
        val a = wallet(chain)
        val prep = a.prepareRegistration(null)
        chain.shield("uerth", 100_000, prep.gas.pc, prep.gas.ciphertext)
        a.sync()
        assertThrows(IllegalArgumentException::class.java) {
            a.register(prep, ByteArray(14_656), listOf("250231", prep.binding.toBigInteger().toString(), "555", Fr.of(77).toBigInteger().toString()), "lean_poa", ByteArray(10))
        }
        assertEquals(0, chain.simulated)
    }
}
