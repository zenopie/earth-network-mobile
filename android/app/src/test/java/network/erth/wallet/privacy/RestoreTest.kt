package network.erth.wallet.privacy

import java.io.IOException
import network.erth.wallet.privacy.keys.PrivacyKeys
import network.erth.wallet.privacy.sync.ChainRoots
import network.erth.wallet.privacy.sync.IdentityPage
import network.erth.wallet.privacy.sync.NotesPage
import network.erth.wallet.privacy.sync.PrivacyIndexer
import network.erth.wallet.privacy.sync.PrivacyStore
import network.erth.wallet.privacy.sync.WalletSync
import network.erth.wallet.privacy.sync.WalletSync.Companion.StateRecord
import network.erth.wallet.privacy.zk.Fr
import network.erth.wallet.privacy.zk.Privacy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A wallet restored from the mnemonic alone: it finds every note, its
 * registration (record notes, block times, the cover set it asks the chain),
 * its handle and caretaker split (state records), and never reuses a closed
 * owner tag.
 */
class RestoreTest : WalletTest() {
    private fun signals(prep: PrivacyWallet.RegistrationPrep, nullifier: String = "123456789") =
        listOf("261001", prep.binding.toBigInteger().toString(), nullifier, Fr.of(77).toBigInteger().toString())

    /** With PRIVACY_TOML_OUT set, every witness as a nargo Prover.toml (see WalletFlowTest). */
    private fun dump(chain: FakeChain, test: String) {
        dumpWitnesses(chain, test)
    }

    private val validator = "earthvaloper1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"

    private fun restoredLive(chain: FakeChain, indexer: PrivacyIndexer = chain): PrivacyWallet {
        val r = wallet(chain, indexer = indexer)
        r.sync()
        assertEquals(WalletSync.IdentityStatus.LIVE, r.identityStatus())
        return r
    }

    /** Appends a note row (a v1 note someone sent) at the block being built. */
    private fun sendNote(chain: FakeChain, to: network.erth.wallet.privacy.keys.ShieldedAddress, memo: ByteArray) {
        val n = network.erth.wallet.privacy.note.NotePlaintext.fresh("uerth", 0, memo)
        val cm = n.cm(to.ownerPk)
        val pos = chain.noteTree.append(cm)
        chain.notes.add(network.erth.wallet.privacy.sync.NoteRow(pos, chain.height, cm, network.erth.wallet.privacy.note.NoteCipher.encrypt(n, to), null))
    }

    /**
     * Thirty abandoned registration attempts, proofs whose broadcast failed, forty failed position locks:
     * a wallet restored from the mnemonic alone still finds every note the
     * chain minted (gas grant, registration ANML and reward, claim, swap
     * output, LP shares and refunds, derth, unbond claim, withdrawal legs),
     * every position, and its registration.
     */
    @Test
    fun restoreFindsEverythingAfterManyFailedAttempts() {
        val chain = FakeChain()
        chain.registrationCountry = "FR"
        val a = wallet(chain)
        a.sync()
        repeat(30) { a.prepareRegistration(null) }
        val prep = a.prepareRegistration(null)
        chain.shield("uerth", 100_000, prep.gas.pc, prep.gas.ciphertext)
        a.sync()
        // The first broadcast of the registration fails after proving; the retry lands.
        chain.rejectNext = 1
        assertThrows(IOException::class.java) { a.register(prep, ByteArray(14_656), signals(prep), "lean_poa", ByteArray(10)) }
        a.sync()
        a.register(prep, ByteArray(14_656), signals(prep), "lean_poa", ByteArray(10))
        a.sync()
        assertEquals(WalletSync.IdentityStatus.LIVE, a.identityStatus())
        assertNull(a.pendingRegistration)
        chain.now += 2 * 86_400
        a.sync()
        a.claimAnml()
        a.sync()
        // Swaps: five refused by the node after proving, one through.
        chain.rejectNext = 5
        repeat(5) { assertThrows(IOException::class.java) { a.noteSwap("uanml", 100_000, "uerth", 1) } }
        a.sync()
        a.noteSwap("uanml", 100_000, "uerth", 1)
        a.sync()
        a.addLiquidityShielded(1, "uanml", 300_000, 1_000_000, "1")
        a.sync()
        a.removeLiquidityShielded(1, "uanml", bal(a, "dexlp/1") / 3)
        a.sync()
        chain.matureWithdrawals()
        a.delegate(validator, 1_500_000)
        a.sync()
        // Forty locks proved and refused: owner-tag counters 0..39 burnt.
        chain.rejectNext = 40
        repeat(40) { assertThrows(IOException::class.java) { a.lockPosition(validator, 100_000, mapOf(2L to 100L)) } }
        a.sync()
        a.lockPosition(validator, 100_000, mapOf(2L to 100L))
        a.sync()
        assertEquals(40, a.positions().single().second)
        a.undelegate(validator, 200_000)
        a.sync()
        assertTrue(a.balances().keys.containsAll(listOf("uerth", "uanml", "dexlp/1", PrivacyWallet.derthDenom(validator))))
        // The undelegation waits for its payout (no claim note any more).
        assertEquals(1, a.pendingUnbonds.size)

        val restored = wallet(chain)
        restored.sync()
        assertEquals(a.balances(), restored.balances())
        assertEquals(a.positions().map { it.first.id to it.second }, restored.positions().map { it.first.id to it.second })
        // The registration, from its record note and the identity stream alone.
        assertEquals(WalletSync.IdentityStatus.LIVE, restored.identityStatus())
        val id = a.store.state.identity!!
        val rid = restored.store.state.identity!!
        assertEquals(listOf(id.leafIndex, id.dscKey, id.country, id.activatedAt), listOf(rid.leafIndex, rid.dscKey, rid.country, rid.activatedAt))
        assertEquals(Privacy.countryField("FR"), rid.country)
        // A restored wallet acts as one: it claims the next day.
        chain.now += 86_400
        restored.sync()
        restored.claimAnml()
        restored.sync()
        assertEquals(bal(a, "uanml") + 1_000_000, bal(restored, "uanml"))
        // A next lock takes a fresh tag past every one in use.
        restored.lockPosition(validator, 100_000, mapOf(3L to 100L))
        restored.sync()
        assertEquals(listOf(40, 41), restored.positions().map { it.second })
        dump(chain, "restore")
    }

    /** The identity rows carry their block's time: a restore never asks the LCD about the registration's block. */
    @Test
    fun restoreTakesTheBlockTimeFromTheIndexer() {
        val chain = FakeChain()
        register(chain, wallet(chain))
        repeat(40) { chain.emptyBlock() }
        chain.blockTimeAsks.clear()
        val r = restoredLive(chain)
        assertTrue(chain.blockTimeAsks.isEmpty())
        // Every country, each with predecessor_at 0 or the time itself.
        assertTrue(r.store.state.regRecords.single().work <= 2 * 677)
    }

    /** No time on the rows: the LCD is asked for a uniform cover set holding the block, once. */
    @Test
    fun restoreWithoutRowTimesAsksACoverSet() {
        val chain = FakeChain()
        register(chain, wallet(chain))
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

    /** A wrong indexer time does not give the record up; the LCD's (cover set) still finds it. */
    @Test
    fun wrongIndexerTimeDoesNotBlockRestore() {
        val chain = FakeChain()
        register(chain, wallet(chain))
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

    /** A record given up is tried again after a store reset (it is found afresh). */
    @Test
    fun exhaustedRecordIsRetriedAfterAReset() {
        val chain = FakeChain()
        register(chain, wallet(chain))
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

    /**
     * The cover set is never drawn past the chain's tip: an inflated
     * synced_height fails the sync before any ask (decoys past the tip would
     * leave the LCD one real height), and an honest restore without row times asks [COVER_SET] heights, every one
     * of them a block the chain has.
     */
    @Test
    fun coverSetIsNeverPastTheTip() {
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

    /** The record memo, version 2 with its tag (PRIVACY_FORMATS §6 golden). */
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

    /**
     * Forged record notes (untagged version 1, or version 2 with a
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
     * With no block time from the node, the fallback search is bounded
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
     * Positions closed before a restore are known from their unlock
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

    @Test
    fun stateRecordsRoundTripAndRefuseForgeries() {
        val k = PrivacyKeys.fromMnemonic(alice)
        val other = PrivacyKeys.fromMnemonic(bob)
        val h = WalletSync.handleMemo(k.nk, WalletSync.RECORD_HOLDS, "alice")
        assertEquals(64, h.size)
        assertEquals(StateRecord.Handle(WalletSync.RECORD_HOLDS, "alice"), WalletSync.parseStateMemo(k.nk, h))
        assertEquals(StateRecord.Handle(WalletSync.RECORD_MOVED_OUT, ""), WalletSync.parseStateMemo(k.nk, WalletSync.handleMemo(k.nk, WalletSync.RECORD_MOVED_OUT)))
        // Only nk tags one: another wallet's record, or a byte changed, is nothing.
        assertNull(WalletSync.parseStateMemo(other.nk, h))
        assertNull(WalletSync.parseStateMemo(k.nk, h.copyOf().also { it[5] = 'x'.code.toByte() }))
        val split = mapOf(1L to 60L, 300L to 40L)
        val c = WalletSync.caretakerMemo(k.nk, WalletSync.RECORD_HOLDS, 1_900_000_000, split)
        assertEquals(StateRecord.Caretaker(WalletSync.RECORD_HOLDS, 1_900_000_000, split), WalletSync.parseStateMemo(k.nk, c))
        // Twenty options with large ids do not fit: held, the split not recorded.
        val big = (0 until 20).associate { (1L shl 40) + it to 5L }
        assertEquals(StateRecord.Caretaker(WalletSync.RECORD_HOLDS, 1_900_000_000, null),
            WalletSync.parseStateMemo(k.nk, WalletSync.caretakerMemo(k.nk, WalletSync.RECORD_HOLDS, 1_900_000_000, big)))
        assertEquals(StateRecord.Caretaker(WalletSync.RECORD_NONE, 0, emptyMap()), WalletSync.parseStateMemo(k.nk, WalletSync.caretakerMemo(k.nk, WalletSync.RECORD_NONE)))
        // Goldens, the same in the iOS tests (Android/iOS byte parity).
        assertEquals("45480101616c6963650000000000000000000000000000000000000000000000000000000000000000000000000000001d745226aa7b8d3ed9c363b444ef95bb", h.joinToString("") { "%02x".format(it.toInt() and 0xff) })
        assertEquals("45430101713fb300013cac02280000000000000000000000000000000000000000000000000000000000000000000000035eda1d684c370b3c709013ca43fb93", c.joinToString("") { "%02x".format(it.toInt() and 0xff) })
        // A registration record is not a state record, and the reverse.
        assertNull(WalletSync.parseStateMemo(k.nk, WalletSync.regMemo(k.nk, Fr.of(77), "FR", 1_790_000_000)))
        assertNull(WalletSync.parseRegMemo(k.nk, h))
    }

    @Test
    fun restoreFindsTheHandleAndCaretakerSplit() {
        val chain = FakeChain()
        val a = wallet(chain, alice)
        register(chain, a, "111")
        a.bindHandle("alice")
        a.sync()
        a.setCaretaker(mapOf(1L to 70L, 2L to 30L))
        a.sync()
        val exp = a.caretakerExpiresAt()
        // The same mnemonic on a new phone: everything from the chain.
        val a2 = wallet(chain, alice)
        a2.sync()
        assertEquals("alice", a2.store.state.handle)
        assertEquals(mapOf(1L to 70L, 2L to 30L), a2.store.state.caretakerSplit)
        assertTrue(a2.caretakerLive())
        // The record's estimate never runs past the chain's expiry.
        assertTrue(a2.caretakerExpiresAt() in (exp - 3_600)..exp)
        // A release and a cleared split are found too.
        a.releaseHandle(); a.sync()
        a.setCaretaker(emptyMap()); a.sync()
        val a3 = wallet(chain, alice)
        a3.sync()
        assertEquals("", a3.store.state.handle)
        assertFalse(a3.caretakerLive())
    }
}
