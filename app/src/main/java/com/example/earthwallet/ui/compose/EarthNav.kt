package network.erth.wallet.ui.compose

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList

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
    /** The four bottom-bar destinations. Only these appear in the bar. */
    sealed interface Tab : EarthRoute

    data object Home : Tab
    data object Earn : Tab
    data object Swap : Tab
    data object Activity : Tab

    data object Send : EarthRoute
    data object Receive : EarthRoute
    data object Settings : EarthRoute
    data object AddressBook : EarthRoute
    data object About : EarthRoute
    data object Personhood : EarthRoute
    data object Allocation : EarthRoute

    data class TransactionDetail(val txHash: String) : EarthRoute
}

/**
 * The back stack.
 *
 * Tabs replace the root rather than piling up: pressing Home, Earn, Home and
 * then back should leave the app, not walk the tab history backwards. Anything
 * pushed on top of a tab is a real push, so back returns to the tab it was
 * opened from.
 */
@Stable
class EarthNavController(initial: EarthRoute.Tab) {
    private val stack: SnapshotStateList<EarthRoute> = mutableStateListOf(initial)

    val current: EarthRoute get() = stack.last()

    /** The tab the current route belongs to, so the bar stays lit on a pushed screen. */
    val currentTab: EarthRoute.Tab get() = stack.first() as EarthRoute.Tab

    val canGoBack: Boolean get() = stack.size > 1

    fun push(route: EarthRoute) {
        if (stack.last() != route) stack.add(route)
    }

    fun selectTab(tab: EarthRoute.Tab) {
        stack.clear()
        stack.add(tab)
    }

    /** Returns false when there was nothing to pop, so the caller can let the system handle back. */
    fun pop(): Boolean {
        if (!canGoBack) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    /** Drops everything above the current tab — used after a transaction completes. */
    fun popToTab() {
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
    }
}

@Composable
fun rememberEarthNavController(initial: EarthRoute.Tab = EarthRoute.Home): EarthNavController {
    val controller = remember { EarthNavController(initial) }
    BackHandler(enabled = controller.canGoBack) { controller.pop() }
    return controller
}
