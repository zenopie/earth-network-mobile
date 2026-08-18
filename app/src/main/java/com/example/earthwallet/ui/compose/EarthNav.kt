package network.erth.wallet.ui.compose

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import network.erth.wallet.R

/**
 * Every place the app can be.
 *
 * A sealed interface rather than strings: a destination that carries an
 * argument carries it as a field, so a typo is a compile error instead of a
 * blank screen. Zodl reaches for navigation-compose here, but that library
 * earns its keep on deep links and argument encoding, and this app has neither
 * yet — what it does need is a back stack, which is fifteen lines.
 */
sealed interface EarthRoute {

    /**
     * The tabs.
     *
     * Two axes, not one: what this wallet holds, and what the protocol does.
     * Wallet and Earn are yours — a balance, a stake, rewards accruing. Swap,
     * Govern and Explore are the chain's, and you visit them to act on it rather
     * than to check on yourself. Collapsing those into one list was what buried
     * liquidity, allocations and the explorer three taps deep in a settings menu.
     */
    sealed interface Tab : EarthRoute {
        val label: String
        val icon: Int
    }

    data object Wallet : Tab {
        override val label = "Wallet"
        override val icon = R.drawable.ic_home_wallet
    }

    data object Earn : Tab {
        override val label = "Earn"
        override val icon = R.drawable.ic_home_earn
    }

    data object Swap : Tab {
        override val label = "Swap"
        override val icon = R.drawable.ic_home_swap
    }

    /** Earth's governance is its two allocation streams; there is no other kind. */
    data object Govern : Tab {
        override val label = "Govern"
        override val icon = R.drawable.ic_home_govern
    }

    data object Explore : Tab {
        override val label = "Explore"
        override val icon = R.drawable.ic_home_explore
    }

    // Pushed on top of a tab.
    data object Send : EarthRoute
    data object Receive : EarthRoute
    data object Activity : EarthRoute
    data object Liquidity : EarthRoute
    data object Settings : EarthRoute
    data object AddressBook : EarthRoute
    data object About : EarthRoute
    data object Personhood : EarthRoute

    data class TransactionDetail(val txHash: String) : EarthRoute
}

/** The tabs, in bar order. */
val EARTH_TABS = listOf(
    EarthRoute.Wallet,
    EarthRoute.Earn,
    EarthRoute.Swap,
    EarthRoute.Govern,
    EarthRoute.Explore,
)

/**
 * The back stack.
 *
 * Each tab keeps its own stack, so leaving a tab mid-way through something and
 * coming back returns you to where you were rather than to the tab's root.
 * Back within a tab pops that tab; back at a tab's root leaves the app rather
 * than walking the tab history, because tab order is not history.
 */
@Stable
class EarthNavController(initial: EarthRoute.Tab) {

    private val stacks: Map<EarthRoute.Tab, SnapshotStateList<EarthRoute>> =
        EARTH_TABS.associateWith { mutableStateListOf<EarthRoute>(it) }

    private val selected = mutableStateListOf(initial)

    val currentTab: EarthRoute.Tab get() = selected.first() as EarthRoute.Tab

    private val stack: SnapshotStateList<EarthRoute> get() = stacks.getValue(currentTab)

    val current: EarthRoute get() = stack.last()

    val canGoBack: Boolean get() = stack.size > 1

    fun push(route: EarthRoute) {
        if (stack.last() != route) stack.add(route)
    }

    fun selectTab(tab: EarthRoute.Tab) {
        // Tapping the tab you are already on returns it to its root — the
        // standard escape hatch out of a screen you pushed and want out of.
        if (tab == currentTab) {
            popToRoot()
            return
        }
        selected[0] = tab
    }

    /** Returns false when there was nothing to pop, so the caller can let the system handle back. */
    fun pop(): Boolean {
        if (!canGoBack) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun popToRoot() {
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
    }
}

@Composable
fun rememberEarthNavController(initial: EarthRoute.Tab = EarthRoute.Wallet): EarthNavController {
    val controller = remember { EarthNavController(initial) }
    BackHandler(enabled = controller.canGoBack) { controller.pop() }
    return controller
}
