package network.erth.wallet

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import network.erth.wallet.privacy.prove.MembershipWitness
import network.erth.wallet.privacy.prove.PrivacyProver
import network.erth.wallet.privacy.prove.ActionWitness
import network.erth.wallet.privacy.prove.StakeWitness
import network.erth.wallet.privacy.zk.Fr
import network.erth.wallet.privacy.zk.MemNodeStore
import network.erth.wallet.privacy.zk.MerkleTree
import network.erth.wallet.privacy.zk.Privacy
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves a membership fixture (tools/privacyfixtures, rebuilt here as
 * FixtureWitnessTest does), an action and a stake witness on the phone and
 * logs the prove times, the number the plan's "under 5 s mid-range" target is
 * about. The proofs are written beside LeanPoaDeviceTest's so the chain's
 * verifier can be run against device output:
 *
 *   adb shell am instrument -w -e class network.erth.wallet.PrivacyProverDeviceTest \
 *     network.erth.wallet.test/androidx.test.runner.AndroidJUnitRunner
 *   adb logcat -s PrivacyProverDeviceTest
 *   adb pull /sdcard/Android/data/network.erth.wallet/files/privacy_{membership,action,stake}.proof
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

    /** A spend of 1 ERTH paying 500 ANML out (a mixed-asset action) against a small tree. */
    @Test
    fun action() {
        val t = MerkleTree(MemNodeStore())
        val nk = det("nk", 0)
        val rho = det("rho", 0)
        val rcm = det("rcm", 0)
        for (p in 0L until 12) {
            t.append(if (p == 4L) Privacy.cm(Privacy.ASSET_ERTH, 1_000_000, Privacy.pc(Privacy.ownerPk(nk), rho, rcm)) else Privacy.cm(Privacy.ASSET_ERTH, p + 1, det("otherpc", p)))
        }
        val w = ActionWitness(
            nk = nk, sAsset = Privacy.ASSET_ERTH, sValue = 1_000_000, sRho = rho, sRcm = rcm, sPos = 4, sPath = t.path(4),
            oAsset = Privacy.assetId("uanml"), oValue = 500, oPc = det("opc", 0), rcv = det("rcv", 0),
            anchor = t.root(), sighash = det("signal", 1),
        )
        repeat(3) { run ->
            val proof = PrivacyProver.proveAction(ctx, w)
            assertEquals(PrivacyProver.PROOF_BYTES, proof.size)
            Log.i(TAG, "action prove #$run: ${PrivacyProver.lastActionMs} ms")
            if (run == 0) java.io.File(ctx.getExternalFilesDir(null), "privacy_action.proof").writeBytes(proof)
        }
    }

    /** An undelegation: two stake notes spent, change back, v_out leaving. */
    @Test
    fun stake() {
        val t = MerkleTree(MemNodeStore())
        val nk = det("nk", 1)
        val opk = Privacy.ownerPk(nk)
        val asset = Privacy.assetId("derth/earthvaloper1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq")
        val amounts = listOf(700_000L, 500_000L)
        val rhos = (0L until 2).map { det("srho", it) }
        val rcms = (0L until 2).map { det("srcm", it) }
        for (i in 0 until 2) t.append(Privacy.stakeCm(asset, amounts[i], Privacy.stakePc(opk, rhos[i], rcms[i])))
        val w = StakeWitness(
            nk = nk, inAmount = amounts, inRho = rhos, inRcm = rcms, inPos = listOf(0, 1), inPath = listOf(t.path(0), t.path(1)),
            outAmount = listOf(200_000, 0), outRho = listOf(det("orho", 0), det("orho", 1)), outRcm = listOf(det("orcm", 0), det("orcm", 1)),
            mintRho = det("mrho", 0), mintRcm = det("mrcm", 0), tagSalt = det("salt", 0),
            anchor = t.root(), asset = asset, vIn = 0, vOut = 1_000_000, sighash = det("signal", 2),
        )
        repeat(3) { run ->
            val proof = PrivacyProver.proveStake(ctx, w)
            assertEquals(PrivacyProver.PROOF_BYTES, proof.size)
            Log.i(TAG, "stake prove #$run: ${PrivacyProver.lastStakeMs} ms")
            if (run == 0) java.io.File(ctx.getExternalFilesDir(null), "privacy_stake.proof").writeBytes(proof)
        }
    }

    companion object { private const val TAG = "PrivacyProverDeviceTest" }
}
