package network.erth.wallet.passport

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The passport circuit's SRS as a local file. The privacy
 * circuits' SRS ships in the APK (PrivacyProver.SRS_ASSET), so a private
 * proof never fetches anything; the passport's (2^18 + 1 points, 16 MiB) is
 * too large to bundle, so it is fetched once at launch, independent of
 * anything the wallet does: the first 262,145 points of Aztec's transcript
 * (crs.aztec.network/g1.dat, a byte range), checked against a pinned hash,
 * kept in no-backup storage. Until it is there a passport proof downloads its
 * own SRS as before (registration is public anyway).
 */
object PassportSrs {
    const val POINTS = (1L shl 18) + 1
    const val SHA256 = "8f5cd75519c2e995fa47aa7ecd7b213b9ae13824bc4b0f15025a63acd8c139eb"
    private const val SOURCE = "https://crs.aztec.network/g1.dat"
    private const val NAME = "srs/bn254_g1_262145.dat"

    private fun file(context: Context) = File(context.noBackupFilesDir, NAME)

    /** The local file when complete (its hash was checked when written); null otherwise. */
    fun path(context: Context): String? = file(context).takeIf { it.length() == POINTS * 64 }?.absolutePath

    /** Fetches the file if missing. Blocking; call once at launch on a background thread. Failures leave nothing behind. */
    @Synchronized
    fun prefetch(context: Context) {
        if (path(context) != null) return
        val dest = file(context)
        dest.parentFile?.mkdirs()
        val part = File(dest.parentFile, dest.name + ".part")
        try {
            val c = URL(SOURCE).openConnection() as HttpURLConnection
            // Never followed to another origin.
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000
            c.readTimeout = 60_000
            c.setRequestProperty("Range", "bytes=0-${POINTS * 64 - 1}")
            try {
                if (c.responseCode != 206 && c.responseCode != 200) return
                val md = MessageDigest.getInstance("SHA-256")
                var left = POINTS * 64
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
                if (left == 0L && hex == SHA256) part.renameTo(dest)
            } finally {
                c.disconnect()
            }
        } catch (e: Exception) {
            // Retried on the next launch.
        } finally {
            part.delete()
        }
    }
}
