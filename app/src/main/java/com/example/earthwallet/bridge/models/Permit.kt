package network.erth.wallet.bridge.models

/**
 * SNIP-24 Permit data model
 *
 * Represents a query permit that allows authentication without requiring
 * a prior transaction to set viewing keys.
 */
data class Permit(
    var permitName: String? = null,
    var allowedTokens: List<String>? = null,
    var permissions: List<String>? = null,
    var signature: String? = null,
    var publicKey: String? = null,
    var timestamp: Long = System.currentTimeMillis()
) {

    /**
     * Constructor with required fields
     */
    constructor(
        permitName: String,
        allowedTokens: List<String>,
        permissions: List<String>
    ) : this(
        permitName = permitName,
        allowedTokens = allowedTokens,
        permissions = permissions,
        timestamp = System.currentTimeMillis()
    )

    /**
     * Check if permit contains permission for a specific action
     */
    fun hasPermission(permission: String): Boolean {
        return permissions?.contains(permission) ?: false
    }

    /**
     * Check if permit allows queries for a specific token contract
     */
    fun allowsToken(contractAddress: String): Boolean {
        return allowedTokens?.contains(contractAddress) ?: false
    }

    /**
     * Check if permit is still valid (basic validation)
     */
    fun isValid(): Boolean {
        return !permitName.isNullOrEmpty() &&
               !allowedTokens.isNullOrEmpty() &&
               !permissions.isNullOrEmpty() &&
               !signature.isNullOrEmpty() &&
               !publicKey.isNullOrEmpty()
    }
}