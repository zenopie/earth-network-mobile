package network.erth.wallet.privacy

import java.net.ServerSocket
import kotlin.concurrent.thread
import network.erth.wallet.privacy.keys.PrivacyKeys
import network.erth.wallet.privacy.note.NoteCipher
import network.erth.wallet.privacy.note.NotePlaintext
import network.erth.wallet.privacy.sync.HeightPage
import network.erth.wallet.privacy.sync.HttpPrivacyIndexer
import network.erth.wallet.privacy.sync.IndexerBaseMoved
import network.erth.wallet.privacy.sync.NoteRow
import network.erth.wallet.privacy.sync.NotesPage
import network.erth.wallet.privacy.sync.PrivacyStore
import network.erth.wallet.privacy.sync.WalletSync
import network.erth.wallet.privacy.zk.Fr
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The indexer's streams as the wallet reads them: the notes format, the
 * backend's paging rule, pages that must advance, positions in range, a base
 * that moved, and backing off while the indexer sheds load.
 */
class IndexerStreamTest : WalletTest() {
    private val hex0 = "00".repeat(32)

    private val hex1 = "00".repeat(31) + "01"

    private val fields = """["position","height","cm","ciphertext","amount","owner_pk","rho","rcm"]"""

    private fun others(chain: FakeChain, n: Int) {
        repeat(n) { chain.mint("uerth", 1, NotePlaintext.randomField(), ByteArray(NoteCipher.BLIND_CIPHERTEXT_BYTES)) }
        chain.emptyBlock()
    }

    /**
     * k delegated before proposal 1's snapshot, beside a second note of ours
     * (another device's), and merged by a top-up (spent) after it: on chain it
     * still votes the value it held at the snapshot.
     */
    private fun voting(): Voting {
        val chain = FakeChain()
        val a = wallet(chain)
        repeat(6) { funded(chain, a, 2_000_000) }
        a.sync()
        a.delegate(vB, 666_666); a.sync()
        val k = a.stakeNotes.single { it.unspent }
        chain.plantStake(a.keys, PrivacyWallet.derthDenom(vB), 300_000); a.sync()
        chain.openProposal(1)
        a.delegate(vB, 333_333); a.sync()
        return Voting(chain, a, a.stakeNotes.first { it.position == k.position })
    }

    private val vB = "earthvaloper1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"

    private class Voting(val chain: FakeChain, val a: PrivacyWallet, val k: network.erth.wallet.privacy.note.OwnedStakeNote)

    private val status = JSONObject().put("chain_id", "earth-1").put("genesis", "0123456789abcdef")
        .put("base", "/privacy/earth-1/0123456789abcdef").put("synced_height", 1).put("halted", JSONObject.NULL).toString().toByteArray()

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


    @Test
    fun notesFormat2ByNameWithOpenRows() {
        // Columns read by name, in any order; the three row kinds.
        val page = HttpPrivacyIndexer.parseNotes(JSONObject("""{"format":2,"fields":["rcm","rho","owner_pk","amount","ciphertext","cm","height","position"],
            "notes":[[null,null,null,null,"AAAA","$hex1",5,0],[null,null,null,"7uerth","AAAA","$hex1",5,1],["$hex1","$hex0","$hex1","5000000uerth",null,"$hex1",6,2]],
            "next_pos":3,"complete":false,"synced_height":6}"""))
        assertEquals(listOf(0L, 1L, 2L), page.rows.map { it.position })
        assertNull(page.rows[0].amount); assertNull(page.rows[0].ownerPk)
        assertEquals("7uerth", page.rows[1].amount); assertNull(page.rows[1].rho)
        val open = page.rows[2]
        assertEquals(0, open.ciphertext.size)
        assertEquals(Fr.of(1), open.ownerPk); assertEquals(Fr.ZERO, open.rho); assertEquals(Fr.of(1), open.rcm)
        // An old backend (format 1, five columns) is refused, not misread.
        assertThrows(java.io.IOException::class.java) {
            HttpPrivacyIndexer.parseNotes(JSONObject("""{"notes":[[0,1,"$hex1","AAAA",null]],"next_pos":1,"complete":false,"synced_height":1}"""))
        }
        // Part of an opening, an opening beside a ciphertext, an open row with no amount: refused.
        for (row in listOf(
            """[0,1,"$hex1",null,"5uerth","$hex1",null,"$hex1"]""",
            """[0,1,"$hex1","AAAA","5uerth","$hex1","$hex1","$hex1"]""",
            """[0,1,"$hex1",null,null,"$hex1","$hex1","$hex1"]""",
            """[0,1,"$hex1",null,"5uerth",null,null,null]""",
        )) {
            assertThrows(row, java.io.IOException::class.java) {
                HttpPrivacyIndexer.parseNotes(JSONObject("""{"format":2,"fields":$fields,"notes":[$row],"next_pos":1,"complete":false,"synced_height":1}"""))
            }
        }
    }

    /** Empty pages that say more follows are inconsistent at once, not asked forever. */
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

    /** A position past u32 (or an oversized page) is an inconsistency, never a crash. */
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
                NotesPage(List(WalletSync.PAGE_SIZE + 1) { NoteRow(fromPos + it, 1, Fr.ONE, ByteArray(0), null) }, fromPos, false, 1)
        }
        assertThrows(WalletSync.Inconsistent::class.java) { wallet(chain, indexer = big).sync() }
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
        assertNotNull(v.a.castStakeVote(1, PrivacyWallet.StakeVoteItem.Validator(vB, 2), yes))
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
                else -> "HTTP/1.0 200 OK\r\nContent-Type: application/json" to """{"format":2,"fields":["position","height","cm","ciphertext","amount","owner_pk","rho","rcm"],"notes":[],"next_pos":0,"complete":false,"synced_height":1}""".toByteArray()
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
}
