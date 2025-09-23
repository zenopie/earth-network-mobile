package network.erth.wallet.ui.pages.managelp

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import network.erth.wallet.R
import java.io.IOException
import java.text.DecimalFormat

/**
 * RecyclerView adapter for displaying pool overview items
 */
class PoolOverviewAdapter(
    private var poolList: List<ManageLPFragment.PoolData>,
    private val clickListener: PoolClickListener
) : RecyclerView.Adapter<PoolOverviewAdapter.PoolViewHolder>() {

    interface PoolClickListener {
        fun onManageClicked(poolData: ManageLPFragment.PoolData)
        fun onClaimClicked(poolData: ManageLPFragment.PoolData)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PoolViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_pool_overview, parent, false)
        return PoolViewHolder(view)
    }

    override fun onBindViewHolder(holder: PoolViewHolder, position: Int) {
        val pool = poolList[position]
        holder.bind(pool, clickListener)
    }

    override fun getItemCount(): Int = poolList.size

    class PoolViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tokenLogo: ImageView = itemView.findViewById(R.id.token_logo)
        private val tokenLabel: TextView = itemView.findViewById(R.id.token_label)
        private val pairLabel: TextView = itemView.findViewById(R.id.pair_label)
        private val rewardsValue: TextView = itemView.findViewById(R.id.rewards_value)
        private val rewardsLabel: TextView = itemView.findViewById(R.id.rewards_label)
        private val liquidityValue: TextView = itemView.findViewById(R.id.liquidity_value)
        private val liquidityLabel: TextView = itemView.findViewById(R.id.liquidity_label)
        private val volumeValue: TextView = itemView.findViewById(R.id.volume_value)
        private val volumeLabel: TextView = itemView.findViewById(R.id.volume_label)
        private val aprValue: TextView = itemView.findViewById(R.id.apr_value)
        private val aprLabel: TextView = itemView.findViewById(R.id.apr_label)
        private val claimButton: Button = itemView.findViewById(R.id.claim_button)
        private val manageButton: Button = itemView.findViewById(R.id.manage_button)
        private val poolBox: View? = itemView.findViewById(R.id.pool_overview_box)
        private val unbondingLock: View? = itemView.findViewById(R.id.unbonding_lock)

        private var currentPool: ManageLPFragment.PoolData? = null

        fun bind(pool: ManageLPFragment.PoolData, clickListener: PoolClickListener) {
            // Store pool reference for logo loading
            this.currentPool = pool

            // Set token information
            tokenLabel.text = pool.tokenKey
            pairLabel.text = "/ERTH"

            // Set pool statistics with proper formatting
            rewardsValue.text = formatNumber(pool.pendingRewards)
            rewardsLabel.text = "Rewards"

            liquidityValue.text = formatNumber(pool.liquidity)
            liquidityLabel.text = "Liquidity (ERTH)"

            volumeValue.text = formatNumber(pool.volume)
            volumeLabel.text = "Volume (7d)"

            aprValue.text = pool.apr
            aprLabel.text = "APR"

            // Check if there are rewards to determine styling
            val hasRewards = try {
                val rewards = pool.pendingRewards.replace(",", "").toDouble()
                rewards > 0
            } catch (e: NumberFormatException) {
                false
            }

            // Apply green outline if has rewards
            poolBox?.setBackgroundResource(
                if (hasRewards) R.drawable.pool_overview_box_rewards
                else R.drawable.pool_overview_box_normal
            )

            // Set button states and click listeners
            claimButton.isEnabled = hasRewards
            claimButton.setOnClickListener {
                if (hasRewards) {
                    clickListener.onClaimClicked(pool)
                }
            }

            manageButton.setOnClickListener {
                clickListener.onManageClicked(pool)
            }

            // Set token logo based on token type
            setTokenLogo(pool.tokenKey)

            // Hide unbonding lock for now - this was for pool overview, not remove liquidity tab
            unbondingLock?.visibility = View.GONE
        }

        private fun setTokenLogo(tokenKey: String) {
            try {
                // Get token info and load logo from assets
                currentPool?.tokenInfo?.logo?.takeIf { it.isNotEmpty() }?.let { logoPath ->
                    loadImageFromAssets(itemView.context, logoPath)
                    return
                }

                // Fallback to token key based loading
                val assetPath = "coin/${tokenKey.uppercase()}.png"
                loadImageFromAssets(itemView.context, assetPath)

            } catch (e: Exception) {
                // Fallback to default
                tokenLogo.setImageResource(R.drawable.ic_token_default)
            }
        }

        private fun loadImageFromAssets(context: Context, assetPath: String) {
            try {
                val inputStream = context.assets.open(assetPath)
                val drawable = Drawable.createFromStream(inputStream, null)
                tokenLogo.setImageDrawable(drawable)
                inputStream.close()
            } catch (e: IOException) {
                tokenLogo.setImageResource(R.drawable.ic_token_default)
            }
        }

        private fun formatNumber(numberStr: String?): String {
            if (numberStr.isNullOrBlank()) {
                return "0"
            }

            return try {
                // Remove any commas and parse
                val number = numberStr.replace(",", "").toDouble()

                when {
                    number == 0.0 -> "0"
                    number >= 1000000 -> {
                        // Show millions with 1 decimal place
                        val formatter = DecimalFormat("#.#M")
                        formatter.format(number / 1000000)
                    }
                    number >= 1000 -> {
                        // Show thousands with 1 decimal place
                        val formatter = DecimalFormat("#.#K")
                        formatter.format(number / 1000)
                    }
                    number >= 1 -> {
                        // Show whole numbers or 1 decimal place
                        val formatter = DecimalFormat("#.#")
                        formatter.format(number)
                    }
                    else -> {
                        // Show small numbers with more precision
                        val formatter = DecimalFormat("#.###")
                        formatter.format(number)
                    }
                }
            } catch (e: NumberFormatException) {
                numberStr // Return as-is if parsing fails
            }
        }
    }

    fun updateData(newPoolList: List<ManageLPFragment.PoolData>) {
        this.poolList = newPoolList
        notifyDataSetChanged()
    }
}