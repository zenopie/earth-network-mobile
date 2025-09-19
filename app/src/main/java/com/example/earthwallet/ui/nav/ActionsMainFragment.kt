package com.example.earthwallet.ui.nav

import com.example.earthwallet.R
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import android.util.Log
import androidx.fragment.app.Fragment

class ActionsMainFragment : Fragment() {

    private var isGovernanceExpanded = false

    companion object {
        @JvmStatic
        fun newInstance(): ActionsMainFragment = ActionsMainFragment()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_actions_main, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Swap Tokens button
        val swapTokens = view.findViewById<Button>(R.id.btn_swap_tokens)
        swapTokens?.setOnClickListener {
            Log.d("ActionsMainFragment", "Swap Tokens button clicked")
            (activity as? com.example.earthwallet.ui.host.HostActivity)?.let {
                it.showFragment("swap")
            } ?: run {
                Toast.makeText(context, "Navigation not available", Toast.LENGTH_SHORT).show()
            }
        }

        // ANML Claim button - request parent HostActivity to show ANML fragment
        val anml = view.findViewById<Button>(R.id.btn_anml_claim)
        anml?.setOnClickListener {
            Log.d("ActionsMainFragment", "ANML Claim button clicked")
            (activity as? com.example.earthwallet.ui.host.HostActivity)?.let {
                it.showFragment("anml")
            } ?: run {
                Toast.makeText(context, "Navigation not available", Toast.LENGTH_SHORT).show()
            }
        }

        // Gas Station button - request parent HostActivity to show Gas Station fragment
        val gasStation = view.findViewById<Button>(R.id.btn_gas_station)
        gasStation?.setOnClickListener {
            Log.d("ActionsMainFragment", "Gas Station button clicked")
            (activity as? com.example.earthwallet.ui.host.HostActivity)?.let {
                it.showFragment("gas_station")
            } ?: run {
                Toast.makeText(context, "Navigation not available", Toast.LENGTH_SHORT).show()
            }
        }

        // Manage LP button - request parent HostActivity to show ManageLP fragment
        val manageLP = view.findViewById<Button>(R.id.btn_manage_lp)
        manageLP?.setOnClickListener {
            Log.d("ActionsMainFragment", "Manage LP button clicked")
            (activity as? com.example.earthwallet.ui.host.HostActivity)?.let {
                it.showFragment("managelp")
            } ?: run {
                Toast.makeText(context, "Navigation not available", Toast.LENGTH_SHORT).show()
            }
        }

        // Stake ERTH button - request parent HostActivity to show Staking fragment
        val stakeERTH = view.findViewById<Button>(R.id.btn_stake_erth)
        stakeERTH?.setOnClickListener {
            Log.d("ActionsMainFragment", "Stake ERTH button clicked")
            (activity as? com.example.earthwallet.ui.host.HostActivity)?.let {
                it.showFragment("staking")
            } ?: run {
                Toast.makeText(context, "Navigation not available", Toast.LENGTH_SHORT).show()
            }
        }

        // Governance button - toggle expansion of submenu
        val governance = view.findViewById<Button>(R.id.btn_governance)
        val governanceSubmenu = view.findViewById<LinearLayout>(R.id.governance_submenu)
        if (governance != null && governanceSubmenu != null) {
            governance.setOnClickListener {
                Log.d("ActionsMainFragment", "Governance button clicked")
                toggleGovernanceSubmenu(governanceSubmenu)
            }
        }

        // Caretaker Fund button
        val caretakerFund = view.findViewById<Button>(R.id.btn_caretaker_fund)
        caretakerFund?.setOnClickListener {
            Log.d("ActionsMainFragment", "Caretaker Fund button clicked")
            (activity as? com.example.earthwallet.ui.host.HostActivity)?.let {
                it.showFragment("caretaker_fund")
            } ?: run {
                Toast.makeText(context, "Navigation not available", Toast.LENGTH_SHORT).show()
            }
        }

        // Deflation Fund button
        val deflationFund = view.findViewById<Button>(R.id.btn_deflation_fund)
        deflationFund?.setOnClickListener {
            Log.d("ActionsMainFragment", "Deflation Fund button clicked")
            (activity as? com.example.earthwallet.ui.host.HostActivity)?.let {
                it.showFragment("deflation_fund")
            } ?: run {
                Toast.makeText(context, "Navigation not available", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun toggleGovernanceSubmenu(submenu: LinearLayout) {
        if (isGovernanceExpanded) {
            submenu.visibility = View.GONE
            isGovernanceExpanded = false
            Log.d("ActionsMainFragment", "Governance submenu collapsed")
        } else {
            submenu.visibility = View.VISIBLE
            isGovernanceExpanded = true
            Log.d("ActionsMainFragment", "Governance submenu expanded")
        }
    }
}