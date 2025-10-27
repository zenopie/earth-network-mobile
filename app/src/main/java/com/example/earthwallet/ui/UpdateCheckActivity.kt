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
import network.erth.wallet.ui.host.HostActivity

/**
 * Activity that checks for updates from Google Play Store before the app starts
 * This ensures users are notified about available updates at launch
 */
class UpdateCheckActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "UpdateCheckActivity"
        private const val UPDATE_REQUEST_CODE = 123
        private const val EXTRA_TEST_MODE = "test_mode"
    }

    private lateinit var appUpdateManager: AppUpdateManager
    private var updateAvailable = false

    private lateinit var appLogo: android.widget.ImageView
    private lateinit var statusText: TextView
    private lateinit var descriptionText: TextView
    private lateinit var updateButton: Button
    private lateinit var skipButton: Button
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_update_check)

        // Initialize views
        appLogo = findViewById(R.id.app_logo)
        statusText = findViewById(R.id.status_text)
        descriptionText = findViewById(R.id.description_text)
        updateButton = findViewById(R.id.update_button)
        skipButton = findViewById(R.id.skip_button)
        progressBar = findViewById(R.id.progress_bar)

        // Hide logo initially while checking
        appLogo.visibility = View.GONE

        // Initialize update manager
        appUpdateManager = AppUpdateManagerFactory.create(this)

        // Setup button listeners
        updateButton.setOnClickListener {
            startUpdate()
        }

        skipButton.setOnClickListener {
            proceedToMainApp()
        }

        // Start update check
        checkForUpdates()
    }

    /**
     * Check Google Play Store for available updates
     */
    private fun checkForUpdates() {
        // Check if test mode is enabled
        if (intent.getBooleanExtra(EXTRA_TEST_MODE, false)) {
            Log.d(TAG, "Test mode enabled - showing update prompt")
            showUpdatePrompt(99)
            return
        }

        Log.d(TAG, "Checking for updates from Play Store...")
        statusText.text = "Checking for updates..."
        progressBar.visibility = View.VISIBLE
        updateButton.visibility = View.GONE
        skipButton.visibility = View.GONE

        appUpdateManager.appUpdateInfo.addOnSuccessListener { appUpdateInfo ->
            val updateAvailability = appUpdateInfo.updateAvailability()
            Log.d(TAG, "Update availability: $updateAvailability")

            when (updateAvailability) {
                UpdateAvailability.UPDATE_AVAILABLE -> {
                    // Update is available
                    updateAvailable = true
                    showUpdatePrompt(appUpdateInfo.availableVersionCode())
                }
                UpdateAvailability.UPDATE_NOT_AVAILABLE -> {
                    // No update available
                    Log.d(TAG, "No update available")
                    proceedToMainApp()
                }
                UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS -> {
                    // An update is already in progress
                    Log.d(TAG, "Update already in progress")
                    startImmediateUpdate()
                }
                else -> {
                    // Unknown state, proceed to app
                    Log.d(TAG, "Unknown update state, proceeding to app")
                    proceedToMainApp()
                }
            }
        }.addOnFailureListener { exception ->
            Log.e(TAG, "Failed to check for updates", exception)
            // On failure, proceed to app anyway (don't block user)
            proceedToMainApp()
        }
    }

    /**
     * Show update prompt to user
     */
    private fun showUpdatePrompt(availableVersionCode: Int) {
        Log.d(TAG, "Update available: version $availableVersionCode")

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

    /**
     * Start the update process
     */
    private fun startUpdate() {
        // In test mode, just log and don't actually update
        if (intent.getBooleanExtra(EXTRA_TEST_MODE, false)) {
            Log.d(TAG, "Test mode - user clicked Update Now (would normally open Play Store)")
            proceedToMainApp()
            return
        }

        appUpdateManager.appUpdateInfo.addOnSuccessListener { appUpdateInfo ->
            if (appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE) {
                startImmediateUpdate()
            } else {
                // Update no longer available, proceed to app
                proceedToMainApp()
            }
        }.addOnFailureListener { exception ->
            Log.e(TAG, "Failed to start update", exception)
            proceedToMainApp()
        }
    }

    /**
     * Start immediate update flow (blocks until update completes)
     */
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
     * Proceed to main app (HostActivity)
     */
    private fun proceedToMainApp() {
        Log.d(TAG, "Proceeding to main app")
        val intent = Intent(this, HostActivity::class.java)
        // Pass any extras from the original intent
        intent.putExtras(getIntent())
        startActivity(intent)
        finish()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == UPDATE_REQUEST_CODE) {
            when (resultCode) {
                RESULT_OK -> {
                    // Update succeeded, app will restart
                    Log.d(TAG, "Update completed successfully")
                    // App will restart automatically
                }
                RESULT_CANCELED -> {
                    // User canceled the update
                    Log.d(TAG, "Update canceled by user")
                    // Check if update is still available
                    checkForUpdates()
                }
                else -> {
                    // Update failed
                    Log.e(TAG, "Update failed with result code: $resultCode")
                    // Proceed to app anyway
                    proceedToMainApp()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()

        // Check if an update is already in progress
        appUpdateManager.appUpdateInfo.addOnSuccessListener { appUpdateInfo ->
            if (appUpdateInfo.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                // Resume the update if it was interrupted
                startImmediateUpdate()
            }
        }
    }
}
