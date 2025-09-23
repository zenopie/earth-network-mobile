package com.example.earthwallet.ui.pages.wallet

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.earthwallet.R
import com.example.earthwallet.wallet.services.SecureWalletManager
import com.google.zxing.BarcodeFormat
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter

/**
 * ReceiveTokensFragment
 *
 * Handles receiving tokens by displaying wallet address and QR code:
 * - Shows the current wallet address
 * - Provides copy-to-clipboard functionality
 * - Generates QR code for easy sharing
 * - Works for both native SCRT and SNIP-20 tokens (same address)
 */
class ReceiveTokensFragment : Fragment() {

    companion object {
        private const val TAG = "ReceiveTokensFragment"
        private const val PREF_FILE = "secret_wallet_prefs"
    }

    // UI Components
    private var addressText: TextView? = null
    private var instructionsText: TextView? = null
    private var copyButton: Button? = null
    private var qrCodeView: ImageView? = null

    // Interface for communication with parent
    interface ReceiveTokensListener {
        fun getCurrentWalletAddress(): String
    }

    private var listener: ReceiveTokensListener? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = when {
            parentFragment is ReceiveTokensListener -> parentFragment as ReceiveTokensListener
            context is ReceiveTokensListener -> context
            else -> {
                null
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_receive_tokens, container, false)

        // Initialize UI components
        addressText = view.findViewById(R.id.addressText)
        instructionsText = view.findViewById(R.id.instructionsText)
        copyButton = view.findViewById(R.id.copyButton)
        qrCodeView = view.findViewById(R.id.qrCodeView)

        setupClickListeners()

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        updateWalletAddress()
    }

    private fun setupClickListeners() {
        copyButton?.setOnClickListener { copyAddressToClipboard() }

        // Also allow clicking on the address text to copy
        addressText?.setOnClickListener { copyAddressToClipboard() }
    }

    private fun updateWalletAddress() {
        try {
            val address = SecureWalletManager.getWalletAddress(requireContext())
            if (!TextUtils.isEmpty(address)) {
                addressText?.text = address
                generateQRCode(address!!)
            } else {
                addressText?.text = "No wallet address available"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get wallet address", e)
            addressText?.text = "Error loading address"
        }
    }

    private fun copyAddressToClipboard() {
        val address = addressText?.text?.toString() ?: ""
        if (!TextUtils.isEmpty(address) &&
            address != "No wallet address available" &&
            address != "Wallet not available") {

            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Secret Address", address)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "Address copied to clipboard", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "No address to copy", Toast.LENGTH_SHORT).show()
        }
    }

    private fun generateQRCode(address: String) {
        if (qrCodeView != null && !TextUtils.isEmpty(address)) {
            try {
                // Generate QR code bitmap
                val qrBitmap = createQRCodeBitmap(address, 300, 300)
                if (qrBitmap != null) {
                    qrCodeView?.setImageBitmap(qrBitmap)
                    qrCodeView?.visibility = View.VISIBLE
                } else {
                    qrCodeView?.visibility = View.GONE
                    Log.e(TAG, "Failed to generate QR code bitmap")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error generating QR code", e)
                qrCodeView?.visibility = View.GONE
            }
        }
    }

    private fun createQRCodeBitmap(content: String, width: Int, height: Int): Bitmap? {
        return try {
            val qrCodeWriter = QRCodeWriter()
            val bitMatrix = qrCodeWriter.encode(content, BarcodeFormat.QR_CODE, width, height)

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            bitmap
        } catch (e: WriterException) {
            Log.e(TAG, "Error creating QR code", e)
            null
        }
    }

    /**
     * Public method to refresh the displayed address
     */
    fun refreshAddress() {
        updateWalletAddress()
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
    }
}