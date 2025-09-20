package com.example.earthwallet.ui.pages.governance

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.earthwallet.Constants
import com.example.earthwallet.R
import com.example.earthwallet.bridge.activities.TransactionActivity
import com.example.earthwallet.bridge.services.SecretQueryService
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Fragment for setting allocation preferences
 * Based on the web app AllocationFund.js pattern
 */
class SetAllocationFragment : Fragment() {

    companion object {
        private const val TAG = "SetAllocationFragment"

        const val ARG_FUND_TYPE = "fund_type"
        const val ARG_FUND_TITLE = "fund_title"
        const val FUND_TYPE_CARETAKER = "caretaker"
        const val FUND_TYPE_DEFLATION = "deflation"

        @JvmStatic
        fun newInstance(fundType: String, fundTitle: String): SetAllocationFragment {
            val fragment = SetAllocationFragment()
            val args = Bundle()
            args.putString(ARG_FUND_TYPE, fundType)
            args.putString(ARG_FUND_TITLE, fundTitle)
            fragment.arguments = args
            return fragment
        }
    }

    // UI Components
    private lateinit var titleText: TextView
    private lateinit var totalPercentageText: TextView
    private lateinit var allocationInputsContainer: LinearLayout
    private lateinit var availableAllocationsContainer: LinearLayout
    private lateinit var setAllocationButton: Button

    // Data
    private val selectedAllocations = mutableListOf<AllocationInput>()
    private val allocationOptions = mutableListOf<AllocationOption>()
    private var totalPercentage = 0
    private var fundType: String? = null
    private var fundTitle: String? = null

    // Services
    private var queryService: SecretQueryService? = null
    private var executorService: ExecutorService? = null

    // Data classes
    private data class AllocationInput(
        val allocationId: Int,
        val name: String,
        var percentage: Int
    )

    private data class AllocationOption(
        val id: Int,
        val name: String
    ) {
        override fun toString(): String = name
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        arguments?.let {
            fundType = it.getString(ARG_FUND_TYPE)
            fundTitle = it.getString(ARG_FUND_TITLE)
        }

        // Initialize services
        queryService = SecretQueryService(requireContext())
        executorService = Executors.newCachedThreadPool()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_set_allocation, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize views
        initializeViews(view)

        // Setup allocation options
        setupAllocationOptions()

        // Load existing user preferences
        loadUserPreferences()
    }

    private fun initializeViews(view: View) {
        titleText = view.findViewById(R.id.title_text)
        totalPercentageText = view.findViewById(R.id.total_percentage_text)
        allocationInputsContainer = view.findViewById(R.id.allocation_inputs_container)
        availableAllocationsContainer = view.findViewById(R.id.available_allocations_container)
        setAllocationButton = view.findViewById(R.id.set_allocation_button)

        // Set title
        titleText.text = "Set $fundTitle Preferences"

        // Setup set allocation button
        setAllocationButton.setOnClickListener { setAllocation() }

        // Initial state
        updateUI()
        updateAllocationInputs()
        updateAvailableAllocations()
    }

    private fun setupAllocationOptions() {
        when (fundType) {
            FUND_TYPE_CARETAKER -> {
                // Caretaker Fund has only one option
                allocationOptions.add(AllocationOption(1, "Caretaker Fund"))
            }
            FUND_TYPE_DEFLATION -> {
                // Deflation Fund options
                allocationOptions.add(AllocationOption(1, "LP Rewards"))
                allocationOptions.add(AllocationOption(2, "SCRT Labs"))
                allocationOptions.add(AllocationOption(3, "ERTH Labs"))
            }
        }

        // Create allocation option cards
        updateAvailableAllocations()
    }

    private fun addSelectedAllocation(option: AllocationOption) {
        // Check if already added
        for (existing in selectedAllocations) {
            if (existing.allocationId == option.id) {
                Toast.makeText(context, "Allocation already added", Toast.LENGTH_SHORT).show()
                return
            }
        }

        // Add new allocation with 0%
        selectedAllocations.add(AllocationInput(option.id, option.name, 0))

        // Update UI components
        updateUI()
        updateAllocationInputs()
        updateAvailableAllocations()
    }

    private fun updateAvailableAllocations() {
        availableAllocationsContainer.removeAllViews()

        // Create flowing layout for chips
        var currentRow: LinearLayout? = null
        var currentRowWidth = 0
        val maxRowWidth = resources.displayMetrics.widthPixels - 64 // Account for margins

        for (option in allocationOptions) {
            // Check if this option is already selected
            val isAlreadySelected = selectedAllocations.any { it.allocationId == option.id }

            if (!isAlreadySelected) {
                val chip = createAllocationChip(option)

                // Measure chip width (approximate)
                val chipWidth = (option.name.length * 12 + 80) // Rough estimate

                // Start new row if needed
                if (currentRow == null || currentRowWidth + chipWidth > maxRowWidth) {
                    currentRow = LinearLayout(context)
                    currentRow.orientation = LinearLayout.HORIZONTAL
                    currentRow.layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    availableAllocationsContainer.addView(currentRow)
                    currentRowWidth = 0
                }

                currentRow.addView(chip)
                currentRowWidth += chipWidth
            }
        }
    }

    private fun createAllocationChip(option: AllocationOption): Button {
        val chip = Button(context)
        chip.text = option.name
        chip.textSize = 14f
        chip.setBackgroundColor(0xFFE0E0E0.toInt())
        chip.setTextColor(0xFF333333.toInt())
        chip.setPadding(16, 8, 16, 8)

        // Set margins
        val chipParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        chipParams.setMargins(4, 4, 4, 4)
        chip.layoutParams = chipParams

        // Add rounded corners and ripple effect
        chip.stateListAnimator = null
        chip.elevation = 2f

        chip.setOnClickListener {
            // Add visual feedback
            chip.setBackgroundColor(0xFF4CAF50.toInt())
            chip.setTextColor(0xFFFFFFFF.toInt())
            chip.postDelayed({ addSelectedAllocation(option) }, 150)
        }

        return chip
    }

    private fun removeAllocation(allocationId: Int) {
        selectedAllocations.removeAll { it.allocationId == allocationId }

        // Update UI components
        updateUI()
        updateAllocationInputs()
        updateAvailableAllocations()
    }

    private fun updateAllocationPercentage(allocationId: Int, percentage: Int) {
        for (allocation in selectedAllocations) {
            if (allocation.allocationId == allocationId) {
                allocation.percentage = percentage
                break
            }
        }

        // Only update the summary, don't rebuild the input views
        updateTotalAndButton()
    }

    private fun updateTotalAndButton() {
        // Calculate total percentage
        totalPercentage = selectedAllocations.sumOf { it.percentage }

        // Update total percentage display
        totalPercentageText.text = "Total: $totalPercentage%"
        totalPercentageText.setTextColor(if (totalPercentage == 100) 0xFF4CAF50.toInt() else 0xFFFF0000.toInt())

        // Update set button
        setAllocationButton.isEnabled = totalPercentage == 100 && selectedAllocations.isNotEmpty()
        setAllocationButton.text = if (totalPercentage == 100) "Set Allocation" else
            "Total must equal 100% ($totalPercentage%)"
    }

    private fun updateUI() {
        updateTotalAndButton()
    }

    private fun updateAllocationInputs() {
        allocationInputsContainer.removeAllViews()

        for (allocation in selectedAllocations) {
            val inputView = createAllocationInputView(allocation)
            allocationInputsContainer.addView(inputView)
        }
    }

    private fun createAllocationInputView(allocation: AllocationInput): View {
        val container = LinearLayout(context)
        container.orientation = LinearLayout.HORIZONTAL
        container.setPadding(20, 15, 20, 15)
        container.setBackgroundColor(0xFFE3F2FD.toInt())

        // Set margins and rounded corners
        val containerParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        containerParams.setMargins(16, 8, 16, 8)
        container.layoutParams = containerParams

        // Allocation name
        val nameText = TextView(context)
        nameText.text = allocation.name
        nameText.textSize = 16f
        nameText.layoutParams = LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        )

        // Percentage input
        val percentageInput = EditText(context)
        percentageInput.setText(allocation.percentage.toString())
        percentageInput.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        percentageInput.hint = "%"
        percentageInput.layoutParams = LinearLayout.LayoutParams(
            100,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        percentageInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                try {
                    val percentage = s.toString().toInt()
                    updateAllocationPercentage(allocation.allocationId, maxOf(0, minOf(100, percentage)))
                } catch (e: NumberFormatException) {
                    updateAllocationPercentage(allocation.allocationId, 0)
                }
            }
        })

        // Remove button
        val removeButton = Button(context)
        removeButton.text = "-"
        removeButton.layoutParams = LinearLayout.LayoutParams(
            80,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        removeButton.setOnClickListener { removeAllocation(allocation.allocationId) }

        container.addView(nameText)
        container.addView(percentageInput)
        container.addView(removeButton)

        return container
    }

    private fun loadUserPreferences() {
        executorService?.execute {
            try {
                val contractAddress = if (FUND_TYPE_CARETAKER == fundType)
                    Constants.REGISTRATION_CONTRACT
                else
                    Constants.STAKING_CONTRACT
                val contractHash = if (FUND_TYPE_CARETAKER == fundType)
                    Constants.REGISTRATION_HASH
                else
                    Constants.STAKING_HASH

                val queryMsg = JSONObject()
                val userQuery = JSONObject()
                userQuery.put("address", "secret1wvha45m7qgr6lc96sqatdq87hu3t25l9fcfex9") // TODO: Get actual wallet address
                queryMsg.put("query_user_allocations", userQuery)

                val result = queryService!!.queryContract(contractAddress, contractHash, queryMsg)

                activity?.runOnUiThread {
                    try {
                        // Process user preferences (same logic as fragments)
                        if (result.has("data") && result.getJSONArray("data").length() > 0) {
                            val dataArray = result.getJSONArray("data")

                            selectedAllocations.clear()
                            for (i in 0 until dataArray.length()) {
                                val item = dataArray.getJSONObject(i)
                                val allocationId = item.optInt("allocation_id", 0)
                                val percentage = item.optString("percentage", "0").toInt()

                                val name = getAllocationName(allocationId)
                                selectedAllocations.add(AllocationInput(allocationId, name, percentage))
                            }

                            updateUI()
                            updateAllocationInputs()
                            updateAvailableAllocations()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing user preferences", e)
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error loading user preferences", e)
            }
        }
    }

    private fun getAllocationName(allocationId: Int): String {
        for (option in allocationOptions) {
            if (option.id == allocationId) {
                return option.name
            }
        }
        return "Unknown ($allocationId)"
    }

    private fun setAllocation() {
        if (totalPercentage != 100 || selectedAllocations.isEmpty()) {
            Toast.makeText(context, "Total must equal 100%", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            // Build the contract execution message matching web app format
            val executeMsg = JSONObject()
            val setAllocation = JSONObject()
            val percentages = JSONArray()

            // Format allocations as expected by the contracts (matching web app)
            for (allocation in selectedAllocations) {
                val allocItem = JSONObject()
                allocItem.put("allocation_id", allocation.allocationId)
                allocItem.put("percentage", allocation.percentage.toString())
                percentages.put(allocItem)
            }

            setAllocation.put("percentages", percentages)
            executeMsg.put("set_allocation", setAllocation)

            Log.d(TAG, "Setting $fundTitle allocations: $executeMsg")

            when (fundType) {
                FUND_TYPE_CARETAKER -> {
                    // Execute on registration contract
                    val intent = Intent(activity, TransactionActivity::class.java)
                    intent.putExtra(TransactionActivity.EXTRA_TRANSACTION_TYPE, TransactionActivity.TYPE_SECRET_EXECUTE)
                    intent.putExtra(TransactionActivity.EXTRA_CONTRACT_ADDRESS, Constants.REGISTRATION_CONTRACT)
                    intent.putExtra(TransactionActivity.EXTRA_CODE_HASH, Constants.REGISTRATION_HASH)
                    intent.putExtra(TransactionActivity.EXTRA_EXECUTE_JSON, executeMsg.toString())

                    startActivityForResult(intent, 1001) // Request code for caretaker fund
                }
                FUND_TYPE_DEFLATION -> {
                    // Execute on staking contract
                    val intent = Intent(activity, TransactionActivity::class.java)
                    intent.putExtra(TransactionActivity.EXTRA_TRANSACTION_TYPE, TransactionActivity.TYPE_SECRET_EXECUTE)
                    intent.putExtra(TransactionActivity.EXTRA_CONTRACT_ADDRESS, Constants.STAKING_CONTRACT)
                    intent.putExtra(TransactionActivity.EXTRA_CODE_HASH, Constants.STAKING_HASH)
                    intent.putExtra(TransactionActivity.EXTRA_EXECUTE_JSON, executeMsg.toString())

                    startActivityForResult(intent, 1002) // Request code for deflation fund
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error building allocation message", e)
            Toast.makeText(context, "Error setting allocation: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == 1001 || requestCode == 1002) { // Caretaker or Deflation fund allocation
            if (resultCode == Activity.RESULT_OK) {
                // Success - allocation was set
                // Go back to previous fragment
                activity?.supportFragmentManager?.popBackStack()
            } else {
                // Handle errors
                var error = "Unknown error"
                data?.let {
                    val errorStr = it.getStringExtra(TransactionActivity.EXTRA_ERROR)
                    if (!errorStr.isNullOrEmpty()) {
                        error = errorStr
                    } else {
                        error = "Transaction cancelled or failed"
                    }
                }

                Log.e(TAG, "Error setting allocation: $error")
                Toast.makeText(context, "Error setting allocation: $error", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (executorService != null && !executorService!!.isShutdown) {
            executorService!!.shutdown()
        }
    }
}