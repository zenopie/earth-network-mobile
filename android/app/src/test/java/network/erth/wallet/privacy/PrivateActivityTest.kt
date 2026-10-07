package network.erth.wallet.privacy

import network.erth.wallet.privacy.PrivateActivityKind as K
import network.erth.wallet.privacy.PrivateActivityRow.Status
import network.erth.wallet.privacy.keys.PrivacyKeys
import network.erth.wallet.privacy.sync.PrivacyStore
import network.erth.wallet.privacy.zk.Fr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The private activity list, built from the sealed store alone: sent txs as
 * recorded (their change and minted notes folded in), received notes
 * labelled, the pending to confirmed transition from sync, failures, and a
 * restored wallet's spends inferred. iOS: PrivateActivityTests.
 */
class PrivateActivityTest {
    private val alice = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private val bob = "legal winner thank year wave sausage worth useful legal winner thank yellow"

    private fun reads(chain: FakeChain) = object : PrivacyChainReads {
        override fun personhoodParams() = PrivacyChainReads.PersonhoodParams(30L * 86_400, 3_600)
        override fun leaseBounds() = chain.leaseBounds()
        override fun ballotInputs(proposalId: Long, optionId: Long) = throw UnsupportedOperationException()
        override fun epochNumber() = chain.epoch
        override fun snapshot(proposalId: Long) = chain.snapshotRead(proposalId)
        override fun stakeNullifierTree(start: Long, limit: Int) = chain.nfTreeRead(start, limit)
        override fun positions() = chain.positionReads()
        override fun debtTree(start: Long, limit: Int) = chain.debtTreeRead(start, limit)
        override fun validators() = chain.validatorsRead()
        override fun minDelegation() = chain.minDelegation
    }

    private fun wallet(chain: FakeChain, words: String, store: PrivacyStore = PrivacyStore.memory()) = PrivacyWallet(
        PrivacyKeys.fromMnemonic(words), store, chain, chain, reads(chain), chain.prover, chain.chainId, chain,
        now = { chain.now },
    )

    /** Alice: gas grant, registration, a claim; bob funded by a shield. */
    private fun registered(chain: FakeChain): PrivacyWallet {
        val a = wallet(chain, alice)
        a.sync()
        val prep = a.prepareRegistration(null)
        chain.shield("uerth", 100_000, prep.gas.pc, prep.gas.ciphertext)
        a.sync()
        val signals = listOf("261001", prep.binding.toBigInteger().toString(), "123456789", Fr.of(77).toBigInteger().toString(), prep.idc.toBigInteger().toString())
        a.register(prep, ByteArray(14_656), signals, "lean_poa", ByteArray(10))
        a.sync()
        return a
    }

    @Test
    fun sentTxsFoldTheirOwnNotesAndReceivedNotesAreLabelled() {
        val chain = FakeChain()
        val a = registered(chain)
        var rows = a.activity()
        // The gas grant: a note the wallet expected, labelled as one.
        val gas = rows.single { it.kind == K.GAS_GRANT }
        assertEquals(listOf(ActivityCoin("uerth", 100_000)), gas.coins)
        assertNull(gas.hash)
        // The registration: its reward notes folded in as what came in, its change never a row.
        val reg = rows.single { it.kind == K.REGISTER }
        assertEquals(Status.CONFIRMED, reg.status)
        assertNotNull(reg.hash)
        assertTrue(reg.fee!! > 0)
        assertTrue(reg.coins.any { it.denom == "uanml" && it.amount == 1_000_000L })
        assertTrue(reg.coins.any { it.denom == "uerth" && it.amount > 0 })
        assertEquals(2, rows.size)

        chain.now += 2 * 86_400
        a.sync()
        a.claimAnml()
        a.sync()
        val claim = a.activity().single { it.kind == K.CLAIM_ANML }
        assertEquals(Status.CONFIRMED, claim.status)
        assertTrue(claim.coins.contains(ActivityCoin("uanml", 1_000_000)))

        // A private send: out exactly the amount (the fee apart), its change folded.
        val b = wallet(chain, bob)
        b.sync()
        a.send(b.address, "uanml", 700_000, counterparty = "@bob")
        a.sync(); b.sync()
        rows = a.activity()
        val send = rows.single { it.kind == K.SEND }
        assertEquals(listOf(ActivityCoin("uanml", -700_000)), send.coins)
        assertEquals("@bob", send.counterparty)
        assertTrue(rows.none { it.kind == K.RECEIVED || it.kind == K.FROM_EARTH || it.kind == K.INFERRED })
        // Newest first.
        assertEquals(K.SEND, rows.first().kind)

        // Bob: a v1 note from someone else.
        val got = b.activity().single()
        assertEquals(K.RECEIVED, got.kind)
        assertEquals(listOf(ActivityCoin("uanml", 700_000)), got.coins)
        assertEquals(Status.CONFIRMED, got.status)
        assertNotNull(got.height)

        // A note the chain minted that the wallet did not expect: from Earth.
        val o = network.erth.wallet.privacy.tx.NoteOut.mintToSelf(b.keys, "uerth")
        chain.shield("uerth", 5_000, o.pc, o.ciphertext)
        b.sync()
        assertEquals(K.FROM_EARTH, b.activity().first { it.coins.single().amount == 5_000L }.kind)
        // A shield of the wallet's own: its public row shows it, never a second one.
        val own = b.shieldOutput("uerth", 7_000)
        chain.shield("uerth", 7_000, own.pc, own.ciphertext)
        b.sync()
        assertTrue(b.activity().none { r -> r.coins.any { it.amount == 7_000L } })
    }

    @Test
    fun pendingUntilSyncSeesItThenConfirmed() {
        val chain = FakeChain()
        val a = registered(chain)
        val b = wallet(chain, bob)
        // The node takes it; the wait never sees its block.
        chain.unconfirmedNext = 1
        runCatching { a.send(b.address, "uanml", 100_000) }
        val pending = a.activity().single { it.kind == K.SEND }
        assertEquals(Status.PENDING, pending.status)
        assertNull(pending.height)
        a.sync()
        val done = a.activity().single { it.kind == K.SEND }
        assertEquals(Status.CONFIRMED, done.status)
        assertEquals(chain.height - 1, done.height)
    }

    @Test
    fun failedAndRefusedTxs() {
        val chain = FakeChain()
        val a = registered(chain)
        val b = wallet(chain, bob)
        // Failed in its block, as the broadcast's own wait saw.
        chain.failInBlockNext = 1
        runCatching { a.send(b.address, "uanml", 100_000) }
        val failed = a.activity().single { it.kind == K.SEND }
        assertEquals(Status.FAILED, failed.status)
        assertTrue(failed.failure!!.startsWith("tx failed"))
        // Refused by CheckTx: in no mempool, so nothing happened and no row.
        chain.rejectNext = 1
        runCatching { a.send(b.address, "uanml", 200_000) }
        assertEquals(1, a.activity().count { it.kind == K.SEND })
    }

    @Test
    fun restoredWalletInfersSpendsAndRebuildsReceived() {
        val chain = FakeChain()
        val a = registered(chain)
        val b = wallet(chain, bob)
        b.sync()
        a.send(b.address, "uanml", 300_000)
        a.sync()

        val r = wallet(chain, alice)
        r.sync()
        val rows = r.activity()
        // No sent record survives a restore: none has a hash or a fee.
        assertTrue(rows.all { it.hash == null && it.fee == null })
        // The registration, by its own record note's height, net of its notes.
        val reg = rows.single { it.kind == K.REGISTER }
        assertTrue(reg.coins.any { it.denom == "uanml" && it.amount == 1_000_000L })
        // The send: a private transaction, its spent amount net of its change; nothing more said.
        val send = rows.single { it.kind == K.INFERRED }
        assertTrue(send.coins.contains(ActivityCoin("uanml", -300_000)))
        assertEquals("", send.counterparty)
        // The gas grant, rebuilt from sync as a note the chain minted.
        assertEquals(listOf(ActivityCoin("uerth", 100_000)), rows.single { it.kind == K.FROM_EARTH }.coins)
        assertTrue(rows.none { it.kind == K.RECEIVED })
    }

    @Test
    fun activityIsSealedStateAndGoesWithTheStore() {
        val chain = FakeChain()
        val a = registered(chain)
        val json = a.store.state.toJson()
        val back = network.erth.wallet.privacy.sync.PrivacyState.fromJson(json)
        assertEquals(a.store.state.activity.sent, back.activity.sent)
        assertEquals(a.store.state.activity.expected, back.activity.expected)
        assertEquals(PrivateActivity.rows(a.store.state), PrivateActivity.rows(back))
        // A reset on the same chain keeps what the wallet sent; the resync folds the notes in again.
        a.store.reset(chain.chainId)
        a.sync()
        assertEquals(1, a.activity().count { it.kind == K.REGISTER && it.hash != null })
        // A relaunch drops it with the rest of the old chain's bookkeeping.
        a.store.switchGenesis("fedcba9876543210")
        assertTrue(a.store.state.activity.sent.isEmpty())
    }

    @Test
    fun clockEstimatesTimesFromHeights() {
        val clock = listOf(100L to 1_000L, 200L to 1_600L)
        assertEquals(1_300L, PrivateActivity.timeAt(clock, 150))
        assertEquals(1_000L - 60L, PrivateActivity.timeAt(clock, 90))
        assertEquals(1_600L + 60L, PrivateActivity.timeAt(clock, 210))
        assertEquals(1_000L - 10 * PrivateActivity.DEFAULT_BLOCK_SECONDS, PrivateActivity.timeAt(listOf(100L to 1_000L), 90))
        assertNull(PrivateActivity.timeAt(emptyList(), 5))
        val log = ActivityLog()
        for (h in 1L..100L) log.tick(h * 10, h * 60)
        assertEquals(ActivityLog.MAX_CLOCK, log.clock.size)
        assertEquals(10L, log.clock.first().first)
        assertEquals(1_000L, log.clock.last().first)
    }

    @Test
    fun coinsNetSpendsAgainstChangeAndFee() {
        val (outs, ins) = PrivateActivity.coins(
            poolSpent = listOf(ActivityCoin("uerth", 1_000), ActivityCoin("uanml", 500)),
            poolBack = listOf(ActivityCoin("uerth", 890), ActivityCoin("uanml", 0)),
            stakeSpent = emptyList(),
            stakeBack = listOf(ActivityCoin("derth/v", 70)),
            fee = 10,
        )
        assertEquals(listOf(ActivityCoin("uanml", 500), ActivityCoin("uerth", 100)), outs)
        assertEquals(listOf(ActivityCoin("derth/v", 70)), ins)
    }
}
