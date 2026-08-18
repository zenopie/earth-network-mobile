package network.erth.wallet.ui.compose

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import network.erth.earth.proto.allocation.StreamId
import network.erth.wallet.R
import network.erth.wallet.chain.Bank
import network.erth.wallet.chain.Dex
import network.erth.wallet.chain.Personhood
import network.erth.wallet.ui.vendor.component.BlankBgScaffold

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

    val wallet: WalletViewModel = viewModel()
    val earn: EarnViewModel = viewModel()
    val allocation: AllocationViewModel = viewModel()
    val markets: MarketsViewModel = viewModel()
    val explore: ExploreViewModel = viewModel()
    val tx: TxController = viewModel()

    val state by wallet.state.collectAsStateWithLifecycle()
    val activity by wallet.activity.collectAsStateWithLifecycle()
    val earnState by earn.state.collectAsStateWithLifecycle()
    val allocationState by allocation.state.collectAsStateWithLifecycle()
    val marketsState by markets.state.collectAsStateWithLifecycle()
    val exploreState by explore.state.collectAsStateWithLifecycle()

    // Each tab loads when it is first shown rather than all at once on start.
    // Five tabs' worth of queries against one node on launch is a slow launch,
    // and four of them are for screens nobody may open.
    LaunchedEffect(nav.currentTab) {
        when (nav.currentTab) {
            EarthRoute.Wallet -> wallet.refresh()
            EarthRoute.Earn -> earn.refresh()
            EarthRoute.Swap -> markets.refresh()
            EarthRoute.Govern -> allocation.refresh()
            EarthRoute.Explore -> explore.refresh()
        }
    }

    val refreshAll = {
        wallet.refresh()
        when (nav.currentTab) {
            EarthRoute.Earn -> earn.refresh()
            EarthRoute.Govern -> allocation.refresh()
            EarthRoute.Swap -> markets.refresh()
            else -> Unit
        }
    }

    val claimAnml = {
        tx.request(
            details = TxConfirmDetails(
                action = "Claim ANML",
                msgTypeUrl = "/earth.personhood.v1.MsgClaimAnml",
                feeUerth = TxController.DEFAULT_FEE_UERTH,
                balanceUerth = state?.balanceUerth ?: 0L,
            ),
            onSuccess = refreshAll,
            build = { ctx -> listOf(Personhood.msgClaimAnml(walletAddress(ctx))) },
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
                    walletName = route.label,
                    balancesVisible = balancesVisible,
                    onToggleBalances = { balancesVisible = !balancesVisible },
                    onSettings = { nav.push(EarthRoute.Settings) },
                    showsBalances = route == EarthRoute.Wallet || route == EarthRoute.Earn,
                    tabAction = if (route == EarthRoute.Swap) {
                        TabAction(
                            icon = R.drawable.ic_bar_liquidity,
                            label = "Liquidity",
                            onClick = { nav.push(EarthRoute.Liquidity) },
                        )
                    } else {
                        null
                    },
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
        EarthContent(
            route = nav.current,
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
            onClaimAnml = claimAnml,
            version = version,
            balancesVisible = balancesVisible,
            onOpenUrl = onOpenUrl,
            onRefresh = refreshAll,
            padding = padding,
        )
    }

    TxSheets(
        controller = tx,
        balanceUerth = state?.balanceUerth ?: 0L,
        context = context,
    )
}

@Composable
@Suppress("LongParameterList")
private fun EarthContent(
    route: EarthRoute,
    nav: EarthNavController,
    tx: TxController,
    state: WalletUiState?,
    activity: List<ActivityRow>?,
    earnState: EarnUiState?,
    allocationState: AllocationUiState?,
    marketsState: MarketsUiState?,
    exploreState: ExploreUiState?,
    earn: EarnViewModel,
    allocation: AllocationViewModel,
    markets: MarketsViewModel,
    onClaimAnml: () -> Unit,
    version: String,
    balancesVisible: Boolean,
    onOpenUrl: (String) -> Unit,
    onRefresh: () -> Unit,
    padding: PaddingValues,
) {
    val loaded = state ?: WalletUiState.EMPTY

    // The wallet tab draws its activity list to the bottom edge, so it takes
    // the padding as content padding rather than as a margin — a list that
    // stops above the bar looks clipped, one that scrolls under it does not.
    val inset = Modifier.padding(
        top = padding.calculateTopPadding(),
        bottom = if (route is EarthRoute.Wallet) 0.dp else padding.calculateBottomPadding(),
    )

    // Which sheet, if any, is open on top of the current screen.
    var staking by remember { mutableStateOf<StakeIntent?>(null) }
    var editing by remember { mutableStateOf<StreamId?>(null) }

    when (route) {
        EarthRoute.Wallet -> HomeScreen(
            erthBalance = state?.let { formatUerth(it.balanceUerth) },
            anmlBalance = state?.let { it.anmlBalance ?: "0" },
            balancesVisible = balancesVisible,
            activity = activity,
            onReceive = { nav.push(EarthRoute.Receive) },
            onSend = { nav.push(EarthRoute.Send) },
            onEarn = { nav.selectTab(EarthRoute.Earn) },
            onClaimAnml = onClaimAnml,
            anmlClaimableAt = state?.anmlClaimableAt,
            onSeeAllActivity = { nav.push(EarthRoute.Activity) },
            modifier = inset,
            contentPadding = padding,
        )

        EarthRoute.Earn -> EarnScreen(
            state = earnState,
            onStake = { staking = StakeIntent.Stake },
            onUnstake = { staking = StakeIntent.Unstake },
            onClaim = {
                val validators = earnState?.delegations?.map { it.validatorOperator }.orEmpty()
                tx.request(
                    details = TxConfirmDetails(
                        action = "Claim rewards",
                        msgTypeUrl = "/cosmos.distribution.v1beta1.MsgWithdrawDelegatorReward",
                        feeUerth = TxController.DEFAULT_FEE_UERTH,
                        balanceUerth = loaded.balanceUerth,
                        amountLabel = "Rewards",
                        amountValue = "${formatUerth(earnState?.rewardsUerth ?: 0)} ERTH",
                    ),
                    // One withdraw per validator, so the gas scales with how
                    // many you delegate to.
                    gasLimit = TxController.DEFAULT_GAS_LIMIT +
                        150_000L * validators.size,
                    onSuccess = onRefresh,
                    build = earn.claimAll(validators),
                )
            },
            modifier = inset,
        )

        EarthRoute.Swap -> SwapScreen(
            erthBalance = state?.let { formatUerth(it.balanceUerth) },
            anmlBalance = state?.let { it.anmlBalance ?: "0" },
            // Only ERTH/ANML for now: it is the one pool, and pairing
            // arbitrary spokes would need a two-hop quote through the hub that
            // the screen has no way to let you choose yet.
            pool = marketsState?.pools?.firstOrNull { it.tokenDenom == "uanml" },
            swapFeePercent = marketsState?.swapFeePercent,
            onSwap = { denomIn, amountIn, denomOut, minOut ->
                tx.request(
                    details = TxConfirmDetails(
                        action = "Swap",
                        msgTypeUrl = "/earth.dex.v1.MsgSwap",
                        feeUerth = TxController.DEFAULT_FEE_UERTH,
                        balanceUerth = loaded.balanceUerth,
                        amountLabel = "You pay",
                        amountValue = "${formatUerth(amountIn.toLong())} " +
                            denomIn.removePrefix("u").uppercase(),
                    ),
                    onSuccess = {
                        onRefresh()
                        markets.refresh()
                    },
                    build = { ctx ->
                        listOf(
                            Dex.msgSwap(
                                walletAddress(ctx),
                                denomIn,
                                amountIn.toString(),
                                denomOut,
                                minOut.toString(),
                            ),
                        )
                    },
                )
            },
            modifier = inset,
        )

        EarthRoute.Govern -> AllocationScreen(
            state = allocationState,
            registered = loaded.registered,
            stakedUerth = loaded.stakedUerth,
            onEdit = { editing = it },
            modifier = inset,
        )

        EarthRoute.Explore -> ExploreScreen(
            state = exploreState,
            onTx = { nav.push(EarthRoute.TransactionDetail(it)) },
            modifier = inset,
        )

        EarthRoute.Receive -> ReceiveScreen(
            state = ReceiveUiState(address = loaded.address),
            modifier = inset,
        )

        EarthRoute.Send -> SendFlow(
            state = loaded,
            tx = tx,
            onSent = onRefresh,
            modifier = inset,
        )

        EarthRoute.Liquidity -> LiquidityScreen(
            pools = marketsState?.pools,
            swapFeePercent = marketsState?.swapFeePercent,
            lpOptionShare = marketsState?.lpOptionShare ?: 0.0,
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

        is EarthRoute.TransactionDetail -> TransactionDetailScreen(
            txHash = route.txHash,
            row = activity?.firstOrNull { it.txHash == route.txHash },
            onOpenExplorer = { onOpenUrl("https://explorer.erth.network/tx/${route.txHash}") },
            modifier = inset,
        )
    }

    staking?.let { intent ->
        val stake = intent == StakeIntent.Stake
        StakeSheet(
            title = if (stake) "Stake ERTH" else "Unstake ERTH",
            choices = if (stake) {
                earnState?.validators.orEmpty()
            } else {
                earnState?.delegations.orEmpty()
            },
            // Staking is capped by what is spendable less the fee; unstaking by
            // what is already with that validator.
            capFor = { v ->
                if (stake) {
                    (loaded.balanceUerth - TxController.DEFAULT_FEE_UERTH).coerceAtLeast(0)
                } else {
                    v.amountUerth
                }
            },
            confirmLabel = if (stake) "Stake" else "Unstake",
            onDismiss = { staking = null },
            onConfirm = { validator, amount ->
                staking = null
                tx.request(
                    details = TxConfirmDetails(
                        action = if (stake) "Stake ERTH" else "Unstake ERTH",
                        msgTypeUrl = if (stake) {
                            "/cosmos.staking.v1beta1.MsgDelegate"
                        } else {
                            "/cosmos.staking.v1beta1.MsgUndelegate"
                        },
                        feeUerth = TxController.DEFAULT_FEE_UERTH,
                        balanceUerth = loaded.balanceUerth,
                        amountLabel = "Amount",
                        amountValue = "${formatUerth(amount)} ERTH",
                    ),
                    onSuccess = onRefresh,
                    build = if (stake) {
                        earn.delegate(validator, amount)
                    } else {
                        earn.undelegate(validator, amount)
                    },
                )
            },
        )
    }

    editing?.let { stream ->
        val streamState = when (stream) {
            StreamId.STREAM_ID_HUMAN -> allocationState?.human
            else -> allocationState?.capital
        }
        if (streamState != null) {
            AllocationEditSheet(
                title = if (stream == StreamId.STREAM_ID_HUMAN) {
                    "Human stream"
                } else {
                    "Capital stream"
                },
                stream = streamState,
                onDismiss = { editing = null },
                onConfirm = { weights ->
                    editing = null
                    tx.request(
                        details = TxConfirmDetails(
                            action = "Set allocation",
                            msgTypeUrl = "/earth.allocation.v1.MsgSetAllocations",
                            feeUerth = TxController.DEFAULT_FEE_UERTH,
                            balanceUerth = loaded.balanceUerth,
                        ),
                        onSuccess = onRefresh,
                        build = allocation.setAllocations(stream, weights),
                    )
                },
            )
        }
    }
}

/** Which direction the stake sheet was opened in. */
private enum class StakeIntent { Stake, Unstake }

private fun walletAddress(ctx: Context): String =
    network.erth.wallet.wallet.services.SecureWalletManager.getWalletAddress(ctx).orEmpty()

/**
 * The settings menu.
 *
 * What is left once the tabs took the chain: the things about *this install* —
 * who it says you are, what it remembers, and what it is.
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
    is EarthRoute.Tab -> label
    EarthRoute.Send -> "Send"
    EarthRoute.Receive -> "Receive"
    EarthRoute.Liquidity -> "Liquidity"
    EarthRoute.Activity -> "Activity"
    EarthRoute.Settings -> "Settings"
    EarthRoute.AddressBook -> "Address book"
    EarthRoute.About -> "About"
    EarthRoute.Personhood -> "Identity"
    is EarthRoute.TransactionDetail -> "Transaction"
}

internal fun formatUerth(micro: Long): String {
    val whole = micro / 1_000_000
    val frac = (micro % 1_000_000).toString().padStart(6, '0').trimEnd('0')
    return if (frac.isEmpty()) "%,d".format(whole) else "%,d.%s".format(whole, frac)
}
