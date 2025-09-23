package com.example.earthwallet.ui.components

import android.app.Dialog
import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.example.earthwallet.R
import com.example.earthwallet.wallet.utils.PinSecurityManager

/**
 * General Purpose PIN Entry Dialog
 *
 * A reusable dialog with a number pad interface for PIN entry.
 * Can be used for authentication, PIN creation, PIN verification, etc.
 */
class PinEntryDialog(private val context: Context) {

    /**
     * PIN entry modes
     */
    enum class PinMode {
        AUTHENTICATE,    // Authenticate with existing PIN
        CREATE,          // Create a new PIN
        VERIFY,          // Verify/confirm a PIN
        CUSTOM          // Custom mode with user-defined behavior
    }

    interface PinEntryListener {
        fun onPinEntered(pin: String)
        fun onPinCancelled()
        fun onPinCreated(pin: String) { /* Optional override for CREATE mode */ }
        fun onPinVerified(pin: String) { /* Optional override for VERIFY mode */ }
        fun onPinSecurityLockout(lockoutMessage: String) { /* Called when PIN is locked out */ }
        fun onPinAuthenticationFailed(remainingAttempts: Int) { /* Called on failed PIN with remaining attempts */ }
    }

    private var dialog: AlertDialog? = null
    private var currentPin = StringBuilder()
    private var listener: PinEntryListener? = null
    private var mode: PinMode = PinMode.AUTHENTICATE
    private var minPinLength: Int = 4
    private var maxPinLength: Int = 6
    private var allowCancel: Boolean = true

    companion object {
        private const val TAG = "PinEntryDialog"
    }

    // UI elements
    private lateinit var pinTitle: TextView
    private lateinit var pinMessage: TextView
    private lateinit var pinDots: Array<TextView>
    private lateinit var confirmButton: Button
    private lateinit var cancelButton: Button

    /**
     * Show PIN dialog with default settings
     */
    fun show(
        title: String = "Enter PIN",
        message: String = "Enter your PIN",
        listener: PinEntryListener
    ) {
        show(
            mode = PinMode.AUTHENTICATE,
            title = title,
            message = message,
            listener = listener
        )
    }

    /**
     * Show PIN dialog with full configuration
     */
    fun show(
        mode: PinMode,
        title: String = getDefaultTitle(mode),
        message: String = getDefaultMessage(mode),
        minLength: Int = 4,
        maxLength: Int = 6,
        allowCancel: Boolean = true,
        listener: PinEntryListener
    ) {
        this.mode = mode
        this.listener = listener
        this.minPinLength = minLength
        this.maxPinLength = maxLength
        this.allowCancel = allowCancel

        // Check PIN security before showing dialog (only for AUTHENTICATE mode)
        if (mode == PinMode.AUTHENTICATE) {
            try {
                val securityStatus = PinSecurityManager.checkPinSecurity(context)
                if (securityStatus.isLockedOut) {
                    listener.onPinSecurityLockout(securityStatus.lockoutMessage ?: "Account locked")
                    return
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check PIN security", e)
                // Continue with normal PIN entry if security check fails
            }
        }

        val view = LayoutInflater.from(context).inflate(R.layout.dialog_pin_number_pad, null)
        initializeViews(view)
        setupUI(title, message)
        setupNumberPad(view)

        dialog = AlertDialog.Builder(context)
            .setView(view)
            .setCancelable(allowCancel)
            .create()

        dialog?.show()
    }

    private fun getDefaultTitle(mode: PinMode): String {
        return when (mode) {
            PinMode.AUTHENTICATE -> "Enter PIN"
            PinMode.CREATE -> "Create PIN"
            PinMode.VERIFY -> "Confirm PIN"
            PinMode.CUSTOM -> "Enter PIN"
        }
    }

    private fun getDefaultMessage(mode: PinMode): String {
        return when (mode) {
            PinMode.AUTHENTICATE -> "Enter your PIN to authenticate"
            PinMode.CREATE -> "Create a new PIN for your wallet"
            PinMode.VERIFY -> "Re-enter your PIN to confirm"
            PinMode.CUSTOM -> "Enter your PIN"
        }
    }

    private fun initializeViews(view: View) {
        pinTitle = view.findViewById(R.id.pin_title)
        pinMessage = view.findViewById(R.id.pin_message)

        pinDots = arrayOf(
            view.findViewById(R.id.pin_dot_1),
            view.findViewById(R.id.pin_dot_2),
            view.findViewById(R.id.pin_dot_3),
            view.findViewById(R.id.pin_dot_4),
            view.findViewById(R.id.pin_dot_5),
            view.findViewById(R.id.pin_dot_6)
        )

        confirmButton = view.findViewById(R.id.btn_confirm)
        cancelButton = view.findViewById(R.id.btn_cancel)
    }

    private fun setupUI(title: String, message: String) {
        pinTitle.text = title
        pinMessage.text = message

        confirmButton.setOnClickListener {
            if (currentPin.length >= minPinLength) {
                val pin = currentPin.toString()
                when (mode) {
                    PinMode.AUTHENTICATE -> {
                        handleAuthenticationAttempt(pin)
                    }
                    PinMode.CREATE -> {
                        listener?.onPinCreated(pin)
                        dismiss()
                    }
                    PinMode.VERIFY -> {
                        listener?.onPinVerified(pin)
                        dismiss()
                    }
                    PinMode.CUSTOM -> {
                        listener?.onPinEntered(pin)
                        dismiss()
                    }
                }
            }
        }

        cancelButton.setOnClickListener {
            listener?.onPinCancelled()
            dismiss()
        }

        // Show/hide cancel button based on allowCancel setting
        if (!allowCancel) {
            cancelButton.visibility = View.GONE
        }

        updatePinDisplay()
    }

    private fun setupNumberPad(view: View) {
        // Number buttons
        val numberButtons = arrayOf(
            view.findViewById<Button>(R.id.btn_0),
            view.findViewById<Button>(R.id.btn_1),
            view.findViewById<Button>(R.id.btn_2),
            view.findViewById<Button>(R.id.btn_3),
            view.findViewById<Button>(R.id.btn_4),
            view.findViewById<Button>(R.id.btn_5),
            view.findViewById<Button>(R.id.btn_6),
            view.findViewById<Button>(R.id.btn_7),
            view.findViewById<Button>(R.id.btn_8),
            view.findViewById<Button>(R.id.btn_9)
        )

        // Set click listeners for number buttons
        for (i in numberButtons.indices) {
            numberButtons[i].setOnClickListener {
                addDigit(i.toString())
            }
        }

        // Backspace button
        val backspaceButton = view.findViewById<Button>(R.id.btn_backspace)
        backspaceButton.setOnClickListener {
            removeLastDigit()
        }
    }

    private fun addDigit(digit: String) {
        if (currentPin.length < maxPinLength) {
            currentPin.append(digit)
            updatePinDisplay()
        }
    }

    private fun removeLastDigit() {
        if (currentPin.isNotEmpty()) {
            currentPin.deleteCharAt(currentPin.length - 1)
            updatePinDisplay()
        }
    }

    private fun updatePinDisplay() {
        // Update dots
        for (i in pinDots.indices) {
            if (i < currentPin.length) {
                pinDots[i].setBackgroundResource(R.drawable.pin_dot_filled)
            } else {
                pinDots[i].setBackgroundResource(R.drawable.pin_dot_empty)
            }
        }

        // Enable/disable confirm button
        confirmButton.isEnabled = currentPin.length >= minPinLength

        // Update security warning message for AUTHENTICATE mode
        if (mode == PinMode.AUTHENTICATE) {
            try {
                val warningMessage = PinSecurityManager.getLockoutStatusMessage(context)
                if (warningMessage != null) {
                    pinMessage.text = warningMessage
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get lockout status", e)
            }
        }
    }

    /**
     * Handle PIN authentication attempt with security checks
     */
    private fun handleAuthenticationAttempt(pin: String) {
        try {
            // First notify the listener (they will validate the PIN)
            listener?.onPinEntered(pin)
            // Don't dismiss here - let the listener handle success/failure
        } catch (e: Exception) {
            Log.e(TAG, "Error during PIN authentication", e)
            // Clear PIN and show error
            currentPin.clear()
            updatePinDisplay()
        }
    }

    /**
     * Call this method when PIN authentication fails
     * Should be called by the listener after PIN validation fails
     */
    fun onAuthenticationFailed() {
        try {
            // Record the failed attempt
            val securityStatus = PinSecurityManager.recordFailedAttempt(context)

            // Clear the entered PIN
            currentPin.clear()
            updatePinDisplay()

            if (securityStatus.isLockedOut) {
                // Account is now locked out
                listener?.onPinSecurityLockout(securityStatus.lockoutMessage ?: "Account locked")
                dismiss()
            } else {
                // Show warning about remaining attempts
                val remaining = 3 - securityStatus.failedAttempts // MAX_ATTEMPTS_BEFORE_LOCKOUT
                listener?.onPinAuthenticationFailed(remaining)

                // Update UI to show warning
                pinMessage.text = "Incorrect PIN. $remaining attempts remaining."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle authentication failure", e)
            // Fallback: just clear the PIN
            currentPin.clear()
            updatePinDisplay()
        }
    }

    /**
     * Call this method when PIN authentication succeeds
     * Should be called by the listener after PIN validation succeeds
     */
    fun onAuthenticationSucceeded() {
        try {
            // Record the successful attempt (clears lockout state)
            PinSecurityManager.recordSuccessfulAttempt(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to record successful PIN attempt", e)
        }

        // Dismiss the dialog
        dismiss()
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
        currentPin.clear()
    }
}