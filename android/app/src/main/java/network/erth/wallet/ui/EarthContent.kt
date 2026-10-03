package network.erth.wallet.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import network.erth.earth.proto.allocation.StreamId
import network.erth.wallet.Constants
import network.erth.wallet.R
import network.erth.wallet.chain.Assembly
import network.erth.wallet.privacy.Amounts
import network.erth.wallet.privacy.PrivacySession
import network.erth.wallet.privacy.PrivacyWallet
import network.erth.wallet.privacy.tx.ShieldMove
import network.erth.wallet.privacy.tx.PrivateMsgs
import network.erth.wallet.chain.EarthTx
import network.erth.earth.proto.shielded.MsgShield
import cosmos.base.v1beta1.CoinOuterClass
import com.google.protobuf.ByteString
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
import network.erth.wallet.ui.privacy.NotesScreen
import network.erth.wallet.ui.privacy.PositionRow
import network.erth.wallet.ui.privacy.PositionsScreen
import network.erth.wallet.ui.privacy.PrivacyActionsState
import network.erth.wallet.ui.privacy.PrivacyActionsViewModel
import network.erth.wallet.ui.privacy.ReferrerSection
import network.erth.wallet.ui.privacy.RemovalBallotsScreen
import network.erth.wallet.chain.math.SwapMath
import cosmos.gov.v1.WeightedVoteOption
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
import network.erth.wallet.ui.wallet.MoveDirection
import network.erth.wallet.ui.wallet.MoveSheet
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
    privacy: PrivacyActionsViewModel,
    privacyState: PrivacyActionsState?,
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
    // Groundworks positions: choosing stake to lock, then its split; or re-splitting one.
    var locking by remember { mutableStateOf(false) }
    var lockDraft by remember { mutableStateOf<Pair<String, Long>?>(null) }
    var resplitting by remember { mutableStateOf<PositionRow?>(null) }
    // Shield / Unshield from the wallet home.
    var moving by remember { mutableStateOf<MoveDirection?>(null) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val stakeVotes: network.erth.wallet.ui.govern.StakeVoteViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val stakeVoteProgress by stakeVotes.progress.collectAsStateWithLifecycle()
    val onShare = { text: String ->
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_TEXT, text)
        context.startActivity(android.content.Intent.createChooser(send, null))
    }

    // Private stake: derth notes per validator.
    val derthHeld = loaded.shielded.filterKeys { it.startsWith("derth/") }
    val privateStake = Amounts.satAdd(Amounts.satSum(derthHeld.values), privacyState?.positions?.let { ps -> Amounts.satSum(ps) { it.position.derth } } ?: 0L)
    // The same stake in ERTH: derth is a claim at rate_v, so face value
    // under-reports it once rewards have compounded.
    fun derthValue(derth: Long, validator: String): Long =
        earnState?.derthValue(derth, validator) ?: derth
    val privateStakeValue = Amounts.satAdd(
        Amounts.satSum(derthHeld.entries) { (denom, amount) -> derthValue(amount, denom.removePrefix("derth/")) },
        privacyState?.positions?.let { ps -> Amounts.satSum(ps) { derthValue(it.position.derth, it.position.validator) } } ?: 0L,
    )

    // LP shares: public ones are ordinary coins (dexlp/<pool>) in the
    // balances call; private ones (a shielded deposit's) are share notes of
    // the same denom in the pool. Both count as this wallet's.
    val shares = remember(loaded.holdings, loaded.shielded) {
        val public = loaded.holdings.filter { it.denom.startsWith("dexlp/") }.map { it.denom to it.amount }
        val private = loaded.shielded.filterKeys { it.startsWith("dexlp/") }.toList()
        (public + private).groupBy({ it.first.removePrefix("dexlp/").toLongOrNull() ?: 0L }, { it.second }).mapValues { Amounts.satSum(it.value) }
    }

    when (route) {
        EarthRoute.Wallet -> HomeScreen(
            // Transparent and shielded ERTH together: both are the owner's.
            // Fees for private actions come from the shielded part only.
            erthBalance = state?.let { formatUerth(it.balanceUerth + it.shieldedErthUerth) },
            anmlBalance = state?.let { it.anmlBalance ?: "0" },
            publicErthUerth = state?.balanceUerth,
            privateErthUerth = state?.shieldedErthUerth,
            privateStakeUerth = privateStakeValue,
            onMove = { moving = it },
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
            privateNotice = state?.privacySyncError,
            // Private stake (derth) and unbonding claims are notes, not bank
            // balances, so they join the portfolio here.
            // Private stake shows its ERTH value, the derth amount beneath.
            holdings = state?.holdings.orEmpty() + state?.shielded.orEmpty()
                .filterKeys { it.startsWith("derth/") || it.startsWith("unbond/") }
                .map { (denom, amount) ->
                    if (denom.startsWith("derth/")) {
                        val op = denom.removePrefix("derth/")
                        Holding(
                            denom = denom,
                            symbol = "Staked (private)",
                            amount = derthValue(amount, op),
                            detail = "${formatUerth(amount)} derth · $op",
                        )
                    } else {
                        Holding(denom = denom, symbol = "Unbonding (private)", amount = amount)
                    }
                } + privacyState?.positions.orEmpty().map { row ->
                    Holding(
                        denom = "position/${row.position.id}",
                        symbol = "Groundworks position",
                        amount = derthValue(row.position.derth, row.position.validator),
                        detail = "${formatUerth(row.position.derth)} derth · ${row.position.validator}",
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
            privateStakedUerth = privateStakeValue,
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
            // ANML exists only as notes and the dex refuses it on every
            // transparent leg, so the swap is a note swap from shielded
            // balances both ways.
            erthUerth = state?.shieldedErthUerth,
            anmlUnits = state?.let { it.shielded["uanml"] ?: 0L },
            pools = marketsState?.pools,
            swapFeePercent = marketsState?.swapFeePercent,
            onSwap = { denomIn, amountIn, denomOut, minOut ->
                tx.requestPrivate(
                    details = TxConfirmDetails(
                        action = "Swap",
                        msgTypeUrl = PrivateMsgs.NOTE_SWAP,
                        balanceUerth = 0L,
                        amountLabel = "You pay",
                        amountValue = "${formatUerth(amountIn.toLong())} " +
                            denomIn.removePrefix("u").uppercase(),
                        minReceived = "${formatUerth(minOut.toLong())} " +
                            denomOut.removePrefix("u").uppercase() +
                            if (denomOut == Constants.UERTH_DENOM) " (fee paid from it)" else "",
                    ),
                    shieldedErth = loaded.shieldedErthUerth,
                    onSuccess = {
                        onRefresh()
                        markets.refresh()
                    },
                    run = { ctx ->
                        PrivacySession.wallet(ctx).noteSwap(denomIn, amountIn.toLong(), denomOut, minOut.toLong()).hash
                    },
                )
            },
            modifier = inset,
        )

        EarthRoute.Govern -> AllocationScreen(
            state = allocationState,
            registered = loaded.registered,
            stakedUerth = privateStake,
            onOpenStream = { nav.push(EarthRoute.Stream(it == StreamId.STREAM_ID_CARETAKER)) },
            onOpenProposals = { nav.push(EarthRoute.Proposals) },
            onOpenRemovals = { nav.push(EarthRoute.RemovalBallots) },
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


        EarthRoute.Personhood -> {
            LaunchedEffect(Unit) { privacy.refresh() }
            PersonhoodScreen(
                registered = loaded.registered,
                anmlBalance = loaded.anmlBalance,
                onRegister = onRegister,
                onClaim = onClaimAnml,
                modifier = inset,
                referrals = {
                    ReferrerSection(
                        myAddress = loaded.address,
                        boundAddress = privacyState?.referrerAddress.orEmpty(),
                        boundAt = privacyState?.referrerBoundAt ?: 0L,
                        lapseSeconds = privacyState?.referrerLapseSeconds ?: 30L * 86_400,
                        onBind = {
                            tx.requestPrivate(
                                details = TxConfirmDetails(
                                    action = "Bind referrer address",
                                    msgTypeUrl = PrivateMsgs.BIND_REFERRER,
                                    balanceUerth = 0L,
                                    recipient = loaded.address,
                                    recipientLabel = "Rewards to",
                                ),
                                shieldedErth = loaded.shieldedErthUerth,
                                onSuccess = { privacy.refresh() },
                                run = { ctx -> PrivacySession.wallet(ctx).bindReferrer(walletAddress(ctx)).hash },
                            )
                        },
                        onShare = { onShare("Join Earth: https://erth.network/ref/${loaded.address}") },
                    )
                },
            )
        }

        EarthRoute.Positions -> {
            LaunchedEffect(Unit) { privacy.refresh() }
            PositionsScreen(
                state = privacyState,
                valueOf = ::derthValue,
                lockable = derthHeld,
                onLock = { locking = true },
                onEditSplit = { resplitting = it },
                onUnlock = { row ->
                    tx.requestPrivate(
                        details = TxConfirmDetails(
                            action = "Unlock position",
                            msgTypeUrl = PrivateMsgs.UNLOCK_POSITION,
                            balanceUerth = 0L,
                            amountLabel = "Returns",
                            amountValue = "${formatUerth(row.position.derth)} derth " +
                                "(${formatUerth(derthValue(row.position.derth, row.position.validator))} ERTH)",
                        ),
                        shieldedErth = loaded.shieldedErthUerth,
                        onSuccess = { onRefresh(); privacy.refresh() },
                        run = { ctx -> PrivacySession.wallet(ctx).unlockPosition(row.position, row.keyIndex).hash },
                    )
                },
                modifier = inset,
            )
        }

        EarthRoute.RemovalBallots -> {
            LaunchedEffect(Unit) { privacy.refresh() }
            RemovalBallotsScreen(
                state = privacyState,
                registered = loaded.registered,
                onPropose = { optionId ->
                    tx.requestPrivate(
                        details = TxConfirmDetails(
                            action = "Propose removing option $optionId",
                            msgTypeUrl = PrivateMsgs.PROPOSE_REMOVAL,
                            balanceUerth = 0L,
                        ),
                        shieldedErth = loaded.shieldedErthUerth,
                        onSuccess = { privacy.refresh() },
                        run = { ctx -> PrivacySession.wallet(ctx).proposeRemoval(optionId).hash },
                    )
                },
                onVote = { optionId, yes ->
                    tx.requestPrivate(
                        details = TxConfirmDetails(
                            action = "Vote ${if (yes) "Yes" else "No"} on removing option $optionId",
                            msgTypeUrl = PrivateMsgs.VOTE_REMOVAL,
                            balanceUerth = 0L,
                        ),
                        shieldedErth = loaded.shieldedErthUerth,
                        onSuccess = { privacy.refresh() },
                        run = { ctx -> PrivacySession.wallet(ctx).voteRemoval(optionId, yes).hash },
                    )
                },
                modifier = inset,
            )
        }

        EarthRoute.Notes -> {
            LaunchedEffect(Unit) { privacy.refresh() }
            NotesScreen(
                state = privacyState,
                shielded = loaded.shielded,
                onMerge = { denom ->
                    tx.requestPrivate(
                        details = TxConfirmDetails(
                            action = "Merge notes",
                            msgTypeUrl = PrivateMsgs.SEND,
                            balanceUerth = 0L,
                        ),
                        shieldedErth = loaded.shieldedErthUerth,
                        onSuccess = { onRefresh(); privacy.refresh() },
                        run = { ctx ->
                            val w = PrivacySession.wallet(ctx)
                            // Stake notes merge by a restake (owner-locked, two per proof).
                            (if (denom.startsWith(PrivacyWallet.DERTH_PREFIX)) w.mergeStake(denom) else w.merge(denom)).hash
                        },
                    )
                },
                modifier = inset,
            )
        }

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
                    !route.human && privateStake <= 0 ->
                        "Stake ERTH privately, then lock it in a position, to take part."
                    else -> null
                },
                // Groundworks is directed by positions, not a per-account split.
                onEdit = { if (route.human) editing = id else nav.push(EarthRoute.Positions) },
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
            // The stake house: private stake votes. Every derth note in the
            // tree when voting opened is spent against that snapshot and
            // minted straight back, its weight counted; each position votes
            // with its key. Final: a note votes once.
            eligibility = if (privateStake <= 0) {
                "Stake ERTH privately to vote here. Only stake held before voting opened counts."
            } else {
                null
            },
            stakeVoteFinal = true,
            stakeVoteProgress = stakeVoteProgress?.takeIf { it.proposalId == route.id },
            onCancelStakeVote = { stakeVotes.cancel() },
            onVote = { proposal, vote -> scope.launch {
                // The weight the confirmation shows: each eligible derth note
                // and position at the snapshot's rate. Read first, so the
                // sheet says what the vote is worth rather than describing it.
                val weight = withContext(Dispatchers.IO) {
                    runCatching {
                        val w = PrivacySession.wallet(context)
                        w.stakeVoteWeight(proposal.id, w.positions().map { it.first })
                    }.getOrNull()
                }
                tx.requestPrivate(
                    details = TxConfirmDetails(
                        action = "Vote ${vote.label} with stake on #${proposal.id} (final)",
                        msgTypeUrl = PrivateMsgs.STAKE_VOTE,
                        balanceUerth = 0L,
                        amountLabel = "Weight",
                        amountValue = weight?.let { wt ->
                            val parts = listOfNotNull(
                                wt.notes.takeIf { it > 0 }?.let { "$it note${if (it == 1) "" else "s"}" },
                                wt.positionIds.size.takeIf { it > 0 }?.let { "$it position${if (it == 1) "" else "s"}" },
                            )
                            "${formatUerth(wt.uerth)} ERTH" + if (parts.isEmpty()) "" else " (${parts.joinToString()})"
                        } ?: "Private stake from before voting opened",
                    ),
                    shieldedErth = loaded.shieldedErthUerth,
                    onSuccess = onRefresh,
                    // K5: the first cast is this sheet's result; the rest
                    // follow in the background, shuffled, a sync and a
                    // random 20-120 s apart, shown and cancellable on the
                    // proposal.
                    run = { _ ->
                        val opts = listOf(WeightedVoteOption.newBuilder().setOption(vote.proto).setWeight("1").build())
                        stakeVotes.controller.startAndAwaitFirst(proposal.id, opts)
                    },
                )
            } },
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
                        amountLabel = "Weight",
                        amountValue = "1 person",
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
            // derth is not a coin: a stake note only its owner can merge,
            // vote, lock or unstake. Nothing can send or sell it.
            note = if (stake) {
                "Staked ERTH stays locked to this wallet: it can't be sent, unshielded or traded, only unstaked."
            } else {
                "Unstaking turns it into a claim, also locked to this wallet, paid out as private ERTH when it matures."
            },
            onDismiss = { staking = null },
            onConfirm = { validator, amount ->
                staking = null
                tx.requestPrivate(
                    details = TxConfirmDetails(
                        action = if (stake) "Stake ERTH" else "Unstake",
                        msgTypeUrl = if (stake) PrivateMsgs.DELEGATE else PrivateMsgs.UNDELEGATE,
                        balanceUerth = 0L,
                        amountLabel = "Amount",
                        amountValue = if (stake) {
                            "${formatUerth(amount)} ERTH"
                        } else {
                            "${formatUerth(amount)} derth (${formatUerth(derthValue(amount, validator))} ERTH)"
                        },
                        recipient = validator,
                        recipientLabel = if (stake) "Validator" else "From validator",
                    ),
                    shieldedErth = loaded.shieldedErthUerth,
                    onSuccess = onRefresh,
                    run = { ctx ->
                        val w = PrivacySession.wallet(ctx)
                        if (stake) {
                            w.delegate(validator, amount).hash
                        } else {
                            // A stake proof spends two notes: merge first if needed.
                            w.consolidateStake(PrivacyWallet.derthDenom(validator), amount)
                            w.undelegate(validator, amount).hash
                        }
                    },
                )
            },
        )
    }

    // ERTH between the public account and this wallet's own notes. Shield is
    // MsgShield, signed by the account (the coins are its), its note to our
    // own shielded address; unshield a private transfer to our own account,
    // its fee from the same notes.
    moving?.let { initial ->
        MoveSheet(
            initial = initial,
            publicUerth = loaded.balanceUerth,
            privateUerth = loaded.shieldedErthUerth,
            unshieldableUerth = loaded.unshieldableErthUerth,
            shieldFee = TxController.DEFAULT_FEE_UERTH,
            unshieldFee = TxController.feeFor(TxController.PRIVATE_GAS_ESTIMATE),
            onDismiss = { moving = null },
            onConfirm = { direction, amount ->
                moving = null
                if (direction == MoveDirection.Shield) {
                    tx.request(
                        details = TxConfirmDetails(
                            action = "Shield ERTH",
                            msgTypeUrl = PrivateMsgs.SHIELD,
                            balanceUerth = loaded.balanceUerth,
                            amountLabel = "Amount",
                            amountValue = "${formatUerth(amount)} ERTH",
                            recipient = "your private balance",
                            recipientLabel = "To",
                        ),
                        onSuccess = onRefresh,
                        build = { ctx ->
                            val out = PrivacySession.wallet(ctx).shieldOutput(Constants.UERTH_DENOM, amount)
                            val msg = MsgShield.newBuilder()
                                .setSender(walletAddress(ctx))
                                .setAmount(CoinOuterClass.Coin.newBuilder().setDenom(Constants.UERTH_DENOM).setAmount(amount.toString()))
                                .setPc(ByteString.copyFrom(out.pc.toBytes()))
                                .setCiphertext(ByteString.copyFrom(out.ciphertext))
                                .build()
                            listOf(EarthTx.anyOf(PrivateMsgs.SHIELD, msg))
                        },
                    )
                } else {
                    // At Max the fee comes out of the amount: the bundle
                    // releases exactly what the notes hold.
                    val feeFromAmount = ShieldMove.feeFromAmount(amount, loaded.unshieldableErthUerth, TxController.feeFor(TxController.PRIVATE_GAS_ESTIMATE))
                    tx.requestPrivate(
                        details = TxConfirmDetails(
                            action = "Unshield ERTH",
                            msgTypeUrl = PrivateMsgs.SEND,
                            balanceUerth = 0L,
                            amountLabel = "Amount",
                            amountValue = "${formatUerth(amount)} ERTH" + if (feeFromAmount) " less the fee" else "",
                            recipient = "your public balance",
                            recipientLabel = "To",
                        ),
                        shieldedErth = loaded.shieldedErthUerth,
                        onSuccess = onRefresh,
                        run = { ctx -> PrivacySession.wallet(ctx).unshield(walletAddress(ctx), Constants.UERTH_DENOM, amount, feeFromAmount).hash },
                    )
                }
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
            // Pool 1's token is ANML, which only notes hold: both legs and
            // the fee come from shielded balances there, in one bundle, and
            // its LP shares are share notes.
            erthAvailable = (
                (if (pool.tokenDenom == Dex.SHIELDED_ONLY) loaded.shieldedErthUerth else loaded.balanceUerth) -
                    TxController.GAS_RESERVE_UERTH
                ).coerceAtLeast(0),
            tokenAvailable = if (pool.tokenDenom == Dex.SHIELDED_ONLY) {
                loaded.shielded[pool.tokenDenom] ?: 0L
            } else {
                loaded.holdings.firstOrNull { it.denom == pool.tokenDenom }?.amount ?: 0L
            },
            // Pool 1's shares are private: share notes (dexlp/<id>) in the pool.
            shareBalance = if (pool.tokenDenom == Dex.SHIELDED_ONLY) {
                loaded.shielded[Dex.shareDenom(pool.id)] ?: 0L
            } else {
                loaded.holdings.firstOrNull { it.denom == Dex.shareDenom(pool.id) }?.amount ?: 0L
            },
            unbondingSeconds = marketsState?.lpUnbondingSeconds ?: 0L,
            onDismiss = { liquidity = null },
            onConfirm = { erthIn, tokenIn, sharesOut ->
                liquidity = null
                val adding = action == LiquidityAction.Add
                if (adding && pool.tokenDenom == Dex.SHIELDED_ONLY) {
                    tx.requestPrivate(
                        details = TxConfirmDetails(
                            action = "Add liquidity",
                            msgTypeUrl = PrivateMsgs.ADD_LIQUIDITY_SHIELDED,
                            balanceUerth = 0L,
                            amountLabel = "Deposit",
                            amountValue = "${formatUerth(erthIn.toLong())} ERTH + ${formatUerth(tokenIn.toLong())} ANML",
                            recipient = "your private balance",
                            recipientLabel = "LP shares to",
                        ),
                        shieldedErth = loaded.shieldedErthUerth,
                        onSuccess = { onRefresh(); markets.refresh() },
                        run = { ctx ->
                            PrivacySession.wallet(ctx).addLiquidityShielded(
                                pool.id, pool.tokenDenom, tokenIn.toLong(), erthIn.toLong(),
                                minShares(pool.id, erthIn, tokenIn),
                            ).hash
                        },
                    )
                    return@LiquiditySheet
                }
                if (!adding && pool.tokenDenom == Dex.SHIELDED_ONLY) {
                    // Share notes: a private withdrawal, both legs paid as
                    // notes when it matures; no account appears anywhere.
                    tx.requestPrivate(
                        details = TxConfirmDetails(
                            action = "Withdraw liquidity",
                            msgTypeUrl = PrivateMsgs.REMOVE_LIQUIDITY_SHIELDED,
                            balanceUerth = 0L,
                            amountLabel = "Shares",
                            amountValue = formatUerth(sharesOut.toLong()),
                            recipient = "your private balance",
                            recipientLabel = "Paid to",
                        ),
                        shieldedErth = loaded.shieldedErthUerth,
                        onSuccess = { onRefresh(); markets.refresh() },
                        run = { ctx ->
                            PrivacySession.wallet(ctx).removeLiquidityShielded(pool.id, pool.tokenDenom, sharesOut.toLong()).hash
                        },
                    )
                    return@LiquiditySheet
                }
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
                                // The ANML leg is paid as a note to us.
                                val note = if (pool.tokenDenom == Dex.SHIELDED_ONLY) PrivacySession.wallet(ctx).withdrawalNote() else null
                                Dex.msgRemoveLiquidity(
                                    creator,
                                    pool.id,
                                    Dex.shareDenom(pool.id),
                                    sharesOut.toString(),
                                    pc = note?.pc?.toBytes(),
                                    ciphertext = note?.ciphertext,
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
                    run {
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
                    }

                },
            )
        }
    }

    if (locking) {
        StakeSheet(
            title = "Lock stake in a position",
            choices = derthHeld.map { (denom, amount) ->
                val op = denom.removePrefix("derth/")
                DelegationRow(
                    validatorOperator = op,
                    moniker = earnState?.validators?.firstOrNull { it.validatorOperator == op }?.moniker ?: op,
                    amountUerth = amount,
                    commission = 0.0,
                )
            },
            capFor = { it.amountUerth },
            confirmLabel = "Next: choose the split",
            onDismiss = { locking = false },
            onConfirm = { validator, amount ->
                locking = false
                lockDraft = validator to amount
            },
        )
    }

    val groundworks = allocationState?.capital
    lockDraft?.let { (validator, amount) ->
        if (groundworks != null) {
            AllocationEditSheet(
                title = "Position split",
                stream = groundworks.copy(mine = emptyMap()),
                onDismiss = { lockDraft = null },
                onConfirm = { weights ->
                    lockDraft = null
                    tx.requestPrivate(
                        details = TxConfirmDetails(
                            action = "Lock position",
                            msgTypeUrl = PrivateMsgs.LOCK_POSITION,
                            balanceUerth = 0L,
                            amountLabel = "Locks",
                            amountValue = "${formatUerth(amount)} derth (${formatUerth(derthValue(amount, validator))} ERTH)",
                            recipient = validator,
                            recipientLabel = "Validator",
                        ),
                        shieldedErth = loaded.shieldedErthUerth,
                        onSuccess = { onRefresh(); privacy.refresh(); allocation.refresh() },
                        run = { ctx ->
                            val w = PrivacySession.wallet(ctx)
                            w.consolidateStake(PrivacyWallet.derthDenom(validator), amount)
                            w.lockPosition(validator, amount, weights.filterValues { it > 0 }).hash
                        },
                    )
                },
            )
        }
    }

    resplitting?.let { row ->
        if (groundworks != null) {
            AllocationEditSheet(
                title = "Position split",
                stream = groundworks.copy(mine = row.position.splits),
                onDismiss = { resplitting = null },
                onConfirm = { weights ->
                    resplitting = null
                    tx.requestPrivate(
                        details = TxConfirmDetails(
                            action = "Change position split",
                            msgTypeUrl = PrivateMsgs.UPDATE_POSITION,
                            balanceUerth = 0L,
                        ),
                        shieldedErth = loaded.shieldedErthUerth,
                        onSuccess = { privacy.refresh(); allocation.refresh() },
                        run = { ctx ->
                            PrivacySession.wallet(ctx).updatePosition(row.position, row.keyIndex, weights.filterValues { it > 0 }).hash
                        },
                    )
                },
            )
        }
    }
}

/**
 * The fewest LP shares a shielded pool deposit accepts: the shares the pool
 * would mint now (the chain's deposit maths over the current reserves and
 * share supply), less 1% for trades landing first. Blocking; "" (no bound)
 * for an empty pool, which seeds at sqrt(erth x token) instead.
 */
private fun minShares(poolId: Long, erthIn: java.math.BigInteger, tokenIn: java.math.BigInteger): String {
    val pool = Dex.pools().firstOrNull { it.id == poolId } ?: return ""
    val supply = network.erth.wallet.privacy.chain.PrivacyQueries.lpShareSupply(poolId)
    val re = pool.erthReserve.toBigInteger()
    val rt = pool.tokenReserve.toBigInteger()
    if (supply.signum() == 0 || re.signum() == 0 || rt.signum() == 0) return ""
    val shares = minOf(erthIn * supply / re, tokenIn * supply / rt)
    return SwapMath.withSlippage(shares, 100).toString()
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
            title = "Shielded notes",
            subtitle = "Merge small notes",
            icon = R.drawable.ic_shield_check,
            onClick = { nav.push(EarthRoute.Notes) },
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
