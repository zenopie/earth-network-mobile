package network.erth.wallet.ui.pages.gasstation

import android.content.Context
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.viewpager2.widget.ViewPager2
import network.erth.wallet.R
import network.erth.wallet.Constants
import network.erth.wallet.wallet.services.SecureWalletManager
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import org.json.JSONObject

/**
 * GasStationFragment with tabs
 *
 * Implements gas station functionality with two tabs:
 * - Swap for Gas: Convert any token to SCRT for gas
 * - Wrap/Unwrap: Convert between SCRT and sSCRT
 */
class GasStationFragment : Fragment(), SwapForGasFragment.SwapForGasListener, WrapUnwrapFragment.WrapUnwrapListener {

    companion object {
        private const val TAG = "GasStationFragment"
    }

    // UI Components
    private var tabLayout: TabLayout? = null
    private var viewPager: ViewPager2? = null

    // State
    private var currentWalletAddress = ""
    private var isRegistered = false
    private var canClaimFaucet = false
    private var hasGasGrant = false

    // Adapter
    private var gasStationAdapter: GasStationTabsAdapter? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_gas_station, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeViews(view)
        setupTabs()
        loadCurrentWalletAddress()
        checkRegistrationStatus()
    }

    private fun initializeViews(view: View) {
        tabLayout = view.findViewById(R.id.tab_layout)
        viewPager = view.findViewById(R.id.view_pager)
    }

    private fun setupTabs() {
        val tabLayout = this.tabLayout ?: return
        val viewPager = this.viewPager ?: return

        // Create adapter
        gasStationAdapter = GasStationTabsAdapter(this)
        viewPager.adapter = gasStationAdapter

        // Connect TabLayout with ViewPager2
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Swap for Gas"
                1 -> "Wrap/Unwrap"
                else -> "Tab $position"
            }
        }.attach()
    }


    private fun loadCurrentWalletAddress() {
        try {
            currentWalletAddress = SecureWalletManager.getWalletAddress(requireContext()) ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load wallet address", e)
            currentWalletAddress = ""
        }
    }

    private fun checkRegistrationStatus() {
        if (TextUtils.isEmpty(currentWalletAddress)) {
            return
        }

        // Check faucet eligibility using API endpoint
        Thread {
            try {
                val url = "${Constants.BACKEND_BASE_URL}/faucet-eligibility/$currentWalletAddress"
                val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                val responseCode = connection.responseCode
                val response = if (responseCode == 200) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                }

                activity?.runOnUiThread {
                    handleFaucetEligibilityResult(responseCode, response)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Faucet eligibility check failed", e)
                // Error will be handled by individual fragments
            }
        }.start()
    }

    private fun handleFaucetEligibilityResult(responseCode: Int, response: String) {
        try {
            if (responseCode == 200) {
                val root = JSONObject(response)
                val registered = root.optBoolean("registered", false)
                val eligible = root.optBoolean("eligible", false)
                val cooldownPassed = root.optBoolean("cooldown_passed", false)
                val nextAvailable = root.optString("next_available_datetime", null)

                updateFaucetStatus(registered, eligible, nextAvailable)
            } else {
                Log.e(TAG, "Faucet eligibility API error: $responseCode - $response")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse faucet eligibility result", e)
        }
    }

    private fun updateFaucetStatus(registered: Boolean, canClaim: Boolean, nextAvailable: String?) {
        isRegistered = registered
        canClaimFaucet = canClaim

        // Refresh child fragments to update their faucet status
        refreshChildFragments()
    }

    private fun claimFaucet() {
        if (TextUtils.isEmpty(currentWalletAddress)) {
            Toast.makeText(requireContext(), "Connect wallet first", Toast.LENGTH_SHORT).show()
            return
        }

        // Notify child fragments that faucet claim is starting
        refreshChildFragments()

        Thread {
            try {
                val url = "${Constants.BACKEND_BASE_URL}/faucet-gas"
                val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.doOutput = true

                val requestBody = JSONObject()
                requestBody.put("address", currentWalletAddress)

                connection.outputStream.use { os ->
                    os.write(requestBody.toString().toByteArray())
                }

                val responseCode = connection.responseCode
                val response = if (responseCode == 200) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                }

                activity?.runOnUiThread {
                    handleFaucetClaimResult(responseCode, response)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Faucet claim failed", e)
                activity?.runOnUiThread {
                    Toast.makeText(requireContext(), "Faucet claim failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun handleFaucetClaimResult(responseCode: Int, response: String) {
        try {
            if (responseCode == 200) {
                val root = JSONObject(response)
                val success = root.optBoolean("success", false)

                if (success) {
                    canClaimFaucet = false
                    hasGasGrant = true
                    Toast.makeText(requireContext(), "Gas allowance granted!", Toast.LENGTH_SHORT).show()

                    // Refresh faucet eligibility
                    checkRegistrationStatus()

                    // Refresh data in child fragments
                    refreshChildFragments()
                } else {
                    val error = root.optString("error", "Unknown error")
                    Toast.makeText(requireContext(), "Faucet error: $error", Toast.LENGTH_SHORT).show()
                }
            } else {
                Log.e(TAG, "Faucet API error: $responseCode - $response")
                Toast.makeText(requireContext(), "Faucet request failed", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse faucet claim result", e)
            Toast.makeText(requireContext(), "Faucet claim failed", Toast.LENGTH_SHORT).show()
        }

        // Refresh child fragments to update faucet status
        refreshChildFragments()
    }


    private fun refreshChildFragments() {
        // Refresh data in all child fragments
        val fragmentManager = childFragmentManager
        val fragments = fragmentManager.fragments
        for (fragment in fragments) {
            when (fragment) {
                is SwapForGasFragment -> fragment.refreshData()
                is WrapUnwrapFragment -> fragment.refreshData()
            }
        }
    }

    private fun getFaucetStatusText(): String {
        return when {
            isRegistered && canClaimFaucet -> {
                "✓ Registered ✓ Available to use"
            }
            isRegistered && !canClaimFaucet -> {
                "✓ Registered ✗ Already used this week"
            }
            else -> {
                "✗ Not registered"
            }
        }
    }

    // SwapForGasFragment.SwapForGasListener implementation
    override fun getCurrentWalletAddress(): String {
        return currentWalletAddress
    }

    override fun getHasGasGrant(): Boolean {
        return hasGasGrant
    }

    override fun onSwapComplete() {
        // Refresh faucet status after a successful swap
        checkRegistrationStatus()
    }

    override fun onFaucetClicked() {
        claimFaucet()
    }

    override fun getFaucetStatus(): Triple<Boolean, Boolean, String?> {
        return Triple(isRegistered, canClaimFaucet, getFaucetStatusText())
    }

    // WrapUnwrapFragment.WrapUnwrapListener implementation
    override fun onWrapUnwrapComplete() {
        // Refresh faucet status after a successful wrap/unwrap
        checkRegistrationStatus()
    }
}