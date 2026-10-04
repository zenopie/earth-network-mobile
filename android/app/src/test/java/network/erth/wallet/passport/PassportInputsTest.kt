package network.erth.wallet.passport

import network.erth.wallet.privacy.Vectors
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The passport proof's witness is the one iOS builds (privacy/passport_witness.json,
 * a synthetic P-256 passport iOS's PassportInputs turned into a lean_poa witness that
 * proves, with the genesis VK, through progate --witness): Android builds it byte for
 * byte from the same DG1 and EF.SOD, so both apps hand bb the same inputs.
 */
class PassportInputsTest {
    private val fixture = JSONObject(Vectors.resource("privacy/passport_witness.json").decodeToString())

    private fun json(v: Any): Any = when (v) {
        is List<*> -> JSONArray(v)
        else -> v
    }

    @Test
    fun buildsTheWitnessIosBuilds() {
        val want = fixture.getJSONObject("witness")
        val inputs = PassportInputs.buildInputs(
            Vectors.unhex(fixture.getString("dg1_hex")), Vectors.unhex(fixture.getString("sod_hex")),
            fixture.getInt("current_date"), want.getString("address"),
        )
        assertEquals(fixture.getString("algorithm"), inputs.algorithm)
        val keys = want.keys().asSequence().toSet()
        assertEquals(keys, inputs.map.keys)
        for (k in keys) assertEquals(k, want.get(k).toString(), json(inputs.map.getValue(k)).toString())
    }

    @Test
    fun aDg1TheSodDoesNotSignIsRefused() {
        val dg1 = Vectors.unhex(fixture.getString("dg1_hex")).also { it[20] = (it[20].toInt() xor 1).toByte() }
        assertThrows(IllegalArgumentException::class.java) {
            PassportInputs.buildInputs(dg1, Vectors.unhex(fixture.getString("sod_hex")), 260819, "0x1")
        }
    }
}
