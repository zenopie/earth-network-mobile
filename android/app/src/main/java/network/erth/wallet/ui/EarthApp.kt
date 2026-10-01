package network.erth.wallet.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.erth.wallet.Constants
import network.erth.wallet.R
import network.erth.wallet.chain.Bank
import network.erth.wallet.privacy.PrivacyAutomation
import network.erth.wallet.privacy.PrivacySession
import network.erth.wallet.privacy.tx.PrivateMsgs
import network.erth.wallet.ui.designsystem.component.BlankBgScaffold
import network.erth.wallet.ui.designsystem.theme.colors.EarthColors
import network.erth.wallet.ui.earn.EarnViewModel
import network.erth.wallet.ui.explore.ExploreViewModel
import network.erth.wallet.ui.govern.AllocationViewModel
import network.erth.wallet.ui.navigation.EarthDetailTopBar
import network.erth.wallet.ui.navigation.EarthMainTopBar
import network.erth.wallet.ui.navigation.EarthRoute
import network.erth.wallet.ui.navigation.EarthTabBar
import network.erth.wallet.ui.navigation.rememberEarthNavController
import network.erth.wallet.ui.registration.RegistrationActivity
import network.erth.wallet.ui.swap.MarketsViewModel
import network.erth.wallet.ui.theme.EarthAccent
import network.erth.wallet.ui.tx.TxConfirmDetails
import network.erth.wallet.ui.tx.TxController
import network.erth.wallet.ui.tx.TxSheets
import network.erth.wallet.ui.wallet.WalletViewModel
import network.erth.wallet.ui.wallet.WalletsViewModel
import network.erth.wallet.wallet.SecureWalletManager

/**
 * The app shell.
 *
 * Their chrome — a top bar carrying wallet identity and the way into settings,
 * screens pushed on top with a back arrow — over a tab bar they do not have,
 * because Earth has a second axis they do not: what this wallet holds, and what
 * the protocol does. Zcash is only ever the first, so one home screen is enough
 * for them; folding markets, allocations and the explorer into a settings menu
 * buried half the chain.
 */
@Composable
fun EarthApp(
    version: String,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val nav = rememberEarthNavController()
    var balancesVisible by remember { mutableStateOf(true) }

    /**
     * Bumped whenever the selected wallet changes.
     *
     * Everything per-wallet keys off this rather than off the tab, because the
     * tab is not what changed. Without it a tab that is not currently on screen
     * keeps the previous wallet's figures and shows them the next time it is
     * opened — and a balance belonging to another address is worse than no
     * balance, since nothing about it looks wrong.
     */
    var walletEpoch by remember { mutableIntStateOf(0) }

    val wallet: WalletViewModel = viewModel()
    val earn: EarnViewModel = viewModel()
    val allocation: AllocationViewModel = viewModel()
    val markets: MarketsViewModel = viewModel()
    val wallets: WalletsViewModel = viewModel()
    val explore: ExploreViewModel = viewModel()
    val tx: TxController = viewModel()

    val state by wallet.state.collectAsStateWithLifecycle()
    val activity by wallet.activity.collectAsStateWithLifecycle()
    val earnState by earn.state.collectAsStateWithLifecycle()
    val allocationState by allocation.state.collectAsStateWithLifecycle()
    val marketsState by markets.state.collectAsStateWithLifecycle()
    val exploreState by explore.state.collectAsStateWithLifecycle()
    val walletsState by wallets.state.collectAsStateWithLifecycle()
    val draftMnemonic by wallets.draftMnemonic.collectAsStateWithLifecycle()
    val walletsError by wallets.error.collectAsStateWithLifecycle()

    // The private automations (daily claim, caretaker refresh, matured
    // unbonding claims) need the keys, so they live exactly as long as this
    // unlocked shell does, and restart on a wallet switch.
    LaunchedEffect(walletEpoch) {
        PrivacySession.clear()
        withContext(Dispatchers.IO) { PrivacyAutomation.loop(context.applicationContext) }
    }

    // Each tab loads when it is first shown rather than all at once on start.
    // Five tabs' worth of queries against one node on launch is a slow launch,
    // and four of them are for screens nobody may open.
    LaunchedEffect(nav.currentTab, walletEpoch) {
        when (nav.currentTab) {
            EarthRoute.Wallet -> wallet.refresh()
            // Earn shows pools now, so it needs the market data too.
            EarthRoute.Earn -> { earn.refresh(); markets.refresh() }
            EarthRoute.Swap -> markets.refresh()
            EarthRoute.Govern -> allocation.refresh()
        }
    }

    /**
     * Re-read everything the screen in front of you shows, and hand back the
     * reads so a caller that wants to wait can.
     *
     * Pull-to-refresh is that caller: its spinner has to stay down until the
     * reads it started have finished, which is what iOS's
     * `.refreshable { await … }` gets for free.
     *
     * The explorer is asked for by route rather than by tab — it is pushed
     * rather than a tab, and keyed off the tab a pull there would refresh the
     * balance behind it and nothing you could see.
     */
    val refreshCurrent: () -> List<Job> = {
        if (nav.current == EarthRoute.Explore) {
            listOf(explore.refresh())
        } else {
            buildList {
                add(wallet.refresh())
                when (nav.currentTab) {
                    EarthRoute.Earn -> { add(earn.refresh()); add(markets.refresh()) }
                    EarthRoute.Govern -> add(allocation.refresh())
                    EarthRoute.Swap -> add(markets.refresh())
                    else -> Unit
                }
            }
        }
    }

    val refreshAll = { refreshCurrent(); Unit }

    /**
     * Switch to another wallet, or re-key after creating one.
     *
     * Forget first, then reload. Tabs that are not on screen are cleared too
     * and reload when next shown — refetching five tabs' worth of queries for
     * screens that may never be opened is what the per-tab loading exists to
     * avoid.
     *
     * [index] of -1 means the store has already changed selection (createWallet
     * selects what it creates), so only the invalidation is needed.
     *
     * Markets and Explore are deliberately not cleared: pools, blocks and
     * validators belong to the chain, not to whoever is looking at them.
     */
    val invalidateWallet = {
        wallet.clear()
        earn.clear()
        allocation.clear()
        walletEpoch++
        wallet.refresh()
    }

    val switchWallet: (Int) -> Unit = { index ->
        if (index < 0) {
            invalidateWallet()
        } else {
            wallets.select(index) { invalidateWallet() }
        }
    }

    // Its own activity, because NFC foreground dispatch is granted per-activity
    // and whatever owns it has to be on top when the passport touches the
    // phone. It finishes back here rather than into a shell of its own.
    val registration = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        // Registration changes the balance, the identity and the activity list,
        // so coming back has to re-read rather than resume whatever was on
        // screen when the flow started. Ignoring the result code on purpose: a
        // cancelled scan can still have received a gas grant.
        invalidateWallet()
    }

    val openRegistration = {
        runCatching {
            registration.launch(Intent(context, RegistrationActivity::class.java))
        }
        Unit
    }

    // Private: a membership proof for today's claim scope, the ANML minted
    // to a note, the fee paid from shielded ERTH. Nothing names this wallet.
    val claimAnml = {
        tx.requestPrivate(
            details = TxConfirmDetails(
                action = "Claim ANML",
                msgTypeUrl = PrivateMsgs.CLAIM_ANML,
                balanceUerth = 0L,
            ),
            shieldedErth = state?.shieldedErthUerth ?: 0L,
            onSuccess = refreshAll,
            run = { ctx -> PrivacySession.wallet(ctx).claimAnml().hash },
        )
    }

    val rows = remember(activity, nav) {
        activity?.map { row ->
            row.copy(onClick = { nav.push(EarthRoute.TransactionDetail(row.txHash)) })
        }
    }

    BlankBgScaffold(
        modifier = modifier,
        topBar = {
            when (val route = nav.current) {
                is EarthRoute.Tab -> EarthMainTopBar(
                    // The Wallet tab is named for whose wallet it is; the other
                    // tabs are named for what they do. "Wallet" over a balance
                    // says nothing the balance does not.
                    walletName = if (route == EarthRoute.Wallet) {
                        state?.name?.takeIf { it.isNotBlank() } ?: "Wallet"
                    } else {
                        route.label
                    },
                    balancesVisible = balancesVisible,
                    onToggleBalances = { balancesVisible = !balancesVisible },
                    onSettings = { nav.push(EarthRoute.Settings) },
                    // Wallet only. Earn had it too, but balancesVisible is read
                    // by HomeScreen alone, so the eye there toggled state that
                    // masked nothing.
                    showsBalances = route == EarthRoute.Wallet,
                    // No tab carries an action any more. Swap had one for
                    // liquidity; pools live on Earn now, behind a selector.
                    tabAction = null,
                )
                else -> EarthDetailTopBar(title = route.title(), onBack = { nav.pop() })
            }
        },
        bottomBar = {
            // The bar is for switching tabs, so it goes away on a pushed screen
            // — leaving it there invites a tap that discards whatever is
            // half-entered on the screen above it.
            if (nav.current is EarthRoute.Tab) {
                EarthTabBar(current = nav.currentTab, onSelect = nav::selectTab)
            }
        },
    ) { padding ->
        // Pull down to re-read the chain. iOS carries `.refreshable` on every
        // screen that reads from the chain, and these are the same screens:
        // the four tabs, the activity list, and the explorer. The rest show
        // what is already on the device and have nothing to re-read.
        //
        // One box either way, with the gesture disabled where it has no
        // meaning, rather than a box that comes and goes — the screens below
        // keep their own modifiers and nothing about the layout moves as you
        // navigate.
        val route = nav.current
        val pullable = route is EarthRoute.Tab ||
            route == EarthRoute.Activity ||
            route == EarthRoute.Explore

        // Only a pull spins this. The app also re-reads on resume and on a tab
        // switch, and an indicator dropping down by itself for those reads
        // looks like the screen reloading on its own.
        val pullScope = rememberCoroutineScope()
        val pullState = rememberPullToRefreshState()
        var pulling by remember { mutableStateOf(false) }

        Box(
            Modifier
                .fillMaxSize()
                .pullToRefresh(
                    isRefreshing = pulling,
                    state = pullState,
                    enabled = pullable,
                    onRefresh = {
                        // The reads belong to the view models and outlive this
                        // scope, so leaving the screen mid-pull abandons the
                        // spinner rather than the read.
                        val reads = refreshCurrent()
                        pullScope.launch {
                            pulling = true
                            try {
                                reads.joinAll()
                            } finally {
                                pulling = false
                            }
                        }
                    },
                ),
        ) {
            EarthContent(
                route = route,
                nav = nav,
                tx = tx,
                state = state,
                activity = rows,
                earnState = earnState,
                allocationState = allocationState,
                marketsState = marketsState,
                exploreState = exploreState,
                earn = earn,
                allocation = allocation,
                markets = markets,
                explore = explore,
                wallets = wallets,
                walletsState = walletsState,
                draftMnemonic = draftMnemonic,
                walletsError = walletsError,
                onSwitchWallet = switchWallet,
                onClaimAnml = claimAnml,
                onRegister = openRegistration,
                version = version,
                balancesVisible = balancesVisible,
                onOpenUrl = onOpenUrl,
                onRefresh = refreshAll,
                padding = padding,
            )

            if (pullable) {
                PullToRefreshDefaults.Indicator(
                    state = pullState,
                    isRefreshing = pulling,
                    containerColor = EarthColors.Surfaces.bgPrimary,
                    color = EarthAccent.ink,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        // Below the top bar rather than under it: the box is
                        // full-bleed, the bar is not part of it.
                        .padding(top = padding.calculateTopPadding()),
                )
            }
        }
    }

    // The free-gas gate. It hung off TxFlow before, so it applies to every
    // transaction from an underfunded account rather than only to
    // registration — which matters because registration is not necessarily
    // the first thing a new human tries.
    TxSheets(
        controller = tx,
        balanceUerth = state?.balanceUerth ?: 0L,
        context = context,
        onGetGas = {
            val address = state?.address
            if (!address.isNullOrEmpty()) {
                // The grant lands as a bank send from the gas wallet, and that
                // send has to make it into a block — so the balance is polled,
                // not read once. A single refresh here always ran before the
                // grant existed, which left the sheet insisting the account was
                // unfunded after the gas had arrived.
                tx.requestGas(
                    address = address,
                    fetchBalance = {
                        withContext(Dispatchers.IO) {
                            runCatching {
                                Bank.balance(address, Constants.UERTH_DENOM).toLong()
                            }.getOrDefault(0L)
                        }
                    },
                    // Bring the rest of the UI in line once it lands; the
                    // sheet reads its balance from this view model.
                    onFunded = { wallet.refresh() },
                )
            }
        },
    )

    // Re-read whenever the app comes back to the foreground.
    //
    // The launcher above covers returning from registration, but not the rest:
    // funds can arrive while the app is backgrounded, from a faucet, another
    // device, or anything else. Without this the balance is only ever as fresh
    // as the last tab switch, which is how a wallet ends up showing zero next
    // to an address that has just been paid.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) wallet.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

internal fun walletAddress(ctx: Context): String =
    SecureWalletManager.getWalletAddress(ctx).orEmpty()

/**
 * The settings menu.
 *
 * What is left once the tabs took the chain: the things about *this install* —
 * who it says you are, what it remembers, and what it is.
 */
private fun EarthRoute.title(): String = when (this) {
    is EarthRoute.Tab -> label
    EarthRoute.Send -> "Send"
    EarthRoute.Receive -> "Receive"
    EarthRoute.Activity -> "Activity"
    EarthRoute.Settings -> "Settings"
    EarthRoute.Security -> "Unlocking"
    EarthRoute.About -> "About"
    is EarthRoute.Stream -> if (human) "Caretaker Fund" else "Groundworks Fund"
    EarthRoute.Proposals -> "Proposals"
    is EarthRoute.ProposalDetail -> "Proposal #$id"
    EarthRoute.Explore -> "Explorer"
    EarthRoute.Personhood -> "Identity"
    EarthRoute.Wallets -> "Wallets"
    EarthRoute.CreateWallet -> "New wallet"
    EarthRoute.ImportWallet -> "Import wallet"
    is EarthRoute.TransactionDetail -> "Transaction"
}
