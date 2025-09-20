package com.example.earthwallet.ui.pages.anml

import com.example.earthwallet.R
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import kotlinx.coroutines.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.security.crypto.EncryptedSharedPreferences
import com.example.earthwallet.Constants
import com.example.earthwallet.wallet.services.SecureWalletManager
import net.sf.scuba.smartcards.CardService
import org.jmrtd.BACKey
import org.jmrtd.BACKeySpec
import org.jmrtd.PassportService
import org.jmrtd.lds.icao.DG1File
import org.jmrtd.lds.icao.MRZInfo
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.*

class PassportScannerFragment : Fragment(), MRZInputFragment.MRZInputListener {

    companion object {
        private const val TAG = "PassportScannerFragment"

        @JvmStatic
        fun newInstance(): PassportScannerFragment = PassportScannerFragment()
    }

    // Backend URL loaded at runtime from SharedPreferences or resources
    private var backendUrl: String = ""

    // UI elements
    private var progressBar: ProgressBar? = null
    private var statusText: TextView? = null
    private var resultContainer: ScrollView? = null
    private var passportNumberText: TextView? = null
    private var nameText: TextView? = null
    private var nationalityText: TextView? = null
    private var dobText: TextView? = null
    private var genderText: TextView? = null
    private var expiryText: TextView? = null
    private var countryText: TextView? = null

    // Verification result UI
    private var verifyStatusText: TextView? = null
    private var trustStatusText: TextView? = null
    private var trustReasonText: TextView? = null
    private var sodStatusText: TextView? = null
    private var dg1StatusText: TextView? = null
    private var rawResponseText: TextView? = null

    // MRZ data from intent
    private var passportNumber: String? = null
    private var dateOfBirth: String? = null
    private var dateOfExpiry: String? = null

    // Interface for NFC communication with parent activity
    interface PassportScannerListener {
        fun onNFCTagDetected(tag: Tag)
        fun requestNFCSetup()
    }

    private var listener: PassportScannerListener? = null

    fun setPassportScannerListener(listener: PassportScannerListener) {
        this.listener = listener
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.activity_main, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // First, try to retrieve MRZ data from SharedPreferences
        val prefs = requireActivity().getSharedPreferences("mrz_data", Activity.MODE_PRIVATE)
        val savedPassportNumber = prefs.getString("passportNumber", null)
        val savedDateOfBirth = prefs.getString("dateOfBirth", null)
        val savedDateOfExpiry = prefs.getString("dateOfExpiry", null)
        Log.d(TAG, "Retrieved MRZ data from SharedPreferences: passportNumber=$savedPassportNumber, " +
                "dateOfBirth=$savedDateOfBirth, dateOfExpiry=$savedDateOfExpiry")

        // Use saved data if available
        if (!isEmpty(savedPassportNumber) && !isEmpty(savedDateOfBirth) && !isEmpty(savedDateOfExpiry)) {
            passportNumber = savedPassportNumber
            dateOfBirth = savedDateOfBirth
            dateOfExpiry = savedDateOfExpiry
            Log.d(TAG, "Using MRZ data from SharedPreferences")
        }

        // Check if from MRZ input activity intent
        activity?.intent?.let { intent ->
            if (intent.hasExtra("passportNumber") && intent.hasExtra("dateOfBirth") && intent.hasExtra("dateOfExpiry")) {
                val intentPassportNumber = intent.getStringExtra("passportNumber")
                val intentDateOfBirth = intent.getStringExtra("dateOfBirth")
                val intentDateOfExpiry = intent.getStringExtra("dateOfExpiry")
                Log.d(TAG, "Retrieved MRZ data from intent: passportNumber=$intentPassportNumber, " +
                        "dateOfBirth=$intentDateOfBirth, dateOfExpiry=$intentDateOfExpiry")

                if (!isEmpty(intentPassportNumber) && !isEmpty(intentDateOfBirth) && !isEmpty(intentDateOfExpiry)) {
                    passportNumber = intentPassportNumber
                    dateOfBirth = intentDateOfBirth
                    dateOfExpiry = intentDateOfExpiry
                    Log.d(TAG, "Using MRZ data from intent")

                    // Save to SharedPreferences for future use
                    val editor = prefs.edit()
                    editor.putString("passportNumber", passportNumber)
                    editor.putString("dateOfBirth", dateOfBirth)
                    editor.putString("dateOfExpiry", dateOfExpiry)
                    editor.apply()
                    Log.d(TAG, "Saved new MRZ data to SharedPreferences")
                }
            }
        }

        // Check if MRZ data is available
        Log.d(TAG, "Checking MRZ data: passportNumber=$passportNumber (isEmpty=${isEmpty(passportNumber)}), " +
                "dateOfBirth=$dateOfBirth (isEmpty=${isEmpty(dateOfBirth)}), " +
                "dateOfExpiry=$dateOfExpiry (isEmpty=${isEmpty(dateOfExpiry)})")
        if (!isMRZDataValid()) {
            Log.d(TAG, "MRZ data is incomplete, showing MRZ input fragment")
            showMRZInputFragment()
            return
        }
        Log.d(TAG, "MRZ data is complete, proceeding with NFC setup")
        Log.d(TAG, "Current MRZ data: passportNumber=$passportNumber, dateOfBirth=$dateOfBirth, dateOfExpiry=$dateOfExpiry")

        // Initialize UI elements
        progressBar = view.findViewById(R.id.progress_bar)
        resultContainer = view.findViewById(R.id.result_container)
        passportNumberText = view.findViewById(R.id.passport_number_text)
        nameText = view.findViewById(R.id.name_text)
        nationalityText = view.findViewById(R.id.nationality_text)
        dobText = view.findViewById(R.id.dob_text)
        genderText = view.findViewById(R.id.gender_text)
        expiryText = view.findViewById(R.id.expiry_text)
        countryText = view.findViewById(R.id.country_text)

        // Simple progress bar - just set visibility
        progressBar?.visibility = View.GONE

        // Verification results UI
        verifyStatusText = view.findViewById(R.id.verify_status_text)
        trustStatusText = view.findViewById(R.id.trust_status_text)
        trustReasonText = view.findViewById(R.id.trust_reason_text)
        sodStatusText = view.findViewById(R.id.sod_status_text)
        dg1StatusText = view.findViewById(R.id.dg1_status_text)
        rawResponseText = view.findViewById(R.id.raw_response_text)

        // Use backend URL from Constants
        backendUrl = "${Constants.BACKEND_BASE_URL}/verify"
        Log.d(TAG, "Backend URL = $backendUrl")

        // Long-press title to configure backend URL
        val title = view.findViewById<TextView>(R.id.title_text)
        title?.setOnLongClickListener {
            showBackendUrlDialog()
            true
        }

        // Request NFC setup from parent activity
        listener?.requestNFCSetup()
    }

    // Called by parent activity when NFC tag is detected
    fun handleNfcTag(tag: Tag?) {
        if (tag != null) {
            Log.d(TAG, "NFC tag discovered, starting passport reading coroutine")
            readPassportAsync(tag)
        }
    }

    private fun readPassportAsync(tag: Tag) {
        Log.d(TAG, "Starting passport reading coroutine - showing loading spinner")

        // Show progress bar on UI thread
        progressBar?.visibility = View.VISIBLE
        resultContainer?.visibility = View.GONE

        // Launch coroutine for background work
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val passportData = withContext(Dispatchers.IO) {
                    readPassport(tag)
                }

                Log.d(TAG, "Passport reading completed with passportData=$passportData")

                if (passportData != null) {
                    Log.d(TAG, "Passport data read successfully")

                    // Hide progress bar
                    progressBar?.visibility = View.GONE

                    // Check if verification was successful
                    val verificationSuccessful = isVerificationSuccessful(passportData)
                    Log.d(TAG, "Verification successful: $verificationSuccessful")

                    // Navigate based on result
                    if (verificationSuccessful) {
                        Log.d(TAG, "Scan successful, navigating back to ANML")
                        navigateBackToANML()
                    } else {
                        Log.d(TAG, "Verification failed, navigating to failure screen")
                        val failureReason = getFailureReason(passportData)
                        val failureDetails = getFailureDetails(passportData)
                        navigateToFailureScreen(failureReason, failureDetails)
                    }
                } else {
                    Log.d(TAG, "Failed to read passport data")
                    // Hide progress bar
                    progressBar?.visibility = View.GONE

                    // Navigate to failure screen
                    Log.d(TAG, "Scan failed, navigating to failure screen")
                    navigateToFailureScreen("Failed to read passport",
                        "Please ensure your passport is placed correctly on the back of your device and try again.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception during passport reading", e)
                progressBar?.visibility = View.GONE
                navigateToFailureScreen("Error reading passport",
                    "An error occurred: ${e.message}")
            }
        }
    }

    /**
     * Determine if verification was successful based on passport data
     */
    private fun isVerificationSuccessful(passportData: PassportData?): Boolean {
        if (passportData == null) {
            return false
        }

        // Check if backend was contacted successfully
        val httpCode = passportData.backendHttpCode
        if (httpCode == null) {
            Log.d(TAG, "Backend was not contacted (no HTTP code)")
            return false
        }

        if (httpCode != 200) {
            Log.d(TAG, "Backend returned error HTTP code: $httpCode")
            return false
        }

        // Check if passive authentication passed
        val passiveAuth = passportData.passiveAuthenticationPassed
        if (passiveAuth == null || !passiveAuth) {
            Log.d(TAG, "Passive authentication failed or null: $passiveAuth")
            return false
        }

        Log.d(TAG, "All verification checks passed")
        return true
    }

    /**
     * Get failure reason for display
     */
    private fun getFailureReason(passportData: PassportData?): String {
        if (passportData == null) {
            return "Failed to read passport"
        }

        val httpCode = passportData.backendHttpCode
        if (httpCode == null) {
            return "Network error"
        }

        if (httpCode != 200) {
            return "Server error (HTTP $httpCode)"
        }

        val passiveAuth = passportData.passiveAuthenticationPassed
        if (passiveAuth == null || !passiveAuth) {
            return "Authentication failed"
        }

        return "Verification failed"
    }

    /**
     * Get detailed failure information
     */
    private fun getFailureDetails(passportData: PassportData?): String {
        if (passportData == null) {
            return "Could not read passport data via NFC. Please try again."
        }

        val details = StringBuilder()

        val httpCode = passportData.backendHttpCode
        if (httpCode == null) {
            details.append("Could not connect to verification server. ")
        } else if (httpCode != 200) {
            details.append("Server returned error code $httpCode. ")
        }

        val trustStatus = passportData.trustChainStatus
        if (!trustStatus.isNullOrEmpty() && trustStatus != "valid") {
            details.append("Trust chain status: $trustStatus. ")
            val trustReason = passportData.trustChainFailureReason
            if (!trustReason.isNullOrEmpty()) {
                details.append("Reason: $trustReason. ")
            }
        }

        val sodStatus = passportData.sodSignatureStatus
        if (!sodStatus.isNullOrEmpty() && sodStatus != "valid") {
            details.append("Document signature invalid. ")
        }

        val dg1Status = passportData.dg1IntegrityStatus
        if (!dg1Status.isNullOrEmpty() && dg1Status != "valid") {
            details.append("Document integrity check failed. ")
        }

        return if (details.isNotEmpty()) {
            details.toString().trim()
        } else {
            "Passport verification failed for unknown reasons."
        }
    }

    /**
     * Read passport data from NFC tag
     */
    private fun readPassport(tag: Tag): PassportData? {
        Log.d(TAG, "Starting passport read from NFC tag")

        var cardService: CardService? = null
        var passportService: PassportService? = null
        val passportData = PassportData()

        try {
            // Get IsoDep from tag
            val isoDep = IsoDep.get(tag)
            if (isoDep == null) {
                Log.e(TAG, "IsoDep is null")
                return null
            }

            // Create card service
            cardService = CardService.getInstance(isoDep)
            passportService = PassportService(cardService, PassportService.NORMAL_MAX_TRANCEIVE_LENGTH, PassportService.DEFAULT_MAX_BLOCKSIZE, true, true)

            // Open connection
            passportService.open()
            Log.d(TAG, "Passport service opened")

            // Create BAC key from MRZ data
            val bacKey = createBACKey()
            if (bacKey == null) {
                Log.e(TAG, "Failed to create BAC key")
                return null
            }

            // Perform BAC
            passportService.doBAC(bacKey)
            Log.d(TAG, "BAC completed successfully")

            // Read DG1 (contains MRZ)
            val dg1InputStream = passportService.getInputStream(PassportService.EF_DG1)
            val dg1File = DG1File(dg1InputStream)
            val mrzInfo = dg1File.mrzInfo

            // Extract passport data
            passportData.passportNumber = mrzInfo.documentNumber?.replace("<", "")?.trim()
            passportData.firstName = mrzInfo.secondaryIdentifier?.replace("<", " ")?.trim()
            passportData.lastName = mrzInfo.primaryIdentifier?.replace("<", " ")?.trim()
            passportData.nationality = mrzInfo.nationality
            passportData.country = mrzInfo.issuingState
            passportData.gender = mrzInfo.gender?.toString()

            // Parse dates
            try {
                val dateFormat = SimpleDateFormat("yyMMdd", Locale.US)
                val dobDate = dateFormat.parse(mrzInfo.dateOfBirth)
                val expiryDate = dateFormat.parse(mrzInfo.dateOfExpiry)

                val displayFormat = SimpleDateFormat("dd/MM/yyyy", Locale.US)
                passportData.dateOfBirth = displayFormat.format(dobDate)
                passportData.dateOfExpiry = displayFormat.format(expiryDate)
            } catch (e: ParseException) {
                Log.e(TAG, "Error parsing dates", e)
                passportData.dateOfBirth = mrzInfo.dateOfBirth
                passportData.dateOfExpiry = mrzInfo.dateOfExpiry
            }

            Log.d(TAG, "Passport data extracted successfully")

            // Read SOD for verification
            val sodInputStream = passportService.getInputStream(PassportService.EF_SOD)
            val sodBytes = readStream(sodInputStream)
            passportData.sodData = sodBytes

            Log.d(TAG, "SOD data read successfully, size: ${sodBytes?.size} bytes")

            // Send to backend for verification
            verifyWithBackend(passportData)

        } catch (e: Exception) {
            Log.e(TAG, "Error reading passport", e)
            return null
        } finally {
            try {
                passportService?.close()
                cardService?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing services", e)
            }
        }

        return passportData
    }

    /**
     * Create BAC key from MRZ data
     */
    private fun createBACKey(): BACKey? {
        return try {
            if (passportNumber.isNullOrEmpty() || dateOfBirth.isNullOrEmpty() || dateOfExpiry.isNullOrEmpty()) {
                Log.e(TAG, "MRZ data is incomplete for BAC key creation")
                return null
            }

            BACKey(passportNumber, dateOfBirth, dateOfExpiry)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating BAC key", e)
            null
        }
    }

    /**
     * Read InputStream to byte array
     */
    private fun readStream(inputStream: InputStream?): ByteArray? {
        if (inputStream == null) return null

        return try {
            val baos = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            var length: Int
            while (inputStream.read(buffer).also { length = it } != -1) {
                baos.write(buffer, 0, length)
            }
            baos.toByteArray()
        } catch (e: IOException) {
            Log.e(TAG, "Error reading stream", e)
            null
        } finally {
            try {
                inputStream.close()
            } catch (e: IOException) {
                Log.e(TAG, "Error closing stream", e)
            }
        }
    }

    /**
     * Send passport data to backend for verification
     */
    private fun verifyWithBackend(passportData: PassportData) {
        if (backendUrl.isEmpty()) {
            Log.w(TAG, "Backend URL is empty, skipping verification")
            return
        }

        Thread {
            try {
                Log.d(TAG, "Sending verification request to backend: $backendUrl")

                val url = URL(backendUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true

                // Create JSON payload
                val payload = JSONObject().apply {
                    put("passport_number", passportData.passportNumber)
                    put("first_name", passportData.firstName)
                    put("last_name", passportData.lastName)
                    put("date_of_birth", passportData.dateOfBirth)
                    put("date_of_expiry", passportData.dateOfExpiry)
                    put("nationality", passportData.nationality)
                    put("country", passportData.country)
                    put("gender", passportData.gender)

                    // Add SOD data if available
                    passportData.sodData?.let { sod ->
                        put("sod_data", android.util.Base64.encodeToString(sod, android.util.Base64.DEFAULT))
                    }
                }

                // Send request
                val outputStream: OutputStream = connection.outputStream
                outputStream.write(payload.toString().toByteArray())
                outputStream.flush()
                outputStream.close()

                // Get response
                val responseCode = connection.responseCode
                passportData.backendHttpCode = responseCode

                val inputStream = if (responseCode == 200) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }

                val response = inputStream?.bufferedReader()?.use { it.readText() } ?: ""
                passportData.backendRawResponse = response

                Log.d(TAG, "Backend response code: $responseCode")
                Log.d(TAG, "Backend response: $response")

                if (responseCode == 200 && response.isNotEmpty()) {
                    parseBackendResponse(passportData, response)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error contacting backend", e)
                passportData.backendHttpCode = null
                passportData.backendRawResponse = "Error: ${e.message}"
            }
        }.start()
    }

    /**
     * Parse backend verification response
     */
    private fun parseBackendResponse(passportData: PassportData, response: String) {
        try {
            val json = JSONObject(response)

            // Parse verification results
            passportData.passiveAuthenticationPassed = json.optBoolean("passive_authentication_passed", false)
            passportData.trustChainStatus = json.optString("trust_chain_status", "unknown")
            passportData.trustChainFailureReason = json.optString("trust_chain_failure_reason", "")
            passportData.sodSignatureStatus = json.optString("sod_signature_status", "unknown")
            passportData.dg1IntegrityStatus = json.optString("dg1_integrity_status", "unknown")

            Log.d(TAG, "Parsed backend response: " +
                    "passiveAuth=${passportData.passiveAuthenticationPassed}, " +
                    "trustStatus=${passportData.trustChainStatus}, " +
                    "sodStatus=${passportData.sodSignatureStatus}, " +
                    "dg1Status=${passportData.dg1IntegrityStatus}")

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing backend response", e)
        }
    }

    /**
     * Check if MRZ data is valid
     */
    private fun isMRZDataValid(): Boolean {
        return !isEmpty(passportNumber) && !isEmpty(dateOfBirth) && !isEmpty(dateOfExpiry)
    }

    /**
     * Check if string is empty or null
     */
    private fun isEmpty(str: String?): Boolean {
        return str.isNullOrEmpty() || str.trim().isEmpty()
    }

    /**
     * Show MRZ input fragment
     */
    private fun showMRZInputFragment() {
        Log.d(TAG, "Showing MRZ input fragment")
        val hostActivity = activity as? com.example.earthwallet.ui.host.HostActivity
        hostActivity?.showFragment("mrz_input")
    }

    /**
     * Navigate back to ANML main page on successful scan
     */
    private fun navigateBackToANML() {
        Log.d(TAG, "Navigating back to ANML")
        val hostActivity = activity as? com.example.earthwallet.ui.host.HostActivity
        hostActivity?.showFragment("anml")
    }

    /**
     * Navigate to failure screen with reason and details
     */
    private fun navigateToFailureScreen(reason: String, details: String) {
        Log.d(TAG, "Navigating to failure screen with reason: $reason")
        val hostActivity = activity as? com.example.earthwallet.ui.host.HostActivity
        val bundle = Bundle().apply {
            putString("failure_reason", reason)
            putString("failure_details", details)
        }
        hostActivity?.showFragment("scan_failure", bundle)
    }

    /**
     * Show dialog to configure backend URL
     */
    private fun showBackendUrlDialog() {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(backendUrl)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Configure Backend URL")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                backendUrl = input.text.toString().trim()
                Log.d(TAG, "Backend URL updated to: $backendUrl")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // MRZInputListener implementation
    override fun onMRZDataEntered(passportNumber: String, dateOfBirth: String, dateOfExpiry: String) {
        Log.d(TAG, "MRZ input provided: passportNumber=$passportNumber, dateOfBirth=$dateOfBirth, dateOfExpiry=$dateOfExpiry")

        this.passportNumber = passportNumber
        this.dateOfBirth = dateOfBirth
        this.dateOfExpiry = dateOfExpiry

        // Save to SharedPreferences
        val prefs = requireActivity().getSharedPreferences("mrz_data", Activity.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.putString("passportNumber", passportNumber)
        editor.putString("dateOfBirth", dateOfBirth)
        editor.putString("dateOfExpiry", dateOfExpiry)
        editor.apply()

        // Navigate back to scanner
        navigateBackToScanner()
    }


    /**
     * Navigate back to scanner from MRZ input
     */
    private fun navigateBackToScanner() {
        Log.d(TAG, "Navigating back to scanner")
        val hostActivity = activity as? com.example.earthwallet.ui.host.HostActivity
        hostActivity?.showFragment("scanner")
    }

    /**
     * Data class to hold passport information
     */
    data class PassportData(
        var passportNumber: String? = null,
        var firstName: String? = null,
        var lastName: String? = null,
        var dateOfBirth: String? = null,
        var dateOfExpiry: String? = null,
        var nationality: String? = null,
        var country: String? = null,
        var gender: String? = null,
        var sodData: ByteArray? = null,
        var backendHttpCode: Int? = null,
        var backendRawResponse: String? = null,
        var passiveAuthenticationPassed: Boolean? = null,
        var trustChainStatus: String? = null,
        var trustChainFailureReason: String? = null,
        var sodSignatureStatus: String? = null,
        var dg1IntegrityStatus: String? = null
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as PassportData

            if (passportNumber != other.passportNumber) return false
            if (firstName != other.firstName) return false
            if (lastName != other.lastName) return false
            if (dateOfBirth != other.dateOfBirth) return false
            if (dateOfExpiry != other.dateOfExpiry) return false
            if (nationality != other.nationality) return false
            if (country != other.country) return false
            if (gender != other.gender) return false
            if (sodData != null) {
                if (other.sodData == null) return false
                if (!sodData.contentEquals(other.sodData)) return false
            } else if (other.sodData != null) return false
            if (backendHttpCode != other.backendHttpCode) return false
            if (backendRawResponse != other.backendRawResponse) return false
            if (passiveAuthenticationPassed != other.passiveAuthenticationPassed) return false
            if (trustChainStatus != other.trustChainStatus) return false
            if (trustChainFailureReason != other.trustChainFailureReason) return false
            if (sodSignatureStatus != other.sodSignatureStatus) return false
            if (dg1IntegrityStatus != other.dg1IntegrityStatus) return false

            return true
        }

        override fun hashCode(): Int {
            var result = passportNumber?.hashCode() ?: 0
            result = 31 * result + (firstName?.hashCode() ?: 0)
            result = 31 * result + (lastName?.hashCode() ?: 0)
            result = 31 * result + (dateOfBirth?.hashCode() ?: 0)
            result = 31 * result + (dateOfExpiry?.hashCode() ?: 0)
            result = 31 * result + (nationality?.hashCode() ?: 0)
            result = 31 * result + (country?.hashCode() ?: 0)
            result = 31 * result + (gender?.hashCode() ?: 0)
            result = 31 * result + (sodData?.contentHashCode() ?: 0)
            result = 31 * result + (backendHttpCode ?: 0)
            result = 31 * result + (backendRawResponse?.hashCode() ?: 0)
            result = 31 * result + (passiveAuthenticationPassed?.hashCode() ?: 0)
            result = 31 * result + (trustChainStatus?.hashCode() ?: 0)
            result = 31 * result + (trustChainFailureReason?.hashCode() ?: 0)
            result = 31 * result + (sodSignatureStatus?.hashCode() ?: 0)
            result = 31 * result + (dg1IntegrityStatus?.hashCode() ?: 0)
            return result
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // No special cleanup needed for simple progress bar
    }
}