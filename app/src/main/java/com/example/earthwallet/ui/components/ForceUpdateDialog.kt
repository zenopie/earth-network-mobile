package network.erth.wallet.ui.components

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.widget.Button
import android.widget.TextView
import network.erth.wallet.R
import network.erth.wallet.wallet.services.UpdateManager

/**
 * Dialog for forcing app updates when minimum version requirements are not met
 */
class ForceUpdateDialog(
    private val context: Context,
    private val updateInfo: UpdateManager.UpdateInfo,
    private val onUpdateClick: () -> Unit = {}
) {

    fun show() {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_force_update, null)

        val titleText = dialogView.findViewById<TextView>(R.id.updateTitle)
        val messageText = dialogView.findViewById<TextView>(R.id.updateMessage)
        val versionText = dialogView.findViewById<TextView>(R.id.versionInfo)
        val updateButton = dialogView.findViewById<Button>(R.id.updateButton)

        // Set content
        titleText.text = if (updateInfo.isForceUpdate) {
            "Update Required"
        } else {
            "Update Available"
        }

        messageText.text = updateInfo.updateMessage.ifEmpty {
            if (updateInfo.isForceUpdate) {
                "This version is no longer supported. Please update to continue using the app."
            } else {
                "A new version of Earth Wallet is available with improvements and bug fixes."
            }
        }

        versionText.text = "Current: ${updateInfo.currentVersion} → Latest: ${updateInfo.latestVersion}"

        updateButton.text = if (updateInfo.isForceUpdate) "Update Now" else "Update"

        // Create dialog
        val alertDialog = AlertDialog.Builder(context)
            .setView(dialogView)
            .setCancelable(!updateInfo.isForceUpdate) // Force update cannot be cancelled
            .create()

        // Set button click listener
        updateButton.setOnClickListener {
            onUpdateClick()
            navigateToStore()
            if (!updateInfo.isForceUpdate) {
                alertDialog.dismiss()
            }
        }

        // For non-force updates, add a "Later" option
        if (!updateInfo.isForceUpdate) {
            alertDialog.setButton(AlertDialog.BUTTON_NEGATIVE, "Later") { dialog, _ ->
                dialog.dismiss()
            }
        }

        alertDialog.show()
    }

    private fun navigateToStore() {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(updateInfo.downloadUrl)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // Fallback to Play Store if custom URL fails
            try {
                val playStoreIntent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://play.google.com/store/apps/details?id=${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(playStoreIntent)
            } catch (e2: Exception) {
                // Last resort - generic Play Store
                val genericIntent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://play.google.com/store")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(genericIntent)
            }
        }
    }
}