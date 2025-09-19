package com.example.earthwallet.ui.pages.governance

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
import com.example.earthwallet.Constants
import com.example.earthwallet.R
import com.example.earthwallet.bridge.services.SecretQueryService
import com.example.earthwallet.ui.components.PieChartView
import com.example.earthwallet.wallet.services.SecureWalletManager
import com.google.android.material.tabs.TabLayout
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Fragment for managing Caretaker Fund allocation voting
 * Voting is 1 person 1 vote (requires passport registration)
 */
class CaretakerFundFragment : Fragment() {

    companion object {
        private const val TAG = "CaretakerFundFragment"

        // Allocation options for Caretaker Fund
        private val ALLOCATION_NAMES = arrayOf(
            "Registration Rewards"  // allocation_id: 1
        )

        @JvmStatic
        fun newInstance(): CaretakerFundFragment = CaretakerFundFragment()
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
        rootView = inflater.inflate(R.layout.fragment_caretaker_fund, container, false)
        return rootView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize services
        queryService = SecretQueryService(context)
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
        titleTextView.text = "Caretaker Fund"

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
                val walletAddress = SecureWalletManager.getWalletAddress(context)
                Log.d(TAG, "Loading allocations with wallet: ${walletAddress ?: "null"}")

                if (walletAddress.isNullOrEmpty()) {
                    Log.w(TAG, "No wallet address available - query may fail")
                }

                // Query current allocations from registration contract
                val queryMsg = JSONObject()
                queryMsg.put("query_allocation_options", JSONObject())

                Log.d(TAG, "Querying caretaker fund allocations from: ${Constants.REGISTRATION_CONTRACT}")
                Log.d(TAG, "Using hash: ${Constants.REGISTRATION_HASH}")
                Log.d(TAG, "Query message: $queryMsg")

                val result = queryService!!.queryContract(
                    Constants.REGISTRATION_CONTRACT,
                    Constants.REGISTRATION_HASH,
                    queryMsg
                )

                Log.d(TAG, "Allocation query result: $result")

                activity?.runOnUiThread {
                    try {
                        // Handle different response formats
                        currentAllocations = JSONArray()

                        if (result.has("data") && result.getJSONArray("data").length() > 0) {
                            val dataArray = result.getJSONArray("data")
                            Log.d(TAG, "Processing SecretQueryService wrapped response with ${dataArray.length()} items")

                            // First pass: collect all raw amounts to calculate total (CaretakerFund format with state wrapper)
                            var totalAmount = 0L
                            for (i in 0 until dataArray.length()) {
                                val item = dataArray.getJSONObject(i)
                                if (item.has("state")) {
                                    val state = item.getJSONObject("state")
                                    totalAmount += state.optLong("amount_allocated", 0)
                                } else {
                                    totalAmount += item.optLong("amount_allocated", 0)
                                }
                            }

                            Log.d(TAG, "Caretaker fund total amount: $totalAmount")

                            // Second pass: calculate percentages with proper distribution
                            val rawAmounts = LongArray(dataArray.length())
                            val allocationIds = IntArray(dataArray.length())

                            // Collect raw data first (CaretakerFund format with state wrapper)
                            for (i in 0 until dataArray.length()) {
                                val item = dataArray.getJSONObject(i)
                                if (item.has("state")) {
                                    // PublicBenefitFund style with state wrapper (CaretakerFund format)
                                    val state = item.getJSONObject("state")
                                    allocationIds[i] = state.optInt("allocation_id", 0)
                                    rawAmounts[i] = state.optLong("amount_allocated", 0)
                                } else {
                                    // Direct format (fallback)
                                    allocationIds[i] = item.optInt("allocation_id", 0)
                                    rawAmounts[i] = item.optLong("amount_allocated", 0)
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
                                    percentages[i] = kotlin.math.floor(exactPercentages[i]).toInt()
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
                            } else {
                                // If no amounts, default to 100% for caretaker fund (registration rewards)
                                if (dataArray.length() > 0) {
                                    percentages[0] = 100
                                }
                            }

                            // Create transformed objects
                            for (i in 0 until dataArray.length()) {
                                val transformed = JSONObject()
                                transformed.put("allocation_id", allocationIds[i])
                                transformed.put("amount_allocated", percentages[i])

                                Log.d(TAG, "Caretaker fund item: allocation_id=${allocationIds[i]}, raw_amount=${rawAmounts[i]} -> ${percentages[i]}%")
                                currentAllocations!!.put(transformed)
                            }
                        } else if (result.toString().startsWith("[")) {
                            // Direct array response (fallback)
                            val directArray = JSONArray(result.toString())
                            Log.d(TAG, "Processing direct array response with ${directArray.length()} items")
                            currentAllocations = directArray
                        } else if (result.has("allocations")) {
                            // Old format
                            currentAllocations = result.getJSONArray("allocations")
                        } else {
                            // Default for caretaker fund - 100% to registration rewards
                            val defaultAllocation = JSONObject()
                            defaultAllocation.put("allocation_id", 1)
                            defaultAllocation.put("amount_allocated", 100)
                            currentAllocations!!.put(defaultAllocation)
                            Log.d(TAG, "Using default caretaker fund allocation: 100% to Registration Rewards")
                        }

                        Log.d(TAG, "Processed allocations: $currentAllocations")
                        updateActualAllocationsUI()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing allocation data", e)
                        Toast.makeText(context, "Error loading allocation data", Toast.LENGTH_SHORT).show()
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error loading actual allocations", e)
                activity?.runOnUiThread {
                    Toast.makeText(context, "Error loading allocations: ${e.message}", Toast.LENGTH_LONG).show()
                    // Show error in UI
                    actualAllocationSection.removeAllViews()
                    val errorText = TextView(context)
                    errorText.text = "Query Error: ${e.message}\n\nContract: ${Constants.REGISTRATION_CONTRACT}\nHash: ${Constants.REGISTRATION_HASH}"
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
                val userAddress = SecureWalletManager.getWalletAddress(context)
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

                Log.d(TAG, "Querying user allocations for: $userAddress")

                val result = queryService!!.queryContract(
                    Constants.REGISTRATION_CONTRACT,
                    Constants.REGISTRATION_HASH,
                    queryMsg
                )

                Log.d(TAG, "User allocation query result: $result")

                activity?.runOnUiThread {
                    try {
                        userAllocations = JSONArray()

                        if (result.has("data") && result.getJSONArray("data").length() > 0) {
                            val dataArray = result.getJSONArray("data")
                            Log.d(TAG, "Processing user allocations with ${dataArray.length()} items")

                            for (i in 0 until dataArray.length()) {
                                val item = dataArray.getJSONObject(i)
                                val transformed = JSONObject()

                                val allocationId = item.optInt("allocation_id", 0)
                                val percentageStr = item.optString("percentage", "0")

                                var percentage = 0
                                try {
                                    percentage = percentageStr.toInt()
                                } catch (e: NumberFormatException) {
                                    Log.w(TAG, "Invalid percentage format: $percentageStr")
                                }

                                transformed.put("allocation_id", allocationId)
                                transformed.put("amount_allocated", percentage)

                                Log.d(TAG, "User allocation item: allocation_id=$allocationId, percentage=$percentage%")
                                userAllocations!!.put(transformed)
                            }
                        } else if (result.toString().startsWith("[")) {
                            userAllocations = JSONArray(result.toString())
                            Log.d(TAG, "Processing user allocations array with ${userAllocations!!.length()} items")
                        } else if (result.has("percentages")) {
                            userAllocations = result.getJSONArray("percentages")
                        }

                        Log.d(TAG, "Processed user allocations: $userAllocations")
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
        actualAllocationSection.removeAllViews()

        // Add pie chart if we have data
        if (currentAllocations != null && currentAllocations!!.length() > 0) {
            val pieChart = createPieChart(currentAllocations!!)
            if (pieChart != null) {
                val chartParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    600
                )
                chartParams.setMargins(40, 20, 40, 20)
                chartParams.gravity = android.view.Gravity.CENTER_HORIZONTAL
                pieChart.layoutParams = chartParams
                actualAllocationSection.addView(pieChart)
            }
        }

        if (currentAllocations == null || currentAllocations!!.length() == 0) {
            val noDataText = TextView(context)
            noDataText.text = "No allocation data available from registration contract.\n\nThis could mean:\n• The contract hasn't been configured yet\n• No allocations have been set\n• Contract query failed"
            noDataText.textSize = 14f
            noDataText.setTextColor(0xFF666666.toInt())
            noDataText.setPadding(20, 20, 20, 20)
            actualAllocationSection.addView(noDataText)
            return
        }

        // Add list view of allocations
        if (currentAllocations != null && currentAllocations!!.length() > 0) {
            try {
                for (i in 0 until currentAllocations!!.length()) {
                    val allocation = currentAllocations!!.getJSONObject(i)
                    val allocationView = createAllocationItemView(allocation)
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
        preferredAllocationSection.removeAllViews()

        // Add pie chart for user data
        if (userAllocations != null && userAllocations!!.length() > 0) {
            val userPieChart = createPieChart(userAllocations!!)
            if (userPieChart != null) {
                val chartParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    600
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
                    val allocationView = createAllocationItemView(allocation)
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
            comingSoonText.text = "Setting preferences requires passport registration!"
            comingSoonText.textSize = 14f
            comingSoonText.setTextColor(0xFF1976D2.toInt())
            comingSoonText.setPadding(20, 10, 20, 20)
            preferredAllocationSection.addView(comingSoonText)
        }

        // Add Set Preferences button
        val setPrefsButton = Button(context)
        setPrefsButton.text = if (userAllocations != null && userAllocations!!.length() > 0)
            "Update Preferences" else "Set Preferences"
        setPrefsButton.setTextColor(0xFFFFFFFF.toInt())
        setPrefsButton.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF4CAF50.toInt())
        setPrefsButton.textSize = 18f
        setPrefsButton.setTypeface(setPrefsButton.typeface, android.graphics.Typeface.BOLD)

        val buttonParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (56 * resources.displayMetrics.density).toInt()
        )
        buttonParams.setMargins(
            (20 * resources.displayMetrics.density).toInt(),
            (20 * resources.displayMetrics.density).toInt(),
            (20 * resources.displayMetrics.density).toInt(),
            (10 * resources.displayMetrics.density).toInt()
        )
        setPrefsButton.layoutParams = buttonParams
        setPrefsButton.setOnClickListener { openSetAllocationActivity() }
        preferredAllocationSection.addView(setPrefsButton)
    }

    private fun createAllocationItemView(allocation: JSONObject): View {
        return try {
            val itemLayout = LinearLayout(context)
            itemLayout.orientation = LinearLayout.HORIZONTAL
            itemLayout.setPadding(20, 15, 20, 15)
            itemLayout.setBackgroundColor(0x10000000)

            val allocationId = allocation.optInt("allocation_id", 0)
            var value = allocation.optInt("amount_allocated", 0)

            if (value == 0 && allocation.has("percentage")) {
                value = allocation.optInt("percentage", 0)
            }

            val name = getAllocationName(allocationId)

            // Color indicator
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
            SetAllocationFragment.FUND_TYPE_CARETAKER,
            "Caretaker Fund"
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