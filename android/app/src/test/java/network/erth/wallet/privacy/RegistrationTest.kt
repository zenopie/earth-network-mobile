package network.erth.wallet.privacy

import java.io.IOException
import network.erth.wallet.privacy.keys.PrivacyKeys
import network.erth.wallet.privacy.note.NotePlaintext
import network.erth.wallet.privacy.sync.PrivacyStore
import network.erth.wallet.privacy.sync.WalletSync
import network.erth.wallet.privacy.tx.PrivateTxEngine
import network.erth.wallet.privacy.zk.Fr
import network.erth.wallet.privacy.zk.Privacy
import network.erth.wallet.referral.Referral
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Registration: the binding the passport proof carries, the passport's
 * date, a referral by handle, and a registration that is never lost
 * (recorded at acceptance, shown when its tx failed in its block).
 */
class RegistrationTest : WalletTest() {
    private fun signals(prep: PrivacyWallet.RegistrationPrep, nullifier: String = "123456789") =
        listOf("261001", prep.binding.toBigInteger().toString(), nullifier, Fr.of(77).toBigInteger().toString())

    @Test
    fun registrationBindingNamesTheChain() {
        val d = Vectors.json.getJSONObject("derive")
        assertEquals("148b3513a501b6ff9c02314f355cb83fb544e22b2a9df79552fe49c944424159", d.getString("reg_pinned"))
        val one = Privacy.registrationBinding("earth-1", Fr.of(1), Fr.of(2), "anml".toByteArray(), Fr.of(3), "erth".toByteArray(), Fr.ZERO)
        assertEquals(d.getString("reg_pinned"), one.toHex())
        val other = Privacy.registrationBinding("earth-testnet-1", Fr.of(1), Fr.of(2), "anml".toByteArray(), Fr.of(3), "erth".toByteArray(), Fr.ZERO)
        assertTrue(one != other)
        // The wallet binds its own chain's id.
        val chain = FakeChain()
        val prep = wallet(chain).prepareRegistration(null)
        assertEquals(Privacy.registrationBinding(chain.chainId, PrivacyKeys.fromMnemonic(alice).idc, prep.anml.pc, prep.anml.ciphertext,
            prep.erth.pc, prep.erth.ciphertext, Fr.ZERO), prep.binding)
    }

    @Test
    fun registrationBindsTheHandleOnly() {
        val chain = FakeChain()
        val a = wallet(chain, alice)
        val prep = a.prepareRegistration(PrivacyWallet.Referrer("bobby", wallet(chain, bob).address))
        val msg = a.registerMsg(prep, ByteArray(1), listOf("1", "2", "3", "4"), "lean_poa", ByteArray(1))
        assertEquals("bobby", msg.affiliateHandle)
        assertEquals(Privacy.affiliateField("bobby"), network.erth.wallet.privacy.tx.PrivateMsgs.affiliateField(msg))
        // No referrer: 0, and no handle on the wire.
        val none = a.registerMsg(a.prepareRegistration(null), ByteArray(1), listOf("1", "2", "3", "4"), "lean_poa", ByteArray(1))
        assertEquals("", none.affiliateHandle)
        assertEquals(Fr.ZERO, network.erth.wallet.privacy.tx.PrivateMsgs.affiliateField(none))
        assertTrue(NotePlaintext.fresh("uerth", 1).rho != Fr.ZERO)
    }

    /** A passport proof whose current_date is not a calendar date is refused before broadcast. */
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

    @Test
    fun referralLinksOnlyFromTheVerifiedHost() {
        assertEquals("alice", Referral.handleFromLink("https://erth.network/ref/alice"))
        assertEquals("a-b-c", Referral.handleFromLink("https://erth.network/ref/a-b-c/"))
        for (bad in listOf(
            "earth://ref/alice", "http://erth.network/ref/alice", "https://erth.network.evil.com/ref/alice",
            "https://evil.com/ref/alice", "https://erth.network/ref/alice/more", "https://erth.network/r/alice",
            "https://erth.network:8443/ref/alice", "https://user@erth.network/ref/alice", "https://erth.network/ref/Alice",
            "https://erth.network/ref/-x-", "https://erth.network/ref/", null,
        )) assertNull(bad, Referral.handleFromLink(bad))
    }

    /** The indexer is down when the registration commits; the pending record survives and resolves later. */
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

    /**
     * The registration is recorded by hash the moment the node accepts
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

    /** Accepted then failed in its block: the failure is kept for the UI, the gas note released later. */
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
        // Public now, it may still land while 261001 is in the chain's 48 h skew.
        assertEquals(1_790_812_800L + PrivacyWallet.REGISTRATION_SKEW_SECONDS, a.store.state.registrationKeepUntil)
        assertEquals(0L, bal(a, "uerth"))
        // Released only once the chain is past the tx's timeout_height, not by the clock.
        chain.now += WalletSync.PENDING_TIMEOUT_S + 1
        a.sync()
        assertEquals(0L, bal(a, "uerth"))
        repeat(PrivateTxEngine.TIMEOUT_BLOCKS.toInt() + 1) { chain.emptyBlock() }
        a.sync()
        assertEquals(100_000L, bal(a, "uerth"))
        // A new registration replaces it.
        a.register(prep, ByteArray(14_656), sigs, "lean_poa", ByteArray(10))
        a.sync()
        assertEquals(WalletSync.IdentityStatus.LIVE, a.identityStatus())
        chain.now = 1_790_812_800L + PrivacyWallet.REGISTRATION_SKEW_SECONDS + 1
        assertTrue(a.store.state.registrationKeepUntil < chain.now)
    }

    /** The keep window is the chain's current_date_max_skew_seconds, read before the broadcast, never below 48 h. */
    @Test
    fun theKeepWindowFollowsTheChainsSkew() {
        assertEquals(PrivacyWallet.REGISTRATION_SKEW_SECONDS, PrivacyWallet.keepSkew(null))
        assertEquals(PrivacyWallet.REGISTRATION_SKEW_SECONDS, PrivacyWallet.keepSkew(0))
        assertEquals(PrivacyWallet.REGISTRATION_SKEW_SECONDS, PrivacyWallet.keepSkew(3_600))
        assertEquals(5 * 86_400L, PrivacyWallet.keepSkew(5 * 86_400L))
        assertEquals(PrivacyWallet.MAX_REGISTRATION_SKEW_SECONDS, PrivacyWallet.keepSkew(Long.MAX_VALUE))
        val chain = FakeChain()
        chain.currentDateMaxSkew = 5 * 86_400L
        val a = wallet(chain)
        val prep = a.prepareRegistration(null)
        chain.shield("uerth", 100_000, prep.gas.pc, prep.gas.ciphertext)
        a.sync()
        chain.failInBlockNext = 1
        val sigs = listOf("261001", prep.binding.toBigInteger().toString(), "1", Fr.of(77).toBigInteger().toString())
        assertThrows(java.io.IOException::class.java) { a.register(prep, ByteArray(14_656), sigs, "lean_poa", ByteArray(10)) }
        assertEquals(1_790_812_800L + 5 * 86_400L, a.store.state.registrationKeepUntil)
    }
}
