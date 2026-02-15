package network.erth.wallet.ui.components

import android.content.Context
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import network.erth.wallet.R
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * Reusable transaction confirmation dialog component
 * Used to show transaction details and get user confirmation before execution
 */
class TransactionConfirmationDialog(context: Context) {

    /**
     * Confirmation result indicating what action the user took
     */
    enum class ConfirmationResult {
        CONFIRMED,           // User confirmed without using gas grant
        CONFIRMED_WITH_GAS,  // User confirmed after watching ad for gas
        CANCELLED            // User cancelled
    }

    interface OnConfirmationListener {
        fun onConfirmed()
        fun onCancelled()
    }

    interface OnConfirmationWithGasListener {
        fun onResult(result: ConfirmationResult)
        fun onAdsForGasClicked(callback: (Boolean) -> Unit)
    }

    class TransactionDetails @JvmOverloads constructor(
        @JvmField val contractAddress: String,
        @JvmField val message: String,
        @JvmField var contractLabel: String = "Contract:",
        @JvmField var funds: String? = null,
        @JvmField var memo: String? = null,
        @JvmField var customWarning: String? = null,
        @JvmField var showAdsForGas: Boolean = false,
        @JvmField var hasExistingGrant: Boolean = false
    ) {
        fun setContractLabel(label: String) = apply { contractLabel = label }
        fun setFunds(funds: String?) = apply { this.funds = funds }
        fun setMemo(memo: String?) = apply { this.memo = memo }
        fun setCustomWarning(warning: String?) = apply { this.customWarning = warning }
        fun setShowAdsForGas(show: Boolean) = apply { this.showAdsForGas = show }
        fun setHasExistingGrant(hasGrant: Boolean) = apply { this.hasExistingGrant = hasGrant }
    }

    private val bottomSheetDialog = BottomSheetDialog(context)
    private var listener: OnConfirmationListener? = null
    private var gasListener: OnConfirmationWithGasListener? = null
    private var loadingOverlay: LinearLayout? = null
    private var mainContent: LinearLayout? = null

    fun show(details: TransactionDetails, listener: OnConfirmationListener) {
        this.listener = listener
        showInternal(details, false)
    }

    fun showWithGasOption(details: TransactionDetails, listener: OnConfirmationWithGasListener) {
        this.gasListener = listener
        showInternal(details, true)
    }

    /**
     * Show the dialog with a loading state. Call hideLoading() when ready to show content.
     */
    fun showLoading() {
        val bottomSheetView = LayoutInflater.from(bottomSheetDialog.context)
            .inflate(R.layout.transaction_confirmation_popup, null)
        bottomSheetDialog.setContentView(bottomSheetView)

        loadingOverlay = bottomSheetView.findViewById(R.id.loading_overlay)
        mainContent = bottomSheetView.findViewById(R.id.main_content)

        loadingOverlay?.visibility = View.VISIBLE
        mainContent?.visibility = View.GONE

        bottomSheetDialog.show()

        // Configure bottom sheet
        val bottomSheet = bottomSheetDialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        bottomSheet?.let { sheet ->
            sheet.setPadding(0, 0, 0, 0)
            sheet.setBackgroundColor(androidx.core.content.ContextCompat.getColor(bottomSheetDialog.context, R.color.surface))

            (sheet.parent as? View)?.setPadding(0, 0, 0, 0)

            val behavior = BottomSheetBehavior.from(sheet)
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
        }

        bottomSheetDialog.window?.let { window ->
            window.decorView.setPadding(0, 0, 0, 0)
            window.setBackgroundDrawableResource(android.R.color.transparent)
        }
    }

    /**
     * Hide loading and show the transaction details
     */
    fun hideLoading() {
        loadingOverlay?.visibility = View.GONE
        mainContent?.visibility = View.VISIBLE
    }

    /**
     * Update the dialog with transaction details after showing loading state
     */
    fun updateWithDetails(details: TransactionDetails, listener: OnConfirmationWithGasListener) {
        this.gasListener = listener

        val bottomSheetView = bottomSheetDialog.findViewById<View>(android.R.id.content) ?: return

        // Hide loading, show content
        loadingOverlay?.visibility = View.GONE
        mainContent?.visibility = View.VISIBLE

        setupContent(bottomSheetView, details, true)
    }

    private fun showInternal(details: TransactionDetails, withGasOption: Boolean) {
        val bottomSheetView = LayoutInflater.from(bottomSheetDialog.context)
            .inflate(R.layout.transaction_confirmation_popup, null)
        bottomSheetDialog.setContentView(bottomSheetView)

        loadingOverlay = bottomSheetView.findViewById(R.id.loading_overlay)
        mainContent = bottomSheetView.findViewById(R.id.main_content)

        // Ensure loading is hidden and content is visible
        loadingOverlay?.visibility = View.GONE
        mainContent?.visibility = View.VISIBLE

        setupContent(bottomSheetView, details, withGasOption)

        bottomSheetDialog.show()

        // Configure bottom sheet
        val bottomSheet = bottomSheetDialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        bottomSheet?.let { sheet ->
            sheet.setPadding(0, 0, 0, 0)
            sheet.setBackgroundColor(androidx.core.content.ContextCompat.getColor(bottomSheetDialog.context, R.color.surface))

            (sheet.parent as? View)?.setPadding(0, 0, 0, 0)

            val behavior = BottomSheetBehavior.from(sheet)
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
        }

        bottomSheetDialog.window?.let { window ->
            window.decorView.setPadding(0, 0, 0, 0)
            window.setBackgroundDrawableResource(android.R.color.transparent)
        }
    }

    private fun setupContent(bottomSheetView: View, details: TransactionDetails, withGasOption: Boolean) {
        var result = ConfirmationResult.CANCELLED

        // Find views
        val contractAddressText = bottomSheetView.findViewById<TextView>(R.id.contract_address_text)
        val transactionTypeText = bottomSheetView.findViewById<TextView>(R.id.transaction_type_text)
        val showMessageButton = bottomSheetView.findViewById<TextView>(R.id.show_message_button)
        val fundsLabel = bottomSheetView.findViewById<TextView>(R.id.funds_label)
        val fundsText = bottomSheetView.findViewById<TextView>(R.id.funds_text)
        val memoLabel = bottomSheetView.findViewById<TextView>(R.id.memo_label)
        val memoText = bottomSheetView.findViewById<TextView>(R.id.memo_text)
        val cancelButton = bottomSheetView.findViewById<Button>(R.id.cancel_button)
        val confirmButton = bottomSheetView.findViewById<Button>(R.id.confirm_button)
        val adsForGasSection = bottomSheetView.findViewById<LinearLayout>(R.id.ads_for_gas_section)
        val adsForGasButton = bottomSheetView.findViewById<Button>(R.id.ads_for_gas_button)
        val gasGrantStatus = bottomSheetView.findViewById<Button>(R.id.gas_grant_status)

        // Set transaction details
        contractAddressText.text = truncateAddress(details.contractAddress)
        transactionTypeText.text = parseTransactionType(details.message)

        // Show raw message button click
        showMessageButton.setOnClickListener {
            showRawMessageDialog(bottomSheetDialog.context, details.message)
        }

        // Show funds section if funds are provided
        if (!TextUtils.isEmpty(details.funds)) {
            fundsLabel?.visibility = View.VISIBLE
            fundsText?.visibility = View.VISIBLE
            fundsText?.text = details.funds
        }

        // Show memo section if memo is provided
        if (!TextUtils.isEmpty(details.memo)) {
            memoLabel?.visibility = View.VISIBLE
            memoText?.visibility = View.VISIBLE
            memoText?.text = details.memo
        }

        // Handle Ads for Gas section
        if (withGasOption && details.showAdsForGas && adsForGasSection != null) {
            adsForGasSection.visibility = View.VISIBLE

            if (details.hasExistingGrant) {
                // Already has a grant - show status
                gasGrantStatus?.visibility = View.VISIBLE
                adsForGasButton?.visibility = View.GONE
            } else {
                // No grant - show button
                gasGrantStatus?.visibility = View.GONE
                adsForGasButton?.visibility = View.VISIBLE
                adsForGasButton?.setOnClickListener {
                    // Disable button while processing
                    adsForGasButton.isEnabled = false
                    adsForGasButton.text = "Loading ad..."

                    gasListener?.onAdsForGasClicked { success ->
                        if (success) {
                            // Ad watched successfully, grant will be created
                            gasGrantStatus?.visibility = View.VISIBLE
                            adsForGasButton.visibility = View.GONE
                            details.hasExistingGrant = true
                        } else {
                            // Ad failed or not available
                            adsForGasButton.isEnabled = true
                            adsForGasButton.text = "Watch Ad"
                        }
                    }

                    // Show "Creating gas grant..." after ad starts (before callback)
                    // This runs immediately after click, callback updates it later
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (!adsForGasButton.isEnabled && adsForGasButton.visibility == View.VISIBLE) {
                            adsForGasButton.text = "Creating gas grant..."
                        }
                    }, 1000)  // Show after 1 second (ad should be showing by then)
                }
            }
        } else {
            adsForGasSection?.visibility = View.GONE
        }

        // Set click listeners
        cancelButton.setOnClickListener {
            result = ConfirmationResult.CANCELLED
            bottomSheetDialog.dismiss()
        }

        confirmButton.setOnClickListener {
            result = if (details.hasExistingGrant) {
                ConfirmationResult.CONFIRMED_WITH_GAS
            } else {
                ConfirmationResult.CONFIRMED
            }
            bottomSheetDialog.dismiss()
        }

        // Handle swipe down / dismiss
        bottomSheetDialog.setOnDismissListener {
            if (withGasOption) {
                gasListener?.onResult(result)
            } else {
                when (result) {
                    ConfirmationResult.CONFIRMED, ConfirmationResult.CONFIRMED_WITH_GAS -> listener?.onConfirmed()
                    ConfirmationResult.CANCELLED -> listener?.onCancelled()
                }
            }
        }
    }

    private fun parseTransactionType(message: String): String {
        try {
            // Parse the JSON message to extract the transaction type
            val json = org.json.JSONObject(message)
            val keys = json.keys()
            if (keys.hasNext()) {
                val key = keys.next()
                return formatTransactionType(key)
            }
        } catch (e: Exception) {
            // If parsing fails, return generic type
        }
        return "Contract Execution"
    }

    private fun formatTransactionType(key: String): String {
        // Convert snake_case or camelCase to Title Case with description
        return when (key.lowercase()) {
            "transfer" -> "Token Transfer"
            "send" -> "Send Tokens"
            "mint" -> "Mint Tokens"
            "burn" -> "Burn Tokens"
            "approve" -> "Approve Spending"
            "increase_allowance" -> "Increase Allowance"
            "decrease_allowance" -> "Decrease Allowance"
            "transfer_from" -> "Transfer From"
            "send_from" -> "Send From"
            "stake" -> "Stake Tokens"
            "unstake" -> "Unstake Tokens"
            "claim" -> "Claim Rewards"
            "claim_rewards" -> "Claim Rewards"
            "withdraw" -> "Withdraw"
            "deposit" -> "Deposit"
            "swap" -> "Token Swap"
            "provide_liquidity" -> "Provide Liquidity"
            "withdraw_liquidity" -> "Withdraw Liquidity"
            "vote" -> "Cast Vote"
            "delegate" -> "Delegate"
            "undelegate" -> "Undelegate"
            "redelegate" -> "Redelegate"
            "register" -> "Register"
            "update" -> "Update"
            "execute" -> "Execute"
            "instantiate" -> "Instantiate Contract"
            "migrate" -> "Migrate Contract"
            else -> key.replace("_", " ")
                .split(" ")
                .joinToString(" ") { word ->
                    word.replaceFirstChar { it.uppercase() }
                }
        }
    }

    private fun truncateAddress(address: String): String {
        if (TextUtils.isEmpty(address)) return ""
        if (address.length <= 20) return address
        return "${address.substring(0, 12)}...${address.substring(address.length - 8)}"
    }

    private fun showRawMessageDialog(context: Context, message: String) {
        val dialog = android.app.Dialog(context, android.R.style.Theme_Material_Light_NoActionBar)
        dialog.setContentView(R.layout.dialog_raw_message)

        val backButton = dialog.findViewById<android.widget.ImageButton>(R.id.back_button)
        val messageText = dialog.findViewById<TextView>(R.id.message_text)

        // Format JSON with indentation and decode nested base64 messages
        val formattedMessage = try {
            val json = org.json.JSONObject(message)
            decodeNestedMessages(json)
            json.toString(2)
        } catch (e: Exception) {
            message
        }

        messageText.text = formattedMessage

        backButton.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun decodeNestedMessages(json: org.json.JSONObject) {
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = json.get(key)

            when {
                // Decode base64 "msg" fields
                key == "msg" && value is String -> {
                    try {
                        val decoded = String(android.util.Base64.decode(value, android.util.Base64.DEFAULT))
                        val nestedJson = org.json.JSONObject(decoded)
                        decodeNestedMessages(nestedJson)
                        json.put(key, nestedJson)
                    } catch (e: Exception) {
                        // Not valid base64 JSON, keep original
                    }
                }
                value is org.json.JSONObject -> decodeNestedMessages(value)
                value is org.json.JSONArray -> {
                    for (i in 0 until value.length()) {
                        val item = value.get(i)
                        if (item is org.json.JSONObject) {
                            decodeNestedMessages(item)
                        }
                    }
                }
            }
        }
    }

    fun dismiss() {
        if (bottomSheetDialog.isShowing) {
            bottomSheetDialog.dismiss()
        }
    }
}