package network.erth.wallet.ui.pages.governance

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import network.erth.wallet.Constants
import network.erth.wallet.R
import network.erth.wallet.bridge.services.SecretQueryService
import network.erth.wallet.ui.components.PieChartView
import network.erth.wallet.wallet.services.SecureWalletManager
import com.google.android.material.tabs.TabLayout
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Fragment for managing Deflation Fund allocation voting
 * Voting is weighted by staked ERTH tokens
 */
class DeflationFundFragment : Fragment() {

    companion object {
        private const val TAG = "DeflationFundFragment"

        // Allocation options for Deflation Fund
        private val ALLOCATION_NAMES = arrayOf(
            "LP Rewards",      // allocation_id: 1
            "SCRT Labs",       // allocation_id: 2
            "ERTH Labs"        // allocation_id: 3
        )

        @JvmStatic
        fun newInstance(): DeflationFundFragment = DeflationFundFragment()
    }

    // UI Components
    private lateinit var rootView: View
    private lateinit var actualAllocationSection: LinearLayout
    private lateinit var preferredAllocationSection: LinearLayout
    private lateinit var tabLayout: TabLayout
    private lateinit var titleTextView: TextView

    // Services
    private var queryService: SecretQueryService? = null
    private var executorService: ExecutorService? = null

    // Data
    private var currentAllocations: JSONArray? = null
    private var userAllocations: JSONArray? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        rootView = inflater.inflate(R.layout.fragment_deflation_fund, container, false)
        return rootView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize services
        queryService = SecretQueryService(requireContext())
        executorService = Executors.newCachedThreadPool()

        initializeViews(view)

        // Load initial data
        loadActualAllocations()
    }

    private fun initializeViews(view: View) {
        actualAllocationSection = view.findViewById(R.id.actual_allocation_section)
        preferredAllocationSection = view.findViewById(R.id.preferred_allocation_section)
        tabLayout = view.findViewById(R.id.tab_layout)
        titleTextView = view.findViewById(R.id.title_text)

        // Set title
        titleTextView.text = "Deflation Fund"

        // Setup tabs
        setupTabs()

        // Initially show actual allocations
        showActualAllocations()
    }

    private fun setupTabs() {
        tabLayout.addTab(tabLayout.newTab().setText("Actual Allocation"))
        tabLayout.addTab(tabLayout.newTab().setText("Preferred Allocation"))

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                when (tab.position) {
                    0 -> showActualAllocations()
                    1 -> showPreferredAllocations()
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun showActualAllocations() {
        // Show/hide sections
        actualAllocationSection.visibility = View.VISIBLE
        preferredAllocationSection.visibility = View.GONE

        // Load data if needed
        if (currentAllocations == null) {
            loadActualAllocations()
        }
    }

    private fun showPreferredAllocations() {
        // Show/hide sections
        actualAllocationSection.visibility = View.GONE
        preferredAllocationSection.visibility = View.VISIBLE

        // Load user allocations
        loadUserAllocations()
    }

    private fun loadActualAllocations() {
        executorService?.execute {
            try {
                // First check if we have a wallet
                val walletAddress = SecureWalletManager.getWalletAddress(requireContext())

                if (walletAddress.isNullOrEmpty()) {
                }
                // Query current allocations from staking contract
                val queryMsg = JSONObject()
                queryMsg.put("query_allocation_options", JSONObject())


                val result = queryService!!.queryContract(
                    Constants.STAKING_CONTRACT,
                    Constants.STAKING_HASH,
                    queryMsg
                )


                activity?.runOnUiThread {
                    try {
                        // Handle different response formats matching what we see in logs
                        currentAllocations = JSONArray()

                        if (result.has("data") && result.getJSONArray("data").length() > 0) {
                            // SecretQueryService wrapped response
                            val dataArray = result.getJSONArray("data")

                            // First pass: collect all raw amounts to calculate total
                            var totalAmount = 0L
                            for (i in 0 until dataArray.length()) {
                                val item = dataArray.getJSONObject(i)
                                val amountStr = item.optString("amount_allocated", "0")
                                try {
                                    totalAmount += amountStr.toLong()
                                } catch (e: NumberFormatException) {
                                }
                            }


                            // Second pass: calculate percentages with proper rounding to ensure 100% total
                            val rawAmounts = LongArray(dataArray.length())
                            val allocationIds = IntArray(dataArray.length())

                            // Collect raw data first
                            for (i in 0 until dataArray.length()) {
                                val item = dataArray.getJSONObject(i)
                                allocationIds[i] = item.optInt("allocation_id", 0)
                                val amountStr = item.optString("amount_allocated", "0")
                                try {
                                    rawAmounts[i] = amountStr.toLong()
                                } catch (e: NumberFormatException) {
                                    rawAmounts[i] = 0
                                }
                            }

                            // Calculate percentages with proper distribution to ensure 100% total
                            val percentages = IntArray(dataArray.length())
                            var totalCalculatedPercentage = 0

                            if (totalAmount > 0) {
                                // First calculate raw percentages
                                val exactPercentages = DoubleArray(dataArray.length())
                                for (i in 0 until dataArray.length()) {
                                    exactPercentages[i] = (rawAmounts[i] * 100.0) / totalAmount
                                    percentages[i] = kotlin.math.floor(exactPercentages[i]).toInt() // Use floor to avoid over-allocation
                                    totalCalculatedPercentage += percentages[i]
                                }

                                // Distribute remaining percentage to items with highest fractional parts
                                val remaining = 100 - totalCalculatedPercentage
                                if (remaining > 0) {
                                    // Create array of indices sorted by fractional part (descending)
                                    val indices = Array(dataArray.length()) { it }

                                    // Sort by fractional part descending
                                    indices.sortWith { a, b ->
                                        val fracA = exactPercentages[a] - kotlin.math.floor(exactPercentages[a])
                                        val fracB = exactPercentages[b] - kotlin.math.floor(exactPercentages[b])
                                        fracB.compareTo(fracA)
                                    }

                                    // Add 1% to the items with highest fractional parts
                                    for (i in 0 until minOf(remaining, indices.size)) {
                                        percentages[indices[i]]++
                                    }
                                }
                            }

                            // Create transformed objects
                            for (i in 0 until dataArray.length()) {
                                val transformed = JSONObject()
                                transformed.put("allocation_id", allocationIds[i])
                                transformed.put("amount_allocated", percentages[i])

                                currentAllocations!!.put(transformed)
                            }
                        } else if (result.toString().startsWith("[")) {
                            // Direct array response (fallback)
                            val directArray = JSONArray(result.toString())
                            currentAllocations = directArray
                        } else if (result.has("allocations")) {
                            // DeflationFund style response (old format)
                            currentAllocations = result.getJSONArray("allocations")
                        }

                        updateActualAllocationsUI()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing allocation data", e)
                        Toast.makeText(context, "Error loading allocation data", Toast.LENGTH_SHORT).show()
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error loading actual allocations", e)
                Log.e(TAG, "Exception type: ${e.javaClass.simpleName}")
                Log.e(TAG, "Exception message: ${e.message}")
                e.printStackTrace()
                activity?.runOnUiThread {
                    Toast.makeText(context, "Error loading allocations: ${e.message}", Toast.LENGTH_LONG).show()
                    // Also update the UI to show the error
                    actualAllocationSection.removeAllViews()
                    val errorText = TextView(context)
                    errorText.text = "Query Error: ${e.message}\n\nContract: ${Constants.STAKING_CONTRACT}\nHash: ${Constants.STAKING_HASH}"
                    errorText.textSize = 14f
                    errorText.setTextColor(0xFFFF0000.toInt())
                    errorText.setPadding(20, 20, 20, 20)
                    actualAllocationSection.addView(errorText)
                }
            }
        }
    }

    private fun loadUserAllocations() {
        executorService?.execute {
            try {
                val userAddress = SecureWalletManager.getWalletAddress(requireContext())
                if (userAddress.isNullOrEmpty()) {
                    activity?.runOnUiThread {
                        Toast.makeText(context, "Wallet address not available", Toast.LENGTH_SHORT).show()
                    }
                    return@execute
                }

                // Query user's preferred allocations
                val queryMsg = JSONObject()
                val userQuery = JSONObject()
                userQuery.put("address", userAddress)
                queryMsg.put("query_user_allocations", userQuery)


                val result = queryService!!.queryContract(
                    Constants.STAKING_CONTRACT,
                    Constants.STAKING_HASH,
                    queryMsg
                )


                activity?.runOnUiThread {
                    try {
                        // Handle user allocation response - SecretQueryService wrapped with "percentage" fields
                        userAllocations = JSONArray()

                        if (result.has("data") && result.getJSONArray("data").length() > 0) {
                            // SecretQueryService wrapped response with percentage field
                            val dataArray = result.getJSONArray("data")

                            for (i in 0 until dataArray.length()) {
                                val item = dataArray.getJSONObject(i)
                                val transformed = JSONObject()

                                // User allocation format has allocation_id and percentage fields
                                val allocationId = item.optInt("allocation_id", 0)
                                val percentageStr = item.optString("percentage", "0")

                                var percentage = 0
                                try {
                                    percentage = percentageStr.toInt()
                                } catch (e: NumberFormatException) {
                                }

                                transformed.put("allocation_id", allocationId)
                                transformed.put("amount_allocated", percentage)

                                userAllocations!!.put(transformed)
                            }
                        } else if (result.toString().startsWith("[")) {
                            // Fallback: direct array response format
                            userAllocations = JSONArray(result.toString())
                        } else if (result.has("percentages")) {
                            // Fallback for older format
                            userAllocations = result.getJSONArray("percentages")
                        }

                        updatePreferredAllocationsUI()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing user allocation data", e)
                        Toast.makeText(context, "Error loading user preferences", Toast.LENGTH_SHORT).show()
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error loading user allocations", e)
                activity?.runOnUiThread {
                    Toast.makeText(context, "Error loading user preferences: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateActualAllocationsUI() {
        // Clear existing views
        actualAllocationSection.removeAllViews()

        // Add pie chart if we have data
        if (currentAllocations != null && currentAllocations!!.length() > 0) {
            val pieChart = createPieChart(currentAllocations!!)
            if (pieChart != null) {
                val chartParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    600 // Consistent height
                )
                chartParams.setMargins(40, 20, 40, 20)
                chartParams.gravity = android.view.Gravity.CENTER_HORIZONTAL
                pieChart.layoutParams = chartParams
                actualAllocationSection.addView(pieChart)
            }
        }

        if (currentAllocations == null || currentAllocations!!.length() == 0) {
            val noDataText = TextView(context)
            noDataText.text = "No allocation data available from staking contract.\n\nThis could mean:\n• The contract hasn't been configured yet\n• No allocations have been set\n• Contract query failed"
            noDataText.textSize = 14f
            noDataText.setTextColor(0xFF666666.toInt())
            noDataText.setPadding(20, 20, 20, 20)
            actualAllocationSection.addView(noDataText)
            return
        }

        // Add list view of allocations with better visual styling if we have data
        if (currentAllocations != null && currentAllocations!!.length() > 0) {
            try {
                for (i in 0 until currentAllocations!!.length()) {
                    val allocation = currentAllocations!!.getJSONObject(i)
                    val allocationView = createAllocationItemView(allocation, false)
                    actualAllocationSection.addView(allocationView)
                }

                // Add total percentage check
                var totalPercentage = 0
                for (i in 0 until currentAllocations!!.length()) {
                    val allocation = currentAllocations!!.getJSONObject(i)
                    totalPercentage += allocation.optInt("amount_allocated", 0)
                }

                val totalLabel = TextView(context)
                totalLabel.text = "Total: $totalPercentage%"
                totalLabel.textSize = 14f
                totalLabel.setTextColor(if (totalPercentage == 100) 0xFF4CAF50.toInt() else 0xFFFF9800.toInt())
                totalLabel.setPadding(20, 10, 20, 10)
                totalLabel.setTypeface(totalLabel.typeface, android.graphics.Typeface.BOLD)
                actualAllocationSection.addView(totalLabel)

            } catch (e: Exception) {
                Log.e(TAG, "Error updating actual allocations UI", e)
            }
        }
    }

    private fun updatePreferredAllocationsUI() {
        // Clear existing views
        preferredAllocationSection.removeAllViews()

        // Add pie chart for user data
        if (userAllocations != null && userAllocations!!.length() > 0) {
            val userPieChart = createPieChart(userAllocations!!)
            if (userPieChart != null) {
                val chartParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    600 // Same size as actual allocation chart
                )
                chartParams.setMargins(40, 20, 40, 20)
                chartParams.gravity = android.view.Gravity.CENTER_HORIZONTAL
                userPieChart.layoutParams = chartParams
                preferredAllocationSection.addView(userPieChart)
            }
        }

        // Show current user allocations if available
        if (userAllocations != null && userAllocations!!.length() > 0) {
            try {
                for (i in 0 until userAllocations!!.length()) {
                    val allocation = userAllocations!!.getJSONObject(i)
                    val allocationView = createAllocationItemView(allocation, false)
                    preferredAllocationSection.addView(allocationView)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error displaying user allocations", e)
            }
        } else {
            val noPrefsText = TextView(context)
            noPrefsText.text = "No preferences set yet."
            noPrefsText.textSize = 14f
            noPrefsText.setTextColor(0xFF666666.toInt())
            noPrefsText.setPadding(20, 10, 20, 10)
            preferredAllocationSection.addView(noPrefsText)

            val comingSoonText = TextView(context)
            comingSoonText.text = "Setting preferences coming soon!"
            comingSoonText.textSize = 14f
            comingSoonText.setTextColor(0xFF1976D2.toInt())
            comingSoonText.setPadding(20, 10, 20, 20)
            preferredAllocationSection.addView(comingSoonText)
        }

        // Add Set Preferences button (styled like swap button)
        val setPrefsButton = Button(context)
        setPrefsButton.text = if (userAllocations != null && userAllocations!!.length() > 0)
            "Update Preferences" else "Set Preferences"
        setPrefsButton.setTextColor(0xFFFFFFFF.toInt())
        setPrefsButton.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF4CAF50.toInt()) // Green like swap button
        setPrefsButton.textSize = 18f // Match swap button text size
        setPrefsButton.setTypeface(setPrefsButton.typeface, android.graphics.Typeface.BOLD)

        val buttonParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (56 * resources.displayMetrics.density).toInt() // 56dp height like swap button
        )
        buttonParams.setMargins(
            (20 * resources.displayMetrics.density).toInt(), // 20dp margins
            (20 * resources.displayMetrics.density).toInt(),
            (20 * resources.displayMetrics.density).toInt(),
            (10 * resources.displayMetrics.density).toInt()
        )
        setPrefsButton.layoutParams = buttonParams
        setPrefsButton.setOnClickListener { openSetAllocationActivity() }
        preferredAllocationSection.addView(setPrefsButton)
    }

    private fun createAllocationItemView(allocation: JSONObject, isEditable: Boolean): View {
        return try {
            val itemLayout = LinearLayout(context)
            itemLayout.orientation = LinearLayout.HORIZONTAL
            itemLayout.setPadding(20, 15, 20, 15)
            itemLayout.setBackgroundColor(0x10000000) // Light gray background

            // Get allocation data
            val allocationId = allocation.optInt("allocation_id", 0)
            var value = allocation.optInt("amount_allocated", 0)

            // Also check for percentage field for user allocations
            if (value == 0 && allocation.has("percentage")) {
                value = allocation.optInt("percentage", 0)
            }

            val name = getAllocationName(allocationId)

            // Add a color indicator for pie chart effect
            val colorIndicator = View(context)
            val color = getPieChartColor(allocationId)
            colorIndicator.setBackgroundColor(color)
            val colorParams = LinearLayout.LayoutParams(20, ViewGroup.LayoutParams.MATCH_PARENT)
            colorParams.setMargins(0, 0, 12, 0)
            colorIndicator.layoutParams = colorParams

            val nameText = TextView(context)
            nameText.text = name
            nameText.textSize = 16f
            nameText.setTextColor(0xFF333333.toInt())
            nameText.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

            val valueText = TextView(context)
            valueText.text = "$value%"
            valueText.textSize = 16f
            valueText.setTextColor(0xFF1976D2.toInt())
            valueText.setTypeface(valueText.typeface, android.graphics.Typeface.BOLD)

            itemLayout.addView(colorIndicator)
            itemLayout.addView(nameText)
            itemLayout.addView(valueText)

            itemLayout
        } catch (e: Exception) {
            Log.e(TAG, "Error creating allocation item view", e)
            View(context)
        }
    }

    private fun getAllocationName(allocationId: Int): String {
        return if (allocationId >= 1 && allocationId <= ALLOCATION_NAMES.size) {
            ALLOCATION_NAMES[allocationId - 1]
        } else {
            "Unknown ($allocationId)"
        }
    }

    private fun getPieChartColor(allocationId: Int): Int {
        // Pie chart colors similar to web app
        val colors = intArrayOf(
            0xFF4CAF50.toInt(), // Green
            0xFF8BC34A.toInt(), // Light Green
            0xFFFF9800.toInt(), // Orange
            0xFFCDDC39.toInt(), // Lime
            0xFF009688.toInt(), // Teal
            0xFF795548.toInt()  // Brown
        )
        return colors[(allocationId - 1) % colors.size]
    }

    private fun createPieChart(allocations: JSONArray): PieChartView? {
        return try {
            val pieChart = PieChartView(requireContext())
            val slices = mutableListOf<PieChartView.PieSlice>()

            for (i in 0 until allocations.length()) {
                val allocation = allocations.getJSONObject(i)
                val allocationId = allocation.optInt("allocation_id", 0)
                val percentage = allocation.optInt("amount_allocated", 0)

                if (percentage > 0) {
                    val name = getAllocationName(allocationId)
                    val color = getPieChartColor(allocationId)
                    slices.add(PieChartView.PieSlice(name, percentage.toFloat(), color))
                }
            }

            pieChart.setData(slices)
            pieChart
        } catch (e: Exception) {
            Log.e(TAG, "Error creating pie chart", e)
            null
        }
    }

    private fun openSetAllocationActivity() {
        val fragment = SetAllocationFragment.newInstance(
            SetAllocationFragment.FUND_TYPE_DEFLATION,
            "Deflation Fund"
        )

        activity?.supportFragmentManager
            ?.beginTransaction()
            ?.replace(R.id.host_content, fragment)
            ?.addToBackStack(null)
            ?.commit()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (executorService != null && !executorService!!.isShutdown) {
            executorService!!.shutdown()
        }
    }
}