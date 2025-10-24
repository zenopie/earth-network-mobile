package network.erth.wallet.ui.pages.anml

import android.app.PendingIntent
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import network.erth.wallet.R

class NFCScannerActivity : AppCompatActivity(), PassportScannerFragment.PassportScannerListener {

    companion object {
        private const val TAG = "NFCScannerActivity"
    }

    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null
    private var passportScannerFragment: PassportScannerFragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nfc_scanner)

        Log.d(TAG, "onCreate")

        // Initialize PassportScannerFragment
        if (savedInstanceState == null) {
            passportScannerFragment = PassportScannerFragment.newInstance().apply {
                setPassportScannerListener(this@NFCScannerActivity)
            }

            supportFragmentManager.beginTransaction()
                .replace(R.id.scanner_container, passportScannerFragment!!, "passport_scanner")
                .commit()
        } else {
            passportScannerFragment = supportFragmentManager.findFragmentByTag("passport_scanner") as? PassportScannerFragment
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume - enabling NFC")

        // Setup NFC
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            Log.w(TAG, "Device doesn't support NFC")
            return
        }

        val nfcIntent = Intent(this, javaClass).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        pendingIntent = PendingIntent.getActivity(
            this,
            0,
            nfcIntent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val techLists = arrayOf(arrayOf(IsoDep::class.java.name))

        try {
            nfcAdapter?.enableForegroundDispatch(this, pendingIntent, null, techLists)
            Log.d(TAG, "NFC foreground dispatch enabled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable NFC", e)
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause - disabling NFC")

        try {
            nfcAdapter?.disableForegroundDispatch(this)
            Log.d(TAG, "NFC foreground dispatch disabled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable NFC", e)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        Log.d(TAG, "onNewIntent: ${intent?.action}")

        if (intent?.action == NfcAdapter.ACTION_TECH_DISCOVERED) {
            val tag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
            }

            Log.d(TAG, "NFC Tag detected: ${tag != null}")
            tag?.let {
                passportScannerFragment?.handleNfcTag(it)
            }
        }
    }

    override fun onNFCTagDetected(tag: Tag) {
        // Not used - we handle NFC in onNewIntent
    }

    override fun requestNFCSetup() {
        // NFC is already set up in onResume
        Log.d(TAG, "requestNFCSetup called - NFC already enabled")
    }
}
