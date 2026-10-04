package network.erth.wallet.privacy

import network.erth.wallet.chain.ChainErrors
import network.erth.wallet.privacy.note.AssetDenoms
import network.erth.wallet.privacy.note.Denoms
import network.erth.wallet.privacy.note.NoteCipher
import network.erth.wallet.privacy.note.NotePlaintext
import network.erth.wallet.privacy.zk.Fr
import network.erth.wallet.privacy.zk.Privacy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Denoms: the SDK's rule, learned only from the wallet's own notes or the
 * chain's asset list, and send-disabled denoms refused with a clear error.
 */
class DenomsTest : WalletTest() {
    /** A v2 mint of [value] [denom] to [w] (what every chain mint is), one block. */
    private fun mintTo(chain: FakeChain, w: PrivacyWallet, denom: String, value: Long): Long {
        val n = NotePlaintext.fresh(denom, value)
        val pos = chain.notes.size.toLong()
        chain.shield(denom, value, n.pc(w.keys.ownerPk), NoteCipher.encryptBlind(n, w.keys.address))
        return pos
    }

    private val vB = "earthvaloper1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"

    @Test
    fun denomsFollowTheSdkRuleAndNeverTheInternalName() {
        assertTrue(Denoms.valid("uerth"))
        assertTrue(Denoms.valid("derth/earthvaloper1abc"))
        assertTrue(Denoms.valid("unbond/earthvaloper1abc/42"))
        assertFalse(Denoms.valid("asset/0a"))
        assertFalse(Denoms.valid("ab"))
        assertFalse(Denoms.valid("1abc"))
        assertFalse(Denoms.valid("a b c"))
        assertFalse(Denoms.valid("a".repeat(129)))
        val d = AssetDenoms()
        assertFalse(d.learn("asset/" + Privacy.assetId("uerth").toHex()))
        // An id the chain states for a denom is learned only if it is the denom's own.
        assertFalse(d.learn("ufoo", Privacy.assetId("ubar")))
        assertTrue(d.learn("ufoo", Privacy.assetId("ufoo")))
        assertEquals("ufoo", d.resolve(Privacy.assetId("ufoo")))
    }

    @Test
    fun anAssetSentinelOrMalformedRowAmountIsRefusedNotStoredAndNeverDuplicates() {
        val chain = FakeChain()
        val w = wallet(chain, alice)
        val good = mintTo(chain, w, "uerth", 1_000)
        val relabeled = mintTo(chain, w, "uerth", 1_000_000)
        val broken = mintTo(chain, w, "uerth", 2_000)
        // A hostile indexer relabels a mint as the internal "asset/<hex>" name of uerth's own id,
        // and serves another with a non-hex sentinel (which threw part way through a page before).
        chain.notes[relabeled.toInt()] = chain.notes[relabeled.toInt()].copy(amount = "1000000asset/" + Privacy.assetId("uerth").toHex())
        chain.notes[broken.toInt()] = chain.notes[broken.toInt()].copy(amount = "2000asset/zz")
        w.sync()
        assertTrue(w.notes.none { it.note.denom.startsWith(NotePlaintext.UNRESOLVED_PREFIX) })
        assertEquals(listOf(good), w.notes.map { it.position })
        w.sync()
        assertEquals(1, w.notes.size)
        assertEquals(chain.notes.size.toLong(), w.store.state.notesNext)
    }

    @Test
    fun aStoredSentinelNoteIsRenamedBack() {
        val chain = FakeChain()
        val w = wallet(chain, alice)
        mintTo(chain, w, "uerth", 5_000)
        w.sync()
        // As an older version's store, which let an indexer relabel.
        val n = w.store.state.notes[0]
        w.store.state.notes[0] = n.copy(note = n.note.copy(denom = NotePlaintext.UNRESOLVED_PREFIX + Privacy.assetId("uerth").toHex()))
        w.sync()
        assertEquals("uerth", w.store.state.notes[0].note.denom)
        assertEquals(5_000L, w.balances()["uerth"])
    }

    @Test
    fun denomsAreLearnedOnlyFromOwnNotesOrTheChainsAssetList() {
        val chain = FakeChain()
        val a = wallet(chain, alice)
        val b = wallet(chain, bob)
        register(chain, a, "601")
        // Rows not ours, each with a junk denom in the amount column.
        repeat(40) { i ->
            val n = NotePlaintext.fresh("uerth", 7)
            val pos = chain.notes.size
            chain.shield("uerth", 7, n.pc(Fr.of(1000L + i)), NoteCipher.encryptBlind(n, b.keys.address))
            chain.notes[pos] = chain.notes[pos].copy(amount = "7junk$i")
        }
        mintTo(chain, a, "ufoo", 9_000)
        a.sync()
        assertTrue(a.store.state.denoms.none { it.startsWith("junk") })
        assertTrue("ufoo" in a.store.state.denoms)
        // b learns ufoo from a v1 note only through the chain's asset list (id checked).
        b.sync()
        a.send(b.keys.address, "ufoo", 4_000)
        chain.assetList = listOf("ufoo" to Privacy.assetId("ubar"))
        b.sync()
        assertEquals(listOf(NotePlaintext.UNRESOLVED_PREFIX + Privacy.assetId("ufoo").toHex()), b.notes.map { it.note.denom })
        chain.assetList = listOf("ufoo" to Privacy.assetId("ufoo"))
        b.sync()
        assertEquals(listOf("ufoo"), b.notes.map { it.note.denom })
        assertEquals(4_000L, b.balances()["ufoo"])
    }

    @Test
    fun sendDisabledIsRefusedOnSwapsAndDelegationWithAClearError() {
        val chain = FakeChain()
        val a = wallet(chain)
        repeat(3) { funded(chain, a, 2_000_000) }
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
}
