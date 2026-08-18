package network.erth.wallet.ui.pages.governance

import network.erth.wallet.ui.components.TxResult
import network.erth.wallet.ui.components.TxFlow
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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.erth.wallet.R
import network.erth.earth.proto.allocation.StreamId
import network.erth.wallet.chain.Allocation
import network.erth.wallet.chain.EarthTx
import network.erth.wallet.wallet.services.EarthWallet
import network.erth.wallet.wallet.services.SecureWalletManager

/**
 * Set allocation preferences for a fund (percentages summing to 100).
 *
 * The fund type selects the backing vote:
 *  - Caretaker  -> x/allocation's human stream (one-human-one-vote)
 *  - Deflation  -> x/allocation's capital stream (stake-weighted)
 *
 * Both are the same engine over separate state, so the only thing that differs
 * below is the stream threaded into every call.
 *
 * Options are loaded live from the relevant module and the user's current split
 * is pre-filled from their voter record.
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
            fragment.arguments = Bundle().apply {
                putString(ARG_FUND_TYPE, fundType)
                putString(ARG_FUND_TITLE, fundTitle)
            }
            return fragment
        }
    }

    private lateinit var titleText: TextView
    private lateinit var totalPercentageText: TextView
    private lateinit var allocationInputsContainer: LinearLayout
    private lateinit var availableAllocationsContainer: LinearLayout
    private lateinit var setAllocationButton: Button

    private val selectedAllocations = mutableListOf<AllocationInput>()
    // Available options as (id, description), source depends on the fund type.
    private val allocationOptions = mutableListOf<Pair<Long, String>>()
    private var totalPercentage = 0
    private var fundType: String = FUND_TYPE_DEFLATION
    private var fundTitle: String? = null

    private data class AllocationInput(val optionId: Long, val name: String, var percentage: Int)

    private fun isCaretaker() = fundType == FUND_TYPE_CARETAKER

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fundType = arguments?.getString(ARG_FUND_TYPE) ?: FUND_TYPE_DEFLATION
        fundTitle = arguments?.getString(ARG_FUND_TITLE)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_set_allocation, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initializeViews(view)
        loadOptionsAndPreferences()
    }

    private fun initializeViews(view: View) {
        titleText = view.findViewById(R.id.title_text)
        totalPercentageText = view.findViewById(R.id.total_percentage_text)
        allocationInputsContainer = view.findViewById(R.id.allocation_inputs_container)
        availableAllocationsContainer = view.findViewById(R.id.available_allocations_container)
        setAllocationButton = view.findViewById(R.id.set_allocation_button)

        titleText.text = fundTitle?.let { "Set $it Preferences" } ?: "Set Allocation Preferences"
        setAllocationButton.setOnClickListener { setAllocation() }

        updateUI()
        updateAllocationInputs()
        updateAvailableAllocations()
    }

    private fun loadOptionsAndPreferences() {
        lifecycleScope.launch {
            try {
                val address = SecureWalletManager.getWalletAddress(requireContext())
                val (options, current) = withContext(Dispatchers.IO) {
                    if (isCaretaker()) {
                        val opts = Allocation.allocationOptions(StreamId.STREAM_ID_HUMAN).map { it.id to it.description }
                        val voter = if (address.isNullOrEmpty()) emptyList() else Allocation.voterAllocations(StreamId.STREAM_ID_HUMAN, address)
                        opts to voter
                    } else {
                        val opts = Allocation.allocationOptions(StreamId.STREAM_ID_CAPITAL).map { it.id to it.description }
                        val voter = if (address.isNullOrEmpty()) emptyList() else Allocation.voterAllocations(StreamId.STREAM_ID_CAPITAL, address)
                        opts to voter
                    }
                }
                if (!isAdded) return@launch

                allocationOptions.clear()
                allocationOptions.addAll(options)

                selectedAllocations.clear()
                for ((optionId, percent) in current) {
                    val name = allocationOptions.find { it.first == optionId }?.second ?: "Option $optionId"
                    selectedAllocations.add(AllocationInput(optionId, name, percent.toInt()))
                }

                updateUI()
                updateAllocationInputs()
                updateAvailableAllocations()
            } catch (e: Exception) {
                Log.e(TAG, "Error loading allocation options", e)
            }
        }
    }

    private fun addSelectedAllocation(option: Pair<Long, String>) {
        if (selectedAllocations.any { it.optionId == option.first }) {
            TxResult.message(requireContext(), "Couldn't continue", "Allocation already added")
            return
        }
        selectedAllocations.add(AllocationInput(option.first, option.second, 0))
        updateUI()
        updateAllocationInputs()
        updateAvailableAllocations()
    }

    private fun updateAvailableAllocations() {
        availableAllocationsContainer.removeAllViews()
        var currentRow: LinearLayout? = null
        var currentRowWidth = 0
        val maxRowWidth = resources.displayMetrics.widthPixels - 64

        for (option in allocationOptions) {
            if (selectedAllocations.any { it.optionId == option.first }) continue
            val chip = createAllocationChip(option)
            val chipWidth = option.second.length * 12 + 80
            if (currentRow == null || currentRowWidth + chipWidth > maxRowWidth) {
                currentRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
                availableAllocationsContainer.addView(currentRow)
                currentRowWidth = 0
            }
            currentRow.addView(chip)
            currentRowWidth += chipWidth
        }
    }

    private fun createAllocationChip(option: Pair<Long, String>): Button {
        return Button(context).apply {
            text = option.second
            textSize = 14f
            setBackgroundColor(0xFFE0E0E0.toInt())
            setTextColor(0xFF333333.toInt())
            setPadding(16, 8, 16, 8)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(4, 4, 4, 4) }
            stateListAnimator = null
            elevation = 2f
            setOnClickListener {
                setBackgroundColor(0xFF4CAF50.toInt())
                setTextColor(0xFFFFFFFF.toInt())
                postDelayed({ addSelectedAllocation(option) }, 150)
            }
        }
    }

    private fun removeAllocation(optionId: Long) {
        selectedAllocations.removeAll { it.optionId == optionId }
        updateUI()
        updateAllocationInputs()
        updateAvailableAllocations()
    }

    private fun updateAllocationPercentage(optionId: Long, percentage: Int) {
        selectedAllocations.find { it.optionId == optionId }?.percentage = percentage
        updateTotalAndButton()
    }

    private fun updateTotalAndButton() {
        totalPercentage = selectedAllocations.sumOf { it.percentage }
        totalPercentageText.text = "Total: $totalPercentage%"
        totalPercentageText.setTextColor(if (totalPercentage == 100) 0xFF4CAF50.toInt() else 0xFFFF0000.toInt())
        setAllocationButton.isEnabled = totalPercentage == 100 && selectedAllocations.isNotEmpty()
        setAllocationButton.text = if (totalPercentage == 100) "Set Allocation" else
            "Total must equal 100% ($totalPercentage%)"
    }

    private fun updateUI() = updateTotalAndButton()

    private fun updateAllocationInputs() {
        allocationInputsContainer.removeAllViews()
        for (allocation in selectedAllocations) {
            allocationInputsContainer.addView(createAllocationInputView(allocation))
        }
    }

    private fun createAllocationInputView(allocation: AllocationInput): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(20, 15, 20, 15)
            setBackgroundColor(0xFFE3F2FD.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(16, 8, 16, 8) }
        }

        val nameText = TextView(context).apply {
            text = allocation.name
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val percentageInput = EditText(context).apply {
            setText(allocation.percentage.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "%"
            layoutParams = LinearLayout.LayoutParams(100, ViewGroup.LayoutParams.WRAP_CONTENT)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val pct = s.toString().toIntOrNull() ?: 0
                    updateAllocationPercentage(allocation.optionId, pct.coerceIn(0, 100))
                }
            })
        }

        val removeButton = Button(context).apply {
            text = "-"
            layoutParams = LinearLayout.LayoutParams(80, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener { removeAllocation(allocation.optionId) }
        }

        container.addView(nameText)
        container.addView(percentageInput)
        container.addView(removeButton)
        return container
    }

    private fun setAllocation() {
        if (totalPercentage != 100 || selectedAllocations.isEmpty()) {
            TxResult.message(requireContext(), "Couldn't continue", "Total must equal 100%")
            return
        }
        val weights = selectedAllocations.map { it.optionId to it.percentage.toLong() }

        setAllocationButton.isEnabled = false
        TxFlow.run(
            fragment = this,
            action = "Set allocation",
            msgTypeUrl = "/earth.allocation.v1.MsgSetAllocations",
            onSuccess = {
                activity?.supportFragmentManager?.popBackStack()
            },
            onFinally = { updateTotalAndButton() },
        ) {
            SecureWalletManager.executeWithMnemonic(requireContext()) { mnemonic ->
                val key = EarthWallet.deriveKey(mnemonic)
                val creator = EarthWallet.address(key)
                val stream = if (isCaretaker()) StreamId.STREAM_ID_HUMAN else StreamId.STREAM_ID_CAPITAL
                val msg = Allocation.msgSetAllocations(creator, stream, weights)
                EarthTx.broadcast(key, listOf(msg))
            }
        }
    }
}
