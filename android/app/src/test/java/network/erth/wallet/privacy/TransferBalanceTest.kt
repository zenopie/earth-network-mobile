package network.erth.wallet.privacy

import network.erth.wallet.privacy.prove.TransferInput
import network.erth.wallet.privacy.prove.TransferOutput
import network.erth.wallet.privacy.prove.TransferWitness
import network.erth.wallet.privacy.zk.Fr
import network.erth.wallet.privacy.zk.Merkle
import network.erth.wallet.privacy.zk.Privacy
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The witness mirrors the transfer circuit's balance rule: for ERTH one
 * balance over all three slots, for any other asset A and the fee slot each
 * on their own.
 */
class TransferBalanceTest {
    private fun ins(vararg v: Long) = v.mapIndexed { i, x -> TransferInput(x, Fr.of(10L + i), Fr.of(20L + i), i.toLong(), List(Merkle.DEPTH) { Fr.ZERO }) }
    private fun outs(vararg v: Long) = v.mapIndexed { i, x -> TransferOutput(x, Fr.of(30L + i)) }
    private fun w(asset: Fr, i: List<TransferInput>, o: List<TransferOutput>, fee: Long, vPub: Long) =
        TransferWitness(asset, Fr.of(7), i, o, Fr.ZERO, fee, vPub, Fr.ZERO)

    @Test
    fun erthOneNotePaysSpendAndFee() {
        w(Privacy.ASSET_ERTH, ins(100, 0, 0), outs(60, 0, 37), 3, 0)
        w(Privacy.ASSET_ERTH, ins(0, 0, 100), outs(60, 37, 0), 3, 0)
        w(Privacy.ASSET_ERTH, ins(100, 0, 0), outs(0, 0, 10), 5, 85)
    }

    @Test
    fun erthCombinedInflationRejected() {
        assertThrows(IllegalArgumentException::class.java) { w(Privacy.ASSET_ERTH, ins(100, 0, 0), outs(60, 0, 38), 3, 0) }
        assertThrows(IllegalArgumentException::class.java) { w(Privacy.ASSET_ERTH, ins(100, 0, 0), outs(0, 0, 10), 5, 86) }
    }

    @Test
    fun otherAssetKeepsTwoBalances() {
        val a = Privacy.assetId("uanml")
        w(a, ins(70, 30, 10), outs(60, 40, 7), 3, 0)
        // The fee out of A, or value moved between A and the fee slot: totals balance, slots do not.
        assertThrows(IllegalArgumentException::class.java) { w(a, ins(100, 0, 0), outs(97, 0, 0), 3, 0) }
        assertThrows(IllegalArgumentException::class.java) { w(a, ins(70, 30, 10), outs(70, 40, 0), 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { w(a, ins(70, 30, 10), outs(60, 30, 17), 3, 0) }
    }
}
