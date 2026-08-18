package network.erth.wallet.ui.compose.registration

import android.app.PendingIntent
import android.content.Intent
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
                            .onSuccess { scan ->
                                val hash = withContext(Dispatchers.IO) {
                                    PassportSession.register(this@RegistrationActivity, scan)
                                }
                                hash.onSuccess {
                                    setResult(RESULT_OK, Intent().putExtra(EXTRA_TX_HASH, it))
                                    finish()
                                }.onFailure { e ->
                                    stage = NfcStage.Failed(
                                        e.message ?: "The registration was rejected.",
                                        canRetry = true,
                                    )
                                }
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

                BlankBgScaffold(
                    topBar = {
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
                            modifier = inset,
                        )
                        Step.Confirm -> MrzConfirmScreen(
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
        const val EXTRA_TX_HASH = "tx_hash"
    }
}
