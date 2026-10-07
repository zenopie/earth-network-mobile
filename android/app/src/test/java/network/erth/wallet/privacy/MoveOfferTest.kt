package network.erth.wallet.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The move reminder follows what can still move: its deadline, and nothing once it has all lapsed or is on its way. */
class MoveOfferTest {
    private fun offer(handleLive: Boolean, voteLive: Boolean, handleExp: Long = 1_000, voteExp: Long = 2_000) =
        PrivacySession.MoveOffer(
            fromIndex = PrivacySession.SELF, fromName = "", handle = "alice", handleLive = handleLive,
            voteLive = voteLive, voteExpiresAt = voteExp, inFlight = emptyList(), feeErth = 0, handleExpiresAt = handleExp,
        )

    @Test
    fun theDeadlineIsTheEarliestLeaseStillToMove() {
        assertEquals(1_000L, offer(handleLive = true, voteLive = true).deadline)
        assertTrue(offer(handleLive = true, voteLive = true).movable)
        // The handle lapsed: the vote's lease is what is left.
        assertEquals(2_000L, offer(handleLive = false, voteLive = true).deadline)
        // Everything lapsed: still shown (the handle's name), but nothing to remind of.
        val gone = offer(handleLive = false, voteLive = false)
        assertTrue(gone.anything)
        assertFalse(gone.movable)
        assertEquals(0L, gone.deadline)
    }
}
