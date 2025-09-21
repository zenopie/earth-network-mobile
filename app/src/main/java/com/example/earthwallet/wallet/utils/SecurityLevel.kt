package com.example.earthwallet.wallet.utils

/**
 * SecurityLevel
 *
 * Represents the security level of wallet data encryption
 */
enum class SecurityLevel(val displayName: String, val description: String) {
    HARDWARE_BACKED(
        "Hardware Secured",
        "Encrypted with hardware-backed keys (TEE/StrongBox)"
    ),
    SOFTWARE_ENCRYPTED(
        "Software Encrypted",
        "Encrypted with device-derived keys"
    ),
    INSECURE(
        "Insecure Storage",
        "Plain text storage - not recommended"
    );

    fun isSecure(): Boolean = this != INSECURE
    fun isHardwareBacked(): Boolean = this == HARDWARE_BACKED
}