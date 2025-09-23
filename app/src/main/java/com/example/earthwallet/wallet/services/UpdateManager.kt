package com.example.earthwallet.wallet.services

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.earthwallet.wallet.utils.UpdateConfig
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages app updates including version checking and forced updates
 */
class UpdateManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "UpdateManager"
        private const val DEFAULT_UPDATE_CHECK_URL = "https://api.github.com/repos/your-repo/earth-network-mobile/releases/latest"

        @Volatile
        private var INSTANCE: UpdateManager? = null

        fun getInstance(context: Context): UpdateManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: UpdateManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    data class UpdateInfo(
        val isUpdateAvailable: Boolean,
        val isForceUpdate: Boolean,
        val latestVersion: String,
        val currentVersion: String,
        val updateMessage: String,
        val downloadUrl: String
    )

    private val _updateInfo = MutableLiveData<UpdateInfo>()
    val updateInfo: LiveData<UpdateInfo> = _updateInfo

    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * Check for app updates
     * @param updateCheckUrl Optional custom URL for update checking
     */
    fun checkForUpdates(updateCheckUrl: String? = null) {
        if (!UpdateConfig.AUTO_CHECK_ENABLED) {
            return
        }

        coroutineScope.launch {
            try {
                val currentVersion = getCurrentVersion()
                val finalUpdateUrl = updateCheckUrl
                    ?: UpdateConfig.CUSTOM_UPDATE_URL
                    ?: DEFAULT_UPDATE_CHECK_URL
                val updateData = fetchUpdateData(finalUpdateUrl)

                if (updateData != null) {
                    val latestVersion = updateData.getString("current_version")
                    val minimumVersion = updateData.getString("minimum_version")
                    val isUpdateAvailable = isNewerVersion(latestVersion, currentVersion)
                    val isForceUpdate = isVersionBelowMinimum(currentVersion, minimumVersion)
                    val updateMessage = updateData.optString("update_message", "")
                    val downloadUrl = updateData.optString("download_url", UpdateConfig.StoreUrls.PLAY_STORE + context.packageName)

                    val updateInfo = UpdateInfo(
                        isUpdateAvailable = isUpdateAvailable,
                        isForceUpdate = isForceUpdate,
                        latestVersion = latestVersion,
                        currentVersion = currentVersion,
                        updateMessage = updateMessage,
                        downloadUrl = downloadUrl
                    )

                    _updateInfo.postValue(updateInfo)
                } else {
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking for updates", e)
            }
        }
    }

    /**
     * Get current app version
     */
    private fun getCurrentVersion(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "1.0.0"
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "Could not get package info", e)
            "1.0.0"
        }
    }

    /**
     * Fetch update data from backend API
     * Expected JSON format:
     * {
     *   "current_version": "1.2.0",
     *   "minimum_version": "1.0.0",
     *   "update_message": "New features and bug fixes",
     *   "download_url": "https://play.google.com/store/apps/details?id=com.example.earthwallet"
     * }
     */
    private suspend fun fetchUpdateData(updateUrl: String): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val url = URL(updateUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("Accept", "application/json")

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = reader.readText()
                reader.close()
                JSONObject(response)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching update data", e)
            null
        }
    }

    /**
     * Compare version strings to determine if an update is available
     */
    private fun isNewerVersion(latestVersion: String, currentVersion: String): Boolean {
        return try {
            val latest = parseVersion(latestVersion)
            val current = parseVersion(currentVersion)
            compareVersions(latest, current) > 0
        } catch (e: Exception) {
            Log.e(TAG, "Error comparing versions", e)
            false
        }
    }

    /**
     * Parse version string into comparable parts
     */
    private fun parseVersion(version: String): List<Int> {
        return version.split(".")
            .map { it.replace(Regex("[^0-9]"), "") }
            .filter { it.isNotEmpty() }
            .map { it.toIntOrNull() ?: 0 }
    }

    /**
     * Compare two version lists
     */
    private fun compareVersions(v1: List<Int>, v2: List<Int>): Int {
        val maxLength = maxOf(v1.size, v2.size)
        for (i in 0 until maxLength) {
            val part1 = v1.getOrNull(i) ?: 0
            val part2 = v2.getOrNull(i) ?: 0
            if (part1 != part2) {
                return part1.compareTo(part2)
            }
        }
        return 0
    }

    /**
     * Check if current version is below minimum required version
     */
    private fun isVersionBelowMinimum(currentVersion: String, minimumVersion: String): Boolean {
        return try {
            val current = parseVersion(currentVersion)
            val minimum = parseVersion(minimumVersion)
            compareVersions(current, minimum) < 0
        } catch (e: Exception) {
            Log.e(TAG, "Error comparing versions for force update", e)
            false
        }
    }


    /**
     * Navigate user to update
     */
    fun navigateToUpdate(updateInfo: UpdateInfo) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(updateInfo.downloadUrl)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error navigating to update", e)
        }
    }

    /**
     * Clean up resources
     */
    fun cleanup() {
        coroutineScope.cancel()
    }
}