package network.erth.wallet.ui.pages.anml

import network.erth.wallet.R
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.AsyncTask
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import network.erth.wallet.Constants
import network.erth.wallet.wallet.services.SecureWalletManager
import net.sf.scuba.smartcards.CardService
import org.jmrtd.BACKey
import org.jmrtd.BACKeySpec
import org.jmrtd.PassportService
import org.jmrtd.lds.icao.DG1File
import org.jmrtd.lds.icao.MRZInfo
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
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

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.activity_main, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // First, try to retrieve MRZ data from SharedPreferences
        val prefs = requireActivity().getSharedPreferences("mrz_data", Activity.MODE_PRIVATE)
        val savedPassportNumber = prefs.getString("passportNumber", null)
        val savedDateOfBirth = prefs.getString("dateOfBirth", null)
        val savedDateOfExpiry = prefs.getString("dateOfExpiry", null)

        // Use saved data if available
        if (!isEmpty(savedPassportNumber) && !isEmpty(savedDateOfBirth) && !isEmpty(savedDateOfExpiry)) {
            passportNumber = savedPassportNumber
            dateOfBirth = savedDateOfBirth
            dateOfExpiry = savedDateOfExpiry
        }

        // Check if from MRZ input activity intent
        activity?.intent?.let { intent ->
            if (intent.hasExtra("passportNumber") && intent.hasExtra("dateOfBirth") && intent.hasExtra("dateOfExpiry")) {
                val intentPassportNumber = intent.getStringExtra("passportNumber")
                val intentDateOfBirth = intent.getStringExtra("dateOfBirth")
                val intentDateOfExpiry = intent.getStringExtra("dateOfExpiry")

                if (!isEmpty(intentPassportNumber) && !isEmpty(intentDateOfBirth) && !isEmpty(intentDateOfExpiry)) {
                    passportNumber = intentPassportNumber
                    dateOfBirth = intentDateOfBirth
                    dateOfExpiry = intentDateOfExpiry

                    // Save to SharedPreferences for future use
                    val editor = prefs.edit()
                    editor.putString("passportNumber", passportNumber)
                    editor.putString("dateOfBirth", dateOfBirth)
                    editor.putString("dateOfExpiry", dateOfExpiry)
                    editor.apply()
                }
            }
        }

        // Check if MRZ data is available
        if (!isMRZDataValid()) {
            showMRZInputFragment()
            return
        }

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
            ReadPassportTask().execute(tag)
        }
    }

    private inner class ReadPassportTask : AsyncTask<Tag, Void, PassportData?>() {
        override fun onPreExecute() {
            super.onPreExecute()
            // Show simple progress bar
            progressBar?.visibility = View.VISIBLE
            resultContainer?.visibility = View.GONE
        }

        override fun doInBackground(vararg params: Tag): PassportData? {
            if (params.isEmpty()) {
                return null
            }
            val tag = params[0]
            return readPassport(tag)
        }

        override fun onPostExecute(passportData: PassportData?) {
            super.onPostExecute(passportData)

            if (passportData != null) {

                // Hide progress bar
                progressBar?.visibility = View.GONE

                // Check if verification was successful
                val verificationSuccessful = isVerificationSuccessful(passportData)

                // Navigate based on result
                if (verificationSuccessful) {
                    clearMRZData()
                    navigateBackToANML()
                } else {
                    val failureReason = getFailureReason(passportData)
                    val failureDetails = getFailureDetails(passportData)
                    navigateToFailureScreen(failureReason, failureDetails)
                }
            } else {
                // Hide progress bar
                progressBar?.visibility = View.GONE

                // Navigate to failure screen
                navigateToFailureScreen(
                    "Failed to read passport",
                    "Please ensure your passport is placed correctly on the back of your device and try again."
                )
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
            return false
        }

        // Check HTTP response code
        if (httpCode < 200 || httpCode >= 300) {
            return false
        }

        // Check if passive authentication passed
        val passiveAuthPassed = passportData.passiveAuthenticationPassed
        if (passiveAuthPassed == true) {
            return true
        }

        return false
    }

    /**
     * Navigate back to ANML main page
     */
    private fun navigateBackToANML() {
        if (activity is network.erth.wallet.ui.host.HostActivity) {
            val hostActivity = activity as network.erth.wallet.ui.host.HostActivity
            // Navigate to ANML page
            hostActivity.showFragment("anml")
        }
    }

    /**
     * Navigate to failure screen with specific reason and details
     */
    private fun navigateToFailureScreen(reason: String, details: String) {
        if (activity is network.erth.wallet.ui.host.HostActivity) {
            val hostActivity = activity as network.erth.wallet.ui.host.HostActivity

            // Create failure fragment with details
            val bundle = Bundle()
            bundle.putString("failure_reason", reason)
            bundle.putString("failure_details", details)


            // Navigate with arguments
            hostActivity.showFragment("scan_failure", bundle)
        }
    }

    /**
     * Get human-readable failure reason based on passport data
     */
    private fun getFailureReason(passportData: PassportData?): String {
        if (passportData == null) {
            return "Failed to read passport"
        }

        val httpCode = passportData.backendHttpCode
        if (httpCode == null) {
            return "Backend server not connected"
        }

        // Check if we have a specific failure reason from backend
        val backendFailureReason = passportData.failureReason
        if (!backendFailureReason.isNullOrEmpty() && httpCode in 200..299) {
            return backendFailureReason
        }

        // Check specific HTTP error codes
        return when {
            httpCode >= 500 -> "Backend server error ($httpCode)"
            httpCode >= 400 -> "Backend request failed ($httpCode)"
            httpCode < 200 || httpCode >= 300 -> "Backend verification failed ($httpCode)"
            else -> {
                val passiveAuthPassed = passportData.passiveAuthenticationPassed
                if (passiveAuthPassed != true) {
                    "Passport authentication failed"
                } else {
                    "Verification failed"
                }
            }
        }
    }

    /**
     * Get detailed failure explanation based on passport data
     */
    private fun getFailureDetails(passportData: PassportData?): String {
        if (passportData == null) {
            return "Could not establish NFC connection with your passport. Please ensure your passport is placed correctly on the back of your device and try again."
        }

        val httpCode = passportData.backendHttpCode
        val rawResponse = passportData.backendRawResponse

        if (httpCode == null) {
            return "The verification server is not reachable. This could be due to:\n• No internet connection\n• Server is offline\n• Network firewall blocking connection\n\nPlease check your connection and try again."
        }

        // Show specific HTTP error details
        var details = when {
            httpCode >= 500 -> "The verification server is experiencing internal errors. Please try again in a few minutes."
            httpCode == 404 -> "The verification endpoint was not found. The server may be misconfigured."
            httpCode == 403 -> "Access to the verification server was denied. Check server configuration."
            httpCode == 400 -> "The server rejected the passport data format. This may indicate a compatibility issue."
            httpCode >= 400 -> "The server rejected the verification request."
            httpCode < 200 || httpCode >= 300 -> "The server returned an unexpected response."
            else -> ""
        }

        // If we have a raw response, try to extract useful error info
        if (!rawResponse.isNullOrEmpty() && httpCode >= 400) {
            // Try to extract error message from JSON response
            try {
                val response = JSONObject(rawResponse)
                val error = response.optString("error", "")
                val message = response.optString("message", "")

                if (error.isNotEmpty()) {
                    details += "\n\nServer error: $error"
                }
                if (message.isNotEmpty() && message != error) {
                    details += "\nDetails: $message"
                }
            } catch (e: Exception) {
                // If we can't parse JSON, show first 200 chars of raw response
                if (rawResponse.length > 200) {
                    details += "\n\nServer response: ${rawResponse.substring(0, 200)}..."
                } else {
                    details += "\n\nServer response: $rawResponse"
                }
            }
        }

        if (httpCode in 200..299) {
            val passiveAuthPassed = passportData.passiveAuthenticationPassed
            if (passiveAuthPassed != true) {
                // Use backend failure reason if available, otherwise show generic message
                val backendFailureReason = passportData.failureReason
                if (!backendFailureReason.isNullOrEmpty()) {
                    details = backendFailureReason
                } else {
                    details = "Your passport could not be authenticated. This could mean:\n• The passport data is corrupted\n• Unsupported passport type\n• Security verification failed\n\nTry scanning again or contact support if the issue persists."
                }
            } else {
                details = "The verification process completed but failed for an unknown reason. Please try again."
            }
        }

        return if (details.isEmpty()) "Please try again or contact support if the problem persists." else details
    }

    // Fragment handling methods
    private fun showMRZInputFragment() {
        val fragment = MRZInputFragment.newInstance()
        fragment.setMRZInputListener(this)

        val fm = childFragmentManager
        val ft = fm.beginTransaction()
        ft.replace(R.id.passport_scanner_content, fragment, "mrz_input")
        ft.commit()
    }

    override fun onMRZDataEntered(passportNumber: String, dateOfBirth: String, dateOfExpiry: String) {
        // Update MRZ data and remove fragment
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

        // Remove fragment and show main scanning UI
        val fm = childFragmentManager
        val fragment = fm.findFragmentByTag("mrz_input")
        if (fragment != null) {
            val ft = fm.beginTransaction()
            ft.remove(fragment)
            ft.commit()
        }

        // Request NFC setup from parent activity
        listener?.requestNFCSetup()
    }

    // All the passport reading logic remains the same
    private fun readPassport(tag: Tag): PassportData? {
        return try {
            val isoDep = IsoDep.get(tag)
            if (isoDep == null) {
                return null
            }
            isoDep.connect()
            isoDep.timeout = 5000

            val cardService = CardService.getInstance(isoDep)
            cardService.open()

            val passportService = PassportService(
                cardService,
                PassportService.NORMAL_MAX_TRANCEIVE_LENGTH,
                PassportService.DEFAULT_MAX_BLOCKSIZE,
                false,
                false
            )
            passportService.open()

            passportService.sendSelectApplet(false)

            if (isEmpty(passportNumber) || isEmpty(dateOfBirth) || isEmpty(dateOfExpiry)) {
                Log.e(TAG, "MRZ data is incomplete, cannot create BAC key")
                return null
            }

            val documentNumber = passportNumber!!
            val birthDate = dateOfBirth!!
            val expiryDate = dateOfExpiry!!

            val bacKey: BACKeySpec = BACKey(documentNumber, birthDate, expiryDate)

            passportService.doBAC(bacKey)

            val dg1In = passportService.getInputStream(PassportService.EF_DG1)
            if (dg1In == null) {
                return null
            }
            val dg1Bytes = readAllBytes(dg1In)
            val dg1File = DG1File(ByteArrayInputStream(dg1Bytes))
            val mrzInfo = dg1File.mrzInfo

            val sodIn = passportService.getInputStream(PassportService.EF_SOD)
            val sodBytes = if (sodIn != null) {
                readAllBytes(sodIn)
            } else {
                null
            }

            val passportData = PassportData()
            if (mrzInfo != null) {
                passportData.documentNumber = mrzInfo.documentNumber
                passportData.primaryIdentifier = mrzInfo.primaryIdentifier
                passportData.secondaryIdentifier = mrzInfo.secondaryIdentifier
                passportData.nationality = mrzInfo.nationality
                passportData.dateOfBirth = mrzInfo.dateOfBirth
                passportData.gender = mrzInfo.gender.toString()
                passportData.dateOfExpiry = mrzInfo.dateOfExpiry
                passportData.issuingState = mrzInfo.issuingState
            } else {
            }

            try {
                val walletAddress = getCurrentWalletAddress()
                val verification = sendToBackend(dg1Bytes, sodBytes, walletAddress)
                if (verification != null) {
                    passportData.backendHttpCode = verification.code
                    passportData.backendRawResponse = verification.body
                    parseAndAttachVerification(passportData, verification.body)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending data to backend", e)
            }

            try {
                passportService.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing passport service", e)
            }
            try {
                cardService.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing card service", e)
            }
            try {
                isoDep.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing ISO-DEP", e)
            }

            passportData
        } catch (e: Exception) {
            Log.e(TAG, "Error reading passport", e)
            null
        }
    }

    @Throws(IOException::class)
    private fun readAllBytes(`in`: InputStream): ByteArray {
        val buffer = ByteArrayOutputStream()
        val tmp = ByteArray(4096)
        var n: Int
        while (`in`.read(tmp).also { n = it } != -1) {
            buffer.write(tmp, 0, n)
        }
        try {
            `in`.close()
        } catch (ignored: Exception) {
        }
        return buffer.toByteArray()
    }

    @Throws(Exception::class)
    private fun sendToBackend(dg1Bytes: ByteArray?, sodBytes: ByteArray?, address: String?): BackendResult? {
        if (dg1Bytes == null) {
            throw IllegalArgumentException("dg1Bytes is required")
        }
        if (backendUrl.isBlank()) {
            return null
        }

        val dg1B64 = android.util.Base64.encodeToString(dg1Bytes, android.util.Base64.NO_WRAP)
        val sodB64 = if (sodBytes != null) {
            android.util.Base64.encodeToString(sodBytes, android.util.Base64.NO_WRAP)
        } else {
            ""
        }

        val payload = JSONObject()
        payload.put("dg1", dg1B64)
        payload.put("sod", sodB64)
        payload.put("address", address ?: "")


        val url = URL(backendUrl)
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 20000
            conn.readTimeout = 30000
            conn.doOutput = true
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")

            val out = payload.toString().toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(out.size)
            conn.connect()

            val os = conn.outputStream
            os.write(out)
            os.flush()
            os.close()

            val responseCode = conn.responseCode

            val responseStream = if (responseCode >= 400) conn.errorStream else conn.inputStream
            var responseBody = ""
            if (responseStream != null) {
                val respBytes = readAllBytes(responseStream)
                responseBody = String(respBytes, Charsets.UTF_8)
            }


            val result = BackendResult()
            result.code = responseCode
            result.body = responseBody
            result.ok = responseCode in 200..299
            result
        } finally {
            conn.disconnect()
        }
    }

    private fun parseAndAttachVerification(pd: PassportData?, jsonBody: String?) {
        if (pd == null || jsonBody == null) return
        try {
            val root = JSONObject(jsonBody)
            pd.passiveAuthenticationPassed = root.optBoolean("passive_authentication_passed", false)
            pd.failureReason = root.optString("failure_reason", "").takeIf { it.isNotEmpty() }
            val details = root.optJSONObject("details")
            if (details != null) {
                val trust = details.optJSONObject("trust_chain")
                if (trust != null) {
                    pd.trustChainStatus = trust.optString("status", "")
                    pd.trustChainFailureReason = trust.optString("failure_reason", "")
                }
                val sodSig = details.optJSONObject("sod_signature")
                if (sodSig != null) {
                    pd.sodSignatureStatus = sodSig.optString("status", "")
                }
                val dg1 = details.optJSONObject("dg1_hash_integrity")
                if (dg1 != null) {
                    pd.dg1IntegrityStatus = dg1.optString("status", "")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse backend JSON", e)
        }
    }

    private fun showBackendUrlDialog() {
        val input = EditText(requireContext())
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        input.hint = "https://host/verify or http://<LAN>:<port>/verify"
        input.setText(backendUrl)

        AlertDialog.Builder(requireContext())
            .setTitle("Set Backend URL")
            .setMessage("This app will POST DG1, SOD Base64, and wallet address to this endpoint.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                backendUrl = input.text?.toString()?.trim() ?: ""
                val appPrefs = requireActivity().getSharedPreferences("app_prefs", Activity.MODE_PRIVATE)
                appPrefs.edit().putString("backend_url", backendUrl).apply()
                Toast.makeText(requireContext(), "Backend URL saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun formatDate(date: String?): String {
        if (date == null) {
            return ""
        }
        return try {
            val inputFormat = SimpleDateFormat("yyMMdd", Locale.getDefault())
            val outputFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val parsedDate = inputFormat.parse(date)
            if (parsedDate != null) {
                outputFormat.format(parsedDate)
            } else {
                date
            }
        } catch (e: ParseException) {
            date
        }
    }

    private fun isEmpty(str: String?): Boolean {
        return str.isNullOrBlank()
    }

    private fun isMRZDataValid(): Boolean {
        return !isEmpty(passportNumber) && !isEmpty(dateOfBirth) && !isEmpty(dateOfExpiry)
    }

    private fun getCurrentWalletAddress(): String? {
        return try {
            val address = SecureWalletManager.getWalletAddress(requireContext())
            address
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Clear MRZ data from SharedPreferences and memory for privacy/security
     */
    private fun clearMRZData() {

        // Clear from memory
        passportNumber = null
        dateOfBirth = null
        dateOfExpiry = null

        // Clear from SharedPreferences
        val prefs = requireActivity().getSharedPreferences("mrz_data", Activity.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.remove("passportNumber")
        editor.remove("dateOfBirth")
        editor.remove("dateOfExpiry")
        editor.apply()

    }

    // Backend HTTP result holder
    private class BackendResult {
        var code: Int = 0
        var body: String = ""
        var ok: Boolean = false
    }

    // Simple class to hold passport data
    private class PassportData {
        var documentNumber: String? = null
        var primaryIdentifier: String? = null
        var secondaryIdentifier: String? = null
        var nationality: String? = null
        var dateOfBirth: String? = null
        var gender: String? = null
        var dateOfExpiry: String? = null
        var issuingState: String? = null

        // Backend verification fields
        var backendHttpCode: Int? = null
        var backendRawResponse: String? = null
        var passiveAuthenticationPassed: Boolean? = null
        var failureReason: String? = null  // Human readable error from backend
        var trustChainStatus: String? = null
        var trustChainFailureReason: String? = null
        var sodSignatureStatus: String? = null
        var dg1IntegrityStatus: String? = null
    }

    override fun onDestroy() {
        super.onDestroy()
        // No special cleanup needed for simple progress bar
    }
}