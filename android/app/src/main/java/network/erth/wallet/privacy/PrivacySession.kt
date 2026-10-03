package network.erth.wallet.privacy

import android.content.Context
import network.erth.wallet.Constants
import network.erth.wallet.privacy.chain.PrivacyQueries
import network.erth.wallet.privacy.chain.RestPrivateChain
import network.erth.wallet.privacy.keys.PrivacyKeys
import network.erth.wallet.privacy.prove.MembershipWitness
import network.erth.wallet.privacy.prove.PrivacyProver
import network.erth.wallet.privacy.prove.ActionWitness
import network.erth.wallet.privacy.prove.StakeWitness
import network.erth.wallet.privacy.sync.HttpPrivacyIndexer
import network.erth.wallet.privacy.sync.PrivacyStore
import network.erth.wallet.privacy.tx.Prover
import network.erth.wallet.privacy.zk.Privacy
import network.erth.wallet.wallet.SecureWalletManager

/**
 * The selected wallet's [PrivacyWallet], built on first use from its mnemonic
 * (the privacy keys are derived, never stored apart from it) and kept while
 * the session lasts. Switching wallets builds another; each wallet's notes
 * live in their own directory.
 */
object PrivacySession {
    @Volatile private var current: Pair<String, PrivacyWallet>? = null

    /** The selected wallet's private side. Blocking (derives keys, opens the store). */
    @Synchronized
    fun wallet(context: Context): PrivacyWallet {
        val app = context.applicationContext
        val address = SecureWalletManager.getWalletAddress(app) ?: throw IllegalStateException("no wallet")
        current?.let { (a, w) -> if (a == address) return w }
        val keys = SecureWalletManager.executeWithMnemonic(app) { PrivacyKeys.fromMnemonic(it) }
        // Named by a hash of the owner key, not the address: nothing on disk
        // pairs the transparent address with the shielded one.
        val id = Privacy.h(Privacy.TAG_OWNER, keys.ownerPk).toHex().take(16)
        val w = PrivacyWallet(
            keys = keys,
            store = PrivacyStore.open(app.filesDir, id),
            indexer = HttpPrivacyIndexer(Constants.EARTH_API_URL),
            chain = RestPrivateChain,
            reads = RestChainReads,
            prover = AndroidProver(app),
            chainId = Constants.EARTH_CHAIN_ID,
            roots = network.erth.wallet.privacy.chain.LcdChainRoots,
        )
        current = address to w
        return w
    }

    private val clearListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** Runs [l] whenever the session's wallet is dropped (lock, session end, wallet switch): what holds the wallet lets go. */
    fun onClear(l: () -> Unit) { clearListeners.add(l) }

    fun removeOnClear(l: () -> Unit) { clearListeners.remove(l) }

    /**
     * Signs a referrer consent with the selected wallet's transparent key
     * (wave 3, L6): what lets the private side bind that wallet's own
     * address. The mnemonic is read for the signature only.
     */
    fun referrerSigner(context: Context): PrivacyWallet.ReferrerSigner = PrivacyWallet.ReferrerSigner { message ->
        SecureWalletManager.executeWithMnemonic(context.applicationContext) { m ->
            network.erth.wallet.crypto.WalletCrypto.signConsent(network.erth.wallet.crypto.EarthWallet.deriveKey(m), message)
        }
    }

    /** Forget the cached wallet (lock, wallet switch); everything that held it is stopped first. */
    fun clear() {
        clearListeners.forEach { runCatching { it() } }
        current = null
    }

    private class AndroidProver(private val context: Context) : Prover {
        override fun proveAction(w: ActionWitness): ByteArray = PrivacyProver.proveAction(context, w)
        override fun proveStake(w: StakeWitness): ByteArray = PrivacyProver.proveStake(context, w)
        override fun proveMembership(w: MembershipWitness): ByteArray = PrivacyProver.proveMembership(context, w)
    }

    private object RestChainReads : PrivacyChainReads {
        override fun personhoodParams() = PrivacyQueries.personhoodParams().let {
            PrivacyChainReads.PersonhoodParams(it.caretakerVoteSeconds, it.identityRootWindowSeconds)
        }

        override fun ballotInputs(proposalId: Long, optionId: Long) = PrivacyQueries.ballotInputs(proposalId, optionId).let {
            PrivacyChainReads.BallotInputs(it.scope, it.excludedDsc, it.excludedCountry, it.maxActivation, it.round, it.ballotId)
        }

        override fun epochNumber(): Long = PrivacyQueries.epoch().number

        override fun snapshot(proposalId: Long) = PrivacyQueries.snapshot(proposalId).let {
            PrivacyChainReads.Snapshot(it.root, it.treeSize, it.height, it.rates)
        }

        override fun positions() = PrivacyQueries.positions().map {
            PrivacyChainReads.Position(it.id, it.validator, it.derth, it.ownerTag, it.splits, it.createdHeight)
        }
    }
}
