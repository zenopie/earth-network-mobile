package network.erth.wallet.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import network.erth.earth.proto.allocation.StreamId
import network.erth.wallet.Constants
import network.erth.wallet.R
import network.erth.wallet.chain.Assembly
import network.erth.wallet.privacy.PrivacySession
import network.erth.wallet.privacy.tx.PrivateMsgs
import network.erth.wallet.chain.Dex
import network.erth.wallet.chain.Gov
import network.erth.wallet.chain.Personhood
import network.erth.wallet.ui.components.formatUerth
import network.erth.wallet.ui.earn.DelegationRow
import network.erth.wallet.ui.earn.EarnScreen
import network.erth.wallet.ui.earn.EarnUiState
import network.erth.wallet.ui.earn.EarnViewModel
import network.erth.wallet.ui.earn.StakeSheet
import network.erth.wallet.ui.explore.ExploreScreen
import network.erth.wallet.ui.explore.ExploreUiState
import network.erth.wallet.ui.explore.ExploreViewModel
import network.erth.wallet.ui.govern.AllocationEditSheet
import network.erth.wallet.ui.govern.AllocationScreen
import network.erth.wallet.ui.govern.AllocationUiState
import network.erth.wallet.ui.govern.AllocationViewModel
import network.erth.wallet.ui.govern.ProposalDetailScreen
import network.erth.wallet.ui.govern.ProposalsScreen
import network.erth.wallet.ui.govern.StreamDetailScreen
import network.erth.wallet.ui.home.HomeScreen
import network.erth.wallet.ui.navigation.EarthNavController
import network.erth.wallet.ui.navigation.EarthRoute
import network.erth.wallet.ui.onboarding.CreateWalletScreen
import network.erth.wallet.ui.onboarding.ImportWalletScreen
import network.erth.wallet.ui.personhood.PersonhoodScreen
import network.erth.wallet.ui.settings.AboutScreen
import network.erth.wallet.ui.settings.SecurityScreen
import network.erth.wallet.ui.settings.SettingsItem
import network.erth.wallet.ui.settings.SettingsScreen
import network.erth.wallet.ui.swap.LiquidityAction
import network.erth.wallet.ui.swap.LiquiditySheet
import network.erth.wallet.ui.swap.MarketsUiState
import network.erth.wallet.ui.swap.MarketsViewModel
import network.erth.wallet.ui.swap.SwapScreen
import network.erth.wallet.ui.tx.TxConfirmDetails
import network.erth.wallet.ui.tx.TxController
import network.erth.wallet.ui.wallet.ActivityRow
import network.erth.wallet.ui.wallet.ActivityScreen
import network.erth.wallet.ui.wallet.ReceiveScreen
import network.erth.wallet.ui.wallet.Holding
import network.erth.wallet.ui.wallet.ReceiveUiState
import network.erth.wallet.ui.wallet.SendFlow
import network.erth.wallet.ui.wallet.TransactionDetailScreen
import network.erth.wallet.ui.wallet.WalletUiState
import network.erth.wallet.ui.wallet.WalletsScreen
import network.erth.wallet.ui.wallet.WalletsUiState
import network.erth.wallet.ui.wallet.WalletsViewModel

/**
 * The screen for a route: each route's view-model state and callbacks wired
 * into its composable. [EarthApp] owns the state; this only routes it.
 */
@Composable
@Suppress("LongParameterList")
internal fun EarthContent(
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
            // Transparent and shielded ERTH together: both are the owner's.
            // Fees for private actions come from the shielded part only.
            erthBalance = state?.let { formatUerth(it.balanceUerth + it.shieldedErthUerth) },
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
            // Private stake (derth) and unbonding claims are notes, not bank
            // balances, so they join the portfolio here.
            holdings = state?.holdings.orEmpty() + state?.shielded.orEmpty()
                .filterKeys { it.startsWith("derth/") || it.startsWith("unbond/") }
                .map { (denom, amount) ->
                    Holding(
                        denom = denom,
                        symbol = if (denom.startsWith("derth/")) "Staked (private)" else "Unbonding (private)",
                        amount = amount,
                    )
                },
            onSeeAllActivity = { nav.push(EarthRoute.Activity) },
            modifier = inset,
            contentPadding = padding,
        )

        EarthRoute.Earn -> EarnScreen(
            state = earnState,
            pools = marketsState?.pools,
            swapFeePercent = marketsState?.swapFeePercent,
            lpOptionShare = marketsState?.lpOptionShare ?: 0.0,
            lpUnbondings = marketsState?.unbondings.orEmpty(),
            lpShares = shares,
            onAddLiquidity = { liquidity = LiquidityAction.Add to it },
            onRemoveLiquidity = { liquidity = LiquidityAction.Remove to it },
            onStake = { staking = StakeIntent.Stake },
            onUnstake = { staking = StakeIntent.Unstake },
            onClaim = {
                val validators = earnState?.delegations?.map { it.validatorOperator }.orEmpty()
                // One withdraw per validator, so the gas scales with how many
                // you delegate to.
                val claimGas = TxController.DEFAULT_GAS_LIMIT + 150_000L * validators.size
                tx.request(
                    details = TxConfirmDetails(
                        action = "Claim rewards",
                        msgTypeUrl = "/cosmos.distribution.v1beta1.MsgWithdrawDelegatorReward",
                        balanceUerth = loaded.balanceUerth,
                        amountLabel = "Rewards",
                        amountValue = "${formatUerth(earnState?.rewardsUerth ?: 0)} ERTH",
                    ),
                    gasLimit = claimGas,
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
                        balanceUerth = loaded.balanceUerth,
                        amountLabel = "You pay",
                        amountValue = "${formatUerth(amountIn.toLong())} " +
                            denomIn.removePrefix("u").uppercase(),
                        minReceived = "${formatUerth(minOut.toLong())} " +
                            denomOut.removePrefix("u").uppercase(),
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
            state = ReceiveUiState(address = loaded.address, shieldedAddress = loaded.shieldedAddress),
            modifier = inset,
        )

        EarthRoute.Send -> SendFlow(
            state = loaded,
            tx = tx,
            onSent = onRefresh,
            modifier = inset,
        )

        EarthRoute.Activity -> ActivityScreen(rows = activity.orEmpty(), modifier = inset)

        EarthRoute.Settings -> SettingsScreen(
            items = settingsItems(nav, state),
            version = version,
            modifier = inset,
        )

        EarthRoute.Security -> SecurityScreen(modifier = inset)

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
            onOpen = { nav.push(EarthRoute.ProposalDetail(it.id)) },
        )

        is EarthRoute.ProposalDetail -> ProposalDetailScreen(
            // Read from the list already loaded rather than fetched again:
            // the tally on it is the live one, and refetching one proposal
            // would put a spinner in front of data that is already here.
            proposal = allocationState?.proposals?.firstOrNull { it.id == route.id },
            modifier = inset,
            // The stake house weighs bonded ERTH and nothing else, so being a
            // verified human buys no say in it — and vice versa below. The two
            // are separate standings and a wallet can hold either, both or
            // neither.
            eligibility = if (loaded.stakedUerth <= 0) {
                "Stake ERTH to vote here. This house is weighted by bonded stake alone."
            } else {
                null
            },
            onVote = { proposal, vote ->
                tx.request(
                    details = TxConfirmDetails(
                        action = "Vote ${vote.label} on #${proposal.id}",
                        msgTypeUrl = Gov.MSG_VOTE_TYPE_URL,
                        balanceUerth = loaded.balanceUerth,
                    ),
                    gasLimit = Gov.VOTE_GAS_LIMIT,
                    onSuccess = onRefresh,
                    build = { ctx ->
                        listOf(Gov.msgVote(walletAddress(ctx), proposal.id, vote))
                    },
                )
            },
            // Absent on a chain without an assembly, which hides the whole
            // second house rather than explaining one that is not there yet.
            assembly = allocationState?.assemblyTallies?.get(route.id),
            assemblyEligibility = if (!loaded.registered) {
                "Register your identity to vote here. This house counts people, " +
                    "not holdings — one registration is one vote."
            } else {
                null
            },
            onAssemblyVote = { proposal, vote ->
                // Private: a membership proof in this ballot's scope; who voted
                // is never known, and a second vote replaces the first.
                tx.requestPrivate(
                    details = TxConfirmDetails(
                        // Says which house, because the stake vote on the same
                        // proposal produces an otherwise identical confirmation
                        // and the two are genuinely different actions.
                        action = "Vote ${vote.label} as a person on #${proposal.id}",
                        msgTypeUrl = Assembly.MSG_VOTE_PROPOSAL_TYPE_URL,
                        balanceUerth = 0L,
                    ),
                    shieldedErth = loaded.shieldedErthUerth,
                    onSuccess = onRefresh,
                    run = { ctx ->
                        PrivacySession.wallet(ctx).voteProposal(proposal.id, vote == Assembly.Vote.Yes).hash
                    },
                )
            },
        )

        is EarthRoute.TransactionDetail -> TransactionDetailScreen(
            txHash = route.txHash,
            row = activity?.firstOrNull { it.txHash == route.txHash },
            onOpenExplorer = { onOpenUrl("https://explorer.erth.network/tx/${route.txHash}") },
            modifier = inset,
        )
    }

    // Staking is private: ERTH is spent from shielded notes into the pool's
    // delegation to a validator and comes back as derth/<validator> notes
    // (worth more ERTH each epoch as rewards compound). Unstaking turns derth
    // into an unbonding claim, paid out automatically once it matures. Only a
    // validator's own self-bond is a transparent delegation now.
    staking?.let { intent ->
        val stake = intent == StakeIntent.Stake
        val derthRows = loaded.shielded.filterKeys { it.startsWith("derth/") }.map { (denom, amount) ->
            val op = denom.removePrefix("derth/")
            DelegationRow(
                validatorOperator = op,
                moniker = earnState?.validators?.firstOrNull { it.validatorOperator == op }?.moniker ?: op,
                amountUerth = amount,
                commission = earnState?.validators?.firstOrNull { it.validatorOperator == op }?.commission ?: 0.0,
            )
        }
        StakeSheet(
            title = if (stake) "Stake ERTH privately" else "Unstake",
            choices = if (stake) earnState?.validators.orEmpty() else derthRows,
            capFor = { v ->
                if (stake) {
                    // A reserve, not one fee: staking everything-but-the-fee
                    // leaves no shielded ERTH to pay for unstaking.
                    (loaded.shieldedErthUerth - TxController.GAS_RESERVE_UERTH).coerceAtLeast(0)
                } else {
                    v.amountUerth
                }
            },
            confirmLabel = if (stake) "Stake" else "Unstake",
            onDismiss = { staking = null },
            onConfirm = { validator, amount ->
                staking = null
                tx.requestPrivate(
                    details = TxConfirmDetails(
                        action = if (stake) "Stake ERTH" else "Unstake",
                        msgTypeUrl = if (stake) PrivateMsgs.DELEGATE else PrivateMsgs.UNDELEGATE,
                        balanceUerth = 0L,
                        amountLabel = "Amount",
                        amountValue = if (stake) "${formatUerth(amount)} ERTH" else "${formatUerth(amount)} derth",
                        recipient = validator,
                        recipientLabel = if (stake) "Validator" else "From validator",
                    ),
                    shieldedErth = loaded.shieldedErthUerth,
                    onSuccess = onRefresh,
                    run = { ctx ->
                        val w = PrivacySession.wallet(ctx)
                        (if (stake) w.delegate(validator, amount) else w.undelegate(validator, amount)).hash
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
            // ante handler cannot charge for. A reserve rather than a single
            // fee, for the same reason as staking: withdrawing this liquidity
            // later is itself a transaction that has to be payable.
            erthAvailable = (loaded.balanceUerth - TxController.GAS_RESERVE_UERTH)
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
                    if (stream == StreamId.STREAM_ID_CARETAKER) {
                        // Private: a membership proof in the caretaker scope.
                        // The split is public, who cast it is not, and it
                        // lapses after R unless refreshed (the automation does).
                        tx.requestPrivate(
                            details = TxConfirmDetails(
                                action = "Set caretaker split",
                                msgTypeUrl = PrivateMsgs.SET_CARETAKER,
                                balanceUerth = 0L,
                            ),
                            shieldedErth = loaded.shieldedErthUerth,
                            onSuccess = onRefresh,
                            run = { ctx -> PrivacySession.wallet(ctx).setCaretaker(weights.filterValues { it > 0 }).hash },
                        )
                        return@AllocationEditSheet
                    }
                    tx.request(
                        details = TxConfirmDetails(
                            action = "Set allocation",
                            msgTypeUrl = "/earth.allocation.v1.MsgSetAllocations",
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
            title = "Unlocking",
            subtitle = "PIN and biometrics",
            icon = R.drawable.ic_lock,
            onClick = { nav.push(EarthRoute.Security) },
        ),
        SettingsItem(
            title = "About",
            icon = R.drawable.ic_info,
            onClick = { nav.push(EarthRoute.About) },
        ),
    )

/** The title the detail bar shows for a pushed route. */
