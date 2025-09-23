package com.example.earthwallet.ui.pages.anml

import com.example.earthwallet.R
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.example.earthwallet.Constants
import com.example.earthwallet.ui.host.HostActivity
import com.example.earthwallet.bridge.services.SecretQueryService
import com.example.earthwallet.wallet.services.SecureWalletManager
import okhttp3.*
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.text.DecimalFormat

class ANMLClaimFragment : Fragment() {

    companion object {
        private const val TAG = "ANMLClaimFragment"

        @JvmStatic
        fun newInstance(): ANMLClaimFragment = ANMLClaimFragment()
    }

    // Interface for communication with parent activity
    interface ANMLClaimListener {
        fun onClaimRequested()
    }

    private var listener: ANMLClaimListener? = null
    private var anmlPriceText: TextView? = null
    private var httpClient: OkHttpClient? = null
    private var queryService: SecretQueryService? = null
    private var isHighStaker = false
    private var adFreeIndicatorContainer: LinearLayout? = null
    private var adFreeStatusText: TextView? = null
    private var currentStakedAmount = 0.0

    fun setANMLClaimListener(listener: ANMLClaimListener) {
        this.listener = listener
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_anml_claim, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        anmlPriceText = view.findViewById(R.id.anml_price_amount)
        adFreeIndicatorContainer = view.findViewById(R.id.ad_free_indicator_container)
        adFreeStatusText = view.findViewById(R.id.ad_free_status_text)

        // Set up click listener for ad-free indicator
        adFreeIndicatorContainer?.setOnClickListener { showAdFreeExplanation() }

        // Initialize query service
        queryService = SecretQueryService(requireContext())

        // Check staking status and fetch ANML price when fragment is created
        checkStakingStatus()
        fetchAnmlPriceAndUpdateDisplay()

        val btnClaim = view.findViewById<Button>(R.id.btn_claim)
        btnClaim?.let { button ->
            // Ensure any theme tinting is cleared so the drawable renders as-designed
            try {
                button.backgroundTintList = null
                button.setTextColor(resources.getColor(R.color.anml_button_text, null))
            } catch (ignored: Exception) {}

            button.setOnClickListener {
                if (isHighStaker) {
                    // High stakers (>=250K ERTH staked) skip the ad
                    listener?.onClaimRequested()
                } else {
                    // Show interstitial ad and start transaction confirmation simultaneously
                    val activity = activity
                    if (activity is HostActivity) {
                        // Start the transaction confirmation immediately (while ad is showing)
                        listener?.onClaimRequested()

                        // Show ad with a no-op callback since transaction is already started
                        activity.showInterstitialAdThen {
                            // Transaction confirmation is already showing, nothing to do here
                        }
                    } else {
                        // Fallback if not in HostActivity
                        listener?.onClaimRequested()
                    }
                }
            }
        }
    }

    private fun fetchAnmlPriceAndUpdateDisplay() {

        if (httpClient == null) {
            httpClient = OkHttpClient()
        }

        val url = "${Constants.BACKEND_BASE_URL}/anml-price"

        val request = Request.Builder()
            .url(url)
            .build()

        httpClient?.newCall(request)?.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Failed to fetch ANML price from: $url", e)
                // Update UI on main thread with error message
                activity?.runOnUiThread {
                    anmlPriceText?.text = "Price unavailable"
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (resp.isSuccessful && resp.body() != null) {
                        try {
                            val responseBody = resp.body()!!.string()
                            val json = JSONObject(responseBody)
                            val price = json.getDouble("price")

                            // Update UI on main thread
                            activity?.runOnUiThread {
                                updateAnmlPriceDisplay(price)
                        } catch (e: JSONException) {
                            Log.e(TAG, "Failed to parse ANML price response", e)
                            // Update UI on main thread with error message
                            activity?.runOnUiThread {
                                anmlPriceText?.text = "Price unavailable"
                            }
                        }
                    } else {
                        // Update UI on main thread with error message
                        activity?.runOnUiThread {
                            anmlPriceText?.text = "Price unavailable"
                        }
                    }
                }
            }
        })
    }

    private fun updateAnmlPriceDisplay(price: Double) {
        if (anmlPriceText == null) {
            return
        }

        try {
            // Format ANML price with appropriate decimal places
            val priceFormat = if (price < 0.01) {
                // For very small values, show more decimal places
                DecimalFormat("$#,##0.######")
            } else {
                DecimalFormat("$#,##0.####")
            }

            val priceDisplay = priceFormat.format(price)
            anmlPriceText?.text = priceDisplay

        } catch (e: Exception) {
            Log.e(TAG, "Error formatting ANML price", e)
            anmlPriceText?.text = "Price unavailable"
        }
    }

    /**
     * Check if user has >= 250K ERTH staked to determine if they should see ads
     */
    private fun checkStakingStatus() {
        Thread {
            try {
                val userAddress = SecureWalletManager.getWalletAddress(requireContext())
                if (userAddress.isNullOrEmpty()) {
                    return@Thread
                }

                // Create query message: { get_user_info: { address: "secret1..." } }
                val queryMsg = JSONObject()
                val getUserInfo = JSONObject()
                getUserInfo.put("address", userAddress)
                queryMsg.put("get_user_info", getUserInfo)


                val result = queryService?.queryContract(
                    Constants.STAKING_CONTRACT,
                    Constants.STAKING_HASH,
                    queryMsg
                )


                // Parse staked amount
                if (result?.has("user_info") == true && !result.isNull("user_info")) {
                    val userInfo = result.getJSONObject("user_info")
                    if (userInfo.has("staked_amount")) {
                        val stakedAmountMicro = userInfo.getLong("staked_amount")
                        val stakedAmountMacro = stakedAmountMicro / 1_000_000.0 // Convert to macro units


                        // Check if user has >= 250K ERTH staked
                        if (stakedAmountMacro >= 250_000.0) {
                            isHighStaker = true
                        } else {
                            isHighStaker = false
                        }

                        // Update UI on main thread
                        activity?.runOnUiThread { updateAdFreeIndicator() }
                    } else {
                        isHighStaker = false
                    }
                } else {
                    isHighStaker = false

                    // Update UI on main thread
                    activity?.runOnUiThread { updateAdFreeIndicator() }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error checking staking status", e)
                isHighStaker = false // Default to showing ads on error

                // Update UI on main thread
                activity?.runOnUiThread { updateAdFreeIndicator() }
            }
        }.start()
    }

    /**
     * Update the ad-free indicator text based on staking status
     */
    private fun updateAdFreeIndicator() {
        adFreeStatusText?.let { statusText ->
            if (isHighStaker) {
                statusText.text = "Ad-Free Experience"
                statusText.setTextColor(resources.getColor(android.R.color.holo_green_dark, null))
            } else {
                statusText.text = "Ads Active"
                statusText.setTextColor(resources.getColor(android.R.color.holo_orange_dark, null))
            }
        }
    }

    /**
     * Show explanation dialog for ad-free functionality
     */
    private fun showAdFreeExplanation() {
        if (context == null) return

        if (isHighStaker) {
            // Dialog for users who already have ad-free
            AlertDialog.Builder(requireContext())
                .setTitle("✨ Ad-Free Experience")
                .setMessage("Congratulations! You have staked 250,000+ ERTH tokens and qualify for an ad-free experience.\n\n" +
                           "Benefits:\n" +
                           "• Skip all advertisements\n" +
                           "• Faster transaction flow\n" +
                           "• Premium user experience\n\n" +
                           "Thank you for being a valued staker! 🚀")
                .setPositiveButton("Got it!", null)
                .show()
        } else {
            // Dialog for users who don't have ad-free yet
            AlertDialog.Builder(requireContext())
                .setTitle("🚀 Unlock Ad-Free Experience")
                .setMessage("Want to skip ads and get a premium experience?\n\n" +
                           "Stake 250,000+ ERTH tokens to unlock:\n" +
                           "• Skip all advertisements\n" +
                           "• Faster transaction flow\n" +
                           "• Premium user experience\n\n" +
                           "Visit the Staking page to stake your ERTH tokens and join our premium users! ✨")
                .setPositiveButton("Got it!", null)
                .setNegativeButton("Go to Staking") { _, _ ->
                    // Navigate to staking page
                    val activity = activity
                    if (activity is HostActivity) {
                        activity.showFragment("staking")
                    }
                }
                .show()
        }
    }
}