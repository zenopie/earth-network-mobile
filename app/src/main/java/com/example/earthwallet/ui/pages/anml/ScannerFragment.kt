package com.example.earthwallet.ui.pages.anml

import android.app.PendingIntent
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.example.earthwallet.R

class ScannerFragment : Fragment(), PassportScannerFragment.PassportScannerListener {

    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null
    private var intentFilters: Array<android.content.IntentFilter>? = null
    private var techLists: Array<Array<String>>? = null
    private var passportScannerFragment: PassportScannerFragment? = null

    companion object {
        @JvmStatic
        fun newInstance(): ScannerFragment = ScannerFragment()
    }

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
        val activity = activity
        if (nfcAdapter != null && activity != null) {
            nfcAdapter?.enableForegroundDispatch(activity, pendingIntent, intentFilters, techLists)
        }
    }

    override fun onPause() {
        super.onPause()
        val activity = activity
        if (nfcAdapter != null && activity != null) {
            nfcAdapter?.disableForegroundDispatch(activity)
        }
    }

    // Handle NFC intents from parent activity
    fun handleNfcIntent(intent: Intent) {
        if (NfcAdapter.ACTION_TECH_DISCOVERED == intent.action) {
            val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
            tag?.let { passportScannerFragment?.handleNfcTag(it) }
        }
    }

    override fun onNFCTagDetected(tag: Tag) {
        // This shouldn't be called since we handle NFC at the fragment level
        passportScannerFragment?.handleNfcTag(tag)
    }

    override fun requestNFCSetup() {
        // Set up NFC
        val activity = activity ?: return

        nfcAdapter = NfcAdapter.getDefaultAdapter(activity)
        if (nfcAdapter == null) {
            // Device doesn't support NFC
            return
        }

        val nfcIntent = Intent(activity, activity.javaClass).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        pendingIntent = PendingIntent.getActivity(
            activity,
            0,
            nfcIntent,
            PendingIntent.FLAG_MUTABLE
        )

        intentFilters = null
        techLists = arrayOf(arrayOf(IsoDep::class.java.name))
    }
}