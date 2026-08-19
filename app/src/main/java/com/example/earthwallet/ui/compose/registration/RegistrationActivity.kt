package network.erth.wallet.ui.compose.registration

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ActivityInfo
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import kotlinx.coroutines.delay
import network.erth.wallet.Constants
import network.erth.wallet.chain.Bank
import network.erth.wallet.ui.ads.RewardedAds
import network.erth.wallet.ui.compose.TxConfirmDetails
import network.erth.wallet.ui.compose.TxConfirmSheet
import network.erth.wallet.ui.compose.TxOutcome
import network.erth.wallet.ui.compose.TxPendingSheet
import network.erth.wallet.ui.compose.TxResultSheet
import network.erth.wallet.wallet.services.SecureWalletManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.erth.wallet.ui.compose.EarthDetailTopBar
import network.erth.wallet.ui.theme.EarthTheme
import network.erth.wallet.ui.vendor.component.BlankBgScaffold
import network.erth.wallet.wallet.passport.PassportSession
import network.erth.wallet.wallet.utils.Referral

/**
 * Registration, start to finish, in one activity.
 *
 * Its own activity rather than a route in the main app for one reason: NFC
 * foreground dispatch is granted per-activity, and an activity that owns the
 * dispatch has to be the one on top when the passport touches the phone.
 * Everything else here would happily be a route.
 *
 * It finishes back to whatever launched it. The old flow pushed fragments into
 * HostActivity, so backing out of the scanner landed in the old app's shell
 * rather than where the person started — which is the bug this replaces.
 */
class RegistrationActivity : ComponentActivity() {

    private var nfcAdapter: NfcAdapter? = null

    /** Set by the composition so onNewIntent can hand a tag to the right step. */
    private var onTag: ((Tag) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        setContent {
            EarthTheme {
                var step: Step by remember { mutableStateOf(Step.Camera) }
                var mrz: PassportSession.Mrz? by remember { mutableStateOf(null) }
                var stage: NfcStage by remember { mutableStateOf(NfcStage.Waiting) }
                var mrzError: String? by remember { mutableStateOf(null) }

                // A referrer captured from a referral link or a Play install
                // is fixed for the session: the person did not type it and
                // should not have to, and letting them edit it turns a link
                // into a form for no reason.
                val linkedReferrer = remember { Referral.get(this@RegistrationActivity) }

                // Held across the whole flow: entered on the confirm screen but
                // not used until the broadcast, several steps later.
                var referrer: String by remember { mutableStateOf(linkedReferrer.orEmpty()) }

                // Held between the read and the broadcast. The proof is built
                // while the passport is against the phone; paying for it is a
                // separate step that can take as long as it needs.
                var scan: PassportSession.Scan? by remember { mutableStateOf(null) }
                var balanceUerth: Long by remember { mutableLongStateOf(0L) }
                var awaitingGas: Boolean by remember { mutableStateOf(false) }
                var outcome: TxOutcome? by remember { mutableStateOf(null) }

                // MsgRegister verifies an UltraHonk proof on chain and is by
                // far the slowest thing this app broadcasts, so the gap between
                // confirming and finishing is seconds of nothing. Every other
                // transaction covers that with TxSheets; this flow builds its
                // own sheets, so it has to raise the same state itself.
                var submitting: Boolean by remember { mutableStateOf(false) }

                val address = remember {
                    runCatching {
                        SecureWalletManager.getWalletAddress(this@RegistrationActivity)
                    }.getOrNull().orEmpty()
                }

                // A wallet that has never received anything has no account on
                // chain, so this reads 0 rather than failing — which is the
                // state a new human is in, and exactly what the ad is for.
                suspend fun refreshBalance() {
                    balanceUerth = withContext(Dispatchers.IO) {
                        runCatching {
                            Bank.balance(address, Constants.UERTH_DENOM).toLong()
                        }.getOrDefault(0L)
                    }
                }

                // Loaded on entry: fetching a rewarded ad takes seconds, and
                // the gate is reached within seconds of a successful read.
                LaunchedEffect(Unit) { RewardedAds.preload(this@RegistrationActivity) }

                // Back moves through the flow rather than out of it, except at
                // the first step where there is nothing behind it.
                BackHandler {
                    when (step) {
                        Step.Camera -> finish()
                        Step.Confirm -> step = Step.Camera
                        Step.Scan -> step = Step.Confirm
                    }
                }

                onTag = handler@{ tag ->
                    val fields = mrz ?: return@handler
                    if (step != Step.Scan || stage is NfcStage.Reading) return@handler
                    // IsoDep is the only tag type a passport presents; anything
                    // else touching the phone here is a transit card and should
                    // not read as a failed passport.
                    if (IsoDep.get(tag) == null) return@handler

                    stage = NfcStage.Reading
                    lifecycleScope.launch {
                        val result = withContext(Dispatchers.IO) {
                            PassportSession.read(this@RegistrationActivity, tag, fields)
                        }
                        result
                            .onSuccess { read ->
                                // Proof done, passport no longer needed. The
                                // fee gate comes next, and it may involve
                                // watching an ad — which is why proving runs
                                // first, so nobody pays attention to an advert
                                // for a registration that was never going to
                                // exist.
                                scan = read
                                refreshBalance()
                            }
                            .onFailure { e ->
                                val failure = (e as? PassportSession.FailureException)?.failure
                                stage = when (failure) {
                                    PassportSession.Failure.WrongMrz -> NfcStage.Failed(
                                        "The chip refused those details. Check the " +
                                            "passport number and dates.",
                                        canRetry = false,
                                    )
                                    PassportSession.Failure.NoSod -> NfcStage.Failed(
                                        "This passport did not provide the signed " +
                                            "data the proof needs.",
                                        canRetry = true,
                                    )
                                    else -> NfcStage.Failed(
                                        "Hold the passport flat against the phone " +
                                            "and keep it still.",
                                        canRetry = true,
                                    )
                                }
                            }
                    }
                }

                // The gas gate. Shown once the proof exists and dismissed only
                // by registering or backing out.
                scan?.let { ready ->
                    TxConfirmSheet(
                        details = TxConfirmDetails(
                            action = "Register",
                            msgTypeUrl = "/earth.personhood.v1.MsgRegister",
                            feeUerth = REGISTER_FEE,
                            balanceUerth = balanceUerth,
                        ),
                        awaitingGas = awaitingGas,
                        onConfirm = {
                            scan = null
                            submitting = true
                            lifecycleScope.launch {
                                val hash = withContext(Dispatchers.IO) {
                                    PassportSession.register(
                                        this@RegistrationActivity,
                                        ready,
                                        referrer,
                                    )
                                }
                                submitting = false
                                hash.onSuccess {
                                    setResult(RESULT_OK, Intent().putExtra(EXTRA_TX_HASH, it))
                                    finish()
                                }.onFailure { e ->
                                    outcome = TxOutcome.Failure("Register", e)
                                }
                            }
                        },
                        onDismiss = {
                            // Keep the proof. Backing out of the fee is not
                            // backing out of the scan, and rebuilding it means
                            // holding the passport against the phone again.
                            scan = null
                            stage = NfcStage.Failed(
                                "Registration was not sent. Your passport does " +
                                    "not need to be scanned again.",
                                canRetry = false,
                            )
                        },
                        onWatchAd = {
                            RewardedAds.show(this@RegistrationActivity, address) { earned ->
                                if (!earned) return@show
                                awaitingGas = true
                                lifecycleScope.launch {
                                    // The reward callback fires when the ad
                                    // finished, not when the gas lands — the
                                    // grant is a send from the gas wallet, made
                                    // out of band when Google calls the backend.
                                    // So the chain is polled rather than
                                    // trusted to be ready.
                                    repeat(GAS_POLL_ATTEMPTS) {
                                        delay(GAS_POLL_INTERVAL_MS)
                                        refreshBalance()
                                        if (balanceUerth >= REGISTER_FEE) return@launch
                                    }
                                }.invokeOnCompletion { awaitingGas = false }
                            }
                        },
                    )
                }

                // Pending, then result — the same one-position, three-state
                // arrangement TxSheets uses, so the failure badge animates in
                // over the spinner instead of appearing from nowhere.
                if (submitting) TxPendingSheet(action = "Register")
                outcome?.let { TxResultSheet(outcome = it, onDismiss = { outcome = null }) }

                // The camera step is the only one that wants a turned phone,
                // and it wants it badly — the MRZ is two long lines and a
                // portrait viewfinder cannot frame them at a readable size.
                // The rest of the flow is a form and an NFC read, both of which
                // want the phone upright and flat.
                //
                // configChanges on the activity means this rotation does not
                // recreate anything, so the composition — and a scan already in
                // progress — survives it.
                LaunchedEffect(step) {
                    requestedOrientation = if (step == Step.Camera) {
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    }
                }

                BlankBgScaffold(
                    topBar = {
                        // The camera step overlays its own controls on the
                        // preview; a bar above it would take a fifth of a
                        // landscape screen to say what the screen already
                        // shows.
                        if (step == Step.Camera) return@BlankBgScaffold
                        EarthDetailTopBar(
                            title = when (step) {
                                Step.Camera -> "Scan passport"
                                Step.Confirm -> "Passport details"
                                Step.Scan -> "Read the chip"
                            },
                            onBack = {
                                when (step) {
                                    Step.Camera -> finish()
                                    Step.Confirm -> step = Step.Camera
                                    Step.Scan -> step = Step.Confirm
                                }
                            },
                        )
                    },
                ) { padding ->
                    val inset = Modifier.padding(
                        top = padding.calculateTopPadding(),
                        bottom = padding.calculateBottomPadding(),
                    )
                    when (step) {
                        Step.Camera -> MrzCameraScreen(
                            onDetected = { mrz = it; step = Step.Confirm },
                            onManualEntry = { mrz = null; step = Step.Confirm },
                            onBack = { finish() },
                            // No inset: the preview runs edge to edge and its
                            // own scrims carry the system bar padding.
                        )
                        Step.Confirm -> MrzConfirmScreen(
                            referrer = referrer,
                            onReferrerChange = { referrer = it },
                            referrerLocked = linkedReferrer != null,
                            initial = mrz,
                            error = mrzError,
                            onContinue = {
                                mrz = it
                                mrzError = null
                                stage = NfcStage.Waiting
                                step = Step.Scan
                            },
                            modifier = inset,
                        )
                        Step.Scan -> NfcScanScreen(
                            stage = stage,
                            onRetry = { stage = NfcStage.Waiting },
                            onManualEntry = {
                                mrzError = "Check each character against the passport."
                                step = Step.Confirm
                            },
                            modifier = inset,
                        )
                    }
                }
            }
        }
    }

    private enum class Step { Camera, Confirm, Scan }

    /**
     * Take NFC while in the foreground.
     *
     * Without this Android hands the tag to whatever app claims passports, or
     * to nothing at all, and the read never starts.
     */
    override fun onResume() {
        super.onResume()
        val adapter = nfcAdapter ?: return
        val flags = PendingIntent.FLAG_MUTABLE
        val intent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            flags,
        )
        runCatching { adapter.enableForegroundDispatch(this, intent, null, null) }
    }

    override fun onPause() {
        super.onPause()
        runCatching { nfcAdapter?.disableForegroundDispatch(this) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val tag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }
        tag?.let { onTag?.invoke(it) }
    }

    companion object {
        /** What MsgRegister costs at the chain's minimum gas price. */
        private const val REGISTER_FEE = 2_000L

        // The grant is a bank send, so it lands in a block. Roughly a minute of
        // patience, which is generous for a five-second block time and cheap
        // because the sheet stays usable throughout.
        private const val GAS_POLL_ATTEMPTS = 20
        private const val GAS_POLL_INTERVAL_MS = 3_000L

        const val EXTRA_TX_HASH = "tx_hash"
    }
}
