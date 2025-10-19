package network.erth.wallet.wallet.services

import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import io.eqoty.cosmwasm.std.types.Coin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import network.erth.wallet.ui.components.PinEntryDialog
import network.erth.wallet.ui.components.StatusModal
import network.erth.wallet.ui.components.TransactionConfirmationDialog
import network.erth.wallet.wallet.utils.BiometricAuthManager
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * TransactionExecutor - handles the complete transaction flow with secure mnemonic handling
 *
 * This service manages:
 * 1. Transaction confirmation dialog
 * 2. Biometric/PIN authentication
 * 3. Transaction execution (via SecretKClient) with just-in-time mnemonic access
 * 4. Status modal (loading/success/error)
 *
 * SECURITY: Mnemonics are accessed just-in-time and cleared immediately after use
 *
 * Usage from fragments:
 * ```
 * lifecycleScope.launch {
 *     val result = TransactionExecutor.executeContract(
 *         fragment = this@MyFragment,
 *         contractAddress = Constants.STAKING_CONTRACT,
 *         message = claimMsg,
 *         codeHash = Constants.STAKING_HASH
 *     )
 *     // Handle result
 * }
 * ```
 */
object TransactionExecutor {

    private const val TAG = "TransactionExecutor"

    /**
     * Execute a contract transaction
     *
     * @param fragment The calling fragment (for context and lifecycle)
     * @param contractAddress The contract address to execute
     * @param message The message to send to the contract (as JSONObject)
     * @param codeHash Optional code hash for the contract
     * @param sentFunds Funds to send with the transaction (default empty)
     * @param gasLimit Gas limit for the transaction (default 200,000)
     * @param contractLabel Label to show in confirmation dialog (default "Contract:")
     * @return Result with transaction response or error
     */
    suspend fun executeContract(
        fragment: Fragment,
        contractAddress: String,
        message: JSONObject,
        codeHash: String? = null,
        sentFunds: List<Coin> = emptyList(),
        gasLimit: Int = 200_000,
        contractLabel: String = "Contract:"
    ): Result<String> = withContext(Dispatchers.Main) {
        val activity = fragment.requireActivity()
        val statusModal = StatusModal(activity)

        try {
            // Step 1: Show confirmation dialog with actual contract message
            val details = TransactionConfirmationDialog.TransactionDetails(
                contractAddress = contractAddress,
                message = message.toString()
            ).setContractLabel(contractLabel)

            val confirmed = showConfirmationDialog(activity, details)
            if (!confirmed) {
                return@withContext Result.failure(Exception("Transaction cancelled by user"))
            }

            // Step 2: Handle authentication
            val authResult = authenticateTransaction(activity)
            if (!authResult) {
                return@withContext Result.failure(Exception("Authentication failed"))
            }

            // Step 3: Show loading modal
            statusModal.show(StatusModal.State.LOADING)

            // Step 4: Execute transaction with just-in-time mnemonic access
            // SECURITY: Mnemonic is only accessed within the executeWithSuspendMnemonic callback
            // and is immediately cleared after use
            val result = withContext(Dispatchers.IO) {
                try {
                    SecureWalletManager.executeWithSuspendMnemonic(activity) { mnemonic ->
                        // Mnemonic only exists in this scope and is cleared after
                        SecretKClient.executeContract(
                            mnemonic = mnemonic,
                            contractAddress = contractAddress,
                            handleMsg = message.toString(),
                            sentFunds = sentFunds,
                            codeHash = codeHash,
                            gasLimit = gasLimit
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Transaction execution failed", e)
                    throw e
                }
            }

            // Step 5: Show success modal (auto-closes after 1.5s)
            statusModal.updateState(StatusModal.State.SUCCESS)

            Result.success(result)

        } catch (e: Throwable) {
            Log.e(TAG, "Transaction failed", e)

            // Show error modal (auto-closes after 1.5s)
            statusModal.updateState(StatusModal.State.ERROR)

            Result.failure(Exception(e.message ?: "Transaction failed", e))
        }
    }

    /**
     * Execute multiple contract messages in a single transaction
     *
     * @param fragment The calling fragment (for context and lifecycle)
     * @param messages List of contract messages to execute
     * @param memo Optional transaction memo
     * @param gasLimit Gas limit for the transaction (default 300,000)
     * @param contractLabel Label to show in confirmation dialog (default "Multi-Message:")
     * @return Result with transaction response or error
     */
    suspend fun executeMultipleContracts(
        fragment: Fragment,
        messages: List<SecretKClient.ContractMessage>,
        memo: String = "",
        gasLimit: Int = 300_000,
        contractLabel: String = "Multi-Message:"
    ): Result<String> = withContext(Dispatchers.Main) {
        val activity = fragment.requireActivity()
        val statusModal = StatusModal(activity)

        try {
            // Build confirmation message showing all contracts
            val messagePreview = buildString {
                appendLine("${messages.size} messages:")
                messages.forEachIndexed { index, msg ->
                    appendLine("${index + 1}. ${msg.contractAddress}")
                }
                if (memo.isNotEmpty()) {
                    appendLine("\nMemo: $memo")
                }
            }

            // Show confirmation dialog
            val details = TransactionConfirmationDialog.TransactionDetails(
                contractAddress = "${messages.size} contracts",
                message = messagePreview
            ).setContractLabel(contractLabel)

            val confirmed = showConfirmationDialog(activity, details)
            if (!confirmed) {
                return@withContext Result.failure(Exception("Transaction cancelled by user"))
            }

            // Authenticate
            val authResult = authenticateTransaction(activity)
            if (!authResult) {
                return@withContext Result.failure(Exception("Authentication failed"))
            }

            // Show loading modal
            statusModal.show(StatusModal.State.LOADING)

            // Execute multi-message transaction
            val result = withContext(Dispatchers.IO) {
                try {
                    SecureWalletManager.executeWithSuspendMnemonic(activity) { mnemonic ->
                        SecretKClient.executeMultipleContracts(
                            mnemonic = mnemonic,
                            messages = messages,
                            memo = memo,
                            gasLimit = gasLimit
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Multi-contract execution failed", e)
                    throw e
                }
            }

            // Show success modal (auto-closes after 1.5s)
            statusModal.updateState(StatusModal.State.SUCCESS)

            Result.success(result)

        } catch (e: Exception) {
            Log.e(TAG, "Multi-contract transaction failed", e)

            // Show error modal (auto-closes after 1.5s)
            statusModal.updateState(StatusModal.State.ERROR)

            Result.failure(Exception(e.message ?: "Multi-contract transaction failed", e))
        }
    }

    /**
     * Send SNIP-20 tokens to a recipient address or contract with an optional message
     *
     * @param fragment The calling fragment
     * @param tokenContract The SNIP-20 token contract address
     * @param tokenHash The SNIP-20 token code hash
     * @param recipient The recipient address or contract
     * @param recipientHash Optional code hash if sending to a contract
     * @param amount The amount to send (in micro units as string)
     * @param message Optional message to send to the recipient contract
     * @param gasLimit Gas limit for the transaction (default 200,000)
     * @return Result with transaction response or error
     */
    suspend fun sendSnip20Token(
        fragment: Fragment,
        tokenContract: String,
        tokenHash: String,
        recipient: String,
        recipientHash: String? = null,
        amount: String,
        message: JSONObject? = null,
        gasLimit: Int = 200_000
    ): Result<String> {
        // Build SNIP-20 send message
        val sendMsg = JSONObject()
        val sendData = JSONObject().apply {
            put("recipient", recipient)
            if (recipientHash != null) {
                put("code_hash", recipientHash)
            }
            put("amount", amount)
            if (message != null) {
                // Encode message to base64
                val encodedMessage = Base64.encodeToString(message.toString().toByteArray(), Base64.NO_WRAP)
                put("msg", encodedMessage)
            }
        }
        sendMsg.put("send", sendData)

        // Execute on the token contract
        return executeContract(
            fragment = fragment,
            contractAddress = tokenContract,
            message = sendMsg,
            codeHash = tokenHash,
            gasLimit = gasLimit,
            contractLabel = "Send Tokens:"
        )
    }

    /**
     * Send native SCRT tokens
     *
     * @param fragment The calling fragment
     * @param toAddress The recipient address
     * @param amount The amount to send in micro units (uscrt)
     * @param gasLimit Gas limit for the transaction (default 50,000)
     * @return Result with transaction response or error
     */
    suspend fun sendNativeToken(
        fragment: Fragment,
        toAddress: String,
        amount: Long,
        gasLimit: Int = 50_000
    ): Result<String> = withContext(Dispatchers.Main) {
        val activity = fragment.requireActivity()
        val statusModal = StatusModal(activity)

        try {
            // Show confirmation dialog
            val details = TransactionConfirmationDialog.TransactionDetails(
                contractAddress = toAddress,
                message = "Amount: ${amount / 1_000_000.0} SCRT"
            ).setContractLabel("Send to:")

            val confirmed = showConfirmationDialog(activity, details)
            if (!confirmed) {
                return@withContext Result.failure(Exception("Transaction cancelled by user"))
            }

            // Authenticate
            val authResult = authenticateTransaction(activity)
            if (!authResult) {
                return@withContext Result.failure(Exception("Authentication failed"))
            }

            // Show loading modal
            statusModal.show(StatusModal.State.LOADING)

            // Execute transaction
            val result = withContext(Dispatchers.IO) {
                try {
                    SecureWalletManager.executeWithSuspendMnemonic(activity) { mnemonic ->
                        SecretKClient.sendTokens(
                            mnemonic = mnemonic,
                            toAddress = toAddress,
                            amount = amount,
                            denom = "uscrt",
                            gasLimit = gasLimit
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Native send failed", e)
                    throw e
                }
            }

            // Show success modal (auto-closes after 1.5s)
            statusModal.updateState(StatusModal.State.SUCCESS)

            Result.success(result)

        } catch (e: Throwable) {
            Log.e(TAG, "Native send failed", e)

            // Show error modal (auto-closes after 1.5s)
            statusModal.updateState(StatusModal.State.ERROR)

            Result.failure(Exception(e.message ?: "Transaction failed", e))
        }
    }

    /**
     * Show confirmation dialog and wait for user response
     */
    private suspend fun showConfirmationDialog(
        activity: FragmentActivity,
        details: TransactionConfirmationDialog.TransactionDetails
    ): Boolean = suspendCancellableCoroutine { continuation ->
        val dialog = TransactionConfirmationDialog(activity)

        dialog.show(details, object : TransactionConfirmationDialog.OnConfirmationListener {
            override fun onConfirmed() {
                if (continuation.isActive) {
                    continuation.resume(true)
                }
            }

            override fun onCancelled() {
                if (continuation.isActive) {
                    continuation.resume(false)
                }
            }
        })

        continuation.invokeOnCancellation {
            // Dialog will be dismissed automatically
        }
    }

    /**
     * Handle transaction authentication (biometric or PIN)
     * Returns true if authenticated, false if cancelled/failed
     */
    private suspend fun authenticateTransaction(activity: FragmentActivity): Boolean {
        // Check if authentication is required
        if (!SecureWalletManager.isTransactionAuthEnabled(activity)) {
            return true  // No auth required
        }

        // Check which auth method to use
        return if (BiometricAuthManager.shouldUseBiometricAuth(activity)) {
            authenticateWithBiometric(activity)
        } else {
            authenticateWithPin(activity)
        }
    }

    /**
     * Authenticate with biometric
     */
    private suspend fun authenticateWithBiometric(activity: FragmentActivity): Boolean =
        suspendCancellableCoroutine { continuation ->
            BiometricAuthManager.authenticateUser(
                activity = activity,
                title = "Authenticate Transaction",
                subtitle = "Use your biometric credential to authorize this transaction",
                callback = object : BiometricAuthManager.BiometricAuthCallback {
                    override fun onAuthenticationSucceeded() {
                        if (continuation.isActive) {
                            continuation.resume(true)
                        }
                    }

                    override fun onAuthenticationError(errorMessage: String) {
                        if (errorMessage == "Use PIN instead") {
                            // User chose PIN instead
                            activity.lifecycleScope.launchWhenStarted {
                                val pinResult = authenticateWithPin(activity)
                                if (continuation.isActive) {
                                    continuation.resume(pinResult)
                                }
                            }
                        } else {
                            if (errorMessage != "Authentication cancelled") {
                                Toast.makeText(activity, errorMessage, Toast.LENGTH_SHORT).show()
                            }
                            if (continuation.isActive) {
                                continuation.resume(false)
                            }
                        }
                    }

                    override fun onAuthenticationFailed() {
                        Toast.makeText(activity, "Authentication failed. Try again.", Toast.LENGTH_SHORT).show()
                        if (continuation.isActive) {
                            continuation.resume(false)
                        }
                    }
                }
            )

            continuation.invokeOnCancellation {
                // Biometric prompt will be dismissed
            }
        }

    /**
     * Authenticate with PIN
     */
    private suspend fun authenticateWithPin(activity: FragmentActivity): Boolean =
        suspendCancellableCoroutine { continuation ->
            val pinDialog = PinEntryDialog(activity)

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

                            if (SecureWalletManager.verifyPinHash(activity, pinHash)) {
                                if (continuation.isActive) {
                                    continuation.resume(true)
                                }
                            } else {
                                Toast.makeText(activity, "Invalid PIN", Toast.LENGTH_SHORT).show()
                                if (continuation.isActive) {
                                    continuation.resume(false)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error verifying PIN", e)
                            Toast.makeText(activity, "Error verifying PIN", Toast.LENGTH_SHORT).show()
                            if (continuation.isActive) {
                                continuation.resume(false)
                            }
                        }
                    }

                    override fun onPinCancelled() {
                        if (continuation.isActive) {
                            continuation.resume(false)
                        }
                    }
                }
            )

            continuation.invokeOnCancellation {
                // PIN dialog will be dismissed
            }
        }
}
