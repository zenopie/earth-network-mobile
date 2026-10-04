package network.erth.wallet.passport

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The passport circuits' SRS as a local file, one per size tier. The privacy
 * circuits' SRS ships in the APK (PrivacyProver.SRS_ASSET), so a private
 * proof never fetches anything; the passport circuits need 2^18 + 1 points
 * (16 MiB, nearly every passport), 2^19 + 1 (32 MiB) or 2^20 + 1 (64 MiB),
 * too large to bundle. Each is the first points of Aztec's transcript
 * (crs.aztec.network/g1.dat, a byte range), checked against the hash pinned in
 * passport_variants.json and kept in no-backup storage. The 2^18 tier is
 * fetched at launch; a passport whose circuit needs a larger one fetches it
 * before proving. A larger file serves a smaller circuit.
 */
object PassportSrs {
    private const val SOURCE = "https://crs.aztec.network/g1.dat"

    private fun file(context: Context, log2: Int) = File(context.noBackupFilesDir, "srs/bn254_g1_${(1L shl log2) + 1}.dat")

    /** The smallest complete local file covering a 2^log2 circuit, or null. */
    fun path(context: Context, log2: Int): String? {
        val tiers = PassportVariants.get(context).srs
        return tiers.keys.filter { it >= log2 }.sorted().firstNotNullOfOrNull { t ->
            file(context, t).takeIf { it.length() == tiers.getValue(t).points * 64 }?.absolutePath
        }
    }

    /** Fetches the 2^18 tier if missing. Blocking; call once at launch on a background thread. */
    fun prefetch(context: Context) {
        runCatching { ensure(context, 18) }
    }

    /**
     * The local file for a 2^log2 circuit, fetching its tier if no file covers
     * it. Blocking. Failures leave nothing behind and throw.
     */
    @Synchronized
    fun ensure(context: Context, log2: Int): String {
        path(context, log2)?.let { return it }
        val tiers = PassportVariants.get(context).srs
        val tier = tiers.keys.filter { it >= log2 }.minOrNull() ?: error("no SRS tier covers 2^$log2")
        val want = tiers.getValue(tier)
        val dest = file(context, tier)
        dest.parentFile?.mkdirs()
        val part = File(dest.parentFile, dest.name + ".part")
        try {
            val c = URL(SOURCE).openConnection() as HttpURLConnection
            // Never followed to another origin.
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000
            c.readTimeout = 60_000
            c.setRequestProperty("Range", "bytes=0-${want.points * 64 - 1}")
            try {
                check(c.responseCode == 206 || c.responseCode == 200) { "SRS download: HTTP ${c.responseCode}" }
                val md = MessageDigest.getInstance("SHA-256")
                var left = want.points * 64
                c.inputStream.use { input ->
                    java.io.FileOutputStream(part).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (left > 0) {
                            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                            if (n < 0) break
                            md.update(buf, 0, n); out.write(buf, 0, n); left -= n
                        }
                        out.fd.sync()
                    }
                }
                val hex = md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                check(left == 0L && hex == want.sha256) { "SRS download did not match its pinned hash" }
                check(part.renameTo(dest)) { "could not install the SRS" }
            } finally {
                c.disconnect()
            }
        } finally {
            part.delete()
        }
        return dest.absolutePath
    }
}
