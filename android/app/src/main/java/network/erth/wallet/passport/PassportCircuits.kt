package network.erth.wallet.passport

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * A passport variant's compiled circuit. The 2^18-tier variants (nearly every
 * passport) are in the APK; the rest are fetched once, on demand, from the
 * manifest's download base (the backend's /circuits) and kept in no-backup
 * storage. Bundled or fetched, the JSON must hash to the variant's pinned
 * sha256, so the server is trusted for availability only. The fetch names
 * the variant, which the registration makes public anyway.
 */
object PassportCircuits {
    /** Large enough for the biggest circuit, small enough to refuse a runaway response. */
    private const val MAX_BYTES = 64L shl 20

    class CircuitUnavailableException(message: String) : Exception(message)

    fun load(context: Context, variant: PassportVariants.Variant): String {
        val bytes = if (variant.bundled) {
            context.assets.open("circuits/${variant.id}.json").use { it.readBytes() }
        } else {
            cached(context, variant) ?: fetch(context, variant)
        }
        check(sha256(bytes) == variant.sha256) { "circuit ${variant.id} does not match its pinned hash" }
        return bytes.decodeToString()
    }

    private fun file(context: Context, variant: PassportVariants.Variant) =
        File(context.noBackupFilesDir, "circuits/${variant.id}.json")

    private fun cached(context: Context, variant: PassportVariants.Variant): ByteArray? =
        file(context, variant).takeIf { it.isFile }?.readBytes()?.takeIf { sha256(it) == variant.sha256 }

    @Synchronized
    private fun fetch(context: Context, variant: PassportVariants.Variant): ByteArray {
        val url = PassportVariants.get(context).downloadBase + variant.id + ".json.gz"
        // The platform permits cleartext (for a user's own LAN node); this does not need it.
        if (!url.startsWith("https://")) throw CircuitUnavailableException("circuit download: not https")
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        val body = try {
            if (c.responseCode != 200) throw CircuitUnavailableException("circuit download: HTTP ${c.responseCode}")
            GZIPInputStream(c.inputStream).use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_BYTES) throw CircuitUnavailableException("circuit download too large")
                }
                out.toByteArray()
            }
        } finally {
            c.disconnect()
        }
        if (sha256(body) != variant.sha256) throw CircuitUnavailableException("circuit ${variant.id} did not match its pinned hash")
        val dest = file(context, variant)
        dest.parentFile?.mkdirs()
        val part = File(dest.parentFile, dest.name + ".part")
        part.writeBytes(body)
        part.renameTo(dest)
        return body
    }

    fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
