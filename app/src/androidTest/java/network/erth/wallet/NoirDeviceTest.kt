package network.erth.wallet

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.noirandroid.lib.Circuit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device validation that the embedded Noir/Barretenberg (UltraHonk) prover
 * works on real hardware: loads a compiled Noir circuit from assets, downloads
 * the SRS, generates a proof, and verifies it — all on the device.
 *
 * This proves the on-device proving engine (the core of the embed) runs on this
 * phone. Run with:
 *   ./gradlew connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=network.erth.wallet.NoirDeviceTest
 */
@RunWith(AndroidJUnit4::class)
class NoirDeviceTest {

    @Test
    fun proveAndVerifyOnDevice() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val circuitJson = ctx.assets.open("circuits/e2e.json")
            .bufferedReader().use { it.readText() }

        // size = SRS points to provision (must cover the circuit's domain).
        val circuit = Circuit.fromJsonManifest(circuitJson, 2048)
        circuit.setupSrs() // downloads SRS from Aztec on first run

        // Circuit: main(x, y: pub) -> pub { assert(x != y); x + y }
        val proof = circuit.prove(mapOf("x" to "0x03", "y" to "0x05"))
        assertTrue("prover returned an empty proof", proof.isNotEmpty())

        assertTrue("UltraHonk proof failed to verify on device", circuit.verify(proof))

        // Persist proof + VK so the chain verifier can be validated against a real
        // device-generated proof (adb pull from getExternalFilesDir).
        val vk = circuit.getVerificationKey()
        val dir = ctx.getExternalFilesDir(null)!!
        java.io.File(dir, "device_proof.hex").writeText(proof)
        java.io.File(dir, "device_vk.hex").writeText(vk)
    }
}
