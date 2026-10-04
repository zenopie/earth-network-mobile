package network.erth.wallet.privacy

import network.erth.wallet.privacy.prove.PrivacyProver
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The prover's fixed inputs. It splits a proof by its public-input count, so
 * a count that lags the bundled circuit breaks every proof of that kind on
 * device: each count is pinned to the circuit ABI. The bundled SRS is the
 * transcript's prefix and covers every privacy circuit.
 */
class ProverTest {
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

    @Test
    fun bundledSrsIsTheTranscriptPrefix() {
        val f = File("src/main/assets/${PrivacyProver.SRS_ASSET}")
        val bytes = f.readBytes()
        assertEquals(PrivacyProver.SRS_POINTS * 64, bytes.size)
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals(PrivacyProver.SRS_SHA256, sha)
        // The first point is the generator (1, 2).
        assertEquals(1, bytes[31].toInt()); assertEquals(2, bytes[63].toInt())
        assertTrue(bytes.copyOf(31).all { it.toInt() == 0 })
        // Every privacy circuit's SRS fits: the size hint's subgroup plus one point.
        assertTrue(PrivacyProver.SRS_SIZE + 1 <= PrivacyProver.SRS_POINTS)
    }
}
