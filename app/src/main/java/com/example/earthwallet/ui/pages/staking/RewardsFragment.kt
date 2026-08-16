package network.erth.wallet.ui.pages.staking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.protobuf.Any as ProtoAny
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.erth.wallet.R
import network.erth.wallet.chain.EarthTx
import network.erth.wallet.chain.Staking
import network.erth.wallet.wallet.services.EarthWallet
import network.erth.wallet.wallet.services.SecureWalletManager

/**
 * Display and claim native x/distribution staking rewards. Claiming withdraws
 * pending rewards from every validator the user has delegated to.
 */
class RewardsFragment : Fragment() {

    companion object {
        private const val TAG = "RewardsFragment"

        @JvmStatic
        fun newInstance(): RewardsFragment = RewardsFragment()
    }

    private lateinit var stakingRewardsText: TextView
    private lateinit var claimRewardsButton: Button
    private lateinit var noRewardsContainer: LinearLayout

    private var transactionSuccessReceiver: BroadcastReceiver? = null
    private var stakingRewards = 0.0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_staking_rewards, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initializeViews(view)
        setupBroadcastReceiver()
        registerBroadcastReceiver()
        claimRewardsButton.setOnClickListener { handleClaimRewards() }
        refreshData()
    }

    private fun initializeViews(view: View) {
        stakingRewardsText = view.findViewById(R.id.staking_rewards_text)
        claimRewardsButton = view.findViewById(R.id.claim_rewards_button)
        noRewardsContainer = view.findViewById(R.id.no_rewards_container)
    }

    private fun setupBroadcastReceiver() {
        transactionSuccessReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                refreshData()
                Handler(Looper.getMainLooper()).postDelayed({ refreshData() }, 500)
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

    fun refreshData() {
        if (!isAdded || context == null) return
        lifecycleScope.launch {
            stakingRewards = try {
                val address = SecureWalletManager.getWalletAddress(requireContext()) ?: return@launch
                val raw = withContext(Dispatchers.IO) { Staking.totalRewards(address) }
                raw.toDouble() / 1_000_000.0
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing rewards", e)
                0.0
            }
            updateUI()
        }
    }

    private fun updateUI() {
        if (stakingRewards > 0) {
            stakingRewardsText.text = String.format("%,.2f ERTH", stakingRewards)
            claimRewardsButton.visibility = View.VISIBLE
            claimRewardsButton.isEnabled = true
            noRewardsContainer.visibility = View.GONE
        } else {
            stakingRewardsText.text = "0 ERTH"
            claimRewardsButton.visibility = View.GONE
            noRewardsContainer.visibility = View.VISIBLE
        }
    }

    private fun handleClaimRewards() {
        claimRewardsButton.isEnabled = false
        lifecycleScope.launch {
            try {
                val txHash = withContext(Dispatchers.IO) {
                    val address = SecureWalletManager.getWalletAddress(requireContext())
                        ?: throw IllegalStateException("No wallet")
                    val delegations = Staking.delegations(address)
                    if (delegations.isEmpty()) throw IllegalStateException("No delegations")
                    SecureWalletManager.executeWithMnemonic(requireContext()) { mnemonic ->
                        val key = EarthWallet.deriveKey(mnemonic)
                        val delegator = EarthWallet.address(key)
                        val msgs: List<ProtoAny> = delegations.map { Staking.msgWithdrawReward(delegator, it.validator) }
                        EarthTx.broadcast(key, msgs)
                    }
                }
                Log.i(TAG, "Claim rewards broadcast: $txHash")
                Toast.makeText(context, "Rewards claimed", Toast.LENGTH_SHORT).show()
                refreshData()
            } catch (e: Exception) {
                Log.e(TAG, "Error claiming rewards", e)
                Toast.makeText(context, "Failed to claim rewards: ${e.message}", Toast.LENGTH_SHORT).show()
                claimRewardsButton.isEnabled = true
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshData()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (transactionSuccessReceiver != null && context != null) {
            try {
                requireActivity().applicationContext.unregisterReceiver(transactionSuccessReceiver)
            } catch (e: Exception) { }
        }
    }
}
