package network.erth.wallet.ui.wallet


/** What the wallet screen shows. Held by the caller; this composable is pure. */
data class WalletUiState(
    /** The name this wallet was created under. */
    val name: String,
    val address: String,
    val balanceUerth: Long,
    val anmlBalance: String?,
    val stakedUerth: Long,
    val rewardsUerth: Long,
    val registered: Boolean,
    /** Every denom this wallet holds, ERTH first. */
    val holdings: List<Holding> = emptyList(),
    /**
     * Unix seconds at which ANML can next be claimed; 0 means now, null means
     * this wallet is not registered and has nothing to claim against.
     *
     * An absolute instant rather than a remaining duration, so the countdown
     * stays right without the state being refreshed every second.
     */
    val anmlClaimableAt: Long? = null,
    /** Shielded ERTH: what private fees are paid from. */
    val shieldedErthUerth: Long = 0,
    /** Shielded holdings per denom (uerth, uanml, derth/<valoper>, unbond/...). */
    val shielded: Map<String, Long> = emptyMap(),
    /** This wallet's shielded address (erthz1...), for receiving. */
    val shieldedAddress: String = "",
    /** Why the last privacy sync failed, if it did. */
    val privacySyncError: String? = null,
    /**
     * The most shielded ERTH one unshield can spend, fee included: its three
     * largest notes. Under [shieldedErthUerth] when the notes need merging.
     */
    val unshieldableErthUerth: Long = 0,
) {
    companion object {
        /**
         * The zero state, for screens that cannot show "loading".
         *
         * Home distinguishes a real zero from a not-yet-loaded one and shows a
         * shimmer for the second; a pushed screen is only reachable once the
         * load has happened, so it takes this rather than carrying the
         * distinction it will never use.
         */
        val EMPTY = WalletUiState(
            name = "",
            address = "",
            balanceUerth = 0,
            anmlBalance = null,
            stakedUerth = 0,
            rewardsUerth = 0,
            registered = false,
            anmlClaimableAt = null,
        )
    }
}
