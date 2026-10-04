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
 * On-device proof of the real register circuits that guard registration (not
 * the toy e2e circuit): lean_poa_p256_sha256 and lean_poa_rsa2048_sha256, the
 * two most passports use, each over its shared fixture's witness
 * (circuits/fixtures/<variant>/expected.json, in the test APK's assets). Proves +
 * verifies on the phone via noir_android (bb v5.0.0 final), then saves the proof
 * + VK so the chain verifier (also bb v5.0.0 final) can be run against a genuine
 * device-generated proof, confirming the full device->chain loop for the large
 * circuit.
 *
 *   adb install -r -t app-debug.apk app-debug-androidTest.apk
 *   adb shell am instrument -w -e class network.erth.wallet.LeanPoaDeviceTest \
 *     network.erth.wallet.test/androidx.test.runner.AndroidJUnitRunner
 *   adb pull /sdcard/Android/data/network.erth.wallet/files/lean_poa_p256_sha256_device_proof.hex
 */
@RunWith(AndroidJUnit4::class)
class LeanPoaDeviceTest {

    @Test
    fun proveLeanPoaOnDevice() = prove("lean_poa_p256_sha256")

    @Test
    fun proveRsaOnDevice() = prove("lean_poa_rsa2048_sha256")

    private fun prove(variant: String) {
        val instr = InstrumentationRegistry.getInstrumentation()
        // circuit ships in the app's assets; inputs ship in the test APK's assets.
        val circuitJson = instr.targetContext.assets.open("circuits/$variant.json")
            .bufferedReader().use { it.readText() }
        val fixture = instr.context.assets.open("$variant/expected.json")
            .bufferedReader().use { it.readText() }
        val inputs = toInputMap(JSONObject(fixture).getJSONObject("witness"))

        // Both are 2^18-tier circuits.
        val circuit = Circuit.fromJsonManifest(circuitJson, 1 shl 18, false, 0L)
        circuit.setupSrs()

        val vk = circuit.getVerificationKey()
        val proof = circuit.prove(inputs, vk, "ultra_honk")
        assertTrue("empty proof", proof.isNotEmpty())
        assertTrue("$variant proof failed to verify on device", circuit.verify(proof, vk, "ultra_honk"))

        val dir = instr.targetContext.getExternalFilesDir(null)!!
        java.io.File(dir, "${variant}_device_proof.hex").writeText(proof)
        java.io.File(dir, "${variant}_device_vk.hex").writeText(vk)
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
