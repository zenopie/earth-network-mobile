package network.erth.wallet.privacy

import network.erth.wallet.ui.govern.positionSplit
import org.junit.Assert.assertEquals
import org.junit.Test

class PositionSplitTest {
    private fun p(derth: Long, splits: Map<Long, Long>) = PrivacyChainReads.Position(1, "v", derth, ByteArray(33), 0, splits)

    @Test
    fun weighsEachPositionByItsStakeAndSumsTo100() {
        assertEquals(emptyMap<Long, Long>(), positionSplit(emptyList()))
        assertEquals(mapOf(2L to 100L), positionSplit(listOf(p(5, mapOf(2L to 100L)))))
        // 3:1 stake, 100% to option 1 vs 100% to option 2.
        assertEquals(mapOf(1L to 75L, 2L to 25L), positionSplit(listOf(p(3_000, mapOf(1L to 100L)), p(1_000, mapOf(2L to 100L)))))
        // Thirds: largest remainder keeps the total at 100.
        val thirds = positionSplit(listOf(p(1, mapOf(1L to 100L)), p(1, mapOf(2L to 100L)), p(1, mapOf(3L to 100L))))
        assertEquals(100L, thirds.values.sum())
    }
}
