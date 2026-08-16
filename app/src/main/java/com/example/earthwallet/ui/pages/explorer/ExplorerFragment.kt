package network.erth.wallet.ui.pages.explorer

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.erth.wallet.R
import network.erth.wallet.chain.Explorer

/**
 * Chain explorer: recent blocks and transactions, the validator set, and
 * registrations by issuing country, plus a search box for a height, tx hash or
 * address.
 *
 * All of this is public LCD data, so nothing here touches the wallet or asks
 * for a PIN — the explorer is readable with no wallet set up at all.
 */
class ExplorerFragment : Fragment() {

    companion object {
        private const val TAG = "ExplorerFragment"

        @JvmStatic
        fun newInstance(): ExplorerFragment = ExplorerFragment()
    }

    private lateinit var chainIdLabel: TextView
    private lateinit var heightLabel: TextView
    private lateinit var blockTimeLabel: TextView
    private lateinit var searchInput: EditText
    private lateinit var searchError: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? = inflater.inflate(R.layout.fragment_explorer, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        chainIdLabel = view.findViewById(R.id.explorer_chain_id)
        heightLabel = view.findViewById(R.id.explorer_height)
        blockTimeLabel = view.findViewById(R.id.explorer_block_time)
        searchInput = view.findViewById(R.id.explorer_search_input)
        searchError = view.findViewById(R.id.explorer_search_error)

        val tabs: TabLayout = view.findViewById(R.id.explorer_tabs)
        val pager: ViewPager2 = view.findViewById(R.id.explorer_pager)
        pager.adapter = ExplorerTabsAdapter(this)
        TabLayoutMediator(tabs, pager) { tab, position ->
            tab.text = when (position) {
                ExplorerTabsAdapter.TAB_BLOCKS -> "Blocks"
                ExplorerTabsAdapter.TAB_TXS -> "Txs"
                ExplorerTabsAdapter.TAB_VALIDATORS -> "Validators"
                ExplorerTabsAdapter.TAB_REGISTRATIONS -> "People"
                else -> "Tab $position"
            }
        }.attach()

        view.findViewById<Button>(R.id.explorer_search_button).setOnClickListener { search() }
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                search()
                true
            } else false
        }

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val status = withContext(Dispatchers.IO) { Explorer.status() }
                if (status == null) {
                    chainIdLabel.text = "offline"
                    heightLabel.text = "—"
                    blockTimeLabel.text = "—"
                    return@launch
                }
                chainIdLabel.text = status.chainId
                heightLabel.text = ExplorerFormat.thousands(status.height)
                blockTimeLabel.text = ExplorerFormat.ago(status.time)
            } catch (e: Exception) {
                Log.e(TAG, "Status query failed", e)
            }
        }
    }

    /**
     * Routes one search box to three destinations.
     *
     * The term is classified rather than asking which kind of id it is: a height
     * is digits, a tx hash is 64 hex characters, an address starts with the
     * chain prefix. Anything else is rejected with a message instead of a
     * pointless round trip.
     */
    private fun search() {
        val term = searchInput.text.toString().trim()
        val hit = Explorer.classifySearch(term)
        if (hit == null) {
            searchError.text = "Enter a block height, a 64-character tx hash, or an earth1… address"
            searchError.visibility = View.VISIBLE
            return
        }
        searchError.visibility = View.GONE
        hideKeyboard()
        when (hit.kind) {
            Explorer.SearchKind.BLOCK -> ExplorerNav.openBlock(activity, hit.value.toLong())
            Explorer.SearchKind.TX -> ExplorerNav.openTx(activity, hit.value)
            Explorer.SearchKind.ACCOUNT -> ExplorerNav.openAccount(activity, hit.value)
        }
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as? InputMethodManager
        imm?.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }
}

/** ViewPager2 adapter for the explorer tabs. */
class ExplorerTabsAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {

    companion object {
        const val TAB_BLOCKS = 0
        const val TAB_TXS = 1
        const val TAB_VALIDATORS = 2
        const val TAB_REGISTRATIONS = 3
    }

    override fun createFragment(position: Int): Fragment = when (position) {
        TAB_BLOCKS -> ExplorerBlocksFragment.newInstance()
        TAB_TXS -> ExplorerTxsFragment.newInstance()
        TAB_VALIDATORS -> ExplorerValidatorsFragment.newInstance()
        TAB_REGISTRATIONS -> ExplorerRegistrationsFragment.newInstance()
        else -> ExplorerBlocksFragment.newInstance()
    }

    override fun getItemCount(): Int = 4
}
