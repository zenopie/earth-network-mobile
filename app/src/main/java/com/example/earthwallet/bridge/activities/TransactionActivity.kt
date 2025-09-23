package network.erth.wallet.bridge.activities

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import network.erth.wallet.R
import network.erth.wallet.ui.components.TransactionConfirmationDialog
import network.erth.wallet.ui.components.StatusModal
import network.erth.wallet.ui.components.PinEntryDialog
import network.erth.wallet.bridge.services.SecretExecuteService
import network.erth.wallet.bridge.services.NativeSendService
import network.erth.wallet.bridge.services.MultiMessageExecuteService
import network.erth.wallet.bridge.services.SnipExecuteService
import network.erth.wallet.bridge.services.PermitSigningService
import network.erth.wallet.wallet.services.SecureWalletManager
import network.erth.wallet.wallet.utils.BiometricAuthManager
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Universal transaction activity that handles all transaction types
 * Shows confirmation dialog, executes via services, displays success animation
 */
class TransactionActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "TransactionActivity"

        // Intent extras for transaction details
        const val EXTRA_TRANSACTION_TYPE = "transaction_type"
        const val EXTRA_TRANSACTION_DETAILS = "transaction_details"
        const val EXTRA_CONTRACT_ADDRESS = "contract_address"
        const val EXTRA_CODE_HASH = "code_hash"
        const val EXTRA_EXECUTE_JSON = "execute_json"
        const val EXTRA_FUNDS = "funds"
        const val EXTRA_MEMO = "memo"
        const val EXTRA_RECIPIENT_ADDRESS = "recipient_address"
        const val EXTRA_AMOUNT = "amount"
        const val EXTRA_MESSAGES = "messages"
        const val EXTRA_TOKEN_CONTRACT = "token_contract"
        const val EXTRA_TOKEN_HASH = "token_hash"
        const val EXTRA_RECIPIENT_HASH = "recipient_hash"
        const val EXTRA_MESSAGE_JSON = "message_json"

        // Result extras
        const val EXTRA_RESULT_JSON = "result_json"
        const val EXTRA_SENDER_ADDRESS = "sender_address"
        const val EXTRA_ERROR = "error"
        const val EXTRA_FEE_GRANTER = "fee_granter"

        // Transaction types
        const val TYPE_SECRET_EXECUTE = "secret_execute"
        const val TYPE_NATIVE_SEND = "native_send"
        const val TYPE_MULTI_MESSAGE = "multi_message"
        const val TYPE_SNIP_EXECUTE = "snip_execute"
        const val TYPE_PERMIT_SIGNING = "permit_signing"
    }

    private var confirmationDialog: TransactionConfirmationDialog? = null
    private var statusModal: StatusModal? = null
    private var transactionType: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transaction)

        transactionType = intent.getStringExtra(EXTRA_TRANSACTION_TYPE)
        if (transactionType == null) {
            finishWithError("No transaction type specified")
            return
        }

        confirmationDialog = TransactionConfirmationDialog(this)
        statusModal = StatusModal(this)

        // Start transaction flow
        showConfirmationDialog()
    }

    private fun showConfirmationDialog() {
        val details = buildTransactionDetails()
        if (details == null) {
            finishWithError("Invalid transaction details")
            return
        }

        confirmationDialog?.show(details, object : TransactionConfirmationDialog.OnConfirmationListener {
            override fun onConfirmed() {
                // User confirmed - check if transaction authentication is required
                checkTransactionAuthentication()
            }

            override fun onCancelled() {
                // User cancelled
                finishWithError("Transaction cancelled")
            }
        })
    }

    private fun buildTransactionDetails(): TransactionConfirmationDialog.TransactionDetails? {
        val intent = intent

        return when (transactionType) {
            TYPE_SECRET_EXECUTE -> {
                val contractAddress = intent.getStringExtra(EXTRA_CONTRACT_ADDRESS)
                val executeJson = intent.getStringExtra(EXTRA_EXECUTE_JSON)
                if (contractAddress == null || executeJson == null) return null

                TransactionConfirmationDialog.TransactionDetails(
                    contractAddress,
                    formatJsonForDisplay(executeJson)
                ).setContractLabel(if (isSnipMessage(executeJson)) "Token Contract:" else "Contract:")
                 .setFunds(intent.getStringExtra(EXTRA_FUNDS))
                 .setMemo(intent.getStringExtra(EXTRA_MEMO))
            }

            TYPE_NATIVE_SEND -> {
                val recipient = intent.getStringExtra(EXTRA_RECIPIENT_ADDRESS)
                val amount = intent.getStringExtra(EXTRA_AMOUNT)
                if (recipient == null || amount == null) return null

                TransactionConfirmationDialog.TransactionDetails(
                    recipient,
                    "Send $amount SCRT"
                ).setContractLabel("Recipient:")
            }

            TYPE_MULTI_MESSAGE -> {
                val messages = intent.getStringExtra(EXTRA_MESSAGES)
                if (messages == null) return null

                TransactionConfirmationDialog.TransactionDetails(
                    "Multiple Messages",
                    messages
                ).setContractLabel("Messages:")
            }

            TYPE_SNIP_EXECUTE -> {
                val tokenContract = intent.getStringExtra(EXTRA_TOKEN_CONTRACT)
                val snipRecipient = intent.getStringExtra(EXTRA_RECIPIENT_ADDRESS)
                val snipRecipientHash = intent.getStringExtra(EXTRA_RECIPIENT_HASH)
                val snipAmount = intent.getStringExtra(EXTRA_AMOUNT)
                val messageJson = intent.getStringExtra(EXTRA_MESSAGE_JSON)
                if (tokenContract == null || snipRecipient == null || snipAmount == null || messageJson == null) return null

                try {
                    // Show the SNIP send structure that will be created with the actual message
                    val sendMsg = JSONObject()
                    val sendData = JSONObject()
                    sendData.put("recipient", snipRecipient)
                    if (!snipRecipientHash.isNullOrEmpty()) {
                        sendData.put("code_hash", snipRecipientHash)
                    }
                    sendData.put("amount", snipAmount)
                    sendData.put("msg", messageJson)
                    sendMsg.put("send", sendData)

                    TransactionConfirmationDialog.TransactionDetails(
                        tokenContract,
                        formatJsonForDisplay(sendMsg.toString())
                    ).setContractLabel("Token Contract:")

                } catch (e: Exception) {
                    // Fallback to simple display if JSON building fails
                    TransactionConfirmationDialog.TransactionDetails(
                        tokenContract,
                        "SNIP Execute: ${formatJsonForDisplay(messageJson)}"
                    ).setContractLabel("Token Contract:")
                }
            }

            TYPE_PERMIT_SIGNING -> {
                val permitName = intent.getStringExtra("permit_name")
                val allowedTokens = intent.getStringExtra("allowed_tokens")
                val permissions = intent.getStringExtra("permissions")
                if (permitName == null || allowedTokens == null || permissions == null) return null

                TransactionConfirmationDialog.TransactionDetails(
                    permitName,
                    "Create SNIP-24 Query Permit\n" +
                    "Tokens: ${allowedTokens.replace(",", ", ")}\n" +
                    "Permissions: ${permissions.replace(",", ", ")}"
                ).setContractLabel("Permit Name:")
            }

            else -> null
        }
    }

    private fun checkTransactionAuthentication() {
        try {
            // Check if transaction authentication is enabled
            if (!SecureWalletManager.isTransactionAuthEnabled(this)) {
                // No authentication required, proceed directly
                statusModal?.show(StatusModal.State.LOADING)
                executeTransaction()
                return
            }

            // Check if biometric authentication should be used
            if (BiometricAuthManager.shouldUseBiometricAuth(this)) {
                authenticateWithBiometric()
            } else {
                authenticateWithPin()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking transaction authentication", e)
            // If there's an error checking auth settings, proceed without auth
            statusModal?.show(StatusModal.State.LOADING)
            executeTransaction()
        }
    }

    private fun authenticateWithBiometric() {
        BiometricAuthManager.authenticateUser(
            activity = this,
            title = "Authenticate Transaction",
            subtitle = "Use your biometric credential to authorize this transaction",
            callback = object : BiometricAuthManager.BiometricAuthCallback {
                override fun onAuthenticationSucceeded() {
                    // Authentication successful, proceed with transaction
                    statusModal?.show(StatusModal.State.LOADING)
                    executeTransaction()
                }

                override fun onAuthenticationError(errorMessage: String) {
                    if (errorMessage == "Use PIN instead") {
                        // User chose to use PIN instead
                        authenticateWithPin()
                    } else if (errorMessage != "Authentication cancelled") {
                        Toast.makeText(this@TransactionActivity, errorMessage, Toast.LENGTH_SHORT).show()
                        finishWithError("Authentication failed")
                    } else {
                        // User cancelled authentication
                        finishWithError("Transaction cancelled")
                    }
                }

                override fun onAuthenticationFailed() {
                    Toast.makeText(this@TransactionActivity, "Authentication failed. Try again.", Toast.LENGTH_SHORT).show()
                    finishWithError("Authentication failed")
                }
            }
        )
    }

    private fun authenticateWithPin() {
        val pinDialog = PinEntryDialog(this)
        pinDialog.show(
            mode = PinEntryDialog.PinMode.AUTHENTICATE,
            title = "Authenticate Transaction",
            message = "Enter your PIN to authorize this transaction",
            listener = object : PinEntryDialog.PinEntryListener {
                override fun onPinEntered(pin: String) {
                    try {
                        val md = MessageDigest.getInstance("SHA-256")
                        val digest = md.digest(pin.toByteArray(StandardCharsets.UTF_8))
                        val sb = StringBuilder()
                        for (b in digest) sb.append(String.format("%02x", b))
                        val pinHash = sb.toString()

                        if (SecureWalletManager.verifyPinHash(this@TransactionActivity, pinHash)) {
                            // PIN correct, proceed with transaction
                            statusModal?.show(StatusModal.State.LOADING)
                            executeTransaction()
                        } else {
                            Toast.makeText(this@TransactionActivity, "Invalid PIN", Toast.LENGTH_SHORT).show()
                            finishWithError("Invalid PIN")
                        }
                    } catch (e: Exception) {
                        Toast.makeText(this@TransactionActivity, "Error checking PIN", Toast.LENGTH_SHORT).show()
                        finishWithError("Authentication error")
                    }
                }

                override fun onPinCancelled() {
                    finishWithError("Transaction cancelled")
                }
            }
        )
    }

    private fun executeTransaction() {
        Thread {
            try {
                val result: String
                val senderAddress: String

                when (transactionType) {
                    TYPE_SECRET_EXECUTE -> {
                        val secretResult = SecretExecuteService.execute(this, intent)
                        result = secretResult[0]
                        senderAddress = secretResult[1]
                    }

                    TYPE_NATIVE_SEND -> {
                        val nativeResult = NativeSendService.execute(this, intent)
                        result = nativeResult[0]
                        senderAddress = nativeResult[1]
                    }

                    TYPE_MULTI_MESSAGE -> {
                        val multiResult = MultiMessageExecuteService.execute(this, intent)
                        result = multiResult[0]
                        senderAddress = multiResult[1]
                    }

                    TYPE_SNIP_EXECUTE -> {
                        // Create properly mapped intent for SnipExecuteService
                        val snipIntent = Intent()
                        snipIntent.putExtra("token_contract", intent.getStringExtra(EXTRA_TOKEN_CONTRACT))
                        snipIntent.putExtra("token_hash", intent.getStringExtra(EXTRA_TOKEN_HASH))
                        snipIntent.putExtra("recipient", intent.getStringExtra(EXTRA_RECIPIENT_ADDRESS))
                        snipIntent.putExtra("recipient_hash", intent.getStringExtra(EXTRA_RECIPIENT_HASH))
                        snipIntent.putExtra("amount", intent.getStringExtra(EXTRA_AMOUNT))
                        snipIntent.putExtra("message_json", intent.getStringExtra(EXTRA_MESSAGE_JSON))

                        val snipResult = SnipExecuteService.execute(this, snipIntent)
                        result = snipResult[0]
                        senderAddress = snipResult[1]
                    }

                    TYPE_PERMIT_SIGNING -> {
                        val permitResult = PermitSigningService.execute(this, intent)
                        result = permitResult[0]
                        senderAddress = permitResult[1]
                    }

                    else -> {
                        throw Exception("Unknown transaction type: $transactionType")
                    }
                }

                // Transaction succeeded
                runOnUiThread { handleTransactionSuccess(result, senderAddress) }

            } catch (e: Exception) {
                Log.e(TAG, "Transaction failed", e)
                runOnUiThread { handleTransactionError("Transaction failed: ${e.message}") }
            }
        }.start()
    }

    private fun handleTransactionSuccess(result: String, senderAddress: String) {
        // Send immediate broadcast for any listening fragments to refresh during animation
        val broadcast = Intent("network.erth.wallet.TRANSACTION_SUCCESS")
        broadcast.putExtra("result", result)
        broadcast.putExtra("senderAddress", senderAddress)
        sendBroadcast(broadcast)

        // Show success animation
        statusModal?.updateState(StatusModal.State.SUCCESS)

        // Delay finish to allow animation to play while UI updates
        Handler(Looper.getMainLooper()).postDelayed({
            finishWithSuccess(result, senderAddress)
        }, 1600) // 1600ms to ensure animation completes
    }

    private fun handleTransactionError(error: String) {
        statusModal?.updateState(StatusModal.State.ERROR)

        // Delay finish to allow error animation to play
        Handler(Looper.getMainLooper()).postDelayed({
            finishWithError(error)
        }, 1600)
    }

    private fun finishWithSuccess(resultJson: String, senderAddress: String) {
        val result = Intent()
        result.putExtra(EXTRA_RESULT_JSON, resultJson)
        result.putExtra(EXTRA_SENDER_ADDRESS, senderAddress)
        setResult(Activity.RESULT_OK, result)
        finish()
    }

    private fun finishWithError(error: String) {
        val result = Intent()
        result.putExtra(EXTRA_ERROR, error)
        setResult(Activity.RESULT_CANCELED, result)
        finish()
    }

    private fun isSnipMessage(json: String?): Boolean {
        if (json == null) return false
        return try {
            json.contains("\"send\"")
        } catch (e: Exception) {
            false
        }
    }

    private fun truncateAddress(address: String?): String {
        if (address.isNullOrEmpty()) return ""
        if (address.length <= 20) return address
        return "${address.substring(0, 10)}...${address.substring(address.length - 6)}"
    }

    private fun formatJsonForDisplay(json: String?): String {
        if (json == null) return ""
        return try {
            val jsonObj = JSONObject(json)
            jsonObj.toString(2) // Pretty print with 2 space indentation
        } catch (e: Exception) {
            json // Return original if parsing fails
        }
    }

    override fun onDestroy() {
        confirmationDialog?.dismiss()
        statusModal?.destroy()
        super.onDestroy()
    }
}