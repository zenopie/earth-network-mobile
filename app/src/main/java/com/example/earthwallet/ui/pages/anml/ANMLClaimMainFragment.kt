package com.example.earthwallet.ui.pages.anml

import com.example.earthwallet.R
import android.app.Activity
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
import com.example.earthwallet.bridge.activities.TransactionActivity
import com.example.earthwallet.Constants
import com.example.earthwallet.ui.components.LoadingOverlay
import com.example.earthwallet.bridge.services.SecretQueryService
import com.example.earthwallet.wallet.services.SecureWalletManager
import org.json.JSONObject

class ANMLClaimMainFragment : Fragment(), ANMLRegisterFragment.ANMLRegisterListener, ANMLClaimFragment.ANMLClaimListener {

    companion object {
        private const val TAG = "ANMLClaimFragment"
        private const val ONE_DAY_MILLIS = 24L * 60L * 60L * 1000L
        private const val REQ_QUERY = 1001
        private const val REQ_EXECUTE = 1002

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
                Log.d(TAG, "Received TRANSACTION_SUCCESS broadcast - refreshing ANML status immediately")

                // Start multiple refresh attempts to ensure UI updates during animation
                checkStatus() // First immediate refresh

                // Stagger additional refreshes to catch the UI during animation
                Handler(Looper.getMainLooper()).postDelayed({
                    Log.d(TAG, "Secondary refresh during animation")
                    checkStatus()
                }, 100) // 100ms delay

                Handler(Looper.getMainLooper()).postDelayed({
                    Log.d(TAG, "Third refresh during animation")
                    checkStatus()
                }, 500) // 500ms delay
            }
        }
    }

    private fun registerBroadcastReceiver() {
        if (activity != null && transactionSuccessReceiver != null) {
            val filter = IntentFilter("com.example.earthwallet.TRANSACTION_SUCCESS")
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    requireActivity().applicationContext.registerReceiver(transactionSuccessReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    requireActivity().applicationContext.registerReceiver(transactionSuccessReceiver, filter)
                }
                Log.d(TAG, "Registered transaction success receiver")
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
        val i = Intent(context, com.example.earthwallet.ui.host.HostActivity::class.java)
        i.putExtra("fragment_to_show", "camera_mrz_scanner")
        startActivity(i)
    }

    override fun onClaimRequested() {
        try {
            val exec = JSONObject()
            exec.put("claim_anml", JSONObject())

            val ei = Intent(context, TransactionActivity::class.java)
            ei.putExtra(TransactionActivity.EXTRA_TRANSACTION_TYPE, TransactionActivity.TYPE_SECRET_EXECUTE)
            ei.putExtra(TransactionActivity.EXTRA_CONTRACT_ADDRESS, Constants.REGISTRATION_CONTRACT)
            ei.putExtra(TransactionActivity.EXTRA_CODE_HASH, Constants.REGISTRATION_HASH)
            ei.putExtra(TransactionActivity.EXTRA_EXECUTE_JSON, exec.toString())
            // Funds/memo/lcd are optional; default LCD is used in the bridge
            startActivityForResult(ei, REQ_EXECUTE)
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to start claim: ${e.message}", Toast.LENGTH_SHORT).show()
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

            // Use SecretQueryService directly in background thread
            Thread {
                try {
                    // Check wallet availability without retrieving mnemonic
                    if (!SecureWalletManager.isWalletAvailable(requireContext())) {
                        activity?.runOnUiThread {
                            showLoading(false)
                            errorText?.let { errorText ->
                                errorText.text = "No wallet found"
                                errorText.visibility = View.VISIBLE
                            }
                        }
                        return@Thread
                    }

                    val queryService = SecretQueryService(requireContext())
                    val result = queryService.queryContract(
                        Constants.REGISTRATION_CONTRACT,
                        Constants.REGISTRATION_HASH,
                        q
                    )

                    // Format result to match expected format
                    val response = JSONObject()
                    response.put("success", true)
                    response.put("result", result)

                    // Handle result on UI thread
                    activity?.runOnUiThread {
                        handleRegistrationQueryResult(response.toString())
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Registration status query failed", e)
                    activity?.runOnUiThread {
                        showLoading(false)
                        errorText?.let { errorText ->
                            errorText.text = "Failed to check status: ${e.message}"
                            errorText.visibility = View.VISIBLE
                        }
                    }
                }
            }.start()
        } catch (e: Exception) {
            Log.e(TAG, "checkStatus failed", e)
            showLoading(false)
            errorText?.let { errorText ->
                errorText.text = "Failed to check status: ${e.message}"
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQ_EXECUTE) {
            // Hide loading screen that might be showing
            showLoading(false)

            if (resultCode == Activity.RESULT_OK) {
                Log.d(TAG, "ANML claim transaction succeeded")
                // Transaction successful - navigate optimistically to complete screen
                showCompleteFragment()
                // Broadcast receiver will handle status refreshes
            } else {
                val error = data?.getStringExtra(TransactionActivity.EXTRA_ERROR) ?: "Unknown error"
                Log.e(TAG, "ANML claim transaction failed: $error")
                // Transaction failed or was cancelled - refresh to ensure UI is correct
                checkStatus()
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
                Log.d(TAG, "Unregistered transaction success receiver")
            } catch (e: IllegalArgumentException) {
                // Receiver was not registered, ignore
                Log.d(TAG, "Receiver was not registered")
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering receiver", e)
            }
        }
    }
}