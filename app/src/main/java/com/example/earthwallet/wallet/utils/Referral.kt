package network.erth.wallet.wallet.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import network.erth.wallet.Constants

/**
 * Where a referrer address comes from, and where it is kept.
 *
 * Two arrival paths, because "downloading from a referral link" and "opening a
 * referral link" are different events:
 *
 *  - **Install referrer.** Play Store carries the `referrer` query parameter of
 *    the store link through the install and hands it to the app on first run.
 *    This is the one that covers someone who did not have the app yet.
 *  - **Deep link.** An `https://erth.network/ref/<address>` or
 *    `earth://ref/<address>` intent, for someone who already has it installed.
 *
 * Stored in plain SharedPreferences rather than the encrypted store: it is a
 * public address, it is needed before any wallet exists, and it must survive
 * the gap between install and registration. It is deliberately NOT cleared
 * after use — a failed registration that gets retried should keep its referrer.
 *
 * First write wins. Someone who arrives through one link and later opens
 * another keeps the first, so a referrer cannot be overwritten by whoever
 * happens to send the most recent link.
 */
object Referral {

    private const val PREFS = "referral"
    private const val KEY_ADDRESS = "referrer_address"
    private const val KEY_CHECKED = "install_referrer_checked"
    private const val TAG = "Referral"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The captured referrer, or null. */
    fun get(context: Context): String? =
        prefs(context).getString(KEY_ADDRESS, null)?.takeIf { it.isNotBlank() }

    /**
     * Records [address] if it looks like an Earth address and none is stored.
     * Returns true if it was taken.
     */
    fun record(context: Context, address: String?): Boolean {
        val candidate = address?.trim().orEmpty()
        if (!looksLikeAddress(candidate)) return false
        val p = prefs(context)
        if (!p.getString(KEY_ADDRESS, null).isNullOrBlank()) return false
        p.edit().putString(KEY_ADDRESS, candidate).apply()
        return true
    }

    /**
     * Pulls the referrer out of a deep link. Accepts the address as the last
     * path segment (`/ref/earth1…`) or as a `?ref=` / `?referrer=` parameter.
     */
    fun fromIntent(context: Context, intent: Intent?): Boolean {
        val data = intent?.data ?: return false
        val fromQuery = data.getQueryParameter("ref") ?: data.getQueryParameter("referrer")
        val candidate = fromQuery ?: data.lastPathSegment
        return record(context, candidate)
    }

    /**
     * Asks Play for the install referrer, once ever.
     *
     * The connection is one-shot and asynchronous, and it fails on any build
     * that did not come from Play — sideloaded, debug, or an emulator without
     * Play services. That is expected and silent: there is simply no referrer
     * to find, and the manual field on the confirm screen still works.
     */
    fun captureInstallReferrer(context: Context) {
        val p = prefs(context)
        if (p.getBoolean(KEY_CHECKED, false)) return
        if (!get(context).isNullOrBlank()) return

        val app = context.applicationContext
        val client = InstallReferrerClient.newBuilder(app).build()
        client.startConnection(object : InstallReferrerStateListener {
            override fun onInstallReferrerSetupFinished(responseCode: Int) {
                runCatching {
                    if (responseCode == InstallReferrerClient.InstallReferrerResponse.OK) {
                        // The value is the raw query string of the store link,
                        // so it is parsed as one rather than assumed to be a
                        // bare address: "referrer=earth1…&utm_source=…".
                        val raw = client.installReferrer.installReferrer.orEmpty()
                        val parsed = Uri.parse("?$raw").getQueryParameter("referrer")
                            ?: raw.takeIf { looksLikeAddress(it.trim()) }
                        if (record(app, parsed)) {
                            Log.i(TAG, "install referrer captured")
                        }
                    }
                    // Marked checked on any terminal response, including
                    // NOT_SUPPORTED — retrying a store that cannot answer just
                    // reopens a connection on every launch forever.
                    p.edit().putBoolean(KEY_CHECKED, true).apply()
                }
                runCatching { client.endConnection() }
            }

            override fun onInstallReferrerServiceDisconnected() {
                // Transient. Left unmarked so the next launch tries again.
            }
        })
    }

    /** Shape only. Whether it is a registered human is the chain's call. */
    private fun looksLikeAddress(value: String): Boolean =
        value.startsWith(Constants.EARTH_PREFIX + "1") && value.length >= 39
}
