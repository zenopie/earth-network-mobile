package network.erth.wallet.ui.pages.anml

import network.erth.wallet.R
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import com.google.android.material.textfield.TextInputEditText

class MRZInputFragment : Fragment() {

    // UI elements
    private var passportNumberEditText: TextInputEditText? = null
    private var dateOfBirthEditText: TextInputEditText? = null
    private var dateOfExpiryEditText: TextInputEditText? = null
    private var scanButton: Button? = null

    // Interface for communication with parent activity
    interface MRZInputListener {
        fun onMRZDataEntered(passportNumber: String, dateOfBirth: String, dateOfExpiry: String)
    }

    private var listener: MRZInputListener? = null

    companion object {
        @JvmStatic
        fun newInstance(): MRZInputFragment = MRZInputFragment()
    }

    fun setMRZInputListener(listener: MRZInputListener) {
        this.listener = listener
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.activity_mrz_input, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Hide bottom navigation and status bar
        (activity as? network.erth.wallet.ui.host.HostActivity)?.let { hostActivity ->
            hostActivity.hideBottomNavigation()

            // Hide status bar using modern approach
            activity?.window?.let { window ->
                WindowCompat.setDecorFitsSystemWindows(window, false)
                val controller = WindowInsetsControllerCompat(window, window.decorView)
                controller.hide(WindowInsetsCompat.Type.statusBars())
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }

        // Initialize UI elements
        passportNumberEditText = view.findViewById(R.id.passport_number)
        dateOfBirthEditText = view.findViewById(R.id.date_of_birth)
        dateOfExpiryEditText = view.findViewById(R.id.date_of_expiry)
        scanButton = view.findViewById(R.id.scan_button)

        // Load captured MRZ data if available, otherwise clear fields
        val prefs = context?.getSharedPreferences("mrz_data", android.content.Context.MODE_PRIVATE)
        val savedPassportNumber = prefs?.getString("passportNumber", "") ?: ""
        val savedDateOfBirth = prefs?.getString("dateOfBirth", "") ?: ""
        val savedDateOfExpiry = prefs?.getString("dateOfExpiry", "") ?: ""

        passportNumberEditText?.setText(savedPassportNumber)
        dateOfBirthEditText?.setText(savedDateOfBirth)
        dateOfExpiryEditText?.setText(savedDateOfExpiry)

        // Set click listener for scan button
        scanButton?.setOnClickListener {
            if (validateInput()) {
                // Get MRZ data and save it to SharedPreferences for the scanner
                val passportNumber = getTextFromEditText(passportNumberEditText)
                val dateOfBirth = getTextFromEditText(dateOfBirthEditText)
                val dateOfExpiry = getTextFromEditText(dateOfExpiryEditText)

                // Do not log sensitive MRZ values in cleartext during demos

                // Save MRZ data to SharedPreferences for the scanner to use
                val scannerPrefs = context?.getSharedPreferences("mrz_data", android.content.Context.MODE_PRIVATE)
                scannerPrefs?.edit()?.apply {
                    putString("passportNumber", passportNumber)
                    putString("dateOfBirth", dateOfBirth)
                    putString("dateOfExpiry", dateOfExpiry)
                    apply()
                }

                // Navigate to scanner fragment - let HostActivity handle UI state
                (activity as? network.erth.wallet.ui.host.HostActivity)?.let { hostActivity ->
                    hostActivity.showFragment("scanner")
                }

                // Also notify listener if present (for embedded usage)
                listener?.onMRZDataEntered(passportNumber, dateOfBirth, dateOfExpiry)
            }
        }
    }

    private fun getTextFromEditText(editText: TextInputEditText?): String {
        return editText?.text?.toString()?.trim() ?: ""
    }

    private fun validateInput(): Boolean {
        // Check if all required fields are filled
        if (isEmpty(passportNumberEditText)) {
            passportNumberEditText?.error = "Passport number is required"
            return false
        }

        if (isEmpty(dateOfBirthEditText)) {
            dateOfBirthEditText?.error = "Date of birth is required"
            return false
        }

        if (isEmpty(dateOfExpiryEditText)) {
            dateOfExpiryEditText?.error = "Date of expiry is required"
            return false
        }

        // Additional validation for specific fields
        val dateOfBirth = getTextFromEditText(dateOfBirthEditText)
        if (dateOfBirth.length != 6) {
            dateOfBirthEditText?.error = "Date of birth must be 6 characters (YYMMDD)"
            return false
        }

        val dateOfExpiry = getTextFromEditText(dateOfExpiryEditText)
        if (dateOfExpiry.length != 6) {
            dateOfExpiryEditText?.error = "Date of expiry must be 6 characters (YYMMDD)"
            return false
        }

        return true
    }

    private fun isEmpty(editText: TextInputEditText?): Boolean {
        return editText?.text?.toString()?.trim().isNullOrEmpty()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Don't restore navigation here - let HostActivity manage it based on the target fragment
        // The HostActivity showFragment() method will properly set navigation state
    }
}