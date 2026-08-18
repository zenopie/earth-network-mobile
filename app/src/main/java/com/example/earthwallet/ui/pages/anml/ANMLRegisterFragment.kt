package network.erth.wallet.ui.pages.anml

import network.erth.wallet.ui.components.TxResult
import network.erth.wallet.R
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
import androidx.activity.result.ActivityResultLauncher
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import network.erth.wallet.wallet.services.ErthPriceService

class ANMLRegisterFragment : Fragment() {

    companion object {
        private const val TAG = "ANMLRegisterFragment"

        @JvmStatic
        fun newInstance(): ANMLRegisterFragment = ANMLRegisterFragment()

        @JvmStatic
        fun newInstance(registrationReward: String): ANMLRegisterFragment {
            return ANMLRegisterFragment().apply {
                arguments = Bundle().apply {
                    putString("registration_reward", registrationReward)
                }
            }
        }
    }

    // Interface for communication with parent activity
    interface ANMLRegisterListener {
        fun onRegisterRequested()
    }

    private var listener: ANMLRegisterListener? = null
    private var registrationReward: String? = null
    private var rewardAmountText: TextView? = null
    private var affiliateAddressInput: EditText? = null
    private lateinit var qrScannerLauncher: ActivityResultLauncher<ScanOptions>
    private var currentErthPrice: Double? = null

    fun setANMLRegisterListener(listener: ANMLRegisterListener) {
        this.listener = listener
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Initialize QR scanner launcher
        qrScannerLauncher = registerForActivityResult(ScanContract()) { result ->
            result.contents?.let { handleQRScanResult(it) }
        }

        return inflater.inflate(R.layout.fragment_anml_register, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Get registration reward from arguments
        arguments?.let { args ->
            registrationReward = args.getString("registration_reward")
        }

        rewardAmountText = view.findViewById(R.id.registration_reward_amount)
        affiliateAddressInput = view.findViewById(R.id.affiliate_address_input)
        updateRewardDisplay()

        // Fetch ERTH price when fragment is created
        fetchErthPriceAndUpdateDisplay()

        val scanQrButton = view.findViewById<ImageButton>(R.id.scan_affiliate_qr_button)
        scanQrButton?.setOnClickListener { launchQRScanner() }

        val btnOpenWallet = view.findViewById<Button>(R.id.btn_open_wallet)
        btnOpenWallet?.let { button ->
            // Ensure any theme tinting is cleared so the drawable renders as-designed
            try {
                button.backgroundTintList = null
                button.setTextColor(resources.getColor(R.color.anml_button_text, null))
            } catch (ignored: Exception) {
            }

            button.setOnClickListener {
                listener?.onRegisterRequested()
            }
        }
    }

    private fun updateRewardDisplay() {
        val rewardText = rewardAmountText ?: return

        try {
            val reward = registrationReward
            if (reward.isNullOrEmpty()) {
                rewardText.text = "Loading..."
                return
            }

            // Convert from micro units (1,000,000 per token) to macro units
            val microPoolAmount = BigDecimal(reward)
            val macroPoolAmount = microPoolAmount.divide(BigDecimal("1000000"), 8, RoundingMode.DOWN)

            // Calculate 1% of the registration reward pool (user's actual reward)
            val actualReward = macroPoolAmount.multiply(BigDecimal("0.01")).setScale(6, RoundingMode.HALF_UP)

            // Format for display - round to 2 decimal places
            val df = DecimalFormat("#,##0.##")
            var rewardDisplay = "${df.format(actualReward)} ERTH"

            // Add USD value if ERTH price is available
            currentErthPrice?.let { price ->
                if (price > 0) {
                    val usdValue = actualReward.multiply(BigDecimal(price.toString()))
                    val usdFormat = DecimalFormat("$#,##0.##")
                    val usdDisplay = usdFormat.format(usdValue)
                    rewardDisplay += " ($usdDisplay)"
                } else {
                }
            }

            rewardText.text = rewardDisplay

        } catch (e: Exception) {
            rewardText.text = "Error calculating reward"
        }
    }

    fun setRegistrationReward(registrationReward: String) {
        this.registrationReward = registrationReward
        updateRewardDisplay()
    }

    private fun fetchErthPriceAndUpdateDisplay() {
        // Price is derived on-chain from the ERTH/USDC pool (see ErthPriceService).
        lifecycleScope.launch {
            val price = withContext(Dispatchers.IO) { ErthPriceService.fetchErthPrice() }
            if (price != null && price > 0) {
                currentErthPrice = price
                updateRewardDisplay()
            }
        }
    }

    private fun launchQRScanner() {
        val options = ScanOptions().apply {
            setPrompt("Scan QR code to get affiliate address")
            setBeepEnabled(true)
            setBarcodeImageEnabled(true)
            setOrientationLocked(true)
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setCameraId(0) // Use rear camera
        }

        qrScannerLauncher.launch(options)
    }

    private fun handleQRScanResult(scannedContent: String) {
        val content = scannedContent.trim()

        // Validate that the scanned content is a valid earth address
        if (content.startsWith("earth1") && content.length >= 44) {
            affiliateAddressInput?.setText(content)
        } else {
            TxResult.message(requireContext(), "Couldn't continue", "Invalid earth address in QR code")
        }
    }

    fun getAffiliateAddress(): String? {
        return affiliateAddressInput?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }
}