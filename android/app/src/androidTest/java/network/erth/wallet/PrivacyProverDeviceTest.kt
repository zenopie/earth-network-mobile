package network.erth.wallet

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import network.erth.wallet.privacy.prove.MembershipWitness
import network.erth.wallet.privacy.prove.PrivacyProver
import network.erth.wallet.privacy.prove.TransferInput
import network.erth.wallet.privacy.prove.TransferOutput
import network.erth.wallet.privacy.prove.TransferWitness
import network.erth.wallet.privacy.zk.Fr
import network.erth.wallet.privacy.zk.MemNodeStore
import network.erth.wallet.privacy.zk.MerkleTree
import network.erth.wallet.privacy.zk.Privacy
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the chain's own membership and transfer fixtures (tools/
 * privacyfixtures, rebuilt here as FixtureWitnessTest does) on the phone and
 * logs the prove times, the number the plan's "under 5 s mid-range" target is
 * about. The proofs are written beside LeanPoaDeviceTest's so the chain's
 * verifier can be run against device output:
 *
 *   adb shell am instrument -w -e class network.erth.wallet.PrivacyProverDeviceTest \
 *     network.erth.wallet.test/androidx.test.runner.AndroidJUnitRunner
 *   adb logcat -s PrivacyProverDeviceTest
 *   adb pull /sdcard/Android/data/network.erth.wallet/files/privacy_{membership,transfer}.proof
 */
@RunWith(AndroidJUnit4::class)
class PrivacyProverDeviceTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun det(label: String, i: Long): Fr = Privacy.h(Privacy.assetId("fixture/$label"), Privacy.u64(i))

    @Test
    fun membership() {
        val t = MerkleTree(MemNodeStore())
        val idSecret = det("id_secret", 0)
        val dscKey = det("dsc", 0)
        val country = Privacy.countryField("DE")
        val activatedAt = 1_790_000_000L
        for (i in 0L until 21) {
            t.append(
                if (i == 13L) Privacy.identityLeaf(Privacy.idc(idSecret), dscKey, country, activatedAt)
                else Privacy.identityLeaf(Privacy.idc(det("other", i)), det("dsc", i % 3), Privacy.countryField(listOf("DE", "FR", "")[(i % 3).toInt()]), 1_780_000_000 + i),
            )
        }
        t.update(3, Fr.ZERO)
        val w = MembershipWitness(idSecret, dscKey, country, activatedAt, 13, t.path(13), t.root(),
            Privacy.assetId("claim:20360"), det("signal", 0), det("dsc", 99), Privacy.countryField("FR"), activatedAt + 86_400)
        repeat(3) { run ->
            val proof = PrivacyProver.proveMembership(ctx, w)
            assertEquals(PrivacyProver.PROOF_BYTES, proof.size)
            Log.i(TAG, "membership prove #$run: ${PrivacyProver.lastMembershipMs} ms")
            if (run == 0) java.io.File(ctx.getExternalFilesDir(null), "privacy_membership.proof").writeBytes(proof)
        }
    }

    @Test
    fun transfer() {
        val t = MerkleTree(MemNodeStore())
        val nk = det("nk", 0)
        val opk = Privacy.ownerPk(nk)
        val a = Privacy.assetId("uanml")
        val inVal = longArrayOf(700_000, 300_000, 50_000)
        val outVal = longArrayOf(600_000, 350_000, 40_000)
        val assets = listOf(a, a, Privacy.ASSET_ERTH)
        val pos = longArrayOf(4, 7, 9)
        val rho = (0L until 3).map { det("rho", it) }
        val rcm = (0L until 3).map { det("rcm", it) }
        var next = 0
        for (p in 0L until 12) {
            var cm = Privacy.cm(Privacy.ASSET_ERTH, p + 1, det("otherpc", p))
            if (next < 3 && p == pos[next]) { cm = Privacy.cm(assets[next], inVal[next], Privacy.pc(opk, rho[next], rcm[next])); next++ }
            t.append(cm)
        }
        val w = TransferWitness(
            a, nk, (0 until 3).map { TransferInput(inVal[it], rho[it], rcm[it], pos[it], t.path(pos[it])) },
            (0 until 3).map { TransferOutput(outVal[it], Privacy.pc(Privacy.ownerPk(det("recipient", it.toLong())), det("orho", it.toLong()), det("orcm", it.toLong()))) },
            t.root(), 10_000, 50_000, det("signal", 1),
        )
        repeat(3) { run ->
            val proof = PrivacyProver.proveTransfer(ctx, w)
            assertEquals(PrivacyProver.PROOF_BYTES, proof.size)
            Log.i(TAG, "transfer prove #$run: ${PrivacyProver.lastTransferMs} ms")
            if (run == 0) java.io.File(ctx.getExternalFilesDir(null), "privacy_transfer.proof").writeBytes(proof)
        }
    }

    companion object { private const val TAG = "PrivacyProverDeviceTest" }
}
