package network.erth.wallet.ui.pages.anml

import network.erth.wallet.R
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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.erth.wallet.chain.Dex
import network.erth.wallet.chain.Staking
import network.erth.wallet.ui.host.HostActivity
import network.erth.wallet.wallet.constants.Tokens
import network.erth.wallet.wallet.services.ErthPriceService
import network.erth.wallet.wallet.services.SecureWalletManager
import java.math.BigInteger
import java.text.DecimalFormat

class ANMLClaimFragment : Fragment() {

    companion object {
        private const val TAG = "ANMLClaimFragment"
        private const val AD_FREE_STAKE_ERTH = 250_000.0

        @JvmStatic
        fun newInstance(): ANMLClaimFragment = ANMLClaimFragment()
    }

    interface ANMLClaimListener {
        fun onClaimRequested()
    }

    private var listener: ANMLClaimListener? = null
    private var anmlPriceText: TextView? = null
    private var isHighStaker = false
    private var adFreeIndicatorContainer: LinearLayout? = null
    private var adFreeStatusText: TextView? = null

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

        adFreeIndicatorContainer?.setOnClickListener { showAdFreeExplanation() }

        checkStakingStatus()
        fetchAnmlPriceAndUpdateDisplay()

        val btnClaim = view.findViewById<Button>(R.id.btn_claim)
        btnClaim?.let { button ->
            try {
                button.backgroundTintList = null
                button.setTextColor(resources.getColor(R.color.anml_button_text, null))
            } catch (ignored: Exception) {}

            button.setOnClickListener {
                if (isHighStaker) {
                    Log.d(TAG, "High staker confirmed - skipping ad")
                    listener?.onClaimRequested()
                } else {
                    val activity = activity
                    if (activity is HostActivity) {
                        activity.showInterstitialAdThen { listener?.onClaimRequested() }
                    } else {
                        listener?.onClaimRequested()
                    }
                }
            }
        }
    }

    /** ANML price (USD) derived from the ANML/ERTH pool spot rate and the ERTH price. */
    private fun fetchAnmlPriceAndUpdateDisplay() {
        lifecycleScope.launch {
            val usd = try {
                withContext(Dispatchers.IO) {
                    val pool = Dex.poolForToken(Tokens.ANML.denom) ?: return@withContext null
                    val tokenReserve = pool.tokenReserve.toDoubleOrNull() ?: return@withContext null
                    if (tokenReserve <= 0) return@withContext null
                    val erthReserve = pool.erthReserve.toDoubleOrNull() ?: return@withContext null
                    val erthPrice = ErthPriceService.fetchErthPrice() ?: return@withContext null
                    // reserves share 6 decimals, so erth/token is ERTH per ANML
                    (erthReserve / tokenReserve) * erthPrice
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to derive ANML price", e)
                null
            }
            if (usd != null) updateAnmlPriceDisplay(usd) else anmlPriceText?.text = "Price unavailable"
        }
    }

    private fun updateAnmlPriceDisplay(price: Double) {
        val priceFormat = if (price < 0.01) DecimalFormat("$#,##0.######") else DecimalFormat("$#,##0.####")
        anmlPriceText?.text = try {
            priceFormat.format(price)
        } catch (e: Exception) {
            "Price unavailable"
        }
    }

    /** Determine ad-free eligibility from the user's native ERTH delegations (>= 250K staked). */
    private fun checkStakingStatus() {
        lifecycleScope.launch {
            try {
                val userAddress = SecureWalletManager.getWalletAddress(requireContext())
                if (userAddress.isNullOrEmpty()) return@launch

                val stakedErth = withContext(Dispatchers.IO) {
                    val base = Staking.delegations(userAddress).fold(BigInteger.ZERO) { acc, d ->
                        acc + (d.amount.toBigIntegerOrNull() ?: BigInteger.ZERO)
                    }
                    base.toDouble() / 1_000_000.0
                }
                isHighStaker = stakedErth >= AD_FREE_STAKE_ERTH
            } catch (e: Exception) {
                Log.e(TAG, "Error checking staking status", e)
                isHighStaker = false
            }
            updateAdFreeIndicator()
        }
    }

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

    private fun showAdFreeExplanation() {
        if (context == null) return
        if (isHighStaker) {
            AlertDialog.Builder(requireContext())
                .setTitle("✨ Ad-Free Experience")
                .setMessage("Congratulations! You have staked 250,000+ ERTH tokens and qualify for an ad-free experience.\n\n" +
                        "Benefits:\n• Skip all advertisements\n• Faster transaction flow\n• Premium user experience\n\n" +
                        "Thank you for being a valued staker! 🚀")
                .setPositiveButton("Got it!", null)
                .show()
        } else {
            AlertDialog.Builder(requireContext())
                .setTitle("🚀 Unlock Ad-Free Experience")
                .setMessage("Want to skip ads and get a premium experience?\n\n" +
                        "Stake 250,000+ ERTH tokens to unlock:\n• Skip all advertisements\n• Faster transaction flow\n• Premium user experience\n\n" +
                        "Visit the Staking page to stake your ERTH tokens and join our premium users! ✨")
                .setPositiveButton("Got it!", null)
                .setNegativeButton("Go to Staking") { _, _ ->
                    (activity as? HostActivity)?.showFragment("staking")
                }
                .show()
        }
    }
}
