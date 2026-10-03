package network.erth.wallet.privacy

import cosmos.gov.v1.VoteOption as GovVoteOption
import cosmos.gov.v1.WeightedVoteOption
import network.erth.wallet.privacy.keys.PrivacyKeys
import network.erth.wallet.privacy.note.NoteCipher
import network.erth.wallet.privacy.note.NotePlaintext
import network.erth.wallet.privacy.sync.IndexerBaseMoved
import network.erth.wallet.privacy.sync.IndexerHalted
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
import network.erth.wallet.privacy.zk.Privacy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The client audit's findings, each driven against [FakeChain]: C1 (every
 * chain-minted note found again from the mnemonic, however many attempts
 * failed), C2 (a registration is never lost to a lagging indexer), C3
 * (indexer trees checked against the chain), L4 (stake votes one at a time),
 * L7 (an out-of-range position is an inconsistency, not a crash), L8
 * (identity restored from the mnemonic alone), and the indexer's URL scheme.
 */
class AuditFixesTest {
    private val alice = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private val validator = "earthvaloper1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"
    private val validator2 = "earthvaloper1qyqszqgpqyqszqgpqyqszqgpqyqszqgpjnp7du"
    private val yes = listOf(WeightedVoteOption.newBuilder().setOption(GovVoteOption.VOTE_OPTION_YES).setWeight("1").build())
    private val pauses = ArrayList<Long>()
    private var snapshot: Long? = null

    private fun reads(chain: FakeChain) = object : PrivacyChainReads {
        override fun personhoodParams() = PrivacyChainReads.PersonhoodParams(30L * 86_400, 3_600)
        override fun ballotInputs(proposalId: Long, optionId: Long) =
            PrivacyChainReads.BallotInputs(Privacy.proposalScope(proposalId, 0), Fr.ZERO, Fr.ZERO, chain.now - 3600, 0, 0)
        override fun epochNumber() = chain.epoch
        override fun snapshot(proposalId: Long) = (snapshot ?: chain.stakeTree.size).let { PrivacyChainReads.Snapshot(chain.stakeTree.rootAt(it), it) }
        override fun positions() = chain.positionReads()
    }

    private fun wallet(chain: FakeChain, words: String = alice, indexer: PrivacyIndexer = chain, store: PrivacyStore = PrivacyStore.memory()) =
        PrivacyWallet(
            PrivacyKeys.fromMnemonic(words), store, indexer, chain, reads(chain), chain.prover, chain.chainId, chain,
            now = { chain.now }, pause = { pauses.add(it) },
        )

    private fun bal(w: PrivacyWallet, d: String) = w.balances()[d] ?: 0L

    private fun signals(prep: PrivacyWallet.RegistrationPrep, nullifier: String = "123456789") =
        listOf("261001", prep.binding.toBigInteger().toString(), nullifier, Fr.of(77).toBigInteger().toString())

    /** Delegates to [inner]; a test overrides what it needs. */
    private open class Wrapped(val inner: PrivacyIndexer) : PrivacyIndexer by inner

    /**
     * C1 + L8: thirty abandoned registration attempts (the audit PoC took
     * eleven), proofs whose broadcast failed, forty failed position locks:
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
        assertTrue(a.unbondDenoms().isNotEmpty())

        val restored = wallet(chain)
        restored.sync()
        assertEquals(a.balances(), restored.balances())
        assertEquals(a.positions().map { it.first.id to it.second }, restored.positions().map { it.first.id to it.second })
        // L8: the registration, from its record note and the identity stream alone.
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

    /** With PRIVACY_TOML_OUT set, every witness as a nargo Prover.toml (see WalletFlowTest). */
    private fun dump(chain: FakeChain, test: String) {
        val out = System.getenv("PRIVACY_TOML_OUT") ?: return
        fun write(kind: String, i: Int, toml: String) =
            java.io.File(out, "$kind/${test}_$i/Prover.toml").apply { parentFile.mkdirs() }.writeText(toml)
        chain.prover.allActions.forEachIndexed { i, w -> write("action", i, w.proverToml()) }
        chain.prover.allStakes.forEachIndexed { i, w -> write("stake", i, w.proverToml()) }
        chain.prover.allMemberships.forEachIndexed { i, w -> write("membership", i, w.proverToml()) }
    }

    /** C2: the indexer is down when the registration commits; the pending record survives and resolves later. */
    @Test
    fun registrationIsNeverLostToALaggingIndexer() {
        val chain = FakeChain()
        var down = false
        val idx = object : Wrapped(chain) {
            override fun status() = if (down) throw IOException("indexer down") else inner.status()
        }
        val store = PrivacyStore.memory()
        val a = wallet(chain, indexer = idx, store = store)
        a.sync()
        val prep = a.prepareRegistration(null)
        chain.shield("uerth", 100_000, prep.gas.pc, prep.gas.ciphertext)
        a.sync()
        down = true
        a.register(prep, ByteArray(14_656), signals(prep), "lean_poa", ByteArray(10))
        val p = assertNotNull(a.pendingRegistration).let { a.pendingRegistration!! }
        assertEquals("123456789", p.passportNullifier)
        assertEquals(WalletSync.IdentityStatus.NONE, a.identityStatus())
        assertThrows(IOException::class.java) { a.sync() }
        assertNotNull(a.pendingRegistration)
        down = false
        a.sync()
        assertNull(a.pendingRegistration)
        assertEquals(WalletSync.IdentityStatus.LIVE, a.identityStatus())
        assertEquals("123456789", store.state.identity!!.passportNullifier)
    }

    /** C3: an indexer serving a note the chain never had (encrypted to us, with roots to match) is refused. */
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
        assertThrows(WalletSync.ChainMismatch::class.java) { w.sync() }
        // Nothing synced from it is kept or spendable.
        assertTrue(w.balances().isEmpty())
        assertTrue(w.store.state.rootsError!!.contains("never recorded"))
        assertThrows(IllegalStateException::class.java) { w.unshield("earth1qqqsyqcyq5rqwzqfpg9scrgwpugpzysncc2uls", "uerth", 1) }
        forge = false
        w.sync()
        assertEquals(1_000_000L, bal(w, "uerth"))
        assertTrue(w.store.state.rootsVerified)
    }

    /** A halted indexer is not synced from; a new genesis under the same chain id wipes the local data. */
    @Test
    fun haltedIndexerAndRelaunch() {
        val chain = FakeChain()
        val a = wallet(chain)
        val o = a.shieldOutput("uerth", 0)
        chain.shield("uerth", 1_000_000, o.pc, o.ciphertext)
        a.sync()
        assertEquals(1_000_000L, bal(a, "uerth"))
        chain.halted = "note tree size differs from the chain's"
        assertThrows(IndexerHalted::class.java) { a.sync() }
        chain.halted = null
        a.store.state.claimedDays.add(5)
        chain.genesis = "fedcba9876543210"
        a.sync()
        assertEquals("fedcba9876543210", a.store.state.genesis)
        // Wiped and resynced from zero: nothing of the old chain's bookkeeping survives.
        assertTrue(a.store.state.claimedDays.isEmpty())
        assertEquals(1_000_000L, bal(a, "uerth"))
    }

    /** The indexer's base moved (404): the wallet reads the status again and carries on. */
    @Test
    fun movedBaseIsReread() {
        val chain = FakeChain()
        var moved = 1
        var statuses = 0
        val idx = object : Wrapped(chain) {
            override fun status() = inner.status().also { statuses++ }
            override fun notes(fromPos: Long, limit: Int?): NotesPage {
                if (moved-- > 0) throw IndexerBaseMoved("404")
                return inner.notes(fromPos, limit)
            }
        }
        wallet(chain, indexer = idx).sync()
        assertEquals(2, statuses)
    }

    /** L7: a position past u32 (or an oversized page) is an inconsistency, never a crash. */
    @Test
    fun outOfRangePositionsAreInconsistent() {
        val chain = FakeChain()
        val idx = object : Wrapped(chain) {
            override fun notes(fromPos: Long, limit: Int?) = NotesPage(
                listOf(NoteRow(fromPos, 1, Fr.ONE, ByteArray(217), null)), fromPos + 1, false, 1,
            ).let { if (fromPos == 0L) it.copy(rows = listOf(NoteRow(0x1_0000_0000L, 1, Fr.ONE, ByteArray(0), null))) else it }
        }
        assertThrows(WalletSync.Inconsistent::class.java) { wallet(chain, indexer = idx).sync() }
        val big = object : Wrapped(chain) {
            override fun notes(fromPos: Long, limit: Int?) =
                NotesPage(List(WalletSync.MAX_PAGE_ROWS + 1) { NoteRow(fromPos + it, 1, Fr.ONE, ByteArray(0), null) }, fromPos, false, 1)
        }
        assertThrows(WalletSync.Inconsistent::class.java) { wallet(chain, indexer = big).sync() }
    }

    /** L4: stake votes go one at a time, a sync and a random pause between them. */
    @Test
    fun stakeVotesAreSpacedOut() {
        val chain = FakeChain()
        val a = wallet(chain)
        repeat(3) {
            val o = a.shieldOutput("uerth", 0)
            chain.shield("uerth", 2_000_000, o.pc, o.ciphertext)
        }
        a.sync()
        a.delegate(validator, 1_000_000)
        a.sync()
        a.delegate(validator2, 1_000_000)
        a.sync()
        snapshot = chain.stakeTree.size
        val before = chain.height
        val votes = a.stakeVoteAll(12, yes)
        snapshot = null
        assertEquals(2, votes.size)
        assertEquals(1, pauses.size)
        assertTrue(pauses.single() in PrivacyWallet.VOTE_PAUSE_MIN_MS..PrivacyWallet.VOTE_PAUSE_MAX_MS)
        assertEquals(setOf(validator, validator2), chain.stakeVotes.map { it.second }.toSet())
        // Separate blocks, the second laid out after a sync saw the first.
        assertEquals(before + 2, chain.height)
        dump(chain, "votes")
    }
}
