package network.erth.wallet.ui.pages.governance

import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.erth.wallet.R
import network.erth.earth.proto.allocation.StreamId
import network.erth.wallet.chain.Allocation
import network.erth.wallet.ui.components.PieChartView
import network.erth.wallet.wallet.services.SecureWalletManager
import com.google.android.material.tabs.TabLayout
import java.math.BigInteger

/**
 * Deflation Fund — the stake-weighted (plutocratic) allocation vote. "Actual"
 * shows how bonded stake is currently split across allocation options (from each
 * option's amount_allocated); "Preferred" shows the signed-in staker's own split
 * with a button to update it. Backed by the chain's stake-weighted allocation module.
 */
class DeflationFundFragment : Fragment() {

    companion object {
        private const val TAG = "DeflationFundFragment"

        @JvmStatic
        fun newInstance(): DeflationFundFragment = DeflationFundFragment()
    }

    private lateinit var actualAllocationSection: LinearLayout
    private lateinit var preferredAllocationSection: LinearLayout
    private lateinit var tabLayout: TabLayout
    private lateinit var titleTextView: TextView

    private val optionNames = mutableMapOf<Long, String>()
    private var actualAllocations: List<Pair<Long, Int>> = emptyList()
    private var userAllocations: List<Pair<Long, Int>> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_deflation_fund, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initializeViews(view)
        loadActualAllocations()
    }

    private fun initializeViews(view: View) {
        actualAllocationSection = view.findViewById(R.id.actual_allocation_section)
        preferredAllocationSection = view.findViewById(R.id.preferred_allocation_section)
        tabLayout = view.findViewById(R.id.tab_layout)
        titleTextView = view.findViewById(R.id.title_text)

        titleTextView.text = "Deflation Fund"
        setupTabs()
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
        actualAllocationSection.visibility = View.VISIBLE
        preferredAllocationSection.visibility = View.GONE
        loadActualAllocations()
    }

    private fun showPreferredAllocations() {
        actualAllocationSection.visibility = View.GONE
        preferredAllocationSection.visibility = View.VISIBLE
        loadUserAllocations()
    }

    private fun loadActualAllocations() {
        lifecycleScope.launch {
            try {
                val options = withContext(Dispatchers.IO) { Allocation.allocationOptions(StreamId.STREAM_ID_CAPITAL) }
                if (!isAdded) return@launch

                optionNames.clear()
                options.forEach { optionNames[it.id] = it.description }

                val total = options.fold(BigInteger.ZERO) { acc, o ->
                    acc + (o.amountAllocated.toBigIntegerOrNull() ?: BigInteger.ZERO)
                }
                actualAllocations = if (total > BigInteger.ZERO) {
                    options.map { o ->
                        val amt = o.amountAllocated.toBigIntegerOrNull() ?: BigInteger.ZERO
                        o.id to (amt * BigInteger.valueOf(100) / total).toInt()
                    }.filter { it.second > 0 }
                } else {
                    emptyList()
                }
                updateActualAllocationsUI()
            } catch (e: Exception) {
                Log.e(TAG, "Error loading actual allocations", e)
            }
        }
    }

    private fun loadUserAllocations() {
        lifecycleScope.launch {
            try {
                val address = SecureWalletManager.getWalletAddress(requireContext())
                val (options, voter) = withContext(Dispatchers.IO) {
                    val opts = Allocation.allocationOptions(StreamId.STREAM_ID_CAPITAL)
                    val v = if (address.isNullOrEmpty()) emptyList() else Allocation.voterAllocations(StreamId.STREAM_ID_CAPITAL, address)
                    opts to v
                }
                if (!isAdded) return@launch
                optionNames.clear()
                options.forEach { optionNames[it.id] = it.description }
                userAllocations = voter.map { it.first to it.second.toInt() }
                updatePreferredAllocationsUI()
            } catch (e: Exception) {
                Log.e(TAG, "Error loading user allocations", e)
            }
        }
    }

    private fun updateActualAllocationsUI() {
        actualAllocationSection.removeAllViews()
        if (actualAllocations.isEmpty()) {
            actualAllocationSection.addView(infoText("No allocations have been set yet."))
            return
        }
        createPieChart(actualAllocations)?.let {
            it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 600).apply {
                setMargins(40, 20, 40, 20)
                gravity = Gravity.CENTER_HORIZONTAL
            }
            actualAllocationSection.addView(it)
        }
        for ((id, pct) in actualAllocations) actualAllocationSection.addView(createAllocationItemView(id, pct))
    }

    private fun updatePreferredAllocationsUI() {
        preferredAllocationSection.removeAllViews()
        if (userAllocations.isNotEmpty()) {
            createPieChart(userAllocations)?.let {
                it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 600).apply {
                    setMargins(40, 20, 40, 20)
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                preferredAllocationSection.addView(it)
            }
            for ((id, pct) in userAllocations) preferredAllocationSection.addView(createAllocationItemView(id, pct))
        } else {
            preferredAllocationSection.addView(infoText("No preferences set yet."))
        }

        val setPrefsButton = Button(context).apply {
            text = if (userAllocations.isNotEmpty()) "Update Preferences" else "Set Preferences"
            setTextColor(0xFFFFFFFF.toInt())
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF4CAF50.toInt())
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (56 * resources.displayMetrics.density).toInt()
            ).apply {
                val m = (20 * resources.displayMetrics.density).toInt()
                setMargins(m, m, m, (10 * resources.displayMetrics.density).toInt())
            }
            setOnClickListener { openSetAllocation() }
        }
        preferredAllocationSection.addView(setPrefsButton)
    }

    private fun infoText(msg: String) = TextView(context).apply {
        text = msg
        textSize = 14f
        setTextColor(0xFF666666.toInt())
        setPadding(20, 20, 20, 20)
    }

    private fun createAllocationItemView(optionId: Long, percent: Int): View {
        val itemLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(20, 15, 20, 15)
            setBackgroundColor(0x10000000)
        }
        val colorIndicator = View(context).apply {
            setBackgroundColor(getPieChartColor(optionId))
            layoutParams = LinearLayout.LayoutParams(20, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                setMargins(0, 0, 12, 0)
            }
        }
        val nameText = TextView(context).apply {
            text = getAllocationName(optionId)
            textSize = 16f
            setTextColor(0xFF333333.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val valueText = TextView(context).apply {
            text = "$percent%"
            textSize = 16f
            setTextColor(0xFF1976D2.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        itemLayout.addView(colorIndicator)
        itemLayout.addView(nameText)
        itemLayout.addView(valueText)
        return itemLayout
    }

    private fun getAllocationName(optionId: Long): String = optionNames[optionId] ?: "Option $optionId"

    private fun getPieChartColor(optionId: Long): Int {
        val colors = intArrayOf(
            0xFF4CAF50.toInt(), 0xFF8BC34A.toInt(), 0xFFFF9800.toInt(),
            0xFFCDDC39.toInt(), 0xFF009688.toInt(), 0xFF795548.toInt()
        )
        return colors[((optionId - 1).coerceAtLeast(0) % colors.size).toInt()]
    }

    private fun createPieChart(allocations: List<Pair<Long, Int>>): PieChartView? {
        return try {
            val pieChart = PieChartView(requireContext())
            val slices = allocations.filter { it.second > 0 }.map { (id, pct) ->
                PieChartView.PieSlice(getAllocationName(id), pct.toFloat(), getPieChartColor(id))
            }
            pieChart.setData(slices)
            pieChart
        } catch (e: Exception) {
            Log.e(TAG, "Error creating pie chart", e)
            null
        }
    }

    private fun openSetAllocation() {
        val fragment = SetAllocationFragment.newInstance(
            SetAllocationFragment.FUND_TYPE_DEFLATION, "Deflation Fund"
        )
        activity?.supportFragmentManager
            ?.beginTransaction()
            ?.replace(R.id.host_content, fragment)
            ?.addToBackStack(null)
            ?.commit()
    }
}
