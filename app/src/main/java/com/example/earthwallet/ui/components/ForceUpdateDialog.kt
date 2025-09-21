package com.example.earthwallet.ui.components

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.example.earthwallet.R
import com.example.earthwallet.wallet.services.UpdateManager

/**
 * Dialog for displaying update notifications, including force updates
 */
class ForceUpdateDialog {

    companion object {
        /**
         * Create and show an update dialog
         */
        fun showUpdateDialog(
            context: Context,
            updateInfo: UpdateManager.UpdateInfo,
            onUpdateClicked: (() -> Unit)? = null,
            onLaterClicked: (() -> Unit)? = null
        ): Dialog? {
            return try {
                val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_force_update, null)

                val titleText = dialogView.findViewById<TextView>(R.id.update_title)
                val messageText = dialogView.findViewById<TextView>(R.id.update_message)
                val versionText = dialogView.findViewById<TextView>(R.id.version_info)
                val updateButton = dialogView.findViewById<Button>(R.id.btn_update)
                val laterButton = dialogView.findViewById<Button>(R.id.btn_later)

                // Set dialog content
                if (updateInfo.isForceUpdate) {
                    titleText.text = "Critical Update Required"
                    messageText.text = if (updateInfo.updateMessage.isNotEmpty()) {
                        updateInfo.updateMessage
                    } else {
                        "A critical update is required to continue using the app. Please update now to access the latest features and security improvements."
                    }
                } else {
                    titleText.text = "Update Available"
                    messageText.text = if (updateInfo.updateMessage.isNotEmpty()) {
                        updateInfo.updateMessage
                    } else {
                        "A new version is available with improvements and bug fixes. Update now to get the latest features."
                    }
                }

                versionText.text = "Current: ${updateInfo.currentVersion} → Latest: ${updateInfo.latestVersion}"

                // Configure buttons based on update type
                if (updateInfo.isForceUpdate) {
                    laterButton.visibility = View.GONE
                    updateButton.text = "Update Now"
                } else {
                    laterButton.visibility = View.VISIBLE
                    updateButton.text = "Update"
                    laterButton.text = "Later"
                }

                val dialog = AlertDialog.Builder(context)
                    .setView(dialogView)
                    .setCancelable(!updateInfo.isForceUpdate) // Force updates cannot be cancelled
                    .create()

                // Set button click listeners
                updateButton.setOnClickListener {
                    onUpdateClicked?.invoke() ?: run {
                        // Default behavior: navigate to update
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            data = Uri.parse(updateInfo.downloadUrl)
                        }
                        context.startActivity(intent)
                    }
                    if (!updateInfo.isForceUpdate) {
                        dialog.dismiss()
                    }
                }

                laterButton.setOnClickListener {
                    onLaterClicked?.invoke()
                    dialog.dismiss()
                }

                // Prevent dismissal for force updates
                dialog.setOnCancelListener {
                    if (!updateInfo.isForceUpdate) {
                        onLaterClicked?.invoke()
                    }
                }

                dialog.show()
                dialog
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Show a simple force update dialog that blocks the app
         */
        fun showBlockingForceUpdateDialog(
            context: Context,
            updateInfo: UpdateManager.UpdateInfo
        ): Dialog? {
            return showUpdateDialog(
                context = context,
                updateInfo = updateInfo,
                onUpdateClicked = {
                    // Navigate to update and don't dismiss dialog
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse(updateInfo.downloadUrl)
                    }
                    context.startActivity(intent)
                },
                onLaterClicked = null // No later option for force updates
            )
        }
    }
}