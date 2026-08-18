package network.erth.wallet.ui.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.gms.ads.rewarded.ServerSideVerificationOptions

/**
 * The "ads for gas" rewarded ad, held for the whole process rather than by one
 * Activity.
 *
 * It used to live on HostActivity, and PassportScannerFragment reached it with
 * `activity as? HostActivity`. That cast is always null in the flow that needs
 * it: registration runs in NFCScannerActivity, which hosts the passport fragment
 * and has no ad support. The button therefore called back with false every time
 * and did nothing, with no log and no error — the failure was indistinguishable
 * from a dead button.
 *
 * Keeping the ad here means whichever Activity is on top can show it, and the
 * one preloaded at launch is still there by the time the scanner needs it.
 */
object RewardedAds {
    private const val TAG = "RewardedAds"

    /** Rewarded unit for "ads for gas". Must match the app's REWARDED_AD_UNIT_ID. */
    private const val AD_UNIT_ID = "ca-app-pub-8662126294069074/9040854138"

    private var ad: RewardedAd? = null
    private var loading = false

    fun isReady(): Boolean = ad != null

    /** Loads an ad if one is not already loaded or in flight. Safe to call often. */
    fun preload(context: Context) {
        if (ad != null || loading) return
        loading = true
        RewardedAd.load(
            context,
            AD_UNIT_ID,
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(loaded: RewardedAd) {
                    Log.d(TAG, "rewarded ad loaded and ready")
                    ad = loaded
                    loading = false
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    Log.e(
                        TAG,
                        "rewarded ad failed to load: code=${error.code} " +
                            "domain=${error.domain} message=${error.message}",
                    )
                    ad = null
                    loading = false
                }
            },
        )
    }

    /**
     * Shows the ad, attaching [walletAddress] as SSV custom_data — that is what
     * tells the backend which address to send the dust to.
     *
     * [callback] reports whether the reward was earned. It is not whether the
     * dust arrived: the grant happens out of band, when Google calls the
     * backend, so the caller has to watch the chain for the funds.
     */
    fun show(activity: Activity, walletAddress: String, callback: (Boolean) -> Unit) {
        val current = ad
        if (current == null) {
            Log.w(TAG, "show(): no ad loaded, reloading and reporting failure")
            preload(activity)
            callback(false)
            return
        }

        current.setServerSideVerificationOptions(
            ServerSideVerificationOptions.Builder().setCustomData(walletAddress).build(),
        )

        current.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                ad = null
                preload(activity)
            }

            override fun onAdFailedToShowFullScreenContent(error: com.google.android.gms.ads.AdError) {
                Log.e(TAG, "rewarded ad failed to show: ${error.message}")
                ad = null
                preload(activity)
                callback(false)
            }
        }

        current.show(activity) { reward ->
            Log.d(TAG, "reward earned: ${reward.amount} ${reward.type} for $walletAddress")
            callback(true)
        }
    }
}
