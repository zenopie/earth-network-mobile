package network.erth.wallet.privacy

import network.erth.wallet.privacy.keys.PrivacyKeys
import network.erth.wallet.privacy.sync.IdentityPage
import network.erth.wallet.privacy.sync.IdentityRow
import network.erth.wallet.privacy.sync.LatestRoots
import network.erth.wallet.privacy.sync.PrivacyStore
import network.erth.wallet.privacy.sync.RootRecord
import network.erth.wallet.privacy.sync.WalletSync
import network.erth.wallet.privacy.zk.Fr
import network.erth.wallet.privacy.zk.MemNodeStore
import network.erth.wallet.privacy.zk.MerkleTree
import network.erth.wallet.privacy.zk.Privacy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The identity a wallet holds: matched only against a verified identity
 * tree with block times the chain could have, dropped on a mismatch or a
 * reset, and its daily claim's checked arithmetic.
 */
class IdentityTest : WalletTest() {
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
     * Forged rows naming activated_at = T (a claimOpensAt trap) with
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
     * A forged activated_at does not survive a ChainMismatch: pinned, the
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

    /** A reset keeps only a verified identity (a store from an older version may hold an unverified one). */
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

    /** Info: a claim for day 0 is refused (iOS trapped on the underflow). */
    @Test
    fun claimDayZeroIsRefused() {
        val chain = FakeChain()
        val a = wallet(chain)
        register(chain, a)
        assertThrows(IllegalArgumentException::class.java) { a.claimAnml(0) }
    }
}
