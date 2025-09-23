package network.erth.wallet.ui.pages.auth

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import network.erth.wallet.R
import network.erth.wallet.wallet.services.SecureWalletManager
import network.erth.wallet.wallet.services.SessionManager
import network.erth.wallet.ui.host.HostActivity
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * PinEntryFragment
 *
 * Handles PIN entry for returning users to unlock their wallets.
 * Features a custom number pad and visual PIN dots.
 */
class PinEntryFragment : Fragment() {

    companion object {
        private const val TAG = "PinEntryFragment"

        @JvmStatic
        fun newInstance(): PinEntryFragment = PinEntryFragment()
    }

    // UI Components
    private lateinit var pinInput: EditText
    private lateinit var tvError: TextView

    // PIN dots
    private lateinit var pinDots: Array<View>

    // Number buttons
    private lateinit var numberButtons: Array<Button>
    private lateinit var btnBackspace: ImageButton

    // Current PIN input
    private var currentPin = ""

    // Interface for communication with parent activity
    interface PinEntryListener {
        fun onPinEntered(pin: String)
    }

    private var listener: PinEntryListener? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is PinEntryListener) {
            listener = context
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_pin_entry, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize UI components
        initializeViews(view)
        setupNumberPad()

        // Focus on hidden input to show keyboard if needed
        pinInput.requestFocus()
    }

    private fun initializeViews(view: View) {
        pinInput = view.findViewById(R.id.pin_input)
        tvError = view.findViewById(R.id.tv_error)
        btnBackspace = view.findViewById(R.id.btn_backspace)

        // Initialize PIN dots
        pinDots = arrayOf(
            view.findViewById(R.id.pin_dot_1),
            view.findViewById(R.id.pin_dot_2),
            view.findViewById(R.id.pin_dot_3),
            view.findViewById(R.id.pin_dot_4),
            view.findViewById(R.id.pin_dot_5),
            view.findViewById(R.id.pin_dot_6)
        )

        // Initialize number buttons
        numberButtons = arrayOf(
            view.findViewById(R.id.btn_0),
            view.findViewById(R.id.btn_1),
            view.findViewById(R.id.btn_2),
            view.findViewById(R.id.btn_3),
            view.findViewById(R.id.btn_4),
            view.findViewById(R.id.btn_5),
            view.findViewById(R.id.btn_6),
            view.findViewById(R.id.btn_7),
            view.findViewById(R.id.btn_8),
            view.findViewById(R.id.btn_9)
        )
    }

    private fun setupNumberPad() {
        // Setup number button clicks
        for (i in numberButtons.indices) {
            val digit = if (i == 0) "0" else i.toString()
            numberButtons[i].setOnClickListener {
                addDigit(digit)
            }
        }

        // Setup backspace button
        btnBackspace.setOnClickListener {
            removeDigit()
        }

        // Disable keyboard input on the hidden EditText
        pinInput.showSoftInputOnFocus = false
        pinInput.isFocusable = false
    }


    private fun addDigit(digit: String) {
        if (currentPin.length < 6) {
            currentPin += digit
            updatePinDots()
            hideError()

            // Auto-submit when PIN reaches 4-6 digits
            if (currentPin.length >= 4) {
                // Add a small delay for better UX
                view?.postDelayed({
                    if (currentPin.length >= 4) {
                        verifyPin()
                    }
                }, 100)
            }
        }
    }

    private fun removeDigit() {
        if (currentPin.isNotEmpty()) {
            currentPin = currentPin.dropLast(1)
            updatePinDots()
            hideError()
        }
    }

    private fun updatePinDots() {
        for (i in pinDots.indices) {
            if (i < currentPin.length) {
                pinDots[i].setBackgroundResource(R.drawable.pin_dot_filled)
            } else {
                pinDots[i].setBackgroundResource(R.drawable.pin_dot_empty)
            }
        }
    }

    private fun verifyPin() {
        if (currentPin.length < 4) {
            showError("PIN must be at least 4 digits")
            return
        }

        try {
            // Hash the entered PIN
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(currentPin.toByteArray(StandardCharsets.UTF_8))
            val sb = StringBuilder()
            for (b in digest) {
                sb.append(String.format("%02x", b))
            }
            val enteredPinHash = sb.toString()

            // Verify against stored PIN hash
            if (SecureWalletManager.verifyPinHashWithoutSession(requireContext(), enteredPinHash)) {
                // PIN is correct - start session and navigate
                try {
                    SessionManager.startSession(requireContext(), currentPin)

                    // Notify HostActivity to navigate
                    (activity as? HostActivity)?.initializeSessionAndNavigate(currentPin)


                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start session", e)
                    showError("Failed to unlock. Please try again.")
                    clearPin()
                }
            } else {
                // Incorrect PIN
                showError("Incorrect PIN. Please try again.")
                clearPin()
            }

        } catch (e: Exception) {
            Log.e(TAG, "PIN verification failed", e)
            showError("Failed to verify PIN. Please try again.")
            clearPin()
        }
    }

    private fun clearPin() {
        currentPin = ""
        updatePinDots()
    }

    private fun showError(message: String) {
        tvError.text = message
        tvError.visibility = View.VISIBLE

        // Shake animation for error feedback
        view?.let { rootView ->
            rootView.animate()
                .translationX(-10f)
                .setDuration(50)
                .withEndAction {
                    rootView.animate()
                        .translationX(10f)
                        .setDuration(50)
                        .withEndAction {
                            rootView.animate()
                                .translationX(0f)
                                .setDuration(50)
                        }
                }
        }
    }

    private fun hideError() {
        tvError.visibility = View.GONE
    }

}