package network.erth.wallet.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import network.erth.wallet.privacy.sync.PendingMove
import network.erth.wallet.privacy.chain.PrivacyQueries
import network.erth.wallet.wallet.SecureWalletManager
import network.erth.wallet.privacy.PrivacyWallet
import network.erth.wallet.privacy.tx.ShieldMove
import network.erth.wallet.privacy.tx.PrivateMsgs
import network.erth.wallet.chain.Allocation
import network.erth.wallet.chain.EarthTx
import network.erth.earth.proto.shielded.MsgShield
import cosmos.base.v1beta1.CoinOuterClass
import com.google.protobuf.ByteString
import network.erth.wallet.chain.Dex
import network.erth.wallet.chain.Gov
import network.erth.wallet.chain.Personhood
import androidx.compose.foundation.layout.fillMaxWidth
import network.erth.wallet.ui.components.LOADING
import network.erth.wallet.ui.components.LoadingScreen
import network.erth.wallet.ui.components.formatUerth
import network.erth.wallet.ui.earn.DelegationRow
import network.erth.wallet.ui.earn.EarnScreen
import network.erth.wallet.ui.earn.EarnUiState
import network.erth.wallet.ui.earn.EarnViewModel
import network.erth.wallet.ui.earn.MoveStakeSheet
import network.erth.wallet.ui.earn.PrivateStakeRow
import network.erth.wallet.ui.earn.UnstakingRow
import network.erth.wallet.ui.earn.StakeSheet
import network.erth.wallet.ui.explore.ExploreScreen
import network.erth.wallet.ui.explore.ExploreUiState
import network.erth.wallet.ui.explore.ExploreViewModel
import network.erth.wallet.ui.govern.AllocationEditSheet
import network.erth.wallet.ui.govern.AllocationScreen
import network.erth.wallet.ui.govern.AllocationUiState
import network.erth.wallet.ui.govern.AllocationViewModel
import network.erth.wallet.ui.govern.GroundworksVoteControls
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
import network.erth.wallet.ui.privacy.PrivacyActionsState
import network.erth.wallet.ui.privacy.PrivacyActionsViewModel
import network.erth.wallet.ui.privacy.HandleScreen
import network.erth.wallet.ui.privacy.PersonalState
import network.erth.wallet.ui.privacy.MoveToWalletScreen
import network.erth.wallet.privacy.Reminders
import network.erth.wallet.privacy.handles.Handles
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
import network.erth.wallet.ui.wallet.ActivityDetailSheet
import network.erth.wallet.ui.wallet.ActivityEntry
import network.erth.wallet.ui.wallet.ActivityView
import network.erth.wallet.ui.wallet.ActivityScreen
import network.erth.wallet.ui.wallet.ReceiveScreen
import network.erth.wallet.ui.wallet.Holding
import network.erth.wallet.ui.wallet.ReceiveUiState
import network.erth.wallet.ui.wallet.SendFlow
import network.erth.wallet.ui.wallet.MoveDirection
import network.erth.wallet.ui.wallet.TransactionDetailScreen
import network.erth.wallet.ui.wallet.WalletUiState
import network.erth.wallet.ui.wallet.WalletsScreen
import network.erth.wallet.ui.wallet.DeleteWalletScreen
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
    activity: List<ActivityEntry>?,
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
    /** AppRefresh.version: bumped after each post-tx re-read, for reads kept here. */
    refreshVersion: Int,
    /** The wallet's balances were never read and the last try failed (WalletViewModel.unreachable). */
    unreachable: Boolean,
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
    // The validator a stake card's action names (null: the picker).
    var stakingFor by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<StreamId?>(null) }
    // The wallet being deleted and its phrase, read after a fresh unlock;
    // held only while its delete screen is up.
    var deleteTarget by remember { mutableStateOf<Pair<Int, String>?>(null) }
    // Moving to another wallet: the chosen wallet (null while choosing),
    // whether this flow created it, and whether a create was started from it.
    var moveTarget by remember { mutableStateOf<Int?>(null) }
    var moveTargetNew by remember { mutableStateOf(false) }
    var creatingForMove by remember { mutableStateOf(false) }
    var liquidity by remember { mutableStateOf<Pair<LiquidityAction, Dex.Pool>?>(null) }
    // Shield / Unshield from the wallet home.
    // The activity row whose detail sheet is open (by id: the live row is shown).
    var openActivity by remember { mutableStateOf<String?>(null) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    // A stake vote in the making: the votes still to confirm, one sheet each
    // (one per validator), each sent only on its own tap.
    var stakeVoting by remember { mutableStateOf<StakeVoting?>(null) }
    // A validator with more notes than one vote holds: the user picks how,
    // before any of the votes is sent.
    var partsChoice by remember { mutableStateOf<Pair<StakeVoting, PrivacyWallet.StakeVoteItem.Validator>?>(null) }
    // This identity's handle, caretaker vote and what is due (reminders only).
    val personal by privacy.personal.collectAsStateWithLifecycle()
    val nodeNotice by network.erth.wallet.chain.NodeConfig.notice.collectAsStateWithLifecycle()
    val now = System.currentTimeMillis() / 1000
    val onShare = { text: String ->
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_TEXT, text)
        context.startActivity(android.content.Intent.createChooser(send, null))
    }

    // Private stake: derth notes per validator.
    val derthHeld = loaded.shielded.filterKeys { it.startsWith("derth/") }
    val privateStake = Amounts.satSum(derthHeld.values)
    // The same stake in ERTH: derth is a claim at rate_v, so face value
    // under-reports it once rewards have compounded.
    fun derthValue(derth: Long, validator: String): Long =
        earnState?.derthValue(derth, validator) ?: derth
    fun monikerOf(validator: String): String =
        earnState?.monikerOf(validator) ?: validator
    val activityView = ActivityView(visible = balancesVisible, name = ::monikerOf, now = now)

    // ERTH between the public account and this wallet's own notes. Shield is
    // MsgShield, signed by the account (the coins are its), its note to our
    // own shielded address; unshield a private transfer to our own account,
    // its fee from the same notes.
    fun move(direction: MoveDirection, amount: Long) {
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
                run = { ctx -> PrivacySession.wallet(ctx).unshield(walletAddress(ctx), Constants.UERTH_DENOM, amount, feeFromAmount).hash },
            )
        }
    }

    // Votes all of this wallet's stake in Groundworks with [split] (empty:
    // stops): one restake per validator, sent one after the other on this one
    // confirmation (PrivacyWallet.castGroundworks).
    fun castGroundworks(split: Map<Long, Long>, action: String) {
        val validators = derthHeld.count { it.value > 0 }
        val options = allocationState?.capital?.options.orEmpty()
        tx.requestPrivate(
            details = TxConfirmDetails(
                action = action,
                msgTypeUrl = PrivateMsgs.RESTAKE,
                balanceUerth = 0L,
                amountLabel = if (split.isEmpty()) null else "Split",
                amountValue = split.entries.sortedByDescending { it.value }.joinToString(", ") { (id, pct) ->
                    val name = options.firstOrNull { it.id == id }?.name ?: "Option $id"
                    "$name $pct%"
                }.ifEmpty { null },
                recipient = if (validators == 1) "At least 1 transaction" else "At least $validators transactions, one per validator",
                recipientLabel = "Sends",
            ),
            shieldedErth = loaded.shieldedErthUerth,
            run = { ctx ->
                PrivacySession.wallet(ctx).castGroundworks(split).lastOrNull()?.hash
                    ?: throw IllegalStateException("Stake ERTH first: your stake is what votes.")
            },
        )
    }

    // LP shares: public ones are ordinary coins (dexlp/<pool>) in the
    // balances call; private ones (a shielded deposit's) are share notes of
    // the same denom in the pool. Both count as this wallet's.
    val shares = remember(state?.holdings, state?.shielded) {
        if (state == null) return@remember null
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
            coins = state?.let { s -> ShieldMove.coins(s.holdings.associate { it.denom to it.amount }, s.shielded) },
            unshieldableUerth = state?.unshieldableErthUerth ?: 0L,
            shieldFee = TxController.DEFAULT_FEE_UERTH,
            unshieldFee = TxController.feeFor(TxController.PRIVATE_GAS_ESTIMATE),
            onMove = ::move,
            balancesVisible = balancesVisible,
            activity = activity,
            activityView = activityView,
            onActivity = { openActivity = it.id },
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
            // What is due, never done unasked: each reminder opens where it is done.
            // A node that failed its recheck (or awaits it) leads: Earth's node is in use meanwhile.
            reminders = listOfNotNull(
                nodeNotice?.let { it to { nav.push(EarthRoute.Network) } },
                // Never read and the node did not answer: "…" alone would look like loading forever.
                if (unreachable && state == null) "Can't reach the network. Pull down to try again." to { nav.push(EarthRoute.Network) } else null,
            ) + personal?.reminders.orEmpty().mapNotNull { r ->
                Reminders.text(r, now) to when (r) {
                    // The Claim button already says so.
                    Reminders.Reminder.AnmlReady -> return@mapNotNull null
                    is Reminders.Reminder.CaretakerExpiring -> { { nav.push(EarthRoute.Stream(true)) } }
                    is Reminders.Reminder.GroundworksExpiring -> { { nav.push(EarthRoute.Stream(false)) } }
                    is Reminders.Reminder.HandleExpiring, is Reminders.Reminder.HandlePaysElsewhere -> { { nav.push(EarthRoute.Handle) } }
                    is Reminders.Reminder.MoveSuggested -> { { nav.push(EarthRoute.Personhood) } }
                    // The handle first when it is one of them; the caretaker vote is renewed where it is cast.
                    is Reminders.Reminder.RenewBeforeLapse -> if (r.handle.isNotEmpty()) { { nav.push(EarthRoute.Handle) } } else { { nav.push(EarthRoute.Stream(true)) } }
                }
            },
            // Private stake (derth) is notes, not bank balances, so it joins
            // the portfolio here: its ERTH value, the derth amount beneath.
            // An undelegation waiting for its payout shows from this wallet's
            // own record until the chain mints the payout to it.
            holdings = state?.shielded.orEmpty()
                .filterKeys { it.startsWith("derth/") }
                .map { (denom, amount) ->
                    val op = denom.removePrefix("derth/")
                    Holding(
                        denom = denom,
                        symbol = "Staked (private)",
                        amount = derthValue(amount, op),
                        detail = "${formatUerth(amount)} derth · $op",
                    )
                } + state?.unstaking.orEmpty().map { u ->
                    Holding(
                        denom = "unstaking/${u.txHash}",
                        symbol = "Unstaking (private)",
                        amount = u.value ?: derthValue(u.derth, u.validator),
                        detail = u.dueBy?.let { "Arrives by about ${network.erth.wallet.ui.privacy.date(it)}" } ?: "Arrives once the unbonding period ends",
                    )
                },
            onSeeAllActivity = { nav.push(EarthRoute.Activity) },
            modifier = inset,
            contentPadding = padding,
        )

        EarthRoute.Earn -> {
          LaunchedEffect(Unit) { privacy.refresh() }
          EarnScreen(
            state = earnState,
            pools = marketsState?.pools,
            swapFeePercent = marketsState?.swapFeePercent,
            lpOptionShare = marketsState?.lpOptionShare ?: 0.0,
            lpUnbondings = marketsState?.unbondings.orEmpty(),
            lpShares = shares,
            onAddLiquidity = { liquidity = LiquidityAction.Add to it },
            onRemoveLiquidity = { liquidity = LiquidityAction.Remove to it },
            onStake = { stakingFor = it; staking = StakeIntent.Stake },
            onUnstake = { stakingFor = it; staking = StakeIntent.Unstake },
            onMove = { stakingFor = it; staking = StakeIntent.Move },
            canStake = privacyState != null,
            balancesVisible = balancesVisible,
            // The wallet's stake notes per validator (StakeRound.lines).
            privateStake = privacyState?.let { ps -> network.erth.wallet.privacy.StakeRound.lines(
                notes = ps.stakeNotes,
                rate = { v -> earnState?.derthRates?.get(v) ?: java.math.BigDecimal.ONE },
            ).map { line ->
                val h = privacyState?.stake?.firstOrNull { it.validator == line.validator }
                PrivateStakeRow(
                    validator = line.validator,
                    moniker = monikerOf(line.validator),
                    derth = line.derth,
                    valueUerth = line.value,
                    free = h?.free ?: 0L,
                    locked = h?.locked ?: 0L,
                    lockedUerth = derthValue(h?.locked ?: 0L, line.validator),
                    lockedUntil = h?.lockedUntil,
                    notes = h?.notes ?: 0,
                    mergeable = h?.mergeable ?: false,
                    standing = earnState?.all?.firstOrNull { it.validator == line.validator }?.let { network.erth.wallet.privacy.StakeRound.Standing.of(it) },
                    commission = earnState?.commissionOf(line.validator) ?: 0.0,
                )
            } },
            unstaking = state?.unstaking?.map { u ->
                UnstakingRow(
                    key = u.txHash,
                    validator = u.validator,
                    moniker = monikerOf(u.validator),
                    valueUerth = u.value ?: derthValue(u.derth, u.validator),
                    dueBy = u.dueBy,
                )
            },
            onMerge = { validator ->
                tx.requestPrivate(
                    details = TxConfirmDetails(
                        action = "Merge stake notes",
                        msgTypeUrl = PrivateMsgs.RESTAKE,
                        balanceUerth = 0L,
                        recipient = validator,
                        recipientLabel = "Validator",
                    ),
                    shieldedErth = loaded.shieldedErthUerth,
                    run = { ctx -> PrivacySession.wallet(ctx).restake(validator).hash },
                )
            },
            modifier = inset,
          )
        }

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
                            denomOut.removePrefix("u").uppercase(),
                    ),
                    shieldedErth = loaded.shieldedErthUerth,
                    run = { ctx ->
                        // An amount past a note's range is refused, never truncated.
                        PrivacySession.wallet(ctx).noteSwap(denomIn, amountIn.longValueExact(), denomOut, minOut.longValueExact()).hash
                    },
                )
            },
            modifier = inset,
        )

        EarthRoute.Govern -> AllocationScreen(
            state = allocationState,
            registered = state?.registered,
            stakedUerth = privateStake.takeIf { state != null },
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

        // Both wait for the wallet's read: an empty QR, or "that leaves
        // nothing for the fee" against a balance not read yet, is wrong.
        EarthRoute.Receive -> if (state == null) LoadingScreen(inset) else ReceiveScreen(
            state = ReceiveUiState(address = loaded.address, shieldedAddress = loaded.shieldedAddress),
            modifier = inset,
        )

        EarthRoute.Send -> if (state == null) LoadingScreen(inset) else SendFlow(
            state = loaded,
            tx = tx,
            // The send is re-read by AppRefresh once the wallet sees its block.
            onSent = {},
            modifier = inset,
        )

        EarthRoute.Activity -> ActivityScreen(rows = activity, view = activityView, onOpen = { openActivity = it.id }, modifier = inset)

        EarthRoute.Settings -> {
            var forgetting by remember { mutableStateOf(false) }
            SettingsScreen(
                items = settingsItems(nav, state, personal) { forgetting = true },
                version = version,
                modifier = inset,
            )
            if (forgetting) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { forgetting = false },
                    title = { androidx.compose.material3.Text("Forget private data?") },
                    text = {
                        androidx.compose.material3.Text(
                            "Deletes this wallet's shielded notes, registration record and sync data from this phone. " +
                                "Nothing on chain changes: the next sync finds everything again from the recovery phrase.",
                        )
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            forgetting = false
                            scope.launch {
                                withContext(Dispatchers.IO) { runCatching { network.erth.wallet.privacy.PrivacySession.forgetPrivateData(context) } }
                                onRefresh()
                            }
                        }) { androidx.compose.material3.Text("Forget") }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { forgetting = false }) { androidx.compose.material3.Text("Cancel") }
                    },
                )
            }
        }

        EarthRoute.Security -> SecurityScreen(modifier = inset)

        EarthRoute.Notifications -> network.erth.wallet.ui.settings.NotificationsScreen(modifier = inset)

        EarthRoute.Network -> network.erth.wallet.ui.settings.NetworkScreen(onChanged = onRefresh, modifier = inset)

        EarthRoute.About -> AboutScreen(
            version = version,
            onPrivacyPolicy = { onOpenUrl("https://erth.network/privacy-policy") },
            onSource = { onOpenUrl("https://github.com/zenopie/earth-network-mobile") },
            modifier = inset,
        )


        EarthRoute.Personhood -> {
            LaunchedEffect(Unit) { privacy.refresh() }
            // After a switch: what the identity this one replaced can bring here (it syncs that wallet).
            var moveOffer by remember { mutableStateOf<PrivacySession.MoveOffer?>(null) }
            var offerTick by remember { mutableStateOf(0) }
            LaunchedEffect(loaded.registered, offerTick, refreshVersion) {
                moveOffer = if (!loaded.registered) null else withContext(Dispatchers.IO) { runCatching { PrivacySession.moveOffer(context) }.getOrNull() }
            }
            fun bring(kind: String) {
                val offer = moveOffer ?: return
                val handle = kind == PendingMove.HANDLE
                tx.requestPrivate(
                    details = TxConfirmDetails(
                        action = if (handle) "Bring @${offer.handle} to this identity" else "Bring your Caretaker split to this identity",
                        msgTypeUrl = if (handle) PrivateMsgs.MOVE_HANDLE else PrivateMsgs.MOVE_CARETAKER,
                        balanceUerth = 0L,
                        // The identity this one replaced pays: another wallet's, or this one's.
                        rows = listOf("Fee paid by" to if (offer.withinWallet) "This wallet (its private ERTH)" else "${offer.fromName} (its private ERTH)"),
                    ),
                    shieldedErth = offer.feeErth,
                    onSuccess = { offerTick++ },
                    run = run@{ ctx ->
                        if (offer.withinWallet) {
                            // This wallet's earlier identity: one phrase holds both secrets.
                            val w = PrivacySession.wallet(ctx)
                            w.sync()
                            return@run (if (handle) w.moveHandleWithin(offer.fromGeneration, expected = offer.handle) else w.moveCaretakerWithin(offer.fromGeneration)).hash
                        }
                        // Proven with both identities' secrets: this wallet's (the successor)
                        // and the previous one's, which builds and pays for the tx.
                        val to = PrivacySession.selfAsSuccessor(ctx)
                        val from = PrivacySession.walletAt(ctx, offer.fromIndex)
                        from.sync()
                        val rec = PrivacySession.recorderFor(ctx, PrivacySession.storeIdOf(to.keys))
                        (if (handle) from.moveHandle(to, rec, expected = offer.handle) else from.moveCaretaker(to, rec)).hash
                    },
                )
            }
            PersonhoodScreen(
                registered = state?.registered,
                onRegister = onRegister,
                onHandle = { nav.push(EarthRoute.Handle) },
                onSwitch = { moveTarget = null; moveTargetNew = false; nav.push(EarthRoute.SwitchIdentity) },
                registeredBefore = loaded.registeredBefore,
                handle = personal?.handle,
                moveOffer = moveOffer,
                onBringHandle = { bring(PendingMove.HANDLE) },
                onBringVote = { bring(PendingMove.CARETAKER) },
                onCheckMoves = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            moveOffer?.takeIf { !it.withinWallet }?.let { o ->
                                runCatching {
                                    val from = PrivacySession.walletAt(context, o.fromIndex)
                                    from.resolvePendingMoves()
                                    PrivacySession.retryMoveRecords(context, from)
                                }
                            }
                            runCatching { PrivacySession.wallet(context).resolvePendingMoves() }
                        }
                        offerTick++
                        privacy.refreshPersonal()
                    }
                },
                modifier = inset,
            )
        }

        EarthRoute.Handle -> {
            LaunchedEffect(Unit) { privacy.refreshPersonal() }
            // Renew-only: the wallet refuses a bind that would change the handle held.
            fun renewHandle(h: String) = tx.requestPrivate(
                details = TxConfirmDetails(
                    action = "Renew @$h for a year",
                    msgTypeUrl = PrivateMsgs.BIND_HANDLE,
                    balanceUerth = 0L,
                    recipient = "@$h · ${Handles.truncate(loaded.shieldedAddress)}",
                    recipientLabel = "Pays",
                ),
                estimatedFee = TxController.feeFor(TxController.BIND_HANDLE_GAS_ESTIMATE),
                shieldedErth = loaded.shieldedErthUerth,
                run = { ctx -> PrivacySession.wallet(ctx).bindHandle(h, renewOnly = true).hash },
            )
            HandleScreen(
                state = personal,
                now = now,
                onClaim = { h ->
                    val changing = personal?.handle?.isNotEmpty() == true
                    tx.requestPrivate(
                        details = TxConfirmDetails(
                            action = if (changing) "Change handle to @$h" else "Claim @$h",
                            msgTypeUrl = PrivateMsgs.BIND_HANDLE,
                            balanceUerth = 0L,
                            recipient = "@$h · ${Handles.truncate(loaded.shieldedAddress)}",
                            recipientLabel = "Pays",
                        ),
                        estimatedFee = TxController.feeFor(TxController.BIND_HANDLE_GAS_ESTIMATE),
                        shieldedErth = loaded.shieldedErthUerth,
                        run = { ctx -> PrivacySession.wallet(ctx).bindHandle(h).hash },
                    )
                },
                onRenew = {
                    val h = personal?.handle.orEmpty()
                    if (h.isNotEmpty()) renewHandle(h)
                },
                onRenewAddressed = { h -> if (personal?.handle.isNullOrEmpty()) renewHandle(h) },
                onRelease = {
                    val h = personal?.handle.orEmpty()
                    tx.requestPrivate(
                        details = TxConfirmDetails(
                            action = "Release @$h",
                            msgTypeUrl = PrivateMsgs.BIND_HANDLE,
                            balanceUerth = 0L,
                        ),
                        estimatedFee = TxController.feeFor(TxController.BIND_HANDLE_GAS_ESTIMATE),
                        shieldedErth = loaded.shieldedErthUerth,
                        run = { ctx -> PrivacySession.wallet(ctx).releaseHandle().hash },
                    )
                },
                onShare = { h -> onShare("Pay me privately on Earth: @$h · join with https://erth.network/ref/$h") },
                modifier = inset,
            )
        }

        EarthRoute.SwitchIdentity -> {
            LaunchedEffect(Unit) { privacy.refreshPersonal(); wallets.refresh() }
            // What a chosen target already holds.
            var targetWarning by remember { mutableStateOf<String?>(null) }
            // One step only: what this identity's predecessor still holds that has not moved here.
            var unmoved by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(Unit) {
                val o = withContext(Dispatchers.IO) { runCatching { PrivacySession.moveOffer(context) }.getOrNull() }
                unmoved = o?.takeIf { it.anything }?.let {
                    val what = listOfNotNull(
                        it.handle.takeIf { h -> h.isNotEmpty() }?.let { h -> "@$h" },
                        "your caretaker vote".takeIf { _ -> it.voteLive },
                    ).joinToString(" and ").ifEmpty { "a move still in flight" }
                    if (it.withinWallet) "Your previous identity still holds $what."
                    else "${it.fromName} still holds $what."
                }
            }
            val walletList = walletsState?.wallets.orEmpty()
            fun check(idx: Int) {
                scope.launch {
                    val info = withContext(Dispatchers.IO) { runCatching { PrivacySession.targetInfo(context, idx) }.getOrNull() }
                    // What a move after the switch could not bring there, said up front.
                    // Any wallet can be a target: it registers its next unused identity.
                    targetWarning = when {
                        info == null -> null
                        info.handleRefusal != null || info.voteRefusal != null ->
                            listOfNotNull(info.handleRefusal?.let { "Handle can't move there: $it." }, info.voteRefusal?.let { "Vote can't move there: $it." }).joinToString(" ")
                        info.live -> "That wallet is registered · its identity is replaced"
                        info.registered -> "That wallet's registration may land first · then retry"
                        else -> null
                    }
                }
            }
            // What this identity holds whose lease ends soon: renewed first (it still can), its move after the switch has a full lease.
            val renewFirst = personal?.takeIf { it.identityLive }?.let { p ->
                network.erth.wallet.ui.privacy.renewSoonText(p.handle, p.handleEntry?.expiresAt ?: 0L, p.caretakerExpiresAt, now)
            }
            val current = walletsState?.selectedIndex ?: SecureWalletManager.getSelectedWalletIndex()
            LaunchedEffect(Unit) { creatingForMove = false; moveTarget?.let(::check) }
            MoveToWalletScreen(
                wallets = walletList,
                currentIndex = current,
                target = moveTarget,
                targetIsNew = moveTargetNew,
                targetWarning = targetWarning.takeIf { moveTarget != null },
                unmoved = unmoved,
                renewFirst = renewFirst,
                onRenewFirst = {
                    val p = personal
                    val handleSoon = p != null && p.handle.isNotEmpty() &&
                        network.erth.wallet.ui.privacy.renewSoonText(p.handle, p.handleEntry?.expiresAt ?: 0L, 0L, now) != null
                    nav.push(if (handleSoon) EarthRoute.Handle else EarthRoute.Stream(true))
                },
                onPick = { idx -> moveTarget = idx; moveTargetNew = false; targetWarning = null; check(idx) },
                onNewWallet = {
                    creatingForMove = true
                    wallets.beginCreate()
                    nav.push(EarthRoute.CreateWallet)
                },
                onBack = { moveTarget = null; moveTargetNew = false; targetWarning = null },
                onScan = { target ->
                    // The target registers the same passport to its next
                    // identity: the chain treats it as a switch (the live
                    // leaf is zeroed).
                    if (target != current) onSwitchWallet(target)
                    moveTarget = null
                    nav.pop()
                    onRegister()
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
            var confirmingDelete by remember { mutableStateOf(false) }
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
                onDelete = { confirmingDelete = true },
                // The top bar's name comes from the wallet read.
                onRename = { name -> walletsState?.selectedIndex?.let { wallets.rename(it, name) { onRefresh() } } },
                modifier = inset,
            )
            if (confirmingDelete) {
                network.erth.wallet.ui.unlock.ConfirmUnlockDialog(
                    onConfirmed = {
                        confirmingDelete = false
                        val idx = walletsState?.selectedIndex ?: SecureWalletManager.getSelectedWalletIndex()
                        val phrase = runCatching { SecureWalletManager.executeWithMnemonicAt(context, idx) { it } }.getOrNull()
                        if (phrase != null) {
                            deleteTarget = idx to phrase
                            nav.push(EarthRoute.DeleteWallet)
                        }
                    },
                    onDismiss = { confirmingDelete = false },
                )
            }
        }

        EarthRoute.AddCaretakerOption -> network.erth.wallet.ui.govern.AddCaretakerOptionScreen(
            address = loaded.address,
            publicUerth = state?.balanceUerth,
            networkFee = TxController.DEFAULT_FEE_UERTH,
            onReview = { name, recipient, burn ->
                // Signed by the public account, which pays the burn; no registration asked.
                tx.request(
                    details = TxConfirmDetails(
                        action = "Add Caretaker option",
                        msgTypeUrl = Allocation.MSG_ADD_ADDRESS_OPTION_TYPE_URL,
                        balanceUerth = loaded.balanceUerth,
                        rows = listOf(
                            "Description" to network.erth.wallet.chain.OptionText.clean(name, 0),
                            "Pays to" to recipient,
                            "Burned" to "${formatUerth(burn)} ERTH, public",
                        ),
                    ),
                    // Back to the stream, which re-reads its options; only if still here.
                    onSuccess = { if (nav.current == EarthRoute.AddCaretakerOption) nav.pop() },
                    build = { ctx ->
                        listOf(EarthTx.anyOf(Allocation.MSG_ADD_ADDRESS_OPTION_TYPE_URL, Allocation.addCaretakerOption(walletAddress(ctx), name, recipient)))
                    },
                )
            },
            modifier = inset,
        )

        EarthRoute.DeleteWallet -> {
            val target = deleteTarget
            var deleting by remember { mutableStateOf(false) }
            // The phrase goes when the screen does, and when the app is backgrounded.
            val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
            androidx.compose.runtime.DisposableEffect(owner) {
                val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
                    if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP && !deleting) { deleteTarget = null; nav.pop() }
                }
                owner.lifecycle.addObserver(obs)
                onDispose { owner.lifecycle.removeObserver(obs); if (!deleting) deleteTarget = null }
            }
            if (target == null) {
                LaunchedEffect(Unit) { nav.pop() }
            } else {
                val list = walletsState?.wallets.orEmpty()
                DeleteWalletScreen(
                    name = list.getOrNull(target.first)?.name ?: "wallet",
                    phrase = target.second,
                    last = list.size <= 1,
                    deleting = deleting,
                    error = walletsError,
                    onDelete = {
                        deleting = true
                        wallets.clearError()
                        wallets.delete(target.first, onFailed = { deleting = false }) { erased ->
                            deleteTarget = null
                            deleting = false
                            // Erased: the session has ended and first run takes over.
                            if (!erased) {
                                onSwitchWallet(-1)
                                nav.popToRoot()
                            }
                        }
                    },
                    modifier = inset,
                )
            }
        }

        EarthRoute.CreateWallet -> CreateWalletScreen(
            mnemonic = draftMnemonic,
            onConfirm = { name ->
                wallets.confirmCreate(name) {
                    // createWallet selects the new wallet, so this is a switch
                    // and has to invalidate like one.
                    onSwitchWallet(-1)
                    // Started from a move: on to its passport step, for the new wallet.
                    if (creatingForMove) {
                        creatingForMove = false
                        moveTarget = SecureWalletManager.getSelectedWalletIndex()
                        moveTargetNew = true
                        nav.pop()
                        return@confirmCreate
                    }
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
                stream = if (route.human) allocationState?.human else allocationState?.capital,
                eligibility = when {
                    // Unread: the vote waits rather than saying "Register" to someone registered.
                    state == null -> LOADING
                    route.human && !loaded.registered ->
                        "Register to vote"
                    !route.human && privateStake <= 0 ->
                        "Stake ERTH to vote"
                    else -> null
                },
                onEdit = { editing = id },
                modifier = inset,
                onAddOption = if (route.human) { { nav.push(EarthRoute.AddCaretakerOption) } } else null,
                address = loaded.address,
                startPreferred = !route.human && privacyState?.groundworksLease?.let { it.lapsed(now) || it.renewalDue(now) } == true,
                onClaim = { o, owed ->
                    tx.request(
                        details = TxConfirmDetails(
                            action = "Claim option payout",
                            msgTypeUrl = Allocation.MSG_CLAIM_ALLOCATION_TYPE_URL,
                            balanceUerth = loaded.balanceUerth,
                            rows = listOf(
                                "Option" to o.name,
                                "Amount" to "About ${formatUerth(owed)} ERTH",
                                "To" to network.erth.wallet.chain.OptionText.short(o.recipient),
                            ),
                        ),
                        build = { ctx -> listOf(EarthTx.anyOf(Allocation.MSG_CLAIM_ALLOCATION_TYPE_URL, Allocation.claim(walletAddress(ctx), id, o.id))) },
                    )
                },
                // Groundworks: every stake tx carries the split onto the note it
                // makes. Vote / Change, its lease, Renew when due, Stop.
                groundworks = if (route.human) null else {
                    {
                        LaunchedEffect(Unit) { privacy.refresh() }
                        // Only votes that count on the split chosen now: weight > 0 (not stale
                        // since a stream reset) or moved-in stake voting pending.
                        val countedVotes = privacyState?.groundworksVotes.orEmpty()
                            .filter { (it.weight > 0 || it.pending > 0) && it.split == privacyState?.groundworksSplit }
                        // Unread, the controls would offer "Vote" to a wallet already voting.
                        if (privacyState == null || state == null) {
                            androidx.compose.material3.Text(LOADING, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        } else GroundworksVoteControls(
                            split = privacyState?.groundworksSplit.orEmpty(),
                            lease = privacyState?.groundworksLease,
                            options = allocationState?.capital?.options.orEmpty(),
                            now = now,
                            voting = if (!balancesVisible) null else {
                                Amounts.satSum(countedVotes) { derthValue(Amounts.satAdd(it.derth, it.pending), it.validator) } to
                                    Amounts.satSum(derthHeld.entries.toList()) { (d, v) -> derthValue(v, d.removePrefix("derth/")) }
                            },
                            pendingUntil = countedVotes.filter { it.pending > 0 }.maxOfOrNull { it.maturesAt },
                            onVote = { editing = id },
                            onRenew = { split -> castGroundworks(split, "Renew Groundworks vote") },
                            onStop = { castGroundworks(emptyMap(), "Stop Groundworks vote") },
                        )
                    }
                },
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
            // tree and unspent when voting opened proves so against that
            // snapshot without being spent (its weight rounded down to three
            // significant figures). Final:
            // a note votes once per proposal, and on every open proposal.
            eligibility = if (privateStake <= 0) "No private stake" else null,
            // The stake held now; only what was held when voting opened counts.
            stakeWeight = Amounts.satSum(derthHeld.entries.toList()) { (d, v) -> derthValue(v, d.removePrefix("derth/")) }
                .takeIf { it > 0 }?.let { "${formatUerth(it)} ERTH" },
            onVote = { proposal, vote -> scope.launch {
                // Every vote this wallet's stake takes: one per validator
                // (its notes, up to two a vote). Each is its own confirm
                // sheet and its own tx, sent on its tap.
                val items = withContext(Dispatchers.IO) {
                    runCatching { PrivacySession.wallet(context).stakeVoteItems(proposal.id) }
                }
                items.onSuccess {
                    if (it.isEmpty()) {
                        tx.showFailure("Vote with stake", IllegalStateException("No stake left to vote"))
                    } else {
                        stakeVoting = StakeVoting(proposal.id, vote, it)
                    }
                }.onFailure { tx.showFailure("Vote with stake", it) }
            } },
            // Absent on a chain without an assembly, which hides the whole
            // second house rather than explaining one that is not there yet.
            assembly = allocationState?.assemblyTallies?.get(route.id),
            assemblyEligibility = when {
                state == null -> LOADING
                !loaded.registered -> "Not registered"
                else -> null
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
                    run = { ctx ->
                        PrivacySession.wallet(ctx).voteProposal(proposal.id, vote == Assembly.Vote.Yes).hash
                    },
                )
            },
        )

        is EarthRoute.TransactionDetail -> TransactionDetailScreen(
            txHash = route.txHash,
            onOpenExplorer = { onOpenUrl("https://explorer.erth.network/tx/${route.txHash}") },
            modifier = inset,
        )
    }

    openActivity?.let { id ->
        activity?.firstOrNull { it.id == id }?.let { entry ->
            ActivityDetailSheet(entry, activityView, onOpenUrl = onOpenUrl, onDismiss = { openActivity = null })
        } ?: run { openActivity = null }
    }

    // Staking is private: ERTH is spent from shielded notes into the pool's
    // delegation to a validator and comes back as derth/<validator> notes
    // (worth more ERTH each epoch as rewards compound). Unstaking names a
    // note of ours the chain pays the ERTH to once the unbonding period ends:
    // nothing more to send. Only a validator's own self-bond is a transparent
    // delegation now.
    // One stake vote sheet at a time: the next item's sheet is raised only
    // once the last one's tx went through, and only shown: nothing is sent
    // until its own Confirm is tapped. Cancelling a sheet ends the run.
    LaunchedEffect(stakeVoting) {
        val v = stakeVoting ?: return@LaunchedEffect
        stakeVoting = null
        v.items.filterIsInstance<PrivacyWallet.StakeVoteItem.Validator>().firstOrNull { it.parts > 1 && it.validator !in v.inParts }?.let {
            partsChoice = v to it
            return@LaunchedEffect
        }
        val item = v.items.firstOrNull() ?: return@LaunchedEffect
        val rest = v.copy(items = v.items.drop(1))
        val preview = withContext(Dispatchers.IO) {
            runCatching { PrivacySession.wallet(context).stakeVotePreview(v.proposalId, item) }.getOrNull()
        }
        val where = when (item) {
            is PrivacyWallet.StakeVoteItem.Validator -> "at ${monikerOf(item.validator)}"
        }
        val left = rest.items.size
        tx.requestPrivate(
            details = TxConfirmDetails(
                action = "Vote ${v.vote.label} with stake $where on #${v.proposalId} (final)",
                msgTypeUrl = PrivateMsgs.STAKE_VOTE,
                balanceUerth = 0L,
                amountLabel = "Weight",
                amountValue = preview?.let { p ->
                    "${formatUerth(p.uerth)} ERTH" + if (p.notes > 0) " (${p.notes} note${if (p.notes == 1) "" else "s"})" else ""
                } ?: "Private stake from before voting opened",
                recipient = if (left > 0) "$left more vote${if (left == 1) "" else "s"} after this one, each confirmed on its own" else null,
                recipientLabel = "Then",
            ),
            shieldedErth = loaded.shieldedErthUerth,
            onSuccess = {
                if (rest.items.isNotEmpty()) stakeVoting = rest
            },
            run = { ctx ->
                val w = PrivacySession.wallet(ctx)
                // The last vote's fee change lands as a note: sync before the next.
                w.sync()
                val opts = listOf(WeightedVoteOption.newBuilder().setOption(v.vote.proto).setWeight("1").build())
                w.castStakeVote(v.proposalId, item, opts) ?: throw IllegalStateException("Nothing of this stake is left to vote on this proposal.")
            },
        )
    }

    partsChoice?.let { (v, item) ->
        val name = monikerOf(item.validator)
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { partsChoice = null },
            title = { androidx.compose.material3.Text("${item.notes} stake notes at $name") },
            text = {
                androidx.compose.material3.Text(
                    // Parts can be linked to each other; a merge helps only later proposals.
                    "Parts: ${item.parts} votes, a fee each.\nMerge: fewer votes on later proposals.",
                )
            },
            confirmButton = {
                Row {
                    androidx.compose.material3.TextButton(onClick = {
                        partsChoice = null
                        stakeVoting = v.copy(
                            items = v.items.flatMap { if (it == item) List(item.parts) { item } else listOf(it) },
                            inParts = v.inParts + item.validator,
                        )
                    }) { androidx.compose.material3.Text("Vote in parts") }
                    androidx.compose.material3.TextButton(onClick = {
                        partsChoice = null
                        tx.requestPrivate(
                            details = TxConfirmDetails(
                                action = "Merge two stake notes",
                                msgTypeUrl = PrivateMsgs.RESTAKE,
                                balanceUerth = 0L,
                                recipient = name,
                                recipientLabel = "Validator",
                            ),
                            shieldedErth = loaded.shieldedErthUerth,
                            run = { ctx -> PrivacySession.wallet(ctx).mergeStake(PrivacyWallet.derthDenom(item.validator)).hash },
                        )
                    }) { androidx.compose.material3.Text("Merge notes") }
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { partsChoice = null }) { androidx.compose.material3.Text("Cancel") }
            },
        )
    }

    staking?.let { intent ->
        // What may leave each validator now: moved-in stake whose window is
        // open stays where it is (refused up front, explained).
        val holdings = privacyState?.stake.orEmpty()
        val derthRows = holdings.filter { it.free > 0 }.map { h ->
            DelegationRow(
                validatorOperator = h.validator,
                moniker = monikerOf(h.validator),
                amountUerth = h.free,
                commission = earnState?.commissionOf(h.validator) ?: 0.0,
            )
        }
        if (intent == StakeIntent.Move) {
            MoveStakeSheet(
                initial = stakingFor,
                sourceDetail = { "${network.erth.wallet.ui.earn.stakeAmount(derthValue(it.amountUerth, it.validatorOperator))} ERTH" },
                sources = derthRows,
                destinations = earnState?.validators.orEmpty(),
                onDismiss = { staking = null },
                onConfirm = { src, dst, amount ->
                    staking = null
                    scope.launch {
                        // The chain's own numbers first (live rates, the
                        // debt tree): the sheet shows what the tx will carry.
                        val quote = withContext(Dispatchers.IO) { runCatching { PrivacySession.wallet(context).quoteMove(src, dst, amount) } }
                        quote.onFailure { tx.showFailure("Move stake", it) }.onSuccess { q ->
                            tx.requestPrivate(
                                details = TxConfirmDetails(
                                    action = "Move stake",
                                    msgTypeUrl = PrivateMsgs.REDELEGATE,
                                    balanceUerth = 0L,
                                    amountLabel = "Moves",
                                    amountValue = "${formatUerth(q.amount)} derth (${formatUerth(q.value)} ERTH)",
                                    recipient = "${monikerOf(q.src)} → ${monikerOf(q.dst)}\n${q.dst}",
                                    recipientLabel = "From → to",
                                    rows = listOf("Arrives as" to "${formatUerth(q.dstDerth)} derth or more"),
                                    notes = listOfNotNull(
                                        // A consequence, not an explanation: the stake is held there for the window.
                                        "Can't move, unstake or lock it again for about ${q.windowSeconds / 86_400} days.",
                                        haircutNote(q.haircut, monikerOf(q.src)),
                                    ),
                                ),
                                shieldedErth = loaded.shieldedErthUerth,
                                run = { ctx -> PrivacySession.wallet(ctx).redelegate(q).hash },
                            )
                        }
                    }
                },
            )
            return@let
        }
        val stake = intent == StakeIntent.Stake
        StakeSheet(
            title = if (stake) "Stake" else "Unstake",
            choices = if (stake) earnState?.pickList.orEmpty() else derthRows,
            initial = stakingFor,
            unit = if (stake) "ERTH" else "derth",
            rowLabel = if (stake) "To" else "From",
            pickFirst = !stake,
            detailFor = { v ->
                if (stake) network.erth.wallet.ui.earn.validatorDetail(earnState?.totalBondedUerth ?: 0L, v.commission)
                else "${network.erth.wallet.ui.earn.stakeAmount(derthValue(v.amountUerth, v.validatorOperator))} ERTH"
            },
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
                scope.launch {
                    if (stake) {
                        // The quote: the derth the chain credits at the live rate, less a margin.
                        val quote = withContext(Dispatchers.IO) { runCatching { PrivacySession.wallet(context).quoteDelegate(validator, amount) } }
                        quote.onFailure { tx.showFailure("Stake ERTH", it) }.onSuccess { q ->
                            tx.requestPrivate(
                                details = TxConfirmDetails(
                                    action = "Stake ERTH",
                                    msgTypeUrl = PrivateMsgs.DELEGATE,
                                    balanceUerth = 0L,
                                    amountLabel = "Amount",
                                    amountValue = "${formatUerth(q.amount)} ERTH",
                                    recipient = validator,
                                    recipientLabel = "Validator",
                                    rows = listOf("You receive" to "${formatUerth(q.derth)} derth"),
                                    notes = listOfNotNull(
                                        haircutNote(q.haircut, null),
                                    ),
                                ),
                                shieldedErth = loaded.shieldedErthUerth,
                                run = { ctx -> PrivacySession.wallet(ctx).delegate(q).hash },
                            )
                        }
                    } else {
                        // The quote: the value at the validator's live rate, and any cleared label's cut.
                        val quote = withContext(Dispatchers.IO) { runCatching { PrivacySession.wallet(context).quoteUndelegate(validator, amount) } }
                        quote.onFailure { tx.showFailure("Unstake", it) }.onSuccess { q ->
                            val cut = q.haircut
                            tx.requestPrivate(
                                details = TxConfirmDetails(
                                    action = "Unstake",
                                    msgTypeUrl = PrivateMsgs.UNDELEGATE,
                                    balanceUerth = 0L,
                                    amountLabel = "Amount",
                                    amountValue = "${formatUerth(amount)} derth (${formatUerth(q.value)} ERTH)",
                                    recipient = validator,
                                    recipientLabel = "From validator",
                                    notes = listOfNotNull(haircutNote(cut, null)),
                                ),
                                shieldedErth = loaded.shieldedErthUerth,
                                // A stake proof spends two notes (at most one
                                // holding moved-in stake): spread over more, it
                                // is refused with "merge them first".
                                run = { ctx -> PrivacySession.wallet(ctx).undelegate(validator, amount, maxHaircut = cut).hash },
                            )
                        }
                    }
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
            balancesKnown = state != null,
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
                        run = { ctx ->
                            // Both legs are notes: refused here, before proving, when too large.
                            network.erth.wallet.privacy.PrivacyWallet.checkWithdrawalNoteLegs(
                                sharesOut, network.erth.wallet.privacy.chain.PrivacyQueries.lpShareSupply(pool.id),
                                pool.erthReserve.toBigInteger(), pool.tokenReserve.toBigInteger(), pool.tokenDenom, erthNote = true, tokenNote = true,
                            )
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
                                    // The same bound as the shielded deposit's.
                                    minShares(pool.id, erthIn, tokenIn),
                                )
                            } else {
                                // The ANML leg is paid as a note to us.
                                val note = if (pool.tokenDenom == Dex.SHIELDED_ONLY) {
                                    network.erth.wallet.privacy.PrivacyWallet.checkWithdrawalNoteLegs(
                                        sharesOut, network.erth.wallet.privacy.chain.PrivacyQueries.lpShareSupply(pool.id),
                                        pool.erthReserve.toBigInteger(), pool.tokenReserve.toBigInteger(), pool.tokenDenom, erthNote = false, tokenNote = true,
                                    )
                                    PrivacySession.wallet(ctx).withdrawalNote()
                                } else null
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
                    if (stream == StreamId.STREAM_ID_GROUNDWORKS) {
                        castGroundworks(weights.filterValues { it > 0 }, "Vote in Groundworks")
                    } else run {
                        // Private: a membership proof in the caretaker scope.
                        // The split is public, who cast it is not, and it
                        // lapses after R (a year) unless its owner casts again:
                        // nothing refreshes it on its own; a reminder comes first.
                        tx.requestPrivate(
                            details = TxConfirmDetails(
                                action = "Set caretaker split",
                                msgTypeUrl = PrivateMsgs.SET_CARETAKER,
                                balanceUerth = 0L,
                            ),
                            shieldedErth = loaded.shieldedErthUerth,
                            run = { ctx -> PrivacySession.wallet(ctx).setCaretaker(weights.filterValues { it > 0 }).hash },
                        )
                    }

                },
            )
        }
    }
}

/**
 * The fewest LP shares a pool deposit accepts (public and shielded alike):
 * the shares the pool would mint now (the chain's deposit maths over fresh
 * reserves and share supply), less 1% for trades landing first. Blocking;
 * "" (no bound) only for an empty pool, which seeds at sqrt(erth x token)
 * instead. A pool or supply the node cannot read refuses the
 * deposit rather than sending it unbounded.
 */
private fun minShares(poolId: Long, erthIn: java.math.BigInteger, tokenIn: java.math.BigInteger): String {
    val pool = Dex.pools().firstOrNull { it.id == poolId } ?: throw IllegalStateException("could not read pool $poolId to bound the deposit; try again")
    val supply = network.erth.wallet.privacy.chain.PrivacyQueries.lpShareSupply(poolId)
    // Priced off the ERTH reserve the deposit will meet, pending LP rewards
    // settled in; the stored one when the node cannot simulate.
    val erthReserve = Dex.settledErthReserve(pool) ?: pool.erthReserve.toBigInteger()
    return SwapMath.minShares(erthIn, tokenIn, erthReserve, pool.tokenReserve.toBigInteger(), supply, 100)
}

/** Which direction the stake sheet was opened in. */
private enum class StakeIntent { Stake, Unstake, Move }

/**
 * The sentence a confirm sheet shows when the tx settles a slash's cut of
 * stake moved in from another validator (the label clears at what the debt
 * tree says it is worth). Null when nothing is cut.
 */
private fun haircutNote(haircut: Long, from: String?): String? = if (haircut <= 0) null else
    "A slash of the validator this stake was moved from${from?.let { " ($it)" } ?: ""} reached it before its window closed: " +
        "${formatUerth(haircut)} derth of it is gone, and this transaction settles that."

private fun settingsItems(nav: EarthNavController, state: WalletUiState?, personal: PersonalState?, onForgetPrivate: () -> Unit): List<SettingsItem> =
    listOf(
        SettingsItem(
            title = "Identity",
            // Null, not "Not registered", while the wallet is still loading.
            // Falling back to the empty state made this assert a fact about
            // whichever wallet had just been switched to, before anything had
            // been read about it.
            // The handle with it: it is managed from Identity, and only a
            // verified human holds one.
            subtitle = state?.let {
                when {
                    !it.registered -> "Not registered"
                    personal?.handle.isNullOrEmpty() -> "Verified human"
                    else -> "Verified human · @${personal?.handle}"
                }
            },
            icon = R.drawable.ic_shield_check,
            onClick = { nav.push(EarthRoute.Personhood) },
        ),
        SettingsItem(
            title = "Merge notes",
            subtitle = "Combine small private notes",
            icon = R.drawable.ic_shield_check,
            onClick = { nav.push(EarthRoute.Notes) },
        ),
        SettingsItem(
            title = "Forget private data",
            subtitle = "Delete this wallet's shielded data from this phone",
            icon = R.drawable.ic_lock,
            onClick = onForgetPrivate,
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
            title = "Unlocking",
            subtitle = "PIN and biometrics",
            icon = R.drawable.ic_lock,
            onClick = { nav.push(EarthRoute.Security) },
        ),
        SettingsItem(
            title = "Notifications",
            subtitle = "ANML ready, new proposals",
            icon = R.drawable.ic_notifications,
            onClick = { nav.push(EarthRoute.Notifications) },
        ),
        SettingsItem(
            title = "Network",
            subtitle = if (network.erth.wallet.chain.NodeConfig.current.isDefault) "Earth's node" else "Your node",
            icon = R.drawable.ic_home_explore,
            onClick = { nav.push(EarthRoute.Network) },
        ),
        SettingsItem(
            title = "About",
            icon = R.drawable.ic_info,
            onClick = { nav.push(EarthRoute.About) },
        ),
    )

/** The title the detail bar shows for a pushed route. */

/**
 * The stake votes still to confirm on [proposalId] (one sheet each), and the
 * validators the user chose to vote in parts.
 */
private data class StakeVoting(
    val proposalId: Long,
    val vote: Gov.Vote,
    val items: List<PrivacyWallet.StakeVoteItem>,
    val inParts: Set<String> = emptySet(),
)
