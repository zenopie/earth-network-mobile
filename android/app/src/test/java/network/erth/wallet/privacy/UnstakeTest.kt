package network.erth.wallet.privacy

import network.erth.wallet.privacy.chain.PrivacyQueries
import network.erth.wallet.privacy.zk.Fr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Undelegation: it pays out by itself to a note named in the msg, its due
 * time comes from epoch timing alone, and the wallet's record of it survives
 * a reset and is dropped when the tx failed.
 */
class UnstakeTest : WalletTest() {
    private fun staked(chain: FakeChain, due: (Long) -> Long? = { null }): PrivacyWallet {
        val a = wallet(chain, r = reads(chain, due = due))
        repeat(4) { funded(chain, a, 2_000_000) }
        a.sync()
        a.delegate(vB, 1_000_000); a.sync()
        return a
    }

    private val vB = "earthvaloper1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"

    @Test
    fun undelegateNamesItsPayoutAndMintsNoStakeNote() {
        val chain = FakeChain()
        val a = staked(chain, due = { e -> 1_000L * e })
        val stakeRows = chain.stakeRows.size
        a.undelegate(vB, 400_000)
        val m = chain.lastMsg as network.erth.earth.proto.shieldedstaking.MsgUndelegate
        assertEquals(32, m.pc.size())
        assertEquals(network.erth.wallet.privacy.note.NoteCipher.BLIND_CIPHERTEXT_BYTES, m.ciphertext.size())
        // Chain dff3a9b: lane A's output (the change) with its 201-byte ciphertext; no credit lane.
        assertEquals(network.erth.wallet.privacy.note.NoteCipher.STAKE_CIPHERTEXT_BYTES, m.stake.ciphertext.size())
        assertTrue(Fr.fromBytes(m.stake.creditCommitment.toByteArray()).isZero && m.stake.creditCiphertext.isEmpty)
        // The change is the proof's own output; nothing minted into the stake tree.
        assertEquals(stakeRows + 1, chain.stakeRows.size)
        val u = a.pendingUnbonds.single()
        assertEquals(Fr.fromBytes(m.pc.toByteArray()), u.pc)
        assertEquals(listOf(400_000L * 10 / 9, chain.epoch, 1L, 1_000L * chain.epoch), listOf(u.value, u.epoch, u.payoutId, u.dueBy))
        assertTrue(u.confirmed)
        dumpWitnesses(chain, "fix7Undelegate")
    }

    /** A payout past one note's worth comes as several notes, one ciphertext at several positions: every one is found. */
    @Test
    fun aSplitPayoutIsFoundWhole() {
        val chain = FakeChain()
        val a = staked(chain)
        // The whole note: the proof's output is a zero note.
        val all = a.stakeBalances().getValue(PrivacyWallet.derthDenom(vB))
        a.undelegate(vB, all)
        val pc = Fr.fromBytes((chain.lastMsg as network.erth.earth.proto.shieldedstaking.MsgUndelegate).pc.toByteArray())
        a.sync()
        val before = bal(a)
        chain.payUnbonds { p -> listOf(p.value / 3, p.value / 3, p.value - 2 * (p.value / 3)) }
        val txs = chain.txs.size
        a.sync()
        assertEquals(txs, chain.txs.size)
        assertEquals(all * 10 / 9, bal(a) - before)
        assertEquals(3, a.notes.count { it.note.pc(a.keys.ownerPk) == pc })
        assertTrue(a.stakeNotes.none { it.spendable })
        assertTrue(a.pendingUnbonds.isEmpty())
        // A restored wallet finds the payout by trial decryption alone.
        val restored = wallet(chain)
        restored.sync()
        assertEquals(a.balances(), restored.balances())
        dumpWitnesses(chain, "fix7SplitPayout")
    }

    @Test
    fun aFailedOrRefusedUndelegationIsForgotten() {
        val chain = FakeChain()
        val a = staked(chain)
        chain.rejectNext = 1
        assertThrows(java.io.IOException::class.java) { a.undelegate(vB, 100_000) }
        assertTrue(a.pendingUnbonds.isEmpty())
        chain.failInBlockNext = 1
        assertThrows(java.io.IOException::class.java) { a.undelegate(vB, 100_000) }
        assertEquals(1, a.pendingUnbonds.size)
        a.sync()
        assertTrue(a.pendingUnbonds.isEmpty())
        assertTrue(chain.unbondPayouts.isEmpty())
    }

    @Test
    fun pendingUnbondsSurviveAReset() {
        val chain = FakeChain()
        val a = staked(chain)
        a.undelegate(vB, 100_000)
        val kept = a.pendingUnbonds
        a.store.reset(chain.chainId)
        assertEquals(kept, a.pendingUnbonds)
    }

    @Test
    fun payoutTimeComesFromEpochTimingAlone() {
        val day = 86_400L
        val unbonding = 21 * day
        val m = PrivacyWallet.PAYOUT_MARGIN_S
        // Epoch 10 began at t and ends at t + day.
        val t = 1_800_000_000L
        // Booked in the epoch in progress: it ends with it, then unbonds.
        assertEquals(t + day + unbonding + m, PrivacyWallet.unbondDueBy(10, 10, t, t + day, day, unbonding))
        // A late epoch end (the end time passed): at least one epoch from its start.
        assertEquals(t + day + unbonding + m, PrivacyWallet.unbondDueBy(10, 10, t, t, day, unbonding))
        // An ended epoch: 9 ended at t, 7 at least two epochs earlier.
        assertEquals(t + unbonding + m, PrivacyWallet.unbondDueBy(9, 10, t, t + day, day, unbonding))
        assertEquals(t - 2 * day + unbonding + m, PrivacyWallet.unbondDueBy(7, 10, t, t + day, day, unbonding))
        // Chain-supplied numbers never wrap.
        assertEquals(null, PrivacyWallet.unbondDueBy(0, Long.MAX_VALUE, 0, 0, Long.MAX_VALUE, 0))
        assertEquals(null, PrivacyWallet.unbondDueBy(1, 1, Long.MAX_VALUE, Long.MAX_VALUE, 1, Long.MAX_VALUE))
        assertEquals(null, PrivacyWallet.unbondDueBy(-1, 3, 0, 1, 1, 1))
        assertEquals(null, PrivacyWallet.unbondDueBy(1, 3, 0, 1, 0, 1))
    }

    @Test
    fun durationsAndPayoutTimesNeverWrap() {
        assertNull(PrivacyQueries.durationSeconds("1e30s"))
        assertNull(PrivacyQueries.durationSeconds("-5s"))
        assertNull(PrivacyQueries.durationSeconds("99999999999999999999999s"))
        assertEquals(1_814_400L, PrivacyQueries.durationSeconds("1814400s"))
        assertEquals(0L, PrivacyQueries.durationSeconds("0.5s"))
        assertNull(PrivacyWallet.unbondDueBy(0, Long.MAX_VALUE, 0, 0, Long.MAX_VALUE, 0))
        assertNull(PrivacyWallet.unbondDueBy(1, 3, Long.MAX_VALUE, 0, 1, Long.MAX_VALUE))
        assertNull(PrivacyWallet.unbondDueBy(-1, 3, 0, 0, 1, 1))
    }
}
