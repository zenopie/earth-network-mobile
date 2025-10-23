package network.erth.wallet.ui.pages.anml

import network.erth.wallet.R
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import network.erth.wallet.Constants
import network.erth.wallet.ui.components.LoadingOverlay
import network.erth.wallet.wallet.services.SecretKClient
import network.erth.wallet.wallet.services.SecureWalletManager
import network.erth.wallet.wallet.services.TransactionExecutor
import kotlinx.coroutines.launch
import org.json.JSONObject

class ANMLClaimMainFragment : Fragment(), ANMLRegisterFragment.ANMLRegisterListener, ANMLClaimFragment.ANMLClaimListener {

    companion object {
        private const val TAG = "ANMLClaimFragment"
        private const val ONE_DAY_MILLIS = 24L * 60L * 60L * 1000L

        @JvmStatic
        fun newInstance(): ANMLClaimMainFragment = ANMLClaimMainFragment()
    }

    private var errorText: TextView? = null
    private var loadingOverlay: LoadingOverlay? = null
    private var fragmentContainer: View? = null

    private var suppressNextQueryDialog = false
    private var currentRegistrationReward: String? = null
    private var transactionSuccessReceiver: BroadcastReceiver? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_anml_claim_main, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        try {
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SecretWallet", e)
        }

        errorText = view.findViewById(R.id.anml_error_text)
        loadingOverlay = view.findViewById(R.id.loading_overlay)
        fragmentContainer = view.findViewById(R.id.anml_root)

        // Initialize the loading overlay with this fragment for Glide
        loadingOverlay?.initializeWithFragment(this)

        setupBroadcastReceiver()
        registerBroadcastReceiver()

        // Start status check
        checkStatus()
    }

    private fun setupBroadcastReceiver() {
        transactionSuccessReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {

                // Start multiple refresh attempts to ensure UI updates during animation
                checkStatus() // First immediate refresh

                // Stagger additional refreshes to catch the UI during animation
                Handler(Looper.getMainLooper()).postDelayed({
                    checkStatus()
                }, 100) // 100ms delay

                Handler(Looper.getMainLooper()).postDelayed({
                    checkStatus()
                }, 500) // 500ms delay
            }
        }
    }

    private fun registerBroadcastReceiver() {
        if (activity != null && transactionSuccessReceiver != null) {
            val filter = IntentFilter("network.erth.wallet.TRANSACTION_SUCCESS")
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    requireActivity().applicationContext.registerReceiver(transactionSuccessReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    requireActivity().applicationContext.registerReceiver(transactionSuccessReceiver, filter)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register broadcast receiver", e)
            }
        }
    }

    private fun showLoading(loading: Boolean) {
        // Use the reusable LoadingOverlay component
        loadingOverlay?.let { overlay ->
            if (loading) {
                overlay.show()
                errorText?.visibility = View.GONE
                hideStatusFragments()
            } else {
                overlay.hide()
            }
        }
    }

    // Debug helpers to surface responses in-app
    private fun showAlert(title: String?, json: String?) {
        try {
            showJsonDialog(title ?: "Response", json)
        } catch (ignored: Exception) {}
    }

    private fun showJsonDialog(title: String?, json: String?) {
        try {
            AlertDialog.Builder(requireContext())
                .setTitle(title ?: "Response")
                .setMessage(json ?: "(empty)")
                .setPositiveButton("OK", null)
                .show()
        } catch (ignored: Exception) {}
    }

    // Show dialog and run a callback after it's dismissed (used to defer follow-up actions)
    private fun showJsonDialogThen(title: String?, json: String?, then: Runnable?) {
        try {
            val dialog = AlertDialog.Builder(requireContext())
                .setTitle(title ?: "Response")
                .setMessage(json ?: "(empty)")
                .setPositiveButton("OK", null)
                .create()
            dialog.setOnDismissListener {
                try {
                    then?.run()
                } catch (ignored: Exception) {}
            }
            dialog.show()
        } catch (ignored: Exception) {}
    }

    private fun hideStatusFragments() {
        val fm = childFragmentManager
        val current = fm.findFragmentById(R.id.anml_root)
        if (current != null) {
            fm.beginTransaction().remove(current).commit()
        }
    }

    private fun showRegisterFragment() {
        hideStatusFragments()
        val fragment = if (currentRegistrationReward != null) {
            ANMLRegisterFragment.newInstance(currentRegistrationReward!!)
        } else {
            ANMLRegisterFragment.newInstance()
        }
        fragment.setANMLRegisterListener(this)
        childFragmentManager.beginTransaction()
            .replace(R.id.anml_root, fragment)
            .commit()
    }

    private fun showClaimFragment() {
        hideStatusFragments()
        val fragment = ANMLClaimFragment.newInstance()
        fragment.setANMLClaimListener(this)
        childFragmentManager.beginTransaction()
            .replace(R.id.anml_root, fragment)
            .commit()
    }

    private fun showCompleteFragment() {
        hideStatusFragments()
        val fragment = ANMLCompleteFragment.newInstance()
        childFragmentManager.beginTransaction()
            .replace(R.id.anml_root, fragment)
            .commit()
    }

    override fun onRegisterRequested() {
        val i = Intent(context, network.erth.wallet.ui.host.HostActivity::class.java)
        i.putExtra("fragment_to_show", "camera_mrz_scanner")
        startActivity(i)
    }

    override fun onClaimRequested() {
        lifecycleScope.launch {
            try {
                val exec = JSONObject()
                exec.put("claim_anml", JSONObject())

                val result = TransactionExecutor.executeContract(
                    fragment = this@ANMLClaimMainFragment,
                    contractAddress = Constants.REGISTRATION_CONTRACT,
                    message = exec,
                    codeHash = Constants.REGISTRATION_HASH,
                    gasLimit = 350_000,  // Needs ~302k gas, set to 350k for safety
                    contractLabel = "Registration Contract:"
                )

                result.onSuccess {
                    // Transaction successful - navigate to complete screen
                    showCompleteFragment()
                    // Broadcast receiver will handle status refreshes
                }.onFailure { error ->
                    if (error.message != "Transaction cancelled by user" &&
                        error.message != "Authentication failed") {
                        Log.e(TAG, "ANML claim failed: ${error.message}")
                        Toast.makeText(context, "Claim failed: ${error.message}", Toast.LENGTH_LONG).show()
                    }
                    // Transaction failed or was cancelled - refresh to ensure UI is correct
                    checkStatus()
                }

            } catch (e: Exception) {
                Toast.makeText(context, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Launch the reusable query activity to check registration/claim status
    private fun checkStatus() {
        try {
            showLoading(true)

            // Get wallet address using SecureWalletManager
            val address: String
            try {
                if (!SecureWalletManager.isWalletAvailable(requireContext())) {
                    showLoading(false)
                    showRegisterFragment()
                    return
                }

                address = SecureWalletManager.getWalletAddress(requireContext()) ?: ""
                if (TextUtils.isEmpty(address)) {
                    showLoading(false)
                    showRegisterFragment()
                    return
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get wallet address: ${e.message}")
                showLoading(false)
                showRegisterFragment()
                return
            }

            // Build query object for the bridge (no proxy)
            val q = JSONObject()
            val inner = JSONObject()
            inner.put("address", address)
            q.put("query_registration_status", inner)

            // Use SecretKClient directly
            lifecycleScope.launch {
                try {
                    // Check wallet availability without retrieving mnemonic
                    if (!SecureWalletManager.isWalletAvailable(requireContext())) {
                        showLoading(false)
                        errorText?.let { errorText ->
                            errorText.text = "No wallet found"
                            errorText.visibility = View.VISIBLE
                        }
                        return@launch
                    }

                    val result = SecretKClient.queryContractJson(
                        Constants.REGISTRATION_CONTRACT,
                        q,
                        Constants.REGISTRATION_HASH
                    )

                    // Extract data from wrapper
                    val actualResult = if (result.has("data")) {
                        result.getJSONObject("data")
                    } else {
                        result
                    }

                    // Format result to match expected format
                    val response = JSONObject()
                    response.put("success", true)
                    response.put("result", actualResult)

                    // Handle result
                    handleRegistrationQueryResult(response.toString())

                } catch (e: Exception) {
                    Log.e(TAG, "Registration status query failed", e)
                    val fullError = "Failed to check status:\n${e.javaClass.simpleName}: ${e.message}\n${e.stackTrace.take(3).joinToString("\n")}"
                    showLoading(false)
                    errorText?.let { errorText ->
                        errorText.text = fullError
                        errorText.visibility = View.VISIBLE
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "checkStatus failed", e)
            showLoading(false)
            errorText?.let { errorText ->
                errorText.text = "Error: ${e.message}"
                errorText.visibility = View.VISIBLE
            }
        }
    }

    private fun handleRegistrationQueryResult(json: String) {
        showLoading(false)
        val wasSuppressed = suppressNextQueryDialog
        suppressNextQueryDialog = false

        try {
            if (TextUtils.isEmpty(json)) {
                errorText?.let { errorText ->
                    errorText.text = "No response from bridge."
                    errorText.visibility = View.VISIBLE
                }
                return
            }

            val root = JSONObject(json)
            val success = root.optBoolean("success", false)
            if (!success) {
                errorText?.let { errorText ->
                    errorText.text = "Bridge returned error."
                    errorText.visibility = View.VISIBLE
                }
                return
            }
            val result = root.optJSONObject("result") ?: root

            // Extract registration reward if available
            currentRegistrationReward = result.optString("registration_reward", null).takeIf { it != "null" }

            if ("no_wallet" == result.optString("status", "")) {
                showRegisterFragment()
                return
            }

            val registered = result.optBoolean("registration_status", false)
            if (!registered) {
                showRegisterFragment()
                return
            }

            val lastClaim = result.optLong("last_claim", 0L)
            val nextClaimMillis = (lastClaim / 1000000L) + ONE_DAY_MILLIS
            val now = System.currentTimeMillis()
            if (now > nextClaimMillis) {
                showClaimFragment()
            } else {
                showCompleteFragment()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse bridge result", e)
            errorText?.let { errorText ->
                errorText.text = "Invalid result from bridge."
                errorText.visibility = View.VISIBLE
            }
        }
    }


    override fun onDestroy() {
        super.onDestroy()

        // Cleanup loading overlay
        if (loadingOverlay != null && context != null) {
            loadingOverlay!!.cleanup(context)
        }

        // Unregister broadcast receiver
        if (transactionSuccessReceiver != null && context != null) {
            try {
                requireActivity().applicationContext.unregisterReceiver(transactionSuccessReceiver)
            } catch (e: IllegalArgumentException) {
                // Receiver was not registered, ignore
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering receiver", e)
            }
        }
    }
}