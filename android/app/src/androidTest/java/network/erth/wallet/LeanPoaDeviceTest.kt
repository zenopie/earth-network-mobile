package network.erth.wallet

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.noirandroid.lib.Circuit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device proof of the real lean_poa passport circuit (~130k gates), the large
 * circuit that actually guards registration — not the toy e2e circuit. Proves +
 * verifies on the phone via noir_android (bb v5.0.0 final), then saves the proof
 * + VK so the chain verifier (also bb v5.0.0 final) can be run against a genuine
 * device-generated proof, confirming the full device->chain loop for the large
 * circuit.
 *
 *   adb install -r -t app-debug.apk app-debug-androidTest.apk
 *   adb shell am instrument -w -e class network.erth.wallet.LeanPoaDeviceTest \
 *     network.erth.wallet.test/androidx.test.runner.AndroidJUnitRunner
 *   adb pull /sdcard/Android/data/network.erth.wallet/files/lean_device_proof.hex
 */
@RunWith(AndroidJUnit4::class)
class LeanPoaDeviceTest {

    @Test
    fun proveLeanPoaOnDevice() {
        val instr = InstrumentationRegistry.getInstrumentation()
        // circuit ships in the app's assets; inputs ship in the test APK's assets.
        val circuitJson = instr.targetContext.assets.open("circuits/lean_poa.json")
            .bufferedReader().use { it.readText() }
        val inputsJson = instr.context.assets.open("lean_inputs.json")
            .bufferedReader().use { it.readText() }
        val inputs = toInputMap(JSONObject(inputsJson))

        // ~130k gates -> domain 2^17; provision 2^18 SRS points to be safe.
        val circuit = Circuit.fromJsonManifest(circuitJson, 1 shl 18, false, 0L)
        circuit.setupSrs()

        val vk = circuit.getVerificationKey()
        val proof = circuit.prove(inputs, vk, "ultra_honk")
        assertTrue("empty proof", proof.isNotEmpty())
        assertTrue("lean_poa proof failed to verify on device", circuit.verify(proof, vk, "ultra_honk"))

        val dir = instr.targetContext.getExternalFilesDir(null)!!
        java.io.File(dir, "lean_device_proof.hex").writeText(proof)
        java.io.File(dir, "lean_device_vk.hex").writeText(vk)
    }

    /** Parses the input JSON into the Map noir_android expects (hex strings, Booleans, Lists). */
    private fun toInputMap(obj: JSONObject): Map<String, Any> {
        val map = LinkedHashMap<String, Any>()
        for (key in obj.keys()) {
            when (val v = obj.get(key)) {
                is JSONArray -> {
                    val list = ArrayList<Any>(v.length())
                    for (i in 0 until v.length()) {
                        when (val e = v.get(i)) {
                            is Boolean -> list.add(e)
                            else -> list.add(e.toString())
                        }
                    }
                    map[key] = list
                }
                else -> map[key] = v.toString()
            }
        }
        return map
    }
}
