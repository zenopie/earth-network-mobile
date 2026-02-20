package network.erth.wallet.ui.nav

import network.erth.wallet.R
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
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

        // Swap Tokens
        view.findViewById<View>(R.id.btn_swap_tokens)?.setOnClickListener {
            (activity as? network.erth.wallet.ui.host.HostActivity)?.let {
                it.showFragment("swap")
            } ?: run {
                Toast.makeText(context, "Navigation not available", Toast.LENGTH_SHORT).show()
            }
        }

        // ANML Claim
        view.findViewById<View>(R.id.btn_anml_claim)?.setOnClickListener {
            (activity as? network.erth.wallet.ui.host.HostActivity)?.let {
                it.showFragment("anml")
            } ?: run {
                Toast.makeText(context, "Navigation not available", Toast.LENGTH_SHORT).show()
            }
        }

        // Manage LP
        view.findViewById<View>(R.id.btn_manage_lp)?.setOnClickListener {
            (activity as? network.erth.wallet.ui.host.HostActivity)?.let {
                it.showFragment("managelp")
            } ?: run {
                Toast.makeText(context, "Navigation not available", Toast.LENGTH_SHORT).show()
            }
        }

        // Stake ERTH
        view.findViewById<View>(R.id.btn_stake_erth)?.setOnClickListener {
            (activity as? network.erth.wallet.ui.host.HostActivity)?.let {
                it.showFragment("staking")
            } ?: run {
                Toast.makeText(context, "Navigation not available", Toast.LENGTH_SHORT).show()
            }
        }

        // Governance - toggle expansion of submenu
        val governanceSubmenu = view.findViewById<LinearLayout>(R.id.governance_submenu)
        val governanceArrow = view.findViewById<ImageView>(R.id.governance_arrow)
        view.findViewById<View>(R.id.btn_governance)?.setOnClickListener {
            if (governanceSubmenu != null) {
                toggleGovernanceSubmenu(governanceSubmenu, governanceArrow)
            }
        }

        // Caretaker Fund
        view.findViewById<View>(R.id.btn_caretaker_fund)?.setOnClickListener {
            (activity as? network.erth.wallet.ui.host.HostActivity)?.let {
                it.showFragment("caretaker_fund")
            } ?: run {
                Toast.makeText(context, "Navigation not available", Toast.LENGTH_SHORT).show()
            }
        }

        // Deflation Fund
        view.findViewById<View>(R.id.btn_deflation_fund)?.setOnClickListener {
            (activity as? network.erth.wallet.ui.host.HostActivity)?.let {
                it.showFragment("deflation_fund")
            } ?: run {
                Toast.makeText(context, "Navigation not available", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun toggleGovernanceSubmenu(submenu: LinearLayout, arrow: ImageView?) {
        if (isGovernanceExpanded) {
            submenu.visibility = View.GONE
            arrow?.rotation = 0f
            isGovernanceExpanded = false
        } else {
            submenu.visibility = View.VISIBLE
            arrow?.rotation = 90f
            isGovernanceExpanded = true
        }
    }
}