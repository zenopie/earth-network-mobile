package com.example.earthwallet.wallet.utils

import com.example.earthwallet.Constants

/**
 * Configuration for app update management
 */
object UpdateConfig {

    /**
     * Enable/disable automatic update checks
     */
    const val AUTO_CHECK_ENABLED = true

    /**
     * Update check interval in milliseconds (24 hours)
     */
    const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

    /**
     * Custom update check URL for your backend API
     * Example: "https://your-backend.com/api/app/version"
     *
     * Expected JSON response format:
     * {
     *   "current_version": "1.2.0",
     *   "minimum_version": "1.0.0",
     *   "update_message": "New features and bug fixes",
     *   "download_url": "https://play.google.com/store/apps/details?id=com.example.earthwallet"
     * }
     */
    val CUSTOM_UPDATE_URL: String? = "${Constants.BACKEND_BASE_URL}/app/version"

    /**
     * App store URLs for different platforms
     */
    object StoreUrls {
        const val PLAY_STORE = "https://play.google.com/store/apps/details?id="
        val CUSTOM_STORE: String? = null
    }
}