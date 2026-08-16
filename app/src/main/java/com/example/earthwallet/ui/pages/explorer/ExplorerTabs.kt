package network.erth.wallet.ui.pages.explorer

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.erth.wallet.R
import network.erth.wallet.chain.Personhood
import network.erth.wallet.chain.Explorer

/**
 * Shared scaffolding for the explorer tabs: one list, pull-to-refresh, and an
 * empty/error line. Subclasses fetch on a background thread in [load] and apply
 * the result on the main thread in [apply].
 */
abstract class ExplorerListFragment<T> : Fragment() {

    protected lateinit var list: RecyclerView
    private lateinit var refresh: SwipeRefreshLayout
    private lateinit var empty: TextView

    /** Runs off the main thread. */
    protected abstract suspend fun load(): T

    /** Runs on the main thread with the result of [load]. Returns true if non-empty. */
    protected abstract fun apply(data: T): Boolean

    /** Shown when [apply] reports no rows. */
    protected open fun emptyMessage(): String = "Nothing to show"

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? = inflater.inflate(R.layout.fragment_explorer_list, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        list = view.findViewById(R.id.explorer_list)
        refresh = view.findViewById(R.id.explorer_list_refresh)
        empty = view.findViewById(R.id.explorer_list_empty)
        list.layoutManager = LinearLayoutManager(requireContext())
        refresh.setOnRefreshListener { reload() }
        reload()
    }

    protected fun reload() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val data = withContext(Dispatchers.IO) { load() }
                val hasRows = apply(data)
                empty.visibility = if (hasRows) View.GONE else View.VISIBLE
                empty.text = emptyMessage()
            } catch (e: Exception) {
                Log.e("Explorer", "Load failed", e)
                empty.visibility = View.VISIBLE
                // Naming the node makes the common failure — pointing at an LCD
                // that isn't running — diagnosable without a logcat.
                empty.text = "Could not reach the chain.\n${e.message ?: ""}"
            } finally {
                refresh.isRefreshing = false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (this::list.isInitialized) reload()
    }
}

/** Recent blocks, newest first. */
class ExplorerBlocksFragment : ExplorerListFragment<Pair<List<Explorer.Block>, Map<String, String>>>() {

    companion object {
        @JvmStatic
        fun newInstance() = ExplorerBlocksFragment()
    }

    private val adapter = BlockAdapter(emptyList(), emptyMap()) { block ->
        ExplorerNav.openBlock(activity, block.height)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        list.adapter = adapter
    }

    override suspend fun load(): Pair<List<Explorer.Block>, Map<String, String>> = coroutineScope {
        // The moniker lookup is three more requests; overlap it with the blocks
        // rather than making the list wait on it.
        val blocks = async { Explorer.recentBlocks(15) }
        val monikers = async(Dispatchers.IO) { proposerMonikers() }
        blocks.await() to monikers.await()
    }

    override fun apply(data: Pair<List<Explorer.Block>, Map<String, String>>): Boolean {
        adapter.submit(data.first, data.second)
        return data.first.isNotEmpty()
    }

    override fun emptyMessage() = "No blocks yet"

    /**
     * Maps consensus addresses to monikers so blocks say who proposed them.
     *
     * The staking module knows monikers but not consensus addresses, so the
     * address is derived from each validator's consensus pubkey.
     */
    private fun proposerMonikers(): Map<String, String> =
        Explorer.validators().validators
            .filter { it.consAddress.isNotEmpty() && it.moniker.isNotEmpty() }
            .associate { it.consAddress to it.moniker }
}

/** Chain-wide recent transactions. */
class ExplorerTxsFragment : ExplorerListFragment<List<Explorer.Tx>>() {

    companion object {
        @JvmStatic
        fun newInstance() = ExplorerTxsFragment()
    }

    private val adapter = TxAdapter(emptyList()) { tx ->
        ExplorerNav.openTx(activity, tx.hash)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        list.adapter = adapter
    }

    override suspend fun load(): List<Explorer.Tx> = Explorer.recentTxs(25)

    override fun apply(data: List<Explorer.Tx>): Boolean {
        adapter.submit(data)
        return data.isNotEmpty()
    }

    override fun emptyMessage() = "No transactions indexed yet"
}

/** Validator set with stake, uptime and slashing status. */
class ExplorerValidatorsFragment : ExplorerListFragment<Explorer.ValidatorSet>() {

    companion object {
        @JvmStatic
        fun newInstance() = ExplorerValidatorsFragment()
    }

    private val adapter = ValidatorAdapter(emptyList(), null) { v ->
        ExplorerNav.openValidator(activity, v.operator)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        list.adapter = adapter
    }

    override suspend fun load(): Explorer.ValidatorSet = Explorer.validators()

    override fun apply(data: Explorer.ValidatorSet): Boolean {
        adapter.submit(data.validators, data.params)
        return data.validators.isNotEmpty()
    }

    override fun emptyMessage() = "No validators found"
}

/**
 * Registrations per issuing country.
 *
 * The web explorer draws these on a world map; here it is a ranked list with
 * proportional bars. A vector atlas would be a large asset and unreadable at
 * phone width, and the ranking is what the map was being read for anyway.
 */
class ExplorerRegistrationsFragment : ExplorerListFragment<List<Personhood.CountryCount>>() {

    companion object {
        @JvmStatic
        fun newInstance() = ExplorerRegistrationsFragment()
    }

    private val adapter = CountryAdapter(emptyList())

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        list.adapter = adapter
    }

    override suspend fun load(): List<Personhood.CountryCount> = Personhood.registrationCountries()

    override fun apply(data: List<Personhood.CountryCount>): Boolean {
        adapter.submit(data)
        return data.isNotEmpty()
    }

    override fun emptyMessage() = "No registrations yet"
}
