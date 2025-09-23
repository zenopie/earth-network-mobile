package com.example.earthwallet.wallet.utils

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * WalletStorageVersion
 *
 * Handles versioning for wallet storage format to enable future migrations.
 * Wraps wallet data with version metadata and provides migration capabilities.
 */
object WalletStorageVersion {

    private const val TAG = "WalletStorageVersion"

    // Current wallet storage version
    const val CURRENT_VERSION = 1

    // Minimum supported version (for migration limits)
    const val MIN_SUPPORTED_VERSION = 1

    // Storage format keys
    private const val KEY_VERSION = "version"
    private const val KEY_WALLETS = "wallets"
    private const val KEY_METADATA = "metadata"
    private const val KEY_CREATED_AT = "created_at"
    private const val KEY_LAST_MIGRATION = "last_migration"

    /**
     * Data class representing versioned wallet storage
     */
    data class VersionedWalletStorage(
        val version: Int,
        val wallets: JSONArray,
        val metadata: JSONObject = JSONObject()
    ) {
        fun toJSON(): JSONObject {
            return JSONObject().apply {
                put(KEY_VERSION, version)
                put(KEY_WALLETS, wallets)
                put(KEY_METADATA, metadata)
            }
        }
    }

    /**
     * Create a new versioned wallet storage with current format
     */
    @JvmStatic
    fun createVersionedStorage(wallets: JSONArray): VersionedWalletStorage {
        val metadata = JSONObject().apply {
            put(KEY_CREATED_AT, System.currentTimeMillis())
            put(KEY_LAST_MIGRATION, CURRENT_VERSION)
        }


        return VersionedWalletStorage(
            version = CURRENT_VERSION,
            wallets = wallets,
            metadata = metadata
        )
    }

    /**
     * Parse wallet storage from JSON string (versioned format only)
     */
    @JvmStatic
    @Throws(Exception::class)
    fun parseWalletStorage(storageJson: String): VersionedWalletStorage {
        if (storageJson.isBlank()) {
            return createVersionedStorage(JSONArray())
        }

        return try {
            val json = JSONObject(storageJson)

            if (!json.has(KEY_VERSION)) {
                throw Exception("Missing version field - storage format not recognized")
            }

            val version = json.getInt(KEY_VERSION)
            val wallets = json.getJSONArray(KEY_WALLETS)
            val metadata = json.optJSONObject(KEY_METADATA) ?: JSONObject()


            val storage = VersionedWalletStorage(version, wallets, metadata)

            // Check if migration is needed
            when {
                version < CURRENT_VERSION -> {
                    migrateStorage(storage)
                }
                version > CURRENT_VERSION -> {
                    throw Exception("Storage version $version is newer than supported version $CURRENT_VERSION")
                }
                else -> storage
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse wallet storage: ${e.message}")
            throw Exception("Invalid wallet storage format: ${e.message}", e)
        }
    }

    /**
     * Serialize versioned wallet storage to JSON string
     */
    @JvmStatic
    fun serializeWalletStorage(storage: VersionedWalletStorage): String {
        return storage.toJSON().toString()
    }

    /**
     * Get wallets array from versioned storage (for backward compatibility)
     */
    @JvmStatic
    fun getWalletsArray(storage: VersionedWalletStorage): JSONArray {
        return storage.wallets
    }

    /**
     * Update wallets in versioned storage
     */
    @JvmStatic
    fun updateWallets(storage: VersionedWalletStorage, newWallets: JSONArray): VersionedWalletStorage {
        return storage.copy(wallets = newWallets)
    }

    /**
     * Migrate wallet storage from old version to current version
     */
    @JvmStatic
    @Throws(Exception::class)
    private fun migrateStorage(oldStorage: VersionedWalletStorage): VersionedWalletStorage {

        if (oldStorage.version < MIN_SUPPORTED_VERSION) {
            throw Exception("Wallet storage version ${oldStorage.version} is too old (minimum supported: $MIN_SUPPORTED_VERSION)")
        }

        var currentStorage = oldStorage

        // Apply migrations step by step
        for (targetVersion in (oldStorage.version + 1)..CURRENT_VERSION) {
            currentStorage = when (targetVersion) {
                2 -> migrateToV2(currentStorage)
                3 -> migrateToV3(currentStorage)
                // Add more migration functions as needed
                else -> throw Exception("No migration available for version $targetVersion")
            }
        }

        // Update metadata
        currentStorage.metadata.put(KEY_LAST_MIGRATION, System.currentTimeMillis())

        return currentStorage
    }

    /**
     * Future migration example to version 2
     * This is a placeholder for when you need to change the wallet format
     */
    @JvmStatic
    @Throws(Exception::class)
    private fun migrateToV2(storage: VersionedWalletStorage): VersionedWalletStorage {

        // Example: Add a new field to each wallet
        val migratedWallets = JSONArray()
        for (i in 0 until storage.wallets.length()) {
            val wallet = storage.wallets.getJSONObject(i)
            // Add new fields or transform existing ones
            // wallet.put("new_field", "default_value")
            migratedWallets.put(wallet)
        }

        return storage.copy(
            version = 2,
            wallets = migratedWallets
        )
    }

    /**
     * Future migration example to version 3
     */
    @JvmStatic
    @Throws(Exception::class)
    private fun migrateToV3(storage: VersionedWalletStorage): VersionedWalletStorage {

        // Example future migration logic
        return storage.copy(version = 3)
    }

    /**
     * Check if storage needs migration
     */
    @JvmStatic
    fun needsMigration(storageJson: String): Boolean {
        if (storageJson.isBlank()) return false

        return try {
            val json = JSONObject(storageJson)
            if (json.has(KEY_VERSION)) {
                json.getInt(KEY_VERSION) < CURRENT_VERSION
            } else {
                false // No version field means invalid format, not legacy
            }
        } catch (e: Exception) {
            false // Invalid format
        }
    }

    /**
     * Get storage version from JSON string
     */
    @JvmStatic
    fun getStorageVersion(storageJson: String): Int {
        if (storageJson.isBlank()) return 0

        return try {
            val json = JSONObject(storageJson)
            json.optInt(KEY_VERSION, 0) // Return 0 for invalid format
        } catch (e: Exception) {
            0 // Invalid format
        }
    }

    /**
     * Validate wallet storage format
     */
    @JvmStatic
    fun validateStorage(storage: VersionedWalletStorage): Boolean {
        return try {
            // Basic validation
            if (storage.version < MIN_SUPPORTED_VERSION || storage.version > CURRENT_VERSION) {
                return false
            }

            // Validate wallets array
            for (i in 0 until storage.wallets.length()) {
                val wallet = storage.wallets.getJSONObject(i)
                if (!wallet.has("name") || !wallet.has("mnemonic") || !wallet.has("address")) {
                    return false
                }
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Storage validation failed", e)
            false
        }
    }
}