package network.erth.wallet.ui.pages.anml

import network.erth.wallet.R
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import org.json.JSONObject

class TestVerifyResultFragment : Fragment() {

    companion object {
        private const val TAG = "TestVerifyResultFragment"

        @JvmStatic
        fun newInstance(): TestVerifyResultFragment = TestVerifyResultFragment()
    }

    private var jsonResponseText: TextView? = null
    private var backButton: Button? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_test_verify_result, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize UI elements
        jsonResponseText = view.findViewById(R.id.json_response_text)
        backButton = view.findViewById(R.id.back_button)

        // Get JSON response from arguments
        val jsonResponse = arguments?.getString("json_response") ?: "{}"

        // Format and display the JSON
        val formattedJson = formatJson(jsonResponse)
        jsonResponseText?.text = formattedJson

        // Set up back button
        backButton?.setOnClickListener {
            navigateBackToScanner()
        }
    }

    /**
     * Format JSON string for better readability
     */
    private fun formatJson(jsonString: String): String {
        return try {
            val jsonObject = JSONObject(jsonString)
            jsonObject.toString(2) // Indent with 2 spaces
        } catch (e: Exception) {
            // If JSON parsing fails, return the raw string
            jsonString
        }
    }

    /**
     * Navigate back to the scanner fragment
     */
    private fun navigateBackToScanner() {
        if (activity is network.erth.wallet.ui.host.HostActivity) {
            val hostActivity = activity as network.erth.wallet.ui.host.HostActivity
            hostActivity.showFragment("scanner")
        }
    }
}
