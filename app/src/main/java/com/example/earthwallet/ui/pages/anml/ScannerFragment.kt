package network.erth.wallet.ui.pages.anml

import android.app.PendingIntent
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import network.erth.wallet.R

class ScannerFragment : Fragment(), PassportScannerFragment.PassportScannerListener {

    companion object {
        private const val TAG = "ScannerFragment"

        @JvmStatic
        fun newInstance(): ScannerFragment = ScannerFragment()
    }

    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null
    private var intentFilters: Array<android.content.IntentFilter>? = null
    private var techLists: Array<Array<String>>? = null
    private var passportScannerFragment: PassportScannerFragment? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Return a simple container for the PassportScannerFragment
        return inflater.inflate(R.layout.fragment_scanner_container, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize and show PassportScannerFragment
        passportScannerFragment = PassportScannerFragment.newInstance().apply {
            setPassportScannerListener(this@ScannerFragment)
        }

        childFragmentManager.beginTransaction()
            .replace(R.id.scanner_fragment_container, passportScannerFragment!!, "passport_scanner")
            .commit()
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume called")
        val activity = activity
        if (nfcAdapter != null && activity != null && pendingIntent != null) {
            try {
                nfcAdapter?.enableForegroundDispatch(activity, pendingIntent, intentFilters, techLists)
                Log.d(TAG, "NFC foreground dispatch enabled")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to enable NFC foreground dispatch", e)
            }
        } else {
            Log.w(TAG, "Cannot enable NFC: adapter=${nfcAdapter != null}, activity=${activity != null}, pendingIntent=${pendingIntent != null}")
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause called - disabling NFC")
        val activity = activity
        if (nfcAdapter != null && activity != null) {
            try {
                nfcAdapter?.disableForegroundDispatch(activity)
                Log.d(TAG, "NFC foreground dispatch disabled")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to disable NFC foreground dispatch", e)
            }
        } else {
            Log.w(TAG, "Cannot disable NFC: adapter=${nfcAdapter != null}, activity=${activity != null}")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Make absolutely sure NFC is disabled when view is destroyed
        val activity = activity
        if (nfcAdapter != null && activity != null) {
            try {
                nfcAdapter?.disableForegroundDispatch(activity)
            } catch (e: Exception) {
                // Ignore if already disabled
            }
        }
    }

    // Handle NFC intents from parent activity
    fun handleNfcIntent(intent: Intent) {
        Log.d(TAG, "handleNfcIntent called with action: ${intent.action}")
        if (NfcAdapter.ACTION_TECH_DISCOVERED == intent.action) {
            val tag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
            }
            Log.d(TAG, "Tag extracted: ${tag != null}")
            tag?.let {
                Log.d(TAG, "Passing tag to PassportScannerFragment")
                passportScannerFragment?.handleNfcTag(it)
            }
        }
    }

    override fun onNFCTagDetected(tag: Tag) {
        // This shouldn't be called since we handle NFC at the fragment level
        passportScannerFragment?.handleNfcTag(tag)
    }

    override fun requestNFCSetup() {
        Log.d(TAG, "requestNFCSetup called")
        // Set up NFC
        val activity = activity ?: run {
            Log.w(TAG, "Activity is null, cannot setup NFC")
            return
        }

        nfcAdapter = NfcAdapter.getDefaultAdapter(activity)
        if (nfcAdapter == null) {
            Log.w(TAG, "Device doesn't support NFC")
            return
        }

        Log.d(TAG, "NFC adapter obtained, setting up pending intent")
        val nfcIntent = Intent(activity, activity.javaClass).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        pendingIntent = PendingIntent.getActivity(
            activity,
            0,
            nfcIntent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        intentFilters = null
        techLists = arrayOf(arrayOf(IsoDep::class.java.name))
        Log.d(TAG, "NFC setup complete, pending intent created")

        // Enable foreground dispatch immediately since we're on the scanner fragment
        if (isResumed && nfcAdapter != null && pendingIntent != null) {
            try {
                nfcAdapter?.enableForegroundDispatch(activity, pendingIntent, intentFilters, techLists)
                Log.d(TAG, "NFC foreground dispatch enabled immediately in requestNFCSetup")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to enable NFC foreground dispatch in requestNFCSetup", e)
            }
        } else {
            Log.d(TAG, "Not enabling NFC foreground dispatch yet - will enable in onResume (isResumed=$isResumed)")
        }
    }
}