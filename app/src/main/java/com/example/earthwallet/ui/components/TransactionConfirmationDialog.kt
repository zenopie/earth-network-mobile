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

    fun show(details: TransactionDetails, listener: OnConfirmationListener) {
        this.listener = listener
        showInternal(details, false)
    }

    fun showWithGasOption(details: TransactionDetails, listener: OnConfirmationWithGasListener) {
        this.gasListener = listener
        showInternal(details, true)
    }

    private fun showInternal(details: TransactionDetails, withGasOption: Boolean) {
        val bottomSheetView = LayoutInflater.from(bottomSheetDialog.context)
            .inflate(R.layout.transaction_confirmation_popup, null)
        bottomSheetDialog.setContentView(bottomSheetView)

        var result = ConfirmationResult.CANCELLED

        // Find views
        val contractLabel = bottomSheetView.findViewById<TextView>(R.id.contract_label)
        val contractAddressText = bottomSheetView.findViewById<TextView>(R.id.contract_address_text)
        val executeMessageText = bottomSheetView.findViewById<TextView>(R.id.execute_message_text)
        val fundsText = bottomSheetView.findViewById<TextView>(R.id.funds_text)
        val memoText = bottomSheetView.findViewById<TextView>(R.id.memo_text)
        val fundsSection = bottomSheetView.findViewById<View>(R.id.funds_section)
        val memoSection = bottomSheetView.findViewById<View>(R.id.memo_section)
        val cancelButton = bottomSheetView.findViewById<Button>(R.id.cancel_button)
        val confirmButton = bottomSheetView.findViewById<Button>(R.id.confirm_button)
        val adsForGasSection = bottomSheetView.findViewById<LinearLayout>(R.id.ads_for_gas_section)
        val adsForGasButton = bottomSheetView.findViewById<Button>(R.id.ads_for_gas_button)
        val gasGrantStatus = bottomSheetView.findViewById<TextView>(R.id.gas_grant_status)

        // Set transaction details
        contractLabel.text = details.contractLabel
        contractAddressText.text = truncateAddress(details.contractAddress)
        executeMessageText.text = details.message

        // Show funds section if funds are provided
        if (!TextUtils.isEmpty(details.funds)) {
            fundsSection.visibility = View.VISIBLE
            fundsText.text = details.funds
        } else {
            fundsSection.visibility = View.GONE
        }

        // Show memo section if memo is provided
        if (!TextUtils.isEmpty(details.memo)) {
            memoSection.visibility = View.VISIBLE
            memoText.text = details.memo
        } else {
            memoSection.visibility = View.GONE
        }

        // Handle Ads for Gas section
        if (withGasOption && details.showAdsForGas && adsForGasSection != null) {
            adsForGasSection.visibility = View.VISIBLE

            if (details.hasExistingGrant) {
                // Already has a grant - show status
                gasGrantStatus?.visibility = View.VISIBLE
                gasGrantStatus?.text = "✓ Gas grant active"
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
                            gasGrantStatus?.text = "✓ Gas grant active"
                            adsForGasButton.visibility = View.GONE
                            details.hasExistingGrant = true
                        } else {
                            // Ad failed or not available
                            adsForGasButton.isEnabled = true
                            adsForGasButton.text = "📺 Ads for Gas"
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

        bottomSheetDialog.show()

        // Configure bottom sheet to expand to full content height
        val bottomSheet = bottomSheetDialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        bottomSheet?.let {
            val behavior = BottomSheetBehavior.from(it)
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
        }
    }

    private fun truncateAddress(address: String): String {
        if (TextUtils.isEmpty(address)) return ""
        if (address.length <= 20) return address
        return "${address.substring(0, 10)}...${address.substring(address.length - 6)}"
    }

    fun dismiss() {
        if (bottomSheetDialog.isShowing) {
            bottomSheetDialog.dismiss()
        }
    }
}