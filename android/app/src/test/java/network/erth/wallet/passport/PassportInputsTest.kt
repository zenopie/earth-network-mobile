package network.erth.wallet.passport

import network.erth.wallet.privacy.Vectors
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Every register-circuit variant's shared fixture (circuits/fixtures/<variant>:
 * a synthetic passport built by circuits/tools/variants.py, whose witness the
 * circuit's own nargo tests and the chain's verifier fixtures prove): Android
 * selects the same variant and builds the same witness byte for byte, as iOS
 * must. And every scheme no circuit covers is refused with its name.
 */
class PassportInputsTest {
    private val variants = PassportVariants(File("src/main/assets/${PassportVariants.ASSET}").readText())

    private fun json(v: Any): Any = when (v) {
        is List<*> -> JSONArray(v)
        else -> v
    }

    @Test
    fun everyVariantHasAFixture() {
        assertEquals(33, variants.variants.size)
    }

    @Test
    fun buildsEachVariantsWitness() {
        for (v in variants.variants) {
            val expected = JSONObject(Vectors.resource("${v.id}/expected.json").decodeToString())
            val want = expected.getJSONObject("witness")
            val inputs = PassportInputs.buildInputs(
                Vectors.resource("${v.id}/dg1.bin"), Vectors.resource("${v.id}/sod.bin"),
                expected.getInt("current_date"), expected.getString("address"), variants,
            )
            assertEquals(v.id, inputs.variant.id)
            assertEquals(v.id, expected.getString("scheme"), inputs.scheme)
            val keys = want.keys().asSequence().toSet()
            assertEquals(v.id, keys, inputs.map.keys)
            for (k in keys) assertEquals("${v.id} $k", want.get(k).toString(), json(inputs.map.getValue(k)).toString())
        }
    }

    @Test
    fun aDg1TheSodDoesNotSignIsRefused() {
        for (v in variants.variants) {
            val expected = JSONObject(Vectors.resource("${v.id}/expected.json").decodeToString())
            try {
                PassportInputs.buildInputs(
                    Vectors.resource("${v.id}/dg1_tampered.bin"), Vectors.resource("${v.id}/sod.bin"),
                    250101, "0x1", variants,
                )
                fail("${v.id}: a tampered DG1 was accepted")
            } catch (e: PassportInputs.PassportDataException) {
                assertEquals(v.id, expected.getString("dg1_tampered_error"), e.code)
            }
        }
    }

    @Test
    fun unsupportedSchemesAreNamed() {
        val dir = javaClass.classLoader!!.getResource("unsupported")
        assertNotNull(dir)
        val names = File(dir!!.toURI()).list()!!.sorted()
        assertTrue(names.size >= 10)
        for (name in names) {
            val want = JSONObject(Vectors.resource("unsupported/$name/expected.json").decodeToString()).getString("unsupported")
            try {
                PassportInputs.buildInputs(
                    Vectors.resource("unsupported/$name/dg1.bin"), Vectors.resource("unsupported/$name/sod.bin"),
                    250101, "0x1", variants,
                )
                fail("$name was accepted")
            } catch (e: PassportInputs.UnsupportedPassportException) {
                assertEquals(name, want, e.scheme)
                assertEquals("This passport's signature type isn't supported yet ($want)", e.message)
            }
        }
    }

    @Test
    fun aNonTd3Dg1IsUnsupported() {
        val v = variants.variants.first()
        try {
            PassportInputs.buildInputs(ByteArray(60), Vectors.resource("${v.id}/sod.bin"), 250101, "0x1", variants)
            fail()
        } catch (e: PassportInputs.UnsupportedPassportException) {
            assertEquals("60-byte DG1 (not a TD3 passport)", e.scheme)
        }
    }

    @Test
    fun bundledCircuitsMatchTheirPins() {
        for (v in variants.variants.filter { it.bundled }) {
            val f = File("src/main/assets/circuits/${v.id}.json")
            assertTrue("${v.id} is not bundled", f.isFile)
            assertEquals(v.id, v.sha256, PassportCircuits.sha256(f.readBytes()))
            assertTrue(v.log2CircuitSize <= 18)
        }
        for (v in variants.variants.filter { !it.bundled }) {
            assertTrue("${v.id} should not be bundled", !File("src/main/assets/circuits/${v.id}.json").exists())
        }
        for (t in listOf(18, 19, 20)) assertNotNull(variants.srs[t])
    }
}
