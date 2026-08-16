package network.erth.wallet.ui.pages.managelp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.erth.wallet.R
import network.erth.wallet.chain.Bank
import network.erth.wallet.chain.Dex
import network.erth.wallet.chain.EarthTx
import network.erth.wallet.wallet.constants.Tokens
import network.erth.wallet.wallet.services.EarthWallet
import network.erth.wallet.wallet.services.SecureWalletManager
import java.text.DecimalFormat

/**
 * Remove liquidity from an earth x/dex pool. LP shares are the plain bank denom
 * "dexlp/{poolId}", so the user's share balance is a bank query and removal is a
 * single MsgRemoveLiquidity (no LP bonding/unbonding on earth).
 */
class RemoveLiquidityFragment : Fragment() {

    companion object {
        private const val TAG = "RemoveLiquidityFragment"

        @JvmStatic
        fun newInstance(tokenKey: String): RemoveLiquidityFragment {
            val fragment = RemoveLiquidityFragment()
            fragment.arguments = Bundle().apply { putString("token_key", tokenKey) }
            return fragment
        }
    }

    private lateinit var removeAmountInput: EditText
    private lateinit var stakedSharesText: TextView
    private lateinit var sharesMaxButton: Button
    private lateinit var removeLiquidityButton: Button

    private var tokenKey: String? = null
    private var userShares = 0.0
    private var poolId: Long? = null
    private var currentWalletAddress = ""
    private var transactionSuccessReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tokenKey = arguments?.getString("token_key")
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.tab_liquidity_remove, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initializeViews(view)
        setupListeners()
        setupBroadcastReceiver()
        registerBroadcastReceiver()
        loadCurrentWalletAddress()
        loadUserShares()
    }

    private fun initializeViews(view: View) {
        removeAmountInput = view.findViewById(R.id.remove_amount_input)
        stakedSharesText = view.findViewById(R.id.staked_shares_text)
        sharesMaxButton = view.findViewById(R.id.shares_max_button)
        removeLiquidityButton = view.findViewById(R.id.remove_liquidity_button)
    }

    private fun setupBroadcastReceiver() {
        transactionSuccessReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                loadUserShares()
                Handler(Looper.getMainLooper()).postDelayed({ loadUserShares() }, 500)
            }
        }
    }

    private fun registerBroadcastReceiver() {
        if (activity != null && transactionSuccessReceiver != null) {
            val filter = IntentFilter("network.erth.wallet.TRANSACTION_SUCCESS")
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    requireActivity().applicationContext.registerReceiver(transactionSuccessReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    requireActivity().applicationContext.registerReceiver(transactionSuccessReceiver, filter)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register broadcast receiver", e)
            }
        }
    }

    private fun setupListeners() {
        sharesMaxButton.setOnClickListener {
            if (userShares > 0) removeAmountInput.setText(userShares.toString())
        }
        removeLiquidityButton.setOnClickListener {
            val removeAmountStr = removeAmountInput.text.toString().trim()
            val removeAmount = removeAmountStr.toDoubleOrNull()
            if (removeAmount == null || removeAmount <= 0) {
                Toast.makeText(context, "Please enter an amount to remove", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (removeAmount > userShares) {
                Toast.makeText(context, "Amount exceeds your shares", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            executeRemoveLiquidity(removeAmountStr)
        }
    }

    private fun loadCurrentWalletAddress() {
        currentWalletAddress = try {
            SecureWalletManager.getWalletAddress(requireContext()) ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load wallet address", e)
            ""
        }
    }

    private fun loadUserShares() {
        val token = tokenKey
        val info = token?.let { Tokens.getTokenInfo(it) }
        if (info == null || TextUtils.isEmpty(currentWalletAddress)) {
            stakedSharesText.text = "Balance: Connect wallet"
            return
        }
        lifecycleScope.launch {
            try {
                val (id, raw) = withContext(Dispatchers.IO) {
                    val p = Dex.poolForToken(info.denom)
                    val shareRaw = if (p != null) Bank.balance(currentWalletAddress, lpDenom(p.id)) else "0"
                    (p?.id) to shareRaw
                }
                poolId = id
                userShares = raw.toDouble() / 1_000_000.0
                updateSharesDisplay()
            } catch (e: Exception) {
                Log.e(TAG, "Error loading user shares", e)
                stakedSharesText.text = "Balance: Error"
            }
        }
    }

    private fun updateSharesDisplay() {
        val df = DecimalFormat("#.######")
        stakedSharesText.text = "Balance: ${df.format(userShares)}"
        sharesMaxButton.visibility = if (userShares > 0) View.VISIBLE else View.GONE
    }

    private fun executeRemoveLiquidity(removeAmountStr: String) {
        val id = poolId
        if (id == null) {
            Toast.makeText(context, "Pool not found", Toast.LENGTH_SHORT).show()
            return
        }
        // LP shares carry 6 decimals like other earth denoms.
        val sharesMicro = Tokens.parseTokenAmount(removeAmountStr, "ERTH") ?: return

        removeLiquidityButton.isEnabled = false
        lifecycleScope.launch {
            try {
                val txHash = withContext(Dispatchers.IO) {
                    SecureWalletManager.executeWithMnemonic(requireContext()) { mnemonic ->
                        val key = EarthWallet.deriveKey(mnemonic)
                        val creator = EarthWallet.address(key)
                        EarthTx.broadcast(
                            key,
                            listOf(Dex.msgRemoveLiquidity(creator, id, lpDenom(id), sharesMicro.toString()))
                        )
                    }
                }
                Log.i(TAG, "Remove liquidity broadcast: $txHash")
                Toast.makeText(context, "Liquidity removed", Toast.LENGTH_SHORT).show()
                removeAmountInput.setText("")
                loadUserShares()
            } catch (e: Exception) {
                Log.e(TAG, "Remove liquidity failed", e)
                Toast.makeText(context, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                removeLiquidityButton.isEnabled = true
            }
        }
    }

    private fun lpDenom(id: Long) = "dexlp/$id"

    override fun onResume() {
        super.onResume()
        loadUserShares()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (transactionSuccessReceiver != null && context != null) {
            try {
                requireActivity().applicationContext.unregisterReceiver(transactionSuccessReceiver)
            } catch (e: IllegalArgumentException) {
                // Receiver was not registered, ignore
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering receiver", e)
            }
        }
    }
}
