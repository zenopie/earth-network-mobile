package network.erth.wallet.privacy

import network.erth.wallet.privacy.prove.PrivacyProver
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The prover splits a proof by its public-input count, so a count that lags
 * the bundled circuit breaks every proof of that kind on device (membership
 * kept 7 after max_predecessor made it 8). Pin each count to the circuit ABI.
 */
class ProverPublicInputsTest {
    private fun width(t: JSONObject): Int = when (t.getString("kind")) {
        "field", "integer", "boolean" -> 1
        "array" -> t.getInt("length") * width(t.getJSONObject("type"))
        "struct" -> t.getJSONArray("fields").let { f -> (0 until f.length()).sumOf { width(f.getJSONObject(it).getJSONObject("type")) } }
        else -> error("unexpected ABI kind ${t.getString("kind")}")
    }

    @Test
    fun publicInputCountsMatchBundledCircuits() {
        for (k in PrivacyProver.Kind.entries) {
            val params = JSONObject(File("src/main/assets/circuits/${k.file}.json").readText())
                .getJSONObject("abi").getJSONArray("parameters")
            val public = (0 until params.length()).map { params.getJSONObject(it) }
                .filter { it.getString("visibility") == "public" }
                .sumOf { width(it.getJSONObject("type")) }
            assertEquals("${k.file} public inputs", public, k.publicInputs)
        }
    }
}
