package network.erth.wallet.passport

import org.json.JSONObject

/**
 * The passport register circuits: one per DSC key type, signature scheme and
 * hash profile (circuits/variants.json, bundled as
 * assets/circuits/passport_variants.json; PASSPORT_COVERAGE.md says which
 * passports use which). iOS reads the same file.
 *
 * A variant at or below the 2^18 tier ships in the app; the others are fetched
 * on demand from [downloadBase] and must hash to [Variant.sha256]. The proving
 * setup (SRS) each tier needs is fetched the same way, pinned by [srs].
 */
class PassportVariants(json: String) {

    class Variant(
        val id: String,
        /** rsa2048 | rsa3072 | rsa4096 | p224 | p256 | p384 | p521 | bp224 | bp256 | bp384 | bp512 */
        val key: String,
        /** pkcs1 | pss | ecdsa */
        val scheme: String,
        /** (data groups, eContent, signature): sha1 | sha224 | sha256 | sha384 | sha512 */
        val hashes: List<String>,
        val eContentMax: Int,
        val signedAttrsMax: Int,
        val log2CircuitSize: Int,
        val bundled: Boolean,
        /** sha256 of the compiled circuit JSON as bundled or served (inflated). */
        val sha256: String,
    )

    class Srs(val points: Long, val sha256: String)

    val variants: List<Variant>
    val downloadBase: String
    val srs: Map<Int, Srs>

    init {
        val o = JSONObject(json)
        val list = o.getJSONArray("variants")
        variants = (0 until list.length()).map { i ->
            val v = list.getJSONObject(i)
            val h = v.getJSONArray("hashes")
            Variant(
                id = v.getString("id"),
                key = v.getString("key"),
                scheme = v.getString("scheme"),
                hashes = (0 until h.length()).map { h.getString(it) },
                eContentMax = v.getInt("e_content_max"),
                signedAttrsMax = v.getInt("signed_attrs_max"),
                log2CircuitSize = v.getInt("log2_circuit_size"),
                bundled = v.getBoolean("bundled"),
                sha256 = v.getString("sha256"),
            )
        }
        downloadBase = o.getString("download_base")
        val s = o.getJSONObject("srs")
        srs = s.keys().asSequence().associate { k ->
            val t = s.getJSONObject(k)
            k.toInt() to Srs(t.getLong("points"), t.getString("sha256"))
        }
    }

    fun byId(id: String): Variant? = variants.firstOrNull { it.id == id }

    companion object {
        const val ASSET = "circuits/passport_variants.json"

        @Volatile private var loaded: PassportVariants? = null

        fun get(context: android.content.Context): PassportVariants =
            loaded ?: synchronized(this) {
                loaded ?: PassportVariants(
                    context.assets.open(ASSET).bufferedReader().use { it.readText() },
                ).also { loaded = it }
            }
    }
}
