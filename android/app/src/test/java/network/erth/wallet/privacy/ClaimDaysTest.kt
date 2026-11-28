package network.erth.wallet.privacy

import network.erth.wallet.privacy.sync.WalletSync
import network.erth.wallet.privacy.zk.Fr
import network.erth.wallet.privacy.zk.Privacy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MsgClaimAnmlDays (chain v1.4.0): a claim takes every unclaimed day up to
 * a week, a run ending today, with one claim proof; the first claim's delay
 * holds; a restored wallet learns the days claimed before it from the
 * chain's refusal and claims after them.
 */
class ClaimDaysTest : WalletTest() {
    private fun registered(chain: FakeChain): PrivacyWallet {
        val a = wallet(chain)
        a.sync()
        val prep = a.prepareRegistration(null)
        chain.shield("uerth", 100_000, prep.gas.pc, prep.gas.ciphertext)
        a.sync()
        val signals = listOf("261001", prep.binding.toBigInteger().toString(), "123456789", Fr.of(77).toBigInteger().toString(), prep.idc.toBigInteger().toString())
        a.register(prep, ByteArray(14_656), signals, "lean_poa", ByteArray(10))
        a.sync()
        assertEquals(WalletSync.IdentityStatus.LIVE, a.identityStatus())
        return a
    }

    @Test
    fun claimsEveryUnclaimedDayUpToAWeek() {
        val chain = FakeChain()
        chain.claimDays = true
        val a = registered(chain)
        val r = chain.now / 86_400
        // Registered on day r: the first claim is day r + 2, whatever the run.
        assertEquals(0, a.claimOutlook()?.days)
        chain.now += 5 * 86_400
        a.sync()
        assertEquals(4, a.claimOutlook()?.days)
        a.claimAnml()
        a.sync()
        assertEquals(5_000_000L, bal(a, "uanml"))
        val w = chain.prover.allClaims.single()
        assertEquals((r + 2..r + 5).map { Privacy.claimScope(it) }, w.scopes)
        assertEquals((r + 1) * 86_400, w.maxActivation)
        assertTrue(a.claimedToday())
        assertEquals(0, a.claimOutlook()?.days)
        assertThrows(IllegalStateException::class.java) { a.claimAnml() }

        // Two days on: two days.
        chain.now += 2 * 86_400
        a.sync()
        assertEquals(2, a.claimOutlook()?.days)
        a.claimAnml()
        a.sync()
        assertEquals(7_000_000L, bal(a, "uanml"))

        // Ten days away: a week's worth, the rest lapsed.
        chain.now += 10 * 86_400
        a.sync()
        assertEquals(7, a.claimOutlook()?.days)
        a.claimAnml()
        a.sync()
        assertEquals(14_000_000L, bal(a, "uanml"))
        assertEquals(7, chain.prover.allClaims.last().scopes.size)
        dumpWitnesses(chain, "claimdays")
    }

    @Test
    fun restoredWalletClaimsAfterTheDaysClaimedBeforeIt() {
        val chain = FakeChain()
        chain.claimDays = true
        val a = registered(chain)
        chain.now += 6 * 86_400
        a.sync()
        a.claimAnml()
        a.sync()
        val before = bal(a, "uanml")

        // Restored from the mnemonic: it knows no claimed day.
        chain.now += 2 * 86_400
        val restored = wallet(chain)
        restored.sync()
        assertEquals(WalletSync.IdentityStatus.LIVE, restored.identityStatus())
        restored.claimAnml()
        restored.sync()
        // The two days since, not one claimed twice.
        assertEquals(before + 2_000_000L, bal(restored, "uanml"))
        assertEquals(2, chain.prover.allClaims.last().scopes.size)
    }

    /**
     * A run the node took that then fails in its block (another claim of a
     * day landed first) has spent its fee: the wallet reports it and never
     * sends another on its own.
     */
    @Test
    fun aClaimThatFailedInItsBlockIsNotSentAgain() {
        val chain = FakeChain()
        chain.claimDays = true
        val a = registered(chain)
        chain.now += 5 * 86_400
        a.sync()
        val day = chain.now / 86_400
        chain.failInBlockNext = 1
        chain.failInBlockCode = 1131
        chain.failInBlockLog = "day $day: a day in this claim has already been claimed"
        assertThrows(java.io.IOException::class.java) { a.claimAnml() }
        assertEquals(1, chain.prover.allClaims.size)
    }

    /** Today claimed by another device: refused, and the claim stops being offered today. */
    @Test
    fun aRestoredWalletLearnsTodayIsClaimed() {
        val chain = FakeChain()
        chain.claimDays = true
        val a = registered(chain)
        chain.now += 5 * 86_400
        a.sync()
        a.claimAnml()
        val restored = wallet(chain)
        restored.sync()
        assertEquals(0L, restored.claimOpensAt())
        assertThrows(IllegalStateException::class.java) { restored.claimAnml() }
        assertTrue(restored.claimedToday())
        assertTrue(restored.claimOpensAt()!! > 0)
        assertEquals(0, restored.claimOutlook()?.days)
    }

    @Test
    fun beforeTheUpgradeTodayAlone() {
        val chain = FakeChain()
        val a = registered(chain)
        chain.now += 5 * 86_400
        a.sync()
        assertEquals(1, a.claimOutlook()?.days)
        a.claimAnml()
        a.sync()
        assertEquals(2_000_000L, bal(a, "uanml"))
        assertTrue(chain.prover.allClaims.isEmpty())
    }

    @Test
    fun theFloorBoundsTheRun() {
        val chain = FakeChain()
        chain.claimDays = true
        val a = registered(chain)
        chain.now += 6 * 86_400
        chain.claimFloor = chain.now / 86_400 - 1
        a.sync()
        assertEquals(2, a.claimOutlook()?.days)
        a.claimAnml()
        a.sync()
        assertEquals(3_000_000L, bal(a, "uanml"))
    }
}
