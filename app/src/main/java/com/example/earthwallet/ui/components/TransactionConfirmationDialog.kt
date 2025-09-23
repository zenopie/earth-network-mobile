package network.erth.wallet.ui.components

import android.content.Context
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.TextView
import network.erth.wallet.R
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * Reusable transaction confirmation dialog component
 * Used to show transaction details and get user confirmation before execution
 */
class TransactionConfirmationDialog(context: Context) {

    interface OnConfirmationListener {
        fun onConfirmed()
        fun onCancelled()
    }

    class TransactionDetails @JvmOverloads constructor(
        @JvmField val contractAddress: String,
        @JvmField val message: String,
        @JvmField var contractLabel: String = "Contract:",
        @JvmField var funds: String? = null,
        @JvmField var memo: String? = null,
        @JvmField var customWarning: String? = null
    ) {
        fun setContractLabel(label: String) = apply { contractLabel = label }
        fun setFunds(funds: String?) = apply { this.funds = funds }
        fun setMemo(memo: String?) = apply { this.memo = memo }
        fun setCustomWarning(warning: String?) = apply { this.customWarning = warning }
    }

    private val bottomSheetDialog = BottomSheetDialog(context)
    private var listener: OnConfirmationListener? = null

    fun show(details: TransactionDetails, listener: OnConfirmationListener) {
        this.listener = listener

        val bottomSheetView = LayoutInflater.from(bottomSheetDialog.context)
            .inflate(R.layout.transaction_confirmation_popup, null)
        bottomSheetDialog.setContentView(bottomSheetView)

        var isConfirmed = false

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

        // Set click listeners
        cancelButton.setOnClickListener {
            isConfirmed = false
            bottomSheetDialog.dismiss()
        }

        confirmButton.setOnClickListener {
            isConfirmed = true
            bottomSheetDialog.dismiss()
        }

        // Handle swipe down / dismiss
        bottomSheetDialog.setOnDismissListener {
            if (isConfirmed) {
                this.listener?.onConfirmed()
            } else {
                this.listener?.onCancelled()
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