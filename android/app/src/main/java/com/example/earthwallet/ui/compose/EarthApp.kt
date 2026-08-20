package network.erth.wallet.ui.compose

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import network.erth.wallet.Constants
import network.erth.wallet.chain.Bank
import network.erth.wallet.ui.ads.RewardedAds
import network.erth.wallet.ui.compose.registration.RegistrationActivity
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

    // Each tab loads when it is first shown rather than all at once on start.
    // Five tabs' worth of queries against one node on launch is a slow launch,
    // and four of them are for screens nobody may open.
    LaunchedEffect(nav.currentTab, walletEpoch) {
        when (nav.currentTab) {
            EarthRoute.Wallet -> wallet.refresh()
            EarthRoute.Earn -> earn.refresh()
            EarthRoute.Swap -> markets.refresh()
            EarthRoute.Govern -> allocation.refresh()
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
        // cancelled scan can still have spent gas on an ad grant.
        invalidateWallet()
    }

    val openRegistration = {
        runCatching {
            registration.launch(Intent(context, RegistrationActivity::class.java))
        }
        Unit
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
    }

    // The ads-for-gas gate, restored to the Compose flow. It hung off TxFlow
    // before, so it applied to every transaction from an underfunded account
    // rather than only to registration — which matters because registration is
    // not necessarily the first thing a new human tries.
    val host = context as? android.app.Activity
    TxSheets(
        controller = tx,
        balanceUerth = state?.balanceUerth ?: 0L,
        context = context,
        onWatchAd = {
            val address = state?.address
            if (host != null && !address.isNullOrEmpty()) {
                RewardedAds.show(host, address) { granted ->
                    // The grant lands as a bank send from the gas wallet, so
                    // the balance has to be re-read before the sheet can tell
                    // whether the fee is now covered.
                    if (granted) wallet.refresh()
                }
            }
        },
    )

    // Loaded ahead of the tap. Fetching a rewarded ad takes seconds, and doing
    // it when the button is pressed makes the button look broken.
    LaunchedEffect(Unit) { RewardedAds.preload(context) }

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
    explore: ExploreViewModel,
    wallets: WalletsViewModel,
    walletsState: WalletsUiState?,
    draftMnemonic: String?,
    walletsError: String?,
    onSwitchWallet: (Int) -> Unit,
    onClaimAnml: () -> Unit,
    onRegister: () -> Unit,
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
    var liquidity by remember { mutableStateOf<Pair<LiquidityAction, Dex.Pool>?>(null) }

    // LP shares arrive in the same balances call as everything else — they are
    // ordinary coins, denominated dexlp/<pool>.
    val shares = remember(loaded.holdings) {
        loaded.holdings
            .filter { it.denom.startsWith("dexlp/") }
            .associate { (it.denom.removePrefix("dexlp/").toLongOrNull() ?: 0L) to it.amount }
    }

    when (route) {
        EarthRoute.Wallet -> HomeScreen(
            erthBalance = state?.let { formatUerth(it.balanceUerth) },
            anmlBalance = state?.let { it.anmlBalance ?: "0" },
            balancesVisible = balancesVisible,
            activity = activity,
            onReceive = { nav.push(EarthRoute.Receive) },
            onSend = { nav.push(EarthRoute.Send) },
            onClaimAnml = onClaimAnml,
            onRegister = onRegister,
            anmlClaimableAt = state?.anmlClaimableAt,
            registered = state?.registered,
            stakedUerth = state?.stakedUerth ?: 0L,
            rewardsUerth = state?.rewardsUerth ?: 0L,
            unbondingUerth = earnState?.unbonding?.sumOf { it.amountUerth } ?: 0L,
            holdings = state?.holdings.orEmpty(),
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
            erthUerth = state?.balanceUerth,
            anmlUnits = state?.holdings?.firstOrNull { it.denom == "uanml" }?.amount ?: state?.let { 0L },
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
            onOpenStream = { nav.push(EarthRoute.Stream(it == StreamId.STREAM_ID_CARETAKER)) },
            onOpenProposals = { nav.push(EarthRoute.Proposals) },
            modifier = inset,
        )

        EarthRoute.Explore -> {
            // Pushed rather than a tab now, so it loads on entry instead of on
            // tab selection.
            LaunchedEffect(Unit) { explore.refresh() }
            ExploreScreen(
                state = exploreState,
                onTx = { nav.push(EarthRoute.TransactionDetail(it)) },
                modifier = inset,
            )
        }

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
            unbondings = marketsState?.unbondings.orEmpty(),
            shares = shares,
            onAdd = { liquidity = LiquidityAction.Add to it },
            onRemove = { liquidity = LiquidityAction.Remove to it },
            modifier = inset,
        )

        EarthRoute.Activity -> ActivityScreen(rows = activity.orEmpty(), modifier = inset)

        EarthRoute.Settings -> SettingsScreen(
            items = settingsItems(nav, state),
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


        EarthRoute.Personhood -> PersonhoodScreen(
            registered = loaded.registered,
            anmlBalance = loaded.anmlBalance,
            onRegister = onRegister,
            onClaim = onClaimAnml,
            modifier = inset,
        )

        EarthRoute.Wallets -> {
            LaunchedEffect(Unit) { wallets.refresh() }
            WalletsScreen(
                state = walletsState,
                // Switching wallets changes whose balance every other screen
                // is showing, so the whole app reloads rather than the list
                // alone. Anything less leaves a stale balance behind a stale
                // address.
                onSelect = { index ->
                    onSwitchWallet(index)
                    nav.pop()
                },
                onCreate = {
                    wallets.beginCreate()
                    nav.push(EarthRoute.CreateWallet)
                },
                onImport = {
                    wallets.clearError()
                    nav.push(EarthRoute.ImportWallet)
                },
                modifier = inset,
            )
        }

        EarthRoute.CreateWallet -> CreateWalletScreen(
            mnemonic = draftMnemonic,
            onConfirm = { name ->
                wallets.confirmCreate(name) {
                    // createWallet selects the new wallet, so this is a switch
                    // and has to invalidate like one.
                    onSwitchWallet(-1)
                    // Back past the phrase, not onto it: the draft is gone
                    // once stored, and returning to a screen that would show
                    // it empty is worse than not returning.
                    nav.pop()
                    nav.pop()
                }
            },
            modifier = inset,
        )

        EarthRoute.ImportWallet -> ImportWalletScreen(
            error = walletsError,
            onImport = { name, phrase ->
                wallets.import(name, phrase) {
                    onSwitchWallet(-1)
                    nav.pop()
                    nav.pop()
                }
            },
            modifier = inset,
        )

        is EarthRoute.Stream -> {
            val id = if (route.human) {
                StreamId.STREAM_ID_CARETAKER
            } else {
                StreamId.STREAM_ID_GROUNDWORKS
            }
            StreamDetailScreen(
                title = if (route.human) "Caretaker Fund" else "Groundworks Fund",
                detail = if (route.human) {
                    "One verified human, one vote."
                } else {
                    "Weighted by the ERTH you have staked."
                },
                stream = if (route.human) allocationState?.human else allocationState?.capital,
                eligibility = when {
                    route.human && !loaded.registered ->
                        "Register your identity to take part."
                    !route.human && loaded.stakedUerth <= 0 ->
                        "Stake ERTH to take part."
                    else -> null
                },
                onEdit = { editing = id },
                modifier = inset,
            )
        }

        EarthRoute.Proposals -> ProposalsScreen(
            proposals = allocationState?.proposals,
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

    liquidity?.let { (action, pool) ->
        LiquiditySheet(
            action = action,
            pool = pool,
            // The fee comes out of the same ERTH being deposited, so the
            // spendable figure has to exclude it or "max" builds a deposit the
            // ante handler cannot charge for.
            erthAvailable = (loaded.balanceUerth - TxController.DEFAULT_FEE_UERTH)
                .coerceAtLeast(0),
            tokenAvailable = loaded.holdings
                .firstOrNull { it.denom == pool.tokenDenom }?.amount ?: 0L,
            shareBalance = shares[pool.id] ?: 0L,
            unbondingSeconds = marketsState?.lpUnbondingSeconds ?: 0L,
            onDismiss = { liquidity = null },
            onConfirm = { erthIn, tokenIn, sharesOut ->
                liquidity = null
                val adding = action == LiquidityAction.Add
                tx.request(
                    details = TxConfirmDetails(
                        action = if (adding) "Add liquidity" else "Withdraw liquidity",
                        msgTypeUrl = if (adding) {
                            "/earth.dex.v1.MsgAddLiquidity"
                        } else {
                            "/earth.dex.v1.MsgRemoveLiquidity"
                        },
                        feeUerth = TxController.DEFAULT_FEE_UERTH,
                        balanceUerth = loaded.balanceUerth,
                        amountLabel = if (adding) "Deposit" else "Shares",
                        amountValue = if (adding) {
                            "${formatUerth(erthIn.toLong())} ERTH + " +
                                "${formatUerth(tokenIn.toLong())} " +
                                pool.tokenDenom.removePrefix("u").uppercase()
                        } else {
                            formatUerth(sharesOut.toLong())
                        },
                    ),
                    onSuccess = {
                        onRefresh()
                        markets.refresh()
                    },
                    build = { ctx ->
                        val creator = walletAddress(ctx)
                        listOf(
                            if (adding) {
                                Dex.msgAddLiquidity(
                                    creator,
                                    pool.id,
                                    Constants.UERTH_DENOM,
                                    erthIn.toString(),
                                    pool.tokenDenom,
                                    tokenIn.toString(),
                                )
                            } else {
                                Dex.msgRemoveLiquidity(
                                    creator,
                                    pool.id,
                                    Dex.shareDenom(pool.id),
                                    sharesOut.toString(),
                                )
                            },
                        )
                    },
                )
            },
        )
    }

    editing?.let { stream ->
        val streamState = when (stream) {
            StreamId.STREAM_ID_CARETAKER -> allocationState?.human
            else -> allocationState?.capital
        }
        if (streamState != null) {
            AllocationEditSheet(
                title = if (stream == StreamId.STREAM_ID_CARETAKER) {
                    "Caretaker Fund"
                } else {
                    "Groundworks Fund"
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
private fun settingsItems(nav: EarthNavController, state: WalletUiState?): List<SettingsItem> =
    listOf(
        SettingsItem(
            title = "Identity",
            // Null, not "Not registered", while the wallet is still loading.
            // Falling back to the empty state made this assert a fact about
            // whichever wallet had just been switched to, before anything had
            // been read about it.
            subtitle = state?.let {
                if (it.registered) "Verified human" else "Not registered"
            },
            icon = R.drawable.ic_shield_check,
            onClick = { nav.push(EarthRoute.Personhood) },
        ),
        SettingsItem(
            title = "Wallets",
            subtitle = state?.name?.takeIf { it.isNotBlank() },
            icon = R.drawable.ic_wallet,
            onClick = { nav.push(EarthRoute.Wallets) },
        ),
        SettingsItem(
            title = "Explorer",
            subtitle = "Blocks, validators and registrations",
            icon = R.drawable.ic_home_explore,
            onClick = { nav.push(EarthRoute.Explore) },
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
    EarthRoute.About -> "About"
    is EarthRoute.Stream -> if (human) "Caretaker Fund" else "Groundworks Fund"
    EarthRoute.Proposals -> "Proposals"
    EarthRoute.Explore -> "Explorer"
    EarthRoute.Personhood -> "Identity"
    EarthRoute.Wallets -> "Wallets"
    EarthRoute.CreateWallet -> "New wallet"
    EarthRoute.ImportWallet -> "Import wallet"
    is EarthRoute.TransactionDetail -> "Transaction"
}

internal fun formatUerth(micro: Long): String {
    val whole = micro / 1_000_000
    val frac = (micro % 1_000_000).toString().padStart(6, '0').trimEnd('0')
    return if (frac.isEmpty()) "%,d".format(whole) else "%,d.%s".format(whole, frac)
}
