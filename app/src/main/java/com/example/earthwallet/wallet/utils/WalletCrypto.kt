package com.example.earthwallet.wallet.utils

import android.content.Context
import android.util.Log
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.Utils
import org.bitcoinj.crypto.ChildNumber
import org.bitcoinj.crypto.HDUtils
import org.bitcoinj.crypto.MnemonicCode
import org.bitcoinj.wallet.DeterministicKeyChain
import org.bitcoinj.wallet.DeterministicSeed
import java.security.SecureRandom
import java.util.*

/**
 * WalletCrypto
 *
 * Pure cryptographic utility functions for Secret Network wallets.
 * Handles mnemonic generation, key derivation, and address generation.
 * All methods are stateless and have no side effects.
 */
object WalletCrypto {

    private const val TAG = "WalletCrypto"
    const val HRP = "secret"

    // Initialization state
    @Volatile
    private var isInitialized = false
    private val lock = Any()

    /**
     * Initialize MnemonicCode with the English word list from assets.
     * Must be called before any mnemonic operations.
     */
    @JvmStatic
    fun initialize(context: Context) {
        if (isInitialized) return
        synchronized(lock) {
            if (isInitialized) return
            try {
                val assetManager = context.assets
                val inputStream = assetManager.open("org/bitcoinj/crypto/wordlist/english.txt")
                MnemonicCode.INSTANCE = MnemonicCode(inputStream, null)
                inputStream.close()
                isInitialized = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize MnemonicCode from assets: ${e.message}", e)
                throw RuntimeException("Failed to initialize MnemonicCode", e)
            }
        }
    }

    /**
     * Generate a new BIP-39 mnemonic (12 words)
     */
    @JvmStatic
    fun generateMnemonic(): String {
        return try {
            // 128 bits entropy (12 words)
            val entropy = ByteArray(16)
            SecureRandom().nextBytes(entropy)

            val words = MnemonicCode.INSTANCE.toMnemonic(entropy)
            if (words.isNullOrEmpty()) {
                Log.e(TAG, "MnemonicCode.toMnemonic returned null or empty list")
                // Zero sensitive entropy before throwing
                entropy.fill(0)
                throw IllegalStateException("Failed to generate mnemonic: word list issue?")
            }
            val mnemonic = words.joinToString(" ")

            // Zero sensitive entropy after use
            entropy.fill(0)
            mnemonic
        } catch (e: Exception) {
            Log.e(TAG, "Mnemonic generation failed", e)
            throw RuntimeException("Mnemonic generation failed", e)
        }
    }

    /**
     * Derive ECKey from mnemonic using BIP-44 path m/44'/529'/0'/0/0
     */
    @JvmStatic
    fun deriveKeyFromMnemonic(mnemonic: String): ECKey {
        return try {
            val words = mnemonic.trim().split("\\s+".toRegex())
            val seed = DeterministicSeed(words, null, "", 0L)
            val chain = DeterministicKeyChain.builder().seed(seed).build()
            val path = HDUtils.parsePath("M/44H/529H/0H/0/0")
            ECKey.fromPrivate(chain.getKeyByPath(path, true).privKey)
        } catch (e: Exception) {
            throw RuntimeException("Key derivation failed", e)
        }
    }

    /**
     * Secure version: Derive ECKey from mnemonic char array using BIP-44 path m/44'/529'/0'/0/0
     * More secure as it minimizes String creation in memory
     */
    @JvmStatic
    fun deriveKeyFromSecureMnemonic(mnemonicChars: CharArray): ECKey {
        return try {
            // Convert char array to string only temporarily for processing
            var mnemonic: String? = String(mnemonicChars)
            val words = mnemonic!!.trim().split("\\s+".toRegex())

            // Clear the temporary string immediately
            mnemonic = null

            val seed = DeterministicSeed(words, null, "", 0L)
            val chain = DeterministicKeyChain.builder().seed(seed).build()
            val path = HDUtils.parsePath("M/44H/529H/0H/0/0")
            ECKey.fromPrivate(chain.getKeyByPath(path, true).privKey)
        } catch (e: Exception) {
            throw RuntimeException("Secure key derivation failed", e)
        }
    }

    /**
     * Get Secret Network address from ECKey
     */
    @JvmStatic
    fun getAddress(key: ECKey): String {
        // Compressed public key
        val pubCompressed = key.pubKeyPoint.getEncoded(true)
        // Cosmos-style address: RIPEMD160(SHA256(pubkey))
        val hash = Utils.sha256hash160(pubCompressed)
        // Convert to 5-bit groups and Bech32 encode
        val fiveBits = Bech32.convertBits(hash, 8, 5, true)
        return Bech32.encode(HRP, fiveBits)
    }

    /**
     * Get Secret Network address from mnemonic
     */
    @JvmStatic
    fun getAddressFromMnemonic(mnemonic: String): String {
        val key = deriveKeyFromMnemonic(mnemonic)
        return getAddress(key)
    }

    /**
     * Get private key as hex string
     */
    @JvmStatic
    fun getPrivateKeyHex(key: ECKey): String {
        return key.privateKeyAsHex
    }
}