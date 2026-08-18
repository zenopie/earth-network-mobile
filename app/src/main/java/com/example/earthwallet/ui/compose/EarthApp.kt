package network.erth.wallet.ui.compose

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import network.erth.wallet.R
import network.erth.wallet.ui.vendor.component.BlankBgScaffold

/**
 * The app shell.
 *
 * Their navigation model, not a bottom tab bar: home carries the balance and
 * four large actions, the top bar carries wallet identity and the way into
 * settings, and everything else is pushed on top with a back arrow. A tab bar
 * would put four permanent destinations on screen, and Earth only has one
 * destination someone returns to — the balance. The rest are things you do
 * once and leave.
 */
@Composable
fun EarthApp(
    /** Null until the first load returns. */
    state: WalletUiState?,
    activity: List<ActivityRow>?,
    version: String,
    onSendTx: (recipient: String, amount: String) -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val nav = rememberEarthNavController()
    var balancesVisible by remember { mutableStateOf(true) }

    // The rows arrive from the chain without knowing where a tap should go;
    // navigation is the shell's business, so it is attached here rather than
    // threaded through the view model.
    val rows = remember(activity, nav) {
        activity?.map { row ->
            row.copy(onClick = { nav.push(EarthRoute.TransactionDetail(row.txHash)) })
        }
    }

    val topBar: @Composable () -> Unit = {
        when (val route = nav.current) {
            EarthRoute.Home -> EarthMainTopBar(
                walletName = "Wallet",
                balancesVisible = balancesVisible,
                onToggleBalances = { balancesVisible = !balancesVisible },
                onSettings = { nav.push(EarthRoute.Settings) },
            )
            else -> EarthDetailTopBar(title = route.title(), onBack = { nav.pop() })
        }
    }

    BlankBgScaffold(modifier = modifier, topBar = topBar) { padding ->
        EarthContent(
            route = nav.current,
            nav = nav,
            state = state,
            activity = rows,
            version = version,
            balancesVisible = balancesVisible,
            onSendTx = onSendTx,
            onOpenUrl = onOpenUrl,
            padding = padding,
        )
    }
}

@Composable
private fun EarthContent(
    route: EarthRoute,
    nav: EarthNavController,
    state: WalletUiState?,
    activity: List<ActivityRow>?,
    version: String,
    balancesVisible: Boolean,
    onSendTx: (String, String) -> Unit,
    onOpenUrl: (String) -> Unit,
    padding: PaddingValues,
) {
    // Home draws its own activity list to the bottom edge, so it takes the
    // padding as content padding rather than as a margin — a list that stops
    // above the gesture bar looks clipped, one that scrolls under it does not.
    val inset = Modifier.padding(
        top = padding.calculateTopPadding(),
        bottom = if (route is EarthRoute.Home) 0.dp else padding.calculateBottomPadding(),
    )

    // Everything past home is only reachable after the load, so it takes the
    // resolved state and never has to render a "loading" it cannot reach.
    val loaded = state ?: WalletUiState.EMPTY

    when (route) {
        EarthRoute.Home -> HomeScreen(
            erthBalance = state?.let { formatUerth(it.balanceUerth) },
            anmlBalance = state?.let { it.anmlBalance ?: "0" },
            balancesVisible = balancesVisible,
            activity = activity,
            onReceive = { nav.push(EarthRoute.Receive) },
            onSend = { nav.push(EarthRoute.Send) },
            onEarn = { nav.push(EarthRoute.Earn) },
            onSwap = { nav.push(EarthRoute.Swap) },
            onSeeAllActivity = { nav.push(EarthRoute.Activity) },
            modifier = inset,
            contentPadding = padding,
        )

        EarthRoute.Receive -> ReceiveScreen(
            state = ReceiveUiState(address = loaded.address),
            modifier = inset,
        )

        EarthRoute.Send -> SendFlow(
            state = loaded,
            onSubmit = onSendTx,
            modifier = inset,
        )

        EarthRoute.Earn -> EarnScreen(
            stakedErth = formatUerth(loaded.stakedUerth),
            rewardsErth = formatUerth(loaded.rewardsUerth),
            hasRewards = loaded.rewardsUerth > 0,
            validators = emptyList(),
            onStake = {},
            onUnstake = {},
            onClaim = {},
            modifier = inset,
        )

        EarthRoute.Swap -> SwapScreen(
            erthBalance = formatUerth(loaded.balanceUerth),
            anmlBalance = loaded.anmlBalance ?: "0",
            modifier = inset,
        )

        EarthRoute.Activity -> ActivityScreen(rows = activity.orEmpty(), modifier = inset)

        EarthRoute.Settings -> SettingsScreen(
            items = settingsItems(nav, loaded),
            version = version,
            modifier = inset,
        )

        EarthRoute.About -> AboutScreen(
            version = version,
            onPrivacyPolicy = { onOpenUrl("https://erth.network/privacy") },
            onTerms = { onOpenUrl("https://erth.network/terms") },
            onSource = { onOpenUrl("https://github.com/zenopie/earth-network-mobile") },
            modifier = inset,
        )

        EarthRoute.AddressBook -> AddressBookScreen(
            contacts = emptyList(),
            onAdd = {},
            onSelect = {},
            modifier = inset,
        )

        EarthRoute.Personhood -> PersonhoodScreen(
            registered = loaded.registered,
            anmlBalance = loaded.anmlBalance,
            onRegister = {},
            onClaim = {},
            modifier = inset,
        )

        EarthRoute.Allocation -> AllocationScreen(
            humanShare = emptyList(),
            capitalShare = emptyList(),
            registered = loaded.registered,
            stakedUerth = loaded.stakedUerth,
            modifier = inset,
        )

        is EarthRoute.TransactionDetail -> TransactionDetailScreen(
            txHash = route.txHash,
            row = activity?.firstOrNull { it.txHash == route.txHash },
            onOpenExplorer = { onOpenUrl("https://explorer.erth.network/tx/${route.txHash}") },
            modifier = inset,
        )
    }
}

/**
 * The settings menu.
 *
 * Ordered by how often it is opened rather than by subsystem: identity first
 * because registering is the thing a new wallet is here to do, then the things
 * that hold data, then the things that are read once.
 */
private fun settingsItems(nav: EarthNavController, state: WalletUiState): List<SettingsItem> =
    listOf(
        SettingsItem(
            title = "Identity",
            subtitle = if (state.registered) "Verified human" else "Not registered",
            icon = R.drawable.ic_shield_check,
            onClick = { nav.push(EarthRoute.Personhood) },
        ),
        SettingsItem(
            title = "Allocations",
            subtitle = "Direct your share of the emission",
            icon = R.drawable.ic_pie_chart,
            onClick = { nav.push(EarthRoute.Allocation) },
        ),
        SettingsItem(
            title = "Address book",
            icon = R.drawable.ic_contacts_white,
            onClick = { nav.push(EarthRoute.AddressBook) },
        ),
        SettingsItem(
            title = "Activity",
            icon = R.drawable.ic_earnings,
            onClick = { nav.push(EarthRoute.Activity) },
        ),
        SettingsItem(
            title = "About",
            icon = R.drawable.ic_info,
            onClick = { nav.push(EarthRoute.About) },
        ),
    )

/** The title the detail bar shows for a pushed route. */
private fun EarthRoute.title(): String = when (this) {
    EarthRoute.Home -> "Earth"
    EarthRoute.Send -> "Send"
    EarthRoute.Receive -> "Receive"
    EarthRoute.Earn -> "Earn"
    EarthRoute.Swap -> "Swap"
    EarthRoute.Activity -> "Activity"
    EarthRoute.Settings -> "Settings"
    EarthRoute.AddressBook -> "Address book"
    EarthRoute.About -> "About"
    EarthRoute.Personhood -> "Identity"
    EarthRoute.Allocation -> "Allocations"
    is EarthRoute.TransactionDetail -> "Transaction"
}

internal fun formatUerth(micro: Long): String {
    val whole = micro / 1_000_000
    val frac = (micro % 1_000_000).toString().padStart(6, '0').trimEnd('0')
    return if (frac.isEmpty()) "%,d".format(whole) else "%,d.%s".format(whole, frac)
}
