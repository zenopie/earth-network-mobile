package network.erth.wallet.privacy

import network.erth.wallet.privacy.sync.IndexerHalted
import network.erth.wallet.privacy.sync.WalletSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A chain relaunch (a new genesis) or a halted indexer: the switch is
 * confirmed by the chain before anything is wiped, and the registration
 * survives it.
 */
class GenesisSwitchTest : WalletTest() {
    /** A relaunch the LCD confirms keeps the registration (record, passport nullifier) and re-verifies its leaf. */
    @Test
    fun genesisSwitchKeepsIdentity() {
        val chain = FakeChain()
        val a = wallet(chain)
        register(chain, a)
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

    /** An indexer's new genesis the LCD does not confirm (or cannot) wipes nothing and syncs nothing. */
    @Test
    fun unverifiedGenesisSwitchKeepsEverything() {
        val chain = FakeChain()
        val a = wallet(chain)
        register(chain, a)
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

    /** A status naming no chain is refused. */
    @Test
    fun nullChainIdIsRefused() {
        val chain = FakeChain()
        val idx = object : Wrapped(chain) {
            override fun status() = inner.status().copy(chainId = null)
        }
        assertThrows(IllegalStateException::class.java) { wallet(chain, indexer = idx).sync() }
    }

    @Test
    fun storeWithoutGenesisKeepsIdentity() {
        val chain = FakeChain()
        val a = wallet(chain)
        register(chain, a)
        val id = a.store.state.identity
        a.store.state.genesis = null
        a.store.save()
        a.sync()
        assertEquals(id, a.store.state.identity)
        assertEquals(WalletSync.IdentityStatus.LIVE, a.identityStatus())
        assertEquals(chain.genesis, a.store.state.genesis)
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
}
