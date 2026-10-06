package network.erth.wallet.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import network.erth.wallet.R
import network.erth.wallet.referral.Referral

/**
 * The launcher: offers a Play Store update, if there is one, before the app
 * starts, then hands over to [MainActivity].
 */
class UpdateCheckActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "UpdateCheckActivity"
        private const val UPDATE_REQUEST_CODE = 123
    }

    private lateinit var appUpdateManager: AppUpdateManager

    private lateinit var appLogo: android.widget.ImageView
    private lateinit var statusText: TextView
    private lateinit var descriptionText: TextView
    private lateinit var updateButton: Button
    private lateinit var skipButton: Button
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_update_check)

        // Capture a referrer before anything else can navigate away. Both are
        // cheap and both no-op once one is stored: the deep link is whatever
        // launched this, the install referrer is a one-shot Play lookup that
        // fails silently on any build that did not come from the store.
        Referral.fromIntent(this, intent)
        Referral.captureInstallReferrer(this)

        appLogo = findViewById(R.id.app_logo)
        statusText = findViewById(R.id.status_text)
        descriptionText = findViewById(R.id.description_text)
        updateButton = findViewById(R.id.update_button)
        skipButton = findViewById(R.id.skip_button)
        progressBar = findViewById(R.id.progress_bar)

        appLogo.visibility = View.GONE

        appUpdateManager = AppUpdateManagerFactory.create(this)

        updateButton.setOnClickListener {
            startUpdate()
        }

        skipButton.setOnClickListener {
            proceedToMainApp()
        }

        checkForUpdates()
    }

    private fun checkForUpdates() {
        statusText.text = "Checking for updates..."
        progressBar.visibility = View.VISIBLE
        updateButton.visibility = View.GONE
        skipButton.visibility = View.GONE

        appUpdateManager.appUpdateInfo.addOnSuccessListener { appUpdateInfo ->
            when (appUpdateInfo.updateAvailability()) {
                UpdateAvailability.UPDATE_AVAILABLE -> showUpdatePrompt()
                UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS -> startImmediateUpdate()
                else -> proceedToMainApp()
            }
        }.addOnFailureListener { exception ->
            Log.e(TAG, "Failed to check for updates", exception)
            // A failed check never blocks the app.
            proceedToMainApp()
        }
    }

    private fun showUpdatePrompt() {
        progressBar.visibility = View.GONE
        appLogo.visibility = View.VISIBLE
        statusText.text = "Update Available"
        descriptionText.text = "A new version of the app is available"
        descriptionText.visibility = View.VISIBLE
        updateButton.visibility = View.VISIBLE
        updateButton.text = "Update Now"
        skipButton.visibility = View.VISIBLE
        skipButton.text = "Skip"
    }

    private fun startUpdate() {
        appUpdateManager.appUpdateInfo.addOnSuccessListener { appUpdateInfo ->
            if (appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE) {
                startImmediateUpdate()
            } else {
                proceedToMainApp()
            }
        }.addOnFailureListener { exception ->
            Log.e(TAG, "Failed to start update", exception)
            proceedToMainApp()
        }
    }

    /** The immediate update flow: Play blocks the app until it completes. */
    private fun startImmediateUpdate() {
        appUpdateManager.appUpdateInfo.addOnSuccessListener { appUpdateInfo ->
            if (appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                || appUpdateInfo.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {

                try {
                    val updateOptions = AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()
                    appUpdateManager.startUpdateFlowForResult(
                        appUpdateInfo,
                        this,
                        updateOptions,
                        UPDATE_REQUEST_CODE
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start update flow", e)
                    proceedToMainApp()
                }
            } else {
                proceedToMainApp()
            }
        }.addOnFailureListener { exception ->
            Log.e(TAG, "Failed to get update info", exception)
            proceedToMainApp()
        }
    }

    /**
     * Proceed to the app: [MainActivity], the one shell.
     */
    private fun proceedToMainApp() {
        val intent = Intent(this, MainActivity::class.java)
        // No extras forwarded. This activity is exported, so
        // they are any app's; MainActivity reads none (a referral link is
        // read here, by Referral.fromIntent).
        startActivity(intent)
        finish()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == UPDATE_REQUEST_CODE) {
            when (resultCode) {
                // Play restarts the app once the update is installed.
                RESULT_OK -> Unit
                RESULT_CANCELED -> checkForUpdates()
                else -> {
                    Log.e(TAG, "Update failed with result code: $resultCode")
                    proceedToMainApp()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()

        // Resume an update that was interrupted.
        appUpdateManager.appUpdateInfo.addOnSuccessListener { appUpdateInfo ->
            if (appUpdateInfo.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                startImmediateUpdate()
            }
        }
    }

    // launchMode is singleTop, so a referral link tapped while this activity is
    // already showing arrives here rather than through onCreate.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Referral.fromIntent(this, intent)
    }
}
